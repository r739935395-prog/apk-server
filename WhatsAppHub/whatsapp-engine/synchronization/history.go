package synchronization

import (
	"runtime"
	"sort"
	"strings"
	"sync"

	"go.mau.fi/whatsmeow/proto/waHistorySync"
	"go.mau.fi/whatsmeow/types"

	"whatshub/events"
	"whatshub/messaging"
)

// LIDMappingSink allows SessionManager to persist newly discovered verified (LID, PN) pairs
// into whatsmeow's per-account LIDStore when HistorySync provides both conv.GetLidJID() and conv.GetPnJID().
type LIDMappingSink func(lidJID, pnJID string)

// Processor handles HistorySync payloads for ONE isolated WhatsApp account.
// Each account slot owns a separate Processor instance so sync state and
// deduplication caches are never shared across accounts.
type Processor struct {
	accountID int
	router    *events.Router
	lidSink   LIDMappingSink

	mu            sync.Mutex
	seenComposite map[string]struct{}
	seenChunks    map[string]struct{}
	totalChats    int
	totalMessages int
}

// NewProcessor creates an isolated HistorySync processor for accountID (1..4).
func NewProcessor(accountID int, router *events.Router) *Processor {
	return &Processor{
		accountID:     accountID,
		router:        router,
		seenComposite: make(map[string]struct{}, 1024),
		seenChunks:    make(map[string]struct{}, 16),
	}
}

// SetLIDMappingSink attaches an optional per-account sink for verified LID<->PN pairs.
func (p *Processor) SetLIDMappingSink(sink LIDMappingSink) {
	p.mu.Lock()
	p.lidSink = sink
	p.mu.Unlock()
}

func normalizeNonADJID(raw string) string {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return ""
	}
	if parsed, err := types.ParseJID(trimmed); err == nil {
		return parsed.ToNonAD().String()
	}
	return trimmed
}

