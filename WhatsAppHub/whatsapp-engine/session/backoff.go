package session

import (
	"math/rand/v2"
	"sync"
	"time"
)

const (
	DefaultInitialDelay = 2 * time.Second
	DefaultMaxDelay     = 60 * time.Second
	DefaultMaxAttempts  = 12
)

// BackoffPolicy computes per-account reconnect intervals with bounded exponential
// backoff, ±20% random jitter, and an explicit max-attempt ceiling to prevent
// infinite reconnect loops when a session is permanently unreachable.
type BackoffPolicy struct {
	mu           sync.Mutex
	initialDelay time.Duration
	maxDelay     time.Duration
	maxAttempts  int
	attempts     int
}

// NewBackoffPolicy constructs an isolated BackoffPolicy for one account slot.
func NewBackoffPolicy(initial, max time.Duration, maxAttempts int) *BackoffPolicy {
	if initial <= 0 {
		initial = DefaultInitialDelay
	}
	if max < initial {
		max = DefaultMaxDelay
	}
	if maxAttempts <= 0 {
		maxAttempts = DefaultMaxAttempts
	}
	return &BackoffPolicy{
		initialDelay: initial,
		maxDelay:     max,
		maxAttempts:  maxAttempts,
	}
}

// NextDelay increments the attempt counter and returns (delay, attemptNumber, allowed).
// When attempts exceed maxAttempts, allowed returns false so reconnection stops cleanly
// instead of spinning in an infinite loop.
func (b *BackoffPolicy) NextDelay(withJitter bool) (time.Duration, int, bool) {
	b.mu.Lock()
	defer b.mu.Unlock()

	if b.attempts >= b.maxAttempts {
		return 0, b.attempts, false
	}
	b.attempts++
	attempt := b.attempts

	delay := b.initialDelay
	for i := 1; i < attempt; i++ {
		if delay >= b.maxDelay/2 {
			delay = b.maxDelay
			break
		}
		delay *= 2
	}
	if delay > b.maxDelay {
		delay = b.maxDelay
	}

	if withJitter && delay > 0 {
		// Apply ±15% jitter
		factor := 0.85 + rand.Float64()*0.30
		delay = time.Duration(float64(delay) * factor)
	}
	return delay, attempt, true
}

// Reset clears the attempt counter after a confirmed connection or network restoration.
func (b *BackoffPolicy) Reset() {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.attempts = 0
}

// Attempts returns the current consecutive reconnect attempt count.
func (b *BackoffPolicy) Attempts() int {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.attempts
}
