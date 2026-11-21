package org.xotabl4.ytdlpgui.core

/** Адреса репозитория и страницы релизов. */
object Links {
  private const val OWNER = "Xotabl4"

  private const val REPO_NAME = "VideoDownloaderApp"

  const val REPOSITORY = "https://github.com/$OWNER/$REPO_NAME"
  const val RELEASES = "$REPOSITORY/releases/latest"
  const val LATEST_RELEASE_API = "https://api.github.com/repos/$OWNER/$REPO_NAME/releases/latest"
  const val LICENSES = "$REPOSITORY/blob/main/LICENSE"
}
