# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep Kotlin data classes for serialization
-keep class com.example.llamadroid.data.** { *; }
-keep class com.example.llamadroid.service.**.* { *; }

# Keep Room entities
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# Keep JSch for SSH
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jzlib.**
-dontwarn org.ietf.jgss.**
-dontwarn com.jcraft.jsch.jgss.**
-dontwarn com.jcraft.jsch.jcraft.Compression
-dontwarn com.gemalto.jp2.**

# Keep OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }

# Keep enums for TypeConverters
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Compose classes
-keep class androidx.compose.** { *; }

# Keep model classes that might be serialized
-keep class com.example.llamadroid.**.model.** { *; }

# Preserve line numbers for crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Gson - preserve Signature and annotations for reflection
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# Keep Gson-serialized classes with their generic signatures
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Explicitly keep HuggingFace API DTOs (Gson needs these for generic List<T>)
-keep class com.example.llamadroid.data.api.HfModelDto { *; }
-keep class com.example.llamadroid.data.api.HfRepoInfoDto { *; }
-keep class com.example.llamadroid.data.api.HfSiblingDto { *; }
-keep class com.example.llamadroid.data.api.HfTreeItemDto { *; }

# Keep all API data classes
-keep class com.example.llamadroid.data.api.** { *; }

# ========== RETROFIT - CRITICAL RULES FOR GENERIC TYPES ==========
# Keep Retrofit service interfaces with their method signatures
-keep,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Keep all Retrofit service interfaces in the api package
-keep interface com.example.llamadroid.data.api.HuggingFaceService { *; }

# Keep method signature information for all Retrofit interfaces
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.GET <methods>;
    @retrofit2.http.POST <methods>;
    @retrofit2.http.PUT <methods>;
    @retrofit2.http.DELETE <methods>;
    @retrofit2.http.Path <methods>;
    @retrofit2.http.Query <methods>;
}

# Keep kotlinx.serialization classes
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}

# Keep the generated serializers
-keep class **$$serializer { *; }
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
