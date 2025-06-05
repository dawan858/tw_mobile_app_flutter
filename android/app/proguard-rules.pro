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