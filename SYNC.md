# Quire sync format

Quire keeps a notes folder the same on every device through an object store that you own. The
desktop app and the Android app use the format in this file. A third app that follows it can
read and write the same notes.

The reference code is in `desktop/src/sync/`. The tests in `desktop/src/sync/tests.rs` run two
devices against one bucket.

## Remote folder

All objects of one notes folder sit under one key prefix. The default prefix is
`quire/<folder name>/`. You can change it in the **Remote folder** field. The prefix always ends
in `/`, unless it is empty.

| Key under the prefix | Content |
| --- | --- |
| `.quire/format.json` | Plain JSON. It tells if the notes are encrypted, and holds the key salt. |
| `.quire/manifest.json` | The manifest as plain JSON, for a folder that is not encrypted. |
| `.quire/manifest.bin` | The manifest as a sealed blob, for an encrypted folder. |
| `<path>` | The text of one note, for a folder that is not encrypted. Example: `Drafts/Plan.md`. |
| `objects/<name>.bin` | The sealed text of one note, for an encrypted folder. |

A note path is relative to the notes folder and uses `/` between folders. Only files that end in
`.md` sync. Files and folders whose names start with `.` do not sync, and neither does
`node_modules`. Line endings are `\n`. An app converts `\r\n` to `\n` when it reads a note.

## Format file

```json
{
  "version": 1,
  "encrypted": true,
  "kdf": "pbkdf2-sha256",
  "iterations": 600000,
  "salt": "<16 random bytes, standard base64>",
  "check": "<the sealed bytes of the ASCII text quire, standard base64>"
}
```

A folder that is not encrypted has only `version` and `encrypted`. The first device that syncs
writes this file. Every later device reads it first and stops with an error in these cases:

- The file says `encrypted` and the device has encryption off.
- The file does not say `encrypted` and the device has encryption on.
- The passphrase does not open `check`.

## Manifest

```json
{
  "version": 1,
  "files": {
    "Drafts/Plan.md": {
      "hash": "<SHA-256 of the note text as UTF-8, lowercase hex>",
      "object": "<the key of the note object, under the prefix>",
      "time": 1762941600000,
      "device": "laptop"
    }
  },
  "folders": ["Archive", "Drafts"]
}
```

`folders` lists every folder in the notes folder, empty folders included. A manifest without
`folders` has no folders. `time` is the upload time in milliseconds since 1970. `device` is the name of the device that
uploaded the text. The desktop app uses the host name. The Android app uses the device model.

## Encryption

1. Derive 64 bytes with PBKDF2-HMAC-SHA256 from the passphrase as UTF-8, the salt and the
   iteration count in the format file.
2. Use bytes 0 to 31 as the AES-256-GCM key.
3. Use bytes 32 to 63 as the name key.

A sealed blob is the ASCII bytes `QUIRE1`, then a random 12-byte nonce, then the AES-GCM
ciphertext with its 16-byte tag at the end. There is no associated data.

The object name of a note is `objects/` plus the first 40 hex characters of
HMAC-SHA256 with the name key over the note path as UTF-8, plus `.bin`. Thus the store sees
neither note names nor note text. The passphrase needs at least 12 characters.

## One sync

A device keeps a base record for each notes folder and store. The base maps each note path to
the hash that both sides had after the last sync on this device. It also lists the folders that
both sides had. Its JSON form is `{"files": {"<path>": "<hash>"}, "folders": ["<path>"]}`. The desktop app keeps it in `sync/`
in its config folder, which is `~/.config/quire/sync/` on Linux. The base never goes to the store.

For each path in the local folder, the manifest or the base, compare the local hash `L`, the
remote hash `R` and the base hash `B`:

| Local | Remote | Condition | Action |
| --- | --- | --- | --- |
| yes | yes | `L = R` | Nothing. The note is in step. |
| yes | no | `L = B` | Another device deleted the note. Move it to `.trash`. |
| yes | no | `L ≠ B` | Upload the note. It is new, or it changed after the remote delete. |
| no | yes | `R = B` | This device deleted the note. Delete the object and the manifest entry. |
| no | yes | `R ≠ B` | Download the note. |
| yes | yes | `B = R` | Only this device changed the note. Upload it. |
| yes | yes | `B = L` | Only the other device changed the note. Download it. |
| yes | yes | other | Both changed the note. See the next section. |

Then compare the folders in the same way. A folder is kept when a note is still in it after the
note steps. Such a note stayed, came down or went up in this sync.

| Local | Remote | Condition | Action |
| --- | --- | --- | --- |
| yes | yes | none | Nothing. |
| yes | no | in the base, no note kept in it | Another device deleted the folder. Move it to `.trash`. |
| yes | no | other | Add the folder to the manifest. |
| no | yes | in the base, no note kept in it | This device deleted the folder. Remove it from the manifest. |
| no | yes | other | Make the folder. |

Move the deepest folders to `.trash` first. A folder moves with every file in it, also files that
are not notes.

After these steps, read the manifest again, apply the changes of this sync to it, and write it.
The second read keeps the entries that another device wrote during the sync. Then save the new
base. It holds each path that is in step after this sync.

## Conflicts

