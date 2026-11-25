use anyhow::{Context, Result, ensure};
use serde::{Deserialize, Serialize};
use windows::{
    Win32::{
        Foundation::{HWND, LPARAM, WPARAM},
        UI::WindowsAndMessaging::{
            EnumWindows, FlashWindow, GW_OWNER, GWL_EXSTYLE, GetForegroundWindow, GetWindow,
            GetWindowLongW, GetWindowTextW, GetWindowThreadProcessId, IsIconic, IsWindowVisible,
            IsZoomed, PostMessageW, SW_MAXIMIZE, SW_MINIMIZE, SW_RESTORE, SetForegroundWindow,
            ShowWindowAsync, WM_CLOSE, WS_EX_TOOLWINDOW,
        },
    },
    core::BOOL as CallbackBool,
};

#[derive(Clone, Copy, Debug, Deserialize)]
#[serde(rename_all = "snake_case")]
pub(crate) enum Command {
    Focus,
    Minimize,
    Maximize,
    Restore,
    NextMonitor,
    Close,
}

#[derive(Serialize)]
pub(crate) struct Entry {
    id: String,
    title: String,
    app: String,
    active: bool,
    minimized: bool,
    maximized: bool,
}

pub(crate) fn list() -> Result<Vec<Entry>> {
    Ok(windows()?
        .into_iter()
        .map(|(handle, title, pid)| Entry {
            id: id(handle, pid),
            title,
            app: crate::audio::process_image(pid)
                .and_then(|path| {
                    std::path::Path::new(&path)
                        .file_stem()
                        .map(|name| name.to_string_lossy().into_owned())
                })
                .unwrap_or_default(),
            // SAFETY: These APIs inspect the live enumerated window and foreground state.
            active: unsafe { GetForegroundWindow() == handle },
            minimized: unsafe { IsIconic(handle).as_bool() },
            maximized: unsafe { IsZoomed(handle).as_bool() },
        })
        .collect())
}

fn id(handle: HWND, pid: u32) -> String {
    format!("{pid:x}:{:x}", handle.0 as usize)
}

pub(crate) fn execute(target: &str, command: Command) -> Result<()> {
    let (window, _, _) = windows()?
        .into_iter()
        .find(|(handle, _, pid)| id(*handle, *pid) == target)
        .context("pencere artık açık değil")?;
    // SAFETY: The window was re-enumerated on this desktop and no pointer is passed to its process.
    unsafe {
        match command {
            Command::Focus | Command::NextMonitor => {
                if IsIconic(window).as_bool() {
                    let _ = ShowWindowAsync(window, SW_RESTORE);
                }
                if GetForegroundWindow() != window && !SetForegroundWindow(window).as_bool() {
                    let _ = FlashWindow(window, true);
                    anyhow::bail!("Windows pencereyi öne getirme isteğini engelledi");
                }
                if matches!(command, Command::NextMonitor) {
                    crate::windows_host::keys(
                        &["WIN".into(), "SHIFT".into(), "RIGHT".into()],
                        0,
                        || true,
                    )?;
                }
            }
            Command::Close => PostMessageW(Some(window), WM_CLOSE, WPARAM(0), LPARAM(0))?,
            _ => {
                let action = match command {
                    Command::Minimize => SW_MINIMIZE,
                    Command::Maximize => SW_MAXIMIZE,
                    _ => SW_RESTORE,
                };
                ensure!(
                    ShowWindowAsync(window, action).as_bool(),
                    "pencere komutu gönderilemedi"
                );
            }
        }
    }
    Ok(())
}

fn windows() -> Result<Vec<(HWND, String, u32)>> {
    let mut items: Vec<(HWND, String, u32)> = Vec::new();
    unsafe extern "system" fn collect(window: HWND, context: LPARAM) -> CallbackBool {
        // SAFETY: EnumWindows invokes synchronously with the address of the live Vec.
        let items = unsafe { &mut *(context.0 as *mut Vec<(HWND, String, u32)>) };
        // SAFETY: The handle belongs to the enumerated top-level window.
        unsafe {
            if items.len() >= 64
                || !IsWindowVisible(window).as_bool()
                || GetWindow(window, GW_OWNER).is_ok()
                || GetWindowLongW(window, GWL_EXSTYLE) & WS_EX_TOOLWINDOW.0 as i32 != 0
            {
                return CallbackBool(1);
            }
            let mut caption = [0u16; 150];
            let length = GetWindowTextW(window, &mut caption);
            let mut pid = 0;
            GetWindowThreadProcessId(window, Some(&mut pid));
            if length > 0 && pid != 0 {
                let title = String::from_utf16_lossy(&caption[..length as usize]);
                items.push((window, title, pid));
            }
        }
        CallbackBool(1)
    }
    // SAFETY: The Vec lives through the entire synchronous call.
    unsafe { EnumWindows(Some(collect), LPARAM((&mut items as *mut Vec<_>) as isize)) }?;
    Ok(items)
}
