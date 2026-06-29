# Add project specific ProGuard rules here.

-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses

# ============================================================================
# 注：历史上这里有大量 -keep com.google.zxing.** / com.journeyapps.** 规则，
# 因为 zxing-android-embedded 的 BarcodeView -> DecoderThread -> Handler 链路
# 严重依赖反射，R8 fullMode 下会被静默裁掉导致扫码失效。
# A 方案重写后已迁移到 CameraX + ML Kit，不再依赖 zxing 任何反射机制，
# 因此整段规则已删除。
# CameraX 与 ML Kit 自身随 AAR 携带 consumer-rules，无需在此重复声明。
# ============================================================================

# ML Kit 自身随 AAR 携带 consumer-rules，这里仅补一条 dontwarn 兜底，
# 避免 release 构建时第三方依赖的可选类报警告。
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# 保留行号方便排查
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile