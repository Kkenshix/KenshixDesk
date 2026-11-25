use anyhow::{Result, ensure};
use rusqlite::{Connection, OptionalExtension, params};
use uuid::Uuid;

use crate::protocol::Deck;

pub(crate) fn open(path: &std::path::Path) -> Result<Connection> {
    let db = Connection::open(path)?;
    db.busy_timeout(std::time::Duration::from_secs(2))?;
    db.execute_batch(
        "PRAGMA journal_mode=WAL;
         CREATE TABLE IF NOT EXISTS macro_steps (
             operation TEXT NOT NULL, step INTEGER NOT NULL,
             state TEXT NOT NULL, elapsed_ms INTEGER NOT NULL, created INTEGER NOT NULL DEFAULT (unixepoch()),
             PRIMARY KEY (operation, step)
         );
         CREATE TABLE IF NOT EXISTS settings (name TEXT PRIMARY KEY, value BLOB NOT NULL);
         CREATE TABLE IF NOT EXISTS button_art (id TEXT PRIMARY KEY, data BLOB NOT NULL);
         CREATE TABLE IF NOT EXISTS operations (
             id TEXT PRIMARY KEY, created INTEGER NOT NULL DEFAULT (unixepoch()),
             result TEXT NOT NULL
         );",
    )?;
    Ok(db)
}

pub(crate) fn get(db: &Connection, name: &str) -> Result<Option<Vec<u8>>> {
    Ok(db
        .query_row("SELECT value FROM settings WHERE name = ?", [name], |row| {
            row.get(0)
        })
        .optional()?)
}

pub(crate) fn put(db: &Connection, name: &str, value: &[u8]) -> Result<()> {
    db.execute(
        "INSERT OR REPLACE INTO settings (name, value) VALUES (?, ?)",
        params![name, value],
    )?;
    Ok(())
}

pub(crate) fn deck(db: &Connection) -> Result<Deck> {
    match get(db, "deck")? {
        Some(bytes) => Ok(serde_json::from_slice(&bytes)?),
        None => Ok(Deck::default()),
    }
}

pub(crate) fn save(db: &mut Connection, mut next: Deck) -> Result<Option<Deck>> {
    let transaction = db.transaction()?;
    let old = deck(&transaction)?;
    if old.revision != next.revision {
        return Ok(None);
    }
    if next.layout.is_none()
        && let Some(mut layout) = old.layout
    {
        let ids: std::collections::HashSet<_> =
            next.buttons.iter().map(|button| button.id).collect();
        layout.placements.retain(|id, _| ids.contains(id));
        next.layout = Some(layout);
    }
    if next.buttons.iter().any(|button| button.behavior.is_some()) {
        let ids: std::collections::HashSet<_> =
            next.buttons.iter().map(|button| button.id).collect();
        let sources: std::collections::HashSet<_> = next
            .buttons
            .iter()
            .filter(|button| {
                button.routine.is_some() && button.action == crate::protocol::Action::Macro
            })
            .map(|button| button.id)
            .collect();
        for button in &mut next.buttons {
            let Some(behavior) = &mut button.behavior else {
                continue;
            };
            behavior.double_tap = behavior.double_tap.filter(|id| ids.contains(id));
            behavior.long_press = behavior.long_press.filter(|id| ids.contains(id));
            if let Some(shared) = behavior.shared_macro.filter(|id| !sources.contains(id))
                && let Some(routine) = old
                    .buttons
                    .iter()
                    .find(|source| source.id == shared)
                    .and_then(|source| source.routine.clone())
            {
                behavior.shared_macro = None;
                button.routine = Some(routine);
            }
        }
    }
    ensure!(next.validate(), "geçersiz panel bilgisi");
    for id in next
        .buttons
        .iter()
        .filter_map(|button| button.art.as_deref())
    {
        ensure!(
            transaction
                .query_row("SELECT 1 FROM button_art WHERE id = ?", [id], |_| Ok(()))
                .optional()?
                .is_some(),
            "buton görseli bulunamadı"
        );
    }
    next.revision = old
        .revision
        .checked_add(1)
        .ok_or_else(|| anyhow::anyhow!("sürüm sınırı"))?;
    let serialized = serde_json::to_vec(&next)?;
    ensure!(
        serialized.len() <= crate::protocol::MAX_MESSAGE - 256,
        "panel bağlantı sınırını aşıyor"
    );
    put(&transaction, "deck", &serialized)?;
    transaction.commit()?;
    Ok(Some(next))
}

