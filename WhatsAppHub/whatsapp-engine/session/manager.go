package session

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waCompanionReg"
	"go.mau.fi/whatsmeow/store"
	"go.mau.fi/whatsmeow/types"
	waEvents "go.mau.fi/whatsmeow/types/events"

	"whatshub/events"
	"whatshub/messaging"
	"whatshub/synchronization"
)

var initWhatsmeowPropsOnce sync.Once

func ensureDevicePropsInitialized() {
	initWhatsmeowPropsOnce.Do(func() {
		store.SetOSInfo("Rafat Hub", [3]uint32{1, 1, 0})
		store.DeviceProps.PlatformType = waCompanionReg.DeviceProps_CHROME.Enum()
	})
}

// SlotSnapshot summarizes an account slot's current connection and identity status.
type SlotSnapshot struct {
	AccountID        int                 `json:"account_id"`
	State            events.AccountState `json:"state"`
	JID              string              `json:"jid"`
	Phone            string              `json:"phone"`
	PushName         string              `json:"push_name"`
	HasSavedSession  bool                `json:"has_saved_session"`
	ErrorMessage     string              `json:"error_message,omitempty"`
	ReconnectAttempt int                 `json:"reconnect_attempt"`
}

// AccountSlot encapsulates ONE completely isolated WhatsApp account session.
type AccountSlot struct {
	mgr         *Manager
	accountID   int
	sessionsDir string
	mediaDir    string
	router      *events.Router
	mediaSem    chan struct{}

	mu              sync.Mutex
	cipher          *SlotCipher
	container       *EncryptedSlotContainer
	client          *whatsmeow.Client
	historyProc     *synchronization.Processor
	backoff         *BackoffPolicy
	state           events.AccountState
	ownJID          string
	phone           string
	pushName        string
	lastErr         string
	manualStop      bool
	reconnectCancel context.CancelFunc
	qrCancel        context.CancelFunc
	qrReadyCh       chan struct{}

	lidMu        sync.Mutex
	emittedPairs map[string]string // lidJID -> pnJID emitted to Android for this account
}

// Manager manages the four independent WhatsApp account slots (1..4).
type Manager struct {
	sessionsRoot string
	mediaRoot    string
	router       *events.Router
	slots        [events.MaxAccountID]*AccountSlot
	networkUp    atomic.Bool
	mediaSem     chan struct{}
}

// NewManager creates an isolated 4-account SessionManager.
func NewManager(sessionsRoot, mediaRoot string, router *events.Router) *Manager {
	ensureDevicePropsInitialized()
	_ = os.MkdirAll(sessionsRoot, 0o700)
	_ = os.MkdirAll(mediaRoot, 0o700)

	m := &Manager{
		sessionsRoot: sessionsRoot,
		mediaRoot:    mediaRoot,
		router:       router,
		mediaSem:     make(chan struct{}, 3),
	}
	m.networkUp.Store(true)

	for i := 0; i < events.MaxAccountID; i++ {
		acctID := i + 1
		slotSessionsDir := filepath.Join(sessionsRoot, fmt.Sprintf("account_%d", acctID))
		slotMediaDir := filepath.Join(mediaRoot, fmt.Sprintf("account_%d", acctID))
		hist := synchronization.NewProcessor(acctID, router)
		slot := &AccountSlot{
			mgr:          m,
			accountID:    acctID,
			sessionsDir:  slotSessionsDir,
			mediaDir:     slotMediaDir,
			router:       router,
			mediaSem:     m.mediaSem,
			historyProc:  hist,
			backoff:      NewBackoffPolicy(DefaultInitialDelay, DefaultMaxDelay, DefaultMaxAttempts),
			state:        events.StateNotConnected,
			emittedPairs: make(map[string]string, 256),
		}
		hist.SetLIDMappingSink(func(lidJID, pnJID string) {
			slot.recordVerifiedLIDMapping(lidJID, pnJID, "history_sync", false)
		})
		m.slots[i] = slot
	}
	return m
}

// Slot returns the isolated AccountSlot for accountID (1..4), or an error if out of range.
func (m *Manager) Slot(accountID int) (*AccountSlot, error) {
	if !events.ValidAccountID(accountID) {
		return nil, fmt.Errorf("invalid account_id %d: must be 1..4", accountID)
	}
	return m.slots[accountID-1], nil
}

// SetSlotKey configures the Android Keystore-unwrapped 256-bit AES-GCM key for one account slot.
func (m *Manager) SetSlotKey(accountID int, keyHex string) error {
	slot, err := m.Slot(accountID)
	if err != nil {
		return err
	}
	sc, err := NewSlotCipher(accountID, keyHex)
	if err != nil {
		return err
	}
	slot.mu.Lock()
	slot.cipher = sc
	slot.mu.Unlock()
	return nil
}

func (s *AccountSlot) dbFilePath() string {
	return filepath.Join(s.sessionsDir, "session.db")
}

func (s *AccountSlot) setStateLocked(next events.AccountState, errMsg string) {
	s.state = next
	s.lastErr = errMsg
	s.router.Emit(events.Envelope{
		Type:             events.EventAccountState,
		AccountID:        s.accountID,
		State:            next,
		JID:              s.ownJID,
		Phone:            s.phone,
		PushName:         s.pushName,
		ErrorMessage:     errMsg,
		ReconnectAttempt: s.backoff.Attempts(),
	})
}

func (s *AccountSlot) cancelReconnectLocked() {
	if s.reconnectCancel != nil {
		s.reconnectCancel()
		s.reconnectCancel = nil
	}
}

