# Add project specific ProGuard rules here.

-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses

# ============================================================================
# ZXing 核心：实时扫码（ScanActivity）走 DefaultDecoderFactory ->
# MultiFormatReader -> 反射加载 QRCodeReader，相册识图（MainActivity）直接
# new QRCodeReader()。两条路径都依赖 ZXing 的反射机制，因此必须 keep 完整：
#  - Reader 接口及其实现类（MultiFormatReader 内部按 BarcodeFormat 反射创建）；
#  - Result / ResultPoint / ResultPointCallback（解码结果与回调通过 zxing 内部
#    Handler.post(Message) 反射读取字段）；
#  - BarcodeFormat / DecodeHintType 枚举（hints 传参依赖枚举常量）。
# 经实测：仅 keep QRCodeReader 时 release 包能识别相册图，但实时扫码完全失效，
# 且 ZXing 内部 catch 掉所有 ReaderException，logcat 不会有任何报错。
# ============================================================================
-keep class com.google.zxing.** { *; }
-keep interface com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# ============================================================================
# zxing-android-embedded：BarcodeView / DecoderThread / DefaultDecoderFactory
# 之间通过 Handler + Message.what 通信，并通过反射读取 BarcodeResult 字段。
# 任意一个相关类被裁/混淆都会导致扫码静默失败，因此整体 keep。
# 包体影响很小：本来就只引了实际用到的几个类。
# ============================================================================
-keep class com.journeyapps.barcodescanner.** { *; }
-keep interface com.journeyapps.barcodescanner.** { *; }
-dontwarn com.journeyapps.barcodescanner.**

# ML Kit 自身随 AAR 携带 consumer-rules，这里仅补一条 dontwarn 兜底，
# 避免 release 构建时第三方依赖的可选类报警告。
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# 保留行号方便排查
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile