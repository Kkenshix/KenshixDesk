use std::time::{Duration, Instant};

use anyhow::{Result, ensure};
use windows::{
    Win32::{
        Foundation::{HLOCAL, LocalFree, POINT},
        Security::Cryptography::{
            CRYPT_INTEGER_BLOB, CRYPTPROTECT_UI_FORBIDDEN, CryptProtectData, CryptUnprotectData,
        },
        UI::Input::KeyboardAndMouse::{
            INPUT, INPUT_0, INPUT_KEYBOARD, INPUT_MOUSE, KEYBDINPUT, KEYEVENTF_EXTENDEDKEY,
            KEYEVENTF_KEYUP, KEYEVENTF_UNICODE, MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP,
            MOUSEINPUT, SendInput, VIRTUAL_KEY, VK_LWIN, VK_MEDIA_NEXT_TRACK, VK_MEDIA_PLAY_PAUSE,
            VK_MEDIA_PREV_TRACK, VK_TAB,
        },
        UI::WindowsAndMessaging::{
            GetPhysicalCursorPos, GetSystemMetrics, SM_CXVIRTUALSCREEN, SM_CYVIRTUALSCREEN,
            SM_XVIRTUALSCREEN, SM_YVIRTUALSCREEN, SetPhysicalCursorPos,
        },
    },
    core::PCWSTR,
};

use crate::protocol::Action;

pub(crate) fn protect(bytes: &[u8], encrypt: bool) -> Result<Vec<u8>> {
    let input = CRYPT_INTEGER_BLOB {
        cbData: bytes.len().try_into()?,
        pbData: bytes.as_ptr().cast_mut(),
    };
    let mut output = CRYPT_INTEGER_BLOB::default();
    // SAFETY: The input refers to live bytes, and DPAPI initializes the output allocation.
    unsafe {
        if encrypt {
            CryptProtectData(
                &input,
                PCWSTR::null(),
                None,
                None,
                None,
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output,
            )?;
        } else {
            CryptUnprotectData(
                &input,
                None,
                None,
                None,
                None,
                CRYPTPROTECT_UI_FORBIDDEN,
                &mut output,
            )?;
        }
        let bytes = std::slice::from_raw_parts(output.pbData, output.cbData as usize).to_vec();
        let _ = LocalFree(Some(HLOCAL(output.pbData.cast())));
        Ok(bytes)
    }
}

fn input_key(key: VIRTUAL_KEY, release: bool) -> INPUT {
    INPUT {
        r#type: INPUT_KEYBOARD,
        Anonymous: INPUT_0 {
            ki: KEYBDINPUT {
                wVk: key,
                dwFlags: if release {
                    KEYEVENTF_KEYUP
                } else {
                    Default::default()
                } | if matches!(
                    key.0,
                    0x21..=0x28 | 0x2C..=0x2E | 0x5B | 0x5C | 0xA3 | 0xA5 | 0x6F
                ) {
                    KEYEVENTF_EXTENDEDKEY
                } else {
                    Default::default()
                },
                ..Default::default()
            },
        },
    }
}

pub(crate) fn run(action: Action) -> Result<()> {
    let keys: &[VIRTUAL_KEY] = match action {
        Action::PlayPause => &[VK_MEDIA_PLAY_PAUSE],
        Action::NextTrack => &[VK_MEDIA_NEXT_TRACK],
        Action::PreviousTrack => &[VK_MEDIA_PREV_TRACK],
        Action::TaskView => &[VK_LWIN, VK_TAB],
        Action::TaskManager => {
            std::process::Command::new(
                std::path::Path::new(&std::env::var("SystemRoot")?).join("System32/Taskmgr.exe"),
            )
            .spawn()?;
            return Ok(());
        }
        _ => anyhow::bail!("geçersiz Windows eylemi"),
    };
    let releases: Vec<_> = keys.iter().rev().map(|&key| input_key(key, true)).collect();
    let inputs: Vec<_> = keys
        .iter()
        .map(|&key| input_key(key, false))
        .chain(releases.iter().copied())
        .collect();
    let size = size_of::<INPUT>() as i32;
    // SAFETY: Both buffers hold initialized INPUT records with the documented record size.
    let sent = unsafe { SendInput(&inputs, size) };
    if sent > 0 && sent < inputs.len() as u32 {
        // SAFETY: The release records are initialized and the buffer remains live.
        unsafe { SendInput(&releases, size) };
    }
    ensure!(sent == inputs.len() as u32, "Windows girişi kabul etmedi");
    Ok(())
}
pub(crate) fn key_code(name: &str) -> Option<VIRTUAL_KEY> {
    let key = name.trim().to_ascii_uppercase();
    if let [byte] = key.as_bytes()
        && byte.is_ascii_alphanumeric()
    {
        return Some(VIRTUAL_KEY(u16::from(*byte)));
    }
    if let Some(number) = key
        .strip_prefix('F')
        .and_then(|name| name.parse::<u16>().ok())
        && (1..=24).contains(&number)
    {
        return Some(VIRTUAL_KEY(0x6F + number));
    }
    Some(VIRTUAL_KEY(match key.as_str() {
        "CTRL" | "CONTROL" => 0x11,
        "SHIFT" => 0x10,
        "ALT" => 0x12,
        "WIN" => 0x5B,
        "ENTER" => 0x0D,
        "PRTSC" | "PRINTSCREEN" | "SNAPSHOT" => 0x2C,
        "ESC" | "ESCAPE" => 0x1B,
        "SPACE" => 0x20,
        "TAB" => 0x09,
        "BACKSPACE" => 0x08,
        "DELETE" => 0x2E,
        "INSERT" => 0x2D,
        "PAGEUP" => 0x21,
        "PAGEDOWN" => 0x22,
        "END" => 0x23,
        "HOME" => 0x24,
        "LEFT" => 0x25,
        "UP" => 0x26,
        "RIGHT" => 0x27,
        "DOWN" => 0x28,
        _ => return None,
    }))
}

