use std::collections::{BTreeMap, HashMap, HashSet};

use serde::{Deserialize, Serialize};
use uuid::Uuid;

#[derive(Clone, Debug, Default, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct Layout {
    pub panels: Vec<Panel>,
    pub placements: BTreeMap<Uuid, Placement>,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct Panel {
    id: Uuid,
    name: String,
    parent: Option<Uuid>,
    kind: PanelKind,
}

#[derive(Clone, Copy, Debug, Deserialize, Serialize)]
#[serde(rename_all = "snake_case")]
enum PanelKind {
    Profile,
    Page,
    Folder,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
#[serde(deny_unknown_fields)]
pub(crate) struct Placement {
    panel: Option<Uuid>,
    color: Option<String>,
    span: u8,
}

impl Layout {
    pub(crate) fn validate(&self, button_ids: &HashSet<Uuid>) -> bool {
        let mut panels = HashMap::new();
        for panel in &self.panels {
            let name = panel.name.trim();
            if panel.id.is_nil()
                || name.is_empty()
                || name.chars().count() > 60
                || name.chars().any(char::is_control)
                || panels.insert(panel.id, panel).is_some()
            {
                return false;
            }
        }

        for panel in &self.panels {
            let parent = panel.parent.and_then(|id| panels.get(&id).copied());
            let valid_parent = match panel.kind {
                PanelKind::Profile => panel.parent.is_none(),
                PanelKind::Page => {
                    parent.is_some_and(|parent| matches!(parent.kind, PanelKind::Profile))
                }
                PanelKind::Folder => parent.is_some(),
            };
            if !valid_parent {
                return false;
            }
            let mut ancestor = parent;
            for _ in 0..64 {
                let Some(parent) = ancestor else { break };
                ancestor = parent.parent.and_then(|id| panels.get(&id).copied());
            }
            if ancestor.is_some() {
                return false;
            }
        }

        self.placements.iter().all(|(id, placement)| {
            button_ids.contains(id)
                && placement
                    .panel
                    .is_none_or(|panel| panels.contains_key(&panel))
                && matches!(placement.span, 1 | 2)
                && placement.color.as_ref().is_none_or(|value| {
                    value.len() == 7
                        && value.starts_with('#')
                        && value.as_bytes()[1..].iter().all(u8::is_ascii_hexdigit)
                })
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_folder_cycles() {
        let id = Uuid::new_v4();
        let layout = Layout {
            panels: vec![Panel {
                id,
                name: "Klasör".into(),
                parent: Some(id),
                kind: PanelKind::Folder,
            }],
            placements: BTreeMap::new(),
        };
        assert!(!layout.validate(&HashSet::new()));
    }
}
