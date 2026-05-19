# 公开 API 在使用方主工程可能被反射或通过 inflate 拿到，保留公共类不混淆。
-keep public class com.example.heatmap.HeatmapTreemapView { *; }
-keep public class com.example.heatmap.HeatmapItem { *; }
