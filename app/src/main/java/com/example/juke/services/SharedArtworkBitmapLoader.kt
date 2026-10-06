package com.example.juke.services

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.*

@UnstableApi
class SharedArtworkBitmapLoader(private val context: Context, private val scope: CoroutineScope) : BitmapLoader {
    private val fallback = DataSourceBitmapLoader(context)
    override fun supportsMimeType(mimeType: String) = fallback.supportsMimeType(mimeType)
    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = fallback.decodeBitmap(data)
    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        if (uri.scheme !in listOf("http", "https")) return fallback.loadBitmap(uri)
        val result = SettableFuture.create<Bitmap>()
        val job = scope.launch {
            try {
                var bitmap: Bitmap? = null
                repeat(3) {
                    if (bitmap == null) {
                        bitmap = com.example.juke.network.ArtworkRepository.load(context, uri.toString())
                        if (bitmap == null) delay(3_000)
                    }
                }
                val loaded = bitmap ?: error("HD artwork unavailable")
                val scale = minOf(1f, 512f / maxOf(loaded.width, loaded.height))
                result.set(if (scale < 1f) Bitmap.createScaledBitmap(loaded,
                    (loaded.width * scale).toInt(), (loaded.height * scale).toInt(), true) else loaded)
            } catch (e: CancellationException) { result.cancel(false); throw e }
            catch (e: Exception) { result.setException(e) }
        }
        result.addListener({ if (result.isCancelled) job.cancel() }, java.util.concurrent.Executor { it.run() })
        return result
    }
}
