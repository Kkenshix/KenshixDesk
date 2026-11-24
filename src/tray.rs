use std::{sync::mpsc, time::Duration};

use anyhow::{Result, ensure};
use tray_icon::{
    Icon, TrayIconBuilder,
    menu::{Menu, MenuEvent, MenuItem, PredefinedMenuItem},
};
use windows::{
    Win32::UI::WindowsAndMessaging::{
        DispatchMessageW, GetMessageW, MB_OK, MSG, MessageBoxW, PostQuitMessage, TranslateMessage,
    },
    core::PCWSTR,
};

fn tell(message: &str) {
    let message: Vec<u16> = message.encode_utf16().chain([0]).collect();
    // SAFETY: The string is NUL-terminated and remains live for the modal call.
    unsafe {
        MessageBoxW(
            None,
            PCWSTR(message.as_ptr()),
            windows::core::w!("KenshixDeck"),
            MB_OK,
        )
    };
}

pub(crate) fn run(
    create_code: impl Fn() -> Result<String> + Send + Sync + 'static,
    revoke: impl Fn() -> Result<()> + Send + Sync + 'static,
    status: String,
    directory: std::path::PathBuf,
    editor_adb: Option<(String, String)>,
    ready: mpsc::SyncSender<()>,
) -> Result<()> {
    let menu = Menu::new();
    let pair = MenuItem::with_id("pair", "Eşleştirme kodunu kopyala", true, None);
    let editor = MenuItem::with_id("editor", "Paneli düzenle", true, None);
    let update = MenuItem::with_id("update", "Güncellemeleri kontrol et", true, None);
    let version = MenuItem::with_id("status", "Bağlantı ve sürüm", true, None);
    let revoke_item = MenuItem::with_id("revoke", "Telefon erişimini iptal et", true, None);
    let quit = MenuItem::with_id("quit", "Çıkış", true, None);
    menu.append_items(&[
        &pair,
        &editor,
        &version,
        &update,
        &revoke_item,
        &PredefinedMenuItem::separator(),
        &quit,
    ])?;
    let icon = Icon::from_rgba(
        include_bytes!("../assets/dragon-tray.rgba").to_vec(),
        32,
        32,
    )?;
    let _tray = TrayIconBuilder::new()
        .with_menu(Box::new(menu))
        .with_menu_on_left_click(true)
        .with_tooltip("KenshixDeck")
        .with_icon(icon)
        .build()?;

    let editor_process = std::sync::Mutex::new(None::<std::process::Child>);
    MenuEvent::set_event_handler(Some(move |event: MenuEvent| {
        match event.id.0.as_str() {
            "pair" => match create_code()
                .and_then(|code| Ok(arboard::Clipboard::new()?.set_text(code)?))
            {
                Ok(()) => tell(
                    "Eşleştirme kodu kopyalandı. Telefonda KenshixDeck eşleştirme ekranına gir. Kod beş dakika geçerlidir.",
                ),
                Err(_) => tell("Eşleştirme kodu kopyalanamadı."),
            },
            "status" => tell(&format!(
                "{status}\n{}",
                crate::updater::current(&directory)
            )),
            "update" => {
                crate::updater::request_check();
                tell(&crate::updater::current(&directory));
            }
            "editor" => {
                let mut editor_process = editor_process.lock().expect("editor lock");
                if editor_process
                    .as_mut()
                    .is_some_and(|process| matches!(process.try_wait(), Ok(None)))
                {
                    return;
                }
                *editor_process = std::env::current_exe()
                    .and_then(|exe| {
                        let mut command = std::process::Command::new(exe);
                        command.arg("--editor");
                        if let Some((adb, serial)) = &editor_adb {
                            command.args(["--adb", adb, serial]);
                        }
                        use std::os::windows::process::CommandExt as _;
                        command.creation_flags(0x08000000).spawn()
                    })
                    .ok();
                if editor_process.is_none() {
                    tell("Panel editörü açılamadı.");
                }
            }

            "revoke" => match revoke() {
                Ok(()) => tell("Telefon erişimi iptal edildi."),
                Err(_) => tell("Telefon erişimi iptal edilemedi."),
            },
            "quit" => {
                // SAFETY: The menu callback runs on the tray thread's Win32 message loop.
                unsafe { PostQuitMessage(0) };
            }
            _ => (),
        }
    }));
    ready.send(())?;
    let mut message = MSG::default();
    // SAFETY: This thread owns the tray and its message queue. The message is
    // forwarded only when GetMessageW initialized it successfully.
    unsafe {
        loop {
            let state = GetMessageW(&mut message, None, 0, 0).0;
            ensure!(state >= 0, "sistem tepsisi iletisi okunamadı");
            if state == 0 {
                break;
            }
            let _ = TranslateMessage(&message);
            DispatchMessageW(&message);
        }
    }
    Ok(())
}

pub(crate) const READY_TIMEOUT: Duration = Duration::from_secs(4);
