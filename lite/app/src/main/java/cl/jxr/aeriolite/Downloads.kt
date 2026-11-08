package cl.jxr.aeriolite

import android.app.DownloadManager
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A movie or episode saved on this device (Android's own download manager does the work). */
class Download(val watch: Watch, val managerId: Long, val file: String) {
    var status = DownloadManager.STATUS_PENDING
    var percent = 0
    val done get() = status == DownloadManager.STATUS_SUCCESSFUL && File(file).exists()
    val failed get() = status == DownloadManager.STATUS_FAILED
}

class Downloads(private val context: Context, private val prefs: SharedPreferences) {
    private val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val entries: MutableList<Download> = load()

    private fun load(): MutableList<Download> = try {
        val a = JSONArray(prefs.getString("downloads", "[]"))
        MutableList(a.length()) {
            val o = a.getJSONObject(it)
            Download(Watch.fromJson(o.getJSONObject("w")), o.optLong("mid"), o.optString("file"))
        }
    } catch (e: Exception) {
        ArrayList()
    }

    private fun save() {
        val a = JSONArray()
        entries.forEach { a.put(JSONObject().put("w", it.watch.toJson()).put("mid", it.managerId).put("file", it.file)) }
        prefs.edit().putString("downloads", a.toString()).apply()
    }

    /** Asks the system to download [url] (movies/series files from the provider). */
    fun start(w: Watch, url: String): Boolean {
        if (get(w.key) != null) return false
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: return false
        val name = w.key.replace(':', '_') + "." + w.ext.ifEmpty { "mp4" }
        val file = File(dir, name)
        if (file.exists()) file.delete()
        val label = if (w.episode) "${w.title} · ${w.subtitle.substringBefore("  ")}" else w.title
        val request = DownloadManager.Request(Uri.parse(url))
            .addRequestHeader("User-Agent", Net.USER_AGENT)
            .setTitle(label)
            .setDescription("AerioTV Lite")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_MOVIES, name)
        val id = try { manager.enqueue(request) } catch (e: Exception) { return false }
        val copy = w.copy(position = 0, duration = 0)
        entries.add(0, Download(copy, id, file.absolutePath))
        save()
        return true
    }

    fun get(key: String): Download? = entries.firstOrNull { it.watch.key == key }

    /** file:// address of a finished download, or null. */
    fun localUri(key: String): String? = get(key)?.let { refresh(it); if (it.done) Uri.fromFile(File(it.file)).toString() else null }

    fun list(series: Boolean): List<Download> {
        val l = entries.filter { it.watch.episode == series }
        l.forEach { refresh(it) }
        return l
    }

    fun anyRunning() = entries.any { refresh(it); !it.done && !it.failed }

    fun remove(key: String) {
        val d = get(key) ?: return
        try { manager.remove(d.managerId) } catch (e: Exception) {}
        File(d.file).delete()
        entries.remove(d)
        save()
    }

    /** Free space where downloads are kept, in bytes (-1 if unknown). */
    fun freeBytes(): Long = try {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        android.os.StatFs(dir.path).availableBytes
    } catch (e: Exception) { -1L }

    /** Size of a finished (or partly downloaded) file. */
    fun fileBytes(d: Download): Long = File(d.file).length()

    /**
     * Size of the file on the server, read from the headers only (no body). -1 if the server
     * doesn't say. Runs on a background thread.
     */
    fun remoteSize(url: String): Long {
        var c: java.net.HttpURLConnection? = null
        return try {
            c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 15_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", Net.USER_AGENT)
                setRequestProperty("Range", "bytes=0-0")
                setRequestProperty("Connection", "close")
            }
            val range = c.getHeaderField("Content-Range")
            range?.substringAfterLast('/')?.trim()?.toLongOrNull()
                ?: c.getHeaderField("Content-Length")?.toLongOrNull()?.takeIf { c.responseCode == 200 && it > 1 }
                ?: -1L
        } catch (e: Exception) { -1L } finally { runCatching { c?.disconnect() } }
    }

    private fun refresh(d: Download) {
        try {
            manager.query(DownloadManager.Query().setFilterById(d.managerId)).use { c ->
                if (c != null && c.moveToFirst()) {
                    d.status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    d.percent = if (total > 0) (got * 100 / total).toInt() else 0
                } else if (File(d.file).exists()) {
                    d.status = DownloadManager.STATUS_SUCCESSFUL
                    d.percent = 100
                } else d.status = DownloadManager.STATUS_FAILED
            }
        } catch (e: Exception) {}
    }
}
