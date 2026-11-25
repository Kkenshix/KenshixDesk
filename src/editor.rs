use std::{
    collections::HashMap,
    io::{BufReader, Cursor},
    time::{Duration, Instant},
};

use anyhow::Result;
use eframe::egui::{self, Color32, Pos2, Rect, Sense, Vec2};
use image::{AnimationDecoder, DynamicImage};
use rusqlite::Connection;
use uuid::Uuid;

use crate::{
    art,
    layout::{Panel, PanelKind, Placement},
    protocol::{Action, Button, Deck},
    routine::{Routine, Step},
    store,
};

const M22_CONTENT_WIDTH: f32 = 1489.0;
const TILE_GAP: f32 = 15.0;
const AUTO_HEIGHT: f32 = 210.0;
const PANEL: Color32 = Color32::from_gray(13);
const CONTROL: Color32 = Color32::from_gray(23);
const BORDER: Color32 = Color32::from_gray(52);
const MUTED: Color32 = Color32::from_gray(185);
const ACCENT: Color32 = Color32::WHITE;

fn theme(ctx: &egui::Context) {
    let mut style = (*ctx.style()).clone();
    style.spacing.item_spacing = Vec2::new(9.0, 9.0);
    style.spacing.button_padding = Vec2::new(11.0, 6.0);
    style.spacing.interact_size.y = 31.0;
    let mut visual = egui::Visuals::dark();
    visual.panel_fill = Color32::BLACK;
    visual.window_fill = PANEL;
    visual.extreme_bg_color = Color32::BLACK;
    visual.faint_bg_color = CONTROL;
    visual.window_stroke = egui::Stroke::new(1.0_f32, BORDER);
    visual.override_text_color = Some(Color32::WHITE);
    visual.selection.bg_fill = Color32::from_gray(52);
    visual.selection.stroke = egui::Stroke::new(1.0_f32, ACCENT);
    visual.hyperlink_color = ACCENT;
    visual.error_fg_color = MUTED;
    visual.warn_fg_color = MUTED;
    for widget in [
        &mut visual.widgets.noninteractive,
        &mut visual.widgets.inactive,
        &mut visual.widgets.hovered,
        &mut visual.widgets.active,
        &mut visual.widgets.open,
    ] {
        widget.bg_fill = CONTROL;
        widget.weak_bg_fill = CONTROL;
        widget.bg_stroke = egui::Stroke::new(1.0_f32, BORDER);
        widget.fg_stroke.color = ACCENT;
        widget.corner_radius = egui::CornerRadius::same(7);
    }
    visual.widgets.hovered.bg_stroke.color = MUTED;
    visual.widgets.active.bg_stroke.color = ACCENT;
    visual.widgets.open.bg_stroke.color = ACCENT;
    visual.widgets.hovered.weak_bg_fill = Color32::from_gray(38);
    style.visuals = visual;
    ctx.set_style(style);
}

pub(crate) fn open(db: Connection, adb: Option<(String, String)>) -> Result<()> {
    let deck = store::deck(&db)?;
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_inner_size([1080.0, 645.0])
            .with_min_inner_size([740.0, 500.0]),
        ..Default::default()
    };
    eframe::run_native(
        "KenshixDeck · Panel editörü",
        options,
        Box::new(move |creation| {
            theme(&creation.egui_ctx);
            let logo = egui::ColorImage::from_rgba_unmultiplied(
                [32, 32],
                include_bytes!("../assets/dragon-tray.rgba"),
            );
            let logo = creation.egui_ctx.load_texture(
                "KenshixDeck logo",
                logo,
                egui::TextureOptions::LINEAR,
            );
            Ok(Box::new(Editor {
                db,
                deck,
                logo,
                panel: None,
                selected: None,
                selection: std::collections::HashSet::new(),
                undo: Vec::new(),
                macro_test: None,
                redo: Vec::new(),
                rename: None,
                message: String::new(),
                drag: None,
                textures: HashMap::new(),
                videos: std::collections::HashSet::new(),
                dirty: false,
                resize_start: None,
                phone: adb.map(PhonePreview::new),
            }))
        }),
    )
    .map_err(|error| anyhow::anyhow!("{error}"))
}

struct Editor {
    db: Connection,
    deck: Deck,
    logo: egui::TextureHandle,
    panel: Option<Uuid>,
    selected: Option<Uuid>,
    selection: std::collections::HashSet<Uuid>,
    undo: Vec<Deck>,
    macro_test: Option<MacroTest>,
    redo: Vec<Deck>,
    rename: Option<(Uuid, String)>,
    message: String,
    drag: Option<Uuid>,
    textures: HashMap<String, ArtPreview>,
    videos: std::collections::HashSet<String>,
    dirty: bool,
    resize_start: Option<(Uuid, Pos2)>,
    phone: Option<PhonePreview>,
}

impl Editor {
    fn save(&mut self) {
        if !self.dirty {
            return;
        }
        match store::save(&mut self.db, self.deck.clone()) {
            Ok(Some(deck)) => {
                self.deck = deck;
                self.dirty = false;
                self.message = "Telefona gönderildi".into();
            }
            Ok(None) => {
                self.message = "Telefon bu paneli değiştirdi. Güncel panel yüklendi.".into();
                if let Ok(deck) = store::deck(&self.db) {
                    self.deck = deck;
                    self.dirty = false;
                    self.undo.clear();
                    self.redo.clear();
                }
            }
            Err(error) => self.message = error.to_string(),
        }
    }

    fn history(&mut self, redo: bool) {
        let (from, to) = if redo {
            (&mut self.redo, &mut self.undo)
        } else {
            (&mut self.undo, &mut self.redo)
        };
        if let Some(mut deck) = from.pop() {
            deck.revision = self.deck.revision;
            to.push(std::mem::replace(&mut self.deck, deck));
            self.selected = self
                .selected
                .filter(|id| self.deck.buttons.iter().any(|button| button.id == *id));
            self.selection.clear();
            self.dirty = true;
            self.message = if redo {
                "Değişiklik yinelendi"
            } else {
                "Değişiklik geri alındı"
            }
            .into();
        }
    }

    fn placement(&self, id: Uuid) -> Placement {
        self.deck
            .layout
            .as_ref()
            .and_then(|layout| layout.placements.get(&id))
            .cloned()
            .unwrap_or(Placement {
                panel: None,
                color: None,
                span: 1,
                width_px: None,
                height_px: None,
            })
    }

    fn place(&mut self, id: Uuid, placement: Placement) {
        self.deck
            .layout
            .get_or_insert_default()
            .placements
            .insert(id, placement);
        self.dirty = true;
    }

    fn panel_name(&self, id: Option<Uuid>) -> &str {
        id.and_then(|id| {
            self.deck
                .layout
                .as_ref()?
                .panels
                .iter()
                .find(|panel| panel.id == id)
        })
        .map_or("Ana panel", |panel| panel.name.as_str())
    }

