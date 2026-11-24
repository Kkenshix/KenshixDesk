use anyhow::{Context, Result, ensure};
use rusqlite::Connection;
use uuid::Uuid;

use crate::{
    protocol::{Action, Button},
    store,
};

pub(crate) enum Edit {
    List,
    Add { label: String, action: Action },
    Remove(Uuid),
}

impl Edit {
    pub(crate) fn from_arguments(
        name: &str,
        arguments: &mut impl Iterator<Item = String>,
    ) -> Result<Self> {
        match name {
            "--buttons" => Ok(Self::List),
            "--add-button" => {
                let label = arguments.next().context("buton adı gerekli")?;
                let action = arguments.next().context("eylem gerekli")?;
                let action = serde_json::from_value(serde_json::Value::String(action))
                    .context("eylem adı geçersiz")?;
                Ok(Self::Add { label, action })
            }
            "--remove-button" => {
                let id = arguments.next().context("buton kimliği gerekli")?.parse()?;
                Ok(Self::Remove(id))
            }
            _ => anyhow::bail!("bilinmeyen düzenleme"),
        }
    }

    pub(crate) fn apply(self, db: &mut Connection) -> Result<()> {
        let mut deck = store::deck(db)?;
        match self {
            Self::List => {
                println!("{}", serde_json::to_string_pretty(&deck)?);
                return Ok(());
            }
            Self::Add { label, action } => {
                deck.buttons.push(Button {
                    id: Uuid::new_v4(),
                    label,
                    action,
                    routine: None,
                    art: None,
                });
            }
            Self::Remove(id) => {
                let count = deck.buttons.len();
                deck.buttons.retain(|button| button.id != id);
                ensure!(deck.buttons.len() < count, "buton bulunamadı");
                if let Some(layout) = &mut deck.layout {
                    layout.placements.remove(&id);
                }
            }
        }
        let serialized = serde_json::to_vec(&deck)?;
        ensure!(
            serialized.len() <= crate::protocol::MAX_MESSAGE - 256,
            "panel boyutu bağlantı sınırını aşıyor"
        );
        store::save(db, deck)?.context("panel değişti, düzenlemeyi yeniden dene")?;
        Ok(())
    }
}
