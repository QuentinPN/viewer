package request

import (
	"encoding/json"
	"fmt"
	"reflect"
	"strings"
	"testing"
	"time"

	"viewer/internal/emby"
)

const leakSecret = "S3CR3T-tok-123"

func Test_token_holders_never_print_the_secret(t *testing.T) {
	hex := fmt.Sprintf("%x", leakSecret)
	store := &emby.MemoryCredentialStore{}
	ur := newTestService(t, store, "")
	tok := emby.NewAccessToken(leakSecret)
	ur.rejected["a"] = rejection{tokenHash: hashToken(tok), message: "refusé"}
	values := map[string]any{
		"SessionToken":     NewSessionToken(leakSecret),
		"EmbyTokenSession": EmbyTokenSession{Token: NewSessionToken(leakSecret)},
		"Revocation":       Revocation{token: NewSessionToken(leakSecret)},
		"rejection":        rejection{tokenHash: hashToken(tok), message: "refusé"},
		"Identity":         Identity{ServerID: "s", UserID: "u", EmbyToken: emby.NewAccessToken(leakSecret)},
		"Service":          ur,
		"Service by value": reflect.ValueOf(ur).Elem().Interface(),
	}
	for name, v := range values {
		for _, verb := range []string{"%v", "%+v", "%#v", "%s", "%q", "%x", "%X", "%d"} {
			for _, out := range []string{fmt.Sprintf(verb, v), fmt.Sprintf(verb, &v), fmt.Sprint(v)} {
				if strings.Contains(out, leakSecret) || strings.Contains(strings.ToLower(out), hex) {
					t.Errorf("%s %s: %s", name, verb, out)
				}
			}
		}
		if _, ok := v.(json.Marshaler); ok {
			if out, err := json.Marshal(v); err == nil || strings.Contains(string(out), leakSecret) {
				t.Errorf("%s: marshaled %s", name, out)
			}
		}
	}
}

func Test_retry_after_of_a_whole_number_of_seconds_is_as_secs_plus_one(t *testing.T) {
	ur := newTestService(t, &emby.MemoryCredentialStore{}, "")
	now := time.Unix(1000, 0)
	ur.now = func() time.Time { return now }
	ur.note(NewRateLimited(300))
	assertErr(t, ur.checkBlocked(), NewRateLimited(301))
	now = now.Add(100*time.Second + 500*time.Millisecond)
	assertErr(t, ur.checkBlocked(), NewRateLimited(200))
	now = now.Add(time.Hour)
	if err := ur.checkBlocked(); err != nil {
		t.Fatal(err)
	}
}
