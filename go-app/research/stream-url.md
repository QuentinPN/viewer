# Stream URLs without token (emby-playback, wave 2B)

`playback::stream_url` (playback.rs) uses the `url` crate. It parses `server.api(raw)`, compares the origin and the `/emby/videos/` prefix of the resolved path, keeps `query_pairs()` except the token parameters, and writes them back with `query_pairs_mut().extend_pairs`. `transcode_reasons` reads the raw query with `form_urlencoded::parse`.

## Sources
- servo/rust-url docs, through context7 (`/servo/rust-url`):
  - `form_urlencoded::byte_serialize` keeps `* - . 0-9 A-Z _ a-z`, writes space as `+`, and writes every other byte as `%XX`;
  - `form_urlencoded::parse` turns `+` into a space, percent-decodes, then decodes UTF-8 lossily.
- WHATWG URL Standard, the basic URL parser and the urlencoded parser and serializer. It is already written out in `internal/emby/server_url.go` (`parseURL`, `parsePath`).

## Go port (`internal/emby/playback.go`)
- **Parsing.** `parseURL` from server_url.go gives the scheme, host, port and the resolved path (`..`, `%2e%2e` and `.` are resolved, and the PATH set is percent-encoded), so the prefix check sees the same path as Rust. `net/url` is not used: it neither resolves dot segments nor applies the WHATWG cleanup.
- **The query.** `parseURL` does not keep the query text, so `rawQuery` takes it after the same input cleanup: leading and trailing C0 controls and spaces trimmed, then tab, CR and LF removed. The query runs from the first `?` to the `#`.
- **Encoding.** The crate percent-encodes the query on parsing and `query_pairs()` decodes it again, so decoding the raw text directly gives the same pairs.
- **`formParse`.**
  - Splits on `&` only and skips empty pieces.
  - Splits each piece at the first `=`, so a lone `f` gives `("f", "")`.
  - Turns `+` into a space and decodes percent sequences leniently: `%zz` stays literal, where `url.QueryUnescape` would fail.
- **`formSerialize` / `formEncode`.**
  - Writes the pairs in their order: `url.Values.Encode` would sort them.
  - Always writes `name=value`, so `f=`.
  - `~` becomes `%7E`, where `url.QueryEscape` keeps it; `*` is kept.
  - `client.go` (2A) reuses `formEncode` for its own query strings.
- Covered by `Test_go_stream_url_keeps_query_order_and_encoding`, by the golden `playback.stream_url` and by the fake-server plan goldens, where order matters: `…PlaySessionId=ps2&AudioStreamIndex=2`, and `VideoCodec` before `TranscodeReasons`.

## Accepted divergence
- Invalid UTF-8 after percent-decoding: Rust's `from_utf8_lossy` writes one U+FFFD per maximal invalid subsequence, while Go's `strings.ToValidUTF8` writes one per run of invalid bytes. This only affects the text of a kept parameter that is not UTF-8. It cannot let a token through, because the key comparison is against ASCII names.
- Mutants with no observable effect, the same in Rust: the scheme and host comparisons and the backslash refusal never decide the outcome on their own. A `raw` starting with a single `/` cannot change the authority, and `\` is already a separator that the prefix check catches. They are kept as defence in depth, as in Rust.
