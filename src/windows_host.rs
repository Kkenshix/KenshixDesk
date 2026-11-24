use anyhow::{Result, ensure};
use windows::{
    Win32::{
        Foundation::{HLOCAL, LocalFree},
        Security::Cryptography::{
            CRYPT_INTEGER_BLOB, CRYPTPROTECT_UI_FORBIDDEN, CryptProtectData, CryptUnprotectData,
        },
        UI::Input::KeyboardAndMouse::{
            INPUT, INPUT_0, INPUT_KEYBOARD, KEYBDINPUT, KEYEVENTF_KEYUP, SendInput, VIRTUAL_KEY,
            VK_LWIN, VK_MEDIA_NEXT_TRACK, VK_MEDIA_PLAY_PAUSE, VK_MEDIA_PREV_TRACK, VK_TAB,
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
