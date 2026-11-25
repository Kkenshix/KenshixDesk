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
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub layout: Option<crate::layout::Layout>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct Button {
    pub id: Uuid,
    pub label: String,
    pub action: Action,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub routine: Option<crate::routine::Routine>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub art: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub behavior: Option<ButtonBehavior>,
}

#[derive(Clone, Debug, Default, Deserialize, Serialize)]
#[serde(default, deny_unknown_fields)]
pub(crate) struct ButtonBehavior {
    pub double_tap: Option<Uuid>,
    pub long_press: Option<Uuid>,
    pub shared_macro: Option<Uuid>,
    pub confirm: bool,
    pub pinned: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Deserialize, Serialize)]
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
    Macro,
}

impl Action {
    pub(crate) fn is_windows(self) -> bool {
        matches!(
            self,
            Self::PlayPause
                | Self::NextTrack
                | Self::PreviousTrack
                | Self::TaskView
                | Self::TaskManager
        )
    }
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
    ObsControls,
    ChangeObs {
        operation: Uuid,
        target: crate::obs::LiveControl,
    },
    Metrics,
    Media,
    Input {
        operation: Uuid,
        input: crate::windows_host::InputAction,
    },
    Windows,
    ControlWindow {
        operation: Uuid,
        window: String,
        command: crate::window_control::Command,
    },
    ControlMedia {
        operation: Uuid,
        session: String,
        command: crate::media::Command,
    },
    UpdateStatus,
    ObsStatus,
    Save {
        deck: Deck,
    },
    Execute {
        operation: Uuid,
        button: Uuid,
    },
    ButtonArt {
        id: String,
        offset: usize,
    },
    OperationStatus {
        operation: Uuid,
    },
    MacroHistory {
        operation: Uuid,
    },
    CancelOperation {
        operation: Uuid,
    },
    Mixer,
    AudioOutputs,
    SelectAudio {
        operation: Uuid,
        output: String,
    },
    ApplyAudioPreset {
        operation: Uuid,
        levels: Vec<crate::audio::PresetLevel>,
    },
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
        let valid = self.buttons.iter().all(|button| {
            let label = button.label.trim();
            !label.is_empty()
                && label.chars().count() <= 60
                && !label.chars().any(char::is_control)
                && !button.id.is_nil()
                && ids.insert(button.id)
                && button.art.as_deref().is_none_or(crate::art::valid_id)
                && match (
                    button.action,
                    &button.routine,
                    button
                        .behavior
                        .as_ref()
                        .and_then(|extra| extra.shared_macro),
                ) {
                    (Action::Macro, Some(routine), None) => routine.valid(),
                    (Action::Macro, None, Some(id)) => {
                        id != button.id
                            && self.buttons.iter().any(|source| {
                                source.id == id
                                    && source.action == Action::Macro
                                    && source.routine.is_some()
                            })
                    }
                    (Action::Macro, _, _) | (_, Some(_), _) | (_, _, Some(_)) => false,
                    _ => true,
                }
                && button.behavior.as_ref().is_none_or(|extra| {
                    [extra.double_tap, extra.long_press]
                        .into_iter()
                        .flatten()
                        .all(|id| {
                            id != button.id && self.buttons.iter().any(|target| target.id == id)
                        })
                })
        });
        valid
            && self
                .layout
                .as_ref()
                .is_none_or(|layout| layout.validate(&ids))
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
            routine: None,
            art: None,
            behavior: None,
        };
        let deck = Deck {
            revision: 0,
            buttons: vec![button.clone(), button],
            layout: None,
        };
        assert!(!deck.validate());
    }
}
