package events

import (
	"encoding/json"
	"fmt"
	"regexp"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	waLog "go.mau.fi/whatsmeow/util/log"
)

const defaultMaxQueueSize = 32768

// Callback receives JSON-encoded event envelopes immediately when registered.
type Callback func(jsonPayload string)

// Router buffers and dispatches account-isolated events from all 4 WhatsApp engines
// using monotonic sequence numbers and explicit durable acknowledgment (Peek + Ack)
// so events are never lost if JNI decoding or Android persistence is interrupted.
type Router struct {
	mu       sync.Mutex
	seq      atomic.Uint64
	maxQueue int
	queue    []Envelope
	callback Callback
	debugLog atomic.Bool
}

// NewRouter creates an isolated event router with bounded memory usage.
func NewRouter(maxQueue int) *Router {
	if maxQueue <= 0 {
		maxQueue = defaultMaxQueueSize
	}
	return &Router{
		maxQueue: maxQueue,
		queue:    make([]Envelope, 0, 512),
	}
}

// SetCallback registers an optional push listener. Passing nil disables push callbacks.
func (r *Router) SetCallback(cb Callback) {
	r.mu.Lock()
	r.callback = cb
	r.mu.Unlock()
}

// SetDebugLogging toggles verbose diagnostic log emission.
func (r *Router) SetDebugLogging(enabled bool) {
	r.debugLog.Store(enabled)
}

// DebugLoggingEnabled reports whether verbose diagnostic logs are enabled.
func (r *Router) DebugLoggingEnabled() bool {
	return r.debugLog.Load()
}

// Emit assigns a monotonic sequence number, stamps composite keys, and enqueues the event.
// Critical messaging/account events are protected from eviction by purging ephemeral
// typing/presence/diagnostic events first if the buffer reaches capacity.
func (r *Router) Emit(env Envelope) bool {
	if env.Type != EventDiagnosticLog {
		if !ValidAccountID(env.AccountID) {
			return false
		}
	} else if env.AccountID != 0 && !ValidAccountID(env.AccountID) {
		return false
	}

	if env.TimestampMs == 0 {
		env.TimestampMs = time.Now().UnixMilli()
	}
	if ValidAccountID(env.AccountID) && env.ChatJID != "" {
		env.CompositeChatKey = CompositeChatKey(env.AccountID, env.ChatJID)
		if env.MessageID != "" {
			env.CompositeMsgKey = CompositeMessageKey(env.AccountID, env.ChatJID, env.MessageID)
		}
	}
	seq := r.seq.Add(1)
	env.Seq = seq

	r.mu.Lock()
	if len(r.queue) >= r.maxQueue {
		if !IsCriticalEvent(env.Type) {
			r.mu.Unlock()
			return true
		}
		evicted := false
		for i := 0; i < len(r.queue); i++ {
			if !IsCriticalEvent(r.queue[i].Type) {
				copy(r.queue[i:], r.queue[i+1:])
				r.queue[len(r.queue)-1] = Envelope{}
				r.queue = r.queue[:len(r.queue)-1]
				evicted = true
				break
			}
		}
		if !evicted && len(r.queue) >= r.maxQueue*2 {
			copy(r.queue[0:], r.queue[1:])
			r.queue[len(r.queue)-1] = Envelope{}
			r.queue = r.queue[:len(r.queue)-1]
		}
	}
	r.queue = append(r.queue, env)
	cb := r.callback
	r.mu.Unlock()

	if cb != nil {
		if data, err := json.Marshal(env); err == nil {
			cb(string(data))
		}
	}
	return true
}

