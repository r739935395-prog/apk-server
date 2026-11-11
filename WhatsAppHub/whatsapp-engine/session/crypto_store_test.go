package session

import (
	"bytes"
	"context"
	"os"
	"path/filepath"
	"testing"

	"go.mau.fi/whatsmeow/proto/waAdv"
	"go.mau.fi/whatsmeow/types"
	waLog "go.mau.fi/whatsmeow/util/log"
)

func TestEncryptedSlotContainerSealsKeysAndSessionsOnDisk(t *testing.T) {
	ctx := context.Background()
	tmpDir := t.TempDir()
	dbPath := filepath.Join(tmpDir, "account_1", "session.db")

	key1Hex := "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
	key2Hex := "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100"

	c1, err := NewSlotCipher(1, key1Hex)
	if err != nil {
		t.Fatalf("NewSlotCipher: %v", err)
	}

	store1, err := OpenEncryptedSlotContainer(ctx, dbPath, 1, c1, waLog.Noop)
	if err != nil {
		t.Fatalf("OpenEncryptedSlotContainer: %v", err)
	}

	dev, err := store1.GetOrCreateDevice(ctx)
	if err != nil {
		t.Fatalf("GetOrCreateDevice: %v", err)
	}

	jid := types.NewJID("967771234567", types.DefaultUserServer)
	jid.Device = 11
	dev.ID = &jid
	dev.Account = &waAdv.ADVSignedDeviceIdentity{
		Details:             []byte("adv-details"),
		AccountSignature:    bytes.Repeat([]byte{0xAA}, 64),
		AccountSignatureKey: bytes.Repeat([]byte{0xBB}, 32),
		DeviceSignature:     bytes.Repeat([]byte{0xCC}, 64),
	}

	origNoise := *dev.NoiseKey.Priv
	origIdent := *dev.IdentityKey.Priv
	origSPK := *dev.SignedPreKey.Priv
	secretSessionPlain := []byte("DOUBLE_RATCHET_SECRET_SESSION_STATE_ACCOUNT_1_ONLY")

	if err := dev.Save(ctx); err != nil {
		t.Fatalf("dev.Save: %v", err)
	}
	if err := dev.Sessions.PutSession(ctx, "967770009999:0", secretSessionPlain); err != nil {
		t.Fatalf("PutSession: %v", err)
	}
	preKeys, err := dev.PreKeys.GetOrGenPreKeys(ctx, 3)
	if err != nil || len(preKeys) != 3 {
		t.Fatalf("GetOrGenPreKeys: %v (len=%d)", err, len(preKeys))
	}
	origPreKey0 := *preKeys[0].Priv

	if err := store1.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}

	// Inspect raw SQLite file bytes on disk: none of the plaintext secrets may appear.
	rawDiskBytes, err := os.ReadFile(dbPath)
	if err != nil {
		t.Fatalf("ReadFile db: %v", err)
	}
	if bytes.Contains(rawDiskBytes, origNoise[:]) {
		t.Fatal("SECURITY FAILURE: raw NoiseKey private key found in SQLite file on disk")
	}
	if bytes.Contains(rawDiskBytes, origIdent[:]) {
		t.Fatal("SECURITY FAILURE: raw IdentityKey private key found in SQLite file on disk")
	}
	if bytes.Contains(rawDiskBytes, origSPK[:]) {
		t.Fatal("SECURITY FAILURE: raw SignedPreKey private key found in SQLite file on disk")
	}
	if bytes.Contains(rawDiskBytes, origPreKey0[:]) {
		t.Fatal("SECURITY FAILURE: raw PreKey private key found in SQLite file on disk")
	}
	if bytes.Contains(rawDiskBytes, secretSessionPlain) {
		t.Fatal("SECURITY FAILURE: plaintext Signal session found in SQLite file on disk")
	}

	// Reopening with Account 2's key or wrong account ID must fail authentication.
	cWrongKey, _ := NewSlotCipher(1, key2Hex)
	storeWrong, err := OpenEncryptedSlotContainer(ctx, dbPath, 1, cWrongKey, waLog.Noop)
	if err != nil {
		t.Fatalf("OpenEncryptedSlotContainer wrong key: %v", err)
	}
	if _, err := storeWrong.GetOrCreateDevice(ctx); err == nil {
		t.Fatal("expected authentication failure when opening Account 1 store with Account 2 key")
	}
	_ = storeWrong.Close()

	cWrongAccountAAD, _ := NewSlotCipher(2, key1Hex)
	storeWrongAcct, err := OpenEncryptedSlotContainer(ctx, dbPath, 2, cWrongAccountAAD, waLog.Noop)
	if err != nil {
		t.Fatalf("OpenEncryptedSlotContainer wrong account ID: %v", err)
	}
	if _, err := storeWrongAcct.GetOrCreateDevice(ctx); err == nil {
		t.Fatal("expected authentication failure when opening Account 1 store under Account 2 scope")
	}
	_ = storeWrongAcct.Close()

	// Reopening with Account 1's genuine key restores identical keys and sessions.
	storeReopen, err := OpenEncryptedSlotContainer(ctx, dbPath, 1, c1, waLog.Noop)
	if err != nil {
		t.Fatalf("reopen store1: %v", err)
	}
	defer storeReopen.Close()

	restoredDev, err := storeReopen.GetOrCreateDevice(ctx)
	if err != nil {
		t.Fatalf("GetOrCreateDevice reopen: %v", err)
	}
	if *restoredDev.NoiseKey.Priv != origNoise || *restoredDev.IdentityKey.Priv != origIdent || *restoredDev.SignedPreKey.Priv != origSPK {
		t.Fatal("restored device keys did not match original keys")
	}
	restoredSession, err := restoredDev.Sessions.GetSession(ctx, "967770009999:0")
	if err != nil || !bytes.Equal(restoredSession, secretSessionPlain) {
		t.Fatalf("restored session mismatch: err=%v got=%q", err, string(restoredSession))
	}
	restoredPK, err := restoredDev.PreKeys.GetPreKey(ctx, preKeys[0].KeyID)
	if err != nil || restoredPK == nil || *restoredPK.Priv != origPreKey0 {
		t.Fatalf("restored prekey mismatch: err=%v", err)
	}
}