pub(crate) fn keys(keys: &[String], hold_ms: u16, active: impl Fn() -> bool) -> Result<()> {
    ensure!(active(), "işlem iptal edildi");
    let keys: Vec<_> = keys
        .iter()
        .map(|key| key_code(key).ok_or_else(|| anyhow::anyhow!("geçersiz tuş")))
        .collect::<Result<_>>()?;
    let down: Vec<_> = keys.iter().map(|&key| input_key(key, false)).collect();
    let up: Vec<_> = keys.iter().rev().map(|&key| input_key(key, true)).collect();
    if hold_ms == 0 {
        let input: Vec<_> = down.into_iter().chain(up.iter().copied()).collect();
        return submit(&input, &up);
    }
    submit(&down, &up)?;
    let end = Instant::now() + Duration::from_millis(hold_ms.into());
    while active() && Instant::now() < end {
        std::thread::sleep(
            end.saturating_duration_since(Instant::now())
                .min(Duration::from_millis(20)),
        );
    }
    submit(&up, &up)?;
    ensure!(active(), "işlem iptal edildi");
    Ok(())
}

pub(crate) fn type_text(value: &str, active: impl Fn() -> bool) -> Result<()> {
    for units in value.chars() {
        ensure!(active(), "işlem iptal edildi");
        let mut encoded = [0; 2];
        let mut inputs = Vec::with_capacity(4);
        let mut releases = Vec::with_capacity(2);
        for unit in units.encode_utf16(&mut encoded) {
            let mut down = input_key(VIRTUAL_KEY(0), false);
            down.Anonymous.ki.wScan = *unit;
            down.Anonymous.ki.dwFlags = KEYEVENTF_UNICODE;
            let mut up = down;
            // SAFETY: This copy contains the keyboard variant.
            unsafe {
                up.Anonymous.ki.dwFlags |= KEYEVENTF_KEYUP;
            }
            inputs.extend([down, up]);
            releases.push(up);
        }
        submit(&inputs, &releases)?;
    }
    Ok(())
}

pub(crate) fn cursor_position() -> Result<(i32, i32)> {
    let mut point = POINT::default();
    // SAFETY: Win32 writes to a live POINT buffer.
    unsafe { GetPhysicalCursorPos(&mut point) }?;
    Ok((point.x, point.y))
}

pub(crate) fn click(x: i32, y: i32) -> Result<()> {
    // SAFETY: These calls read virtual desktop bounds and set the interactive cursor position.
    unsafe {
        let left = GetSystemMetrics(SM_XVIRTUALSCREEN);
        let top = GetSystemMetrics(SM_YVIRTUALSCREEN);
        let width = GetSystemMetrics(SM_CXVIRTUALSCREEN);
        let height = GetSystemMetrics(SM_CYVIRTUALSCREEN);
        ensure!(
            (left..left + width).contains(&x) && (top..top + height).contains(&y),
            "koordinat masaüstü dışında"
        );
        SetPhysicalCursorPos(x, y)?;
    }
    let input = [MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP].map(|flags| INPUT {
        r#type: INPUT_MOUSE,
        Anonymous: INPUT_0 {
            mi: MOUSEINPUT {
                dwFlags: flags,
                ..Default::default()
            },
        },
    });
    submit(&input, &input[1..])
}

fn submit(input: &[INPUT], cleanup: &[INPUT]) -> Result<()> {
    let size = size_of::<INPUT>() as i32;
    // SAFETY: Both slices contain initialized Win32 INPUT records.
    let sent = unsafe { SendInput(input, size) };
    if sent > 0 && sent < input.len() as u32 {
        // SAFETY: Cleanup contains the matching release records.
        unsafe { SendInput(cleanup, size) };
    }
    ensure!(sent == input.len() as u32, "Windows girişi kabul etmedi");
    Ok(())
}
pub(crate) fn running_programs() -> Option<std::collections::HashSet<String>> {
    use windows::Win32::{
        Foundation::CloseHandle,
        System::Diagnostics::ToolHelp::{
            CreateToolhelp32Snapshot, PROCESSENTRY32W, Process32FirstW, Process32NextW,
            TH32CS_SNAPPROCESS,
        },
    };
    // SAFETY: The handle belongs to this function, and the entry is sized for the API.
    unsafe {
        let handle = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0).ok()?;
        let mut entry = PROCESSENTRY32W {
            dwSize: size_of::<PROCESSENTRY32W>() as u32,
            ..Default::default()
        };
        let mut state = Process32FirstW(handle, &mut entry);
        let mut names = std::collections::HashSet::new();
        while state.is_ok() {
            let length = entry
                .szExeFile
                .iter()
                .position(|&unit| unit == 0)
                .unwrap_or(entry.szExeFile.len());
            names.insert(String::from_utf16_lossy(&entry.szExeFile[..length]).to_ascii_lowercase());
            state = Process32NextW(handle, &mut entry);
        }
        let _ = CloseHandle(handle);
        (!names.is_empty()).then_some(names)
    }
}
