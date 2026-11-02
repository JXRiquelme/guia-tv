package cl.jxr.aeriolite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Very small logo loader: memory LRU + disk cache, downsampled to TV list size. */
class Logos(context: Context) {
    private val dir = File(context.cacheDir, "logos").apply { mkdirs() }
    private val memory = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val failed = HashSet<String>()

    fun load(url: String, view: ImageView) {
        view.tag = url
        if (url.isEmpty()) {
            view.setImageDrawable(null)
            return
        }
        memory.get(url)?.let {
            view.setImageBitmap(it)
            return
        }
        view.setImageDrawable(null)
        if (url in failed) return
        pool.execute {
            val bmp = try {
                fetch(url)
            } catch (e: Throwable) {
                null
            }
            main.post {
                if (bmp == null) {
                    failed.add(url)
                } else {
                    memory.put(url, bmp)
                    if (view.tag == url) view.setImageBitmap(bmp)
                }
            }
        }
    }

    private fun fetch(url: String): Bitmap? {
        val file = File(dir, md5(url))
        if (!file.exists() || file.length() == 0L) Net.download(url, file)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 200 && bounds.outHeight / (sample * 2) >= 100) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