    fn create_panel(&mut self, kind: PanelKind) {
        let parent = if kind == PanelKind::Profile {
            None
        } else {
            self.panel
        };
        let id = Uuid::new_v4();
        let name = match kind {
            PanelKind::Profile => "Yeni profil",
            PanelKind::Page => "Yeni sayfa",
            PanelKind::Folder => "Yeni klasör",
        };
        self.deck.layout.get_or_insert_default().panels.push(Panel {
            id,
            name: name.into(),
            parent,
            kind,
        });
        self.rename = Some((id, name.into()));
        self.panel = Some(id);
        self.selected = None;
        self.dirty = true;
        self.save();
    }

    fn delete_panel(&mut self, id: Uuid) {
        let Some(layout) = &mut self.deck.layout else {
            return;
        };
        let occupied = layout.panels.iter().any(|panel| panel.parent == Some(id))
            || layout
                .placements
                .values()
                .any(|placement| placement.panel == Some(id));
        if occupied {
            self.message =
                "Paneli silmeden önce içindeki butonları ve alt panelleri taşı veya sil.".into();
            return;
        }
        self.panel = layout
            .panels
            .iter()
            .find(|panel| panel.id == id)
            .and_then(|panel| panel.parent);
        layout.panels.retain(|panel| panel.id != id);
        self.dirty = true;
        self.save();
    }

    fn create_button(&mut self) {
        let id = Uuid::new_v4();
        self.deck.buttons.push(Button {
            id,
            label: "Yeni buton".into(),
            action: Action::Mixer,
            routine: None,
            art: None,
            behavior: None,
        });
        self.place(
            id,
            Placement {
                panel: self.panel,
                color: None,
                span: 1,
                width_px: None,
                height_px: None,
            },
        );
        self.selected = Some(id);
    }

    fn preview(&mut self, ui: &mut egui::Ui) {
        egui::Frame::new()
            .fill(Color32::BLACK)
            .corner_radius(15)
            .stroke(egui::Stroke::new(1.0_f32, BORDER))
            .inner_margin(egui::Margin::same(18))
            .show(ui, |ui| {
                let screen_height = (ui.available_width() + 36.0) * 720.0 / 1600.0;
                ui.set_min_height((screen_height - 36.0).max(130.0));
                ui.horizontal(|ui| {
                    ui.label(egui::RichText::new("KenshixDeck").strong().size(17.0));
                    ui.separator();
                    ui.label(self.panel_name(self.panel));
                });
                ui.add_space(19.0);
                let children: Vec<_> = self
                    .deck
                    .layout
                    .iter()
                    .flat_map(|layout| &layout.panels)
                    .filter(|panel| panel.parent == self.panel && panel.kind != PanelKind::Profile)
                    .cloned()
                    .collect();
                let buttons: Vec<_> = self
                    .deck
                    .buttons
                    .iter()
                    .filter(|button| self.placement(button.id).panel == self.panel)
                    .cloned()
                    .collect();
                let mut tiles: Vec<Tile> = children.into_iter().map(Tile::Panel).collect();
                tiles.extend(buttons.into_iter().map(Tile::Button));
                tiles.push(Tile::Create);
                let width = ui.available_width().max(100.0);
                let scale = width / M22_CONTENT_WIDTH;
                let gap = scale * TILE_GAP;
                let cell = (width - gap * 5.0) / 5.0;
                let origin = ui.cursor().min;
                let mut row_y = 0.0;
                let mut row_height: f32 = 0.0;
                let mut column_x = 0.0;
                let mut drop_onto = None;
                let mut guide_tiles = Vec::new();
                let mut active_guide = None;
                for tile in tiles {
                    let placement = if let Tile::Button(button) = &tile {
                        Some(self.placement(button.id))
                    } else {
                        None
                    };
                    let span = placement.as_ref().map_or(1, |p| p.span);
                    let tile_width = placement
                        .as_ref()
                        .and_then(|p| p.width_px)
                        .map_or(f32::from(span) * (cell + gap) - gap, |px| {
                            f32::from(px) * scale
                        })
                        .min(width - gap);
                    let tile_height = placement
                        .as_ref()
                        .and_then(|p| p.height_px)
                        .map_or(AUTO_HEIGHT, f32::from)
                        * scale;
                    if column_x + tile_width + gap > width + 0.1 {
                        row_y += row_height + gap;
                        row_height = 0.0;
                        column_x = 0.0;
                    }
                    let start = origin + Vec2::new(column_x, row_y);
                    let rect = Rect::from_min_size(start, Vec2::new(tile_width, tile_height));
                    column_x += tile_width + gap;
                    row_height = row_height.max(tile_height);
                    match tile {
                        Tile::Panel(panel) => {
                            let response =
                                ui.interact(rect, ui.id().with(panel.id), Sense::click());
                            phone_tile(
                                ui,
                                rect,
                                &panel.name,
                                if panel.kind == PanelKind::Page {
                                    "Sayfa"
                                } else {
                                    "Klasör"
                                },
                                PANEL,
                                false,
                            );
                            if response.clicked() {
                                self.panel = Some(panel.id);
                                self.selected = None;
                            }
                        }
                        Tile::Button(button) => {
                            let mut placement = placement.expect("button placement");
                            guide_tiles.push((button.id, rect));
                            let handle_size =
                                17.0_f32.min(rect.width() / 2.0).min(rect.height() / 2.0);
                            let resize =
                                Rect::from_min_max(rect.max - Vec2::splat(handle_size), rect.max);
                            let response =
                                ui.interact(rect, ui.id().with(button.id), Sense::click_and_drag());
                            let handle = ui
                                .interact(resize, ui.id().with((button.id, "size")), Sense::drag())
                                .on_hover_cursor(egui::CursorIcon::ResizeNwSe);
                            phone_tile(
                                ui,
                                rect,
                                &button.label,
                                action_label(button.action),
                                placement
                                    .color
                                    .as_deref()
                                    .and_then(parse_color)
                                    .unwrap_or(PANEL),
                                self.selected == Some(button.id)
                                    || self.selection.contains(&button.id),
                            );
                            if let Some(id) = &button.art {
                                self.draw_art(ui, id, rect);
                            }
                            if self.selected == Some(button.id) || handle.hovered() {
                                ui.painter().line_segment(
                                    [
                                        resize.right_bottom() - Vec2::new(3.0, 10.0),
                                        resize.right_bottom() - Vec2::new(10.0, 3.0),
                                    ],
                                    egui::Stroke::new(1.5_f32, ACCENT),
                                );
                            }
                            if response.clicked() {
                                if ui.input(|input| input.modifiers.ctrl) {
                                    if !self.selection.remove(&button.id) {
                                        self.selection.insert(button.id);
                                    }
                                    if let Some(previous) =
                                        self.selected.filter(|id| *id != button.id)
                                    {
                                        self.selection.insert(previous);
                                    }
                                } else {
                                    self.selection.clear();
                                }
                                self.selected = Some(button.id);
                            }
                            if let Some(pointer) = ui.input(|input| input.pointer.latest_pos()) {
                                if response.dragged() {
                                    active_guide = Some((
                                        button.id,
                                        Rect::from_center_size(pointer, rect.size()),
                                        false,
                                    ));
                                } else if handle.dragged() {
                                    active_guide = Some((
                                        button.id,
                                        Rect::from_min_max(
                                            rect.min,
                                            pointer.max(rect.min + Vec2::splat(12.0)),
                                        ),
                                        true,
                                    ));
                                }
                            }
                            if response.drag_started() {
                                self.drag = Some(button.id);
                                self.selected = Some(button.id);
                            }
                            if ui.input(|input| input.pointer.any_released())
                                && ui
                                    .input(|input| input.pointer.interact_pos())
                                    .is_some_and(|point| rect.contains(point))
                            {
                                drop_onto = Some(button.id);
                            }
                            if handle.drag_started() {
                                self.selected = Some(button.id);
                                self.resize_start = handle
                                    .interact_pointer_pos()
                                    .map(|point| (button.id, point));
                            }
                            if handle.drag_stopped()
                                && let Some((id, start)) = self.resize_start.take()
                                && id == button.id
                                && let Some(end) = ui.input(|input| input.pointer.latest_pos())
                            {
                                let delta = (end - start) / scale;
                                if delta.length_sq() >= 1.0 {
                                    placement.width_px = Some(
                                        (rect.width() / scale + delta.x).round().clamp(80.0, 1500.0)
                                            as u16,
                                    );
                                    placement.height_px = Some(
                                        (rect.height() / scale + delta.y).round().clamp(70.0, 660.0)
                                            as u16,
                                    );
                                    self.place(button.id, placement);
                                }
                            }
                        }
                        Tile::Create => {
                            ui.painter().rect_filled(rect, 16, Color32::BLACK);
                            ui.painter().text(
                                rect.center(),
                                egui::Align2::CENTER_CENTER,
                                "+",
                                egui::FontId::proportional(33.0),
                                ACCENT,
                            );
                            if ui
                                .interact(rect, ui.id().with("new"), Sense::click())
                                .clicked()
                            {
                                self.create_button();
                            }
                        }
                    }
                }
                if let Some((id, rect, resizing)) = active_guide {
                    let painter = ui.painter();
                    let guide = egui::Stroke::new(1.0_f32, Color32::from_gray(165));
                    let ghost = egui::Stroke::new(1.0_f32, ACCENT);
                    painter.rect_stroke(rect, 7, ghost, egui::StrokeKind::Outside);
                    let x_values = if resizing {
                        [rect.max.x; 3]
                    } else {
                        [rect.min.x, rect.center().x, rect.max.x]
                    };
                    let y_values = if resizing {
                        [rect.max.y; 3]
                    } else {
                        [rect.min.y, rect.center().y, rect.max.y]
                    };
                    for (other, edge) in &guide_tiles {
                        if *other == id {
                            continue;
                        }
                        for x in [edge.min.x, edge.center().x, edge.max.x] {
                            if x_values.iter().any(|value| (x - value).abs() < 5.0) {
                                painter.vline(x, origin.y..=origin.y + row_y + row_height, guide);
                            }
                        }
                        for y in [edge.min.y, edge.center().y, edge.max.y] {
                            if y_values.iter().any(|value| (y - value).abs() < 5.0) {
                                painter.hline(origin.x..=origin.x + width, y, guide);
                            }
                        }
                    }
                }
                ui.allocate_space(Vec2::new(width, row_y + row_height + gap));
                if ui.input(|input| input.pointer.any_released())
                    && let (Some(from), Some(to)) = (self.drag.take(), drop_onto)
                    && from != to
                    && let (Some(from), Some(to)) = (
                        self.deck
                            .buttons
                            .iter()
                            .position(|button| button.id == from),
                        self.deck.buttons.iter().position(|button| button.id == to),
                    )
                {
                    let item = self.deck.buttons.remove(from);
                    self.deck.buttons.insert(to, item);
                    self.dirty = true;
                }
            });
    }

