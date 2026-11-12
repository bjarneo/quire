package app.quire.notes.core.sync

import app.quire.notes.data.LocalFs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.TreeMap

/** A bucket in memory, shared by the devices of a test. */
class Mem : ObjStore {
    val objects = TreeMap<String, ByteArray>()
    override fun get(key: String): ByteArray? = synchronized(objects) { objects[key]?.copyOf() }
    override fun put(key: String, body: ByteArray) = synchronized(objects) { objects[key] = body.copyOf() }
    override fun delete(key: String) {
        synchronized(objects) { objects.remove(key) }
    }
    override val place: String get() = "mem"
}

/** A notes folder on disk with its own base record, as one device has it. */
class Device(tag: String, val name: String) {
    val root: File = Files.createTempDirectory("quire-sync-$tag-$name").toFile()
    private val baseFile = File(root.parentFile, root.name + ".base.json")

    fun write(p: String, t: String) {
        val f = File(root, p)
        f.parentFile?.mkdirs()
        f.writeText(t)
    }

    fun read(p: String): String? = File(root, p).takeIf { it.isFile }?.readText()

    fun remove(p: String) = assertTrue(File(root, p).delete())

    fun mkdir(p: String) = assertTrue(File(root, p).mkdirs())

    fun rmdir(p: String) = assertTrue(File(root, p).deleteRecursively())

    fun isDir(p: String) = File(root, p).isDirectory

    /** The notes and folders as the app loads them, with the file system of the app. */
    fun local(): Pair<Map<String, String>, Set<String>> = LocalFs(root).load().let { it.files to it.folders }

    fun run(store: ObjStore, r: Remote): Outcome {
        val (files, dirs) = local()
        return SyncEngine.run(store, r, files, dirs, name, SyncEngine.loadBase(baseFile))
    }

    /** Syncs, then applies the outcome as the app does, then saves the base. */
    fun sync(store: ObjStore, r: Remote, dirty: Set<String> = emptySet()): Outcome {
        val out = run(store, r)
        val (files, dirs) = local()
        val plan = SyncEngine.plan(out, files, dirs, dirty)
        val fs = LocalFs(root)
        for ((p, t) in plan.writes) fs.write(p, t)
        for (p in plan.deletes) fs.delete(p)
        for (d in plan.mkdirs) fs.mkdirs(d)
        for (d in plan.rmdirs) fs.deleteDir(d)
        SyncEngine.saveBase(baseFile, out.base)
        return out
    }
}

class SyncEngineTest {
    private fun remote(encrypt: Boolean, pass: String = "a long enough passphrase") = Remote("mem", emptyMap(), "", encrypt, pass)

    private fun twoDevices(encrypt: Boolean, tag: String) {
        val bucket = Mem()
        val r = remote(encrypt)
        val laptop = Device(tag, "laptop")
        val phone = Device(tag, "phone")
        laptop.write("Ideas.md", "# Ideas")
        laptop.write("Drafts/Plan.md", "# Plan")
        assertEquals(2, laptop.sync(bucket, r).uploaded)
        // A second device gets both notes.
        val first = phone.sync(bucket, r)
        assertEquals(2, first.pulls.size)
        assertEquals("# Plan", phone.read("Drafts/Plan.md"))
        // An edit on the phone reaches the laptop.
        phone.write("Ideas.md", "# Ideas\n\nfrom the phone")
        assertEquals(1, phone.sync(bucket, r).uploaded)
        laptop.sync(bucket, r)
        assertEquals("# Ideas\n\nfrom the phone", laptop.read("Ideas.md"))
        // A delete on the laptop removes the note on the phone.
        laptop.remove("Drafts/Plan.md")
        assertEquals(1, laptop.sync(bucket, r).deletedRemote)
        assertEquals(listOf("Drafts/Plan.md" to Sign.sha256Hex("# Plan")), phone.sync(bucket, r).removes)
        assertNull(phone.read("Drafts/Plan.md"))
        // Both change the same note: each keeps its own text and gets the other beside it.
        laptop.write("Ideas.md", "laptop text")
        phone.write("Ideas.md", "phone text")
        laptop.sync(bucket, r)
        val out = phone.sync(bucket, r)
        assertEquals(listOf("Ideas (conflict laptop).md"), out.conflicts)
        assertEquals("phone text", phone.read("Ideas.md"))
        assertEquals("laptop text", phone.read("Ideas (conflict laptop).md"))
        laptop.sync(bucket, r)
        assertEquals("phone text", laptop.read("Ideas.md"))
        assertEquals("laptop text", laptop.read("Ideas (conflict laptop).md"))
        // An empty folder and its subfolder reach the other device, and so does their delete.
        laptop.mkdir("Archive/2025")
        laptop.sync(bucket, r)
        assertEquals(listOf("Archive", "Archive/2025"), phone.sync(bucket, r).mkdirs)
        assertTrue(phone.isDir("Archive/2025"))
        laptop.rmdir("Archive")
        laptop.sync(bucket, r)
        assertEquals(listOf("Archive/2025", "Archive"), phone.sync(bucket, r).rmdirs)
        assertFalse(phone.isDir("Archive"))
        assertTrue("the folder went to the trash", phone.isDir(".trash/Archive"))
        // A folder that gets a new note on one device while the other deletes it stays, with the note.
        phone.rmdir("Drafts")
        laptop.write("Drafts/New.md", "# New")
        phone.sync(bucket, r)
        val kept = laptop.sync(bucket, r)
        assertTrue("a folder with a new note stays: ${kept.rmdirs}", kept.rmdirs.isEmpty())
        val back = phone.sync(bucket, r)
        assertEquals(listOf("Drafts/New.md"), back.pulls.map { it.path })
        assertEquals("# New", phone.read("Drafts/New.md"))
        // A sync with nothing new changes nothing.
        val quiet = phone.sync(bucket, r)
        assertEquals(Triple(0, 0, 0), Triple(quiet.uploaded, quiet.pulls.size, quiet.removes.size))
        assertEquals(Triple(0, 0, 0), Triple(quiet.deletedRemote, quiet.mkdirs.size, quiet.rmdirs.size))
        assertEquals(laptop.local(), phone.local())
        val keys = bucket.objects.keys
        if (encrypt) {
            assertTrue("object names hide the paths: $keys", keys.none { it.contains("Ideas") })
            assertTrue("text is encrypted", bucket.objects.values.none { String(it, Charsets.UTF_8).contains("phone text") })
            assertTrue(keys.toString(), ".quire/manifest.bin" in keys && ".quire/manifest.json" !in keys)
            val format = Json.parse(String(bucket.objects.getValue(".quire/format.json"))) as Map<*, *>
            assertEquals(true, format["encrypted"])
            assertEquals("pbkdf2-sha256", format["kdf"])
            assertEquals(600_000L, format["iterations"])
        } else {
            assertEquals(setOf(".quire/format.json", ".quire/manifest.json", "Ideas.md", "Ideas (conflict laptop).md", "Drafts/New.md"), keys.toSet())
            val m = Json.parse(String(bucket.objects.getValue(".quire/manifest.json"))) as Map<*, *>
            val e = (m["files"] as Map<*, *>)["Ideas.md"] as Map<*, *>
            assertEquals(Sign.sha256Hex("phone text"), e["hash"])
            assertEquals("Ideas.md", e["object"])
            assertEquals("phone", e["device"])
            assertTrue(e["time"] is Long)
            assertEquals(1L, m["version"])
            assertEquals(listOf("Drafts"), m["folders"])
        }
    }

    @Test
    fun twoDevicesStayInStep() = twoDevices(false, "plain")

    @Test
    fun twoDevicesStayInStepEncrypted() = twoDevices(true, "enc")

    @Test
    fun aWrongPassphraseIsRefused() {
        val bucket = Mem()
        val d = Device("pass", "laptop")
        d.write("A.md", "a")
        d.sync(bucket, remote(true))
        val e = Device("pass2", "phone")
        try {
            e.run(bucket, remote(true, "a different passphrase"))
            fail("a wrong passphrase synced")
        } catch (x: SyncException) {
            assertTrue(x.message, x.message!!.contains("passphrase"))
        }
        try {
            e.run(bucket, remote(false))
            fail("a plain device synced with an encrypted folder")
        } catch (x: SyncException) {
            assertTrue(x.message, x.message!!.contains("encrypted"))
        }
        try {
            SyncEngine.run(remote(true, "short"), emptyMap(), emptySet(), "phone", Base())
            fail("a short passphrase synced")
        } catch (x: SyncException) {
            assertEquals("The passphrase needs at least 12 characters.", x.message)
        }
    }

    @Test
    fun aPlainFolderRefusesAnEncryptedDevice() {
        val bucket = Mem()
        Device("plainfmt", "laptop").also { it.write("A.md", "a") }.sync(bucket, remote(false))
        try {
            Device("plainfmt2", "phone").run(bucket, remote(true))
            fail("an encrypted device synced with a plain folder")
        } catch (x: SyncException) {
            assertEquals("The remote notes are not encrypted. Turn off Encrypt before upload, or use a new remote folder.", x.message)
        }
    }

    @Test
    fun aNoteThatChangedDuringTheSyncKeepsItsText() {
        val bucket = Mem()
        val r = remote(false)
        val laptop = Device("guard", "laptop")
        val phone = Device("guard", "phone")
        laptop.write("A.md", "one")
        laptop.write("B.md", "bee")
        laptop.sync(bucket, r)
        phone.sync(bucket, r)
        laptop.write("A.md", "two")
        laptop.remove("B.md")
        laptop.sync(bucket, r)
        // The phone syncs, but the person edits both notes before the outcome lands.
        val out = phone.run(bucket, r)
        assertEquals(listOf("A.md"), out.pulls.map { it.path })
        assertEquals(listOf("B.md"), out.removes.map { it.first })
        phone.write("A.md", "one, edited")
        val plan = SyncEngine.plan(out, phone.local().first, emptySet(), setOf("B.md"))
        assertEquals(listOf("A (conflict laptop).md" to "two"), plan.writes)
        assertEquals(emptyList<String>(), plan.deletes)
        assertEquals(listOf("A (conflict laptop).md"), plan.conflicts)
        // Without edits, the pull and the delete apply.
        val clean = SyncEngine.plan(out, mapOf("A.md" to "one", "B.md" to "bee"), emptySet(), emptySet())
        assertEquals(listOf("A.md" to "two"), clean.writes)
        assertEquals(listOf("B.md"), clean.deletes)
    }

    @Test
    fun anUnsavedBaseLosesNoNotes() {
        val bucket = Mem()
        val r = remote(false)
        val laptop = Device("nobase", "laptop")
        val phone = Device("nobase", "phone")
        laptop.write("A.md", "a")
        laptop.sync(bucket, r)
        // The phone stops after the sync and before it applies the outcome.
        assertEquals(1, phone.run(bucket, r).pulls.size)
        val again = phone.sync(bucket, r)
        assertEquals(listOf("A.md"), again.pulls.map { it.path })
        assertEquals(0, again.deletedRemote)
        assertEquals("a", phone.read("A.md"))
    }

    @Test
    fun skipsPathsThatMustNotSync() {
        val bucket = Mem()
        val r = remote(false)
        val d = Device("skip", "laptop")
        d.write("A.md", "a")
        d.sync(bucket, r)
        // A manifest entry that points outside the folder never comes down.
        val m = Json.parse(String(bucket.objects.getValue(".quire/manifest.json"))) as Map<*, *>
        val files = LinkedHashMap(m["files"] as Map<*, *>)
        files["../evil.md"] = mapOf("hash" to "x", "object" to "evil", "time" to 1L, "device" to "x")
        bucket.objects[".quire/manifest.json"] = Json.write(mapOf("version" to 1, "files" to files)).toByteArray()
        bucket.objects["evil"] = "bad".toByteArray()
        val out = Device("skip2", "phone").sync(bucket, r)
        assertEquals(listOf("A.md"), out.pulls.map { it.path })
        assertFalse(File(d.root.parentFile, "evil.md").exists())
    }

    @Test
    fun aFolderThatGetsANoteBeforeTheDeleteLandsStays() {
        val bucket = Mem()
        val r = remote(false)
        val laptop = Device("dirguard", "laptop")
        val phone = Device("dirguard", "phone")
        laptop.mkdir("Old")
        laptop.sync(bucket, r)
        phone.sync(bucket, r)
        laptop.rmdir("Old")
        laptop.sync(bucket, r)
        val out = phone.run(bucket, r)
        assertEquals(listOf("Old"), out.rmdirs)
        // The person adds a note to the folder before the outcome lands.
        assertEquals(emptyList<String>(), SyncEngine.plan(out, mapOf("Old/Fresh.md" to "x"), setOf("Old"), emptySet()).rmdirs)
        assertEquals(listOf("Old"), SyncEngine.plan(out, emptyMap(), setOf("Old"), emptySet()).rmdirs)
    }

    @Test
    fun readsTheOldBaseForm() {
        val f = File(Files.createTempDirectory("quire-base").toFile(), "b.json")
        f.writeText("""{"A.md":"aa","B/C.md":"cc"}""")
        assertEquals(Base(mapOf("A.md" to "aa", "B/C.md" to "cc")), SyncEngine.loadBase(f))
        SyncEngine.saveBase(f, Base(mapOf("A.md" to "aa"), setOf("B", "A")))
        assertEquals("""{"files":{"A.md":"aa"},"folders":["A","B"]}""", f.readText())
        assertEquals(Base(mapOf("A.md" to "aa"), setOf("A", "B")), SyncEngine.loadBase(f))
        assertEquals(Base(), SyncEngine.loadBase(File(f.parentFile, "missing.json")))
    }

    @Test
    fun testReportsWhatTheStoreHolds() {
        val bucket = Mem()
        val r = remote(false)
        assertEquals("Connected to mem. 3 notes will upload on the first sync.", SyncEngine.test(r, 3, false, bucket))
        assertEquals("Connected to mem. Everything is up to date.", SyncEngine.test(r, 3, true, bucket))
        // Test connection only reads.
        assertTrue(bucket.objects.isEmpty())
        Device("test", "laptop").also { it.write("A.md", "a") }.sync(bucket, r)
        assertEquals("Connected to mem. It holds 1 note.", SyncEngine.test(r, 0, false, bucket))
    }
}