func (s *AccountSlot) cancelQRLocked() {
	if s.qrCancel != nil {
		s.qrCancel()
		s.qrCancel = nil
	}
}

func (s *AccountSlot) ensureClientLocked(ctx context.Context) (*whatsmeow.Client, bool, error) {
	if s.cipher == nil {
		return nil, false, errors.New("account encryption key not initialized from Android Keystore")
	}
	if s.client != nil && s.container != nil {
		hasID := s.client.Store != nil && s.client.Store.ID != nil
		return s.client, hasID, nil
	}

	logger := events.NewAccountLogger(s.router, s.accountID, fmt.Sprintf("Acct%d", s.accountID))
	container, err := OpenEncryptedSlotContainer(ctx, s.dbFilePath(), s.accountID, s.cipher, logger.Sub("DB"))
	if err != nil {
		return nil, false, err
	}
	dev, err := container.GetOrCreateDevice(ctx)
	if err != nil {
		_ = container.Close()
		return nil, false, err
	}

	cli := whatsmeow.NewClient(dev, logger.Sub("WA"))
	cli.EnableAutoReconnect = false
	cli.AutoTrustIdentity = true

	acctID := s.accountID
	cli.AddEventHandler(func(rawEvt any) {
		s.handleWhatsmeowEvent(acctID, rawEvt)
	})

	s.container = container
	s.client = cli
	if dev.ID != nil {
		s.ownJID = dev.ID.ToNonAD().String()
		s.phone = dev.ID.User
		s.pushName = dev.PushName
		return cli, true, nil
	}
	return cli, false, nil
}

// recordVerifiedLIDMapping persists a verified (lidJID, pnJID) pair into this account's
// whatsmeow LIDStore and emits EventIdentityMapping once per newly discovered pair.
func (s *AccountSlot) recordVerifiedLIDMapping(lidStr, pnStr, source string, emitEvent bool) {
	if !strings.HasSuffix(lidStr, "@lid") || !strings.HasSuffix(pnStr, "@s.whatsapp.net") {
		return
	}
	s.lidMu.Lock()
	already := s.emittedPairs[lidStr] == pnStr
	s.emittedPairs[lidStr] = pnStr
	s.lidMu.Unlock()

	s.mu.Lock()
	cli := s.client
	s.mu.Unlock()

	if cli != nil && cli.Store != nil && cli.Store.LIDs != nil {
		lidJID, err1 := types.ParseJID(lidStr)
		pnJID, err2 := types.ParseJID(pnStr)
		if err1 == nil && err2 == nil {
			ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
			_ = cli.Store.LIDs.PutLIDMapping(ctx, lidJID.ToNonAD(), pnJID.ToNonAD())
			cancel()
		}
	}

	if emitEvent && !already {
		s.router.Emit(events.Envelope{
			Type:          events.EventIdentityMapping,
			AccountID:     s.accountID,
			ChatJID:       pnStr,
			RawChatJID:    lidStr,
			LidJID:        lidStr,
			PnJID:         pnStr,
			MappingSource: source,
		})
	}
}

// resolvePeerIdentity resolves a 1:1 peer JID and optional alternate JID strictly within s.accountID.
// When a verified @s.whatsapp.net PN exists (either via stanza alt JID or whatsmeow's LIDStore),
// canonical returns the @s.whatsapp.net JID while preserving raw, lidJID, and pnJID.
// If only @lid is known, canonical remains @lid (never fabricating a fake phone number).
func (s *AccountSlot) resolvePeerIdentity(primary, alt types.JID, source string) (canonical, raw, lidStr, pnStr string) {
	p := primary.ToNonAD()
	if p.IsEmpty() {
		return "", "", "", ""
	}
	raw = p.String()

	// Group or broadcast JIDs are never LID/PN user identities.
	if p.Server == types.GroupServer || p.Server == types.BroadcastServer || p.Server == types.NewsletterServer {
		return raw, raw, "", ""
	}

	a := alt.ToNonAD()
	if p.Server == types.HiddenUserServer || strings.HasSuffix(raw, "@lid") {
		lidStr = raw
	} else if p.Server == types.DefaultUserServer || strings.HasSuffix(raw, "@s.whatsapp.net") {
		pnStr = raw
	}

	if !a.IsEmpty() {
		aStr := a.String()
		if (a.Server == types.HiddenUserServer || strings.HasSuffix(aStr, "@lid")) && lidStr == "" {
			lidStr = aStr
		} else if (a.Server == types.DefaultUserServer || strings.HasSuffix(aStr, "@s.whatsapp.net")) && pnStr == "" {
			pnStr = aStr
		}
	}

	s.mu.Lock()
	cli := s.client
	s.mu.Unlock()

	if cli != nil && cli.Store != nil && cli.Store.LIDs != nil {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		if lidStr != "" && pnStr == "" {
			if lidParsed, err := types.ParseJID(lidStr); err == nil {
				if resolvedPN, lErr := cli.Store.LIDs.GetPNForLID(ctx, lidParsed.ToNonAD()); lErr == nil && !resolvedPN.IsEmpty() {
					pnStr = resolvedPN.ToNonAD().String()
				}
			}
		} else if pnStr != "" && lidStr == "" {
			if pnParsed, err := types.ParseJID(pnStr); err == nil {
				if resolvedLID, lErr := cli.Store.LIDs.GetLIDForPN(ctx, pnParsed.ToNonAD()); lErr == nil && !resolvedLID.IsEmpty() {
					lidStr = resolvedLID.ToNonAD().String()
				}
			}
		}
		cancel()
	}

	if lidStr != "" && pnStr != "" {
		s.recordVerifiedLIDMapping(lidStr, pnStr, source, true)
		return pnStr, raw, lidStr, pnStr
	}
	if pnStr != "" {
		return pnStr, raw, lidStr, pnStr
	}
	return raw, raw, lidStr, pnStr
}

