package app.quire.notes.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import app.quire.notes.core.ImageSource
import app.quire.notes.core.Img
import app.quire.notes.state.QuireViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An image in the preview: on its way, ready with its own size in pixels, or a message. */
sealed class Pic {
    object Loading : Pic()
    class Ready(val bitmap: ImageBitmap, val width: Int, val height: Int) : Pic()
    class Note(val msg: String) : Pic()
}

/** Decoded images, kept while memory allows, so the preview does not decode them again. */
object PicCache {
    // Wide images decode at a smaller sample, which a phone column does not need.
    private const val MAX_DECODE = 2048

    private val cache = object : LruCache<String, Pic>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Pic): Int = if (value is Pic.Ready) value.bitmap.width * value.bitmap.height * 4 else 1024
    }

    fun peek(key: String): Pic? = cache.get(key)

    /** Reads and decodes an image. Missing files and web errors become a message. Runs off the main thread. */
    suspend fun load(key: String, src: ImageSource, read: () -> ByteArray): Pic {
        cache.get(key)?.let { return it }
        val pic = withContext(Dispatchers.IO) {
            val bytes = try {
                read()
            } catch (e: Exception) {
                return@withContext when (src) {
                    is ImageSource.Url -> Pic.Note("Could not load ${src.url}: ${e.message ?: e.javaClass.simpleName}")
                    is ImageSource.Missing -> Pic.Note("No image at ${src.src}")
                    else -> Pic.Note("No image at ${label(src)}")
                }
            }
            decode(bytes) ?: Pic.Note("Could not show ${label(src)}: Quire can't draw this type of image.")
        }
        // A failed download can work later, so only images and missing files stay in the cache.
        if (pic is Pic.Ready || src !is ImageSource.Url) cache.put(key, pic)
        return pic
    }

    private fun label(src: ImageSource) = when (src) {
        is ImageSource.Vault -> src.path
        is ImageSource.Path -> src.path
        is ImageSource.Url -> src.url
        is ImageSource.Missing -> src.src
    }

    private fun decode(bytes: ByteArray): Pic? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (w / (sample * 2) >= MAX_DECODE) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return Pic.Ready(bmp.asImageBitmap(), w, h)
    }
}

/**
 * One image of an image line. It fills the column, but not more than its own width in pixels,
 * with round corners. A box in the code colour holds its place while it loads.
 */
@Composable
fun Picture(vm: QuireViewModel, img: Img, c: QColors, textSize: TextUnit, em: (Float) -> Dp) {
    val src = remember(img, vm.active, vm.images) { vm.imageSource(img) }
    val key = remember(src, vm.images) { vm.imageKey(src) }
    val pic by produceState(PicCache.peek(key) ?: Pic.Loading, key) {
        value = PicCache.load(key, src) { vm.imageBytes(src) }
    }
    val alt = img.alt.ifEmpty { "Image" }
    when (val p = pic) {
        is Pic.Loading -> Box(
            Modifier
                .fillMaxWidth()
                .height(em(6f))
                .background(c.code, RoundedCornerShape(6.dp))
                .semantics { contentDescription = alt }
        )
        is Pic.Ready -> Image(
            bitmap = p.bitmap,
            contentDescription = alt,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .widthIn(max = p.width.dp)
                .fillMaxWidth()
                .aspectRatio(p.width.toFloat() / p.height)
                .clip(RoundedCornerShape(6.dp)),
        )
        is Pic.Note -> Box(
            Modifier
                .fillMaxWidth()
                .background(c.code, RoundedCornerShape(6.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) { Txt(p.msg, textSize * 0.8f, c.mute) }
    }
}
