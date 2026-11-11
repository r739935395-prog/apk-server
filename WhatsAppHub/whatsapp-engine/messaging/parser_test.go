package messaging

import (
	"testing"

	"go.mau.fi/whatsmeow/proto/waCommon"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"google.golang.org/protobuf/proto"
)

func TestParseTextAndQuotedReply(t *testing.T) {
	raw := &waE2E.Message{
		ExtendedTextMessage: &waE2E.ExtendedTextMessage{
			Text: proto.String("Line 1\nLine 2 Arabic مرحبا"),
			ContextInfo: &waE2E.ContextInfo{
				StanzaID:    proto.String("ORIG_MSG_1"),
				Participant: proto.String("967770000001@s.whatsapp.net"),
				QuotedMessage: &waE2E.Message{
					Conversation: proto.String("Original quoted text"),
				},
			},
		},
	}
	parsed := ParseE2EMessage(raw)
	if parsed.Kind != KindMessage {
		t.Fatalf("expected KindMessage, got %s", parsed.Kind)
	}
	if parsed.Text != "Line 1\nLine 2 Arabic مرحبا" {
		t.Fatalf("unexpected text: %q", parsed.Text)
	}
	if parsed.QuotedMessageID != "ORIG_MSG_1" || parsed.QuotedText != "Original quoted text" {
		t.Fatalf("unexpected quote extraction: %+v", parsed)
	}
}

func TestParseEphemeralWrappedVoiceAndSticker(t *testing.T) {
	voiceWrapped := &waE2E.Message{
		EphemeralMessage: &waE2E.FutureProofMessage{
			Message: &waE2E.Message{
				AudioMessage: &waE2E.AudioMessage{
					PTT:        proto.Bool(true),
					Seconds:    proto.Uint32(14),
					DirectPath: proto.String("/v/t62.7117-24/12345"),
					MediaKey:   []byte("0123456789abcdef0123456789abcdef"),
				},
			},
		},
	}
	pVoice := ParseE2EMessage(voiceWrapped)
	if pVoice.MediaType != "voice" || !pVoice.IsVoiceNote || pVoice.DurationSec != 14 {
		t.Fatalf("unexpected voice parse: %+v", pVoice)
	}

	stickerMsg := &waE2E.Message{
		StickerMessage: &waE2E.StickerMessage{
			Mimetype:   proto.String("image/webp"),
			DirectPath: proto.String("/v/t62.15575-24/999"),
			MediaKey:   []byte("0123456789abcdef0123456789abcdef"),
		},
	}
	pSticker := ParseE2EMessage(stickerMsg)
	if pSticker.MediaType != "sticker" || ExtensionForMime(pSticker.MediaType, pSticker.MimeType, "") != ".webp" {
		t.Fatalf("unexpected sticker parse: %+v", pSticker)
	}
}

func TestParseReactionAndRevoke(t *testing.T) {
	rx := ParseE2EMessage(&waE2E.Message{
		ReactionMessage: &waE2E.ReactionMessage{
			Key:  &waCommon.MessageKey{ID: proto.String("TARGET_1")},
			Text: proto.String("👍"),
		},
	})
	if rx.Kind != KindReaction || rx.TargetMessageID != "TARGET_1" || rx.ReactionEmoji != "👍" {
		t.Fatalf("unexpected reaction: %+v", rx)
	}

	revType := waE2E.ProtocolMessage_REVOKE
	rev := ParseE2EMessage(&waE2E.Message{
		ProtocolMessage: &waE2E.ProtocolMessage{
			Type: &revType,
			Key:  &waCommon.MessageKey{ID: proto.String("DEL_99")},
		},
	})
	if rev.Kind != KindRevoke || rev.TargetMessageID != "DEL_99" {
		t.Fatalf("unexpected revoke: %+v", rev)
	}
}

func TestParseRecipientJIDStripsDeviceSuffix(t *testing.T) {
	jid, err := ParseRecipientJID("967771112223:4@s.whatsapp.net")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if jid.Device != 0 || jid.String() != "967771112223@s.whatsapp.net" {
		t.Fatalf("expected non-AD JID, got %s (device=%d)", jid.String(), jid.Device)
	}
}
