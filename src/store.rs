use anyhow::{Result, ensure};
use rusqlite::{Connection, OptionalExtension, params};
use uuid::Uuid;

use crate::protocol::Deck;

pub(crate) fn open(path: &std::path::Path) -> Result<Connection> {
    let db = Connection::open(path)?;
    db.busy_timeout(std::time::Duration::from_secs(2))?;
    db.execute_batch(
        "PRAGMA journal_mode=WAL;
         CREATE TABLE IF NOT EXISTS settings (name TEXT PRIMARY KEY, value BLOB NOT NULL);
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
    ensure!(next.validate(), "geçersiz buton bilgisi");
    let transaction = db.transaction()?;
    let old = deck(&transaction)?;
    if old.revision != next.revision {
        return Ok(None);
    }
    next.revision = old
        .revision
        .checked_add(1)
        .ok_or_else(|| anyhow::anyhow!("sürüm sınırı"))?;
    put(&transaction, "deck", &serde_json::to_vec(&next)?)?;
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
