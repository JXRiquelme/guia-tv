package cl.jxr.aeriolite

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
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

class MainActivity : Activity() {

    companion object {
        const val GUIDE_URL = "https://raw.githubusercontent.com/JXRiquelme/guia-tv/main/guia.xml"
        const val START_CHANNEL = "Canal 13"
        const val DEFAULT_SERVER = "https://reborned.cc:8443"
        const val GUIDE_MAX_AGE = 3 * 3600_000L

        // Extra rows of the channel panel
        const val ROW_MOVIES = 0
        const val ROW_SERIES = 1
        const val CH_OFFSET = 2
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

    // Views
    private lateinit var root: FrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var status: TextView
    private lateinit var info: LinearLayout
    private lateinit var infoLogo: ImageView
    private lateinit var infoTitle: TextView
    private lateinit var infoNow: TextView
    private lateinit var infoNext: TextView
    private lateinit var infoProgress: ProgressBar
    private lateinit var panel: LinearLayout
    private lateinit var panelClock: TextView
    private lateinit var list: ListView
    private lateinit var numberView: TextView
    private lateinit var setup: LinearLayout
    private lateinit var setupInput: EditText
    private lateinit var setupMessage: TextView
    private lateinit var account: LinearLayout
    private lateinit var accServer: EditText
    private lateinit var accUser: EditText
    private lateinit var accPass: EditText
    private lateinit var accMessage: TextView
    private lateinit var vodBar: LinearLayout
    private lateinit var vodTitle: TextView
    private lateinit var vodSub: TextView
    private lateinit var vodProgress: ProgressBar
    private lateinit var vodTime: TextView
    private val adapter = ChannelAdapter()

    // Movies / series browser
    private lateinit var browser: LinearLayout
    private lateinit var browserTitle: TextView
    private lateinit var catList: ListView
    private lateinit var itemList: ListView
    private lateinit var browserMessage: TextView
    private val catAdapter = CategoryAdapter()
    private val itemAdapter = ItemAdapter()
    private var browserSeries = false
    private var pendingSeries = false
    private val categoryCache = HashMap<Boolean, List<Category>>()
    private val itemCache = HashMap<String, List<VodItem>>()
    private var cats: List<Category> = emptyList()
    private var hasContinueRow = false
    private var rows: List<Any> = emptyList()
    private var openSeries: VodItem? = null
    private var seriesBackRows: List<Any> = emptyList()
    private var seriesBackSelection = 0
    private var episodes: List<Episode> = emptyList()
    private var loadToken = 0

    private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("lite", Context.MODE_PRIVATE)
        logos = Logos(this)
        history = History(prefs)
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
            if (panel.visibility == View.VISIBLE) {
                panelClock.text = hm.format(Date())
                adapter.notifyDataSetChanged()
            }
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
        adapter.notifyDataSetChanged()
        refreshGuide(false)
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
                if (playing == null) {
                    hideStatus()
                    if (vod == null && browser.visibility != View.VISIBLE) play(startIndex()) else current = startIndex()
                } else {
                    val same = result.indexOfFirst { it.url == playing.url }
                        .takeIf { it >= 0 } ?: result.indexOfFirst { it.name == playing.name }
                    if (same >= 0) current = same
                    else if (vod == null && browser.visibility != View.VISIBLE) play(startIndex())
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
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        p.setVideoSurfaceView(surface)
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> showStatus("Cargando…")
                    Player.STATE_READY -> {
                        retries = 0
                        hideStatus()
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
        showInfo()
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

    private fun zap(step: Int) {
        if (channels.isEmpty()) return
        play((current + step + channels.size) % channels.size)
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
        browser.visibility = View.GONE
        panel.visibility = View.GONE
        info.visibility = View.GONE
        vod = w
        retries = 0
        val url = if (w.episode) acc.episodeUrl(w.id, w.ext) else acc.movieUrl(w.id, w.ext)
        val item = MediaItem.Builder().setUri(url)
        if (w.ext.equals("m3u8", true)) item.setMimeType(MimeTypes.APPLICATION_M3U8)
        val p = ensurePlayer()
        p.setMediaItem(item.build())
        val saved = history.get(w.key)
        val resume = saved != null && !saved.finished && saved.position > 30_000
        if (resume) p.seekTo(saved!!.position)
        p.prepare()
        p.playWhenReady = true
        if (resume) Toast.makeText(this, "Continuando desde ${clock(saved!!.position)}", Toast.LENGTH_LONG).show()
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

    // ---------------------------------------------------------------- movies & series browser

    private fun openBrowser(series: Boolean) {
        if (xtream() == null) {
            pendingSeries = series
            showAccount(null)
            return
        }
        panel.visibility = View.GONE
        info.visibility = View.GONE
        stopPlayback()
        if (browserSeries != series || cats.isEmpty()) {
            browserSeries = series
            openSeries = null
            rows = emptyList()
            cats = emptyList()
        }
        showBrowser()
        if (cats.isEmpty()) loadCategories() else catList.requestFocus()
    }

    private fun showBrowser() {
        browserTitle.text = when {
            openSeries != null -> "Series  ›  ${openSeries!!.title}"
            browserSeries -> "Series"
            else -> "Películas"
        }
        hasContinueRow = history.continueWatching(browserSeries).isNotEmpty()
        catAdapter.notifyDataSetChanged()
        browser.visibility = View.VISIBLE
    }

    private fun closeBrowser() {
        browser.visibility = View.GONE
        browserMessage.visibility = View.GONE
        root.requestFocus()
        if (channels.isNotEmpty()) play(current)
    }

    private fun browserStatus(text: String?) {
        browserMessage.text = text.orEmpty()
        browserMessage.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun loadCategories() {
        val acc = xtream() ?: return
        val series = browserSeries
        categoryCache[series]?.let {
            cats = it
            catAdapter.notifyDataSetChanged()
            catList.requestFocus()
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
                    catAdapter.notifyDataSetChanged()
                    browserStatus(if (it.isEmpty()) "El proveedor no tiene categorías aquí." else "Elige una categoría.")
                    catList.requestFocus()
                    catList.setSelection(0)
                }.onFailure {
                    browserStatus("No se pudo cargar: ${it.message}\nOK en una categoría para reintentar.")
                }
            }
        }
    }

    private fun onCategory(position: Int) {
        openSeries = null
        if (hasContinueRow && position == 0) {
            setRows(history.continueWatching(browserSeries))
            browserStatus(null)
            showBrowser()
            itemList.requestFocus()
            return
        }
        if (cats.isEmpty()) {
            loadCategories()
            return
        }
        val cat = cats.getOrNull(position - if (hasContinueRow) 1 else 0) ?: return
        val acc = xtream() ?: return
        val series = browserSeries
        val key = "$series:${cat.id}"
        showBrowser()
        itemCache[key]?.let {
            setRows(it)
            browserStatus(if (it.isEmpty()) "Categoría vacía." else null)
            itemList.requestFocus()
            return
        }
        val token = ++loadToken
        setRows(emptyList())
        browserStatus("Cargando ${cat.name}…")
        io.execute {
            val result = runCatching { Xtream.items(acc, series, cat.id) }
            main.post {
                if (token != loadToken) return@post
                result.onSuccess {
                    itemCache[key] = it
                    setRows(it)
                    browserStatus(if (it.isEmpty()) "Categoría vacía." else null)
                    if (it.isNotEmpty()) itemList.requestFocus()
                }.onFailure { browserStatus("No se pudo cargar: ${it.message}") }
            }
        }
    }

    private fun setRows(newRows: List<Any>) {
        rows = newRows
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
        when (val row = rows.getOrNull(position)) {
            is Watch -> {
                if (row.episode && row.seriesId.isNotEmpty() && openSeries?.id != row.seriesId) {
                    // Load the episode list in the background so "next episode" works.
                    loadEpisodes(VodItem(true, row.seriesId, row.title, row.poster, row.ext, "", ""), playAfter = row)
                } else {
                    playVod(row)
                }
            }
            is Episode -> openSeries?.let { playVod(episodeWatch(it, row)) }
            is VodItem -> if (row.series) {
                seriesBackRows = rows
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
            showBrowser()
            setRows(emptyList())
            browserStatus("Cargando episodios…")
        }
        io.execute {
            val result = runCatching { Xtream.episodes(acc, series.id) }
            main.post {
                if (token != loadToken) return@post
                if (playAfter != null) {
                    // Coming from "Seguir viendo": remember the series, then play.
                    seriesBackRows = rows
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
                    setRows(it)
                    browserStatus(if (it.isEmpty()) "Esta serie no tiene episodios." else null)
                    val resumeIdx = it.indexOfFirst { e -> history.get("e:" + e.id)?.let { w -> !w.finished } == true }
                    if (resumeIdx >= 0) itemList.setSelection(resumeIdx)
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
                itemAdapter.notifyDataSetChanged()
                itemList.setSelection(seriesBackSelection)
                showBrowser()
                itemList.requestFocus()
            }
            itemList.hasFocus() -> catList.requestFocus()
            else -> {
                loadToken++
                closeBrowser()
            }
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (setup.visibility == View.VISIBLE || panel.visibility == View.VISIBLE ||
            account.visibility == View.VISIBLE || browser.visibility == View.VISIBLE
        ) return super.onKeyDown(keyCode, event)
        if (vod != null) return vodKey(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> zap(1)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> zap(-1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_TV_INPUT -> showPanel()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> showInfo()
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
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                p.playWhenReady = !p.playWhenReady
                if (!p.playWhenReady) saveVodProgress()
                showVodBar()
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { p.playWhenReady = true; showVodBar() }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { p.playWhenReady = false; saveVodProgress(); showVodBar() }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> seekBy(-step)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seekBy(step)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MENU -> showVodBar()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private fun onBack() {
        when {
            account.visibility == View.VISIBLE -> {
                account.visibility = View.GONE
                if (browser.visibility != View.VISIBLE && vod == null) {
                    if (player == null || player?.isPlaying != true) play(current)
                    root.requestFocus()
                }
            }
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

    // ---------------------------------------------------------------- touch (phones / tablets)

    private fun overlayOpen() = setup.visibility == View.VISIBLE || account.visibility == View.VISIBLE ||
        browser.visibility == View.VISIBLE

    private fun onTap() {
        when {
            overlayOpen() -> {}
            panel.visibility == View.VISIBLE -> hidePanel()
            vod != null -> {
                val p = player
                if (vodBar.visibility == View.VISIBLE && p != null) {
                    p.playWhenReady = !p.playWhenReady
                    if (!p.playWhenReady) saveVodProgress()
                }
                showVodBar()
            }
            else -> showPanel()
        }
    }

    /** Horizontal swipe: live -> change channel (left = next), movie -> seek 30 s. */
    private fun onSwipe(toLeft: Boolean) {
        if (overlayOpen() || panel.visibility == View.VISIBLE) return
        if (vod != null) seekBy(if (toLeft) 30_000L else -30_000L) else zap(if (toLeft) 1 else -1)
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

    // ---------------------------------------------------------------- overlays

    private val hideInfo = Runnable { info.visibility = View.GONE }

    private fun showInfo() {
        if (channels.isEmpty()) return
        updateInfo()
        info.visibility = View.VISIBLE
        main.removeCallbacks(hideInfo)
        main.postDelayed(hideInfo, 5000)
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
        main.removeCallbacks(hideInfo)
        info.visibility = View.GONE
        panelClock.text = hm.format(Date())
        adapter.notifyDataSetChanged()
        panel.visibility = View.VISIBLE
        list.requestFocus()
        list.setSelectionFromTop(current + CH_OFFSET, dp(140))
    }

    private fun hidePanel() {
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
        else "Para ver películas y series escribe tu cuenta Xtream (se guarda solo en esta TV)."
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
        cats = emptyList()
        account.visibility = View.GONE
        openBrowser(pendingSeries)
    }

    // ---------------------------------------------------------------- UI

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

    private fun input(hint: String, password: Boolean = false) = EditText(this).apply {
        setTextColor(Color.WHITE)
        setHintTextColor(0xFF8FA0CC.toInt())
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
        selector = box(0xFF2F6FEB.toInt(), 8)
        isVerticalScrollBarEnabled = false
        itemsCanFocus = false
        isFocusable = true
    }

    private fun buildUi() {
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitVideo() }
        }
        surface = SurfaceView(this)
        root.addView(surface, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        val minSwipe = dp(80)
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onTap()
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val start = e1 ?: return false
                val dx = e2.x - start.x
                val dy = e2.y - start.y
                if (Math.abs(dx) < minSwipe || Math.abs(dx) < Math.abs(dy) * 1.5f) return false
                onSwipe(dx < 0)
                return true
            }
        })
        root.setOnTouchListener { _, e -> gestures.onTouchEvent(e); true }

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
        infoNext = text(16f, 0xFFB8C7F0.toInt())
        infoProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        col.addView(infoTitle)
        col.addView(infoNow)
        col.addView(infoProgress, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(4); bottomMargin = dp(4) })
        col.addView(infoNext)
        info.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(info, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dp(40); rightMargin = dp(40); bottomMargin = dp(28)
        })

        // Bottom bar (movies / series)
        vodBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(14), dp(22), dp(14))
            background = box(0xE60A1E5A.toInt(), 14)
            visibility = View.GONE
        }
        vodTitle = text(24f, bold = true)
        vodSub = text(17f, 0xFFB8C7F0.toInt())
        vodProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        vodTime = text(17f)
        vodBar.addView(vodTitle)
        vodBar.addView(vodSub)
        vodBar.addView(vodProgress, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(8); bottomMargin = dp(6) })
        val timeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        timeRow.addView(vodTime, LinearLayout.LayoutParams(0, -2, 1f))
        timeRow.addView(text(14f, 0xFF8FA0CC.toInt()).apply { this.text = "OK pausa  ·  ◀ ▶ retrocede/adelanta  ·  Atrás vuelve" })
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

        // Channel list panel
        panel = LinearLayout(this).apply {
            isClickable = true
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF00A1238.toInt())
            setPadding(dp(16), dp(18), dp(16), dp(10))
            visibility = View.GONE
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(text(24f, bold = true).apply { this.text = "Canales" }, LinearLayout.LayoutParams(0, -2, 1f))
        panelClock = text(20f, 0xFFB8C7F0.toInt())
        header.addView(panelClock)
        panel.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        list = styledList().apply {
            adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, position, _ ->
                when {
                    position == ROW_MOVIES -> openBrowser(false)
                    position == ROW_SERIES -> openBrowser(true)
                    position - CH_OFFSET in channels.indices -> {
                        hidePanel()
                        play(position - CH_OFFSET)
                    }
                    position == channels.size + CH_OFFSET -> {
                        pendingSeries = false
                        showAccount(null)
                    }
                    else -> showSetup(null)
                }
            }
        }
        panel.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        panel.addView(text(13f, 0xFF8FA0CC.toInt()).apply {
            this.text = "OK: ver   ·   Atrás: cerrar   ·   En pantalla: ▲▼ cambia canal, números para ir directo"
        })
        root.addView(panel, FrameLayout.LayoutParams(dp(470), -1, Gravity.START))

        // Movies / series browser
        browser = LinearLayout(this).apply {
            isClickable = true
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFA081030.toInt())
            setPadding(dp(32), dp(22), dp(32), dp(14))
            visibility = View.GONE
        }
        browserTitle = text(28f, bold = true)
        browser.addView(browserTitle, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        catList = styledList().apply {
            adapter = catAdapter
            setOnItemClickListener { _, _, position, _ -> onCategory(position) }
        }
        body.addView(catList, LinearLayout.LayoutParams(dp(320), -1).apply { rightMargin = dp(20) })
        val right = FrameLayout(this)
        itemList = styledList().apply {
            adapter = itemAdapter
            setOnItemClickListener { _, _, position, _ -> onItem(position) }
        }
        right.addView(itemList, FrameLayout.LayoutParams(-1, -1))
        browserMessage = text(20f, 0xFFB8C7F0.toInt()).apply {
            maxLines = 4
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        right.addView(browserMessage, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        body.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        browser.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        browser.addView(text(13f, 0xFF8FA0CC.toInt()).apply {
            this.text = "OK: abrir   ·   ◀ ▶: categorías / títulos   ·   Atrás: volver"
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        root.addView(browser, FrameLayout.LayoutParams(-1, -1))

        numberView = text(48f, bold = true).apply {
            setPadding(dp(18), dp(6), dp(18), dp(6))
            background = box(0xCC000000.toInt())
            visibility = View.GONE
        }
        root.addView(numberView, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(30); rightMargin = dp(40)
        })

        // Playlist setup screen
        setup = LinearLayout(this).apply {
            isClickable = true
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(36), dp(28), dp(36), dp(28))
            background = box(0xFF0A1E5A.toInt(), 16)
            visibility = View.GONE
        }
        setup.addView(text(30f, bold = true).apply { this.text = "AerioTV Lite" })
        setupMessage = text(18f, 0xFFB8C7F0.toInt()).apply { maxLines = 3; gravity = Gravity.CENTER }
        setup.addView(setupMessage, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10); bottomMargin = dp(16) })
        setupInput = input("https://…").apply {
            textSize = 22f
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
            isClickable = true
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(36), dp(28), dp(36), dp(28))
            background = box(0xFF0A1E5A.toInt(), 16)
            visibility = View.GONE
        }
        account.addView(text(28f, bold = true).apply { this.text = "Películas y series" })
        accMessage = text(17f, 0xFFB8C7F0.toInt()).apply { maxLines = 3; gravity = Gravity.CENTER }
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
        addView(text(18f, 0xFF8FA0CC.toInt()).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(dp(40), -2).apply { rightMargin = dp(12) })
        addView(ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(dp(logoW), dp(logoH)).apply { rightMargin = dp(12) })
        val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(19f, bold = true))
        col.addView(text(15f, 0xFFB8C7F0.toInt()))
        addView(col, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun fillRow(row: LinearLayout, number: String, logo: String, title: String, sub: String, highlight: Boolean = false) {
        (row.getChildAt(0) as TextView).text = number
        logos.load(logo, row.getChildAt(1) as ImageView)
        val col = row.getChildAt(2) as LinearLayout
        (col.getChildAt(0) as TextView).apply {
            text = title
            setTextColor(if (highlight) 0xFF7FD3FF.toInt() else Color.WHITE)
        }
        (col.getChildAt(1) as TextView).apply {
            text = sub
            visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private inner class ChannelAdapter : BaseAdapter() {
        override fun getCount() = if (channels.isEmpty()) 0 else channels.size + CH_OFFSET + 2
        override fun getItem(position: Int): Any? = channels.getOrNull(position - CH_OFFSET)
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? LinearLayout) ?: makeRow(72, 40)
            when {
                position == ROW_MOVIES -> fillRow(row, "▶", "", "Películas", "Catálogo del proveedor")
                position == ROW_SERIES -> fillRow(row, "▶", "", "Series", "Catálogo del proveedor")
                position - CH_OFFSET in channels.indices -> {
                    val index = position - CH_OFFSET
                    val ch = channels[index]
                    val program = Guide.nowNext(guide[ch.tvgId], System.currentTimeMillis()).first
                    fillRow(row, ch.number.toString(), ch.logo, ch.name, program?.title.orEmpty(), index == current)
                }
                position == channels.size + CH_OFFSET -> fillRow(row, "⚙", "", "Cuenta de películas y series", "")
                else -> fillRow(row, "⚙", "", "Cambiar dirección de la lista", "")
            }
            return row
        }
    }

    private inner class CategoryAdapter : BaseAdapter() {
        override fun getCount() = cats.size + if (hasContinueRow) 1 else 0
        override fun getItem(position: Int): Any? = null
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tv = (convertView as? TextView) ?: text(19f).apply { setPadding(dp(12), dp(10), dp(12), dp(10)) }
            tv.text = if (hasContinueRow && position == 0) "▶ Seguir viendo"
            else cats.getOrNull(position - if (hasContinueRow) 1 else 0)?.name.orEmpty()
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
}
