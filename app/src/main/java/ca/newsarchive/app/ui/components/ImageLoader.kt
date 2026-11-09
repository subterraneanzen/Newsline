package ca.newsarchive.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import ca.newsarchive.app.LocalContainer
import ca.newsarchive.core.net.HttpFetcher
import ca.newsarchive.core.text.TextNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Small image loader (memory + disk cache, downsampling) so the app needs no image library.
 * Images are only fetched when displayed and when the user has images enabled.
 */
class ImageLoader(context: Context, private val http: HttpFetcher) {
    private val dir = File(context.cacheDir, "images").apply { mkdirs() }
    private val memory = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun load(url: String, maxWidth: Int = 1080): Bitmap? {
        memory.get(url)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val f = File(dir, TextNormalizer.sha256(url).take(32))
                val bytes = if (f.exists()) f.readBytes() else {
                    val r = http.get(url, accept = "image/*")
                    if (!r.ok || r.bytes.isEmpty()) return@runCatching null
                    f.writeBytes(r.bytes); r.bytes
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()?.also { memory.put(url, it) }
        }
    }

    /** Keeps the disk cache under a size limit (oldest files removed first). */
    fun trim(maxBytes: Long = 150L * 1024 * 1024) {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) { if (total <= maxBytes) break; total -= f.length(); f.delete() }
    }
}

@Composable
fun RemoteImage(url: String?, modifier: Modifier = Modifier, contentDescription: String? = null, maxWidth: Int = 1080) {
    if (url.isNullOrBlank()) return
    val loader = LocalContainer.current.images
    var bmp by remember(url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) { bmp = loader.load(url, maxWidth) }
    val b = bmp
    if (b != null) {
        Image(b.asImageBitmap(), contentDescription, modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}
