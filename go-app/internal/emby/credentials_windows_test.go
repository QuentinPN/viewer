//go:build windows

package emby

// These tests write to the real Windows Credential Manager, under a service name of
// their own (`Viewer-gotest-<random>`): no real entry of the app is ever read,
// listed or touched. Every entry is deleted by t.Cleanup, registered before writing.

import (
	"crypto/rand"
	"encoding/hex"
	"strings"
	"testing"
)

func throwawayStore(t *testing.T) (*WindowsCredentialStore, string) {
	t.Helper()
	b := make([]byte, 8)
	if _, err := rand.Read(b); err != nil {
		t.Fatal(err)
	}
	store := NewWindowsCredentialStore("Viewer-gotest-" + hex.EncodeToString(b))
	account := "test/" + hex.EncodeToString(b)
	t.Cleanup(func() { _ = store.Delete(account) })
	return store, account
}

// Rust `windows_store_round_trip` (ignored by default there; run here on a
// throwaway entry).
func Test_windows_store_round_trip(t *testing.T) {
	store, account := throwawayStore(t)
	token := NewAccessToken("round-trip-token")
	if got, err := store.Load(account); err != nil || got != nil {
		t.Fatal(got, err)
	}
	if err := store.Save(account, token); err != nil {
		t.Fatal(err)
	}
	got, err := store.Load(account)
	if err != nil || got == nil || *got != token {
		t.Fatal(got, err)
	}
	if err := store.Delete(account); err != nil {
		t.Fatal(err)
	}
	if got, err := store.Load(account); err != nil || got != nil {
		t.Fatal(got, err)
	}
	// Deleting a missing entry is not an error (sign-out after a lost token).
	if err := store.Delete(account); err != nil {
		t.Fatal(err)
	}
}

// The entry is the one keyring 3.6.3 writes, byte for byte (research/credentials.md).
func TestWindowsStoreWritesTheKeyringFormat(t *testing.T) {
	store, account := throwawayStore(t)
	// A surrogate pair and an accent: UTF-16 LE, no terminator.
	secret := "jeton-\U0001F600-é"
	if err := store.Save(account, NewAccessToken(secret)); err != nil {
		t.Fatal(err)
	}
	raw, err := readCredential(account + "." + store.service)
	if err != nil {
		t.Fatal(err)
	}
	want := []byte{'j', 0, 'e', 0, 't', 0, 'o', 0, 'n', 0, '-', 0, 0x3D, 0xD8, 0x00, 0xDE, '-', 0, 0xE9, 0}
	if string(raw.blob) != string(want) {
		t.Fatalf("blob % x", raw.blob)
	}
	if raw.target != account+"."+store.service || raw.userName != account || raw.comment != "keyring v3.6.3" ||
		raw.alias != "" || raw.credType != credTypeGeneric || raw.persist != credPersistEnterprise || raw.attrCount != 0 {
		t.Fatalf("%+v", raw)
	}
	got, err := store.Load(account)
	if err != nil || got.Expose() != secret {
		t.Fatal(got, err)
	}
}

// An entry Rust could not read is not read either: odd length, lone surrogate.
func TestWindowsStoreRefusesWhatKeyringRefuses(t *testing.T) {
	store, account := throwawayStore(t)
	target := account + "." + store.service
	for _, blob := range [][]byte{{'a', 0, 'b'}, {0x00, 0xD8, 'a', 0}} {
		if err := writeCredential(target, account, blob); err != nil {
			t.Fatal(err)
		}
		_, err := store.Load(account)
		wantKind(t, err, KindCredentialStore)
		if !strings.Contains(err.Error(), "Data is not UTF-8 encoded") {
			t.Fatal(err)
		}
	}
	// An empty blob is an empty token, as `String::from_utf16(&[])`.
	if err := writeCredential(target, account, nil); err != nil {
		t.Fatal(err)
	}
	got, err := store.Load(account)
	if err != nil || got == nil || got.Expose() != "" {
		t.Fatal(got, err)
	}
}

func TestWindowsStoreChecksKeyringLimits(t *testing.T) {
	store, account := throwawayStore(t)
	err := store.Save(strings.Repeat("a", 514), NewAccessToken("x"))
	wantKind(t, err, KindCredentialStore)
	if !strings.Contains(err.Error(), "Attribute 'user' is longer than platform limit of 513 chars") {
		t.Fatal(err)
	}
	err = store.Save(account, NewAccessToken(strings.Repeat("x", 1281)))
	wantKind(t, err, KindCredentialStore)
	if got, err := store.Load(account); err != nil || got != nil {
		t.Fatal("rien ne doit être écrit", got, err)
	}
}

func TestDecodeUTF16LE(t *testing.T) {
	for blob, want := range map[string]string{"": "", "a\x00": "a", "\x3d\xd8\x00\xde": "\U0001F600"} {
		if got, ok := decodeUTF16LE([]byte(blob)); !ok || got != want {
			t.Errorf("% x: %q %v", blob, got, ok)
		}
	}
	for _, blob := range []string{"a", "\x00\xdc", "\x00\xd8"} {
		if _, ok := decodeUTF16LE([]byte(blob)); ok {
			t.Errorf("% x accepté", blob)
		}
	}
}
