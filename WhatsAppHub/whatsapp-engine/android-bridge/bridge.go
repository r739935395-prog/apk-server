package androidbridge

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"strings"
	"sync"
	"time"

	"whatshub/events"
	"whatshub/messaging"
	"whatshub/session"
)

// EngineVersion pins the exact upstream whatsmeow revision bundled into the native library.
const EngineVersion = "whatshub-engine/1.0.0 (go.mau.fi/whatsmeow@c88388f983b4bc43dfd0bcc2b46969fcde82d61f)"

// ResultEnvelope is the standard JSON response returned across JNI for imperative calls.
type ResultEnvelope struct {
	OK          bool   `json:"ok"`
	Error       string `json:"error,omitempty"`
	MessageID   string `json:"message_id,omitempty"`
	TimestampMs int64  `json:"timestamp_ms,omitempty"`
	PairingCode string `json:"pairing_code,omitempty"`
	MediaPath   string `json:"media_path,omitempty"`
}

func okResult() string {
	b, _ := json.Marshal(ResultEnvelope{OK: true})
	return string(b)
}

func errResult(err error) string {
	msg := "unknown error"
	if err != nil {
		msg = events.SanitizeLogLine(err.Error())
	}
	b, _ := json.Marshal(ResultEnvelope{OK: false, Error: msg})
	return string(b)
}

// Bridge coordinates the Go event router and 4-account SessionManager for Android JNI.
type Bridge struct {
	mu      sync.RWMutex
	router  *events.Router
	manager *session.Manager
}

var defaultBridge = &Bridge{}

// Default returns the process-wide singleton Bridge used by JNI entrypoints.
func Default() *Bridge {
	return defaultBridge
}

// NewBridge creates an isolated Bridge instance (used in integration tests).
func NewBridge() *Bridge {
	return &Bridge{}
}

// Init initializes session/media directories, configures Android TLS root CA certificates,
// and prepares the 4 isolated account slots.
func (b *Bridge) Init(sessionsDir, mediaDir, caCertPath string) string {
	if strings.TrimSpace(sessionsDir) == "" || strings.TrimSpace(mediaDir) == "" {
		return errResult(errors.New("sessionsDir and mediaDir are required"))
	}
	if trimmedCA := strings.TrimSpace(caCertPath); trimmedCA != "" {
		if info, err := os.Stat(trimmedCA); err == nil && info.Size() > 0 {
			_ = os.Setenv("SSL_CERT_FILE", trimmedCA)
		}
	}

	b.mu.Lock()
	defer b.mu.Unlock()
	if b.manager != nil {
		return okResult()
	}
	b.router = events.NewRouter(8192)
	b.manager = session.NewManager(sessionsDir, mediaDir, b.router)
	return okResult()
}

func (b *Bridge) getComponents() (*session.Manager, *events.Router, error) {
	b.mu.RLock()
	defer b.mu.RUnlock()
	if b.manager == nil || b.router == nil {
		return nil, nil, errors.New("WhatsApp engine is not initialized")
	}
	return b.manager, b.router, nil
}

// Version returns the pinned engine & protocol version string.
func (b *Bridge) Version() string {
	return EngineVersion
}

// SetSlotKey injects the Android Keystore-unwrapped 256-bit AES-GCM key (64 hex chars) for accountID (1..4).
func (b *Bridge) SetSlotKey(accountID int, keyHex string) string {
	mgr, _, err := b.getComponents()
	if err != nil {
		return errResult(err)
	}
	if err := mgr.SetSlotKey(accountID, keyHex); err != nil {
		return errResult(err)
	}
	return okResult()
}

// ConnectAccount connects or starts QR pairing for accountID (1..4).
func (b *Bridge) ConnectAccount(accountID int) string {
	mgr, _, err := b.getComponents()
	if err != nil {
		return errResult(err)
	}
	if err := mgr.Connect(accountID); err != nil {
		return errResult(err)
	}
	return okResult()
}

// DisconnectAccount disconnects accountID without affecting any other account.
func (b *Bridge) DisconnectAccount(accountID int) string {
	mgr, _, err := b.getComponents()
	if err != nil {
		return errResult(err)
	}
	if err := mgr.Disconnect(accountID); err != nil {
		return errResult(err)
	}
	return okResult()
}

// RemoveAccount logs out and wipes local session + cached media strictly for accountID.
func (b *Bridge) RemoveAccount(accountID int) string {
	mgr, _, err := b.getComponents()
	if err != nil {
		return errResult(err)
	}
	if err := mgr.RemoveAccount(accountID); err != nil {
		return errResult(err)
	}
	return okResult()
}

// RequestPairingCode requests an 8-character WhatsApp companion pairing code for phone.
func (b *Bridge) RequestPairingCode(accountID int, phone string) string {
	mgr, _, err := b.getComponents()
	if err != nil {
		return errResult(err)
	}
	code, err := mgr.RequestPairingCode(accountID, phone)
	if err != nil {
		return errResult(err)
	}
	out, _ := json.Marshal(ResultEnvelope{
		OK:          true,
		PairingCode: code,
	})
	return string(out)
}

