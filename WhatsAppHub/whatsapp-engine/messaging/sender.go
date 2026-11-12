package messaging

import (
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
	"google.golang.org/protobuf/proto"
)

// SendResult holds metadata returned by WhatsApp servers when a message is sent.
type SendResult struct {
	MessageID   string `json:"message_id"`
	TimestampMs int64  `json:"timestamp_ms"`
}

// ReplyQuote carries optional reply context for outgoing messages.
type ReplyQuote struct {
	QuotedMessageID string
	QuotedSenderJID string
	QuotedText      string
}

func buildContextInfo(chat types.JID, quote *ReplyQuote) *waE2E.ContextInfo {
	if quote == nil || strings.TrimSpace(quote.QuotedMessageID) == "" {
		return nil
	}
	ci := &waE2E.ContextInfo{
		StanzaID:  proto.String(quote.QuotedMessageID),
		RemoteJID: proto.String(chat.ToNonAD().String()),
		QuotedMessage: &waE2E.Message{
			Conversation: proto.String(quote.QuotedText),
		},
	}
	if strings.TrimSpace(quote.QuotedSenderJID) != "" {
		if parsed, err := types.ParseJID(quote.QuotedSenderJID); err == nil {
			ci.Participant = proto.String(parsed.ToNonAD().String())
		}
	}
	return ci
}

// ParseRecipientJID parses and normalizes a chat JID, stripping any companion device suffix.
func ParseRecipientJID(raw string) (types.JID, error) {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return types.EmptyJID, errors.New("recipient chat JID is empty")
	}
	if !strings.Contains(trimmed, "@") {
		digits := strings.TrimPrefix(trimmed, "+")
		return types.NewJID(digits, types.DefaultUserServer), nil
	}
	jid, err := types.ParseJID(trimmed)
	if err != nil {
		return types.EmptyJID, fmt.Errorf("invalid JID %q: %w", trimmed, err)
	}
	return jid.ToNonAD(), nil
}

func buildSendExtra(clientMsgID string) []whatsmeow.SendRequestExtra {
	trimmed := strings.TrimSpace(clientMsgID)
	if trimmed == "" {
		return nil
	}
	return []whatsmeow.SendRequestExtra{{ID: types.MessageID(trimmed)}}
}

// SendText sends a plain or quoted text message on the given isolated client.
// When clientMsgID is non-empty, whatsmeow transmits using that exact deterministic MessageID
// so the pre-persisted Pending record on Android reconciles idempotently with zero ID drift.
func SendText(ctx context.Context, cli *whatsmeow.Client, chatJID, text string, quote *ReplyQuote, clientMsgID ...string) (SendResult, error) {
	if cli == nil || !cli.IsConnected() {
		return SendResult{}, errors.New("account client is not connected")
	}
	if strings.TrimSpace(text) == "" {
		return SendResult{}, errors.New("message text is empty")
	}
	to, err := ParseRecipientJID(chatJID)
	if err != nil {
		return SendResult{}, err
	}

	var msg *waE2E.Message
	if ci := buildContextInfo(to, quote); ci != nil {
		msg = &waE2E.Message{
			ExtendedTextMessage: &waE2E.ExtendedTextMessage{
				Text:        proto.String(text),
				ContextInfo: ci,
			},
		}
	} else {
		msg = &waE2E.Message{
			Conversation: proto.String(text),
		}
	}

	var preID string
	if len(clientMsgID) > 0 {
		preID = clientMsgID[0]
	}
	resp, err := cli.SendMessage(ctx, to, msg, buildSendExtra(preID)...)
	if err != nil {
		return SendResult{}, err
	}
	return SendResult{
		MessageID:   resp.ID,
		TimestampMs: resp.Timestamp.UnixMilli(),
	}, nil
}

