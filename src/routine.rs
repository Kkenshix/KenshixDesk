use std::{
    path::PathBuf,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
    time::{Duration, Instant},
};

use anyhow::{Result, ensure};
use rusqlite::Connection;
use serde::{Deserialize, Serialize};
use uuid::Uuid;

use crate::{protocol::Action, store, windows_host};

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct Routine {
    pub steps: Vec<Step>,
    pub continue_on_error: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub trigger: Option<crate::automation::Trigger>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(tag = "type", rename_all = "snake_case", deny_unknown_fields)]
pub(crate) enum Step {
    Keys { keys: Vec<String>, hold_ms: u16 },
    Text { text: String },
    Launch { executable: String },
    Click { x: i32, y: i32 },
    Wait { ms: u16 },
    Action { action: Action },
    WaitObs { ms: u16 },
    IfObs { skip: u8 },
}

impl Routine {
    pub(crate) fn valid(&self) -> bool {
        self.trigger
            .as_ref()
            .is_none_or(crate::automation::Trigger::valid)
            && (1..=24).contains(&self.steps.len())
            && self
                .steps
                .iter()
                .enumerate()
                .all(|(index, step)| match step {
                    Step::Keys { keys, hold_ms } => {
                        (1..=5).contains(&keys.len())
                            && *hold_ms <= 5_000
                            && keys.iter().enumerate().all(|(position, key)| {
                                windows_host::key_code(key).is_some_and(|code| {
                                    keys[..position].iter().all(|previous| {
                                        windows_host::key_code(previous) != Some(code)
                                    })
                                })
                            })
                    }
                    Step::Text { text } => {
                        (1..=512).contains(&text.encode_utf16().count())
                            && !text.chars().any(char::is_control)
                    }
                    Step::Click { x, y } => {
                        (-65_536..=65_536).contains(x) && (-65_536..=65_536).contains(y)
                    }
                    Step::Launch { executable } => {
                        let path = std::path::Path::new(executable);
                        executable.len() <= 400
                            && path.is_absolute()
                            && !executable.starts_with(r"\\")
                            && !executable.chars().any(char::is_control)
                            && path.extension().is_some_and(|extension| {
                                extension.eq_ignore_ascii_case("exe")
                                    || extension.eq_ignore_ascii_case("lnk")
                            })
                    }
                    Step::Wait { ms } | Step::WaitObs { ms } => (1..=15_000).contains(ms),
                    Step::Action { action } => !matches!(
                        action,
                        Action::Macro | Action::Mixer | Action::ObsStatus | Action::Spotify
                    ),
                    Step::IfObs { skip } => {
                        *skip > 0 && index + usize::from(*skip) < self.steps.len()
                    }
                })
    }

    fn run(&self, db: &Connection, id: Uuid, canceled: &AtomicBool) -> &'static str {
        let deadline = Instant::now() + Duration::from_secs(60);
        let active = || !canceled.load(Ordering::Relaxed) && Instant::now() < deadline;
        let mut failed = false;
        let mut skip_count = 0;
        for (index, step) in self.steps.iter().enumerate() {
            if !active() {
                break;
            }
            if skip_count > 0 {
                skip_count -= 1;
                let _ = store::macro_step(db, id, index + 1, "skipped", 0);
                continue;
            }
            let starting = Instant::now();
            let outcome = match step {
                Step::Keys { keys, hold_ms } => windows_host::keys(keys, *hold_ms, active),
                Step::Text { text } => windows_host::type_text(text, active),
                Step::Launch { executable } => windows_host::launch_local(executable),
                Step::Click { x, y } => windows_host::click(*x, *y),
                Step::Wait { ms } => wait(
                    Duration::from_millis((*ms).into()),
                    &active,
                    || false,
                    false,
                ),
                Step::WaitObs { ms } => wait(
                    Duration::from_millis((*ms).into()),
                    &active,
                    || crate::obs::status(db).is_ok(),
                    true,
                ),
                Step::Action { action } if action.is_windows() => windows_host::run(*action),
                Step::Action { action } => crate::obs::run(db, *action),
                Step::IfObs { skip } => {
                    if crate::obs::status(db).is_err() {
                        skip_count = *skip;
                    }
                    Ok(())
                }
            };
            let step_result = if outcome.is_ok() { "ok" } else { "failed" };
            let _ = store::macro_step(
                db,
                id,
                index + 1,
                step_result,
                starting.elapsed().as_millis() as u64,
            );
            if outcome.is_err() {
                failed = true;
                if !self.continue_on_error {
                    break;
                }
            }
        }
        if canceled.load(Ordering::Relaxed) {
            "canceled"
        } else if Instant::now() >= deadline {
            "timeout"
        } else if failed {
            "failed"
        } else {
            "ok"
        }
    }
}

