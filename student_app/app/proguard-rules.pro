-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# Firebase
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }

# Kotlin Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.batchfee.student.**$$serializer { *; }
-keepclassmembers class com.batchfee.student.** {
    *** Companion;
}
-keepclasseswithmembers class com.batchfee.student.** {
    kotlinx.serialization.KSerializer serializer(...);
}
