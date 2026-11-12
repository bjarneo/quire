//! The settings sheet.

use rune::{Color, El, ScrollSpec, Tx, col, el, icon, row, text};

use super::V;
use super::widgets::{CLOSE_THIN, label, outline_button, seg, settings_seg, switch};
use super::{settings_mcp, settings_sync};
use crate::app::{App, SECS, Sec};
use crate::keys::{k, mod_click};
use crate::theme::{self, EDITOR_FONTS, mono, sans};

pub fn settings(v: V) -> El {
    let p = v.p;
    let s = v.s;
    let mut nav = col().key("set-nav").w(190.0).h_full().gap(2.0).py(18.0).px(10.0).fill(p.side).child(text("settings").font(mono(11.0, 400.0)).color(p.mute).px(10.0).pb(10.0));
    for (id, lbl) in SECS {
        let on = s.set_sec == id;
        nav = nav.child(
            row()
                .key(format!("nav-{lbl}"))
                .h(30.0)
                .px(10.0)
                .center_y()
                .radius(6.0)
                .fill(if on { p.act } else { Color::TRANSPARENT })
                .hover_group()
                .role("tab")
                .on_press(move |s: &mut App| s.set_sec = id)
                .child(label(lbl, sans(13.5, if on { 500.0 } else { 400.0 }), if on { p.ink } else { p.ink2 }).group_color(p.ink)),
        );
    }
    let title = SECS.iter().find(|x| x.0 == s.set_sec).map_or("", |x| x.1);
    let head = row()
        .key("set-head")
        .h(58.0)
        .w_full()
        .pl(32.0)
        .pr(16.0)
        .center_y()
        .justify_between()
        .child(label(title, sans(17.0, 600.0), p.ink))
        .child(
            row()
                .key("set-close")
                .size(28.0, 28.0)
                .center()
                .radius(6.0)
                .hover(p.hover)
                .hover_group()
                .label("Close")
                .help("Close")
                .on_press(|s: &mut App| s.settings_open = false)
                .child(icon(&CLOSE_THIN, 10.0).color(p.mute).group_color(p.ink)),
        );
    let body = match s.set_sec {
        Sec::Appearance => appearance(v),
        Sec::Editor => editor(v),
        Sec::Files => files(v),
        Sec::Sync => settings_sync::sync(v),
        Sec::Mcp => settings_mcp::mcp(v),
        Sec::Keys => keys(v),
    };
    let content = col()
        .key("set-content")
        .grow(1.0)
        .min_w(0.0)
        .h_full()
        .child(head)
        .child(col().key("set-scroll").grow(1.0).min_h(0.0).w_full().scroll(ScrollSpec { stick_bottom: false }).child(col().key(format!("set-body-{title}")).w_full().px(32.0).pb(28.0).child(body)));
    let [a, b] = theme::settings_shadows();
    // The shadow sits on an outer box: a rounded clip would cut it off.
    let panel = el()
        .key("settings-frame")
        .w_full()
        .max_w(780.0)
        .h(580.0f32.min(v.vh - 48.0))
        .radius(12.0)
        .fill(p.bg)
        .box_shadow(a)
        .box_shadow(b)
        .child(
            row()
                .key("settings")
                .w_full()
                .h_full()
                .radius(12.0)
                .clip_rounded(12.0)
                .role("dialog")
                .label("Settings")
                .modal(true, |s: &mut App| s.settings_open = false)
                .child(nav)
                .child(content),
        );
    col()
        .key("set-layer")
        .pin(Some(0.0), Some(0.0), Some(0.0), Some(0.0))
        .paint_order(40)
        .fill(p.scrim_settings)
        .padding(24.0)
        .center()
        .child(panel)
}

/// The width of the settings body, for grids that fit columns to it.
pub fn body_width(v: V) -> f32 {
    (v.vw - 48.0).min(780.0) - 190.0 - 64.0
}

/// A settings row: a title and description on the left, a control on the right.
pub fn row_(v: V, key: &str, title: &str, desc: Option<&str>, control: El) -> El {
    let p = v.p;
    let mut left = col().key("text").grow(1.0).shrink().min_w(0.0).child(label(title, sans(14.0, 500.0), p.ink));
    if let Some(d) = desc {
        left = left.child(desc_text(v, d));
    }
    row().key(key).w_full().gap(24.0).py(16.0).center_y().border_sides([1.0, 0.0, 0.0, 0.0], p.line).border_layout().child(left).child(control)
}

pub fn desc_text(v: V, d: &str) -> El {
    text(d).font(sans(12.5, 400.0).lh(12.5 * 1.45)).color(v.p.mute).wrap().mt(3.0).w_full()
}

/// A block with a title and description over its own content.
pub fn block(v: V, key: &str, title: &str, desc: Option<&str>) -> El {
    let p = v.p;
    let mut c = col().key(key).w_full().py(16.0).border_sides([1.0, 0.0, 0.0, 0.0], p.line).border_layout().child(label(title, sans(14.0, 500.0), p.ink));
    if let Some(d) = desc {
        c = c.child(desc_text(v, d));
    }
    c
}

fn appearance(v: V) -> El {
    let set = v.s.settings();
    let theme_seg = |id: &'static str, l: &str| seg(l, set.theme == id, move |s, _| s.set_theme(id));
    let prev = |id: &'static str, l: &str| seg(l, set.preview_font == id, move |s, _| s.settings_mut().preview_font = id.into());
    col()
        .key("appearance")
        .w_full()
        .child(row_(v, "theme", "Theme", Some(if theme::omarchy().is_some() { "Match system follows your Omarchy theme, colours included." } else { "Match system follows your OS setting." }), settings_seg(v.p, "theme-seg", vec![theme_seg("light", "Light"), theme_seg("dark", "Dark"), theme_seg("system", "Match system")])))
        .child(row_(v, "prev-font", "Preview typeface", Some("Used when reading a note in preview."), settings_seg(v.p, "prev-seg", vec![prev("serif", "Serif"), prev("sans", "Sans")])))
}

fn editor(v: V) -> El {
    let p = v.p;
    let set = v.s.settings();
    let mut cards = row().key("font-cards").w_full().gap(10.0).mt(14.0);
    for f in &EDITOR_FONTS {
        let on = set.font == f.id;
        let id = f.id;
        let mut b = col()
            .key(format!("font-{id}"))
            .grow(1.0)
            .min_w(0.0)
            .gap(8.0)
            .pt(12.0)
            .px(14.0)
            .pb(14.0)
            .radius(9.0)
            .fill(p.bg)
            .border(1.0, if on { p.acc } else { p.line })
            .border_layout()
            .role("radio")
            .checked(on)
            .label(f.label)
            .on_press_tx(move |s: &mut App, tx: &mut Tx| {
                s.settings_mut().font = id.into();
                tx.set_font_families("Instrument Sans", theme::editor_font(id).name);
            })
            .child(label(f.label, sans(11.5, 400.0), if on { p.acc } else { p.mute }))
            .child(label("## Field notes", rune::TextStyle::new(15.0, 400.0).family(f.family).lh(22.5), p.ink))
            .child(label("Rain until 14:00.", rune::TextStyle::new(13.0, 400.0).family(f.family).lh(19.5), p.ink2));
        if on {
            b = b.box_shadow(rune::Shadow { x: 0.0, y: 0.0, blur: 0.0, spread: 1.0, color: p.acc, inset: false });
        }
        cards = cards.child(b);
    }
    let size = set.size;
    let step = |key: &str, t: &str, d: i32| {
        row()
            .key(key)
            .size(28.0, 26.0)
            .center()
            .radius(6.0)
            .hover(p.bg)
            .role("button")
            .label(if d < 0 { "Smaller" } else { "Larger" })
            .on_press(move |s: &mut App| {
                let n = (s.settings().size as i32 + d).clamp(12, 24) as u32;
                s.settings_mut().size = n;
            })
            .child(label(t, sans(15.0, 400.0), p.ink2))
    };
    let size_ctl = row()
        .key("size-ctl")
        .gap(2.0)
        .padding(2.0)
        .radius(8.0)
        .fill(p.side)
        .center_y()
        .child(step("size-down", "−", -1))
        .child(row().key("size-label").min_w(48.0).justify_center().child(label(format!("{size} px"), mono(12.0, 400.0), p.ink)))
        .child(step("size-up", "+", 1));
    let width = |id: &'static str, l: &str| seg(l, set.width == id, move |s, _| s.settings_mut().width = id.into());
    let mut c = col()
        .key("editor-set")
        .w_full()
        .child(block(v, "font", "Editor font", Some("Monospaced, so Markdown syntax lines up as you type.")).child(cards))
        .child(row_(v, "size", "Font size", None, size_ctl))
        .child(row_(v, "width", "Line width", Some("Readable keeps lines to about 72 characters."), settings_seg(p, "width-seg", vec![width("readable", "Readable"), width("full", "Full width")])));
    type Get = fn(&crate::store::Settings) -> bool;
    type Set = fn(&mut crate::store::Settings);
    let toggles: [(&str, &str, &str, Get, Set); 4] = [
        ("spell", "Spell check", "Underline misspelled words while you type.", |s| s.spellcheck, |s| s.spellcheck = !s.spellcheck),
        ("lists", "Continue lists", "Pressing Enter in a list starts the next item.", |s| s.continue_lists, |s| s.continue_lists = !s.continue_lists),
        ("dim", "Dim other paragraphs in focus mode", "Only the paragraph you are writing stays at full strength.", |s| s.dim_focus, |s| s.dim_focus = !s.dim_focus),
        ("typewriter", "Typewriter scrolling in focus mode", "Keeps the line you are typing near the middle of the window.", |s| s.typewriter, |s| s.typewriter = !s.typewriter),
    ];
    for (key, t, d, get, flip) in toggles {
        c = c.child(row_(v, key, t, Some(d), switch(p, &format!("{key}-switch"), get(set), t, move |s, _| flip(s.settings_mut()))));
    }
    c
}

