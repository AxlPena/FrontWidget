# FrontWidget release keep rules.
# Proton Core ships most of its own consumer rules via the proguard-rules artifact;
# these are safety nets for the parts that are reached reflectively / via JNI.

# gopenpgp + go-srp native bindings (JNI) and their Java wrappers.
-keep class com.proton.gopenpgp.** { *; }
-keep class com.proton.** { *; }
-dontwarn com.proton.**

# gomobile runtime support classes (package "go.*"), invoked via JNI.
-keep class go.** { *; }
-dontwarn go.**

# kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepclassmembers class **$$serializer { *; }
-keepclassmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room / Hilt generated code is covered by their own consumer rules; keep entities safe.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# Gson data models for the weather/geocoding API are (de)serialized reflectively;
# keep their fields so R8 obfuscation doesn't break field-name mapping.
-keep class com.saveory.frontwidget.data.** { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Retrofit generic signatures + service interfaces.
-keepattributes Signature, Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Our ported Proton Calendar API (Retrofit service) + event models. The Retrofit interface is
# accessed reflectively (dynamic proxy); keep its method signatures/annotations intact.
-keep interface com.saveory.frontwidget.proton.calendar.CalendarApi { *; }
-keep class com.saveory.frontwidget.proton.calendar.** { *; }
