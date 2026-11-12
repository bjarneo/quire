//! Locking notes in the app: the lock dialog, unlock, lock again and remove the password.

use rune::Tx;

use crate::app::{App, Mode, Unlocked};
use crate::lock;

impl App {
    /// Seals an unlocked note's new text into the folder copy, to save soon.
    pub(crate) fn store_unlocked(&mut self, rel: &str, text: String) {
        let Some(u) = self.unlocked.get_mut(rel) else { return };
        if u.text == text {
            return;
        }
        u.text = text;
        let sealed = lock::seal(&u.key, &u.text);
        if let Some(v) = &mut self.vault {
            v.files.insert(rel.to_string(), sealed);
        }
        self.mark_dirty_pub(rel);
    }

    /// An unlocked note changed outside the app, through sync or another app. The new text
    /// opens with the key in memory, or the note locks again.
    pub(crate) fn outside_locked_text(&mut self, rel: &str, text: String) {
        let opened = match self.unlocked.get(rel) {
            Some(u) if lock::is_locked(&text) => lock::open_with(&u.key, &text).map(Some),
            Some(_) => Ok(None),
            None => return,
        };
        if let Some(v) = &mut self.vault {
            v.files.insert(rel.to_string(), text.clone());
        }
        match opened {
            Ok(Some(plain)) => {
                if let Some(u) = self.unlocked.get_mut(rel) {
                    u.text = plain.clone();
                }
                if let Some(e) = self.edits.get_mut(rel) {
                    let (a, b) = (e.anchor.min(plain.len()), e.focus.min(plain.len()));
                    e.replace_text(plain);
                    e.set_selection(a, b);
                }
            }
            // The password was removed elsewhere: the note is plain now.
            Ok(None) => {
                self.unlocked.remove(rel);
                if let Some(e) = self.edits.get_mut(rel) {
                    e.replace_text(text);
                }
            }
            Err(_) => {
                self.unlocked.remove(rel);
                self.edits.remove(rel);
                self.notice = Some(format!("{} has another password now. Unlock it again.", crate::md::base(rel)));
            }
        }
    }

    /// Opens the dialog that asks for a new password, for the active note.
    pub fn ask_lock(&mut self, tx: &mut Tx) {
        let Some(a) = self.active.clone() else { return };
        if self.note_unlocked(&a) {
            self.relock();
            return;
        }
        if self.note_locked(&a) {
            return;
        }
        self.fields.remove("lock-pass");
        self.fields.remove("lock-repeat");
        self.lock_dialog = Some(String::new());
        tx.focus("lock-pass", false);
    }

    /// Locks the active note with the password in the dialog.
    pub fn lock_note(&mut self, tx: &mut Tx) {
        let pass = self.fields.get("lock-pass").map(|e| e.text.clone()).unwrap_or_default();
        let repeat = self.fields.get("lock-repeat").map(|e| e.text.clone()).unwrap_or_default();
        if pass.chars().count() < lock::MIN_PASSWORD {
            self.lock_dialog = Some(format!("The password needs at least {} characters.", lock::MIN_PASSWORD));
            return;
        }
        if pass != repeat {
            self.lock_dialog = Some("The two passwords are not the same.".into());
            return;
        }
        let Some(a) = self.active.clone() else { return };
        let text = self.active_text().to_string();
        let key = lock::new_key(&pass);
        self.lock_dialog = None;
        self.fields.remove("lock-pass");
        self.fields.remove("lock-repeat");
        // The note stays open after the lock. It locks when its tab closes or with "Lock now".
        self.unlocked.insert(a.clone(), Unlocked { key, text: String::new() });
        self.store_unlocked(&a, text);
        self.flush_notes();
        self.focus_editor(tx);
    }

    /// Locks the active note again: its plain text leaves memory.
    pub fn relock(&mut self) {
        let Some(a) = self.active.clone() else { return };
        if !self.unlocked.contains_key(&a) {
            return;
        }
        self.flush_notes();
        self.unlocked.remove(&a);
        self.edits.remove(&a);
        self.unlock_error = None;
        self.fields.remove("unlock-pass");
    }

    /// Opens the active locked note with the password in the lock panel.
    pub fn unlock(&mut self, tx: &mut Tx) {
        let Some(a) = self.active.clone() else { return };
        let Some(text) = self.files().get(&a).cloned() else { return };
        let pass = self.fields.get("unlock-pass").map(|e| e.text.clone()).unwrap_or_default();
        match lock::open(&text, &pass) {
            Ok((plain, key)) => {
                self.unlock_error = None;
                self.fields.remove("unlock-pass");
                self.edits.remove(&a);
                self.unlocked.insert(a, Unlocked { key, text: plain });
                self.rebuild_names_pub();
                self.focus_editor(tx);
            }
            Err(e) => self.unlock_error = Some(e),
        }
    }

    /// Saves the active unlocked note as plain text again.
    pub fn remove_password(&mut self, tx: &mut Tx) {
        let Some(a) = self.active.clone() else { return };
        let Some(u) = self.unlocked.remove(&a) else { return };
        if let Some(v) = &mut self.vault {
            v.files.insert(a.clone(), u.text);
        }
        self.mark_dirty_pub(&a);
        self.flush_notes();
        self.rebuild_names_pub();
        if self.mode == Mode::Source {
            self.focus_editor(tx);
        }
    }
}
