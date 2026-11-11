# Keep JNI bridge methods called from Go libwhatshub_engine.so
-keep class com.rafat.whatshub.bridge.NativeBridge {
    *;
}

# Keep SQLCipher JNI classes
-keep class net.zetetic.database.** { *; }
-dontwarn net.zetetic.database.**

# Keep Kotlinx Serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.rafat.whatshub.**$$serializer { *; }
-keepclassmembers class com.rafat.whatshub.** {
    *** Companion;
}
-keepclasseswithmembers class com.rafat.whatshub.** {
    kotlinx.serialization.KSerializer serializer(...);
}