    fn draw_art(&mut self, ui: &egui::Ui, id: &str, tile: Rect) {
        if !ui.clip_rect().intersects(tile) {
            return;
        }
        if self.textures.len() >= 16 && !self.textures.contains_key(id) {
            self.textures.clear();
        }
        if self.videos.contains(id) {
            ui.painter().with_clip_rect(tile).text(
                tile.center(),
                egui::Align2::CENTER_CENTER,
                "MP4",
                egui::FontId::proportional(13.0),
                ACCENT,
            );
            return;
        }
        let Some(preview) = self.textures.get(id) else {
            if let Ok(Some(raw)) = art::read(&self.db, id) {
                if art::is_mp4(&raw) {
                    self.videos.insert(id.into());
                    self.draw_art(ui, id, tile);
                } else if let Some(art) = ArtPreview::new(ui, id, &raw) {
                    self.textures.insert(id.into(), art);
                    self.draw_art(ui, id, tile);
                }
            }
            return;
        };
        let image = Rect::from_min_max(
            tile.min + Vec2::new(5.0, 5.0),
            Pos2::new(tile.max.x - 5.0, tile.min.y + tile.height() * 0.58),
        );
        if !image.is_positive() {
            return;
        }
        let (texture, delay) = preview.frame();
        if let Some(delay) = delay {
            ui.ctx().request_repaint_after(delay);
        }
        let source = texture.size_vec2();
        let scale = (image.width() / source.x).min(image.height() / source.y);
        ui.painter()
            .with_clip_rect(image.intersect(ui.clip_rect()))
            .image(
                texture.id(),
                Rect::from_center_size(image.center(), source * scale),
                Rect::from_min_max(Pos2::ZERO, Pos2::new(1.0, 1.0)),
                Color32::WHITE,
            );
    }

