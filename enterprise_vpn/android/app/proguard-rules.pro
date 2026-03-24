# Enterprise VPN ProGuard Configuration
# Production-ready obfuscation and optimization rules

# ============================================
# OPTIMIZATION SETTINGS
# ============================================

# Enable aggressive optimization
-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontpreverify
-verbose

# Optimization settings
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*,!code/allocation/variable
-allowaccessmodification

# ============================================
# KEEP RULES - APPLICATION CORE
# ============================================

# Keep the application class
-keep class com.enterprise.vpn.EnterpriseVpnApp { *; }

# Keep all native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep VPN Service components
-keep class com.enterprise.vpn.service.** { *; }
-keep class com.enterprise.vpn.receiver.** { *; }
-keep class com.enterprise.vpn.widget.** { *; }

# Keep JNI bridge classes
-keep class com.enterprise.vpn.jni.** { *; }
-keepclassmembers class com.enterprise.vpn.jni.** {
    native <methods>;
}

# ============================================
# KEEP RULES - VPN ENGINE (Tun2Socks/Xray)
# ============================================

# Keep all tun2socks classes
-keep class org.time4cat.tun2socks.** { *; }
-keep class com.github.shadowsocks.** { *; }

# Keep Xray-core classes
-keep class com.xray.** { *; }
-keep class io.xray.** { *; }

# Keep packet processing classes
-keep class **.packet.** { *; }
-keep class **.tun.** { *; }

# ============================================
# KEEP RULES - DATA MODELS
# ============================================

# Keep all data classes for JSON serialization
-keep class com.enterprise.vpn.data.model.** { *; }

# Keep Parcelable implementations
-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}

# Keep Serializable implementations
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ============================================
# KEEP RULES - KOTLIN
# ============================================

# Keep Kotlin metadata
-keep class kotlin.Metadata { *; }
-keep class kotlin.** { *; }
-dontwarn kotlin.**

# Keep Kotlin coroutines
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Keep Kotlin serialization
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep KotlinParcelize
-keep @kotlinx.parcelize.Parcelize class * implements android.os.Parcelable

# ============================================
# KEEP RULES - HILT/DAGGER
# ============================================

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# Hilt generated classes
-keep,allowobfuscation,allowshrinking class com.enterprise.vpn.di.** { *; }
-keep,allowobfuscation,allowshrinking class * extends dagger.hilt.android.internal.managers.ComponentSupplier { *; }

# ============================================
# KEEP RULES - RETROFIT/OKHTTP
# ============================================

# Retrofit
-keepattributes Signature
-keepattributes *Annotation*
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }

# Keep response models
-keep class com.enterprise.vpn.data.api.response.** { *; }
-keep class com.enterprise.vpn.data.api.request.** { *; }

# ============================================
# KEEP RULES - ROOM DATABASE
# ============================================

-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ============================================
# KEEP RULES - FLUTTER
# ============================================

# Keep Flutter plugin classes
-keep class io.flutter.app.** { *; }
-keep class io.flutter.plugin.** { *; }
-keep class io.flutter.util.** { *; }
-keep class io.flutter.view.** { *; }
-keep class io.flutter.** { *; }
-keep class io.flutter.plugins.** { *; }

# Keep MethodChannel handlers
-keep class * implements io.flutter.plugin.common.MethodCallHandler { *; }
-keep class * implements io.flutter.plugin.common.EventChannel$StreamHandler { *; }

# ============================================
# KEEP RULES - SECURITY
# ============================================

# Keep encryption classes
-keep class com.enterprise.vpn.security.** { *; }
-keep class javax.crypto.** { *; }
-keep class java.security.** { *; }

# ============================================
# REMOVE LOGGING IN RELEASE
# ============================================

-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
}

-assumenosideeffects class timber.log.Timber {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
}

# ============================================
# CRASHLYTICS
# ============================================

-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Throwable
-keep class com.crashlytics.** { *; }
-dontwarn com.crashlytics.**

# ============================================
# GENERAL OPTIMIZATIONS
# ============================================

# Remove unused resources
-shrinkResources true

# Remove System.out calls
-assumenosideeffects class java.io.PrintStream {
    public *** println(...);
    public *** print(...);
}

# Remove debug asserts
-assumenosideeffects class android.os.Debug {
    public static *** isDebuggerConnected(...);
}

# ============================================
# WARNINGS
# ============================================

-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn edu.umd.cs.findbugs.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn org.checkerframework.**

# ============================================
# CUSTOM RULES
# ============================================

# Keep any class with native methods that might be called from JNI
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep enum classes
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep custom views
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Keep support library fragments
-keep class * extends androidx.fragment.app.Fragment { *; }
