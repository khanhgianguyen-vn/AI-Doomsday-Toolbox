# Gemma Server ProGuard Rules

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep NanoHTTPD
-keep class fi.iki.elonen.** { *; }

# Keep server data classes
-keep class com.llmnode.gemmaserver.server.** { *; }
-keep class com.llmnode.gemmaserver.network.** { *; }
-keep class com.llmnode.gemmaserver.security.** { *; }

# Keep enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Compose classes
-keep class androidx.compose.** { *; }

# Preserve line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Suppress warnings
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
