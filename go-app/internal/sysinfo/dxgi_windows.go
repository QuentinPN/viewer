//go:build windows

package sysinfo

import (
	"fmt"
	"log/slog"
	"slices"
	"unsafe"

	"golang.org/x/sys/windows"
)

// Interface ids (dxgi.h, dxgi1_6.h, d3d11.h; values as in the `windows` crate).
var (
	iidFactory1    = mustGUID("{770AAE78-F26F-4DBA-A829-253C83D1B387}") // IDXGIFactory1
	iidDXGIDevice  = mustGUID("{54EC77FA-1377-44E6-8C32-88FD5F44C84C}") // IDXGIDevice
	iidOutput6     = mustGUID("{068346E8-AAEC-4B84-ADD7-137F513F77A1}") // IDXGIOutput6
	iidVideoDevice = mustGUID("{10EC4D5B-975A-4689-B9E4-D0AAC30FE333}") // ID3D11VideoDevice
)

// Decoder profiles (d3d11.h: D3D11_DECODER_PROFILE_*), in Codec order.
var decoderProfiles = []struct {
	guid  windows.GUID
	codec Codec
}{
	{mustGUID("{1B81BE68-A0C7-11D3-B984-00C04F2E73C5}"), H264},        // H264_VLD_NOFGT
	{mustGUID("{5B11D51B-2F4C-4452-BCC3-09F2A1160CC0}"), Hevc},        // HEVC_VLD_MAIN
	{mustGUID("{107AF0E0-EF1A-4D19-ABA8-67A163073D13}"), Hevc10},      // HEVC_VLD_MAIN10
	{mustGUID("{463707F8-A1D0-4585-876D-83AA6D60B89E}"), Vp9},         // VP9_VLD_PROFILE0
	{mustGUID("{A4C749EF-6ECF-48AA-8448-50A7A1165FF7}"), Vp9Profile2}, // VP9_VLD_10BIT_PROFILE2
	{mustGUID("{B8BE4CCB-CF53-46BA-8D59-D6B8A6DA5D2A}"), Av1},         // AV1_VLD_PROFILE0
}

// Vtable slots. IUnknown takes 0..2, IDXGIObject 3..6 (SetPrivateData,
// SetPrivateDataInterface, GetPrivateData, GetParent).
const (
	// IDXGIFactory (dxgi.h) 7..11: EnumAdapters, MakeWindowAssociation,
	// GetWindowAssociation, CreateSwapChain, CreateSoftwareAdapter.
	// IDXGIFactory1 (dxgi.h) 12: EnumAdapters1, 13: IsCurrent.
	slotFactoryEnumAdapters1 = 12
	// IDXGIAdapter (dxgi.h) 7: EnumOutputs, 8: GetDesc, 9: CheckInterfaceSupport.
	slotAdapterEnumOutputs           = 7
	slotAdapterCheckInterfaceSupport = 9
	// IDXGIAdapter1 (dxgi.h) 10: GetDesc1.
	slotAdapter1GetDesc1 = 10
	// IDXGIOutput (dxgi.h) 7..18, IDXGIOutput1 (dxgi1_2.h) 19..22, IDXGIOutput2
	// 23 (SupportsOverlays), IDXGIOutput3 24, IDXGIOutput4 25, IDXGIOutput5 26
	// (DuplicateOutput1) and IDXGIOutput6 (dxgi1_6.h) 27: GetDesc1.
	slotOutput6GetDesc1 = 27
	// ID3D11VideoDevice (d3d11.h) 3..10: CreateVideoDecoder, CreateVideoProcessor,
	// CreateAuthenticatedChannel, CreateCryptoSession, CreateVideoDecoderOutputView,
	// CreateVideoProcessorInputView, CreateVideoProcessorOutputView,
	// CreateVideoProcessorEnumerator; 11: GetVideoDecoderProfileCount,
	// 12: GetVideoDecoderProfile.
	slotVideoGetProfileCount = 11
	slotVideoGetProfile      = 12
)

const (
	adapterFlagSoftware = 2 // DXGI_ADAPTER_FLAG_SOFTWARE (dxgi.h)
	// colorSpaceHDR10 is DXGI_COLOR_SPACE_RGB_FULL_G2084_NONE_P2020 (dxgicommon.h):
	// PQ and BT.2020.
	colorSpaceHDR10   = 12
	driverTypeUnknown = 0     // D3D_DRIVER_TYPE_UNKNOWN (d3dcommon.h): the adapter is given
	createVideoFlag   = 0x800 // D3D11_CREATE_DEVICE_VIDEO_SUPPORT (d3d11.h)
	d3d11SDKVersion   = 7     // D3D11_SDK_VERSION (d3d11.h)
)

