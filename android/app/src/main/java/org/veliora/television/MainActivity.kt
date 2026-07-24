package org.veliora.television

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

class MainActivity : Activity() {

    companion object {
        private const val HOST = "appassets.androidplatform.net"
        private const val START_URL = "https://$HOST/index.html"
        private const val REQ_PLAYER = 1
    }

    private lateinit var webView: WebView
    private val proxy = ProxyHandler()

    // 拦截到的 player.html 完整地址，供原生播放失败时回退 WebView 播放器
    private var pendingPlayerUrl: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        webView.setBackgroundColor(Color.BLACK)
        webView.keepScreenOn = true // 视频应用常亮
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true                      // localStorage（源配置/播放进度/主题）
            mediaPlaybackRequiresUserGesture = false      // 遥控器点播直接起播
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW // https 页面源播放 http m3u8
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(false)
        }

        // assets/ 目录映射为 https://appassets.androidplatform.net/ —— 安全源，
        // 保证 crypto.subtle 与 localStorage 可用（等价 server.mjs 的静态文件服务）
        val assetLoader = WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url
                // /proxy/<encodedUrl> → 原生转发（替代 server.mjs 代理端点）
                if (url.host == HOST && url.encodedPath?.startsWith("/proxy/") == true) {
                    return proxy.handle(url)
                }
                return assetLoader.shouldInterceptRequest(url)
            }

            // 页面跳 player.html 时改用原生 ExoPlayer 播放（4K 硬解直通）。
            // 仅拦截页面发起的导航；回退时 loadUrl() 不经过此回调，天然放行。
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url
                if (url.host == HOST && url.path?.endsWith("/player.html") == true) {
                    launchNativePlayer(url)
                    return true
                }
                return false
            }
        }

        // JS 桥：页面在首页按返回键时通知原生退出
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun exitApp() = runOnUiThread { finish() }
        }, "AndroidTV")

        webView.requestFocus()
        webView.loadUrl(START_URL)
    }

    /**
     * 解析 player.html 的 URL 参数 + localStorage 里的集数列表（tv-remote.js play() 约定），
     * 组装后调起原生 PlayerActivity。localStorage 读取是异步回调，完成后再启动。
     */
    private fun launchNativePlayer(uri: Uri) {
        pendingPlayerUrl = uri.toString()
        val videoUrl = uri.getQueryParameter("url").orEmpty()
        if (videoUrl.isBlank()) return
        val title = uri.getQueryParameter("title").orEmpty()
        val indexParam = uri.getQueryParameter("index")?.toIntOrNull() ?: 0
        val positionSec = uri.getQueryParameter("position")?.toIntOrNull() ?: 0

        webView.evaluateJavascript(
            """JSON.stringify({
                eps: localStorage.getItem('currentEpisodes'),
                ad: localStorage.getItem('adFilteringEnabled')
            })"""
        ) { raw ->
            var episodes = arrayListOf(videoUrl)
            var index = 0
            var adFilter = true
            try {
                val outer = JSONTokener(raw).nextValue() as? String
                if (outer != null) {
                    val obj = JSONObject(outer)
                    val epsJson = obj.optString("eps", "")
                    if (epsJson.isNotBlank() && epsJson != "null") {
                        val arr = JSONArray(epsJson)
                        val list = ArrayList<String>(arr.length())
                        for (i in 0 until arr.length()) list.add(arr.getString(i))
                        // localStorage 可能残留上一部片的集数，须校验当前 URL 在列表内
                        val at = if (indexParam in list.indices && list[indexParam] == videoUrl)
                            indexParam else list.indexOf(videoUrl)
                        if (at >= 0) {
                            episodes = list
                            index = at
                        }
                    }
                    adFilter = obj.optString("ad", "true") != "false"
                }
            } catch (_: Exception) {
                // 解析失败则退化为单集播放
            }
            startActivityForResult(
                Intent(this, PlayerActivity::class.java).apply {
                    putStringArrayListExtra(PlayerActivity.EXTRA_EPISODES, episodes)
                    putExtra(PlayerActivity.EXTRA_INDEX, index)
                    putExtra(PlayerActivity.EXTRA_TITLE, title)
                    putExtra(PlayerActivity.EXTRA_POSITION_SEC, positionSec)
                    putExtra(PlayerActivity.EXTRA_AD_FILTER, adFilter)
                },
                REQ_PLAYER
            )
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_PLAYER && resultCode == PlayerActivity.RESULT_FALLBACK) {
            // 原生管线播不了的流，回退 WebView hls.js 播放器兜底
            pendingPlayerUrl?.let { webView.loadUrl(it) }
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    /**
     * 遥控器按键：
     * - 方向键/OK 键由 WebView 自动映射为 ArrowUp/Down/Left/Right/Enter，tv-remote.js 直接处理
     * - BACK 键交给页面逐级返回；index.html 首页无处可退时退出 App，player.html 返回 index.html
     * - 播放/暂停媒体键控制 ArtPlayer（player.js 的全局 art 实例）
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                webView.evaluateJavascript(
                    """
                    (function () {
                        if (location.pathname.indexOf('player.html') !== -1) {
                            location.href = 'index.html';
                            return;
                        }
                        var home = document.querySelector('.tv-view.active');
                        if (!home || home.id === 'viewHome') {
                            if (window.AndroidTV) AndroidTV.exitApp();
                            return;
                        }
                        document.dispatchEvent(new KeyboardEvent('keydown', {
                            key: 'Backspace', keyCode: 8, bubbles: true, cancelable: true
                        }));
                    })();
                    """.trimIndent(), null
                )
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                // player.js 用 `let art` 声明（词法全局，不在 window 上），需用 typeof 探测
                webView.evaluateJavascript(
                    "typeof art !== 'undefined' && art && art.toggle && art.toggle();", null
                )
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
