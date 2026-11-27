package netaddr

import "testing"

func TestIsLocalHost(t *testing.T) {
	cases := []struct {
		host string
		want bool
	}{
		{"127.0.0.1", true},
		{"10.0.0.5", true},
		{"172.20.1.1", true},
		{"192.168.1.14", true},
		{"::1", true},
		{"localhost", true},
		{"LocalHost", true},
		{"nas.local", true},
		{"NAS.LOCAL", true},
		{"8.8.8.8", false},
		{"172.32.0.1", false},
		{"::ffff:127.0.0.1", false}, // Ipv6Addr::is_loopback is `::1` only
		{"fd00::1", false},
		{"request.exemple.fr", false},
		{"local", false},
		{"", false},
	}
	for _, c := range cases {
		if got := IsLocalHost(c.host); got != c.want {
			t.Errorf("IsLocalHost(%q) = %v, attendu %v", c.host, got, c.want)
		}
	}
}
