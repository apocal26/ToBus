# Keep readable stack traces for release diagnostics
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# App metadata & updater FileProvider
-keep class com.example.BuildConfig { *; }
-keep class androidx.core.content.FileProvider { *; }

# Android AppWidget providers & RemoteViewsService
-keep public class com.example.widget.** { *; }

# MapLibre Native / JNI callbacks & reflection hooks
-keep class org.maplibre.android.** { *; }
-keep class org.maplibre.geojson.** { *; }
-dontwarn org.maplibre.**

# Kotlin Coroutines (ServiceLoader & FastServiceLoader)
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# OkHttp / Okio optional JVM/platform integrations
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