// Connect starts QR pairing (if unlinked) or restores an existing encrypted session (if paired).
func (m *Manager) Connect(accountID int) error {
	slot, err := m.Slot(accountID)
	if err != nil {
		return err
	}
	return slot.connect(m)
}

func (s *AccountSlot) connect(mgr *Manager) error {
	s.mu.Lock()
	s.manualStop = false
	s.cancelReconnectLocked()
	s.cancelQRLocked()

	if s.client != nil && s.client.IsConnected() {
		s.setStateLocked(events.StateConnected, "")
		s.mu.Unlock()
		return nil
	}

	s.setStateLocked(events.StateConnecting, "")
	cli, alreadyPaired, err := s.ensureClientLocked(context.Background())
	if err != nil {
		s.setStateLocked(events.StateConnectionError, sanitizeUserError(err))
		s.mu.Unlock()
		return err
	}

	if !alreadyPaired {
		qrCtx, qrCancel := context.WithCancel(context.Background())
		s.qrCancel = qrCancel
		qrReady := make(chan struct{})
		s.qrReadyCh = qrReady

		qrChan, qrErr := cli.GetQRChannel(qrCtx)
		if qrErr != nil {
			qrCancel()
			s.setStateLocked(events.StateConnectionError, sanitizeUserError(qrErr))
			s.mu.Unlock()
			return qrErr
		}

		if connErr := cli.Connect(); connErr != nil {
			qrCancel()
			s.setStateLocked(events.StateConnectionError, sanitizeUserError(connErr))
			s.mu.Unlock()
			return connErr
		}

		go s.consumeQRChannel(qrChan, qrReady)
		s.mu.Unlock()
		return nil
	}

	s.mu.Unlock()
	if connErr := cli.Connect(); connErr != nil {
		s.mu.Lock()
		s.setStateLocked(events.StateConnectionError, sanitizeUserError(connErr))
		s.mu.Unlock()
		s.scheduleReconnect(mgr, sanitizeUserError(connErr))
		return connErr
	}
	return nil
}

func (s *AccountSlot) consumeQRChannel(qrChan <-chan whatsmeow.QRChannelItem, qrReady chan struct{}) {
	readyClosed := false
	closeReady := func() {
		if !readyClosed {
			readyClosed = true
			close(qrReady)
		}
	}
	defer closeReady()

	for item := range qrChan {
		switch item.Event {
		case whatsmeow.QRChannelEventCode:
			closeReady()
			s.mu.Lock()
			if s.state != events.StatePairing && s.state != events.StateConnected {
				s.state = events.StateWaitingForQR
				s.router.Emit(events.Envelope{
					Type:      events.EventAccountState,
					AccountID: s.accountID,
					State:     events.StateWaitingForQR,
				})
			}
			s.mu.Unlock()

			expSec := int(item.Timeout.Seconds())
			if expSec <= 0 {
				expSec = 20
			}
			s.router.Emit(events.Envelope{
				Type:         events.EventQRCode,
				AccountID:    s.accountID,
				QRCode:       item.Code,
				ExpiresInSec: expSec,
			})

		case whatsmeow.QRChannelSuccess.Event:
			s.mu.Lock()
			s.backoff.Reset()
			if s.client != nil && s.client.Store != nil && s.client.Store.ID != nil {
				s.ownJID = s.client.Store.ID.ToNonAD().String()
				s.phone = s.client.Store.ID.User
				s.pushName = s.client.Store.PushName
			}
			s.setStateLocked(events.StatePairing, "")
			s.mu.Unlock()

		case whatsmeow.QRChannelTimeout.Event:
			s.mu.Lock()
			if s.state != events.StateConnected {
				s.setStateLocked(events.StateDisconnected, "QR pairing timed out. Tap Connect to generate a fresh QR code.")
			}
			s.mu.Unlock()

		case whatsmeow.QRChannelScannedWithoutMultidevice.Event:
			s.mu.Lock()
			s.setStateLocked(events.StateConnectionError, "Multi-device must be enabled on the primary WhatsApp phone.")
			s.mu.Unlock()

		case whatsmeow.QRChannelClientOutdated.Event:
			s.mu.Lock()
			s.setStateLocked(events.StateConnectionError, "Protocol version rejected by WhatsApp server (client outdated).")
			s.mu.Unlock()

		case whatsmeow.QRChannelEventError:
			s.mu.Lock()
			errMsg := "Pairing failed"
			if item.Error != nil {
				errMsg = sanitizeUserError(item.Error)
			}
			s.setStateLocked(events.StateConnectionError, errMsg)
			s.mu.Unlock()
		}
	}
}

var nonDigitsRe = regexp.MustCompile(`[^0-9]+`)

