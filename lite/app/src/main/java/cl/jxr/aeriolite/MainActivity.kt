package cl.jxr.aeriolite

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.util.Rational
import android.content.Context
import android.content.SharedPreferences
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.LayerDrawable
import android.view.TextureView
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.media.AudioManager
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : Activity() {

    companion object {
        const val GUIDE_URL = "https://raw.githubusercontent.com/JXRiquelme/guia-tv/main/guia.xml"
        const val START_CHANNEL = "Canal 13"
        const val DEFAULT_SERVER = "https://reborned.cc:8443"
        const val GUIDE_MAX_AGE = 3 * 3600_000L

        const val TAB_LIVE = 0
        const val TAB_MOVIES = 1
        const val TAB_SERIES = 2

        const val MODE_CATEGORY = 0
        const val MODE_CONTINUE = 1
        const val MODE_SEARCH = 2
        const val MODE_LATER = 3

        /** Phone/tablet app: "liquid glass" look. Android TV app: solid colours (lighter to draw). */
        const val GLASS = !BuildConfig.TV
        const val SIDE_BUTTON = 44
        const val SIDE_TOP = 20
        val ACCENT = if (GLASS) 0xFFFFD27A.toInt() else 0xFF7FD3FF.toInt()
        val MUTED = if (GLASS) 0xFFD0D3DA.toInt() else 0xFFB8C7F0.toInt()
        val HINT = if (GLASS) 0xFFA3A8B3.toInt() else 0xFF8FA0CC.toInt()
        /** Selection / focus. */
        val BLUE = if (GLASS) 0x4DFFFFFF else 0xFF2F6FEB.toInt()
        /** Active chip / tab. */
        val CHIP = if (GLASS) 0x3DFFFFFF else 0xFF3B5BA8.toInt()
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2)
    private lateinit var prefs: SharedPreferences
    private lateinit var logos: Logos
    private lateinit var history: History
    private lateinit var later: Later
    private var aspectMode = 0 // 0 normal, 1 16:9, 2 4:3

    private var channels: List<Channel> = emptyList()
    private var guide: Map<String, List<Program>> = emptyMap()
    private var playlistAccount: XtreamAccount? = null
    private var current = 0
    private var player: ExoPlayer? = null
    private var lastBack = 0L
    private var digits = ""
    private var videoW = 0
    private var videoH = 0
    private var videoRatio = 1f

    /** Non-null while a movie or episode is playing. */
    private var vod: Watch? = null

    // Hidden channels (by name) and hidden categories (by id) for movies / series.
    private lateinit var hiddenChannels: MutableSet<String>
    private lateinit var hiddenMovieCats: MutableSet<String>
    private lateinit var hiddenSeriesCats: MutableSet<String>
    private fun hiddenCats(series: Boolean) = if (series) hiddenSeriesCats else hiddenMovieCats

    // Live views
    private lateinit var root: FrameLayout
    /** SurfaceView on TV (cheapest); TextureView on phones so it can be blurred behind the glass. */
    private lateinit var surface: View
    private lateinit var shutter: View
    private lateinit var status: TextView
    private lateinit var info: LinearLayout
    private lateinit var infoLogo: ImageView
    private lateinit var infoTitle: TextView
    private lateinit var infoNow: TextView
    private lateinit var infoNext: TextView
    private lateinit var infoProgress: ProgressBar
    private lateinit var menuButton: ImageView
    private lateinit var infoOptions: TextView
    private lateinit var card: LinearLayout
    private lateinit var cardLogo: ImageView
    private lateinit var cardName: TextView
    private lateinit var cardNow: TextView
    private lateinit var numberView: TextView

    // Channel panel
    private lateinit var panel: LinearLayout
    private lateinit var panelTabs: List<TextView>
    private lateinit var panelSearch: EditText
    private lateinit var list: ListView
    private val adapter = ChannelAdapter()
    private var shown: List<Int> = emptyList()
    private var channelQuery = ""

    // Setup screens
    private lateinit var setup: LinearLayout
    private lateinit var setupInput: EditText
    private lateinit var setupMessage: TextView
    private lateinit var account: LinearLayout
    private lateinit var accServer: EditText
    private lateinit var accUser: EditText
    private lateinit var accPass: EditText
    private lateinit var accMessage: TextView

    // Movie bar
    private lateinit var vodBar: LinearLayout
    private lateinit var vodTitle: TextView
    private lateinit var vodSub: TextView
    private lateinit var vodProgress: SeekBar
    private var seekDragging = false
    private lateinit var topButtons: LinearLayout
    private lateinit var infoButton: ImageView
    private lateinit var lockButton: ImageView
    /** Phone held upright: video on top, a row of buttons, then the guide fixed below. */
    private var portrait = false
    private lateinit var portraitBar: LinearLayout
    private lateinit var portraitOption: TextView
    private lateinit var portraitCast: ImageView
    private lateinit var portraitPip: ImageView
    private lateinit var epgCloseButton: View
    private val landscapeParams = HashMap<View, FrameLayout.LayoutParams>()
    private lateinit var brightSlider: VerticalSlider
    private lateinit var volSlider: VerticalSlider
    /** Controls locked against accidental touches (phones). */
    private var locked = false
    private lateinit var statsView: TextView
    private lateinit var castButton: ImageView
    private lateinit var pipButton: ImageView
    private val isTv by lazy { BuildConfig.TV || packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }
    private var castingTo: String? = null
    private var castRoutes: List<CastRoute> = emptyList()
    private val caster: Caster? by lazy {
        if (isTv || !Caster.playServicesAvailable(this)) null
        else Caster(this, ::castStarted, ::castEnded) { msg -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }
    private lateinit var vodTime: TextView
    private lateinit var btnPlay: TextView
    private lateinit var vodControls: List<TextView>
    private lateinit var subtitleText: TextView

    // Program guide grid
    private lateinit var epgBox: LinearLayout
    private lateinit var epgView: EpgView
    private lateinit var epgChannel: TextView
    private lateinit var epgTitle: TextView
    private lateinit var epgTime: TextView
    private lateinit var epgDesc: TextView

    // Generic option picker (audio, subtitles, aspect, item menu)
    private lateinit var pickerBox: LinearLayout
    private lateinit var pickerLayer: FrameLayout
    private lateinit var pickerTitle: TextView
    private lateinit var pickerList: ListView
    private var pickerOptions: List<String> = emptyList()
    private var pickerSelected = -1
    private var pickerAction: ((Int) -> Unit)? = null
    private var pickerReturnFocus: View? = null
    private val pickerAdapter = PickerAdapter()

    // Movies / series browser
    private lateinit var browser: LinearLayout
    private lateinit var browserTabs: List<TextView>
    private lateinit var grid: ListView
    private lateinit var continueBox: LinearLayout
    private lateinit var continueRow: LinearLayout
    private lateinit var chipsScroll: HorizontalScrollView
    private lateinit var chipsRow: LinearLayout
    private lateinit var browserSearch: EditText
    private lateinit var gridLabel: TextView
    private lateinit var browserMessage: TextView
    private lateinit var seriesView: LinearLayout
    private lateinit var seriesPoster: ImageView
    private lateinit var seriesTitle: TextView
    private lateinit var seriesInfo: TextView
    private lateinit var episodeList: ListView
    private lateinit var seasonScroll: HorizontalScrollView
    private lateinit var seasonRow: LinearLayout
    private var seasonEpisodes: List<Episode> = emptyList()
    private var selectedSeason = -1
    private lateinit var topToggleContinue: TextView
    private lateinit var topToggleLater: TextView
    private var topMode = 0 // 0 seguir viendo, 1 ver más tarde
    private val rowAdapter = GridRowAdapter()
    private val episodeAdapter = EpisodeAdapter()
    private var browserSeries = false
    private var pendingSeries = false
    private val categoryCache = HashMap<Boolean, List<Category>>()
    private val itemCache = HashMap<String, List<VodItem>>()
    private val catalogCache = HashMap<Boolean, List<VodItem>>()
    private val selectedCat = HashMap<Boolean, Category>()
    private var cats: List<Category> = emptyList()
    private var visibleCats: List<Category> = emptyList()
    private var chips: List<TextView> = emptyList()

    /** What the grid shows: VodItem or Watch entries. */
    private var items: List<Any> = emptyList()
    private var gridMode = MODE_CATEGORY
    private var openSeries: VodItem? = null
    private var episodes: List<Episode> = emptyList()
    private var loadToken = 0
    private var tileW = 0
    private var posterH = 0

    // Hide-categories dialog
    private lateinit var filterBox: LinearLayout
    private lateinit var filterTitle: TextView
    private lateinit var filterList: ListView
    private val filterAdapter = FilterAdapter()
    private var filterEntries: List<Pair<String, String>> = emptyList()
    private var filterHidden: MutableSet<String> = HashSet()
    private var filterKey = ""

    private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("lite", Context.MODE_PRIVATE)
        logos = Logos(this)
        history = History(prefs)
        later = Later(prefs)
        hiddenChannels = HashSet(prefs.getStringSet("hide_ch", emptySet()) ?: emptySet())
        hiddenMovieCats = HashSet(prefs.getStringSet("hide_vod", emptySet()) ?: emptySet())
        hiddenSeriesCats = HashSet(prefs.getStringSet("hide_series", emptySet()) ?: emptySet())
        buildUi()
        if (!isTv && prefs.getBoolean("show_stats", false)) toggleStats()
        if (GLASS) {
            // glass surfaces cast shadows (elevation), which also decides what is drawn on top:
            // menus and full-screen views must stay above the guide and the cards
            fun z(v: View, d: Int) { v.translationZ = dp(d).toFloat() }
            z(panel, 14); z(browser, 16); z(seriesView, 18); z(status, 24)
            z(filterBox, 30); z(setup, 34); z(account, 34); z(pickerLayer, 40)
        }
        val url = prefs.getString("list", "").orEmpty()
        if (url.isEmpty()) showSetup(null) else start()
        main.postDelayed(ticker, 30_000)
    }

    override fun onStart() {
        super.onStart()
        if (player != null) return
        val w = vod
        when {
            w != null -> playVod(w)
            browser.visibility == View.VISIBLE || setup.visibility == View.VISIBLE || account.visibility == View.VISIBLE -> {}
            channels.isNotEmpty() -> play(current)
        }
    }

    // ---------------------------------------------------------------- picture in picture (phones only)

    /** Only phones/tablets that support it; never on Android TV. */
    private val pipAllowed by lazy {
        !BuildConfig.TV && Build.VERSION.SDK_INT >= 26 &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            !packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPipNow(onlyIfPlaying = true)
    }

    private fun enterPipNow(onlyIfPlaying: Boolean) {
        if (!pipAllowed || Build.VERSION.SDK_INT < 26 || castingTo != null) return
        val watching = player != null && browser.visibility != View.VISIBLE &&
            setup.visibility != View.VISIBLE && account.visibility != View.VISIBLE
        if (!watching || (onlyIfPlaying && player?.isPlaying != true)) return
        try {
            val ratio = if (videoW > 0 && videoH > 0) {
                val r = (videoW * videoRatio / videoH).coerceIn(0.5f, 2.3f)
                Rational((r * 1000).toInt(), 1000)
            } else Rational(16, 9)
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(ratio).build())
        } catch (e: Exception) {
            // device refused PiP; nothing else to do
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            hideInfo.run()
            topButtons.visibility = View.GONE
            hideVodBar()
            hideStatus()
            panel.visibility = View.GONE
            epgBox.visibility = View.GONE
            pickerLayer.visibility = View.GONE
            card.visibility = View.GONE
            numberView.visibility = View.GONE
            statsView.visibility = View.GONE
        } else if (!isTv && prefs.getBoolean("show_stats", false)) {
            statsView.visibility = View.VISIBLE
        }
    }

    override fun onStop() {
        super.onStop()
        saveVodProgress()
        releasePlayer()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
        super.onDestroy()
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (info.visibility == View.VISIBLE) updateInfo()
            if (epgBox.visibility == View.VISIBLE) epgView.invalidate()
            if (panel.visibility == View.VISIBLE) adapter.notifyDataSetChanged()
            val g = File(filesDir, "guia.xml")
            if (channels.isNotEmpty() && System.currentTimeMillis() - g.lastModified() > GUIDE_MAX_AGE) refreshGuide(true)
            main.postDelayed(this, 30_000)
        }
    }

    // ---------------------------------------------------------------- live data

    private fun start() {
        hideSetup()
        val cached = File(filesDir, "list.m3u")
        val text = if (cached.exists()) cached.readText() else ""
        val fromCache = if (text.isNotEmpty()) Playlist.parse(text) else emptyList()
        playlistAccount = XtreamAccount.fromPlaylist(text)
        if (fromCache.isNotEmpty()) {
            setChannels(fromCache)
            play(startIndex())
            refreshList(silent = true)
        } else {
            showStatus("Cargando canales…")
            refreshList(silent = false)
        }
    }

    private fun startIndex(): Int {
        // Phones/tablets reopen on the last channel watched (with its chosen option);
        // the Android TV build always starts on Canal 13.
        if (!BuildConfig.TV) {
            val last = prefs.getString("last_channel", null)
            if (last != null) {
                val i = channels.indexOfFirst { it.name == last }
                if (i >= 0) return i
            }
        }
        val exact = channels.indexOfFirst { it.name.equals(START_CHANNEL, true) }
        if (exact >= 0) return exact
        val loose = channels.indexOfFirst { it.name.contains(START_CHANNEL, true) }
        return if (loose >= 0) loose else 0
    }

    private fun setChannels(newList: List<Channel>) {
        channels = newList
        rebuildShown()
        refreshGuide(false)
    }

    /** Channels in the side list: not hidden and matching the search box. */
    private fun rebuildShown() {
        val q = Search.normalize(channelQuery.trim())
        shown = channels.indices.filter { i ->
            channels[i].name !in hiddenChannels && (q.isEmpty() || Search.normalize(channels[i].name).contains(q))
        }
        adapter.notifyDataSetChanged()
    }

    private fun refreshList(silent: Boolean) {
        val url = prefs.getString("list", "").orEmpty()
        io.execute {
            val file = File(filesDir, "list.m3u")
            val text = try {
                Net.download(url, file)
                file.readText()
            } catch (e: Exception) {
                main.post {
                    if (!silent) showSetup("No se pudo descargar la lista (${e.message}). Revisa la dirección o la conexión.")
                }
                return@execute
            }
            val result = Playlist.parse(text)
            main.post {
                playlistAccount = XtreamAccount.fromPlaylist(text)
                if (result.isEmpty()) {
                    if (!silent) showSetup("La dirección no contiene una lista M3U válida.")
                    return@post
                }
                if (result == channels) return@post
                val playing = channels.getOrNull(current)
                setChannels(result)
                val canPlay = vod == null && browser.visibility != View.VISIBLE
                if (playing == null) {
                    hideStatus()
                    if (canPlay) play(startIndex()) else current = startIndex()
                } else {
                    val same = result.indexOfFirst { it.url == playing.url }
                        .takeIf { it >= 0 } ?: result.indexOfFirst { it.name == playing.name }
                    if (same >= 0) current = same
                    else if (canPlay) play(startIndex())
                    else current = startIndex()
                }
            }
        }
    }

    private fun refreshGuide(forceDownload: Boolean) {
        val wanted = channels.mapNotNull { it.tvgId.ifEmpty { null } }.toSet()
        if (wanted.isEmpty()) return
        io.execute {
            val file = File(filesDir, "guia.xml")
            val stale = !file.exists() || System.currentTimeMillis() - file.lastModified() > GUIDE_MAX_AGE
            if (forceDownload || stale) {
                try {
                    Net.download(GUIDE_URL, file)
                } catch (e: Exception) {
                    // keep using whatever is cached
                }
            }
            if (!file.exists()) return@execute
            val parsed = try {
                Guide.parse(file, wanted, System.currentTimeMillis())
            } catch (e: Exception) {
                return@execute
            }
            main.post {
                guide = parsed
                if (::epgView.isInitialized) epgView.invalidate()
                adapter.notifyDataSetChanged()
                if (info.visibility == View.VISIBLE) updateInfo()
            }
        }
    }

    // ---------------------------------------------------------------- playback

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Net.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            // Close each stream's socket when switching: IPTV servers often allow a single
            // connection per account and keep a reused/half-open one "busy" for a while.
            .setDefaultRequestProperties(mapOf("Connection" to "close"))
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        )
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 40_000, 1_500, 3_000)
            .build()
        // The device's own audio decoders go first (movies with 5.1 / AC-3 audio stalled at 0:00
        // when FFmpeg was preferred); FFmpeg only takes formats the device can't decode
        // (AC-3, E-AC-3, MP2… on phones without them), so those channels are not silent.
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val p = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http, extractors))
            .setLoadControl(load)
            .build()
        val sv = surface
        if (sv is SurfaceView) p.setVideoSurfaceView(sv) else p.setVideoTextureView(sv as TextureView)
        applyLanguages(p)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> if (card.visibility != View.VISIBLE && recoverAttempts == 0) {
                        val note = resumeNote
                        showStatus(if (vod != null && note != null) "Cargando…\n$note" else "Cargando…")
                    }
                    Player.STATE_READY -> {
                        stallSince = 0L
                        recoverAttempts = 0
                        resumeNote = null
                        hideStatus()
                        fadeCard()
                    }
                    Player.STATE_ENDED -> if (vod != null) vodEnded() else recover()
                    else -> {}
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (vod != null) updateVodBar()
            }

            override fun onRenderedFirstFrame() {
                shutter.visibility = View.GONE
            }

            override fun onCues(cueGroup: CueGroup) {
                val t = cueGroup.cues.mapNotNull { it.text?.toString() }.joinToString("\n").trim()
                subtitleText.text = t
                subtitleText.visibility = if (t.isEmpty() || vod == null) View.GONE else View.VISIBLE
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player?.seekToDefaultPosition()
                    player?.prepare()
                } else {
                    fadeCard()
                    recover()
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoW = videoSize.width
                    videoH = videoSize.height
                    videoRatio = videoSize.pixelWidthHeightRatio
                    fitVideo()
                }
            }
        })
        player = p
        main.removeCallbacks(health)
        main.postDelayed(health, 1_000)
        return p
    }

    private fun play(index: Int) {
        if (channels.isEmpty()) return
        main.removeCallbacks(pendingZap)
        saveVodProgress()
        if (vod != null) shutter.visibility = View.VISIBLE
        vod = null
        resumeNote = null
        hideVodBar()
        subtitleText.visibility = View.GONE
        if (aspectMode != 0) { aspectMode = 0; fitVideo() }
        current = index.coerceIn(0, channels.size - 1)
        recoverAttempts = 0
        if (castingTo != null) {
            castMedia()?.let { caster?.send(it) }
            showInfo(false)
            return
        }
        val ch = channels[current]
        if (!BuildConfig.TV) prefs.edit().putString("last_channel", ch.name).apply()
        measuredFps = 0f
        lastFrames = -1
        altOverride = null
        recoverAttempts = 0
        stallSince = 0L
        val p = ensurePlayer()
        applyLanguages(p)
        p.setMediaItem(liveItem(ch))
        p.prepare()
        p.playWhenReady = true
        playToken++
        showInfo(false)
        if (portrait) applyLayoutMode()
    }

    private fun liveItem(ch: Channel): MediaItem {
        val url = ch.options.getOrNull(altOverride ?: -1)?.url ?: streamUrl(ch)
        val item = MediaItem.Builder().setUri(url)
        if (url.contains(".m3u8", true)) item.setMimeType(MimeTypes.APPLICATION_M3U8)
        return item.build()
    }

    // ---------------------------------------------------------------- self-healing playback

    /** Option tried automatically when the chosen one stops working (not saved). */
    private var altOverride: Int? = null
    private var recoverAttempts = 0
    private var stallSince = 0L

    /**
     * Checks the stream every second. If it is stuck (buffering, or failed) for a few seconds
     * it is reopened quietly with a fresh connection, keeping the last picture on screen. After
     * a few failed tries it moves to the channel's next option; it never gives up while you
     * stay on the channel, and only says something if it takes long.
     */
    private val health: Runnable = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && castingTo == null && p.playWhenReady) {
                val failed = p.playbackState == Player.STATE_IDLE && p.playerError != null
                val stuck = p.playbackState == Player.STATE_BUFFERING || failed
                val now = SystemClock.elapsedRealtime()
                if (stuck) {
                    if (stallSince == 0L) stallSince = now
                    // first open gets more time; later stalls are fixed faster
                    val limit = when {
                        failed -> 1_500L
                        recoverAttempts == 0 && p.currentPosition < 1_000 -> 10_000L
                        else -> 6_000L
                    }
                    if (now - stallSince >= limit) recover()
                } else stallSince = 0L
            }
            main.postDelayed(this, 1_000)
        }
    }

    private fun recover() {
        val p = player ?: return
        if (castingTo != null) return
        recoverAttempts++
        stallSince = SystemClock.elapsedRealtime()
        val token = playToken
        val w = vod
        if (w != null) {
            // movie / episode: reopen at the same second
            val pos = p.currentPosition
            if (recoverAttempts >= 3) showStatus("Recuperando la conexión…")
            p.stop()
            main.postDelayed({
                if (player === p && token == playToken) {
                    p.prepare()
                    if (pos > 0) p.seekTo(pos)
                    p.playWhenReady = true
                }
            }, 800)
            return
        }
        val ch = channels.getOrNull(current) ?: return
        // every 3rd failed try, move to the next option of the channel (if it has any)
        if (recoverAttempts % 3 == 0 && ch.alts.isNotEmpty()) {
            val base = altOverride ?: altIndex(ch)
            altOverride = (base + 1) % ch.options.size
            if (info.visibility == View.VISIBLE) updateInfo()
        }
        when {
            recoverAttempts >= 3 * ch.options.size + 3 ->
                showStatus("Sin señal en ${ch.name}.\nSigue intentando… o prueba otro canal.")
            recoverAttempts >= 3 -> showStatus("Recuperando la señal…")
        }
        p.stop()   // frees the (single) connection; the last picture stays on screen
        main.postDelayed({
            if (player === p && token == playToken && vod == null) {
                p.setMediaItem(liveItem(ch))
                p.prepare()
                p.playWhenReady = true
            }
        }, if (recoverAttempts > 6) 4_000L else 800L)
    }

    /** Bumped on every new channel/movie so delayed retries of an older one are ignored. */
    private var playToken = 0

    /**
     * ▲▼ zapping: the banner follows each press at once, but the stream only opens when the
     * presses stop (≈0.6 s). Fast zapping no longer opens a connection per channel.
     */
    private val pendingZap = Runnable { play(current) }

    private fun releasePlayer() {
        main.removeCallbacks(health)
        main.removeCallbacks(vodSaver)
        main.removeCallbacks(pendingZap)
        player?.release()
        player = null
    }

    private fun stopPlayback() {
        main.removeCallbacks(vodSaver)
        main.removeCallbacks(pendingZap)
        player?.playWhenReady = false   // nothing to watch: the health check leaves it alone
        player?.stop()
        hideStatus()
    }

    /** Next visible channel in [step] direction (hidden channels are skipped). */
    private fun neighbor(step: Int): Int {
        val order = channels.indices.filter { channels[it].name !in hiddenChannels }.ifEmpty { channels.indices.toList() }
        val pos = order.indexOf(current)
        if (pos < 0) return order.firstOrNull { it > current } ?: order.first()
        return order[(pos + step + order.size) % order.size]
    }

    private fun zap(step: Int) {
        if (channels.isEmpty()) return
        current = neighbor(step)
        player?.playWhenReady = false
        player?.stop()                      // drop the old stream right away
        shutter.visibility = View.VISIBLE   // no frozen frame of the old channel
        hideStatus()
        showInfo(false)
        main.removeCallbacks(pendingZap)
        main.postDelayed(pendingZap, 600)
    }

    private fun fitVideo() {
        val w = root.width
        val box = videoBox()
        val h = box[1]
        if (w == 0 || h == 0 || videoW == 0 || videoH == 0) return
        val aspect = when (aspectMode) {
            1 -> 16f / 9f
            2 -> 4f / 3f
            else -> videoW * videoRatio / videoH
        }
        val lp = surface.layoutParams as FrameLayout.LayoutParams
        if (w.toFloat() / h > aspect) {
            lp.height = h
            lp.width = (h * aspect).toInt()
        } else {
            lp.width = w
            lp.height = (w / aspect).toInt()
        }
        lp.gravity = if (portrait) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.CENTER
        lp.topMargin = if (portrait) box[0] + (h - lp.height) / 2 else 0
        surface.layoutParams = lp
        placeSideButtons()
    }

    /** Side buttons sit in the middle of the black bars beside the picture (or near the edge if there are none). */
    private fun placeSideButtons() {
        if (isTv || !::topButtons.isInitialized) return
        val w = root.width
        val vw = (surface.layoutParams as FrameLayout.LayoutParams).width
        val bar = if (w > 0 && vw in 1 until w) (w - vw) / 2 else 0
        val button = dp(SIDE_BUTTON)
        val margin = if (bar >= button + dp(12)) (bar - button) / 2 else dp(20)
        (menuButton.layoutParams as FrameLayout.LayoutParams).let { if (it.leftMargin != margin) { it.leftMargin = margin; menuButton.layoutParams = it } }
        (topButtons.layoutParams as FrameLayout.LayoutParams).let { if (it.rightMargin != margin) { it.rightMargin = margin; topButtons.layoutParams = it } }
        // sliders: from the middle of the screen (or below the buttons) down to near the bottom
        val h = root.height
        if (h == 0) return
        val column = dp(SIDE_TOP) + 4 * button + 4 * dp(10)
        val top = maxOf(h / 2, column + dp(6))
        val height = (h - top - dp(20)).coerceAtLeast(dp(90))
        // movie bar: same height as the sliders, between them, with the same gap on each side
        if (!portrait) {
            val side = margin + button + dp(12)
            val lp = vodBar.layoutParams as FrameLayout.LayoutParams
            if (lp.topMargin != top || lp.height != height || lp.leftMargin != side || lp.gravity != (Gravity.TOP or Gravity.START)) {
                lp.gravity = Gravity.TOP or Gravity.START
                lp.width = -1
                lp.height = height
                lp.topMargin = top
                lp.bottomMargin = 0
                lp.leftMargin = side
                lp.rightMargin = side
                vodBar.layoutParams = lp
            }
        }
        for ((v, left) in listOf(brightSlider to true, volSlider to false)) {
            val lp = v.layoutParams as FrameLayout.LayoutParams
            if (lp.topMargin != top || lp.height != height || (if (left) lp.leftMargin else lp.rightMargin) != margin) {
                lp.topMargin = top
                lp.height = height
                if (left) lp.leftMargin = margin else lp.rightMargin = margin
                v.layoutParams = lp
            }
        }
    }

    // ---------------------------------------------------------------- lock, brightness, volume (phones)

    private fun toggleLock() {
        locked = !locked
        lockButton.setImageResource(if (locked) R.drawable.ic_lock else R.drawable.ic_lock_open)
        lockButton.setColorFilter(if (locked) ACCENT else Color.WHITE)
        main.removeCallbacks(hideInfo)
        if (locked) {
            info.visibility = View.GONE
            menuButton.visibility = View.GONE
            if (vod != null) { main.removeCallbacks(hideVodBarTask); vodBar.visibility = View.GONE }
            showTopButtons()
            Toast.makeText(this, "Controles bloqueados. Toca el candado para desbloquear.", Toast.LENGTH_SHORT).show()
            main.postDelayed(hideInfo, 2500)
        } else if (vod != null) {
            showTopButtons()
            showVodBar()
        } else showInfo(true)
    }

    /** Locked: a touch only brings up the lock button for a moment. */
    private fun showLockOnly() {
        showTopButtons()
        main.removeCallbacks(hideInfo)
        main.postDelayed(hideInfo, 2500)
    }

    /** Keeps the controls on screen while a slider is being dragged. */
    private fun holdControls(touching: Boolean) {
        main.removeCallbacks(hideInfo)
        main.removeCallbacks(hideVodBarTask)
        if (!touching) {
            if (vod != null) main.postDelayed(hideVodBarTask, 4000) else main.postDelayed(hideInfo, 4000)
        }
    }

    private val audio by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

    private fun setBrightness(v: Float) {
        window.attributes = window.attributes.apply { screenBrightness = v.coerceIn(0.01f, 1f) }
    }

    private fun setVolume(v: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(v * max), 0)
    }

    private fun currentBrightness(): Float {
        val b = window.attributes.screenBrightness
        if (b >= 0f) return b
        return try {
            android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (e: Exception) { 0.5f }
    }

    /** Sliders follow the side buttons (shown with them, never while locked). */
    private fun syncSliders() {
        if (!::volSlider.isInitialized) return
        val show = !isTv && !portrait && !locked && topButtons.visibility == View.VISIBLE && menuButton.visibility == View.VISIBLE ||
            (!isTv && !locked && vod != null && topButtons.visibility == View.VISIBLE)
        val target = if (show) View.VISIBLE else View.GONE
        if (brightSlider.visibility != target) {
            if (show) {
                brightSlider.value = currentBrightness()
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                volSlider.value = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
            }
            brightSlider.visibility = target
            volSlider.visibility = target
        }
    }


    // ---------------------------------------------------------------- upright phone layout

    private val gridColumns get() = if (portrait) 3 else 6

    /** Upright: guide dragged down, only the player (centred) with its card and buttons below. */
    private var guideCollapsed = false
    private var topInset = 0
    private var bottomInset = 0
    private lateinit var portraitTabs: LinearLayout
    private lateinit var portraitTabViews: List<TextView>
    private lateinit var portraitGuide: ImageView

    private fun buildPortraitBar() {
        // Canales / Películas / Series at the very top (under the status bar)
        portraitTabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setBackgroundColor(Color.BLACK)
            isClickable = true
            visibility = View.GONE
        }
        portraitTabViews = listOf("Canales", "Películas", "Series").mapIndexed { i, label ->
            text(15f, bold = true).apply {
                this.text = label
                gravity = Gravity.CENTER
                setPadding(dp(16), 0, dp(16), 0)
                isClickable = true
                setOnClickListener {
                    when (i) {
                        TAB_MOVIES -> openBrowser(false)
                        TAB_SERIES -> openBrowser(true)
                        else -> if (browser.visibility == View.VISIBLE) closeBrowser()
                    }
                }
            }
        }
        portraitTabViews.forEach { portraitTabs.addView(it, LinearLayout.LayoutParams(-2, dp(36)).apply { rightMargin = dp(6) }) }
        root.addView(portraitTabs, FrameLayout.LayoutParams(-1, dp(44), Gravity.TOP))

        portraitBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundColor(Color.BLACK)
            isClickable = true
            visibility = View.GONE
        }
        fun icon(res: Int, action: () -> Unit) = ImageView(this).apply {
            setImageResource(res)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(9), dp(9), dp(9), dp(9))
            background = surfaceBg(0xE60A1E5A.toInt(), 20, 0, 20)
            isClickable = true
            setOnClickListener { action() }
        }
        fun add(v: View, w: Int = dp(40)) = portraitBar.addView(v, LinearLayout.LayoutParams(w, dp(40)).apply { rightMargin = dp(8) })
        // only while the guide is folded away: brings it back
        portraitGuide = icon(R.drawable.ic_menu) { expandGuide() }
        add(portraitGuide)
        portraitOption = text(14f, bold = true).apply {
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_swap, 0, 0, 0)
            compoundDrawablePadding = dp(6)
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(14), 0)
            background = surfaceBg(0xE60A1E5A.toInt(), 20, 0, 20)
            isClickable = true
            setOnClickListener { chooseOption() }
        }
        add(portraitOption, -2)
        portraitBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        portraitCast = icon(R.drawable.ic_cast) { castMenu() }
        portraitPip = icon(R.drawable.ic_pip) { enterPipNow(onlyIfPlaying = false) }
        add(portraitCast)
        add(portraitPip)
        add(icon(R.drawable.ic_info) { toggleStats() })
        portraitBar.addView(icon(R.drawable.ic_settings) { openSettings() }, LinearLayout.LayoutParams(dp(40), dp(40)))
        root.addView(portraitBar, FrameLayout.LayoutParams(-1, dp(52), Gravity.TOP))
    }

    private fun updatePortraitBar() {
        if (!::portraitBar.isInitialized) return
        val ch = channels.getOrNull(current)
        if (ch == null || ch.alts.isEmpty()) portraitOption.visibility = View.GONE
        else {
            portraitOption.text = ch.options[altOverride?.takeIf { it < ch.options.size } ?: altIndex(ch)].label
            portraitOption.visibility = View.VISIBLE
        }
        portraitCast.visibility = if (caster != null) View.VISIBLE else View.GONE
        portraitPip.visibility = if (pipAllowed && castingTo == null) View.VISIBLE else View.GONE
        portraitGuide.visibility = if (guideCollapsed) View.VISIBLE else View.GONE
        portraitTabViews.forEachIndexed { i, t ->
            val active = i == TAB_LIVE
            t.setTextColor(if (active) Color.WHITE else MUTED)
            t.background = if (active) box(0x2EFFFFFF, 18) else null
        }
    }

    /** The fixed guide below the player follows the channel being watched. */
    private fun syncPortraitGuide() {
        if (channels.isEmpty()) return
        val rows = channels.filter { it.name !in hiddenChannels }.ifEmpty { channels }
        val currentRow = rows.indexOfFirst { it.number == channels.getOrNull(current)?.number }.coerceAtLeast(0)
        epgView.setData(rows, currentRow)
        if (guide.isEmpty()) refreshGuide(false)
    }

    private fun place(v: View, lp: FrameLayout.LayoutParams) {
        if (!landscapeParams.containsKey(v)) landscapeParams[v] = FrameLayout.LayoutParams(v.layoutParams as FrameLayout.LayoutParams)
        v.layoutParams = lp
    }

    private fun restore(v: View) {
        landscapeParams[v]?.let { v.layoutParams = FrameLayout.LayoutParams(it) }
    }

    /** Where the picture goes: [top, height] of the video area. */
    private fun videoBox(): IntArray {
        val w = root.width
        val h = root.height
        if (!portrait) return intArrayOf(0, h)
        val live = vod == null
        val top = topInset + if (live) dp(44) else 0
        if (live && !guideCollapsed) return intArrayOf(top, w * 9 / 16)
        val below = if (live) dp(52 + 96 + 12) else dp(150)
        val height = (h - bottomInset - below - top).coerceAtLeast(w * 9 / 16)
        return intArrayOf(top, height)
    }

    /** Header of the upright guide dragged down: fold the guide away. */
    private fun collapseGuide() {
        if (guideCollapsed) return
        epgBox.animate().translationY(epgBox.height.toFloat()).alpha(0f).setDuration(200).withEndAction {
            guideCollapsed = true
            epgBox.translationY = 0f
            epgBox.alpha = 1f
            applyLayoutMode()
        }.start()
    }

    private fun expandGuide() {
        if (!guideCollapsed) return
        guideCollapsed = false
        applyLayoutMode()
        epgBox.translationY = epgBox.height.toFloat().coerceAtLeast(dp(300).toFloat())
        epgBox.animate().translationY(0f).setDuration(220).start()
    }

    private fun readInsets() {
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        val underBars = loc[1] == 0
        @Suppress("DEPRECATION")
        val ins = if (Build.VERSION.SDK_INT >= 23) root.rootWindowInsets else null
        @Suppress("DEPRECATION")
        topInset = if (!portrait || !underBars) 0 else (ins?.systemWindowInsetTop?.takeIf { it > 0 } ?: statusBarHeight())
        @Suppress("DEPRECATION")
        bottomInset = if (!portrait) 0 else (ins?.systemWindowInsetBottom ?: 0)
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    /** Switches between the sideways (full screen) and upright (player + guide) layouts. */
    private fun applyLayoutMode() {
        val w = root.width
        val h = root.height
        if (w == 0 || h == 0) return
        val was = portrait
        portrait = !isTv && GLASS && h > w && !(Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode)
        epgView.setCompact(portrait)
        if (!GLASS) { fitVideo(); return }
        // status bar: visible upright, hidden sideways
        if (portrait) window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        else window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        readInsets()
        if (portrait) {
            val box = videoBox()
            val boxTop = box[0]
            val boxH = box[1]
            val live = vod == null
            menuButton.visibility = View.GONE
            topButtons.visibility = View.GONE
            panel.visibility = View.GONE
            place(shutter, FrameLayout.LayoutParams(-1, boxH, Gravity.TOP).apply { topMargin = boxTop })
            place(card, FrameLayout.LayoutParams(-1, boxH, Gravity.TOP).apply { topMargin = boxTop })
            place(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = boxTop + boxH / 2 - dp(32); leftMargin = dp(16); rightMargin = dp(16)
            })
            place(subtitleText, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = boxTop + boxH - dp(64); leftMargin = dp(16); rightMargin = dp(16)
            })
            place(statsView, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = boxTop + dp(8) })
            place(browser, FrameLayout.LayoutParams(-1, -1).apply { topMargin = topInset; bottomMargin = bottomInset })
            place(seriesView, FrameLayout.LayoutParams(-1, -1).apply { topMargin = topInset; bottomMargin = bottomInset })
            place(filterBox, FrameLayout.LayoutParams(minOf(dp(560), w - dp(24)), -1, Gravity.CENTER).apply {
                topMargin = topInset + dp(12); bottomMargin = bottomInset + dp(12)
            })
            place(pickerBox, FrameLayout.LayoutParams(minOf(dp(460), w - dp(32)), -2, Gravity.CENTER))
            portraitTabs.visibility = if (live) View.VISIBLE else View.GONE
            (portraitTabs.layoutParams as FrameLayout.LayoutParams).let { it.topMargin = topInset; portraitTabs.layoutParams = it }
            if (live && !guideCollapsed) {
                // player · buttons · guide
                portraitBar.visibility = View.VISIBLE
                portraitBar.layoutParams = FrameLayout.LayoutParams(-1, dp(52), Gravity.TOP).apply { topMargin = boxTop + boxH }
                val guideTop = boxTop + boxH + dp(52)
                place(epgBox, FrameLayout.LayoutParams(-1, h - guideTop - bottomInset - dp(8), Gravity.TOP).apply {
                    setMargins(dp(8), guideTop, dp(8), 0)
                })
                epgBox.animate().cancel()
                epgBox.translationX = 0f
                epgBox.alpha = 1f
                epgBox.visibility = View.VISIBLE
                epgCloseButton.visibility = View.GONE
                info.visibility = View.GONE
                // menus float centred over the guide
                pickerLayer.setPadding(0, guideTop, 0, bottomInset)
                syncPortraitGuide()
            } else if (live) {
                // player only: card and buttons at the bottom
                epgBox.visibility = View.GONE
                portraitBar.visibility = View.VISIBLE
                portraitBar.layoutParams = FrameLayout.LayoutParams(-1, dp(52), Gravity.BOTTOM).apply { bottomMargin = bottomInset + dp(4) }
                place(info, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                    setMargins(dp(10), 0, dp(10), bottomInset + dp(60))
                })
                updateInfo()
                info.visibility = View.VISIBLE
                pickerLayer.setPadding(0, topInset, 0, bottomInset)
            } else {
                // movie / episode: like the sideways player, upright
                portraitBar.visibility = View.GONE
                epgBox.visibility = View.GONE
                info.visibility = View.GONE
                place(vodBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(10), 0, dp(10), bottomInset + dp(12)) })
                pickerLayer.setPadding(0, topInset, 0, bottomInset)
            }
            updatePortraitBar()
        } else if (was) {
            for (v in landscapeParams.keys) restore(v)
            portraitBar.visibility = View.GONE
            portraitTabs.visibility = View.GONE
            epgBox.visibility = View.GONE
            info.visibility = View.GONE
            epgCloseButton.visibility = View.VISIBLE
            pickerLayer.setPadding(0, 0, 0, 0)
        }
        if (!portrait) (epgBox.layoutParams as FrameLayout.LayoutParams).let {
            val want = (w * 0.72f).toInt()
            if (it.width != want) { it.width = want; epgBox.layoutParams = it }
        }
        if (was != portrait) {
            if (videoW == 0) {
                val box = videoBox()
                val lp = surface.layoutParams as FrameLayout.LayoutParams
                lp.width = -1; lp.height = if (portrait) box[1] else -1; lp.gravity = Gravity.TOP; lp.topMargin = box[0]
                surface.layoutParams = lp
            }
            tileW = 0
            if (::grid.isInitialized) (grid.adapter as? BaseAdapter)?.notifyDataSetChanged()
        }
        fitVideo()
    }

    // ---------------------------------------------------------------- stream info (i)

    private var lastFrames = -1
    private var lastFramesAt = 0L
    private var measuredFps = 0f

    private fun toggleStats() {
        val on = statsView.visibility != View.VISIBLE
        prefs.edit().putBoolean("show_stats", on).apply()
        infoButton.setColorFilter(if (on) ACCENT else Color.WHITE)
        lastFrames = -1
        main.removeCallbacks(statsTick)
        if (on) { statsView.text = "…"; statsView.visibility = View.VISIBLE; statsTick.run() }
        else statsView.visibility = View.GONE
    }

    private val statsTick = object : Runnable {
        override fun run() {
            val p = player
            val f = p?.videoFormat
            if (p != null && f != null && f.width > 0) {
                // Frames really shown per second (the stream's declared rate is often missing in TV streams)
                val counters = p.videoDecoderCounters
                val now = SystemClock.elapsedRealtime()
                if (counters != null) {
                    counters.ensureUpdated()
                    val frames = counters.renderedOutputBufferCount
                    if (lastFrames >= 0 && now > lastFramesAt && frames >= lastFrames) {
                        val fps = (frames - lastFrames) * 1000f / (now - lastFramesAt)
                        measuredFps = if (measuredFps == 0f) fps else measuredFps * 0.6f + fps * 0.4f
                    }
                    lastFrames = frames
                    lastFramesAt = now
                }
                val fps = when {
                    measuredFps > 1f -> measuredFps
                    f.frameRate > 0f -> f.frameRate
                    else -> 0f
                }
                val label = when {
                    f.height >= 2000 -> "4K"
                    f.height >= 1000 -> "1080p"
                    f.height >= 700 -> "720p"
                    else -> "${f.height}p"
                }
                statsView.text = "$label  ·  ${f.width}×${f.height}" + if (fps > 0f) "  ·  ${"%.0f".format(Locale.US, fps)} fps" else ""
            } else {
                statsView.text = "Sin video"
                measuredFps = 0f
                lastFrames = -1
            }
            main.postDelayed(this, 1000)
        }
    }

    // ---------------------------------------------------------------- movies & series playback

    private fun xtream(): XtreamAccount? {
        playlistAccount?.let { return it }
        val acc = XtreamAccount(
            prefs.getString("xt_server", "").orEmpty(),
            prefs.getString("xt_user", "").orEmpty(),
            prefs.getString("xt_pass", "").orEmpty(),
        )
        return acc.takeIf { it.valid }
    }

    private fun playVod(w: Watch) {
        val acc = xtream() ?: return
        saveVodProgress()
        if (portrait) root.post { applyLayoutMode() }
        hideKeyboard()
        browser.visibility = View.GONE
        panel.visibility = View.GONE
        hideInfo.run()
        vod = w
        recoverAttempts = 0
        if (castingTo != null) {
            castMedia()?.let { caster?.send(it) }
            showVodBar()
            return
        }
        if (aspectMode != 0) { aspectMode = 0; fitVideo() }
        val url = if (w.episode) acc.episodeUrl(w.id, w.ext) else acc.movieUrl(w.id, w.ext)
        val item = MediaItem.Builder().setUri(url)
        if (w.ext.equals("m3u8", true)) item.setMimeType(MimeTypes.APPLICATION_M3U8)
        shutter.visibility = View.VISIBLE
        val p = ensurePlayer()
        applyLanguages(p)
        p.setMediaItem(item.build())
        val saved = history.get(w.key)
        val resumeAt = saved?.takeIf { !it.finished && it.position > 10_000 }?.position ?: 0L
        if (resumeAt > 0) p.seekTo(resumeAt)
        p.prepare()
        p.playWhenReady = true
        playToken++
        subtitleText.text = ""
        subtitleText.visibility = View.GONE
        // Appears in "Seguir viendo" right away, not only after some minutes.
        history.update(w, resumeAt, saved?.duration ?: 0L)
        resumeNote = if (resumeAt > 0) "Continuando desde ${clock(resumeAt)}" else null
        if (resumeNote != null) showStatus("Cargando…\n$resumeNote")
        root.requestFocus()
        showVodBar()
        main.removeCallbacks(vodSaver)
        main.postDelayed(vodSaver, 10_000)
    }

    private val vodSaver = object : Runnable {
        override fun run() {
            if (vod != null && player?.isPlaying == true) saveVodProgress()
            if (vod != null) main.postDelayed(this, 10_000)
        }
    }

    private fun saveVodProgress() {
        val w = vod ?: return
        val p = player ?: return
        val pos = p.currentPosition
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        history.update(w, pos.coerceAtLeast(0), dur)
    }

    private fun vodEnded() {
        val w = vod ?: return
        val dur = player?.duration?.takeIf { it > 0 } ?: w.duration
        history.update(w, dur, dur)
        if (w.episode) {
            val idx = episodes.indexOfFirst { it.id == w.id }
            val next = episodes.getOrNull(idx + 1)
            val series = openSeries
            if (idx >= 0 && next != null && series != null) {
                playVod(episodeWatch(series, next))
                return
            }
        }
        leaveVod()
    }

    /** Back from a movie/episode: keep the progress and show the catalog again. */
    private fun leaveVod() {
        saveVodProgress()
        vod = null
        stopPlayback()
        hideVodBar()
        subtitleText.visibility = View.GONE
        browser.visibility = View.VISIBLE
        browser.alpha = 0f
        browser.animate().alpha(1f).setDuration(200).start()
        refreshContinue()
        rowAdapter.notifyDataSetChanged()
        if (seriesView.visibility == View.VISIBLE) {
            episodeAdapter.notifyDataSetChanged()
            val lastId = history.continueWatching(true).firstOrNull { it.seriesId == openSeries?.id }?.id
            val lastSeason = episodes.firstOrNull { it.id == lastId }?.season
            if (lastSeason != null && lastSeason != selectedSeason) selectSeason(lastSeason, lastId)
            episodeList.requestFocus()
        } else {
            grid.requestFocus()
        }
    }

    /** True while the bar is on screen only because of ◀ ▶ seeking (arrows keep seeking). */
    private var seekMode = false

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: Long.MAX_VALUE
        p.seekTo((p.currentPosition + deltaMs).coerceIn(0, dur))
        val wasVisible = vodBar.visibility == View.VISIBLE
        showVodBar()
        if (!wasVisible || !vodBar.hasFocus()) seekMode = true
    }

    private val hideVodBarTask: Runnable = object : Runnable {
        override fun run() {
            val p = player
            when {
                vod == null || vodBar.visibility != View.VISIBLE -> {}
                p != null && !p.playWhenReady -> {}                       // paused: stays until resumed
                pickerLayer.visibility == View.VISIBLE -> main.postDelayed(this, 3000)
                seekDragging -> main.postDelayed(this, 3000)
                else -> {
                    vodBar.visibility = View.GONE
                    topButtons.visibility = View.GONE
                    seekMode = false
                    root.requestFocus()
                }
            }
        }
    }
    private val vodBarUpdater = object : Runnable {
        override fun run() {
            if (vodBar.visibility == View.VISIBLE) {
                updateVodBar()
                main.postDelayed(this, 1000)
            }
        }
    }

    private fun showVodBar(focusControls: Boolean = false) {
        val w = vod ?: return
        if (focusControls) {
            seekMode = false
            btnPlay.post { btnPlay.requestFocus() }
        }
        vodTitle.text = w.title
        vodSub.text = w.subtitle
        vodSub.visibility = if (w.subtitle.isEmpty()) View.GONE else View.VISIBLE
        vodBar.visibility = View.VISIBLE
        updateVodBar()
        main.removeCallbacks(vodBarUpdater)
        main.postDelayed(vodBarUpdater, 1000)
        main.removeCallbacks(hideVodBarTask)
        main.postDelayed(hideVodBarTask, 5000)
    }

    private fun hideVodBar() {
        vodBar.visibility = View.GONE
        topButtons.visibility = View.GONE
        if (vodBar.hasFocus()) root.requestFocus()
        main.removeCallbacks(vodBarUpdater)
    }

    private fun updateVodBar() {
        val p = player ?: return
        val pos = p.currentPosition
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        if (seekDragging || pendingSeekTarget >= 0) return
        vodProgress.progress = if (dur > 0) (pos * 1000 / dur).toInt() else 0
        val state = if (!p.playWhenReady) "Pausa   ·   " else ""
        vodTime.text = state + clock(pos) + (if (dur > 0) " / " + clock(dur) else "")
        btnPlay.text = if (p.playWhenReady) "❚❚  Pausa" else "▶  Reproducir"
        if (!p.playWhenReady) {
            vodBar.visibility = View.VISIBLE
        }
    }

    // Timeline: drag with the finger, or ◀ ▶ on the remote when the bar has focus.
    private var pendingSeekTarget = -1L
    private val applyKeySeek = Runnable {
        if (pendingSeekTarget >= 0) player?.seekTo(pendingSeekTarget)
        pendingSeekTarget = -1L
    }

    private fun timelinePreview(progress: Int, fromUser: Boolean) {
        if (!fromUser) return
        val p = player ?: return
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val target = dur * progress / 1000
        vodTime.text = "Ir a  " + clock(target) + " / " + clock(dur)
        main.removeCallbacks(hideVodBarTask)
        main.postDelayed(hideVodBarTask, 5000)
        if (!seekDragging) {
            // remote control: seek once the presses stop
            pendingSeekTarget = target
            main.removeCallbacks(applyKeySeek)
            main.postDelayed(applyKeySeek, 600)
        }
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }

    // ---------------------------------------------------------------- tabs

    private fun onTab(tab: Int) {
        closePicker(restoreFocus = false)
        when (tab) {
            TAB_LIVE -> {
                if (browser.visibility == View.VISIBLE) closeBrowser()
                if (!portrait) showPanel()
            }
            TAB_MOVIES -> openBrowser(false)
            TAB_SERIES -> openBrowser(true)
        }
    }

    private fun markTabs(tabs: List<TextView>, active: Int) {
        tabs.forEachIndexed { i, t ->
            t.setTextColor(if (i == active) ACCENT else Color.WHITE)
            t.background = focusBg(10, if (i == active) (if (GLASS) 0x29FFFFFF else 0x332F6FEB) else Color.TRANSPARENT)
        }
    }

    // ---------------------------------------------------------------- movies & series browser

    private fun openBrowser(series: Boolean) {
        if (xtream() == null) {
            pendingSeries = series
            showAccount(null)
            return
        }
        hideKeyboard()
        panel.visibility = View.GONE
        hideInfo.run()
        stopPlayback()
        val switching = browserSeries != series || browser.visibility != View.VISIBLE
        if (browserSeries != series) {
            browserSeries = series
            closeSeriesView()
            closeSearch(restore = false)
            items = emptyList()
            rowAdapter.notifyDataSetChanged()
            cats = categoryCache[series].orEmpty()
        }
        markTabs(browserTabs, if (series) TAB_SERIES else TAB_MOVIES)
        if (browser.visibility != View.VISIBLE) {
            browser.alpha = 0f
            browser.visibility = View.VISIBLE
            browser.animate().alpha(1f).setDuration(220).start()
        } else if (switching) {
            grid.alpha = 0f
            grid.animate().alpha(1f).setDuration(220).start()
        }
        refreshContinue()
        if (cats.isEmpty()) {
            loadCategories()
        } else {
            buildChips()
            if (items.isEmpty()) selectCategory(selectedCat[series] ?: defaultCategory())
        }
        grid.setSelectionFromTop(0, 0)
        grid.post {
            grid.setSelectionFromTop(0, 0)
            val first = if (continueBox.visibility == View.VISIBLE) continueRow.getChildAt(0) else chips.firstOrNull()
            (first ?: grid).requestFocus()
            grid.post { grid.setSelectionFromTop(0, 0) }
        }
    }

    /** Show the catalog from its first line ("Seguir viendo" or the categories). */
    private fun scrollCatalogTop() {
        if (grid.firstVisiblePosition != 0 || (grid.getChildAt(0)?.top ?: 0) != 0) grid.setSelectionFromTop(0, 0)
    }

    private fun closeBrowser() {
        hideKeyboard()
        browser.visibility = View.GONE
        root.requestFocus()
        shutter.visibility = View.VISIBLE
        if (channels.isNotEmpty()) play(current)
    }

    private fun gridStatus(text: String?) {
        browserMessage.text = text.orEmpty()
        browserMessage.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    /** Tile and poster size: 6 columns across the width, vertical 2:3 posters. */
    private fun measureTiles() {
        val width = (if (grid.width > 0) grid.width else root.width - dp(48)).coerceAtLeast(dp(300))
        tileW = width / gridColumns
        posterH = (tileW - dp(14)) * 3 / 2
    }

    private fun defaultCategory(): Category? {
        val list = visibleCats
        if (list.isEmpty()) return null
        fun key(c: Category) = Search.normalize(c.name).trim()
        return if (browserSeries) {
            list.firstOrNull { key(it) == "hbo" } ?: list.firstOrNull { key(it).contains("hbo") } ?: list.first()
        } else {
            list.firstOrNull { key(it) == "estrenos" }
                ?: list.firstOrNull { key(it).contains("estreno") && !key(it).contains("cam") }
                ?: list.first()
        }
    }

    private fun loadCategories(then: (() -> Unit)? = null) {
        val acc = xtream() ?: return
        val series = browserSeries
        categoryCache[series]?.let {
            cats = it
            buildChips()
            then?.invoke()
            return
        }
        val token = ++loadToken
        gridStatus("Cargando categorías…")
        io.execute {
            val result = runCatching { Xtream.categories(acc, series) }
            main.post {
                if (token != loadToken || series != browserSeries) return@post
                result.onSuccess {
                    categoryCache[series] = it
                    cats = it
                    buildChips()
                    if (then != null) then() else selectCategory(selectedCat[series] ?: defaultCategory())
                }.onFailure {
                    gridStatus("No se pudo cargar: ${it.message}")
                }
            }
        }
    }

    /** Horizontal row of category chips (hidden ones left out). */
    private fun buildChips() {
        visibleCats = cats.filter { it.id !in hiddenCats(browserSeries) }
        chipsRow.removeAllViews()
        chips = visibleCats.map { cat ->
            text(16f).apply {
                this.text = cat.name
                maxWidth = dp(260)
                setPadding(dp(16), dp(7), dp(16), dp(7))
                isFocusable = true
                isClickable = true
                setOnClickListener { selectCategory(cat) }
                setOnFocusChangeListener { _, has -> if (has) scrollCatalogTop() }
            }
        }
        chips.forEach { chipsRow.addView(it, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) }) }
        markChips()
    }

    private fun markChips() {
        val sel = if (gridMode == MODE_CATEGORY) selectedCat[browserSeries]?.id else null
        chips.forEachIndexed { i, chip ->
            val active = visibleCats.getOrNull(i)?.id == sel
            chip.background = focusBg(18, if (active) CHIP else 0x22FFFFFF)
            chip.setTextColor(if (active) Color.WHITE else MUTED)
        }
    }

    private fun selectCategory(cat: Category?) {
        closeSearch(restore = false)
        if (cat == null) {
            gridStatus(if (cats.isEmpty()) "El proveedor no tiene categorías aquí." else "Todas las categorías están ocultas (usa el filtro).")
            setItems(emptyList(), "")
            return
        }
        val series = browserSeries
        selectedCat[series] = cat
        gridMode = MODE_CATEGORY
        markChips()
        visibleCats.indexOfFirst { it.id == cat.id }.takeIf { it >= 0 }?.let { idx ->
            chips.getOrNull(idx)?.let { chip -> chipsScroll.post { chipsScroll.smoothScrollTo((chip.left - dp(40)).coerceAtLeast(0), 0) } }
        }
        val key = "$series:${cat.id}"
        itemCache[key]?.let {
            setItems(it, cat.name)
            gridStatus(if (it.isEmpty()) "Categoría vacía." else null)
            return
        }
        val acc = xtream() ?: return
        val token = ++loadToken
        setItems(emptyList(), cat.name)
        gridStatus("Cargando ${cat.name}…")
        io.execute {
            val result = runCatching { Xtream.items(acc, series, cat.id) }
            main.post {
                result.onSuccess { itemCache[key] = it }
                if (token != loadToken || series != browserSeries) return@post
                result.onSuccess {
                    setItems(it, cat.name)
                    gridStatus(if (it.isEmpty()) "Categoría vacía." else null)
                }.onFailure { gridStatus("No se pudo cargar: ${it.message}") }
            }
        }
    }

    private fun setItems(list: List<Any>, label: String) {
        items = list
        gridLabel.text = label
        gridLabel.visibility = if (label.isEmpty()) View.GONE else View.VISIBLE
        rowAdapter.notifyDataSetChanged()
        grid.alpha = 0.35f
        grid.animate().alpha(1f).setDuration(200).start()
    }

    /** Top section: "Seguir viendo" or "Ver más tarde", up to 5 posters plus "Ver todo". */
    private fun refreshContinue() {
        measureTiles()
        val cont = history.continueWatching(browserSeries)
        val saved = later.list(browserSeries)
        continueRow.removeAllViews()
        if (cont.isEmpty() && saved.isEmpty()) {
            continueBox.visibility = View.GONE
            return
        }
        if (topMode == 0 && cont.isEmpty()) topMode = 1
        if (topMode == 1 && saved.isEmpty()) topMode = 0
        continueBox.visibility = View.VISIBLE
        topToggleContinue.visibility = if (cont.isEmpty()) View.GONE else View.VISIBLE
        topToggleLater.visibility = if (saved.isEmpty()) View.GONE else View.VISIBLE
        listOf(topToggleContinue to 0, topToggleLater to 1).forEach { (t, mode) ->
            val active = mode == topMode
            t.background = focusBg(16, if (active) CHIP else 0x22FFFFFF)
            t.setTextColor(if (active) Color.WHITE else MUTED)
        }
        val entries: List<Any> = if (topMode == 0) cont else saved
        entries.take(5).forEach { item ->
            val tile = makeTile()
            bindTile(tile, item)
            tile.setOnClickListener { onItem(item) }
            tile.setOnLongClickListener { itemMenu(item); true }
            tile.setOnFocusChangeListener { _, has -> if (has) scrollCatalogTop() }
            continueRow.addView(tile, LinearLayout.LayoutParams(tileW, -2))
        }
        val all = makeTile()
        bindMore(all, entries.size)
        all.setOnClickListener { showAllTop() }
        all.setOnFocusChangeListener { _, has -> if (has) scrollCatalogTop() }
        continueRow.addView(all, LinearLayout.LayoutParams(tileW, -2))
    }

    private fun switchTop(mode: Int) {
        if (topMode == mode) return
        topMode = mode
        refreshContinue()
        continueRow.alpha = 0.3f
        continueRow.animate().alpha(1f).setDuration(200).start()
    }

    private fun showAllTop() {
        closeSearch(restore = false)
        if (topMode == 0) {
            gridMode = MODE_CONTINUE
            setItems(history.continueWatching(browserSeries), "Seguir viendo · todo")
        } else {
            gridMode = MODE_LATER
            setItems(later.list(browserSeries), "Ver más tarde · todo")
        }
        markChips()
        gridStatus(null)
        grid.post { grid.setSelectionFromTop(grid.headerViewsCount, 0) }
    }

    private fun episodeWatch(series: VodItem, e: Episode) = Watch(
        episode = true,
        id = e.id,
        ext = e.ext,
        title = series.title,
        subtitle = "T${e.season} · E${e.number}  ${e.title}",
        poster = series.poster,
        seriesId = series.id,
    )

    private fun onItem(item: Any?) {
        hideKeyboard()
        when (item) {
            is Watch -> {
                if (item.episode && item.seriesId.isNotEmpty() && openSeries?.id != item.seriesId) {
                    loadEpisodes(VodItem(true, item.seriesId, item.title, item.poster, item.ext, "", ""), playAfter = item)
                } else {
                    playVod(item)
                }
            }
            is VodItem -> if (item.series) openSeriesView(item)
            else playVod(Watch(false, item.id, item.ext, item.title, item.year, item.poster))
        }
    }

    private fun openSeriesView(series: VodItem) {
        openSeries = series
        episodes = emptyList()
        seasonEpisodes = emptyList()
        seasonRow.removeAllViews()
        episodeAdapter.notifyDataSetChanged()
        logos.load(series.poster, seriesPoster)
        seriesTitle.text = series.title
        seriesInfo.text = listOf(series.year, series.rating.ifEmpty { "" }.let { if (it.isEmpty()) "" else "★ $it" })
            .filter { it.isNotEmpty() }.joinToString("   ·   ")
        seriesView.alpha = 0f
        seriesView.translationX = dp(40).toFloat()
        seriesView.visibility = View.VISIBLE
        seriesView.animate().alpha(1f).translationX(0f).setDuration(220).start()
        loadEpisodes(series, null)
    }

    private fun closeSeriesView() {
        if (!::seriesView.isInitialized) return
        seriesView.visibility = View.GONE
        openSeries = null
        episodes = emptyList()
    }

    /** Season chips "T1  T2  T3…" above the episode list. */
    private fun buildSeasons(focusEpisodeId: String?) {
        seasonRow.removeAllViews()
        val seasons = episodes.map { it.season }.distinct()
        seasons.forEach { n ->
            seasonRow.addView(text(17f, bold = true).apply {
                this.text = if (n > 0) "T$n" else "Extras"
                tag = n
                gravity = Gravity.CENTER
                minWidth = dp(64)
                setPadding(dp(14), dp(7), dp(14), dp(7))
                isFocusable = true
                isClickable = true
                setOnClickListener { selectSeason(n, null) }
            }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        }
        seasonScroll.visibility = if (seasons.size > 1) View.VISIBLE else View.GONE
        val start = episodes.firstOrNull { it.id == focusEpisodeId }?.season
            ?: episodes.firstOrNull { e -> history.get("e:" + e.id)?.let { !it.finished } == true }?.season
            ?: seasons.firstOrNull() ?: -1
        selectSeason(start, focusEpisodeId)
    }

    private fun selectSeason(n: Int, focusEpisodeId: String?) {
        selectedSeason = n
        seasonEpisodes = episodes.filter { it.season == n }
        for (i in 0 until seasonRow.childCount) {
            val chip = seasonRow.getChildAt(i) as TextView
            val active = chip.tag == n
            chip.background = focusBg(16, if (active) CHIP else 0x22FFFFFF)
            chip.setTextColor(if (active) Color.WHITE else MUTED)
            if (active) seasonScroll.post { seasonScroll.smoothScrollTo((chip.left - dp(40)).coerceAtLeast(0), 0) }
        }
        episodeAdapter.notifyDataSetChanged()
        episodeList.alpha = 0.3f
        episodeList.animate().alpha(1f).setDuration(180).start()
        val idx = seasonEpisodes.indexOfFirst { it.id == focusEpisodeId }.takeIf { it >= 0 }
            ?: seasonEpisodes.indexOfFirst { e -> history.get("e:" + e.id)?.let { !it.finished } == true }
        episodeList.setSelection(idx.coerceAtLeast(0))
    }

    private fun loadEpisodes(series: VodItem, playAfter: Watch?) {
        val acc = xtream() ?: return
        val token = ++loadToken
        if (playAfter == null) {
            seriesInfo.text = "Cargando episodios…"
        }
        io.execute {
            val result = runCatching { Xtream.episodes(acc, series.id) }
            main.post {
                if (token != loadToken) return@post
                if (playAfter != null) {
                    // From "Seguir viendo": keep the series so "next episode" works.
                    openSeries = series
                    episodes = result.getOrDefault(emptyList())
                    logos.load(series.poster, seriesPoster)
                    seriesTitle.text = series.title
                    seriesInfo.text = "${episodes.size} episodios"
                    seriesView.visibility = View.VISIBLE
                    buildSeasons(playAfter.id)
                    playVod(playAfter)
                    return@post
                }
                result.onSuccess {
                    episodes = it
                    val seasons = it.map { e -> e.season }.distinct().size
                    seriesInfo.text = if (it.isEmpty()) "Esta serie no tiene episodios."
                    else "$seasons temporada${if (seasons == 1) "" else "s"}  ·  ${it.size} episodios"
                    buildSeasons(null)
                    episodeList.requestFocus()
                }.onFailure { seriesInfo.text = "No se pudo cargar: ${it.message}" }
            }
        }
    }

    private fun browserBack() {
        when {
            seriesView.visibility == View.VISIBLE -> {
                loadToken++
                closeSeriesView()
                refreshContinue()
                rowAdapter.notifyDataSetChanged()
                grid.requestFocus()
            }
            gridMode == MODE_SEARCH || browserSearch.visibility == View.VISIBLE -> closeSearch(restore = true)
            gridMode == MODE_CONTINUE || gridMode == MODE_LATER -> selectCategory(selectedCat[browserSeries] ?: defaultCategory())
            else -> {
                loadToken++
                closeBrowser()
            }
        }
    }

    // ---------------------------------------------------------------- search

    private fun toggleBrowserSearch() {
        if (browserSearch.visibility == View.VISIBLE) {
            closeSearch(restore = true)
        } else {
            browserSearch.visibility = View.VISIBLE
            browserSearch.setText("")
            browserSearch.requestFocus()
            showKeyboard(browserSearch)
        }
    }

    private fun closeSearch(restore: Boolean) {
        main.removeCallbacks(searchDebounce)
        if (!::browserSearch.isInitialized) return
        val wasSearching = gridMode == MODE_SEARCH
        browserSearch.visibility = View.GONE
        hideKeyboard()
        if (wasSearching) {
            gridMode = MODE_CATEGORY
            if (restore) selectCategory(selectedCat[browserSeries] ?: defaultCategory())
        }
    }

    private val searchDebounce = Runnable { runSearch(browserSearch.text.toString()) }

    private fun runSearch(raw: String) {
        val query = raw.trim()
        if (query.length < 2) return
        val acc = xtream() ?: return
        val series = browserSeries
        gridMode = MODE_SEARCH
        markChips()
        val token = ++loadToken
        val show = { all: List<VodItem> ->
            val q = Search.normalize(query)
            val hidden = hiddenCats(series)
            val found = all.filter { it.categoryId !in hidden && it.searchKey.contains(q) }
            setItems(found.take(600), "Resultados para “$query” (${found.size})")
            gridStatus(if (found.isEmpty()) "Sin resultados para “$query”." else null)
        }
        val cached = catalogCache[series]
        if (cached != null) {
            show(cached)
            return
        }
        setItems(emptyList(), "Buscando “$query”…")
        gridStatus("Buscando en todo el catálogo…\nLa primera búsqueda tarda un poco.")
        io.execute {
            val result = runCatching { Xtream.items(acc, series, null) }
            main.post {
                result.onSuccess { catalogCache[series] = it }
                if (token != loadToken || series != browserSeries) return@post
                result.onSuccess { show(it) }.onFailure { gridStatus("No se pudo buscar: ${it.message}") }
            }
        }
    }

    private fun togglePanelSearch() {
        if (panelSearch.visibility == View.VISIBLE) {
            closePanelSearch()
        } else {
            panelSearch.visibility = View.VISIBLE
            panelSearch.setText("")
            panelSearch.requestFocus()
            showKeyboard(panelSearch)
        }
    }

    private fun closePanelSearch() {
        panelSearch.visibility = View.GONE
        hideKeyboard()
        if (channelQuery.isNotEmpty()) {
            channelQuery = ""
            rebuildShown()
        }
        list.requestFocus()
    }

    private fun showKeyboard(view: View) {
        view.post {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(root.windowToken, 0)
    }

    // ---------------------------------------------------------------- hide categories / channels

    private fun openFilter(kind: Int) {
        when (kind) {
            TAB_LIVE -> {
                filterKey = "hide_ch"
                filterHidden = hiddenChannels
                filterEntries = channels.map { it.name to it.name }
                filterTitle.text = "Mostrar u ocultar canales"
            }
            else -> {
                val series = kind == TAB_SERIES
                if (categoryCache[series] == null) {
                    loadCategories { openFilter(kind) }
                    return
                }
                filterKey = if (series) "hide_series" else "hide_vod"
                filterHidden = hiddenCats(series)
                filterEntries = categoryCache[series].orEmpty().map { it.id to it.name }
                filterTitle.text = if (series) "Mostrar u ocultar categorías de series" else "Mostrar u ocultar categorías de películas"
            }
        }
        hideKeyboard()
        filterAdapter.notifyDataSetChanged()
        filterBox.alpha = 0f
        filterBox.visibility = View.VISIBLE
        filterBox.animate().alpha(1f).setDuration(180).start()
        filterList.requestFocus()
        filterList.setSelection(0)
    }

    private fun closeFilter() {
        filterBox.visibility = View.GONE
        prefs.edit().putStringSet(filterKey, HashSet(filterHidden)).apply()
        if (filterKey == "hide_ch") {
            rebuildShown()
            list.requestFocus()
        } else {
            buildChips()
            val sel = selectedCat[browserSeries]
            if (sel == null || sel.id in hiddenCats(browserSeries)) selectCategory(defaultCategory())
            chips.firstOrNull()?.requestFocus()
        }
    }

    // ---------------------------------------------------------------- keys

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && vod != null && vodBar.visibility == View.VISIBLE) {
            main.removeCallbacks(hideVodBarTask)
            main.postDelayed(hideVodBarTask, 5000)
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (locked) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) showLockOnly()
                return true
            }
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) onBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun overlayOpen() = setup.visibility == View.VISIBLE || account.visibility == View.VISIBLE ||
        browser.visibility == View.VISIBLE || filterBox.visibility == View.VISIBLE || pickerLayer.visibility == View.VISIBLE ||
        epgBox.visibility == View.VISIBLE

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (overlayOpen() || panel.visibility == View.VISIBLE) return super.onKeyDown(keyCode, event)
        if (vod != null) return vodKey(keyCode, event)
        when (keyCode) {
            // ▼ = next channel in the list, ▲ = previous (like the side list and the guide)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_DOWN -> zap(1)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_UP -> zap(-1)
            // OK: short press = card, then panel (on release); long press = other options of the channel
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.repeatCount == 0) { okLong = false; okArmed = true }
                else if (okArmed && !okLong && event.repeatCount >= 2) { okLong = true; chooseOption() }
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_TV_INPUT -> showPanel()
            KeyEvent.KEYCODE_PROG_RED, KeyEvent.KEYCODE_O -> chooseOption()
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_GUIDE -> openEpg()
            KeyEvent.KEYCODE_INFO -> showInfo(false)
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> typeDigit(keyCode - KeyEvent.KEYCODE_0)
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private var okLong = false
    private var okArmed = false

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (okArmed && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            okArmed = false
            if (!okLong && !event.isCanceled && vod == null && !overlayOpen() && panel.visibility != View.VISIBLE) {
                // 1st OK: channel card (with its options); OK again while it is on screen: side panel
                if (info.visibility == View.VISIBLE) showPanel() else showInfo(false)
            }
            okLong = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun vodKey(keyCode: Int, event: KeyEvent): Boolean {
        val p = player ?: return super.onKeyDown(keyCode, event)
        val step = when {
            event.repeatCount > 15 -> 60_000L
            event.repeatCount > 3 -> 30_000L
            else -> 15_000L
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (vodBar.visibility == View.VISIBLE) showVodBar(true) else { togglePause(); showVodBar(true) }
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> togglePause()
            KeyEvent.KEYCODE_MEDIA_PLAY -> { p.playWhenReady = true; showVodBar() }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { p.playWhenReady = false; saveVodProgress(); showVodBar() }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val forward = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                when {
                    // moving between the buttons: never seek, not even past the last one
                    vodBar.visibility == View.VISIBLE && vodBar.hasFocus() -> {}
                    // bar on screen but not from seeking: arrows go to the buttons
                    vodBar.visibility == View.VISIBLE && !seekMode -> showVodBar(true)
                    else -> seekBy(if (forward) step else -step)
                }
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> seekBy(-step)
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seekBy(step)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MENU -> showVodBar(true)
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    // ---------------------------------------------------------------- audio, subtitles, aspect

    private fun trackLabel(f: Format, n: Int): String {
        val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }?.let { code ->
            Locale(code).getDisplayLanguage(Locale("es")).replaceFirstChar { it.uppercase() }
        }
        val parts = listOfNotNull(
            f.label?.takeIf { it.isNotBlank() },
            lang,
            if (f.channelCount > 0) when (f.channelCount) { 1 -> "mono"; 2 -> "estéreo"; 6 -> "5.1"; 8 -> "7.1"; else -> "${f.channelCount} canales" } else null,
        ).distinct()
        return parts.joinToString(" · ").ifEmpty { "Pista $n" }
    }

    private fun chooseTrack(type: Int) {
        val p = player ?: return
        val groups = p.currentTracks.groups.filter { it.type == type && it.isSupported }
        val choices = ArrayList<Pair<Tracks.Group, Int>>()
        groups.forEach { g -> for (i in 0 until g.length) if (g.isTrackSupported(i)) choices.add(g to i) }
        val textType = type == C.TRACK_TYPE_TEXT
        if (choices.isEmpty()) {
            Toast.makeText(this, if (textType) "Este contenido no trae subtítulos." else "Solo hay una pista de audio.", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = ArrayList<String>()
        if (textType) labels.add("Desactivados")
        choices.forEachIndexed { n, (g, i) -> labels.add(trackLabel(g.getTrackFormat(i), n + 1)) }
        val disabled = p.trackSelectionParameters.disabledTrackTypes.contains(type)
        var selected = choices.indexOfFirst { (g, i) -> g.isTrackSelected(i) }
        if (textType) selected = if (disabled || selected < 0) 0 else selected + 1
        showPicker(if (textType) "Subtítulos" else "Audio", labels, selected) { pick ->
            val params = p.trackSelectionParameters.buildUpon()
            if (textType && pick == 0) {
                params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            } else {
                val (g, i) = choices[if (textType) pick - 1 else pick]
                params.setTrackTypeDisabled(type, false)
                params.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
            }
            p.trackSelectionParameters = params.build()
        }
    }

    private fun chooseAspect() {
        showPicker("Relación de aspecto", listOf("Normal", "16:9", "4:3"), aspectMode) {
            aspectMode = it
            fitVideo()
        }
    }

    // ---------------------------------------------------------------- cast (Chromecast / Google TV)

    private fun castMedia(): CastMedia? {
        val w = vod
        if (w != null) {
            val acc = xtream() ?: return null
            val pos = player?.currentPosition ?: history.get(w.key)?.position ?: 0L
            return CastMedia(if (w.episode) acc.episodeUrl(w.id, w.ext) else acc.movieUrl(w.id, w.ext),
                w.title, w.subtitle, w.poster, live = false, ext = w.ext, positionMs = pos)
        }
        val ch = channels.getOrNull(current) ?: return null
        val now = Guide.nowNext(guide[ch.tvgId], System.currentTimeMillis()).first
        return CastMedia(streamUrl(ch), ch.name, now?.title.orEmpty(), ch.logo, live = true, ext = "", positionMs = 0)
    }

    private fun castMenu() {
        val c = caster ?: return
        val name = castingTo
        if (name != null) {
            showPicker("Transmitiendo a $name", listOf("Dejar de transmitir", "Seguir transmitiendo"), -1) { pick ->
                if (pick == 0) c.stop()
            }
            return
        }
        castRoutes = emptyList()
        showPicker("Transmitir a…", listOf("Buscando Chromecast y Google TV…"), -1) { pick ->
            val route = castRoutes.getOrNull(pick) ?: return@showPicker
            val media = castMedia() ?: return@showPicker
            c.castTo(route, media)
            Toast.makeText(this, "Conectando con ${route.name}…", Toast.LENGTH_SHORT).show()
        }
        val started = c.startScan { routes ->
            castRoutes = routes
            if (pickerLayer.visibility != View.VISIBLE) return@startScan
            pickerOptions = if (routes.isEmpty()) listOf("Buscando Chromecast y Google TV…") else routes.map { it.name }
            (pickerList.layoutParams as LinearLayout.LayoutParams).height = pickerOptions.size * (dp(46) + 1)
            pickerList.requestLayout()
            pickerAdapter.notifyDataSetChanged()
        }
        if (!started) {
            closePicker()
            Toast.makeText(this, "La transmisión no está disponible en este equipo.", Toast.LENGTH_LONG).show()
        }
    }

    private fun castStarted(device: String) {
        castingTo = device
        if (vod != null) saveVodProgress()
        stopPlayback()
        showStatus("Transmitiendo a $device\nToca la pantalla y el botón de transmitir para detener.")
        showTopButtons()
    }

    private fun castEnded(lastPositionMs: Long) {
        castingTo = null
        hideStatus()
        val w = vod
        if (w != null) {
            if (lastPositionMs > 0) history.update(w, lastPositionMs, w.duration)
            playVod(w)
        } else if (channels.isNotEmpty() && browser.visibility != View.VISIBLE) {
            play(current)
        }
    }

    // ---------------------------------------------------------------- channel options (Opción 2, 3…)

    private fun altIndex(ch: Channel) = prefs.getInt("alt_" + ch.name, 0).coerceIn(0, ch.alts.size)
    private fun streamUrl(ch: Channel) = ch.options[altIndex(ch)].url

    private fun chooseOption() {
        val ch = channels.getOrNull(current) ?: return
        if (vod != null) return
        if (ch.alts.isEmpty()) {
            Toast.makeText(this, "Este canal tiene una sola opción.", Toast.LENGTH_SHORT).show()
            return
        }
        main.removeCallbacks(hideInfo)
        showPicker(ch.name, ch.options.map { it.label }, altIndex(ch)) { pick ->
            if (pick != altIndex(ch)) {
                prefs.edit().putInt("alt_" + ch.name, pick).apply()
                player?.stop()
                shutter.visibility = View.VISIBLE
                play(current)
            } else showInfo(false)
        }
    }

    // ---------------------------------------------------------------- settings (audio / subtitles)

    private val audioChoices = listOf("es" to "Español", "en" to "Inglés", "" to "Original del contenido")
    private val subChoices = listOf("off" to "Desactivados", "es" to "Español", "en" to "Inglés")
    private fun audioPref() = prefs.getString("audio_lang", "es").orEmpty()
    private fun subPref() = prefs.getString("sub_lang", "off").orEmpty()

    /** Applies the general language settings (and drops choices made for the previous video). */
    private fun applyLanguages(p: ExoPlayer) {
        val b = p.trackSelectionParameters.buildUpon().clearOverrides()
        b.setPreferredAudioLanguage(audioPref().ifEmpty { null })
        val sub = subPref()
        if (sub == "off") {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            b.setPreferredTextLanguage(null)
        } else {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            b.setPreferredTextLanguage(sub)
            b.setSelectUndeterminedTextLanguage(true)
        }
        p.trackSelectionParameters = b.build()
    }

    private fun openSettings() {
        val audio = audioChoices.firstOrNull { it.first == audioPref() }?.second ?: "Español"
        val subs = subChoices.firstOrNull { it.first == subPref() }?.second ?: "Desactivados"
        showPicker("Configuración", listOf(
            "Idioma del audio:  $audio",
            "Subtítulos:  $subs",
            "Cuenta de películas y series",
            "Cambiar dirección de la lista",
        ), -1) { pick ->
            when (pick) {
                0 -> showPicker("Idioma del audio", audioChoices.map { it.second }, audioChoices.indexOfFirst { it.first == audioPref() }) { a ->
                    prefs.edit().putString("audio_lang", audioChoices[a].first).apply()
                    player?.let(::applyLanguages)
                    openSettings()
                }
                1 -> showPicker("Subtítulos", subChoices.map { it.second }, subChoices.indexOfFirst { it.first == subPref() }) { t ->
                    prefs.edit().putString("sub_lang", subChoices[t].first).apply()
                    player?.let(::applyLanguages)
                    openSettings()
                }
                2 -> { pendingSeries = false; hidePanel(); browser.visibility = View.GONE; showAccount(null) }
                3 -> { hidePanel(); browser.visibility = View.GONE; showSetup(null) }
            }
        }
    }

    // ---------------------------------------------------------------- picker

    private fun showPicker(title: String, options: List<String>, selected: Int, action: (Int) -> Unit) {
        pickerReturnFocus = currentFocus
        pickerTitle.text = title
        pickerOptions = options
        pickerSelected = selected
        pickerAction = action
        pickerAdapter.notifyDataSetChanged()
        main.removeCallbacks(hideVodBarTask)
        // While a movie plays, open in the upper half so the control bar stays visible.
        val h = root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val rowH = dp(46)
        val maxList = if (vod != null) (h * 0.5f).toInt() - dp(90) else (h * 0.7f).toInt() - dp(90)
        (pickerList.layoutParams as LinearLayout.LayoutParams).height =
            (options.size * (rowH + 1)).coerceAtMost(maxList.coerceAtLeast(rowH * 2))
        (pickerBox.layoutParams as FrameLayout.LayoutParams).apply {
            gravity = if (vod != null) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.CENTER
            topMargin = if (vod != null) dp(24) else 0
        }
        pickerBox.requestLayout()
        pickerLayer.animate().cancel()
        pickerLayer.alpha = 0f
        pickerBox.translationY = -dp(16).toFloat()
        pickerLayer.visibility = View.VISIBLE
        pickerLayer.animate().alpha(1f).setDuration(170).start()
        pickerBox.animate().translationY(0f).setDuration(170).start()
        pickerList.requestFocus()
        pickerList.setSelection(selected.coerceAtLeast(0))
    }

    /** Fades the menu out; [restoreFocus] = false when another screen is taking over. */
    private fun closePicker(restoreFocus: Boolean = true) {
        if (pickerLayer.visibility != View.VISIBLE) return
        if (castingTo == null) caster?.stopScan()
        pickerAction = null
        pickerLayer.animate().cancel()
        pickerLayer.animate().alpha(0f).setDuration(160).withEndAction {
            pickerLayer.visibility = View.GONE
            pickerLayer.alpha = 1f
        }.start()
        pickerBox.animate().translationY(-dp(12).toFloat()).setDuration(160).start()
        if (restoreFocus) {
            val back = pickerReturnFocus
            if (back != null && back.isShown) back.requestFocus() else root.requestFocus()
            if (vod != null) showVodBar()
        }
    }

    // ---------------------------------------------------------------- long press menu

    private fun itemMenu(item: Any) {
        when (item) {
            is VodItem -> {
                val inLater = later.contains(item)
                val open = if (item.series) "Abrir serie" else "Reproducir"
                showPicker(item.title, listOf(open, if (inLater) "Quitar de Ver más tarde" else "Agregar a Ver más tarde"), -1) { pick ->
                    if (pick == 0) onItem(item)
                    else {
                        if (inLater) later.remove(item) else later.add(item)
                        Toast.makeText(this, if (inLater) "Quitado de Ver más tarde" else "Agregado a Ver más tarde", Toast.LENGTH_SHORT).show()
                        if (!inLater) topMode = 1
                        refreshContinue()
                        if (gridMode == MODE_LATER) setItems(later.list(browserSeries), "Ver más tarde · todo")
                    }
                }
            }
            is Watch -> showPicker(item.title, listOf("Continuar", "Quitar de Seguir viendo"), -1) { pick ->
                if (pick == 0) onItem(item)
                else {
                    history.remove(item)
                    refreshContinue()
                    if (gridMode == MODE_CONTINUE) setItems(history.continueWatching(browserSeries), "Seguir viendo · todo")
                }
            }
        }
    }

    private fun togglePause() {
        val p = player ?: return
        p.playWhenReady = !p.playWhenReady
        if (!p.playWhenReady) saveVodProgress()
        showVodBar()
    }

    private fun onBack() {
        when {
            pickerLayer.visibility == View.VISIBLE -> closePicker()
            epgBox.visibility == View.VISIBLE && !portrait -> closeEpg()
            portrait && guideCollapsed && vod == null && browser.visibility != View.VISIBLE && panel.visibility != View.VISIBLE -> expandGuide()
            filterBox.visibility == View.VISIBLE -> closeFilter()
            account.visibility == View.VISIBLE -> {
                account.visibility = View.GONE
                hideKeyboard()
                if (browser.visibility != View.VISIBLE && vod == null) {
                    if (player?.isPlaying != true) play(current)
                    root.requestFocus()
                }
            }
            panel.visibility == View.VISIBLE && panelSearch.visibility == View.VISIBLE -> closePanelSearch()
            panel.visibility == View.VISIBLE -> {
                hidePanel()
                armExit()
            }
            browser.visibility == View.VISIBLE -> browserBack()
            vod != null -> leaveVod()
            setup.visibility == View.VISIBLE && channels.isNotEmpty() -> {
                hideSetup()
                play(current)
            }
            System.currentTimeMillis() - lastBack < 2500 -> finish()
            channels.isNotEmpty() -> showPanel()
            else -> armExit()
        }
    }

    private fun armExit() {
        lastBack = System.currentTimeMillis()
        Toast.makeText(this, "Presiona Atrás otra vez para salir", Toast.LENGTH_SHORT).show()
    }

    private fun typeDigit(d: Int) {
        if (digits.length >= 3) digits = ""
        digits += d
        numberView.text = digits
        numberView.visibility = View.VISIBLE
        main.removeCallbacks(jumpToDigits)
        main.postDelayed(jumpToDigits, 1500)
    }

    private val jumpToDigits = Runnable {
        val n = digits.toIntOrNull()
        digits = ""
        numberView.visibility = View.GONE
        if (n != null && n in 1..channels.size) play(n - 1)
    }

    // ---------------------------------------------------------------- touch (phones / tablets)

    private var downX = 0f
    private var downY = 0f
    private var tracking = false
    private var dragging = false
    private var dragTarget = -1
    private var velocity: VelocityTracker? = null
    private val slop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private var cardPending = false

    private fun onTouch(e: MotionEvent) {
        if (locked) {
            if (e.actionMasked == MotionEvent.ACTION_UP) showLockOnly()
            return
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                tracking = true
                dragging = false
                dragTarget = -1
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                val dx = e.x - downX
                val dy = e.y - downY
                if (!dragging && tracking && canDrag()) {
                    // live: vertical drag changes channel; movies: horizontal drag seeks
                    if (vod == null && abs(dy) > slop * 2 && abs(dy) > abs(dx) * 1.3f) {
                        dragging = true
                        hideInfo.run()
                    } else if (vod != null && abs(dx) > slop * 2 && abs(dx) > abs(dy) * 1.3f) {
                        dragging = true
                    } else if (vod != null && !isTv && abs(dy) > slop * 2 && abs(dy) > abs(dx) * 1.3f) {
                        // movies/series: left half = brightness, right half = volume
                        dragging = true
                        adjust = if (downX < root.width / 2f) 1 else 2
                        adjustStart = if (adjust == 1) currentBrightness() else currentVolume()
                        showVodBar()
                        showTopButtons()
                        holdControls(true)
                    }
                }
                if (!dragging && (abs(dx) > slop * 2 || abs(dy) > slop * 2)) tracking = false
                if (dragging && vod == null) dragTo(dy)
                if (dragging && adjust != 0) {
                    val v = (adjustStart - dy / (root.height * 0.75f)).coerceIn(0f, 1f)
                    if (adjust == 1) { setBrightness(v); brightSlider.value = v } else { setVolume(v); volSlider.value = v }
                }
            }
            MotionEvent.ACTION_UP -> {
                velocity?.addMovement(e)
                velocity?.computeCurrentVelocity(1000)
                val vy = velocity?.yVelocity ?: 0f
                val dx = e.x - downX
                val dy = e.y - downY
                val horizontalSwipe = abs(dx) > dp(80) && abs(dx) > abs(dy) * 1.5f
                when {
                    dragging && adjust != 0 -> holdControls(false)
                    // one swipe = 15 s, like the buttons
                    dragging && vod != null -> seekBy(if (dx > 0) 15_000L else -15_000L)
                    dragging -> endDrag(dy, vy)
                    // live: finger to the right = channel list (from the left), to the left = guide (from the right)
                    !dragging && vod == null && !portrait && horizontalSwipe && !overlayOpen() && panel.visibility != View.VISIBLE ->
                        if (dx > 0) showPanel() else openEpg()
                    panel.visibility == View.VISIBLE && horizontalSwipe && dx < 0 -> hidePanel()
                    tracking && abs(dx) < slop * 2 && abs(dy) < slop * 2 -> onTap()
                }
                dragging = false
                tracking = false
                adjust = 0
                velocity?.recycle()
                velocity = null
            }
            MotionEvent.ACTION_CANCEL -> {
                if (adjust != 0) holdControls(false)
                adjust = 0
                if (dragging && vod == null) endDrag(0f, 0f)
                dragging = false
                tracking = false
            }
        }
    }

    /** Vertical swipe during movies: 0 none, 1 brightness, 2 volume. */
    private var adjust = 0
    private var adjustStart = 0f

    private fun currentVolume(): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    private fun canDrag() = !overlayOpen() && panel.visibility != View.VISIBLE &&
        (vod != null || channels.size > 1)

    /** Moves the video with the finger (up = next channel) while the next channel's card slides in. */
    private fun dragTo(dy: Float) {
        val h = videoBox()[1].toFloat()
        val target = neighbor(if (dy < 0) 1 else -1)
        if (target != dragTarget) {
            dragTarget = target
            fillCard(target)
        }
        surface.translationY = dy
        card.alpha = 1f
        card.translationX = 0f
        card.visibility = View.VISIBLE
        card.translationY = if (dy < 0) h + dy else -h + dy
    }

    private fun endDrag(dy: Float, vy: Float) {
        val h = videoBox()[1].toFloat()
        val fling = abs(vy) > dp(900) && (vy < 0) == (dy < 0)
        val commit = dragTarget >= 0 && dy != 0f && (abs(dy) > h * 0.25f || fling)
        if (commit) {
            val dir = if (dy < 0) -1f else 1f
            val target = dragTarget
            surface.animate().translationY(dir * h).setDuration(180).start()
            card.animate().translationY(0f).setDuration(180).withEndAction {
                surface.translationY = 0f
                cardPending = true
                play(target)
                main.removeCallbacks(cardTimeout)
                main.postDelayed(cardTimeout, 5000)
            }.start()
        } else {
            surface.animate().translationY(0f).setDuration(160).start()
            val away = if (card.translationY > 0) h else -h
            card.animate().translationY(away).setDuration(160).withEndAction { card.visibility = View.GONE }.start()
        }
    }

    private val cardTimeout = Runnable { fadeCard() }

    private fun fadeCard() {
        if (!cardPending || card.visibility != View.VISIBLE) return
        cardPending = false
        main.removeCallbacks(cardTimeout)
        card.animate().alpha(0f).setDuration(250).withEndAction {
            card.visibility = View.GONE
            card.alpha = 1f
        }.start()
    }

    private fun fillCard(index: Int) {
        val ch = channels.getOrNull(index) ?: return
        logos.load(ch.logo, cardLogo)
        cardName.text = "${ch.number}   ${ch.name}"
        val now = Guide.nowNext(guide[ch.tvgId], System.currentTimeMillis()).first
        cardNow.text = now?.let { "${hm.format(Date(it.start))} – ${hm.format(Date(it.stop))}   ${it.title}" } ?: ""
    }

    private fun onTap() {
        when {
            epgBox.visibility == View.VISIBLE -> closeEpg()
            overlayOpen() -> {}
            panel.visibility == View.VISIBLE -> hidePanel()
            vod != null -> if (vodBar.visibility == View.VISIBLE) hideVodBar() else { showVodBar(); showTopButtons() }
            info.visibility == View.VISIBLE && menuButton.visibility == View.VISIBLE -> hideInfo.run()
            else -> showInfo(true)
        }
    }

    // ---------------------------------------------------------------- program guide (EPG)

    private fun openEpg() {
        if (channels.isEmpty() || vod != null || portrait) return
        hideInfo.run()
        panel.visibility = View.GONE
        val rows = channels.filter { it.name !in hiddenChannels }.ifEmpty { channels }
        val currentRow = rows.indexOfFirst { it.number == channels.getOrNull(current)?.number }.coerceAtLeast(0)
        epgView.setData(rows, currentRow)
        if (guide.isEmpty()) refreshGuide(false)
        epgBox.animate().cancel()
        epgClosing = false
        epgBox.visibility = View.VISIBLE
        epgBox.translationY = 0f
        epgBox.translationX = root.width * 0.3f
        epgBox.alpha = 0f
        epgBox.animate().translationX(0f).alpha(1f).setDuration(220).start()
        epgView.requestFocus()
    }

    private var epgClosing = false

    private fun closeEpg() {
        if (epgBox.visibility != View.VISIBLE || epgClosing || portrait) return
        epgClosing = true
        root.requestFocus()
        epgBox.animate().cancel()
        epgBox.animate().translationX(epgBox.width.toFloat() * 0.6f).alpha(0f).setDuration(220).withEndAction {
            epgBox.visibility = View.GONE
            epgBox.translationX = 0f
            epgBox.alpha = 1f
            epgClosing = false
        }.start()
    }

    /** Finger dragging the guide to the right: it follows the finger; far enough closes it. */
    private var epgDragX = 0f
    private var epgDragging = false
    private var epgDragY = 0f
    private fun epgDrag(e: MotionEvent): Boolean {
        if (portrait) {
            // upright: drag the guide down to leave only the player
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { epgDragY = e.rawY; epgDragging = false }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - epgDragY
                    if (!epgDragging && dy > slop * 2) epgDragging = true
                    if (epgDragging) epgBox.translationY = dy.coerceAtLeast(0f)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (epgDragging) {
                        if (epgBox.translationY > dp(70)) collapseGuide()
                        else epgBox.animate().translationY(0f).setDuration(160).start()
                    }
                    val was = epgDragging
                    epgDragging = false
                    return was
                }
            }
            return epgDragging
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { epgDragX = e.rawX; epgDragging = false }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - epgDragX
                if (!epgDragging && dx > slop * 2) epgDragging = true
                if (epgDragging) epgBox.translationX = dx.coerceAtLeast(0f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (epgDragging) {
                    if (epgBox.translationX > dp(80)) closeEpg()
                    else epgBox.animate().translationX(0f).setDuration(160).start()
                }
                val was = epgDragging
                epgDragging = false
                return was
            }
        }
        return epgDragging
    }

    private val epgHost = object : EpgView.Host {
        override fun programs(channel: Channel) = guide[channel.tvgId]
        override fun logo(url: String) = logos.bitmap(url) { if (::epgView.isInitialized) epgView.invalidate() }

        override fun onSelected(channel: Channel, program: Program?) {
            epgChannel.text = "${channel.number}   ${channel.name}"
            if (program == null) {
                epgTitle.text = "Sin información de guía"
                epgTime.text = ""
                epgDesc.text = ""
            } else {
                epgTitle.text = program.title
                val now = System.currentTimeMillis()
                val tag = when {
                    program.start <= now && program.stop > now -> "  ·  Ahora"
                    program.stop <= now -> "  ·  Ya emitido"
                    else -> ""
                }
                epgTime.text = hm.format(Date(program.start)) + " – " + hm.format(Date(program.stop)) + tag
                epgDesc.text = program.desc
            }
        }

        override fun onPullDown(e: MotionEvent) = epgDrag(e)

        override fun onPlay(channel: Channel) {
            val index = channels.indexOfFirst { it.number == channel.number }
            closeEpg()
            if (index >= 0 && index != current) play(index) else showInfo(false)
        }
    }

    // ---------------------------------------------------------------- overlays

    private val hideInfo = Runnable {
        if (!(portrait && guideCollapsed && vod == null)) info.visibility = View.GONE
        menuButton.visibility = View.GONE
        if (vod == null || locked) topButtons.visibility = View.GONE
    }

    /** Cast + picture-in-picture buttons (top right), only on phones/tablets. */
    private fun showTopButtons() {
        if (isTv || portrait) return
        castButton.visibility = if (caster != null && !locked) View.VISIBLE else View.GONE
        pipButton.visibility = if (pipAllowed && castingTo == null && !locked) View.VISIBLE else View.GONE
        infoButton.visibility = if (castingTo == null && !locked) View.VISIBLE else View.GONE
        lockButton.visibility = View.VISIBLE
        topButtons.visibility = View.VISIBLE
        placeSideButtons()
        castButton.alpha = if (castingTo != null) 1f else 0.9f
        castButton.setColorFilter(if (castingTo != null) ACCENT else Color.WHITE)
    }

    private fun showInfo(touch: Boolean) {
        if (channels.isEmpty()) return
        if (portrait) {   // upright: the guide (or the card at the bottom) shows the programme
            updatePortraitBar()
            if (guideCollapsed) { updateInfo(); info.visibility = View.VISIBLE }
            return
        }
        updateInfo()
        info.visibility = View.VISIBLE
        if (touch) {
            menuButton.visibility = View.VISIBLE
            showTopButtons()
        }
        main.removeCallbacks(hideInfo)
        main.postDelayed(hideInfo, if (touch) 6000 else 5000)
    }

    private fun updateInfo() {
        val ch = channels.getOrNull(current) ?: return
        logos.load(ch.logo, infoLogo)
        infoTitle.text = "${ch.number}   ${ch.name}"
        val now = System.currentTimeMillis()
        val (cur, next) = Guide.nowNext(guide[ch.tvgId], now)
        if (cur != null) {
            infoNow.text = "Ahora  ${hm.format(Date(cur.start))} – ${hm.format(Date(cur.stop))}   ${cur.title}"
            infoProgress.visibility = View.VISIBLE
            infoProgress.progress = (((now - cur.start) * 1000) / (cur.stop - cur.start).coerceAtLeast(1)).toInt()
        } else {
            infoNow.text = "Sin información de guía"
            infoProgress.visibility = View.INVISIBLE
        }
        infoNext.text = next?.let { "Después  ${hm.format(Date(it.start))}   ${it.title}" } ?: ""
        infoNext.visibility = if (next == null) View.GONE else View.VISIBLE
        if (ch.alts.isEmpty()) infoOptions.visibility = View.GONE
        else {
            val label = ch.options[altOverride?.takeIf { it < ch.options.size } ?: altIndex(ch)].label
            infoOptions.text = if (isTv) "$label  ·  mantén OK" else label
            infoOptions.visibility = View.VISIBLE
        }
    }

    private fun showPanel() {
        closePicker(restoreFocus = false)
        if (channels.isEmpty()) {
            showSetup(null)
            return
        }
        hideInfo.run()
        markTabs(panelTabs, TAB_LIVE)
        adapter.notifyDataSetChanged()
        if (panel.visibility != View.VISIBLE) {
            panel.alpha = 0f
            panel.translationX = -dp(60).toFloat()
            panel.visibility = View.VISIBLE
            panel.animate().alpha(1f).translationX(0f).setDuration(200).start()
        }
        list.requestFocus()
        val pos = shown.indexOf(current).coerceAtLeast(0)
        list.setSelectionFromTop(pos, dp(140))
    }

    private fun hidePanel() {
        if (panelSearch.visibility == View.VISIBLE) {
            panelSearch.visibility = View.GONE
            if (channelQuery.isNotEmpty()) {
                channelQuery = ""
                rebuildShown()
            }
        }
        hideKeyboard()
        panel.visibility = View.GONE
        root.requestFocus()
    }

    private var resumeNote: String? = null

    /** Center message; while a movie plays it sits higher so it never covers the control bar. */
    private fun showStatus(text: String) {
        status.text = text
        status.translationY = if (vod != null) -root.height * 0.22f else 0f
        status.visibility = View.VISIBLE
    }

    private fun hideStatus() {
        status.visibility = View.GONE
    }

    private fun showSetup(message: String?) {
        hidePanel()
        hideStatus()
        releasePlayer()
        setupInput.setText(prefs.getString("list", "").orEmpty().ifEmpty { "https://tinyurl.com/" })
        setupInput.setSelection(setupInput.text.length)
        setupMessage.text = message ?: "Escribe la dirección de tu lista de canales (la misma del Roku)."
        setup.visibility = View.VISIBLE
        setupInput.requestFocus()
    }

    private fun hideSetup() {
        setup.visibility = View.GONE
        hideKeyboard()
        root.requestFocus()
    }

    private fun saveSetup() {
        val url = setupInput.text.toString().trim()
        if (!url.startsWith("http://") && !url.startsWith("https://") || url.length < 12) {
            setupMessage.text = "La dirección debe empezar con https://"
            return
        }
        prefs.edit().putString("list", url).apply()
        File(filesDir, "list.m3u").delete()
        channels = emptyList()
        current = 0
        start()
    }

    private fun showAccount(message: String?) {
        panel.visibility = View.GONE
        accServer.setText(prefs.getString("xt_server", "").orEmpty().ifEmpty { DEFAULT_SERVER })
        accUser.setText(prefs.getString("xt_user", "").orEmpty())
        accPass.setText(prefs.getString("xt_pass", "").orEmpty())
        accMessage.text = message ?: if (playlistAccount != null)
            "Tu lista ya trae la cuenta; lo que escribas aquí solo se usa si la quitas de la lista."
        else "Para ver películas y series escribe tu cuenta Xtream (se guarda solo en este equipo)."
        account.visibility = View.VISIBLE
        (if (accUser.text.isEmpty()) accUser else accServer).requestFocus()
    }

    private fun saveAccount() {
        val acc = XtreamAccount(accServer.text.toString().trim(), accUser.text.toString().trim(), accPass.text.toString().trim())
        if (!acc.valid) {
            accMessage.text = "Completa servidor (https://…), usuario y clave."
            return
        }
        prefs.edit().putString("xt_server", acc.base).putString("xt_user", acc.user).putString("xt_pass", acc.pass).apply()
        categoryCache.clear()
        itemCache.clear()
        catalogCache.clear()
        cats = emptyList()
        account.visibility = View.GONE
        hideKeyboard()
        openBrowser(pendingSeries)
    }

    // ---------------------------------------------------------------- UI helpers

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun text(size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun box(color: Int, radius: Int = 12) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    /**
     * Floating surface. TV app: the plain [tvColor] box. Phone app: "liquid glass" — a
     * translucent smoky fill, a soft light sheen at the top and a thin bright rim.
     * [level] 0 = light (bars, buttons), 1 = panels, 2 = dialogs (more opaque, easier to read).
     */
    private fun surfaceBg(tvColor: Int, tvRadius: Int, level: Int = 0, radius: Int = tvRadius + 8): Drawable {
        if (!GLASS) return box(tvColor, tvRadius)
        val r = dp(radius).toFloat()
        val (top, bottom) = when (level) {
            0 -> 0x80262A33.toInt() to 0x99121418.toInt()
            1 -> 0xB3262A33.toInt() to 0xC70F1114.toInt()
            else -> 0xD92A2E37.toInt() to 0xE6121418.toInt()
        }
        val fill = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)).apply { cornerRadius = r }
        val sheen = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x2EFFFFFF, 0x0AFFFFFF, 0x00FFFFFF)).apply { cornerRadius = r }
        val rim = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = r
            setStroke(dp(1).coerceAtLeast(1), 0x40FFFFFF)
        }
        return LayerDrawable(arrayOf(fill, sheen, rim))
    }

    /** Applies [surfaceBg] plus a soft shadow on phones. */
    private fun View.glassy(tvColor: Int, tvRadius: Int, level: Int = 0, radius: Int = tvRadius + 8) {
        background = surfaceBg(tvColor, tvRadius, level, radius)
        if (GLASS && Build.VERSION.SDK_INT >= 21) elevation = dp(if (level == 0) 6 else 10).toFloat()
    }

    // ---------------------------------------------------------------- blur behind the glass (phones, Android 12+)

    private var blurred = false
    private var blurRadius = 0f
    private var blurAnim: ValueAnimator? = null

    /** Blurs the video while a panel, the guide or a menu is open (like ColorOS / iOS). */
    private fun updateBackdrop() {
        if (!GLASS || Build.VERSION.SDK_INT < 31 || !::surface.isInitialized) return
        val want = !(Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode) && (panel.visibility == View.VISIBLE || (epgBox.visibility == View.VISIBLE && !portrait) ||
            pickerLayer.visibility == View.VISIBLE || filterBox.visibility == View.VISIBLE ||
            setup.visibility == View.VISIBLE || account.visibility == View.VISIBLE)
        if (want == blurred) return
        blurred = want
        blurAnim?.cancel()
        blurAnim = ValueAnimator.ofFloat(blurRadius, if (want) dp(26).toFloat() else 0f).apply {
            duration = 240
            addUpdateListener {
                blurRadius = it.animatedValue as Float
                surface.setRenderEffect(if (blurRadius < 0.5f) null
                    else RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP))
            }
            start()
        }
    }

    /** Blue when focused (remote) or pressed (touch), [base] otherwise. */
    private fun focusBg(radius: Int = 10, base: Int = Color.TRANSPARENT): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), box(BLUE, radius))
        addState(intArrayOf(android.R.attr.state_pressed), box(BLUE, radius))
        addState(intArrayOf(), if (base == Color.TRANSPARENT) ColorDrawable(Color.TRANSPARENT) else box(base, radius))
    }

    private fun input(hint: String, password: Boolean = false) = EditText(this).apply {
        setTextColor(Color.WHITE)
        setHintTextColor(HINT)
        this.hint = hint
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        setSingleLine()
        inputType = InputType.TYPE_CLASS_TEXT or
            if (password) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI
        imeOptions = EditorInfo.IME_ACTION_NEXT
    }

    private fun styledList() = ListView(this).apply {
        divider = ColorDrawable(0x22FFFFFF)
        dividerHeight = 1
        selector = box(BLUE, 8)
        isVerticalScrollBarEnabled = false
        itemsCanFocus = false
        isFocusable = true
    }

    private fun iconButton(icon: Int, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(icon)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(9), dp(9), dp(9), dp(9))
        background = focusBg(22)
        isFocusable = true
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** "Canales  Películas  Series" (+ optional search / filter icons). Same fixed size everywhere. */
    private fun topBar(onSearch: (() -> Unit)?, onFilter: (() -> Unit)?): Pair<LinearLayout, List<TextView>> {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tabs = listOf("Canales", "Películas", "Series").mapIndexed { i, label ->
            text(15f, bold = true).apply {
                this.text = label
                gravity = Gravity.CENTER
                background = focusBg()
                isFocusable = true
                isClickable = true
                setOnClickListener { onTab(i) }
            }
        }
        tabs.forEach { bar.addView(it, LinearLayout.LayoutParams(dp(84), dp(40)).apply { rightMargin = dp(4) }) }
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        fun icon(res: Int, action: () -> Unit) = iconButton(res, action).apply { setPadding(dp(7), dp(7), dp(7), dp(7)) }
        if (onSearch != null) bar.addView(icon(R.drawable.ic_search, onSearch), LinearLayout.LayoutParams(dp(34), dp(34)))
        if (onFilter != null) bar.addView(icon(R.drawable.ic_filter, onFilter), LinearLayout.LayoutParams(dp(34), dp(34)).apply { leftMargin = dp(2) })
        bar.addView(icon(R.drawable.ic_settings) { openSettings() }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { leftMargin = dp(2) })
        return bar to tabs
    }

    private fun searchBox(hint: String) = input(hint).apply {
        inputType = InputType.TYPE_CLASS_TEXT
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        background = box(0x33FFFFFF, 8)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        visibility = View.GONE
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitVideo() }
            setOnTouchListener { _, e -> onTouch(e); true }
            if (GLASS) viewTreeObserver.addOnGlobalLayoutListener { updateBackdrop(); syncSliders() }
        }
        surface = if (GLASS) TextureView(this) else SurfaceView(this)
        root.addView(surface, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        shutter = View(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
        root.addView(shutter, FrameLayout.LayoutParams(-1, -1))

        // Card of the channel that slides in while swiping
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            if (GLASS) background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF23262E.toInt(), 0xFF08090B.toInt()))
            else setBackgroundColor(0xFF0B1230.toInt())
            visibility = View.GONE
        }
        cardLogo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        card.addView(cardLogo, LinearLayout.LayoutParams(dp(220), dp(124)).apply { bottomMargin = dp(18) })
        cardName = text(30f, bold = true).apply { gravity = Gravity.CENTER }
        cardNow = text(18f, MUTED).apply { gravity = Gravity.CENTER }
        card.addView(cardName)
        card.addView(cardNow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        root.addView(card, FrameLayout.LayoutParams(-1, -1))

        // Bottom info banner (live)
        // Phones get a compact card (on a TV it is read from the sofa)
        val compact = !isTv
        info = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (compact) setPadding(dp(12), dp(8), dp(10), dp(8)) else setPadding(dp(20), dp(14), dp(20), dp(14))
            glassy(0xE60A1E5A.toInt(), if (compact) 12 else 14)
            visibility = View.GONE
        }
        infoLogo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        info.addView(infoLogo, if (compact) LinearLayout.LayoutParams(dp(64), dp(36)).apply { rightMargin = dp(12) }
            else LinearLayout.LayoutParams(dp(120), dp(68)).apply { rightMargin = dp(18) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        infoTitle = text(if (compact) 15f else 24f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        infoNow = text(if (compact) 13f else 18f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        infoNext = text(if (compact) 12f else 16f, MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        infoProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        col.addView(infoTitle)
        col.addView(infoNow)
        col.addView(infoProgress, LinearLayout.LayoutParams(-1, dp(if (compact) 3 else 6)).apply {
            topMargin = dp(if (compact) 3 else 4); bottomMargin = dp(if (compact) 2 else 4)
        })
        col.addView(infoNext)
        info.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        // ⇄ Opción N: other streams of the same channel
        infoOptions = text(if (compact) 13f else 16f, bold = true).apply {
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_swap, 0, 0, 0)
            compoundDrawablePadding = dp(6)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(if (compact) 7 else 9), dp(14), dp(if (compact) 7 else 9))
            background = box(0x33FFFFFF, 20)
            isClickable = true
            visibility = View.GONE
            setOnClickListener { chooseOption() }
        }
        info.addView(infoOptions, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        root.addView(info, if (compact) FrameLayout.LayoutParams(dp(600), -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            leftMargin = dp(16); rightMargin = dp(16); bottomMargin = dp(14)
        } else FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dp(40); rightMargin = dp(40); bottomMargin = dp(28)
        })

        fun roundButton(icon: Int, action: () -> Unit) = ImageView(this).apply {
            setImageResource(icon)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(11), dp(11), dp(11), dp(11))
            glassy(0xE60A1E5A.toInt(), 24)
            isClickable = true
            setOnClickListener { action() }
        }

        // Round icon button (touch) that opens the side panel (channels, movies, series)
        menuButton = roundButton(R.drawable.ic_menu) { showPanel() }.apply { visibility = View.GONE }
        menuButton.setPadding(dp(10), dp(10), dp(10), dp(10))
        root.addView(menuButton, FrameLayout.LayoutParams(dp(SIDE_BUTTON), dp(SIDE_BUTTON), Gravity.TOP or Gravity.START).apply {
            topMargin = dp(SIDE_TOP); leftMargin = dp(24)
        })

        // Cast + PiP (phones/tablets only)
        // Right side, one column centred in the black bar: cast, picture in picture, stream info
        topButtons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        castButton = roundButton(R.drawable.ic_cast) { castMenu() }
        pipButton = roundButton(R.drawable.ic_pip) { enterPipNow(onlyIfPlaying = false) }
        infoButton = roundButton(R.drawable.ic_info) { toggleStats() }
        lockButton = roundButton(R.drawable.ic_lock_open) { toggleLock() }
        listOf(castButton, pipButton, infoButton, lockButton).forEach {
            it.setPadding(dp(10), dp(10), dp(10), dp(10))
            topButtons.addView(it, LinearLayout.LayoutParams(dp(SIDE_BUTTON), dp(SIDE_BUTTON)).apply { bottomMargin = dp(10) })
        }
        root.addView(topButtons, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(SIDE_TOP); rightMargin = dp(24)
        })

        // Brightness (left) and volume (right) sliders, lower half of each side
        brightSlider = VerticalSlider(this, getDrawable(R.drawable.ic_brightness)!!.mutate()).apply {
            background = surfaceBg(0xE60A1E5A.toInt(), 22, 0, 22)
            visibility = View.GONE
            onChange = { setBrightness(it) }
            onTouchState = { holdControls(it) }
        }
        volSlider = VerticalSlider(this, getDrawable(R.drawable.ic_volume)!!.mutate()).apply {
            background = surfaceBg(0xE60A1E5A.toInt(), 22, 0, 22)
            visibility = View.GONE
            onChange = { setVolume(it) }
            onTouchState = { holdControls(it) }
        }
        root.addView(brightSlider, FrameLayout.LayoutParams(dp(SIDE_BUTTON), dp(140), Gravity.TOP or Gravity.START))
        root.addView(volSlider, FrameLayout.LayoutParams(dp(SIDE_BUTTON), dp(140), Gravity.TOP or Gravity.END))

        // Minimal stream info (resolution · fps) over the video, toggled with (i)
        statsView = text(13f, bold = true).apply {
            setPadding(dp(12), dp(5), dp(12), dp(5))
            glassy(0xB3000000.toInt(), 14)
            visibility = View.GONE
        }
        root.addView(statsView, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(14)
        })
        if (GLASS) buildPortraitBar()

        // Bottom bar (movies / series)
        vodBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(14), dp(22), dp(14))
            glassy(0xE60A1E5A.toInt(), 14)
            visibility = View.GONE
        }
        val compactBar = GLASS
        if (compactBar) {
            vodBar.setPadding(dp(18), dp(8), dp(14), dp(8))
            vodBar.gravity = Gravity.CENTER_VERTICAL
        }
        vodTitle = text(if (compactBar) 17f else 24f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        vodSub = text(if (compactBar) 13f else 17f, MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        vodProgress = SeekBar(this).apply {
            max = 1000
            keyProgressIncrement = 10
            isFocusable = true
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) = timelinePreview(progress, fromUser)
                override fun onStartTrackingTouch(bar: SeekBar) {
                    seekDragging = true
                }
                override fun onStopTrackingTouch(bar: SeekBar) {
                    seekDragging = false
                    val p = player ?: return
                    val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
                    p.seekTo(dur * bar.progress / 1000)
                    showVodBar()
                }
            })
        }
        vodTime = text(if (compactBar) 13f else 17f)
        vodBar.addView(vodTitle)
        vodBar.addView(vodSub)
        vodBar.addView(vodProgress, LinearLayout.LayoutParams(-1, dp(if (compactBar) 24 else 28)).apply { topMargin = dp(if (compactBar) 2 else 4); bottomMargin = dp(2) })
        vodBar.isClickable = true
        if (!compactBar) vodBar.addView(vodTime)
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (compactBar) controls.addView(vodTime, LinearLayout.LayoutParams(0, -2, 1f))
        fun control(label: String, action: () -> Unit) = text(if (compactBar) 13f else 16f, bold = true).apply {
            this.text = label
            gravity = Gravity.CENTER
            if (compactBar) setPadding(dp(11), dp(6), dp(11), dp(6)) else setPadding(dp(14), dp(8), dp(14), dp(8))
            background = focusBg(18, 0x22FFFFFF)
            isFocusable = true
            isClickable = true
            setOnClickListener {
                action()
                if (vod != null) showVodBar()
            }
        }
        btnPlay = control("❚❚  Pausa") { togglePause() }
        vodControls = listOf(
            control("−15 s") { seekBy(-15_000) },
            btnPlay,
            control("+15 s") { seekBy(15_000) },
            control("Audio") { chooseTrack(C.TRACK_TYPE_AUDIO) },
            control("Subtítulos") { chooseTrack(C.TRACK_TYPE_TEXT) },
            control("Aspecto") { chooseAspect() },
        )
        vodControls.forEach { controls.addView(it, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(if (compactBar) 6 else 8) }) }
        vodBar.addView(controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (compactBar) 4 else 10) })

        subtitleText = text(24f).apply {
            maxLines = 4
            gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
            setPadding(dp(10), dp(2), dp(10), dp(2))
            setBackgroundColor(0x66000000)
            visibility = View.GONE
        }
        root.addView(subtitleText, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(48); leftMargin = dp(60); rightMargin = dp(60)
        })
        root.addView(vodBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dp(40); rightMargin = dp(40); bottomMargin = dp(28)
        })

        status = text(22f).apply {
            maxLines = 3
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(14), dp(24), dp(14))
            glassy(0xCC000000.toInt(), 12, 1)
            visibility = View.GONE
        }
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        // Channel side panel
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            if (GLASS) glassy(0, 0, 1, 26) else setBackgroundColor(0xF20A1238.toInt())
            setPadding(dp(14), dp(14), dp(14), dp(10))
            visibility = View.GONE
        }
        val (panelBar, pTabs) = topBar({ togglePanelSearch() }, { openFilter(TAB_LIVE) })
        panelTabs = pTabs
        panel.addView(panelBar, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        panelSearch = searchBox("Buscar canal").apply {
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    channelQuery = s?.toString().orEmpty()
                    rebuildShown()
                }
            })
            setOnEditorActionListener { _, _, _ -> hideKeyboard(); list.requestFocus(); true }
        }
        panel.addView(panelSearch, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        list = styledList().apply {
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, position, _ ->
                when {
                    position < shown.size -> {
                        hidePanel()
                        play(shown[position])
                    }
                    position == shown.size -> openSettings()
                    position == shown.size + 1 -> {
                        pendingSeries = false
                        showAccount(null)
                    }
                    else -> showSetup(null)
                }
            }
        }
        panel.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        panel.addView(text(13f, HINT).apply {
            this.text = "OK: ver  ·  Atrás: cerrar  ·  ▲▼ en pantalla cambia canal"
        })
        root.addView(panel, FrameLayout.LayoutParams(dp(400), -1, Gravity.START).apply {
            if (GLASS) setMargins(dp(10), dp(10), 0, dp(10))
        })

        // Movies / series browser
        browser = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            if (GLASS) background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFA1A1C22.toInt(), 0xFF070809.toInt()))
            else setBackgroundColor(0xFA081030.toInt())
            setPadding(dp(24), dp(14), dp(24), dp(4))
            visibility = View.GONE
        }
        val (browserBar, bTabs) = topBar(null, null)
        browserTabs = bTabs
        browser.addView(browserBar, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        val content = FrameLayout(this)
        grid = ListView(this).apply {
            divider = null
            dividerHeight = 0
            selector = ColorDrawable(Color.TRANSPARENT)
            itemsCanFocus = true
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(0, 0, 0, dp(24))
        }
        // Header 1: "Seguir viendo"
        continueBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val topHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        topToggleContinue = text(17f, bold = true).apply {
            this.text = "Seguir viendo"
            setPadding(dp(14), dp(6), dp(14), dp(6))
            isFocusable = true
            isClickable = true
            setOnClickListener { switchTop(0) }
            setOnFocusChangeListener { _, has -> if (has) { switchTop(0); scrollCatalogTop() } }
        }
        topToggleLater = text(17f, bold = true).apply {
            this.text = "Ver más tarde"
            setPadding(dp(14), dp(6), dp(14), dp(6))
            isFocusable = true
            isClickable = true
            setOnClickListener { switchTop(1) }
            setOnFocusChangeListener { _, has -> if (has) { switchTop(1); scrollCatalogTop() } }
        }
        topHead.addView(topToggleContinue, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        topHead.addView(topToggleLater)
        continueBox.addView(topHead, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(4); bottomMargin = dp(8) })
        continueRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        continueBox.addView(continueRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })
        grid.addHeaderView(continueBox, null, false)
        // Header 2: categories + search + filter
        val catalogHead = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val chipsLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        chipsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
        }
        chipsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        chipsScroll.addView(chipsRow)
        chipsLine.addView(chipsScroll, LinearLayout.LayoutParams(0, -2, 1f))
        chipsLine.addView(iconButton(R.drawable.ic_search) { toggleBrowserSearch() }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(10) })
        chipsLine.addView(iconButton(R.drawable.ic_filter) { openFilter(if (browserSeries) TAB_SERIES else TAB_MOVIES) },
            LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(6) })
        catalogHead.addView(chipsLine, LinearLayout.LayoutParams(-1, -2))
        browserSearch = searchBox("Buscar en todo el catálogo").apply {
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    main.removeCallbacks(searchDebounce)
                    if ((s?.length ?: 0) >= 2) main.postDelayed(searchDebounce, 900)
                }
            })
            setOnEditorActionListener { _, _, _ ->
                main.removeCallbacks(searchDebounce)
                runSearch(text.toString())
                hideKeyboard()
                true
            }
        }
        catalogHead.addView(browserSearch, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        gridLabel = text(15f, MUTED).apply { visibility = View.GONE }
        catalogHead.addView(gridLabel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); leftMargin = dp(6) })
        browserMessage = text(18f, MUTED).apply {
            maxLines = 3
            gravity = Gravity.CENTER
            setPadding(0, dp(30), 0, dp(30))
            visibility = View.GONE
        }
        catalogHead.addView(browserMessage, LinearLayout.LayoutParams(-1, -2))
        catalogHead.setPadding(0, 0, 0, dp(8))
        grid.addHeaderView(catalogHead, null, false)
        grid.adapter = rowAdapter
        content.addView(grid, FrameLayout.LayoutParams(-1, -1))

        // Series detail: poster + episodes
        seriesView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isClickable = true
            setBackgroundColor(if (GLASS) 0xFF0C0D10.toInt() else 0xFF081030.toInt())
            visibility = View.GONE
        }
        val seriesLeft = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        seriesPoster = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = box(if (GLASS) 0xFF1E2027.toInt() else 0xFF141C3A.toInt(), 8)
        }
        seriesLeft.addView(seriesPoster, LinearLayout.LayoutParams(dp(180), dp(270)))
        seriesTitle = text(20f, bold = true).apply { maxLines = 3 }
        seriesLeft.addView(seriesTitle, LinearLayout.LayoutParams(dp(180), -2).apply { topMargin = dp(10) })
        seriesInfo = text(15f, MUTED).apply { maxLines = 3 }
        seriesLeft.addView(seriesInfo, LinearLayout.LayoutParams(dp(180), -2).apply { topMargin = dp(4) })
        seriesView.addView(seriesLeft, LinearLayout.LayoutParams(-2, -1).apply { rightMargin = dp(24) })
        val seriesRight = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        seasonScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        seasonRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        seasonScroll.addView(seasonRow)
        seriesRight.addView(seasonScroll, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        episodeList = styledList().apply {
            adapter = episodeAdapter
            setOnItemClickListener { _, _, position, _ ->
                val s = openSeries ?: return@setOnItemClickListener
                seasonEpisodes.getOrNull(position)?.let { playVod(episodeWatch(s, it)) }
            }
        }
        seriesRight.addView(episodeList, LinearLayout.LayoutParams(-1, 0, 1f))
        seriesView.addView(seriesRight, LinearLayout.LayoutParams(0, -1, 1f))
        content.addView(seriesView, FrameLayout.LayoutParams(-1, -1))
        browser.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(browser, FrameLayout.LayoutParams(-1, -1))
        grid.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ ->
            if (r - l != or - ol) {
                measureTiles()
                if (browser.visibility == View.VISIBLE) {
                    refreshContinue()
                    rowAdapter.notifyDataSetChanged()
                }
            }
        }

        numberView = text(48f, bold = true).apply {
            setPadding(dp(18), dp(6), dp(18), dp(6))
            glassy(0xCC000000.toInt(), 12, 1)
            visibility = View.GONE
        }
        root.addView(numberView, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(30); rightMargin = dp(40)
        })

        // Hide categories / channels dialog
        filterBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setPadding(dp(24), dp(20), dp(24), dp(16))
            glassy(0xFF0A1E5A.toInt(), 16, 2)
            visibility = View.GONE
        }
        filterTitle = text(22f, bold = true)
        filterBox.addView(filterTitle)
        filterBox.addView(text(15f, MUTED).apply { this.text = "OK o toque para mostrar u ocultar. Lo oculto no aparece en listas ni búsquedas." },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4); bottomMargin = dp(10) })
        filterList = styledList().apply {
            adapter = filterAdapter
            setOnItemClickListener { _, _, position, _ ->
                val id = filterEntries.getOrNull(position)?.first ?: return@setOnItemClickListener
                if (!filterHidden.remove(id)) filterHidden.add(id)
                filterAdapter.notifyDataSetChanged()
            }
        }
        filterBox.addView(filterList, LinearLayout.LayoutParams(-1, 0, 1f))
        filterBox.addView(Button(this).apply {
            this.text = "Listo"
            setOnClickListener { closeFilter() }
        }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END; topMargin = dp(8) })
        root.addView(filterBox, FrameLayout.LayoutParams(dp(560), -1, Gravity.CENTER).apply {
            topMargin = dp(30); bottomMargin = dp(30)
        })

        // Program guide: details on top, grid below, over the video
        epgBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            if (GLASS) glassy(0, 0, 1, 26) else setBackgroundColor(0xF2081030.toInt())
            setPadding(dp(20), dp(12), dp(20), dp(10))
            visibility = View.GONE
        }
        val epgHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        val epgInfo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        epgChannel = text(15f, ACCENT, bold = true)
        epgTitle = text(21f, bold = true)
        epgTime = text(15f, MUTED)
        epgDesc = text(14f, MUTED).apply { maxLines = 2 }
        epgInfo.addView(epgChannel)
        epgInfo.addView(epgTitle)
        epgInfo.addView(epgTime)
        epgInfo.addView(epgDesc)
        epgHead.addView(epgInfo, LinearLayout.LayoutParams(0, -2, 1f))
        epgHead.setOnTouchListener { _, e -> epgDrag(e); true }
        epgCloseButton = text(16f, bold = true).apply {
            this.text = "Cerrar"
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(8), dp(18), dp(8))
            background = focusBg(18, 0x33FFFFFF)
            isClickable = true
            isFocusable = false
            setOnClickListener { closeEpg() }
        }
        epgHead.addView(epgCloseButton, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        epgBox.addView(epgHead, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        epgView = EpgView(this, epgHost).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        epgBox.addView(epgView, LinearLayout.LayoutParams(-1, 0, 1f))
        // The guide is a panel on the right (the channel list is on the left); video stays visible at the left.
        root.addView(epgBox, FrameLayout.LayoutParams(-1, -1, Gravity.END).apply {
            if (GLASS) setMargins(0, dp(10), dp(10), dp(10))
        })
        root.post {
            (epgBox.layoutParams as FrameLayout.LayoutParams).width = (root.width * 0.72f).toInt()
            epgBox.requestLayout()
        }
        root.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                root.post { applyLayoutMode() }
            }
        }

        // Option picker (audio / subtitles / aspect / item menu)
        pickerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setPadding(dp(20), dp(18), dp(20), dp(14))
            glassy(0xF20A1E5A.toInt(), 16, 2)
        }
        pickerTitle = text(21f, bold = true)
        pickerBox.addView(pickerTitle, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        pickerList = styledList().apply {
            adapter = pickerAdapter
            setOnItemClickListener { _, _, position, _ ->
                val action = pickerAction
                closePicker()
                action?.invoke(position)
            }
        }
        pickerBox.addView(pickerList, LinearLayout.LayoutParams(-1, -2))
        pickerLayer = FrameLayout(this).apply {
            setBackgroundColor(0x55000000)
            isClickable = true
            visibility = View.GONE
            setOnClickListener { closePicker() }
        }
        pickerLayer.addView(pickerBox, FrameLayout.LayoutParams(dp(460), -2, Gravity.CENTER))
        root.addView(pickerLayer, FrameLayout.LayoutParams(-1, -1))

        // Playlist setup screen
        setup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            setPadding(dp(36), dp(28), dp(36), dp(28))
            glassy(0xFF0A1E5A.toInt(), 16, 2)
            visibility = View.GONE
        }
        setup.addView(text(30f, bold = true).apply { this.text = "AerioTV Lite" })
        setupMessage = text(18f, MUTED).apply { maxLines = 3; gravity = Gravity.CENTER }
        setup.addView(setupMessage, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10); bottomMargin = dp(16) })
        setupInput = input("https://…").apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, _, _ -> saveSetup(); true }
        }
        setup.addView(setupInput, LinearLayout.LayoutParams(dp(560), -2))
        setup.addView(Button(this).apply {
            this.text = "Guardar y conectar"
            setOnClickListener { saveSetup() }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        root.addView(setup, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        // Xtream account screen
        account = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            setPadding(dp(36), dp(28), dp(36), dp(28))
            glassy(0xFF0A1E5A.toInt(), 16, 2)
            visibility = View.GONE
        }
        account.addView(text(28f, bold = true).apply { this.text = "Películas y series" })
        accMessage = text(17f, MUTED).apply { maxLines = 3; gravity = Gravity.CENTER }
        account.addView(accMessage, LinearLayout.LayoutParams(dp(560), -2).apply { topMargin = dp(8); bottomMargin = dp(12) })
        accServer = input("Servidor (https://…)")
        accUser = input("Usuario")
        accPass = input("Clave", password = true).apply {
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, _, _ -> saveAccount(); true }
        }
        account.addView(accServer, LinearLayout.LayoutParams(dp(560), -2))
        account.addView(accUser, LinearLayout.LayoutParams(dp(560), -2))
        account.addView(accPass, LinearLayout.LayoutParams(dp(560), -2))
        account.addView(Button(this).apply {
            this.text = "Guardar"
            setOnClickListener { saveAccount() }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14) })
        root.addView(account, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        setContentView(root)
        root.requestFocus()
    }


    private fun makeRow(logoW: Int, logoH: Int, compact: Boolean = false): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = if (compact) dp(5) else dp(7)
        setPadding(dp(6), pad, dp(6), pad)
        addView(text(if (compact) 15f else 18f, HINT).apply { gravity = Gravity.END },
            LinearLayout.LayoutParams(dp(if (compact) 30 else 40), -2).apply { rightMargin = dp(if (compact) 8 else 12) })
        addView(ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(dp(logoW), dp(logoH)).apply { rightMargin = dp(if (compact) 8 else 12) })
        val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(if (compact) 16f else 19f, bold = true))
        col.addView(text(if (compact) 13f else 15f, MUTED))
        addView(col, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun fillRow(row: LinearLayout, number: String, logo: String, title: String, sub: String, highlight: Boolean = false) {
        (row.getChildAt(0) as TextView).apply {
            text = if (highlight) "▶" else number
            setTextColor(if (highlight) ACCENT else HINT)
        }
        logos.load(logo, row.getChildAt(1) as ImageView)
        val col = row.getChildAt(2) as LinearLayout
        (col.getChildAt(0) as TextView).apply {
            text = title
            setTextColor(if (highlight) ACCENT else Color.WHITE)
        }
        (col.getChildAt(1) as TextView).apply {
            text = sub
            visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private inner class ChannelAdapter : BaseAdapter() {
        override fun getCount() = if (channels.isEmpty()) 0 else shown.size + 3
        override fun getItem(position: Int): Any? = shown.getOrNull(position)?.let { channels.getOrNull(it) }
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? LinearLayout) ?: makeRow(56, 32, compact = true)
            when {
                position < shown.size -> {
                    val index = shown[position]
                    val ch = channels[index]
                    val program = Guide.nowNext(guide[ch.tvgId], System.currentTimeMillis()).first
                    fillRow(row, ch.number.toString(), ch.logo, ch.name, program?.title.orEmpty(), vod == null && index == current)
                }
                position == shown.size -> fillRow(row, "⚙", "", "Configuración", "Idioma del audio y subtítulos")
                position == shown.size + 1 -> fillRow(row, "⚙", "", "Cuenta de películas y series", "")
                else -> fillRow(row, "⚙", "", "Cambiar dirección de la lista", "")
            }
            return row
        }
    }

    // ---------------------------------------------------------------- poster tiles

    private fun makeTile(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(5), dp(5), dp(5), dp(5))
        background = focusBg(10, Color.TRANSPARENT)
        isFocusable = true
        isClickable = true
        val frame = FrameLayout(this@MainActivity)
        val poster = ImageView(this@MainActivity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = box(if (GLASS) 0xFF1E2027.toInt() else 0xFF141C3A.toInt(), 8)
        }
        frame.addView(poster, FrameLayout.LayoutParams(-1, -1))
        val placeholder = text(15f, MUTED).apply {
            maxLines = 3
            gravity = Gravity.CENTER
            setPadding(dp(6), 0, dp(6), 0)
        }
        frame.addView(placeholder, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        val badge = text(12f, bold = true).apply {
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = box(0xCC000000.toInt(), 6)
        }
        frame.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(5); bottomMargin = dp(5) })
        val bar = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        frame.addView(bar, FrameLayout.LayoutParams(-1, dp(4), Gravity.BOTTOM))
        addView(frame, LinearLayout.LayoutParams(-1, posterH.coerceAtLeast(dp(90))))
        addView(text(13f, bold = true).apply {
            maxLines = 2
            gravity = Gravity.CENTER_HORIZONTAL
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        addView(text(12f, MUTED).apply { gravity = Gravity.CENTER_HORIZONTAL }, LinearLayout.LayoutParams(-1, -2))
    }

    private class TileParts(val frame: FrameLayout, val poster: ImageView, val placeholder: TextView,
                            val badge: TextView, val bar: ProgressBar, val title: TextView, val sub: TextView)

    private fun parts(tile: LinearLayout): TileParts {
        val frame = tile.getChildAt(0) as FrameLayout
        return TileParts(frame, frame.getChildAt(0) as ImageView, frame.getChildAt(1) as TextView,
            frame.getChildAt(2) as TextView, frame.getChildAt(3) as ProgressBar,
            tile.getChildAt(1) as TextView, tile.getChildAt(2) as TextView)
    }

    private fun bindTile(tile: LinearLayout, item: Any) {
        val p = parts(tile)
        val lp = p.frame.layoutParams
        if (lp.height != posterH && posterH > 0) {
            lp.height = posterH
            p.frame.layoutParams = lp
        }
        p.poster.visibility = View.VISIBLE
        when (item) {
            is VodItem -> {
                logos.load(item.poster, p.poster)
                p.placeholder.text = if (item.poster.isEmpty()) item.title else ""
                p.title.text = item.title
                val saved = if (item.series) null else history.get("m:" + item.id)
                val sub = when {
                    saved?.finished == true -> "Vista"
                    later.contains(item) -> listOf(item.year, "Ver más tarde").filter { it.isNotEmpty() }.joinToString(" · ")
                    else -> item.year
                }
                p.sub.text = sub
                p.sub.visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
                p.badge.text = item.rating.take(3)
                p.badge.visibility = if (item.rating.isEmpty()) View.GONE else View.VISIBLE
                val part = saved?.takeIf { !it.finished && it.duration > 0 && it.position > 0 }
                p.bar.visibility = if (part == null) View.GONE else View.VISIBLE
                if (part != null) p.bar.progress = (part.position * 1000 / part.duration).toInt()
            }
            is Watch -> {
                logos.load(item.poster, p.poster)
                p.placeholder.text = if (item.poster.isEmpty()) item.title else ""
                p.title.text = item.title
                p.sub.text = if (item.episode) item.subtitle.substringBefore("  ") else "Quedaste en ${clock(item.position)}"
                p.sub.visibility = View.VISIBLE
                p.badge.visibility = View.GONE
                p.bar.visibility = if (item.duration > 0) View.VISIBLE else View.GONE
                if (item.duration > 0) p.bar.progress = (item.position * 1000 / item.duration).toInt()
            }
        }
    }

    private fun bindMore(tile: LinearLayout, total: Int) {
        val p = parts(tile)
        logos.load("", p.poster)
        p.placeholder.text = "Ver todo\n($total)"
        p.placeholder.setTextColor(Color.WHITE)
        p.title.text = ""
        p.sub.visibility = View.GONE
        p.badge.visibility = View.GONE
        p.bar.visibility = View.GONE
        val lp = p.frame.layoutParams
        if (lp.height != posterH && posterH > 0) {
            lp.height = posterH
            p.frame.layoutParams = lp
        }
    }

    // ---------------------------------------------------------------- adapters

    /** Each list row holds 6 poster tiles. */
    private inner class GridRowAdapter : BaseAdapter() {
        override fun getCount() = (items.size + gridColumns - 1) / gridColumns
        override fun getItem(position: Int): Any? = null
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            if (tileW == 0) measureTiles()
            val cols = gridColumns
            val row = (convertView as? LinearLayout)?.takeIf { it.tag == posterH * 10 + cols } ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                tag = posterH * 10 + cols
                repeat(cols) { addView(makeTile(), LinearLayout.LayoutParams(tileW, -2)) }
            }
            for (i in 0 until cols) {
                val tile = row.getChildAt(i) as LinearLayout
                val item = items.getOrNull(position * cols + i)
                if (item == null) {
                    tile.visibility = View.INVISIBLE
                    tile.setOnClickListener(null)
                    tile.setOnLongClickListener(null)
                } else {
                    tile.visibility = View.VISIBLE
                    bindTile(tile, item)
                    tile.setOnClickListener { onItem(item) }
                    tile.setOnLongClickListener { itemMenu(item); true }
                }
            }
            return row
        }
    }

    private inner class EpisodeAdapter : BaseAdapter() {
        override fun getCount() = seasonEpisodes.size
        override fun getItem(position: Int): Any? = seasonEpisodes.getOrNull(position)
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? LinearLayout) ?: makeRow(96, 54)
            val e = seasonEpisodes[position]
            val w = history.get("e:" + e.id)
            val sub = when {
                w == null -> ""
                w.finished -> "Visto"
                w.position > 30_000 -> "Quedaste en ${clock(w.position)}"
                else -> ""
            }
            fillRow(row, e.number.toString(), e.image, e.title, sub)
            return row
        }
    }

    private inner class PickerAdapter : BaseAdapter() {
        override fun getCount() = pickerOptions.size
        override fun getItem(position: Int): Any? = pickerOptions.getOrNull(position)
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tv = (convertView as? TextView) ?: text(18f).apply {
                setPadding(dp(12), 0, dp(12), 0)
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = android.widget.AbsListView.LayoutParams(-1, dp(46))
            }
            val sel = position == pickerSelected
            tv.text = (if (pickerSelected >= 0) (if (sel) "●   " else "○   ") else "") + pickerOptions[position]
            tv.setTextColor(if (sel) ACCENT else Color.WHITE)
            return tv
        }
    }

    private inner class FilterAdapter : BaseAdapter() {
        override fun getCount() = filterEntries.size
        override fun getItem(position: Int): Any? = filterEntries.getOrNull(position)
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tv = (convertView as? TextView) ?: text(19f).apply { setPadding(dp(12), dp(10), dp(12), dp(10)) }
            val (id, name) = filterEntries[position]
            val hidden = id in filterHidden
            tv.text = (if (hidden) "☐   " else "☑   ") + name + if (hidden) "   (oculto)" else ""
            tv.setTextColor(if (hidden) HINT else Color.WHITE)
            return tv
        }
    }
}
