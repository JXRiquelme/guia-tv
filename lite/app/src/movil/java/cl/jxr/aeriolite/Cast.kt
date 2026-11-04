package cl.jxr.aeriolite

import android.content.Context
import android.net.Uri
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
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

    private fun load(session: CastSession, m: CastMedia) {
        val client = session.remoteMediaClient ?: return onError("El equipo no aceptó el contenido.")
        val type = when {
            m.ext.equals("m3u8", true) || m.url.contains(".m3u8", true) -> "application/x-mpegurl"
            m.live -> "video/mp2t"
            m.ext.equals("mkv", true) -> "video/x-matroska"
            else -> "video/mp4"
        }
        val meta = MediaMetadata(if (m.live) MediaMetadata.MEDIA_TYPE_GENERIC else MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, m.title)
            if (m.subtitle.isNotEmpty()) putString(MediaMetadata.KEY_SUBTITLE, m.subtitle)
            if (m.image.startsWith("http")) addImage(WebImage(Uri.parse(m.image)))
        }
        val info = MediaInfo.Builder(m.url)
            .setStreamType(if (m.live) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(type)
            .setMetadata(meta)
            .build()
        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(info)
            .setAutoplay(true)
            .setCurrentTime(if (m.live) 0 else m.positionMs)
            .build()
        client.load(request).setResultCallback { result ->
            if (!result.status.isSuccess) {
                onError(
                    if (m.live) "El equipo no pudo abrir este canal (formato de transmisión no compatible con Chromecast)."
                    else "El equipo no pudo reproducir este contenido (formato no compatible)."
                )
            }
        }
    }
}
