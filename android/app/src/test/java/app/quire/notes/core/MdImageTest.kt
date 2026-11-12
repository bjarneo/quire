package app.quire.notes.core

import app.quire.notes.data.LocalFs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The image tests of desktop/src/md.rs, and the trash names of files with an extension. */
class MdImageTest {
    @Test
    fun imageLinesParse() {
        assertEquals(listOf(Img("A cat", "attachments/My cat.png", false)), Md.imageLine("![A cat](attachments/My%20cat.png)"))
        val two = Md.imageLine("  ![](a.png \"Title\")  ![[Photos/b.jpg|300]]")!!
        assertEquals("a.png", two[0].src)
        assertEquals(Img("b", "Photos/b.jpg", true), two[1])
        assertEquals("with space.png", Md.imageLine("![x](<with space.png>)")!![0].src)
        assertEquals("https://example.com/a%20b.png", Md.imageLine("![x](https://example.com/a%20b.png)")!![0].src)
        assertNull(Md.imageLine("text ![x](a.png)"))
        assertNull(Md.imageLine("![x](a.png) and text"))
        assertNull("an embed of a note is not an image", Md.imageLine("![[Note]]"))
        assertEquals("✓ 100%", Md.percentDecode("%E2%9C%93 100%"))
        assertEquals("%é", Md.percentDecode("%é"))
    }

    @Test
    fun imageBlocksAndPaths() {
        val b = Md.parseBlocks("Text\n![a](a.png)\nmore")
        assertEquals(listOf(BPara::class, BImage::class, BPara::class), b.map { it::class })
        assertEquals("../../attachments/a.png", Md.relative("Drafts/Old", "attachments/a.png"))
        assertEquals("attachments/a.png", Md.relative("", "attachments/a.png"))
        assertEquals("attachments/a.png", Md.resolvePath("Drafts", "../attachments/a.png"))
        assertNull(Md.resolvePath("", "../a.png"))
        assertEquals("My%20cat%20%281%29.png", Md.linkPath("My cat (1).png"))
        assertTrue(Md.isImage("x/Y.JPG") && !Md.isImage("x.md"))
    }

    @Test
    fun imageSourcesFollowTheDesktopRules() {
        val all = listOf("attachments/cat.png", "Drafts/cat.png", "Photos/b.jpg", "Drafts/Old/x.png")
        fun src(s: String, embed: Boolean = false, dir: String = "Drafts") = Images.source(Img("a", s, embed), dir, all)
        // Relative to the note first, then to the notes folder.
        assertEquals(ImageSource.Vault("Drafts/cat.png"), src("cat.png"))
        assertEquals(ImageSource.Vault("attachments/cat.png"), src("../attachments/cat.png"))
        assertEquals(ImageSource.Vault("attachments/cat.png"), src("attachments/cat.png"))
        assertEquals(ImageSource.Missing("dog.png"), src("dog.png"))
        // An embed: the exact path, then the name in the folder of the note, then the name anywhere.
        assertEquals(ImageSource.Vault("Photos/b.jpg"), src("photos/B.jpg", embed = true))
        assertEquals(ImageSource.Vault("Drafts/cat.png"), src("cat.png", embed = true))
        // Sorted by byte order, as the desktop sorts: "Drafts" comes before "attachments".
        assertEquals(ImageSource.Vault("Drafts/cat.png"), src("cat.png", embed = true, dir = ""))
        assertEquals(ImageSource.Vault("Drafts/Old/x.png"), src("x.png", embed = true, dir = ""))
        assertEquals(ImageSource.Url("https://example.com/a.png"), src("https://example.com/a.png"))
        assertEquals(ImageSource.Path("/sdcard/a.png"), src("file:///sdcard/a.png"))
        assertEquals(ImageSource.Path("/sdcard/a.png"), src("/sdcard/a.png"))
    }

    @Test
    fun insertedImagesGetALineOfTheirOwn() {
        assertEquals("\n![My cat](../attachments/My%20cat.png)\n", Images.linkText(listOf("attachments/My cat.png"), "Drafts", 'x', 'y'))
        assertEquals("![a](attachments/a.png)\n![b](attachments/b.png)\n", Images.linkText(listOf("attachments/a.png", "attachments/b.png"), "", null, null))
        assertEquals("![a](attachments/a.png)", Images.linkText(listOf("attachments/a.png"), "", '\n', '\n'))
        assertEquals("![x](attachments/[x].png)\n", Images.linkText(listOf("attachments/[x].png"), "", null, null))
        assertEquals("photo.jpg", Images.nameFor("photo.jpg", "image/jpeg"))
        assertEquals("Image 2026-11-12 13.58.04.png", Images.nameFor("1000000123", "image/png", "2026-11-12 13.58.04"))
        assertEquals("My photo.jpg", Images.nameFor("My photo", "image/jpeg", "2026-11-12 13.58.04"))
        assertEquals("Image 2026-11-12 13.58.04.png", Images.nameFor("38.png", "image/png", "2026-11-12 13.58.04"))
        assertEquals("image.webp", Images.nameFor(null, "image/webp"))
        assertNull(Images.nameFor("notes.txt", "text/plain"))
    }

    @Test
    fun anImageEmbedIsNoNoteLink() {
        val line = "see ![[wide.png]] and [[Plan]] and [[Photos/cat.JPG|Cat]]"
        assertNull(Md.linkAt(line, line.indexOf("wide")))
        assertEquals("Plan", Md.linkAt(line, line.indexOf("Plan"))?.second)
        assertNull(Md.linkAt(line, line.indexOf("Photos")))
    }

    @Test
    fun uniqueNamesNumberBeforeTheExtension() {
        val taken = setOf("cat.png", "cat 2.png", "notes")
        assertEquals("cat 3.png", Md.uniqueName("cat.png") { it in taken })
        assertEquals("dog.png", Md.uniqueName("dog.png") { it in taken })
        assertEquals("notes 2", Md.uniqueName("notes") { it in taken })
    }

    @Test
    fun trashNamesKeepTheExtension() {
        assertEquals("Drafts - Plan.md", Md.trashFileName("Drafts/Plan.md") { false })
        assertEquals("attachments - cat 2.png", Md.trashFileName("attachments/cat.png") { it == "attachments - cat.png" })
        val root = Files.createTempDirectory("quire-trash").toFile()
        val fs = LocalFs(root)
        repeat(2) {
            File(root, "attachments").mkdirs()
            File(root, "attachments/cat.png").writeBytes(byteArrayOf(1, 2, 3, it.toByte()))
            fs.delete("attachments/cat.png")
        }
        assertFalse(File(root, "attachments/cat.png").exists())
        assertTrue(byteArrayOf(1, 2, 3, 0).contentEquals(File(root, ".trash/attachments - cat.png").readBytes()))
        assertTrue(byteArrayOf(1, 2, 3, 1).contentEquals(File(root, ".trash/attachments - cat 2.png").readBytes()))
        File(root, "Drafts").mkdirs()
        File(root, "Drafts/Plan.md").writeText("# Plan")
        fs.delete("Drafts/Plan.md")
        assertEquals("# Plan", File(root, ".trash/Drafts - Plan.md").readText())
        // The image listing skips the trash, and has the size of each image.
        File(root, "attachments/dog.webp").writeBytes(ByteArray(7))
        assertEquals(mapOf("attachments/dog.webp" to 7L), fs.load().images.mapValues { it.value.size })
    }
}
