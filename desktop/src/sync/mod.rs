//! Sync: keeps a notes folder the same on every device through an object store.
//!
//! The remote folder holds `.quire/format.json` (plain: whether the notes are encrypted, and the
//! key's salt), a manifest (`.quire/manifest.json`, or `.quire/manifest.bin` when encrypted)
//! that maps each note's path to the hash of its text and its object, and one object per note.
//! A sync compares, for each note, the local hash, the remote hash and the hash both had at the
//! last sync on this device. The side that changed wins. When both changed, the note keeps the
//! local text and the remote text comes down beside it as `name (conflict device).md`.
//! `SYNC.md` describes the format for other implementations.

pub mod app;
pub mod crypto;
pub mod sign;
pub mod store;

use std::collections::{BTreeMap, BTreeSet};
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::md;
use store::Store;

const FORMAT: &str = ".quire/format.json";

/// What sync needs from the settings.
#[derive(Clone, Debug)]
pub struct Remote {
    pub provider: String,
    pub cfg: BTreeMap<String, String>,
    pub prefix: String,
    pub encrypt: bool,
    pub pass: String,
}

impl Remote {
    fn val(&self, k: &str, d: &str) -> String {
        self.cfg.get(k).map(|v| v.trim().to_string()).filter(|v| !v.is_empty()).unwrap_or_else(|| d.to_string())
    }

    /// A stable id for this folder on this store, for the last-sync record.
    fn id(&self, root: &Path) -> String {
        let key = format!("{}|{}|{}|{}|{}", root.display(), self.provider, self.val("bucket", ""), self.val("container", ""), self.prefix);
        sign::sha256_hex(key.as_bytes())[..20].to_string()
    }
}

/// The region the settings show first for a provider, used until one is picked.
fn default_region(pid: &str) -> &'static str {
    crate::ui::settings_sync::PROVIDERS.iter().find(|p| p.id == pid).and_then(|p| p.regions.first()).map_or("us-east-1", |r| r.0)
}

/// The remote folder inside the bucket, always ending in `/` unless empty.
pub fn clean_prefix(p: &str) -> String {
    let t = p.trim().trim_matches('/');
    if t.is_empty() { String::new() } else { format!("{t}/") }
}

pub fn open_store(r: &Remote) -> Result<Box<dyn Store>, String> {
    let pid = r.provider.as_str();
    let region = r.val("region", default_region(pid));
    let s3 = |endpoint: String, sign_region: &str, path_style: bool, secret_label: &str| -> Box<dyn Store> {
        Box::new(store::S3::new(&endpoint, &r.val("bucket", ""), sign_region, &r.val("accessKey", ""), &r.val("secretKey", ""), path_style, &r.prefix, secret_label))
    };
    Ok(match pid {
        "s3" => s3(format!("https://s3.{region}.amazonaws.com"), &region, false, "secret access key"),
        "hetzner" => s3(format!("https://{region}.your-objectstorage.com"), &region, true, "secret access key"),
        "r2" => s3(format!("https://{}.r2.cloudflarestorage.com", r.val("accountId", "")), "auto", true, "secret access key"),
        "b2" => s3(format!("https://s3.{region}.backblazeb2.com"), &region, true, "application key"),
        "do" => s3(format!("https://{region}.digitaloceanspaces.com"), "us-east-1", true, "secret access key"),
        "wasabi" => s3(format!("https://s3.{region}.wasabisys.com"), &region, true, "secret access key"),
        "scaleway" => s3(format!("https://s3.{region}.scw.cloud"), &region, true, "secret access key"),
        "ovh" => s3(format!("https://s3.{region}.io.cloud.ovh.net"), &region, true, "secret access key"),
        "custom" => s3(r.val("endpoint", ""), &r.val("region", "us-east-1"), r.val("addressing", "path") != "vhost", "secret access key"),
        "azure" => Box::new(store::Azure::new(&r.val("account", ""), &r.val("container", ""), &r.val("azAuth", "key"), &r.val("azKey", ""), &r.prefix)?),
        "gcs" if r.val("gcsAuth", "sa") == "hmac" => Box::new(store::S3::new(
            "https://storage.googleapis.com",
            &r.val("bucket", ""),
            "auto",
            &r.val("hmacId", ""),
            &r.val("hmacSecret", ""),
            true,
            &r.prefix,
            "HMAC secret",
        )),
        "gcs" => Box::new(store::Gcs::new(&r.val("bucket", ""), &r.val("gcsJson", ""), &r.prefix)?),
        _ => return Err("Pick a storage backend first.".into()),
    })
}

