# kotlinx.serialization: keep the generated serializers of the API models.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class eu.llembas.android.api.** {
    *** Companion;
}
-keepclasseswithmembers class eu.llembas.android.api.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class eu.llembas.android.api.**$$serializer { *; }

# Markwon references optional classes it does not need here.
-dontwarn org.commonmark.ext.gfm.strikethrough.**
-dontwarn io.noties.markwon.**

# OkHttp's optional TLS providers.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
