// Package formenc is the `application/x-www-form-urlencoded` serializer of the url
// crate (`form_urlencoded`), shared by every query string built here.
package formenc

import "strings"

// Encode is `form_urlencoded::byte_serialize`: ASCII alphanumerics and `*-._` kept,
// space as `+`, every other byte as uppercase %XX.
func Encode(b *strings.Builder, s string) {
	const hexDigits = "0123456789ABCDEF"
	for i := 0; i < len(s); i++ {
		c := s[i]
		switch {
		case (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
			c == '*' || c == '-' || c == '.' || c == '_':
			b.WriteByte(c)
		case c == ' ':
			b.WriteByte('+')
		default:
			b.WriteByte('%')
			b.WriteByte(hexDigits[c>>4])
			b.WriteByte(hexDigits[c&0xf])
		}
	}
}

// Serialize is `form_urlencoded::Serializer`: `name=value` joined by `&`, in order.
func Serialize(pairs [][2]string) string {
	var b strings.Builder
	for i, pair := range pairs {
		if i > 0 {
			b.WriteByte('&')
		}
		Encode(&b, pair[0])
		b.WriteByte('=')
		Encode(&b, pair[1])
	}
	return b.String()
}