fn wait(
    duration: Duration,
    active: &impl Fn() -> bool,
    ready: impl Fn() -> bool,
    required: bool,
) -> Result<()> {
    let end = Instant::now() + duration;
    while active() && Instant::now() < end {
        if ready() {
            return Ok(());
        }
        std::thread::sleep(
            end.saturating_duration_since(Instant::now())
                .min(Duration::from_millis(100)),
        );
    }
    ensure!(active() && !required, "bekleme tamamlanmadı");
    Ok(())
}

struct Running {
    id: Uuid,
    canceled: Arc<AtomicBool>,
}
static EXITING: AtomicBool = AtomicBool::new(false);
static RUNNING: Mutex<Option<Running>> = Mutex::new(None);

pub(crate) fn busy() -> bool {
    RUNNING.lock().expect("macro lock").is_some()
}

pub(crate) fn prepare_exit() -> bool {
    let running = RUNNING.lock().expect("macro lock");
    if running.is_some() {
        return false;
    }
    EXITING.store(true, Ordering::Relaxed);
    true
}

pub(crate) fn resume_after_update_error() {
    EXITING.store(false, Ordering::Relaxed);
}

pub(crate) fn status(db: &Connection, id: Uuid) -> Result<String> {
    if RUNNING
        .lock()
        .expect("macro lock")
        .as_ref()
        .is_some_and(|running| running.id == id)
    {
        return Ok("running".into());
    }
    Ok(store::operation_status(db, id)?.unwrap_or_else(|| "unknown".into()))
}

pub(crate) fn cancel(id: Uuid) {
    if let Some(running) = RUNNING
        .lock()
        .expect("macro lock")
        .as_ref()
        .filter(|running| running.id == id)
    {
        running.canceled.store(true, Ordering::Relaxed);
    }
}

pub(crate) fn start(db: &mut Connection, id: Uuid, routine: Routine) -> Result<String> {
    ensure!(routine.valid(), "makro adımları geçersiz");
    let mut running = RUNNING.lock().expect("macro lock");
    if EXITING.load(Ordering::Relaxed) {
        return Ok("busy".into());
    }
    if let Some(current) = running.as_ref() {
        return Ok(if current.id == id { "running" } else { "busy" }.into());
    }
    let path = PathBuf::from(
        db.path()
            .filter(|path| !path.is_empty())
            .ok_or_else(|| anyhow::anyhow!("veritabanı yolu yok"))?,
    );
    let worker_db = store::open(&path)?;
    if let Some(result) = store::claim(db, id)? {
        return Ok(result);
    }
    let canceled = Arc::new(AtomicBool::new(false));
    *running = Some(Running {
        id,
        canceled: Arc::clone(&canceled),
    });
    if std::thread::Builder::new()
        .name("deck-macro".into())
        .spawn(move || {
            let _ = worker_db.execute(
                "DELETE FROM macro_steps WHERE created < unixepoch() - 86400",
                [],
            );
            let result = routine.run(&worker_db, id, &canceled);
            let _ = store::finish(&worker_db, id, result);
            *RUNNING.lock().expect("macro lock") = None;
        })
        .is_err()
    {
        *running = None;
        store::finish(db, id, "failed")?;
        return Ok("failed".into());
    }
    Ok("running".into())
}
