package updater

import (
	"fmt"
	"strconv"
	"strings"
)

// Version is a SemVer 2.0.0 version, parsed as strictly as the semver crate
// (Version::from_str): three numeric parts without leading zeros, optional
// pre-release and build identifiers. Ordering follows the semver crate: the
// SemVer precedence, then the build metadata as a tie-breaker (empty first).
type Version struct {
	Major, Minor, Patch uint64
	Pre                 []string
	Build               []string
}

// ParseVersion parses s as semver; no surrounding space, no leading v.
func ParseVersion(s string) (Version, error) {
	var v Version
	core, build, hasBuild := strings.Cut(s, "+")
	core, pre, hasPre := strings.Cut(core, "-")
	parts := strings.Split(core, ".")
	if len(parts) != 3 {
		return Version{}, fmt.Errorf("invalid semver %q", s)
	}
	nums := [3]*uint64{&v.Major, &v.Minor, &v.Patch}
	for i, p := range parts {
		n, err := numeric(p)
		if err != nil {
			return Version{}, fmt.Errorf("invalid semver %q", s)
		}
		*nums[i] = n
	}
	if hasPre {
		ids, err := identifiers(pre, true)
		if err != nil {
			return Version{}, fmt.Errorf("invalid semver %q", s)
		}
		v.Pre = ids
	}
	if hasBuild {
		ids, err := identifiers(build, false)
		if err != nil {
			return Version{}, fmt.Errorf("invalid semver %q", s)
		}
		v.Build = ids
	}
	return v, nil
}

func isDigits(s string) bool {
	if s == "" {
		return false
	}
	for _, c := range s {
		if c < '0' || c > '9' {
			return false
		}
	}
	return true
}

func numeric(s string) (uint64, error) {
	if !isDigits(s) || (len(s) > 1 && s[0] == '0') {
		return 0, fmt.Errorf("bad number %q", s)
	}
	return strconv.ParseUint(s, 10, 64)
}

// identifiers splits dot-separated identifiers of [0-9A-Za-z-]; a numeric
// pre-release identifier has no leading zero.
func identifiers(s string, pre bool) ([]string, error) {
	ids := strings.Split(s, ".")
	for _, id := range ids {
		if id == "" {
			return nil, fmt.Errorf("empty identifier")
		}
		for _, c := range id {
			if !(c == '-' || c >= '0' && c <= '9' || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z') {
				return nil, fmt.Errorf("bad identifier %q", id)
			}
		}
		if pre && isDigits(id) && len(id) > 1 && id[0] == '0' {
			return nil, fmt.Errorf("leading zero in %q", id)
		}
	}
	return ids, nil
}

// String renders the version as the semver crate does.
func (v Version) String() string {
	s := fmt.Sprintf("%d.%d.%d", v.Major, v.Minor, v.Patch)
	if len(v.Pre) > 0 {
		s += "-" + strings.Join(v.Pre, ".")
	}
	if len(v.Build) > 0 {
		s += "+" + strings.Join(v.Build, ".")
	}
	return s
}

func cmpUint(a, b uint64) int {
	switch {
	case a < b:
		return -1
	case a > b:
		return 1
	}
	return 0
}

// Compare is -1, 0 or 1 as v is older than, equal to or newer than o.
func (v Version) Compare(o Version) int {
	if c := cmpUint(v.Major, o.Major); c != 0 {
		return c
	}
	if c := cmpUint(v.Minor, o.Minor); c != 0 {
		return c
	}
	if c := cmpUint(v.Patch, o.Patch); c != 0 {
		return c
	}
	// A version without pre-release is newer than any with one.
	switch {
	case len(v.Pre) == 0 && len(o.Pre) > 0:
		return 1
	case len(v.Pre) > 0 && len(o.Pre) == 0:
		return -1
	}
	if c := cmpIdentifiers(v.Pre, o.Pre); c != 0 {
		return c
	}
	// Build metadata: no precedence in SemVer, a tie-breaker in the semver crate
	// (empty first, then identifier by identifier).
	return cmpIdentifiers(v.Build, o.Build)
}

// cmpIdentifiers compares identifier lists: numeric ones by value and before
// alphanumeric ones, alphanumeric ones in ASCII order, a shorter list first when
// it is a prefix of the other.
func cmpIdentifiers(a, b []string) int {
	for i := 0; i < len(a) && i < len(b); i++ {
		x, y := a[i], b[i]
		xn, yn := isDigits(x), isDigits(y)
		switch {
		case xn && yn:
			nx := strings.TrimLeft(x, "0")
			ny := strings.TrimLeft(y, "0")
			if len(nx) != len(ny) {
				return cmpUint(uint64(len(nx)), uint64(len(ny)))
			}
			if c := strings.Compare(nx, ny); c != 0 {
				return c
			}
			if c := cmpUint(uint64(len(x)), uint64(len(y))); c != 0 {
				return c
			}
		case xn:
			return -1
		case yn:
			return 1
		default:
			if c := strings.Compare(x, y); c != 0 {
				return c
			}
		}
	}
	return cmpUint(uint64(len(a)), uint64(len(b)))
}