// adapterDesc1 is DXGI_ADAPTER_DESC1 (dxgi.h), x64 layout (312 bytes).
type adapterDesc1 struct {
	Description           [128]uint16
	VendorID              uint32
	DeviceID              uint32
	SubSysID              uint32
	Revision              uint32
	DedicatedVideoMemory  uintptr // SIZE_T
	DedicatedSystemMemory uintptr
	SharedSystemMemory    uintptr
	AdapterLuid           [2]uint32 // LUID {DWORD LowPart; LONG HighPart}
	Flags                 uint32
}

// outputDesc1 is DXGI_OUTPUT_DESC1 (dxgi1_6.h), x64 layout (152 bytes).
type outputDesc1 struct {
	DeviceName            [32]uint16
	DesktopCoordinates    [4]int32 // RECT
	AttachedToDesktop     int32    // BOOL
	Rotation              int32    // DXGI_MODE_ROTATION
	Monitor               uintptr  // HMONITOR
	BitsPerColor          uint32
	ColorSpace            int32 // DXGI_COLOR_SPACE_TYPE
	RedPrimary            [2]float32
	GreenPrimary          [2]float32
	BluePrimary           [2]float32
	WhitePoint            [2]float32
	MinLuminance          float32
	MaxLuminance          float32
	MaxFullFrameLuminance float32
}

var (
	dxgiDLL  = windows.NewLazySystemDLL("dxgi.dll")
	d3d11DLL = windows.NewLazySystemDLL("d3d11.dll")
)

// createFactory is CreateDXGIFactory1 (dxgi.h, exported by dxgi.dll).
func createFactory() (*comObject, error) {
	proc := dxgiDLL.NewProc("CreateDXGIFactory1")
	if err := proc.Find(); err != nil {
		return nil, err
	}
	var factory *comObject
	// unsafe: iidFactory1 is a package-level GUID and &factory a Go variable of
	// pointer size, filled by the callee; both outlive the call (LazyProc.Call keeps
	// them alive like syscall.SyscallN).
	hr, _, _ := proc.Call(uintptr(unsafe.Pointer(&iidFactory1)), uintptr(unsafe.Pointer(&factory)))
	if failed(hr) || factory == nil {
		return nil, fmt.Errorf("CreateDXGIFactory1: HRESULT 0x%08X", uint32(hr))
	}
	return factory, nil
}

// adapters enumerates every adapter (IDXGIFactory1::EnumAdapters1, slot 12) until
// the call fails (DXGI_ERROR_NOT_FOUND past the end). Each is owned by the caller.
func adapters(factory *comObject) []*comObject {
	var out []*comObject
	for i := 0; ; i++ {
		var adapter *comObject
		// unsafe: &adapter is a Go variable the callee fills with an adapter pointer.
		hr := factory.call(slotFactoryEnumAdapters1, uintptr(i), uintptr(unsafe.Pointer(&adapter)))
		if failed(hr) || adapter == nil {
			return out
		}
		out = append(out, adapter)
	}
}

// desc1 is IDXGIAdapter1::GetDesc1 (slot 10).
func desc1(adapter *comObject) (adapterDesc1, bool) {
	var d adapterDesc1
	// unsafe: &d points to a Go struct with the layout of DXGI_ADAPTER_DESC1 that the
	// callee fills in; it outlives the call.
	hr := adapter.call(slotAdapter1GetDesc1, uintptr(unsafe.Pointer(&d)))
	return d, !failed(hr)
}

func releaseAll(list []*comObject) {
	for _, o := range list {
		o.release()
	}
}

func platformGPUs() []GpuInfo {
	factory, err := createFactory()
	if err != nil {
		slog.Info("DXGI factory unavailable: " + err.Error())
		return nil
	}
	defer factory.release()
	all := adapters(factory)
	defer releaseAll(all)

	var found []GpuInfo
	for _, adapter := range all {
		d, ok := desc1(adapter)
		if !ok || d.Flags&adapterFlagSoftware != 0 {
			continue
		}
		var driver *string
		var umd int64
		// IDXGIAdapter::CheckInterfaceSupport (slot 9): the UMD version of the
		// IDXGIDevice interface in a LARGE_INTEGER.
		// unsafe: iidDXGIDevice is package-level; &umd is a Go int64 filled by the callee.
		hr := adapter.call(slotAdapterCheckInterfaceSupport, uintptr(unsafe.Pointer(&iidDXGIDevice)), uintptr(unsafe.Pointer(&umd)))
		if !failed(hr) {
			v := DriverVersion(umd)
			driver = &v
		}
		gpu := GpuInfo{Name: WideToString(d.Description[:]), Driver: driver}
		// One adapter per GPU, but some list theirs twice (one per output).
		if !slices.ContainsFunc(found, func(g GpuInfo) bool { return sameGPU(g, gpu) }) {
			found = append(found, gpu)
		}
	}
	return found
}