// RequestPairingCode initiates phone-number linking for an unlinked slot using whatsmeow.Client.PairPhone.
func (m *Manager) RequestPairingCode(accountID int, rawPhone string) (string, error) {
	slot, err := m.Slot(accountID)
	if err != nil {
		return "", err
	}
	phoneDigits := nonDigitsRe.ReplaceAllString(rawPhone, "")
	if len(phoneDigits) < 7 || len(phoneDigits) > 15 {
		return "", errors.New("enter a valid international phone number with country code (7-15 digits)")
	}

	slot.mu.Lock()
	needConnect := slot.client == nil || !slot.client.IsConnected()
	slot.mu.Unlock()

	if needConnect {
		if err := slot.connect(m); err != nil {
			return "", err
		}
	}

	slot.mu.Lock()
	cli := slot.client
	qrReady := slot.qrReadyCh
	if cli != nil && cli.Store != nil && cli.Store.ID != nil {
		slot.mu.Unlock()
		return "", errors.New("this account slot is already paired; remove it first to pair a different phone")
	}
	slot.mu.Unlock()

	if cli == nil {
		return "", errors.New("client unavailable")
	}

	if qrReady != nil {
		select {
		case <-qrReady:
		case <-time.After(12 * time.Second):
			return "", errors.New("timed out waiting for WhatsApp pairing handshake")
		}
	}

	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()

	code, err := cli.PairPhone(ctx, phoneDigits, true, whatsmeow.PairClientChrome, "Chrome (Linux)")
	if err != nil {
		slot.mu.Lock()
		slot.setStateLocked(events.StateConnectionError, sanitizeUserError(err))
		slot.mu.Unlock()
		return "", err
	}

	slot.mu.Lock()
	slot.phone = phoneDigits
	slot.setStateLocked(events.StatePairing, "")
	slot.mu.Unlock()

	m.router.Emit(events.Envelope{
		Type:        events.EventPairingCode,
		AccountID:   accountID,
		Phone:       phoneDigits,
		PairingCode: code,
	})
	return code, nil
}

// Disconnect cleanly closes the WebSocket for one account without deleting its saved session.
func (m *Manager) Disconnect(accountID int) error {
	slot, err := m.Slot(accountID)
	if err != nil {
		return err
	}
	slot.mu.Lock()
	defer slot.mu.Unlock()

	slot.manualStop = true
	slot.cancelReconnectLocked()
	slot.cancelQRLocked()
	slot.backoff.Reset()
	if slot.client != nil {
		slot.client.Disconnect()
	}
	slot.setStateLocked(events.StateDisconnected, "")
	return nil
}

// RemoveAccount logs out on WhatsApp (if connected), closes and deletes the isolated SQLite
// session database and cached media for ONLY this account slot, resetting it to NotConnected.
func (m *Manager) RemoveAccount(accountID int) error {
	slot, err := m.Slot(accountID)
	if err != nil {
		return err
	}
	slot.mu.Lock()
	slot.manualStop = true
	slot.cancelReconnectLocked()
	slot.cancelQRLocked()
	slot.backoff.Reset()
	cli := slot.client
	cont := slot.container
	slot.client = nil
	slot.container = nil
	slot.mu.Unlock()

	if cli != nil {
		if cli.IsConnected() && cli.Store != nil && cli.Store.ID != nil {
			ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
			_ = cli.Logout(ctx)
			cancel()
		}
		cli.RemoveEventHandlers()
		cli.Disconnect()
	}
	if cont != nil {
		_ = cont.Close()
	}

	_ = os.RemoveAll(slot.sessionsDir)
	_ = os.RemoveAll(slot.mediaDir)
	slot.historyProc.Reset()

	slot.lidMu.Lock()
	slot.emittedPairs = make(map[string]string, 64)
	slot.lidMu.Unlock()

	slot.mu.Lock()
	slot.ownJID = ""
	slot.phone = ""
	slot.pushName = ""
	slot.setStateLocked(events.StateNotConnected, "")
	slot.mu.Unlock()
	return nil
}

// OnNetworkChanged notifies all four isolated slots of Android network transitions.
func (m *Manager) OnNetworkChanged(available bool) {
	wasUp := m.networkUp.Swap(available)
	if !available {
		return
	}
	for i := 0; i < events.MaxAccountID; i++ {
		slot := m.slots[i]
		slot.mu.Lock()
		hasPaired := (slot.client != nil && slot.client.Store != nil && slot.client.Store.ID != nil) || slot.hasSessionFileLocked()
		shouldResume := !slot.manualStop && hasPaired &&
			(slot.state == events.StateReconnecting || slot.state == events.StateConnectionError || (!wasUp && slot.state == events.StateDisconnected))
		if shouldResume {
			slot.backoff.Reset()
			slot.cancelReconnectLocked()
		}
		slot.mu.Unlock()

		if shouldResume {
			go func(acct int) {
				_ = m.Connect(acct)
			}(slot.accountID)
		}
	}
}

func (s *AccountSlot) hasSessionFileLocked() bool {
	info, err := os.Stat(s.dbFilePath())
	return err == nil && info.Size() > 0
}

func (s *AccountSlot) scheduleReconnect(mgr *Manager, reason string) {
	s.mu.Lock()
	if s.manualStop || s.state == events.StateAuthExpired || s.state == events.StateNotConnected {
		s.mu.Unlock()
		return
	}
	hasPaired := s.client != nil && s.client.Store != nil && s.client.Store.ID != nil
	if !hasPaired {
		s.setStateLocked(events.StateDisconnected, reason)
		s.mu.Unlock()
		return
	}

	s.cancelReconnectLocked()
	if mgr != nil && !mgr.networkUp.Load() {
		s.setStateLocked(events.StateReconnecting, "Waiting for network connectivity...")
		s.mu.Unlock()
		return
	}

	delay, attempt, allowed := s.backoff.NextDelay(true)
	if !allowed {
		s.setStateLocked(events.StateConnectionError, "Automatic reconnection paused after repeated failures. Tap Reconnect to retry.")
		s.mu.Unlock()
		return
	}

	ctx, cancel := context.WithCancel(context.Background())
	s.reconnectCancel = cancel
	s.state = events.StateReconnecting
	s.lastErr = reason
	s.router.Emit(events.Envelope{
		Type:             events.EventAccountState,
		AccountID:        s.accountID,
		State:            events.StateReconnecting,
		JID:              s.ownJID,
		Phone:            s.phone,
		PushName:         s.pushName,
		ErrorMessage:     reason,
		ReconnectAttempt: attempt,
	})
	s.mu.Unlock()

	go func() {
		timer := time.NewTimer(delay)
		defer timer.Stop()
		select {
		case <-ctx.Done():
			return
		case <-timer.C:
		}
		_ = s.connect(mgr)
	}()
}

