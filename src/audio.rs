use std::collections::BTreeMap;

use anyhow::{Context, Result, ensure};
use serde::Serialize;
use windows::{
    Win32::{
        Foundation::{CloseHandle, HANDLE},
        Media::Audio::{
            AudioSessionStateExpired, Endpoints::IAudioEndpointVolume, IAudioSessionControl2,
            IAudioSessionManager2, IMMDevice, IMMDeviceEnumerator, ISimpleAudioVolume,
            MMDeviceEnumerator, eCapture, eConsole, eRender,
        },
        System::{
            Com::{
                CLSCTX_ALL, COINIT_MULTITHREADED, CoCreateInstance, CoInitializeEx, CoUninitialize,
            },
            Threading::{
                OpenProcess, PROCESS_NAME_WIN32, PROCESS_QUERY_LIMITED_INFORMATION,
                QueryFullProcessImageNameW,
            },
        },
    },
    core::{Interface, PWSTR},
};

#[derive(Clone, Serialize)]
pub(crate) struct Level {
    pub(crate) id: String,
    label: String,
    percent: u8,
    muted: bool,
    mixed: bool,
}

pub(crate) const SPOTIFY: &str =
    "app:de0b92d2d2872a9f08e72f1d87fecd65897fcb31a986b1e337ed9417221827f7";

struct Com;

impl Drop for Com {
    fn drop(&mut self) {
        // SAFETY: This guard balances successful initialization on the current thread.
        unsafe { CoUninitialize() };
    }
}

struct Process(HANDLE);

impl Drop for Process {
    fn drop(&mut self) {
        // SAFETY: The handle belongs to this guard, returned by OpenProcess.
        let _ = unsafe { CloseHandle(self.0) };
    }
}

pub(crate) fn process_image(pid: u32) -> Option<String> {
    let mut buffer = vec![0u16; 32768];
    let mut length = buffer.len() as u32;
    // SAFETY: The writable UTF-16 buffer holds the advertised number of code units.
    unsafe {
        let process = Process(OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid).ok()?);
        QueryFullProcessImageNameW(
            process.0,
            PROCESS_NAME_WIN32,
            PWSTR(buffer.as_mut_ptr()),
            &mut length,
        )
        .ok()?;
    }
    String::from_utf16(&buffer[..length as usize]).ok()
}

fn devices() -> Result<(Com, IMMDeviceEnumerator)> {
    // SAFETY: This function is called synchronously, and the guard stays on the same thread.
    unsafe {
        CoInitializeEx(None, COINIT_MULTITHREADED).ok()?;
        let apartment = Com;
        let devices = CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL)?;
        Ok((apartment, devices))
    }
}

fn session_groups(
    device: &IMMDevice,
) -> Result<BTreeMap<String, (String, Vec<ISimpleAudioVolume>)>> {
    let mut groups = BTreeMap::<String, (String, Vec<ISimpleAudioVolume>)>::new();
    // SAFETY: All interfaces are held until iteration ends in the COM apartment.
    unsafe {
        let manager: IAudioSessionManager2 = device.Activate(CLSCTX_ALL, None)?;
        let sessions = manager.GetSessionEnumerator()?;
        for index in 0..sessions.GetCount()? {
            let Ok(session) = sessions.GetSession(index) else {
                continue;
            };
            if session
                .GetState()
                .is_ok_and(|state| state == AudioSessionStateExpired)
            {
                continue;
            }
            let Ok(control) = session.cast::<IAudioSessionControl2>() else {
                continue;
            };
            let Ok(volume) = session.cast::<ISimpleAudioVolume>() else {
                continue;
            };
            let system = control.IsSystemSoundsSession().0 == 0;
            let (group, label) = if system {
                ("system".to_owned(), "Sistem sesleri".to_owned())
            } else {
                let Ok(pid) = control.GetProcessId() else {
                    continue;
                };
                if let Some(image) = process_image(pid) {
                    let name = std::path::Path::new(&image)
                        .file_stem()
                        .unwrap_or_default()
                        .to_string_lossy()
                        .into_owned();
                    let hash = format!(
                        "{:x}",
                        <sha2::Sha256 as sha2::Digest>::digest(image.to_lowercase().as_bytes())
                    );
                    if name.eq_ignore_ascii_case("spotify") {
                        (SPOTIFY.to_owned(), "Spotify".to_owned())
                    } else {
                        (format!("app:{hash}"), name)
                    }
                } else {
                    let Ok(instance) = control.GetSessionInstanceIdentifier() else {
                        continue;
                    };
                    let instance_text = instance.to_string();
                    windows::Win32::System::Com::CoTaskMemFree(Some(instance.0.cast()));
                    let Ok(instance_text) = instance_text else {
                        continue;
                    };
                    let hash = format!(
                        "{:x}",
                        <sha2::Sha256 as sha2::Digest>::digest(instance_text.as_bytes())
                    );
                    (format!("session:{hash}"), format!("Ses oturumu ({pid})"))
                }
            };
            groups
                .entry(group)
                .or_insert_with(|| (label, Vec::new()))
                .1
                .push(volume);
        }
    }
    Ok(groups)
}

