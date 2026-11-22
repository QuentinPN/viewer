package sysinfo

import (
	"encoding/json"
	"os"
	"runtime"
	"testing"
	"unicode/utf16"
)

// Tests of system_info.rs, same names with the `Test_` prefix.

func Test_windows_11_is_named_from_its_build(t *testing.T) {
	for _, c := range []struct {
		product string
		build   uint32
		want    string
	}{
		{"Windows 10 Pro", 22631, "Windows 11 Pro"},
		{"Windows 10 Pro N", 19045, "Windows 10 Pro N"},
		{" Windows 10 Home ", 22000, "Windows 11 Home"},
		{"Windows Server 2022", 20348, "Windows Server 2022"},
	} {
		if got := Edition(c.product, c.build); got != c.want {
			t.Errorf("Edition(%q, %d) = %q, want %q", c.product, c.build, got, c.want)
		}
	}
}

func Test_build_carries_the_update_revision(t *testing.T) {
	rev := uint32(5131)
	if got := BuildLabel("19045", &rev); got != "19045.5131" {
		t.Errorf("got %q", got)
	}
	if got := BuildLabel("19045", nil); got != "19045" {
		t.Errorf("got %q", got)
	}
}

func Test_driver_version_splits_the_umd_parts(t *testing.T) {
	umd := int64(32)<<48 | 15<<16 | 6094
	if got := DriverVersion(umd); got != "32.0.15.6094" {
		t.Errorf("got %q", got)
	}
}

func Test_wide_strings_stop_at_nul(t *testing.T) {
	var buf [8]uint16
	copy(buf[:], utf16.Encode([]rune("GPU")))
	if got := WideToString(buf[:]); got != "GPU" {
		t.Errorf("got %q", got)
	}
}

// print_report is `#[ignore]` in Rust: it prints what this PC reports.
func Test_print_report(t *testing.T) {
	if os.Getenv("VIEWER_PRINT_REPORT") == "" {
		t.Skip("ignored by default, like `#[ignore]`: set VIEWER_PRINT_REPORT=1 and use -v")
	}
	b, _ := json.MarshalIndent(Read(true), "", "  ")
	t.Logf("%s", b)
}

func Test_report_is_read_on_this_pc(t *testing.T) {
	// Read only; on Windows the registry always has the edition.
	for _, debug := range []bool{true, false} {
		r := Read(debug)
		if r.DebugBuild != debug {
			t.Errorf("DebugBuild = %v, want %v", r.DebugBuild, debug)
		}
		if runtime.GOOS == "windows" && r.OS == nil {
			t.Error("os is nil on Windows")
		}
	}
}

// --- Go-only tests ---------------------------------------------------------

func Test_go_report_json_shape(t *testing.T) {
	ver := "23H2"
	drv := "32.0.15.6094"
	decoders := []Codec{H264, Hevc10, Av1}
	r := Report{
		DebugBuild: true,
		OS:         &OsInfo{Edition: "Windows 11 Pro", DisplayVersion: &ver, Build: "22631.4460"},
		GPUs:       []GpuInfo{{Name: "GPU", Driver: &drv}, {Name: "Other"}},
		Decoders:   &decoders,
		Displays:   []DisplayInfo{{Name: `\\.\DISPLAY1`, HDR: true}},
	}
	got, _ := json.Marshal(r)
	// serde's output for the same value: struct order, camelCase, Option as null.
	want := `{"debugBuild":true,"os":{"edition":"Windows 11 Pro","displayVersion":"23H2","build":"22631.4460"},` +
		`"gpus":[{"name":"GPU","driver":"32.0.15.6094"},{"name":"Other","driver":null}],` +
		`"decoders":["h264","hevc10","av1"],"displays":[{"name":"\\\\.\\DISPLAY1","hdr":true}]}`
	if string(got) != want {
		t.Errorf("got  %s\nwant %s", got, want)
	}

	empty := []Codec{}
	got, _ = json.Marshal(Report{GPUs: []GpuInfo{}, Decoders: &empty, Displays: []DisplayInfo{}})
	want = `{"debugBuild":false,"os":null,"gpus":[],"decoders":[],"displays":[]}`
	if string(got) != want {
		t.Errorf("got  %s\nwant %s", got, want)
	}
	got, _ = json.Marshal(Report{GPUs: []GpuInfo{}, Displays: []DisplayInfo{}})
	want = `{"debugBuild":false,"os":null,"gpus":[],"decoders":null,"displays":[]}`
	if string(got) != want {
		t.Errorf("got  %s\nwant %s", got, want)
	}
}

func Test_go_codec_names_and_order(t *testing.T) {
	got, _ := json.Marshal(codecOrder)
	if string(got) != `["h264","hevc","hevc10","vp9","vp9Profile2","av1"]` {
		t.Errorf("got %s", got)
	}
	sorted := sortCodecs([]Codec{Av1, Vp9Profile2, H264, Hevc10, Hevc})
	want := []Codec{H264, Hevc, Hevc10, Vp9Profile2, Av1}
	if len(sorted) != len(want) {
		t.Fatalf("got %v", sorted)
	}
	for i := range want {
		if sorted[i] != want[i] {
			t.Fatalf("got %v, want %v", sorted, want)
		}
	}
}

func Test_go_edge_cases(t *testing.T) {
	if got := Edition("Windows 10", 22000); got != "Windows 11" {
		t.Errorf("got %q", got)
	}
	if got := Edition("Windows 10 Pro", 21999); got != "Windows 10 Pro" {
		t.Errorf("got %q", got)
	}
	// Negative i64 is read as u64, like `umd as u64`.
	if got := DriverVersion(-1); got != "65535.65535.65535.65535" {
		t.Errorf("got %q", got)
	}
	// An unpaired surrogate is replaced, like from_utf16_lossy.
	if got := WideToString([]uint16{'a', 0xD800, 'b', 0, 'c'}); got != "a�b" {
		t.Errorf("got %q", got)
	}
	// Trimmed, and a buffer without NUL is read whole.
	if got := WideToString(utf16.Encode([]rune("  GPU 1 "))); got != "GPU 1" {
		t.Errorf("got %q", got)
	}
	for in, want := range map[string]uint32{"22631": 22631, " 19045 ": 19045, "+7": 7} {
		if got, ok := parseU32(in); !ok || got != want {
			t.Errorf("parseU32(%q) = %d, %v", in, got, ok)
		}
	}
	for _, in := range []string{"", "x", "-1", "4294967296", "12 3", "+"} {
		if _, ok := parseU32(in); ok {
			t.Errorf("parseU32(%q) accepted", in)
		}
	}
}
