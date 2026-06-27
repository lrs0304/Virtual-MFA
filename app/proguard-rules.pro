# Add project specific ProGuard rules here.

-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses

# ZXing
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.barcodescanner.** { *; }

# 保留行号方便排查
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile