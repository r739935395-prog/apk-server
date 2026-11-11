package events

import "fmt"

// MinAccountID and MaxAccountID define the four isolated WhatsApp account slots (1..4).
const (
	MinAccountID = 1
	MaxAccountID = 4
)

// ValidAccountID reports whether accountID belongs to one of the four isolated slots.
func ValidAccountID(accountID int) bool {
	return accountID >= MinAccountID && accountID <= MaxAccountID
}

// AccountState represents all required user-visible connection states for an account.
type AccountState string

const (
	StateNotConnected    AccountState = "not_connected"
	StateConnecting      AccountState = "connecting"
	StateWaitingForQR    AccountState = "waiting_for_qr"
	StatePairing         AccountState = "pairing"
	StateConnected       AccountState = "connected"
	StateReconnecting    AccountState = "reconnecting"
	StateDisconnected    AccountState = "disconnected"
	StateAuthExpired     AccountState = "auth_expired"
	StateConnectionError AccountState = "connection_error"
)

// EventType enumerates structured Go-to-Kotlin event kinds.
type EventType string

const (
	EventAccountState        EventType = "account_state"
	EventQRCode              EventType = "qr_code"
	EventPairingCode         EventType = "pairing_code"
	EventMessage             EventType = "message"
	EventReaction            EventType = "reaction"
	EventMessageRevoked      EventType = "message_revoked"
	EventReceipt             EventType = "receipt"
	EventTyping              EventType = "typing"
	EventPresence            EventType = "presence"
	EventHistorySyncProgress EventType = "history_sync_progress"
	EventContactsBatch       EventType = "contacts_batch"
	EventGroupsBatch         EventType = "groups_batch"
	EventMediaDownloaded     EventType = "media_downloaded"
	EventDiagnosticLog       EventType = "diagnostic_log"
)

// CompositeChatKey produces the mandatory isolated chat key: "account_id:chat_jid".
func CompositeChatKey(accountID int, chatJID string) string {
	return fmt.Sprintf("%d:%s", accountID, chatJID)
}

// CompositeMessageKey produces the mandatory isolated message key: "account_id:chat_jid:message_id".
func CompositeMessageKey(accountID int, chatJID, messageID string) string {
	return fmt.Sprintf("%d:%s:%s", accountID, chatJID, messageID)
}

// ContactDTO carries sanitized per-account contact metadata.
type ContactDTO struct {
	JID          string `json:"jid"`
	FullName     string `json:"full_name"`
	FirstName    string `json:"first_name"`
	PushName     string `json:"push_name"`
	BusinessName string `json:"business_name"`
}

// GroupMemberDTO carries group participant membership for a specific account's group.
type GroupMemberDTO struct {
	JID          string `json:"jid"`
	PhoneJID     string `json:"phone_jid,omitempty"`
	IsAdmin      bool   `json:"is_admin"`
	IsSuperAdmin bool   `json:"is_super_admin"`
}

// GroupDTO carries group metadata scoped to a specific account.
type GroupDTO struct {
	JID              string           `json:"jid"`
	Name             string           `json:"name"`
	Topic            string           `json:"topic"`
	OwnerJID         string           `json:"owner_jid"`
	ParticipantCount int              `json:"participant_count"`
	IsAnnounce       bool             `json:"is_announce"`
	IsLocked         bool             `json:"is_locked"`
	CreatedAtMs      int64            `json:"created_at_ms"`
	Participants     []GroupMemberDTO `json:"participants,omitempty"`
}

