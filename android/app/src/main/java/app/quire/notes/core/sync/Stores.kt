package app.quire.notes.core.sync

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.SSLException

/**
 * An object store. It gets, puts and deletes objects under the remote folder. All calls block.
 * The sync calls it from 8 threads at once, so an implementation must be safe for that.
 */
interface ObjStore {
    /** The bytes of the object, or null when it does not exist. */
    fun get(key: String): ByteArray?
    fun put(key: String, body: ByteArray)
    fun delete(key: String)

    /** The bucket or the container, for messages. */
    val place: String
}

/** HTTP over HttpURLConnection. A transport failure becomes a [SyncException] with a plain message. */
internal object Http {
    class Response(val code: Int, val body: ByteArray) {
        val text: String get() = String(body, Charsets.UTF_8)
    }

    private const val LIMIT = 256 * 1024 * 1024

    /** The waits between tries scale by this. Tests make it small. */
    @Volatile
    var waitScale = 1.0

    /** A request that got no answer, and whether another try can help. */
    class Fail(msg: String, val again: Boolean) : Exception(msg)

    /**
     * Runs [once] up to 5 times. It tries again after 0.5, 1, 2 and 4 seconds when the service
     * is busy (429, 500, 502, 503, 504) or the connection failed. Object stores send these now
     * and then, and one of them must not fail a sync of hundreds of notes.
     */
    fun retry(once: () -> Response): Response {
        val waits = doubleArrayOf(0.5, 1.0, 2.0, 4.0)
        var i = 0
        while (true) {
            var fail: Fail? = null
            val r = try {
                once()
            } catch (f: Fail) {
                fail = f
                null
            }
            val again = if (r != null) r.code in setOf(429, 500, 502, 503, 504) else fail!!.again
            if (!again || i == waits.size) {
                if (r != null) return r
                throw SyncException(fail!!.message ?: "The connection broke.")
            }
            Thread.sleep((waits[i] * waitScale * 1000).toLong().coerceAtLeast(1))
            i++
        }
    }

    /** The message for a busy or failing service, the same for every provider. */
    fun busy(code: Int): String? = when (code) {
        429 -> "The storage service asks Quire to slow down (429). Quire tries again on the next sync."
        in 502..504 -> "The storage service did not answer in time ($code). Quire tries again on the next sync."
        in 500..599 -> "The storage service had an error ($code). Quire tries again on the next sync."
        else -> null
    }

    /** "host" or "host:port" from a URL, without a default port. Null when the URL has no host. */
    fun hostPort(url: String): String? {
        val scheme = url.substringBefore("://", "").lowercase()
        val rest = url.substringAfter("://", url)
        var hp = rest.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
        if (hp.isEmpty()) return null
        if ((scheme == "https" && hp.endsWith(":443")) || (scheme == "http" && hp.endsWith(":80"))) hp = hp.substringBeforeLast(':')
        return hp
    }

    /** One request with tries again, as [retry] says. */
    fun send(method: String, url: String, headers: List<Pair<String, String>>, body: ByteArray?): Response =
        retry { once(method, url, headers, body) }

