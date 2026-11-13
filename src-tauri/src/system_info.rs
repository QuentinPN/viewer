//! What this PC says about itself, for Réglages › À propos (ADR 0116): the build,
//! the Windows edition and build, and the graphics adapters. Read only: registry
//! values and DXGI descriptions, no shell, nothing created but a DXGI factory.

use serde::Serialize;

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct SystemReport {
    /// `debug_assertions`: the separate debug build (ADR 0105).
    pub debug_build: bool,
    /// `None` when the registry could not be read.
    pub os: Option<OsInfo>,
    /// Hardware adapters, the software renderer left out; empty when DXGI failed.
    pub gpus: Vec<GpuInfo>,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct OsInfo {
    /// "Windows 11 Pro", corrected from the registry's "Windows 10 Pro" on Windows 11.
    pub edition: String,
    /// "23H2"; `None` on builds that do not store it.
    pub display_version: Option<String>,
    /// "22631.4460": build and update revision.
    pub build: String,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct GpuInfo {
    pub name: String,
    /// User-mode driver version "32.0.15.6094"; `None` when DXGI does not give it.
    pub driver: Option<String>,
}

/// First build of Windows 11: the registry `ProductName` still says "Windows 10".
const FIRST_WINDOWS_11_BUILD: u32 = 22000;

/// "Windows 10 Pro" on build 22631 is "Windows 11 Pro".
pub fn edition(product_name: &str, build: u32) -> String {
    let name = product_name.trim();
    if build >= FIRST_WINDOWS_11_BUILD
        && let Some(rest) = name.strip_prefix("Windows 10")
    {
        return format!("Windows 11{rest}");
    }
    name.to_owned()
}

/// "22631" and revision 4460 make "22631.4460".
pub fn build_label(current_build: &str, revision: Option<u32>) -> String {
    match revision {
        Some(r) => format!("{}.{r}", current_build.trim()),
        None => current_build.trim().to_owned(),
    }
}

/// The four 16-bit parts of DXGI's `UMDVersion`, high to low.
pub fn driver_version(umd: i64) -> String {
    let v = umd as u64;
    format!(
        "{}.{}.{}.{}",
        (v >> 48) & 0xffff,
        (v >> 32) & 0xffff,
        (v >> 16) & 0xffff,
        v & 0xffff
    )
}

/// A UTF-16 buffer up to its first NUL.
pub fn wide_to_string(wide: &[u16]) -> String {
    let end = wide.iter().position(|&c| c == 0).unwrap_or(wide.len());
    String::from_utf16_lossy(&wide[..end]).trim().to_owned()
}

pub fn report() -> SystemReport {
    SystemReport {
        debug_build: cfg!(debug_assertions),
        os: platform::os(),
        gpus: platform::gpus(),
    }
}

#[tauri::command]
pub async fn system_report() -> Result<SystemReport, String> {
    tauri::async_runtime::spawn_blocking(report)
        .await
        .map_err(|e| e.to_string())
}

#[cfg(windows)]
mod platform {
    use std::ffi::c_void;

    use windows::Win32::Graphics::Dxgi::{
        CreateDXGIFactory1, DXGI_ADAPTER_FLAG_SOFTWARE, IDXGIDevice, IDXGIFactory1,
    };
    use windows::core::Interface;
    use windows_sys::Win32::Foundation::ERROR_SUCCESS;
    use windows_sys::Win32::System::Registry::{
        HKEY_LOCAL_MACHINE, RRF_RT_REG_DWORD, RRF_RT_REG_SZ, RegGetValueW,
    };

    use super::{GpuInfo, OsInfo, build_label, driver_version, edition, wide_to_string};

    const CURRENT_VERSION: &str = r"SOFTWARE\Microsoft\Windows NT\CurrentVersion";

    fn wide(s: &str) -> Vec<u16> {
        s.encode_utf16().chain(std::iter::once(0)).collect()
    }

    fn reg_string(value: &str) -> Option<String> {
        let key = wide(CURRENT_VERSION);
        let name = wide(value);
        let mut buf = [0u16; 256];
        let mut size = std::mem::size_of_val(&buf) as u32;
        // SAFETY: the buffer and its size in bytes match; key and value are NUL-terminated.
        let status = unsafe {
            RegGetValueW(
                HKEY_LOCAL_MACHINE,
                key.as_ptr(),
                name.as_ptr(),
                RRF_RT_REG_SZ,
                std::ptr::null_mut(),
                buf.as_mut_ptr().cast::<c_void>(),
                &mut size,
            )
        };
        (status == ERROR_SUCCESS)
            .then(|| wide_to_string(&buf))
            .filter(|s| !s.is_empty())
    }

    fn reg_dword(value: &str) -> Option<u32> {
        let key = wide(CURRENT_VERSION);
        let name = wide(value);
        let mut data = 0u32;
        let mut size = std::mem::size_of::<u32>() as u32;
        // SAFETY: a DWORD read into a u32 of the declared size.
        let status = unsafe {
            RegGetValueW(
                HKEY_LOCAL_MACHINE,
                key.as_ptr(),
                name.as_ptr(),
                RRF_RT_REG_DWORD,
                std::ptr::null_mut(),
                (&mut data as *mut u32).cast::<c_void>(),
                &mut size,
            )
        };
        (status == ERROR_SUCCESS).then_some(data)
    }

    pub fn os() -> Option<OsInfo> {
        let product = reg_string("ProductName")?;
        let current_build = reg_string("CurrentBuild")?;
        let number = current_build.trim().parse().unwrap_or(0);
        Some(OsInfo {
            edition: edition(&product, number),
            display_version: reg_string("DisplayVersion"),
            build: build_label(&current_build, reg_dword("UBR")),
        })
    }

    pub fn gpus() -> Vec<GpuInfo> {
        // SAFETY: plain DXGI calls on interfaces owned by this function.
        let factory: IDXGIFactory1 = match unsafe { CreateDXGIFactory1() } {
            Ok(f) => f,
            Err(e) => {
                log::info!("DXGI factory unavailable: {e}");
                return Vec::new();
            }
        };
        let mut found = Vec::new();
        for i in 0.. {
            // SAFETY: COM call on the factory owned above; an index past the end errs.
            let Ok(adapter) = (unsafe { factory.EnumAdapters1(i) }) else {
                break;
            };
            // SAFETY: COM call on an adapter the factory just returned.
            let Ok(desc) = (unsafe { adapter.GetDesc1() }) else {
                continue;
            };
            if desc.Flags & DXGI_ADAPTER_FLAG_SOFTWARE.0 as u32 != 0 {
                continue;
            }
            // SAFETY: same adapter; the IID is a static GUID.
            let driver = unsafe { adapter.CheckInterfaceSupport(&IDXGIDevice::IID) }
                .ok()
                .map(driver_version);
            let gpu = GpuInfo {
                name: wide_to_string(&desc.Description),
                driver,
            };
            // One adapter per GPU, but some list theirs twice (one per output).
            if !found.contains(&gpu) {
                found.push(gpu);
            }
        }
        found
    }
}

#[cfg(not(windows))]
mod platform {
    use super::{GpuInfo, OsInfo};

    pub fn os() -> Option<OsInfo> {
        None
    }

    pub fn gpus() -> Vec<GpuInfo> {
        Vec::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn windows_11_is_named_from_its_build() {
        assert_eq!(edition("Windows 10 Pro", 22631), "Windows 11 Pro");
        assert_eq!(edition("Windows 10 Pro N", 19045), "Windows 10 Pro N");
        assert_eq!(edition(" Windows 10 Home ", 22000), "Windows 11 Home");
        assert_eq!(edition("Windows Server 2022", 20348), "Windows Server 2022");
    }

    #[test]
    fn build_carries_the_update_revision() {
        assert_eq!(build_label("19045", Some(5131)), "19045.5131");
        assert_eq!(build_label("19045", None), "19045");
    }

    #[test]
    fn driver_version_splits_the_umd_parts() {
        let umd = (32i64 << 48) | (15 << 16) | 6094;
        assert_eq!(driver_version(umd), "32.0.15.6094");
    }

    #[test]
    fn wide_strings_stop_at_nul() {
        let mut buf = [0u16; 8];
        for (i, c) in "GPU".encode_utf16().enumerate() {
            buf[i] = c;
        }
        assert_eq!(wide_to_string(&buf), "GPU");
    }

    #[test]
    fn report_is_read_on_this_pc() {
        // Read only; on Windows the registry always has the edition.
        let r = report();
        assert_eq!(r.debug_build, cfg!(debug_assertions));
        if cfg!(windows) {
            assert!(r.os.is_some());
        }
    }
}