fn output_groups(
    devices: &IMMDeviceEnumerator,
    default: &IMMDevice,
) -> Result<BTreeMap<String, (String, Vec<ISimpleAudioVolume>)>> {
    use windows::Win32::{Media::Audio::DEVICE_STATE_ACTIVE, System::Com::CoTaskMemFree};
    let mut groups = session_groups(default)?;
    // SAFETY: The devices and session interfaces belong to the caller's live apartment.
    unsafe {
        let id_of = |device: &IMMDevice| -> Result<String> {
            let pointer = device.GetId()?;
            let id = pointer.to_string();
            CoTaskMemFree(Some(pointer.0.cast()));
            Ok(id?)
        };
        let Ok(default_id) = id_of(default) else {
            return Ok(groups);
        };
        let Ok(outputs) = devices.EnumAudioEndpoints(eRender, DEVICE_STATE_ACTIVE) else {
            return Ok(groups);
        };
        for index in 0..outputs.GetCount().unwrap_or_default() {
            let Ok(output) = outputs.Item(index) else {
                continue;
            };
            if id_of(&output).is_ok_and(|id| id == default_id) {
                continue;
            }
            if let Ok(mut other) = session_groups(&output)
                && let Some((label, mut sessions)) = other.remove(SPOTIFY)
            {
                groups
                    .entry(SPOTIFY.to_owned())
                    .or_insert_with(|| (label, Vec::new()))
                    .1
                    .append(&mut sessions);
            }
        }
    }
    Ok(groups)
}

fn percent(level: f32) -> u8 {
    (level.clamp(0.0, 1.0) * 100.0).round() as u8
}

fn endpoint(device: &IMMDevice) -> Result<IAudioEndpointVolume> {
    // SAFETY: The caller owns a live device and an initialized COM apartment.
    Ok(unsafe { device.Activate(CLSCTX_ALL, None)? })
}

fn endpoint_level(device: &IMMDevice, id: &str, label: &str) -> Result<Level> {
    let volume = endpoint(device)?;
    // SAFETY: The endpoint remains live in the caller's COM apartment.
    let (level, muted) = unsafe {
        (
            volume.GetMasterVolumeLevelScalar()?,
            volume.GetMute()?.as_bool(),
        )
    };
    Ok(Level {
        id: id.to_owned(),
        label: label.to_owned(),
        percent: percent(level),
        muted,
        mixed: false,
    })
}

pub(crate) fn mixer() -> Result<Vec<Level>> {
    levels(true)
}

pub(crate) fn media_levels() -> Result<Vec<Level>> {
    levels(false)
}

pub(crate) fn media_level<'a>(levels: &'a [Level], source: &str) -> Option<&'a Level> {
    if crate::media::is_spotify(source) {
        return levels.iter().find(|level| level.id == SPOTIFY);
    }
    let source = source.to_ascii_lowercase();
    let name = source.rsplit(['\\', '/']).next().unwrap_or(&source);
    let mut matching = levels.iter().filter(|level| {
        level.id != SPOTIFY && level.id.starts_with("app:") && {
            let label = level.label.to_ascii_lowercase();
            !label.is_empty()
                && (name == label
                    || name.strip_suffix(".exe") == Some(label.as_str())
                    || name.split(['.', '_', '!']).any(|part| part == label))
        }
    });
    if let Some(first) = matching.next()
        && matching.next().is_none()
    {
        return Some(first);
    }
    levels.iter().find(|level| level.id == "master")
}

fn levels(include_capture: bool) -> Result<Vec<Level>> {
    let (_com, devices) = devices()?;
    // SAFETY: The enumerator is used synchronously inside the live apartment.
    let (output, capture) = unsafe {
        (
            devices.GetDefaultAudioEndpoint(eRender, eConsole)?,
            include_capture
                .then(|| devices.GetDefaultAudioEndpoint(eCapture, eConsole).ok())
                .flatten(),
        )
    };
    let mut levels = vec![endpoint_level(&output, "master", "Genel Windows sesi")?];
    if let Some(capture) = capture
        && let Ok(input) = endpoint_level(&capture, "microphone", "Varsayılan mikrofon")
    {
        levels.push(input);
    }
    for (id, (label, sessions)) in output_groups(&devices, &output)? {
        // SAFETY: Each simple volume interface belongs to the live apartment.
        let values: Vec<_> = sessions
            .iter()
            .filter_map(|volume| unsafe {
                Some((
                    percent(volume.GetMasterVolume().ok()?),
                    volume.GetMute().ok()?.as_bool(),
                ))
            })
            .collect();
        let Some(&(level, muted)) = values.first() else {
            continue;
        };
        levels.push(Level {
            id,
            label,
            percent: level,
            muted,
            mixed: values.iter().any(|&value| value != (level, muted)),
        });
    }
    Ok(levels)
}

pub(crate) fn set(target: &str, volume: Option<u8>, mute: Option<bool>) -> Result<()> {
    ensure!(
        volume.is_none_or(|level| level <= 100),
        "ses yüzdesi geçersiz"
    );
    let new_level = volume.map(|level| f32::from(level) / 100.0);
    let (_com, devices) = devices()?;
    let flow = if target == "microphone" {
        eCapture
    } else {
        eRender
    };
    // SAFETY: These COM interfaces remain inside the live apartment. A null event
    // context explicitly selects the Core Audio default notification context.
    unsafe {
        let device = devices.GetDefaultAudioEndpoint(flow, eConsole)?;
        if target == "master" || target == "microphone" {
            let endpoint = endpoint(&device)?;
            if let Some(level) = new_level {
                endpoint.SetMasterVolumeLevelScalar(level, std::ptr::null())?;
            }
            if let Some(mute) = mute {
                endpoint.SetMute(mute, std::ptr::null())?;
            }
            return Ok(());
        }
        let groups = if target == SPOTIFY {
            output_groups(&devices, &device)?
        } else {
            session_groups(&device)?
        };
        let (_, sessions) = groups.get(target).context("ses oturumu kapanmış")?;
        for session in sessions {
            if let Some(level) = new_level {
                session.SetMasterVolume(level, std::ptr::null())?;
            }
            if let Some(mute) = mute {
                session.SetMute(mute, std::ptr::null())?;
            }
        }
    }
    Ok(())
}
#[derive(serde::Deserialize, Debug)]
#[serde(deny_unknown_fields)]
pub(crate) struct PresetLevel {
    id: String,
    percent: u8,
}

pub(crate) fn apply_preset(levels: &[PresetLevel]) -> Result<()> {
    ensure!(
        !levels.is_empty()
            && levels.len() <= 40
            && levels.iter().all(|entry| entry.percent <= 100
                && entry
                    .id
                    .strip_prefix("app:")
                    .is_some_and(crate::art::valid_id)),
        "ses profili geçersiz"
    );
    let (_com, devices) = devices()?;
    // SAFETY: The listed interfaces are owned by the current apartment.
    unsafe {
        let groups = output_groups(
            &devices,
            &devices.GetDefaultAudioEndpoint(eRender, eConsole)?,
        )?;
        let mut applied = false;
        for entry in levels {
            if let Some((_, sessions)) = groups.get(&entry.id) {
                for session in sessions {
                    session.SetMasterVolume(f32::from(entry.percent) / 100.0, std::ptr::null())?;
                    applied = true;
                }
            }
        }
        ensure!(
            applied,
            "bu profildeki uygulamalardan hiçbiri şu anda ses oturumu açmamış"
        );
    }
    Ok(())
}

#[derive(Serialize)]
pub(crate) struct Output {
    id: String,
    name: String,
    active: bool,
}

