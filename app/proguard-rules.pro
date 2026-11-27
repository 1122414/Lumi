# Lumi Production R8 / ProGuard Rules

# Keep JNI & native bridge methods for MediaPipe Tasks Vision (magic_touch.tflite)
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# Keep ML Kit Face Detection & Object Detection reflection entry points
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.mlkit.**

# Keep CameraX & CameraX Extensions OEM vendor implementations
-keep class androidx.camera.** { *; }
-keep class androidx.camera.extensions.impl.** { *; }
-dontwarn androidx.camera.extensions.impl.**

# Keep AndroidX lifecycle / Compose metadata needed at runtime
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Strip verbose debug logging in Release builds while keeping warnings/errors
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
