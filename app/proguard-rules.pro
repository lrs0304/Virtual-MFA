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

# AndroidX Security
-keep class androidx.security.** { *; }

# Keep model used by Gson
-keep class com.risonliang.mfa.model.** { *; }

# Google Tink （androidx.security.crypto 依赖）引用了 JSR-305 编译期注解，
# 这些注解在 Android 运行时不存在，需告知 R8 忽略缺失。
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**
-dontwarn com.google.errorprone.annotations.**

# Tink 的 KeysDownloader 引用了 google-http-client 和 joda-time，
# 仅用于远程下载公钥；本应用为纯本地 MFA，无网络权限，永远不会触发该代码路径。
# 因此忽略这些可选依赖的缺失即可，无需引入对应库。
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**

# 同时显式丢弃未被使用的 KeysDownloader，防止其成为根引用
-dontwarn com.google.crypto.tink.util.KeysDownloader
-dontwarn com.google.crypto.tink.util.KeysDownloader$**

# 保留 Tink 反射所需的类与成员
-keep class com.google.crypto.tink.** { *; }
-keep class com.google.crypto.tink.proto.** { *; }
-keep class com.google.crypto.tink.shaded.protobuf.** { *; }

# 保留行号方便排查
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile