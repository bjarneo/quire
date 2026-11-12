//! The right panel (outline and backlinks) and the status bar.

use rune::{Color, El, ScrollSpec, col, el, row, text};

use super::V;
use super::editor::{group, link_at_caret};
use super::widgets::label;
use crate::app::{App, Mode};
use crate::keys::mod_click;
use crate::md;
use crate::theme::{mono, sans};

pub fn outline(v: V) -> El {
    let p = v.p;
    let s = v.s;
    let doc = s.active_text();
    let heads = md::headings(doc);
    let caret = s.active.as_ref().and_then(|a| s.edits.get(a)).map_or(0, |e| e.focus.min(doc.len()));
    let (line, _) = md::line_col(doc, caret);
    let cur = if s.mode == Mode::Source {
        heads.iter().rposition(|h| h.line <= line)
    } else if heads.is_empty() {
        None
    } else {
        Some(v.prev_head.min(heads.len() - 1))
    };
    let min_lv = heads.iter().map(|h| h.level).min().unwrap_or(6);
    let mut c = col().key("outline-list").w_full().pt(24.0).px(10.0).pb(24.0).child(text("outline").font(mono(11.0, 400.0)).color(p.mute).px(10.0).pb(8.0));
    for (i, h) in heads.iter().enumerate() {
        let on = cur == Some(i);
        let line = h.line;
        c = c.child(
            row()
                .key(format!("ol-{i}"))
                .gap(8.0)
                .pt(4.0)
                .pb(4.0)
                .pr(8.0)
                .pl(8.0 + (h.level - min_lv) as f32 * 14.0)
                .center_y()
                .radius(6.0)
                .hover(p.hover)
                .role("button")
                .on_press_tx(move |s: &mut App, tx| s.jump(line, tx))
                .child(el().key("dot").size(5.0, 5.0).radius(2.5).fill(if on { p.acc } else { Color::TRANSPARENT }))
                .child(text(h.text.clone()).font(sans(13.0, if on { 500.0 } else { 400.0 }).lh(13.0 * 1.35)).color(if on { p.ink } else { p.ink2 }).truncate().shrink().min_w(0.0)),
        );
    }
    if heads.is_empty() {
        c = c.child(label("No headings in this note.", sans(12.5, 400.0), p.mute).py(2.0).px(10.0));
    }
    c = c.child(text("linked from").font(mono(11.0, 400.0)).color(p.mute).pt(26.0).px(10.0).pb(8.0));
    let mut back = s.active.as_ref().map(|a| md::backlinks(s.files(), a)).unwrap_or_default();
    if let Some(vault) = &s.vault {
        back.sort_by_key(|(p, _)| (vault.born.get(p).copied().unwrap_or(u128::MAX), p.clone()));
    }
    let name_style = sans(13.0, 400.0);
    let small = mono(10.5, 400.0);
    let shift = super::baseline_shift(name_style, small);
    for (i, (bp, _)) in back.iter().enumerate() {
        let path = bp.clone();
        c = c.child(
            row()
                .key(format!("bl-{i}"))
                .gap(8.0)
                .py(4.0)
                .px(10.0)
                .radius(6.0)
                .hover(p.hover)
                .hover_group()
                .role("button")
                .on_press_tx(move |s: &mut App, tx| s.open_file(&path, None, tx))
                .child(text(md::base(bp)).font(name_style).color(p.ink2).group_color(p.ink).truncate().shrink().min_w(0.0))
                .child(text(md::dir(bp)).font(small).color(p.mute).truncate().grow(1.0).shrink().min_w(0.0).mt(shift)),
        );
    }
    if back.is_empty() {
        c = c.child(text("No notes link here yet.").font(sans(12.5, 400.0).lh(12.5 * 1.45)).color(p.mute).py(2.0).px(10.0));
    }
    col().key("outline").w(236.0).h_full().min_h(0.0).border_sides([0.0, 0.0, 0.0, 1.0], p.line).border_layout().scroll(ScrollSpec { stick_bottom: false }).child(c)
}

pub fn status(v: V) -> El {
    let p = v.p;
    let s = v.s;
    let doc = s.active_text();
    let edit = s.active.as_ref().and_then(|a| s.edits.get(a));
    let (a, b) = edit.map_or((0, 0), |e| (e.anchor.min(e.focus).min(doc.len()), e.anchor.max(e.focus).min(doc.len())));
    let pos = edit.map_or(0, |e| e.focus.min(doc.len()));
    let (line, ls) = md::line_col(doc, pos);
    let col_ = doc[ls..pos].chars().count();
    let sel = &doc[a..b];
    let style = mono(11.5, 400.0);
    let preview = s.mode == Mode::Preview;
    let pos_label = if preview {
        "preview".to_string()
    } else if !sel.is_empty() {
        format!("{} selected", group(sel.chars().count()))
    } else {
        format!("Ln {}, Col {}", line + 1, col_ + 1)
    };
    let wc = md::words(doc);
    let word_label = if !sel.is_empty() {
        format!("{} of {} words", group(md::words(sel)), group(wc))
    } else {
        format!("{} {}", group(wc), if wc == 1 { "word" } else { "words" })
    };
    let read = format!("{} min read", ((wc as f32 / 230.0).round() as usize).max(1));
    let mut r = row().key("status").h(29.0).w_full().gap(18.0).px(16.0).center_y().border_sides([1.0, 0.0, 0.0, 0.0], p.line).border_layout().clip_rounded(0.0).child(label(pos_label, style, p.mute));
    if !preview {
        if let Some(t) = link_at_caret(doc, pos) {
            let verb = if s.names.resolve(&t).is_some() { " to open “" } else { " to create “" };
            r = r.child(text(format!("{}{verb}{t}”", mod_click())).font(style).color(p.acc).truncate().shrink().min_w(0.0));
        }
    }
    r = r.child(el().grow(1.0)).child(label(word_label, style, p.mute)).child(label(read, style, p.mute));
    if let Some((lbl, busy, failed)) = s.sync_label() {
        let dot = if failed { p.err } else if busy { p.acc } else { p.mark };
        let mut sync = row()
            .key("sync-state")
            .role("button")
            .on_press(|s: &mut App| {
                s.open_settings();
                s.set_sec = crate::app::Sec::Sync;
            })
            .gap(6.0)
            .center_y().child(el().size(6.0, 6.0).radius(3.0).fill(dot)).child(label(lbl, style, if failed { p.err } else { p.mute }));
        if let Some(e) = &s.sync.error {
            sync = sync.help(e.clone());
        }
        r = r.child(sync);
    }
    r
}
