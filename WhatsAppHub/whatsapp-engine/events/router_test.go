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

func TestPeekAndAckDurabilityPreservesUnackedEvents(t *testing.T) {
	r := NewRouter(64)
	r.Emit(Envelope{Type: EventMessage, AccountID: 1, ChatJID: "967771111111@s.whatsapp.net", MessageID: "m1", Text: "first"})
	r.Emit(Envelope{Type: EventMessage, AccountID: 2, ChatJID: "967772222222@s.whatsapp.net", MessageID: "m2", Text: "second"})
	r.Emit(Envelope{Type: EventMessage, AccountID: 3, ChatJID: "967773333333@s.whatsapp.net", MessageID: "m3", Text: "third"})

	// Peek twice without Ack: queue MUST still contain all 3 messages (simulating JNI/DB failure before Ack)
	peek1 := r.Peek(2)
	peek2 := r.Peek(2)
	if len(peek1) != 2 || len(peek2) != 2 || peek1[0].Seq != peek2[0].Seq {
		t.Fatalf("expected non-destructive Peek, got %+v vs %+v", peek1, peek2)
	}
	if r.PendingCount() != 3 {
		t.Fatalf("expected 3 unacked events, got %d", r.PendingCount())
	}

	// Ack first 2 events; 3rd must remain intact
	acked := r.Ack(peek1[1].Seq)
	if acked != 2 || r.PendingCount() != 1 {
		t.Fatalf("expected 2 acked and 1 remaining, got acked=%d pending=%d", acked, r.PendingCount())
	}
	rem := r.Peek(10)
	if len(rem) != 1 || rem[0].MessageID != "m3" {
		t.Fatalf("expected m3 remaining, got %+v", rem)
	}
}

func TestCriticalEventsNeverEvictedByEphemeralTypingFlood(t *testing.T) {
	r := NewRouter(4)
	r.Emit(Envelope{Type: EventMessage, AccountID: 1, ChatJID: "967770000001@s.whatsapp.net", MessageID: "keep_1"})
	r.Emit(Envelope{Type: EventTyping, AccountID: 1, ChatJID: "967770000001@s.whatsapp.net", IsTyping: true})
	r.Emit(Envelope{Type: EventTyping, AccountID: 2, ChatJID: "967770000002@s.whatsapp.net", IsTyping: true})
	r.Emit(Envelope{Type: EventTyping, AccountID: 3, ChatJID: "967770000003@s.whatsapp.net", IsTyping: true})
	// Queue is now at capacity (4). Emitting critical messages must evict typing, never keep_1!
	r.Emit(Envelope{Type: EventMessage, AccountID: 2, ChatJID: "967770000002@s.whatsapp.net", MessageID: "keep_2"})
	r.Emit(Envelope{Type: EventMessage, AccountID: 3, ChatJID: "967770000003@s.whatsapp.net", MessageID: "keep_3"})

	batch := r.Poll(10)
	found := map[string]bool{}
	for _, ev := range batch {
		if ev.Type == EventMessage {
			found[ev.MessageID] = true
		}
	}
	if !found["keep_1"] || !found["keep_2"] || !found["keep_3"] {
		t.Fatalf("critical message lost during ephemeral flood: %+v", found)
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
