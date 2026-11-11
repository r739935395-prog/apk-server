package synchronization

import (
	"sort"
	"strings"
	"sync"

	"go.mau.fi/whatsmeow/proto/waHistorySync"
	"go.mau.fi/whatsmeow/types"

	"whatshub/events"
	"whatshub/messaging"
)

// Processor handles HistorySync payloads for ONE isolated WhatsApp account.
// Each account slot owns a separate Processor instance so sync state and
// deduplication caches are never shared across accounts.
type Processor struct {
	accountID int
	router    *events.Router

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

// ProcessSyncData imports conversations, messages, and pushnames from a HistorySync payload.
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
	p.mu.Unlock()

	// 1. Import pushnames into account-scoped contacts batch
	if pushnames := data.GetPushnames(); len(pushnames) > 0 {
		batch := make([]events.ContactDTO, 0, len(pushnames))
		for _, pn := range pushnames {
			rawJID := strings.TrimSpace(pn.GetID())
			name := strings.TrimSpace(pn.GetPushname())
			if rawJID == "" || name == "" {
				continue
			}
			if parsed, err := types.ParseJID(rawJID); err == nil {
				rawJID = parsed.ToNonAD().String()
			}
			batch = append(batch, events.ContactDTO{
				JID:      rawJID,
				PushName: name,
			})
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
		rawChatJID := strings.TrimSpace(conv.GetID())
		if rawChatJID == "" || rawChatJID == "status@broadcast" {
			continue
		}
		chatJID := rawChatJID
		isGroup := strings.HasSuffix(chatJID, "@g.us")
		if parsed, err := types.ParseJID(rawChatJID); err == nil {
			chatJID = parsed.ToNonAD().String()
			isGroup = parsed.Server == types.GroupServer
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

			compMsgKey := events.CompositeMessageKey(p.accountID, chatJID, msgID)
			p.mu.Lock()
			if _, exists := p.seenComposite[compMsgKey]; exists {
				p.mu.Unlock()
				continue
			}
			p.seenComposite[compMsgKey] = struct{}{}
			p.mu.Unlock()

			parsed := messaging.ParseE2EMessage(webMsg.GetMessage())
			if parsed.Kind != messaging.KindMessage {
				continue
			}

			tsSec := int64(webMsg.GetMessageTimestamp())
			if tsSec <= 0 {
				tsSec = int64(conv.GetLastMsgTimestamp())
			}
			tsMs := tsSec * 1000

			isFromMe := webMsg.GetKey().GetFromMe()
			senderJID := chatJID
			if isGroup {
				if part := strings.TrimSpace(webMsg.GetParticipant()); part != "" {
					if pJID, err := types.ParseJID(part); err == nil {
						senderJID = pJID.ToNonAD().String()
					} else {
						senderJID = part
					}
				} else if kp := strings.TrimSpace(webMsg.GetKey().GetParticipant()); kp != "" {
					if pJID, err := types.ParseJID(kp); err == nil {
						senderJID = pJID.ToNonAD().String()
					} else {
						senderJID = kp
					}
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
					ChatJID:          chatJID,
					ChatName:         chatName,
					MessageID:        msgID,
					CompositeChatKey: events.CompositeChatKey(p.accountID, chatJID),
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
			// Only attach conversation unread count to the newest imported message of the conversation
			if idx < len(items)-1 {
				items[idx].env.UnreadCount = 0
			}
			p.router.Emit(items[idx].env)
			importedMessages++
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
