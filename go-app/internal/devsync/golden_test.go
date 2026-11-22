package devsync

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"viewer/internal/emby"
)

// The events.sync_* goldens (golden.rs builds them by hand). Here each value is
// produced by the Go code: the status, notices and actions by the leader, the
// device list by TVDevices. No golden holds a float, so the comparison is byte
// for byte against the compacted golden (key order included).

const goldenDir = "../../testdata/golden"

func assertGolden(t *testing.T, name string, v any) {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join(goldenDir, name))
	if err != nil {
		t.Fatalf("golden %s: %v", name, err)
	}
	var want bytes.Buffer
	if err := json.Compact(&want, raw); err != nil {
		t.Fatalf("golden %s: %v", name, err)
	}
	got, err := json.Marshal(v)
	if err != nil {
		t.Fatalf("%s: marshal: %v", name, err)
	}
	if !bytes.Equal(got, want.Bytes()) {
		t.Errorf("%s\ngot    %s\ngolden %s", name, got, want.Bytes())
	}
}

// only returns the one effect of type T in fx.
func only[T Effect](t *testing.T, fx []Effect) T {
	t.Helper()
	var found []T
	for _, e := range fx {
		if v, ok := e.(T); ok {
			found = append(found, v)
		}
	}
	if len(found) != 1 {
		t.Fatalf("want one %T in %s", *new(T), show(fx))
	}
	return found[0]
}

func TestGolden_events_sync_status(t *testing.T) {
	l := &Leader{}
	assertGolden(t, "events.sync_status.idle.json", l.Status())
	invite(l, 0, snap("68397", false))
	assertGolden(t, "events.sync_status.waiting.json", l.Status())
	l.Handle(1000, snap("68397", true), tv(0, ready("68397")))
	assertGolden(t, "events.sync_status.linked_no_drift.json", l.Status())
	l.Handle(1100, nil, tv(1, Ping{T: 1, DriftMs: i64p(-120)}))
	assertGolden(t, "events.sync_status.linked.json", l.Status())
}

func TestGolden_events_sync_notice(t *testing.T) {
	s := snap("1", false)

	l := &Leader{}
	assertGolden(t, "events.sync_notice.following.json", only[Notice](t, l.Handle(0, s, tv(0, Join{}))))

	l = linked()
	assertGolden(t, "events.sync_notice.lost.json", only[Notice](t, l.Handle(1000+Silence, nil, Tick{})))

	l = &Leader{}
	invite(l, 0, s)
	assertGolden(t, "events.sync_notice.no_answer.json", only[Notice](t, l.Handle(ReplyTimeout, s, Tick{})))

	l = &Leader{}
	invite(l, 0, s)
	assertGolden(t, "events.sync_notice.busy.json", only[Notice](t, l.Handle(100, s, tv(0, bye(ByeBusy)))))
}

func TestGolden_events_sync_action(t *testing.T) {
	l := linked()
	assertGolden(t, "events.sync_action.next.json", only[UIAction](t, l.Handle(1100, nil, tv(1, Cmd{Action: CmdNext}))))
	assertGolden(t, "events.sync_action.previous.json", only[UIAction](t, l.Handle(1200, nil, tv(2, Cmd{Action: CmdPrevious}))))
	assertGolden(t, "events.sync_action.stop.json", only[UIAction](t, l.Handle(1300, nil, tv(3, bye(ByeStopped)))))
}

func TestGolden_events_sync_devices(t *testing.T) {
	client, name := TVClient, "Shield"
	s := []emby.SessionInfoDto{{ID: "a1b2c3", Client: &client, DeviceName: &name, SupportsRemoteControl: true}}
	assertGolden(t, "events.sync_devices.json", TVDevices(s, "dev-pc"))
}
