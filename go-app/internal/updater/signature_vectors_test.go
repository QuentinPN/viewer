package updater

import (
	"bytes"
	"crypto/ed25519"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// signatureVectors is testdata/signature-vectors.json, also read by
// src/release/signature.test.ts: the release script (TypeScript) and this
// verifier must build the same SignedMessage and the same signature.
type signatureVectors struct {
	Seed      string `json:"seed"`
	PublicKey string `json:"publicKey"`
	Cases     []struct {
		Version   string `json:"version"`
		Installer string `json:"installer"`
		Message   string `json:"message"`
		Signature string `json:"signature"`
	} `json:"cases"`
}

func Test_go_signature_vectors_shared_with_the_release_script(t *testing.T) {
	raw, err := os.ReadFile(filepath.Join("testdata", "signature-vectors.json"))
	if err != nil {
		t.Fatal(err)
	}
	var v signatureVectors
	if err := json.Unmarshal(raw, &v); err != nil {
		t.Fatal(err)
	}
	seed, err := hex.DecodeString(v.Seed)
	if err != nil || len(seed) != ed25519.SeedSize {
		t.Fatalf("seed: %v", err)
	}
	priv := ed25519.NewKeyFromSeed(seed)
	pub := base64.StdEncoding.EncodeToString(priv.Public().(ed25519.PublicKey))
	if pub != v.PublicKey {
		t.Fatalf("public key %s, vectors %s", pub, v.PublicKey)
	}
	if len(v.Cases) == 0 {
		t.Fatal("no case")
	}
	for _, c := range v.Cases {
		installer, err := base64.StdEncoding.DecodeString(c.Installer)
		if err != nil {
			t.Fatalf("%s: installer: %v", c.Version, err)
		}
		msg := SignedMessage(c.Version, installer)
		if got := hex.EncodeToString(msg); got != c.Message {
			t.Errorf("%s: message %s, vectors %s", c.Version, got, c.Message)
		}
		if got := base64.StdEncoding.EncodeToString(ed25519.Sign(priv, msg)); got != c.Signature {
			t.Errorf("%s: signature %s, vectors %s", c.Version, got, c.Signature)
		}
		if err := Verify(installer, c.Version, c.Signature, v.PublicKey); err != nil {
			t.Errorf("%s: %v", c.Version, err)
		}
		if err := Verify(append(installer, 0), c.Version, c.Signature, v.PublicKey); Category(err) != "signature" {
			t.Errorf("%s: altered installer accepted: %v", c.Version, err)
		}
	}
}

// The public key injected at the release build (scripts/build-wails-exe.ts)
// must parse as the base64 of 32 bytes, as Verify expects.
func Test_go_committed_release_public_key_is_well_formed(t *testing.T) {
	raw, err := os.ReadFile(filepath.Join("..", "..", "build", "updater-ed25519.pub"))
	if err != nil {
		t.Fatal(err)
	}
	key := strings.TrimSpace(string(raw))
	decoded, err := base64.StdEncoding.DecodeString(key)
	if err != nil || len(decoded) != ed25519.PublicKeySize {
		t.Fatalf("updater-ed25519.pub is not the base64 of 32 bytes: %v", err)
	}
	if bytes.Contains(raw, []byte("PRIVATE")) {
		t.Fatal("updater-ed25519.pub holds a private key")
	}
}

// Checks a built release with the app's own code path: the manifest written by
// `node scripts/publish.ts --dry-run` (VIEWER_RELEASE_MANIFEST) goes through
// parseManifest, then the installer (VIEWER_RELEASE_INSTALLER) through Verify
// with the committed public key. Skipped unless both variables are set.
func Test_go_release_manifest_verifies(t *testing.T) {
	manifestPath, installerPath := os.Getenv("VIEWER_RELEASE_MANIFEST"), os.Getenv("VIEWER_RELEASE_INSTALLER")
	if manifestPath == "" || installerPath == "" {
		t.Skip("VIEWER_RELEASE_MANIFEST and VIEWER_RELEASE_INSTALLER not set")
	}
	body, err := os.ReadFile(manifestPath)
	if err != nil {
		t.Fatal(err)
	}
	installer, err := os.ReadFile(installerPath)
	if err != nil {
		t.Fatal(err)
	}
	pub, err := os.ReadFile(filepath.Join("..", "..", "build", "updater-ed25519.pub"))
	if err != nil {
		t.Fatal(err)
	}
	rel, err := parseManifest(body, Version{})
	if err != nil || rel == nil {
		t.Fatalf("manifest: %v %v", rel, err)
	}
	if err := Verify(installer, rel.version, rel.signature, string(pub)); err != nil {
		t.Fatalf("version %s: %v", rel.version, err)
	}
	t.Logf("version %s, url %s: ed25519 signature verified", rel.version, rel.url)
}