func sameGPU(a, b GpuInfo) bool {
	if a.Name != b.Name || (a.Driver == nil) != (b.Driver == nil) {
		return false
	}
	return a.Driver == nil || *a.Driver == *b.Driver
}

// hardwareAdapters are the adapters that are not the software renderer, owned by
// the caller. An adapter whose description cannot be read is taken as software.
func hardwareAdapters() []*comObject {
	factory, err := createFactory()
	if err != nil {
		return nil
	}
	defer factory.release()
	var hw []*comObject
	for _, a := range adapters(factory) {
		if d, ok := desc1(a); ok && d.Flags&adapterFlagSoftware == 0 {
			hw = append(hw, a)
		} else {
			a.release()
		}
	}
	return hw
}

// codecsOf gives the codecs of the profiles a driver lists, in Codec order.
func codecsOf(profiles []windows.GUID) []Codec {
	var found []Codec
	for _, p := range decoderProfiles {
		if slices.Contains(profiles, p.guid) {
			found = append(found, p.codec)
		}
	}
	return sortCodecs(found)
}

// platformDecoders: the first hardware adapter is the one D3D11 (and mpv) use by
// default.
func platformDecoders() *[]Codec {
	hw := hardwareAdapters()
	defer releaseAll(hw)
	if len(hw) == 0 {
		return nil
	}
	proc := d3d11DLL.NewProc("D3D11CreateDevice")
	if err := proc.Find(); err != nil {
		slog.Info("D3D11 video device unavailable: " + err.Error())
		return nil
	}
	var device *comObject
	// D3D11CreateDevice(pAdapter, DriverType, Software, Flags, pFeatureLevels,
	// FeatureLevels, SDKVersion, ppDevice, pFeatureLevel, ppImmediateContext) (d3d11.h).
	// unsafe: hw[0] is a live adapter owned here (released after the call); &device is
	// a Go variable filled by the callee; the other pointers are NULL.
	hr, _, _ := proc.Call(
		uintptr(unsafe.Pointer(hw[0])), driverTypeUnknown, 0, createVideoFlag,
		0, 0, d3d11SDKVersion, uintptr(unsafe.Pointer(&device)), 0, 0)
	if failed(hr) || device == nil {
		slog.Info(fmt.Sprintf("D3D11 video device unavailable: HRESULT 0x%08X", uint32(hr)))
		return nil
	}
	defer device.release()
	video, err := device.queryInterface(&iidVideoDevice)
	if err != nil {
		return nil
	}
	defer video.release()

	// ID3D11VideoDevice::GetVideoDecoderProfileCount (slot 11) returns a UINT.
	count := uint32(video.call(slotVideoGetProfileCount))
	profiles := make([]windows.GUID, 0, count)
	for i := range count {
		var g windows.GUID
		// ID3D11VideoDevice::GetVideoDecoderProfile (slot 12): (UINT, GUID*).
		// unsafe: &g is a Go GUID filled by the callee; i is below the count.
		if hr := video.call(slotVideoGetProfile, uintptr(i), uintptr(unsafe.Pointer(&g))); !failed(hr) {
			profiles = append(profiles, g)
		}
	}
	codecs := codecsOf(profiles)
	return &codecs
}

func platformDisplays() []DisplayInfo {
	var displays []DisplayInfo
	hw := hardwareAdapters()
	defer releaseAll(hw)
	for _, adapter := range hw {
		for j := 0; ; j++ {
			var output *comObject
			// IDXGIAdapter::EnumOutputs (slot 7): (UINT, IDXGIOutput**).
			// unsafe: &output is a Go variable the callee fills in.
			hr := adapter.call(slotAdapterEnumOutputs, uintptr(j), uintptr(unsafe.Pointer(&output)))
			if failed(hr) || output == nil {
				break
			}
			out6, err := output.queryInterface(&iidOutput6)
			output.release()
			if err != nil {
				continue
			}
			var d outputDesc1
			// IDXGIOutput6::GetDesc1 (slot 27).
			// unsafe: &d points to a Go struct with the layout of DXGI_OUTPUT_DESC1.
			hr = out6.call(slotOutput6GetDesc1, uintptr(unsafe.Pointer(&d)))
			out6.release()
			if failed(hr) {
				continue
			}
			displays = append(displays, DisplayInfo{
				Name: WideToString(d.DeviceName[:]),
				HDR:  d.ColorSpace == colorSpaceHDR10,
			})
		}
	}
	return displays
}
