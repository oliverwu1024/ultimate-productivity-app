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

# --- Update-banner manifest (Gson-deserialised, lives outside dto package) ---
# Without this, R8 renames `versionCode` → `a`, Gson can't bind, the value
# parses as 0, and the in-app update banner silently never shows.
-keep class com.ultiq.app.util.VersionManifest { *; }

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

# --- MediaPipe Tasks Audio (YAMNet snore + cough detection, Phase 10) --------
# In v2.11.0 these rules were insufficient — release-build snore/cough
# detection silently failed because R8 was stripping the AutoValue-generated
# subclasses MediaPipe uses for nearly every result/options object. Debug
# builds (R8 off) worked; release builds (R8 on) didn't fire any events.
# v2.11.1 widens the net to the entire mediapipe namespace + AutoValue +
# TFLite + protobuf.
-dontwarn javax.lang.model.**
-dontwarn autovalue.shaded.**
-dontwarn com.google.auto.value.**
-dontwarn com.google.mediapipe.proto.**
-dontwarn com.google.errorprone.annotations.**

# Keep the entire MediaPipe namespace. AudioClassifier results
# (AudioClassifierResult / ClassificationResult / Classifications / Category)
# are AutoValue-generated; the public classes are reachable but the
# generated AutoValue_* subclasses get pruned unless we keep everything.
-keep class com.google.mediapipe.** { *; }
-keepclassmembers class com.google.mediapipe.** { *; }

# AutoValue — MediaPipe's entire result + options object graph uses these.
# Keep both the visible AutoValue classes AND the underscore-prefixed
# generated implementations that AutoValue's factory methods instantiate.
-keep class **AutoValue_** { *; }
-keep class **$AutoValue_** { *; }
-keep class **AutoOneOf_** { *; }
-keepclassmembers class **AutoValue_** { *; }
-keepclassmembers class **$AutoValue_** { *; }
-keep @com.google.auto.value.AutoValue class * { *; }
-keep @com.google.auto.value.AutoOneOf class * { *; }
-keep class com.google.auto.value.** { *; }

# TensorFlow Lite — YAMNet model loads + runs via the TFLite interpreter,
# which MediaPipe instantiates from native code via JNI. The interpreter
# class + its delegate classes (XNNPACK / GPU / NNAPI) must survive R8.
-keep class org.tensorflow.lite.** { *; }
-keep class * extends org.tensorflow.lite.Interpreter
-dontwarn org.tensorflow.lite.**

# Protobuf — MediaPipe's options / config types serialise through
# proto-lite. R8 strips fields without these explicit keeps.
-keep class com.google.protobuf.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessage { <fields>; }
-dontwarn com.google.protobuf.**

# androidx + jspecify annotations referenced by MediaPipe public API.
-keep class androidx.annotation.** { *; }
-dontwarn org.jspecify.annotations.**
-dontwarn org.checkerframework.**
