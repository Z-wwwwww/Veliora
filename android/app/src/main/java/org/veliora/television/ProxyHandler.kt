package org.veliora.television

import android.net.Uri
import android.util.Log
import android.webkit.WebResourceResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * 原生实现 server.mjs 的 /proxy/<encodeURIComponent(url)> 端点。
 * 行为对齐 server.mjs:167-238：UA 伪装、豆瓣图床 Referer、敏感响应头过滤。
 * 本地 App 内部信任，跳过 auth 参数校验（server.mjs 的密码鉴权是为公网部署设计的）。
 */
class ProxyHandler {

    companion object {
        private const val TAG = "VelioraProxy"
        // 与 server.mjs config.userAgent 一致
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"

        // server.mjs BLOCKED_HOSTS / BLOCKED_IP_PREFIXES 同款默认值
        private val BLOCKED_HOSTS = setOf("localhost", "127.0.0.1", "0.0.0.0", "::1")
        private val BLOCKED_IP_PREFIXES = listOf("192.168.", "10.", "172.")

        // server.mjs FILTERED_HEADERS + WebView 不适用的传输层头
        private val FILTERED_HEADERS = setOf(
            "content-security-policy", "cookie", "set-cookie",
            "x-frame-options", "access-control-allow-origin",
            "content-encoding", "content-length", "transfer-encoding", "connection"
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true) // 对应 server.mjs 的 maxRetries 重试
        .build()

    fun handle(uri: Uri): WebResourceResponse {
        val target: String
        try {
            // 必须用 encodedPath：目标 URL 的 ?& 等字符以百分号编码存在于路径段中
            val encoded = uri.encodedPath?.removePrefix("/proxy/") ?: ""
            target = URLDecoder.decode(encoded, "UTF-8")
        } catch (e: Exception) {
            return errorResponse(400, "无效的代理路径")
        }

        if (!isValidUrl(target)) {
            return errorResponse(400, "无效或被禁止的目标 URL")
        }

        return try {
            val reqBuilder = Request.Builder()
                .url(target)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
            // 豆瓣图片 CDN 有防盗链，需带对应 Referer，否则返回 418（对齐 server.mjs:194）
            if (target.contains("doubanio.com")) {
                reqBuilder.header("Referer", "https://movie.douban.com/")
            }

            val resp = client.newCall(reqBuilder.build()).execute()
            val body = resp.body ?: return errorResponse(502, "目标无响应体")

            val contentType = body.contentType()
            val mime = contentType?.let { "${it.type}/${it.subtype}" }
                ?: "application/octet-stream"
            val charset = contentType?.charset()?.name()

            val headers = mutableMapOf<String, String>()
            for ((name, value) in resp.headers) {
                if (name.lowercase() !in FILTERED_HEADERS) headers[name] = value
            }
            headers["Access-Control-Allow-Origin"] = "*"

            // WebResourceResponse 不允许 3xx 状态码、空的或非 ASCII 的 reasonPhrase
            var code = resp.code
            if (code in 300..399) code = 502
            val reason = resp.message.ifBlank { "OK" }.let {
                if (it.all { c -> c.code in 32..126 }) it else "OK"
            }

            WebResourceResponse(mime, charset, code, reason, headers, body.byteStream())
        } catch (e: Exception) {
            Log.w(TAG, "代理请求失败: $target", e)
            errorResponse(502, "代理请求失败: ${e.message}")
        }
    }

    /** 对齐 server.mjs isValidUrl：仅 http/https，拦截本机与内网地址 */
    private fun isValidUrl(url: String): Boolean {
        val parsed = try {
            java.net.URI(url)
        } catch (e: Exception) {
            return false
        }
        if (parsed.scheme != "http" && parsed.scheme != "https") return false
        val host = parsed.host ?: return false
        if (host in BLOCKED_HOSTS) return false
        if (BLOCKED_IP_PREFIXES.any { host.startsWith(it) }) return false
        return true
    }

    private fun errorResponse(code: Int, message: String): WebResourceResponse {
        val json = """{"success":false,"error":"$message"}"""
        // reasonPhrase 需为 ASCII，中文信息放在 JSON body 里
        return WebResourceResponse(
            "application/json", "utf-8", code, "Proxy Error",
            mapOf("Access-Control-Allow-Origin" to "*"),
            ByteArrayInputStream(json.toByteArray(Charsets.UTF_8))
        )
    }
}
