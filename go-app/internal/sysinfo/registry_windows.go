//go:build windows

package sysinfo

import (
	"encoding/binary"

	"golang.org/x/sys/windows/registry"
)

const currentVersion = `SOFTWARE\Microsoft\Windows NT\CurrentVersion`

// maxRegistryChars mirrors the 256-unit buffer of the Rust `reg_string`: a longer
// value makes RegGetValueW fail with ERROR_MORE_DATA, and the value counts as absent.
const maxRegistryChars = 255

func openCurrentVersion() (registry.Key, error) {
	return registry.OpenKey(registry.LOCAL_MACHINE, currentVersion, registry.QUERY_VALUE)
}

// regString reads a REG_SZ of HKLM\...\CurrentVersion; nil when absent, of another
// type, too long, or empty after trimming.
func regString(name string) *string {
	k, err := openCurrentVersion()
	if err != nil {
		return nil
	}
	defer k.Close()
	v, typ, err := k.GetStringValue(name)
	if err != nil || typ != registry.SZ {
		return nil
	}
	if len(utf16Of(v)) > maxRegistryChars {
		return nil
	}
	s := WideToString(utf16Of(v))
	if s == "" {
		return nil
	}
	return &s
}

// regDword reads a REG_DWORD; nil when absent or of another type.
func regDword(name string) *uint32 {
	k, err := openCurrentVersion()
	if err != nil {
		return nil
	}
	defer k.Close()
	var buf [4]byte
	n, typ, err := k.GetValue(name, buf[:])
	if err != nil || typ != registry.DWORD || n != 4 {
		return nil
	}
	v := binary.LittleEndian.Uint32(buf[:])
	return &v
}

func platformOS() *OsInfo {
	product := regString("ProductName")
	currentBuild := regString("CurrentBuild")
	if product == nil || currentBuild == nil {
		return nil
	}
	number, _ := parseU32(*currentBuild) // unparsable: 0, like `unwrap_or(0)`
	return &OsInfo{
		Edition:        Edition(*product, number),
		DisplayVersion: regString("DisplayVersion"),
		Build:          BuildLabel(*currentBuild, regDword("UBR")),
	}
}
