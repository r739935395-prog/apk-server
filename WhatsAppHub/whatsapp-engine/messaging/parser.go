package messaging

import (
	"encoding/base64"
	"strings"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waE2E"
)

// ParsedKind distinguishes regular messages, reactions, revocations, and edits.
type ParsedKind string

const (
	KindMessage  ParsedKind = "message"
	KindReaction ParsedKind = "reaction"
	KindRevoke   ParsedKind = "revoke"
	KindEdit     ParsedKind = "edit"
	KindIgnore   ParsedKind = "ignore"
)

// ParsedMessage holds normalized content extracted from a waE2E.Message.
type ParsedMessage struct {
	Kind             ParsedKind
	Text             string
	MediaType        string // "", "image", "video", "audio", "voice", "document", "sticker"
	MimeType         string
	FileName         string
	FileSize         int64
	DurationSec      int
	IsVoiceNote      bool
	DirectPath       string
	MediaKeyB64      string
	FileSHA256B64    string
	FileEncSHA256B64 string
	Downloadable     whatsmeow.DownloadableMessage

	QuotedMessageID string
	QuotedSenderJID string
	QuotedText      string

	TargetMessageID string
	ReactionEmoji   string
}

// UnwrapMessage strips wrapper containers (ephemeral, view-once, document-with-caption).
func UnwrapMessage(msg *waE2E.Message) *waE2E.Message {
	for i := 0; i < 6 && msg != nil; i++ {
		switch {
		case msg.GetEphemeralMessage().GetMessage() != nil:
			msg = msg.GetEphemeralMessage().GetMessage()
		case msg.GetDocumentWithCaptionMessage().GetMessage() != nil:
			msg = msg.GetDocumentWithCaptionMessage().GetMessage()
		case msg.GetViewOnceMessage().GetMessage() != nil:
			msg = msg.GetViewOnceMessage().GetMessage()
		case msg.GetViewOnceMessageV2().GetMessage() != nil:
			msg = msg.GetViewOnceMessageV2().GetMessage()
		case msg.GetViewOnceMessageV2Extension().GetMessage() != nil:
			msg = msg.GetViewOnceMessageV2Extension().GetMessage()
		default:
			return msg
		}
	}
	return msg
}

func extractContextInfo(ci *waE2E.ContextInfo, out *ParsedMessage) {
	if ci == nil {
		return
	}
	out.QuotedMessageID = ci.GetStanzaID()
	out.QuotedSenderJID = ci.GetParticipant()
	if qm := UnwrapMessage(ci.GetQuotedMessage()); qm != nil {
		switch {
		case qm.GetConversation() != "":
			out.QuotedText = qm.GetConversation()
		case qm.GetExtendedTextMessage() != nil:
			out.QuotedText = qm.GetExtendedTextMessage().GetText()
		case qm.GetImageMessage() != nil:
			out.QuotedText = firstNonEmpty(qm.GetImageMessage().GetCaption(), "[Image]")
		case qm.GetVideoMessage() != nil:
			out.QuotedText = firstNonEmpty(qm.GetVideoMessage().GetCaption(), "[Video]")
		case qm.GetAudioMessage() != nil:
			if qm.GetAudioMessage().GetPTT() {
				out.QuotedText = "[Voice note]"
			} else {
				out.QuotedText = "[Audio]"
			}
		case qm.GetDocumentMessage() != nil:
			out.QuotedText = firstNonEmpty(qm.GetDocumentMessage().GetFileName(), "[Document]")
		case qm.GetStickerMessage() != nil:
			out.QuotedText = "[Sticker]"
		}
	}
}

