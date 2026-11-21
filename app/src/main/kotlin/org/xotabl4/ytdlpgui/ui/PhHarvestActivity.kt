package org.xotabl4.ytdlpgui.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.xotabl4.ytdlpgui.core.PhCookies
import org.xotabl4.ytdlpgui.core.PhHarvest

private const val TAG = "PhHarvest"

/**
 * Скрытое окно-харвестер: открывает страницу PornHub в реальном WebView, ставит cookies
 * подтверждения возраста и забирает качества из `flashvars.mediaDefinitions`.
 *
 * Окно прозрачное и вынесено за пределы экрана. Headless-WebView не подходит: без
 * реального окна скрипты сайта ссылки не отдают.
 */
class PhHarvestActivity : Activity() {
  private var webView: WebView? = null
  private var finished = false
  private var attempts = 0
  private val handler = Handler(Looper.getMainLooper())
  private val timeout =
    Runnable {
      Log.w(TAG, "timeout, no media URL")
      finishWith("", null)
    }

  @SuppressLint("SetJavaScriptEnabled")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window?.setBackgroundDrawableResource(android.R.color.transparent)
    val pageUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
    if (pageUrl.isEmpty()) {
      finishWith("", null)
      return
    }

    val root = FrameLayout(this)
    val view =
      WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // Никакого звука: медиа не запускаем вообще, а запросы потоков блокируем.
        settings.mediaPlaybackRequiresUserGesture = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.userAgentString = PhCookies.USER_AGENT
        // Окно невидимое, но с реальным размером: иначе сайт не отдаёт ссылки.
        alpha = 0f
        translationY = 20_000f
        webViewClient =
          object : WebViewClient() {
            override fun shouldInterceptRequest(
              view: WebView,
              request: WebResourceRequest,
            ): WebResourceResponse? {
              // Ссылки на потоки нужны только как текст (flashvars), воспроизведение — нет.
              val url = request.url?.toString().orEmpty().lowercase()
              if (url.contains(".m3u8") || url.contains(".mp4")) {
                return WebResourceResponse(
                  "text/plain",
                  "utf-8",
                  java.io.ByteArrayInputStream(ByteArray(0)),
                )
              }
              return null
            }

