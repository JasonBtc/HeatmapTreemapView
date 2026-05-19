package com.example.phview

import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.phview.databinding.ActivityPhBinding
import com.example.heatmap.HeatmapItem
import com.google.android.material.bottomsheet.BottomSheetDialog

class PHActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhBinding
    private val fullTickers: List<CryptoTicker> by lazy { sampleTickers() }
    private var currentScope: Int = 50
    private var currentMetric: Metric = Metric.CHANGE_24H
    private var currentScale: ScaleBasis = ScaleBasis.VOLUME
    private var currentCategory: Category = Category.USDT_MARGINED
    private var currentPopup: PopupWindow? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.phToolbar.setNavigationOnClickListener { finish() }
        binding.phShare.setOnClickListener {
            Toast.makeText(this, "分享", Toast.LENGTH_SHORT).show()
        }

        bindChip(binding.phFilterQuote.root, currentCategory.label) { showCategorySheet() }
        bindChip(binding.phFilterMetric.root, currentScale.label) { showScaleSheet() }
        bindChip(binding.phFilterRange.root, currentMetric.label) { showMetricSheet() }
        bindChip(binding.phFilterScope.root, scopeLabel(currentScope)) { showScopeSheet() }

        binding.phTreemap.onTileClick = { item, rect ->
            (item.payload as? CryptoTicker)?.let { showTilePopup(it, rect) }
        }
        // 仿币安热力图：第 1 行单 tile（BTC），第 2 行 2 个 tile（ETH+BTCUSDC），剩余 squarified。
        binding.phTreemap.topRowSizes = intArrayOf(1, 2)
        applyData()
    }

    override fun onPause() {
        super.onPause()
        currentPopup?.dismiss()
        currentPopup = null
    }

    private fun bindChip(rootView: View, label: String, onClick: () -> Unit) {
        rootView.findViewById<TextView>(R.id.chip_text).text = label
        rootView.setOnClickListener { onClick() }
    }

    private fun scopeLabel(n: Int): String = "前${n}币种"

    private fun applyData() {
        val filtered = fullTickers.filter { currentCategory in categoriesOf(it) }
            .take(currentScope)
        binding.phTreemap.setData(filtered.map { toHeatmapItem(it, currentMetric, currentScale) })
    }

    private fun showScopeSheet() {
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.ph_scope_sheet, null, false)
        dialog.setContentView(view)
        val opts = mapOf(
            10 to view.findViewById<TextView>(R.id.scope_opt_10),
            20 to view.findViewById<TextView>(R.id.scope_opt_20),
            30 to view.findViewById<TextView>(R.id.scope_opt_30),
            50 to view.findViewById<TextView>(R.id.scope_opt_50),
        )
        opts.forEach { (n, tv) ->
            tv.isSelected = (n == currentScope)
            tv.setOnClickListener {
                currentScope = n
                binding.phFilterScope.root.findViewById<TextView>(R.id.chip_text).text = scopeLabel(n)
                applyData()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showTilePopup(ticker: CryptoTicker, rectInView: RectF) {
        currentPopup?.dismiss()
        val content = LayoutInflater.from(this)
            .inflate(R.layout.ph_tile_popup, binding.root as ViewGroup, false)

        content.findViewById<TextView>(R.id.popup_symbol).text = ticker.symbol
        content.findViewById<TextView>(R.id.popup_price).text = formatPrice(ticker.price)
        val change = content.findViewById<TextView>(R.id.popup_change)
        change.text = formatPercent(ticker.changePercent)
        change.setTextColor(ContextCompat.getColor(
            this,
            if (ticker.changePercent < 0) R.color.ph_down_text else R.color.ph_up_text,
        ))
        val volume = if (ticker.volume > 0.0) ticker.volume else ticker.weight * 1.7e8
        content.findViewById<TextView>(R.id.popup_volume).text = "成交量 " + formatVolumeCNY(volume)

        val badge = content.findViewById<TextView>(R.id.popup_badge)
        val badgeText = ticker.contractType ?: defaultBadge(ticker.symbol)
        if (badgeText != null) {
            badge.visibility = View.VISIBLE
            badge.text = badgeText
        } else {
            badge.visibility = View.GONE
        }

        val star = content.findViewById<ImageView>(R.id.popup_star)
        star.alpha = 0.7f
        star.setOnClickListener {
            star.isSelected = !star.isSelected
            star.alpha = if (star.isSelected) 1f else 0.7f
        }

        content.findViewById<TextView>(R.id.popup_trade).setOnClickListener {
            currentPopup?.dismiss()
            Toast.makeText(this, "交易 ${ticker.symbol}", Toast.LENGTH_SHORT).show()
        }

        val popup = PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            isOutsideTouchable = true
            isFocusable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(8f)
        }
        currentPopup = popup

        content.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val popupW = content.measuredWidth
        val popupH = content.measuredHeight

        val tileLoc = IntArray(2)
        binding.phTreemap.getLocationOnScreen(tileLoc)
        val tileTopOnScreen = tileLoc[1] + rectInView.top
        val tileLeftOnScreen = tileLoc[0] + rectInView.left

        val rootView = binding.root as View
        val rootW = rootView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val arrowDefaultStart = dp(24f).toInt()
        val tileCenterX = (tileLeftOnScreen + rectInView.width() / 2f).toInt()
        var x = tileCenterX - arrowDefaultStart - dp(8f).toInt()
        x = x.coerceIn(dp(8f).toInt(), (rootW - popupW - dp(8f).toInt()).coerceAtLeast(dp(8f).toInt()))
        val y = (tileTopOnScreen + rectInView.height() / 2f - popupH).toInt()
            .coerceAtLeast(dp(8f).toInt())

        popup.showAtLocation(rootView, 0, x, y)

        adjustArrowOffset(content, tileCenterX - x)
    }

    private fun adjustArrowOffset(content: View, desiredCenterX: Int) {
        val arrow = content.findViewById<ImageView>(R.id.popup_arrow)
        arrow.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val lp = arrow.layoutParams as ViewGroup.MarginLayoutParams
        val minStart = dp(12f).toInt()
        val maxStart = (content.measuredWidth - arrow.measuredWidth - dp(12f).toInt()).coerceAtLeast(minStart)
        val centered = desiredCenterX - arrow.measuredWidth / 2
        lp.marginStart = centered.coerceIn(minStart, maxStart)
        arrow.layoutParams = lp
    }

    // --- 类型（category）：决定参与渲染的 ticker 子集 ---

    private enum class Category(val label: String) {
        USDT_MARGINED("U本位"),
        COIN_MARGINED("币本位"),
        DEFI("DeFi"),
        BASIC_SERVICES("基础服务"),
        LAYER_1("Layer 1"),
        LAYER_2("Layer 2"),
        NFT("NFT"),
    }

    private fun showCategorySheet() {
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.ph_category_sheet, null, false)
        dialog.setContentView(view)
        val opts = mapOf(
            Category.USDT_MARGINED to view.findViewById<TextView>(R.id.cat_opt_usdt),
            Category.COIN_MARGINED to view.findViewById<TextView>(R.id.cat_opt_coin),
            Category.DEFI to view.findViewById<TextView>(R.id.cat_opt_defi),
            Category.BASIC_SERVICES to view.findViewById<TextView>(R.id.cat_opt_infra),
            Category.LAYER_1 to view.findViewById<TextView>(R.id.cat_opt_l1),
            Category.LAYER_2 to view.findViewById<TextView>(R.id.cat_opt_l2),
            Category.NFT to view.findViewById<TextView>(R.id.cat_opt_nft),
        )
        opts.forEach { (cat, tv) ->
            tv.isSelected = (cat == currentCategory)
            tv.setOnClickListener {
                currentCategory = cat
                binding.phFilterQuote.root.findViewById<TextView>(R.id.chip_text).text = cat.label
                applyData()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /**
     * 一个 ticker 可同时属于多个分类（如 BTCUSDT 同时是 U本位 与 Layer 1）。
     *
     * 真实接入时应改为后端返回的 tag 列表 —— 这里只做演示。
     */
    private fun categoriesOf(t: CryptoTicker): Set<Category> {
        val tags = mutableSetOf<Category>()
        when {
            t.symbol.endsWith("USDT") -> tags.add(Category.USDT_MARGINED)
            t.symbol.endsWith("USDC") -> tags.add(Category.COIN_MARGINED)
            else -> tags.add(Category.USDT_MARGINED) // 现货 / 杂项默认归到 U本位 桶
        }
        when (baseAsset(t.symbol)) {
            "BTC", "ETH", "SOL", "BNB", "AVAX", "ADA", "DOT", "ATOM", "ALGO", "EGLD",
            "TON", "SUI", "APT", "NEAR", "TRX", "LTC", "BCH", "ETC", "ICP", "XLM",
            "EOS", "SEI", "FIL" -> tags.add(Category.LAYER_1)
            "ARB", "OP" -> tags.add(Category.LAYER_2)
            "AAVE", "UNI", "LDO", "MKR", "RUNE", "INJ", "FET", "EDEN" -> tags.add(Category.DEFI)
            "ZEC", "XAU", "XAG", "XRP", "LINK" -> tags.add(Category.BASIC_SERVICES)
            "APE", "SAND", "GALA" -> tags.add(Category.NFT)
        }
        return tags
    }

    // --- 规模依据（scale）：决定每个 tile 的面积权重 ---

    private enum class ScaleBasis(val label: String) {
        VOLUME("交易量"),
        CHANGE_24H("24h 涨跌"),
        MARKET_CAP("市值"),
        TURNOVER("成交量/市值"),
    }

    private fun showScaleSheet() {
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.ph_scale_sheet, null, false)
        dialog.setContentView(view)
        val opts = mapOf(
            ScaleBasis.VOLUME to view.findViewById<TextView>(R.id.scale_opt_volume),
            ScaleBasis.CHANGE_24H to view.findViewById<TextView>(R.id.scale_opt_change),
            ScaleBasis.MARKET_CAP to view.findViewById<TextView>(R.id.scale_opt_mcap),
            ScaleBasis.TURNOVER to view.findViewById<TextView>(R.id.scale_opt_turnover),
        )
        opts.forEach { (basis, tv) ->
            tv.isSelected = (basis == currentScale)
            tv.setOnClickListener {
                currentScale = basis
                binding.phFilterMetric.root.findViewById<TextView>(R.id.chip_text).text = basis.label
                applyData()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /**
     * 根据当前选择的规模依据计算 tile 权重。
     * - VOLUME：直接用 24h 成交量
     * - CHANGE_24H：用涨跌幅绝对值（下限 0.1，避免几乎不动的币种消失）
     * - MARKET_CAP：用市值（[marketCapOf] 派生）
     * - TURNOVER：成交量 / 市值（换手率）
     */
    private fun scaleWeight(t: CryptoTicker, basis: ScaleBasis): Double {
        val volume = if (t.volume > 0.0) t.volume else t.weight * 1.0e7
        val mcap = marketCapOf(t)
        return when (basis) {
            ScaleBasis.VOLUME -> volume.coerceAtLeast(1.0)
            ScaleBasis.CHANGE_24H -> kotlin.math.abs(t.changePercent).coerceAtLeast(0.1)
            ScaleBasis.MARKET_CAP -> mcap.coerceAtLeast(1.0)
            ScaleBasis.TURNOVER -> (volume / mcap).coerceAtLeast(0.0001)
        }
    }

    /**
     * 演示用的伪市值：按 symbol 哈希派生 5..50 的乘子乘以成交量。
     * 真实接入时应替换为后端返回的 market cap 字段。
     */
    private fun marketCapOf(t: CryptoTicker): Double {
        val seed = ((t.symbol.hashCode() and 0x7fffffff) % 1000) / 1000.0
        val factor = 5.0 + seed * 45.0
        val volume = if (t.volume > 0.0) t.volume else t.weight * 1.0e7
        return volume * factor
    }

    // --- 指标（metric）：决定 tile 的颜色与副标题 ---

    private enum class Metric(val label: String) {
        CHANGE_24H("24小时表现(%)"),
        CHANGE_1H("1小时表现(%)"),
        CHANGE_7D("7天表现(%)"),
        CHANGE_30D("30天表现(%)"),
        RSI_14("14天RSI"),
        FUNDING("资金费率"),
        VOLATILITY("日波动率(%)"),
    }

    private fun showMetricSheet() {
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.ph_metric_sheet, null, false)
        dialog.setContentView(view)
        val opts = mapOf(
            Metric.CHANGE_24H to view.findViewById<TextView>(R.id.metric_opt_24h),
            Metric.CHANGE_1H to view.findViewById<TextView>(R.id.metric_opt_1h),
            Metric.CHANGE_7D to view.findViewById<TextView>(R.id.metric_opt_7d),
            Metric.CHANGE_30D to view.findViewById<TextView>(R.id.metric_opt_30d),
            Metric.RSI_14 to view.findViewById<TextView>(R.id.metric_opt_rsi14),
            Metric.FUNDING to view.findViewById<TextView>(R.id.metric_opt_funding),
            Metric.VOLATILITY to view.findViewById<TextView>(R.id.metric_opt_vol),
        )
        opts.forEach { (metric, tv) ->
            tv.isSelected = (metric == currentMetric)
            tv.setOnClickListener {
                currentMetric = metric
                binding.phFilterRange.root.findViewById<TextView>(R.id.chip_text).text = metric.label
                applyData()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    // --- 业务映射：ticker → heatmap item ---

    private fun toHeatmapItem(ticker: CryptoTicker, metric: Metric, scale: ScaleBasis): HeatmapItem {
        val value = metricValue(ticker, metric)
        return HeatmapItem(
            label = ticker.symbol,
            shortLabel = baseAsset(ticker.symbol),
            primary = formatPrice(ticker.price),
            secondary = formatMetricValue(metric, value),
            weight = scaleWeight(ticker, scale),
            tileColor = metricTileColor(metric, value),
            payload = ticker,
        )
    }

    /**
     * 各指标的取值。除 24h 涨跌幅来自真实字段外，其余指标基于 ticker.symbol 的哈希做伪随机派生，
     * 仅作演示用 —— 实际接入 WS/REST 时应替换为后端字段。
     */
    private fun metricValue(t: CryptoTicker, m: Metric): Double {
        val seed = ((t.symbol.hashCode() and 0x7fffffff) % 1000) / 1000.0  // 0..1
        return when (m) {
            Metric.CHANGE_24H -> t.changePercent
            Metric.CHANGE_1H -> t.changePercent / (2 + seed * 4)
            Metric.CHANGE_7D -> t.changePercent * (1.5 + seed * 2)
            Metric.CHANGE_30D -> t.changePercent * (3 + seed * 3)
            Metric.RSI_14 -> (50 + t.changePercent * 4 + (seed - 0.5) * 20).coerceIn(5.0, 95.0)
            Metric.FUNDING -> t.changePercent / 100.0
            Metric.VOLATILITY -> kotlin.math.abs(t.changePercent) * (1.5 + seed)
        }
    }

    private fun formatMetricValue(m: Metric, v: Double): String = when (m) {
        Metric.CHANGE_24H,
        Metric.CHANGE_1H,
        Metric.CHANGE_7D,
        Metric.CHANGE_30D,
        Metric.VOLATILITY -> formatPercent(v)
        Metric.RSI_14 -> "%.1f".format(v)
        Metric.FUNDING -> {
            val sign = if (v >= 0) "+" else ""
            "$sign${"%.4f".format(v)}%"
        }
    }

    private fun metricTileColor(m: Metric, v: Double): Int {
        val res = when (m) {
            Metric.CHANGE_24H,
            Metric.CHANGE_1H,
            Metric.CHANGE_7D,
            Metric.CHANGE_30D -> changeColorRes(v)
            Metric.FUNDING -> changeColorRes(v * 1000.0)  // funding 数量级很小，放大后套用同一阈值
            Metric.RSI_14 -> when {
                v < 30 -> R.color.ph_tile_down_strong
                v < 45 -> R.color.ph_tile_down_weak
                v < 55 -> R.color.ph_tile_neutral
                v < 70 -> R.color.ph_tile_up_weak
                else -> R.color.ph_tile_up_strong
            }
            Metric.VOLATILITY -> when {
                v < 1 -> R.color.ph_tile_neutral
                v < 2 -> R.color.ph_tile_down_weak
                v < 4 -> R.color.ph_tile_down
                else -> R.color.ph_tile_down_strong
            }
        }
        return ContextCompat.getColor(this, res)
    }

    private fun changeColorRes(percent: Double): Int = when {
        percent <= -3.0 -> R.color.ph_tile_down_strong
        percent <= -1.0 -> R.color.ph_tile_down
        percent < 0.0 -> R.color.ph_tile_down_weak
        percent == 0.0 -> R.color.ph_tile_neutral
        percent < 1.0 -> R.color.ph_tile_up_weak
        percent < 3.0 -> R.color.ph_tile_up
        else -> R.color.ph_tile_up_strong
    }

    private fun baseAsset(symbol: String): String {
        val quotes = listOf("USDT", "USDC", "BUSD", "FDUSD", "TUSD", "DAI", "BTC", "ETH", "BNB", "TRY", "EUR")
        for (q in quotes) {
            if (symbol.length > q.length && symbol.endsWith(q)) return symbol.dropLast(q.length)
        }
        return symbol
    }

    private fun defaultBadge(symbol: String): String? = when {
        symbol.endsWith("USDT") || symbol.endsWith("USDC") -> "永续"
        else -> null
    }

    private fun formatPrice(p: Double): String = when {
        p == 0.0 -> "$0.00"
        p >= 1 -> "$" + "%.2f".format(p)
        p >= 0.01 -> "$" + "%.4f".format(p)
        else -> "$" + "%.6f".format(p)
    }

    private fun formatPercent(p: Double): String {
        val sign = if (p >= 0) "+" else ""
        return "$sign${"%.2f".format(p)}%"
    }

    private fun formatVolumeCNY(v: Double): String = when {
        v >= 1e8 -> "$" + "%.2f".format(v / 1e8) + "十亿"
        v >= 1e4 -> "$" + "%.2f".format(v / 1e4) + "万"
        else -> "$" + "%.2f".format(v)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    // --- Mock ticker data for the screen ---

    private data class CryptoTicker(
        val symbol: String,
        val price: Double,
        val changePercent: Double,
        val weight: Double,
        val volume: Double = 0.0,
        val contractType: String? = null,
    )

    // volume 单位：美元。按真实币安 24h 成交量比例分布：
    // BTC:ETH ≈ 1.4:1，BTC 占总和 ~28%，与币安截图布局接近（BTC tile 占屏幕高度 ~25-30%）。
    private fun sampleTickers(): List<CryptoTicker> = listOf(
        CryptoTicker("BTCUSDT",   76900.00, -1.51,  18.0, volume = 18.0e8),
        CryptoTicker("ETHUSDT",    2115.82, -3.27,  13.0, volume = 13.0e8),
        CryptoTicker("BTCUSDC",   76869.80, -1.53,   8.0, volume =  8.0e8),
        CryptoTicker("ETHUSDC",    2115.23, -3.28,   3.0, volume =  3.0e8),
        CryptoTicker("SOLUSDT",      84.58, -2.51,   2.5, volume =  2.5e8),
        CryptoTicker("XRPUSDT",       2.215,-1.78,   2.2, volume =  2.2e8),
        CryptoTicker("DOGEUSDT",      0.165,-2.41,   2.0, volume =  2.0e8),
        CryptoTicker("BNBUSDT",     598.10, -1.05,   1.5, volume =  1.5e8),
        CryptoTicker("HYPEUSDT",     32.55,  5.80,   1.4, volume =  1.4e8),
        CryptoTicker("ADAUSDT",       0.715,-2.12,   1.2, volume =  1.2e8),
        CryptoTicker("AVAXUSDT",     21.40, -3.51,   1.0, volume =  1.0e8),
        CryptoTicker("LINKUSDT",     14.92, -1.10,   0.9, volume =  0.9e8),
        CryptoTicker("XAUUSDT",    3025.10, -0.12,   0.8, volume =  0.8e8),
        CryptoTicker("ZECUSDT",     528.36,  1.55,   0.7, volume =  0.7e8),
        CryptoTicker("LTCUSDT",      88.10, -1.95,   0.7, volume =  0.7e8),
        CryptoTicker("TRXUSDT",       0.234, 0.45,   0.6, volume =  0.6e8),
        CryptoTicker("DOTUSDT",       4.180,-2.81,   0.55, volume = 0.55e8),
        CryptoTicker("UNIUSDT",       7.420, 2.30,   0.5,  volume = 0.5e8),
        CryptoTicker("EDENUSDT",      0.082, 2.10,   0.5,  volume = 0.5e8),
        CryptoTicker("NEARUSDT",      3.890,-3.10,   0.45, volume = 0.45e8),
        CryptoTicker("TONUSDT",       2.910,-1.80,   0.4,  volume = 0.4e8),
        CryptoTicker("ATOMUSDT",      5.230,-1.55,   0.4,  volume = 0.4e8),
        CryptoTicker("APTUSDT",       6.910, 0.60,   0.35, volume = 0.35e8),
        CryptoTicker("FILUSDT",       4.620,-0.95,   0.3,  volume = 0.3e8),
        CryptoTicker("PEPEUSDT",      0.0000076, 1.10, 0.3, volume = 0.3e8),
        CryptoTicker("ARBUSDT",       0.812, 2.75,   0.3,  volume = 0.3e8),
        CryptoTicker("ICPUSDT",       7.110,-1.40,   0.28, volume = 0.28e8),
        CryptoTicker("SHIBUSDT",      0.0000156,-2.00, 0.25, volume = 0.25e8),
        CryptoTicker("INJUSDT",       9.230,-2.40,   0.25, volume = 0.25e8),
        CryptoTicker("WIFUSDT",       0.910, 4.20,   0.22, volume = 0.22e8),
        CryptoTicker("OPUSDT",        1.620,-0.55,   0.22, volume = 0.22e8),
        CryptoTicker("AAVEUSDT",    180.10,  3.10,   0.2,  volume = 0.2e8),
        CryptoTicker("SUIUSDT",       3.450, 1.95,   0.2,  volume = 0.2e8),
        CryptoTicker("BCHUSDT",     312.40, -1.20,   0.18, volume = 0.18e8),
        CryptoTicker("APEUSDT",       0.612, 0.40,   0.16, volume = 0.16e8),
        CryptoTicker("ETCUSDT",      19.20, -2.10,   0.16, volume = 0.16e8),
        CryptoTicker("MKRUSDT",    1450.00, -0.80,   0.15, volume = 0.15e8),
        CryptoTicker("LDOUSDT",       1.085,-1.55,   0.14, volume = 0.14e8),
        CryptoTicker("RUNEUSDT",      1.420, 0.90,   0.13, volume = 0.13e8),
        CryptoTicker("SANDUSDT",      0.310,-1.10,   0.12, volume = 0.12e8),
        CryptoTicker("FETUSDT",       0.842,-3.40,   0.12, volume = 0.12e8),
        CryptoTicker("XAGUSDT",      33.10,  0.88,   0.11, volume = 0.11e8),
        CryptoTicker("BSB",           1.230, 3.40,   0.10, volume = 0.10e8, contractType = "现货"),
        CryptoTicker("EOSUSDT",       0.612, 0.25,   0.09, volume = 0.09e8),
        CryptoTicker("ALGOUSDT",      0.218,-1.80,   0.08, volume = 0.08e8),
        CryptoTicker("SEIUSDT",       0.231, 1.30,   0.07, volume = 0.07e8),
        CryptoTicker("XLMUSDT",       0.282,-2.20,   0.06, volume = 0.06e8),
        CryptoTicker("ZILUSDT",       0.0182, 0.65,  0.05, volume = 0.05e8),
        CryptoTicker("EGLDUSDT",     27.30, -1.45,   0.045,volume = 0.045e8),
        CryptoTicker("GALAUSDT",      0.0205, 2.10,  0.04, volume = 0.04e8),
    )
}
