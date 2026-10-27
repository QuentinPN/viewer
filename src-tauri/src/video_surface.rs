//! Native video surface hosting strategies (Windows). See ADR 0003.
//!
//! `Parent` (default, validated in Gate 1): mpv receives the main window HWND as
//! `wid` and creates its own child window; the transparent WebView2 composites
//! above it, so overlays work.
//! `Child` (rejected, kept for re-testing on other WebView2 versions): our own
//! Win32 child window at the bottom of the z-order. mpv renders into it, but nothing
//! is visible through the transparent WebView2; the desktop shows through instead.
//! `Separate`: no `wid`, mpv opens its own top-level window (does not follow
//! fullscreen/resize; comparison only).

use serde::Serialize;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum SurfaceMode {
    Child,
    Parent,
    Separate,
}

impl SurfaceMode {
    pub fn from_env() -> Self {
        match std::env::var("VIEWER_VIDEO_MODE").as_deref() {
            Ok("child") => Self::Child,
            Ok("separate") => Self::Separate,
            _ => Self::Parent,
        }
    }
}

#[cfg(windows)]
mod imp {
    use std::ffi::c_void;
    use windows_sys::Win32::Foundation::{HWND, LPARAM, LRESULT, RECT, WPARAM};
    use windows_sys::Win32::Graphics::Gdi::{BLACK_BRUSH, GetStockObject, HBRUSH};
    use windows_sys::Win32::System::LibraryLoader::GetModuleHandleW;
    use windows_sys::Win32::UI::WindowsAndMessaging::{
        CreateWindowExW, DefWindowProcW, DestroyWindow, GetClientRect, HWND_BOTTOM, RegisterClassW,
        SWP_NOACTIVATE, SWP_NOZORDER, SetWindowPos, WNDCLASSW, WS_CHILD, WS_CLIPCHILDREN,
        WS_CLIPSIBLINGS, WS_DISABLED, WS_VISIBLE,
    };

    fn class_name() -> Vec<u16> {
        "ViewerVideoSurface\0".encode_utf16().collect()
    }

    unsafe extern "system" fn wnd_proc(hwnd: HWND, msg: u32, w: WPARAM, l: LPARAM) -> LRESULT {
        // SAFETY: forwarding the exact arguments received from the system.
        unsafe { DefWindowProcW(hwnd, msg, w, l) }
    }

    /// Child HWND hosting the video. Must be created and used on the UI thread.
    pub struct ChildSurface {
        hwnd: HWND,
        parent: HWND,
    }

    // SAFETY: the handle is only an identifier; all Win32 calls on it are made
    // from the UI thread (setup hook and window-event callbacks).
    unsafe impl Send for ChildSurface {}
    // SAFETY: see above.
    unsafe impl Sync for ChildSurface {}

    impl ChildSurface {
        pub fn create(parent: *mut c_void) -> Option<Self> {
            let parent = parent as HWND;
            let class_name = class_name();
            // SAFETY: standard Win32 class registration and window creation with a
            // valid parent HWND provided by Tauri; failures are checked.
            unsafe {
                let instance = GetModuleHandleW(std::ptr::null());
                let class = WNDCLASSW {
                    lpfnWndProc: Some(wnd_proc),
                    hInstance: instance,
                    lpszClassName: class_name.as_ptr(),
                    hbrBackground: GetStockObject(BLACK_BRUSH) as HBRUSH,
                    ..std::mem::zeroed()
                };
                RegisterClassW(&class); // fails harmlessly if already registered
                let mut rc: RECT = std::mem::zeroed();
                GetClientRect(parent, &mut rc);
                let hwnd = CreateWindowExW(
                    0,
                    class_name.as_ptr(),
                    std::ptr::null(),
                    // Disabled: never takes focus or mouse input; the WebView keeps both.
                    WS_CHILD | WS_VISIBLE | WS_CLIPSIBLINGS | WS_CLIPCHILDREN | WS_DISABLED,
                    0,
                    0,
                    rc.right - rc.left,
                    rc.bottom - rc.top,
                    parent,
                    std::ptr::null_mut(),
                    instance,
                    std::ptr::null(),
                );
                if hwnd.is_null() {
                    return None;
                }
                SetWindowPos(
                    hwnd,
                    HWND_BOTTOM,
                    0,
                    0,
                    0,
                    0,
                    0x0001 /* SWP_NOSIZE */ | SWP_NOACTIVATE,
                );
                Some(Self { hwnd, parent })
            }
        }

        pub fn wid(&self) -> i64 {
            self.hwnd as i64
        }

        /// Matches the parent's client area. Call on every parent resize.
        pub fn fit_parent(&self) {
            // SAFETY: both handles are alive for the lifetime of the main window.
            unsafe {
                let mut rc: RECT = std::mem::zeroed();
                GetClientRect(self.parent, &mut rc);
                SetWindowPos(
                    self.hwnd,
                    std::ptr::null_mut(),
                    0,
                    0,
                    rc.right - rc.left,
                    rc.bottom - rc.top,
                    SWP_NOZORDER | SWP_NOACTIVATE,
                );
            }
        }
    }

    impl Drop for ChildSurface {
        fn drop(&mut self) {
            // SAFETY: we own the window; destroying it after mpv has been torn down.
            unsafe { DestroyWindow(self.hwnd) };
        }
    }
}

#[cfg(windows)]
pub use imp::ChildSurface;
