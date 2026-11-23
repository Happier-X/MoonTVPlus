# Native Shell AAR: JNI 和反射入口全部保留
-keep class com.longbridge.nsh.** { *; }
-keepclasseswithmembers class * { native <methods>; }
-keepclassmembers class * { native <methods>; }
-dontwarn com.longbridge.nsh.**

# Media3 / ExoPlayer 的反射和播放器扩展
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# 宿主组件与桥
-keep class com.moontvplus.nshhost.** { *; }

# Kotlin / AndroidX 常见反射告警
-dontwarn org.jetbrains.annotations.**
