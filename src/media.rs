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
        MediaPlaybackAutoRepeatMode, MediaPlaybackType,
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
    #[serde(skip_serializing_if = "Option::is_none")]
    spotify: Option<SpotifyPlayback>,
}

#[derive(Serialize)]
struct SpotifyPlayback {
    shuffle: Option<bool>,
    repeat: Option<i32>,
    position_ms: Option<i64>,
    end_ms: Option<i64>,
}

#[derive(Clone, Copy, Debug, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case", deny_unknown_fields)]
pub(crate) enum SpotifySetting {
    Shuffle { enabled: bool },
    Repeat { mode: u8 },
    Seek { position_ms: i64 },
}

#[derive(Serialize)]
pub(crate) struct MediaSnapshot {
    #[serde(rename = "type")]
    kind: &'static str,
    sessions: Vec<NowPlaying>,
    spotify_volume: Option<crate::audio::Level>,
}

pub(crate) fn is_spotify(source: &str) -> bool {
    let source = source.to_ascii_lowercase();
    let name = source.rsplit(['\\', '/']).next().unwrap_or(&source);
    matches!(name, "spotify" | "spotify.exe") || name.starts_with("spotifyab.spotifymusic_")
}

struct Apartment;
impl Drop for Apartment {
    fn drop(&mut self) {
        // SAFETY: This guard balances successful RoInitialize on this thread.
        unsafe { RoUninitialize() };
    }
}

pub(crate) fn snapshot() -> Result<MediaSnapshot> {
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
    Ok(MediaSnapshot {
        kind: "media",
        sessions,
        spotify_volume: levels
            .into_iter()
            .find(|level| level.id == crate::audio::SPOTIFY),
    })
}

pub(crate) fn control(id: &str, command: Command) -> Result<()> {
    ensure!(crate::art::valid_id(id), "geçersiz medya oturumu");
    let (_apartment, manager) = open()?;
    let session = find_session(&manager, id)?;
    let result = match command {
        Command::Play => session.TryPlayAsync()?,
        Command::Pause => session.TryPauseAsync()?,
        Command::Next => session.TrySkipNextAsync()?,
        Command::Previous => session.TrySkipPreviousAsync()?,
    };
    ensure!(result.join()?, "medya oynatıcı bu komutu kabul etmedi");
    Ok(())
}

fn find_session(manager: &Manager, id: &str) -> Result<Session> {
    manager
        .GetSessions()?
        .into_iter()
        .take(16)
        .find(|session| identity(session).is_ok_and(|(current, _, _)| current == id))
        .context("medya oturumu değişti")
}

pub(crate) fn set_spotify(id: &str, setting: SpotifySetting) -> Result<()> {
    ensure!(crate::art::valid_id(id), "geçersiz Spotify oturumu");
    let (_apartment, manager) = open()?;
    let session = find_session(&manager, id)?;
    ensure!(
        is_spotify(&session.SourceAppUserModelId()?.to_string()),
        "Spotify oturumu bekleniyor"
    );
    let controls = session.GetPlaybackInfo()?.Controls()?;
    let result = match setting {
        SpotifySetting::Shuffle { enabled } if controls.IsShuffleEnabled()? => {
            session.TryChangeShuffleActiveAsync(enabled)?
        }
        SpotifySetting::Repeat { mode } if mode <= 2 && controls.IsRepeatEnabled()? => {
            session.TryChangeAutoRepeatModeAsync(MediaPlaybackAutoRepeatMode(i32::from(mode)))?
        }
        SpotifySetting::Seek { position_ms } if controls.IsPlaybackPositionEnabled()? => {
            let timeline = session.GetTimelineProperties()?;
            let position = position_ms
                .checked_mul(10_000)
                .context("geçersiz parça konumu")?;
            ensure!(
                (timeline.StartTime()?.Duration..=timeline.EndTime()?.Duration).contains(&position),
                "geçersiz parça konumu"
            );
            session.TryChangePlaybackPositionAsync(position)?
        }
        _ => anyhow::bail!("Spotify bu ayarı Windows'a açmamış"),
    };
    ensure!(result.join()?, "Spotify ayarı uygulamadı");
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
    let spotify = is_spotify(&raw_source).then(|| {
        let timeline = controls
            .IsPlaybackPositionEnabled()
            .unwrap_or(false)
            .then(|| session.GetTimelineProperties().ok())
            .flatten();
        SpotifyPlayback {
            shuffle: controls
                .IsShuffleEnabled()
                .unwrap_or(false)
                .then(|| info.IsShuffleActive().and_then(|value| value.Value()).ok())
                .flatten(),
            repeat: controls
                .IsRepeatEnabled()
                .unwrap_or(false)
                .then(|| {
                    info.AutoRepeatMode()
                        .and_then(|value| value.Value())
                        .ok()
                        .map(|mode| mode.0)
                })
                .flatten(),
            position_ms: timeline
                .as_ref()
                .and_then(|value| value.Position().ok())
                .map(|value| value.Duration / 10_000),
            end_ms: timeline
                .and_then(|value| value.EndTime().ok())
                .map(|value| value.Duration / 10_000),
        }
    });
    let (source, browser) = service_name(&raw_source);
    let video = playback == Some(MediaPlaybackType::Video)
        || browser
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
        spotify,
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
    if is_spotify(source) {
        return ("Spotify".into(), false);
    }
    let lower = source.to_ascii_lowercase();
    for (keyword, title, browser) in [
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
