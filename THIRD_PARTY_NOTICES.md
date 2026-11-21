# Сторонние компоненты

Приложение собрано из собственного кода и сторонних библиотек. Ниже перечислено, что именно
распространяется внутри APK и на каких условиях.

| Компонент | Версия | Лицензия | Где используется |
| --- | --- | --- | --- |
| youtubedl-android | 0.18.1 | GPL-3.0 | Python 3.12, yt-dlp, запуск процессов (`io.github.junkfood02.youtubedl-android`) |
| yt-dlp | 2026.10.26 | Unlicense | само скачивание, разбор форматов |
| yt-dlp-ejs | 0.8.0 | Unlicense | решение JS-задач YouTube |
| CPython | 3.12 | PSF-2.0 | интерпретатор внутри youtubedl-android |
| FFmpeg | 9.0.2 (сборка Termux) | GPL-3.0 | склейка, конвертация, HLS |
| curl_cffi | 0.17.0 | MIT | TLS-отпечаток для сайтов с антибот-защитой |
| cffi | 2.1.1 | MIT | обвязка curl_cffi |
| libcurl-impersonate | 2.3.2 | MIT (curl) | HTTP-клиент с подменой отпечатка |
| BoringSSL | по ветке curl-impersonate | OpenSSL/ISC | TLS внутри libcurl-impersonate |
| nghttp2, nghttp3, ngtcp2 | по ветке curl-impersonate | MIT | HTTP/2 и HTTP/3 |
| brotli | 1.2.1 | MIT | сжатие |
| zstd | по ветке curl-impersonate | BSD-3-Clause | сжатие |
| libffi | 3.8.0 | MIT | cffi |
| certifi | актуальный на момент сборки | MPL-2.0 | корневые сертификаты для curl_cffi |
| Jetpack Compose, AndroidX, Navigation 3 | по версиям из `gradle/libs.versions.toml` | Apache-2.0 | интерфейс |
| Kotlin, kotlinx.coroutines | по версиям из `gradle/libs.versions.toml` | Apache-2.0 | язык и асинхронность |
| XZ for Java (`org.tukaani:xz`) | 1.10 | Public Domain | распаковка `.deb` при обновлении FFmpeg |

## Что это значит на практике

- youtubedl-android и FFmpeg распространяются под GPL-3.0. Приложение, которое их использует,
  целиком распространяется под GPL-3.0 — см. `LICENSE`.
- Исходный код FFmpeg доступен на <https://ffmpeg.org/download.html> и в репозитории Termux
  (<https://github.com/termux/termux-packages>), версия и флаги сборки — `tools/fetch_ffmpeg.py`.
- Исходный код youtubedl-android: <https://github.com/junkfood02/youtubedl-android>.
- Тексты лицензий Apache-2.0 и MIT приложены в `licenses/` (файлы `Apache-2.0.txt`, `MIT.txt`).
