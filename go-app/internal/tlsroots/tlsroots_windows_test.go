//go:build windows

package tlsroots

import (
	"crypto/x509"
	"testing"
)

func Test_only_server_authentication_roots_are_kept(t *testing.T) {
	if !allowsServerAuth(validUses{all: true}) {
		t.Error("all uses refused")
	}
	if !allowsServerAuth(validUses{oids: []string{"1.3.6.1.5.5.7.3.4", "1.3.6.1.5.5.7.3.1"}}) {
		t.Error("server auth among others refused")
	}
	// Email only, and a root Windows has disabled for every use.
	if allowsServerAuth(validUses{oids: []string{"1.3.6.1.5.5.7.3.4"}}) {
		t.Error("email only accepted")
	}
	if allowsServerAuth(validUses{oids: []string{}}) {
		t.Error("no use accepted")
	}
}

func Test_the_real_store_gives_certificates(t *testing.T) {
	roots, err := windowsRoots()
	if err != nil {
		t.Fatal(err)
	}
	if len(roots) == 0 {
		t.Fatal("no root")
	}
	parsed := 0
	for _, der := range roots {
		// Every DER certificate is an ASN.1 sequence.
		if len(der) == 0 || der[0] != 0x30 {
			t.Fatalf("not DER: %x", der[:min(4, len(der))])
		}
		if _, err := x509.ParseCertificate(der); err == nil {
			parsed++
		}
	}
	t.Logf("%d roots, %d parse with crypto/x509", len(roots), parsed)
	// Go's parser is stricter than Windows': tolerate a few, not most.
	if parsed < len(roots)*9/10 {
		t.Errorf("only %d of %d roots parse", parsed, len(roots))
	}
}
