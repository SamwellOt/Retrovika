# LibretroDroid usa JNI: manter classes chamadas pelo código nativo.
-keep class com.swordfish.libretrodroid.** { *; }
-keepclassmembers class * { native <methods>; }
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.retrovika.app.** { *** Companion; kotlinx.serialization.KSerializer serializer(...); }
# commons-compress: codecs opcionais (zstd, brotli, asm...) não são usados; só ZIP/7z.
-dontwarn org.apache.commons.compress.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
