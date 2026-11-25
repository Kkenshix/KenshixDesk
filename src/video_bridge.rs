use std::{
    net::Ipv4Addr,
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
    time::Duration,
};

use anyhow::{Context, Result, ensure};
use axum::{
    Router,
    body::Bytes,
    extract::{
        State, WebSocketUpgrade,
        ws::{Message, WebSocket},
    },
    http::{HeaderMap, StatusCode, header},
    response::{IntoResponse, Response},
    routing::get,
};
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use serde::{Deserialize, Serialize};
use serde_json::json;
use subtle::ConstantTimeEq;
use tokio::sync::broadcast;
use uuid::Uuid;

const PORT: u16 = 43852;
const KEY_FILE: &str = "browser-video.key";
const FRAME_LIMIT: usize = 256 * 1024;

#[derive(Clone)]
pub(crate) struct VideoBridge(Arc<Bridge>);

struct Bridge {
    state: Mutex<Option<Live>>,
    phone: broadcast::Sender<ScreenEvent>,
    browser: broadcast::Sender<(Uuid, Command)>,
}

impl Default for VideoBridge {
    fn default() -> Self {
        Self(Arc::new(Bridge {
            state: Mutex::default(),
            phone: broadcast::channel(18).0,
            browser: broadcast::channel(8).0,
        }))
    }
}

#[derive(Clone, Copy, Deserialize, Serialize)]
#[serde(rename_all = "lowercase")]
enum Site {
    Youtube,
    Kick,
    Opera,
}

#[derive(Clone, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
struct Video {
    id: Uuid,
    site: Site,
    width: u16,
    height: u16,
    volume: u8,
}

impl Video {
    fn valid(&self) -> bool {
        !self.id.is_nil()
            && (2..=960).contains(&self.width)
            && (2..=540).contains(&self.height)
            && self.width.is_multiple_of(2)
            && self.height.is_multiple_of(2)
            && self.volume <= 100
    }
}

struct Live {
    publisher: Uuid,
    video: Video,
    paused: bool,
}

#[derive(Clone)]
enum ScreenEvent {
    Begin(Video),
    End(Uuid),
    Volume(Uuid, u8),
    Paused(Uuid, bool),
    Unavailable,
    Frame(Bytes),
}

#[derive(Clone, Copy, Serialize)]
#[serde(tag = "type", rename_all = "snake_case")]
enum Command {
    Stop,
    Volume { percent: u8 },
}

#[derive(Deserialize)]
#[serde(tag = "type", rename_all = "snake_case", deny_unknown_fields)]
enum BrowserMessage {
    Begin { video: Video },
    End,
    Volume { percent: u8 },
    Paused { value: bool },
    Unavailable,
    Status { stage: TraceStage },
    Ping,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "snake_case")]
enum TraceStage {
    Ready,
    NoMedia,
    Playing,
    Paused,
    Capturing,
    NoFrames,
}

impl VideoBridge {
    pub(crate) fn close(&self, id: Uuid) {
        if let Ok(mut state) = self.0.state.lock()
            && let Some(live) = state.as_ref().filter(|live| live.video.id == id)
        {
            let _ = self.0.browser.send((live.publisher, Command::Stop));
            *state = None;
            let _ = self.0.phone.send(ScreenEvent::End(id));
        }
    }

    pub(crate) fn volume(&self, id: Uuid, percent: u8) -> bool {
        if percent <= 100
            && let Ok(state) = self.0.state.lock()
            && let Some(live) = state.as_ref().filter(|live| live.video.id == id)
        {
            return self
                .0
                .browser
                .send((live.publisher, Command::Volume { percent }))
                .is_ok();
        }
        false
    }

    fn unpublish(&self, publisher: Uuid) {
        if let Ok(mut state) = self.0.state.lock()
            && let Some(live) = state.as_ref().filter(|live| live.publisher == publisher)
        {
            let id = live.video.id;
            *state = None;
            let _ = self.0.phone.send(ScreenEvent::End(id));
        }
    }

