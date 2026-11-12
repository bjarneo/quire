package app.quire.notes.core.sync

import app.quire.notes.core.Md
import app.quire.notes.core.Sync
import java.io.File
import java.util.Base64
import java.util.TreeMap
import java.util.TreeSet

/** What sync needs from the settings. [cfg] holds the fields of one provider. */
data class Remote(
    val provider: String,
    val cfg: Map<String, String>,
    val prefix: String,
    val encrypt: Boolean,
    val pass: String,
) {
    fun v(k: String, d: String): String = cfg[k]?.trim()?.takeIf { it.isNotEmpty() } ?: d

    /** A stable id for this notes folder on this store, for the base record. [root] is the folder location. */
    fun id(root: String): String =
        Sign.sha256Hex("$root|$provider|${v("bucket", "")}|${v("container", "")}|$prefix").substring(0, 20)

    // The passphrase stays out of logs and error reports.
    override fun toString(): String = "Remote($provider, $prefix, encrypt=$encrypt)"
}

/** A note to write locally. */
data class Pull(
    val path: String,
    val text: String,
    /** The hash of the local text that this sync saw, or null when the note was not there. */
    val seen: String?,
    /** The device that wrote the remote text. */
    val device: String,
)

/** What both sides had after the last sync on this device: a hash for each note, and the folders. */
data class Base(val files: Map<String, String> = emptyMap(), val folders: Set<String> = emptySet())

/** What a sync did, and what the app must still apply to the open folder. */
class Outcome(
    val pulls: List<Pull>,
    /** Notes that another device deleted, with the local hash that this sync saw. */
    val removes: List<Pair<String, String>>,
    val uploaded: Int,
    val deletedRemote: Int,
    val conflicts: List<String>,
    /** Folders that another device made. The app makes them here. */
    val mkdirs: List<String>,
    /**
     * Folders that another device deleted, the deepest first. The app moves each one to .trash
     * when no note is in it.
     */
    val rmdirs: List<String>,
    val place: String,
    /**
     * The new base record. The app saves it with [SyncEngine.saveBase] after it applied the
     * outcome. If the app stops before that, the next sync starts from the old base, and no
     * note or folder is lost.
     */
    val base: Base,
)

private class Entry(val hash: String, val obj: String, val time: Long, val device: String)

private class Manifest(val files: TreeMap<String, Entry> = TreeMap(), val folders: TreeSet<String> = TreeSet())

/**
 * Sync: keeps a notes folder the same on every device through an object store. Follows
 * desktop/src/sync/mod.rs and SYNC.md, so the desktop app and this app share one remote folder.
 */
object SyncEngine {
    private const val FORMAT = ".quire/format.json"

    /** The remote folder inside the bucket. It always ends in "/", unless it is empty. */
    fun cleanPrefix(p: String): String {
        val t = p.trim().trim('/')
        return if (t.isEmpty()) "" else "$t/"
    }

    /** The remote folder from the settings, or quire/<folder name>/. */
    fun prefixFor(cfg: Map<String, String>, folderName: String): String =
        cleanPrefix(cfg["prefix"]?.takeIf { it.isNotBlank() } ?: "quire/$folderName/")

    /** The region that the settings show first for a provider, used until one is picked. */
    private fun defaultRegion(pid: String): String = Sync.provider(pid)?.regions?.firstOrNull()?.first ?: "us-east-1"

