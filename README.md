# HeatmapTreemapView

一个轻量级的 Android 自定义 View，基于 **squarified treemap**（近似正方形树状图）算法渲染一组带权重的彩色方块 —— 典型用途：仿币安/交易所的"加密货币热力图"。

仓库结构：

```
phview/
├── app/        演示应用（com.example.phview，PHActivity 仿币安热力图页面）
└── heatmap/    独立的 library 模块（com.example.heatmap），即本组件
```

`heatmap` 模块零业务依赖：不引用任何 `R.*` / `androidx.*` / `Material*`，只依赖 Kotlin stdlib + Android framework，可以直接抽出到独立工程。

---

## 一、引入

当前以源码模块形式接入，在使用方工程的 `settings.gradle`：

```groovy
include ':heatmap'
project(':heatmap').projectDir = file('/path/to/phview/heatmap')
```

然后在 `app/build.gradle`：

```groovy
dependencies {
    implementation project(':heatmap')
}
```

> 后续如发布到 Maven，预留的 `consumer-rules.pro` 会自动保留 `HeatmapTreemapView` / `HeatmapItem` 两个公开类不被混淆。

---

## 二、最小可用示例

### 布局 XML

```xml
<com.example.heatmap.HeatmapTreemapView
    android:id="@+id/treemap"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
```

### 代码

```kotlin
val view = findViewById<HeatmapTreemapView>(R.id.treemap)

view.setData(listOf(
    HeatmapItem(
        label = "BTCUSDT",
        shortLabel = "BTC",
        primary = "$76900.00",
        secondary = "-1.51%",
        weight = 18.0,
        tileColor = 0xFFF1A4B0.toInt(),
    ),
    HeatmapItem(
        label = "ETHUSDT",
        shortLabel = "ETH",
        primary = "$2115.82",
        secondary = "-3.27%",
        weight = 13.0,
        tileColor = 0xFFEA5A6F.toInt(),
    ),
    // ...
))
```

就是这两个类的全部 API。**View 完全与业务无关** —— 货币符号、百分号、配色、币种命名规则全部由调用方决定。

---

## 三、`HeatmapItem` 字段含义

| 字段 | 类型 | 说明 |
|---|---|---|
| `label` | `String` | tile 内主标签文本，必填 |
| `shortLabel` | `String?` | 兜底文本：当 `label` 缩到下限字号仍放不下时退化用，典型如 `BTCUSDT` → `BTC` |
| `primary` | `String?` | 描述第一行，通常是价格 |
| `secondary` | `String?` | 描述第二行，通常是涨跌幅 |
| `weight` | `Double` | 用于 squarified 布局的相对面积权重，**必须 > 0** 否则被过滤 |
| `tileColor` | `Int` | tile 背景填充色（ARGB），由调用方按业务规则决定（如按涨跌幅映射颜色阶） |
| `payload` | `Any?` | 调用方任意附带数据，会原样回传到 `onTileClick` |

---

## 四、点击事件

```kotlin
view.onTileClick = { item, rectInView ->
    // item 是 setData 传入的对象本身；rectInView 是该 tile 在 View 坐标系下的矩形，可用来定位浮窗
    val ticker = item.payload as? MyTicker ?: return@onTileClick
    showPopup(ticker, rectInView)
}
```

`rectInView` 提供给调用方做 PopupWindow / 弹层定位时的依据。

---

## 五、强制顶部行结构（`topRowSizes`）

纯 squarified 算法在不同数据比例下，顶部几行的"每行 tile 数量"会变化。如果产品上希望**始终是固定结构**（如币安那样：第 1 行 1 个、第 2 行 2 个），可用：

```kotlin
view.topRowSizes = intArrayOf(1, 2)
```

效果：

- 按权重降序后的前 1 个 item 独占第 1 行（全宽）
- 第 2、3 个 item 共享第 2 行（按各自权重比例分宽）
- 第 4+ 个 item 走标准 squarified

`topRowSizes = intArrayOf()` 表示完全交给算法自适应（默认）。

---

## 六、可定制的视觉参数

全部以 `var` 暴露，可在任意时机修改（颜色/线宽类的 setter 会自动重绘）：

```kotlin
view.tileCornerRadius          = dp(8f)
view.tileGap                   = dp(3f)
view.tilePaddingHorizontal     = dp(10f)
view.tilePaddingVertical       = dp(6f)
view.descriptionLineSpacing    = dp(2f)

view.labelTextSizeScale        = 7.5f      // size = clamp(tileHeight / scale, min, max)
view.labelTextSizeMin          = sp(10f)
view.labelTextSizeMax          = sp(22f)
view.descriptionTextSizeScale  = 14.5f
view.descriptionTextSizeMin    = sp(9f)
view.descriptionTextSizeMax    = sp(13f)

view.labelTextColor            = Color.WHITE
view.descriptionTextColor      = Color.WHITE
view.tileStrokeColor           = 0x10000000
view.tileStrokeWidth           = dp(0.5f)
```

默认值已按币安热力图风格调过，开箱即用。

---

## 七、文本绘制策略（自动响应空间）

每个 tile 绘制时按可用宽高自动选择最佳呈现，4 级递进截断（参考币安 `SpotMarketPairTreeMapView.e()` 逻辑）：

1. 默认渲染 label（最多 2 行折行）+ primary + secondary
2. 若可用高度不够 → 丢掉 primary，只保留 secondary
3. 还是不够 → label 退化为 `shortLabel`
4. 仍然不够 → 丢掉描述，只画 label

横向方面：

- 描述行如果超宽，会先把字号缩到 `descriptionTextSizeMin`；仍放不下则整组丢弃
- 标签如果超宽，也会按"最宽行"收缩字号到 `labelTextSizeMin`
- 绘制前 `clipRect(tile)` 兜底，绝不溢出 tile

---

## 八、刷新数据

```kotlin
view.setData(newList)
```

内部会按 `weight` 倒序、过滤 `weight <= 0`、立即重新布局并重绘。每次都是全量替换。

---

## 九、性能注意事项

> WebSocket 行情 1Hz 推送、≤100 tiles 的场景实测 onDraw 通常 < 5ms，渲染本身没问题。
> 主要开销在每帧分配（`HeatmapItem` / 中间 `List` / `measureText`）带来的 GC 抖动。

如需进一步优化：

- 数据高频更新时在调用方做节流/合批（`Flow.conflate()` / `sample(...)`）后再 `setData`
- 调用方复用 StringBuilder 而非 `"%.2f".format(...)` 减少短命 String
- 若只是颜色/文本变、权重不变，未来可考虑添加 `updateValues()` API 跳过排序/布局，本仓库当前未实现

---

## 十、与币安热力图的对照

`app` 模块 `PHActivity.kt` 是一份完整的对照实现 —— 演示了：

- 通过 `payload` 字段把行业务 ticker 透传回点击回调
- 4 个底部弹窗（类型 / 规模依据 / 指标 / 范围）如何切换 ScaleBasis、Metric、Category、Scope 后调用 `setData`
- tile 点击弹出带三角指向的浮窗
- 涨跌幅、RSI、波动率、资金费率等不同指标的颜色阈值映射
- 设置 `topRowSizes = intArrayOf(1, 2)` 复现币安"BTC 单行 + ETH/BTCUSDC 共享行"的稳定结构

接入真实行情时，只需把 `PHActivity.sampleTickers()` 替换为后端数据源即可，library 模块不需要任何改动。
