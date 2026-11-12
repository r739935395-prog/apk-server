package synchronization

import (
	"testing"

	"go.mau.fi/whatsmeow/proto/waCommon"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/proto/waHistorySync"
	"go.mau.fi/whatsmeow/proto/waWeb"
	"google.golang.org/protobuf/proto"

	"whatshub/events"
)

func buildSampleHistoryPayload() *waHistorySync.HistorySync {
	syncType := waHistorySync.HistorySync_INITIAL_BOOTSTRAP
	return &waHistorySync.HistorySync{
		SyncType:   &syncType,
		ChunkOrder: proto.Uint32(1),
		Progress:   proto.Uint32(100),
		Pushnames: []*waHistorySync.Pushname{
			{ID: proto.String("967770001111@s.whatsapp.net"), Pushname: proto.String("Ahmad")},
		},
		Conversations: []*waHistorySync.Conversation{
			{
				ID:          proto.String("967770001111@s.whatsapp.net"),
				Name:        proto.String("Ahmad"),
				UnreadCount: proto.Uint32(2),
				Messages: []*waHistorySync.HistorySyncMsg{
					{
						MsgOrderID: proto.Uint64(2),
						Message: &waWeb.WebMessageInfo{
							Key:              &waCommon.MessageKey{ID: proto.String("M_NEW"), FromMe: proto.Bool(false)},
							MessageTimestamp: proto.Uint64(1700000200),
							Message:          &waE2E.Message{Conversation: proto.String("Second message")},
						},
					},
					{
						MsgOrderID: proto.Uint64(1),
						Message: &waWeb.WebMessageInfo{
							Key:              &waCommon.MessageKey{ID: proto.String("M_OLD"), FromMe: proto.Bool(true)},
							MessageTimestamp: proto.Uint64(1700000100),
							Message:          &waE2E.Message{Conversation: proto.String("First message")},
						},
					},
					{
						MsgOrderID: proto.Uint64(3),
						Message: &waWeb.WebMessageInfo{
							Key:              &waCommon.MessageKey{ID: proto.String("M_OLD"), FromMe: proto.Bool(true)},
							MessageTimestamp: proto.Uint64(1700000100),
							Message:          &waE2E.Message{Conversation: proto.String("First message duplicate")},
						},
					},
				},
			},
		},
	}
}

func TestHistorySyncDeduplicationOrderingAndAccountIsolation(t *testing.T) {
	router := events.NewRouter(128)
	proc1 := NewProcessor(1, router)
	proc2 := NewProcessor(2, router)

	payload := buildSampleHistoryPayload()

	chats1, msgs1 := proc1.ProcessSyncData(payload)
	if chats1 != 1 || msgs1 != 2 {
		t.Fatalf("account 1 expected 1 chat & 2 deduplicated msgs, got %d chats & %d msgs", chats1, msgs1)
	}

	dupChats, dupMsgs := proc1.ProcessSyncData(payload)
	if dupChats != 0 || dupMsgs != 0 {
		t.Fatalf("expected duplicate chunk to import 0 items, got %d/%d", dupChats, dupMsgs)
	}

	chats2, msgs2 := proc2.ProcessSyncData(payload)
	if chats2 != 1 || msgs2 != 2 {
		t.Fatalf("account 2 expected independent import of 1 chat & 2 msgs, got %d/%d", chats2, msgs2)
	}

	all := router.Poll(64)
	var acct1Msgs, acct2Msgs []events.Envelope
	for _, ev := range all {
		if ev.Type == events.EventMessage {
			if ev.AccountID == 1 {
				acct1Msgs = append(acct1Msgs, ev)
			} else if ev.AccountID == 2 {
				acct2Msgs = append(acct2Msgs, ev)
			}
		}
	}

	if len(acct1Msgs) != 2 || len(acct2Msgs) != 2 {
		t.Fatalf("expected 2 msgs per account, got acct1=%d acct2=%d", len(acct1Msgs), len(acct2Msgs))
	}
	if acct1Msgs[0].MessageID != "M_OLD" || acct1Msgs[1].MessageID != "M_NEW" {
		t.Fatalf("expected chronological ordering M_OLD -> M_NEW, got %s -> %s", acct1Msgs[0].MessageID, acct1Msgs[1].MessageID)
	}
	if acct1Msgs[0].CompositeMsgKey == acct2Msgs[0].CompositeMsgKey {
		t.Fatalf("composite message keys collided across accounts: %s", acct1Msgs[0].CompositeMsgKey)
	}
}

func TestHistorySyncLIDToPNCanonicalResolution(t *testing.T) {
	router := events.NewRouter(64)
	proc := NewProcessor(1, router)
	var sinkLid, sinkPn string
	proc.SetLIDMappingSink(func(lidJID, pnJID string) {
		sinkLid = lidJID
		sinkPn = pnJID
	})

	syncType := waHistorySync.HistorySync_RECENT
	payload := &waHistorySync.HistorySync{
		SyncType:   &syncType,
		ChunkOrder: proto.Uint32(1),
		Progress:   proto.Uint32(100),
		Conversations: []*waHistorySync.Conversation{
			{
				ID:     proto.String("1849204819204@lid"),
				LidJID: proto.String("1849204819204@lid"),
				PnJID:  proto.String("967771234567@s.whatsapp.net"),
				Name:   proto.String("Khalid"),
				Messages: []*waHistorySync.HistorySyncMsg{
					{
						MsgOrderID: proto.Uint64(1),
						Message: &waWeb.WebMessageInfo{
							Key:              &waCommon.MessageKey{ID: proto.String("LID_MSG_1"), FromMe: proto.Bool(false)},
							MessageTimestamp: proto.Uint64(1700000500),
							Message:          &waE2E.Message{Conversation: proto.String("Hello via LID")},
						},
					},
				},
			},
		},
	}

	chats, msgs := proc.ProcessSyncData(payload)
	if chats != 1 || msgs != 1 {
		t.Fatalf("expected 1 chat and 1 msg, got %d/%d", chats, msgs)
	}
	if sinkLid != "1849204819204@lid" || sinkPn != "967771234567@s.whatsapp.net" {
		t.Fatalf("LID sink not invoked properly: lid=%s pn=%s", sinkLid, sinkPn)
	}

	polled := router.Poll(16)
	var foundMapping, foundMsg bool
	for _, ev := range polled {
		if ev.Type == events.EventIdentityMapping {
			foundMapping = true
			if ev.LidJID != "1849204819204@lid" || ev.PnJID != "967771234567@s.whatsapp.net" {
				t.Fatalf("unexpected mapping event: %+v", ev)
			}
		}
		if ev.Type == events.EventMessage {
			foundMsg = true
			if ev.ChatJID != "967771234567@s.whatsapp.net" || ev.RawChatJID != "1849204819204@lid" {
				t.Fatalf("expected canonical PN chatJID with preserved RawChatJID, got %+v", ev)
			}
		}
	}
	if !foundMapping || !foundMsg {
		t.Fatalf("missing mapping (%v) or message (%v)", foundMapping, foundMsg)
	}
}