    fun openStore(r: Remote): ObjStore {
        val pid = r.provider
        val region = r.v("region", defaultRegion(pid))
        fun s3(endpoint: String, signRegion: String, pathStyle: Boolean, label: String = "secret access key"): ObjStore =
            S3(endpoint, r.v("bucket", ""), signRegion, r.v("accessKey", ""), r.v("secretKey", ""), pathStyle, r.prefix, label)
        return when (pid) {
            "s3" -> s3("https://s3.$region.amazonaws.com", region, false)
            "hetzner" -> s3("https://$region.your-objectstorage.com", region, true)
            "r2" -> s3("https://${r.v("accountId", "")}.r2.cloudflarestorage.com", "auto", true)
            "b2" -> s3("https://s3.$region.backblazeb2.com", region, true, "application key")
            "do" -> s3("https://$region.digitaloceanspaces.com", "us-east-1", true)
            "wasabi" -> s3("https://s3.$region.wasabisys.com", region, true)
            "scaleway" -> s3("https://s3.$region.scw.cloud", region, true)
            "ovh" -> s3("https://s3.$region.io.cloud.ovh.net", region, true)
            "custom" -> s3(r.v("endpoint", ""), r.v("region", "us-east-1"), r.v("addressing", "path") != "vhost")
            "azure" -> Azure(r.v("account", ""), r.v("container", ""), r.v("azAuth", "key"), r.v("azKey", ""), r.prefix)
            "gcs" -> if (r.v("gcsAuth", "sa") == "hmac") {
                S3("https://storage.googleapis.com", r.v("bucket", ""), "auto", r.v("hmacId", ""), r.v("hmacSecret", ""), true, r.prefix, "HMAC secret")
            } else {
                Gcs(r.v("bucket", ""), r.v("gcsJson", ""), r.prefix)
            }
            else -> throw SyncException("Pick a storage backend first.")
        }
    }

    private fun checkPass(r: Remote) {
        if (r.encrypt && r.pass.codePointCount(0, r.pass.length) < 12) throw SyncException("The passphrase needs at least 12 characters.")
    }

    /** Reads the format of the remote folder, or writes it when [create] is true. Returns the keys when encrypted. */
    private fun openFormat(store: ObjStore, r: Remote, create: Boolean): Keys? {
        val bytes = store.get(FORMAT)
        if (bytes == null) {
            if (!create) return null
            val f = LinkedHashMap<String, Any?>()
            f["version"] = 1
            f["encrypted"] = r.encrypt
            var keys: Keys? = null
            if (r.encrypt) {
                val salt = Crypto.random(16)
                val k = Crypto.derive(r.pass, salt, Crypto.ITERATIONS)
                val b = Base64.getEncoder()
                f["kdf"] = "pbkdf2-sha256"
                f["iterations"] = Crypto.ITERATIONS
                f["salt"] = b.encodeToString(salt)
                f["check"] = b.encodeToString(Crypto.seal(k, "quire".toByteArray()))
                keys = k
            }
            store.put(FORMAT, Json.write(f, pretty = true).toByteArray(Charsets.UTF_8))
            return keys
        }
        val bad = "The remote folder has a format file Quire cannot read."
        val f = try {
            Json.parse(String(bytes, Charsets.UTF_8)) as? Map<*, *>
        } catch (e: Exception) {
            null
        } ?: throw SyncException(bad)
        val encrypted = f["encrypted"] as? Boolean ?: throw SyncException(bad)
        if (encrypted && !r.encrypt) throw SyncException("The remote notes are encrypted. Turn on Encrypt before upload and enter the passphrase.")
        if (!encrypted && r.encrypt) throw SyncException("The remote notes are not encrypted. Turn off Encrypt before upload, or use a new remote folder.")
        if (!encrypted) return null
        val dec = Base64.getDecoder()
        val salt = (f["salt"] as? String)?.let { runCatching { dec.decode(it) }.getOrNull() } ?: throw SyncException("The remote format has no salt.")
        val iterations = when (val it = f["iterations"]) {
            null -> Crypto.ITERATIONS.toLong()
            is Long -> it
            else -> throw SyncException(bad)
        }
        if (iterations < 1 || iterations > 50_000_000) throw SyncException(bad)
        val k = Crypto.derive(r.pass, salt, iterations.toInt())
        val check = (f["check"] as? String)?.let { runCatching { dec.decode(it) }.getOrNull() } ?: throw SyncException("The remote format has no check value.")
        val ok = try {
            Crypto.open(k, check).contentEquals("quire".toByteArray())
        } catch (e: SyncException) {
            false
        }
        if (!ok) throw SyncException("The passphrase does not match the one these notes were encrypted with.")
        return k
    }

    private fun manifestKey(keys: Keys?) = if (keys != null) ".quire/manifest.bin" else ".quire/manifest.json"

