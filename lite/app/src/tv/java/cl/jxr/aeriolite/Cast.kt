package cl.jxr.aeriolite

import android.content.Context

/**
 * Android TV build: no Google Cast library at all (lighter app). Same API as the phone
 * build, but never available, so the cast button never appears.
 */
@Suppress("UNUSED_PARAMETER")
class Caster(
    context: Context,
    onStarted: (deviceName: String) -> Unit,
    onEnded: (lastPositionMs: Long) -> Unit,
    onError: (String) -> Unit,
) {
    companion object {
        fun playServicesAvailable(context: Context): Boolean = false
    }

    val casting: Boolean get() = false
    val deviceName: String? get() = null
    fun startScan(onRoutes: (List<CastRoute>) -> Unit): Boolean = false
    fun stopScan() {}
    fun castTo(target: CastRoute, media: CastMedia) {}
    fun send(media: CastMedia) {}
    fun stop() {}
}
