# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------
# Keep rules for enabling R8/minify (isMinifyEnabled = true) on the release
# build. UNTESTED - verify a release build on a real device (network
# deserialization + orbit propagation are the risky paths) BEFORE flipping
# isMinifyEnabled to true in build.gradle.kts. Left commented on purpose.
# ---------------------------------------------------------------------------
#
# # Keep generic signatures / annotations Moshi & Retrofit rely on.
# -keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
#
# # Moshi (codegen adapters + reflective fallback)
# -keep class **JsonAdapter { *; }
# -keepclassmembers class * { @com.squareup.moshi.* <methods>; }
# -keep @com.squareup.moshi.JsonClass class * { *; }
# -dontwarn com.squareup.moshi.**
#
# # Retrofit / OkHttp
# -dontwarn retrofit2.**
# -dontwarn okhttp3.**
# -dontwarn okio.**
# -keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
#
# # DTOs deserialized by name + domain models referenced reflectively
# -keep class pl.put.observationcompanion.data.remote.dto.** { *; }
#
# # predict4java (SGP4/SDP4 propagator)
# -keep class uk.me.g4dpz.satellite.** { *; }
# -dontwarn uk.me.g4dpz.satellite.**
#
# # osmdroid
# -dontwarn org.osmdroid.**
#
# # Room generated implementations are kept by the Room consumer rules.
