use anyhow::{Context, Result, ensure};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use windows::{
    Media::{
        Control::{
            GlobalSystemMediaTransportControlsSession as Session,
            GlobalSystemMediaTransportControlsSessionManager as Manager,
            GlobalSystemMediaTransportControlsSessionPlaybackStatus as Status,
        },
        MediaPlaybackType,
    },
    Win32::System::WinRT::{RO_INIT_MULTITHREADED, RoInitialize, RoUninitialize},
};

#[derive(Clone, Copy, Debug, Deserialize)]
#[serde(rename_all = "snake_case")]
pub(crate) enum Command {
    Play,
    Pause,
    Next,
    Previous,
}

#[derive(Serialize)]
pub(crate) struct NowPlaying {
    id: String,
    title: String,
    artist: String,
    source: String,
    source_key: String,
    video: bool,
    active: bool,
    playing: bool,
    play: bool,
    pause: bool,
    next: bool,
    previous: bool,
    volume: Option<crate::audio::Level>,
}

struct Apartment;
impl Drop for Apartment {
    fn drop(&mut self) {
        // SAFETY: This guard balances successful RoInitialize on this thread.
        unsafe { RoUninitialize() };
    }
}

pub(crate) fn snapshot() -> Result<Vec<NowPlaying>> {
    let (_apartment, manager) = open()?;
    let preferred = manager.GetCurrentSession().ok();
    let mut sessions = Vec::new();
    let levels = crate::audio::media_levels().unwrap_or_default();
    for session in manager.GetSessions()?.into_iter().take(16) {
        if let Ok(mut item) = details(&session) {
            item.active = preferred.as_ref() == Some(&session);
            let raw_source = session.SourceAppUserModelId()?.to_string();
            item.volume = crate::audio::media_level(&levels, &raw_source).cloned();
            sessions.push(item);
        }
    }
    sessions.sort_by_key(|item| (!item.playing, !item.active));
    Ok(sessions)
}

pub(crate) fn control(id: &str, command: Command) -> Result<()> {
    ensure!(crate::art::valid_id(id), "geçersiz medya oturumu");
    let (_apartment, manager) = open()?;
    let session = manager
        .GetSessions()?
        .into_iter()
        .take(16)
        .find(|session| identity(session).is_ok_and(|(current, _, _)| current == id))
        .context("medya oturumu değişti")?;
    let result = match command {
        Command::Play => session.TryPlayAsync()?,
        Command::Pause => session.TryPauseAsync()?,
        Command::Next => session.TrySkipNextAsync()?,
        Command::Previous => session.TrySkipPreviousAsync()?,
    };
    ensure!(result.join()?, "medya oynatıcı bu komutu kabul etmedi");
    Ok(())
}

fn open() -> Result<(Apartment, Manager)> {
    // SAFETY: WinRT initialization and the associated guard stay on this thread.
    unsafe { RoInitialize(RO_INIT_MULTITHREADED) }?;
    let apartment = Apartment;
    Ok((apartment, Manager::RequestAsync()?.join()?))
}

fn identity(session: &Session) -> Result<(String, String, String)> {
    let source = session.SourceAppUserModelId()?.to_string();
    let properties = session.TryGetMediaPropertiesAsync()?.join()?;
    let title = format_field(&properties.Title()?.to_string());
    let artist = properties
        .Artist()
        .map(|value| format_field(&value.to_string()))
        .unwrap_or_default();
    let id = format!(
        "{:x}",
        Sha256::digest(format!("{source}\0{title}\0{artist}").as_bytes())
    );
    Ok((id, title, artist))
}

fn details(session: &Session) -> Result<NowPlaying> {
    let (id, title, artist) = identity(session)?;
    ensure!(!title.is_empty(), "medya başlığı bildirilmedi");
    let raw_source = session.SourceAppUserModelId()?.to_string();
    let info = session.GetPlaybackInfo()?;
    let controls = info.Controls()?;
    let playback = info
        .PlaybackType()
        .ok()
        .and_then(|value| value.Value().ok());
    let (source, browser) = service_name(&raw_source);
    let video = playback == Some(MediaPlaybackType::Video)
        || (browser && playback != Some(MediaPlaybackType::Music))
        || title.to_ascii_lowercase().contains("youtube");
    Ok(NowPlaying {
        id,
        title,
        artist,
        source,
        source_key: format!("{:x}", Sha256::digest(raw_source.as_bytes())),
        video,
        active: false,
        playing: info.PlaybackStatus()? == Status::Playing,
        play: controls.IsPlayEnabled().unwrap_or(false),
        pause: controls.IsPauseEnabled().unwrap_or(false),
        next: controls.IsNextEnabled().unwrap_or(false),
        previous: controls.IsPreviousEnabled().unwrap_or(false),
        volume: None,
    })
}

fn format_field(value: &str) -> String {
    value
        .chars()
        .filter(|character| !character.is_control())
        .take(160)
        .collect()
}

fn service_name(source: &str) -> (String, bool) {
    let lower = source.to_ascii_lowercase();
    for (keyword, title, browser) in [
        ("spotify", "Spotify", false),
        ("firefox", "Firefox", true),
        ("opera", "Opera", true),
        ("brave", "Brave", true),
        ("vivaldi", "Vivaldi", true),
        ("msedge", "Microsoft Edge", true),
        ("microsoftedge", "Microsoft Edge", true),
        ("chrome", "Google Chrome", true),
        ("chromium", "Chromium", true),
        ("youtube", "YouTube", true),
        ("mediaplayer", "Windows Media Player", false),
        ("zunemusic", "Windows Media Player", false),
        ("vlc", "VLC", false),
    ] {
        if lower.contains(keyword) {
            return (title.into(), browser);
        }
    }
    let label = source.rsplit(['\\', '/', '!']).next().unwrap_or(source);
    (
        format_field(label.strip_suffix(".exe").unwrap_or(label)),
        false,
    )
}
