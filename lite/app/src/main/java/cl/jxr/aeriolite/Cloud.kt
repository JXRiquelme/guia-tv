package cl.jxr.aeriolite

import android.app.Activity
import android.content.SharedPreferences
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Google account + Supabase: keeps "Seguir viendo" (the playback history), "Ver más tarde" and the
 * option chosen for each channel the same on every device.
 *
 * Cloud document = one row per account in public.user_data:
 *   history: [Watch json, positions in ms], later: [{series,id,title,…}], channel_options: {name: index}
 *
 * Local-first: everything keeps working offline. Each local change is pushed a moment later; when
 * the app opens or comes back, the cloud copy wins if another device saved after us.
 */
class Cloud(private val prefs: SharedPreferences, private val onRemoteApplied: () -> Unit) {

    val configured get() = BuildConfig.SUPABASE_URL.isNotEmpty() && BuildConfig.SUPABASE_KEY.isNotEmpty()
    val email: String? get() = prefs.getString("cloud_email", null)
    val signedIn get() = prefs.getString("cloud_refresh", null) != null

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var applying = false
    private var dirty = prefs.getBoolean("cloud_dirty", false)

    private val watcher = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (applying || key == null || !signedIn) return@OnSharedPreferenceChangeListener
        if (key == "history" || key == "later" || key.startsWith("alt_")) markDirty()
    }

    init { prefs.registerOnSharedPreferenceChangeListener(watcher) }

    // ------------------------------------------------------------ sign in / out

    /** Google's own account chooser of this device (phone or TV). */
    fun signIn(activity: Activity, done: (String?) -> Unit) {
        if (!configured || BuildConfig.GOOGLE_WEB_CLIENT_ID.isEmpty()) { done("La sincronización no está configurada."); return }
        val option = GetGoogleIdOption.Builder()
            .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        CredentialManager.create(activity).getCredentialAsync(activity, request, CancellationSignal(), { main.post(it) },
            object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                override fun onResult(result: GetCredentialResponse) {
                    val c = result.credential
                    if (c !is CustomCredential || c.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                        done("Google no entregó una cuenta."); return
                    }
                    val idToken = GoogleIdTokenCredential.createFrom(c.data).idToken
                    io.execute {
                        val error = try {
                            val r = post("/auth/v1/token?grant_type=id_token",
                                JSONObject().put("provider", "google").put("id_token", idToken), null)
                            saveSession(JSONObject(r))
                            firstSync()
                            null
                        } catch (e: Exception) { "No se pudo iniciar sesión: ${e.message}" }
                        main.post { done(error) }
                    }
                }

                override fun onError(e: GetCredentialException) {
                    done(if (e is GetCredentialCancellationException) null
                        else "Google no permitió iniciar sesión en este equipo (${e.type.substringAfterLast('.')}).")
                }
            })
    }

    fun signOut() {
        prefs.edit().remove("cloud_access").remove("cloud_refresh").remove("cloud_expires")
            .remove("cloud_email").remove("cloud_user").remove("cloud_stamp").remove("cloud_dirty").apply()
        dirty = false
    }

    // ------------------------------------------------------------ sync

    /** App opened or came back: take what other devices saved, or push what is pending here. */
    fun refresh() {
        if (!signedIn || !configured) return
        io.execute { runCatching { pullOrPush() } }
    }

    fun syncNow(done: (String?) -> Unit) {
        io.execute {
            val error = try { pullOrPush(); null } catch (e: Exception) { e.message ?: "Error" }
            main.post { done(error) }
        }
    }

    private val pushTask = Runnable { io.execute { runCatching { push() } } }

    private fun markDirty() {
        dirty = true
        prefs.edit().putBoolean("cloud_dirty", true).apply()
        main.removeCallbacks(pushTask)
        main.postDelayed(pushTask, 4000)
    }

    private fun pullOrPush() {
        val remote = fetch()
        val stamp = prefs.getString("cloud_stamp", "") ?: ""
        when {
            remote == null -> push()
            remote.optString("updated_at") != stamp && !dirty -> apply(remote)
            remote.optString("updated_at") != stamp && dirty -> { apply(merge(remote)); push() }
            dirty -> push()
        }
    }

    /** First sign-in on this device: keep what both sides have. */
    private fun firstSync() {
        val remote = fetch()
        if (remote != null) apply(merge(remote))
        push()
    }

    private fun fetch(): JSONObject? {
        val a = JSONArray(get("/rest/v1/user_data?select=history,later,channel_options,updated_at"))
        return if (a.length() > 0) a.getJSONObject(0) else null
    }

    private fun push() {
        val user = prefs.getString("cloud_user", null) ?: return
        val now = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())
        val body = JSONArray().put(local().put("user_id", user).put("updated_at", now))
        val saved = JSONArray(post("/rest/v1/user_data?on_conflict=user_id", body, "resolution=merge-duplicates,return=representation"))
        val stamp = saved.optJSONObject(0)?.optString("updated_at") ?: now
        dirty = false
        prefs.edit().putString("cloud_stamp", stamp).putBoolean("cloud_dirty", false).apply()
    }

    private fun local(): JSONObject {
        val options = JSONObject()
        prefs.all.forEach { (k, v) -> if (k.startsWith("alt_") && v is Int) options.put(k.removePrefix("alt_"), v) }
        return JSONObject()
            .put("history", JSONArray(prefs.getString("history", "[]")))
            .put("later", JSONArray(prefs.getString("later", "[]")))
            .put("channel_options", options)
    }

    /** Union of both sides: newest position per title, every "ver más tarde", cloud options first. */
    private fun merge(remote: JSONObject): JSONObject {
        val mine = local()
        val byKey = LinkedHashMap<String, JSONObject>()
        fun addAll(a: JSONArray?) {
            if (a == null) return
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val k = (if (o.optBoolean("episode")) "e:" else "m:") + o.optString("id")
                val old = byKey[k]
                if (old == null || o.optLong("updated") > old.optLong("updated")) byKey[k] = o
            }
        }
        addAll(remote.optJSONArray("history")); addAll(mine.optJSONArray("history"))
        val history = JSONArray()
        byKey.values.sortedByDescending { it.optLong("updated") }.take(60).forEach { history.put(it) }

        val later = JSONArray()
        val seen = HashSet<String>()
        listOf(remote.optJSONArray("later"), mine.optJSONArray("later")).forEach { a ->
            if (a != null) for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                if (seen.add(o.optBoolean("series").toString() + o.optString("id"))) later.put(o)
            }
        }
        val options = mine.optJSONObject("channel_options") ?: JSONObject()
        remote.optJSONObject("channel_options")?.let { r -> r.keys().forEach { options.put(it, r.get(it)) } }
        return JSONObject().put("history", history).put("later", later).put("channel_options", options)
            .put("updated_at", remote.optString("updated_at"))
    }

    private fun apply(doc: JSONObject) {
        main.post {
            applying = true
            val e = prefs.edit()
            e.putString("history", (doc.optJSONArray("history") ?: JSONArray()).toString())
            e.putString("later", (doc.optJSONArray("later") ?: JSONArray()).toString())
            prefs.all.keys.filter { it.startsWith("alt_") }.forEach { e.remove(it) }
            doc.optJSONObject("channel_options")?.let { o -> o.keys().forEach { e.putInt("alt_$it", o.optInt(it)) } }
            if (doc.optString("updated_at").isNotEmpty()) e.putString("cloud_stamp", doc.optString("updated_at"))
            e.apply()
            applying = false
            onRemoteApplied()
        }
    }

    // ------------------------------------------------------------ Supabase REST

    private fun saveSession(r: JSONObject) {
        val user = r.optJSONObject("user")
        prefs.edit()
            .putString("cloud_access", r.getString("access_token"))
            .putString("cloud_refresh", r.getString("refresh_token"))
            .putLong("cloud_expires", System.currentTimeMillis() + r.optLong("expires_in", 3600) * 1000)
            .apply {
                if (user != null) {
                    putString("cloud_user", user.optString("id"))
                    putString("cloud_email", user.optString("email"))
                }
            }
            .apply()
    }

    /** A valid access token, refreshed when it is about to expire. */
    private fun token(): String {
        val refresh = prefs.getString("cloud_refresh", null) ?: throw IllegalStateException("Sin sesión")
        if (System.currentTimeMillis() < prefs.getLong("cloud_expires", 0) - 60_000) {
            prefs.getString("cloud_access", null)?.let { return it }
        }
        saveSession(JSONObject(post("/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", refresh), null, auth = false)))
        return prefs.getString("cloud_access", null)!!
    }

    private fun open(path: String, method: String, auth: Boolean): HttpURLConnection =
        (URL(BuildConfig.SUPABASE_URL.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000; readTimeout = 20_000
            setRequestProperty("apikey", BuildConfig.SUPABASE_KEY)
            // the publishable key goes only as apikey; Authorization carries the user's session
            if (auth) setRequestProperty("Authorization", "Bearer " + token())
            setRequestProperty("Content-Type", "application/json")
        }

    private fun read(c: HttpURLConnection): String {
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        c.disconnect()
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(text).let { it.optString("msg").ifEmpty { it.optString("message").ifEmpty { it.optString("error_description") } } } }.getOrNull()
            throw RuntimeException(msg?.ifEmpty { null } ?: "HTTP $code")
        }
        return text
    }

    private fun get(path: String) = read(open(path, "GET", auth = true))

    private fun post(path: String, body: Any, prefer: String?, auth: Boolean = path.startsWith("/rest")): String {
        val c = open(path, "POST", auth)
        if (prefer != null) c.setRequestProperty("Prefer", prefer)
        c.doOutput = true
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        return read(c).ifBlank { "[]" }
    }
}