// SendReaction sends or clears an emoji reaction on an existing message.
func SendReaction(ctx context.Context, cli *whatsmeow.Client, chatJID, senderJID, targetMessageID, emoji string) (SendResult, error) {
	if cli == nil || !cli.IsConnected() {
		return SendResult{}, errors.New("account client is not connected")
	}
	chat, err := ParseRecipientJID(chatJID)
	if err != nil {
		return SendResult{}, err
	}
	var sender types.JID
	if strings.TrimSpace(senderJID) != "" {
		if parsed, pErr := types.ParseJID(senderJID); pErr == nil {
			sender = parsed.ToNonAD()
		}
	}
	msg := cli.BuildReaction(chat, sender, targetMessageID, emoji)
	resp, err := cli.SendMessage(ctx, chat, msg)
	if err != nil {
		return SendResult{}, err
	}
	return SendResult{
		MessageID:   resp.ID,
		TimestampMs: resp.Timestamp.UnixMilli(),
	}, nil
}

// RevokeMessage deletes/revokes a message in a conversation.
func RevokeMessage(ctx context.Context, cli *whatsmeow.Client, chatJID, senderJID, targetMessageID string) (SendResult, error) {
	if cli == nil || !cli.IsConnected() {
		return SendResult{}, errors.New("account client is not connected")
	}
	chat, err := ParseRecipientJID(chatJID)
	if err != nil {
		return SendResult{}, err
	}
	var sender types.JID
	if strings.TrimSpace(senderJID) != "" {
		if parsed, pErr := types.ParseJID(senderJID); pErr == nil {
			sender = parsed.ToNonAD()
		}
	}
	msg := cli.BuildRevoke(chat, sender, targetMessageID)
	resp, err := cli.SendMessage(ctx, chat, msg)
	if err != nil {
		return SendResult{}, err
	}
	return SendResult{
		MessageID:   resp.ID,
		TimestampMs: resp.Timestamp.UnixMilli(),
	}, nil
}

// MediaUploadRequest describes a local file to upload and send on a specific account.
type MediaUploadRequest struct {
	ChatJID         string
	ClientMessageID string
	MediaType       string // "image", "video", "audio", "voice", "document", "sticker"
	FilePath        string
	MimeType        string
	Caption         string
	FileName        string
	DurationSec     uint32
	Quote           *ReplyQuote
}

