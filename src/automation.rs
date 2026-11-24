use std::{
    collections::{HashMap, HashSet},
    path::PathBuf,
    time::{Duration, Instant},
};

use rusqlite::Connection;
use serde::{Deserialize, Serialize};
use uuid::Uuid;
use windows::Win32::System::SystemInformation::GetLocalTime;

use crate::{routine, store};

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case", deny_unknown_fields)]
pub(crate) enum Trigger {
    HostStart,
    Daily { hour: u8, minute: u8 },
    AppStarted { executable: String },
    AppStopped { executable: String },
    ObsRecordingStarted,
    ObsRecordingStopped,
    DiskUsage { percent: u8 },
}

impl Trigger {
    pub(crate) fn valid(&self) -> bool {
        match self {
            Self::Daily { hour, minute } => *hour < 24 && *minute < 60,
            Self::DiskUsage { percent } => (10..=100).contains(percent),
            Self::AppStarted { executable } | Self::AppStopped { executable } => {
                executable.len() <= 64
                    && executable.to_ascii_lowercase().ends_with(".exe")
                    && executable
                        .chars()
                        .all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | ' ' | '-' | '_'))
            }
            _ => true,
        }
    }
}

struct Entry {
    trigger: Trigger,
    previous: Option<bool>,
    last_run: Option<Instant>,
}

pub(crate) fn launch(path: PathBuf) {
    let _ = std::thread::Builder::new()
        .name("deck-automation".into())
        .spawn(move || {
            let Ok(mut db) = store::open(&path) else {
                return;
            };
            let mut entries: HashMap<Uuid, Entry> = HashMap::new();
            let mut first = true;
            loop {
                if let Ok(deck) = store::deck(&db) {
                    let rows: Vec<_> = deck
                        .buttons
                        .into_iter()
                        .filter_map(|button| {
                            button
                                .routine
                                .filter(|value| value.trigger.is_some())
                                .map(|value| (button.id, value))
                        })
                        .collect();
                    entries.retain(|id, _| rows.iter().any(|(button, _)| button == id));
                    let processes = rows
                        .iter()
                        .any(|(_, item)| {
                            matches!(
                                item.trigger,
                                Some(Trigger::AppStarted { .. } | Trigger::AppStopped { .. })
                            )
                        })
                        .then(crate::windows_host::running_programs)
                        .flatten();
                    let obs_recording = rows
                        .iter()
                        .any(|(_, item)| {
                            matches!(
                                item.trigger,
                                Some(Trigger::ObsRecordingStarted | Trigger::ObsRecordingStopped)
                            )
                        })
                        .then(|| crate::obs::recording(&db).ok())
                        .flatten();
                    let disk = rows
                        .iter()
                        .any(|(_, item)| matches!(item.trigger, Some(Trigger::DiskUsage { .. })))
                        .then(crate::metrics::disk_percent)
                        .flatten();
                    // SAFETY: GetLocalTime writes and returns its own SYSTEMTIME.
                    let time = unsafe { GetLocalTime() };
                    let today = u32::from(time.wYear) * 10_000
                        + u32::from(time.wMonth) * 100
                        + u32::from(time.wDay);
                    for (id, definition) in rows {
                        let Some(trigger) = definition.trigger.clone() else {
                            continue;
                        };
                        let entry = entries.entry(id).or_insert_with(|| Entry {
                            trigger: trigger.clone(),
                            previous: None,
                            last_run: None,
                        });
                        if entry.trigger != trigger {
                            *entry = Entry {
                                trigger: trigger.clone(),
                                previous: None,
                                last_run: None,
                            };
                        }
                        let daily_key = format!("automation_daily:{id}");
                        let fire = match &trigger {
                            Trigger::HostStart => first,
                            Trigger::Daily { hour, minute } => {
                                u16::from(*hour) == time.wHour
                                    && u16::from(*minute) == time.wMinute
                                    && store::get(&db, &daily_key).ok().flatten().as_deref()
                                        != Some(&today.to_le_bytes())
                            }
                            Trigger::AppStarted { executable }
                            | Trigger::AppStopped { executable } => {
                                let expected = matches!(trigger, Trigger::AppStarted { .. });
                                let current = processes.as_ref().map(|items: &HashSet<String>| {
                                    items.contains(&executable.to_ascii_lowercase()) == expected
                                });
                                rising(entry, current)
                            }
                            Trigger::ObsRecordingStarted => rising(entry, obs_recording),
                            Trigger::ObsRecordingStopped => {
                                rising(entry, obs_recording.map(|value| !value))
                            }
                            Trigger::DiskUsage { percent } => {
                                let state = disk.map(|value| {
                                    if entry.previous == Some(true) {
                                        value >= percent.saturating_sub(5)
                                    } else {
                                        value >= *percent
                                    }
                                });
                                rising(entry, state)
                            }
                        };
                        if fire
                            && entry
                                .last_run
                                .is_none_or(|run| run.elapsed() >= Duration::from_secs(30))
                        {
                            if matches!(trigger, Trigger::Daily { .. })
                                && store::put(&db, &daily_key, &today.to_le_bytes()).is_err()
                            {
                                continue;
                            }
                            entry.last_run = Some(Instant::now());
                            if matches!(trigger, Trigger::DiskUsage { .. }) {
                                let _ = store::put(
                                    &db,
                                    "automation_alert",
                                    format!("Sistem diski %{} dolu.", disk.unwrap_or(0)).as_bytes(),
                                );
                            } else {
                                let _ = routine::start(&mut db, Uuid::new_v4(), definition);
                            }
                        }
                    }
                }
                first = false;
                std::thread::sleep(Duration::from_secs(2));
            }
        });
}

fn rising(entry: &mut Entry, state: Option<bool>) -> bool {
    let fire = entry.previous == Some(false) && state == Some(true);
    entry.previous = state;
    fire
}

pub(crate) fn alert(db: &Connection) -> Option<String> {
    String::from_utf8(store::get(db, "automation_alert").ok()??).ok()
}
