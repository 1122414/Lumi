# Lumi Production R8 / ProGuard Rules

# Keep native JNI methods everywhere so .so libraries never fail GetMethodID/FindClass
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Protobuf JavaLite reflection (used by MediaPipe & ML Kit VisionKit native graphs)
-keep class com.google.protobuf.** { *; }
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-dontwarn com.google.protobuf.**

# Keep MediaPipe Tasks Vision & TFLite JNI bridges (magic_touch.tflite)
-keep class com.google.mediapipe.** { *; }
-keep class org.tensorflow.** { *; }
-keep class com.google.auto.value.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn org.tensorflow.**
-dontwarn com.google.auto.value.**

# Keep ML Kit Face Detection, Object Detection, VisionKit JNI, ODML & Dynamite reflection
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.vision.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class com.google.android.gms.dynamite.** { *; }
-keep class com.google.android.libraries.vision.** { *; }
-keep class com.google.android.libraries.intelligence.** { *; }
-keep class com.google.android.odml.** { *; }
-keep class com.google.firebase.components.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**
-dontwarn com.google.android.libraries.**
-dontwarn com.google.android.odml.**
-dontwarn com.google.firebase.**

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
