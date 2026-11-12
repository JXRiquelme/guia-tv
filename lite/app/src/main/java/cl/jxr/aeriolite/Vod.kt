package cl.jxr.aeriolite

import android.content.SharedPreferences
import android.util.JsonReader
import android.util.JsonToken
import java.io.InputStreamReader
import java.text.Normalizer
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
    val categoryId: String = "",
) {
    /** Lowercase, accent-free title used by the search. */
    val searchKey: String by lazy { Search.normalize(title) }
}

object Search {
    private val marks = Regex("\\p{Mn}+")
    fun normalize(s: String): String = marks.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase()
}

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

    private fun JsonReader.str(): String = when (peek()) {
        JsonToken.NULL -> { nextNull(); "" }
        JsonToken.STRING, JsonToken.NUMBER -> nextString().trim()
        JsonToken.BOOLEAN -> nextBoolean().toString()
        else -> { skipValue(); "" }
    }

    /**
     * Movies or series of one category, or the whole catalog when [categoryId] is null.
     * Streams the JSON so a large catalog does not have to fit in memory as text.
     */
    fun items(acc: XtreamAccount, series: Boolean, categoryId: String?): List<VodItem> {
        val action = if (series) "get_series" else "get_vod_streams"
        val url = if (categoryId == null) acc.api(action) else acc.api(action, "category_id" to categoryId)
        val c = Net.open(url, 90_000)
        val out = ArrayList<VodItem>()
        try {
            JsonReader(InputStreamReader(Net.stream(c), "UTF-8")).use { r ->
                val first = try { r.peek() } catch (e: Exception) { throw IllegalStateException("El servidor no respondió") }
                if (first != JsonToken.BEGIN_ARRAY) throw IllegalStateException("Usuario o clave Xtream incorrectos")
                r.beginArray()
                while (r.hasNext()) {
                    if (r.peek() != JsonToken.BEGIN_OBJECT) { r.skipValue(); continue }
                    var id = ""; var name = ""; var poster = ""; var ext = ""; var date = ""; var rating = ""; var cat = ""
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "stream_id" -> if (!series) id = r.str() else r.skipValue()
                            "series_id" -> if (series) id = r.str() else r.skipValue()
                            "name" -> name = r.str()
                            "stream_icon", "cover" -> { val v = r.str(); if (poster.isEmpty()) poster = v }
                            "container_extension" -> ext = r.str()
                            "releasedate", "releaseDate", "release_date", "year" -> { val v = r.str(); if (date.isEmpty()) date = v }
                            "rating" -> rating = r.str()
                            "category_id" -> cat = r.str()
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                    if (id.isEmpty()) continue
                    out.add(
                        VodItem(
                            series = series,
                            id = id,
                            title = name.ifEmpty { "Sin título" },
                            poster = poster,
                            ext = ext.ifEmpty { "mp4" },
                            year = Regex("(19|20)\\d\\d").find(date)?.value.orEmpty(),
                            rating = rating.takeIf { it.isNotEmpty() && it != "0" && it != "0.0" }.orEmpty(),
                            categoryId = cat,
                        )
                    )
                }
                r.endArray()
            }
        } finally {
            c.disconnect()
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

    /** Re-read after the cloud copy replaced the saved list. */
    fun reload() { entries.clear(); entries.addAll(load()) }

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

    /** Started but unfinished movies, or episodes (latest one per series), newest first. */
    /** For series: one entry each, the most advanced episode started (season, then number). */
    fun continueWatching(series: Boolean): List<Watch> {
        val open = entries.filter { it.episode == series && !it.finished }
        if (!series) return open
        val out = ArrayList<Watch>()
        val at = HashMap<String, Int>()
        for (w in open) {
            val key = w.seriesId.ifEmpty { w.id }
            val i = at[key]
            if (i == null) { at[key] = out.size; out.add(w) }
            else if (order(w) > order(out[i])) out[i] = w
        }
        return out
    }

    private val orderRx = Regex("^T(\\d+) · E(\\d+)")

    private fun order(w: Watch): Int =
        orderRx.find(w.subtitle)?.let { it.groupValues[1].toInt() * 10_000 + it.groupValues[2].toInt() } ?: -1

    fun remove(w: Watch) {
        if (w.episode && w.seriesId.isNotEmpty()) entries.removeAll { it.seriesId == w.seriesId }
        else entries.removeAll { it.key == w.key }
        save()
    }

    fun watched(key: String) = get(key)?.finished == true
}

/** "Ver más tarde" list (only on this device). */
class Later(private val prefs: SharedPreferences) {
    private val entries: MutableList<VodItem> = load()

    private fun load(): MutableList<VodItem> = try {
        val a = JSONArray(prefs.getString("later", "[]"))
        MutableList(a.length()) {
            val o = a.getJSONObject(it)
            VodItem(o.optBoolean("series"), o.optString("id"), o.optString("title"), o.optString("poster"),
                o.optString("ext", "mp4"), o.optString("year"), o.optString("rating"), o.optString("cat"))
        }
    } catch (e: Exception) {
        ArrayList()
    }

    private fun save() {
        val a = JSONArray()
        entries.forEach {
            a.put(JSONObject().put("series", it.series).put("id", it.id).put("title", it.title).put("poster", it.poster)
                .put("ext", it.ext).put("year", it.year).put("rating", it.rating).put("cat", it.categoryId))
        }
        prefs.edit().putString("later", a.toString()).apply()
    }

    fun reload() { entries.clear(); entries.addAll(load()) }

    fun list(series: Boolean) = entries.filter { it.series == series }
    fun contains(item: VodItem) = entries.any { it.series == item.series && it.id == item.id }

    fun add(item: VodItem) {
        if (contains(item)) return
        entries.add(0, item)
        save()
    }

    fun remove(item: VodItem) {
        entries.removeAll { it.series == item.series && it.id == item.id }
        save()
    }
}