// SendMedia uploads a file to WhatsApp CDN using encrypted media keys and sends the message.
func SendMedia(ctx context.Context, cli *whatsmeow.Client, req MediaUploadRequest) (SendResult, error) {
	if cli == nil || !cli.IsConnected() {
		return SendResult{}, errors.New("account client is not connected")
	}
	to, err := ParseRecipientJID(req.ChatJID)
	if err != nil {
		return SendResult{}, err
	}
	data, err := os.ReadFile(req.FilePath)
	if err != nil {
		return SendResult{}, fmt.Errorf("read media file: %w", err)
	}
	if len(data) == 0 {
		return SendResult{}, errors.New("media file is empty")
	}

	var waMedia whatsmeow.MediaType
	switch req.MediaType {
	case "image", "sticker":
		waMedia = whatsmeow.MediaImage
	case "video":
		waMedia = whatsmeow.MediaVideo
	case "audio", "voice":
		waMedia = whatsmeow.MediaAudio
	default:
		waMedia = whatsmeow.MediaDocument
	}

	up, err := cli.Upload(ctx, data, waMedia)
	if err != nil {
		return SendResult{}, fmt.Errorf("upload media: %w", err)
	}

	ci := buildContextInfo(to, req.Quote)
	nowSec := time.Now().Unix()
	var msg *waE2E.Message

	switch req.MediaType {
	case "image":
		mime := firstNonEmpty(req.MimeType, "image/jpeg")
		msg = &waE2E.Message{
			ImageMessage: &waE2E.ImageMessage{
				URL:               proto.String(up.URL),
				DirectPath:        proto.String(up.DirectPath),
				MediaKey:          up.MediaKey,
				Mimetype:          proto.String(mime),
				Caption:           proto.String(req.Caption),
				FileEncSHA256:     up.FileEncSHA256,
				FileSHA256:        up.FileSHA256,
				FileLength:        proto.Uint64(up.FileLength),
				MediaKeyTimestamp: proto.Int64(nowSec),
				ContextInfo:       ci,
			},
		}
	case "video":
		mime := firstNonEmpty(req.MimeType, "video/mp4")
		msg = &waE2E.Message{
			VideoMessage: &waE2E.VideoMessage{
				URL:               proto.String(up.URL),
				DirectPath:        proto.String(up.DirectPath),
				MediaKey:          up.MediaKey,
				Mimetype:          proto.String(mime),
				Caption:           proto.String(req.Caption),
				FileEncSHA256:     up.FileEncSHA256,
				FileSHA256:        up.FileSHA256,
				FileLength:        proto.Uint64(up.FileLength),
				Seconds:           proto.Uint32(req.DurationSec),
				MediaKeyTimestamp: proto.Int64(nowSec),
				ContextInfo:       ci,
			},
		}
	case "audio", "voice":
		isPTT := req.MediaType == "voice"
		mime := firstNonEmpty(req.MimeType, "audio/ogg; codecs=opus")
		msg = &waE2E.Message{
			AudioMessage: &waE2E.AudioMessage{
				URL:               proto.String(up.URL),
				DirectPath:        proto.String(up.DirectPath),
				MediaKey:          up.MediaKey,
				Mimetype:          proto.String(mime),
				FileEncSHA256:     up.FileEncSHA256,
				FileSHA256:        up.FileSHA256,
				FileLength:        proto.Uint64(up.FileLength),
				Seconds:           proto.Uint32(req.DurationSec),
				PTT:               proto.Bool(isPTT),
				MediaKeyTimestamp: proto.Int64(nowSec),
				ContextInfo:       ci,
			},
		}
	case "sticker":
		mime := firstNonEmpty(req.MimeType, "image/webp")
		msg = &waE2E.Message{
			StickerMessage: &waE2E.StickerMessage{
				URL:               proto.String(up.URL),
				DirectPath:        proto.String(up.DirectPath),
				MediaKey:          up.MediaKey,
				Mimetype:          proto.String(mime),
				FileEncSHA256:     up.FileEncSHA256,
				FileSHA256:        up.FileSHA256,
				FileLength:        proto.Uint64(up.FileLength),
				MediaKeyTimestamp: proto.Int64(nowSec),
				ContextInfo:       ci,
			},
		}
	default:
		name := firstNonEmpty(req.FileName, filepath.Base(req.FilePath), "document")
		mime := firstNonEmpty(req.MimeType, "application/octet-stream")
		msg = &waE2E.Message{
			DocumentMessage: &waE2E.DocumentMessage{
				URL:               proto.String(up.URL),
				DirectPath:        proto.String(up.DirectPath),
				MediaKey:          up.MediaKey,
				Mimetype:          proto.String(mime),
				Title:             proto.String(name),
				FileName:          proto.String(name),
				Caption:           proto.String(req.Caption),
				FileEncSHA256:     up.FileEncSHA256,
				FileSHA256:        up.FileSHA256,
				FileLength:        proto.Uint64(up.FileLength),
				MediaKeyTimestamp: proto.Int64(nowSec),
				ContextInfo:       ci,
			},
		}
	}

	resp, err := cli.SendMessage(ctx, to, msg, buildSendExtra(req.ClientMessageID)...)
	if err != nil {
		return SendResult{}, err
	}
	return SendResult{
		MessageID:   resp.ID,
		TimestampMs: resp.Timestamp.UnixMilli(),
	}, nil
}

var unsafeFileCharsRe = regexp.MustCompile(`[^a-zA-Z0-9._-]+`)