    fn inspect_button(&mut self, ui: &mut egui::Ui, id: Uuid) {
        let Some(index) = self.deck.buttons.iter().position(|item| item.id == id) else {
            return;
        };
        let mut placement = self.placement(id);
        let panel_names: Vec<_> = std::iter::once((None, "Ana panel".to_owned()))
            .chain(
                self.deck
                    .layout
                    .iter()
                    .flat_map(|layout| &layout.panels)
                    .map(|panel| (Some(panel.id), panel.name.clone())),
            )
            .collect();
        let current_name = self.panel_name(placement.panel).to_owned();
        let alternatives: Vec<_> = self
            .deck
            .buttons
            .iter()
            .filter(|record| record.id != id)
            .map(|record| (record.id, record.label.clone(), record.routine.is_some()))
            .collect();
        let button = &mut self.deck.buttons[index];
        let label = ui.add(
            egui::TextEdit::singleline(&mut button.label)
                .char_limit(60)
                .hint_text("Buton adı"),
        );
        self.dirty |= label.changed();
        let prior = button.action;
        action_picker(ui, "button-action", &mut button.action, false);
        if prior != button.action {
            if let Some(behavior) = &mut button.behavior {
                behavior.shared_macro = None;
            }
            button.routine = (button.action == Action::Macro).then(|| Routine {
                steps: vec![],
                continue_on_error: false,
                trigger: None,
            });
            self.dirty = true;
        }
        let mut behavior = button.behavior.clone().unwrap_or_default();
        ui.collapsing("Dokunma ve sabitleme", |ui| {
            self.dirty |= ui
                .checkbox(&mut behavior.confirm, "Çalıştırmadan önce onay iste")
                .changed();
            self.dirty |= ui
                .checkbox(&mut behavior.pinned, "Sık kullanılanlara sabitle")
                .changed();
            self.dirty |= button_reference(
                ui,
                "Çift dokunma",
                &mut behavior.double_tap,
                alternatives
                    .iter()
                    .map(|(id, name, _)| (*id, name.as_str())),
            );
            self.dirty |= button_reference(
                ui,
                "Basılı tutma",
                &mut behavior.long_press,
                alternatives
                    .iter()
                    .map(|(id, name, _)| (*id, name.as_str())),
            );
        });
        if button.action == Action::Macro {
            let shared = button_reference(
                ui,
                "Paylaşılan makro",
                &mut behavior.shared_macro,
                alternatives
                    .iter()
                    .filter(|(_, _, macro_only)| *macro_only)
                    .map(|(id, name, _)| (*id, name.as_str())),
            );
            if shared {
                button.routine = if behavior.shared_macro.is_some() {
                    None
                } else {
                    Some(Routine {
                        steps: vec![],
                        continue_on_error: false,
                        trigger: None,
                    })
                };
                self.dirty = true;
            }
        }
        if behavior.confirm
            || behavior.pinned
            || behavior.double_tap.is_some()
            || behavior.long_press.is_some()
            || behavior.shared_macro.is_some()
        {
            button.behavior = Some(behavior);
        } else {
            button.behavior = None;
        }
        if let Some(routine) = &mut button.routine {
            self.dirty |= routine_editor(ui, routine);
            if ui
                .add_enabled(
                    routine.valid() && self.macro_test.is_none(),
                    egui::Button::new("Makroyu dene"),
                )
                .clicked()
            {
                self.macro_test = Some(MacroTest::Waiting(
                    Instant::now() + Duration::from_secs(3),
                    routine.clone(),
                ));
            }
        }
        ui.separator();
        let prior_size = (placement.width_px, placement.height_px);
        let mut width = placement.width_px.unwrap_or_else(|| {
            (((M22_CONTENT_WIDTH - TILE_GAP * 5.0) / 5.0 + TILE_GAP) * f32::from(placement.span)
                - TILE_GAP)
                .round() as u16
        });
        let mut height = placement.height_px.unwrap_or(AUTO_HEIGHT as u16);
        ui.label("Buton boyutu");
        ui.horizontal(|ui| {
            if ui
                .add(
                    egui::DragValue::new(&mut width)
                        .range(80..=1500)
                        .speed(1)
                        .prefix("En ")
                        .suffix(" px"),
                )
                .changed()
            {
                placement.width_px = Some(width);
            }
            if ui
                .add(
                    egui::DragValue::new(&mut height)
                        .range(70..=660)
                        .speed(1)
                        .prefix("Boy ")
                        .suffix(" px"),
                )
                .changed()
            {
                placement.height_px = Some(height);
            }
        });
        if ui.small_button("Otomatik boyut").clicked() {
            placement.width_px = None;
            placement.height_px = None;
        }
        let prior_color = placement.color.clone();
        let mut tone = placement
            .color
            .as_deref()
            .and_then(parse_color)
            .unwrap_or(PANEL)
            .r();
        ui.horizontal(|ui| {
            if ui
                .add(egui::Slider::new(&mut tone, 0..=62).text("Buton tonu"))
                .changed()
            {
                placement.color = Some(format!("#{tone:02X}{tone:02X}{tone:02X}"));
            }
            if ui.small_button("Sıfırla").clicked() {
                placement.color = None;
            }
        });
        let prior_panel = placement.panel;
        egui::ComboBox::from_label("Panel")
            .selected_text(current_name)
            .show_ui(ui, |ui| {
                for (panel, name) in panel_names {
                    ui.selectable_value(&mut placement.panel, panel, name);
                }
            });
        if ui.button("Fotoğraf / GIF / MP4 ekle").clicked()
            && let Some(path) = rfd::FileDialog::new()
                .add_filter("Buton medyası", &["gif", "png", "jpg", "jpeg", "mp4"])
                .pick_file()
        {
            match art::save(&self.db, &path) {
                Ok(hash) => {
                    button.art = Some(hash);
                    self.message = "Medya eklendi. Kaydet ile telefona gönder.".into();
                    self.dirty = true;
                }
                Err(error) => self.message = error.to_string(),
            }
        }
        if button.art.is_some() && ui.small_button("Görseli kaldır").clicked() {
            button.art = None;
            self.dirty = true;
        }
        ui.separator();
        let valid =
            !button.label.trim().is_empty() && button.routine.as_ref().is_none_or(Routine::valid);
        let save = ui.add_enabled(
            valid && self.dirty,
            egui::Button::new("Kaydet ve telefona gönder"),
        );
        let clone = ui.button("Çoğalt").clicked();
        let delete = ui.button("Butonu sil").clicked();
        if prior_size != (placement.width_px, placement.height_px)
            || prior_color != placement.color
            || prior_panel != placement.panel
        {
            self.place(id, placement.clone());
        }
        if save.clicked()
            || (valid
                && label.lost_focus()
                && ui.input(|input| input.key_pressed(egui::Key::Enter)))
        {
            self.save();
        }
        if clone {
            let mut copy = self.deck.buttons[index].clone();
            copy.id = Uuid::new_v4();
            self.selected = Some(copy.id);
            self.place(copy.id, placement);
            self.deck.buttons.insert(index + 1, copy);
            self.save();
        }
        if delete {
            self.deck.buttons.remove(index);
            if let Some(layout) = &mut self.deck.layout {
                layout.placements.remove(&id);
            }
            self.selected = None;
            self.dirty = true;
            self.save();
        }
    }
}

