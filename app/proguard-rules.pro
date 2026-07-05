# ============================================================
# 钱包哨兵 — ProGuard / R8 混淆规则
# 适用: R8 full mode (isMinifyEnabled=true + proguard-android-optimize.txt)
# ============================================================

# === 通用 Android 保留 ===
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepattributes Exceptions,LineNumberTable,SourceFile
-renamesourcefileattribute SourceFile
-keepattributes SourceFile

# === kotlinx.serialization ===
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# @Serializable model classes: data.model + data.repository (AppConfig, DataExport etc.)
-keep class com.balancesentinel.app.data.model.** {
    *;
}
-keep @kotlinx.serialization.Serializable class com.balancesentinel.app.data.** {
    *;
}

# kotlinx.serialization generated serializers (needed for ListSerializer etc.)
-keep,includedescriptorclasses class com.balancesentinel.app.**$$serializer { *; }
-keepclassmembers class com.balancesentinel.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.balancesentinel.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# === kotlinx.coroutines ===
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.coroutines.** {
    volatile <fields>;
}
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# === OkHttp ===
-dontwarn okhttp3.internal.platform.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# === AndroidX Security / EncryptedSharedPreferences / Tink ===
-keep class androidx.security.crypto.** { *; }
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.crypto.tink.**

# === Compose (targeted rules — narrower than blanket keep) ===
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.graphics.** { *; }
-keep class androidx.compose.ui.text.** { *; }
-keep class androidx.compose.foundation.** { *; }
-keep class androidx.compose.material3.** { *; }
-keep class androidx.compose.material.icons.** { *; }
-keep class androidx.compose.ui.tooling.preview.** { *; }

# Compose runtime: keep Composable functions and their state
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
-keepclassmembers class * {
    @androidx.compose.ui.tooling.preview.Preview <methods>;
}

# === AndroidX Lifecycle (ViewModel constructors accessed via reflection) ===
-keep class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keep class * extends androidx.lifecycle.AndroidViewModel {
    <init>(...);
}
-keep class androidx.lifecycle.** { *; }

# === AndroidX Activity / Core ===
-keep class androidx.activity.** { *; }
-keep class androidx.core.** { *; }

# === Manifest-registered components (Android instantiates via reflection) ===
# Application
-keep class com.balancesentinel.app.DeepSeekApp { *; }

# Activities
-keep class com.balancesentinel.app.MainActivity { *; }
-keep class com.balancesentinel.app.widget.WidgetConfigActivity { *; }

# Widget AppWidgetProvider receivers (5 sizes)
-keep class com.balancesentinel.app.widget.StaticWidgetProvider { *; }
-keep class com.balancesentinel.app.widget.StaticWidgetProvider_2x1 { *; }
-keep class com.balancesentinel.app.widget.StaticWidgetProvider_2x2 { *; }
-keep class com.balancesentinel.app.widget.StaticWidgetProvider_3x1 { *; }
-keep class com.balancesentinel.app.widget.StaticWidgetProvider_4x2 { *; }
-keep class com.balancesentinel.app.widget.StaticWidgetProvider_5x1 { *; }

# Widget helper classes
-keep class com.balancesentinel.app.widget.BalanceWidgetDataStore { *; }
-keep class com.balancesentinel.app.widget.SparklineDrawer { *; }
-keep class com.balancesentinel.app.widget.WidgetConfigStore { *; }
-keep class com.balancesentinel.app.widget.WidgetErrorLogger { *; }

# BroadcastReceivers
-keep class com.balancesentinel.app.receiver.BootReceiver { *; }
-keep class com.balancesentinel.app.receiver.KeepAliveReceiver { *; }
-keep class com.balancesentinel.app.receiver.MidnightReceiver { *; }
-keep class com.balancesentinel.app.receiver.SnoozeReceiver { *; }

# Service
-keep class com.balancesentinel.app.service.BalanceRefreshService { *; }

# === Repository / Engine layers (keep public APIs, R8 optimizes internals) ===
-keep class com.balancesentinel.app.data.repository.** { public *; }
-keep class com.balancesentinel.app.data.engine.** { public *; }
-keep class com.balancesentinel.app.data.api.** { public *; }

# === Utility classes used across modules ===
-keep class com.balancesentinel.app.data.util.Logger { *; }
-keep class com.balancesentinel.app.util.FormatUtils { *; }
-keep class com.balancesentinel.app.CrashLogger { *; }

# === kotlin.Metadata (required for reflection-based kotlin features) ===
-keep class kotlin.Metadata { *; }

# === R8 optimization tweaks ===
# Allow merging to reduce method count
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizationpasses 5
# Merge classes where safe
-allowaccessmodification
# Keep resource names for Compose
-keepclassmembers class **.R$* {
    public static <fields>;
}

# === Remove logging in release (optional — keep for crash diagnosis) ===
# -assumenosideeffects class android.util.Log {
#     public static int v(...);
#     public static int d(...);
# }

# === Suppress warnings for unused/optional dependencies ===
-dontwarn javax.annotation.**
-dontwarn java.lang.invoke.**
-dontwarn com.sun.**
-dontwarn org.jetbrains.annotations.**
