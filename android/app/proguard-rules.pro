# Keep OkHttp security provider classes
-keep class org.bouncycastle.jsse.** { *; }
-keep class org.conscrypt.** { *; }
-keep class org.openjsse.** { *; }

# Keep OkHttp platform classes
-keep class okhttp3.internal.platform.** { *; }

# Keep OkHttp classes
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# Keep car library classes
-keep class bw.car.** { *; }
-keep class bw.car.power.** { *; }
-keep class bw.car.welcome.** { *; }

# Keep internal Android classes
-keep class com.android.internal.annotations.** { *; }
-keep class com.android.internal.policy.** { *; }
-keep class com.android.internal.util.** { *; }

# Keep music proxy classes and their inner classes
-keep class com.bw.musicconstantproxy.** { *; }
-keep class com.bw.musicconstantproxy.FileManager { *; }
-keep class com.bw.musicconstantproxy.FileManager$OnCompletedCallback { *; }
-keep class com.bw.musicconstantproxy.PlayBackConstance { *; }
-keep class com.bw.musicconstantproxy.PlayBackConstance$MEDIA_TYPE { *; }
-keep class com.bw.musicconstantproxy.PlayBackConstance$MusicListType { *; }
-keep class com.bw.musicconstantproxy.PlayBackConstance$PLAYMODE { *; }
-keep class com.bw.musicconstantproxy.PlayBackConstance$UriMode { *; }
-keep class com.bw.musicconstantproxy.utils.** { *; }
-keep class com.bw.musicproxy.** { *; }

# Keep Flutter classes
-keep class io.flutter.** { *; }
-keep class io.flutter.embedding.** { *; }
-keep class io.flutter.plugin.** { *; }
-keep class io.flutter.util.** { *; }
-keep class io.flutter.view.** { *; }
-keep class io.flutter.app.** { *; }

# Keep Google Play Core classes
-keep class com.google.android.play.core.** { *; }
-keep class com.google.android.play.core.splitinstall.** { *; }
-keep class com.google.android.play.core.splitcompat.** { *; }
-keep class com.google.android.play.core.tasks.** { *; }

# Keep specific missing classes
-keep class com.google.android.play.core.splitcompat.SplitCompatApplication { *; }
-keep class com.google.android.play.core.splitinstall.SplitInstallException { *; }
-keep class com.google.android.play.core.splitinstall.SplitInstallManager { *; }
-keep class com.google.android.play.core.splitinstall.SplitInstallManagerFactory { *; }
-keep class com.google.android.play.core.splitinstall.SplitInstallRequest { *; }
-keep class com.google.android.play.core.splitinstall.SplitInstallRequest$Builder { *; }
-keep class com.google.android.play.core.splitinstall.SplitInstallSessionState { *; }
-keep class com.google.android.play.core.splitinstall.SplitInstallStateUpdatedListener { *; }
-keep class com.google.android.play.core.tasks.OnFailureListener { *; }
-keep class com.google.android.play.core.tasks.OnSuccessListener { *; }
-keep class com.google.android.play.core.tasks.Task { *; }

# Keep Guava classes
-keep class com.google.common.** { *; }
-keep class com.google.common.reflect.** { *; }

# Keep Flutter wrapper classes
-keep class com.trackingWorld.** { *; }

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Parcelable implementations
-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}

# Keep R8 rules
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature
-keepattributes Exceptions

# Keep JavaScript interface methods
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep custom views
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Keep custom application class
-keep public class * extends android.app.Application

# Keep custom activity classes
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# Keep all classes with @Keep annotation
-keep class androidx.annotation.Keep
-keep @androidx.annotation.Keep class * {*;}
-keepclasseswithmembers class * {
    @androidx.annotation.Keep <methods>;
}
-keepclasseswithmembers class * {
    @androidx.annotation.Keep <fields>;
}
-keepclasseswithmembers class * {
    @androidx.annotation.Keep <init>(...);
}

# Keep all classes in the bw.car package
-keep class bw.car.** { *; }
-keepclassmembers class bw.car.** { *; }

# Keep all classes in the com.bw.musicproxy package
-keep class com.bw.musicproxy.** { *; }
-keepclassmembers class com.bw.musicproxy.** { *; }

# Keep all classes in the com.bw.musicconstantproxy package
-keep class com.bw.musicconstantproxy.** { *; }
-keepclassmembers class com.bw.musicconstantproxy.** { *; }

# Keep all inner classes
-keep class **$* { *; }

# Keep Firebase classes
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }

# Keep Okio classes
-keep class okio.** { *; }

# Keep Java reflection classes
-keep class java.lang.reflect.** { *; } 