// GetAccountsSnapshot returns JSON array of all 4 account statuses.
func (b *Bridge) GetAccountsSnapshot() string {
	mgr, _, err := b.getComponents()
	if err != nil {
		return "[]"
	}
	return mgr.GetAllSnapshotsJSON()
}

// PollEvents drains up to maxBatch queued events as a JSON array.
func (b *Bridge) PollEvents(maxBatch int) string {
	_, router, err := b.getComponents()
	if err != nil {
		return "[]"
	}
	return router.PollJSON(maxBatch)
}

type sendTextPayload struct {
	ChatJID         string `json:"chat_jid"`
	Text            string `json:"text"`
	QuotedMessageID string `json:"quoted_message_id,omitempty"`
	QuotedSenderJID string `json:"quoted_sender_jid,omitempty"`
	QuotedText      string `json:"quoted_text,omitempty"`
}

// SendText sends a text or quoted reply message strictly via accountID's session.
func (b *Bridge) SendText(accountID int, payloadJSON string) string {
	mgr, _, err := b.getComponents()
	if err != nil {
		return errResult(err)
	}
	cli, _, err := mgr.ClientForAccount(accountID)
	if err != nil {
		return errResult(err)
	}
	var req sendTextPayload
	if err := json.Unmarshal([]byte(payloadJSON), &req); err != nil {
		return errResult(err)
	}
	var quote *messaging.ReplyQuote
	if req.QuotedMessageID != "" {
		quote = &messaging.ReplyQuote{
			QuotedMessageID: req.QuotedMessageID,
			QuotedSenderJID: req.QuotedSenderJID,
			QuotedText:      req.QuotedText,
		}
	}
	ctx, cancel := context.WithTimeout(context.Background(), 25*time.Second)
	defer cancel()

	res, err := messaging.SendText(ctx, cli, req.ChatJID, req.Text, quote)
	if err != nil {
		return errResult(err)
	}
	out, _ := json.Marshal(ResultEnvelope{
		OK:          true,
		MessageID:   res.MessageID,
		TimestampMs: res.TimestampMs,
	})
	return string(out)
}

type sendMediaPayload struct {
	ChatJID         string `json:"chat_jid"`
	MediaType       string `json:"media_type"`
	FilePath        string `json:"file_path"`
	MimeType        string `json:"mime_type"`
	Caption         string `json:"caption"`
	FileName        string `json:"file_name"`
	DurationSec     uint32 `json:"duration_sec"`
	QuotedMessageID string `json:"quoted_message_id,omitempty"`
	QuotedSenderJID string `json:"quoted_sender_jid,omitempty"`
	QuotedText      string `json:"quoted_text,omitempty"`
}

// SendMedia uploads and sends an image, video, audio, voice note, document, or sticker strictly via accountID.
func (b *Bridge) SendMedia(accountID int, payloadJSON string) string {
	mgr, _, err := b.getComponents()
	if err != nil {
		return errResult(err)
	}
	cli, _, err := mgr.ClientForAccount(accountID)
	if err != nil {
		return errResult(err)
	}
	var req sendMediaPayload
	if err := json.Unmarshal([]byte(payloadJSON), &req); err != nil {
		return errResult(err)
	}
	var quote *messaging.ReplyQuote
	if req.QuotedMessageID != "" {
		quote = &messaging.ReplyQuote{
			QuotedMessageID: req.QuotedMessageID,
			QuotedSenderJID: req.QuotedSenderJID,
			QuotedText:      req.QuotedText,
		}
	}
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()

	res, err := messaging.SendMedia(ctx, cli, messaging.MediaUploadRequest{
		ChatJID:     req.ChatJID,
		MediaType:   req.MediaType,
		FilePath:    req.FilePath,
		MimeType:    req.MimeType,
		Caption:     req.Caption,
		FileName:    req.FileName,
		DurationSec: req.DurationSec,
		Quote:       quote,
	})
	if err != nil {
		return errResult(err)
	}
	out, _ := json.Marshal(ResultEnvelope{
		OK:          true,
		MessageID:   res.MessageID,
		TimestampMs: res.TimestampMs,
	})
	return string(out)
}

