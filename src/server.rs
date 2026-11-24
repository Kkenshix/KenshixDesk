use std::{
    net::{Ipv4Addr, SocketAddr},
    path::PathBuf,
    sync::{Arc, Mutex},
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use anyhow::{Context, Result, ensure};
use axum::{
    Router,
    extract::{
        State, WebSocketUpgrade,
        ws::{Message, WebSocket},
    },
    response::Response,
    routing::get,
};
use axum_server::tls_rustls::RustlsConfig;
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use rusqlite::Connection;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;
use uuid::Uuid;

use crate::{
    protocol::{Action, MAX_MESSAGE, PORT, Request, VERSION},
    store, windows_host,
};

const TOKEN_DURATION: u64 = 90 * 24 * 60 * 60;
const PAIR_DURATION: Duration = Duration::from_secs(300);
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(3);

struct Pairing {
    code: String,
    expires: Instant,
    attempts: u8,
}

struct Host {
    db: Connection,
    pairing: Option<Pairing>,
}

type Shared = Arc<Mutex<Host>>;

fn secret() -> String {
    URL_SAFE_NO_PAD.encode(rand::random::<[u8; 24]>())
}

fn hash(bytes: &[u8]) -> [u8; 32] {
    Sha256::digest(bytes).into()
}

fn now() -> Result<u64> {
    Ok(SystemTime::now().duration_since(UNIX_EPOCH)?.as_secs())
}

fn tls_identity(db: &Connection) -> Result<(Vec<u8>, Vec<u8>)> {
    if let Some(identity) = store::get(db, "tls_identity")? {
        let (certificate, key): (Vec<u8>, Vec<u8>) = serde_json::from_slice(&identity)?;
        return Ok((certificate, windows_host::protect(&key, false)?));
    }
    let (certificate, key) = {
        let identity = rcgen::generate_simple_self_signed(vec!["kenshixdeck.local".to_owned()])?;
        (
            identity.cert.der().as_ref().to_vec(),
            identity.signing_key.serialize_der(),
        )
    };
    let encrypted = windows_host::protect(&key, true)?;
    store::put(
        db,
        "tls_identity",
        &serde_json::to_vec(&(&certificate, encrypted))?,
    )?;
    Ok((certificate, key))
}

pub(crate) async fn start() -> Result<()> {
    let mut arguments = std::env::args().skip(1);
    let mut lan = None;
    let mut pair = false;
    let mut revoke = false;
    let mut headless = false;
    let mut obs_port = None;
    let mut edit = None;
    let mut editor = false;
    let mut adb: Option<(String, String)> = None;
    while let Some(argument) = arguments.next() {
        match argument.as_str() {
            "--pair" => pair = true,
            "--editor" => editor = true,
            "--buttons" | "--add-button" | "--remove-button" => {
                ensure!(
                    edit.is_none(),
                    "her çağrıda bir düzenleme işlemi kullanılabilir"
                );
                edit = Some(crate::desktop::Edit::from_arguments(
                    &argument,
                    &mut arguments,
                )?);
            }
            "--revoke" => revoke = true,
            "--headless" => headless = true,
            "--configure-obs" => {
                obs_port = Some(
                    arguments
                        .next()
                        .context("OBS bağlantı noktası gerekli")?
                        .parse()?,
                )
            }
            "--lan" => {
                let address: Ipv4Addr = arguments.next().context("LAN adresi gerekli")?.parse()?;
                ensure!(
                    address.is_private(),
                    "LAN adresi özel bir IPv4 adresi olmalı"
                );
                lan = Some(address);
            }
            "--adb" => {
                let executable = arguments.next().context("ADB yolu gerekli")?;
                let serial = arguments.next().context("Telefon seri numarası gerekli")?;
                ensure!(
                    !serial.is_empty()
                        && serial
                            .bytes()
                            .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-'),
                    "USB seri numarası geçersiz"
                );
                adb = Some((executable, serial));
            }
            _ => anyhow::bail!("bilinmeyen seçenek: {argument}"),
        }
    }

    let directory =
        PathBuf::from(std::env::var_os("LOCALAPPDATA").context("LOCALAPPDATA bulunamadı")?)
            .join("KenshixDeck");
    std::fs::create_dir_all(&directory)?;
    let mut db = store::open(&directory.join("deck.sqlite3"))?;
    if editor {
        return crate::editor::open(db, adb);
    }
    if let Some(edit) = edit {
        return edit.apply(&mut db);
    }
    if let Some(port) = obs_port {
        crate::obs::configure(&db, port)?;
        println!("OBS bağlantı ayarı kaydedildi.");
        return Ok(());
    }
    if revoke {
        db.execute("DELETE FROM settings WHERE name = 'token'", [])?;
        println!("Telefon erişimi iptal edildi.");
        return Ok(());
    }
    rustls::crypto::ring::default_provider()
        .install_default()
        .map_err(|_| anyhow::anyhow!("TLS sağlayıcısı başlatılamadı"))?;
    let (certificate, key) = tls_identity(&db)?;
    let fingerprint = format!("{:x}", Sha256::digest(&certificate));
    let tls = RustlsConfig::from_der(vec![certificate], key).await?;
    let usb_address = SocketAddr::from((Ipv4Addr::LOCALHOST, PORT));
    let usb_listener =
        std::net::TcpListener::bind(usb_address).context("USB dinleyicisi açılamadı")?;
    let lan_listener = lan
        .map(|address| std::net::TcpListener::bind((address, PORT)))
        .transpose()
        .context("LAN dinleyicisi açılamadı")?;
    usb_listener.set_nonblocking(true)?;
    if let Some(listener) = &lan_listener {
        listener.set_nonblocking(true)?;
    }
    let pairing = pair.then(|| Pairing {
        code: secret(),
        expires: Instant::now() + PAIR_DURATION,
        attempts: 0,
    });
    if let Some(pairing) = &pairing {
        println!("Eşleştirme (5 dakika): {}.{}", fingerprint, pairing.code);
    }
    let editor_adb = adb.clone();
    crate::updater::launch(directory.clone(), adb.clone());
    if let Some((executable, serial)) = adb {
        tokio::spawn(adb_reverse(executable, serial));
    }
    crate::automation::launch(directory.join("deck.sqlite3"));
    let shared = Arc::new(Mutex::new(Host { db, pairing }));
    if !headless {
        let (ready, started) = std::sync::mpsc::sync_channel(1);
        let code_host = Arc::clone(&shared);
        let revoke_host = Arc::clone(&shared);
        let status = format!(
            "KenshixDeck {}
USB: {}
Wi-Fi: {}
Build: {}",
            env!("CARGO_PKG_VERSION"),
            PORT,
            lan.map(|address| address.to_string())
                .unwrap_or_else(|| "Kapalı".to_owned()),
            option_env!("DECK_SHA").unwrap_or("local")
        );
        std::thread::spawn(move || {
            let result = crate::tray::run(
                move || {
                    let mut host = code_host
                        .lock()
                        .map_err(|_| anyhow::anyhow!("host kilidi"))?;
                    let code = secret();
                    let text = format!("{}.{}", fingerprint, code);
                    host.pairing = Some(Pairing {
                        code,
                        expires: Instant::now() + PAIR_DURATION,
                        attempts: 0,
                    });
                    Ok(text)
                },
                move || {
                    let mut host = revoke_host
                        .lock()
                        .map_err(|_| anyhow::anyhow!("host kilidi"))?;
                    host.db
                        .execute("DELETE FROM settings WHERE name = 'token'", [])?;
                    host.pairing = None;
                    Ok(())
                },
                status,
                directory,
                editor_adb,
                ready,
            );
            if let Err(error) = result {
                eprintln!("{error:#}");
            }
            std::process::exit(0);
        });
        started
            .recv_timeout(crate::tray::READY_TIMEOUT)
            .context("sistem tepsisi başlatılamadı")?;
    }
    let app = Router::new().route("/v1", get(upgrade)).with_state(shared);
    let service = app.clone().into_make_service();
    let usb = axum_server::from_tcp_rustls(usb_listener, tls.clone())?.serve(service);
    println!("KenshixDeck USB portu: {PORT}");
    if let Some(listener) = lan_listener {
        println!("KenshixDeck Wi-Fi: {}", listener.local_addr()?);
        let wifi = axum_server::from_tcp_rustls(listener, tls)?.serve(app.into_make_service());
        tokio::try_join!(usb, wifi)?;
    } else {
        usb.await?;
    }
    Ok(())
}

async fn adb_reverse(executable: String, serial: String) {
    let port = format!("tcp:{PORT}");
    loop {
        let mut command = tokio::process::Command::new(&executable);
        command.args(["-s", &serial, "reverse", "--no-rebind", &port, &port]);
        command
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .kill_on_drop(true);
        let _ = tokio::time::timeout(Duration::from_secs(3), command.status()).await;
        tokio::time::sleep(Duration::from_secs(2)).await;
    }
}

async fn upgrade(State(host): State<Shared>, ws: WebSocketUpgrade) -> Response {
    ws.max_frame_size(MAX_MESSAGE)
        .max_message_size(MAX_MESSAGE)
        .on_upgrade(move |socket| session(socket, host))
}

async fn receive(socket: &mut WebSocket) -> Option<Request> {
    loop {
        match socket.recv().await? {
            Ok(Message::Text(text)) => return serde_json::from_str(text.as_str()).ok(),
            Ok(Message::Ping(_) | Message::Pong(_)) => (),
            _ => return None,
        }
    }
}

async fn send(socket: &mut WebSocket, response: Value) -> bool {
    matches!(
        tokio::time::timeout(
            Duration::from_secs(3),
            socket.send(Message::Text(response.to_string().into()))
        )
        .await,
        Ok(Ok(()))
    )
}

async fn with_host<T: Send + 'static>(
    host: Shared,
    task: impl FnOnce(&mut Host) -> Result<T> + Send + 'static,
) -> Result<T> {
    tokio::task::spawn_blocking(move || {
        let mut host = host.lock().map_err(|_| anyhow::anyhow!("host kilidi"))?;
        task(&mut host)
    })
    .await?
}