fn files(v: V) -> El {
    let p = v.p;
    let s = v.s;
    let path = s.vault.as_ref().map_or(String::new(), |x| crate::store::tilde(&x.path));
    let loc = row()
        .key("location")
        .w_full()
        .gap(24.0)
        .py(16.0)
        .center_y()
        .border_sides([1.0, 0.0, 0.0, 0.0], p.line)
        .border_layout()
        .child(
            col()
                .key("text")
                .grow(1.0)
                .shrink()
                .min_w(0.0)
                .child(label("Folder location", sans(14.0, 500.0), p.ink))
                .child(text(path).font(mono(12.0, 400.0)).color(p.mute).wrap().wrap_anywhere().mt(4.0).w_full()),
        )
        .child(outline_button(p, "switch", "Switch folder…", 30.0, |s, tx| s.go_welcome(tx)));
    let cur = s.settings().new_folder.clone();
    let mut opts = row().key("folder-opts").w_full().flex_wrap().gap(6.0).mt(12.0);
    let mut list = vec![(String::new(), "Folder root".to_string())];
    list.extend(s.folder_list().into_iter().map(|f| (f.clone(), format!("{f}/"))));
    for (i, (val, lbl)) in list.into_iter().enumerate() {
        let on = cur == val;
        let mut b = row()
            .key(format!("nf-{i}"))
            .h(28.0)
            .px(12.0)
            .center()
            .radius(7.0)
            .border(1.0, p.line)
            .border_layout()
            .fill(if on { p.bg } else { Color::TRANSPARENT })
            .role("radio")
            .checked(on)
            .on_press(move |s: &mut App| s.settings_mut().new_folder = val.clone())
            .child(label(lbl, mono(12.0, 400.0), if on { p.ink } else { p.mute }));
        if on {
            for sh in theme::seg_shadows() {
                b = b.box_shadow(sh);
            }
        }
        opts = opts.child(b);
    }
    let upd = s.settings().update_links;
    col()
        .key("files-set")
        .w_full()
        .child(loc)
        .child(block(v, "new-in", "New notes go in", Some("Notes created from links and the switcher land here too.")).child(opts))
        .child(row_(
            v,
            "links",
            "Update links when renaming",
            Some("Rewrites [[links]] in other notes so they still point to the renamed file."),
            switch(p, "links-switch", upd, "Update links when renaming", |s, _| {
                let u = s.settings().update_links;
                s.settings_mut().update_links = !u;
            }),
        ))
        .child(row_(v, "sample", "Sample notes", Some("Put Field Notes back the way it started."), outline_button(p, "restore", "Restore", 30.0, |s, tx| s.reset_sample(tx))))
}

fn keys(v: V) -> El {
    let p = v.p;
    let rows: [(&str, &[&str]); 10] = [
        ("Quick switcher", &["Mod", "O"]),
        ("Command palette", &["Mod", "P"]),
        ("New note", &["Mod", "N"]),
        ("Source / preview", &["Mod", "E"]),
        ("Search all notes", &["Mod", "Shift", "F"]),
        ("Focus mode", &["Mod", "."]),
        ("Toggle sidebar", &["Mod", "\\"]),
        ("Settings", &["Mod", ","]),
        ("Bold", &["Mod", "B"]),
        ("Italic", &["Mod", "I"]),
    ];
    let mut c = col().key("keys").w_full();
    let mut all: Vec<(String, String)> = rows.iter().map(|(l, ks)| (l.to_string(), k(ks))).collect();
    all.push(("Follow a [[link]]".into(), mod_click()));
    for (i, (l, kb)) in all.into_iter().enumerate() {
        c = c.child(
            row()
                .key(format!("key-{i}"))
                .w_full()
                .gap(16.0)
                .py(11.0)
                .center_y()
                .justify_between()
                .border_sides([1.0, 0.0, 0.0, 0.0], p.line)
                .border_layout()
                .child(label(l, sans(13.5, 400.0), p.ink))
                .child(super::widgets::padded(kb, mono(11.5, 400.0), p.ink2, 7.0, 2.0, 2.0).radius(5.0).fill(p.bg).border_sides([1.0, 1.0, 2.0, 1.0], p.line).border_layout()),
        );
    }
    c
}