    /** One request: status and body. A transport failure becomes a [Fail] with a plain message. */
    fun once(method: String, url: String, headers: List<Pair<String, String>>, body: ByteArray?): Response {
        val where = "Could not reach ${hostPort(url) ?: "the server"}."
        val u = try {
            val uri = URI(url)
            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrEmpty()) throw IllegalArgumentException()
            uri.toURL()
        } catch (e: Exception) {
            throw Fail("$where The endpoint is not a valid URL.", false)
        }
        val c = try {
            u.openConnection() as HttpURLConnection
        } catch (e: IOException) {
            throw Fail("$where The connection broke.", true)
        }
        try {
            val code = try {
                c.connectTimeout = 15_000
                c.readTimeout = 120_000
                c.instanceFollowRedirects = false
                c.useCaches = false
                c.requestMethod = method
                for ((k, v) in headers) c.setRequestProperty(k, v)
                if (body != null) {
                    c.doOutput = true
                    c.setFixedLengthStreamingMode(body.size)
                    c.outputStream.use { it.write(body) }
                }
                c.responseCode
            } catch (e: UnknownHostException) {
                throw Fail("$where The host name does not resolve. Check the endpoint and the region.", false)
            } catch (e: SSLException) {
                throw Fail("$where The TLS check failed: ${e.message ?: e.javaClass.simpleName}.", true)
            } catch (e: ConnectException) {
                throw Fail("$where The connection was refused or timed out.", true)
            } catch (e: NoRouteToHostException) {
                throw Fail("$where The connection was refused or timed out.", true)
            } catch (e: SocketTimeoutException) {
                throw Fail("$where The connection was refused or timed out.", true)
            } catch (e: IOException) {
                throw Fail("$where The connection broke.", true)
            } catch (e: SecurityException) {
                throw Fail("$where The connection broke.", false)
            }
            val bytes = try {
                val stream = if (code >= 400) c.errorStream else c.inputStream
                stream?.use { read(it) } ?: ByteArray(0)
            } catch (e: SyncException) {
                throw e
            } catch (e: IOException) {
                throw Fail("The connection broke: ${e.message ?: e.javaClass.simpleName}", true)
            }
            return Response(code, bytes)
        } finally {
            c.disconnect()
        }
    }

    private fun read(s: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > LIMIT) throw SyncException("The server sent a file that is too large.")
        }
        return out.toByteArray()
    }

    /** A message for a failed request, from its status and the XML or JSON error of the service. */
    fun failure(code: Int, body: ByteArray, secretLabel: String): String {
        val text = String(body, Charsets.UTF_8)
        fun tag(t: String): String? {
            val open = "<$t>"
            val i = text.indexOf(open)
            if (i < 0) return null
            return text.substring(i + open.length).substringBefore("<$t>").substringBefore("</$t>")
        }
        val name = tag("Code") ?: ""
        busy(code)?.let { return it }
        return when {
            name == "NoSuchBucket" || name == "ContainerNotFound" -> "The bucket does not exist. Check the bucket name and the region."
            code == 403 || code == 401 || name == "SignatureDoesNotMatch" || name == "InvalidAccessKeyId" || name == "AuthenticationFailed" ->
                "Access denied ($code). Check the $secretLabel."
            code == 301 || name == "PermanentRedirect" || name == "AuthorizationHeaderMalformed" ->
                "The bucket is in another region. Pick the region it was made in."
            else -> "The server answered $code. ${tag("Message") ?: text.take(160)}"
        }
    }
}

/** S3 and the S3-compatible stores, with Signature Version 4. */
class S3(
    endpoint: String,
    bucket: String,
    region: String,
    access: String,
    secret: String,
    private val pathStyle: Boolean,
    private val prefix: String,
    /** The name of the secret field, for messages, for example "secret access key". */
    private val secretLabel: String,
) : ObjStore {
    private val endpoint = endpoint.trim().trimEnd('/')
    private val bucket = bucket.trim()
    private val region = region.trim().ifEmpty { "us-east-1" }
    private val access = access.trim()
    private val secret = secret.trim()

    override val place: String get() = bucket

    private fun request(method: String, key: String, body: ByteArray?): Http.Response {
        val scheme = endpoint.substringBefore("://", "https")
        // The signed host must be the Host header that HttpURLConnection sends, so a default port goes.
        val hostPort = Http.hostPort(if (endpoint.contains("://")) endpoint else "https://$endpoint") ?: ""
        val obj = Sign.uriEncode(prefix + key, true)
        val host = if (pathStyle) hostPort else "$bucket.$hostPort"
        val uri = if (pathStyle) "/${Sign.uriEncode(bucket, false)}/$obj" else "/$obj"
        val payload = Sign.sha256Hex(body ?: ByteArray(0))
        // Each try signs again, with the time of that try.
        return Http.retry {
            val (amzDate, date) = Sign.amzDates(Sign.nowSecs())
            val signed = listOf("host" to host, "x-amz-content-sha256" to payload, "x-amz-date" to amzDate)
            val auth = Sign.SigV4(access, secret, region, "s3").authorization(method, uri, "", signed, payload, amzDate, date)
            val headers = mutableListOf("x-amz-content-sha256" to payload, "x-amz-date" to amzDate, "Authorization" to auth)
            if (body != null) headers.add("Content-Type" to "application/octet-stream")
            Http.once(method, "$scheme://$host$uri", headers, body)
        }
    }

    override fun get(key: String): ByteArray? {
        val r = request("GET", key, null)
        return when {
            r.code == 200 -> r.body
            r.code == 404 && !r.text.contains("NoSuchBucket") -> null
            else -> throw SyncException(Http.failure(r.code, r.body, secretLabel))
        }
    }

    override fun put(key: String, body: ByteArray) {
        val r = request("PUT", key, body)
        if (r.code !in 200..299) throw SyncException(Http.failure(r.code, r.body, secretLabel))
    }

    override fun delete(key: String) {
        val r = request("DELETE", key, null)
        if (r.code !in 200..299 && r.code != 404) throw SyncException(Http.failure(r.code, r.body, secretLabel))
    }
}