    fn update(&self, publisher: Uuid, message: BrowserMessage) {
        let Ok(mut state) = self.0.state.lock() else {
            return;
        };
        let owned = state
            .as_ref()
            .is_some_and(|live| live.publisher == publisher);
        match message {
            BrowserMessage::Begin { video } if video.valid() => {
                if let Some(old) = state.take() {
                    let _ = self.0.phone.send(ScreenEvent::End(old.video.id));
                    if old.publisher != publisher {
                        let _ = self.0.browser.send((old.publisher, Command::Stop));
                    }
                }
                let _ = self.0.phone.send(ScreenEvent::Begin(video.clone()));
                *state = Some(Live {
                    publisher,
                    video,
                    paused: false,
                });
            }
            BrowserMessage::End if owned => {
                let id = state.take().expect("owned stream").video.id;
                let _ = self.0.phone.send(ScreenEvent::End(id));
            }
            BrowserMessage::Volume { percent } if owned && percent <= 100 => {
                let live = state.as_mut().expect("owned stream");
                live.video.volume = percent;
                let _ = self
                    .0
                    .phone
                    .send(ScreenEvent::Volume(live.video.id, percent));
            }
            BrowserMessage::Paused { value } if owned => {
                let live = state.as_mut().expect("owned stream");
                live.paused = value;
                let _ = self.0.phone.send(ScreenEvent::Paused(live.video.id, value));
            }
            BrowserMessage::Unavailable => {
                let _ = self.0.phone.send(ScreenEvent::Unavailable);
            }
            _ => {}
        }
    }

    pub(crate) async fn receive_phone(
        self,
        mut socket: WebSocket,
        authorize: impl AsyncFn() -> bool,
    ) {
        let mut receiver = self.0.phone.subscribe();
        let live = self
            .0
            .state
            .lock()
            .ok()
            .and_then(|state| state.as_ref().map(|live| (live.video.clone(), live.paused)));
        if let Some((video, paused)) = live {
            let id = video.id;
            if !send_phone(&mut socket, ScreenEvent::Begin(video)).await {
                return;
            }
            if paused && !send_phone(&mut socket, ScreenEvent::Paused(id, true)).await {
                return;
            }
        }
        let mut key_needed = true;
        let mut verify = tokio::time::interval(Duration::from_secs(3));
        loop {
            tokio::select! {
                event = receiver.recv() => match event {
                    Ok(ScreenEvent::Frame(frame)) => {
                        if key_needed && frame[0] == 0 { continue; }
                        key_needed = false;
                        if !send_phone(&mut socket, ScreenEvent::Frame(frame)).await { return; }
                    }
                    Ok(event) => {
                        if matches!(event, ScreenEvent::Begin(_) | ScreenEvent::End(_)) { key_needed = true; }
                        if !send_phone(&mut socket, event).await { return; }
                    }
                    Err(broadcast::error::RecvError::Lagged(_)) => {
                        // Reconnect for an authoritative session header if the receiver fell behind.
                        return;
                    }
                    Err(broadcast::error::RecvError::Closed) => return,
                },
                incoming = socket.recv() => match incoming {
                    Some(Ok(Message::Ping(_) | Message::Pong(_))) => {}
                    _ => return,
                },
                _ = verify.tick() => {
                    if !authorize().await { return; }
                }
            }
        }
    }

    pub(crate) fn launch(&self, directory: &Path) -> Result<()> {
        let key = match std::fs::read_to_string(directory.join(KEY_FILE)) {
            Ok(raw) => raw.trim().to_owned(),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(()),
            Err(error) => return Err(error).context("Tarayıcı bağlantı anahtarı okunamadı"),
        };
        ensure!(valid_key(&key), "Tarayıcı bağlantı anahtarı geçersiz");
        let listener = std::net::TcpListener::bind((Ipv4Addr::LOCALHOST, PORT))
            .context("Tarayıcı bağlantı noktası açılamadı")?;
        listener.set_nonblocking(true)?;
        let listener = tokio::net::TcpListener::from_std(listener)?;
        let app = Router::new()
            .route("/capture", get(upgrade_browser))
            .with_state((self.clone(), key));
        tokio::spawn(async move {
            if let Err(error) = axum::serve(listener, app).await {
                eprintln!("Tarayıcı bağlantısı durdu: {error}");
            }
        });
        Ok(())
    }
}

