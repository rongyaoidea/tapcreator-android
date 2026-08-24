package com.tapcreator.app.backend.sandbox

import com.tapcreator.app.data.model.TapcreatorException
import com.tapcreator.app.data.prefs.SettingsStore
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 基于沙箱的搜索与网页抓取工具——替代当前脆弱的 OkHttp+Bing HTML 正则解析。
 *
 * 优势：
 *  - 搜索：用 curl 调 DuckDuckGo HTML API + Python BeautifulSoup 解析（比正则更健壮）
 *  - 网页抓取：用 curl + Python 提取正文（比手写 HTML 清理更强大）
 *  - 支持用 Python 写复杂抓取逻辑（Agent 也可自写脚本）
 *
 * 沙箱不可用时回退到原有的 OkHttp 方式（由 AgentBrain 处理）。
 */
@Singleton
class SandboxSearchTool @Inject constructor(
    private val sandbox: PRootSandbox,
    private val settings: SettingsStore,
) {

    data class SearchResult(val title: String, val url: String)

    /**
     * 联网搜索：优先用配置的搜索 API（如有），否则用 curl 调 DuckDuckGo。
     * @return 搜索结果列表，失败返回空列表
     */
    suspend fun search(query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        if (!sandbox.hasCommand("curl")) {
            // 尝试等待网络就绪并安装基础包
            sandbox.ensureBasePackages()
            if (!sandbox.hasCommand("curl")) return@withContext emptyList()
        }

        // 1. 优先用配置的搜索 API（如 Bing Search / SerpAPI）
        val apiKey = settings.searchApiKey()
        val apiUrl = settings.searchApiUrl()
        if (!apiKey.isNullOrBlank() && !apiUrl.isNullOrBlank()) {
            val result = searchViaApi(query, apiUrl, apiKey)
            if (result.isNotEmpty()) return@withContext result
        }

        // 2. 回退：DuckDuckGo HTML 搜索
        return@withContext searchViaDuckDuckGo(query)
    }

    /** 用配置的搜索 API 搜索（OpenAI 兼容格式：POST {apiUrl}?q={query} + Authorization: Bearer {key}） */
    private suspend fun searchViaApi(query: String, apiUrl: String, apiKey: String): List<SearchResult> {
        val script = """
import sys, json, urllib.request, html, re
try:
    url = '${apiUrl.replace("'", "\\'")}?q=' + urllib.parse.quote('${query.replace("'", "\\'")}')
    req = urllib.request.Request(url, headers={'Authorization': 'Bearer ${apiKey.replace("'", "\\'")}', 'User-Agent': 'Mozilla/5.0'})
    resp = urllib.request.urlopen(req, timeout=15)
    data = json.loads(resp.read())
    items = data.get('webPages', {}).get('value', []) if 'webPages' in data else data.get('results', [])
    for item in items[:6]:
        title = item.get('name', '')
        link = item.get('url', '')
        if title and link: print(f"{title}\t{link}")
except Exception as e:
    print(f"ERROR:{e}", file=sys.stderr)
        """.trimIndent()
        val scriptFile = File(System.getProperty("java.io.tmpdir"), "api_search.py")
        scriptFile.writeText(script)
        val sandboxScript = writeTempToSandbox(scriptFile)
        val result = sandbox.exec("python3 '$sandboxScript'", timeoutMs = 20_000L)
        if (!result.isSuccess) return emptyList()
        return result.output.lines().mapNotNull { line ->
            val parts = line.split("\t", limit = 2)
            if (parts.size == 2) SearchResult(parts[0].trim(), parts[1].trim()) else null
        }
    }

    /**
     * 用 DuckDuckGo HTML 搜索 + Python 解析结果。
     */
    private suspend fun searchViaDuckDuckGo(query: String): List<SearchResult> {
        val queryFile = File(System.getProperty("java.io.tmpdir"), "bing_query.txt")
        queryFile.writeText(query)
        val sandboxQuery = writeTempToSandbox(queryFile)
        // 用 cn.bing.com（国内可访问）替代 DuckDuckGo
        val script = """
import sys, html, re, urllib.request
try:
    q = open('$sandboxQuery').read().strip()
    url = 'https://cn.bing.com/search?q=' + urllib.parse.quote(q) + '&count=6'
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/122 Safari/537.36'})
    resp = urllib.request.urlopen(req, timeout=15)
    text = resp.read().decode('utf-8', errors='replace')
    results = []
    for block in text.split('class="b_algo"'):
        if len(results) >= 6: break
        m = re.search(r'<h2[^>]*>\\s*<a[^>]*>(.*?)</a>\\s*</h2>', block, re.S)
        hm = re.search(r'<h2[^>]*>\\s*<a[^>]*href="([^"]+)"', block)
        if m and hm:
            title = html.unescape(re.sub(r'<[^>]+>', ' ', m.group(1)).strip())
            u = html.unescape(hm.group(1))
            if u.startswith('http'):
                results.append((title[:100], u))
    for t, u in results:
        print(f"{t}\t{u}")
except Exception as e:
    print(f"ERROR:{e}", file=sys.stderr)
        """.trimIndent()
        val scriptFile = File(System.getProperty("java.io.tmpdir"), "bing_search.py")
        scriptFile.writeText(script)
        val sandboxScript = writeTempToSandbox(scriptFile)
        val result = sandbox.exec("python3 '$sandboxScript'", timeoutMs = 20_000L)
        if (!result.isSuccess) return emptyList()
        return result.output.lines().mapNotNull { line ->
            val parts = line.split("\t", limit = 2)
            if (parts.size == 2) SearchResult(parts[0].trim(), parts[1].trim()) else null
        }
    }

    /**
     * 抓取网页正文：用 curl + Python 提取纯文本。
     * @return 正文文本，失败返回空串
     */
    suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        if (!sandbox.hasCommand("curl")) {
            sandbox.ensureBasePackages()
            if (!sandbox.hasCommand("curl")) return@withContext ""
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) return@withContext ""

        val script = """
import sys, html, re, urllib.request
try:
    url = sys.argv[1]
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'})
    resp = urllib.request.urlopen(req, timeout=20)
    raw = resp.read().decode('utf-8', errors='replace')
    # 去 script/style
    raw = re.sub(r'(?is)<script[^>]*>.*?</script>', ' ', raw)
    raw = re.sub(r'(?is)<style[^>]*>.*?</style>', ' ', raw)
    # 块级标签转换行
    raw = re.sub(r'(?i)<br\s*/?>|</p>|</div>|</h[1-6]>|</li>|<li>', '\n', raw)
    # 去所有标签
    raw = re.sub(r'<[^>]+>', ' ', raw)
    # 解码 HTML 实体
    for ent, ch in [('&amp;','&'),('&nbsp;',' '),('&lt;','<'),('&gt;','>'),('&quot;','"'),('&#39;',"'")]:
        raw = raw.replace(ent, ch)
    # 压缩空白
    raw = re.sub(r'[ \t\xa0]+', ' ', raw)
    raw = re.sub(r'\n{3,}', '\n\n', raw).strip()
    print(raw[:5000])
except Exception as e:
    print(f"ERROR:{e}", file=sys.stderr)
        """.trimIndent()

        val scriptFile = File(System.getProperty("java.io.tmpdir"), "web_fetch.py")
        scriptFile.writeText(script)
        val sandboxScript = writeTempToSandbox(scriptFile)

        val result = sandbox.exec("python3 '$sandboxScript' '$url'", timeoutMs = 25_000L)
        if (!result.isSuccess) return@withContext ""
        result.output
    }

    /** 把临时文件复制到沙箱 media 目录并返回沙箱内路径 */
    private suspend fun writeTempToSandbox(file: File): String {
        // 确保沙箱就绪
        sandbox.ensureReady()
        // 沙箱 work/media 目录映射到宿主侧 context.filesDir/media，需将文件实际复制过去
        val sandboxDir = File(sandbox.hostMediaDir())
        sandboxDir.mkdirs()
        val target = File(sandboxDir, file.name)
        if (file.exists()) {
            file.copyTo(target, overwrite = true)
        }
        return "/work/media/${file.name}"
    }
}
