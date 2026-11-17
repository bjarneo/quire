//! Dictation: the toolbar's microphone starts the system's own speech to text. The words come
//! into the note as typed text, so the editor takes them as it takes keys.
//!
//! - macOS: AppKit's dictation, the same as Edit > Start Dictation.
//! - Omarchy: `omarchy-dictation toggle`, with the backend set in Setup > Defaults > Dictation.
//! - Other systems show no button.

use std::path::Path;
use std::process::{Command, Stdio};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// The dictation that this system has.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    Mac,
    Omarchy,
}

impl Kind {
    /// The button's tooltip.
    pub fn help(self) -> &'static str {
        match self {
            Kind::Mac => "Dictate",
            Kind::Omarchy => "Dictate. Select again to stop.",
        }
    }
}

/// The dictation button's state.
#[derive(Default)]
pub struct Dictation {
    /// `None` hides the button.
    pub kind: Option<Kind>,
    /// When macOS dictation starts. See `App::dictate`.
    pub start_at: Option<Instant>,
    /// The message of a failed start, for the notice line.
    error: Arc<Mutex<Option<String>>>,
    /// The starts so far. Tests count them, because a test starts nothing.
    pub runs: usize,
}

/// The time from a press to the start of macOS dictation. The frame after a press resets the
/// input method, and macOS dictation sends its words through the input method.
pub const MAC_DELAY: Duration = Duration::from_millis(100);

impl Dictation {
    pub fn new() -> Self {
        Dictation { kind: detect(), ..Default::default() }
    }

    /// Starts the system's dictation. On Omarchy, a second run stops it.
    pub fn run(&mut self, waker: Option<rune::Waker>) {
        self.runs += 1;
        if cfg!(test) {
            return;
        }
        match self.kind {
            Some(Kind::Mac) => {
                if let Err(e) = mac_start() {
                    self.fail(e);
                }
            }
            Some(Kind::Omarchy) => omarchy_toggle(self.error.clone(), waker),
            None => {}
        }
    }

    /// The message of a failed start, once.
    pub fn take_error(&self) -> Option<String> {
        self.error.lock().ok()?.take()
    }

    fn fail(&self, msg: String) {
        if let Ok(mut e) = self.error.lock() {
            *e = Some(msg);
        }
    }
}

/// Finds the system's dictation. Tests find none, so they never start a recording.
fn detect() -> Option<Kind> {
    if cfg!(test) {
        None
    } else if cfg!(target_os = "macos") {
        Some(Kind::Mac)
    } else if cfg!(target_os = "linux") && omarchy_ready() {
        Some(Kind::Omarchy)
    } else {
        None
    }
}

/// Omarchy binds its dictation keys only when the program of the chosen backend is installed
/// (`default/hypr/bindings/dictation.lua`). The button uses the same test.
fn omarchy_ready() -> bool {
    if !on_path("omarchy-dictation") {
        return false;
    }
    let Ok(out) = Command::new("omarchy-default-dictation").stdin(Stdio::null()).stderr(Stdio::null()).output() else {
        return false;
    };
    let text = String::from_utf8_lossy(&out.stdout);
    let backend = text.lines().next().unwrap_or("").trim();
    out.status.success() && valid_backend(backend) && on_path(backend)
}

/// A backend name as `omarchy-default-dictation` accepts it: `[a-z0-9][a-z0-9-]*`.
fn valid_backend(b: &str) -> bool {
    let ok = |c: char| c.is_ascii_lowercase() || c.is_ascii_digit();
    b.chars().next().is_some_and(ok) && b.chars().all(|c| ok(c) || c == '-')
}

fn on_path(name: &str) -> bool {
    std::env::var_os("PATH").is_some_and(|path| std::env::split_paths(&path).any(|d| is_program(&d.join(name))))
}

#[cfg(unix)]
fn is_program(p: &Path) -> bool {
    use std::os::unix::fs::PermissionsExt;
    p.metadata().is_ok_and(|m| m.is_file() && m.permissions().mode() & 0o111 != 0)
}

#[cfg(not(unix))]
fn is_program(p: &Path) -> bool {
    p.is_file()
}

/// Runs `omarchy-dictation toggle`. The backend types the words into the focused window. A
/// failure comes back as a message, and the waker shows it.
fn omarchy_toggle(error: Arc<Mutex<Option<String>>>, waker: Option<rune::Waker>) {
    let set = move |msg: String| {
        if let Ok(mut e) = error.lock() {
            *e = Some(msg);
        }
        if let Some(w) = &waker {
            w();
        }
    };
    let child = Command::new("omarchy-dictation").arg("toggle").stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::piped()).spawn();
    let child = match child {
        Ok(c) => c,
        Err(e) => return set(format!("Could not run omarchy-dictation: {e}")),
    };
    std::thread::spawn(move || {
        let Ok(out) = child.wait_with_output() else { return };
        if out.status.success() {
            return;
        }
        let err = String::from_utf8_lossy(&out.stderr);
        match err.lines().rev().map(str::trim).find(|l| !l.is_empty()) {
            Some(line) => set(format!("omarchy-dictation failed: {line}")),
            None => set("omarchy-dictation failed.".into()),
        }
    });
}

/// Sends `startDictation:` as the Edit menu does. A nil target sends it up from the first
/// responder, Rune's view, to the application, which starts dictation in the focused field.
#[cfg(target_os = "macos")]
fn mac_start() -> Result<(), String> {
    use objc2::runtime::{AnyObject, Bool};
    use objc2::{class, msg_send, sel};
    // SAFETY: Rune polls on the main thread, where AppKit must be called. The application object
    // lives as long as the process.
    let sent: Bool = unsafe {
        let app: *mut AnyObject = msg_send![class!(NSApplication), sharedApplication];
        let nil: *mut AnyObject = std::ptr::null_mut();
        msg_send![app, sendAction: sel!(startDictation:), to: nil, from: nil]
    };
    if sent.as_bool() { Ok(()) } else { Err("macOS did not start dictation. Turn on Dictation in System Settings > Keyboard.".into()) }
}

#[cfg(not(target_os = "macos"))]
fn mac_start() -> Result<(), String> {
    Err("This system has no macOS dictation.".into())
}

#[cfg(test)]
mod tests {
    use super::valid_backend;

    #[test]
    fn backend_names() {
        for ok in ["voxtype", "superwhisper", "hex", "a-1"] {
            assert!(valid_backend(ok), "{ok}");
        }
        for bad in ["", "-x", "Voxtype", "vox type", "../bin/sh", "a_b"] {
            assert!(!valid_backend(bad), "{bad}");
        }
    }
}
