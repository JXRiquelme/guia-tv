package cl.jxr.aeriolite

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.text.TextPaint
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Program guide grid drawn on a single canvas (no view per program), so it stays
 * light on old TVs. Rows = channels, columns = time.
 */
@SuppressLint("ViewConstructor")
class EpgView(context: Context, private val host: Host) : View(context) {

    interface Host {
        fun programs(channel: Channel): List<Program>?
        fun logo(url: String): Bitmap?
        fun onSelected(channel: Channel, program: Program?)
        fun onPlay(channel: Channel)

        /** Touch dragging down from the top of the grid (to close the guide). */
        fun onPullDown(e: MotionEvent): Boolean
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private val rulerH = dp(30f)
    private val rowH = dp(54f)
    private var chanW = dp(210f)

    /** Narrower channel column (phone held upright). */
    fun setCompact(compact: Boolean) {
        val w = dp(if (compact) 150f else 210f)
        if (w != chanW) { chanW = w; invalidate() }
    }
    private val pxPerMin = dp(4.4f)
    private val gap = dp(2f)
    private val radius = dp(6f)

    // Phone app: translucent "glass" cells over the blurred video; TV app: solid colours (faster)
    private val glass = !BuildConfig.TV
    private val bgPaint = Paint().apply { color = if (glass) Color.TRANSPARENT else 0xFF000000.toInt() }
    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (glass) 0x1FFFFFFF else 0xFF17181B.toInt() }
    private val nowCellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (glass) 0x33FFFFFF else 0xFF26282D.toInt() }
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (glass) 0x66FFFFFF else 0xFF55585F.toInt() }
    private val chanPaint = Paint().apply { color = if (glass) 0x14FFFFFF else 0xFF0E0F11.toInt() }
    private val chanSelPaint = Paint().apply { color = if (glass) 0x38FFFFFF else 0xFF34363C.toInt() }
    private val linePaint = Paint().apply { color = 0xFFFF5A5A.toInt(); strokeWidth = dp(2f) }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = dp(15f); isFakeBoldText = true }
    private val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = if (!BuildConfig.TV) 0xFFD0D3DA.toInt() else 0xFFD0D3DA.toInt(); textSize = dp(12f) }
    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = dp(15f); isFakeBoldText = true }
    private val numPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = if (!BuildConfig.TV) 0xFFA3A8B3.toInt() else 0xFFA3A8B3.toInt(); textSize = dp(13f) }
    private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val rect = RectF()

    var rows: List<Channel> = emptyList()
        private set
    private var row = 0
    private var cursor = 0L          // selected time
    private var windowStart = 0L     // time at the left edge of the grid
    private var scrollY = 0f         // pixel offset of the rows

    fun setData(channels: List<Channel>, currentIndex: Int) {
        rows = channels
        row = currentIndex.coerceIn(0, max(0, channels.size - 1))
        val now = System.currentTimeMillis()
        cursor = now
        windowStart = floorHalfHour(now) - 30 * 60_000L
        scrollY = 0f
        post { ensureRowVisible(center = true); notifySelection(); invalidate() }
    }

    private fun floorHalfHour(t: Long) = t - t % (30 * 60_000L)

    private val gridW get() = width - chanW
    private val spanMs get() = (gridW / pxPerMin * 60_000).toLong()
    private fun x(t: Long) = chanW + (t - windowStart) / 60_000f * pxPerMin
    private fun visibleRows() = ((height - rulerH) / rowH).toInt().coerceAtLeast(1)

    private fun programAt(r: Int, t: Long): Program? =
        rows.getOrNull(r)?.let { host.programs(it) }?.firstOrNull { it.start <= t && it.stop > t }

    private fun notifySelection() {
        val ch = rows.getOrNull(row) ?: return
        host.onSelected(ch, programAt(row, cursor))
    }

    private fun ensureRowVisible(center: Boolean = false) {
        val maxScroll = max(0f, rows.size * rowH - (height - rulerH))
        val y = row * rowH
        if (center) scrollY = y - (height - rulerH) / 2 + rowH / 2
        else if (y < scrollY) scrollY = y
        else if (y + rowH > scrollY + (height - rulerH)) scrollY = y + rowH - (height - rulerH)
        scrollY = scrollY.coerceIn(0f, maxScroll)
    }

    private fun ensureTimeVisible() {
        val span = spanMs
        if (cursor < windowStart) windowStart = floorHalfHour(cursor)
        else if (cursor > windowStart + span - 15 * 60_000L) windowStart = floorHalfHour(cursor - span / 3)
    }

    // ------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        if (rows.isEmpty()) return
        val now = System.currentTimeMillis()
        val windowEnd = windowStart + spanMs

        // time ruler
        var t = floorHalfHour(windowStart)
        while (t < windowEnd) {
            val xx = x(t)
            if (xx >= chanW) canvas.drawText(hm.format(Date(t)), xx + dp(4f), rulerH - dp(9f), timePaint)
            t += 30 * 60_000L
        }

        canvas.save()
        canvas.clipRect(0f, rulerH, width.toFloat(), height.toFloat())
        val first = (scrollY / rowH).toInt().coerceAtLeast(0)
        val last = min(rows.size - 1, first + visibleRows() + 1)
        for (r in first..last) {
            val top = rulerH + r * rowH - scrollY
            val ch = rows[r]
            // channel column
            canvas.drawRect(0f, top, chanW - gap, top + rowH - gap, if (r == row) chanSelPaint else chanPaint)
            canvas.drawText(ch.number.toString(), dp(8f), top + rowH / 2 + dp(5f), numPaint)
            var nameX = dp(40f)
            host.logo(ch.logo)?.let { bmp ->
                val lh = rowH - dp(18f)
                val lw = min(dp(52f), lh * bmp.width / max(1, bmp.height))
                rect.set(nameX, top + (rowH - gap - lh) / 2, nameX + lw, top + (rowH - gap + lh) / 2)
                canvas.drawBitmap(bmp, null, rect, null)
                nameX += dp(58f)
            }
            val name = TextUtils.ellipsize(ch.name, namePaint, chanW - nameX - dp(8f), TextUtils.TruncateAt.END)
            canvas.drawText(name, 0, name.length, nameX, top + rowH / 2 + dp(5f), namePaint)

            // programs
            canvas.save()
            canvas.clipRect(chanW, top, width.toFloat(), top + rowH)
            val list = host.programs(ch)
            val visible = list?.filter { it.stop > windowStart && it.start < windowEnd }.orEmpty()
            if (visible.isEmpty()) {
                rect.set(chanW, top, width.toFloat() - gap, top + rowH - gap)
                canvas.drawRoundRect(rect, radius, radius, if (r == row) selPaint else cellPaint)
                canvas.drawText("Sin información de guía", chanW + dp(10f), top + rowH / 2 + dp(5f), timePaint)
            } else {
                for (p in visible) {
                    val x1 = max(chanW, x(p.start))
                    val x2 = min(width.toFloat(), x(p.stop)) - gap
                    if (x2 <= x1) continue
                    rect.set(x1, top, x2, top + rowH - gap)
                    val selected = r == row && p.start <= cursor && p.stop > cursor
                    val airing = p.start <= now && p.stop > now
                    canvas.drawRoundRect(rect, radius, radius, if (selected) selPaint else if (airing) nowCellPaint else cellPaint)
                    val tx = x1 + dp(8f)
                    val avail = x2 - tx - dp(6f)
                    if (avail > dp(12f)) {
                        val title = TextUtils.ellipsize(p.title, titlePaint, avail, TextUtils.TruncateAt.END)
                        canvas.drawText(title, 0, title.length, tx, top + dp(22f), titlePaint)
                        val time = TextUtils.ellipsize(hm.format(Date(p.start)) + " – " + hm.format(Date(p.stop)), timePaint, avail, TextUtils.TruncateAt.END)
                        canvas.drawText(time, 0, time.length, tx, top + dp(40f), timePaint)
                    }
                }
            }
            canvas.restore()
        }
        canvas.restore()

        // "now" line
        val nx = x(now)
        if (nx > chanW && nx < width) canvas.drawLine(nx, rulerH - dp(6f), nx, height.toFloat(), linePaint)
    }

    // ------------------------------------------------------------ remote control

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (rows.isEmpty()) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> moveRow(-1)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> moveRow(1)
            KeyEvent.KEYCODE_PAGE_UP -> moveRow(-visibleRows())
            KeyEvent.KEYCODE_PAGE_DOWN -> moveRow(visibleRows())
            KeyEvent.KEYCODE_DPAD_LEFT -> moveTime(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveTime(1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                rows.getOrNull(row)?.let { host.onPlay(it) }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private fun moveRow(step: Int) {
        row = (row + step).coerceIn(0, rows.size - 1)
        ensureRowVisible()
        notifySelection()
        invalidate()
    }

    /** Jump to the previous / next program of the selected row (30 min when there is no guide). */
    private fun moveTime(dir: Int) {
        val list = rows.getOrNull(row)?.let { host.programs(it) }
        val cur = programAt(row, cursor)
        val now = System.currentTimeMillis()
        cursor = if (dir > 0) {
            val next = list?.firstOrNull { it.start >= (cur?.stop ?: (cursor + 1)) }
            next?.start ?: (cursor + 30 * 60_000L)
        } else {
            val prev = list?.lastOrNull { it.stop <= (cur?.start ?: cursor) }
            prev?.start ?: (cursor - 30 * 60_000L)
        }
        cursor = cursor.coerceIn(now - 2 * 3600_000L, now + 36 * 3600_000L)
        if (dir > 0) cursor += 1 // inside the program, not at its edge
        ensureTimeVisible()
        notifySelection()
        invalidate()
    }

    // ------------------------------------------------------------ touch

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            val maxScroll = max(0f, rows.size * rowH - (height - rulerH))
            scrollY = (scrollY + dy).coerceIn(0f, maxScroll)
            val now = System.currentTimeMillis()
            windowStart = (windowStart + (dx / pxPerMin * 60_000).toLong())
                .coerceIn(now - 2 * 3600_000L, now + 30 * 3600_000L)
            invalidate()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (e.y < rulerH) return true
            val r = ((e.y - rulerH + scrollY) / rowH).toInt()
            if (r !in rows.indices) return true
            if (e.x < chanW) {
                row = r
                invalidate()
                host.onPlay(rows[r])
                return true
            }
            val t = windowStart + ((e.x - chanW) / pxPerMin * 60_000).toLong()
            val before = programAt(row, cursor)
            val again = r == row && before != null && before == programAt(r, t)
            row = r
            cursor = t
            notifySelection()
            invalidate()
            if (again) host.onPlay(rows[r])
            return true
        }
    })

    private var pullCandidate = false
    private var pulling = false
    private var touchX = 0f
    private var touchY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchX = event.x
                touchY = event.y
                pullCandidate = false // closing is done by dragging the header to the right
                pulling = false
            }
            MotionEvent.ACTION_MOVE -> if (pullCandidate && !pulling) {
                val dy = event.y - touchY
                val dx = event.x - touchX
                if (dy > dp(14f) && dy > kotlin.math.abs(dx) * 1.5f) {
                    pulling = true
                    val start = MotionEvent.obtain(event)
                    start.action = MotionEvent.ACTION_DOWN
                    host.onPullDown(start)
                    start.recycle()
                } else if (kotlin.math.abs(dx) > dp(14f) || dy < -dp(14f)) {
                    pullCandidate = false
                }
            }
        }
        if (pulling) {
            host.onPullDown(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) pulling = false
            return true
        }
        return gestures.onTouchEvent(event) || super.onTouchEvent(event)
    }
}
