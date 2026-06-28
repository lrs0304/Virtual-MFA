# Add project specific ProGuard rules here.

-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses

# ZXing：仅保留反射会用到的内容，让 R8 剔除未使用的 Reader/Writer
# （MultiFormatReader 内部用 Class.forName 加载各码制 Reader，而本项目仅用 QRCodeReader，
#  因此无需 keep 整个 zxing 包，可显著缩减包体）
-keep class com.google.zxing.qrcode.QRCodeReader { *; }
-keep class com.google.zxing.BarcodeFormat { *; }
-keep class com.google.zxing.DecodeHintType { *; }
-dontwarn com.google.zxing.**

# zxing-android-embedded 内部 Camera 相关回调通过反射调用，需要保留
-keep class com.journeyapps.barcodescanner.BarcodeView { *; }
-keep class com.journeyapps.barcodescanner.ViewfinderView { *; }
-keep class com.journeyapps.barcodescanner.DecoratedBarcodeView { *; }
-keep class com.journeyapps.barcodescanner.camera.** { *; }
-dontwarn com.journeyapps.barcodescanner.**

# ML Kit 自身随 AAR 携带 consumer-rules，这里仅补一条 dontwarn 兜底，
# 避免 release 构建时第三方依赖的可选类报警告。
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# 保留行号方便排查
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile