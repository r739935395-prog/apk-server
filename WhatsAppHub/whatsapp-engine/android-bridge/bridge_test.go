package androidbridge

import (
	"encoding/json"
	"path/filepath"
	"testing"

	"whatshub/session"
)

func TestBridgeLifecycleAndFourAccountSnapshots(t *testing.T) {
	b := NewBridge()
	tmp := t.TempDir()

	initRes := b.Init(filepath.Join(tmp, "sessions"), filepath.Join(tmp, "media"), "")
	var env ResultEnvelope
	if err := json.Unmarshal([]byte(initRes), &env); err != nil || !env.OK {
		t.Fatalf("Init failed: %s (%v)", initRes, err)
	}
	defer b.Shutdown()

	// Invalid key length must fail cleanly
	badKeyRes := b.SetSlotKey(1, "1234")
	_ = json.Unmarshal([]byte(badKeyRes), &env)
	if env.OK {
		t.Fatal("expected short key to be rejected")
	}

	// Valid 32-byte keys for all 4 accounts succeed
	for acct := 1; acct <= 4; acct++ {
		keyHex := "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"
		res := b.SetSlotKey(acct, keyHex)
		_ = json.Unmarshal([]byte(res), &env)
		if !env.OK {
			t.Fatalf("SetSlotKey(%d) failed: %s", acct, res)
		}
	}

	// Out-of-range Account 5 must be rejected
	outOfRange := b.ConnectAccount(5)
	_ = json.Unmarshal([]byte(outOfRange), &env)
	if env.OK {
		t.Fatal("expected ConnectAccount(5) to fail")
	}

	snapJSON := b.GetAccountsSnapshot()
	var snaps []session.SlotSnapshot
	if err := json.Unmarshal([]byte(snapJSON), &snaps); err != nil || len(snaps) != 4 {
		t.Fatalf("expected 4 account snapshots, got %s (%v)", snapJSON, err)
	}
}