/** Azure Blob Storage, with an account key or a SAS token. */
class Azure(
    account: String,
    container: String,
    auth: String,
    secret: String,
    private val prefix: String,
    /** "https://<account>.blob.core.windows.net", or another base for a test server. */
    base: String? = null,
) : ObjStore {
    private val account = account.trim()
    private val container = container.trim()
    private val key: ByteArray?
    private val sas: String?
    private val base = (base ?: "https://${this.account}.blob.core.windows.net").trimEnd('/')

    init {
        if (auth == "sas") {
            key = null
            sas = secret.trim().trimStart('?')
        } else {
            key = try {
                Base64.getDecoder().decode(secret.trim())
            } catch (e: IllegalArgumentException) {
                throw SyncException("The account key is not valid base64. Copy it again from the Azure portal.")
            }
            sas = null
        }
    }

    override val place: String get() = container

    private val label get() = if (sas != null) "SAS token" else "account key"

    private fun request(method: String, key: String, body: ByteArray?): Http.Response {
        val blob = Sign.uriEncode(prefix + key, true)
        val rest = base.substringAfter("://", "")
        val basePath = if (rest.contains('/')) rest.substring(rest.indexOf('/')) else ""
        val cont = Sign.uriEncode(container, false)
        val path = "${basePath.trimEnd('/')}/$cont/$blob"
        var url = "$base/$cont/$blob"
        if (sas != null) url += "?$sas"
        return Http.retry {
            val xMs = mutableListOf("x-ms-date" to Sign.rfc1123(Sign.nowSecs()), "x-ms-version" to "2021-08-06")
            if (method == "PUT") xMs.add("x-ms-blob-type" to "BlockBlob")
            val headers = ArrayList(xMs)
            val type = if (body != null) "application/octet-stream" else ""
            if (body != null) headers.add("Content-Type" to type)
            this.key?.let { k ->
                val len = if (body == null || body.isEmpty()) "" else body.size.toString()
                headers.add("Authorization" to Sign.azureSharedKey(k, account, method, len, type, xMs, "/$account$path"))
            }
            Http.once(method, url, headers, body)
        }
    }

    override fun get(key: String): ByteArray? {
        val r = request("GET", key, null)
        return when {
            r.code == 200 -> r.body
            r.code == 404 && !r.text.contains("ContainerNotFound") -> null
            else -> throw SyncException(Http.failure(r.code, r.body, label))
        }
    }

    override fun put(key: String, body: ByteArray) {
        val r = request("PUT", key, body)
        if (r.code !in 200..299) throw SyncException(Http.failure(r.code, r.body, label))
    }

    override fun delete(key: String) {
        val r = request("DELETE", key, null)
        if (r.code !in 200..299 && r.code != 404) throw SyncException(Http.failure(r.code, r.body, "account key"))
    }
}

/**
 * Google Cloud Storage with a service account key. A signed JWT buys an OAuth token, and the JSON
 * API moves the objects.
 */
class Gcs(bucket: String, json: String, private val prefix: String) : ObjStore {
    private val bucket = bucket.trim()
    private val email: String
    private val key: PrivateKey
    private val tokenUri: String
    private var token: String? = null
    private var tokenUntil = 0L

