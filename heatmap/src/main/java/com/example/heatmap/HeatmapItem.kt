package com.example.heatmap

/**
 * [HeatmapTreemapView] 渲染的单个数据项。
 *
 * View 本身与业务无关，只画带颜色的圆角矩形与文字；所有业务相关的格式化
 * （货币符号、百分号、配色、币种命名规则等）都在调用方构造本对象时完成。
 *
 * @property label tile 中绘制的主标签文本，例如 `"BTCUSDT"`。
 * @property shortLabel 当 [label] 缩到下限字号仍放不下、且 tile 又装不下描述行时，
 *  作为退化兜底显示的更短文本（例如 `"BTC"`）。可为 `null`。
 * @property primary 描述区的第一行（通常是格式化后的价格）。`null` 表示不显示。
 * @property secondary 描述区的第二行（通常是格式化后的涨跌幅）。`null` 表示不显示。
 * @property weight 用于 squarified 布局的相对面积权重，**必须大于 0**，否则会被过滤掉。
 * @property tileColor tile 背景填充色（ARGB），由调用方根据业务规则（涨跌、品类等）决定。
 * @property payload 调用方任意附带数据，会原样回传到 [HeatmapTreemapView.onTileClick]。
 */
data class HeatmapItem(
    val label: String,
    val shortLabel: String? = null,
    val primary: String? = null,
    val secondary: String? = null,
    val weight: Double,
    val tileColor: Int,
    val payload: Any? = null,
)
