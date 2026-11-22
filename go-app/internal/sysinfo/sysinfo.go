// Package sysinfo is what this PC says about itself, for Réglages › À propos
// (ADR 0116): the build, the Windows edition and build, the graphics adapters,
// their hardware decoders and the displays' HDR state. Port of
// src-tauri/src/system_info.rs. Read only: registry values, DXGI descriptions and a
// short-lived D3D11 video device; no shell, no cgo (see research/dxgi.md).
package sysinfo

import (
	"fmt"
	"strings"
	"unicode/utf16"
)

// Report is `SystemReport` (serde `rename_all = "camelCase"`; field order is the
// order of the Rust struct). Slices are never nil, so they serialize as `[]`.
type Report struct {
	// DebugBuild is the separate debug build (ADR 0105): Rust's `debug_assertions`.
	// Go has no such flag, so the caller says (see MAP.md, decisions of wave2A).
	DebugBuild bool `json:"debugBuild"`
	// OS is nil (JSON null) when the registry could not be read.
	OS *OsInfo `json:"os"`
	// GPUs are the hardware adapters, the software renderer left out; empty when
	// DXGI failed.
	GPUs []GpuInfo `json:"gpus"`
	// Decoders are the codecs the first hardware adapter decodes through D3D11
	// Video, the API of mpv's `d3d11va` hwdec; nil (JSON null) when its video device
	// could not be opened. A pointer, so that an empty list stays `[]`.
	Decoders *[]Codec `json:"decoders"`
	// Displays are each display of the hardware adapters; empty when none could be
	// read.
	Displays []DisplayInfo `json:"displays"`
}

// Codec is a hardware decoding profile the driver lists (DXVA/D3D11 Video profile
// GUIDs). The string is the serde camelCase name.
type Codec string

// The codecs, in the order of the Rust enum (which derives `Ord`).
const (
	H264        Codec = "h264"
	Hevc        Codec = "hevc"
	Hevc10      Codec = "hevc10"
	Vp9         Codec = "vp9"
	Vp9Profile2 Codec = "vp9Profile2"
	Av1         Codec = "av1"
)

// codecOrder is the declaration order of the Rust enum.
var codecOrder = []Codec{H264, Hevc, Hevc10, Vp9, Vp9Profile2, Av1}

// DisplayInfo is one display.
type DisplayInfo struct {
	// Name is `\.\DISPLAY1`: Windows' name, the one its display settings number.
	Name string `json:"name"`
	// HDR is on in Windows: the output's colour space is BT.2020 with PQ.
	HDR bool `json:"hdr"`
}

// OsInfo is the Windows edition and build.
type OsInfo struct {
	// Edition is "Windows 11 Pro", corrected from the registry's "Windows 10 Pro" on
	// Windows 11.
	Edition string `json:"edition"`
	// DisplayVersion is "23H2"; nil (null) on builds that do not store it.
	DisplayVersion *string `json:"displayVersion"`
	// Build is "22631.4460": build and update revision.
	Build string `json:"build"`
}

// GpuInfo is one graphics adapter.
type GpuInfo struct {
	Name string `json:"name"`
	// Driver is the user-mode driver version "32.0.15.6094"; nil (null) when DXGI
	// does not give it.
	Driver *string `json:"driver"`
}

// firstWindows11Build is the first build of Windows 11: the registry `ProductName`
// still says "Windows 10".
const firstWindows11Build = 22000

// Edition: "Windows 10 Pro" on build 22631 is "Windows 11 Pro".
func Edition(productName string, build uint32) string {
	name := strings.TrimSpace(productName)
	if build >= firstWindows11Build {
		if rest, ok := strings.CutPrefix(name, "Windows 10"); ok {
			return "Windows 11" + rest
		}
	}
	return name
}

// BuildLabel: "22631" and revision 4460 make "22631.4460".
func BuildLabel(currentBuild string, revision *uint32) string {
	if revision != nil {
		return fmt.Sprintf("%s.%d", strings.TrimSpace(currentBuild), *revision)
	}
	return strings.TrimSpace(currentBuild)
}

// DriverVersion gives the four 16-bit parts of DXGI's `UMDVersion`, high to low.
func DriverVersion(umd int64) string {
	v := uint64(umd)
	return fmt.Sprintf("%d.%d.%d.%d", (v>>48)&0xffff, (v>>32)&0xffff, (v>>16)&0xffff, v&0xffff)
}

// WideToString reads a UTF-16 buffer up to its first NUL (unpaired surrogates become
// U+FFFD, like `from_utf16_lossy`) and trims it.
func WideToString(wide []uint16) string {
	end := len(wide)
	for i, c := range wide {
		if c == 0 {
			end = i
			break
		}
	}
	return strings.TrimSpace(string(utf16.Decode(wide[:end])))
}

// sortCodecs orders codecs like the Rust `Ord` (declaration order).
func sortCodecs(found []Codec) []Codec {
	out := make([]Codec, 0, len(found))
	for _, c := range codecOrder {
		for _, f := range found {
			if f == c {
				out = append(out, c)
				break
			}
		}
	}
	return out
}

// Read is `report()`: reads this PC. debugBuild is reported as is.
func Read(debugBuild bool) Report {
	gpus, displays := platformGPUs(), platformDisplays()
	if gpus == nil {
		gpus = []GpuInfo{}
	}
	if displays == nil {
		displays = []DisplayInfo{}
	}
	return Report{
		DebugBuild: debugBuild,
		OS:         platformOS(),
		GPUs:       gpus,
		Decoders:   platformDecoders(),
		Displays:   displays,
	}
}

// utf16Of is the UTF-16 encoding of s.
func utf16Of(s string) []uint16 { return utf16.Encode([]rune(s)) }

// parseU32 is Rust's `str::parse::<u32>` on a trimmed string: digits only, optional
// leading `+`, no overflow.
func parseU32(s string) (uint32, bool) {
	s = strings.TrimPrefix(strings.TrimSpace(s), "+")
	if s == "" {
		return 0, false
	}
	var n uint64
	for _, c := range s {
		if c < '0' || c > '9' {
			return 0, false
		}
		n = n*10 + uint64(c-'0')
		if n > 0xffffffff {
			return 0, false
		}
	}
	return uint32(n), true
}
