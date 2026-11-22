//go:build windows

package tlsroots

import (
	"fmt"
	"runtime"
	"slices"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

const serverAuthOID = "1.3.6.1.5.5.7.3.1"

var (
	crypt32                     = windows.NewLazySystemDLL("crypt32.dll")
	procCertGetEnhancedKeyUsage = crypt32.NewProc("CertGetEnhancedKeyUsage")
	procCertVerifyTimeValidity  = crypt32.NewProc("CertVerifyTimeValidity")
)

// validUses is schannel's `ValidUses`: all uses, or an explicit list of OIDs.
type validUses struct {
	all  bool
	oids []string
}

func allowsServerAuth(u validUses) bool {
	return u.all || slices.Contains(u.oids, serverAuthOID)
}

// windowsRoots returns the DER of the roots Windows trusts for server
// authentication: the current user's view of the ROOT store (it includes the
// machine's roots), minus the expired, the ones disabled for server
// authentication and those in the Disallowed store.
func windowsRoots() ([][]byte, error) {
	disallowed := map[string]bool{}
	err := forEachCert("Disallowed", func(cert *windows.CertContext) error {
		disallowed[string(certDER(cert))] = true
		return nil
	})
	if err != nil {
		return nil, err
	}
	var roots [][]byte
	err = forEachCert("ROOT", func(cert *windows.CertContext) error {
		uses, err := certValidUses(cert)
		if err != nil {
			return err
		}
		der := certDER(cert)
		if allowsServerAuth(uses) && isTimeValid(cert) && !disallowed[string(der)] {
			roots = append(roots, der)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	return roots, nil
}

// forEachCert opens the current user's system store `name` and calls fn for every
// certificate, stopping at the first error.
func forEachCert(name string, fn func(*windows.CertContext) error) error {
	namePtr, err := windows.UTF16PtrFromString(name)
	if err != nil {
		return err
	}
	// The para argument of CertOpenStore is a pointer to the UTF-16 store name; it
	// is only read during the call, and namePtr stays alive until KeepAlive below.
	store, err := windows.CertOpenStore(windows.CERT_STORE_PROV_SYSTEM, 0, 0,
		windows.CERT_SYSTEM_STORE_CURRENT_USER, uintptr(unsafe.Pointer(namePtr)))
	runtime.KeepAlive(namePtr)
	if err != nil {
		return fmt.Errorf("magasin %s : %w", name, err)
	}
	defer windows.CertCloseStore(store, 0)

	var prev *windows.CertContext
	for {
		// The enumeration frees prev; it returns nil at the end (or on failure,
		// which ends the walk the same way schannel's iterator does).
		cert, _ := windows.CertEnumCertificatesInStore(store, prev)
		if cert == nil {
			return nil
		}
		if err := fn(cert); err != nil {
			_ = windows.CertFreeCertificateContext(cert)
			return err
		}
		prev = cert
	}
}

// certDER copies the DER bytes out of the context.
func certDER(cert *windows.CertContext) []byte {
	// EncodedCert points to Length bytes owned by the context, valid until the
	// context is freed or the enumeration moves on; the copy outlives both.
	return slices.Clone(unsafe.Slice(cert.EncodedCert, cert.Length))
}

// isTimeValid is `CertVerifyTimeValidity(now)`: 0 inside the validity period, -1
// before it, 1 after it.
func isTimeValid(cert *windows.CertContext) bool {
	// A nil time means now; CertInfo is owned by the context, alive during the call.
	ret, _, _ := procCertVerifyTimeValidity.Call(0, uintptr(unsafe.Pointer(cert.CertInfo)))
	return int32(ret) == 0
}

// certValidUses is schannel's `valid_uses`: CertGetEnhancedKeyUsage with both the
// extension and the property. No usage list at all (CRYPT_E_NOT_FOUND) means every
// use; an empty list means none.
func certValidUses(cert *windows.CertContext) (validUses, error) {
	var size uint32
	r, _, e := procCertGetEnhancedKeyUsage.Call(uintptr(unsafe.Pointer(cert)), 0, 0, uintptr(unsafe.Pointer(&size)))
	if r == 0 {
		return validUses{}, e
	}
	// A []uint64 backing store: aligned for CERT_ENHKEY_USAGE, whose OID strings
	// are written after the header inside this same buffer. It stays reachable
	// until the OIDs are copied out below.
	buf := make([]uint64, (size+7)/8)
	usage := (*windows.CertEnhKeyUsage)(unsafe.Pointer(&buf[0]))
	r, _, e = procCertGetEnhancedKeyUsage.Call(uintptr(unsafe.Pointer(cert)), 0, uintptr(unsafe.Pointer(usage)), uintptr(unsafe.Pointer(&size)))
	if r == 0 {
		return validUses{}, e
	}
	count := usage.Length
	if count == 0 {
		switch e {
		case syscall.Errno(windows.CRYPT_E_NOT_FOUND):
			return validUses{all: true}, nil
		case syscall.Errno(0):
		default:
			return validUses{}, e
		}
	}
	oids := make([]string, 0, count)
	for _, p := range unsafe.Slice(usage.UsageIdentifiers, count) {
		// Each pointer targets a NUL-terminated ASCII OID inside buf.
		oids = append(oids, windows.BytePtrToString(p))
	}
	runtime.KeepAlive(buf)
	return validUses{oids: oids}, nil
}
