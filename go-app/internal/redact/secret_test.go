package redact

import (
	"encoding/json"
	"fmt"
	"strings"
	"testing"
)

type holder struct{ secret string }

func (h holder) String() string               { return Masked("Holder") }
func (h holder) GoString() string             { return Masked("Holder") }
func (h holder) Format(f fmt.State, _ rune)   { WriteMasked(f, "Holder") }
func (h holder) MarshalJSON() ([]byte, error) { return RefuseMarshal("Holder") }

func TestMaskedHelpers(t *testing.T) {
	if got := Masked("Token"); got != "Token(***)" {
		t.Fatalf("Masked = %q", got)
	}
	h := holder{secret: "S3CR3T"}
	for _, verb := range []string{"%v", "%+v", "%#v", "%s", "%q", "%x", "%d"} {
		if out := fmt.Sprintf(verb, h); out != "Holder(***)" {
			t.Errorf("%s : %q", verb, out)
		}
	}
	out, err := json.Marshal(h)
	if err == nil || strings.Contains(string(out), "S3CR3T") {
		t.Fatalf("json : %s, %v", out, err)
	}
	if !strings.Contains(err.Error(), "Holder ne se sérialise pas") {
		t.Fatalf("erreur : %v", err)
	}
}
