package tlsroots

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func Test_pem_wraps_at_64_characters(t *testing.T) {
	if got, want := toPEM([][]byte{{1, 2, 3}}), "-----BEGIN CERTIFICATE-----\nAQID\n-----END CERTIFICATE-----\n"; got != want {
		t.Fatalf("toPEM = %q, want %q", got, want)
	}
	pem := toPEM([][]byte{make([]byte, 49), make([]byte, 3)})
	lines := strings.Split(strings.TrimSuffix(pem, "\n"), "\n")
	if len(lines[1]) != 64 {
		t.Errorf("first line is %d characters", len(lines[1]))
	}
	for i, want := range map[int]string{2: "AA==", 3: "-----END CERTIFICATE-----", 4: "-----BEGIN CERTIFICATE-----", 5: "AAAA"} {
		if lines[i] != want {
			t.Errorf("line %d = %q, want %q", i, lines[i], want)
		}
	}
}

func Test_writes_once_and_restores_a_modified_file(t *testing.T) {
	path := filepath.Join(t.TempDir(), "tls-é", "sub", "tls-roots.pem")
	read := func() string {
		b, err := os.ReadFile(path)
		if err != nil {
			t.Fatal(err)
		}
		return string(b)
	}

	if err := writeIfChanged(path, "un"); err != nil {
		t.Fatal(err)
	}
	if read() != "un" {
		t.Fatalf("content = %q", read())
	}
	if _, err := os.Stat(strings.TrimSuffix(path, ".pem") + ".tmp"); err == nil {
		t.Fatal("temporary file left behind")
	}

	// Identical content: the file is not replaced, even read-only.
	if err := os.Chmod(path, 0o444); err != nil {
		t.Fatal(err)
	}
	if err := writeIfChanged(path, "un"); err != nil {
		t.Fatalf("identical content rewritten: %v", err)
	}
	if err := os.Chmod(path, 0o644); err != nil {
		t.Fatal(err)
	}

	if err := os.WriteFile(path, []byte("modifié"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := writeIfChanged(path, "un"); err != nil {
		t.Fatal(err)
	}
	if read() != "un" {
		t.Fatalf("content = %q, want restored", read())
	}
}

func Test_refresh_writes_the_roots_as_pem(t *testing.T) {
	path := filepath.Join(t.TempDir(), "tls-roots.pem")
	if err := Refresh(path); err != nil {
		t.Skipf("no Windows store here: %v", err)
	}
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(string(b), "-----BEGIN CERTIFICATE-----\n") || !strings.HasSuffix(string(b), "-----END CERTIFICATE-----\n") {
		t.Fatalf("not PEM: %.60q", b)
	}
	// A second refresh leaves the file untouched.
	before, _ := os.Stat(path)
	if err := Refresh(path); err != nil {
		t.Fatal(err)
	}
	after, _ := os.Stat(path)
	if !after.ModTime().Equal(before.ModTime()) {
		t.Error("identical roots rewritten")
	}
}
