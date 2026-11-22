//go:build windows

package sysinfo

import (
	"strings"
	"testing"
	"unsafe"

	"golang.org/x/sys/windows"
)

func Test_decoder_profiles_map_to_codecs(t *testing.T) {
	av1 := mustGUID("{B8BE4CCB-CF53-46BA-8D59-D6B8A6DA5D2A}")
	mpeg2 := mustGUID("{EE27417F-5E28-4E65-BEEA-1D26B508ADC9}") // D3D11_DECODER_PROFILE_MPEG2_VLD
	hevc10 := mustGUID("{107AF0E0-EF1A-4D19-ABA8-67A163073D13}")
	got := codecsOf([]windows.GUID{av1, mpeg2, hevc10})
	if len(got) != 2 || got[0] != Hevc10 || got[1] != Av1 {
		t.Errorf("got %v, want [hevc10 av1]", got)
	}
	if got := codecsOf(nil); len(got) != 0 {
		t.Errorf("got %v", got)
	}
}

// The C layouts the syscalls fill in: a wrong size would corrupt memory or read zeros.
func Test_go_descriptor_layouts(t *testing.T) {
	if got := unsafe.Sizeof(adapterDesc1{}); got != 312 {
		t.Errorf("DXGI_ADAPTER_DESC1 is %d bytes, want 312", got)
	}
	if got := unsafe.Offsetof(adapterDesc1{}.Flags); got != 304 {
		t.Errorf("DXGI_ADAPTER_DESC1.Flags at %d, want 304", got)
	}
	if got := unsafe.Sizeof(outputDesc1{}); got != 152 {
		t.Errorf("DXGI_OUTPUT_DESC1 is %d bytes, want 152", got)
	}
	if got := unsafe.Offsetof(outputDesc1{}.ColorSpace); got != 100 {
		t.Errorf("DXGI_OUTPUT_DESC1.ColorSpace at %d, want 100", got)
	}
	if got := unsafe.Offsetof(outputDesc1{}.Monitor); got != 88 {
		t.Errorf("DXGI_OUTPUT_DESC1.Monitor at %d, want 88", got)
	}
}

// Runs the real DXGI / D3D11 probe on this machine. The adapter names are logged
// here only (never in the app's report, which the UI shows to its own user).
func Test_go_real_dxgi_probe_on_this_pc(t *testing.T) {
	factory, err := createFactory()
	if err != nil {
		t.Skipf("DXGI unavailable on this machine: %v", err)
	}
	defer factory.release()
	all := adapters(factory)
	defer releaseAll(all)
	if len(all) < 1 {
		t.Fatal("no DXGI adapter enumerated")
	}
	for i, a := range all {
		d, ok := desc1(a)
		if !ok {
			t.Errorf("adapter %d: GetDesc1 failed", i)
			continue
		}
		name := WideToString(d.Description[:])
		if name == "" {
			t.Errorf("adapter %d has an empty description", i)
		}
		t.Logf("adapter %d: %q vendor=0x%04X flags=%d", i, name, d.VendorID, d.Flags)
	}

	gpus := platformGPUs()
	for _, g := range gpus {
		driver := "<none>"
		if g.Driver != nil {
			driver = *g.Driver
		}
		t.Logf("gpu: %q driver=%s", g.Name, driver)
		if strings.TrimSpace(g.Name) == "" {
			t.Error("a GPU has an empty name")
		}
	}
	if len(gpus) == 0 {
		t.Log("no hardware adapter here (software renderer only)")
	}

	if dec := platformDecoders(); dec != nil {
		t.Logf("decoders: %v", *dec)
		if len(gpus) == 0 {
			t.Error("decoders read without a hardware adapter")
		}
	} else {
		t.Log("decoders: unknown")
	}
	for _, d := range platformDisplays() {
		t.Logf("display %s hdr=%v", d.Name, d.HDR)
		if !strings.HasPrefix(d.Name, `\\.\DISPLAY`) {
			t.Errorf("unexpected display name %q", d.Name)
		}
	}
}

func Test_go_registry_reads(t *testing.T) {
	os := platformOS()
	if os == nil {
		t.Fatal("no OS info on Windows")
	}
	if !strings.HasPrefix(os.Edition, "Windows") || os.Build == "" {
		t.Errorf("odd OS info: %+v", os)
	}
	t.Logf("os: %s build %s (display version present: %v)", os.Edition, os.Build, os.DisplayVersion != nil)
	if regString("NoSuchValueHere") != nil || regDword("NoSuchValueHere") != nil {
		t.Error("absent values must be nil")
	}
	// ProductName is a string: read as a DWORD it is absent; UBR the other way round.
	if regDword("ProductName") != nil || regString("UBR") != nil {
		t.Error("a value of the wrong type must be nil")
	}
}

func Test_go_guids_parse(t *testing.T) {
	// mustGUID panics on a typo; touch every table entry.
	if len(decoderProfiles) != 6 {
		t.Fatalf("%d profiles", len(decoderProfiles))
	}
	for i, p := range decoderProfiles {
		if p.codec != codecOrder[i] {
			t.Errorf("profile %d is %s, want %s", i, p.codec, codecOrder[i])
		}
	}
}
