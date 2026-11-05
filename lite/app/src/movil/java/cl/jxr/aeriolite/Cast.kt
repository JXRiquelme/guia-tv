package cl.jxr.aeriolite

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.images.WebImage

/** Uses Google's default media receiver (Chromecast, Google TV / Android TV with Cast built in). */
class LiteCastOptions : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/**
 * Small wrapper around the Cast framework. Created lazily, only on phones/tablets,
 * so Android TV never loads any of it.
 */
class Caster(
    private val context: Context,
    private val onStarted: (deviceName: String) -> Unit,
    private val onEnded: (lastPositionMs: Long) -> Unit,
    private val onError: (String) -> Unit,
) {
    private var castContext: CastContext? = null
    private var pending: CastMedia? = null
    private var callback: MediaRouter.Callback? = null
    private val router: MediaRouter by lazy { MediaRouter.getInstance(context) }

    companion object {
        fun playServicesAvailable(context: Context): Boolean = try {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        } catch (e: Throwable) {
            false
        }
    }

    private val listener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            onStarted(session.castDevice?.friendlyName ?: "TV")
            pending?.let { load(session, it) }
            pending = null
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            onStarted(session.castDevice?.friendlyName ?: "TV")
            pending?.let { load(session, it) }
            pending = null
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            pending = null
            onError("No se pudo conectar con el equipo.")
        }

        override fun onSessionEnding(session: CastSession) {
            lastPosition = session.remoteMediaClient?.approximateStreamPosition ?: lastPosition
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            onEnded(lastPosition)
        }

        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    private var lastPosition = 0L

    @Suppress("DEPRECATION")
    private fun cast(): CastContext? {
        castContext?.let { return it }
        return try {
            CastContext.getSharedInstance(context).also {
                it.sessionManager.addSessionManagerListener(listener, CastSession::class.java)
                castContext = it
            }
        } catch (e: Throwable) {
            null
        }
    }

    val casting: Boolean get() = cast()?.sessionManager?.currentCastSession?.isConnected == true
    val deviceName: String? get() = cast()?.sessionManager?.currentCastSession?.castDevice?.friendlyName

    /** Starts looking for devices; [onRoutes] is called every time the list changes. */
    fun startScan(onRoutes: (List<CastRoute>) -> Unit): Boolean {
        val selector: MediaRouteSelector = cast()?.mergedSelector ?: return false
        stopScan()
        fun publish() {
            onRoutes(router.routes.filter { !it.isDefault && it.isEnabled && it.matchesSelector(selector) }.map { CastRoute(it.name, it) })
        }
        val cb = object : MediaRouter.Callback() {
            override fun onRouteAdded(r: MediaRouter, route: MediaRouter.RouteInfo) = publish()
            override fun onRouteRemoved(r: MediaRouter, route: MediaRouter.RouteInfo) = publish()
            override fun onRouteChanged(r: MediaRouter, route: MediaRouter.RouteInfo) = publish()
        }
        callback = cb
        router.addCallback(selector, cb, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        publish()
        return true
    }

    fun stopScan() {
        callback?.let { router.removeCallback(it) }
        callback = null
    }

    /** Connects to [route]; [media] starts playing there as soon as the session is up. */
    fun castTo(target: CastRoute, media: CastMedia) {
        val route = target.handle as MediaRouter.RouteInfo
        stopScan()
        val session = cast()?.sessionManager?.currentCastSession
        if (session?.isConnected == true) {
            load(session, media)
            return
        }
        pending = media
        router.selectRoute(route)
    }

    /** While a session is open, switch what the TV is showing. */
    fun send(media: CastMedia) {
        cast()?.sessionManager?.currentCastSession?.takeIf { it.isConnected }?.let { load(it, media) }
    }

    fun stop() {
        cast()?.sessionManager?.endCurrentSession(true)
    }

    private val main = Handler(Looper.getMainLooper())
    private var statusCallback: Pair<RemoteMediaClient, RemoteMediaClient.Callback>? = null
    private var loadToken = 0

    /**
     * The IPTV server allows one connection at a time: the phone has just stopped its own
     * playback, so the TV asks a moment later. Meanwhile the real stream address is resolved
     * (the list's links redirect) and live .ts streams are asked for as HLS (.m3u8), the only
     * live format the Chromecast receiver plays.
     */
    private fun load(session: CastSession, m: CastMedia) {
        val token = ++loadToken
        val started = System.currentTimeMillis()
        Thread {
            var url = resolve(m.url)
            var hls = m.ext.equals("m3u8", true) || url.substringBefore('?').endsWith(".m3u8", true)
            if (m.live && !hls) {
                val path = url.substringBefore('?')
                if (path.endsWith(".ts", true)) {
                    url = path.dropLast(3) + ".m3u8" + url.substring(path.length)
                    hls = true
                }
            }
            val wait = 2000 - (System.currentTimeMillis() - started)
            if (wait > 0) Thread.sleep(wait)
            main.post { if (token == loadToken) sendTo(session, m, url, hls) }
        }.start()
    }

    /** Follows redirects without opening the video itself. */
    private fun resolve(address: String): String {
        var url = address
        repeat(6) {
            val c = try { URL(url).openConnection() as HttpURLConnection } catch (e: Exception) { return url }
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.setRequestProperty("User-Agent", Net.USER_AGENT)
                val code = c.responseCode
                if (code !in 300..399) return url
                val loc = c.getHeaderField("Location") ?: return url
                url = URL(URL(url), loc).toString()
            } catch (e: Exception) {
                return url
            } finally {
                c.disconnect()
            }
        }
        return url
    }

    private fun sendTo(session: CastSession, m: CastMedia, url: String, hls: Boolean) {
        val client = session.remoteMediaClient ?: return failed("El equipo no aceptó el contenido.")
        val type = when {
            hls -> "application/x-mpegurl"
            m.live -> "video/mp2t"
            m.ext.equals("mkv", true) -> "video/x-matroska"
            else -> "video/mp4"
        }
        val meta = MediaMetadata(if (m.live) MediaMetadata.MEDIA_TYPE_GENERIC else MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, m.title)
            if (m.subtitle.isNotEmpty()) putString(MediaMetadata.KEY_SUBTITLE, m.subtitle)
            if (m.image.startsWith("http")) addImage(WebImage(Uri.parse(m.image)))
        }
        val info = MediaInfo.Builder(url)
            .setStreamType(if (m.live) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(type)
            .setMetadata(meta)
            .build()
        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(info)
            .setAutoplay(true)
            .setCurrentTime(if (m.live) 0 else m.positionMs)
            .build()
        val message = if (m.live) "La TV no pudo abrir este canal: el servidor no lo entrega en un formato que Chromecast acepte. Prueba otra opción del canal."
            else "La TV no pudo reproducir este contenido (formato no compatible con Chromecast)."
        watch(client, message)
        client.load(request).setResultCallback { result ->
            if (!result.status.isSuccess) failed(message)
        }
    }

    /** The receiver can accept the request and fail later: then stop instead of staying on the cast icon. */
    private fun watch(client: RemoteMediaClient, message: String) {
        statusCallback?.let { (c, cb) -> c.unregisterCallback(cb) }
        val cb = object : RemoteMediaClient.Callback() {
            override fun onStatusUpdated() {
                val st = client.mediaStatus ?: return
                if (st.playerState == MediaStatus.PLAYER_STATE_IDLE && st.idleReason == MediaStatus.IDLE_REASON_ERROR) {
                    failed(message)
                }
            }
        }
        client.registerCallback(cb)
        statusCallback = client to cb
    }

    private fun failed(message: String) {
        statusCallback?.let { (c, cb) -> c.unregisterCallback(cb) }
        statusCallback = null
        onError(message)
        stop()
    }
}
