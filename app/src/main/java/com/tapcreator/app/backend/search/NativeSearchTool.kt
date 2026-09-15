package com.tapcreator.app.backend.search

import com.tapcreator.app.data.prefs.SettingsStore
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 安卓原生联网搜索与网页抓取（替代已下线的 Alpine/PRoot 沙箱方案）。
 *
 * - 搜索：优先用用户配置的搜索 API（若有），否则用国内可访问的 Bing HTML 端点 + 解析。
 * - 抓取：OkHttp 下载 + HTML 清理转纯文本（上限 512KB 防 OOM）。
 * 纯 Android SDK + OkHttp，无需沙箱二进制与 rootfs。[HtmlSearchParser] 负责纯解析逻辑。
 */
@Singleton
class NativeSearchTool @Inject constructor(
    private val http: OkHttpClient,
    private val settings: SettingsStore,
) {

    data class SearchResult(val title: String, val url: String)

    /** 联网搜索：先走配置 API（失败静默回退），再走 Bing HTML（异常向上抛，供 Agent 回灌为可读错误）。 */
    suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val apiKey = runCatching { settings.searchApiKey() }.getOrNull()
        val apiUrl = runCatching { settings.searchApiUrl() }.getOrNull()
        if (!apiKey.isNullOrBlank() && !apiUrl.isNullOrBlank()) {
            val viaApi = runCatching { searchViaApi(query, apiUrl, apiKey) }.getOrNull().orEmpty()
            if (viaApi.isNotEmpty()) return@withContext viaApi
        }
        searchViaBing(query)
    }

    /** 用配置的搜索 API（OpenAI 兼容：GET {apiUrl}?q={query} + Bearer Key）。 */
    private fun searchViaApi(query: String, apiUrl: String, apiKey: String): List<SearchResult> {
        val url = apiUrl.trim().trimEnd('/') + "?q=" + URLEncoder.encode(query, "UTF-8")
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("User-Agent", "Mozilla/5.0")
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.code !in 200..299) return emptyList()
            return HtmlSearchParser.parseApiResults(readCapped(resp, 512 * 1024))
                .map { (title, link) -> SearchResult(title, link) }
        }
    }

    /** Bing HTML 搜索（国内可访问端点，轻量解析标题与链接）。 */
    private fun searchViaBing(query: String): List<SearchResult> {
        val endpoint = "https://cn.bing.com/search?q=" + URLEncoder.encode(query, "UTF-8")
        http.newCall(webRequest(endpoint)).execute().use { resp ->
            if (resp.code !in 200..299) return emptyList()
            return HtmlSearchParser.parseBingResults(readCapped(resp, 1024 * 1024))
                .map { (title, link) -> SearchResult(title, link) }
        }
    }

    /** 抓取网页正文并转纯文本。失败返回空串。 */
    suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return@withContext ""
        try {
            http.newCall(webRequest(url)).execute().use { resp ->
                if (resp.code !in 200..299) "" else HtmlSearchParser.toPlainText(readCapped(resp, 512 * 1024))
            }
        } catch (_: Throwable) {
            ""
        }
    }

    private fun webRequest(url: String) = Request.Builder()
        .url(url)
        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/122 Safari/537.36")
        .build()

    /** 读取响应体到上限字节，循环读完避免 socket 分块截断；整块解码规避多字节字符被切断。 */
    private fun readCapped(resp: okhttp3.Response, cap: Int): String {
        val bytes = java.io.ByteArrayOutputStream()
        resp.body?.byteStream()?.use { ins ->
            val buf = ByteArray(64 * 1024)
            var total = 0
            var n = ins.read(buf)
            while (n > 0 && total < cap) {
                bytes.write(buf, 0, n)
                total += n
                n = ins.read(buf)
            }
        }
        return bytes.toString("UTF-8")
    }
}
