package emby

// Server address validation (ADR 0004), port of src-tauri/src/emby/server_url.rs:
// http(s) only, no credentials, query or fragment, and plain HTTP only towards local
// network addresses.
//
// The Rust code parses with the `url` crate (WHATWG URL Standard). net/url differs
// on default ports, host case, numeric IPv4 forms, IPv6 form, dot segments,
// backslashes and tab/newline stripping, several of which change IsLocal; so the
// WHATWG steps for special schemes are written out below. Divergences from the
// crate are listed in go-app/research/server-url.md. A non-ASCII host goes through
// UTS 46 (golang.org/x/net/idna) to punycode, as the crate does.

import (
	"strconv"
	"strings"
	"unicode/utf8"

	"golang.org/x/net/idna"
)

// ServerURL is a normalized Emby server base URL, without the `/emby` API prefix.
type ServerURL struct {
	scheme string
	host   urlHost
	port   int // -1: none (or the scheme's default)
	path   string
}

type hostKind int

const (
	hostDomain hostKind = iota
	hostIPv4
	hostIPv6
)

type urlHost struct {
	kind   hostKind
	domain string
	ipv4   uint32
	ipv6   [8]uint16
}

// ParseServerURL validates and normalizes what the user typed. Errors are EmbyError
// values: InvalidUrl, or InsecureRemoteUrl for plain HTTP to a non-local host.
func ParseServerURL(input string) (ServerURL, error) {
	trimmed := strings.TrimSpace(input)
	if trimmed == "" {
		return ServerURL{}, NewInvalidURL("adresse vide")
	}
	// Users often type "192.0.2.10:8096" without a scheme. Default to http: the
	// local-network rule below still refuses plain HTTP to public hosts.
	withScheme := trimmed
	if !strings.Contains(trimmed, "://") {
		withScheme = "http://" + trimmed
	}
	p, msg := parseURL(withScheme)
	if msg != "" {
		return ServerURL{}, NewInvalidURL(msg)
	}
	if p.scheme != "http" && p.scheme != "https" {
		return ServerURL{}, NewInvalidURL("schéma « " + p.scheme + " » non pris en charge")
	}
	if p.credentials {
		return ServerURL{}, NewInvalidURL("l'adresse ne doit pas contenir d'identifiants")
	}
	if p.query || p.fragment {
		return ServerURL{}, NewInvalidURL("l'adresse ne doit contenir ni « ? » ni « # »")
	}
	// Keep a reverse-proxy prefix, drop a trailing slash and a typed "/emby" or
	// "/web/..." suffix copied from the browser address bar. As in Rust, only the
	// first "/web" of the path is looked at (MAP.md, Bugs relevés).
	path := strings.TrimRight(p.path, "/")
	if i := strings.Index(path, "/web"); i >= 0 && (path[i:] == "/web" || strings.HasPrefix(path[i:], "/web/")) {
		path = path[:i]
	}
	path = strings.TrimSuffix(path, "/emby")
	if path == "" {
		path = "/"
	}
	u := ServerURL{scheme: p.scheme, host: p.host, port: p.port, path: path}
	if u.scheme == "http" && !u.IsLocal() {
		return ServerURL{}, ErrInsecureRemoteURL
	}
	return u, nil
}

// IsHTTPS reports an https:// address.
func (u ServerURL) IsHTTPS() bool { return u.scheme == "https" }

// IsLocal is true for loopback, private, link-local and well-known local DNS suffixes.
func (u ServerURL) IsLocal() bool {
	switch u.host.kind {
	case hostIPv4:
		ip := u.host.ipv4
		a, b := ip>>24, (ip>>16)&0xff
		return a == 127 || a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168) ||
			(a == 169 && b == 254)
	case hostIPv6:
		s := u.host.ipv6
		loopback := s == [8]uint16{0, 0, 0, 0, 0, 0, 0, 1}
		return loopback || s[0]&0xfe00 == 0xfc00 || s[0]&0xffc0 == 0xfe80
	default:
		d := strings.ToLower(u.host.domain)
		if d == "localhost" {
			return true
		}
		for _, suffix := range []string{".localhost", ".local", ".lan", ".home.arpa"} {
			if strings.HasSuffix(d, suffix) {
				return true
			}
		}
		return false
	}
}

