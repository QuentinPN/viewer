package emby

import (
	"fmt"
	"strings"
	"testing"
)

func TestCredentialAccountIsServerSlashUser(t *testing.T) {
	if got := CredentialAccount(fakeServerID, fakeUserID); got != fakeServerID+"/"+fakeUserID {
		t.Fatal(got)
	}
}

func TestMemoryCredentialStore(t *testing.T) {
	var store MemoryCredentialStore
	var _ CredentialStore = &store
	if got, err := store.Load("a"); got != nil || err != nil {
		t.Fatal(got, err)
	}
	if err := store.Save("a", NewAccessToken("t1")); err != nil {
		t.Fatal(err)
	}
	if err := store.Save("a", NewAccessToken("t2")); err != nil {
		t.Fatal(err)
	}
	if got, _ := store.Load("a"); got == nil || got.Expose() != "t2" {
		t.Fatal(got)
	}
	if err := store.Delete("a"); err != nil {
		t.Fatal(err)
	}
	if err := store.Delete("a"); err != nil {
		t.Fatal(err)
	}
	if got, _ := store.Load("a"); got != nil {
		t.Fatal(got)
	}
}

// Reported by wave 6: %#v printed the tokens of a MemoryCredentialStore.
func TestMemoryCredentialStoreNeverPrintsItsTokens(t *testing.T) {
	store := &MemoryCredentialStore{}
	if err := store.Save("a", NewAccessToken("secret-token")); err != nil {
		t.Fatal(err)
	}
	for _, format := range []string{"%v", "%+v", "%#v", "%s"} {
		if got := fmt.Sprintf(format, store); strings.Contains(got, "secret-token") {
			t.Fatalf("%s : %s", format, got)
		}
	}
}
