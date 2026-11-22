package emby

import "testing"

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