// API is the absolute URL of an API path, e.g. API("/Users/AuthenticateByName").
func (u ServerURL) API(path string) string { return u.String() + "/emby" + path }

// String is the base URL without trailing slash, as shown to the user and persisted.
func (u ServerURL) String() string {
	return strings.TrimRight(u.serialize(), "/")
}

func (u ServerURL) serialize() string {
	var b strings.Builder
	b.WriteString(u.scheme)
	b.WriteString("://")
	b.WriteString(u.host.String())
	if u.port >= 0 {
		b.WriteByte(':')
		b.WriteString(strconv.Itoa(u.port))
	}
	b.WriteString(u.path)
	return b.String()
}

func (h urlHost) String() string {
	switch h.kind {
	case hostIPv4:
		ip := h.ipv4
		return strconv.Itoa(int(ip>>24)) + "." + strconv.Itoa(int(ip>>16&0xff)) + "." +
			strconv.Itoa(int(ip>>8&0xff)) + "." + strconv.Itoa(int(ip&0xff))
	case hostIPv6:
		return "[" + serializeIPv6(h.ipv6) + "]"
	default:
		return h.domain
	}
}

// ------------------------------------------------------------ WHATWG parsing

type parsedURL struct {
	scheme      string
	credentials bool // a non-empty username or password
	host        urlHost
	port        int
	path        string
	query       bool
	fragment    bool
}

// Parse error texts of the url crate (ParseError's Display), kept so the messages
// read the same; the golden compares only the kind for them.
const (
	errEmptyHost   = "empty host"
	errInvalidPort = "invalid port number"
	errIPv4        = "invalid IPv4 address"
	errIPv6        = "invalid IPv6 address"
	errDomainChar  = "invalid domain character"
	errNoBase      = "relative URL without a base"
	errIDNA        = "invalid international domain name"
)

// hostIDNA is the UTS 46 profile of the url crate: non-transitional, hyphens and DNS
// lengths unchecked, bidi and joiners checked.
var hostIDNA = idna.New(idna.MapForLookup(), idna.Transitional(false), idna.CheckHyphens(false),
	idna.VerifyDNSLength(false), idna.BidiRule(), idna.CheckJoiners(true))

var defaultPorts = map[string]int{"http": 80, "https": 443, "ws": 80, "wss": 443, "ftp": 21}