impl eframe::App for Editor {
    fn update(&mut self, ctx: &egui::Context, _: &mut eframe::Frame) {
        ctx.request_repaint_after(Duration::from_millis(440));
        if !self.dirty
            && let Ok(next) = store::deck(&self.db)
            && next.revision != self.deck.revision
        {
            self.deck = next;
            self.undo.clear();
            self.redo.clear();
            if self.panel.is_some() && self.panel_name(self.panel) == "Ana panel" {
                self.panel = None;
            }
        }
        let (undo, redo) = ctx.input_mut(|input| {
            (
                input.consume_key(
                    egui::Modifiers::CTRL.plus(egui::Modifiers::SHIFT),
                    egui::Key::Z,
                ),
                input.consume_key(egui::Modifiers::CTRL, egui::Key::Y),
            )
        });
        if undo || redo {
            self.history(true);
        } else if !ctx.wants_keyboard_input()
            && ctx.input_mut(|input| input.consume_key(egui::Modifiers::CTRL, egui::Key::Z))
        {
            self.history(false);
        }
        let before = self.deck.clone();
        let stack_length = (self.undo.len(), self.redo.len());
        if self.dirty
            && ctx.input_mut(|input| input.consume_key(egui::Modifiers::CTRL, egui::Key::S))
        {
            self.save();
        }
        egui::TopBottomPanel::top("brand-bar").show(ctx, |ui| {
            ui.horizontal(|ui| {
                ui.add(egui::Image::new((self.logo.id(), Vec2::splat(29.0))));
                ui.label(egui::RichText::new("KenshixDeck").size(23.0).strong());
                if let Some(phone) = &mut self.phone {
                    ui.separator();
                    ui.selectable_value(&mut phone.opened, false, "Panel editörü");
                    ui.selectable_value(&mut phone.opened, true, "M22 gerçek ekranı");
                }
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    let save = egui::Button::new(
                        egui::RichText::new("Kaydet").color(Color32::BLACK).strong(),
                    )
                    .fill(ACCENT);
                    if ui
                        .add_enabled(self.dirty, save)
                        .on_hover_text("Ctrl+S")
                        .clicked()
                    {
                        self.save();
                    }
                });
            });
        });
        egui::TopBottomPanel::top("toolbar").show(ctx, |ui| {
            ui.horizontal_wrapped(|ui| {
                ui.label(egui::RichText::new("Panel").color(MUTED));
                if ui
                    .add_enabled(!self.undo.is_empty(), egui::Button::new("Geri al"))
                    .clicked()
                {
                    self.history(false);
                }
                if ui
                    .add_enabled(!self.redo.is_empty(), egui::Button::new("Yinele"))
                    .clicked()
                {
                    self.history(true);
                }

                egui::ComboBox::from_id_salt("panels")
                    .selected_text(self.panel_name(self.panel))
                    .show_ui(ui, |ui| {
                        ui.selectable_value(&mut self.panel, None, "Ana panel");
                        if let Some(layout) = &self.deck.layout {
                            for panel in &layout.panels {
                                ui.selectable_value(&mut self.panel, Some(panel.id), &panel.name);
                            }
                        }
                    });
                if ui.button("+ Profil").clicked() {
                    self.create_panel(PanelKind::Profile);
                }
                let is_profile = self
                    .deck
                    .layout
                    .iter()
                    .flat_map(|layout| &layout.panels)
                    .any(|panel| Some(panel.id) == self.panel && panel.kind == PanelKind::Profile);
                if ui
                    .add_enabled(is_profile, egui::Button::new("+ Sayfa"))
                    .clicked()
                {
                    self.create_panel(PanelKind::Page);
                }
                if ui
                    .add_enabled(self.panel.is_some(), egui::Button::new("+ Klasör"))
                    .clicked()
                {
                    self.create_panel(PanelKind::Folder);
                }
                if let Some(id) = self.panel {
                    if ui.button("Adını değiştir").clicked() {
                        self.rename = Some((id, self.panel_name(self.panel).into()));
                    }
                    if ui.button("Paneli sil").clicked() {
                        self.delete_panel(id);
                    }
                }
            });
            if let Some((id, name)) = &mut self.rename {
                let id = *id;
                let mut confirm = false;
                ui.horizontal(|ui| {
                    ui.add(egui::TextEdit::singleline(name).char_limit(60));
                    confirm = ui.button("Adı kaydet").clicked() && !name.trim().is_empty();
                });
                if confirm {
                    if let Some(panel) = self
                        .deck
                        .layout
                        .iter_mut()
                        .flat_map(|layout| &mut layout.panels)
                        .find(|panel| panel.id == id)
                    {
                        panel.name = name.trim().to_owned();
                        self.dirty = true;
                        self.save();
                    }
                    self.rename = None;
                }
            }
        });
        let live = self.phone.as_ref().is_some_and(|phone| phone.opened);
        if !live {
            egui::SidePanel::right("inspector")
                .default_width(280.0)
                .show(ctx, |ui| {
                    ui.heading("Buton ayarları");
                    ui.separator();
                    if self.selection.len() > 1 {
                        ui.label(format!("{} buton seçildi", self.selection.len()));
                        if ui.button("Seçilenleri aynı boyuta getir").clicked()
                            && let Some(id) = self.selected
                        {
                            let size = self.placement(id);
                            let selected: Vec<_> = self.selection.iter().copied().collect();
                            for id in selected {
                                let mut placement = self.placement(id);
                                placement.width_px = size.width_px;
                                placement.height_px = size.height_px;
                                placement.span = size.span;
                                self.place(id, placement);
                            }
                        }
                        ui.menu_button("Seçilileri başka panele taşı", |ui| {
                            let panels: Vec<_> = self
                                .deck
                                .layout
                                .iter()
                                .flat_map(|layout| layout.panels.iter())
                                .map(|panel| (Some(panel.id), panel.name.clone()))
                                .collect();
                            for (target, name) in
                                std::iter::once((None, "Ana panel".into())).chain(panels)
                            {
                                if ui.button(name).clicked() {
                                    let items: Vec<_> = self.selection.iter().copied().collect();
                                    for id in items {
                                        let mut placement = self.placement(id);
                                        placement.panel = target;
                                        self.place(id, placement);
                                    }
                                    self.selection.clear();
                                    self.panel = target;
                                    ui.close();
                                }
                            }
                        });
                        if ui.button("Seçimi temizle").clicked() {
                            self.selection.clear();
                        }
                        ui.separator();
                    }
                    egui::ScrollArea::vertical().show(ui, |ui| {
                        if let Some(id) = self.selected {
                            self.inspect_button(ui, id);
                        } else {
                            ui.label("Düzenlemek için önizlemeden bir buton seç.");
                        }
                    });
                });
        }
        if let Some(MacroTest::Waiting(until, _)) = &self.macro_test
            && Instant::now() >= *until
            && let Some(MacroTest::Waiting(_, routine)) = self.macro_test.take()
        {
            let id = Uuid::new_v4();
            match crate::routine::start(&mut self.db, id, routine) {
                Ok(status) if status == "running" => self.macro_test = Some(MacroTest::Running(id)),
                Ok(status) => self.message = format!("Makro başlatılamadı · {status}"),
                Err(error) => self.message = error.to_string(),
            }
        }
        egui::TopBottomPanel::bottom("state").show(ctx, |ui| {
            if let Some(test) = &self.macro_test {
                match test {
                    MacroTest::Waiting(until, _) => {
                        let until = *until;
                        ui.horizontal(|ui| {
                            ui.label(format!("Makro testi {} sn sonra başlayacak. Hedef pencereye geç.", until.saturating_duration_since(Instant::now()).as_secs() + 1));
                            if ui.button("Vazgeç").clicked() { self.macro_test = None; }
                        });
                    }
                    MacroTest::Running(id) => {
                        let id = *id;
                        let status = crate::routine::status(&self.db, id).unwrap_or_else(|_| "unknown".into());
                        ui.horizontal_wrapped(|ui| {
                            ui.label(match status.as_str() {
                                "ok" => "Makro testi tamamlandı",
                                "running" => "Makro testi çalışıyor",
                                "canceled" => "Makro testi iptal edildi",
                                _ => "Makro testi tamamlanamadı",
                            });
                            if status == "running" {
                                if ui.button("Durdur").clicked() { crate::routine::cancel(id); }
                            } else if ui.button("Sonucu kapat").clicked() { self.macro_test = None; }
                        });
                        if let Ok(steps) = store::macro_history(&self.db, id) {
                            ui.horizontal_wrapped(|ui| for (step, state, elapsed) in steps {
                                let outcome = match state.as_str() { "ok" => "Tamam", "skipped" => "Atlandı", _ => "Hata" };
                                ui.label(format!("Adım {step} · {outcome} · {elapsed} ms"));
                            });
                        }
                    }
                }
            }

            ui.label(if self.message.is_empty() {
                "Butonu taşı veya sağ alt köşesinden iki yönde boyutlandır. Kaydet, değişiklikleri telefona gönderir."
            } else {
                &self.message
            });
        });
        egui::CentralPanel::default().show(ctx, |ui| {
            if let Some(phone) = self.phone.as_mut().filter(|phone| phone.opened) {
                phone.show(ui);
            } else {
                egui::ScrollArea::vertical().show(ui, |ui| self.preview(ui));
            }
        });
        if stack_length == (self.undo.len(), self.redo.len()) {
            let mut before = before;
            before.revision = self.deck.revision;
            if serde_json::to_vec(&before).ok() != serde_json::to_vec(&self.deck).ok() {
                self.undo.push(before);
                if self.undo.len() > 32 {
                    self.undo.remove(0);
                }
                self.redo.clear();
            }
        }
    }
}

