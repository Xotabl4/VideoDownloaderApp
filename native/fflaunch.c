/*
 * fflaunch — крошечный лаунчер обновляемого ffmpeg.
 *
 * Android 10+ (SELinux, untrusted_app) запрещает execve файлов из каталога данных
 * приложения, поэтому распакованный ffmpeg (files/ffmpeg/<версия>/real/) запустить
 * напрямую нельзя. Лаунчер лежит в nativeLibraryDir (там exec разрешён) и подменяет
 * процесс на:
 *     /system/bin/linker64 <files>/ffmpeg/current/real/<ffmpeg|ffprobe> "$@"
 *
 * Его вызывают двумя путями:
 *   1. youtubedl-android жёстко передаёт --ffmpeg-location <nativeLibraryDir>/libffmpeg.so
 *      (этот путь зашит в библиотеку и переопределяет нашу опцию), поэтому лаунчер
 *      компилируется именно как libffmpeg.so / libffprobe.so.
 *   2. Мы сами создаём симлинки <files>/ffmpeg/current/bin/{ffmpeg,ffprobe} → libffmpeg.so
 *      (для своей проверки -version и на случай прямого --ffmpeg-location).
 *
 * Имя бинаря берём из basename(argv[0]):
 *   ffmpeg / libffmpeg.so → ffmpeg;  ffprobe / libffprobe.so → ffprobe.
 *
 * Путь к ffmpeg зашит под конкретное приложение (packageName фиксирован).
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define APP_FFMPEG_ROOT "/data/user/0/org.xotabl4.ytdlpgui/files/ffmpeg/current"
#define PATH_LIMIT 4096

static void fail(const char *what) {
  fprintf(stderr, "fflaunch: %s\n", what);
  _exit(127);
}

static int is_ffprobe(const char *base) {
  return strcmp(base, "ffprobe") == 0 || strcmp(base, "libffprobe.so") == 0;
}

int main(int argc, char **argv) {
  if (argc < 1 || argv[0] == NULL || argv[0][0] == '\0') fail("no argv[0]");

  const char *slash = strrchr(argv[0], '/');
  const char *base = slash != NULL ? slash + 1 : argv[0];
  const char *name = is_ffprobe(base) ? "ffprobe" : "ffmpeg";

  char exe[PATH_LIMIT];
  snprintf(exe, sizeof exe, "%s/real/%s", APP_FFMPEG_ROOT, name);
  if (access(exe, R_OK) != 0) {
    fprintf(stderr, "fflaunch: not found: %s\n", exe);
    _exit(127);
  }

  char **args = calloc((size_t) argc + 2, sizeof(char *));
  if (args == NULL) fail("out of memory");
  args[0] = (char *) "/system/bin/linker64";
  args[1] = exe;
  for (int i = 1; i < argc; i++) args[i + 1] = argv[i];
  args[argc + 1] = NULL;

  /* Окружение делаем чистым. youtubedl-android запускает yt-dlp с LD_LIBRARY_PATH на свой
   * Python-каталог, где лежит СТАРАЯ libc++_shared.so без нужных символов — унаследованный
   * LD_LIBRARY_PATH перебивает RUNPATH и ffmpeg падает на
   * `cannot locate symbol "__from_chars_floating_point"` (libplacebo → libc++).
   * Свои пути задавать не надо: при установке RUNPATH всех ELF переписан на $ORIGIN,
   * поэтому линкер сам находит наши библиотеки (проверено на устройстве: без
   * LD_LIBRARY_PATH ffmpeg 9.0.2 работает, а с нашим lib-каталогом в LD_LIBRARY_PATH —
   * ломается, т.к. Termux'овский libz.so.1 затеняет системный libz и рушит libunwindstack). */
  unsetenv("LD_LIBRARY_PATH");
  unsetenv("LD_PRELOAD");

  execv("/system/bin/linker64", args);
  perror("fflaunch: execv");
  return 127;
}
