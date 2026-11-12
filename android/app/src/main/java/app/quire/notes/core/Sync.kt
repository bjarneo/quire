package app.quire.notes.core

/** A storage backend from providerDefs() in the prototype. */
data class Provider(
    val id: String,
    val name: String,
    val full: String,
    val kind: String,
    val regions: List<Pair<String, String>>? = null,
    val ep: ((Map<String, String>) -> String)? = null,
)

/** One field of the provider form. [type] is null for a plain text field. */
data class SyncField(
    val key: String,
    val label: String,
    val req: Boolean = true,
    val type: String? = null,
    val wide: Boolean = false,
    val def: String = "",
    val hint: String = "",
    val ph: String = "",
    val opts: List<Pair<String, String>> = emptyList(),
    val value: String = "",
)

object Sync {
    /** Fields that hold credentials. Quire keeps these in the Android Keystore, not in plain preferences. */
    val secretKeys = setOf("secretKey", "azKey", "hmacSecret", "gcsJson")

    private fun plain(vararg r: String) = r.map { it to it }

    val providers: List<Provider> = listOf(
        Provider("none", "Local only", "", "no sync"),
        Provider("s3", "Amazon S3", "Amazon S3", "S3", plain("us-east-1", "us-west-2", "eu-west-1", "eu-central-1", "ap-southeast-1")) { "https://s3.${it["region"]}.amazonaws.com" },
        Provider(
            "hetzner", "Hetzner", "Hetzner Object Storage", "Object Storage · S3",
            listOf("fsn1" to "fsn1 · Falkenstein", "nbg1" to "nbg1 · Nuremberg", "hel1" to "hel1 · Helsinki")
        ) { "https://${it["region"]}.your-objectstorage.com" },
        Provider("azure", "Azure", "Azure Blob Storage", "Blob Storage"),
        Provider("gcs", "Google Cloud", "Google Cloud Storage", "Cloud Storage"),
        Provider("r2", "Cloudflare R2", "Cloudflare R2", "S3-compatible") {
            "https://${it["accountId"]?.ifEmpty { null } ?: "<account-id>"}.r2.cloudflarestorage.com"
        },
        Provider("b2", "Backblaze B2", "Backblaze B2", "S3-compatible", plain("us-west-004", "us-east-005", "eu-central-003")) { "https://s3.${it["region"]}.backblazeb2.com" },
        Provider("do", "DigitalOcean", "DigitalOcean Spaces", "Spaces · S3", plain("ams3", "fra1", "nyc3", "sfo3", "sgp1")) { "https://${it["region"]}.digitaloceanspaces.com" },
        Provider("wasabi", "Wasabi", "Wasabi", "S3-compatible", plain("us-east-1", "eu-central-1", "ap-northeast-1")) { "https://s3.${it["region"]}.wasabisys.com" },
        Provider("scaleway", "Scaleway", "Scaleway Object Storage", "S3-compatible", plain("fr-par", "nl-ams", "pl-waw")) { "https://s3.${it["region"]}.scw.cloud" },
        Provider("ovh", "OVHcloud", "OVHcloud Object Storage", "S3-compatible", plain("gra", "sbg", "de", "uk", "waw", "bhs")) { "https://s3.${it["region"]}.io.cloud.ovh.net" },
        Provider("custom", "Other", "S3-compatible server", "MinIO, Garage, Ceph…"),
    )

    fun provider(id: String): Provider? = providers.find { it.id == id }

    /** The form for a provider. Follows syncSpec() in the prototype. */
    fun spec(pid: String, cfg: Map<String, String>, vaultName: String): List<SyncField> {
        val p = provider(pid)
        if (p == null || pid == "none") return emptyList()
        val out = ArrayList<SyncField>()
        fun f(field: SyncField) = out.add(field)
        fun v(k: String, d: String): String = cfg[k]?.takeIf { it.isNotEmpty() } ?: d
        fun prefix() = f(SyncField("prefix", "Remote folder", req = false, def = "quire/$vaultName/", hint = "inside the bucket"))

        if (pid == "azure") {
            f(SyncField("account", "Storage account", ph = "mynotesstorage"))
            f(SyncField("container", "Container", ph = "notes"))
            f(SyncField("azAuth", "Sign in with", type = "choice", wide = true, req = false, def = "key", opts = listOf("key" to "account key", "sas" to "SAS token")))
            f(SyncField("azKey", if (v("azAuth", "key") == "sas") "SAS token" else "Account key", type = "secret", wide = true))
            prefix()
            f(SyncField("ep", "Endpoint", type = "read", wide = true, req = false, value = "https://${v("account", "<account>")}.blob.core.windows.net/${v("container", "<container>")}"))
            return out
        }
        if (pid == "gcs") {
            f(SyncField("bucket", "Bucket", ph = "my-notes"))
            prefix()
            f(SyncField("gcsAuth", "Sign in with", type = "choice", wide = true, req = false, def = "sa", opts = listOf("sa" to "service account key", "hmac" to "HMAC keys")))
            if (v("gcsAuth", "sa") == "sa") {
                f(SyncField("gcsJson", "Service account key", type = "area", wide = true, hint = "paste the JSON key file", ph = "{ \"type\": \"service_account\", \"project_id\": \"…\" }"))
            } else {
                f(SyncField("hmacId", "Access ID"))
                f(SyncField("hmacSecret", "Secret", type = "secret"))
            }
            f(SyncField("ep", "Endpoint", type = "read", wide = true, req = false, value = "https://storage.googleapis.com/${v("bucket", "<bucket>")}"))
            return out
        }
        val regions = p.regions
        if (regions != null) f(SyncField("region", "Region", type = "choice", wide = true, req = false, opts = regions, def = regions[0].first))
        if (pid == "r2") f(SyncField("accountId", "Account ID", wide = true, ph = "from the R2 overview page"))
        if (pid == "custom") {
            f(SyncField("endpoint", "Endpoint", wide = true, ph = "https://minio.example.com:9000"))
            f(SyncField("region", "Region", req = false, def = "us-east-1"))
            f(SyncField("addressing", "Addressing", type = "choice", req = false, def = "path", opts = listOf("path" to "path-style", "vhost" to "virtual-hosted")))
        }
        f(SyncField("bucket", if (pid == "do") "Space name" else "Bucket", ph = "my-notes"))
        prefix()
        f(SyncField("accessKey", if (pid == "b2") "Key ID" else "Access key ID"))
        f(SyncField("secretKey", if (pid == "b2") "Application key" else "Secret access key", type = "secret"))
        val ep = p.ep
        if (ep != null) {
            val region = v("region", regions?.get(0)?.first ?: "")
            f(SyncField("ep", "Endpoint", type = "read", wide = true, req = false, value = ep(cfg + ("region" to region))))
        }
        return out
    }

    /** The required fields that are still empty. Follows testSync() in the prototype. */
    fun missing(spec: List<SyncField>, cfg: Map<String, String>): List<SyncField> =
        spec.filter { d -> d.req && (cfg[d.key] ?: d.def).trim().isEmpty() }
}
