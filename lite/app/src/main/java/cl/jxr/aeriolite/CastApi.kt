package cl.jxr.aeriolite

/** What to send to the TV. */
data class CastMedia(
    val url: String,
    val title: String,
    val subtitle: String,
    val image: String,
    val live: Boolean,
    val ext: String,
    val positionMs: Long,
)

/** A Chromecast / Google TV found on the network ([handle] belongs to the Cast library). */
class CastRoute(val name: String, val handle: Any)
