package app.quire.notes.core

/** Where the pixels of an image in a note come from. */
sealed class ImageSource {
    /** A file in the notes folder, by its path relative to the folder. */
    data class Vault(val path: String) : ImageSource()

    /** A file outside the notes folder, by its absolute path. */
    data class Path(val path: String) : ImageSource()

    /** A web image. */
    data class Url(val url: String) : ImageSource()

    /** No file at the path that the note gives. */
    data class Missing(val src: String) : ImageSource()
}

/**
 * Images in notes: where the file of an image is, and the lines that link inserted images.
 * Follows desktop/src/images.rs. Inserted images go to attachments/ in the notes folder.
 */
object Images {
    /** The folder inside the notes folder that takes inserted images. */
    const val ATTACHMENTS = "attachments"

    /** Larger files neither insert nor sync. */
    const val MAX_BYTES = 50L * 1024 * 1024

    /**
     * Where the pixels of [img] in a note in the folder [noteDir] come from. [all] holds the
     * image files of the notes folder. An embed finds its file by the exact path, then by name
     * in the folder of the note, then by name anywhere. A path is relative to the note first,
     * then relative to the notes folder.
     */
    fun source(img: Img, noteDir: String, all: Collection<String>): ImageSource {
        val src = img.src.trim()
        if (src.startsWith("http://") || src.startsWith("https://")) return ImageSource.Url(src)
        if (img.embed) {
            val sorted = all.sorted()
            val want = src.lowercase()
            val name = want.substringAfterLast('/')
            val hit = sorted.find { it.lowercase() == want }
                ?: sorted.find { Md.dir(it) == noteDir && it.lowercase().endsWith(name) }
                ?: sorted.find { it.substringAfterLast('/').lowercase() == name }
            return if (hit != null) ImageSource.Vault(hit) else ImageSource.Missing(src)
        }
        if (src.startsWith("file://")) return ImageSource.Path(src.removePrefix("file://"))
        if (src.startsWith("/")) return ImageSource.Path(src)
        val known = all.toSet()
        for (base in listOf(noteDir, "")) {
            val rel = Md.resolvePath(base, src) ?: continue
            if (rel in known) return ImageSource.Vault(rel)
        }
        return ImageSource.Missing(src)
    }

    /**
     * The text to put at the caret of a note in [noteDir] for the images at [rels]: one
     * `![name](path)` line each, with a line break before and after when the text around the
     * caret needs one. [before] and [after] are the characters around the caret, or null.
     */
    fun linkText(rels: List<String>, noteDir: String, before: Char?, after: Char?): String {
        val lines = rels.map { r ->
            val file = r.substringAfterLast('/')
            val alt = if (file.contains('.')) file.substringBeforeLast('.') else file
            "![" + alt.replace("[", "").replace("]", "") + "](" + Md.linkPath(Md.relative(noteDir, r)) + ")"
        }
        val sb = StringBuilder()
        if (before != null && before != '\n') sb.append('\n')
        sb.append(lines.joinToString("\n"))
        if (after == null || after != '\n') sb.append('\n')
        return sb.toString()
    }

    /**
     * A file name with an image extension for a picked file, from its name or its type. Null
     * when it is no image. The photo picker gives a number as the name, so such a file gets
     * "Image <when>" with [stamp], for example "Image 2026-11-12 13.58.04.png".
     */
    fun nameFor(display: String?, mime: String?, stamp: String = ""): String? {
        val given = display?.trim()?.replace('/', '-')?.ifEmpty { null }
        if (given != null && Md.isImage(given)) {
            val stem = given.substringBeforeLast('.')
            val ext = given.substringAfterLast('.')
            return if (stem.isNotEmpty() && stem.all { it.isDigit() } && stamp.isNotEmpty()) "Image $stamp.$ext" else given
        }
        val name = if (given == null || given.all { it.isDigit() }) (if (stamp.isEmpty()) "image" else "Image $stamp") else given
        val ext = when (mime?.lowercase()) {
            "image/png" -> "png"
            "image/jpeg", "image/jpg" -> "jpg"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/bmp", "image/x-ms-bmp" -> "bmp"
            "image/svg+xml" -> "svg"
            "image/avif" -> "avif"
            else -> return null
        }
        return "$name.$ext"
    }
}
