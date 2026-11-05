# kotlinx.serialization: keep generated serializers for @Serializable DTOs (R8 full mode).
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.bbro.kidtube.data.remote.** {
    *** Companion;
}
-keepclasseswithmembers class com.bbro.kidtube.data.remote.** {
    kotlinx.serialization.KSerializer serializer(...);
}
