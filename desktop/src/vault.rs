//! A notes folder on disk: reading it, and a writer thread that keeps disk work off the UI thread.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::mpsc::{Sender, channel};
use std::sync::{Arc, Mutex};

#[derive(Clone, Debug)]
pub struct Vault {
    pub name: String,
    pub path: PathBuf,
    /// Folders relative to the root, `/`-separated, including empty ones.
    pub folders: Vec<String>,
    /// Note path relative to the root (`Drafts/On slow software.md`) to its text.
    pub files: BTreeMap<String, String>,
    /// Creation order of each note (file birth time, else modification time), for lists the
    /// design keeps in the order notes were made, such as backlinks.
    pub born: std::collections::HashMap<String, u128>,
}

fn skip(name: &str) -> bool {
    name.starts_with('.') || name == "node_modules"
}

/// Reads every `.md` file under `root`. Hidden folders (`.git`, `.trash`) stay out.
pub fn load(root: &Path) -> std::io::Result<Vault> {
    let mut files = BTreeMap::new();
    let mut born = std::collections::HashMap::new();
    let mut folders = Vec::new();
    let mut stack = vec![(root.to_path_buf(), String::new())];
    let mut count = 0usize;
    while let Some((dir, rel)) = stack.pop() {
        let rd = match std::fs::read_dir(&dir) {
            Ok(r) => r,
            Err(e) if rel.is_empty() => return Err(e),
            Err(_) => continue,
        };
        for entry in rd.flatten() {
            let name = entry.file_name().to_string_lossy().into_owned();
            if skip(&name) {
                continue;
            }
            let Ok(ft) = entry.file_type() else { continue };
            let child = if rel.is_empty() { name.clone() } else { format!("{rel}/{name}") };
            if ft.is_dir() {
                folders.push(child.clone());
                stack.push((entry.path(), child));
            } else if ft.is_file() && name.to_lowercase().ends_with(".md") {
                count += 1;
                if count > 20_000 {
                    continue;
                }
                if let Ok(t) = std::fs::read_to_string(entry.path()) {
                    let meta = entry.metadata().ok();
                    let when = meta.and_then(|m| m.created().or_else(|_| m.modified()).ok());
                    let ns = when.and_then(|w| w.duration_since(std::time::UNIX_EPOCH).ok()).map_or(0, |d| d.as_nanos());
                    born.insert(child.clone(), ns);
                    files.insert(child, t.replace("\r\n", "\n"));
                }
            }
        }
    }
    folders.sort();
    let name = root.file_name().map_or_else(|| root.to_string_lossy().into_owned(), |n| n.to_string_lossy().into_owned());
    Ok(Vault { name, path: root.to_path_buf(), folders, files, born })
}

pub fn count_notes(root: &Path) -> usize {
    let mut n = 0;
    let mut stack = vec![root.to_path_buf()];
    while let Some(d) = stack.pop() {
        let Ok(rd) = std::fs::read_dir(&d) else { continue };
        for e in rd.flatten() {
            let name = e.file_name().to_string_lossy().into_owned();
            if skip(&name) {
                continue;
            }
            match e.file_type() {
                Ok(t) if t.is_dir() => stack.push(e.path()),
                Ok(t) if t.is_file() && name.to_lowercase().ends_with(".md") => n += 1,
                _ => {}
            }
        }
        if n > 99_999 {
            break;
        }
    }
    n
}

#[derive(Debug)]
pub enum Op {
    Write { root: PathBuf, rel: String, text: String },
    Rename { root: PathBuf, from: String, to: String },
    Delete { root: PathBuf, rel: String },
    Mkdir { root: PathBuf, rel: String },
    SaveState(Box<crate::store::Persist>),
}

/// Disk work runs on this thread in the order it was sent. Errors land in `errors`.
#[derive(Clone)]
pub struct Writer {
    tx: Sender<Op>,
    pub errors: Arc<Mutex<Vec<String>>>,
}

impl Writer {
    pub fn start(waker: Option<rune::Waker>) -> Writer {
        let (tx, rx) = channel::<Op>();
        let errors: Arc<Mutex<Vec<String>>> = Arc::default();
        let errs = errors.clone();
        std::thread::Builder::new()
            .name("quire-writer".into())
            .spawn(move || {
                while let Ok(op) = rx.recv() {
                    if let Err(e) = apply(&op) {
                        let what = match &op {
                            Op::Write { rel, .. } | Op::Delete { rel, .. } | Op::Mkdir { rel, .. } => rel.clone(),
                            Op::Rename { from, .. } => from.clone(),
                            Op::SaveState(_) => "settings".into(),
                        };
                        if let Ok(mut v) = errs.lock() {
                            v.push(format!("Could not save {what}: {e}"));
                        }
                        if let Some(w) = &waker {
                            w();
                        }
                    }
                }
            })
            .ok();
        Writer { tx, errors }
    }

    pub fn send(&self, op: Op) {
        let _ = self.tx.send(op);
    }
}

fn apply(op: &Op) -> std::io::Result<()> {
    match op {
        Op::Write { root, rel, text } => {
            let p = root.join(rel);
            if let Some(d) = p.parent() {
                std::fs::create_dir_all(d)?;
            }
            let tmp = p.with_file_name(format!(".{}.quire-tmp", p.file_name().map_or_else(String::new, |n| n.to_string_lossy().into_owned())));
            std::fs::write(&tmp, text)?;
            std::fs::rename(&tmp, &p)
        }
        Op::Rename { root, from, to } => {
            let dst = root.join(to);
            if let Some(d) = dst.parent() {
                std::fs::create_dir_all(d)?;
            }
            std::fs::rename(root.join(from), dst)
        }
        Op::Delete { root, rel } => {
            // Move the note to `.trash` inside the folder, so it can come back.
            let src = root.join(rel);
            let trash = root.join(".trash");
            std::fs::create_dir_all(&trash)?;
            let name = rel.replace('/', " - ");
            let mut dst = trash.join(&name);
            let mut n = 2;
            while dst.exists() {
                dst = trash.join(format!("{} {n}.md", crate::md::strip_md(&name)));
                n += 1;
            }
            std::fs::rename(src, dst)
        }
        Op::Mkdir { root, rel } => std::fs::create_dir_all(root.join(rel)),
        Op::SaveState(p) => crate::store::save(p),
    }
}

/// Writes the sample notes into `root`, replacing files with the same names.
pub fn write_sample(root: &Path) -> std::io::Result<()> {
    for f in crate::sample::FOLDERS {
        std::fs::create_dir_all(root.join(f))?;
    }
    // Files keep the order they were made in (some lists follow it). File times tick in
    // steps of a few milliseconds, so leave a gap between the notes.
    for (rel, text) in crate::sample::files() {
        let p = root.join(rel);
        let _ = std::fs::remove_file(&p);
        std::fs::write(&p, text)?;
        std::thread::sleep(std::time::Duration::from_millis(6));
    }
    Ok(())
}
