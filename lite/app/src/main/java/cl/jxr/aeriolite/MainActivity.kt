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
import android.view.Gravity
import android.view.KeyEvent
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
        const val GUIDE_MAX_AGE = 3 * 3600_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var prefs: SharedPreferences
    private lateinit var logos: Logos

    private var channels: List<Channel> = emptyList()
    private var guide: Map<String, List<Program>> = emptyMap()
    private var current = 0
    private var player: ExoPlayer? = null
    private var retries = 0
    private var lastBack = 0L
    private var digits = ""
    private var videoW = 0
    private var videoH = 0
    private var videoRatio = 1f

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
    private val adapter = ChannelAdapter()

    private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences("lite", Context.MODE_PRIVATE)
        logos = Logos(this)
        buildUi()
        val url = prefs.getString("list", "").orEmpty()
        if (url.isEmpty()) showSetup(null) else start()
        main.postDelayed(ticker, 30_000)
    }

    override fun onStart() {
        super.onStart()
        if (player == null && channels.isNotEmpty() && setup.visibility != View.VISIBLE) play(current)
    }

    override fun onStop() {
        super.onStop()
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

    // ---------------------------------------------------------------- data

    private fun start() {
        hideSetup()
        val cached = File(filesDir, "list.m3u")
        val fromCache = if (cached.exists()) Playlist.parse(cached.readText()) else emptyList()
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
            val result = try {
                Net.download(url, file)
                Playlist.parse(file.readText())
            } catch (e: Exception) {
                main.post {
                    if (!silent) showSetup("No se pudo descargar la lista (${e.message}). Revisa la dirección o la conexión.")
                }
                return@execute
            }
            main.post {
                if (result.isEmpty()) {
                    if (!silent) showSetup("La dirección no contiene una lista M3U válida.")
                    return@post
                }
                val playing = channels.getOrNull(current)
                val changed = result != channels
                if (!changed) return@post
                setChannels(result)
                if (playing == null) {
                    hideStatus()
                    play(startIndex())
                } else {
                    // Keep watching the same channel after the list changes.
                    val same = result.indexOfFirst { it.url == playing.url }
                        .takeIf { it >= 0 } ?: result.indexOfFirst { it.name == playing.name }
                    if (same >= 0) current = same else play(startIndex())
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
                    Player.STATE_BUFFERING -> showStatus("Cargando…")
                    Player.STATE_READY -> {
                        retries = 0
                        hideStatus()
                    }
                    Player.STATE_ENDED -> retry()
                    else -> {}
                }
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
            main.postDelayed({
                if (player === p && current == index) {
                    p.prepare()
                    p.playWhenReady = true
                }
            }, 2000L * retries)
        } else {
            showStatus("No se pudo reproducir ${channels.getOrNull(current)?.name ?: "el canal"}.\nOK para ver los canales.")
        }
    }

    private fun releasePlayer() {
        player?.release()
        player = null
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

    // ---------------------------------------------------------------- keys

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
            return onBack()
        }
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (setup.visibility == View.VISIBLE || panel.visibility == View.VISIBLE) return super.onKeyDown(keyCode, event)
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

    private fun onBack(): Boolean {
        when {
            panel.visibility == View.VISIBLE -> hidePanel()
            setup.visibility == View.VISIBLE && channels.isNotEmpty() -> {
                hideSetup()
                if (player == null) play(current)
            }
            info.visibility == View.VISIBLE -> info.visibility = View.GONE
            System.currentTimeMillis() - lastBack < 2500 -> finish()
            else -> {
                lastBack = System.currentTimeMillis()
                Toast.makeText(this, "Presiona Atrás otra vez para salir", Toast.LENGTH_SHORT).show()
            }
        }
        return true
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
        list.setSelectionFromTop(current, dp(140))
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

    private fun buildUi() {
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitVideo() }
        }
        surface = SurfaceView(this)
        root.addView(surface, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))

        status = text(22f).apply {
            maxLines = 3
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(14), dp(24), dp(14))
            background = box(0xCC000000.toInt())
            visibility = View.GONE
        }
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        // Bottom info banner
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

        // Channel list panel
        panel = LinearLayout(this).apply {
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
        list = ListView(this).apply {
            adapter = this@MainActivity.adapter
            divider = ColorDrawable(0x22FFFFFF)
            dividerHeight = 1
            selector = box(0xFF2F6FEB.toInt(), 8)
            isVerticalScrollBarEnabled = false
            itemsCanFocus = false
            setOnItemClickListener { _, _, position, _ ->
                if (position >= channels.size) {
                    showSetup(null)
                } else {
                    hidePanel()
                    play(position)
                }
            }
        }
        panel.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        panel.addView(text(13f, 0xFF8FA0CC.toInt()).apply {
            this.text = "OK: ver   ·   Atrás: cerrar   ·   En pantalla: ▲▼ cambia canal, números para ir directo"
        })
        root.addView(panel, FrameLayout.LayoutParams(dp(470), -1, Gravity.START))

        numberView = text(48f, bold = true).apply {
            setPadding(dp(18), dp(6), dp(18), dp(6))
            background = box(0xCC000000.toInt())
            visibility = View.GONE
        }
        root.addView(numberView, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(30); rightMargin = dp(40)
        })

        // Setup screen
        setup = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(36), dp(28), dp(36), dp(28))
            background = box(0xFF0A1E5A.toInt(), 16)
            visibility = View.GONE
        }
        setup.addView(text(30f, bold = true).apply { this.text = "AerioTV Lite" })
        setupMessage = text(18f, 0xFFB8C7F0.toInt()).apply { maxLines = 3; gravity = Gravity.CENTER }
        setup.addView(setupMessage, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10); bottomMargin = dp(16) })
        setupInput = EditText(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, _, _ -> saveSetup(); true }
        }
        setup.addView(setupInput, LinearLayout.LayoutParams(dp(560), -2))
        setup.addView(Button(this).apply {
            this.text = "Guardar y conectar"
            setOnClickListener { saveSetup() }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
        root.addView(setup, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        setContentView(root)
        root.requestFocus()
    }

    private inner class ChannelAdapter : BaseAdapter() {
        override fun getCount() = if (channels.isEmpty()) 0 else channels.size + 1
        override fun getItem(position: Int): Any? = channels.getOrNull(position)
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val row = (convertView as? LinearLayout) ?: makeRow()
            val number = row.getChildAt(0) as TextView
            val logo = row.getChildAt(1) as ImageView
            val col = row.getChildAt(2) as LinearLayout
            val name = col.getChildAt(0) as TextView
            val now = col.getChildAt(1) as TextView
            if (position >= channels.size) {
                number.text = "⚙"
                logos.load("", logo)
                name.text = "Cambiar dirección de la lista"
                now.text = ""
                now.visibility = View.GONE
                return row
            }
            val ch = channels[position]
            number.text = ch.number.toString()
            logos.load(ch.logo, logo)
            name.text = ch.name
            name.setTextColor(if (position == current) 0xFF7FD3FF.toInt() else Color.WHITE)
            val program = Guide.nowNext(guide[ch.tvgId], System.currentTimeMillis()).first
            now.text = program?.title ?: ""
            now.visibility = if (program == null) View.GONE else View.VISIBLE
            return row
        }

        private fun makeRow(): LinearLayout = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(text(18f, 0xFF8FA0CC.toInt()).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(dp(40), -2).apply { rightMargin = dp(12) })
            addView(ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(dp(72), dp(40)).apply { rightMargin = dp(12) })
            val col = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
            col.addView(text(19f, bold = true))
            col.addView(text(15f, 0xFFB8C7F0.toInt()))
            addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }
}
