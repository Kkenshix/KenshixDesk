use std::{
    net::Ipv4Addr,
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};

use anyhow::{Context, Result, ensure};
use axum::{
    Json, Router,
    extract::{DefaultBodyLimit, State},
    http::{HeaderMap, StatusCode, header},
    routing::post,
};
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use subtle::ConstantTimeEq;
use uuid::Uuid;

const PORT: u16 = 43852;
const LEASE: Duration = Duration::from_secs(20);
const KEY_FILE: &str = "browser-video.key";

#[derive(Clone, Default)]
pub(crate) struct VideoBridge(Arc<Mutex<Option<Pending>>>);

#[derive(Clone, Copy, Deserialize, Serialize)]
#[serde(rename_all = "lowercase")]
enum Site {
    Youtube,
    Kick,
}

#[derive(Clone, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct Video {
    id: Uuid,
    site: Site,
    key: String,
    seconds: u32,
}

impl Video {
    fn valid(&self) -> bool {
        let length = self.key.len();
        !self.id.is_nil()
            && self.seconds <= 86_400
            && self
                .key
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || b"_-".contains(&byte))
            && match self.site {
                Site::Youtube => length == 11,
                Site::Kick => {
                    (2..=50).contains(&length)
                        && self.key.bytes().all(|byte| !byte.is_ascii_uppercase())
                }
            }
    }
}

struct Pending {
    video: Video,
    until: Instant,
    playing: bool,
    closed: bool,
}

#[derive(Clone, Copy, Deserialize, Debug)]
#[serde(rename_all = "snake_case")]
pub(crate) enum PhoneEvent {
    Playing,
    Closed,
}

impl VideoBridge {
    pub(crate) fn snapshot(&self) -> Option<Video> {
        let state = self.0.lock().ok()?;
        state
            .as_ref()
            .filter(|item| !item.closed && item.until > Instant::now())
            .map(|item| item.video.clone())
    }

    pub(crate) fn phone_event(&self, id: Uuid, event: PhoneEvent) {
        if let Ok(mut state) = self.0.lock()
            && let Some(item) = state
                .as_mut()
                .filter(|item| item.video.id == id && !item.closed)
        {
            match event {
                PhoneEvent::Playing => item.playing = true,
                PhoneEvent::Closed => item.closed = true,
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
            .route("/handoff", post(receive))
            .layer(DefaultBodyLimit::max(1024))
            .with_state((self.clone(), key));
        tokio::spawn(async move {
            if let Err(error) = axum::serve(listener, app).await {
                eprintln!("Tarayıcı bağlantısı durdu: {error}");
            }
        });
        Ok(())
    }
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct BrowserRequest {
    video: Option<Video>,
    watch: Option<Uuid>,
}

async fn receive(
    State((bridge, key)): State<(VideoBridge, String)>,
    headers: HeaderMap,
    Json(request): Json<BrowserRequest>,
) -> Result<Json<Value>, StatusCode> {
    let supplied = headers
        .get(header::AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.strip_prefix("Bearer "))
        .unwrap_or_default();
    if !bool::from(supplied.as_bytes().ct_eq(key.as_bytes()))
        || headers.get(header::ORIGIN).is_some_and(|origin| {
            !origin
                .to_str()
                .is_ok_and(|value| value.starts_with("chrome-extension://"))
        })
    {
        return Err(StatusCode::UNAUTHORIZED);
    }
    let mut state = bridge
        .0
        .lock()
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    match (request.video, request.watch) {
        (Some(video), None) if video.valid() => {
            if state.as_ref().is_none_or(|item| item.video.id != video.id) {
                *state = Some(Pending {
                    video,
                    until: Instant::now() + LEASE,
                    playing: false,
                    closed: false,
                });
            }
            Ok(Json(json!({"accepted":true})))
        }
        (None, Some(id)) => Ok(Json(json!({"playing":state.as_ref().is_some_and(|item| {
            item.video.id == id && item.playing && !item.closed && item.until > Instant::now()
        })}))),
        _ => Err(StatusCode::BAD_REQUEST),
    }
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
