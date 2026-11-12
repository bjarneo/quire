//! The quick switcher and command palette.

use rune::{Color, El, Key, KeyEvent, ScrollSpec, Span, Tx, col, el, rich, row, text_input};

use super::V;
use super::widgets::label;
use crate::app::{App, PalMode};
use crate::commands::{self, pal_data};
use crate::keys::k;
use crate::theme::{self, mono, sans};

pub fn palette(v: V) -> El {
    let p = v.p;
    let s = v.s;
    let Some(pal) = &s.palette else { return el() };
    let Some(d) = pal_data(s, v.dark) else { return el() };
    let cmds = d.mode == PalMode::Commands;
    let head = row()
        .key("pal-head")
        .h(53.0)
        .w_full()
        .gap(12.0)
        .px(18.0)
        .center_y()
        .border_sides([0.0, 0.0, 1.0, 0.0], p.line)
        .border_layout()
        .child(super::widgets::padded(if cmds { "run" } else { "open" }, mono(11.0, 400.0), p.acc, 7.0, 3.0, 0.0).radius(5.0).fill(p.soft))
        .child(
            text_input(&pal.q, sans(15.0, 400.0))
                .key("pal-input")
                .single_line()
                .px(2.0)
                .py(1.0)
                .grow(1.0)
                .min_w(0.0)
                .color(p.ink)
                .placeholder(if cmds { "Type a command…" } else { "Find a note by name…" }, theme::hex("#9a9184"))
                .caret(p.ink, p.sel)
                .on_edit(|s: &mut App| &mut s.palette.get_or_insert_with(|| crate::app::Palette { mode: PalMode::Files, q: rune::TextEdit::new(""), idx: 0 }).q)
                .on_input_change(|s: &mut App, _| {
                    if let Some(p) = &mut s.palette {
                        p.idx = 0;
                    }
                })
                .on_input_key(on_pal_key),
        );

    let mut list = col().key("pal-items").w_full().padding(6.0);
    for (i, it) in d.items.iter().enumerate() {
        let on = i == d.idx;
        let mut t = String::new();
        let mut spans = Vec::new();
        for (seg, hit) in &it.segs {
            let a = t.len();
            t.push_str(seg);
            if *hit {
                spans.push(Span { color: Some(p.acc), weight: Some(600.0), ..Span::new(a, t.len()) });
            }
        }
        let action = it.action.clone();
        list = list.child(
            row()
                .key(format!("pal-{i}"))
                .min_h(36.0)
                .w_full()
                .gap(12.0)
                .px(12.0)
                .center_y()
                .radius(7.0)
                .fill(if on { p.act } else { Color::TRANSPARENT })
                .role("option")
                .on_hover(move |s: &mut App| {
                    if let Some(p) = &mut s.palette {
                        if p.idx != i {
                            p.idx = i;
                        }
                    }
                })
                .on_press_tx(move |s: &mut App, tx| commands::run(s, action.clone(), tx))
                .child(rich(t, spans).font(sans(14.0, 400.0)).color(p.ink).truncate().grow(1.0).shrink().min_w(0.0))
                .child(label(it.sub.clone(), mono(11.5, 400.0), p.mute)),
        );
    }
    if d.items.is_empty() {
        list = list.child(label("Nothing matches.", sans(13.0, 400.0), p.mute).py(14.0).px(12.0));
    }
    let scroller = col().key("pal-scroll").w_full().max_h(392.0).min_h(0.0).scroll(ScrollSpec { stick_bottom: false }).child(list);

    let foot_style = mono(11.0, 400.0);
    let foot = row()
        .key("pal-foot")
        .h(35.0)
        .w_full()
        .gap(16.0)
        .px(18.0)
        .center_y()
        .border_sides([1.0, 0.0, 0.0, 0.0], p.line)
        .border_layout()
        .clip_rounded(0.0)
        .child(label("↑↓ select", foot_style, p.mute))
        .child(label(if cmds { "↵ run" } else { "↵ open" }, foot_style, p.mute))
        .child(label("esc close", foot_style, p.mute))
        .child(el().grow(1.0))
        .child(label(if cmds { format!("{} for notes", k(&["Mod", "O"])) } else { "type > for commands".into() }, foot_style, p.mute));

    let [a, b] = theme::palette_shadows();
    let panel = el().key("palette-frame").w_full().max_w(600.0).radius(12.0).fill(p.bg).box_shadow(a).box_shadow(b).child(
        col()
            .key("palette")
            .w_full()
            .radius(12.0)
            .clip_rounded(12.0)
            .role("dialog")
            .label(if cmds { "Commands" } else { "Quick switcher" })
            .modal_tx(true, |s: &mut App, tx| s.close_palette(tx))
            .child(head)
            .child(scroller)
            .child(foot),
    );
    col()
        .key("pal-layer")
        .pin(Some(0.0), Some(0.0), Some(0.0), Some(0.0))
        .paint_order(50)
        .fill(p.scrim_palette)
        .items_center()
        .pt((v.vh * 0.11).round())
        .px(20.0)
        .pb(20.0)
        .child(panel)
}

fn on_pal_key(s: &mut App, ev: &KeyEvent, tx: &mut Tx) -> bool {
    let dark = s.sys_dark.get();
    let dark = s.is_dark(dark);
    let Some(d) = pal_data(s, dark) else { return false };
    let n = d.items.len().max(1);
    match ev.key {
        Key::Down => {
            if let Some(p) = &mut s.palette {
                p.idx = (d.idx + 1) % n;
                tx.scroll_into_view(format!("pal-{}", p.idx));
            }
            true
        }
        Key::Up => {
            if let Some(p) = &mut s.palette {
                p.idx = (d.idx + n - 1) % n;
                tx.scroll_into_view(format!("pal-{}", p.idx));
            }
            true
        }
        Key::Enter => {
            if let Some(it) = d.items.into_iter().nth(d.idx) {
                commands::run(s, it.action, tx);
            }
            true
        }
        Key::Backspace => {
            let Some(p) = &mut s.palette else { return false };
            if p.q.text.is_empty() && p.mode == PalMode::Commands {
                p.mode = PalMode::Files;
                p.idx = 0;
                return true;
            }
            false
        }
        Key::Escape => {
            s.close_palette(tx);
            true
        }
        _ => false,
    }
}