async fn send_phone(socket: &mut WebSocket, event: ScreenEvent) -> bool {
    let response = match event {
        ScreenEvent::Frame(frame) => Message::Binary(frame),
        ScreenEvent::Begin(video) => {
            Message::Text(json!({"type":"begin","video":video}).to_string().into())
        }
        ScreenEvent::End(id) => Message::Text(json!({"type":"end","id":id}).to_string().into()),
        ScreenEvent::Volume(id, percent) => Message::Text(
            json!({"type":"volume","id":id,"percent":percent})
                .to_string()
                .into(),
        ),
        ScreenEvent::Paused(id, value) => Message::Text(
            json!({"type":"paused","id":id,"value":value})
                .to_string()
                .into(),
        ),
        ScreenEvent::Unavailable => Message::Text(json!({"type":"unavailable"}).to_string().into()),
    };
    matches!(
        tokio::time::timeout(Duration::from_millis(450), socket.send(response)).await,
        Ok(Ok(()))
    )
}

async fn upgrade_browser(
    State((bridge, key)): State<(VideoBridge, String)>,
    headers: HeaderMap,
    upgrade: WebSocketUpgrade,
) -> Response {
    if headers.get(header::ORIGIN).is_some_and(|origin| {
        !origin
            .to_str()
            .is_ok_and(|value| value.starts_with("chrome-extension://"))
    }) {
        return StatusCode::UNAUTHORIZED.into_response();
    }
    upgrade
        .max_frame_size(FRAME_LIMIT)
        .max_message_size(FRAME_LIMIT)
        .on_upgrade(move |socket| browser_socket(socket, bridge, key))
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct BrowserHello {
    key: String,
}

fn foreground_pid() -> Option<u32> {
    use windows::Win32::UI::WindowsAndMessaging::{GetForegroundWindow, GetWindowThreadProcessId};
    let mut pid = 0;
    // SAFETY: The HWND comes from Windows and the output pointer is valid for one u32.
    let thread = unsafe { GetWindowThreadProcessId(GetForegroundWindow(), Some(&mut pid)) };
    (thread != 0 && pid != 0).then_some(pid)
}

fn browser_process(pid: u32) -> bool {
    crate::audio::process_image(pid).is_some_and(|image| {
        Path::new(&image).file_name().is_some_and(|name| {
            let name = name.to_string_lossy();
            [
                "opera.exe",
                "chrome.exe",
                "msedge.exe",
                "brave.exe",
                "vivaldi.exe",
                "chromium.exe",
            ]
            .iter()
            .any(|candidate| name.eq_ignore_ascii_case(candidate))
        })
    })
}

async fn browser_socket(mut socket: WebSocket, bridge: VideoBridge, key: String) {
    let hello = tokio::time::timeout(Duration::from_secs(3), socket.recv()).await;
    let Ok(Some(Ok(Message::Text(hello)))) = hello else {
        return;
    };
    if !serde_json::from_str::<BrowserHello>(&hello)
        .is_ok_and(|hello| bool::from(hello.key.as_bytes().ct_eq(key.as_bytes())))
    {
        return;
    }
    let publisher = Uuid::new_v4();
    let mut commands = bridge.0.browser.subscribe();
    let _ = socket
        .send(Message::Text("{\"type\":\"ready\"}".into()))
        .await;
    let trace = std::env::var_os("KDECK_VIDEO_TRACE").is_some();
    let mut foreground = tokio::time::interval(Duration::from_millis(45));
    foreground.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    let mut previous_pid = None;
    let mut previous_focus = None;
    let mut first_frame = false;
    if trace {
        eprintln!("Video: browser connected");
    }
    loop {
        tokio::select! {
            message = socket.recv() => match message {
                Some(Ok(Message::Text(text))) => {
                    if let Ok(event) = serde_json::from_str(&text) {
                        match event {
                            BrowserMessage::Status { stage } => {
                                if trace { eprintln!("Video: browser status {stage:?}"); }
                            }
                            event => {
                                if matches!(event, BrowserMessage::Begin { .. }) {
                                    first_frame = false;
                                    if trace { eprintln!("Video: began"); }
                                }
                                if matches!(event, BrowserMessage::End) && trace { eprintln!("Video: ended"); }
                                bridge.update(publisher, event);
                            }
                        }
                    }
                }
                Some(Ok(Message::Binary(frame))) if frame.len() > 9 && frame[0] <= 1 => {
                    if let Ok(state) = bridge.0.state.lock()
                        && state.as_ref().is_some_and(|live| live.publisher == publisher)
                    {
                        if !first_frame {
                            first_frame = true;
                            if trace { eprintln!("Video: first frame"); }
                        }
                        let _ = bridge.0.phone.send(ScreenEvent::Frame(frame));
                    }
                }
                Some(Ok(Message::Pong(_) | Message::Ping(_))) => {}
                _ => break,
            },
            _ = foreground.tick() => {
                let Some(pid) = foreground_pid() else { continue };
                if Some(pid) == previous_pid { continue; }
                previous_pid = Some(pid);
                let focused = browser_process(pid);
                if Some(focused) == previous_focus { continue; }
                previous_focus = Some(focused);
                if trace { eprintln!("Video: Windows browser focused = {focused}"); }
                let message = Message::Text(json!({"type":"foreground","value":focused}).to_string().into());
                if !matches!(tokio::time::timeout(Duration::from_millis(450), socket.send(message)).await,
                    Ok(Ok(()))) { break; }
            },
            command = commands.recv() => match command {
                Ok((id, command)) if id == publisher => {
                    if !matches!(tokio::time::timeout(Duration::from_millis(450),
                        socket.send(Message::Text(json!(command).to_string().into()))).await, Ok(Ok(()))) { break; }
                }
                Ok(_) | Err(broadcast::error::RecvError::Lagged(_)) => {}
                _ => break,
            }
        }
    }
    bridge.unpublish(publisher);
}

fn valid_key(key: &str) -> bool {
    key.len() == 43
        && key
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || b"_-".contains(&byte))
}

pub(crate) fn install(directory: &Path) -> Result<PathBuf> {
    let key_path = directory.join(KEY_FILE);
    let key = match std::fs::read_to_string(&key_path) {
        Ok(raw) if valid_key(raw.trim()) => raw.trim().to_owned(),
        Ok(_) => anyhow::bail!("Mevcut tarayıcı bağlantı anahtarı geçersiz"),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            use std::io::Write;
            let key = URL_SAFE_NO_PAD.encode(rand::random::<[u8; 32]>());
            let mut file = std::fs::OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(&key_path)?;
            file.write_all(key.as_bytes())?;
            key
        }
        Err(error) => return Err(error).context("Tarayıcı bağlantı anahtarı okunamadı"),
    };
    let extension = directory.join("browser-extension");
    std::fs::create_dir_all(&extension)?;
    for (name, data) in [
        ("manifest.json", include_str!("../browser/manifest.json")),
        ("worker.js", include_str!("../browser/worker.js")),
        ("video.js", include_str!("../browser/video.js")),
    ] {
        std::fs::write(extension.join(name), data)?;
    }
    std::fs::write(
        extension.join("config.js"),
        format!("const bridgeKey = {};\n", serde_json::to_string(&key)?),
    )?;
    Ok(extension)
}
