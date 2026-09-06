package org.veliora.television

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

class MainActivity : Activity() {

    companion object {
        private const val HOST = "appassets.androidplatform.net"
        private const val START_URL = "https://$HOST/index.html"
        private const val REQ_PLAYER = 1

        // 注入脚本约定的应答值：拿不到它就认为渲染进程已经不在了
        private const val JS_OK = "\"ok\""
        // 正常情况 evaluateJavascript 回调是毫秒级的，超这么久基本等同渲染进程已死。
        // 给到 2 秒是留给低端电视偶发的长任务，免得把活着的页面误判成死的
        private const val JS_ALIVE_TIMEOUT_MS = 2000L

        // 返回键：player.html 回选片页；index.html 交给页面的 tvBack 决定这一下关什么。
        // 「首页就退出 App」的判断不能放在这里：从壳子只能看到 .tv-view.active，看不见
        // 首页上盖着的加载遮罩 / 密码框 / 弹层，按返回会越过它们直接把 App 退掉。
        // 末尾统一返回 'ok'，用于判断渲染进程是否还活着。
        private const val JS_BACK = """
            (function () {
                if (location.pathname.indexOf('player.html') !== -1) {
                    location.href = 'index.html';
                    return 'ok';
                }
                if (window.tvBack) { window.tvBack(); return 'ok'; }
                // 页面脚本还没跑起来（启动瞬间按返回）：退回老规则兜底
                var v = document.querySelector('.tv-view.active');
                if (!v || v.id === 'viewHome') {
                    if (window.AndroidTV) AndroidTV.exitApp();
                    return 'ok';
                }
                document.dispatchEvent(new KeyboardEvent('keydown', {
                    key: 'Backspace', keyCode: 8, bubbles: true, cancelable: true
                }));
                return 'ok';
            })();
        """
    }

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private val proxy = ProxyHandler()
    private val handler = Handler(Looper.getMainLooper())

    // 拦截到的 player.html 完整地址，供原生播放失败时回退 WebView 播放器
    private var pendingPlayerUrl: String? = null

    // 重建 WebView 后要回到的页面（通常是首页；回退网页播放器时是 player.html）
    private var lastPageUrl = START_URL
    private var pageReady = false      // 当前文档已加载完（加载中不做探活，避免误判）
    private var pageEverReady = false  // 这个 WebView 实例至少成功加载过一个页面

    // 等待应答的探活序号（0 = 没有在等）。用序号而非布尔，旧回调就不会误判新一轮探活。
    private var jsProbeSeq = 0
    private var jsProbePending = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // assets/ 目录映射为 https://appassets.androidplatform.net/ —— 安全源，
        // 保证 crypto.subtle 与 localStorage 可用（等价 server.mjs 的静态文件服务）
        assetLoader = WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        // WebView 放在容器里而不是直接 setContentView：渲染进程被系统回收后要能整只换掉
        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        setContentView(root)

        webView = createWebView()
        root.addView(webView, matchParent())
        webView.requestFocus()
        webView.loadUrl(START_URL)
    }

    private fun matchParent() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT
    )

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val wv = WebView(this)
        wv.setBackgroundColor(Color.BLACK)
        wv.keepScreenOn = true // 视频应用常亮

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true                      // localStorage（源配置/播放进度/主题）
            mediaPlaybackRequiresUserGesture = false      // 遥控器点播直接起播
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW // https 页面源播放 http m3u8
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(false)
        }

        wv.webViewClient = object : WebViewClient() {
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

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                pageReady = false
                if (Uri.parse(url).host == HOST) lastPageUrl = url
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                pageEverReady = true
            }

            /**
             * 电视内存小，切到别的 App 后 WebView 的渲染进程常被系统回收。
             * 不接这个回调整个 App 进程会被系统连带杀掉；接了但什么都不做则是黑屏 +
             * 所有依赖注入 JS 的交互（返回键）全部失灵 —— 这里直接换一只新 WebView 自愈。
             */
            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                // 已经被换掉的旧实例（destroy 过了，别再动它），吃掉回调即可
                if (view !== webView) return true
                rebuildWebView()
                return true
            }
        }

        // JS 桥：页面在首页按返回键时通知原生退出
        wv.addJavascriptInterface(object {
            @JavascriptInterface
            fun exitApp() = runOnUiThread { finish() }
        }, "AndroidTV")

        return wv
    }

    /** 渲染进程死了之后原地换一只新 WebView，回到最近的页面 */
    private fun rebuildWebView() {
        jsProbePending = 0
        val target = lastPageUrl
        val dead = webView

        root.removeView(dead)
        dead.stopLoading()
        dead.destroy()

        pageReady = false
        pageEverReady = false
        webView = createWebView()
        root.addView(webView, matchParent())
        webView.requestFocus()
        webView.loadUrl(target)
    }

    /**
     * 执行页面脚本，并对「渲染进程已被回收」兜底：超时没等到约定应答就重建 WebView，
     * 不让返回键这类完全依赖注入 JS 的交互变成死键。
     */
    private fun evalGuarded(js: String) {
        val seq = ++jsProbeSeq
        jsProbePending = seq
        handler.postDelayed({
            if (jsProbePending != seq) return@postDelayed
            jsProbePending = 0
            // 从没加载成功过（不是渲染进程的问题），重建也没意义，直接退出别把人困住
            if (pageEverReady) rebuildWebView() else finish()
        }, JS_ALIVE_TIMEOUT_MS)
        webView.evaluateJavascript(js) { result ->
            if (jsProbePending == seq && result == JS_OK) jsProbePending = 0
        }
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
        if (requestCode == REQ_PLAYER && resultCode == RESULT_OK && data != null) {
            // 原生播放器退出：把看到第几集/第几秒交给页面写进观看历史（页面还停在选片页，没被卸载）。
            // 页面若恰好在重建（渲染进程被回收）则这一次丢掉，历史里仍有起播时写的那条
            val idx = data.getIntExtra(PlayerActivity.RESULT_EXTRA_INDEX, -1)
            val pos = data.getIntExtra(PlayerActivity.RESULT_EXTRA_POSITION_SEC, 0)
            val dur = data.getIntExtra(PlayerActivity.RESULT_EXTRA_DURATION_SEC, 0)
            webView.evaluateJavascript(
                "window.tvPlaybackReport && window.tvPlaybackReport($idx, $pos, $dur);", null
            )
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
                evalGuarded(JS_BACK)
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
        webView.resumeTimers()
        webView.requestFocus()
        webView.invalidate()   // 个别电视机型回前台后不自动重绘
        // 从别的 App 回来时渲染进程可能已经没了：先探活，死了就换新的，别把黑屏摆在用户面前
        if (pageReady) evalGuarded("'ok';")
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        root.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }
}
