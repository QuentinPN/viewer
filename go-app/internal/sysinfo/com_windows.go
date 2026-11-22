//go:build windows

package sysinfo

// Minimal COM client over syscall, without cgo (research/dxgi.md). Interfaces are
// called through their vtable: the first word of a COM object points to an array of
// function pointers, slot 0..2 being IUnknown (QueryInterface, AddRef, Release) and
// the methods of each interface following in declaration order, base interfaces
// first. Every slot index used by this package is named at its call site with the
// header it comes from (dxgi.h, dxgi1_2.h .. dxgi1_6.h, d3d11.h); the order was also
// cross-checked against the generated vtables of the `windows` crate the Rust side
// builds with.

import (
	"fmt"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

// vtable is a COM vtable seen through a fixed-size window. The real vtable is never
// as long as the window and no slot past the interface's own count is ever read.
type vtable [32]uintptr

// comObject is the memory layout of any COM interface pointer: a pointer to its
// vtable. A *comObject points into memory the COM server owns (not into the Go
// heap): the Go GC ignores it, and it stays valid until the last Release.
type comObject struct {
	vtbl *vtable
}

// IUnknown slots (unknwn.h).
const (
	slotQueryInterface = 0
	slotRelease        = 2
)

// call invokes the method in the given vtable slot with `this` first.
//
// unsafe: o is a live COM interface pointer, so *o is a valid comObject whose vtbl
// points to the server's vtable; slot is below the interface's slot count (the
// callers use the named constants). The args are uintptr(unsafe.Pointer(&x)) of Go
// variables at the call sites; //go:uintptrescapes makes the compiler keep those
// variables alive, on the heap (so a stack move cannot invalidate them), until this
// function returns, as it does for syscall.SyscallN. The callee writes to them only
// within their declared size.
//
//go:uintptrescapes
func (o *comObject) call(slot int, args ...uintptr) uintptr {
	r1, _, _ := syscall.SyscallN(o.vtbl[slot], append([]uintptr{uintptr(unsafe.Pointer(o))}, args...)...)
	return r1
}

// failed reports a failing HRESULT (negative), like `FAILED`.
func failed(hr uintptr) bool { return int32(hr) < 0 }

// release drops one reference (IUnknown::Release, slot 2). Safe on nil.
func (o *comObject) release() {
	if o != nil {
		o.call(slotRelease)
	}
}

// queryInterface is IUnknown::QueryInterface (slot 0): the same object seen as the
// interface iid, with its own reference to release.
func (o *comObject) queryInterface(iid *windows.GUID) (*comObject, error) {
	var out *comObject
	// unsafe: iid points to a package-level GUID and &out is a Go variable of pointer
	// size that the callee fills with the interface pointer; both outlive the call.
	hr := o.call(slotQueryInterface, uintptr(unsafe.Pointer(iid)), uintptr(unsafe.Pointer(&out)))
	if failed(hr) || out == nil {
		return nil, fmt.Errorf("QueryInterface: HRESULT 0x%08X", uint32(hr))
	}
	return out, nil
}

func mustGUID(s string) windows.GUID {
	g, err := windows.GUIDFromString(s)
	if err != nil {
		panic("sysinfo: bad GUID " + s) // programming error, caught by the tests
	}
	return g
}
