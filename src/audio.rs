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

#[derive(Serialize)]
pub(crate) struct Level {
    id: String,
    label: String,
    percent: u8,
    muted: bool,
    mixed: bool,
}

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

fn process_image(pid: u32) -> Option<String> {
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
                    (format!("app:{hash}"), name)
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
    let (_com, devices) = devices()?;
    // SAFETY: The enumerator is used synchronously inside the live apartment.
    let (output, capture) = unsafe {
        (
            devices.GetDefaultAudioEndpoint(eRender, eConsole)?,
            devices.GetDefaultAudioEndpoint(eCapture, eConsole),
        )
    };
    let mut levels = vec![endpoint_level(&output, "master", "Genel Windows sesi")?];
    if let Ok(capture) = capture
        && let Ok(input) = endpoint_level(&capture, "microphone", "Varsayılan mikrofon")
    {
        levels.push(input);
    }
    for (id, (label, sessions)) in session_groups(&output)? {
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
        let groups = session_groups(&device)?;
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