#[derive(Serialize, Deserialize, Default)]
struct Format {
    version: u32,
    encrypted: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    kdf: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    iterations: Option<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    salt: Option<String>,
    /// `quire` sealed with the key, to tell a wrong passphrase at once.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    check: Option<String>,
}

#[derive(Serialize, Deserialize, Default, Clone)]
struct Manifest {
    version: u32,
    files: BTreeMap<String, Entry>,
    /// Every folder, empty ones included. Folders that hold notes are here too.
    #[serde(default)]
    folders: BTreeSet<String>,
}

/// What both sides had after the last sync on this device.
#[derive(Serialize, Deserialize, Default)]
struct Base {
    files: BTreeMap<String, String>,
    #[serde(default)]
    folders: BTreeSet<String>,
}

#[derive(Serialize, Deserialize, Clone, Debug, PartialEq)]
struct Entry {
    hash: String,
    object: String,
    time: i64,
    device: String,
}

/// A note to write locally.
#[derive(Debug, Clone, PartialEq)]
pub struct Pull {
    pub path: String,
    pub text: String,
    /// The hash of the local text this sync saw, or None when the note was not there. If the
    /// note changed since, the app keeps both versions.
    pub seen: Option<String>,
    /// The device that wrote the remote text.
    pub device: String,
}

/// What a sync did, and what the app must still apply to the open folder.
#[derive(Debug, Default)]
pub struct Outcome {
    pub pulls: Vec<Pull>,
    /// Notes another device deleted, with the local hash this sync saw. The app moves them to
    /// `.trash` if they did not change since.
    pub removes: Vec<(String, String)>,
    pub uploaded: usize,
    pub deleted_remote: usize,
    pub conflicts: Vec<String>,
    /// Folders another device made. The app makes them here.
    pub mkdirs: Vec<String>,
    /// Folders another device deleted. The app moves each one to `.trash` if no note is left
    /// in it.
    pub rmdirs: Vec<String>,
    pub place: String,
    /// The notes folder this sync ran on.
    pub root: PathBuf,
    /// The new base record and where it goes. The app saves it after it applied the rest, so
    /// a note that never reached the disk is not taken as in step.
    pub base: Option<(PathBuf, Vec<u8>)>,
}

impl Outcome {
    /// Saves the base record now, for tests that applied the outcome themselves.
    #[cfg(test)]
    pub fn save_base(&self) -> Result<(), String> {
        match &self.base {
            Some((p, b)) => crate::vault::write_atomic(p, b).map_err(|e| e.to_string()),
            None => Ok(()),
        }
    }
}

fn b64() -> base64::engine::GeneralPurpose {
    base64::engine::general_purpose::STANDARD
}

/// Opens the remote folder: reads or writes its format, and returns the keys if encrypted.
fn open_format(store: &mut dyn Store, r: &Remote, create: bool) -> Result<Option<crypto::Keys>, String> {
    use base64::Engine;
    let found = store.get(FORMAT)?;
    let Some(bytes) = found else {
        if !create {
            return Ok(None);
        }
        let mut f = Format { version: 1, encrypted: r.encrypt, ..Default::default() };
        let keys = if r.encrypt {
            let salt = crypto::random(16);
            let k = crypto::derive(&r.pass, &salt, crypto::ITERATIONS);
            f.kdf = Some("pbkdf2-sha256".into());
            f.iterations = Some(crypto::ITERATIONS);
            f.salt = Some(b64().encode(&salt));
            f.check = Some(b64().encode(crypto::seal(&k, b"quire")));
            Some(k)
        } else {
            None
        };
        store.put(FORMAT, serde_json::to_vec_pretty(&f).unwrap_or_default().as_slice())?;
        return Ok(keys);
    };
    let f: Format = serde_json::from_slice(&bytes).map_err(|_| "The remote folder has a format file Quire cannot read.".to_string())?;
    match (f.encrypted, r.encrypt) {
        (true, false) => return Err("The remote notes are encrypted. Turn on Encrypt before upload and enter the passphrase.".into()),
        (false, true) => return Err("The remote notes are not encrypted. Turn off Encrypt before upload, or use a new remote folder.".into()),
        (false, false) => return Ok(None),
        (true, true) => {}
    }
    let salt = f.salt.as_deref().and_then(|s| b64().decode(s).ok()).ok_or("The remote format has no salt.")?;
    let k = crypto::derive(&r.pass, &salt, f.iterations.unwrap_or(crypto::ITERATIONS));
    let check = f.check.as_deref().and_then(|s| b64().decode(s).ok()).ok_or("The remote format has no check value.")?;
    match crypto::open(&k, &check) {
        Ok(v) if v == b"quire" => Ok(Some(k)),
        _ => Err("The passphrase does not match the one these notes were encrypted with.".into()),
    }
}

