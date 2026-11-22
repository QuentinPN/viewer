package emby

// Test-only exports for the external tests (package emby_test): artworkcache and
// request import emby, so tests that need them live outside the package.

import (
	"strings"
	"sync/atomic"
	"testing"
)

// Fixture constants of tests.rs.
const (
	FakeServerID = fakeServerID
	FakeUserID   = fakeUserID
	FakeToken    = fakeToken
	FakePassword = fakePassword
)

// FakeBrowseServer is a browse_like fake server: its address and the number of
// image requests it received.
func FakeBrowseServer(t *testing.T) (url string, imageFetches func() int) {
	t.Helper()
	server := startFakeEmby(t, fakeBrowseLike(new(atomic.Bool)))
	return server.URL, func() int {
		n := 0
		for _, r := range server.requests() {
			if strings.Contains(r.Path, "/Images/") {
				n++
			}
		}
		return n
	}
}

// FakeService is the service of `fixture()`: a memory store and a session.json in a
// fresh directory.
func FakeService(t *testing.T) *EmbyService {
	t.Helper()
	return newFixture(t).service
}
