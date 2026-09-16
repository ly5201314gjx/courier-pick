# onnxruntime could write to logs about JNA
-dontwarn org.slf4j.**
-dontwarn org.bytedeco.**
-dontwarn java.awt.**
-dontwarn javax.swing.**
-keep class com.microsoft.onnxruntime.** { *; }
-keep class ai.onnxruntime.** { *; }
-keep class com.google.zxing.** { *; }