// DownloadMediaByMetadata downloads and decrypts media using stored metadata into the isolated account media directory.
func DownloadMediaByMetadata(
	ctx context.Context,
	cli *whatsmeow.Client,
	accountMediaDir string,
	messageID string,
	mediaType string,
	mimeType string,
	fileName string,
	directPath string,
	mediaKeyB64 string,
	fileSHA256B64 string,
	fileEncSHA256B64 string,
) (string, error) {
	if cli == nil {
		return "", errors.New("account client is nil")
	}
	if directPath == "" || mediaKeyB64 == "" {
		return "", errors.New("missing media directPath or mediaKey")
	}

	mediaKey, err := base64.StdEncoding.DecodeString(mediaKeyB64)
	if err != nil {
		return "", fmt.Errorf("decode mediaKey: %w", err)
	}
	fileHash, _ := base64.StdEncoding.DecodeString(fileSHA256B64)
	encHash, _ := base64.StdEncoding.DecodeString(fileEncSHA256B64)

	var waMedia whatsmeow.MediaType
	var mmsType string
	switch mediaType {
	case "image", "sticker":
		waMedia = whatsmeow.MediaImage
		mmsType = "image"
	case "video":
		waMedia = whatsmeow.MediaVideo
		mmsType = "video"
	case "audio", "voice":
		waMedia = whatsmeow.MediaAudio
		mmsType = "audio"
	default:
		waMedia = whatsmeow.MediaDocument
		mmsType = "document"
	}

	subDir := filepath.Join(accountMediaDir, firstNonEmpty(mediaType, "files"))
	if err := os.MkdirAll(subDir, 0o700); err != nil {
		return "", fmt.Errorf("create media subdir: %w", err)
	}

	safeID := unsafeFileCharsRe.ReplaceAllString(messageID, "_")
	if safeID == "" {
		safeID = fmt.Sprintf("msg_%d", time.Now().UnixNano())
	}
	ext := ExtensionForMime(mediaType, mimeType, fileName)
	destPath := filepath.Join(subDir, safeID+ext)

	if info, statErr := os.Stat(destPath); statErr == nil && info.Size() > 0 {
		return destPath, nil
	}

	plaintext, err := cli.DownloadMediaWithPath(ctx, directPath, encHash, fileHash, mediaKey, waMedia, mmsType, len(fileHash) == 0)
	if err != nil {
		return "", fmt.Errorf("download media: %w", err)
	}

	tmpPath := destPath + ".tmp"
	if err := os.WriteFile(tmpPath, plaintext, 0o600); err != nil {
		return "", fmt.Errorf("write media file: %w", err)
	}
	if err := os.Rename(tmpPath, destPath); err != nil {
		_ = os.Remove(tmpPath)
		return "", fmt.Errorf("commit media file: %w", err)
	}
	return destPath, nil
}

// MarkMessagesRead sends read receipts for one or more message IDs in a chat.
func MarkMessagesRead(ctx context.Context, cli *whatsmeow.Client, chatJID, senderJID string, msgIDs []string) error {
	if cli == nil || !cli.IsConnected() || len(msgIDs) == 0 {
		return nil
	}
	chat, err := ParseRecipientJID(chatJID)
	if err != nil {
		return err
	}
	var sender types.JID
	if strings.TrimSpace(senderJID) != "" {
		if parsed, pErr := types.ParseJID(senderJID); pErr == nil {
			sender = parsed.ToNonAD()
		}
	}
	ids := make([]types.MessageID, 0, len(msgIDs))
	for _, id := range msgIDs {
		if trimmed := strings.TrimSpace(id); trimmed != "" {
			ids = append(ids, trimmed)
		}
	}
	if len(ids) == 0 {
		return nil
	}
	return cli.MarkRead(ctx, ids, time.Now(), chat, sender)
}

// SendChatTyping sends a composing or paused presence indicator for a conversation.
func SendChatTyping(ctx context.Context, cli *whatsmeow.Client, chatJID string, isTyping bool, isAudio bool) error {
	if cli == nil || !cli.IsConnected() {
		return errors.New("account client is not connected")
	}
	chat, err := ParseRecipientJID(chatJID)
	if err != nil {
		return err
	}
	state := types.ChatPresencePaused
	media := types.ChatPresenceMediaText
	if isTyping {
		state = types.ChatPresenceComposing
		if isAudio {
			media = types.ChatPresenceMediaAudio
		}
	}
	return cli.SendChatPresence(ctx, chat, state, media)
}