func (s *AccountSlot) handleWhatsmeowEvent(accountID int, rawEvt any) {
	if accountID != s.accountID {
		return
	}

	switch evt := rawEvt.(type) {
	case *waEvents.PairSuccess:
		s.mu.Lock()
		s.ownJID = evt.ID.ToNonAD().String()
		s.phone = evt.ID.User
		s.backoff.Reset()
		s.setStateLocked(events.StatePairing, "")
		s.mu.Unlock()

	case *waEvents.Connected:
		s.mu.Lock()
		s.backoff.Reset()
		s.cancelReconnectLocked()
		if s.client != nil && s.client.Store != nil && s.client.Store.ID != nil {
			s.ownJID = s.client.Store.ID.ToNonAD().String()
			s.phone = s.client.Store.ID.User
			s.pushName = s.client.Store.PushName
		}
		cli := s.client
		s.setStateLocked(events.StateConnected, "")
		s.mu.Unlock()

		if cli != nil {
			_ = cli.SendPresence(context.Background(), types.PresenceAvailable)
			go s.syncContactsAndGroups()
		}

	case *waEvents.Disconnected:
		s.scheduleReconnect(s.mgr, "Connection interrupted")

	case *waEvents.LoggedOut:
		s.mu.Lock()
		s.manualStop = true
		s.cancelReconnectLocked()
		s.setStateLocked(events.StateAuthExpired, "Session unlinked or expired on primary phone. Please re-pair this account.")
		s.mu.Unlock()

	case *waEvents.ConnectFailure:
		if evt.Reason.IsLoggedOut() {
			s.mu.Lock()
			s.manualStop = true
			s.cancelReconnectLocked()
			s.setStateLocked(events.StateAuthExpired, "Authentication expired ("+evt.Reason.String()+"). Please re-pair.")
			s.mu.Unlock()
		} else {
			s.scheduleReconnect(s.mgr, "Connect failure: "+evt.Reason.String())
		}

	case *waEvents.TemporaryBan:
		s.mu.Lock()
		s.manualStop = true
		s.cancelReconnectLocked()
		s.setStateLocked(events.StateConnectionError, fmt.Sprintf("WhatsApp temporary restriction reported (code %s, expire %v).", evt.Code.String(), evt.Expire))
		s.mu.Unlock()

	case *waEvents.Message:
		s.processIncomingMessage(evt)

	case *waEvents.Receipt:
		s.processReceipt(evt)

	case *waEvents.ChatPresence:
		canonicalChat, rawChat, lidJID, pnJID := s.resolvePeerIdentity(evt.Chat, types.EmptyJID, "chat_presence")
		senderCanon, _, _, _ := s.resolvePeerIdentity(evt.Sender, types.EmptyJID, "chat_presence_sender")
		s.router.Emit(events.Envelope{
			Type:       events.EventTyping,
			AccountID:  s.accountID,
			ChatJID:    canonicalChat,
			RawChatJID: rawChat,
			LidJID:     lidJID,
			PnJID:      pnJID,
			SenderJID:  senderCanon,
			IsTyping:   evt.State == types.ChatPresenceComposing,
			MediaKind:  string(evt.Media),
		})

	case *waEvents.Presence:
		lastSeenMs := int64(0)
		if !evt.LastSeen.IsZero() {
			lastSeenMs = evt.LastSeen.UnixMilli()
		}
		canonicalFrom, _, lidJID, pnJID := s.resolvePeerIdentity(evt.From, types.EmptyJID, "presence")
		s.router.Emit(events.Envelope{
			Type:       events.EventPresence,
			AccountID:  s.accountID,
			JID:        canonicalFrom,
			LidJID:     lidJID,
			PnJID:      pnJID,
			IsOnline:   !evt.Unavailable,
			LastSeenMs: lastSeenMs,
		})

	case *waEvents.HistorySync:
		if evt.Data != nil {
			go s.historyProc.ProcessSyncData(evt.Data)
		}

	case *waEvents.JoinedGroup:
		go s.syncContactsAndGroups()

	case *waEvents.PushName:
		if !evt.JID.IsEmpty() && evt.NewPushName != "" {
			canonJID, _, lidJID, pnJID := s.resolvePeerIdentity(evt.JID, types.EmptyJID, "push_name")
			s.router.Emit(events.Envelope{
				Type:      events.EventContactsBatch,
				AccountID: s.accountID,
				Contacts: []events.ContactDTO{
					{
						JID:      canonJID,
						LidJID:   lidJID,
						PnJID:    pnJID,
						PushName: evt.NewPushName,
					},
				},
			})
		}
	}
}