When both sides changed a note, the device keeps its own text at the path and uploads it. The
remote text goes to a new note beside it, named `<name> (conflict <device>).md`. The device
uploads that copy at once, so every device gets both versions. If the name is taken, the device
adds a number: `<name> (conflict <device> 2).md`.

An app must not overwrite a note that changed while a sync ran. Before it writes a downloaded
note, it compares the current text with the hash the sync saw. If the two differ, it writes the
download as a conflict copy. A delete from another device applies only to a note that did not
change.

## Stores

All S3 requests use AWS Signature Version 4 with the service `s3`. Each request signs the
`host`, `x-amz-content-sha256` and `x-amz-date` headers. The payload hash is the SHA-256 of the
body. A GET that returns 404 with the code `NoSuchKey` means that the object does not exist.

| Provider id | Endpoint | Signing region | Addressing |
| --- | --- | --- | --- |
| `s3` | `https://s3.<region>.amazonaws.com` | the region | virtual-hosted |
| `hetzner` | `https://<region>.your-objectstorage.com` | the region | path-style |
| `r2` | `https://<accountId>.r2.cloudflarestorage.com` | `auto` | path-style |
| `b2` | `https://s3.<region>.backblazeb2.com` | the region | path-style |
| `do` | `https://<region>.digitaloceanspaces.com` | `us-east-1` | path-style |
| `wasabi` | `https://s3.<region>.wasabisys.com` | the region | path-style |
| `scaleway` | `https://s3.<region>.scw.cloud` | the region | path-style |
| `ovh` | `https://s3.<region>.io.cloud.ovh.net` | the region | path-style |
| `custom` | the `endpoint` field | the `region` field | the `addressing` field |
| `gcs` with HMAC keys | `https://storage.googleapis.com` | `auto` | path-style |

The settings fields are `bucket`, `prefix`, `accessKey`, `secretKey` and `region`. R2 also has
`accountId`. A custom server also has `endpoint` and `addressing`, which is `path` or `vhost`.
Without a picked region, an app uses the first region that the settings list for the provider.

**Google Cloud Storage with a service account key** (`gcsAuth` is `sa`):

1. Sign a JWT with RS256 and the `private_key` from the key file in `gcsJson`. The claims are
   `iss` set to `client_email`, `scope` set to
   `https://www.googleapis.com/auth/devstorage.read_write`, `aud` set to `token_uri`, `iat`, and
   `exp` set to `iat` plus 3600.
2. Post it to `token_uri` as the `assertion` of the grant type
   `urn:ietf:params:oauth:grant-type:jwt-bearer`.
3. Send the access token as `Authorization: Bearer <token>` to the JSON API:
   - Get an object: `GET /storage/v1/b/<bucket>/o/<key>?alt=media`
   - Put an object: `POST /upload/storage/v1/b/<bucket>/o?uploadType=media&name=<key>`
   - Delete an object: `DELETE /storage/v1/b/<bucket>/o/<key>`

The key in these paths is fully URL-encoded, `/` included. The fields for HMAC keys are `hmacId`
and `hmacSecret`.

**Azure Blob Storage** uses `https://<account>.blob.core.windows.net/<container>/<key>` with
`x-ms-version: 2021-08-06`. A PUT sends `x-ms-blob-type: BlockBlob`. The fields are `account`,
`container`, `azAuth` and `azKey`. With `azAuth` set to `key`, `azKey` is the base64 account
key, and each request carries a Shared Key signature. With `azAuth` set to `sas`, `azKey` is a
SAS token that the app appends to the URL as the query string. The token needs read, write,
create and delete permissions on the container.

## When a device syncs

- When a notes folder opens.
- After a save, when **When to sync** is **On save**. The sync starts 1.5 seconds after the save.
- Every 5 minutes, when **When to sync** is **Every 5 min**.
- When you select **Sync now** in the settings or in the command palette.

A device syncs only after **Test connection** passed with the current settings. When
**When to sync** is **Manually**, only **Sync now** starts a sync.

## Test vectors

These values come from `cargo test print_vectors -- --nocapture` in `desktop/`. A second
AES-GCM implementation opens both sealed blobs.

| Input | Value |
| --- | --- |
| Passphrase | `correct horse battery staple` |
| Salt | `0123456789abcdef` as ASCII, base64 `MDEyMzQ1Njc4OWFiY2RlZg==` |
| Iterations | `1000` |
| AES key, hex | `caa4aad92ca0635b01e04707f5fd851b42533044bec2a2ec393e589a2441a652` |
| Name key, hex | `d86dac01e25c2374f088dcdb35777a514fdffe3c66b5b055cdf2ac23671f52f2` |
| Object name of `Drafts/Plan.md` | `objects/106404d37208edd67089d2ad1258f2d0385c19fa.bin` |
| Sealed `# Blåbær`, base64 | `UVVJUkUxyHeerIFLzO6WIrgd6vd6nFBCHkNaN/NGe60nmwng72y4MbVhouA=` |
| Sealed `quire`, base64 | `UVVJUkUx1R27W/XnzAUMDiCCWoIsHIQv6CJ8Dn8sKqPUlrdkcpwZ` |
| SHA-256 of `# Plan` | `1ee55b7ae632bd99dfea4f2d791ff06b1119975e96d4f822050923e7499f44cb` |

The AWS documentation example for a GET Object request also applies to SigV4. Its signature is
`f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41`.