fn manifest_key(keys: &Option<crypto::Keys>) -> &'static str {
    if keys.is_some() { ".quire/manifest.bin" } else { ".quire/manifest.json" }
}

fn read_manifest(store: &mut dyn Store, keys: &Option<crypto::Keys>) -> Result<Manifest, String> {
    let Some(bytes) = store.get(manifest_key(keys))? else { return Ok(Manifest { version: 1, ..Default::default() }) };
    let plain = match keys {
        Some(k) => crypto::open(k, &bytes)?,
        None => bytes,
    };
    serde_json::from_slice(&plain).map_err(|_| "The remote manifest could not be read.".to_string())
}

fn write_manifest(store: &mut dyn Store, keys: &Option<crypto::Keys>, m: &Manifest) -> Result<(), String> {
    let json = serde_json::to_vec(m).unwrap_or_default();
    let body = match keys {
        Some(k) => crypto::seal(k, &json),
        None => json,
    };
    store.put(manifest_key(keys), &body)
}

/// Checks the settings against the store, as "Test connection" does.
pub fn test(r: &Remote, local_notes: usize, synced_before: bool) -> Result<String, String> {
    if r.encrypt && r.pass.chars().count() < 12 {
        return Err("The passphrase needs at least 12 characters.".into());
    }
    let mut store = open_store(r)?;
    let keys = open_format(store.as_mut(), r, false)?;
    let place = store.place();
    let remote = match store.get(manifest_key(&keys))? {
        Some(_) => read_manifest(store.as_mut(), &keys)?.files.len(),
        None => 0,
    };
    Ok(if remote > 0 {
        format!("Connected to {place}. It holds {remote} {}.", if remote == 1 { "note" } else { "notes" })
    } else if synced_before {
        format!("Connected to {place}. Everything is up to date.")
    } else {
        format!("Connected to {place}. {local_notes} {} will upload on the first sync.", if local_notes == 1 { "note" } else { "notes" })
    })
}

fn base_path(r: &Remote, root: &Path) -> Option<PathBuf> {
    crate::store::dirs().map(|d| d.config().join("sync").join(format!("{}.json", r.id(root))))
}

fn load_base(p: &Option<PathBuf>) -> Base {
    let Some(bytes) = p.as_ref().and_then(|p| std::fs::read(p).ok()) else { return Base::default() };
    // A record from before folders synced is a plain map of notes.
    serde_json::from_slice(&bytes)
        .ok()
        .or_else(|| serde_json::from_slice::<BTreeMap<String, String>>(&bytes).ok().map(|files| Base { files, folders: BTreeSet::new() }))
        .unwrap_or_default()
}


pub fn device_name() -> String {
    let h = std::fs::read_to_string("/etc/hostname").ok().map(|s| s.trim().to_string()).filter(|s| !s.is_empty());
    h.or_else(|| std::env::var("COMPUTERNAME").ok()).or_else(|| std::env::var("HOSTNAME").ok()).unwrap_or_else(|| "desktop".into())
}