func (s *AccountSlot) processIncomingMessage(evt *waEvents.Message) {
	if evt == nil {
		return
	}
	rawChatPrimary := evt.Info.Chat
	// When a message is sent from the primary WhatsApp Messenger phone, DeviceSentMeta or
	// DeviceSentMessage.DestinationJID identifies the target chat if Info.Chat points to self.
	if evt.Info.DeviceSentMeta != nil && evt.Info.DeviceSentMeta.DestinationJID != "" {
		if destJID, err := types.ParseJID(evt.Info.DeviceSentMeta.DestinationJID); err == nil && !destJID.IsEmpty() {
			s.mu.Lock()
			own := s.ownJID
			s.mu.Unlock()
			if rawChatPrimary.IsEmpty() || rawChatPrimary.ToNonAD().String() == own {
				rawChatPrimary = destJID
			}
		}
	}
	if dsm := evt.Message.GetDeviceSentMessage(); dsm != nil && dsm.GetDestinationJID() != "" {
		if destJID, err := types.ParseJID(dsm.GetDestinationJID()); err == nil && !destJID.IsEmpty() {
			s.mu.Lock()
			own := s.ownJID
			s.mu.Unlock()
			if rawChatPrimary.IsEmpty() || rawChatPrimary.ToNonAD().String() == own {
				rawChatPrimary = destJID
			}
		}
	}

	if rawChatPrimary.IsEmpty() {
		return
	}
	nonADChat := rawChatPrimary.ToNonAD().String()
	if nonADChat == "status@broadcast" || strings.HasSuffix(nonADChat, "@broadcast") {
		return
	}

	// Determine alternate JID for 1:1 conversations from whatsmeow MessageSource
	chatAlt := evt.Info.RecipientAlt
	if !evt.Info.IsGroup && chatAlt.IsEmpty() && !evt.Info.IsFromMe {
		chatAlt = evt.Info.SenderAlt
	}
	chatJID, rawChatJID, lidJID, pnJID := s.resolvePeerIdentity(rawChatPrimary, chatAlt, "message")
	senderJID, _, senderLidJID, senderPnJID := s.resolvePeerIdentity(evt.Info.Sender, evt.Info.SenderAlt, "message_sender")

	msgID := string(evt.Info.ID)
	tsMs := evt.Info.Timestamp.UnixMilli()
	if tsMs <= 0 {
		tsMs = time.Now().UnixMilli()
	}

	if evt.Info.Edit == types.EditAttributeSenderRevoke || evt.Info.Edit == types.EditAttributeAdminRevoke {
		targetID := msgID
		if pm := evt.Message.GetProtocolMessage(); pm != nil && pm.GetKey().GetID() != "" {
			targetID = pm.GetKey().GetID()
		}
		s.router.Emit(events.Envelope{
			Type:            events.EventMessageRevoked,
			AccountID:       s.accountID,
			ChatJID:         chatJID,
			RawChatJID:      rawChatJID,
			LidJID:          lidJID,
			PnJID:           pnJID,
			TargetMessageID: targetID,
			SenderJID:       senderJID,
			TimestampMs:     tsMs,
		})
		return
	}

	parsed := messaging.ParseE2EMessage(evt.Message)
	switch parsed.Kind {
	case messaging.KindIgnore:
		return

	case messaging.KindReaction:
		s.router.Emit(events.Envelope{
			Type:            events.EventReaction,
			AccountID:       s.accountID,
			ChatJID:         chatJID,
			RawChatJID:      rawChatJID,
			LidJID:          lidJID,
			PnJID:           pnJID,
			TargetMessageID: parsed.TargetMessageID,
			SenderJID:       senderJID,
			Emoji:           parsed.ReactionEmoji,
			TimestampMs:     tsMs,
		})

	case messaging.KindRevoke:
		s.router.Emit(events.Envelope{
			Type:            events.EventMessageRevoked,
			AccountID:       s.accountID,
			ChatJID:         chatJID,
			RawChatJID:      rawChatJID,
			LidJID:          lidJID,
			PnJID:           pnJID,
			TargetMessageID: parsed.TargetMessageID,
			SenderJID:       senderJID,
			TimestampMs:     tsMs,
		})

	case messaging.KindEdit, messaging.KindMessage:
		status := "delivered"
		if evt.Info.IsFromMe {
			status = "sent"
		}
		// Keep sender PushName fresh in contacts batch for 1:1 and group senders when non-empty
		if !evt.Info.IsFromMe && strings.TrimSpace(evt.Info.PushName) != "" && senderJID != "" {
			s.router.Emit(events.Envelope{
				Type:      events.EventContactsBatch,
				AccountID: s.accountID,
				Contacts: []events.ContactDTO{
					{
						JID:      senderJID,
						LidJID:   senderLidJID,
						PnJID:    senderPnJID,
						PushName: strings.TrimSpace(evt.Info.PushName),
					},
				},
			})
		}

		env := events.Envelope{
			Type:             events.EventMessage,
			AccountID:        s.accountID,
			ChatJID:          chatJID,
			RawChatJID:       rawChatJID,
			LidJID:           lidJID,
			PnJID:            pnJID,
			SenderLidJID:     senderLidJID,
			SenderPnJID:      senderPnJID,
			MessageID:        msgID,
			CompositeChatKey: events.CompositeChatKey(s.accountID, chatJID),
			CompositeMsgKey:  events.CompositeMessageKey(s.accountID, chatJID, msgID),
			SenderJID:        senderJID,
			SenderName:       evt.Info.PushName,
			IsFromMe:         evt.Info.IsFromMe,
			IsGroup:          evt.Info.IsGroup,
			Text:             parsed.Text,
			MediaType:        parsed.MediaType,
			MimeType:         parsed.MimeType,
			FileName:         parsed.FileName,
			FileSize:         parsed.FileSize,
			DurationSec:      parsed.DurationSec,
			IsVoiceNote:      parsed.IsVoiceNote,
			DirectPath:       parsed.DirectPath,
			MediaKeyB64:      parsed.MediaKeyB64,
			FileSHA256B64:    parsed.FileSHA256B64,
			FileEncSHA256B64: parsed.FileEncSHA256B64,
			QuotedMessageID:  parsed.QuotedMessageID,
			QuotedSenderJID:  parsed.QuotedSenderJID,
			QuotedText:       parsed.QuotedText,
			EditTargetID:     parsed.TargetMessageID,
			Status:           status,
			TimestampMs:      tsMs,
		}
		s.router.Emit(env)

		if parsed.MediaType != "" && parsed.DirectPath != "" && parsed.MediaKeyB64 != "" && parsed.FileSize <= 16*1024*1024 {
			go s.autoDownloadMedia(chatJID, rawChatJID, msgID, parsed)
		}
	}
}

