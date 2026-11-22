package updater

import (
	"crypto/ed25519"
	"crypto/sha512"
	"encoding/base64"
	"errors"
	"strings"
)

// PublicKey is the pinned ed25519 public key of the update signatures, as the
// base64 of its 32 raw bytes. It is a var so the release build can inject it:
//
//	-ldflags "-X viewer/internal/updater.PublicKey=<base64>"
//
// The release build (scripts/build-wails-exe.ts) injects the key of
// go-app/build/updater-ed25519.pub. Empty, every update fails closed with the
// category "signature": an unsigned or unverifiable installer is never kept.
// The source keeps it empty.
var PublicKey = ""

// SignaturePrefix separates the update signatures from any other use of the key.
const SignaturePrefix = "viewer-update-v1\x00"

// SignedMessage is what an update signature covers:
//
//	"viewer-update-v1" 0x00 version 0x00 SHA-512(installer)
//
// version is the canonical semver of the release (package.json, no leading v).
// Binding the version is the place of the plugin's signed-version check
// (updater.rs verify_signed_version, 2.14.1): the Tauri CLI writes
// `version:X.Y.Z` in the minisign trusted comment and the plugin rejects a
// mismatch, so an old authentic installer cannot be replayed as a newer
// version over plain http. Here the version is always signed.
func SignedMessage(version string, installer []byte) []byte {
	digest := sha512.Sum512(installer)
	msg := make([]byte, 0, len(SignaturePrefix)+len(version)+1+len(digest))
	msg = append(msg, SignaturePrefix...)
	msg = append(msg, version...)
	msg = append(msg, 0)
	return append(msg, digest[:]...)
}

// canonicalVersion is the semver rendering of v (leading v dropped), or v
// trimmed when it is not semver, as the plugin compares signed versions.
func canonicalVersion(v string) string {
	v = strings.TrimSpace(v)
	if parsed, err := ParseVersion(strings.TrimLeft(v, "v")); err == nil {
		return parsed.String()
	}
	return v
}

// Verify checks that signature (base64, with or without padding) is the pure
// ed25519 signature of SignedMessage(version, installer) under publicKey
// (base64 of 32 bytes). Every failure, an empty key or another version
// included, is KindSignature.
func Verify(installer []byte, version, signature, publicKey string) error {
	pub, err := decodeBase64(publicKey)
	if err != nil || len(pub) != ed25519.PublicKeySize {
		return newError(KindSignature, errors.New("no valid pinned public key"))
	}
	sig, err := decodeBase64(signature)
	if err != nil || len(sig) != ed25519.SignatureSize {
		return newError(KindSignature, errors.New("signature does not decode"))
	}
	if !ed25519.Verify(ed25519.PublicKey(pub), SignedMessage(canonicalVersion(version), installer), sig) {
		return newError(KindSignature, errors.New("signature does not verify for this version"))
	}
	return nil
}

func decodeBase64(s string) ([]byte, error) {
	s = strings.TrimSpace(s)
	if s == "" {
		return nil, errors.New("empty")
	}
	if b, err := base64.StdEncoding.DecodeString(s); err == nil {
		return b, nil
	}
	return base64.RawStdEncoding.DecodeString(s)
}
