package com.example.heatmap

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * 使用 squarified（近似正方形）算法渲染一组 [HeatmapItem] 的热力树状图。
 *
 * 该 View 故意做成与业务无关，可以直接抽到独立 library 模块中复用：
 * - 数据只通过 [setData] 输入；
 * - 所有业务相关的格式化（货币符号、百分号、配色、币种命名规则等）由调用方在
 *   构造 [HeatmapItem] 时完成，View 内部不做任何假设。
 *
 * 可定制的渲染参数都以公开 `var` 暴露，可在 [setData] 之前或之后随时修改；
 * 修改样式属性后视图会自动 `invalidate()` 重绘，修改尺寸属性后需调用 [setData] 或
 * [invalidate] 触发刷新。
 */
class HeatmapTreemapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // --- 公开的渲染参数（library 友好） ---
    /** 每个 tile 的圆角半径，单位：px。 */
    var tileCornerRadius: Float = dp(6f)
    /** 相邻 tile 之间预留的间距，单位：px。 */
    var tileGap: Float = dp(3f)
    /** tile 内文本到左右两边的水平内边距，单位：px。 */
    var tilePaddingHorizontal: Float = dp(8f)
    /** tile 内文本到上下两边的垂直内边距，单位：px。 */
    var tilePaddingVertical: Float = dp(6f)
    /** 描述区多行（primary/secondary）之间的行间距，单位：px。 */
    var descriptionLineSpacing: Float = dp(2f)

    /** 标签（label）的字号公式：`clamp(tileHeight / labelTextSizeScale, min, max)`。 */
    var labelTextSizeScale: Float = 7.5f
    /** 标签字号下限，单位：px。tile 太小时仍不低于此值。 */
    var labelTextSizeMin: Float = sp(10f)
    /** 标签字号上限，单位：px。tile 很大时也不超过此值。 */
    var labelTextSizeMax: Float = sp(22f)
    /** 描述（primary/secondary）的字号公式：`clamp(tileHeight / descriptionTextSizeScale, min, max)`。 */
    var descriptionTextSizeScale: Float = 14.5f
    /** 描述字号下限，单位：px。 */
    var descriptionTextSizeMin: Float = sp(9f)
    /** 描述字号上限，单位：px。 */
    var descriptionTextSizeMax: Float = sp(13f)

    /** 标签文字颜色（ARGB）。设置后立即生效。 */
    var labelTextColor: Int = Color.WHITE
        set(value) { field = value; labelPaint.color = value; invalidate() }
    /** 描述文字颜色（ARGB）。设置后立即生效。 */
    var descriptionTextColor: Int = Color.WHITE
        set(value) { field = value; descriptionPaint.color = value; invalidate() }
    /** tile 描边颜色（ARGB）。默认是 10% 黑，提供轻微的边界感。 */
    var tileStrokeColor: Int = 0x10000000
        set(value) { field = value; strokePaint.color = value; invalidate() }
    /** tile 描边线宽，单位：px。 */
    var tileStrokeWidth: Float = dp(0.5f)
        set(value) { field = value; strokePaint.strokeWidth = value; invalidate() }

    /**
     * 顶部强制行结构。
     *
     * 数组的每一项代表"从最大的 item 开始连续取 N 个，铺成一行全宽条带"。
     * 整段消费完后剩余 item 走标准 squarified 算法填充下方空间。
     *
     * 典型用法（仿币安热力图）：`intArrayOf(1, 2)` —— 第 1 行只放最大的一项（如 BTCUSDT），
     * 第 2 行放紧随其后的 2 项（如 ETHUSDT 与 BTCUSDC），第 3 行起为小方块网格。
     *
     * 默认空数组，即整张图完全由 squarified 自适应。
     */
    var topRowSizes: IntArray = intArrayOf()
        set(value) { field = value; relayout(); invalidate() }

    /**
     * tile 点击回调。
     *
     * @param item 被点击的数据项（即 [setData] 传入的对象本身）。
     * @param rectInView 被点击 tile 在当前 View 坐标系下的矩形区域，可用于浮窗定位。
     */
    var onTileClick: ((item: HeatmapItem, rectInView: RectF) -> Unit)? = null

    // --- 内部画笔与状态 ---
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = tileStrokeWidth
        color = tileStrokeColor
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = labelTextColor
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val descriptionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = descriptionTextColor
    }

    private var items: List<HeatmapItem> = emptyList()
    private val layoutRects: MutableList<RectF> = mutableListOf()

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val idx = hitTest(e.x, e.y)
            if (idx >= 0) {
                onTileClick?.invoke(items[idx], RectF(layoutRects[idx]))
                return true
            }
            return false
        }
    })

    /**
     * 替换当前显示的数据集并立即重排重绘。
     *
     * - 内部会按 [HeatmapItem.weight] 降序排序，调用方传入顺序不影响结果。
     * - `weight <= 0` 的项会被过滤掉（squarified 算法不接受非正面积）。
     * - 若整体权重和为 0，View 会清空显示。
     *
     * @param data 要展示的数据项集合。
     */
    fun setData(data: List<HeatmapItem>) {
        items = data.filter { it.weight > 0.0 }.sortedByDescending { it.weight }
        relayout()
        invalidate()
    }

    /**
     * View 尺寸变化时重新计算 treemap 布局。
     *
     * 框架在 layout 阶段会回调此方法；如果在 View 还没被测量时调用了 [setData]，
     * 也会在第一次 [onSizeChanged] 触发时把布局补上。
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        relayout()
    }

    /**
     * 把手势事件交给内部的 [GestureDetector]，仅识别"单击抬起"用于触发 [onTileClick]。
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    /**
     * 命中测试：在当前布局结果中查找包含点 `(x, y)` 的 tile 下标。
     *
     * 由于 squarified 布局是从大到小排列、且各矩形互不重叠，这里采用从前往后线性扫描；
     * 数据量较大时也可改为四叉树或扫描线优化，但目前完全够用。
     *
     * @return 命中的 tile 索引；未命中则返回 -1。
     */
    private fun hitTest(x: Float, y: Float): Int {
        for (i in items.indices) {
            if (layoutRects[i].contains(x, y)) return i
        }
        return -1
    }

    /**
     * 重新计算所有 tile 的矩形位置。
     *
     * 步骤：
     * 1. 计算 View 可用总面积；
     * 2. 把每个 item 的 `weight` 等比缩放到「单位为像素²」的面积值；
     * 3. 若 [topRowSizes] 非空：先按其消费前若干个 item，每段铺成一条全宽水平条带；
     * 4. 调用 [squarify] 把剩余 item 填充进剩余矩形。
     *
     * 计算结果存放在 [layoutRects]，与 [items] 一一对应。
     */
    private fun relayout() {
        layoutRects.clear()
        if (items.isEmpty() || width == 0 || height == 0) return
        val totalArea = width.toDouble() * height.toDouble()
        val totalWeight = items.sumOf { it.weight }
        if (totalWeight <= 0.0) return
        val scale = totalArea / totalWeight
        val normalized = items.map { it.weight * scale }
        val rects = MutableList(items.size) { RectF() }

        val w = width.toFloat()
        val h = height.toFloat()
        var startIdx = 0
        var curY = 0f

        // 强制顶部行：每段消费 rowSize 个 item，铺成一条全宽水平条带。
        // 条带高度 = 该段总面积 / w，从而条带内部各 tile 的面积总和恰好等于这几个 item 的权重之和。
        for (rowSize in topRowSizes) {
            if (rowSize <= 0) continue
            val rowEnd = (startIdx + rowSize).coerceAtMost(items.size)
            if (rowEnd <= startIdx) break
            val rowList = normalized.subList(startIdx, rowEnd)
            val rowSum = rowList.sum()
            val rowH = (rowSum / w).toFloat()
            if (rowH <= 0f || curY + rowH > h + 0.5f) break
            var curX = 0f
            for ((i, area) in rowList.withIndex()) {
                val tileW = (area / rowSum * w).toFloat()
                val realW = if (i == rowList.size - 1) w - curX else tileW
                rects[startIdx + i] = RectF(curX, curY, curX + realW, curY + rowH)
                curX += realW
            }
            curY += rowH
            startIdx = rowEnd
        }

        // 剩余 item 走标准 squarified。
        if (startIdx < items.size && curY < h) {
            squarify(normalized, startIdx, items.size, 0f, curY, w, h - curY, rects)
        }

        layoutRects.addAll(rects)
    }

    /**
     * Squarified Treemap 核心算法（Bruls / Huijsen / van Wijk, 2000）。
     *
     * 在剩余矩形区域 `(x, y, w, h)` 内，从 [values] 中依次取面积值放入"当前条带"。
     * 每加入一项就评估条带内最大/最小矩形的最差长宽比 [worst]，一旦加入新项会让结果
     * 变差，就停止生长，把当前条带"铺出去"（紧贴较短边），然后对剩余项与剩余矩形递归。
     *
     * @param values 已按面积降序排好的面积值数组（像素²）。
     * @param startIdx 本次递归处理的起始下标（包含）。
     * @param endExclusive 本次递归处理的结束下标（不包含）。
     * @param x 剩余可用矩形的左上 x。
     * @param y 剩余可用矩形的左上 y。
     * @param w 剩余可用矩形的宽。
     * @param h 剩余可用矩形的高。
     * @param out 输出：与 [items] 同长度的矩形列表，本方法会按 [startIdx] 起填入结果。
     */
    private fun squarify(
        values: List<Double>,
        startIdx: Int,
        endExclusive: Int,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        out: MutableList<RectF>,
    ) {
        if (startIdx >= endExclusive || w <= 0f || h <= 0f) return
        if (endExclusive - startIdx == 1) {
            out[startIdx] = RectF(x, y, x + w, y + h)
            return
        }
        val side = min(w, h).toDouble()
        var rowEnd = startIdx + 1
        var rowSum = values[startIdx]
        var rowMin = values[startIdx]
        var rowMax = values[startIdx]
        var bestWorst = worst(rowSum, rowMin, rowMax, side)

        while (rowEnd < endExclusive) {
            val v = values[rowEnd]
            val newSum = rowSum + v
            val newMin = min(rowMin, v)
            val newMax = max(rowMax, v)
            val newWorst = worst(newSum, newMin, newMax, side)
            if (newWorst <= bestWorst) {
                rowEnd++
                rowSum = newSum
                rowMin = newMin
                rowMax = newMax
                bestWorst = newWorst
            } else break
        }

        val remainingArea = values.subList(startIdx, endExclusive).sum()
        val thickness = (rowSum / remainingArea) * if (w >= h) w else h
        val rowList = values.subList(startIdx, rowEnd)

        if (w >= h) {
            var py = y
            val stripWidth = thickness.toFloat()
            for ((i, v) in rowList.withIndex()) {
                val itemH = ((v / rowSum) * h).toFloat()
                val realH = if (i == rowList.size - 1) y + h - py else itemH
                out[startIdx + i] = RectF(x, py, x + stripWidth, py + realH)
                py += realH
            }
            squarify(values, rowEnd, endExclusive, x + stripWidth, y, w - stripWidth, h, out)
        } else {
            var px = x
            val stripHeight = thickness.toFloat()
            for ((i, v) in rowList.withIndex()) {
                val itemW = ((v / rowSum) * w).toFloat()
                val realW = if (i == rowList.size - 1) x + w - px else itemW
                out[startIdx + i] = RectF(px, y, px + realW, y + stripHeight)
                px += realW
            }
            squarify(values, rowEnd, endExclusive, x, y + stripHeight, w, h - stripHeight, out)
        }
    }

    /**
     * 估算"在长度为 [side] 的条带上铺这一组面积"时，最差矩形的长宽比。
     *
     * 取 `max(side² · maxV / sum², sum² / (side² · minV))`，值越小说明矩形越接近正方形。
     * Squarified 算法用这个指标决定是否继续往当前条带里塞新矩形。
     */
    private fun worst(sum: Double, minV: Double, maxV: Double, side: Double): Double {
        val s2 = sum * sum
        val side2 = side * side
        return max(side2 * maxV / s2, s2 / (side2 * minV))
    }

    /**
     * 绘制入口：先填充矩形和描边，再调用 [drawTileText] 绘制文字。
     *
     * 为了在视觉上拉开 tile，会在原始 layout 矩形上向内收缩半个 [tileGap]。
     */
    override fun onDraw(canvas: Canvas) {
        if (items.isEmpty() || layoutRects.isEmpty()) return
        val halfGap = tileGap / 2f
        for (i in items.indices) {
            val item = items[i]
            val r = layoutRects[i]
            val inset = RectF(
                r.left + halfGap,
                r.top + halfGap,
                r.right - halfGap,
                r.bottom - halfGap,
            )
            if (inset.width() <= 0f || inset.height() <= 0f) continue

            fillPaint.color = item.tileColor
            canvas.drawRoundRect(inset, tileCornerRadius, tileCornerRadius, fillPaint)
            canvas.drawRoundRect(inset, tileCornerRadius, tileCornerRadius, strokePaint)

            drawTileText(canvas, item, inset)
        }
    }

    /**
     * 在单个 tile 内绘制 label 与 description（primary/secondary）。
     *
     * 关键策略：
     * 1. 字号按 tile 高度动态计算，再 clamp 到 min/max；
     * 2. label 最多 2 行折行；超宽则把字号收缩到 [labelTextSizeMin]；
     * 3. description 不折行，超宽就缩字号；连 secondary 都放不下就整组放弃；
     * 4. 垂直方向按"label + 描述"总高度做 4 级渐进截断：
     *    - Pass 1：放不下整组描述 → 只保留 secondary 一行；
     *    - Pass 2：还不够 → 用 [HeatmapItem.shortLabel] 替换 label；
     *    - Pass 3：还不够 → 整组描述都丢弃；
     *    - Pass 4：连 label 都装不下 → 按可用高度截掉多余行；
     * 5. 最终整体文本块在 tile 内**垂直居中**；
     * 6. 绘制前 `clipRect(rect)`，任何意外溢出也不会画到 tile 外面。
     *
     * @param canvas onDraw 传入的画布。
     * @param item 当前 tile 对应的数据项。
     * @param rect 当前 tile 的内缩矩形（即 onDraw 里的 `inset`）。
     */
    private fun drawTileText(canvas: Canvas, item: HeatmapItem, rect: RectF) {
        val w = rect.width()
        val h = rect.height()
        if (w < dp(24f) || h < dp(16f)) return

        labelPaint.textSize = (h / labelTextSizeScale).coerceIn(labelTextSizeMin, labelTextSizeMax)
        descriptionPaint.textSize = (h / descriptionTextSizeScale)
            .coerceIn(descriptionTextSizeMin, descriptionTextSizeMax)

        val maxTextWidth = w - tilePaddingHorizontal * 2f
        val availH = h - tilePaddingVertical * 2f
        if (maxTextWidth <= 0f || availH <= 0f) return

        // 1) label：最多 2 行折行，再按最宽行收缩字号到下限。
        var labelLines = wrapText(item.label, labelPaint, maxTextWidth, maxLines = 2)
        shrinkToFitWidest(labelLines, labelPaint, maxTextWidth, labelTextSizeMin)

        // 2) description：先确保 secondary（如百分比）能横向放下；放不下就只画 label。
        val primary = item.primary
        val secondary = item.secondary
        var descLines: List<String> = emptyList()
        if (secondary != null) {
            if (shrinkToFit(secondary, descriptionPaint, maxTextWidth, descriptionTextSizeMin)) {
                val primaryFits = primary != null &&
                    descriptionPaint.measureText(primary) <= maxTextWidth
                descLines = if (primaryFits) listOf(primary!!, secondary) else listOf(secondary)
            }
        } else if (primary != null) {
            if (shrinkToFit(primary, descriptionPaint, maxTextWidth, descriptionTextSizeMin)) {
                descLines = listOf(primary)
            }
        }

        val labelFm = labelPaint.fontMetrics
        val descFm = descriptionPaint.fontMetrics
        val labelLineH = labelFm.descent - labelFm.ascent
        val descLineH = descFm.descent - descFm.ascent

        var labelH = labelLines.size * labelLineH
        var descH = if (descLines.isEmpty()) 0f
            else descLines.size * descLineH + (descLines.size - 1) * descriptionLineSpacing

        // 垂直截断 Pass 1：label + 完整 description 装不下 → 只留 secondary。
        if (descLines.size > 1 && secondary != null && availH < labelH + descH) {
            descLines = listOf(secondary)
            descH = descLineH
        }
        // Pass 2：仍装不下 → 用 shortLabel 退化 label，给 description 让出空间。
        if (availH < labelH + descH && !item.shortLabel.isNullOrEmpty()) {
            labelLines = wrapText(item.shortLabel, labelPaint, maxTextWidth, maxLines = 1)
            shrinkToFitWidest(labelLines, labelPaint, maxTextWidth, labelTextSizeMin)
            labelH = labelLines.size * labelLineH
        }
        // Pass 3：还是装不下 → 整组 description 丢弃。
        if (availH < labelH + descH) {
            descLines = emptyList()
            descH = 0f
        }
        // Pass 4：连 label 都超高 → 截掉多余行，至少保证 1 行可见。
        if (labelH > availH) {
            val keep = (availH / labelLineH).toInt().coerceAtLeast(1)
            labelLines = labelLines.take(keep)
            labelH = keep * labelLineH
        }

        val totalH = labelH + descH
        val topOffset = ((availH - totalH).coerceAtLeast(0f)) / 2f
        val left = rect.left + tilePaddingHorizontal
        var y = rect.top + tilePaddingVertical + topOffset

        canvas.save()
        canvas.clipRect(rect)
        for (line in labelLines) {
            canvas.drawText(line, left, y - labelFm.ascent, labelPaint)
            y += labelLineH
        }
        for ((idx, line) in descLines.withIndex()) {
            if (idx > 0) y += descriptionLineSpacing
            canvas.drawText(line, left, y - descFm.ascent, descriptionPaint)
            y += descLineH
        }
        canvas.restore()
    }

    /**
     * 不断减小 [paint] 的字号直到 [text] 能在 [maxWidth] 内放下，或字号触底到 [minSize]。
     *
     * **副作用**：会修改 [paint] 的 `textSize`。调用者若需要恢复原值，需自行保存。
     *
     * @return 最终 [text] 是否能在 [maxWidth] 内放下。
     */
    private fun shrinkToFit(text: String, paint: Paint, maxWidth: Float, minSize: Float): Boolean {
        if (paint.measureText(text) <= maxWidth) return true
        while (paint.textSize > minSize) {
            paint.textSize = (paint.textSize - sp(0.5f)).coerceAtLeast(minSize)
            if (paint.measureText(text) <= maxWidth) return true
        }
        return paint.measureText(text) <= maxWidth
    }

    /**
     * [shrinkToFit] 的多行版本：以多行中"最宽的一行"为基准收缩字号到下限。
     *
     * 用于 label 折行后保证两行都能放下而不溢出。
     *
     * @param lines 已经折好的多行文本。
     * @param paint 要调整的画笔。
     * @param maxWidth tile 内文本最大可用宽度。
     * @param minSize 字号收缩下限，达到此值仍放不下也会停止。
     */
    private fun shrinkToFitWidest(lines: List<String>, paint: Paint, maxWidth: Float, minSize: Float) {
        var widest = 0f
        for (l in lines) widest = max(widest, paint.measureText(l))
        if (widest <= maxWidth) return
        while (paint.textSize > minSize) {
            paint.textSize = (paint.textSize - sp(0.5f)).coerceAtLeast(minSize)
            widest = 0f
            for (l in lines) widest = max(widest, paint.measureText(l))
            if (widest <= maxWidth) return
        }
    }

    /**
     * 简单的字符级折行：在不超过 [maxWidth] 的前提下按字符切分到最多 [maxLines] 行。
     *
     * 不做单词边界识别（symbol 多为 "BTCUSDT" 这类无空格短串，按字符切已经够用）；
     * 如果第 [maxLines] 行仍未塞下剩余字符，剩余部分会被静默丢弃 —— 调用方应在外层
     * 自行控制是否退化到 [HeatmapItem.shortLabel]。
     */
    private fun wrapText(text: String, paint: Paint, maxWidth: Float, maxLines: Int): List<String> {
        if (paint.measureText(text) <= maxWidth) return listOf(text)
        if (maxLines <= 1) return listOf(text)
        val result = mutableListOf<String>()
        var start = 0
        while (start < text.length && result.size < maxLines) {
            var end = text.length
            while (end > start && paint.measureText(text, start, end) > maxWidth) end--
            if (end == start) end = start + 1
            result.add(text.substring(start, end))
            start = end
        }
        return result
    }

    /** dp → px 转换。 */
    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    /** sp → px 转换（跟随系统字体缩放）。 */
    private fun sp(v: Float): Float = v * resources.displayMetrics.scaledDensity
}
