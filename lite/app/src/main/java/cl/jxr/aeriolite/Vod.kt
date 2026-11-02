package cl.jxr.aeriolite

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class XtreamAccount(val base: String, val user: String, val pass: String) {
    val valid get() = base.startsWith("http") && user.isNotBlank() && pass.isNotBlank()
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private val root get() = base.trimEnd('/')

    fun api(action: String, vararg extra: Pair<String, String>): String {
        val sb = StringBuilder("$root/player_api.php?username=${enc(user)}&password=${enc(pass)}&action=$action")
        extra.forEach { sb.append('&').append(it.first).append('=').append(enc(it.second)) }
        return sb.toString()
    }

    fun movieUrl(id: String, ext: String) = "$root/movie/${enc(user)}/${enc(pass)}/$id.${ext.ifBlank { "mp4" }}"
    fun episodeUrl(id: String, ext: String) = "$root/series/${enc(user)}/${enc(pass)}/$id.${ext.ifBlank { "mp4" }}"

    companion object {
        /** Optional line in the M3U:  #XTREAM https://server:port usuario clave */
        fun fromPlaylist(text: String): XtreamAccount? {
            for (raw in text.lineSequence().take(50)) {
                val line = raw.trim()
                if (!line.startsWith("#XTREAM", true)) continue
                val parts = line.substringAfter(' ', "").trim().split(Regex("\\s+"))
                if (parts.size >= 3) return XtreamAccount(parts[0], parts[1], parts[2]).takeIf { it.valid }
            }
            return null
        }
    }
}

data class Category(val id: String, val name: String)

/** A movie or a series in a category list. */
data class VodItem(
    val series: Boolean,
    val id: String,
    val title: String,
    val poster: String,
    val ext: String,
    val year: String,
    val rating: String,
)

data class Episode(
    val id: String,
    val season: Int,
    val number: Int,
    val title: String,
    val ext: String,
    val image: String,
)

object Xtream {
    private fun fetch(url: String): String {
        val c = Net.open(url, 30_000)
        try {
            return Net.stream(c).bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun array(text: String): JSONArray {
        val t = text.trim()
        if (!t.startsWith("[")) throw IllegalStateException(
            if (t.contains("auth", true) || t.isEmpty()) "Usuario o clave Xtream incorrectos" else "Respuesta inesperada del servidor"
        )
        return JSONArray(t)
    }

    private fun JSONObject.s(key: String): String {
        if (!has(key) || isNull(key)) return ""
        return optString(key, "").trim()
    }

    fun categories(acc: XtreamAccount, series: Boolean): List<Category> {
        val a = array(fetch(acc.api(if (series) "get_series_categories" else "get_vod_categories")))
        val out = ArrayList<Category>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val id = o.s("category_id")
            if (id.isEmpty()) continue
            out.add(Category(id, o.s("category_name").ifEmpty { id }))
        }
        return out
    }

    fun items(acc: XtreamAccount, series: Boolean, categoryId: String): List<VodItem> {
        val text = fetch(acc.api(if (series) "get_series" else "get_vod_streams", "category_id" to categoryId))
        val a = array(text)
        val out = ArrayList<VodItem>(a.length())
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val id = o.s(if (series) "series_id" else "stream_id")
            if (id.isEmpty()) continue
            val date = if (series) o.s("releaseDate").ifEmpty { o.s("release_date") } else o.s("releasedate").ifEmpty { o.s("year") }
            out.add(
                VodItem(
                    series = series,
                    id = id,
                    title = o.s("name").ifEmpty { "Sin título" },
                    poster = if (series) o.s("cover") else o.s("stream_icon"),
                    ext = o.s("container_extension").ifEmpty { "mp4" },
                    year = Regex("(19|20)\\d\\d").find(date)?.value.orEmpty(),
                    rating = o.s("rating").takeIf { it.isNotEmpty() && it != "0" && it != "0.0" }.orEmpty(),
                )
            )
        }
        return out
    }