// Peek returns up to maxBatch queued events in FIFO order WITHOUT removing them.
// Callers must invoke Ack(upToSeq) after durably persisting the batch.
func (r *Router) Peek(maxBatch int) []Envelope {
	if maxBatch <= 0 {
		maxBatch = 128
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if len(r.queue) == 0 {
		return nil
	}
	n := maxBatch
	if n > len(r.queue) {
		n = len(r.queue)
	}
	out := make([]Envelope, n)
	copy(out, r.queue[:n])
	return out
}

// PeekJSON returns a JSON array string of up to maxBatch unacknowledged events.
func (r *Router) PeekJSON(maxBatch int) string {
	batch := r.Peek(maxBatch)
	if len(batch) == 0 {
		return "[]"
	}
	data, err := json.Marshal(batch)
	if err != nil {
		return "[]"
	}
	return string(data)
}

// Ack removes all queued events with Seq <= upToSeq and returns the number of acknowledged items.
func (r *Router) Ack(upToSeq uint64) int {
	if upToSeq == 0 {
		return 0
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if len(r.queue) == 0 {
		return 0
	}
	cut := 0
	for cut < len(r.queue) && r.queue[cut].Seq <= upToSeq {
		cut++
	}
	if cut == 0 {
		return 0
	}
	rem := len(r.queue) - cut
	if rem == 0 {
		for i := range r.queue {
			r.queue[i] = Envelope{}
		}
		r.queue = r.queue[:0]
	} else {
		copy(r.queue[0:], r.queue[cut:])
		for i := rem; i < len(r.queue); i++ {
			r.queue[i] = Envelope{}
		}
		r.queue = r.queue[:rem]
	}
	return cut
}

// Poll dequeues up to maxBatch events immediately (retained for legacy callers/tests).
func (r *Router) Poll(maxBatch int) []Envelope {
	batch := r.Peek(maxBatch)
	if len(batch) > 0 {
		r.Ack(batch[len(batch)-1].Seq)
	}
	return batch
}

// PollJSON returns a JSON array string of up to maxBatch queued events and removes them.
func (r *Router) PollJSON(maxBatch int) string {
	batch := r.Poll(maxBatch)
	if len(batch) == 0 {
		return "[]"
	}
	data, err := json.Marshal(batch)
	if err != nil {
		return "[]"
	}
	return string(data)
}

// PendingCount returns the current number of unacknowledged events in the queue.
func (r *Router) PendingCount() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return len(r.queue)
}

var (
	hexOrBase64SecretRe = regexp.MustCompile(`[A-Za-z0-9+/=_-]{28,}`)
	jidUserDigitsRe     = regexp.MustCompile(`(\d{3})\d{4,}(\d{2})@(s\.whatsapp\.net|lid)`)
	sensitiveKeywords   = []string{
		"noise_key", "identity_key", "signed_pre_key", "adv_key",
		"media_key", "secret", "pairing_code", "qrcode", "qr=",
		"private", "cipher", "token",
	}
)

// SanitizeLogLine scrubs cryptographic material, long base64/hex tokens, QR data,
// and masks phone digits inside JIDs before diagnostic logging.
func SanitizeLogLine(raw string) string {
	lower := strings.ToLower(raw)
	for _, kw := range sensitiveKeywords {
		if strings.Contains(lower, kw) {
			return "[REDACTED SENSITIVE DIAGNOSTIC]"
		}
	}
	masked := jidUserDigitsRe.ReplaceAllString(raw, "${1}****${2}@${3}")
	masked = hexOrBase64SecretRe.ReplaceAllString(masked, "[REDACTED_TOKEN]")
	if len(masked) > 360 {
		masked = masked[:360] + "..."
	}
	return masked
}

// AccountLogger implements waLog.Logger scoped to a single account slot and
// sanitizes all messages before forwarding them to the diagnostic event stream.
type AccountLogger struct {
	router    *Router
	accountID int
	module    string
}

// NewAccountLogger creates a sanitized logger for an account (1..4) or engine (0).
func NewAccountLogger(router *Router, accountID int, module string) waLog.Logger {
	return &AccountLogger{
		router:    router,
		accountID: accountID,
		module:    module,
	}
}

func (l *AccountLogger) emitLog(level, msg string, args ...any) {
	if l.router == nil {
		return
	}
	if level == "DEBUG" && !l.router.debugLog.Load() {
		return
	}
	// Drop raw XML/Recv/Send node dumps even in debug mode to prevent message content logging.
	if strings.Contains(l.module, "Recv") || strings.Contains(l.module, "Send") {
		if level == "DEBUG" || level == "INFO" {
			return
		}
	}
	formatted := fmt.Sprintf(msg, args...)
	sanitized := SanitizeLogLine(formatted)
	l.router.Emit(Envelope{
		Type:        EventDiagnosticLog,
		AccountID:   l.accountID,
		LogLevel:    level,
		LogModule:   l.module,
		LogMessage:  sanitized,
		TimestampMs: time.Now().UnixMilli(),
	})
}

func (l *AccountLogger) Errorf(msg string, args ...any) { l.emitLog("ERROR", msg, args...) }
func (l *AccountLogger) Warnf(msg string, args ...any)  { l.emitLog("WARN", msg, args...) }
func (l *AccountLogger) Infof(msg string, args ...any)  { l.emitLog("INFO", msg, args...) }
func (l *AccountLogger) Debugf(msg string, args ...any) { l.emitLog("DEBUG", msg, args...) }

func (l *AccountLogger) Sub(subModule string) waLog.Logger {
	next := subModule
	if l.module != "" {
		next = l.module + "/" + subModule
	}
	return &AccountLogger{
		router:    l.router,
		accountID: l.accountID,
		module:    next,
	}
}
