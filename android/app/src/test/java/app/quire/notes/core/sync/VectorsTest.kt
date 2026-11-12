package app.quire.notes.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/** The test vectors in SYNC.md. The desktop app prints them with `cargo test print_vectors`. */
class VectorsTest {
    private val keys = Crypto.derive("correct horse battery staple", "0123456789abcdef".toByteArray(), 1000)
    private fun b64(s: String) = Base64.getDecoder().decode(s)

    @Test
    fun saltEncodesAsInTheSpec() {
        assertEquals("MDEyMzQ1Njc4OWFiY2RlZg==", Base64.getEncoder().encodeToString("0123456789abcdef".toByteArray()))
    }

    @Test
    fun keysMatchTheDesktopApp() {
        assertEquals("caa4aad92ca0635b01e04707f5fd851b42533044bec2a2ec393e589a2441a652", Sign.hex(keys.enc))
        assertEquals("d86dac01e25c2374f088dcdb35777a514fdffe3c66b5b055cdf2ac23671f52f2", Sign.hex(keys.names))
    }

    @Test
    fun objectNameMatchesTheDesktopApp() {
        assertEquals("objects/106404d37208edd67089d2ad1258f2d0385c19fa.bin", Crypto.objectName(keys, "Drafts/Plan.md"))
    }

    @Test
    fun opensBlobsThatTheDesktopAppSealed() {
        assertEquals("# Blåbær", String(Crypto.open(keys, b64("UVVJUkUxyHeerIFLzO6WIrgd6vd6nFBCHkNaN/NGe60nmwng72y4MbVhouA=")), Charsets.UTF_8))
        assertEquals("quire", String(Crypto.open(keys, b64("UVVJUkUx1R27W/XnzAUMDiCCWoIsHIQv6CJ8Dn8sKqPUlrdkcpwZ")), Charsets.UTF_8))
    }

    @Test
    fun sealsAndOpens() {
        val c = Crypto.seal(keys, "# Note".toByteArray())
        assertEquals("QUIRE1", String(c, 0, 6, Charsets.US_ASCII))
        assertEquals(6 + 12 + 6 + 16, c.size)
        assertEquals("# Note", String(Crypto.open(keys, c)))
        val wrong = Crypto.derive("another pass phrase", "0123456789abcdef".toByteArray(), 1000)
        try {
            Crypto.open(wrong, c)
            fail("a wrong key opened the blob")
        } catch (e: SyncException) {
            assertEquals("The passphrase does not open the remote notes.", e.message)
        }
        try {
            Crypto.open(keys, "# plain text, not sealed at all".toByteArray())
            fail("plain text opened")
        } catch (e: SyncException) {
            assertEquals("A remote file is not in Quire's encrypted format.", e.message)
        }
        assertNotEquals(Crypto.objectName(keys, "A.md"), Crypto.objectName(keys, "B.md"))
    }

