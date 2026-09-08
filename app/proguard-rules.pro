# libVLC is loaded through JNI and looks classes up by name.
-keep class org.videolan.libvlc.** { *; }

# kotlinx.serialization: keep generated serializers and the companion lookups R8 full mode
# would otherwise strip (the library's own consumer rules cover most of this; these are the
# documented belt-and-braces rules).
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.iptv.tv.**$$serializer { *; }
-keepclassmembers class com.iptv.tv.** { *** Companion; }
-keepclasseswithmembers class com.iptv.tv.** { kotlinx.serialization.KSerializer serializer(...); }

# Retrofit interfaces are used through reflection on generic return types.
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