// Envelope is a strongly-typed JSON event emitted from Go to Kotlin.
// Every event carries AccountID (1..4, or 0 only for engine-level diagnostics).
type Envelope struct {
	Seq               uint64       `json:"seq"`
	Type             EventType    `json:"type"`
	AccountID        int          `json:"account_id"`
	TimestampMs      int64        `json:"timestamp_ms"`

	// Account state fields
	State            AccountState `json:"state,omitempty"`
	JID              string       `json:"jid,omitempty"`
	Phone            string       `json:"phone,omitempty"`
	PushName         string       `json:"push_name,omitempty"`
	ErrorMessage     string       `json:"error_message,omitempty"`
	ReconnectAttempt int          `json:"reconnect_attempt,omitempty"`

	// Pairing fields (never written to diagnostic logs)
	QRCode           string       `json:"qr_code,omitempty"`
	ExpiresInSec     int          `json:"expires_in_sec,omitempty"`
	PairingCode      string       `json:"pairing_code,omitempty"`

	// Message / media / reply / edit fields
	ChatJID          string       `json:"chat_jid,omitempty"`
	ChatName         string       `json:"chat_name,omitempty"`
	MessageID        string       `json:"message_id,omitempty"`
	CompositeChatKey string       `json:"composite_chat_key,omitempty"`
	CompositeMsgKey  string       `json:"composite_msg_key,omitempty"`
	SenderJID        string       `json:"sender_jid,omitempty"`
	SenderName       string       `json:"sender_name,omitempty"`
	IsFromMe         bool         `json:"is_from_me,omitempty"`
	IsGroup          bool         `json:"is_group,omitempty"`
	Text             string       `json:"text,omitempty"`
	MediaType        string       `json:"media_type,omitempty"` // "", "image", "video", "audio", "voice", "document", "sticker"
	MediaPath        string       `json:"media_path,omitempty"`
	MimeType         string       `json:"mime_type,omitempty"`
	FileName         string       `json:"file_name,omitempty"`
	FileSize         int64        `json:"file_size,omitempty"`
	DurationSec      int          `json:"duration_sec,omitempty"`
	IsVoiceNote      bool         `json:"is_voice_note,omitempty"`
	DirectPath       string       `json:"direct_path,omitempty"`
	MediaKeyB64      string       `json:"media_key_b64,omitempty"`
	FileSHA256B64    string       `json:"file_sha256_b64,omitempty"`
	FileEncSHA256B64 string       `json:"file_enc_sha256_b64,omitempty"`
	QuotedMessageID  string       `json:"quoted_message_id,omitempty"`
	QuotedSenderJID  string       `json:"quoted_sender_jid,omitempty"`
	QuotedText       string       `json:"quoted_text,omitempty"`
	EditTargetID     string       `json:"edit_target_id,omitempty"`
	IsHistorySync    bool         `json:"is_history_sync,omitempty"`
	UnreadCount      int          `json:"unread_count,omitempty"`
	Status           string       `json:"status,omitempty"` // "pending", "sent", "delivered", "read", "failed"

	// Reaction / revoke / receipt / presence / typing
	TargetMessageID  string       `json:"target_message_id,omitempty"`
	Emoji            string       `json:"emoji,omitempty"`
	MessageIDs       []string     `json:"message_ids,omitempty"`
	ReceiptStatus    string       `json:"receipt_status,omitempty"`
	IsTyping         bool         `json:"is_typing,omitempty"`
	MediaKind        string       `json:"media_kind,omitempty"`
	IsOnline         bool         `json:"is_online,omitempty"`
	LastSeenMs       int64        `json:"last_seen_ms,omitempty"`

	// History sync progress
	SyncType         string       `json:"sync_type,omitempty"`
	ChunkIndex       int          `json:"chunk_index,omitempty"`
	ProgressPct      int          `json:"progress_pct,omitempty"`
	ImportedChats    int          `json:"imported_chats,omitempty"`
	ImportedMessages int          `json:"imported_messages,omitempty"`

	// Contacts & groups batches
	Contacts         []ContactDTO `json:"contacts,omitempty"`
	Groups           []GroupDTO   `json:"groups,omitempty"`

	// Sanitized diagnostic log
	LogLevel         string       `json:"log_level,omitempty"`
	LogModule        string       `json:"log_module,omitempty"`
	LogMessage       string       `json:"log_message,omitempty"`
}
