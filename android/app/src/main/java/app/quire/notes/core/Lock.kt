package app.quire.notes.core

import app.quire.notes.core.sync.Crypto
import app.quire.notes.core.sync.Keys
import app.quire.notes.core.sync.SyncException
import java.util.Base64

/** What it takes to seal a note again without the password: the key and its salt. */
class LockKey(internal val keys: Keys, internal val salt: ByteArray, internal val iterations: Int) {
    // No key material in logs.
    override fun toString() = "LockKey(..)"
}

/** A note that opened: its plain text and its key. */
class Opened(val text: String, val key: LockKey)

/**
 * Notes locked with a password. A locked note is still a .md file in the folder, but its text
 * is sealed with AES-256-GCM, with a key from the password by PBKDF2-HMAC-SHA256. The file
 * holds a header line, a sentence for other apps, and the sealed text in base64 lines of 76
 * characters. Follows desktop/src/lock.rs and "Locked notes" in SYNC.md.
 */
object Lock {
    const val MAGIC = "%QUIRE-LOCKED-NOTE"
    private const val NOTICE = "This note is locked with a password. Open it in Quire to read it."

    /** A password shorter than this is refused. */
    const val MIN_PASSWORD = 8

    /** True when the text of a note is a locked note. */
    fun isLocked(text: String): Boolean = text.startsWith(MAGIC)

    /** A new key for [password], with a new salt. It runs PBKDF2, so call it off the main thread. */
    fun newKey(password: String): LockKey {
        val salt = Crypto.random(16)
        return LockKey(Crypto.derive(password, salt, Crypto.ITERATIONS), salt, Crypto.ITERATIONS)
    }

    /** A key from known parts, for tests and test values. */
    fun keyFor(password: String, salt: ByteArray, iterations: Int) = LockKey(Crypto.derive(password, salt, iterations), salt, iterations)

    /** The text of the locked note that holds [plain]. Each call makes a new nonce. */
    fun seal(key: LockKey, plain: String): String {
        val b64 = Base64.getEncoder()
        val body = b64.encodeToString(Crypto.seal(key.keys, plain.toByteArray(Charsets.UTF_8)))
        val out = StringBuilder("$MAGIC v1 pbkdf2-sha256 ${key.iterations} ${b64.encodeToString(key.salt)}\n\n$NOTICE\n\n")
        body.chunked(76).forEachIndexed { i, chunk ->
            if (i > 0) out.append('\n')
            out.append(chunk)
        }
        out.append('\n')
        return out.toString()
    }

    private class Parts(val salt: ByteArray, val iterations: Int, val sealed: ByteArray)

    /** The salt, the iteration count and the sealed bytes of a locked note. */
    private fun parts(text: String): Parts {
        val bad = "This note is not in the locked note format."
        val lines = text.lines()
        val head = lines.firstOrNull()?.trim()?.split(Regex("\\s+")) ?: throw SyncException(bad)
        if (head.isEmpty() || head[0] != MAGIC) throw SyncException(bad)
        // Only "v1 pbkdf2-sha256 <iterations> <salt>" opens. Another header is a newer format.
        if (head.size != 5 || head[1] != "v1" || head[2] != "pbkdf2-sha256") {
            if (head.size >= 2) throw SyncException("This note is locked with a newer format (${head[1]}). Update Quire to open it.")
            throw SyncException(bad)
        }
        val iterations = head[3].toIntOrNull()?.takeIf { it > 0 } ?: throw SyncException(bad)
        val dec = Base64.getDecoder()
        val salt = try {
            dec.decode(head[4])
        } catch (e: IllegalArgumentException) {
            throw SyncException(bad)
        }
        // The body is every line after the notice, joined.
        val body = lines.drop(1).filter { it.isNotBlank() && it != NOTICE }.joinToString("") { it.trim() }
        val sealed = try {
            dec.decode(body)
        } catch (e: IllegalArgumentException) {
            throw SyncException(bad)
        }
        return Parts(salt, iterations, sealed)
    }

    private fun utf8(b: ByteArray): String {
        return try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            throw SyncException("The note is not text.")
        }
    }

    /**
     * The plain text and the key of a locked note, when the password is right. It runs PBKDF2,
     * so call it off the main thread.
     */
    fun open(text: String, password: String): Opened {
        val p = parts(text)
        val keys = Crypto.derive(password, p.salt, p.iterations)
        val plain = try {
            Crypto.open(keys, p.sealed)
        } catch (e: SyncException) {
            throw SyncException("The password does not open this note.")
        }
        return Opened(utf8(plain), LockKey(keys, p.salt, p.iterations))
    }

    /**
     * The plain text of a locked note with a key from before, for a note that changed on disk
     * while it was unlocked. Fails when another device locked it with another password.
     */
    fun openWith(key: LockKey, text: String): String {
        val p = parts(text)
        if (!p.salt.contentEquals(key.salt)) throw SyncException("The note has another password now.")
        val plain = try {
            Crypto.open(key.keys, p.sealed)
        } catch (e: SyncException) {
            throw SyncException("The note has another password now.")
        }
        return utf8(plain)
    }
}
