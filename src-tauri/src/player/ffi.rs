//! Minimal runtime-loaded binding to the libmpv client API (`client.h`, API 2.x).
//!
//! The library is loaded with `libloading` instead of linked at build time so that
//! the app can start, report a clear diagnostic when `libmpv-2.dll` is missing, and
//! so that no import library is needed to build. Only the functions actually used
//! are declared; struct layouts mirror `mpv/client.h` and must stay in sync with it.

use std::ffi::{c_char, c_int, c_ulong, c_void};
use std::path::Path;

use libloading::Library;

/// Opaque `mpv_handle`.
#[repr(C)]
pub struct MpvHandle {
    _private: [u8; 0],
}

pub const MPV_FORMAT_STRING: c_int = 1;
pub const MPV_FORMAT_FLAG: c_int = 3;
pub const MPV_FORMAT_DOUBLE: c_int = 5;

pub const MPV_EVENT_NONE: c_int = 0;
pub const MPV_EVENT_SHUTDOWN: c_int = 1;
pub const MPV_EVENT_LOG_MESSAGE: c_int = 2;
pub const MPV_EVENT_END_FILE: c_int = 7;
pub const MPV_EVENT_FILE_LOADED: c_int = 8;
pub const MPV_EVENT_VIDEO_RECONFIG: c_int = 17;
pub const MPV_EVENT_PLAYBACK_RESTART: c_int = 21;
pub const MPV_EVENT_PROPERTY_CHANGE: c_int = 22;

pub const MPV_END_FILE_REASON_EOF: c_int = 0;
pub const MPV_END_FILE_REASON_STOP: c_int = 2;
pub const MPV_END_FILE_REASON_QUIT: c_int = 3;
pub const MPV_END_FILE_REASON_ERROR: c_int = 4;

/// Minimum client API major version this binding was written against.
pub const REQUIRED_API_MAJOR: c_ulong = 2;

#[repr(C)]
pub struct MpvEvent {
    pub event_id: c_int,
    pub error: c_int,
    pub reply_userdata: u64,
    pub data: *mut c_void,
}

#[repr(C)]
pub struct MpvEventProperty {
    pub name: *const c_char,
    pub format: c_int,
    pub data: *mut c_void,
}

#[repr(C)]
pub struct MpvEventEndFile {
    pub reason: c_int,
    pub error: c_int,
    pub playlist_entry_id: i64,
    pub playlist_insert_id: i64,
    pub playlist_insert_num_entries: c_int,
}

#[repr(C)]
pub struct MpvEventLogMessage {
    pub prefix: *const c_char,
    pub level: *const c_char,
    pub text: *const c_char,
    pub log_level: c_int,
}

/// Function table resolved from the loaded library. The `Library` is kept alive
/// alongside the pointers, so they never outlive the module.
pub struct MpvApi {
    pub client_api_version: unsafe extern "C" fn() -> c_ulong,
    pub error_string: unsafe extern "C" fn(c_int) -> *const c_char,
    pub free: unsafe extern "C" fn(*mut c_void),
    pub create: unsafe extern "C" fn() -> *mut MpvHandle,
    pub initialize: unsafe extern "C" fn(*mut MpvHandle) -> c_int,
    pub terminate_destroy: unsafe extern "C" fn(*mut MpvHandle),
    pub set_option_string:
        unsafe extern "C" fn(*mut MpvHandle, *const c_char, *const c_char) -> c_int,
    pub command: unsafe extern "C" fn(*mut MpvHandle, *mut *const c_char) -> c_int,
    pub set_property_string:
        unsafe extern "C" fn(*mut MpvHandle, *const c_char, *const c_char) -> c_int,
    pub get_property_string: unsafe extern "C" fn(*mut MpvHandle, *const c_char) -> *mut c_char,
    pub observe_property: unsafe extern "C" fn(*mut MpvHandle, u64, *const c_char, c_int) -> c_int,
    pub request_log_messages: unsafe extern "C" fn(*mut MpvHandle, *const c_char) -> c_int,
    pub wait_event: unsafe extern "C" fn(*mut MpvHandle, f64) -> *mut MpvEvent,
    pub wakeup: unsafe extern "C" fn(*mut MpvHandle),
    _lib: Library,
}

impl MpvApi {
    /// Loads libmpv from `path` and resolves every symbol used by the engine.
    ///
    /// # Safety
    /// Loading a library runs its initialisers. The caller must trust `path`
    /// (it comes from the app's own install directory or an explicit dev override).
    pub unsafe fn load(path: &Path) -> Result<Self, libloading::Error> {
        // SAFETY: the caller vouches for the library; symbol signatures below
        // match mpv/client.h for client API 2.x.
        unsafe {
            let lib = Library::new(path)?;
            macro_rules! sym {
                ($name:literal) => {
                    *lib.get(concat!($name, "\0").as_bytes())?
                };
            }
            Ok(Self {
                client_api_version: sym!("mpv_client_api_version"),
                error_string: sym!("mpv_error_string"),
                free: sym!("mpv_free"),
                create: sym!("mpv_create"),
                initialize: sym!("mpv_initialize"),
                terminate_destroy: sym!("mpv_terminate_destroy"),
                set_option_string: sym!("mpv_set_option_string"),
                command: sym!("mpv_command"),
                set_property_string: sym!("mpv_set_property_string"),
                get_property_string: sym!("mpv_get_property_string"),
                observe_property: sym!("mpv_observe_property"),
                request_log_messages: sym!("mpv_request_log_messages"),
                wait_event: sym!("mpv_wait_event"),
                wakeup: sym!("mpv_wakeup"),
                _lib: lib,
            })
        }
    }
}
