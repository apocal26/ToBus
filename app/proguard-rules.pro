# Keep readable stack traces for release diagnostics
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Android AppWidget providers & RemoteViewsService
-keep public class com.example.widget.** { *; }

# MapLibre Native / JNI callbacks & reflection hooks
-keep class org.maplibre.android.** { *; }
-keep class org.maplibre.geojson.** { *; }
-dontwarn org.maplibre.**

# OkHttp / Okio optional JVM/platform integrations
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
