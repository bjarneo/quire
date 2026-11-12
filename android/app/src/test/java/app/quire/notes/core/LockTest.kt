package app.quire.notes.core

import app.quire.notes.core.sync.SyncException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The tests of desktop/src/lock.rs, and the locked note in the test values of SYNC.md. */
class LockTest {
    private val salt = "0123456789abcdef".toByteArray()
    private val notice = "This note is locked with a password. Open it in Quire to read it."

    private fun err(f: () -> Unit): String = try {
        f()
        fail("no error")
        ""
    } catch (e: SyncException) {
        e.message!!
    }

    @Test
    fun theTestValueOfSyncMdOpens() {
        val note = """
            %QUIRE-LOCKED-NOTE v1 pbkdf2-sha256 1000 MDEyMzQ1Njc4OWFiY2RlZg==

            This note is locked with a password. Open it in Quire to read it.

            UVVJUkUxuYsECSutmGY8MVrT44GXP+mqVNaRrkyLtnVcXqlJUQ/+AdtwaCyZUuSDFLdqVw==
        """.trimIndent() + "\n"
        assertTrue(Lock.isLocked(note))
        assertEquals("# Diary\n\nBlåbær\n", Lock.open(note, "tiger lily").text)
        assertEquals("The password does not open this note.", err { Lock.open(note, "tiger lilies") })
    }

    @Test
    fun aLockedNoteOpensWithItsPasswordOnly() {
        val key = Lock.keyFor("tiger lily", salt, 1000)
        val text = "# Diary\n\nBlåbær and [[links]] #private\n"
        val locked = Lock.seal(key, text)
        assertTrue(Lock.isLocked(locked))
        assertTrue(locked.contains(notice))
        assertTrue("no plain text, tags or links show", !locked.contains("Diary") && !locked.contains("#private") && !locked.contains("[["))
        assertTrue(locked.lines().all { it.length <= 76 || it.startsWith(Lock.MAGIC) })
        val opened = Lock.open(locked, "tiger lily")
        assertEquals(text, opened.text)
        assertEquals("edited", Lock.openWith(opened.key, Lock.seal(opened.key, "edited")))
        assertEquals("The password does not open this note.", err { Lock.open(locked, "wrong pass") })
        val other = Lock.keyFor("other", "fedcba9876543210".toByteArray(), 1000)
        assertEquals("The note has another password now.", err { Lock.openWith(key, Lock.seal(other, "x")) })
        assertTrue(err { Lock.open(locked.replace(" v1 ", " v9 "), "tiger lily") }.contains("newer format"))
        // Two seals of one text differ, because each has its own nonce.
        assertNotEquals(Lock.seal(key, text), Lock.seal(key, text))
        assertFalse(Lock.isLocked("# A note"))
    }

    @Test
    fun theFileFormatMatchesTheSpec() {
        val key = Lock.keyFor("tiger lily", salt, 1000)
        val long = "x".repeat(500)
        val lines = Lock.seal(key, long).split('\n')
        assertEquals("%QUIRE-LOCKED-NOTE v1 pbkdf2-sha256 1000 MDEyMzQ1Njc4OWFiY2RlZg==", lines[0])
        assertEquals("", lines[1])
        assertEquals(notice, lines[2])
        assertEquals("", lines[3])
        assertEquals(76, lines[4].length)
        assertEquals("the file ends with a line break", "", lines.last())
        assertEquals(long, Lock.open(Lock.seal(key, long), "tiger lily").text)
        assertEquals("This note is not in the locked note format.", err { Lock.open("%QUIRE-LOCKED-NOTE v1 pbkdf2-sha256 1000 MDEy\n\n!!!", "tiger lily") })
        // Search skips a locked note, so its notice does not match.
        assertTrue(Md.search(mapOf("Diary.md" to Lock.seal(key, "secret words")), "password").isEmpty())
        assertTrue(Md.search(mapOf("Diary.md" to Lock.seal(key, "secret words")), "secret").isEmpty())
    }
}