            override fun onPageFinished(view: WebView, url: String?) {
              // Ссылки появляются после подгрузки скриптов PH — пробуем несколько раз.
              probeMedia()
            }
          }
      }
    webView = view
    // Никаких разрешений (микрофон/камера/autoplay) — окно скрытое.
    view.webChromeClient =
      object : android.webkit.WebChromeClient() {
        override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
          request.deny()
        }
      }
    root.addView(
      view,
      FrameLayout.LayoutParams(dp(360), dp(640), Gravity.TOP or Gravity.START),
    )
    setContentView(root)

    CookieManager.getInstance().apply {
      setAcceptCookie(true)
      setCookie("https://www.pornhub.com", "age_verified=1; path=/")
      setCookie("https://www.pornhub.com", "accessAgeDisclaimerPH=1; path=/")
      setCookie("https://www.pornhub.com", "platform=pc; path=/")
    }

    view.loadUrl(pageUrl)
    handler.postDelayed(timeout, 25_000L)
  }

  private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

  private fun probeMedia() {
    if (finished) return
    val view = webView ?: return
    view.evaluateJavascript(JS_MEDIA_LIST) { raw ->
      val (title, list) = parseMediaList(raw)
      if (list.isNotEmpty()) {
        Log.i(TAG, "media variants: ${list.size}, title=${title.take(80)}")
        finishWith(title, list)
      } else if (attempts < MAX_ATTEMPTS) {
        attempts++
        Log.d(TAG, "media not found yet, attempt=$attempts")
        handler.postDelayed({ probeMedia() }, 1500)
      } else {
        Log.w(TAG, "media not found after $MAX_ATTEMPTS attempts")
        finishWith("", null)
      }
    }
  }

  /**
   * Ответ JS: `название видео` + `\u0001` + строки `quality|format|url` (лучшее качество первым).
   */
  private fun parseMediaList(raw: String?): Pair<String, List<String>> {
    if (raw.isNullOrBlank() || raw == "null") return "" to emptyList()
    val text =
      raw.trim()
        .removePrefix("\"")
        .removeSuffix("\"")
        .replace("\\n", "\n")
        .replace("\\u003c", "<")
        .replace("\\u003e", ">")
        .replace("\\\"", "\"")
        .replace("\\u0001", "\u0001")
    val sep = text.indexOf('\u0001')
    val title = if (sep >= 0) text.substring(0, sep).trim() else ""
    val listPart = if (sep >= 0) text.substring(sep + 1) else text
    val list =
      listPart
        .lines()
        .map { it.trim() }
        .filter { it.count { ch -> ch == '|' } >= 2 }
        .distinct()
        .sortedByDescending { it.substringBefore('|').toIntOrNull() ?: 0 }
    return title to list
  }

  private fun finishWith(title: String, list: List<String>?) {
    if (finished) return
    finished = true
    handler.removeCallbacks(timeout)
    PhHarvest.complete(title, list)
    runCatching { webView?.stopLoading() }
    runCatching { webView?.destroy() }
    webView = null
    // Возвращаем MainActivity на передний план: харвестер работает в отдельной задаче.
    runCatching {
      startActivity(
        android.content.Intent(this, org.xotabl4.ytdlpgui.MainActivity::class.java)
          .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
      )
    }
    finish()
  }

  override fun onDestroy() {
    handler.removeCallbacks(timeout)
    if (!finished) PhHarvest.complete("", null)
    runCatching { webView?.destroy() }
    webView = null
    super.onDestroy()
  }

  companion object {
    const val EXTRA_URL = "ph_url"
    private const val MAX_ATTEMPTS = 10

    /**
     * Возвращает `quality|format|url` по одной строке на вариант из `flashvars.mediaDefinitions`
     * (лучшее качество первым). Если их нет — единственную ссылку из HTML (quality=0).
     */
    private const val JS_MEDIA_LIST =
      "(function(){try{" +
      "var fv=null;try{for(var k in window){if(k&&k.indexOf('flashvars')===0&&window[k]){fv=window[k];break;}}}catch(e){}" +
      "var t='';try{if(fv)t=fv.video_title||fv.title||'';}catch(e){}" +
      "if(!t){try{if(window.video_title)t=window.video_title;}catch(e){}}" +
      "if(!t){try{var og=document.querySelector('meta[property=\\'og:title\\']');if(og)t=og.content||'';}catch(e){}}" +
      "if(!t){try{var h1=document.querySelector('h1');if(h1)t=h1.textContent||'';}catch(e){}}" +
      "if(!t)t=document.title||'';" +
      "var defs=(fv&&fv.mediaDefinitions)||[];" +
      "var out=[];" +
      "for(var i=0;i<defs.length;i++){var d=defs[i]||{};" +
      "var u=d.videoUrl||d.video_url||'';if(!u)continue;" +
      "var f=u.indexOf('.mp4')>=0?'mp4':(u.indexOf('.m3u8')>=0?'m3u8':'');if(!f)continue;" +
      "var q=d.quality;" +
      "if(q&&q.length&&typeof q!=='string'){var m=0;for(var j=0;j<q.length;j++){var v=parseInt(q[j],10);if(v>m)m=v;}q=m;}" +
      "var n=parseInt(q,10);if(isNaN(n))n=0;" +
      "out.push(n+'|'+f+'|'+u);}" +
      "if(out.length)return t+String.fromCharCode(1)+out.join(String.fromCharCode(10));" +
      "var h=document.documentElement.outerHTML;" +
      "var i=h.indexOf('.mp4');var j=h.indexOf('.m3u8');" +
      "if(j>=0&&(i<0||j<i))i=j;" +
      "if(i<0)return t+String.fromCharCode(1);" +
      "var s=h.lastIndexOf('http',i);if(s<0)return t+String.fromCharCode(1);" +
      "var e=i+4;" +
      "while(e<h.length){var c=h.charCodeAt(e);" +
      "if(c===34||c===39||c===32||c===60||c===62||c===92)break;e++;}" +
      "return t+String.fromCharCode(1)+'0|'+((i===h.indexOf('.m3u8'))?'m3u8':'mp4')+'|'+h.substring(s,e);" +
      "}catch(e){return '';}})()"

  }
}
