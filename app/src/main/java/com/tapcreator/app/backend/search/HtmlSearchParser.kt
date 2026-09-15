package com.tapcreator.app.backend.search

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 搜索结果的纯解析工具（无 Android / 网络依赖，可直接 JVM 单测）。
 *
 * 由 [NativeSearchTool] 复用：Bing HTML 结果解析、网页正文转纯文本、搜索 API JSON 解析。
 */
internal object HtmlSearchParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 解析 Bing 搜索 HTML，抽取标题与链接。
     * Bing 每条结果位于 `class="b_algo"` 块内，标题链接为块内首个 `<h2><a ...>`。
     */
    fun parseBingResults(html: String, limit: Int = 6): List<Pair<String, String>> {
        if (html.isBlank()) return emptyList()
        val results = mutableListOf<Pair<String, String>>()
        for (block in html.split("class=\"b_algo\"").drop(1)) {
            if (results.size >= limit) break
            val rawTitle = Regex("<h2[^>]*>\\s*<a[^>]*>(.*?)</a>\\s*</h2>").find(block)
                ?.groupValues?.getOrNull(1) ?: continue
            val href = Regex("<h2[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"").find(block)
                ?.groupValues?.getOrNull(1) ?: continue
            val title = stripTags(rawTitle).trim()
            if (title.isBlank() || !href.startsWith("http")) continue
            results += title to href
        }
        return results
    }

    /** 去除标题内残留的 <strong>/<b> 等标签并解码 HTML 实体。 */
    fun stripTags(raw: String): String =
        decodeEntities(Regex("<[^>]+>").replace(raw, " ").replace(Regex("\\s+"), " ").trim())

    /** 解析搜索 API 返回的 JSON（兼容 {webPages:{value:[{name,url}]}} 与 {results:[{title|name,url|link}]}）。 */
    fun parseApiResults(body: String, limit: Int = 6): List<Pair<String, String>> {
        if (body.isBlank()) return emptyList()
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val items = when {
            "webPages" in root -> runCatching { root["webPages"]!!.jsonObject["value"]!!.jsonArray }.getOrNull()
            "results" in root -> runCatching { root["results"]!!.jsonArray }.getOrNull()
            else -> null
        } ?: return emptyList()
        return items.take(limit).mapNotNull { el ->
            val obj = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val title = obj["name"]?.jsonPrimitive?.content
                ?: obj["title"]?.jsonPrimitive?.content
                ?: return@mapNotNull null
            val link = obj["url"]?.jsonPrimitive?.content
                ?: obj["link"]?.jsonPrimitive?.content
                ?: return@mapNotNull null
            if (title.isBlank() || !link.startsWith("http")) null else title.trim().take(100) to link.trim()
        }
    }

    /** HTML 转纯文本：去 script/style、块级标签转换行、去标签、解码实体、压缩空白。 */
    fun toPlainText(html: String): String = decodeEntities(
        html
            .replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
            .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</h[1-6]>|</li>|<li>"), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("[\\s\u00a0]+"), " ")
            .trim()
    )

    /** 解码常见 HTML 实体。 */
    fun decodeEntities(s: String) = s
        .replace("&amp;", "&").replace("&nbsp;", " ")
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'")
        .replace("&apos;", "'").replace("&ldquo;", "“").replace("&rdquo;", "”")
        .replace("&lsquo;", "‘").replace("&rsquo;", "’").replace("&ndash;", "–")
        .replace("&mdash;", "—").replace("&hellip;", "…").replace("&ensp;", " ")
        .replace("&emsp;", " ")
}
