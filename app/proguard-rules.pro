# LibretroDroid usa JNI: manter classes chamadas pelo código nativo.
-keep class com.swordfish.libretrodroid.** { *; }
-keepclassmembers class * { native <methods>; }
# ONNX Runtime (MeikiOCR): a ponte JNI procura OnnxTensor, TensorInfo, NodeInfo, OrtException etc. pelo nome e
# chama construtores que só o nativo usa. O .aar só traz regras para a telemetria; sem esta, o R8 renomeia ou
# remove essas classes e a primeira tradução com o OCR para jogos derruba o app (erro nativo, não dá para pegar).
-keep class ai.onnxruntime.** { *; }
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.retrovika.app.** { *** Companion; kotlinx.serialization.KSerializer serializer(...); }
# commons-compress: codecs opcionais (zstd, brotli, asm...) não são usados; só ZIP/7z.
-dontwarn org.apache.commons.compress.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