    fun episodes(acc: XtreamAccount, seriesId: String): List<Episode> {
        val root = JSONObject(fetch(acc.api("get_series_info", "series_id" to seriesId)))
        val out = ArrayList<Episode>()
        fun addSeason(season: Int, list: JSONArray) {
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                val id = o.s("id")
                if (id.isEmpty()) continue
                val info = o.optJSONObject("info")
                val num = o.s("episode_num").toIntOrNull() ?: (i + 1)
                val s = o.s("season").toIntOrNull() ?: season
                out.add(
                    Episode(
                        id = id,
                        season = s,
                        number = num,
                        title = o.s("title").ifEmpty { "Episodio $num" },
                        ext = o.s("container_extension").ifEmpty { "mp4" },
                        image = info?.let { it.s("movie_image").ifEmpty { it.s("cover_big") } }.orEmpty(),
                    )
                )
            }
        }
        when (val eps = root.opt("episodes")) {
            is JSONObject -> eps.keys().forEach { key -> eps.optJSONArray(key)?.let { addSeason(key.toIntOrNull() ?: 0, it) } }
            is JSONArray -> for (i in 0 until eps.length()) eps.optJSONArray(i)?.let { addSeason(i + 1, it) }
        }
        out.sortWith(compareBy({ it.season }, { it.number }))
        return out
    }
}

/** Something played on demand, also what the history stores. */
data class Watch(
    val episode: Boolean,
    val id: String,
    val ext: String,
    val title: String,
    val subtitle: String,
    val poster: String,
    val seriesId: String = "",
    var position: Long = 0,
    var duration: Long = 0,
    var updated: Long = 0,
) {
    val key get() = (if (episode) "e:" else "m:") + id
    val finished get() = duration > 0 && position >= duration * 95 / 100

    fun toJson(): JSONObject = JSONObject()
        .put("episode", episode).put("id", id).put("ext", ext).put("title", title)
        .put("subtitle", subtitle).put("poster", poster).put("seriesId", seriesId)
        .put("position", position).put("duration", duration).put("updated", updated)

    companion object {
        fun fromJson(o: JSONObject) = Watch(
            episode = o.optBoolean("episode"),
            id = o.optString("id"),
            ext = o.optString("ext", "mp4"),
            title = o.optString("title"),
            subtitle = o.optString("subtitle"),
            poster = o.optString("poster"),
            seriesId = o.optString("seriesId"),
            position = o.optLong("position"),
            duration = o.optLong("duration"),
            updated = o.optLong("updated"),
        )
    }
}

/** Local watch history (only on this TV). */
class History(private val prefs: SharedPreferences) {
    private val entries: MutableList<Watch> = load()

    private fun load(): MutableList<Watch> = try {
        val a = JSONArray(prefs.getString("history", "[]"))
        MutableList(a.length()) { Watch.fromJson(a.getJSONObject(it)) }
    } catch (e: Exception) {
        ArrayList()
    }

    private fun save() {
        val a = JSONArray()
        entries.forEach { a.put(it.toJson()) }
        prefs.edit().putString("history", a.toString()).apply()
    }

    fun get(key: String): Watch? = entries.firstOrNull { it.key == key }

    fun update(w: Watch, position: Long, duration: Long) {
        entries.removeAll { it.key == w.key }
        w.position = position
        if (duration > 0) w.duration = duration
        w.updated = System.currentTimeMillis()
        entries.add(0, w)
        while (entries.size > 60) entries.removeAt(entries.size - 1)
        save()
    }

    /** Unfinished movies, or unfinished episodes (one per series), newest first. */
    fun continueWatching(series: Boolean): List<Watch> {
        val seen = HashSet<String>()
        return entries.filter { w ->
            w.episode == series && !w.finished && w.position > 30_000 &&
                (!series || seen.add(w.seriesId.ifEmpty { w.id }))
        }
    }

    fun watched(key: String) = get(key)?.finished == true
}
