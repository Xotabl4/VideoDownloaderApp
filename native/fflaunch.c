/*
 * fflaunch — маленький лаунчер обновляемого ffmpeg.
 *
 * Android не даёт запускать ffmpeg из папки данных, поэтому лаунчер (он лежит
 * в nativeLibraryDir) запускает нужный бинарник через linker64:
 *     /system/bin/linker64 <files>/ffmpeg/current/real/<ffmpeg|ffprobe> "$@"
 *
 * Имя бинаря берётся из basename(argv[0]):
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

  /* Окружение очищаем. youtubedl-android запускает yt-dlp с LD_LIBRARY_PATH на свой
   * Python-каталог, где лежит старая libc++_shared.so без нужных символов: унаследованный
   * LD_LIBRARY_PATH перебивает RUNPATH и ffmpeg падает на cannot locate symbol
   * __from_chars_floating_point. Свои пути задавать не нужно: RUNPATH всех ELF переписан
   * на $ORIGIN, поэтому линкер сам находит библиотеки сборки. */
  unsetenv("LD_LIBRARY_PATH");
  unsetenv("LD_PRELOAD");

  execv("/system/bin/linker64", args);
  perror("fflaunch: execv");
  return 127;
}