fn authorized(db: &Connection, digest: &[u8; 32]) -> Result<bool> {
    let Some(bytes) = store::get(db, "token")? else {
        return Ok(false);
    };
    let (stored, expires): ([u8; 32], u64) = serde_json::from_slice(&bytes)?;
    Ok(bool::from(stored.ct_eq(digest)) && now()? < expires)
}

fn pair(host: &mut Host, code: &str) -> Result<Value> {
    let denied = json!({"type":"error","code":"pair_denied"});
    let Some(pairing) = &mut host.pairing else {
        return Ok(denied);
    };
    if Instant::now() >= pairing.expires || pairing.attempts >= 5 {
        return Ok(denied);
    }
    pairing.attempts += 1;
    if !bool::from(hash(pairing.code.as_bytes()).ct_eq(&hash(code.as_bytes()))) {
        return Ok(denied);
    }
    let token = secret();
    store::put(
        &host.db,
        "token",
        &serde_json::to_vec(&(hash(token.as_bytes()), now()? + TOKEN_DURATION))?,
    )?;
    host.pairing = None;
    Ok(json!({"type":"paired","token":token}))
}

async fn session(mut socket: WebSocket, host: Shared) {
    let Ok(Some(request)) = tokio::time::timeout(HANDSHAKE_TIMEOUT, receive(&mut socket)).await
    else {
        return;
    };
    let digest = match request {
        Request::Pair {
            version: VERSION,
            code,
        } => {
            if let Ok(response) = with_host(host, move |host| pair(host, &code)).await {
                send(&mut socket, response).await;
            }
            return;
        }
        Request::Hello {
            version: VERSION,
            token,
        } => hash(token.as_bytes()),
        _ => {
            send(&mut socket, json!({"type":"error","code":"protocol"})).await;
            return;
        }
    };
    let initial = with_host(Arc::clone(&host), move |host| {
        if authorized(&host.db, &digest)? {
            Ok(json!({"type":"snapshot","deck":store::deck(&host.db)?}))
        } else {
            Ok(json!({"type":"error","code":"unauthorized"}))
        }
    })
    .await;
    let Ok(initial) = initial else { return };
    let active = initial["type"] == "snapshot";
    if !send(&mut socket, initial).await || !active {
        return;
    }

    while let Ok(Some(request)) =
        tokio::time::timeout(Duration::from_secs(15), receive(&mut socket)).await
    {
        let result = with_host(Arc::clone(&host), move |host| {
            if !authorized(&host.db, &digest)? {
                return Ok(json!({"type":"error","code":"unauthorized"}));
            }
            handle(&mut host.db, request)
        })
        .await
        .unwrap_or_else(|_| json!({"type":"error","code":"host_error"}));
        let active = result["code"] != "unauthorized";
        if !send(&mut socket, result).await || !active {
            return;
        }
        tokio::time::sleep(Duration::from_millis(30)).await;
    }
}

