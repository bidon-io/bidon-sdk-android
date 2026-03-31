-keeppackagenames org.bidon.**

-keep class org.bidon.zmaticoo.ZmaticooAdapter { *; }

-dontskipnonpubliclibraryclasses

# SDK API
-keep class com.maticoo.sdk.** { *; }
-keep class com.zmaticoo.sdk.** { *; }
-keep class com.maticooad.sdk.** { *; }
-keep interface com.maticoo.sdk.** { *; }
-keep interface com.zmaticoo.sdk.** { *; }
-keep interface com.maticooad.sdk.** { *; }
-dontwarn com.maticoo.sdk.**
-dontwarn com.zmaticoo.sdk.**

# R
-keepclassmembers class **.R$* {
    public static <fields>;
}
-keepattributes *Annotation*,InnerClasses
-keepnames class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