func (s *AccountSlot) autoDownloadMedia(chatJID, rawChatJID, msgID string, parsed messaging.ParsedMessage) {
	s.mediaSem <- struct{}{}
	defer func() { <-s.mediaSem }()

	s.mu.Lock()
	cli := s.client
	mediaDir := s.mediaDir
	s.mu.Unlock()
	if cli == nil {
		return
	}

	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	localPath, err := messaging.DownloadMediaByMetadata(
		ctx, cli, mediaDir, msgID,
		parsed.MediaType, parsed.MimeType, parsed.FileName,
		parsed.DirectPath, parsed.MediaKeyB64, parsed.FileSHA256B64, parsed.FileEncSHA256B64,
	)
	errMsg := ""
	if err != nil {
		errMsg = sanitizeUserError(err)
	}
	s.router.Emit(events.Envelope{
		Type:         events.EventMediaDownloaded,
		AccountID:    s.accountID,
		ChatJID:      chatJID,
		RawChatJID:   rawChatJID,
		MessageID:    msgID,
		MediaPath:    localPath,
		ErrorMessage: errMsg,
	})
}

func (s *AccountSlot) processReceipt(evt *waEvents.Receipt) {
	if evt == nil || len(evt.MessageIDs) == 0 {
		return
	}
	var receiptStatus string
	switch evt.Type {
	case types.ReceiptTypeRead, types.ReceiptTypeReadSelf, types.ReceiptTypePlayed, types.ReceiptTypePlayedSelf:
		receiptStatus = "read"
	case types.ReceiptTypeDelivered, types.ReceiptTypeSender:
		receiptStatus = "delivered"
	default:
		return
	}
	ids := make([]string, 0, len(evt.MessageIDs))
	for _, id := range evt.MessageIDs {
		ids = append(ids, string(id))
	}
	chatAlt := evt.RecipientAlt
	if chatAlt.IsEmpty() && !evt.IsGroup {
		chatAlt = evt.SenderAlt
	}
	chatJID, rawChatJID, lidJID, pnJID := s.resolvePeerIdentity(evt.Chat, chatAlt, "receipt")
	s.router.Emit(events.Envelope{
		Type:          events.EventReceipt,
		AccountID:     s.accountID,
		ChatJID:       chatJID,
		RawChatJID:    rawChatJID,
		LidJID:        lidJID,
		PnJID:         pnJID,
		MessageIDs:    ids,
		ReceiptStatus: receiptStatus,
		TimestampMs:   evt.Timestamp.UnixMilli(),
	})
}

func (s *AccountSlot) syncContactsAndGroups() {
	if contacts, err := s.fetchContacts(); err == nil && len(contacts) > 0 {
		s.router.Emit(events.Envelope{
			Type:      events.EventContactsBatch,
			AccountID: s.accountID,
			Contacts:  contacts,
		})
	}
	if groups, err := s.fetchGroups(); err == nil && len(groups) > 0 {
		s.router.Emit(events.Envelope{
			Type:      events.EventGroupsBatch,
			AccountID: s.accountID,
			Groups:    groups,
		})
	}
}

