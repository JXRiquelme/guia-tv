package cl.jxr.aeriolite

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

data class Channel(
    val number: Int,
    val name: String,
    val tvgId: String,
    val logo: String,
    val url: String,
    /** Other streams of the same channel ("Opción 2", "Opción 3"…), from #EXTALT lines or "(Op. N)" entries. */
    val alts: List<Alt> = emptyList(),
) {
    /** Main stream first, then the alternatives. */
    val options: List<Alt> get() = listOf(Alt("Opción 1", url)) + alts
}

data class Alt(val label: String, val url: String)

data class Program(
    val start: Long,
    val stop: Long,
    val title: String,
    val desc: String,
)

object Net {
    const val USER_AGENT = "VLC/3.0.20 LibVLC/3.0.20"

    /** Opens a URL following redirects (also http <-> https), returns the connection. */
    fun open(address: String, timeoutMs: Int = 20000): HttpURLConnection {
        var url = URL(address)
        repeat(8) {
            val c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setRequestProperty("Accept-Encoding", "gzip")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IllegalStateException("Redirección sin destino")
                c.disconnect()
                url = URL(url, loc)
            } else if (code in 200..299) {
                return c
            } else {
                c.disconnect()
                throw IllegalStateException("El servidor respondió $code")
            }
        }
        throw IllegalStateException("Demasiadas redirecciones")
    }

    fun stream(c: HttpURLConnection): InputStream {
        val raw = c.inputStream
        return if ("gzip".equals(c.contentEncoding, true)) java.util.zip.GZIPInputStream(raw) else raw
    }

    /** Downloads into [target] atomically. */
    fun download(address: String, target: File) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        val c = open(address)
        try {
            stream(c).use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
        } finally {
            c.disconnect()
        }
        if (tmp.length() == 0L) throw IllegalStateException("Descarga vacía")
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }
}

object Playlist {
    private val attr = Regex("""([\w-]+)="([^"]*)"""")

    private val opSuffix = Regex("""\s*\((?:Op\.?|Opci[oó]n)\s*(\d+)\)\s*$""", RegexOption.IGNORE_CASE)

    fun parse(text: String): List<Channel> {
        val out = ArrayList<Channel>()
        val alts = ArrayList<MutableList<Alt>>()
        var target: MutableList<Alt>? = null
        var pending: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTALT:", ignoreCase = true)) {
                // #EXTALT:Opción 2 (FHD)|http://… -> belongs to the channel just above
                val body = line.substring(8)
                val bar = body.lastIndexOf('|')
                if (bar > 0 && target != null) {
                    val u = body.substring(bar + 1).trim()
                    if (u.startsWith("http", true)) target.add(Alt(body.substring(0, bar).trim(), u))
                }
            } else if (line.startsWith("#EXTINF", ignoreCase = true)) {
                pending = line
            } else if (!line.startsWith("#") && pending != null) {
                val info = pending
                pending = null
                if (!line.startsWith("http", ignoreCase = true)) continue
                val attrs = HashMap<String, String>()
                attr.findAll(info).forEach { attrs[it.groupValues[1].lowercase()] = it.groupValues[2] }
                val name = displayName(info).ifEmpty { attrs["tvg-name"] ?: "Canal ${out.size + 1}" }
                // "DAZN LaLiga 1 (Op. 2)" is not listed: it becomes an option of "DAZN LaLiga 1"
                val op = opSuffix.find(name)
                if (op != null) {
                    val base = name.substring(0, op.range.first).trim()
                    val owner = out.indexOfFirst { it.name.equals(base, true) }
                    if (owner >= 0) {
                        alts[owner].add(Alt("Opción ${op.groupValues[1]}", line))
                        target = null
                        continue
                    }
                }
                out.add(
                    Channel(
                        number = out.size + 1,
                        name = name,
                        tvgId = attrs["tvg-id"].orEmpty(),
                        logo = attrs["tvg-logo"].orEmpty(),
                        url = line,
                    )
                )
                target = ArrayList<Alt>().also { alts.add(it) }
            }
        }
        return out.mapIndexed { i, ch ->
            if (alts[i].isEmpty()) ch
            else ch.copy(alts = alts[i].distinctBy { it.url }.filter { it.url != ch.url }.mapIndexed { n, a ->
                // numbered in order, keeping a quality tag such as "(FHD)"
                val tag = quality.find(a.label)?.value
                Alt("Opción ${n + 2}" + (tag?.let { " $it" } ?: ""), a.url)
            })
        }
    }

    private val quality = Regex("""\((F?HD|SD|4K)\)""", RegexOption.IGNORE_CASE)

    private fun displayName(line: String): String {
        var quoted = false
        for (i in line.indices) {
            val ch = line[i]
            if (ch == '"') quoted = !quoted
            if (ch == ',' && !quoted) return line.substring(i + 1).trim()
        }
        return ""
    }
}

object Guide {
    private fun fmt(): SimpleDateFormat =
        SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    private fun time(f: SimpleDateFormat, value: String?): Long {
        if (value.isNullOrBlank()) return 0
        return try {
            val v = value.trim()
            (if (v.length > 14 && v.contains(' ')) f.parse(v) else f.parse(v.take(14) + " +0000"))?.time ?: 0
        } catch (e: Exception) {
            0
        }
    }

    /** Streams the XMLTV file keeping only the window the app shows (now-2h .. now+36h). */
    fun parse(file: File, wanted: Set<String>, now: Long): Map<String, List<Program>> {
        val from = now - 2 * 3600_000L
        val to = now + 36 * 3600_000L
        val map = HashMap<String, ArrayList<Program>>()
        val f = fmt()
        file.inputStream().buffered(64 * 1024).use { input ->
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(input, "UTF-8")
            var event = p.eventType
            var channel: String? = null
            var start = 0L
            var stop = 0L
            var title = ""
            var desc = ""
            var keep = false
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "programme" -> {
                            channel = p.getAttributeValue(null, "channel")
                            keep = channel != null && channel in wanted
                            if (keep) {
                                start = time(f, p.getAttributeValue(null, "start"))
                                stop = time(f, p.getAttributeValue(null, "stop"))
                                keep = start > 0 && stop > from && start < to
                            }
                            title = ""
                            desc = ""
                        }
                        "title" -> if (keep && title.isEmpty()) title = p.nextText().trim()
                        "desc" -> if (keep && desc.isEmpty()) desc = p.nextText().trim().take(400)
                    }
                } else if (event == XmlPullParser.END_TAG && p.name == "programme") {
                    if (keep && channel != null) {
                        map.getOrPut(channel) { ArrayList() }.add(Program(start, stop, title.ifEmpty { "Sin título" }, desc))
                    }
                    keep = false
                }
                event = p.next()
            }
        }
        map.values.forEach { list -> list.sortBy { it.start } }
        return map
    }

    fun nowNext(list: List<Program>?, now: Long): Pair<Program?, Program?> {
        if (list.isNullOrEmpty()) return null to null
        val idx = list.indexOfFirst { it.start <= now && it.stop > now }
        if (idx >= 0) return list[idx] to list.getOrNull(idx + 1)
        return null to list.firstOrNull { it.start > now }
    }
}