// parseURL runs the WHATWG basic URL parser without a base. The authority and path
// are parsed for the special schemes except file; for file and the non-special
// schemes only the scheme is read: ParseServerURL refuses them anyway. A non-empty
// second result is the failure text.
func parseURL(input string) (parsedURL, string) {
	// Leading and trailing C0 controls and spaces go, then every tab and newline.
	input = strings.TrimFunc(input, func(r rune) bool { return r <= 0x20 })
	input = strings.Map(func(r rune) rune {
		if r == '\t' || r == '\n' || r == '\r' {
			return -1
		}
		return r
	}, input)

	colon := -1
	for i := 0; i < len(input); i++ {
		c := input[i]
		if c == ':' {
			colon = i
			break
		}
		alpha := (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
		if !alpha && (i == 0 || !((c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.')) {
			return parsedURL{}, errNoBase
		}
	}
	if colon <= 0 {
		return parsedURL{}, errNoBase
	}
	out := parsedURL{scheme: strings.ToLower(input[:colon]), port: -1}
	defaultPort, special := defaultPorts[out.scheme]
	if !special {
		return out, "" // file and non-special schemes: refused by the caller
	}
	rest := input[colon+1:]
	// Special authority: any number of / and \ before it.
	rest = strings.TrimLeft(rest, `/\`)
	end := strings.IndexAny(rest, `/\?#`)
	if end < 0 {
		end = len(rest)
	}
	authority, tail := rest[:end], rest[end:]

	hostPort := authority
	if at := strings.LastIndexByte(authority, '@'); at >= 0 {
		user, pass, _ := strings.Cut(authority[:at], ":")
		out.credentials = user != "" || pass != ""
		hostPort = authority[at+1:]
		if hostPort == "" {
			return parsedURL{}, errEmptyHost
		}
	}
	hostText, portText, hasPort := splitHostPort(hostPort)
	if hostText == "" {
		return parsedURL{}, errEmptyHost
	}
	host, msg := parseHost(hostText)
	if msg != "" {
		return parsedURL{}, msg
	}
	out.host = host
	if hasPort && portText != "" {
		port, ok := parsePort(portText)
		if !ok {
			return parsedURL{}, errInvalidPort
		}
		if port != defaultPort {
			out.port = port
		}
	}

	pathText := tail
	if i := strings.IndexAny(tail, "?#"); i >= 0 {
		pathText = tail[:i]
		q := tail[i:]
		if q[0] == '?' {
			out.query = true
		}
		out.fragment = strings.Contains(q, "#")
	}
	out.path = parsePath(pathText)
	return out, ""
}

// splitHostPort splits at the first ':' outside brackets.
func splitHostPort(s string) (host, port string, hasPort bool) {
	inBrackets := false
	for i := 0; i < len(s); i++ {
		switch s[i] {
		case '[':
			inBrackets = true
		case ']':
			inBrackets = false
		case ':':
			if !inBrackets {
				return s[:i], s[i+1:], true
			}
		}
	}
	return s, "", false
}

func parsePort(s string) (int, bool) {
	n := 0
	for i := 0; i < len(s); i++ {
		c := s[i]
		if c < '0' || c > '9' {
			return 0, false
		}
		n = n*10 + int(c-'0')
		if n > 65535 {
			return 0, false
		}
	}
	return n, true
}

func parseHost(s string) (urlHost, string) {
	if s[0] == '[' {
		if s[len(s)-1] != ']' {
			return urlHost{}, errIPv6
		}
		pieces, ok := parseIPv6(s[1 : len(s)-1])
		if !ok {
			return urlHost{}, errIPv6
		}
		return urlHost{kind: hostIPv6, ipv6: pieces}, ""
	}
	decoded := percentDecode(s)
	if !utf8.ValidString(decoded) {
		return urlHost{}, errIDNA
	}
	for i := 0; i < len(decoded); i++ {
		if decoded[i] >= 0x80 {
			ascii, err := hostIDNA.ToASCII(decoded)
			if err != nil {
				return urlHost{}, errIDNA
			}
			decoded = ascii
			break
		}
	}
	domain := strings.ToLower(decoded)
	if domain == "" {
		return urlHost{}, errEmptyHost
	}
	for i := 0; i < len(domain); i++ {
		if isForbiddenDomainByte(domain[i]) {
			return urlHost{}, errDomainChar
		}
	}
	if endsInNumber(domain) {
		ip, ok := parseIPv4(domain)
		if !ok {
			return urlHost{}, errIPv4
		}
		return urlHost{kind: hostIPv4, ipv4: ip}, ""
	}
	return urlHost{kind: hostDomain, domain: domain}, ""
}

func isForbiddenDomainByte(c byte) bool {
	if c <= 0x1f || c == 0x7f {
		return true
	}
	return strings.IndexByte(" #%/:<>?@[\\]^|", c) >= 0
}

func percentDecode(s string) string {
	if !strings.Contains(s, "%") {
		return s
	}
	var b strings.Builder
	for i := 0; i < len(s); i++ {
		if s[i] == '%' && i+2 < len(s) && isHex(s[i+1]) && isHex(s[i+2]) {
			b.WriteByte(hexVal(s[i+1])<<4 | hexVal(s[i+2]))
			i += 2
			continue
		}
		b.WriteByte(s[i])
	}
	return b.String()
}

func isHex(c byte) bool {
	return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
}

func hexVal(c byte) byte {
	switch {
	case c >= 'a':
		return c - 'a' + 10
	case c >= 'A':
		return c - 'A' + 10
	default:
		return c - '0'
	}
}

// endsInNumber: the last label (a trailing empty one ignored) is decimal digits or 0x-hex.
func endsInNumber(domain string) bool {
	parts := strings.Split(domain, ".")
	if parts[len(parts)-1] == "" {
		if len(parts) == 1 {
			return false
		}
		parts = parts[:len(parts)-1]
	}
	last := parts[len(parts)-1]
	if last != "" && strings.Trim(last, "0123456789") == "" {
		return true
	}
	if len(last) >= 2 && last[0] == '0' && (last[1] == 'x' || last[1] == 'X') {
		for i := 2; i < len(last); i++ {
			if !isHex(last[i]) {
				return false
			}
		}
		return true
	}
	return false
}

func parseIPv4(s string) (uint32, bool) {
	parts := strings.Split(s, ".")
	if parts[len(parts)-1] == "" && len(parts) > 1 {
		parts = parts[:len(parts)-1]
	}
	if len(parts) > 4 {
		return 0, false
	}
	numbers := make([]uint64, len(parts))
	for i, p := range parts {
		n, ok := parseIPv4Number(p)
		if !ok {
			return 0, false
		}
		numbers[i] = n
	}
	for _, n := range numbers[:len(numbers)-1] {
		if n > 255 {
			return 0, false
		}
	}
	last := numbers[len(numbers)-1]
	if last >= uint64(1)<<(8*(5-len(numbers))) {
		return 0, false
	}
	ip := last
	for i, n := range numbers[:len(numbers)-1] {
		ip += n << (8 * (3 - i))
	}
	return uint32(ip), true
}

// parseIPv4Number reads decimal, 0x hexadecimal or 0-led octal; values past 2^32 are
// capped there, which every caller refuses.
func parseIPv4Number(s string) (uint64, bool) {
	if s == "" {
		return 0, false
	}
	radix := uint64(10)
	switch {
	case len(s) >= 2 && s[0] == '0' && (s[1] == 'x' || s[1] == 'X'):
		radix, s = 16, s[2:]
	case len(s) >= 2 && s[0] == '0':
		radix, s = 8, s[1:]
	}
	var n uint64
	for i := 0; i < len(s); i++ {
		c := s[i]
		var d uint64
		switch {
		case c >= '0' && c <= '9':
			d = uint64(c - '0')
		case radix == 16 && isHex(c):
			d = uint64(hexVal(c))
		default:
			return 0, false
		}
		if d >= radix {
			return 0, false
		}
		if n <= 1<<32 {
			n = n*radix + d
		}
	}
	return n, true
}

// parseIPv6 is the WHATWG IPv6 parser (no zone identifier).
func parseIPv6(s string) ([8]uint16, bool) {
	var addr [8]uint16
	piece, compress, p := 0, -1, 0
	at := func(i int) int {
		if i < len(s) {
			return int(s[i])
		}
		return -1
	}
	if at(p) == ':' {
		if at(p+1) != ':' {
			return addr, false
		}
		p += 2
		piece++
		compress = piece
	}
	for at(p) != -1 {
		if piece == 8 {
			return addr, false
		}
		if at(p) == ':' {
			if compress != -1 {
				return addr, false
			}
			p++
			piece++
			compress = piece
			continue
		}
		value, length := 0, 0
		for length < 4 && at(p) != -1 && isHex(byte(at(p))) {
			value = value*16 + int(hexVal(byte(at(p))))
			p++
			length++
		}
		if at(p) == '.' {
			if length == 0 {
				return addr, false
			}
			p -= length
			if piece > 6 {
				return addr, false
			}
			seen := 0
			for at(p) != -1 {
				v4 := -1
				if seen > 0 {
					if at(p) == '.' && seen < 4 {
						p++
					} else {
						return addr, false
					}
				}
				if c := at(p); c < '0' || c > '9' {
					return addr, false
				}
				for c := at(p); c >= '0' && c <= '9'; c = at(p) {
					n := c - '0'
					switch v4 {
					case -1:
						v4 = n
					case 0:
						return addr, false
					default:
						v4 = v4*10 + n
					}
					if v4 > 255 {
						return addr, false
					}
					p++
				}
				addr[piece] = addr[piece]*0x100 + uint16(v4)
				seen++
				if seen == 2 || seen == 4 {
					piece++
				}
			}
			if seen != 4 {
				return addr, false
			}
			break
		} else if at(p) == ':' {
			p++
			if at(p) == -1 {
				return addr, false
			}
		} else if at(p) != -1 {
			return addr, false
		}
		addr[piece] = uint16(value)
		piece++
	}
	if compress != -1 {
		swaps := piece - compress
		piece = 7
		for piece != 0 && swaps > 0 {
			addr[piece], addr[compress+swaps-1] = addr[compress+swaps-1], addr[piece]
			piece--
			swaps--
		}
	} else if piece != 8 {
		return addr, false
	}
	return addr, true
}

// serializeIPv6: lowercase hex pieces, the first longest run of two or more zero
// pieces written "::" (never the dotted IPv4 form).
func serializeIPv6(a [8]uint16) string {
	start, best := -1, 1
	for i := 0; i < 8; {
		if a[i] != 0 {
			i++
			continue
		}
		j := i
		for j < 8 && a[j] == 0 {
			j++
		}
		if j-i > best {
			start, best = i, j-i
		}
		i = j
	}
	var b strings.Builder
	for i := 0; i < 8; i++ {
		if i == start {
			if i == 0 {
				b.WriteString("::")
			} else {
				b.WriteString(":")
			}
			i += best - 1
			continue
		}
		b.WriteString(strconv.FormatUint(uint64(a[i]), 16))
		if i != 7 {
			b.WriteByte(':')
		}
	}
	return b.String()
}

// parsePath is the WHATWG path state of a special URL: / and \ separate segments,
// "." and ".." (also written %2e) are resolved, the path percent-encode set applied.
func parsePath(s string) string {
	if s != "" {
		s = s[1:] // the separator that ended the authority
	}
	var path []string
	start := 0
	for i := 0; i <= len(s); i++ {
		if i < len(s) && s[i] != '/' && s[i] != '\\' {
			continue
		}
		buf := s[start:i]
		last := i == len(s)
		start = i + 1
		switch {
		case isDoubleDot(buf):
			if len(path) > 0 {
				path = path[:len(path)-1]
			}
			if last {
				path = append(path, "")
			}
		case isSingleDot(buf):
			if last {
				path = append(path, "")
			}
		default:
			path = append(path, percentEncodePath(buf))
		}
	}
	return "/" + strings.Join(path, "/")
}

func isSingleDot(s string) bool { return s == "." || strings.EqualFold(s, "%2e") }

func isDoubleDot(s string) bool {
	switch strings.ToLower(s) {
	case "..", ".%2e", "%2e.", "%2e%2e":
		return true
	}
	return false
}

// percentEncodePath applies the url crate's PATH set: C0 controls, space, ", #, <,
// >, ?, `, {, }, DEL and every non-ASCII byte.
func percentEncodePath(s string) string {
	const hexDigits = "0123456789ABCDEF"
	var b strings.Builder
	for i := 0; i < len(s); i++ {
		c := s[i]
		if c < 0x20 || c >= 0x7f || strings.IndexByte(" \"#<>?`{}", c) >= 0 {
			b.WriteByte('%')
			b.WriteByte(hexDigits[c>>4])
			b.WriteByte(hexDigits[c&0xf])
			continue
		}
		b.WriteByte(c)
	}
	return b.String()
}
