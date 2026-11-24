use std::{
    io::{BufReader, Cursor, Read},
    path::Path,
};

use anyhow::{Context, Result, ensure};
use base64::{Engine, engine::general_purpose::STANDARD};
use image::{ImageFormat, ImageReader};
use rusqlite::{Connection, OptionalExtension, params};
use serde_json::{Value, json};
use sha2::{Digest, Sha256};

pub(crate) const LIMIT: usize = 512 * 1024;
const CHUNK: usize = 32 * 1024;

pub(crate) fn valid_id(hash: &str) -> bool {
    hash.len() == 64
        && hash
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

pub(crate) fn save(db: &Connection, file: &Path) -> Result<String> {
    let mut bytes = Vec::new();
    std::fs::File::open(file)?
        .take((LIMIT + 1) as u64)
        .read_to_end(&mut bytes)?;
    ensure!(bytes.len() <= LIMIT, "Görsel en fazla 512 KB olabilir");
    let reader = ImageReader::new(BufReader::new(Cursor::new(&bytes))).with_guessed_format()?;
    ensure!(
        matches!(
            reader.format(),
            Some(ImageFormat::Gif | ImageFormat::Png | ImageFormat::Jpeg)
        ),
        "GIF, PNG veya JPEG seç"
    );
    let (width, height) = reader.into_dimensions()?;
    ensure!(
        (1..=800).contains(&width) && (1..=800).contains(&height),
        "Görsel en fazla 800 × 800 olabilir"
    );
    let id = format!("{:x}", Sha256::digest(&bytes));
    let used: usize = db.query_row(
        "SELECT coalesce(sum(length(data)), 0) FROM button_art",
        [],
        |row| row.get(0),
    )?;
    ensure!(
        used + bytes.len() <= 32 * 1024 * 1024 || read(db, &id)?.is_some(),
        "Görsel alanı dolu"
    );
    db.execute(
        "INSERT OR IGNORE INTO button_art (id, data) VALUES (?, ?)",
        params![id, bytes],
    )?;
    Ok(id)
}

pub(crate) fn read(db: &Connection, id: &str) -> Result<Option<Vec<u8>>> {
    Ok(db
        .query_row("SELECT data FROM button_art WHERE id = ?", [id], |row| {
            row.get(0)
        })
        .optional()?)
}

pub(crate) fn chunk(db: &Connection, id: &str, offset: usize) -> Result<Value> {
    ensure!(
        valid_id(id) && offset < LIMIT && offset.is_multiple_of(CHUNK),
        "geçersiz görsel isteği"
    );
    ensure!(
        crate::store::deck(db)?
            .buttons
            .iter()
            .any(|button| button.art.as_deref() == Some(id)),
        "görsel panelde yok"
    );
    let bytes = read(db, id)?.context("görsel bulunamadı")?;
    ensure!(offset < bytes.len(), "geçersiz görsel konumu");
    let end = (offset + CHUNK).min(bytes.len());
    Ok(
        json!({"type":"button_art","id":id,"offset":offset,"total":bytes.len(),"data":STANDARD.encode(&bytes[offset..end])}),
    )
}