// ParseE2EMessage converts a raw protobuf WhatsApp message into a normalized ParsedMessage.
func ParseE2EMessage(raw *waE2E.Message) ParsedMessage {
	msg := UnwrapMessage(raw)
	if msg == nil {
		return ParsedMessage{Kind: KindIgnore}
	}

	// Protocol messages (revocations & message edits)
	if pm := msg.GetProtocolMessage(); pm != nil {
		switch pm.GetType() {
		case waE2E.ProtocolMessage_REVOKE:
			targetID := pm.GetKey().GetID()
			if targetID == "" {
				return ParsedMessage{Kind: KindIgnore}
			}
			return ParsedMessage{
				Kind:            KindRevoke,
				TargetMessageID: targetID,
			}
		case waE2E.ProtocolMessage_MESSAGE_EDIT:
			targetID := pm.GetKey().GetID()
			edited := ParseE2EMessage(pm.GetEditedMessage())
			if targetID != "" && edited.Kind == KindMessage {
				edited.Kind = KindEdit
				edited.TargetMessageID = targetID
				return edited
			}
		default:
			return ParsedMessage{Kind: KindIgnore}
		}
	}

	// Reactions
	if rx := msg.GetReactionMessage(); rx != nil {
		targetID := rx.GetKey().GetID()
		if targetID == "" {
			return ParsedMessage{Kind: KindIgnore}
		}
		return ParsedMessage{
			Kind:            KindReaction,
			TargetMessageID: targetID,
			ReactionEmoji:   rx.GetText(),
		}
	}

	// Plain text
	if conv := msg.GetConversation(); conv != "" {
		return ParsedMessage{
			Kind: KindMessage,
			Text: conv,
		}
	}

	// Extended text (multiline, links, quoted replies)
	if ext := msg.GetExtendedTextMessage(); ext != nil {
		out := ParsedMessage{
			Kind: KindMessage,
			Text: ext.GetText(),
		}
		extractContextInfo(ext.GetContextInfo(), &out)
		return out
	}

	// Image
	if img := msg.GetImageMessage(); img != nil {
		out := ParsedMessage{
			Kind:             KindMessage,
			Text:             img.GetCaption(),
			MediaType:        "image",
			MimeType:         firstNonEmpty(img.GetMimetype(), "image/jpeg"),
			FileSize:         int64(img.GetFileLength()),
			DirectPath:       img.GetDirectPath(),
			MediaKeyB64:      b64(img.GetMediaKey()),
			FileSHA256B64:    b64(img.GetFileSHA256()),
			FileEncSHA256B64: b64(img.GetFileEncSHA256()),
			Downloadable:     img,
		}
		extractContextInfo(img.GetContextInfo(), &out)
		return out
	}

	// Video
	if vid := msg.GetVideoMessage(); vid != nil {
		out := ParsedMessage{
			Kind:             KindMessage,
			Text:             vid.GetCaption(),
			MediaType:        "video",
			MimeType:         firstNonEmpty(vid.GetMimetype(), "video/mp4"),
			FileSize:         int64(vid.GetFileLength()),
			DurationSec:      int(vid.GetSeconds()),
			DirectPath:       vid.GetDirectPath(),
			MediaKeyB64:      b64(vid.GetMediaKey()),
			FileSHA256B64:    b64(vid.GetFileSHA256()),
			FileEncSHA256B64: b64(vid.GetFileEncSHA256()),
			Downloadable:     vid,
		}
		extractContextInfo(vid.GetContextInfo(), &out)
		return out
	}

	// Audio & Voice Note (PTT)
	if aud := msg.GetAudioMessage(); aud != nil {
		mediaKind := "audio"
		if aud.GetPTT() {
			mediaKind = "voice"
		}
		out := ParsedMessage{
			Kind:             KindMessage,
			MediaType:        mediaKind,
			MimeType:         firstNonEmpty(aud.GetMimetype(), "audio/ogg; codecs=opus"),
			FileSize:         int64(aud.GetFileLength()),
			DurationSec:      int(aud.GetSeconds()),
			IsVoiceNote:      aud.GetPTT(),
			DirectPath:       aud.GetDirectPath(),
			MediaKeyB64:      b64(aud.GetMediaKey()),
			FileSHA256B64:    b64(aud.GetFileSHA256()),
			FileEncSHA256B64: b64(aud.GetFileEncSHA256()),
			Downloadable:     aud,
		}
		extractContextInfo(aud.GetContextInfo(), &out)
		return out
	}

	// Document
	if doc := msg.GetDocumentMessage(); doc != nil {
		out := ParsedMessage{
			Kind:             KindMessage,
			Text:             doc.GetCaption(),
			MediaType:        "document",
			MimeType:         firstNonEmpty(doc.GetMimetype(), "application/octet-stream"),
			FileName:         firstNonEmpty(doc.GetFileName(), doc.GetTitle(), "document"),
			FileSize:         int64(doc.GetFileLength()),
			DirectPath:       doc.GetDirectPath(),
			MediaKeyB64:      b64(doc.GetMediaKey()),
			FileSHA256B64:    b64(doc.GetFileSHA256()),
			FileEncSHA256B64: b64(doc.GetFileEncSHA256()),
			Downloadable:     doc,
		}
		extractContextInfo(doc.GetContextInfo(), &out)
		return out
	}

	// Sticker
	if stk := msg.GetStickerMessage(); stk != nil {
		out := ParsedMessage{
			Kind:             KindMessage,
			MediaType:        "sticker",
			MimeType:         firstNonEmpty(stk.GetMimetype(), "image/webp"),
			FileSize:         int64(stk.GetFileLength()),
			DirectPath:       stk.GetDirectPath(),
			MediaKeyB64:      b64(stk.GetMediaKey()),
			FileSHA256B64:    b64(stk.GetFileSHA256()),
			FileEncSHA256B64: b64(stk.GetFileEncSHA256()),
			Downloadable:     stk,
		}
		extractContextInfo(stk.GetContextInfo(), &out)
		return out
	}

	return ParsedMessage{Kind: KindIgnore}
}

// ExtensionForMime maps common WhatsApp media MIME types to safe file extensions.
func ExtensionForMime(mediaType, mimeType, fileName string) string {
	if fileName != "" {
		if idx := strings.LastIndex(fileName, "."); idx > 0 && idx < len(fileName)-1 {
			ext := strings.ToLower(fileName[idx:])
			if len(ext) <= 8 && !strings.ContainsAny(ext, `/\`) {
				return ext
			}
		}
	}
	mt := strings.ToLower(strings.TrimSpace(strings.Split(mimeType, ";")[0]))
	switch mt {
	case "image/jpeg", "image/jpg":
		return ".jpg"
	case "image/png":
		return ".png"
	case "image/webp":
		return ".webp"
	case "image/gif":
		return ".gif"
	case "video/mp4":
		return ".mp4"
	case "video/3gpp":
		return ".3gp"
	case "audio/ogg", "application/ogg":
		return ".ogg"
	case "audio/mpeg", "audio/mp3":
		return ".mp3"
	case "audio/mp4", "audio/aac", "audio/x-m4a":
		return ".m4a"
	case "application/pdf":
		return ".pdf"
	}
	switch mediaType {
	case "image":
		return ".jpg"
	case "video":
		return ".mp4"
	case "audio", "voice":
		return ".ogg"
	case "sticker":
		return ".webp"
	default:
		return ".bin"
	}
}

func b64(b []byte) string {
	if len(b) == 0 {
		return ""
	}
	return base64.StdEncoding.EncodeToString(b)
}

func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if strings.TrimSpace(v) != "" {
			return v
		}
	}
	return ""
}
