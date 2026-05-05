# R8 / ProGuard rules for Ultiq release builds.
# isMinifyEnabled = true uses these on top of proguard-android-optimize.txt.

# --- Strip debug & verbose logs from release ----------------------------------
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}

# --- Keep Retrofit interfaces & their annotations -----------------------------
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response

-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>

-keep class kotlin.Metadata { *; }

# --- Keep Gson model fields (Retrofit DTOs are deserialised by reflection) ---
-keep class com.ultiq.app.data.remote.dto.** { *; }
-keepclassmembers class com.ultiq.app.data.remote.dto.** {
    <fields>;
    <init>(...);
}

# --- Room entities and DAOs ---------------------------------------------------
-keep class com.ultiq.app.data.local.entity.** { *; }
-keep class com.ultiq.app.data.local.dao.** { *; }
-keep @androidx.room.* class * { *; }
-keep class * extends androidx.room.RoomDatabase

# --- OkHttp / OkHttp SSE -----------------------------------------------------
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Tink (used by androidx.security.crypto) ---------------------------------
# Tink references errorprone annotations that aren't on the runtime classpath.
-dontwarn com.google.errorprone.annotations.**

# --- SQLCipher --------------------------------------------------------------
# SupportOpenHelperFactory + native bindings need their classes preserved.
-keep class net.zetetic.database.sqlcipher.** { *; }
-keep class net.sqlcipher.** { *; }
-dontwarn net.zetetic.database.**

# --- Compose / Kotlinx coroutines --------------------------------------------
-keepclasseswithmembernames class * {
    native <methods>;
}
-dontwarn kotlinx.coroutines.debug.**

# --- WorkManager workers (instantiated by reflection) ------------------------
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker

# --- BroadcastReceivers / Services in manifest -------------------------------
-keep class com.ultiq.app.receiver.** { *; }
-keep class com.ultiq.app.service.** { *; }
-keep class com.ultiq.app.ui.lockout.LockoutDeviceAdminReceiver { *; }

# --- App entry points ---------------------------------------------------------
-keep class com.ultiq.app.UltiqApp { *; }
-keep class com.ultiq.app.MainActivity { *; }
