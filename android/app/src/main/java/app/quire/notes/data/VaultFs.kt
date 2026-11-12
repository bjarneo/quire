package app.quire.notes.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import app.quire.notes.core.Md
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** The size and the change time in milliseconds of a file, to tell when it changed. */
data class Stamp(val size: Long, val time: Long)

/** A loaded vault: the text of each note, the folders, and the image files with their stamps. */
class Loaded(val files: Map<String, String>, val folders: Set<String>, val images: Map<String, Stamp> = emptyMap())

/**
 * Reads and writes the .md files of one vault. Paths are relative to the vault root and use "/".
 * All calls block. The view model calls them on one background thread, in order.
 */
interface VaultFs {
    val displayPath: String
    fun load(): Loaded
    fun write(path: String, content: String)

    /** The bytes of a file, such as an image. */
    fun readBytes(path: String): ByteArray

    /** Writes the bytes of a file, such as an image, and makes its folders. */
    fun writeBytes(path: String, bytes: ByteArray)
    fun rename(from: String, to: String)
    fun delete(path: String)
    fun mkdirs(dir: String)

    /** Moves a note into another folder. Fails when a file with that name is already there. */
    fun move(from: String, to: String)

    /**
     * Moves the folder [dir] with all its files and subfolders into .trash, under the name from
     * [Md.trashDirName]. Files that are not notes move too.
     */
    fun deleteDir(dir: String)
}

/** Keeps line endings and a byte order mark the way the file had them. The editor sees "\n" only. */
abstract class BaseFs : VaultFs {
    private val crlf: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val bom: MutableSet<String> = Collections.synchronizedSet(HashSet())

    protected fun decode(path: String, raw: String): String {
        var t = raw
        if (t.startsWith('﻿')) {
            bom.add(path)
            t = t.substring(1)
        } else {
            bom.remove(path)
        }
        if (t.contains("\r\n")) {
            crlf.add(path)
            t = t.replace("\r\n", "\n")
        } else {
            crlf.remove(path)
        }
        return t
    }

    protected fun encode(path: String, text: String): String {
        var t = text
        if (path in crlf) t = t.replace("\n", "\r\n")
        if (path in bom) t = "﻿" + t
        return t
    }

    protected fun moveFlags(from: String, to: String) {
        if (crlf.remove(from)) crlf.add(to)
        if (bom.remove(from)) bom.add(to)
    }

    protected fun skip(name: String) = name.startsWith(".") || name == "node_modules"

    /** Drops the line-ending flags of the notes in [dir], after the folder went away. */
    protected fun forgetDir(dir: String) {
        crlf.removeAll { Md.inDir(it, dir) }
        bom.removeAll { Md.inDir(it, dir) }
    }
}

/** A vault in the app's own storage, for example files/vaults/Field Notes. */
class LocalFs(private val root: File) : BaseFs() {
    override val displayPath: String = root.absolutePath

    override fun load(): Loaded {
        if (!root.isDirectory) throw FileNotFoundException("The folder ${root.absolutePath} does not exist.")
        val files = LinkedHashMap<String, String>()
        val folders = LinkedHashSet<String>()
        val images = LinkedHashMap<String, Stamp>()
        fun walk(dir: File, rel: String) {
            val kids = dir.listFiles() ?: return
            for (k in kids.sortedBy { it.name }) {
                if (skip(k.name)) continue
                val r = Md.join(rel, k.name)
                if (k.isDirectory) {
                    folders.add(r)
                    walk(k, r)
                } else if (Md.isNote(k.name)) {
                    files[r] = decode(r, k.readText())
                } else if (Md.isImage(k.name)) {
                    images[r] = Stamp(k.length(), k.lastModified())
                }
            }
        }
        walk(root, "")
        return Loaded(files, folders, images)
    }

    override fun readBytes(path: String): ByteArray = File(root, path).readBytes()

