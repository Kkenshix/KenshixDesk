use serde::{Deserialize, Serialize};
use uuid::Uuid;

pub(crate) const VERSION: u8 = 1;
pub(crate) const PORT: u16 = 43851;
pub(crate) const MAX_MESSAGE: usize = 64 * 1024;

#[derive(Clone, Debug, Default, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct Deck {
    pub revision: u64,
    pub buttons: Vec<Button>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct Button {
    pub id: Uuid,
    pub label: String,
    pub action: Action,
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
pub(crate) enum Action {
    PlayPause,
    NextTrack,
    PreviousTrack,
    TaskView,
    TaskManager,
    ObsStartRecord,
    ObsStopRecord,
    ObsToggleRecord,
    ObsPauseRecord,
    ObsResumeRecord,
    ObsStartStream,
    ObsStopStream,
    ObsStartReplay,
    ObsStopReplay,
    ObsSaveReplay,
    ObsStatus,
    Mixer,
}

#[derive(Debug, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case", deny_unknown_fields)]
pub(crate) enum Request {
    Pair {
        version: u8,
        code: String,
    },
    Hello {
        version: u8,
        token: String,
    },
    Snapshot,
    ObsStatus,
    Save {
        deck: Deck,
    },
    Execute {
        operation: Uuid,
        button: Uuid,
    },
    Mixer,
    SetVolume {
        operation: Uuid,
        target: String,
        percent: u8,
    },
    SetMute {
        operation: Uuid,
        target: String,
        muted: bool,
    },
}

impl Deck {
    pub(crate) fn validate(&self) -> bool {
        let mut ids = std::collections::HashSet::new();
        self.buttons.iter().all(|button| {
            let label = button.label.trim();
            !label.is_empty()
                && label.chars().count() <= 60
                && !label.chars().any(char::is_control)
                && !button.id.is_nil()
                && ids.insert(button.id)
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_unknown_fields_and_actions() {
        assert!(
            serde_json::from_str::<Request>(
                r#"{"type":"hello","version":1,"token":"token","admin":true}"#
            )
            .is_err()
        );
        assert!(serde_json::from_str::<Action>(r#""powershell""#).is_err());
    }

    #[test]
    fn rejects_duplicate_button_ids() {
        let button = Button {
            id: Uuid::new_v4(),
            label: "Müzik".to_owned(),
            action: Action::PlayPause,
        };
        let deck = Deck {
            revision: 0,
            buttons: vec![button.clone(), button],
        };
        assert!(!deck.validate());
    }
}