    private fun readManifest(store: ObjStore, keys: Keys?): Manifest {
        val bytes = store.get(manifestKey(keys)) ?: return Manifest()
        val plain = if (keys != null) Crypto.open(keys, bytes) else bytes
        val bad = SyncException("The remote manifest could not be read.")
        val m = try {
            Json.parse(String(plain, Charsets.UTF_8)) as? Map<*, *>
        } catch (e: Exception) {
            null
        } ?: throw bad
        val files = m["files"] as? Map<*, *> ?: throw bad
        val out = TreeMap<String, Entry>()
        for ((p, e) in files) {
            val o = e as? Map<*, *> ?: throw bad
            val hash = o["hash"] as? String ?: throw bad
            val obj = o["object"] as? String ?: throw bad
            val time = (o["time"] as? Number)?.toLong() ?: throw bad
            val device = o["device"] as? String ?: throw bad
            out[p.toString()] = Entry(hash, obj, time, device)
        }
        // A manifest from before folders synced has no folders.
        val dirs = TreeSet<String>()
        when (val f = m["folders"]) {
            null -> {}
            is List<*> -> for (d in f) dirs.add(d as? String ?: throw bad)
            else -> throw bad
        }
        return Manifest(out, dirs)
    }

    private fun writeManifest(store: ObjStore, keys: Keys?, m: Manifest) {
        val files = LinkedHashMap<String, Any?>()
        for ((p, e) in m.files) files[p] = linkedMapOf("hash" to e.hash, "object" to e.obj, "time" to e.time, "device" to e.device)
        val json = Json.write(linkedMapOf("version" to 1, "files" to files, "folders" to m.folders.toList())).toByteArray(Charsets.UTF_8)
        store.put(manifestKey(keys), if (keys != null) Crypto.seal(keys, json) else json)
    }

    /** Checks the settings against the store, as Test connection does. It only reads. */
    fun test(r: Remote, localNotes: Int, syncedBefore: Boolean, store: ObjStore? = null): String {
        checkPass(r)
        val s = store ?: openStore(r)
        val keys = openFormat(s, r, false)
        val place = s.place
        val remote = if (s.get(manifestKey(keys)) != null) readManifest(s, keys).files.size else 0
        return when {
            remote > 0 -> "Connected to $place. It holds $remote ${if (remote == 1) "note" else "notes"}."
            syncedBefore -> "Connected to $place. Everything is up to date."
            else -> "Connected to $place. $localNotes ${if (localNotes == 1) "note" else "notes"} will upload on the first sync."
        }
    }

    /** A path that may sync: a .md note, with no part that starts with "." and no node_modules. */
    fun syncable(p: String): Boolean {
        if (!p.endsWith(".md", ignoreCase = true) || p.contains('\\') || p.contains('\u0000')) return false
        return p.split('/').all { it.isNotEmpty() && !it.startsWith(".") && it != "node_modules" }
    }

    /** A folder that may sync: no part that starts with "." and no node_modules. */
    fun syncableDir(p: String): Boolean {
        if (p.contains('\\') || p.contains('\u0000')) return false
        return p.split('/').all { it.isNotEmpty() && !it.startsWith(".") && it != "node_modules" }
    }

    fun conflictPath(p: String, device: String, taken: Set<String>): String {
        val dir = Md.dir(p)
        val base = Md.base(p)
        val dev = device.replace(Regex("""[\\/:]"""), "-").trim()
        var c = Md.join(dir, "$base (conflict $dev).md")
        var n = 2
        while (c in taken) c = Md.join(dir, "$base (conflict $dev ${n++}).md")
        return c
    }

    fun loadBase(file: File?): Base {
        if (file == null || !file.isFile) return Base()
        return try {
            val m = Json.parse(file.readText()) as? Map<*, *> ?: return Base()
            fun strings(v: Any?) = (v as? Map<*, *>)?.entries?.mapNotNull { (k, h) -> (h as? String)?.let { k.toString() to it } }?.toMap() ?: emptyMap()
            val files = m["files"]
            // A record from before folders synced is a plain map of notes. A note path ends in .md, so it is never "files".
            if (files is Map<*, *>) {
                Base(strings(files), (m["folders"] as? List<*>)?.mapNotNull { it as? String }?.toSet() ?: emptySet())
            } else {
                Base(strings(m))
            }
        } catch (e: Exception) {
            Base()
        }
    }

