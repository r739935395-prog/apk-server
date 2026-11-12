package session

import (
	"bytes"
	"context"
	"fmt"
	"path/filepath"
	"testing"
	"time"

	"go.mau.fi/whatsmeow/proto/waAdv"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
	waEvents "go.mau.fi/whatsmeow/types/events"
	"google.golang.org/protobuf/proto"

	"whatshub/events"
)

func testHexKeyForSlot(acct int) string {
	return fmt.Sprintf("%02x0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e%02x", acct, acct)
}

func seedPairedSession(t *testing.T, mgr *Manager, acct int, phone string) {
	t.Helper()
	if err := mgr.SetSlotKey(acct, testHexKeyForSlot(acct)); err != nil {
		t.Fatalf("SetSlotKey(%d): %v", acct, err)
	}
	slot, err := mgr.Slot(acct)
	if err != nil {
		t.Fatalf("Slot(%d): %v", acct, err)
	}
	slot.mu.Lock()
	cli, _, err := slot.ensureClientLocked(context.Background())
	if err != nil {
		slot.mu.Unlock()
		t.Fatalf("ensureClientLocked(%d): %v", acct, err)
	}
	jid := types.NewJID(phone, types.DefaultUserServer)
	jid.Device = uint16(acct)
	cli.Store.ID = &jid
	cli.Store.PushName = fmt.Sprintf("Account %d User", acct)
	cli.Store.Account = &waAdv.ADVSignedDeviceIdentity{
		Details:             []byte("details"),
		AccountSignature:    bytes.Repeat([]byte{byte(acct)}, 64),
		AccountSignatureKey: bytes.Repeat([]byte{byte(acct + 10)}, 32),
		DeviceSignature:     bytes.Repeat([]byte{byte(acct + 20)}, 64),
	}
	if err := cli.Store.Save(context.Background()); err != nil {
		slot.mu.Unlock()
		t.Fatalf("Save(%d): %v", acct, err)
	}
	slot.mu.Unlock()

	// Simulate protocol Connected event on this isolated slot
	slot.handleWhatsmeowEvent(acct, &waEvents.Connected{})
}

func TestFourAccountIsolationDisconnectRemoveAndSessionRestore(t *testing.T) {
	tmp := t.TempDir()
	sessionsDir := filepath.Join(tmp, "sessions")
	mediaDir := filepath.Join(tmp, "media")
	router := events.NewRouter(512)

	mgr := NewManager(sessionsDir, mediaDir, router)

	// 1-4: Seed and mark Connected all 4 independent accounts
	phones := []string{"967770000001", "967770000002", "967770000003", "967770000004"}
	for acct := 1; acct <= 4; acct++ {
		seedPairedSession(t, mgr, acct, phones[acct-1])
		snap, err := mgr.GetAccountSnapshot(acct)
		if err != nil {
			t.Fatalf("GetAccountSnapshot(%d): %v", acct, err)
		}
		if snap.State != events.StateConnected || snap.Phone != phones[acct-1] || !snap.HasSavedSession {
			t.Fatalf("unexpected snapshot for account %d: %+v", acct, snap)
		}
	}
	_ = router.Poll(512) // Clear initial connection events

	// 5 & 7: Deliver messages with IDENTICAL chat JID and message ID on all 4 accounts
	sharedChat := types.NewJID("967779999999", types.DefaultUserServer)
	for acct := 1; acct <= 4; acct++ {
		slot, _ := mgr.Slot(acct)
		slot.handleWhatsmeowEvent(acct, &waEvents.Message{
			Info: types.MessageInfo{
				MessageSource: types.MessageSource{
					Chat:     sharedChat,
					Sender:   sharedChat,
					IsFromMe: false,
				},
				ID:        "SHARED_MSG_ID_777",
				PushName:  "Customer",
				Timestamp: time.Unix(1700000500+int64(acct), 0),
			},
			Message: &waE2E.Message{
				Conversation: proto.String(fmt.Sprintf("Payload for account %d", acct)),
			},
		})
	}

	rawDelivered := router.Poll(64)
	var delivered []events.Envelope
	for _, ev := range rawDelivered {
		if ev.Type == events.EventMessage {
			delivered = append(delivered, ev)
		}
	}
	if len(delivered) != 4 {
		t.Fatalf("expected 4 isolated message events, got %d (raw=%d)", len(delivered), len(rawDelivered))
	}
	for i, ev := range delivered {
		wantAcct := i + 1
		if ev.AccountID != wantAcct {
			t.Fatalf("expected event %d to belong to account %d, got %d", i, wantAcct, ev.AccountID)
		}
		if ev.Text != fmt.Sprintf("Payload for account %d", wantAcct) {
			t.Fatalf("cross-account payload mismatch on account %d: %q", wantAcct, ev.Text)
		}
	}

	// 8 & 9: Disconnect Account 2 and confirm Accounts 1, 3, and 4 remain Connected & functional
	if err := mgr.Disconnect(2); err != nil {
		t.Fatalf("Disconnect(2): %v", err)
	}
	snap2, _ := mgr.GetAccountSnapshot(2)
	if snap2.State != events.StateDisconnected {
		t.Fatalf("expected Account 2 disconnected, got %s", snap2.State)
	}
	for _, activeAcct := range []int{1, 3, 4} {
		snap, _ := mgr.GetAccountSnapshot(activeAcct)
		if snap.State != events.StateConnected {
			t.Fatalf("disconnecting Account 2 affected Account %d (state=%s)", activeAcct, snap.State)
		}
	}

	// Remove Account 4 completely and verify Accounts 1, 2, 3 still retain saved sessions
	if err := mgr.RemoveAccount(4); err != nil {
		t.Fatalf("RemoveAccount(4): %v", err)
	}
	snap4, _ := mgr.GetAccountSnapshot(4)
	if snap4.State != events.StateNotConnected || snap4.HasSavedSession {
		t.Fatalf("expected Account 4 wiped, got %+v", snap4)
	}

	// 10 & 11: Shut down Manager and recreate to verify saved sessions 1, 2, 3 restore from disk
	mgr.Shutdown()

	router2 := events.NewRouter(256)
	mgr2 := NewManager(sessionsDir, mediaDir, router2)
	defer mgr2.Shutdown()

	for _, persistedAcct := range []int{1, 2, 3} {
		if err := mgr2.SetSlotKey(persistedAcct, testHexKeyForSlot(persistedAcct)); err != nil {
			t.Fatalf("SetSlotKey restore(%d): %v", persistedAcct, err)
		}
		slot, _ := mgr2.Slot(persistedAcct)
		slot.mu.Lock()
		_, alreadyPaired, err := slot.ensureClientLocked(context.Background())
		gotPhone := slot.phone
		slot.mu.Unlock()
		if err != nil || !alreadyPaired || gotPhone != phones[persistedAcct-1] {
			t.Fatalf("restore failed for account %d: err=%v paired=%v phone=%s", persistedAcct, err, alreadyPaired, gotPhone)
		}
	}
}