pub(crate) fn outputs() -> Result<Vec<Output>> {
    use windows::Win32::{
        Foundation::PROPERTYKEY,
        Media::Audio::DEVICE_STATE_ACTIVE,
        System::Com::{
            CoTaskMemFree, STGM_READ,
            StructuredStorage::{PropVariantClear, PropVariantToStringAlloc},
        },
    };
    use windows::core::GUID;
    const NAME: PROPERTYKEY = PROPERTYKEY {
        fmtid: GUID::from_u128(0xa45c254e_df1c_4efd_8020_67d146a850e0),
        pid: 14,
    };
    let (_com, devices) = devices()?;
    // SAFETY: Buffers returned by Core Audio are converted and freed on this apartment thread.
    unsafe {
        let get_id = |device: &IMMDevice| -> Result<String> {
            let raw = device.GetId()?;
            let value = raw.to_string();
            CoTaskMemFree(Some(raw.0.cast()));
            Ok(value?)
        };
        let default = devices
            .GetDefaultAudioEndpoint(eRender, eConsole)
            .ok()
            .and_then(|device| get_id(&device).ok());
        let devices = devices.EnumAudioEndpoints(eRender, DEVICE_STATE_ACTIVE)?;
        let mut result = Vec::new();
        for index in 0..devices.GetCount()?.min(26) {
            let device = devices.Item(index)?;
            let id = get_id(&device)?;
            let mut property = device.OpenPropertyStore(STGM_READ)?.GetValue(&NAME)?;
            let raw = PropVariantToStringAlloc(&property);
            let _ = PropVariantClear(&mut property);
            let raw = raw?;
            let name = raw.to_string();
            CoTaskMemFree(Some(raw.0.cast()));
            result.push(Output {
                active: default.as_ref() == Some(&id),
                id,
                name: name?,
            });
        }
        Ok(result)
    }
}

pub(crate) fn select_output(id: &str) -> Result<()> {
    use std::ffi::c_void;
    use windows::{
        Win32::System::Com::CoCreateInstance,
        core::{GUID, HRESULT, IUnknown, IUnknown_Vtbl, Interface, PCWSTR},
    };
    ensure!(
        id.len() < 768 && outputs()?.iter().any(|output| output.id == id),
        "etkin ses çıkışı bulunamadı"
    );
    let (_com, _) = devices()?;
    const CLASS: GUID = GUID::from_u128(0x870af99c_171d_4f9e_af0d_e63df40c2bc9);
    const POLICY: GUID = GUID::from_u128(0xf8679f50_850a_41cf_9c72_430f290290c8);
    #[repr(C)]
    struct PolicyTable {
        base: IUnknown_Vtbl,
        unused: [usize; 10],
        set_default: unsafe extern "system" fn(*mut c_void, PCWSTR, i32) -> HRESULT,
    }
    let wide: Vec<u16> = id.encode_utf16().chain([0]).collect();
    // SAFETY: QueryInterface validates the policy interface UUID; the slot matches its Windows ABI.
    unsafe {
        let root: IUnknown = CoCreateInstance(&CLASS, None, CLSCTX_ALL)?;
        let mut raw = std::ptr::null_mut();
        root.query(&POLICY, &mut raw).ok()?;
        ensure!(!raw.is_null(), "Windows ses ilkesi arayüzü yok");
        let policy = IUnknown::from_raw(raw);
        let table = &**(policy.as_raw() as *const *const PolicyTable);
        (table.set_default)(policy.as_raw(), PCWSTR(wide.as_ptr()), 0).ok()?;
        (table.set_default)(policy.as_raw(), PCWSTR(wide.as_ptr()), 1).ok()?;
    }
    ensure!(
        outputs()?
            .iter()
            .any(|output| output.id == id && output.active),
        "Windows ses çıkışını değiştirmedi"
    );
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn spotify_never_falls_back_to_windows_or_browser_volume() {
        let level = |id: &str, label: &str| Level {
            id: id.into(),
            label: label.into(),
            percent: 42,
            muted: false,
            mixed: false,
        };
        let mut levels = vec![level("master", "Windows"), level("app:chrome", "chrome")];
        assert!(media_level(&levels, "Spotify.exe").is_none());
        assert!(media_level(&levels, "SpotifyAB.SpotifyMusic_package!Spotify").is_none());
        levels.push(level(SPOTIFY, "Spotify"));
        assert_eq!(media_level(&levels, "Spotify.exe").unwrap().id, SPOTIFY);
        assert_ne!(media_level(&levels, "Chrome.spotify").unwrap().id, SPOTIFY);
    }
}
