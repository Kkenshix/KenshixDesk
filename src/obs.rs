use std::{
    net::{Ipv4Addr, SocketAddr, TcpStream},
    time::Duration,
};

use anyhow::{Context, Result, ensure};
use base64::{Engine, engine::general_purpose::STANDARD};
use rusqlite::Connection;
use serde_json::{Value, json};
use sha2::{Digest, Sha256};
use tungstenite::{Message, WebSocket, protocol::WebSocketConfig};
use uuid::Uuid;

use crate::{protocol::Action, store, windows_host};

pub(crate) fn configure(db: &Connection, port: u16) -> Result<()> {
    ensure!(port != 0, "OBS bağlantı noktası geçersiz");
    let password = rpassword::prompt_password("OBS WebSocket parolası: ")?;
    let encrypted = windows_host::protect(&serde_json::to_vec(&(port, password))?, true)?;
    store::put(db, "obs", &encrypted)
}

fn authentication(password: &str, salt: &str, challenge: &str) -> String {
    let secret = STANDARD.encode(Sha256::digest([password, salt].concat().as_bytes()));
    STANDARD.encode(Sha256::digest([&secret, challenge].concat().as_bytes()))
}

struct Obs(WebSocket<TcpStream>);

impl Obs {
    fn connect(db: &Connection) -> Result<Self> {
        let stored = store::get(db, "obs")?.context("OBS bağlantısı yapılandırılmadı")?;
        let (port, password): (u16, String) =
            serde_json::from_slice(&windows_host::protect(&stored, false)?)?;
        let address = SocketAddr::from((Ipv4Addr::LOCALHOST, port));
        let timeout = Duration::from_millis(500);
        let stream =
            TcpStream::connect_timeout(&address, timeout).context("OBS WebSocket erişilemiyor")?;
        stream.set_read_timeout(Some(timeout))?;
        stream.set_write_timeout(Some(timeout))?;
        let websocket = WebSocketConfig::default()
            .max_message_size(Some(512 * 1024))
            .max_frame_size(Some(512 * 1024));
        let (socket, _) = tungstenite::client::client_with_config(
            format!("ws://{address}"),
            stream,
            Some(websocket),
        )
        .map_err(|_| anyhow::anyhow!("OBS WebSocket oturumu açılamadı"))?;
        let mut obs = Self(socket);
        let hello = obs.receive(0)?;
        ensure!(
            hello["rpcVersion"]
                .as_u64()
                .is_some_and(|version| version >= 1),
            "OBS RPC sürümü uyumsuz"
        );
        let mut identify = json!({"rpcVersion":1,"eventSubscriptions":0});
        if let Some(auth) = hello.get("authentication") {
            let salt = auth["salt"].as_str().context("OBS salt vermedi")?;
            let challenge = auth["challenge"]
                .as_str()
                .context("OBS challenge vermedi")?;
            identify["authentication"] = json!(authentication(&password, salt, challenge));
        }
        obs.send(1, identify)?;
        obs.receive(2)?;
        Ok(obs)
    }

    fn receive(&mut self, expected: u8) -> Result<Value> {
        let message = self.0.read()?;
        let parsed: Value = serde_json::from_str(message.to_text()?)?;
        ensure!(parsed["op"] == expected, "OBS beklenmeyen yanıt verdi");
        Ok(parsed["d"].clone())
    }

    fn send(&mut self, opcode: u8, data: Value) -> Result<()> {
        self.0.send(Message::Text(
            json!({"op":opcode,"d":data}).to_string().into(),
        ))?;
        Ok(())
    }

    fn request(&mut self, command: &str) -> Result<Value> {
        let request_id = Uuid::new_v4().to_string();
        self.send(6, json!({"requestType":command,"requestId":request_id}))?;
        let response = self.receive(7)?;
        ensure!(
            response["requestId"] == request_id && response["requestType"] == command,
            "OBS işlem kimliği eşleşmedi"
        );
        ensure!(
            response["requestStatus"]["result"] == true,
            "OBS isteği reddetti"
        );
        Ok(response["responseData"].clone())
    }
}

pub(crate) fn run(db: &Connection, action: Action) -> Result<()> {
    let command = match action {
        Action::ObsStartRecord => "StartRecord",
        Action::ObsStopRecord => "StopRecord",
        Action::ObsToggleRecord => "ToggleRecord",
        Action::ObsPauseRecord => "PauseRecord",
        Action::ObsResumeRecord => "ResumeRecord",
        Action::ObsStartStream => "StartStream",
        Action::ObsStopStream => "StopStream",
        Action::ObsStartReplay => "StartReplayBuffer",
        Action::ObsStopReplay => "StopReplayBuffer",
        Action::ObsSaveReplay => "SaveReplayBuffer",
        _ => anyhow::bail!("geçersiz OBS eylemi"),
    };
    Obs::connect(db)?.request(command)?;
    Ok(())
}

pub(crate) fn recording(db: &Connection) -> Result<bool> {
    Obs::connect(db)?.request("GetRecordStatus")?["outputActive"]
        .as_bool()
        .context("OBS kayıt durumu yok")
}

pub(crate) fn status(db: &Connection) -> Result<Value> {
    let mut obs = Obs::connect(db)?;
    let recording = obs.request("GetRecordStatus")?;
    let streaming = obs.request("GetStreamStatus")?;
    let replay = obs.request("GetReplayBufferStatus")?;
    let scene = obs.request("GetCurrentProgramScene")?;
    let stats = obs.request("GetStats")?;
    Ok(json!({
        "type":"obs_status",
        "recording":recording["outputActive"],
        "paused":recording["outputPaused"],
        "duration_ms":recording["outputDuration"],
        "streaming":streaming["outputActive"],
        "replay":replay["outputActive"],
        "scene":scene["sceneName"],
        "render_fps":stats["activeFps"]
    }))
}
