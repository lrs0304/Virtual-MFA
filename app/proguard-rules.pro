# Add project specific ProGuard rules here.

-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses

# Gson
-keep class com.google.gson.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
# Backup payload classes (Gson reflection)
-keep class com.risonliang.mfa.data.BackupCodec$* { *; }

# ZXing
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.barcodescanner.** { *; }

# Keep model used by Gson
-keep class com.risonliang.mfa.model.** { *; }

# 保留行号方便排查
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile