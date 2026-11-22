//go:build windows

package emby

// Windows Credential Manager store, port of `WindowsCredentialStore`
// (src-tauri/src/emby/credentials.rs). It reads and writes the entries of the Rust
// `keyring` crate 3.6.3 (windows-native) byte for byte, so a user signed in with the
// Tauri app stays signed in (research/credentials.md):
//   - generic credential, target `{account}.{service}`, UserName `{account}`;
//   - Persist CRED_PERSIST_ENTERPRISE, comment `keyring v3.6.3`, no alias, no attribute;
//   - blob: the token in UTF-16 little-endian, without terminator.

import (
	"errors"
	"fmt"
	"syscall"
	"unicode/utf16"
	"unsafe"

	"golang.org/x/sys/windows"
)

const (
	credTypeGeneric       = 1
	credPersistEnterprise = 3
	// Limits checked by keyring before any call (wincred.h).
	credMaxUsernameLength          = 513
	credMaxGenericTargetNameLength = 32767
	credMaxStringLength            = 256
	credMaxCredentialBlobSize      = 5 * 512
	keyringComment                 = "keyring v3.6.3"
)

var (
	advapi32        = windows.NewLazySystemDLL("advapi32.dll")
	procCredReadW   = advapi32.NewProc("CredReadW")
	procCredWriteW  = advapi32.NewProc("CredWriteW")
	procCredDeleteW = advapi32.NewProc("CredDeleteW")
	procCredFree    = advapi32.NewProc("CredFree")
)

// credentialW is CREDENTIALW. Go's natural alignment gives the SDK layout on amd64
// and arm64 (pointer fields on 8-byte boundaries, 4 bytes of padding after
// CredentialBlobSize).
type credentialW struct {
	Flags              uint32
	Type               uint32
	TargetName         *uint16
	Comment            *uint16
	LastWritten        windows.Filetime
	CredentialBlobSize uint32
	CredentialBlob     *byte
	Persist            uint32
	AttributeCount     uint32
	Attributes         uintptr
	TargetAlias        *uint16
	UserName           *uint16
}

// WindowsCredentialStore keeps tokens in the Windows Credential Manager under
// service (CredentialServiceRelease or CredentialServiceDebug).
type WindowsCredentialStore struct {
	service string
}

func NewWindowsCredentialStore(service string) *WindowsCredentialStore {
	return &WindowsCredentialStore{service: service}
}

// keyringError is a `keyring::Error`; its text is what Rust puts in CredentialStore.
type keyringError struct {
	noEntry bool
	text    string
}

func (e keyringError) Error() string { return e.text }

var errNoEntry = keyringError{noEntry: true, text: "No matching entry found in secure storage"}

// windowsErrorText is the Display of keyring's `windows::Error`.
func windowsErrorText(code syscall.Errno) string {
	switch code {
	case windows.ERROR_NO_SUCH_LOGON_SESSION:
		return "Windows ERROR_NO_SUCH_LOGON_SESSION"
	case windows.ERROR_NOT_FOUND:
		return "Windows ERROR_NOT_FOUND"
	case 2202: // ERROR_BAD_USERNAME
		return "Windows ERROR_BAD_USERNAME"
	case windows.ERROR_INVALID_FLAGS:
		return "Windows ERROR_INVALID_FLAGS"
	case windows.ERROR_INVALID_PARAMETER:
		return "Windows ERROR_INVALID_PARAMETER"
	default:
		return fmt.Sprintf("Windows error code %d", uint32(code))
	}
}

// decodeError is keyring's `decode_error` for the last error of a failed call.
func decodeError(err error) error {
	var code syscall.Errno
	if !errors.As(err, &code) {
		return keyringError{text: "Platform secure storage failure: " + err.Error()}
	}
	switch code {
	case windows.ERROR_NOT_FOUND:
		return errNoEntry
	case windows.ERROR_NO_SUCH_LOGON_SESSION:
		return keyringError{text: "Couldn't access platform secure storage: " + windowsErrorText(code)}
	default:
		return keyringError{text: "Platform secure storage failure: " + windowsErrorText(code)}
	}
}

// target is keyring's `{user}.{service}`; validate is `validate_attributes` (lengths
// in UTF-8 bytes, as `String::len`).
func (s *WindowsCredentialStore) target(account string) (string, error) {
	target := account + "." + s.service
	switch {
	case len(account) > credMaxUsernameLength:
		return "", keyringError{text: fmt.Sprintf("Attribute 'user' is longer than platform limit of %d chars", credMaxUsernameLength)}
	case len(target) > credMaxGenericTargetNameLength:
		return "", keyringError{text: fmt.Sprintf("Attribute 'target' is longer than platform limit of %d chars", credMaxGenericTargetNameLength)}
	case len(keyringComment) > credMaxStringLength:
		return "", keyringError{text: fmt.Sprintf("Attribute 'comment' is longer than platform limit of %d chars", credMaxStringLength)}
	}
	return target, nil
}

func storeError(err error) error { return NewCredentialStore(err.Error()) }

// Save is `set_password`: the entry is created or replaced.
func (s *WindowsCredentialStore) Save(account string, token AccessToken) error {
	target, err := s.target(account)
	if err != nil {
		return storeError(err)
	}
	units := utf16.Encode([]rune(token.Expose()))
	if len(units)*2 > credMaxCredentialBlobSize {
		return storeError(keyringError{text: fmt.Sprintf("Attribute 'password encoded as UTF-16' is longer than platform limit of %d chars", credMaxCredentialBlobSize)})
	}
	blob := make([]byte, len(units)*2)
	for i, u := range units {
		blob[2*i] = byte(u)
		blob[2*i+1] = byte(u >> 8)
	}
	defer clear(blob)
	defer clear(units)
	if err := writeCredential(target, account, blob); err != nil {
		return storeError(err)
	}
	return nil
}

// Load is `get_password`: nil when there is no entry.
func (s *WindowsCredentialStore) Load(account string) (*AccessToken, error) {
	target, err := s.target(account)
	if err != nil {
		return nil, storeError(err)
	}
	raw, err := readCredential(target)
	if errors.Is(err, errNoEntry) {
		return nil, nil
	}
	if err != nil {
		return nil, storeError(err)
	}
	defer clear(raw.blob)
	password, ok := decodeUTF16LE(raw.blob)
	if !ok {
		return nil, storeError(keyringError{text: "Data is not UTF-8 encoded"})
	}
	t := NewAccessToken(password)
	return &t, nil
}

// Delete is `delete_credential`; a missing entry is not an error.
func (s *WindowsCredentialStore) Delete(account string) error {
	target, err := s.target(account)
	if err != nil {
		return storeError(err)
	}
	if err := deleteCredential(target); err != nil && !errors.Is(err, errNoEntry) {
		return storeError(err)
	}
	return nil
}

// decodeUTF16LE is keyring's `extract_password`: an odd length or a lone surrogate
// is refused (`String::from_utf16`).
func decodeUTF16LE(blob []byte) (string, bool) {
	if len(blob)%2 != 0 {
		return "", false
	}
	units := make([]uint16, len(blob)/2)
	defer clear(units)
	for i := range units {
		units[i] = uint16(blob[2*i]) | uint16(blob[2*i+1])<<8
	}
	for i := 0; i < len(units); i++ {
		u := units[i]
		switch {
		case 0xD800 <= u && u < 0xDC00:
			if i+1 >= len(units) || units[i+1] < 0xDC00 || units[i+1] >= 0xE000 {
				return "", false
			}
			i++
		case 0xDC00 <= u && u < 0xE000:
			return "", false
		}
	}
	return string(utf16.Decode(units)), true
}

// rawCredential is what CredReadW returned, copied out of the Windows allocation.
type rawCredential struct {
	credType  uint32
	persist   uint32
	target    string
	userName  string
	comment   string
	alias     string
	attrCount uint32
	blob      []byte
}

func writeCredential(target, userName string, blob []byte) error {
	targetW, err := windows.UTF16PtrFromString(target)
	if err != nil {
		return keyringError{text: "Platform secure storage failure: " + err.Error()}
	}
	userW, err := windows.UTF16PtrFromString(userName)
	if err != nil {
		return keyringError{text: "Platform secure storage failure: " + err.Error()}
	}
	commentW, _ := windows.UTF16PtrFromString(keyringComment)
	aliasW, _ := windows.UTF16PtrFromString("")
	cred := credentialW{
		Type:               credTypeGeneric,
		TargetName:         targetW,
		Comment:            commentW,
		CredentialBlobSize: uint32(len(blob)),
		Persist:            credPersistEnterprise,
		TargetAlias:        aliasW,
		UserName:           userW,
	}
	if len(blob) > 0 {
		cred.CredentialBlob = &blob[0]
	}
	// unsafe.Pointer: cred and every buffer it points to are Go memory kept alive by
	// the conversion inside the call expression; CredWriteW copies them and keeps no
	// reference after it returns.
	r, _, callErr := procCredWriteW.Call(uintptr(unsafe.Pointer(&cred)), 0)
	if r == 0 {
		return decodeError(callErr)
	}
	return nil
}

func readCredential(target string) (rawCredential, error) {
	targetW, err := windows.UTF16PtrFromString(target)
	if err != nil {
		return rawCredential{}, keyringError{text: "Platform secure storage failure: " + err.Error()}
	}
	var pcred *credentialW
	// unsafe.Pointer: targetW is Go memory alive for the call; &pcred receives a
	// pointer to a CREDENTIALW that Windows allocates and that only CredFree releases.
	r, _, callErr := procCredReadW.Call(uintptr(unsafe.Pointer(targetW)), credTypeGeneric, 0, uintptr(unsafe.Pointer(&pcred)))
	if r == 0 {
		// Failed: nothing was allocated, nothing to free.
		return rawCredential{}, decodeError(callErr)
	}
	// unsafe.Pointer: pcred is valid until CredFree below; its strings are
	// NUL-terminated and its blob holds CredentialBlobSize bytes, all inside the same
	// allocation. Everything is copied before the free.
	defer procCredFree.Call(uintptr(unsafe.Pointer(pcred)))
	out := rawCredential{
		credType:  pcred.Type,
		persist:   pcred.Persist,
		target:    windows.UTF16PtrToString(pcred.TargetName),
		userName:  windows.UTF16PtrToString(pcred.UserName),
		comment:   windows.UTF16PtrToString(pcred.Comment),
		alias:     windows.UTF16PtrToString(pcred.TargetAlias),
		attrCount: pcred.AttributeCount,
	}
	if n := pcred.CredentialBlobSize; n > 0 && pcred.CredentialBlob != nil {
		winBlob := unsafe.Slice(pcred.CredentialBlob, n)
		out.blob = append([]byte(nil), winBlob...)
		// keyring's `erase_secret`: the Windows copy is wiped before it is freed.
		clear(winBlob)
	}
	return out, nil
}

func deleteCredential(target string) error {
	targetW, err := windows.UTF16PtrFromString(target)
	if err != nil {
		return keyringError{text: "Platform secure storage failure: " + err.Error()}
	}
	// unsafe.Pointer: targetW is Go memory alive for the call, not kept by Windows.
	r, _, callErr := procCredDeleteW.Call(uintptr(unsafe.Pointer(targetW)), credTypeGeneric, 0)
	if r == 0 {
		return decodeError(callErr)
	}
	return nil
}