fn operation(
    db: &mut Connection,
    operation: Uuid,
    action: impl FnOnce(&Connection) -> Result<()>,
) -> Result<Value> {
    if let Some(result) = store::claim(db, operation)? {
        return Ok(json!({"type":"action_result","operation":operation,"status":result}));
    }
    let status = if action(db).is_ok() { "ok" } else { "failed" };
    store::finish(db, operation, status)?;
    Ok(json!({"type":"action_result","operation":operation,"status":status}))
}

fn handle(db: &mut Connection, request: Request) -> Result<Value> {
    Ok(match request {
        Request::Snapshot => {
            json!({"type":"snapshot","deck":store::deck(db)?,"alert":crate::automation::alert(db)})
        }
        Request::Save { deck } if deck.validate() => match store::save(db, deck)? {
            Some(deck) => json!({"type":"saved","deck":deck}),
            None => json!({"type":"conflict","deck":store::deck(db)?}),
        },
        Request::Execute {
            operation: id,
            button,
        } => {
            let button = store::deck(db)?
                .buttons
                .into_iter()
                .find(|record| record.id == button);
            if let Some(routine) = button.as_ref().and_then(|button| button.routine.clone()) {
                let status = crate::routine::start(db, id, routine)?;
                return Ok(json!({"type":"action_result","operation":id,"status":status}));
            }
            match button.map(|button| button.action) {
                Some(
                    action @ (Action::PlayPause
                    | Action::NextTrack
                    | Action::PreviousTrack
                    | Action::TaskView
                    | Action::TaskManager),
                ) => operation(db, id, |_| windows_host::run(action))?,
                Some(action) if !matches!(action, Action::Mixer | Action::ObsStatus) => {
                    operation(db, id, |db| crate::obs::run(db, action))?
                }
                _ => json!({"type":"action_result","operation":id,"status":"failed"}),
            }
        }
        Request::ButtonArt { id, offset } => crate::art::chunk(db, &id, offset)?,
        Request::OperationStatus { operation } => {
            let status = crate::routine::status(db, operation)?;
            json!({"type":"action_result","operation":operation,"status":status})
        }
        Request::CancelOperation { operation } => {
            crate::routine::cancel(operation);
            let status = crate::routine::status(db, operation)?;
            json!({"type":"action_result","operation":operation,"status":status})
        }
        Request::ObsStatus => crate::obs::status(db)
            .unwrap_or_else(|_| json!({"type":"error","code":"obs_unavailable"})),
        Request::UpdateStatus => {
            let state = db
                .path()
                .and_then(|path| std::path::Path::new(path).parent())
                .and_then(crate::updater::read_status);
            json!({"type":"update_status","state":state})
        }
        Request::Metrics => json!({"type":"metrics","measurements":crate::metrics::read()}),
        Request::Mixer => json!({"type":"mixer","levels":crate::audio::mixer()?}),
        Request::SetVolume {
            operation: id,
            target,
            percent,
        } if percent <= 100 => {
            operation(db, id, |_| crate::audio::set(&target, Some(percent), None))?
        }
        Request::SetMute {
            operation: id,
            target,
            muted,
        } => operation(db, id, |_| crate::audio::set(&target, None, Some(muted)))?,
        _ => json!({"type":"error","code":"invalid_request"}),
    })
}