    init {
        val v = try {
            Json.parse(json) as? Map<*, *>
        } catch (e: Exception) {
            null
        } ?: throw SyncException("The service account key is not valid JSON. Paste the whole key file.")
        fun s(k: String) = v[k] as? String
        email = s("client_email") ?: throw SyncException("The key file has no client_email.")
        val pem = s("private_key") ?: throw SyncException("The key file has no private_key.")
        key = try {
            val b64 = pem.replace(Regex("-----[A-Z ]+-----"), "").replace(Regex("\\s"), "")
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)))
        } catch (e: Exception) {
            throw SyncException("The private key in the key file could not be read.")
        }
        tokenUri = s("token_uri") ?: "https://oauth2.googleapis.com/token"
    }

    override val place: String get() = bucket

    @Synchronized
    private fun token(): String {
        token?.let { if (System.nanoTime() < tokenUntil) return it }
        val b64 = Base64.getUrlEncoder().withoutPadding()
        val now = Sign.nowSecs()
        val header = b64.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val claims = Json.write(
            mapOf(
                "aud" to tokenUri, "exp" to now + 3600, "iat" to now, "iss" to email,
                "scope" to "https://www.googleapis.com/auth/devstorage.read_write",
            )
        )
        val body = header + "." + b64.encodeToString(claims.toByteArray(Charsets.UTF_8))
        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(key)
            update(body.toByteArray(Charsets.US_ASCII))
            sign()
        }
        val jwt = body + "." + b64.encodeToString(sig)
        val form = "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=$jwt"
        val r = Http.send("POST", tokenUri, listOf("Content-Type" to "application/x-www-form-urlencoded"), form.toByteArray(Charsets.US_ASCII))
        if (r.code != 200) throw SyncException("Google refused the service account key (${r.code}). Check that the key is current.")
        val v = try {
            Json.parse(r.text) as? Map<*, *>
        } catch (e: Exception) {
            null
        }
        val t = v?.get("access_token") as? String ?: throw SyncException("Google gave no access token.")
        val ttl = ((v["expires_in"] as? Long) ?: 3600L) - 120
        token = t
        tokenUntil = System.nanoTime() + maxOf(ttl, 0L) * 1_000_000_000L
        return t
    }

    private fun objectUrl(key: String) =
        "https://storage.googleapis.com/storage/v1/b/${Sign.uriEncode(bucket, false)}/o/${Sign.uriEncode(prefix + key, false)}"

    override fun get(key: String): ByteArray? {
        val t = token()
        val r = Http.send("GET", objectUrl(key) + "?alt=media", listOf("Authorization" to "Bearer $t"), null)
        Http.busy(r.code)?.let { throw SyncException(it) }
        return when {
            r.code == 200 -> r.body
            r.code == 404 && !r.text.contains("bucket does not exist") -> null
            r.code == 404 -> throw SyncException("The bucket does not exist. Check the bucket name.")
            r.code == 401 || r.code == 403 ->
                throw SyncException("Access denied (${r.code}). Give the service account the Storage Object Admin role on the bucket.")
            else -> throw SyncException("Google Cloud answered ${r.code}.")
        }
    }

    override fun put(key: String, body: ByteArray) {
        val t = token()
        val url = "https://storage.googleapis.com/upload/storage/v1/b/${Sign.uriEncode(bucket, false)}/o?uploadType=media&name=" +
            Sign.uriEncode(prefix + key, false)
        val r = Http.send("POST", url, listOf("Authorization" to "Bearer $t", "Content-Type" to "application/octet-stream"), body)
        if (r.code !in 200..299) throw SyncException(Http.busy(r.code) ?: "Google Cloud refused the upload (${r.code}).")
    }

    override fun delete(key: String) {
        val t = token()
        val r = Http.send("DELETE", objectUrl(key), listOf("Authorization" to "Bearer $t"), null)
        if (r.code !in 200..299 && r.code != 404) throw SyncException(Http.busy(r.code) ?: "Google Cloud refused the delete (${r.code}).")
    }
}