    fun saveBase(file: File, base: Base) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(Json.write(linkedMapOf("files" to TreeMap(base.files), "folders" to base.folders.sorted())))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    /** One sync. Checks the passphrase and opens the store from the settings first. */
    fun run(r: Remote, local: Map<String, String>, localDirs: Set<String>, device: String, base: Base): Outcome {
        checkPass(r)
        return run(openStore(r), r, local, localDirs, device, base)
    }

    /**
     * One sync of a notes folder. [local] holds every saved note of the folder, [localDirs]
     * holds its folders, and [base] holds what both sides had after the last sync on this device.
     */
    fun run(store: ObjStore, r: Remote, local: Map<String, String>, localDirs: Set<String>, device: String, base: Base): Outcome {
        val keys = openFormat(store, r, true)
        val m = readManifest(store, keys)
        val manifest = m.files
        val baseDirs = base.folders
        val baseFiles = base.files
        val pulls = ArrayList<Pull>()
        val removes = ArrayList<Pair<String, String>>()
        val conflicts = ArrayList<String>()
        var uploaded = 0
        var deletedRemote = 0
        val newBase = TreeMap<String, String>()
        val sets = LinkedHashMap<String, Entry>()
        val dels = LinkedHashSet<String>()
        val now = System.currentTimeMillis()
        val paths = TreeSet<String>()
        paths.addAll(local.keys)
        paths.addAll(manifest.keys)
        paths.addAll(baseFiles.keys)
        paths.removeAll { !syncable(it) }
        val taken = HashSet(paths)

        fun upload(p: String, text: String) {
            val obj = if (keys != null) Crypto.objectName(keys, p) else p
            val bytes = text.toByteArray(Charsets.UTF_8)
            store.put(obj, if (keys != null) Crypto.seal(keys, bytes) else bytes)
            val h = Sign.sha256Hex(text)
            sets[p] = Entry(h, obj, now, device)
            dels.remove(p)
            newBase[p] = h
            uploaded++
        }

        fun pull(e: Entry): String? {
            val bytes = store.get(e.obj) ?: return null
            val plain = if (keys != null) Crypto.open(keys, bytes) else bytes
            return String(plain, Charsets.UTF_8).replace("\r\n", "\n")
        }

        for (p in paths) {
            val t = local[p]
            val lh = t?.let { Sign.sha256Hex(it) }
            val e = manifest[p]
            val b = baseFiles[p]
            when {
                t != null && e != null && lh == e.hash -> newBase[p] = lh
                t != null && e == null -> {
                    // Another device deleted the note, and this one did not change it.
                    if (b == lh) removes.add(p to lh!!) else upload(p, t)
                }
                t == null && e != null -> {
                    if (b == e.hash) {
                        store.delete(e.obj)
                        dels.add(p)
                        sets.remove(p)
                        deletedRemote++
                    } else {
                        pull(e)?.let {
                            newBase[p] = e.hash
                            pulls.add(Pull(p, it, null, e.device))
                        }
                    }
                }
                t != null && e != null -> {
                    when (b) {
                        e.hash -> upload(p, t)
                        lh -> pull(e)?.let {
                            newBase[p] = e.hash
                            pulls.add(Pull(p, it, lh, e.device))
                        }
                        else -> {
                            // Both sides changed: keep the text of this device, and bring the other beside it.
                            pull(e)?.let {
                                val c = conflictPath(p, e.device, taken)
                                taken.add(c)
                                conflicts.add(c)
                                // The copy goes up at once, so every device sees the conflict.
                                upload(c, it)
                                pulls.add(Pull(c, it, null, e.device))
                            }
                            upload(p, t)
                        }
                    }
                }
            }
        }

        // Folders, after the notes: a folder stays while a note is left in it.
        val removed = removes.map { it.first }.toSet()
        val kept = HashSet<String>()
        local.keys.filterTo(kept) { it !in removed }
        pulls.mapTo(kept) { it.path }
        kept.addAll(sets.keys)
        fun holdsNotes(f: String) = kept.any { Md.inDir(it, f) }
        val here = localDirs.filter { syncableDir(it) }.toSet()
        val there = m.folders.filter { syncableDir(it) }.toSet()
        val allDirs = TreeSet<String>()
        allDirs.addAll(here)
        allDirs.addAll(there)
        allDirs.addAll(baseDirs.filter { syncableDir(it) })
        val newDirs = TreeSet<String>()
        val mkdirs = ArrayList<String>()
        val rmdirs = ArrayList<String>()
        val addDirs = ArrayList<String>()
        val dropDirs = ArrayList<String>()
        for (f in allDirs) {
            val before = f in baseDirs
            when {
                f in here && f in there -> newDirs.add(f)
                f in here && before && !holdsNotes(f) -> rmdirs.add(f)
                f in here -> {
                    addDirs.add(f)
                    newDirs.add(f)
                }
                f in there && before && !holdsNotes(f) -> dropDirs.add(f)
                f in there -> {
                    mkdirs.add(f)
                    newDirs.add(f)
                }
            }
        }
        // The deepest folder goes first, so a parent is empty when its turn comes.
        rmdirs.sortByDescending { d -> d.count { it == '/' } }

        if (sets.isNotEmpty() || dels.isNotEmpty() || addDirs.isNotEmpty() || dropDirs.isNotEmpty()) {
            // Read the manifest again just before the write, so a device that synced meanwhile
            // keeps its entries. Then lay the changes of this sync over it.
            val fresh = readManifest(store, keys)
            for ((p, e) in sets) fresh.files[p] = e
            for (p in dels) fresh.files.remove(p)
            fresh.folders.addAll(addDirs)
            fresh.folders.removeAll(dropDirs.toSet())
            writeManifest(store, keys, fresh)
        }
        return Outcome(pulls, removes, uploaded, deletedRemote, conflicts, mkdirs, rmdirs, store.place, Base(newBase, newDirs))
    }