pub fn conflict_path(p: &str, device: &str, taken: &BTreeSet<String>) -> String {
    let (dir, base) = (md::dir(p), md::base(p));
    let dev = md::clean_name(device);
    let mut c = md::join(dir, &format!("{base} (conflict {dev}).md"));
    let mut n = 2;
    while taken.contains(&c) {
        c = md::join(dir, &format!("{base} (conflict {dev} {n}).md"));
        n += 1;
    }
    c
}

/// Lets `run_with` use one `as_mut()` call style for a borrowed store.
struct Boxed<'a>(&'a mut dyn Store);

impl Boxed<'_> {
    fn as_mut(&mut self) -> &mut dyn Store {
        &mut *self.0
    }
    fn place(&self) -> String {
        self.0.place()
    }
}

enum Change {
    Set(String, Entry),
    Remove(String),
    AddFolder(String),
    RemoveFolder(String),
}

/// One sync of the folder at `root`, which must hold every saved note.
pub fn run(r: &Remote, root: &Path, device: &str) -> Result<Outcome, String> {
    if r.encrypt && r.pass.chars().count() < 12 {
        return Err("The passphrase needs at least 12 characters.".into());
    }
    let mut store = open_store(r)?;
    let bp = base_path(r, root);
    run_with(store.as_mut(), r, root, device, &bp)
}