func TestLIDToPNMappingReceiptCorrelationAndDeviceSentEcho(t *testing.T) {
	tmp := t.TempDir()
	router := events.NewRouter(256)
	mgr := NewManager(filepath.Join(tmp, "sessions"), filepath.Join(tmp, "media"), router)
	defer mgr.Shutdown()

	seedPairedSession(t, mgr, 1, "967770000001")
	_ = router.Poll(256)

	slot, _ := mgr.Slot(1)
	lidJID := types.NewJID("18899900011122", types.HiddenUserServer)
	pnJID := types.NewJID("967771112222", types.DefaultUserServer)

	// 1. Incoming @lid message carrying SenderAlt -> should emit identity_mapping + canonical PN message
	slot.handleWhatsmeowEvent(1, &waEvents.Message{
		Info: types.MessageInfo{
			MessageSource: types.MessageSource{
				Chat:      lidJID,
				Sender:    lidJID,
				SenderAlt: pnJID,
				IsFromMe:  false,
			},
			ID:        "LID_IN_MSG_1",
			PushName:  "Customer One",
			Timestamp: time.Unix(1700001000, 0),
		},
		Message: &waE2E.Message{
			Conversation: proto.String("Hello over LID"),
		},
	})

	evs := router.Poll(32)
	var sawMapping, sawMsg bool
	for _, ev := range evs {
		if ev.Type == events.EventIdentityMapping {
			sawMapping = true
			if ev.LidJID != "18899900011122@lid" || ev.PnJID != "967771112222@s.whatsapp.net" {
				t.Fatalf("unexpected mapping event: %+v", ev)
			}
		}
		if ev.Type == events.EventMessage {
			sawMsg = true
			if ev.ChatJID != "967771112222@s.whatsapp.net" || ev.RawChatJID != "18899900011122@lid" {
				t.Fatalf("expected canonical PN ChatJID, got chat=%s raw=%s", ev.ChatJID, ev.RawChatJID)
			}
		}
	}
	if !sawMapping || !sawMsg {
		t.Fatalf("expected both identity_mapping and message events, got mapping=%v msg=%v", sawMapping, sawMsg)
	}

	// 2. Subsequent @lid receipt WITHOUT SenderAlt -> must still resolve to canonical PN chat!
	slot.handleWhatsmeowEvent(1, &waEvents.Receipt{
		MessageSource: types.MessageSource{
			Chat:   lidJID,
			Sender: lidJID,
		},
		MessageIDs: []types.MessageID{"3EB0ABCDEF123456789012"},
		Type:       types.ReceiptTypeRead,
	})
	recEvs := router.Poll(16)
	if len(recEvs) != 1 || recEvs[0].ChatJID != "967771112222@s.whatsapp.net" || recEvs[0].ReceiptStatus != "read" {
		t.Fatalf("expected receipt resolved to canonical PN chat, got %+v", recEvs)
	}
}