    override fun writeBytes(path: String, bytes: ByteArray) {
        val f = File(root, path)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, ".${f.name}.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) {
            f.writeBytes(bytes)
            tmp.delete()
        }
    }

    override fun write(path: String, content: String) {
        val f = File(root, path)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, ".${f.name}.tmp")
        tmp.writeText(encode(path, content))
        if (!tmp.renameTo(f)) {
            f.writeText(encode(path, content))
            tmp.delete()
        }
    }

    override fun rename(from: String, to: String) {
        val a = File(root, from)
        val b = File(root, to)
        b.parentFile?.mkdirs()
        if (!a.renameTo(b)) throw IOException("Could not rename $from")
        moveFlags(from, to)
    }

    /** Moves the file to `.trash` inside the folder, so a mistaken delete can be undone. The name keeps its extension. */
    override fun delete(path: String) {
        val src = File(root, path)
        if (!src.exists()) return
        val trash = File(root, ".trash")
        trash.mkdirs()
        val dst = File(trash, Md.trashFileName(path) { File(trash, it).exists() })
        if (!src.renameTo(dst)) {
            src.copyTo(dst)
            src.delete()
        }
    }

    override fun mkdirs(dir: String) {
        File(root, dir).mkdirs()
    }

    override fun move(from: String, to: String) {
        val a = File(root, from)
        val b = File(root, to)
        if (b.exists()) throw IOException("${b.name} is already in ${Md.dir(to).ifEmpty { "the folder root" }}.")
        b.parentFile?.mkdirs()
        if (!a.renameTo(b)) throw IOException("Could not move $from")
        moveFlags(from, to)
    }

    override fun deleteDir(dir: String) {
        val src = File(root, dir)
        if (dir.isEmpty() || !src.isDirectory) return
        val trash = File(root, ".trash")
        trash.mkdirs()
        val dst = File(trash, Md.trashDirName(dir) { File(trash, it).exists() })
        if (!src.renameTo(dst)) {
            src.copyRecursively(dst)
            if (!src.deleteRecursively()) throw IOException("Could not remove $dir after the copy to .trash")
        }
        forgetDir(dir)
    }
}

