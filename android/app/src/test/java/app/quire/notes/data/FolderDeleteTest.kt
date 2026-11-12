package app.quire.notes.data

import app.quire.notes.core.Md
import app.quire.notes.core.sync.Base
import app.quire.notes.core.sync.Remote
import app.quire.notes.core.sync.SyncEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FolderDeleteTest {
    private fun vault(): File {
        val root = Files.createTempDirectory("quire-folder-delete").toFile()
        fun put(p: String, t: String) = File(root, p).also { it.parentFile?.mkdirs() }.writeText(t)
        put("Drafts/Keep.md", "# Keep")
        put("Drafts/Old/A.md", "# A")
        put("Drafts/Old/Sub/B.md", "# B")
        put("Drafts/Old/picture.png", "not a note")
        // A folder that an earlier delete put in the trash takes the first name.
        put(".trash/Drafts - Old/Earlier.md", "# Earlier")
        return root
    }

    @Test
    fun trashNamesUseDashesAndNumbers() {
        assertEquals("Drafts - Old", Md.trashDirName("Drafts/Old") { false })
        assertEquals("Old", Md.trashDirName("Old") { false })
        val taken = setOf("Drafts - Old", "Drafts - Old 2")
        assertEquals("Drafts - Old 3", Md.trashDirName("Drafts/Old") { it in taken })
        assertTrue(Md.inDir("Drafts/Old/A.md", "Drafts/Old"))
        assertFalse(Md.inDir("Drafts/Older/A.md", "Drafts/Old"))
        assertFalse(Md.inDir("Drafts/Old", "Drafts/Old"))
        assertFalse(Md.inDir("A.md", ""))
    }

    @Test
    fun theWholeFolderMovesToTheTrash() {
        val root = vault()
        val fs = LocalFs(root)
        fs.load()
        fs.deleteDir("Drafts/Old")
        assertFalse(File(root, "Drafts/Old").exists())
        val dst = File(root, ".trash/Drafts - Old 2")
        assertEquals("# A", File(dst, "A.md").readText())
        assertEquals("# B", File(dst, "Sub/B.md").readText())
        assertEquals("not a note", File(dst, "picture.png").readText())
        assertEquals("# Earlier", File(root, ".trash/Drafts - Old/Earlier.md").readText())
        val after = fs.load()
        assertEquals(setOf("Drafts/Keep.md"), after.files.keys)
        assertEquals(setOf("Drafts"), after.folders)
        // A folder that is not there is no error.
        fs.deleteDir("Drafts/Old")
        fs.deleteDir("")
        assertTrue(File(root, "Drafts/Keep.md").isFile)
    }

    @Test
    fun theNextSyncRemovesTheRemoteNotes() {
        val root = vault()
        val fs = LocalFs(root)
        val bucket = app.quire.notes.core.sync.Mem()
        val r = Remote("mem", emptyMap(), "", false, "")
        val a = fs.load()
        val first = SyncEngine.run(bucket, r, a.files, a.folders, "phone", Base())
        assertEquals(3, first.uploaded)
        fs.deleteDir("Drafts/Old")
        val b = fs.load()
        val second = SyncEngine.run(bucket, r, b.files, b.folders, "phone", first.base)
        assertEquals(2, second.deletedRemote)
        assertEquals(setOf(".quire/format.json", ".quire/manifest.json", "Drafts/Keep.md"), bucket.objects.keys)
    }
}
