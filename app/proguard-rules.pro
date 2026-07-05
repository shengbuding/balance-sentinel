# === kotlinx.serialization ===
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep data model classes used with kotlinx.serialization
-keep class com.balancesentinel.app.data.model.** {
    *;
}

# === OkHttp ===
-dontwarn okhttp3.internal.platform.**
-keep class okhttp3.** { *; }

# === EncryptedSharedPreferences / Tink ===
-keep class androidx.security.crypto.** { *; }
-dontwarn com.google.errorprone.annotations.**

# === Widget classes ===
-keep class com.balancesentinel.app.widget.** { *; }

# === Compose (safe mode) ===
-keep class androidx.compose.** { *; }
