// Package netaddr holds network address rules shared by the integrations.
package netaddr

import (
	"net/netip"
	"strings"

	"viewer/internal/redact"
)

// IsLocalHost is `is_local` on the serialized host: IPv4 loopback or private, IPv6
// loopback, `localhost` or `*.local`. Plain `http://` is accepted only there.
func IsLocalHost(host string) bool {
	if addr, err := netip.ParseAddr(host); err == nil {
		if addr.Is4() {
			return addr.IsLoopback() || addr.IsPrivate()
		}
		// Ipv6Addr::is_loopback is `::1` only, not an IPv4-mapped loopback.
		return addr == netip.IPv6Loopback()
	}
	d := redact.ASCIILower(host)
	return d == "localhost" || strings.HasSuffix(d, ".local")
}
