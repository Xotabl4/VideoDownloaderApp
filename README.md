# Video Downloader для Android

Приложение для скачивания видео по ссылке. Написано на Kotlin + Jetpack Compose, внутри APK работают
yt-dlp и FFmpeg, поэтому поддерживаются все площадки, которые умеет yt-dlp: YouTube, RuTube, VK Video
и другие.

| Параметр | Значение |
| --- | --- |
| Пакет | `org.xotabl4.ytdlpgui` |
| Версия | 0.4.0 |
| Android | 7.0 и новее (API 24), только arm64-v8a |
| Лицензия | GPL-3.0 |

## Установка

1. Откройте раздел **Releases** и скачайте APK из последнего релиза.
2. Разрешите установку из неизвестных источников.
3. При первом запуске разрешите уведомления — без них не видно прогресса загрузки.

В Google Play приложение не публикуется.

## Возможности

- Ссылка, список форматов, выбор качества и контейнера.
- Конвертация и склейка дорожек через FFmpeg: mp4, mkv, webm, avi, mp3, m4a, wav, flac.
- Загрузка в фоне: прогресс в шторке, отмена, повтор после ошибки.
- Две загрузки одновременно, остальные ждут в очереди.
- Плейлисты YouTube, RuTube и VK: файлы раскладываются в подпапку с названием плейлиста.
- История загрузок и список незавершённых задач, которые переживают перезапуск.
- Выбор папки сохранения, по умолчанию системная `Download`.
- Обновление yt-dlp и FFmpeg из приложения.
- Проверка новых версий приложения через GitHub Releases.
- Тёмная и светлая темы, языки RU и EN.

## Ограничения

- YouTube с российских адресов не отвечает: нужен VPN. RuTube и VK Video работают без него.
- Сервисы с DRM (Кинопоиск, Okko, Wink, Start и подобные) не поддерживаются.
- Cookies браузера не импортируются: приложение не читает личные данные.
- Сборка только под arm64-v8a — так поставляются Python, yt-dlp и FFmpeg.

## Сборка из исходников

Нужны Android Studio 2026.2 (встроенный JBR 25) и Android SDK. Версии инструментов: AGP 9.0.1,
Gradle 9.1.0, Kotlin 2.3.20, target JVM 17.

```
gradlew testDebugUnitTest
gradlew assembleRelease
```

Готовый файл: `app/build/outputs/apk/release/app-release.apk`.

Подпись release-сборки берётся из `keys/keystore.properties` рядом с проектом (путь `../keys/keystore.properties`).
Если файла нет, release собирается отладочным ключом — для локальных проверок этого достаточно.

Крупные бинарники (Python, yt-dlp, FFmpeg, curl-impersonate) уже лежат в репозитории, отдельно скачивать
ничего не нужно.

## Структура проекта

```
app/src/main/kotlin/org/xotabl4/ytdlpgui/
  core/            логика: форматы, загрузка, хранилище, история, обновления
  ui/              экраны Compose
  service/         foreground-сервис загрузки
app/src/test/            юнит-тесты (JUnit)
app/src/androidTest/     инструментальные тесты Compose
baselineprofile/         генератор Baseline Profile
native/                  лаунчер FFmpeg на C
tools/                   скрипты: сборка лаунчера, иконки, загрузка FFmpeg
```

## Обновления

Актуальная версия определяется по GitHub Releases через API (`releases/latest`). Если вышла новая
версия, в настройках появляется отметка, а кнопка открывает страницу релиза.

## Лицензии

Проект распространяется по **GPL-3.0**, текст — в файле `LICENSE`. Список сторонних компонентов
и их лицензий — в `THIRD_PARTY_NOTICES.md`.

Приложение использует youtubedl-android и FFmpeg, обе библиотеки под GPL-3.0, поэтому изменённые
сборки нужно распространять на тех же условиях.

## English

Android video downloader built with Kotlin and Jetpack Compose. yt-dlp and FFmpeg run inside the APK,
so any site supported by yt-dlp works. Install the APK from Releases (Android 7+, arm64). Licensed
under GPL-3.0, see `LICENSE` and `THIRD_PARTY_NOTICES.md`.