enum MacroTest {
    Waiting(Instant, Routine),
    Running(Uuid),
}

struct ArtPreview {
    frames: Vec<(egui::TextureHandle, u64)>,
    started: Instant,
    period_ms: u64,
}

impl ArtPreview {
    fn new(ui: &egui::Ui, id: &str, data: &[u8]) -> Option<Self> {
        fn texture(ui: &egui::Ui, name: String, image: DynamicImage) -> egui::TextureHandle {
            let image = image.thumbnail(160, 120).into_rgba8();
            let color = egui::ColorImage::from_rgba_unmultiplied(
                [image.width() as usize, image.height() as usize],
                image.as_raw(),
            );
            ui.ctx()
                .load_texture(name, color, egui::TextureOptions::LINEAR)
        }
        let mut frames = Vec::new();
        let mut period_ms = 0;
        if data.starts_with(b"GIF") {
            let decoder =
                image::codecs::gif::GifDecoder::new(BufReader::new(Cursor::new(data))).ok()?;
            for (index, frame) in decoder.into_frames().take(48).enumerate() {
                let frame = frame.ok()?;
                let (numerator, denominator) = frame.delay().numer_denom_ms();
                period_ms += u64::from((numerator / denominator.max(1)).clamp(33, 5000));
                frames.push((
                    texture(
                        ui,
                        format!("{id}-{index}"),
                        DynamicImage::ImageRgba8(frame.into_buffer()),
                    ),
                    period_ms,
                ));
            }
        } else {
            let decoded = image::ImageReader::new(Cursor::new(data))
                .with_guessed_format()
                .ok()?
                .decode()
                .ok()?;
            frames.push((texture(ui, id.into(), decoded), 0));
        }
        (!frames.is_empty()).then(|| Self {
            frames,
            started: Instant::now(),
            period_ms,
        })
    }

    fn frame(&self) -> (&egui::TextureHandle, Option<Duration>) {
        if self.frames.len() < 2 {
            return (&self.frames[0].0, None);
        }
        let position = (self.started.elapsed().as_millis() % u128::from(self.period_ms)) as u64;
        let index = self.frames.partition_point(|(_, end)| *end <= position);
        let (texture, end) = &self.frames[index];
        (texture, Some(Duration::from_millis(*end - position)))
    }
}

enum Tile {
    Panel(Panel),
    Button(Button),
    Create,
}

fn phone_tile(ui: &egui::Ui, rect: Rect, text: &str, action: &str, color: Color32, selected: bool) {
    let painter = ui.painter().with_clip_rect(rect);
    painter.rect_filled(rect, 11, color);
    let outline = if selected {
        egui::Stroke::new(2.0_f32, ACCENT)
    } else {
        egui::Stroke::new(1.0_f32, BORDER)
    };
    painter.rect_stroke(rect, 11, outline, egui::StrokeKind::Inside);
    let mark = match action {
        "Sayfa" => "▤",
        "Klasör" => "▱",
        "Ses Mikseri" => "≋",
        _ => "↗",
    };
    painter.text(
        rect.left_top() + Vec2::new(10.0, 9.0),
        egui::Align2::LEFT_TOP,
        mark,
        egui::FontId::proportional(17.0),
        ACCENT,
    );
    painter.text(
        rect.left_bottom() + Vec2::new(10.0, -25.0),
        egui::Align2::LEFT_BOTTOM,
        text,
        egui::FontId::proportional(15.0),
        Color32::WHITE,
    );
    painter.text(
        rect.left_bottom() + Vec2::new(10.0, -9.0),
        egui::Align2::LEFT_BOTTOM,
        action,
        egui::FontId::proportional(10.0),
        Color32::LIGHT_GRAY,
    );
}

fn parse_color(input: &str) -> Option<Color32> {
    let value = u32::from_str_radix(input.strip_prefix('#')?, 16).ok()?;
    let red = (value >> 16) & 255;
    let green = (value >> 8) & 255;
    let blue = value & 255;
    let shade = ((red * 21 + green * 72 + blue * 7) / 100).min(62);
    Some(Color32::from_gray(shade as u8))
}

const ACTIONS: &[(Action, &str)] = &[
    (Action::Mixer, "Ses Mikseri"),
    (Action::Spotify, "Spotify"),
    (Action::Macro, "Tuş, tıklama veya makro"),
    (Action::PlayPause, "Oynat / duraklat"),
    (Action::NextTrack, "Sonraki parça"),
    (Action::PreviousTrack, "Önceki parça"),
    (Action::TaskView, "Görev görünümü"),
    (Action::TaskManager, "Görev Yöneticisi"),
    (Action::ObsStatus, "OBS durumu"),
    (Action::ObsStartRecord, "OBS kaydı başlat"),
    (Action::ObsStopRecord, "OBS kaydı durdur"),
    (Action::ObsToggleRecord, "OBS kaydını aç / kapat"),
    (Action::ObsPauseRecord, "OBS kaydını duraklat"),
    (Action::ObsResumeRecord, "OBS kaydını sürdür"),
    (Action::ObsStartStream, "OBS yayını başlat"),
    (Action::ObsStopStream, "OBS yayını durdur"),
    (Action::ObsStartReplay, "Replay Buffer başlat"),
    (Action::ObsStopReplay, "Replay Buffer durdur"),
    (Action::ObsSaveReplay, "Replay Buffer kaydet"),
];

fn action_label(action: Action) -> &'static str {
    ACTIONS
        .iter()
        .find(|(item, _)| *item == action)
        .map_or("", |(_, label)| *label)
}

fn button_reference<'a>(
    ui: &mut egui::Ui,
    label: &str,
    value: &mut Option<Uuid>,
    options: impl Iterator<Item = (Uuid, &'a str)>,
) -> bool {
    let candidates: Vec<_> = options.collect();
    let previous = *value;
    let title = candidates
        .iter()
        .find(|(id, _)| Some(*id) == previous)
        .map_or("Yok", |(_, name)| *name);
    egui::ComboBox::from_label(label)
        .selected_text(title)
        .show_ui(ui, |ui| {
            ui.selectable_value(value, None, "Yok");
            for (id, name) in candidates {
                ui.selectable_value(value, Some(id), name);
            }
        });
    previous != *value
}

fn action_picker(
    ui: &mut egui::Ui,
    salt: impl std::hash::Hash,
    action: &mut Action,
    nested: bool,
) -> bool {
    let before = *action;
    egui::ComboBox::from_id_salt(salt)
        .selected_text(action_label(*action))
        .show_ui(ui, |ui| {
            for (item, label) in ACTIONS {
                if !nested
                    || !matches!(
                        item,
                        Action::Mixer | Action::ObsStatus | Action::Macro | Action::Spotify
                    )
                {
                    ui.selectable_value(action, *item, *label);
                }
            }
        });
    before != *action
}