// ProcessSyncData imports conversations, messages, verified LID/PN identity pairs, and pushnames from a HistorySync payload.
func (p *Processor) ProcessSyncData(data *waHistorySync.HistorySync) (importedChats int, importedMessages int) {
	if data == nil || !events.ValidAccountID(p.accountID) || p.router == nil {
		return 0, 0
	}

	syncType := data.GetSyncType().String()
	chunkIndex := int(data.GetChunkOrder())
	progressPct := int(data.GetProgress())
	chunkKey := syncType + "#" + intToStr(chunkIndex)

	p.mu.Lock()
	if _, duplicateChunk := p.seenChunks[chunkKey]; duplicateChunk && chunkIndex > 0 {
		p.mu.Unlock()
		return 0, 0
	}
	p.seenChunks[chunkKey] = struct{}{}
	lidSink := p.lidSink
	p.mu.Unlock()

	// 1. Import pushnames into account-scoped contacts batch
	if pushnames := data.GetPushnames(); len(pushnames) > 0 {
		batch := make([]events.ContactDTO, 0, len(pushnames))
		for _, pn := range pushnames {
			rawJID := normalizeNonADJID(pn.GetID())
			name := strings.TrimSpace(pn.GetPushname())
			if rawJID == "" || name == "" {
				continue
			}
			dto := events.ContactDTO{
				JID:      rawJID,
				PushName: name,
			}
			if strings.HasSuffix(rawJID, "@lid") {
				dto.LidJID = rawJID
			} else if strings.HasSuffix(rawJID, "@s.whatsapp.net") {
				dto.PnJID = rawJID
			}
			batch = append(batch, dto)
		}
		if len(batch) > 0 {
			p.router.Emit(events.Envelope{
				Type:      events.EventContactsBatch,
				AccountID: p.accountID,
				Contacts:  batch,
			})
		}
	}

	// 2. Process conversations and their messages in chronological order
	for _, conv := range data.GetConversations() {
		rawConvID := strings.TrimSpace(conv.GetID())
		if rawConvID == "" || rawConvID == "status@broadcast" || strings.HasSuffix(rawConvID, "@broadcast") {
			continue
		}
		rawChatJID := normalizeNonADJID(rawConvID)
		isGroup := strings.HasSuffix(rawChatJID, "@g.us")
		if parsed, err := types.ParseJID(rawConvID); err == nil {
			isGroup = parsed.Server == types.GroupServer
		}

		// Extract verified LID <-> PN pair from HistorySync conversation metadata (strictly per account)
		lidJID := normalizeNonADJID(conv.GetLidJID())
		pnJID := normalizeNonADJID(conv.GetPnJID())
		if !isGroup {
			if strings.HasSuffix(rawChatJID, "@lid") && lidJID == "" {
				lidJID = rawChatJID
			}
			if strings.HasSuffix(rawChatJID, "@s.whatsapp.net") && pnJID == "" {
				pnJID = rawChatJID
			}
		}

		canonicalChatJID := rawChatJID
		if !isGroup && lidJID != "" && pnJID != "" &&
			strings.HasSuffix(lidJID, "@lid") && strings.HasSuffix(pnJID, "@s.whatsapp.net") {
			// Canonicalize 1:1 chats to the verified phone JID while preserving raw & LID JIDs
			canonicalChatJID = pnJID
			if lidSink != nil {
				lidSink(lidJID, pnJID)
			}
			p.router.Emit(events.Envelope{
				Type:          events.EventIdentityMapping,
				AccountID:     p.accountID,
				ChatJID:       canonicalChatJID,
				RawChatJID:    rawChatJID,
				LidJID:        lidJID,
				PnJID:         pnJID,
				MappingSource: "history_sync",
			})
		}

		chatName := strings.TrimSpace(conv.GetName())
		unreadCount := int(conv.GetUnreadCount())

		histMsgs := conv.GetMessages()
		if len(histMsgs) == 0 {
			continue
		}

		type orderedItem struct {
			tsMs    int64
			orderID uint64
			env     events.Envelope
		}
		items := make([]orderedItem, 0, len(histMsgs))

		for _, hm := range histMsgs {
			webMsg := hm.GetMessage()
			if webMsg == nil || webMsg.GetKey() == nil {
				continue
			}
			msgID := strings.TrimSpace(webMsg.GetKey().GetID())
			if msgID == "" {
				continue
			}

			compMsgKey := events.CompositeMessageKey(p.accountID, canonicalChatJID, msgID)
			p.mu.Lock()
			if _, exists := p.seenComposite[compMsgKey]; exists {
				p.mu.Unlock()
				continue
			}
			p.seenComposite[compMsgKey] = struct{}{}
			p.mu.Unlock()

			parsed := messaging.ParseE2EMessage(webMsg.GetMessage())
			if parsed.Kind != messaging.KindMessage && parsed.Kind != messaging.KindEdit {
				continue
			}

			tsSec := int64(webMsg.GetMessageTimestamp())
			if tsSec <= 0 {
				tsSec = int64(conv.GetLastMsgTimestamp())
			}
			tsMs := tsSec * 1000

			isFromMe := webMsg.GetKey().GetFromMe()
			senderJID := canonicalChatJID
			if isGroup {
				if part := normalizeNonADJID(webMsg.GetParticipant()); part != "" {
					senderJID = part
				} else if kp := normalizeNonADJID(webMsg.GetKey().GetParticipant()); kp != "" {
					senderJID = kp
				}
			}

			status := "delivered"
			if isFromMe {
				status = "sent"
			}

			items = append(items, orderedItem{
				tsMs:    tsMs,
				orderID: hm.GetMsgOrderID(),
				env: events.Envelope{
					Type:             events.EventMessage,
					AccountID:        p.accountID,
					ChatJID:          canonicalChatJID,
					RawChatJID:       rawChatJID,
					LidJID:           lidJID,
					PnJID:            pnJID,
					ChatName:         chatName,
					MessageID:        msgID,
					CompositeChatKey: events.CompositeChatKey(p.accountID, canonicalChatJID),
					CompositeMsgKey:  compMsgKey,
					SenderJID:        senderJID,
					SenderName:       strings.TrimSpace(webMsg.GetPushName()),
					IsFromMe:         isFromMe,
					IsGroup:          isGroup,
					Text:             parsed.Text,
					MediaType:        parsed.MediaType,
					MimeType:         parsed.MimeType,
					FileName:         parsed.FileName,
					FileSize:         parsed.FileSize,
					DurationSec:      parsed.DurationSec,
					IsVoiceNote:      parsed.IsVoiceNote,
					DirectPath:       parsed.DirectPath,
					MediaKeyB64:      parsed.MediaKeyB64,
					FileSHA256B64:    parsed.FileSHA256B64,
					FileEncSHA256B64: parsed.FileEncSHA256B64,
					QuotedMessageID:  parsed.QuotedMessageID,
					QuotedSenderJID:  parsed.QuotedSenderJID,
					QuotedText:       parsed.QuotedText,
					EditTargetID:     parsed.TargetMessageID,
					IsHistorySync:    true,
					UnreadCount:      unreadCount,
					Status:           status,
					TimestampMs:      tsMs,
				},
			})
		}

		if len(items) == 0 {
			continue
		}

		sort.Slice(items, func(i, j int) bool {
			if items[i].tsMs != items[j].tsMs {
				return items[i].tsMs < items[j].tsMs
			}
			return items[i].orderID < items[j].orderID
		})

		importedChats++
		for idx := range items {
			if idx < len(items)-1 {
				items[idx].env.UnreadCount = 0
			}
			p.router.Emit(items[idx].env)
			importedMessages++
			if importedMessages%200 == 0 {
				runtime.Gosched()
			}
		}
	}

	p.mu.Lock()
	p.totalChats += importedChats
	p.totalMessages += importedMessages
	totChats := p.totalChats
	totMsgs := p.totalMessages
	p.mu.Unlock()

	status := "in_progress"
	if progressPct >= 100 {
		status = "completed"
	}

	p.router.Emit(events.Envelope{
		Type:             events.EventHistorySyncProgress,
		AccountID:        p.accountID,
		SyncType:         syncType,
		ChunkIndex:       chunkIndex,
		ProgressPct:      progressPct,
		ImportedChats:    totChats,
		ImportedMessages: totMsgs,
		Status:           status,
	})

	return importedChats, importedMessages
}

// Reset clears in-memory deduplication state when an account is removed/replaced.
func (p *Processor) Reset() {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.seenComposite = make(map[string]struct{}, 256)
	p.seenChunks = make(map[string]struct{}, 16)
	p.totalChats = 0
	p.totalMessages = 0
}

func intToStr(v int) string {
	if v == 0 {
		return "0"
	}
	neg := v < 0
	if neg {
		v = -v
	}
	var b [16]byte
	pos := len(b)
	for v > 0 {
		pos--
		b[pos] = byte('0' + v%10)
		v /= 10
	}
	if neg {
		pos--
		b[pos] = '-'
	}
	return string(b[pos:])
}
