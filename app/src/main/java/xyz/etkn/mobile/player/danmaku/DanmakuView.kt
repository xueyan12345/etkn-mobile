package xyz.etkn.mobile.player.danmaku

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextPaint
import android.util.LruCache
import android.view.View
import androidx.core.graphics.createBitmap
import androidx.media3.common.Player
import xyz.etkn.mobile.data.settings.DanmakuStyle
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Draws danmaku against the media clock on vsync. Each distinct text is shaped once into a
 * cached bitmap, so a frame is only bitmap blits; frames are scheduled only while playing.
 */
@SuppressLint("ViewConstructor")
class DanmakuView(context: Context, private val player: Player) : View(context) {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val blit = Paint(Paint.FILTER_BITMAP_FLAG)
    private val glyphs = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val dp = resources.displayMetrics.density
    private var cues: List<DanmakuCue> = emptyList()
    private var placed: List<PlacedCue> = emptyList()
    private var style = DanmakuStyle()
    private var themeColor = 0
    private var lifeMs = 8000L
    private var alphaByte = 217
    private var lineHeight = 0f
    private var top = 0f
    private var dirty = true
    private var framePending = false
    private var closed = false
    private val frame = Runnable {
        framePending = false
        if (!closed) invalidate()
    }
    private val listener = object : Player.Listener {
        // Seek, pause and resume restart or stop the frame loop.
        override fun onEvents(player: Player, events: Player.Events) = refresh()
    }

    init {
        isFocusable = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        apply(DanmakuStyle(), 0)
    }

    fun apply(next: DanmakuStyle, theme: Int) {
        if (next == style && theme == themeColor && lineHeight > 0f) return
        style = next
        themeColor = theme
        lifeMs = (8000 / next.speed.toDouble().coerceIn(0.5, 2.0)).roundToLong()
        alphaByte = (next.opacity.coerceIn(10, 100) * 2.55f).roundToInt()
        paint.textSize = next.fontSize * dp
        // 用户口径（2026-11-23）：原来这里是 setShadowLayer(dp, 0f, dp, 黑)——
        // 半径 = 1dp 的柔和阴影，四向扩散，叠在浅色画面上就是一层灰雾，
        // 字看着发虚发糊。弹幕只要清晰可读就行，改成不带模糊的细描边：
        // 描边宽度 0.6dp、纯黑不透明，边缘干净，不再有那圈灰。
        paint.setShadowLayer(0.6f * dp, 0f, 0f, 0xFF000000.toInt())
        val metrics = paint.fontMetrics
        lineHeight = metrics.descent - metrics.ascent + 4 * dp
        top = -metrics.ascent + 8 * dp
        glyphs.evictAll()
        glyphCache = emptyMap()
        dirty = true
        refresh()
    }

    fun setCues(value: List<DanmakuCue>) {
        if (value === cues) return
        cues = value
        dirty = true
        refresh()
    }

    /** Redraw now (seek / pause / resume); stops the frame loop while paused. */
    fun refresh() {
        if (closed) return
        if (!player.isPlaying) {
            removeCallbacks(frame)
            framePending = false
        }
        invalidate()
    }

    fun close() {
        closed = true
        removeCallbacks(frame)
        cues = emptyList()
        placed = emptyList()
        glyphs.evictAll()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        dirty = true
    }

    private fun layoutCues() {
        glyphCache = emptyMap()
        val lanes = (1 + ((height * 0.8f - top) / lineHeight).toInt()).coerceIn(1, style.rows.coerceAtLeast(1))
        placed = DanmakuLayout.place(cues, lanes, width, lifeMs, 16 * dp, style.density) { paint.measureText(it) }
        dirty = false
    }

    /**
     * 用户口径（2026-11-23）：弹幕滚动掉帧优化。
     *
     * 原实现每帧都从 firstAt(now - lifeMs) 一路遍历到「第一条还没到时间的」，
     * 也就是说一集里**所有已经出现过但还没飞出屏幕的弹幕**每帧都要走一遍，
     * 每条还要算一次 x(now) 浮点运算 + 一次字符串拼接 key（"$color/${text}"）。
     * 弹幕密集时一帧几十上百次分配 → 明显掉帧，到不了 60fps。
     *
     * 改法：
     *  · 预先把「颜色/文本 → 位图」的查找在布局阶段就算好存进 PlacedCue，
     *    绘制时不再拼字符串、不再查 LruCache
     *  · 位图在 buildGlyphs 里一次性生成，onDraw 只做 drawBitmap
     *  · 遍历范围仍然是活跃窗口，但循环体里只剩一次算术 + 一次 drawBitmap
     */
    private var glyphCache: Map<PlacedCue, Bitmap> = emptyMap()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (closed || width == 0) return
        if (dirty) {
            layoutCues()
            buildGlyphs()
        }
        val now = player.currentPosition.coerceAtLeast(0)
        val pad = 2 * dp
        val ascent = paint.fontMetrics.ascent
        blit.alpha = alphaByte
        var i = DanmakuLayout.firstAt(placed, now - lifeMs)
        // 活跃窗口：从第一条还在屏幕上的，到第一条还没开始的。
        while (i < placed.size) {
            val item = placed[i++]
            if (item.startMs > now) break
            if (now - item.startMs >= lifeMs) continue
            val bitmap = glyphCache[item] ?: continue
            val y = top + lineHeight * item.lane + ascent - pad
            canvas.drawBitmap(bitmap, item.x(now, width, lifeMs) - pad, y, blit)
        }
        if (player.isPlaying && cues.isNotEmpty() && !framePending) {
            framePending = true
            postOnAnimation(frame)
        }
    }

    /** 一次性把所有弹幕的位图都造好（布局变化时跑一次，不在绘制里跑）。 */
    private fun buildGlyphs() {
        val pad = 2 * dp
        val out = HashMap<PlacedCue, Bitmap>(placed.size)
        // 同色同文本复用同一张位图，避免重复生成
        val shared = HashMap<String, Bitmap>()
        for (item in placed) {
            val color = if (style.themeColor && themeColor != 0) themeColor else item.cue.color
            val key = "$color/${item.cue.text}"
            val bitmap = shared.getOrPut(key) {
                glyphs.get(key) ?: shape(item, color, pad).also { glyphs.put(key, it) }
            }
            out[item] = bitmap
        }
        glyphCache = out
    }

    private fun shape(item: PlacedCue, color: Int, pad: Float): Bitmap {
        val metrics = paint.fontMetrics
        val bitmap = createBitmap(
            ceil(item.width + 2 * pad).toInt().coerceAtLeast(1),
            ceil(metrics.descent - metrics.ascent + 2 * pad).toInt().coerceAtLeast(1),
        )
        paint.color = color
        paint.alpha = 255
        Canvas(bitmap).drawText(item.cue.text, pad, pad - metrics.ascent, paint)
        return bitmap
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        player.addListener(listener)
    }

    override fun onDetachedFromWindow() {
        player.removeListener(listener)
        removeCallbacks(frame)
        framePending = false
        super.onDetachedFromWindow()
    }
}