fn routine_editor(ui: &mut egui::Ui, routine: &mut Routine) -> bool {
    let mut changed = ui
        .checkbox(&mut routine.continue_on_error, "Hata verirse devam et")
        .changed();
    changed |= trigger_editor(ui, &mut routine.trigger);
    let mut remove = None;
    let mut swap = None;
    let length = routine.steps.len();
    for (index, step) in routine.steps.iter_mut().enumerate() {
        ui.push_id(index, |ui| {
            ui.horizontal(|ui| {
                ui.label(format!("{}. adım", index + 1));
                if index > 0 && ui.small_button("↑").clicked() {
                    swap = Some((index, index - 1));
                }
                if index + 1 < length && ui.small_button("↓").clicked() {
                    swap = Some((index, index + 1));
                }
                if ui.small_button("Sil").clicked() {
                    remove = Some(index);
                }
            });
            match step {
                Step::Keys { keys, hold_ms } => {
                    let capture = ui.id().with("capture-keys");
                    let deadline = ui.ctx().data(|data| data.get_temp::<Instant>(capture));
                    if let Some(deadline) = deadline {
                        if let Some(captured) = crate::windows_host::capture_shortcut() {
                            *keys = captured;
                            changed = true;
                            ui.ctx().data_mut(|data| data.remove::<Instant>(capture));
                        } else if deadline <= Instant::now() {
                            ui.ctx().data_mut(|data| data.remove::<Instant>(capture));
                        } else {
                            ui.label("Klavye kısayoluna bas");
                            ui.ctx().request_repaint_after(Duration::from_millis(12));
                        }
                    } else if ui.button("Tuşları yakala").clicked() {
                        ui.ctx().data_mut(|data| {
                            data.insert_temp(capture, Instant::now() + Duration::from_secs(7))
                        });
                        ui.ctx().request_repaint_after(Duration::from_millis(12));
                    }
                    let mut entry = keys.join("+");
                    if ui
                        .add(
                            egui::TextEdit::singleline(&mut entry)
                                .hint_text("Ctrl+Shift+M")
                                .char_limit(70),
                        )
                        .changed()
                    {
                        *keys = entry.split('+').map(str::trim).map(str::to_owned).collect();
                        changed = true;
                    }
                    changed |= ui
                        .add(
                            egui::DragValue::new(hold_ms)
                                .range(0..=5000)
                                .suffix(" ms basılı tut"),
                        )
                        .changed();
                }
                Step::Text { text } => {
                    changed |= ui
                        .add(
                            egui::TextEdit::singleline(text)
                                .hint_text("Yazılacak metin")
                                .char_limit(512),
                        )
                        .changed();
                }
                Step::Launch { executable } => {
                    ui.menu_button("Başlat menüsünden seç", |ui| {
                        egui::ScrollArea::vertical()
                            .max_height(270.0)
                            .show(ui, |ui| {
                                for (name, shortcut) in crate::windows_host::start_menu_apps() {
                                    if ui.button(name).clicked() {
                                        *executable = shortcut
                                            .to_string_lossy()
                                            .trim_start_matches(r"\\?\")
                                            .to_owned();
                                        changed = true;
                                        ui.close();
                                    }
                                }
                            });
                    });
                    ui.horizontal(|ui| {
                        changed |= ui
                            .add(
                                egui::TextEdit::singleline(executable)
                                    .hint_text(r"C:\...\Uygulama.exe")
                                    .char_limit(400),
                            )
                            .changed();
                        if ui.button("EXE seç").clicked()
                            && let Some(path) = rfd::FileDialog::new()
                                .add_filter("Uygulama", &["exe"])
                                .pick_file()
                        {
                            *executable = path.to_string_lossy().into_owned();
                            changed = true;
                        }
                    });
                }
                Step::Click { x, y } => {
                    let sampling_id = ui.id().with("cursor-sample");
                    let deadline = ui.ctx().data(|data| data.get_temp::<Instant>(sampling_id));
                    if let Some(deadline) = deadline {
                        let remaining = deadline.saturating_duration_since(Instant::now());
                        if remaining.is_zero() {
                            ui.ctx()
                                .data_mut(|data| data.remove::<Instant>(sampling_id));
                            if let Ok((left, top)) = crate::windows_host::cursor_position() {
                                *x = left;
                                *y = top;
                                changed = true;
                            }
                        } else {
                            ui.label(format!(
                                "İmleci hedefe taşı · {} sn",
                                remaining.as_secs() + 1
                            ));
                            ui.ctx().request_repaint_after(Duration::from_millis(120));
                        }
                    } else if ui.button("İmleç konumunu al (3 sn)").clicked() {
                        ui.ctx().data_mut(|data| {
                            data.insert_temp(sampling_id, Instant::now() + Duration::from_secs(3))
                        });
                        ui.ctx().request_repaint_after(Duration::from_millis(120));
                    }
                    ui.horizontal(|ui| {
                        changed |= ui
                            .add(egui::DragValue::new(x).prefix("X ").range(-65536..=65536))
                            .changed();
                        changed |= ui
                            .add(egui::DragValue::new(y).prefix("Y ").range(-65536..=65536))
                            .changed();
                    });
                }
                Step::Action { action } => {
                    changed |= action_picker(ui, "step-action", action, true)
                }
                Step::Wait { ms } => {
                    changed |= ui
                        .add(
                            egui::DragValue::new(ms)
                                .range(1..=15000)
                                .suffix(" ms bekle"),
                        )
                        .changed()
                }
                Step::WaitObs { ms } => {
                    changed |= ui
                        .add(
                            egui::DragValue::new(ms)
                                .range(1..=15000)
                                .prefix("OBS için ")
                                .suffix(" ms"),
                        )
                        .changed()
                }
                Step::IfObs { skip } => {
                    changed |= ui
                        .add(
                            egui::DragValue::new(skip)
                                .range(1..=23)
                                .suffix(" adım atla (OBS kapalıysa)"),
                        )
                        .changed()
                }
            }
        });
    }
    if let Some(index) = remove {
        routine.steps.remove(index);
        changed = true;
    }
    if let Some((first, second)) = swap {
        routine.steps.swap(first, second);
        changed = true;
    }
    if routine.steps.len() < 24 {
        ui.menu_button("+ Makro adımı", |ui| {
            let choices = [
                (
                    "Tuş veya kısayol",
                    Step::Keys {
                        keys: vec!["A".into()],
                        hold_ms: 0,
                    },
                ),
                (
                    "Metin yaz",
                    Step::Text {
                        text: String::new(),
                    },
                ),
                ("Koordinata tıkla", Step::Click { x: 0, y: 0 }),
                (
                    "Uygulama aç",
                    Step::Launch {
                        executable: String::new(),
                    },
                ),
                ("Bekle", Step::Wait { ms: 500 }),
                (
                    "Hazır eylem",
                    Step::Action {
                        action: Action::PlayPause,
                    },
                ),
                ("OBS hazır olana kadar bekle", Step::WaitObs { ms: 5000 }),
                ("OBS hazır değilse adım atla", Step::IfObs { skip: 1 }),
            ];
            for (name, step) in choices {
                if ui.button(name).clicked() {
                    routine.steps.push(step);
                    changed = true;
                    ui.close();
                }
            }
        });
    }
    changed
}
fn trigger_editor(ui: &mut egui::Ui, trigger: &mut Option<crate::automation::Trigger>) -> bool {
    use crate::automation::Trigger;
    fn kind(trigger: &Option<Trigger>) -> &'static str {
        match trigger {
            None => "Elle çalıştır",
            Some(Trigger::HostStart) => "Host açıldığında",
            Some(Trigger::Daily { .. }) => "Her gün belirli saatte",
            Some(Trigger::AppStarted { .. }) => "Uygulama açıldığında",
            Some(Trigger::AppStopped { .. }) => "Uygulama kapandığında",
            Some(Trigger::ObsRecordingStarted) => "OBS kaydı başlayınca",
            Some(Trigger::ObsRecordingStopped) => "OBS kaydı durunca",
            Some(Trigger::DiskUsage { .. }) => "Disk doluluk uyarısı",
        }
    }
    let before = trigger.clone();
    egui::ComboBox::from_id_salt("trigger")
        .selected_text(kind(trigger))
        .show_ui(ui, |ui| {
            for candidate in [
                None,
                Some(Trigger::HostStart),
                Some(Trigger::Daily {
                    hour: 18,
                    minute: 0,
                }),
                Some(Trigger::AppStarted {
                    executable: String::new(),
                }),
                Some(Trigger::AppStopped {
                    executable: String::new(),
                }),
                Some(Trigger::ObsRecordingStarted),
                Some(Trigger::ObsRecordingStopped),
                Some(Trigger::DiskUsage { percent: 90 }),
            ] {
                let label = kind(&candidate);
                if ui.selectable_label(kind(trigger) == label, label).clicked()
                    && kind(trigger) != label
                {
                    *trigger = candidate;
                }
            }
        });
    match trigger {
        Some(Trigger::Daily { hour, minute }) => {
            ui.horizontal(|ui| {
                ui.add(egui::DragValue::new(hour).range(0..=23).prefix("Saat "));
                ui.add(egui::DragValue::new(minute).range(0..=59).prefix("Dakika "));
            });
        }
        Some(Trigger::AppStarted { executable } | Trigger::AppStopped { executable }) => {
            ui.add(
                egui::TextEdit::singleline(executable)
                    .hint_text("Uygulama adı, ör. notepad.exe")
                    .char_limit(64),
            );
        }
        Some(Trigger::DiskUsage { percent }) => {
            ui.add(egui::DragValue::new(percent).range(10..=100).prefix("%"));
        }
        _ => {}
    }
    before != *trigger
}
struct PhonePreview {
    adb: (String, String),
    opened: bool,
    actual_size: bool,
    pending: Option<std::sync::mpsc::Receiver<Result<egui::ColorImage>>>,
    texture: Option<egui::TextureHandle>,
    next: Instant,
    unavailable: bool,
}