// SendReaction sends an emoji reaction strictly via accountID.
func (b *Bridge) SendReaction(accountID int, chatJID, senderJID, targetMessageID, emoji string) string {
	mgr, _, err := b.getComponents()
	if err != nil {
		return errResult(err)
	}
	cli, _, err := mgr.ClientForAccount(accountID)
	if err != nil {
		return errResult(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()

	res, err := messaging.SendReaction(ctx, cli, chatJID, senderJID, targetMessageID, emoji)
	if err != nil {
		return errResult(err)
	}
	out, _ := json.Marshal(ResultEnvelope{
		OK:          true,
		MessageID:   res.MessageID,
		TimestampMs: res.TimestampMs,
	})
	return string(out)
}

// RevokeMessage deletes/revokes a message strictly via accountID.
func (b *Bridge) RevokeMessage(accountID int, chatJID, senderJID, targetMessageID string) string {
	mgr, _, err := b.getComponents()
	if err != nil {
		return errResult(err)
	}
	cli, _, err := mgr.ClientForAccount(accountID)
	if err != nil {
		return errResult(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()

	res, err := messaging.RevokeMessage(ctx, cli, chatJID, senderJID, targetMessageID)
	if err != nil {
		return errResult(err)
	}
	out, _ := json.Marshal(ResultEnvelope{
		OK:          true,
		MessageID:   res.MessageID,
		TimestampMs: res.TimestampMs,
	})
	return string(out)
}

type downloadMediaPayload struct {
	MessageID        string `json:"message_id"`
	MediaType        string `json:"media_type"`
	MimeType         string `json:"mime_type"`
	FileName         string `json:"file_name"`
	DirectPath       string `json:"direct_path"`
	MediaKeyB64      string `json:"media_key_b64"`
	FileSHA256B64    string `json:"file_sha256_b64"`
	FileEncSHA256B64 string `json:"file_enc_sha256_b64"`
}

// DownloadMedia downloads encrypted WhatsApp media into accountID's isolated media folder.
func (b *Bridge) DownloadMedia(accountID int, payloadJSON string) string {
	mgr, _, err := b.getComponents()
	if err != nil {
		return errResult(err)
	}
	cli, accountMediaDir, err := mgr.ClientForAccount(accountID)
	if err != nil {
		return errResult(err)
	}
	var req downloadMediaPayload
	if err := json.Unmarshal([]byte(payloadJSON), &req); err != nil {
		return errResult(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()

	localPath, err := messaging.DownloadMediaByMetadata(
		ctx, cli, accountMediaDir, req.MessageID,
		req.MediaType, req.MimeType, req.FileName,
		req.DirectPath, req.MediaKeyB64, req.FileSHA256B64, req.FileEncSHA256B64,
	)
	if err != nil {
		return errResult(err)
	}
	out, _ := json.Marshal(ResultEnvelope{
		OK:        true,
		MediaPath: localPath,
	})
	return string(out)
}

// MarkRead sends read receipts for comma-separated message IDs on accountID.
func (b *Bridge) MarkRead(accountID int, chatJID, senderJID, msgIDsCSV string) string {
	mgr, _, err := b.getComponents()
	if err != nil {
		return errResult(err)
	}
	cli, _, err := mgr.ClientForAccount(accountID)
	if err != nil {
		return errResult(err)
	}
	parts := strings.Split(msgIDsCSV, ",")
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()

	if err := messaging.MarkMessagesRead(ctx, cli, chatJID, senderJID, parts); err != nil {
		return errResult(err)
	}
	return okResult()
}

// SendTyping sends composing/paused chat presence on accountID.
func (b *Bridge) SendTyping(accountID int, chatJID string, isTyping, isAudio bool) string {
	mgr, _, err := b.getComponents()
	if err != nil {
		return errResult(err)
	}
	cli, _, err := mgr.ClientForAccount(accountID)
	if err != nil {
		return errResult(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	if err := messaging.SendChatTyping(ctx, cli, chatJID, isTyping, isAudio); err != nil {
		return errResult(err)
	}
	return okResult()
}

// GetContacts returns JSON contacts for accountID.
func (b *Bridge) GetContacts(accountID int) string {
	mgr, _, err := b.getComponents()
	if err != nil {
		return "[]"
	}
	out, err := mgr.GetContactsJSON(accountID)
	if err != nil {
		return "[]"
	}
	return out
}

// GetGroups returns JSON joined groups for accountID.
func (b *Bridge) GetGroups(accountID int) string {
	mgr, _, err := b.getComponents()
	if err != nil {
		return "[]"
	}
	out, err := mgr.GetGroupsJSON(accountID)
	if err != nil {
		return "[]"
	}
	return out
}

// GetGroupInfo returns JSON metadata and participant list for one group on accountID.
func (b *Bridge) GetGroupInfo(accountID int, groupJID string) string {
	mgr, _, err := b.getComponents()
	if err != nil {
		return "{}"
	}
	out, err := mgr.GetGroupInfoJSON(accountID, groupJID)
	if err != nil {
		return "{}"
	}
	return out
}

// OnNetworkChanged notifies the engine when Android connectivity changes.
func (b *Bridge) OnNetworkChanged(available bool) string {
	mgr, _, err := b.getComponents()
	if err != nil {
		return errResult(err)
	}
	mgr.OnNetworkChanged(available)
	return okResult()
}

// SetDebugLogging toggles sanitized verbose diagnostics.
func (b *Bridge) SetDebugLogging(enabled bool) string {
	_, router, err := b.getComponents()
	if err != nil {
		return errResult(err)
	}
	router.SetDebugLogging(enabled)
	return okResult()
}

// Shutdown cleanly closes all 4 sessions and SQLite handles.
func (b *Bridge) Shutdown() string {
	b.mu.Lock()
	mgr := b.manager
	b.manager = nil
	b.router = nil
	b.mu.Unlock()

	if mgr != nil {
		mgr.Shutdown()
	}
	return okResult()
}
