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
					// Duplicate message ID inside the same conversation
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

	// Re-delivering identical chunk 1 to account 1 must be ignored
	dupChats, dupMsgs := proc1.ProcessSyncData(payload)
	if dupChats != 0 || dupMsgs != 0 {
		t.Fatalf("expected duplicate chunk to import 0 items, got %d/%d", dupChats, dupMsgs)
	}

	// Delivering identical chat/message IDs to Account 2 MUST succeed independently
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
