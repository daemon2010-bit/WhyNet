# Keep WhyNet kotlinx.serialization models & Room entities cleanly under R8
-keepattributes *Annotation*, InnerClasses, Signature
-keep class dev.whynet.core.model.** { *; }
-keep class dev.whynet.core.storage.** { *; }
-dontnote kotlinx.serialization.AnnotationsKt
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
