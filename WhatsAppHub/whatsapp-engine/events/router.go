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

const defaultMaxQueueSize = 8192

// Router buffers events from all four isolated sessions and serializes them
// cleanly in FIFO batches for Kotlin consumption.
type Router struct {
	mu       sync.Mutex
	queue    []Envelope
	maxSize  int
	seq      atomic.Uint64
	debugLog atomic.Bool
}

// NewRouter creates an event router with a bounded buffer.
func NewRouter(maxSize int) *Router {
	if maxSize <= 0 {
		maxSize = defaultMaxQueueSize
	}
	return &Router{
		queue:   make([]Envelope, 0, 256),
		maxSize: maxSize,
	}
}

// SetDebugLogging toggles verbose diagnostic log events (still sanitized).
func (r *Router) SetDebugLogging(enabled bool) {
	r.debugLog.Store(enabled)
}

// Emit appends an event stamped with sequence number and current timestamp.
// Account-scoped events with an out-of-range accountID are rejected to prevent
// cross-account or unattributed event leakage.
func (r *Router) Emit(env Envelope) bool {
	if env.Type != EventDiagnosticLog && !ValidAccountID(env.AccountID) {
		return false
	}
	if env.TimestampMs == 0 {
		env.TimestampMs = time.Now().UnixMilli()
	}
	if env.ChatJID != "" && env.CompositeChatKey == "" && ValidAccountID(env.AccountID) {
		env.CompositeChatKey = CompositeChatKey(env.AccountID, env.ChatJID)
	}
	if env.ChatJID != "" && env.MessageID != "" && env.CompositeMsgKey == "" && ValidAccountID(env.AccountID) {
		env.CompositeMsgKey = CompositeMessageKey(env.AccountID, env.ChatJID, env.MessageID)
	}
	env.Seq = r.seq.Add(1)

	r.mu.Lock()
	defer r.mu.Unlock()

	if len(r.queue) >= r.maxSize {
		// Drop oldest low-priority diagnostic/presence event first before dropping oldest item.
		dropped := false
		for i, item := range r.queue {
			if item.Type == EventDiagnosticLog || item.Type == EventPresence || item.Type == EventTyping {
				r.queue = append(r.queue[:i], r.queue[i+1:]...)
				dropped = true
				break
			}
		}
		if !dropped {
			r.queue = r.queue[1:]
		}
	}
	r.queue = append(r.queue, env)
	return true
}

// Poll drains up to maxBatch events from the queue.
func (r *Router) Poll(maxBatch int) []Envelope {
	if maxBatch <= 0 {
		maxBatch = 128
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if len(r.queue) == 0 {
		return nil
	}
	if maxBatch >= len(r.queue) {
		out := r.queue
		r.queue = make([]Envelope, 0, 128)
		return out
	}
	out := make([]Envelope, maxBatch)
	copy(out, r.queue[:maxBatch])
	remaining := make([]Envelope, len(r.queue)-maxBatch)
	copy(remaining, r.queue[maxBatch:])
	r.queue = remaining
	return out
}

// PollJSON drains up to maxBatch events as a JSON array string.
func (r *Router) PollJSON(maxBatch int) string {
	batch := r.Poll(maxBatch)
	if len(batch) == 0 {
		return "[]"
	}
	b, err := json.Marshal(batch)
	if err != nil {
		return "[]"
	}
	return string(b)
}

// PendingCount returns the number of queued events awaiting delivery.
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