    /** The changes to make in the open folder after a sync. */
    class Apply(
        val writes: List<Pair<String, String>>,
        val deletes: List<String>,
        val conflicts: List<String>,
        val mkdirs: List<String>,
        val rmdirs: List<String>,
    )

    /**
     * Lays the outcome of a sync over the open folder. [current] is the text of each note now,
     * and [dirty] holds the notes with edits that are not on disk yet. A note that changed while
     * the sync ran keeps its new text, and the remote text goes beside it as a conflict copy. A
     * note that another device deleted stays when it changed here. The next sync uploads both.
     * A folder that another device deleted stays when a note is in it now. [currentDirs] holds
     * the folders now.
     */
    fun plan(out: Outcome, current: Map<String, String>, currentDirs: Set<String>, dirty: Set<String>): Apply {
        val conflicts = ArrayList(out.conflicts)
        val taken = HashSet(current.keys)
        out.pulls.forEach { taken.add(it.path) }
        val writes = ArrayList<Pair<String, String>>()
        for (pl in out.pulls) {
            val cur = current[pl.path]?.let { Sign.sha256Hex(it) }
            if (cur == Sign.sha256Hex(pl.text)) continue
            val path = if (pl.path in dirty || cur != pl.seen) {
                conflictPath(pl.path, pl.device, taken).also {
                    taken.add(it)
                    conflicts.add(it)
                }
            } else {
                pl.path
            }
            writes.add(path to pl.text)
        }
        val deletes = out.removes.filter { (p, seen) -> p !in dirty && current[p]?.let { Sign.sha256Hex(it) } == seen }.map { it.first }
        val notes = HashSet(current.keys)
        writes.forEach { notes.add(it.first) }
        notes.removeAll(deletes.toSet())
        val mkdirs = out.mkdirs.filter { it !in currentDirs }
        val rmdirs = out.rmdirs.filter { d -> d in currentDirs && notes.none { Md.inDir(it, d) } }
        return Apply(writes, deletes, conflicts, mkdirs, rmdirs)
    }

    /** "just now", "4m ago", "2h ago" or "3d ago". */
    fun ago(ms: Long, now: Long = System.currentTimeMillis()): String {
        val d = maxOf(now - ms, 0L) / 1000.0
        return when {
            d < 45 -> "just now"
            d < 3600 -> "${Math.round(d / 60)}m ago"
            d < 86_400 -> "${Math.round(d / 3600)}h ago"
            else -> "${Math.round(d / 86_400)}d ago"
        }
    }
}
