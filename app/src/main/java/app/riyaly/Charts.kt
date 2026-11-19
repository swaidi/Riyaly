package app.riyaly

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.google.android.material.color.MaterialColors
import kotlin.math.ceil
import kotlin.math.max

private fun Context.dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
private fun Context.sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

private fun View.onSurface() = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
private fun View.muted() = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
private fun View.track() = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceVariant, Color.LTGRAY)

/** Horizontal bars: one row per item. Tap a row to drill down. */
@SuppressLint("ViewConstructor")
class HBarChart(ctx: Context) : View(ctx) {
    data class Item(val label: String, val value: Double, val color: Int, val tag: Any?, val valueText: String)

    var items: List<Item> = emptyList()
        set(v) { field = v; requestLayout(); invalidate() }
    var onTap: ((Any?) -> Unit)? = null

    private val rowH = ctx.dp(34f)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ctx.sp(13f) }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (rowH * max(items.size, 1)).toInt())
    }

    override fun onDraw(c: Canvas) {
        if (items.isEmpty()) return
        val maxV = items.maxOf { it.value }.coerceAtLeast(1.0)
        val labelW = width * 0.32f
        val valueW = context.dp(78f)
        val barX = labelW + context.dp(6f)
        val barMax = width - barX - valueW
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        items.forEachIndexed { i, it ->
            val top = i * rowH
            val cy = top + rowH / 2
            text.color = onSurface()
            val label = ellipsize(it.label, labelW - context.dp(4f))
            val bw = (barMax * (it.value / maxV)).toFloat().coerceAtLeast(context.dp(3f))
            val bh = context.dp(14f)
            bar.color = track()
            if (!rtl) {
                text.textAlign = Paint.Align.LEFT
                c.drawText(label, 0f, cy + text.textSize / 3, text)
                r.set(barX, cy - bh / 2, barX + barMax, cy + bh / 2); c.drawRoundRect(r, bh / 2, bh / 2, bar)
                bar.color = it.color
                r.set(barX, cy - bh / 2, barX + bw, cy + bh / 2); c.drawRoundRect(r, bh / 2, bh / 2, bar)
                text.color = muted(); text.textAlign = Paint.Align.RIGHT
                c.drawText(it.valueText, width.toFloat(), cy + text.textSize / 3, text)
            } else {
                text.textAlign = Paint.Align.RIGHT
                c.drawText(label, width.toFloat(), cy + text.textSize / 3, text)
                val right = width - barX
                r.set(right - barMax, cy - bh / 2, right, cy + bh / 2); c.drawRoundRect(r, bh / 2, bh / 2, bar)
                bar.color = it.color
                r.set(right - bw, cy - bh / 2, right, cy + bh / 2); c.drawRoundRect(r, bh / 2, bh / 2, bar)
                text.color = muted(); text.textAlign = Paint.Align.LEFT
                c.drawText(it.valueText, 0f, cy + text.textSize / 3, text)
            }
        }
    }

    private fun ellipsize(s: String, w: Float): String {
        if (text.measureText(s) <= w) return s
        var t = s
        while (t.length > 1 && text.measureText("$t…") > w) t = t.dropLast(1)
        return "$t…"
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            val i = (e.y / rowH).toInt()
            if (i in items.indices) { performClick(); onTap?.invoke(items[i].tag) }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

/** Monthly trend, one line per series. Tap to drill into a month. */
@SuppressLint("ViewConstructor")
class LineChart(ctx: Context) : View(ctx) {
    data class Series(val name: String, val color: Int, val values: List<Double>)

    var labels: List<String> = emptyList()
    var series: List<Series> = emptyList()
        set(v) { field = v; requestLayout(); invalidate() }
    var onTap: ((Int) -> Unit)? = null

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dp(2.5f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = ctx.dp(1f) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ctx.sp(11f) }
    private val legendH get() = if (series.size > 1) ceil(series.size / 3.0).toFloat() * context.dp(20f) else 0f
    private val left = ctx.dp(40f)
    private val bottom = ctx.dp(22f)
    private val plotH = ctx.dp(170f)

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (plotH + bottom + legendH + context.dp(8f)).toInt())
    }

    override fun onDraw(c: Canvas) {
        if (labels.isEmpty()) return
        val maxV = series.flatMap { it.values }.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
        val niceMax = nice(maxV)
        val w = width - left - context.dp(8f)
        val stepX = if (labels.size > 1) w / (labels.size - 1) else 0f
        fun x(i: Int) = left + i * stepX
        fun y(v: Double) = (plotH - (v / niceMax) * (plotH - context.dp(8f))).toFloat()

        grid.color = track(); text.color = muted()
        text.textAlign = Paint.Align.RIGHT
        for (g in 0..3) {
            val v = niceMax * g / 3
            val yy = y(v)
            c.drawLine(left, yy, width.toFloat(), yy, grid)
            c.drawText(short(v), left - context.dp(4f), yy + text.textSize / 3, text)
        }
        text.textAlign = Paint.Align.CENTER
        val every = max(1, ceil(labels.size / 6.0).toInt())
        labels.forEachIndexed { i, l -> if (i % every == 0 || i == labels.lastIndex) c.drawText(l, x(i), plotH + context.dp(16f), text) }

        for (s in series) {
            line.color = s.color; dot.color = s.color
            for (i in 1 until s.values.size) c.drawLine(x(i - 1), y(s.values[i - 1]), x(i), y(s.values[i]), line)
            s.values.forEachIndexed { i, v -> c.drawCircle(x(i), y(v), context.dp(3f), dot) }
        }
        if (series.size > 1) {
            text.textAlign = Paint.Align.LEFT; text.color = onSurface()
            val colW = width / 3f
            series.forEachIndexed { i, s ->
                val lx = (i % 3) * colW + context.dp(4f)
                val ly = plotH + bottom + (i / 3) * context.dp(20f) + context.dp(12f)
                dot.color = s.color
                c.drawCircle(lx + context.dp(5f), ly - text.textSize / 3, context.dp(5f), dot)
                c.drawText(s.name.take(16), lx + context.dp(14f), ly, text)
            }
        }
    }

    private fun nice(v: Double): Double {
        val mag = Math.pow(10.0, Math.floor(Math.log10(v)))
        val n = v / mag
        return (if (n <= 1) 1.0 else if (n <= 2) 2.0 else if (n <= 5) 5.0 else 10.0) * mag
    }

    private fun short(v: Double): String = when {
        v >= 1_000_000 -> "%.1fM".format(v / 1_000_000)
        v >= 1_000 -> "%.0fK".format(v / 1_000)
        else -> "%.0f".format(v)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP && labels.isNotEmpty()) {
            val w = width - left - context.dp(8f)
            val stepX = if (labels.size > 1) w / (labels.size - 1) else 1f
            val i = Math.round((e.x - left) / stepX).coerceIn(0, labels.lastIndex)
            performClick(); onTap?.invoke(i)
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

/** Day-of-week × hour heat map. Tap a cell to see those transactions. */
@SuppressLint("ViewConstructor")
class Heatmap(ctx: Context) : View(ctx) {
    /** counts[day][hour], day 0 = Sunday. */
    var counts: Array<IntArray> = Array(7) { IntArray(24) }
        set(v) { field = v; invalidate() }
    var dayLabels: List<String> = listOf("S", "M", "T", "W", "T", "F", "S")
    var color: Int = Color.rgb(31, 111, 80)
    var onTap: ((Int, Int) -> Unit)? = null

    private val cell = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = ctx.sp(10f) }
    private val left = ctx.dp(34f)
    private val top = ctx.dp(16f)
    private val r = RectF()

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val cw = (width - left) / 24f
        setMeasuredDimension(width, (top + cw * 7 * 1.15f + context.dp(4f)).toInt())
    }

    override fun onDraw(c: Canvas) {
        val cw = (width - left) / 24f
        val ch = cw * 1.15f
        val maxC = counts.maxOf { row -> row.maxOrNull() ?: 0 }.coerceAtLeast(1)
        text.color = muted(); text.textAlign = Paint.Align.CENTER
        for (hh in 0 until 24 step 3) c.drawText("$hh", left + hh * cw + cw / 2, top - context.dp(4f), text)
        text.textAlign = Paint.Align.LEFT
        for (d in 0 until 7) {
            c.drawText(dayLabels.getOrElse(d) { "" }, 0f, top + d * ch + ch / 2 + text.textSize / 3, text)
            for (hh in 0 until 24) {
                val v = counts[d][hh]
                cell.color = if (v == 0) track() else blend(v.toFloat() / maxC)
                r.set(left + hh * cw + 1, top + d * ch + 1, left + (hh + 1) * cw - 1, top + (d + 1) * ch - 1)
                c.drawRoundRect(r, cw * 0.2f, cw * 0.2f, cell)
            }
        }
    }

    private fun blend(f: Float): Int {
        val a = (60 + 195 * f).toInt().coerceIn(60, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            val cw = (width - left) / 24f
            val ch = cw * 1.15f
            val hh = ((e.x - left) / cw).toInt()
            val d = ((e.y - top) / ch).toInt()
            if (hh in 0..23 && d in 0..6) { performClick(); onTap?.invoke(d, hh) }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