pub(crate) fn claim(db: &mut Connection, id: Uuid) -> Result<Option<String>> {
    ensure!(!id.is_nil(), "geçersiz işlem kimliği");
    let transaction = db.transaction()?;
    transaction.execute(
        "DELETE FROM operations WHERE created < unixepoch() - 86400",
        [],
    )?;
    let id = id.to_string();
    let result = transaction
        .query_row("SELECT result FROM operations WHERE id = ?", [&id], |row| {
            row.get(0)
        })
        .optional()?;
    if result.is_none() {
        transaction.execute(
            "INSERT INTO operations (id, result) VALUES (?, 'unknown')",
            [&id],
        )?;
    }
    transaction.commit()?;
    Ok(result)
}

pub(crate) fn macro_step(
    db: &Connection,
    id: Uuid,
    step: usize,
    state: &str,
    elapsed_ms: u64,
) -> Result<()> {
    db.execute(
        "INSERT OR REPLACE INTO macro_steps(operation,step,state,elapsed_ms) VALUES (?,?,?,?)",
        params![id.to_string(), step, state, elapsed_ms],
    )?;
    Ok(())
}

pub(crate) fn macro_history(db: &Connection, id: Uuid) -> Result<Vec<(usize, String, u64)>> {
    let mut query = db
        .prepare("SELECT step,state,elapsed_ms FROM macro_steps WHERE operation=? ORDER BY step")?;
    Ok(query
        .query_map([id.to_string()], |row| {
            Ok((row.get(0)?, row.get(1)?, row.get(2)?))
        })?
        .collect::<rusqlite::Result<_>>()?)
}

pub(crate) fn operation_status(db: &Connection, id: Uuid) -> Result<Option<String>> {
    Ok(db
        .query_row(
            "SELECT result FROM operations WHERE id = ?",
            [id.to_string()],
            |row| row.get(0),
        )
        .optional()?)
}

pub(crate) fn finish(db: &Connection, id: Uuid, result: &str) -> Result<()> {
    db.execute(
        "UPDATE operations SET result = ? WHERE id = ?",
        params![result, id.to_string()],
    )?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stale_revisions_cannot_overwrite_a_saved_deck() -> Result<()> {
        let mut db = open(std::path::Path::new(":memory:"))?;
        assert_eq!(
            save(&mut db, Deck::default())?.map(|deck| deck.revision),
            Some(1)
        );
        assert!(save(&mut db, Deck::default())?.is_none());
        assert_eq!(deck(&db)?.revision, 1);
        Ok(())
    }

    #[test]
    fn legacy_save_keeps_panel_metadata_and_prunes_removed_buttons() -> Result<()> {
        let mut db = open(std::path::Path::new(":memory:"))?;
        let id = Uuid::new_v4();
        let profile = Uuid::new_v4();
        let original: Deck = serde_json::from_value(serde_json::json!({
            "revision": 0, "buttons": [{"id":id,"label":"Ses","action":"mixer"}],
            "layout":{"panels":[{"id":profile,"name":"Ev","parent":null,"kind":"profile"}],
                "placements":{id.to_string():{"panel":profile,"span":2,"color":"#123456"}}}
        }))?;
        save(&mut db, original)?;
        let legacy = Deck {
            revision: 1,
            ..Deck::default()
        };
        let saved = save(&mut db, legacy)?.expect("revisions agree");
        let layout = saved.layout.expect("layout retained");
        assert_eq!(layout.panels.len(), 1);
        assert!(layout.placements.is_empty());
        Ok(())
    }

    #[test]
    fn claimed_operations_survive_a_lost_result() -> Result<()> {
        let mut db = open(std::path::Path::new(":memory:"))?;
        let id = Uuid::new_v4();
        assert!(claim(&mut db, id)?.is_none());
        assert_eq!(claim(&mut db, id)?.as_deref(), Some("unknown"));
        finish(&db, id, "ok")?;
        assert_eq!(claim(&mut db, id)?.as_deref(), Some("ok"));
        Ok(())
    }
}
