package session

import (
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sync"
	"time"

	_ "github.com/mattn/go-sqlite3"
	"go.mau.fi/whatsmeow/store"
	"go.mau.fi/whatsmeow/store/sqlstore"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/util/keys"
	waLog "go.mau.fi/whatsmeow/util/log"
)

var (
	sealedMagic                 = []byte("WHHUB1")
	errInvalidKeyLength         = errors.New("slot encryption key must be 32 bytes (64 hex chars)")
	errCiphertextAuthentication = errors.New("session store authentication failed (wrong key or corrupted database)")
)

// SlotCipher performs authenticated AES-256-GCM encryption scoped to one account slot.
type SlotCipher struct {
	accountID int
	aead      cipher.AEAD
}

// NewSlotCipher constructs a SlotCipher from a 64-character hex-encoded 256-bit key.
func NewSlotCipher(accountID int, keyHex string) (*SlotCipher, error) {
	raw, err := hex.DecodeString(keyHex)
	if err != nil || len(raw) != 32 {
		return nil, errInvalidKeyLength
	}
	return NewSlotCipherFromBytes(accountID, raw)
}

// NewSlotCipherFromBytes constructs a SlotCipher from a 32-byte key.
func NewSlotCipherFromBytes(accountID int, rawKey []byte) (*SlotCipher, error) {
	if len(rawKey) != 32 {
		return nil, errInvalidKeyLength
	}
	block, err := aes.NewCipher(rawKey)
	if err != nil {
		return nil, err
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	return &SlotCipher{
		accountID: accountID,
		aead:      aead,
	}, nil
}

func (sc *SlotCipher) aadFor(tableScope string) []byte {
	return []byte(fmt.Sprintf("whatshub:acct=%d:scope=%s", sc.accountID, tableScope))
}

// Seal encrypts plaintext with AES-256-GCM and prepends the WHHUB1 header + 12B random nonce.
func (sc *SlotCipher) Seal(tableScope string, plaintext []byte) ([]byte, error) {
	if len(plaintext) == 0 {
		return nil, nil
	}
	nonce := make([]byte, sc.aead.NonceSize())
	if _, err := io.ReadFull(rand.Reader, nonce); err != nil {
		return nil, err
	}
	ciphertext := sc.aead.Seal(nil, nonce, plaintext, sc.aadFor(tableScope))
	out := make([]byte, 0, len(sealedMagic)+len(nonce)+len(ciphertext))
	out = append(out, sealedMagic...)
	out = append(out, nonce...)
	out = append(out, ciphertext...)
	return out, nil
}

// Open verifies and decrypts an AES-256-GCM blob produced by Seal.
func (sc *SlotCipher) Open(tableScope string, blob []byte) ([]byte, error) {
	if len(blob) == 0 {
		return nil, nil
	}
	nonceSize := sc.aead.NonceSize()
	if len(blob) < len(sealedMagic)+nonceSize+sc.aead.Overhead() {
		return nil, errCiphertextAuthentication
	}
	if !bytes.Equal(blob[:len(sealedMagic)], sealedMagic) {
		return nil, errCiphertextAuthentication
	}
	nonce := blob[len(sealedMagic) : len(sealedMagic)+nonceSize]
	ciphertext := blob[len(sealedMagic)+nonceSize:]
	plain, err := sc.aead.Open(nil, nonce, ciphertext, sc.aadFor(tableScope))
	if err != nil {
		return nil, errCiphertextAuthentication
	}
	return plain, nil
}

const initSealedTablesSQL = `
CREATE TABLE IF NOT EXISTS whatshub_sealed_device_keys (
	jid                 TEXT PRIMARY KEY,
	sealed_noise_priv   BLOB NOT NULL,
	sealed_ident_priv   BLOB NOT NULL,
	sealed_spk_priv     BLOB NOT NULL,
	sealed_spk_sig      BLOB NOT NULL,
	sealed_adv_secret   BLOB NOT NULL
);

CREATE TABLE IF NOT EXISTS whatshub_sealed_prekeys (
	jid         TEXT NOT NULL,
	key_id      INTEGER NOT NULL,
	sealed_priv BLOB NOT NULL,
	uploaded    BOOLEAN NOT NULL DEFAULT 0,
	PRIMARY KEY (jid, key_id)
);
`

// EncryptedSlotContainer wraps sqlstore.Container for a single isolated account slot,
// sealing all private keys and Signal session material at rest with AES-256-GCM.
type EncryptedSlotContainer struct {
	accountID int
	dbPath    string
	sqlDB     *sql.DB
	inner     *sqlstore.Container
	cipher    *SlotCipher
	log       waLog.Logger
}

// OpenEncryptedSlotContainer opens (or creates) an isolated SQLite store for one account.
func OpenEncryptedSlotContainer(
	ctx context.Context,
	dbPath string,
	accountID int,
	slotCipher *SlotCipher,
	log waLog.Logger,
) (*EncryptedSlotContainer, error) {
	if slotCipher == nil {
		return nil, errors.New("slotCipher is required")
	}
	if err := os.MkdirAll(filepath.Dir(dbPath), 0o700); err != nil {
		return nil, fmt.Errorf("mkdir session dir: %w", err)
	}
	dsn := fmt.Sprintf("file:%s?_foreign_keys=on&_busy_timeout=5000&_journal_mode=WAL&_synchronous=NORMAL", dbPath)
	sqlDB, err := sql.Open("sqlite3", dsn)
	if err != nil {
		return nil, fmt.Errorf("open sqlite3: %w", err)
	}
	sqlDB.SetMaxOpenConns(1)

	if _, err := sqlDB.ExecContext(ctx, "PRAGMA foreign_keys = ON;"); err != nil {
		_ = sqlDB.Close()
		return nil, fmt.Errorf("enable foreign keys: %w", err)
	}

	inner := sqlstore.NewWithDB(sqlDB, "sqlite3", log)
	if err := inner.Upgrade(ctx); err != nil {
		_ = sqlDB.Close()
		return nil, fmt.Errorf("upgrade whatsmeow schema: %w", err)
	}
	if _, err := sqlDB.ExecContext(ctx, initSealedTablesSQL); err != nil {
		_ = sqlDB.Close()
		return nil, fmt.Errorf("init sealed tables: %w", err)
	}

	return &EncryptedSlotContainer{
		accountID: accountID,
		dbPath:    dbPath,
		sqlDB:     sqlDB,
		inner:     inner,
		cipher:    slotCipher,
		log:       log,
	}, nil
}

// GetOrCreateDevice loads the existing encrypted device identity or creates a fresh unlinked one.
func (ec *EncryptedSlotContainer) GetOrCreateDevice(ctx context.Context) (*store.Device, error) {
	devices, err := ec.inner.GetAllDevices(ctx)
	if err != nil {
		return nil, err
	}
	if len(devices) == 0 {
		dev := ec.inner.NewDevice()
		dev.Container = ec
		return dev, nil
	}

	dev := devices[0]
	if dev.ID == nil {
		dev.Container = ec
		return dev, nil
	}

	// Unseal actual private keys from whatshub_sealed_device_keys
	var sNoise, sIdent, sSPK, sSPKSig, sAdv []byte
	err = ec.sqlDB.QueryRowContext(
		ctx,
		`SELECT sealed_noise_priv, sealed_ident_priv, sealed_spk_priv, sealed_spk_sig, sealed_adv_secret
		 FROM whatshub_sealed_device_keys WHERE jid = ?`,
		dev.ID.String(),
	).Scan(&sNoise, &sIdent, &sSPK, &sSPKSig, &sAdv)
	if err != nil {
		return nil, fmt.Errorf("load sealed device keys: %w", err)
	}

	noisePriv, err := ec.cipher.Open("device_noise", sNoise)
	if err != nil || len(noisePriv) != 32 {
		return nil, errCiphertextAuthentication
	}
	identPriv, err := ec.cipher.Open("device_ident", sIdent)
	if err != nil || len(identPriv) != 32 {
		return nil, errCiphertextAuthentication
	}
	spkPriv, err := ec.cipher.Open("device_spk", sSPK)
	if err != nil || len(spkPriv) != 32 {
		return nil, errCiphertextAuthentication
	}
	spkSig, err := ec.cipher.Open("device_spk_sig", sSPKSig)
	if err != nil || len(spkSig) != 64 {
		return nil, errCiphertextAuthentication
	}
	advSecret, err := ec.cipher.Open("device_adv", sAdv)
	if err != nil {
		return nil, errCiphertextAuthentication
	}

	dev.NoiseKey = keys.NewKeyPairFromPrivateKey(*(*[32]byte)(noisePriv))
	dev.IdentityKey = keys.NewKeyPairFromPrivateKey(*(*[32]byte)(identPriv))
	dev.SignedPreKey.KeyPair = *keys.NewKeyPairFromPrivateKey(*(*[32]byte)(spkPriv))
	dev.SignedPreKey.Signature = (*[64]byte)(spkSig)
	dev.AdvSecretKey = advSecret

	ec.wrapDeviceStores(dev)
	return dev, nil
}

// PutDevice seals private keys into whatshub_sealed_device_keys and writes only
// random 32-byte placeholders into whatsmeow_device so raw private keys never rest on disk.
func (ec *EncryptedSlotContainer) PutDevice(ctx context.Context, device *store.Device) error {
	if device.ID == nil {
		return sqlstore.ErrDeviceIDMustBeSet
	}

	sNoise, err := ec.cipher.Seal("device_noise", device.NoiseKey.Priv[:])
	if err != nil {
		return err
	}
	sIdent, err := ec.cipher.Seal("device_ident", device.IdentityKey.Priv[:])
	if err != nil {
		return err
	}
	sSPK, err := ec.cipher.Seal("device_spk", device.SignedPreKey.Priv[:])
	if err != nil {
		return err
	}
	sSPKSig, err := ec.cipher.Seal("device_spk_sig", device.SignedPreKey.Signature[:])
	if err != nil {
		return err
	}
	sAdv, err := ec.cipher.Seal("device_adv", device.AdvSecretKey)
	if err != nil {
		return err
	}

	// Generate random 32-byte non-secret placeholders for whatsmeow_device's CHECK(length=32) columns.
	maskNoise := randomBytes32()
	maskIdent := randomBytes32()
	maskSPK := randomBytes32()
	maskAdv := randomBytes32()

	realNoise := device.NoiseKey
	realIdent := device.IdentityKey
	realSPKPair := device.SignedPreKey.KeyPair
	realAdv := device.AdvSecretKey

	device.NoiseKey = keys.NewKeyPairFromPrivateKey(maskNoise)
	device.IdentityKey = keys.NewKeyPairFromPrivateKey(maskIdent)
	device.SignedPreKey.KeyPair = *keys.NewKeyPairFromPrivateKey(maskSPK)
	device.AdvSecretKey = maskAdv[:]

	wasInit := device.Initialized
	putErr := ec.inner.PutDevice(ctx, device)

	// Immediately restore the real in-memory keys on device.
	device.NoiseKey = realNoise
	device.IdentityKey = realIdent
	device.SignedPreKey.KeyPair = realSPKPair
	device.AdvSecretKey = realAdv

	if putErr != nil {
		return putErr
	}

	_, err = ec.sqlDB.ExecContext(
		ctx,
		`INSERT INTO whatshub_sealed_device_keys (
			jid, sealed_noise_priv, sealed_ident_priv, sealed_spk_priv, sealed_spk_sig, sealed_adv_secret
		) VALUES (?, ?, ?, ?, ?, ?)
		ON CONFLICT(jid) DO UPDATE SET
			sealed_noise_priv = excluded.sealed_noise_priv,
			sealed_ident_priv = excluded.sealed_ident_priv,
			sealed_spk_priv   = excluded.sealed_spk_priv,
			sealed_spk_sig    = excluded.sealed_spk_sig,
			sealed_adv_secret = excluded.sealed_adv_secret`,
		device.ID.String(), sNoise, sIdent, sSPK, sSPKSig, sAdv,
	)
	if err != nil {
		return fmt.Errorf("store sealed device keys: %w", err)
	}

	if !wasInit {
		ec.wrapDeviceStores(device)
	} else {
		device.Container = ec
	}
	return nil
}

// DeleteDevice wipes both whatsmeow_device and sealed key material for this slot.
func (ec *EncryptedSlotContainer) DeleteDevice(ctx context.Context, device *store.Device) error {
	if device != nil && device.ID != nil {
		_, _ = ec.sqlDB.ExecContext(ctx, `DELETE FROM whatshub_sealed_device_keys WHERE jid = ?`, device.ID.String())
		_, _ = ec.sqlDB.ExecContext(ctx, `DELETE FROM whatshub_sealed_prekeys WHERE jid = ?`, device.ID.String())
	}
	return ec.inner.DeleteDevice(ctx, device)
}

func (ec *EncryptedSlotContainer) wrapDeviceStores(device *store.Device) {
	device.Container = ec
	device.Sessions = &encryptedSessionStore{inner: device.Sessions, cipher: ec.cipher}
	device.SenderKeys = &encryptedSenderKeyStore{inner: device.SenderKeys, cipher: ec.cipher}
	device.AppStateKeys = &encryptedAppStateSyncKeyStore{inner: device.AppStateKeys, cipher: ec.cipher}
	device.MsgSecrets = &encryptedMsgSecretStore{inner: device.MsgSecrets, cipher: ec.cipher}
	device.EventBuffer = &encryptedEventBuffer{inner: device.EventBuffer, cipher: ec.cipher}
	if device.ID != nil {
		device.PreKeys = &encryptedPreKeyStore{
			sqlDB:  ec.sqlDB,
			jid:    device.ID.String(),
			cipher: ec.cipher,
		}
	}
}

// Close cleanly checkpoints WAL and closes the underlying SQLite handle.
func (ec *EncryptedSlotContainer) Close() error {
	if ec == nil || ec.sqlDB == nil {
		return nil
	}
	_, _ = ec.sqlDB.Exec("PRAGMA wal_checkpoint(TRUNCATE);")
	return ec.inner.Close()
}

func randomBytes32() [32]byte {
	var b [32]byte
	_, _ = io.ReadFull(rand.Reader, b[:])
	return b
}

// --- Encrypted SessionStore Decorator ---

type encryptedSessionStore struct {
	inner  store.SessionStore
	cipher *SlotCipher
}

func (s *encryptedSessionStore) GetSession(ctx context.Context, address string) ([]byte, error) {
	blob, err := s.inner.GetSession(ctx, address)
	if err != nil || len(blob) == 0 {
		return blob, err
	}
	return s.cipher.Open("sessions", blob)
}

func (s *encryptedSessionStore) HasSession(ctx context.Context, address string) (bool, error) {
	return s.inner.HasSession(ctx, address)
}

func (s *encryptedSessionStore) GetManySessions(ctx context.Context, addresses []string) (map[string][]byte, error) {
	rawMap, err := s.inner.GetManySessions(ctx, addresses)
	if err != nil || len(rawMap) == 0 {
		return rawMap, err
	}
	out := make(map[string][]byte, len(rawMap))
	for addr, blob := range rawMap {
		if len(blob) == 0 {
			continue
		}
		plain, openErr := s.cipher.Open("sessions", blob)
		if openErr != nil {
			return nil, openErr
		}
		out[addr] = plain
	}
	return out, nil
}

func (s *encryptedSessionStore) PutSession(ctx context.Context, address string, session []byte) error {
	sealed, err := s.cipher.Seal("sessions", session)
	if err != nil {
		return err
	}
	return s.inner.PutSession(ctx, address, sealed)
}

func (s *encryptedSessionStore) PutManySessions(ctx context.Context, sessions map[string][]byte) error {
	sealedMap := make(map[string][]byte, len(sessions))
	for addr, plain := range sessions {
		sealed, err := s.cipher.Seal("sessions", plain)
		if err != nil {
			return err
		}
		sealedMap[addr] = sealed
	}
	return s.inner.PutManySessions(ctx, sealedMap)
}

func (s *encryptedSessionStore) DeleteAllSessions(ctx context.Context, phone string) error {
	return s.inner.DeleteAllSessions(ctx, phone)
}

func (s *encryptedSessionStore) DeleteSession(ctx context.Context, address string) error {
	return s.inner.DeleteSession(ctx, address)
}

func (s *encryptedSessionStore) MigratePNToLID(ctx context.Context, pn, lid types.JID) error {
	return s.inner.MigratePNToLID(ctx, pn, lid)
}

// --- Encrypted SenderKeyStore Decorator ---

type encryptedSenderKeyStore struct {
	inner  store.SenderKeyStore
	cipher *SlotCipher
}

func (s *encryptedSenderKeyStore) PutSenderKey(ctx context.Context, group, user string, session []byte) error {
	sealed, err := s.cipher.Seal("sender_keys", session)
	if err != nil {
		return err
	}
	return s.inner.PutSenderKey(ctx, group, user, sealed)
}

func (s *encryptedSenderKeyStore) GetSenderKey(ctx context.Context, group, user string) ([]byte, error) {
	blob, err := s.inner.GetSenderKey(ctx, group, user)
	if err != nil || len(blob) == 0 {
		return blob, err
	}
	return s.cipher.Open("sender_keys", blob)
}

// --- Encrypted AppStateSyncKeyStore Decorator ---

type encryptedAppStateSyncKeyStore struct {
	inner  store.AppStateSyncKeyStore
	cipher *SlotCipher
}

func (s *encryptedAppStateSyncKeyStore) PutAppStateSyncKey(ctx context.Context, id []byte, key store.AppStateSyncKey) error {
	sealedData, err := s.cipher.Seal("app_state_keys", key.Data)
	if err != nil {
		return err
	}
	key.Data = sealedData
	return s.inner.PutAppStateSyncKey(ctx, id, key)
}

func (s *encryptedAppStateSyncKeyStore) GetAppStateSyncKey(ctx context.Context, id []byte) (*store.AppStateSyncKey, error) {
	item, err := s.inner.GetAppStateSyncKey(ctx, id)
	if err != nil || item == nil {
		return item, err
	}
	plain, err := s.cipher.Open("app_state_keys", item.Data)
	if err != nil {
		return nil, err
	}
	cp := *item
	cp.Data = plain
	return &cp, nil
}

func (s *encryptedAppStateSyncKeyStore) GetLatestAppStateSyncKeyID(ctx context.Context) ([]byte, error) {
	return s.inner.GetLatestAppStateSyncKeyID(ctx)
}

func (s *encryptedAppStateSyncKeyStore) GetAllAppStateSyncKeys(ctx context.Context) ([]*store.AppStateSyncKey, error) {
	items, err := s.inner.GetAllAppStateSyncKeys(ctx)
	if err != nil || len(items) == 0 {
		return items, err
	}
	out := make([]*store.AppStateSyncKey, 0, len(items))
	for _, item := range items {
		plain, openErr := s.cipher.Open("app_state_keys", item.Data)
		if openErr != nil {
			return nil, openErr
		}
		cp := *item
		cp.Data = plain
		out = append(out, &cp)
	}
	return out, nil
}

// --- Encrypted MsgSecretStore Decorator ---

type encryptedMsgSecretStore struct {
	inner  store.MsgSecretStore
	cipher *SlotCipher
}

func (s *encryptedMsgSecretStore) PutMessageSecrets(ctx context.Context, inserts []store.MessageSecretInsert) error {
	sealedInserts := make([]store.MessageSecretInsert, len(inserts))
	for i, ins := range inserts {
		sealed, err := s.cipher.Seal("msg_secrets", ins.Secret)
		if err != nil {
			return err
		}
		sealedInserts[i] = ins
		sealedInserts[i].Secret = sealed
	}
	return s.inner.PutMessageSecrets(ctx, sealedInserts)
}

func (s *encryptedMsgSecretStore) PutMessageSecret(ctx context.Context, chat, sender types.JID, id types.MessageID, secret []byte) error {
	sealed, err := s.cipher.Seal("msg_secrets", secret)
	if err != nil {
		return err
	}
	return s.inner.PutMessageSecret(ctx, chat, sender, id, sealed)
}

func (s *encryptedMsgSecretStore) GetMessageSecret(ctx context.Context, chat, sender types.JID, id types.MessageID) ([]byte, types.JID, error) {
	blob, senderOut, err := s.inner.GetMessageSecret(ctx, chat, sender, id)
	if err != nil || len(blob) == 0 {
		return blob, senderOut, err
	}
	plain, err := s.cipher.Open("msg_secrets", blob)
	return plain, senderOut, err
}

// --- Encrypted EventBuffer Decorator ---

type encryptedEventBuffer struct {
	inner  store.EventBuffer
	cipher *SlotCipher
}

func (e *encryptedEventBuffer) GetBufferedEvent(ctx context.Context, ciphertextHash [32]byte) (*store.BufferedEvent, error) {
	ev, err := e.inner.GetBufferedEvent(ctx, ciphertextHash)
	if err != nil || ev == nil || len(ev.Plaintext) == 0 {
		return ev, err
	}
	plain, err := e.cipher.Open("event_buffer", ev.Plaintext)
	if err != nil {
		return nil, err
	}
	cp := *ev
	cp.Plaintext = plain
	return &cp, nil
}

func (e *encryptedEventBuffer) PutBufferedEvent(ctx context.Context, ciphertextHash [32]byte, plaintext []byte, serverTimestamp time.Time) error {
	sealed, err := e.cipher.Seal("event_buffer", plaintext)
	if err != nil {
		return err
	}
	return e.inner.PutBufferedEvent(ctx, ciphertextHash, sealed, serverTimestamp)
}

func (e *encryptedEventBuffer) DoDecryptionTxn(ctx context.Context, fn func(context.Context) error) error {
	return e.inner.DoDecryptionTxn(ctx, fn)
}

func (e *encryptedEventBuffer) ClearBufferedEventPlaintext(ctx context.Context, ciphertextHash [32]byte) error {
	return e.inner.ClearBufferedEventPlaintext(ctx, ciphertextHash)
}

func (e *encryptedEventBuffer) DeleteOldBufferedHashes(ctx context.Context) error {
	return e.inner.DeleteOldBufferedHashes(ctx)
}

func (e *encryptedEventBuffer) GetOutgoingEvent(ctx context.Context, chatJID, altChatJID types.JID, id types.MessageID) (string, []byte, error) {
	format, blob, err := e.inner.GetOutgoingEvent(ctx, chatJID, altChatJID, id)
	if err != nil || len(blob) == 0 {
		return format, blob, err
	}
	plain, err := e.cipher.Open("retry_buffer", blob)
	return format, plain, err
}

func (e *encryptedEventBuffer) AddOutgoingEvent(ctx context.Context, chatJID types.JID, id types.MessageID, format string, plaintext []byte) error {
	sealed, err := e.cipher.Seal("retry_buffer", plaintext)
	if err != nil {
		return err
	}
	return e.inner.AddOutgoingEvent(ctx, chatJID, id, format, sealed)
}

func (e *encryptedEventBuffer) DeleteOldOutgoingEvents(ctx context.Context) error {
	return e.inner.DeleteOldOutgoingEvents(ctx)
}

// --- Encrypted PreKeyStore ---

type encryptedPreKeyStore struct {
	mu     sync.Mutex
	sqlDB  *sql.DB
	jid    string
	cipher *SlotCipher
}

func (p *encryptedPreKeyStore) nextIDLocked(ctx context.Context) (uint32, error) {
	var last sql.NullInt64
	if err := p.sqlDB.QueryRowContext(ctx, `SELECT MAX(key_id) FROM whatshub_sealed_prekeys WHERE jid = ?`, p.jid).Scan(&last); err != nil {
		return 0, err
	}
	return uint32(last.Int64) + 1, nil
}

func (p *encryptedPreKeyStore) insertPreKeyLocked(ctx context.Context, id uint32, uploaded bool) (*keys.PreKey, error) {
	pk := keys.NewPreKey(id)
	sealed, err := p.cipher.Seal("prekeys", pk.Priv[:])
	if err != nil {
		return nil, err
	}
	_, err = p.sqlDB.ExecContext(
		ctx,
		`INSERT INTO whatshub_sealed_prekeys (jid, key_id, sealed_priv, uploaded) VALUES (?, ?, ?, ?)`,
		p.jid, pk.KeyID, sealed, uploaded,
	)
	if err != nil {
		return nil, err
	}
	return pk, nil
}

func (p *encryptedPreKeyStore) GenOnePreKey(ctx context.Context) (*keys.PreKey, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	id, err := p.nextIDLocked(ctx)
	if err != nil {
		return nil, err
	}
	return p.insertPreKeyLocked(ctx, id, true)
}

func (p *encryptedPreKeyStore) GetOrGenPreKeys(ctx context.Context, count uint32) ([]*keys.PreKey, error) {
	p.mu.Lock()
	defer p.mu.Unlock()

	rows, err := p.sqlDB.QueryContext(
		ctx,
		`SELECT key_id, sealed_priv FROM whatshub_sealed_prekeys WHERE jid = ? AND uploaded = 0 ORDER BY key_id LIMIT ?`,
		p.jid, count,
	)
	if err != nil {
		return nil, err
	}
	var out []*keys.PreKey
	for rows.Next() {
		var id uint32
		var sealed []byte
		if scanErr := rows.Scan(&id, &sealed); scanErr != nil {
			_ = rows.Close()
			return nil, scanErr
		}
		plain, openErr := p.cipher.Open("prekeys", sealed)
		if openErr != nil || len(plain) != 32 {
			_ = rows.Close()
			return nil, errCiphertextAuthentication
		}
		out = append(out, &keys.PreKey{
			KeyPair: *keys.NewKeyPairFromPrivateKey(*(*[32]byte)(plain)),
			KeyID:   id,
		})
	}
	_ = rows.Close()

	if uint32(len(out)) < count {
		nextID, err := p.nextIDLocked(ctx)
		if err != nil {
			return nil, err
		}
		need := int(count) - len(out)
		for i := 0; i < need; i++ {
			pk, genErr := p.insertPreKeyLocked(ctx, nextID, false)
			if genErr != nil {
				return nil, genErr
			}
			out = append(out, pk)
			nextID++
		}
	}
	return out, nil
}

func (p *encryptedPreKeyStore) GetPreKey(ctx context.Context, id uint32) (*keys.PreKey, error) {
	var sealed []byte
	err := p.sqlDB.QueryRowContext(
		ctx,
		`SELECT sealed_priv FROM whatshub_sealed_prekeys WHERE jid = ? AND key_id = ?`,
		p.jid, id,
	).Scan(&sealed)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	plain, err := p.cipher.Open("prekeys", sealed)
	if err != nil || len(plain) != 32 {
		return nil, errCiphertextAuthentication
	}
	return &keys.PreKey{
		KeyPair: *keys.NewKeyPairFromPrivateKey(*(*[32]byte)(plain)),
		KeyID:   id,
	}, nil
}

func (p *encryptedPreKeyStore) RemovePreKey(ctx context.Context, id uint32) error {
	_, err := p.sqlDB.ExecContext(ctx, `DELETE FROM whatshub_sealed_prekeys WHERE jid = ? AND key_id = ?`, p.jid, id)
	return err
}

func (p *encryptedPreKeyStore) MarkPreKeysAsUploaded(ctx context.Context, upToID uint32) error {
	_, err := p.sqlDB.ExecContext(
		ctx,
		`UPDATE whatshub_sealed_prekeys SET uploaded = 1 WHERE jid = ? AND key_id <= ?`,
		p.jid, upToID,
	)
	return err
}

func (p *encryptedPreKeyStore) UploadedPreKeyCount(ctx context.Context) (int, error) {
	var cnt int
	err := p.sqlDB.QueryRowContext(
		ctx,
		`SELECT COUNT(*) FROM whatshub_sealed_prekeys WHERE jid = ? AND uploaded = 1`,
		p.jid,
	).Scan(&cnt)
	return cnt, err
}