fn run_with(store: &mut dyn Store, r: &Remote, root: &Path, device: &str, bp: &Option<PathBuf>) -> Result<Outcome, String> {
    let mut store = Boxed(store);
    let keys = open_format(store.as_mut(), r, true)?;
    let manifest = read_manifest(store.as_mut(), &keys)?;
    let loaded = crate::vault::load(root).map_err(|e| format!("Could not read the folder: {e}"))?;
    let (local, local_dirs) = (loaded.files, loaded.folders.into_iter().collect::<BTreeSet<String>>());
    let bp = bp.clone();
    let Base { files: base, folders: base_dirs } = load_base(&bp);
    let mut out = Outcome { place: store.place(), root: root.to_path_buf(), ..Default::default() };
    let mut new_base = BTreeMap::new();
    let mut changes = Vec::new();
    let now = sign::now_secs() * 1000;
    let hash = |t: &str| sign::sha256_hex(t.as_bytes());
    let mut paths: BTreeSet<String> = local.keys().cloned().collect();
    paths.extend(manifest.files.keys().cloned());
    paths.extend(base.keys().cloned());
    let mut taken: BTreeSet<String> = paths.clone();

    let upload = |store: &mut dyn Store, p: &str, text: &str, changes: &mut Vec<Change>, new_base: &mut BTreeMap<String, String>, out: &mut Outcome| -> Result<(), String> {
        let object = match &keys {
            Some(k) => crypto::object_name(k, p),
            None => p.to_string(),
        };
        let body = match &keys {
            Some(k) => crypto::seal(k, text.as_bytes()),
            None => text.as_bytes().to_vec(),
        };
        store.put(&object, &body)?;
        let h = hash(text);
        changes.push(Change::Set(p.to_string(), Entry { hash: h.clone(), object, time: now, device: device.to_string() }));
        new_base.insert(p.to_string(), h);
        out.uploaded += 1;
        Ok(())
    };
    let pull = |store: &mut dyn Store, e: &Entry| -> Result<Option<String>, String> {
        let Some(bytes) = store.get(&e.object)? else { return Ok(None) };
        let plain = match &keys {
            Some(k) => crypto::open(k, &bytes)?,
            None => bytes,
        };
        Ok(Some(String::from_utf8_lossy(&plain).replace("\r\n", "\n")))
    };

    for p in &paths {
        let l = local.get(p).map(|t| (t, hash(t)));
        let rem = manifest.files.get(p);
        let b = base.get(p);
        match (l, rem) {
            (Some((_, lh)), Some(e)) if lh == e.hash => {
                new_base.insert(p.clone(), lh);
            }
            (Some((t, lh)), None) => {
                if b == Some(&lh) {
                    // Another device deleted it and this one did not change it.
                    out.removes.push((p.clone(), lh));
                } else {
                    upload(store.as_mut(), p, t, &mut changes, &mut new_base, &mut out)?;
                }
            }
            (None, Some(e)) => {
                if b == Some(&e.hash) {
                    store.as_mut().delete(&e.object)?;
                    changes.push(Change::Remove(p.clone()));
                    out.deleted_remote += 1;
                } else if let Some(text) = pull(store.as_mut(), e)? {
                    new_base.insert(p.clone(), e.hash.clone());
                    out.pulls.push(Pull { path: p.clone(), text, seen: None, device: e.device.clone() });
                }
            }
            (None, None) => {}
            (Some((t, lh)), Some(e)) => {
                if b == Some(&e.hash) {
                    upload(store.as_mut(), p, t, &mut changes, &mut new_base, &mut out)?;
                } else if b == Some(&lh) {
                    if let Some(text) = pull(store.as_mut(), e)? {
                        new_base.insert(p.clone(), e.hash.clone());
                        out.pulls.push(Pull { path: p.clone(), text, seen: Some(lh), device: e.device.clone() });
                    }
                } else {
                    // Both sides changed: keep this device's text, and bring the other beside it.
                    if let Some(text) = pull(store.as_mut(), e)? {
                        let c = conflict_path(p, &e.device, &taken);
                        taken.insert(c.clone());
                        out.conflicts.push(c.clone());
                        // The copy goes up at once, so every device sees the conflict.
                        upload(store.as_mut(), &c, &text, &mut changes, &mut new_base, &mut out)?;
                        out.pulls.push(Pull { path: c, text, seen: None, device: e.device.clone() });
                    }
                    upload(store.as_mut(), p, t, &mut changes, &mut new_base, &mut out)?;
                }
            }
        }
    }

    // Folders, after the notes: a folder stays while a note is left in it.
    let kept: BTreeSet<String> = local
        .keys()
        .filter(|p| !out.removes.iter().any(|(r, _)| r == *p))
        .cloned()
        .chain(out.pulls.iter().map(|pl| pl.path.clone()))
        .chain(changes.iter().filter_map(|c| if let Change::Set(p, _) = c { Some(p.clone()) } else { None }))
        .collect();
    let holds_notes = |f: &str| {
        let pre = format!("{f}/");
        kept.iter().any(|p| p.starts_with(&pre))
    };
    let mut new_dirs = BTreeSet::new();
    let mut all_dirs: BTreeSet<String> = local_dirs.clone();
    all_dirs.extend(manifest.folders.iter().cloned());
    all_dirs.extend(base_dirs.iter().cloned());
    for f in &all_dirs {
        let (here, there, before) = (local_dirs.contains(f), manifest.folders.contains(f), base_dirs.contains(f));
        match (here, there) {
            (true, true) => {
                new_dirs.insert(f.clone());
            }
            (true, false) if before && !holds_notes(f) => out.rmdirs.push(f.clone()),
            (true, false) => {
                changes.push(Change::AddFolder(f.clone()));
                new_dirs.insert(f.clone());
            }
            (false, true) if before && !holds_notes(f) => changes.push(Change::RemoveFolder(f.clone())),
            (false, true) => {
                out.mkdirs.push(f.clone());
                new_dirs.insert(f.clone());
            }
            (false, false) => {}
        }
    }
    // The deepest folder goes first, so a parent is empty when its turn comes.
    out.rmdirs.sort_by_key(|f| std::cmp::Reverse(f.matches('/').count()));

    if !changes.is_empty() {
        // Read the manifest again just before writing, so a device that synced meanwhile keeps
        // its entries, then lay this sync's changes over it.
        let mut fresh = read_manifest(store.as_mut(), &keys)?;
        fresh.version = 1;
        for c in changes {
            match c {
                Change::Set(p, e) => {
                    fresh.files.insert(p, e);
                }
                Change::Remove(p) => {
                    fresh.files.remove(&p);
                }
                Change::AddFolder(f) => {
                    fresh.folders.insert(f);
                }
                Change::RemoveFolder(f) => {
                    fresh.folders.remove(&f);
                }
            }
        }
        write_manifest(store.as_mut(), &keys, &fresh)?;
    }
    out.base = bp.map(|p| (p, serde_json::to_vec(&Base { files: new_base, folders: new_dirs }).unwrap_or_default()));
    Ok(out)
}

#[cfg(test)]
mod tests;
