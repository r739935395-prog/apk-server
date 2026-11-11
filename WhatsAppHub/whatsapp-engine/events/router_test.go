package events

import (
	"encoding/json"
	"strings"
	"sync"
	"testing"
)

func TestRouterRejectsInvalidAccountIDs(t *testing.T) {
	r := NewRouter(64)
	if r.Emit(Envelope{Type: EventMessage, AccountID: 0, ChatJID: "967770000001@s.whatsapp.net", MessageID: "m1"}) {
		t.Fatal("expected account_id=0 message to be rejected")
	}
	if r.Emit(Envelope{Type: EventMessage, AccountID: 5, ChatJID: "967770000001@s.whatsapp.net", MessageID: "m1"}) {
		t.Fatal("expected account_id=5 message to be rejected")
	}
	if r.PendingCount() != 0 {
		t.Fatalf("expected empty queue, got %d", r.PendingCount())
	}
}

func TestFourAccountIsolationOnIdenticalChatAndMessageIDs(t *testing.T) {
	r := NewRouter(256)
	sharedChat := "967771234567@s.whatsapp.net"
	sharedMsgID := "BAE5F91234ABCDEF"

	var wg sync.WaitGroup
	for acct := 1; acct <= 4; acct++ {
		acct := acct
		wg.Add(1)
		go func() {
			defer wg.Done()
			ok := r.Emit(Envelope{
				Type:      EventMessage,
				AccountID: acct,
				ChatJID:   sharedChat,
				MessageID: sharedMsgID,
				Text:      "hello",
			})
			if !ok {
				t.Errorf("emit failed for account %d", acct)
			}
		}()
	}
	wg.Wait()

	rawJSON := r.PollJSON(10)
	var batch []Envelope
	if err := json.Unmarshal([]byte(rawJSON), &batch); err != nil {
		t.Fatalf("unmarshal failed: %v", err)
	}
	if len(batch) != 4 {
		t.Fatalf("expected 4 isolated events, got %d", len(batch))
	}

	seenChatKeys := make(map[string]bool)
	seenMsgKeys := make(map[string]bool)
	seenAccounts := make(map[int]bool)

	for _, ev := range batch {
		seenAccounts[ev.AccountID] = true
		if seenChatKeys[ev.CompositeChatKey] {
			t.Fatalf("duplicate composite chat key: %s", ev.CompositeChatKey)
		}
		if seenMsgKeys[ev.CompositeMsgKey] {
			t.Fatalf("duplicate composite message key: %s", ev.CompositeMsgKey)
		}
		seenChatKeys[ev.CompositeChatKey] = true
		seenMsgKeys[ev.CompositeMsgKey] = true
	}
	if len(seenAccounts) != 4 {
		t.Fatalf("expected all 4 accounts represented, got %v", seenAccounts)
	}
}

func TestSanitizeLogLineNeverLeaksSecrets(t *testing.T) {
	secretLine := "loaded noise_key=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
	out := SanitizeLogLine(secretLine)
	if strings.Contains(out, "0123456789abcdef") {
		t.Fatalf("secret leaked in log: %s", out)
	}

	tokenLine := "connected user 967771234567@s.whatsapp.net session ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+/=="
	out2 := SanitizeLogLine(tokenLine)
	if strings.Contains(out2, "967771234567") {
		t.Fatalf("full phone number leaked in log: %s", out2)
	}
	if strings.Contains(out2, "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789") {
		t.Fatalf("long token leaked in log: %s", out2)
	}
}
