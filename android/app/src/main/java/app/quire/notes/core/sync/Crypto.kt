package app.quire.notes.core.sync

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The two keys from a passphrase. [enc] encrypts. [names] names the objects. */
class Keys(val enc: ByteArray, val names: ByteArray)

/**
 * Encryption before upload: AES-256-GCM, with keys from the passphrase by PBKDF2-HMAC-SHA256.
 * Follows desktop/src/sync/crypto.rs, so both apps read the same files.
 */
object Crypto {
    const val ITERATIONS = 600_000
    private val MAGIC = "QUIRE1".toByteArray(Charsets.US_ASCII)
    private val rng = SecureRandom()

    // The last derived keys. The derivation takes about a second on a phone, and each sync needs it.
    private var cached: Pair<String, Keys>? = null

    /** 64 bytes from the passphrase: the first 32 encrypt, the last 32 name the objects. */
    fun derive(pass: String, salt: ByteArray, iterations: Int): Keys {
        val id = Sign.sha256Hex(pass) + ":" + Sign.hex(salt) + ":" + iterations
        synchronized(this) { cached?.let { if (it.first == id) return it.second } }
        val out = pbkdf2(pass.toByteArray(Charsets.UTF_8), salt, iterations, 64)
        val k = Keys(out.copyOfRange(0, 32), out.copyOfRange(32, 64))
        synchronized(this) { cached = id to k }
        return k
    }

    /** PBKDF2 with HMAC-SHA256, from RFC 8018. The platform factory takes chars, so Quire does the bytes itself. */
    fun pbkdf2(pass: ByteArray, salt: ByteArray, iterations: Int, len: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (pass.isEmpty()) ByteArray(1) else pass, "HmacSHA256"))
        val out = ByteArray(len)
        var block = 1
        var pos = 0
        while (pos < len) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            val u = mac.doFinal()
            val t = u.copyOf()
            for (n in 1 until iterations) {
                mac.update(u)
                mac.doFinal(u, 0)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            val take = minOf(t.size, len - pos)
            System.arraycopy(t, 0, out, pos, take)
            pos += take
            block++
        }
        return out
    }

    fun random(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    /** "QUIRE1", a 12-byte nonce, then the ciphertext with its tag. */
    fun seal(k: Keys, plain: ByteArray): ByteArray {
        val nonce = random(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k.enc, "AES"), GCMParameterSpec(128, nonce))
        return MAGIC + nonce + c.doFinal(plain)
    }

    fun open(k: Keys, data: ByteArray): ByteArray {
        if (data.size < MAGIC.size + 12 + 16 || !data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw SyncException("A remote file is not in Quire's encrypted format.")
        }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(k.enc, "AES"), GCMParameterSpec(128, data, MAGIC.size, 12))
        return try {
            c.doFinal(data, MAGIC.size + 12, data.size - MAGIC.size - 12)
        } catch (e: AEADBadTagException) {
            throw SyncException("The passphrase does not open the remote notes.")
        }
    }

    /** The object name of a note, so the store never sees the path of the note. */
    fun objectName(k: Keys, path: String): String =
        "objects/" + Sign.hex(Sign.hmac(k.names, path.toByteArray(Charsets.UTF_8))).substring(0, 40) + ".bin"
}

/** A sync failure with a message for the person. */
class SyncException(msg: String) : Exception(msg)
