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

pub(crate) const LIMIT: usize = 8 * 1024 * 1024;
const CHUNK: usize = 32 * 1024;

pub(crate) fn valid_id(hash: &str) -> bool {
    hash.len() == 64
        && hash
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

pub(crate) fn is_mp4(data: &[u8]) -> bool {
    data.get(4..8) == Some(b"ftyp")
        && data
            .get(8..12)
            .is_some_and(|brand| brand != b"qt  " && brand != b"M4A ")
}

pub(crate) fn save(db: &Connection, file: &Path) -> Result<String> {
    let mut bytes = Vec::new();
    std::fs::File::open(file)?
        .take((LIMIT + 1) as u64)
        .read_to_end(&mut bytes)?;
    ensure!(
        !bytes.is_empty() && bytes.len() <= LIMIT,
        "Medya 8 MB veya daha küçük olmalı"
    );
    if !is_mp4(&bytes) {
        let reader = ImageReader::new(BufReader::new(Cursor::new(&bytes))).with_guessed_format()?;
        let format = reader.format();
        ensure!(
            matches!(
                format,
                Some(ImageFormat::Gif | ImageFormat::Png | ImageFormat::Jpeg)
            ),
            "GIF, PNG, JPG veya MP4 seç"
        );
        let (width, height) = reader.into_dimensions()?;
        ensure!(
            (1..=4096).contains(&width) && (1..=4096).contains(&height),
            "Görsel en fazla 4096 × 4096 olabilir"
        );
        if format == Some(ImageFormat::Gif) {
            ensure!(
                width <= 800 && height <= 800,
                "GIF en fazla 800 × 800 olabilir"
            );
        } else {
            let decoded = image::load_from_memory_with_format(
                &bytes,
                format.context("Görsel türü bilinmiyor")?,
            )?;
            if width > 800 || height > 800 {
                let mut normalized = Cursor::new(Vec::new());
                let decoded = decoded.thumbnail(800, 800);
                decoded.write_to(
                    &mut normalized,
                    if format == Some(ImageFormat::Jpeg) {
                        ImageFormat::Jpeg
                    } else {
                        ImageFormat::Png
                    },
                )?;
                bytes = normalized.into_inner();
            }
        }
    }
    let id = format!("{:x}", Sha256::digest(&bytes));
    let used: usize = db.query_row(
        "SELECT coalesce(sum(length(data)), 0) FROM button_art",
        [],
        |row| row.get(0),
    )?;
    ensure!(
        used + bytes.len() <= 64 * 1024 * 1024 || read(db, &id)?.is_some(),
        "Buton medyası için alan kalmadı"
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
        "Geçersiz medya isteği"
    );
    ensure!(
        crate::store::deck(db)?
            .buttons
            .iter()
            .any(|button| button.art.as_deref() == Some(id)),
        "Medya panelde yok"
    );
    let (total, part): (usize, Vec<u8>) = db
        .query_row(
            "SELECT length(data), substr(data, ?, ?) FROM button_art WHERE id = ?",
            params![offset + 1, CHUNK, id],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .optional()?
        .context("Medya bulunamadı")?;
    ensure!(
        offset < total && total <= LIMIT && !part.is_empty(),
        "Geçersiz medya konumu"
    );
    Ok(
        json!({"type":"button_art","id":id,"offset":offset,"total":total,"data":STANDARD.encode(part)}),
    )
}
