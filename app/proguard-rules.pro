# Правила R8 для release-сборки.

# --- youtubedl-android ---
# Библиотека общается с Python-процессом и использует свои классы через reflection/сервисы:
# вырезать их нельзя, иначе инициализация yt-dlp падает уже в release.
-keep class com.yausername.** { *; }
-keep class com.yausername.youtubedl_android.** { *; }
-dontwarn com.yausername.**

# --- Apache Commons Compress ---
# youtubedl-common распаковывает Python-zip через ArchiveStreamFactory/ServiceLoader:
# при обфускации падает с «class j7.a is not a concrete class» (проверено на устройстве).
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**

# --- XZ / tar (FfmpegUpdater разбирает .deb из Termux) ---
-keep class org.tukaani.xz.** { *; }
-dontwarn org.tukaani.xz.**

# --- kotlinx.serialization (ключи навигации @Serializable) ---
-keepattributes *Annotation*, InnerClasses
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class org.xotabl4.ytdlpgui.**$$serializer { *; }
-keepclassmembers class org.xotabl4.ytdlpgui.** {
    *** Companion;
}
-keepclasseswithmembers class org.xotabl4.ytdlpgui.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- нативные/рефлексивные точки входа ---
# Kotlin-объекты (DownloadManager, YtDlp, FfmpegRuntime…) держим целиком: на них ссылаются
# имена классов в логах и в javа-обёртках библиотек.
-keep class org.xotabl4.ytdlpgui.core.** { *; }
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Compose сам поставляет правила; эти атрибуты нужны для корректной работы UI-тулинга и логов.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