func (s *AccountSlot) fetchContacts() ([]events.ContactDTO, error) {
	s.mu.Lock()
	cli := s.client
	s.mu.Unlock()
	if cli == nil || cli.Store == nil || cli.Store.Contacts == nil {
		return nil, errors.New("contacts store unavailable")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()

	rawMap, err := cli.Store.Contacts.GetAllContacts(ctx)
	if err != nil {
		return nil, err
	}
	out := make([]events.ContactDTO, 0, len(rawMap))
	for jid, info := range rawMap {
		canon, _, lidJID, pnJID := s.resolvePeerIdentity(jid, types.EmptyJID, "contacts_store")
		if canon == "" {
			continue
		}
		out = append(out, events.ContactDTO{
			JID:          canon,
			LidJID:       lidJID,
			PnJID:        pnJID,
			FullName:     info.FullName,
			FirstName:    info.FirstName,
			PushName:     info.PushName,
			BusinessName: info.BusinessName,
		})
	}
	return out, nil
}

func (s *AccountSlot) fetchGroups() ([]events.GroupDTO, error) {
	s.mu.Lock()
	cli := s.client
	s.mu.Unlock()
	if cli == nil || !cli.IsConnected() {
		return nil, errors.New("account client not connected")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()

	joined, err := cli.GetJoinedGroups(ctx)
	if err != nil {
		return nil, err
	}
	out := make([]events.GroupDTO, 0, len(joined))
	for _, g := range joined {
		if g == nil {
			continue
		}
		out = append(out, mapGroupInfo(g))
	}
	return out, nil
}

func mapGroupInfo(g *types.GroupInfo) events.GroupDTO {
	members := make([]events.GroupMemberDTO, 0, len(g.Participants))
	for _, p := range g.Participants {
		phoneJID := ""
		if !p.PhoneNumber.IsEmpty() {
			phoneJID = p.PhoneNumber.ToNonAD().String()
		}
		members = append(members, events.GroupMemberDTO{
			JID:          p.JID.ToNonAD().String(),
			PhoneJID:     phoneJID,
			IsAdmin:      p.IsAdmin,
			IsSuperAdmin: p.IsSuperAdmin,
		})
	}
	createdMs := int64(0)
	if !g.GroupCreated.IsZero() {
		createdMs = g.GroupCreated.UnixMilli()
	}
	return events.GroupDTO{
		JID:              g.JID.ToNonAD().String(),
		Name:             g.Name,
		Topic:            g.Topic,
		OwnerJID:         g.OwnerJID.ToNonAD().String(),
		ParticipantCount: len(g.Participants),
		IsAnnounce:       g.IsAnnounce,
		IsLocked:         g.IsLocked,
		CreatedAtMs:      createdMs,
		Participants:     members,
	}
}

// ClientForAccount returns the isolated whatsmeow.Client strictly owned by accountID.
func (m *Manager) ClientForAccount(accountID int) (*whatsmeow.Client, string, error) {
	slot, err := m.Slot(accountID)
	if err != nil {
		return nil, "", err
	}
	slot.mu.Lock()
	defer slot.mu.Unlock()
	if slot.client == nil {
		return nil, slot.mediaDir, errors.New("account session is not initialized")
	}
	return slot.client, slot.mediaDir, nil
}

// GetAccountSnapshot returns a sanitized status snapshot for one account.
func (m *Manager) GetAccountSnapshot(accountID int) (SlotSnapshot, error) {
	slot, err := m.Slot(accountID)
	if err != nil {
		return SlotSnapshot{}, err
	}
	slot.mu.Lock()
	defer slot.mu.Unlock()
	return SlotSnapshot{
		AccountID:        slot.accountID,
		State:            slot.state,
		JID:              slot.ownJID,
		Phone:            slot.phone,
		PushName:         slot.pushName,
		HasSavedSession:  slot.hasSessionFileLocked(),
		ErrorMessage:     slot.lastErr,
		ReconnectAttempt: slot.backoff.Attempts(),
	}, nil
}

// GetAllSnapshotsJSON returns JSON status snapshots for all 4 accounts.
func (m *Manager) GetAllSnapshotsJSON() string {
	snaps := make([]SlotSnapshot, 0, events.MaxAccountID)
	for acct := 1; acct <= events.MaxAccountID; acct++ {
		if snap, err := m.GetAccountSnapshot(acct); err == nil {
			snaps = append(snaps, snap)
		}
	}
	b, err := json.Marshal(snaps)
	if err != nil {
		return "[]"
	}
	return string(b)
}

// GetContactsJSON fetches and serializes contacts strictly for accountID.
func (m *Manager) GetContactsJSON(accountID int) (string, error) {
	slot, err := m.Slot(accountID)
	if err != nil {
		return "[]", err
	}
	list, err := slot.fetchContacts()
	if err != nil {
		return "[]", err
	}
	b, err := json.Marshal(list)
	if err != nil {
		return "[]", err
	}
	return string(b), nil
}

// GetGroupsJSON fetches and serializes joined groups strictly for accountID.
func (m *Manager) GetGroupsJSON(accountID int) (string, error) {
	slot, err := m.Slot(accountID)
	if err != nil {
		return "[]", err
	}
	list, err := slot.fetchGroups()
	if err != nil {
		return "[]", err
	}
	b, err := json.Marshal(list)
	if err != nil {
		return "[]", err
	}
	return string(b), nil
}

// GetGroupInfoJSON fetches detailed metadata & participants for one group under accountID.
func (m *Manager) GetGroupInfoJSON(accountID int, groupJID string) (string, error) {
	cli, _, err := m.ClientForAccount(accountID)
	if err != nil || !cli.IsConnected() {
		return "{}", errors.New("account is not connected")
	}
	jid, err := messaging.ParseRecipientJID(groupJID)
	if err != nil {
		return "{}", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()

	info, err := cli.GetGroupInfo(ctx, jid)
	if err != nil {
		return "{}", err
	}
	dto := mapGroupInfo(info)
	b, err := json.Marshal(dto)
	if err != nil {
		return "{}", err
	}
	return string(b), nil
}

// Shutdown cleanly disconnects all active accounts and checkpoints all SQLite session stores.
func (m *Manager) Shutdown() {
	for i := 0; i < events.MaxAccountID; i++ {
		slot := m.slots[i]
		slot.mu.Lock()
		slot.manualStop = true
		slot.cancelReconnectLocked()
		slot.cancelQRLocked()
		cli := slot.client
		cont := slot.container
		slot.client = nil
		slot.container = nil
		slot.mu.Unlock()

		if cli != nil {
			cli.RemoveEventHandlers()
			cli.Disconnect()
		}
		if cont != nil {
			_ = cont.Close()
		}
	}
}

func sanitizeUserError(err error) string {
	if err == nil {
		return ""
	}
	msg := events.SanitizeLogLine(err.Error())
	if strings.TrimSpace(msg) == "" {
		return "Operation failed"
	}
	return msg
}
