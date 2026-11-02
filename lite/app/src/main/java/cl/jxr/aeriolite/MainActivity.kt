package cl.jxr.aeriolite

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

        const val ACCENT = 0xFF7FD3FF.toInt()
        const val MUTED = 0xFFB8C7F0.toInt()
        const val HINT = 0xFF8FA0CC.toInt()
        const val BLUE = 0xFF2F6FEB.toInt()
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
    private var retries = 0
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
    private lateinit var surface: SurfaceView
    private lateinit var status: TextView
    private lateinit var info: LinearLayout
    private lateinit var infoLogo: ImageView
    private lateinit var infoTitle: TextView
    private lateinit var infoNow: TextView
    private lateinit var infoNext: TextView
    private lateinit var infoProgress: ProgressBar
    private lateinit var menuButton: TextView
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
    private lateinit var vodProgress: ProgressBar
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
            .setReadTimeoutMs(20_000)
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        )
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 40_000, 1_500, 3_000)
            .build()
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http, extractors))
            .setLoadControl(load)
            .build()
        p.setVideoSurfaceView(surface)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> if (card.visibility != View.VISIBLE) showStatus("Cargando…")
                    Player.STATE_READY -> {
                        retries = 0
                        hideStatus()
                        fadeCard()
                    }
                    Player.STATE_ENDED -> if (vod != null) vodEnded() else retry()
                    else -> {}
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (vod != null) updateVodBar()
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
                    retry()
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
        return p
    }

    private fun play(index: Int) {
        if (channels.isEmpty()) return
        saveVodProgress()
        vod = null
        hideVodBar()
        subtitleText.visibility = View.GONE
        if (aspectMode != 0) { aspectMode = 0; fitVideo() }
        current = index.coerceIn(0, channels.size - 1)
        retries = 0
        val ch = channels[current]
        val item = MediaItem.Builder().setUri(ch.url)
        if (ch.url.contains(".m3u8", true)) item.setMimeType(MimeTypes.APPLICATION_M3U8)
        val p = ensurePlayer()
        p.setMediaItem(item.build())
        p.prepare()
        p.playWhenReady = true
        showInfo(false)
    }

    private fun retry() {
        val p = player ?: return
        if (retries < 3) {
            retries++
            showStatus("Reconectando… ($retries/3)")
            val index = current
            val playing = vod
            main.postDelayed({
                if (player === p && current == index && vod === playing) {
                    p.prepare()
                    p.playWhenReady = true
                }
            }, 2000L * retries)
        } else {
            val name = vod?.title ?: channels.getOrNull(current)?.name ?: "el canal"
            showStatus("No se pudo reproducir $name.\n" + if (vod != null) "Atrás para volver." else "OK para ver los canales.")
        }
    }

    private fun releasePlayer() {
        main.removeCallbacks(vodSaver)
        player?.release()
        player = null
    }

    private fun stopPlayback() {
        main.removeCallbacks(vodSaver)
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
        play(neighbor(step))
    }

    private fun fitVideo() {
        val w = root.width
        val h = root.height
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
        lp.gravity = Gravity.CENTER
        surface.layoutParams = lp
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
        hideKeyboard()
        browser.visibility = View.GONE
        panel.visibility = View.GONE
        hideInfo.run()
        vod = w
        retries = 0
        if (aspectMode != 0) { aspectMode = 0; fitVideo() }
        val url = if (w.episode) acc.episodeUrl(w.id, w.ext) else acc.movieUrl(w.id, w.ext)
        val item = MediaItem.Builder().setUri(url)
        if (w.ext.equals("m3u8", true)) item.setMimeType(MimeTypes.APPLICATION_M3U8)
        val p = ensurePlayer()
        p.setMediaItem(item.build())
        val saved = history.get(w.key)
        val resumeAt = saved?.takeIf { !it.finished && it.position > 10_000 }?.position ?: 0L
        if (resumeAt > 0) p.seekTo(resumeAt)
        p.prepare()
        p.playWhenReady = true
        subtitleText.text = ""
        subtitleText.visibility = View.GONE
        // Appears in "Seguir viendo" right away, not only after some minutes.
        history.update(w, resumeAt, saved?.duration ?: 0L)
        if (resumeAt > 0) Toast.makeText(this, "Continuando desde ${clock(resumeAt)}", Toast.LENGTH_LONG).show()
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

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: Long.MAX_VALUE
        p.seekTo((p.currentPosition + deltaMs).coerceIn(0, dur))
        showVodBar()
    }

    private val hideVodBarTask = Runnable {
        if (player?.isPlaying == true && pickerBox.visibility != View.VISIBLE) {
            vodBar.visibility = View.GONE
            root.requestFocus()
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
        if (focusControls) btnPlay.post { btnPlay.requestFocus() }
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
        if (vodBar.hasFocus()) root.requestFocus()
        main.removeCallbacks(vodBarUpdater)
    }

    private fun updateVodBar() {
        val p = player ?: return
        val pos = p.currentPosition
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        vodProgress.progress = if (dur > 0) (pos * 1000 / dur).toInt() else 0
        val state = if (!p.playWhenReady) "Pausa   ·   " else ""
        vodTime.text = state + clock(pos) + (if (dur > 0) " / " + clock(dur) else "")
        btnPlay.text = if (p.playWhenReady) "❚❚  Pausa" else "▶  Reproducir"
        if (!p.playWhenReady) {
            vodBar.visibility = View.VISIBLE
            main.removeCallbacks(hideVodBarTask)
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
        when (tab) {
            TAB_LIVE -> {
                if (browser.visibility == View.VISIBLE) closeBrowser()
                showPanel()
            }
            TAB_MOVIES -> openBrowser(false)
            TAB_SERIES -> openBrowser(true)
        }
    }

    private fun markTabs(tabs: List<TextView>, active: Int) {
        tabs.forEachIndexed { i, t ->
            t.setTextColor(if (i == active) ACCENT else Color.WHITE)
            t.background = focusBg(10, if (i == active) 0x332F6FEB else Color.TRANSPARENT)
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
        grid.post {
            val first = if (continueBox.visibility == View.VISIBLE) continueRow.getChildAt(0) else chips.firstOrNull()
            (first ?: grid).requestFocus()
        }
    }

    private fun closeBrowser() {
        hideKeyboard()
        browser.visibility = View.GONE
        root.requestFocus()
        if (channels.isNotEmpty()) play(current)
    }

    private fun gridStatus(text: String?) {
        browserMessage.text = text.orEmpty()
        browserMessage.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    /** Tile and poster size: 6 columns across the width, vertical 2:3 posters. */
    private fun measureTiles() {
        val width = (if (grid.width > 0) grid.width else root.width - dp(48)).coerceAtLeast(dp(300))
        tileW = width / 6
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
            }
        }
        chips.forEach { chipsRow.addView(it, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) }) }
        markChips()
    }

    private fun markChips() {
        val sel = if (gridMode == MODE_CATEGORY) selectedCat[browserSeries]?.id else null
        chips.forEachIndexed { i, chip ->
            val active = visibleCats.getOrNull(i)?.id == sel
            chip.background = focusBg(18, if (active) 0xFF3B5BA8.toInt() else 0x22FFFFFF)
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
            t.background = focusBg(16, if (active) 0xFF3B5BA8.toInt() else 0x22FFFFFF)
            t.setTextColor(if (active) Color.WHITE else MUTED)
        }
        val entries: List<Any> = if (topMode == 0) cont else saved
        entries.take(5).forEach { item ->
            val tile = makeTile()
            bindTile(tile, item)
            tile.setOnClickListener { onItem(item) }
            tile.setOnLongClickListener { itemMenu(item); true }
            continueRow.addView(tile, LinearLayout.LayoutParams(tileW, -2))
        }
        val all = makeTile()
        bindMore(all, entries.size)
        all.setOnClickListener { showAllTop() }
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
            chip.background = focusBg(16, if (active) 0xFF3B5BA8.toInt() else 0x22FFFFFF)
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
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) onBack()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun overlayOpen() = setup.visibility == View.VISIBLE || account.visibility == View.VISIBLE ||
        browser.visibility == View.VISIBLE || filterBox.visibility == View.VISIBLE || pickerBox.visibility == View.VISIBLE ||
        epgBox.visibility == View.VISIBLE

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (overlayOpen() || panel.visibility == View.VISIBLE) return super.onKeyDown(keyCode, event)
        if (vod != null) return vodKey(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> zap(1)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> zap(-1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_TV_INPUT -> showPanel()
            KeyEvent.KEYCODE_DPAD_RIGHT -> openEpg()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_INFO -> showInfo(false)
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> typeDigit(keyCode - KeyEvent.KEYCODE_0)
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
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
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> seekBy(-step)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seekBy(step)
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

    // ---------------------------------------------------------------- picker

    private fun showPicker(title: String, options: List<String>, selected: Int, action: (Int) -> Unit) {
        pickerReturnFocus = currentFocus
        pickerTitle.text = title
        pickerOptions = options
        pickerSelected = selected
        pickerAction = action
        pickerAdapter.notifyDataSetChanged()
        main.removeCallbacks(hideVodBarTask)
        pickerBox.alpha = 0f
        pickerBox.visibility = View.VISIBLE
        pickerBox.animate().alpha(1f).setDuration(160).start()
        pickerList.requestFocus()
        pickerList.setSelection(selected.coerceAtLeast(0))
    }

    private fun closePicker() {
        pickerBox.visibility = View.GONE
        pickerAction = null
        val back = pickerReturnFocus
        if (back != null && back.isShown) back.requestFocus() else root.requestFocus()
        if (vod != null) showVodBar()
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
            pickerBox.visibility == View.VISIBLE -> closePicker()
            epgBox.visibility == View.VISIBLE -> closeEpg()
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
                if (!dragging && tracking && abs(dx) > slop * 2 && abs(dx) > abs(dy) * 1.3f && canDrag()) {
                    dragging = true
                    hideInfo.run()
                }
                if (!dragging && abs(dy) > slop * 2) tracking = false
                if (dragging && vod == null) dragTo(dx)
            }
            MotionEvent.ACTION_UP -> {
                velocity?.addMovement(e)
                velocity?.computeCurrentVelocity(1000)
                val vx = velocity?.xVelocity ?: 0f
                val dx = e.x - downX
                val dy = e.y - downY
                when {
                    !dragging && vod == null && dy < -dp(90) && abs(dy) > abs(dx) * 1.5f &&
                        !overlayOpen() && panel.visibility != View.VISIBLE -> openEpg()
                    dragging && vod != null -> seekBy(if (dx < 0) 30_000L else -30_000L)
                    dragging -> endDrag(dx, vx)
                    tracking && abs(dx) < slop * 2 && abs(e.y - downY) < slop * 2 -> onTap()
                }
                dragging = false
                tracking = false
                velocity?.recycle()
                velocity = null
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging && vod == null) endDrag(0f, 0f)
                dragging = false
                tracking = false
            }
        }
    }

    private fun canDrag() = !overlayOpen() && panel.visibility != View.VISIBLE &&
        (vod != null || channels.size > 1)

    /** Moves the video with the finger while the next channel's card slides in. */
    private fun dragTo(dx: Float) {
        val w = root.width.toFloat()
        val target = neighbor(if (dx < 0) 1 else -1)
        if (target != dragTarget) {
            dragTarget = target
            fillCard(target)
        }
        surface.translationX = dx
        card.alpha = 1f
        card.visibility = View.VISIBLE
        card.translationX = if (dx < 0) w + dx else -w + dx
    }

    private fun endDrag(dx: Float, vx: Float) {
        val w = root.width.toFloat()
        val fling = abs(vx) > dp(900) && (vx < 0) == (dx < 0)
        val commit = dragTarget >= 0 && dx != 0f && (abs(dx) > w * 0.28f || fling)
        if (commit) {
            val dir = if (dx < 0) -1f else 1f
            val target = dragTarget
            surface.animate().translationX(dir * w).setDuration(180).start()
            card.animate().translationX(0f).setDuration(180).withEndAction {
                surface.translationX = 0f
                cardPending = true
                play(target)
                main.removeCallbacks(cardTimeout)
                main.postDelayed(cardTimeout, 5000)
            }.start()
        } else {
            surface.animate().translationX(0f).setDuration(160).start()
            val away = if (card.translationX > 0) w else -w
            card.animate().translationX(away).setDuration(160).withEndAction { card.visibility = View.GONE }.start()
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
            vod != null -> if (vodBar.visibility == View.VISIBLE) hideVodBar() else showVodBar()
            info.visibility == View.VISIBLE && menuButton.visibility == View.VISIBLE -> hideInfo.run()
            else -> showInfo(true)
        }
    }

    // ---------------------------------------------------------------- program guide (EPG)

    private fun openEpg() {
        if (channels.isEmpty() || vod != null) return
        hideInfo.run()
        panel.visibility = View.GONE
        val rows = channels.filter { it.name !in hiddenChannels }.ifEmpty { channels }
        val currentRow = rows.indexOfFirst { it.number == channels.getOrNull(current)?.number }.coerceAtLeast(0)
        epgView.setData(rows, currentRow)
        if (guide.isEmpty()) refreshGuide(false)
        epgBox.visibility = View.VISIBLE
        epgBox.translationY = root.height * 0.3f
        epgBox.alpha = 0f
        epgBox.animate().translationY(0f).alpha(1f).setDuration(220).start()
        epgView.requestFocus()
    }

    private fun closeEpg() {
        epgBox.visibility = View.GONE
        root.requestFocus()
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

        override fun onPlay(channel: Channel) {
            val index = channels.indexOfFirst { it.number == channel.number }
            closeEpg()
            if (index >= 0 && index != current) play(index) else showInfo(false)
        }
    }

    // ---------------------------------------------------------------- overlays

    private val hideInfo = Runnable {
        info.visibility = View.GONE
        menuButton.visibility = View.GONE
    }

    private fun showInfo(touch: Boolean) {
        if (channels.isEmpty()) return
        updateInfo()
        info.visibility = View.VISIBLE
        if (touch) menuButton.visibility = View.VISIBLE
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
    }

    private fun showPanel() {
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

    private fun showStatus(text: String) {
        status.text = text
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
            text(18f, bold = true).apply {
                this.text = label
                gravity = Gravity.CENTER
                background = focusBg()
                isFocusable = true
                isClickable = true
                setOnClickListener { onTab(i) }
            }
        }
        tabs.forEach { bar.addView(it, LinearLayout.LayoutParams(dp(112), dp(42)).apply { rightMargin = dp(6) }) }
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        if (onSearch != null) bar.addView(iconButton(R.drawable.ic_search, onSearch), LinearLayout.LayoutParams(dp(44), dp(44)))
        if (onFilter != null) bar.addView(iconButton(R.drawable.ic_filter, onFilter), LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(6) })
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
        }
        surface = SurfaceView(this)
        root.addView(surface, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))

        // Card of the channel that slides in while swiping
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF0B1230.toInt())
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
        info = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = box(0xE60A1E5A.toInt(), 14)
            visibility = View.GONE
        }
        infoLogo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        info.addView(infoLogo, LinearLayout.LayoutParams(dp(120), dp(68)).apply { rightMargin = dp(18) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        infoTitle = text(24f, bold = true)
        infoNow = text(18f)
        infoNext = text(16f, MUTED)
        infoProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        col.addView(infoTitle)
        col.addView(infoNow)
        col.addView(infoProgress, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(4); bottomMargin = dp(4) })
        col.addView(infoNext)
        info.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(info, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dp(40); rightMargin = dp(40); bottomMargin = dp(28)
        })

        // Round button (touch) that opens the channel list
        menuButton = text(18f, bold = true).apply {
            this.text = "  Canales"
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_menu, 0, 0, 0)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(20), dp(10))
            background = box(0xE60A1E5A.toInt(), 28)
            isClickable = true
            visibility = View.GONE
            setOnClickListener { showPanel() }
        }
        root.addView(menuButton, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            topMargin = dp(24); leftMargin = dp(32)
        })

        // Bottom bar (movies / series)
        vodBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(14), dp(22), dp(14))
            background = box(0xE60A1E5A.toInt(), 14)
            visibility = View.GONE
        }
        vodTitle = text(24f, bold = true)
        vodSub = text(17f, MUTED)
        vodProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        vodTime = text(17f)
        vodBar.addView(vodTitle)
        vodBar.addView(vodSub)
        vodBar.addView(vodProgress, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(8); bottomMargin = dp(6) })
        vodBar.isClickable = true
        vodBar.addView(vodTime)
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun control(label: String, action: () -> Unit) = text(16f, bold = true).apply {
            this.text = label
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
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
        vodControls.forEach { controls.addView(it, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) }) }
        vodBar.addView(controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

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
            background = box(0xCC000000.toInt())
            visibility = View.GONE
        }
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        // Channel side panel
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setBackgroundColor(0xF20A1238.toInt())
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
                    position == shown.size -> {
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
        root.addView(panel, FrameLayout.LayoutParams(dp(520), -1, Gravity.START))

        // Movies / series browser
        browser = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setBackgroundColor(0xFA081030.toInt())
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
            setOnFocusChangeListener { _, has -> if (has) switchTop(0) }
        }
        topToggleLater = text(17f, bold = true).apply {
            this.text = "Ver más tarde"
            setPadding(dp(14), dp(6), dp(14), dp(6))
            isFocusable = true
            isClickable = true
            setOnClickListener { switchTop(1) }
            setOnFocusChangeListener { _, has -> if (has) switchTop(1) }
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
            setBackgroundColor(0xFF081030.toInt())
            visibility = View.GONE
        }
        val seriesLeft = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        seriesPoster = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = box(0xFF141C3A.toInt(), 8)
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
            background = box(0xCC000000.toInt())
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
            background = box(0xFF0A1E5A.toInt(), 16)
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
            setBackgroundColor(0xF2081030.toInt())
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
        epgHead.addView(text(16f, bold = true).apply {
            this.text = "Cerrar"
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(8), dp(18), dp(8))
            background = focusBg(18, 0x33FFFFFF)
            isClickable = true
            isFocusable = false
            setOnClickListener { closeEpg() }
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        epgBox.addView(epgHead, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        epgView = EpgView(this, epgHost).apply {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        epgBox.addView(epgView, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(epgBox, FrameLayout.LayoutParams(-1, -1, Gravity.BOTTOM))
        root.post {
            // Leave the top part of the screen to the video.
            (epgBox.layoutParams as FrameLayout.LayoutParams).height = (root.height * 0.66f).toInt()
            epgBox.requestLayout()
        }
        root.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) {
                (epgBox.layoutParams as FrameLayout.LayoutParams).height = ((bottom - top) * 0.66f).toInt()
                epgBox.requestLayout()
            }
        }

        // Option picker (audio / subtitles / aspect / item menu)
        pickerBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            setPadding(dp(20), dp(18), dp(20), dp(14))
            background = box(0xF20A1E5A.toInt(), 16)
            visibility = View.GONE
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
        root.addView(pickerBox, FrameLayout.LayoutParams(dp(440), -2, Gravity.CENTER))

        // Playlist setup screen
        setup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            setPadding(dp(36), dp(28), dp(36), dp(28))
            background = box(0xFF0A1E5A.toInt(), 16)
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
            background = box(0xFF0A1E5A.toInt(), 16)
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


    private fun makeRow(logoW: Int, logoH: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(7), dp(8), dp(7))
        addView(text(18f, HINT).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(dp(40), -2).apply { rightMargin = dp(12) })
        addView(ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(dp(logoW), dp(logoH)).apply { rightMargin = dp(12) })
        val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(19f, bold = true))
        col.addView(text(15f, MUTED))
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
        override fun getCount() = if (channels.isEmpty()) 0 else shown.size + 2
        override fun getItem(position: Int): Any? = shown.getOrNull(position)?.let { channels.getOrNull(it) }
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? LinearLayout) ?: makeRow(72, 40)
            when {
                position < shown.size -> {
                    val index = shown[position]
                    val ch = channels[index]
                    val program = Guide.nowNext(guide[ch.tvgId], System.currentTimeMillis()).first
                    fillRow(row, ch.number.toString(), ch.logo, ch.name, program?.title.orEmpty(), vod == null && index == current)
                }
                position == shown.size -> fillRow(row, "⚙", "", "Cuenta de películas y series", "")
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
            background = box(0xFF141C3A.toInt(), 8)
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
        override fun getCount() = (items.size + 5) / 6
        override fun getItem(position: Int): Any? = null
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            if (tileW == 0) measureTiles()
            val row = (convertView as? LinearLayout)?.takeIf { it.tag == posterH } ?: LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                tag = posterH
                repeat(6) { addView(makeTile(), LinearLayout.LayoutParams(tileW, -2)) }
            }
            for (i in 0 until 6) {
                val tile = row.getChildAt(i) as LinearLayout
                val item = items.getOrNull(position * 6 + i)
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
            val tv = (convertView as? TextView) ?: text(19f).apply { setPadding(dp(12), dp(11), dp(12), dp(11)) }
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
