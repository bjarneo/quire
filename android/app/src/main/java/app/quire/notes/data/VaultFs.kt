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

class Loaded(val files: Map<String, String>, val folders: Set<String>)

/**
 * Reads and writes the .md files of one vault. Paths are relative to the vault root and use "/".
 * All calls block. The view model calls them on one background thread, in order.
 */
interface VaultFs {
    val displayPath: String
    fun load(): Loaded
    fun write(path: String, content: String)
    fun rename(from: String, to: String)
    fun delete(path: String)
    fun mkdirs(dir: String)

    /** Moves a note into another folder. Fails when a file with that name is already there. */
    fun move(from: String, to: String)
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

    protected fun skip(name: String) = name.startsWith(".")
}

/** A vault in the app's own storage, for example files/vaults/Field Notes. */
class LocalFs(private val root: File) : BaseFs() {
    override val displayPath: String = root.absolutePath

    override fun load(): Loaded {
        if (!root.isDirectory) throw FileNotFoundException("The folder ${root.absolutePath} does not exist.")
        val files = LinkedHashMap<String, String>()
        val folders = LinkedHashSet<String>()
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
                }
            }
        }
        walk(root, "")
        return Loaded(files, folders)
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

    /** Moves the note to `.trash` inside the folder, so a mistaken delete can be undone. */
    override fun delete(path: String) {
        val src = File(root, path)
        if (!src.exists()) return
        val trash = File(root, ".trash")
        trash.mkdirs()
        val base = path.replace('/', ' ').let { if (it.endsWith(".md", true)) it.dropLast(3) else it }
        var dst = File(trash, "$base.md")
        var n = 2
        while (dst.exists()) dst = File(trash, "$base ${n++}.md")
        if (!src.renameTo(dst)) {
            dst.writeText(src.readText())
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
        fun walk(docId: String, rel: String) {
            val q = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val kids = ArrayList<Triple<String, String, String>>()
            val cursor = cr.query(
                q,
                arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE),
                null, null, null
            ) ?: throw IOException("Quire can't read this folder.")
            cursor.use { c ->
                while (c.moveToNext()) kids.add(Triple(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: ""))
            }
            for ((id, name, mime) in kids.sortedBy { it.second }) {
                if (skip(name)) continue
                val r = Md.join(rel, name)
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                if (mime == Document.MIME_TYPE_DIR) {
                    folders.add(r)
                    docs[r] = uri
                    walk(id, r)
                } else if (Md.isNote(name)) {
                    docs[r] = uri
                    files[r] = decode(r, read(uri))
                }
            }
        }
        walk(rootId, "")
        return Loaded(files, folders)
    }

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
        // A known type for .md keeps providers from adding a second extension.
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension("md") ?: "application/octet-stream"
        var uri = parent.createFile(mime, name)?.uri ?: throw IOException("Can't create $path")
        if (displayName(uri) != name) {
            uri = DocumentsContract.renameDocument(cr, uri, name) ?: uri
        }
        docs[path] = uri
        return uri
    }

    override fun write(path: String, content: String) {
        val bytes = encode(path, content).toByteArray(Charsets.UTF_8)
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

    /** Copies the note into `.trash` inside the folder, then removes it, so it can come back. */
    override fun delete(path: String) {
        val uri = docs[path] ?: return
        val text = read(uri)
        val base = path.replace('/', ' ').let { if (it.endsWith(".md", true)) it.dropLast(3) else it }
        val trash = DocumentFile.fromTreeUri(ctx, dirUri(".trash"))
        var name = "$base.md"
        var n = 2
        while (trash?.findFile(name) != null) name = "$base ${n++}.md"
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension("md") ?: "application/octet-stream"
        val made = trash?.createFile(mime, name)?.uri ?: throw IOException("Can't move $path to .trash")
        cr.openOutputStream(made, "w")?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: throw IOException("Can't write .trash/$name")
        docs.remove(path)
        DocumentsContract.deleteDocument(cr, uri)
    }

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
