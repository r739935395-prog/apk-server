package session

import (
	"testing"
	"time"
)

func TestBackoffExponentialProgressionCeilingAndMaxAttempts(t *testing.T) {
	b := NewBackoffPolicy(2*time.Second, 20*time.Second, 5)

	expected := []time.Duration{
		2 * time.Second,
		4 * time.Second,
		8 * time.Second,
		16 * time.Second,
		20 * time.Second,
	}

	for i, exp := range expected {
		got, attempt, ok := b.NextDelay(false)
		if !ok {
			t.Fatalf("attempt %d unexpectedly disallowed", i+1)
		}
		if attempt != i+1 || got != exp {
			t.Fatalf("attempt %d: expected %v, got %v", attempt, exp, got)
		}
	}

	// 6th attempt must be rejected to prevent infinite reconnect loops.
	if _, _, ok := b.NextDelay(false); ok {
		t.Fatal("expected 6th attempt to exceed maxAttempts and return ok=false")
	}

	// Reset restores initial delay.
	b.Reset()
	got, attempt, ok := b.NextDelay(false)
	if !ok || attempt != 1 || got != 2*time.Second {
		t.Fatalf("after reset expected 2s attempt 1, got %v attempt %d ok=%v", got, attempt, ok)
	}
}
