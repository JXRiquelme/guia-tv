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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
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

    // Movies / series browser
    private lateinit var browser: LinearLayout
    private lateinit var browserTabs: List<TextView>
    private lateinit var browserSub: TextView
    private lateinit var browserSearch: EditText
    private lateinit var catList: ListView
    private lateinit var itemList: ListView
    private lateinit var browserMessage: TextView
    private val catAdapter = CategoryAdapter()
    private val itemAdapter = ItemAdapter()
    private var browserSeries = false
    private var pendingSeries = false
    private val categoryCache = HashMap<Boolean, List<Category>>()
    private val itemCache = HashMap<String, List<VodItem>>()
    private val catalogCache = HashMap<Boolean, List<VodItem>>()
    private var cats: List<Category> = emptyList()
    private var visibleCats: List<Category> = emptyList()
    private var hasContinueRow = false
    private var rows: List<Any> = emptyList()
    private var rowsLabel = ""
    private var openSeries: VodItem? = null
    private var seriesBackRows: List<Any> = emptyList()
    private var seriesBackLabel = ""
    private var seriesBackSelection = 0
    private var episodes: List<Episode> = emptyList()
    private var loadToken = 0
    private var searchActive = false
    private var rowsBeforeSearch: List<Any> = emptyList()
    private var labelBeforeSearch = ""

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
        val aspect = videoW * videoRatio / videoH
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
        val url = if (w.episode) acc.episodeUrl(w.id, w.ext) else acc.movieUrl(w.id, w.ext)
        val item = MediaItem.Builder().setUri(url)
        if (w.ext.equals("m3u8", true)) item.setMimeType(MimeTypes.APPLICATION_M3U8)
        val p = ensurePlayer()
        p.setMediaItem(item.build())
        val saved = history.get(w.key)
        val resumeAt = saved?.takeIf { !it.finished && it.position > 30_000 }?.position ?: 0L
        if (resumeAt > 0) p.seekTo(resumeAt)
        p.prepare()
        p.playWhenReady = true
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
        if (pos > 5_000) history.update(w, pos, dur)
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
        showBrowser()
        if (rows.isNotEmpty()) {
            itemAdapter.notifyDataSetChanged()
            itemList.requestFocus()
        } else {
            catList.requestFocus()
        }
    }

    private fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: Long.MAX_VALUE
        p.seekTo((p.currentPosition + deltaMs).coerceIn(0, dur))
        showVodBar()
    }

    private val hideVodBarTask = Runnable { if (player?.isPlaying == true) vodBar.visibility = View.GONE }
    private val vodBarUpdater = object : Runnable {
        override fun run() {
            if (vodBar.visibility == View.VISIBLE) {
                updateVodBar()
                main.postDelayed(this, 1000)
            }
        }
    }

    private fun showVodBar() {
        val w = vod ?: return
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
        main.removeCallbacks(vodBarUpdater)
    }

    private fun updateVodBar() {
        val p = player ?: return
        val pos = p.currentPosition
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        vodProgress.progress = if (dur > 0) (pos * 1000 / dur).toInt() else 0
        val state = if (!p.playWhenReady) "Pausa   ·   " else ""
        vodTime.text = state + clock(pos) + (if (dur > 0) " / " + clock(dur) else "")
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
            t.typeface = if (i == active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
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
        if (browserSeries != series || cats.isEmpty()) {
            browserSeries = series
            openSeries = null
            rows = emptyList()
            rowsLabel = ""
            cats = emptyList()
            visibleCats = emptyList()
            closeSearch(restore = false)
        }
        showBrowser()
        if (cats.isEmpty()) loadCategories() else catList.requestFocus()
    }

    private fun showBrowser() {
        markTabs(browserTabs, if (browserSeries) TAB_SERIES else TAB_MOVIES)
        val base = if (browserSeries) "Series" else "Películas"
        browserSub.text = when {
            openSeries != null -> "$base  ›  ${openSeries!!.title}"
            rowsLabel.isNotEmpty() -> "$base  ›  $rowsLabel"
            else -> base
        }
        hasContinueRow = history.continueWatching(browserSeries).isNotEmpty()
        visibleCats = cats.filter { it.id !in hiddenCats(browserSeries) }
        catAdapter.notifyDataSetChanged()
        browser.visibility = View.VISIBLE
    }

    private fun closeBrowser() {
        hideKeyboard()
        browser.visibility = View.GONE
        browserMessage.visibility = View.GONE
        root.requestFocus()
        if (channels.isNotEmpty()) play(current)
    }

    private fun browserStatus(text: String?) {
        browserMessage.text = text.orEmpty()
        browserMessage.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun loadCategories(then: (() -> Unit)? = null) {
        val acc = xtream() ?: return
        val series = browserSeries
        categoryCache[series]?.let {
            cats = it
            showBrowser()
            catList.requestFocus()
            then?.invoke()
            return
        }
        val token = ++loadToken
        browserStatus("Cargando categorías…")
        catList.requestFocus()
        io.execute {
            val result = runCatching { Xtream.categories(acc, series) }
            main.post {
                if (token != loadToken) return@post
                result.onSuccess {
                    categoryCache[series] = it
                    cats = it
                    showBrowser()
                    browserStatus(if (it.isEmpty()) "El proveedor no tiene categorías aquí." else "Elige una categoría.")
                    catList.requestFocus()
                    catList.setSelection(0)
                    then?.invoke()
                }.onFailure {
                    browserStatus("No se pudo cargar: ${it.message}\nOK en una categoría para reintentar.")
                }
            }
        }
    }

    private fun onCategory(position: Int) {
        openSeries = null
        closeSearch(restore = false)
        if (hasContinueRow && position == 0) {
            setRows(history.continueWatching(browserSeries), "Seguir viendo")
            browserStatus(null)
            showBrowser()
            itemList.requestFocus()
            return
        }
        if (cats.isEmpty()) {
            loadCategories()
            return
        }
        val cat = visibleCats.getOrNull(position - if (hasContinueRow) 1 else 0) ?: return
        val acc = xtream() ?: return
        val series = browserSeries
        val key = "$series:${cat.id}"
        itemCache[key]?.let {
            setRows(it, cat.name)
            showBrowser()
            browserStatus(if (it.isEmpty()) "Categoría vacía." else null)
            itemList.requestFocus()
            return
        }
        val token = ++loadToken
        setRows(emptyList(), cat.name)
        showBrowser()
        browserStatus("Cargando ${cat.name}…")
        io.execute {
            val result = runCatching { Xtream.items(acc, series, cat.id) }
            main.post {
                if (token != loadToken) return@post
                result.onSuccess {
                    itemCache[key] = it
                    setRows(it, cat.name)
                    browserStatus(if (it.isEmpty()) "Categoría vacía." else null)
                    if (it.isNotEmpty()) itemList.requestFocus()
                }.onFailure { browserStatus("No se pudo cargar: ${it.message}") }
            }
        }
    }

    private fun setRows(newRows: List<Any>, label: String) {
        rows = newRows
        rowsLabel = label
        itemAdapter.notifyDataSetChanged()
        itemList.setSelection(0)
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

    private fun onItem(position: Int) {
        hideKeyboard()
        when (val row = rows.getOrNull(position)) {
            is Watch -> {
                if (row.episode && row.seriesId.isNotEmpty() && openSeries?.id != row.seriesId) {
                    loadEpisodes(VodItem(true, row.seriesId, row.title, row.poster, row.ext, "", ""), playAfter = row)
                } else {
                    playVod(row)
                }
            }
            is Episode -> openSeries?.let { playVod(episodeWatch(it, row)) }
            is VodItem -> if (row.series) {
                seriesBackRows = rows
                seriesBackLabel = rowsLabel
                seriesBackSelection = position
                loadEpisodes(row, null)
            } else {
                playVod(Watch(false, row.id, row.ext, row.title, row.year, row.poster))
            }
        }
    }

    private fun loadEpisodes(series: VodItem, playAfter: Watch?) {
        val acc = xtream() ?: return
        val token = ++loadToken
        if (playAfter == null) {
            openSeries = series
            setRows(emptyList(), rowsLabel)
            showBrowser()
            browserStatus("Cargando episodios…")
        }
        io.execute {
            val result = runCatching { Xtream.episodes(acc, series.id) }
            main.post {
                if (token != loadToken) return@post
                if (playAfter != null) {
                    // Coming from "Seguir viendo": remember the series, then play.
                    seriesBackRows = rows
                    seriesBackLabel = rowsLabel
                    seriesBackSelection = itemList.selectedItemPosition.coerceAtLeast(0)
                    openSeries = series
                    episodes = result.getOrDefault(emptyList())
                    if (episodes.isNotEmpty()) {
                        rows = episodes
                        itemAdapter.notifyDataSetChanged()
                        val idx = episodes.indexOfFirst { it.id == playAfter.id }
                        if (idx >= 0) itemList.setSelection(idx)
                    }
                    playVod(playAfter)
                    return@post
                }
                result.onSuccess {
                    episodes = it
                    rows = it
                    itemAdapter.notifyDataSetChanged()
                    showBrowser()
                    browserStatus(if (it.isEmpty()) "Esta serie no tiene episodios." else null)
                    val resumeIdx = it.indexOfFirst { e -> history.get("e:" + e.id)?.let { w -> !w.finished } == true }
                    itemList.setSelection(resumeIdx.coerceAtLeast(0))
                    itemList.requestFocus()
                }.onFailure { browserStatus("No se pudo cargar: ${it.message}") }
            }
        }
    }

    private fun browserBack() {
        when {
            openSeries != null -> {
                openSeries = null
                episodes = emptyList()
                loadToken++
                browserStatus(null)
                rows = seriesBackRows
                rowsLabel = seriesBackLabel
                itemAdapter.notifyDataSetChanged()
                itemList.setSelection(seriesBackSelection)
                showBrowser()
                itemList.requestFocus()
            }
            searchActive -> closeSearch(restore = true)
            itemList.hasFocus() -> catList.requestFocus()
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
        if (::browserSearch.isInitialized) {
            browserSearch.visibility = View.GONE
            hideKeyboard()
        }
        if (searchActive && restore) {
            loadToken++
            rows = rowsBeforeSearch
            rowsLabel = labelBeforeSearch
            itemAdapter.notifyDataSetChanged()
            browserStatus(null)
            showBrowser()
            itemList.requestFocus()
        }
        searchActive = false
    }

    private val searchDebounce = Runnable { runSearch(browserSearch.text.toString()) }

    private fun runSearch(raw: String) {
        val query = raw.trim()
        if (query.length < 2) return
        val acc = xtream() ?: return
        val series = browserSeries
        if (!searchActive) {
            rowsBeforeSearch = rows
            labelBeforeSearch = rowsLabel
            searchActive = true
        }
        openSeries = null
        val token = ++loadToken
        val show = { all: List<VodItem> ->
            val q = Search.normalize(query)
            val hidden = hiddenCats(series)
            val found = all.filter { it.categoryId !in hidden && it.searchKey.contains(q) }
            setRows(found.take(400), "Buscar “$query”")
            showBrowser()
            browserStatus(if (found.isEmpty()) "Sin resultados para “$query”." else null)
        }
        val cached = catalogCache[series]
        if (cached != null) {
            show(cached)
            return
        }
        setRows(emptyList(), "Buscar “$query”")
        showBrowser()
        browserStatus("Buscando en todo el catálogo…\nLa primera búsqueda tarda un poco.")
        io.execute {
            val result = runCatching { Xtream.items(acc, series, null) }
            main.post {
                result.onSuccess { catalogCache[series] = it }
                if (token != loadToken) return@post
                result.onSuccess { show(it) }.onFailure { browserStatus("No se pudo buscar: ${it.message}") }
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
        filterBox.visibility = View.VISIBLE
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
            showBrowser()
            catList.requestFocus()
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
        browser.visibility == View.VISIBLE || filterBox.visibility == View.VISIBLE

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (overlayOpen() || panel.visibility == View.VISIBLE) return super.onKeyDown(keyCode, event)
        if (vod != null) return vodKey(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> zap(1)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> zap(-1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_TV_INPUT -> showPanel()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> showInfo(false)
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
            else -> 10_000L
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> togglePause()
            KeyEvent.KEYCODE_MEDIA_PLAY -> { p.playWhenReady = true; showVodBar() }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { p.playWhenReady = false; saveVodProgress(); showVodBar() }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> seekBy(-step)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seekBy(step)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MENU -> showVodBar()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private fun togglePause() {
        val p = player ?: return
        p.playWhenReady = !p.playWhenReady
        if (!p.playWhenReady) saveVodProgress()
        showVodBar()
    }

    private fun onBack() {
        when {
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
                when {
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
            overlayOpen() -> {}
            panel.visibility == View.VISIBLE -> hidePanel()
            vod != null -> if (vodBar.visibility == View.VISIBLE) togglePause() else showVodBar()
            info.visibility == View.VISIBLE && menuButton.visibility == View.VISIBLE -> hideInfo.run()
            else -> showInfo(true)
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
        panel.visibility = View.VISIBLE
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

    /** Blue when focused (remote) or pressed (touch), transparent otherwise. */
    private fun focusBg(radius: Int = 10): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), box(BLUE, radius))
        addState(intArrayOf(android.R.attr.state_pressed), box(BLUE, radius))
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
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

    /** "Canales  Películas  Series   [lupa] [filtro]" */
    private fun topBar(onSearch: () -> Unit, onFilter: () -> Unit): Pair<LinearLayout, List<TextView>> {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tabs = listOf("Canales", "Películas", "Series").mapIndexed { i, label ->
            text(21f).apply {
                this.text = label
                setPadding(dp(12), dp(8), dp(12), dp(8))
                background = focusBg()
                isFocusable = true
                isClickable = true
                setOnClickListener { onTab(i) }
            }
        }
        tabs.forEach { bar.addView(it, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(4) }) }
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(iconButton(R.drawable.ic_search, onSearch), LinearLayout.LayoutParams(dp(44), dp(44)))
        bar.addView(iconButton(R.drawable.ic_filter, onFilter), LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(6) })
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
        val timeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        timeRow.addView(vodTime, LinearLayout.LayoutParams(0, -2, 1f))
        timeRow.addView(text(14f, HINT).apply { this.text = "OK pausa  ·  ◀ ▶ retrocede/adelanta  ·  Atrás vuelve" })
        vodBar.addView(timeRow)
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
            setPadding(dp(28), dp(16), dp(28), dp(12))
            visibility = View.GONE
        }
        val (browserBar, bTabs) = topBar({ toggleBrowserSearch() }, { openFilter(if (browserSeries) TAB_SERIES else TAB_MOVIES) })
        browserTabs = bTabs
        browser.addView(browserBar, LinearLayout.LayoutParams(-1, -2))
        browserSub = text(17f, MUTED)
        browser.addView(browserSub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(8); leftMargin = dp(12) })
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
                if (rows.isNotEmpty()) itemList.requestFocus()
                true
            }
        }
        browser.addView(browserSearch, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        catList = styledList().apply {
            adapter = catAdapter
            setOnItemClickListener { _, _, position, _ -> onCategory(position) }
        }
        body.addView(catList, LinearLayout.LayoutParams(dp(300), -1).apply { rightMargin = dp(18) })
        val right = FrameLayout(this)
        itemList = styledList().apply {
            adapter = itemAdapter
            setOnItemClickListener { _, _, position, _ -> onItem(position) }
        }
        right.addView(itemList, FrameLayout.LayoutParams(-1, -1))
        browserMessage = text(20f, MUTED).apply {
            maxLines = 4
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        right.addView(browserMessage, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        body.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        browser.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        browser.addView(text(13f, HINT).apply {
            this.text = "OK: abrir  ·  ◀ ▶: categorías / títulos  ·  ▲ arriba: pestañas, buscar y filtro  ·  Atrás: volver"
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        root.addView(browser, FrameLayout.LayoutParams(-1, -1))

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

    // ---------------------------------------------------------------- adapters

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

    private inner class CategoryAdapter : BaseAdapter() {
        override fun getCount() = visibleCats.size + if (hasContinueRow) 1 else 0
        override fun getItem(position: Int): Any? = null
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tv = (convertView as? TextView) ?: text(19f).apply { setPadding(dp(12), dp(10), dp(12), dp(10)) }
            tv.text = if (hasContinueRow && position == 0) "▶ Seguir viendo"
            else visibleCats.getOrNull(position - if (hasContinueRow) 1 else 0)?.name.orEmpty()
            return tv
        }
    }

    private inner class ItemAdapter : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int): Any? = rows.getOrNull(position)
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? LinearLayout) ?: makeRow(46, 66)
            when (val r = rows[position]) {
                is VodItem -> {
                    val seen = !r.series && history.watched("m:" + r.id)
                    val partial = if (r.series) null else history.get("m:" + r.id)?.takeIf { !it.finished && it.position > 30_000 }
                    val sub = listOfNotNull(
                        r.year.ifEmpty { null },
                        r.rating.ifEmpty { null }?.let { "★ $it" },
                        if (seen) "Vista" else null,
                        partial?.let { "Quedaste en ${clock(it.position)}" },
                    ).joinToString("   ·   ")
                    fillRow(row, "", r.poster, r.title, sub)
                }
                is Episode -> {
                    val w = history.get("e:" + r.id)
                    val sub = when {
                        w == null -> ""
                        w.finished -> "Visto"
                        w.position > 30_000 -> "Quedaste en ${clock(w.position)}"
                        else -> ""
                    }
                    fillRow(row, r.number.toString(), r.image, "T${r.season} · ${r.title}", sub)
                }
                is Watch -> fillRow(row, "", r.poster, r.title,
                    listOf(r.subtitle, "Quedaste en ${clock(r.position)}").filter { it.isNotEmpty() }.joinToString("   ·   "))
            }
            return row
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