/** A vault in a folder that the person picked with the Storage Access Framework. */
class SafFs(private val ctx: Context, private val tree: Uri) : BaseFs() {
    private val cr = ctx.contentResolver
    private val docs = ConcurrentHashMap<String, Uri>()
    private val rootId: String = DocumentsContract.getTreeDocumentId(tree)
    private val rootUri: Uri = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)

    // The description runs a provider query, so the view model reads it on the background thread first.
    override val displayPath: String by lazy { describe(ctx, tree) }

    override fun load(): Loaded {
        docs.clear()
        docs[""] = rootUri
        val files = LinkedHashMap<String, String>()
        val folders = LinkedHashSet<String>()
        val images = LinkedHashMap<String, Stamp>()
        class Kid(val id: String, val name: String, val mime: String, val size: Long, val time: Long)
        fun walk(docId: String, rel: String) {
            val q = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val kids = ArrayList<Kid>()
            val cursor = cr.query(
                q,
                arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED),
                null, null, null
            ) ?: throw IOException("Quire can't read this folder.")
            cursor.use { c ->
                while (c.moveToNext()) {
                    val size = if (c.isNull(3)) -1L else c.getLong(3)
                    val time = if (c.isNull(4)) 0L else c.getLong(4)
                    kids.add(Kid(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "", size, time))
                }
            }
            for (k in kids.sortedBy { it.name }) {
                if (skip(k.name)) continue
                val r = Md.join(rel, k.name)
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, k.id)
                if (k.mime == Document.MIME_TYPE_DIR) {
                    folders.add(r)
                    docs[r] = uri
                    walk(k.id, r)
                } else if (Md.isNote(k.name)) {
                    docs[r] = uri
                    files[r] = decode(r, read(uri))
                } else if (Md.isImage(k.name)) {
                    docs[r] = uri
                    images[r] = Stamp(k.size, k.time)
                }
            }
        }
        walk(rootId, "")
        return Loaded(files, folders, images)
    }

    override fun readBytes(path: String): ByteArray {
        val uri = docs[path] ?: throw FileNotFoundException("No file at $path")
        return cr.openInputStream(uri)?.use { it.readBytes() } ?: throw FileNotFoundException("No file at $path")
    }

    override fun writeBytes(path: String, bytes: ByteArray) = put(path, bytes)

    private fun read(uri: Uri): String =
        cr.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""

    private fun dirUri(dir: String): Uri {
        if (dir.isEmpty()) return rootUri
        docs[dir]?.let { return it }
        val parent = DocumentFile.fromTreeUri(ctx, dirUri(Md.dir(dir))) ?: throw IOException("Can't open $dir")
        val name = dir.substringAfterLast('/')
        val made = parent.findFile(name)?.takeIf { it.isDirectory } ?: parent.createDirectory(name)
            ?: throw IOException("Can't create the folder $dir")
        docs[dir] = made.uri
        return made.uri
    }

    private fun displayName(uri: Uri): String? =
        cr.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun createFile(path: String): Uri {
        val name = path.substringAfterLast('/')
        val parent = DocumentFile.fromTreeUri(ctx, dirUri(Md.dir(path))) ?: throw IOException("Can't open ${Md.dir(path)}")
        var uri = parent.createFile(mimeOf(name), name)?.uri ?: throw IOException("Can't create $path")
        if (displayName(uri) != name) {
            uri = DocumentsContract.renameDocument(cr, uri, name) ?: uri
        }
        docs[path] = uri
        return uri
    }

    override fun write(path: String, content: String) = put(path, encode(path, content).toByteArray(Charsets.UTF_8))

    private fun put(path: String, bytes: ByteArray) {
        fun put(uri: Uri) {
            val out = try {
                cr.openOutputStream(uri, "wt")
            } catch (e: Exception) {
                null
            } ?: cr.openOutputStream(uri, "w") ?: throw IOException("Can't write $path")
            out.use { it.write(bytes) }
        }
        val known = docs[path]
        if (known != null) {
            try {
                put(known)
                return
            } catch (e: FileNotFoundException) {
                docs.remove(path)
            }
        }
        put(createFile(path))
    }

    override fun rename(from: String, to: String) {
        val uri = docs[from] ?: throw IOException("Can't find $from")
        val nu = DocumentsContract.renameDocument(cr, uri, to.substringAfterLast('/')) ?: uri
        docs.remove(from)
        docs[to] = nu
        moveFlags(from, to)
    }

    /** Copies the file into `.trash` inside the folder, then removes it, so it can come back. The name keeps its extension. */
    override fun delete(path: String) {
        val uri = docs[path] ?: return
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
        val trash = DocumentFile.fromTreeUri(ctx, dirUri(".trash"))
        val name = Md.trashFileName(path) { trash?.findFile(it) != null }
        var made = trash?.createFile(mimeOf(name), name)?.uri ?: throw IOException("Can't move $path to .trash")
        if (displayName(made) != name) made = DocumentsContract.renameDocument(cr, made, name) ?: made
        cr.openOutputStream(made, "w")?.use { it.write(bytes) } ?: throw IOException("Can't write .trash/$name")
        docs.remove(path)
        DocumentsContract.deleteDocument(cr, uri)
    }

    /** A known type for the extension keeps providers from adding a second extension. */
    private fun mimeOf(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    override fun mkdirs(dir: String) {
        dirUri(dir)
    }

    /**
     * Moves the document with the provider's own move when it has one. Otherwise Quire copies
     * the bytes into a new document and deletes the old one.
     */
    override fun move(from: String, to: String) {
        val uri = docs[from] ?: throw IOException("Can't find $from")
        val name = to.substringAfterLast('/')
        val srcParent = dirUri(Md.dir(from))
        val dstParent = dirUri(Md.dir(to))
        val target = DocumentFile.fromTreeUri(ctx, dstParent) ?: throw IOException("Can't open ${Md.dir(to)}")
        if (target.findFile(name) != null) throw IOException("$name is already in ${Md.dir(to).ifEmpty { "the folder root" }}.")
        var moved: Uri? = try {
            DocumentsContract.moveDocument(cr, uri, srcParent, dstParent)
        } catch (e: Exception) {
            null
        }
        if (moved == null) {
            val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("Can't read $from")
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension("md") ?: "application/octet-stream"
            var made = target.createFile(mime, name)?.uri ?: throw IOException("Can't create $to")
            if (displayName(made) != name) made = DocumentsContract.renameDocument(cr, made, name) ?: made
            val out = try {
                cr.openOutputStream(made, "wt")
            } catch (e: Exception) {
                null
            } ?: cr.openOutputStream(made, "w") ?: throw IOException("Can't write $to")
            out.use { it.write(bytes) }
            DocumentsContract.deleteDocument(cr, uri)
            moved = made
        } else if (displayName(moved) != name) {
            moved = DocumentsContract.renameDocument(cr, moved, name) ?: moved
        }
        docs.remove(from)
        docs[to] = moved
        moveFlags(from, to)
    }

    override fun deleteDir(dir: String) {
        if (dir.isEmpty()) return
        val uri = docs[dir] ?: return
        val trashUri = dirUri(".trash")
        val trash = DocumentFile.fromTreeUri(ctx, trashUri) ?: throw IOException("Can't open .trash")
        val name = Md.trashDirName(dir) { trash.findFile(it) != null }
        val moved: Uri? = try {
            DocumentsContract.moveDocument(cr, uri, dirUri(Md.dir(dir)), trashUri)
        } catch (e: Exception) {
            null
        }
        if (moved != null) {
            if (displayName(moved) != name) DocumentsContract.renameDocument(cr, moved, name)
        } else {
            // The provider has no move, so Quire copies the folder and then deletes it.
            val made = trash.createDirectory(name) ?: throw IOException("Can't create .trash/$name")
            if (made.name != name) DocumentsContract.renameDocument(cr, made.uri, name)
            copyTree(DocumentsContract.getDocumentId(uri), made)
            DocumentsContract.deleteDocument(cr, uri)
        }
        docs.keys.removeAll { it == dir || Md.inDir(it, dir) }
        forgetDir(dir)
    }

    private fun copyTree(srcId: String, dst: DocumentFile) {
        val q = DocumentsContract.buildChildDocumentsUriUsingTree(tree, srcId)
        val kids = ArrayList<Triple<String, String, String>>()
        cr.query(q, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
            while (c.moveToNext()) kids.add(Triple(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "application/octet-stream"))
        } ?: throw IOException("Quire can't read this folder.")
        for ((id, name, mime) in kids) {
            val src = DocumentsContract.buildDocumentUriUsingTree(tree, id)
            if (mime == Document.MIME_TYPE_DIR) {
                copyTree(id, dst.createDirectory(name) ?: throw IOException("Can't create .trash/$name"))
            } else {
                var made = dst.createFile(mime, name)?.uri ?: throw IOException("Can't create .trash/$name")
                if (displayName(made) != name) made = DocumentsContract.renameDocument(cr, made, name) ?: made
                cr.openInputStream(src)?.use { input ->
                    (cr.openOutputStream(made, "w") ?: throw IOException("Can't write .trash/$name")).use { input.copyTo(it) }
                }
            }
        }
    }

    companion object {
        /** A readable location for a picked folder, for example /sdcard/Documents/Notes. */
        fun describe(ctx: Context, tree: Uri): String {
            val id = try {
                DocumentsContract.getTreeDocumentId(tree)
            } catch (e: Exception) {
                return tree.toString()
            }
            if (tree.authority == "com.android.externalstorage.documents") {
                val vol = id.substringBefore(':')
                val rest = id.substringAfter(':', "")
                return if (vol == "primary") "/sdcard/$rest".trimEnd('/') else "/storage/$vol/$rest".trimEnd('/')
            }
            val label = try {
                ctx.packageManager.resolveContentProvider(tree.authority ?: "", 0)?.loadLabel(ctx.packageManager)?.toString()
            } catch (e: Exception) {
                null
            }
            val name = DocumentFile.fromTreeUri(ctx, tree)?.name ?: id
            return if (label != null) "$label/$name" else name
        }
    }
}