    @Test
    fun pbkdf2MatchesRfc7914() {
        // RFC 7914, section 11: PBKDF2-HMAC-SHA256 of "passwd" with salt "salt", 1 iteration, 64 bytes.
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            Sign.hex(Crypto.pbkdf2("passwd".toByteArray(), "salt".toByteArray(), 1, 64)),
        )
    }

    @Test
    fun sha256OfANote() {
        assertEquals("1ee55b7ae632bd99dfea4f2d791ff06b1119975e96d4f822050923e7499f44cb", Sign.sha256Hex("# Plan"))
    }

    @Test
    fun sigV4MatchesTheAwsExample() {
        val s = Sign.SigV4("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "us-east-1", "s3")
        val empty = Sign.sha256Hex(ByteArray(0))
        val headers = listOf(
            "host" to "examplebucket.s3.amazonaws.com",
            "range" to "bytes=0-9",
            "x-amz-content-sha256" to empty,
            "x-amz-date" to "20130524T000000Z",
        )
        val auth = s.authorization("GET", "/test.txt", "", headers, empty, "20130524T000000Z", "20130524")
        assertTrue(auth, auth.endsWith("Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"))
        assertTrue(auth, auth.startsWith("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, SignedHeaders=host;range;x-amz-content-sha256;x-amz-date,"))
    }

    @Test
    fun datesAndEncoding() {
        assertEquals("20130524T000000Z" to "20130524", Sign.amzDates(1_369_353_600))
        assertEquals("Fri, 24 May 2013 00:00:00 GMT", Sign.rfc1123(1_369_353_600))
        assertEquals("Thu, 01 Jan 1970 00:00:00 GMT", Sign.rfc1123(0))
        assertEquals("20000229T235959Z" to "20000229", Sign.amzDates(951_868_799))
        assertEquals("Drafts/On%20slow%20software.md", Sign.uriEncode("Drafts/On slow software.md", true))
        assertEquals("a%2Fb", Sign.uriEncode("a/b", false))
        assertEquals("Bl%C3%A5b%C3%A6r", Sign.uriEncode("Blåbær", true))
        assertEquals("Ideas%20%28draft%29%20%232.md", Sign.uriEncode("Ideas (draft) #2.md", true))
    }

    @Test
    fun emptyHmacKeyIsAllowed() {
        // HMAC-SHA256 with an empty key and empty data, from a known value.
        assertEquals("b613679a0814d9ec772f95d778c35fc5ff1697c493715653c6c712144292c5ad", Sign.hex(Sign.hmac(ByteArray(0), ByteArray(0))))
    }

    @Test
    fun azureSharedKeyHasTheFieldsInOrder() {
        val key = "a key".toByteArray()
        val got = Sign.azureSharedKey(key, "acct", "PUT", "5", "application/octet-stream", listOf("x-ms-version" to "2021-08-06", "X-Ms-Date" to "d"), "/acct/c/b")
        val want = "PUT\n\n\n5\n\napplication/octet-stream\n\n\n\n\n\n\nx-ms-date:d\nx-ms-version:2021-08-06\n/acct/c/b"
        assertEquals("SharedKey acct:" + Base64.getEncoder().encodeToString(Sign.hmac(key, want.toByteArray())), got)
    }

    @Test
    fun jsonReadsAndWrites() {
        val v = Json.parse("""{"a": [1, 2.5, true, null, "x\"y\\zå\n"], "b": {}, "n": -12, "t": 1762941600000}""") as Map<*, *>
        assertEquals(listOf(1L, 2.5, true, null, "x\"y\\zå\n"), v["a"])
        assertEquals(emptyMap<String, Any>(), v["b"])
        assertEquals(-12L, v["n"])
        assertEquals(1762941600000L, v["t"])
        val text = Json.write(mapOf("k" to "q\"\n\u0001", "l" to listOf(1, 2L), "m" to emptyList<Any>()))
        assertEquals("""{"k":"q\"\n\u0001","l":[1,2],"m":[]}""", text)
        assertEquals(mapOf("k" to "q\"\n\u0001", "l" to listOf(1L, 2L), "m" to emptyList<Any>()), Json.parse(text))
        assertEquals("{\n  \"version\": 1,\n  \"encrypted\": false\n}", Json.write(linkedMapOf("version" to 1, "encrypted" to false), pretty = true))
        for (bad in listOf("", "{", "{\"a\":}", "[1,]x", "tru", "\"open")) {
            try {
                Json.parse(bad)
                fail("parsed $bad")
            } catch (e: IllegalArgumentException) {
                // Expected.
            }
        }
    }

    @Test
    fun namesAndPrefixes() {
        assertEquals("quire/Field Notes/", SyncEngine.cleanPrefix("/quire/Field Notes"))
        assertEquals("", SyncEngine.cleanPrefix(" / "))
        assertEquals("quire/Field Notes/", SyncEngine.prefixFor(emptyMap(), "Field Notes"))
        assertEquals("backup/", SyncEngine.prefixFor(mapOf("prefix" to "backup"), "Field Notes"))
        assertTrue(SyncEngine.syncable("Drafts/Plan.md"))
        assertFalse(SyncEngine.syncable(".trash/Plan.md"))
        assertFalse(SyncEngine.syncable("node_modules/x.md"))
        assertFalse(SyncEngine.syncable("../escape.md"))
        assertFalse(SyncEngine.syncable("/abs.md"))
        assertFalse(SyncEngine.syncable("a//b.md"))
        assertFalse(SyncEngine.syncable("Plan.txt"))
        assertEquals("Ideas (conflict Pixel 9).md", SyncEngine.conflictPath("Ideas.md", "Pixel 9", emptySet()))
        assertEquals("A/Ideas (conflict a-b 2).md", SyncEngine.conflictPath("A/Ideas.md", " a/b ", setOf("A/Ideas (conflict a-b).md")))
        assertEquals("just now", SyncEngine.ago(1000, 30_000))
        assertEquals("2m ago", SyncEngine.ago(0, 120_000))
        assertEquals("3h ago", SyncEngine.ago(0, 3 * 3_600_000L))
        assertEquals("2d ago", SyncEngine.ago(0, 2 * 86_400_000L))
    }
}
