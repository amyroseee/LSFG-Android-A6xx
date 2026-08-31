# JNI symbol-discovery binding: the native side resolves Java methods by their
# mangled symbol name (Java_com_lsfg_android_session_NativeBridge_xxx), so the
# class FQN and every `external fun` must be preserved exactly. RegisterNatives
# is NOT used.
-keep class com.lsfg.android.session.NativeBridge { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Service / Activity / Receiver / AccessibilityService / Application: loaded by
# name from the manifest by the system.
-keep class * extends android.app.Service
-keep class * extends android.app.Activity
-keep class * extends android.app.Application
-keep class * extends android.content.BroadcastReceiver
-keep class * extends android.content.ContentProvider
-keep class * extends android.accessibilityservice.AccessibilityService

# AIDL-generated stubs for Shizuku user-service IPC.
-keep class com.lsfg.android.shizuku.** { *; }
-keep interface com.lsfg.android.shizuku.** { *; }

# Shizuku API uses reflection / dynamic proxies for the manager service binder.
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# Shizuku spawns ShizukuCaptureUserService in a separate process and instantiates
# it by class name via reflection (Class.forName(...).newInstance()). The class
# name is also passed to bindUserService through ComponentName, which uses the
# obfuscated name. Keep both the class and its no-arg constructor verbatim.
-keep class com.lsfg.android.session.ShizukuCaptureUserService { *; }

# libsu spawns a remote root process and resolves classes by name across the IPC boundary.
-keep class com.topjohnwu.superuser.** { *; }
-keep interface com.topjohnwu.superuser.** { *; }
-dontwarn com.topjohnwu.superuser.**

# RootCaptureService extends libsu's RootService and is instantiated by name in
# the spawned root process. Same constraint as ShizukuCaptureUserService.
-keep class com.lsfg.android.session.RootCaptureService { *; }
-keep class com.lsfg.android.session.RootCaptureService$* { *; }

# Compose runtime needs Signature/InnerClasses for state-handling reflection.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, SourceFile, LineNumberTable

# Parcelables / Binder stubs declared anywhere in the app.
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Strip log calls from the release build to shave a bit more.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
}

# Stop R8 from stripping the line numbers we use to map native crash reports.
-renamesourcefileattribute SourceFile
