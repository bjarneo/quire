package app.quire.notes.core.sync

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Request signing: AWS Signature Version 4 for S3, the S3-compatible stores and Google Cloud HMAC
 * keys, and Azure Shared Key. Plus the UTC dates that both need. Follows desktop/src/sync/sign.rs.
 */
object Sign {
    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun sha256Hex(data: ByteArray): String = hex(sha256(data))

    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    fun hex(b: ByteArray): String {
        val out = StringBuilder(b.size * 2)
        for (x in b) {
            val v = x.toInt() and 0xff
            out.append(HEX[v shr 4]).append(HEX[v and 15])
        }
        return out.toString()
    }

    private const val HEX = "0123456789abcdef"

    /**
     * HMAC-SHA256. HMAC pads a short key with zero bytes, so one zero byte is the same key as an
     * empty key. Java does not accept an empty key.
     */
    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(1) else key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /**
     * Percent-encodes as AWS and Azure expect. Unreserved characters stay, "/" stays when
     * [keepSlash] is true, and each other byte becomes %XX.
     */
    fun uriEncode(s: String, keepSlash: Boolean): String {
        val out = StringBuilder(s.length)
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val v = b.toInt() and 0xff
            val c = v.toChar()
            if (v < 128 && (c.isLetterOrDigit() || c in "-._~" || (keepSlash && c == '/'))) {
                out.append(c)
            } else {
                out.append('%').append(HEX_UP[v shr 4]).append(HEX_UP[v and 15])
            }
        }
        return out.toString()
    }

    private const val HEX_UP = "0123456789ABCDEF"

    fun nowSecs(): Long = System.currentTimeMillis() / 1000

    private class Utc(val y: Long, val m: Int, val d: Int, val hh: Int, val mm: Int, val ss: Int, val weekday: Int)

    /** The civil date and time in UTC, from Howard Hinnant's days-to-civil. */
    private fun utc(secs: Long): Utc {
        val days = Math.floorDiv(secs, 86_400L)
        val rem = Math.floorMod(secs, 86_400L)
        val z = days + 719_468
        val era = Math.floorDiv(z, 146_097L)
        val doe = Math.floorMod(z, 146_097L)
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val y = yoe + era * 400 + if (m <= 2) 1 else 0
        // 1970-01-01 was a Thursday.
        val weekday = Math.floorMod(days + 4, 7L).toInt()
        return Utc(y, m, d, (rem / 3600).toInt(), ((rem % 3600) / 60).toInt(), (rem % 60).toInt(), weekday)
    }

    private fun p2(n: Int) = n.toString().padStart(2, '0')

    /** "20130524T000000Z" and "20130524". */
    fun amzDates(secs: Long): Pair<String, String> {
        val t = utc(secs)
        val date = t.y.toString().padStart(4, '0') + p2(t.m) + p2(t.d)
        return "${date}T${p2(t.hh)}${p2(t.mm)}${p2(t.ss)}Z" to date
    }

    private val DAYS = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    private val MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** "Fri, 24 May 2013 00:00:00 GMT", the form that Azure wants in x-ms-date. */
    fun rfc1123(secs: Long): String {
        val t = utc(secs)
        return "${DAYS[t.weekday]}, ${p2(t.d)} ${MONTHS[t.m - 1]} ${t.y.toString().padStart(4, '0')} ${p2(t.hh)}:${p2(t.mm)}:${p2(t.ss)} GMT"
    }

    class SigV4(val access: String, val secret: String, val region: String, val service: String) {
        /**
         * The Authorization header for one request. [headers] have lowercase names and must
         * include host. [query] is the canonical query string, already encoded and sorted.
         */
        fun authorization(
            method: String,
            uri: String,
            query: String,
            headers: List<Pair<String, String>>,
            payloadHash: String,
            amzDate: String,
            date: String,
        ): String {
            val hs = headers.sortedBy { it.first }
            val canonicalHeaders = hs.joinToString("") { "${it.first}:${it.second.trim()}\n" }
            val signed = hs.joinToString(";") { it.first }
            val canonical = "$method\n$uri\n$query\n$canonicalHeaders\n$signed\n$payloadHash"
            val scope = "$date/$region/$service/aws4_request"
            val toSign = "AWS4-HMAC-SHA256\n$amzDate\n$scope\n${sha256Hex(canonical)}"
            val kDate = hmac("AWS4$secret".toByteArray(Charsets.UTF_8), date.toByteArray(Charsets.UTF_8))
            val kRegion = hmac(kDate, region.toByteArray(Charsets.UTF_8))
            val kService = hmac(kRegion, service.toByteArray(Charsets.UTF_8))
            val kSigning = hmac(kService, "aws4_request".toByteArray(Charsets.UTF_8))
            val signature = hex(hmac(kSigning, toSign.toByteArray(Charsets.UTF_8)))
            return "AWS4-HMAC-SHA256 Credential=$access/$scope, SignedHeaders=$signed, Signature=$signature"
        }
    }

    /**
     * Azure Shared Key: the signature over the fixed fields of the request, its x-ms headers and
     * its resource. [contentLength] is empty for a request without a body.
     */
    fun azureSharedKey(
        key: ByteArray,
        account: String,
        method: String,
        contentLength: String,
        contentType: String,
        xMs: List<Pair<String, String>>,
        resource: String,
    ): String {
        val hs = xMs.map { it.first.lowercase() to it.second }.sortedBy { it.first }
        val canonicalHeaders = hs.joinToString("") { "${it.first}:${it.second.trim()}\n" }
        // VERB, Content-Encoding, Content-Language, Content-Length, Content-MD5, Content-Type, Date,
        // If-Modified-Since, If-Match, If-None-Match, If-Unmodified-Since, Range.
        val toSign = "$method\n\n\n$contentLength\n\n$contentType\n\n\n\n\n\n\n$canonicalHeaders$resource"
        val sig = java.util.Base64.getEncoder().encodeToString(hmac(key, toSign.toByteArray(Charsets.UTF_8)))
        return "SharedKey $account:$sig"
    }
}