impl PhonePreview {
    fn new(adb: (String, String)) -> Self {
        Self {
            adb,
            opened: false,
            actual_size: false,
            pending: None,
            texture: None,
            next: Instant::now(),
            unavailable: false,
        }
    }

    fn show(&mut self, ui: &mut egui::Ui) {
        ui.horizontal(|ui| {
            ui.heading("M22 · USB");
            ui.selectable_value(&mut self.actual_size, false, "Pencereye sığdır");
            ui.selectable_value(&mut self.actual_size, true, "1:1 piksel");
            if let Some(texture) = &self.texture {
                let [width, height] = texture.size();
                ui.label(format!("{width} × {height} px"));
            }
        });
        ui.separator();
        if let Some(receiver) = &self.pending {
            match receiver.try_recv() {
                Ok(Ok(image)) => {
                    self.texture = Some(ui.ctx().load_texture(
                        "m22-screen",
                        image,
                        egui::TextureOptions::NEAREST,
                    ));
                    self.unavailable = false;
                    self.pending = None;
                }
                Ok(Err(_)) | Err(std::sync::mpsc::TryRecvError::Disconnected) => {
                    self.texture = None;
                    self.unavailable = true;
                    self.pending = None;
                }
                Err(std::sync::mpsc::TryRecvError::Empty) => {}
            }
        }
        if self.pending.is_none() && Instant::now() >= self.next {
            let (sender, receiver) = std::sync::mpsc::sync_channel(1);
            self.pending = Some(receiver);
            self.next = Instant::now() + Duration::from_secs(2);
            let adb = self.adb.clone();
            let context = ui.ctx().clone();
            std::thread::spawn(move || {
                let _ = sender.send(phone_frame(&adb));
                context.request_repaint();
            });
        }
        if let Some(texture) = &self.texture {
            let px = texture.size_vec2();
            let area = ui.available_size();
            if self.actual_size {
                let physical = px / ui.ctx().pixels_per_point();
                egui::ScrollArea::both()
                    .id_salt("m22-pixel-scroll")
                    .show(ui, |ui| {
                        ui.add(
                            egui::Image::new((texture.id(), physical)).fit_to_exact_size(physical),
                        );
                    });
            } else {
                let scale = (area.x / px.x).min(area.y / px.y).max(0.01);
                let fitted = px * scale;
                let bounds = ui.allocate_space(area).1;
                ui.painter().rect_filled(bounds, 4, Color32::BLACK);
                ui.painter().image(
                    texture.id(),
                    Rect::from_center_size(bounds.center(), fitted),
                    Rect::from_min_max(Pos2::ZERO, Pos2::new(1.0, 1.0)),
                    Color32::WHITE,
                );
            }
        } else {
            ui.label(if self.unavailable {
                "USB bağlıyken ve KenshixDeck öndeyken görüntü alınır."
            } else {
                "M22 ekranı alınıyor…"
            });
        }
    }
}

fn phone_frame((adb, serial): &(String, String)) -> Result<egui::ColorImage> {
    use std::os::windows::process::CommandExt;
    let run = |args: &[&str]| {
        std::process::Command::new(adb)
            .args(["-s", serial])
            .args(args)
            .creation_flags(0x08000000)
            .output()
    };
    let foreground = || -> Result<bool> {
        let windows = run(&["shell", "dumpsys", "activity", "activities"])?;
        Ok(windows.status.success()
            && String::from_utf8_lossy(&windows.stdout)
                .lines()
                .any(|line| {
                    line.contains("topResumedActivity") && line.contains(" com.kenshix.deck/")
                }))
    };
    anyhow::ensure!(foreground()?, "M22'de KenshixDeck önde değil");
    let png = run(&["exec-out", "screencap", "-p"])?;
    anyhow::ensure!(
        png.status.success() && png.stdout.len() < 8 * 1024 * 1024 && foreground()?,
        "M22 görüntüsü alınamadı"
    );
    let image =
        image::load_from_memory_with_format(&png.stdout, image::ImageFormat::Png)?.into_rgba8();
    Ok(egui::ColorImage::from_rgba_unmultiplied(
        [image.width() as usize, image.height() as usize],
        image.as_raw(),
    ))
}
