package messaging

import (
	"encoding/base64"
	"fmt"
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

// UnwrapMessage recursively strips WhatsApp wrapper containers:
// primary-phone DeviceSentMessage, EditedMessage, EphemeralMessage,
// DocumentWithCaption, ViewOnce (v1/v2/ext), GroupMentioned, and LottieSticker.
func UnwrapMessage(msg *waE2E.Message) *waE2E.Message {
	for i := 0; i < 8 && msg != nil; i++ {
		switch {
		case msg.GetDeviceSentMessage().GetMessage() != nil:
			msg = msg.GetDeviceSentMessage().GetMessage()
		case msg.GetEditedMessage().GetMessage() != nil:
			msg = msg.GetEditedMessage().GetMessage()
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
		case msg.GetGroupMentionedMessage().GetMessage() != nil:
			msg = msg.GetGroupMentionedMessage().GetMessage()
		case msg.GetLottieStickerMessage().GetMessage() != nil:
			msg = msg.GetLottieStickerMessage().GetMessage()
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
		case qm.GetLocationMessage() != nil || qm.GetLiveLocationMessage() != nil:
			out.QuotedText = "[Location]"
		case qm.GetContactMessage() != nil || qm.GetContactsArrayMessage() != nil:
			out.QuotedText = "[Contact]"
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

	// Location & Live Location
	if loc := msg.GetLocationMessage(); loc != nil {
		label := strings.TrimSpace(firstNonEmpty(loc.GetName(), loc.GetAddress(), loc.GetComment()))
		coords := fmt.Sprintf("%.5f, %.5f", loc.GetDegreesLatitude(), loc.GetDegreesLongitude())
		text := "Location: " + coords
		if label != "" {
			text = fmt.Sprintf("Location: %s (%s)", label, coords)
		}
		out := ParsedMessage{Kind: KindMessage, Text: text}
		extractContextInfo(loc.GetContextInfo(), &out)
		return out
	}
	if live := msg.GetLiveLocationMessage(); live != nil {
		coords := fmt.Sprintf("%.5f, %.5f", live.GetDegreesLatitude(), live.GetDegreesLongitude())
		text := "Live Location: " + coords
		if strings.TrimSpace(live.GetCaption()) != "" {
			text = fmt.Sprintf("Live Location: %s (%s)", strings.TrimSpace(live.GetCaption()), coords)
		}
		out := ParsedMessage{Kind: KindMessage, Text: text}
		extractContextInfo(live.GetContextInfo(), &out)
		return out
	}

	// Shared Contact & Contacts Array
	if ct := msg.GetContactMessage(); ct != nil {
		name := firstNonEmpty(ct.GetDisplayName(), "Shared Contact")
		out := ParsedMessage{Kind: KindMessage, Text: "Contact: " + name}
		extractContextInfo(ct.GetContextInfo(), &out)
		return out
	}
	if cta := msg.GetContactsArrayMessage(); cta != nil {
		names := make([]string, 0, len(cta.GetContacts()))
		for _, c := range cta.GetContacts() {
			if n := strings.TrimSpace(c.GetDisplayName()); n != "" {
				names = append(names, n)
			}
		}
		summary := firstNonEmpty(strings.Join(names, ", "), cta.GetDisplayName(), "Shared Contacts")
		out := ParsedMessage{Kind: KindMessage, Text: "Contacts: " + summary}
		extractContextInfo(cta.GetContextInfo(), &out)
		return out
	}

	// Polls (v1 / v2 / v3 / v5 / v6)
	poll := msg.GetPollCreationMessage()
	if poll == nil {
		poll = msg.GetPollCreationMessageV2()
	}
	if poll == nil {
		poll = msg.GetPollCreationMessageV3()
	}
	if poll == nil {
		poll = msg.GetPollCreationMessageV5()
	}
	if poll == nil {
		poll = msg.GetPollCreationMessageV6()
	}
	if poll != nil {
		opts := make([]string, 0, len(poll.GetOptions()))
		for _, opt := range poll.GetOptions() {
			if n := strings.TrimSpace(opt.GetOptionName()); n != "" {
				opts = append(opts, "• "+n)
			}
		}
		text := "Poll: " + firstNonEmpty(poll.GetName(), "Untitled Poll")
		if len(opts) > 0 {
			text += "\n" + strings.Join(opts, "\n")
		}
		out := ParsedMessage{Kind: KindMessage, Text: text}
		extractContextInfo(poll.GetContextInfo(), &out)
		return out
	}

	// Buttons / Template replies
	if br := msg.GetButtonsResponseMessage(); br != nil && br.GetSelectedDisplayText() != "" {
		return ParsedMessage{Kind: KindMessage, Text: br.GetSelectedDisplayText()}
	}
	if tr := msg.GetTemplateButtonReplyMessage(); tr != nil && tr.GetSelectedDisplayText() != "" {
		return ParsedMessage{Kind: KindMessage, Text: tr.GetSelectedDisplayText()}
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
