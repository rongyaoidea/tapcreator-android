package com.tapcreator.app.backend.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HtmlSearchParser 纯 JVM 单测：覆盖 Bing HTML 解析、搜索 API JSON 解析与正文转纯文本。
 * 沙箱下线后搜索/抓取全部由原生解析承担，解析错误会直接导致 Agent 拿不到搜索结果。
 */
class HtmlSearchParserTest {

    @Test
    fun `parseBingResults extracts title and url pairs`() {
        val html = """
            <li class="b_algo"><h2><a href="https://example.com/a">示例<strong>标题</strong>A</a></h2></li>
            <li class="b_algo"><h2><a href="https://example.com/b">标题 B</a></h2></li>
        """.trimIndent()
        val results = HtmlSearchParser.parseBingResults(html)
        assertEquals(2, results.size)
        assertEquals("示例 标题 A" to "https://example.com/a", results[0])
        assertEquals("标题 B" to "https://example.com/b", results[1])
    }

    @Test
    fun `parseBingResults skips non-http and blank titles`() {
        val html = """
            <li class="b_algo"><h2><a href="/relative">相对链接</a></h2></li>
            <li class="b_algo"><h2><a href="https://example.com/ok">有效</a></h2></li>
        """.trimIndent()
        val results = HtmlSearchParser.parseBingResults(html)
        assertEquals(1, results.size)
        assertEquals("https://example.com/ok", results[0].second)
    }

    @Test
    fun `parseBingResults respects limit`() {
        val blocks = (1..10).joinToString("") { i ->
            """<li class="b_algo"><h2><a href="https://example.com/$i">T$i</a></h2></li>"""
        }
        assertEquals(6, HtmlSearchParser.parseBingResults(blocks).size)
        assertEquals(3, HtmlSearchParser.parseBingResults(blocks, limit = 3).size)
    }

    @Test
    fun `parseBingResults empty html yields empty list`() {
        assertTrue(HtmlSearchParser.parseBingResults("").isEmpty())
        assertTrue(HtmlSearchParser.parseBingResults("<html>no results</html>").isEmpty())
    }

    @Test
    fun `parseApiResults handles webPages value shape`() {
        val body = """{"webPages":{"value":[{"name":"标题A","url":"https://a.com"},{"name":"标题B","url":"https://b.com"}]}}"""
        val results = HtmlSearchParser.parseApiResults(body)
        assertEquals(listOf("标题A" to "https://a.com", "标题B" to "https://b.com"), results)
    }

    @Test
    fun `parseApiResults handles results shape with link field`() {
        val body = """{"results":[{"title":"T1","link":"https://t1.com"},{"name":"T2","url":"https://t2.com"}]}"""
        val results = HtmlSearchParser.parseApiResults(body)
        assertEquals(listOf("T1" to "https://t1.com", "T2" to "https://t2.com"), results)
    }

    @Test
    fun `parseApiResults ignores malformed json and blank entries`() {
        assertTrue(HtmlSearchParser.parseApiResults("not json").isEmpty())
        assertTrue(HtmlSearchParser.parseApiResults("").isEmpty())
        val body = """{"results":[{"title":"","url":"https://x.com"},{"title":"T","url":"ftp://x"}]}"""
        assertTrue(HtmlSearchParser.parseApiResults(body).isEmpty())
    }

    @Test
    fun `toPlainText strips tags scripts and decodes entities`() {
        val html = """
            <html><head><script>var x = 1;</script><style>.a{color:red}</style></head>
            <body><h1>标题</h1><p>正文&nbsp;A&amp;B</p><div>第二段</div></body></html>
        """.trimIndent()
        val text = HtmlSearchParser.toPlainText(html)
        assertFalse(text.contains("var x"))
        assertFalse(text.contains("color:red"))
        assertFalse(text.contains("<h1>"))
        assertTrue(text.contains("标题"))
        assertTrue(text.contains("正文 A&B"))
        assertTrue(text.contains("第二段"))
    }

    @Test
    fun `stripTags collapses whitespace and decodes entities`() {
        assertEquals("A B", HtmlSearchParser.stripTags("  A   <b>B</b>  "))
        assertEquals("\"quoted\"", HtmlSearchParser.stripTags("&quot;quoted&quot;"))
    }
}
