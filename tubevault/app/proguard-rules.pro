# youtubedl-android parses yt-dlp JSON straight into Jackson-annotated models.
-keep class com.yausername.youtubedl_android.** { *; }
-keep class com.yausername.youtubedl_common.** { *; }
-keepattributes *Annotation*
-dontwarn com.fasterxml.jackson.**
-keep class com.fasterxml.jackson.** { *; }
