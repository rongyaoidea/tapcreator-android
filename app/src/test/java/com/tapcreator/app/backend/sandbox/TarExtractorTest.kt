package com.tapcreator.app.backend.sandbox

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * PRootSandbox 的 tar 拆包回归测试：用真实 Alpine minirootfs 资产验证解压完整性。
 *
 * 背景：tar 内 ./bin/sh -> /bin/busybox 是符号链接且 busybox 条目排在其后，
 * 若链接在建链时目标尚未解压（或相对/绝对路径解析错误），会产出「缺少 /bin/sh」的不完整 rootfs。
 * 本测试直接用与 Android 端相同实现（PRootSandbox.extractTar companion）解真实资产，确保链接延迟补建生效。
 */
class TarExtractorTest {

    @Test
    fun `extractTar produces bin-sh and busybox from real alpine rootfs`() {
        val resource = TarExtractorTest::class.java.classLoader!!
            .getResourceAsStream("alpine-rootfs.bin")
            ?: error("测试资源 alpine-rootfs.bin 缺失（应从 app/src/main/assets 复制到 app/src/test/resources）")
        val tmpTar = File.createTempFile("alpine-rootfs", ".tar")
        tmpTar.outputStream().use { out -> GZIPInputStream(resource).use { it.copyTo(out) } }

        val root = File.createTempFile("tar-extract-placeholder", "").let { placeholder ->
            placeholder.delete()
            File(placeholder.parentFile, "rootfs-${System.nanoTime()}").apply { mkdirs() }
        }

        try {
            PRootSandbox.extractTar(tmpTar, root)

            // 关键位点 1：/bin/sh 是 busybox 的符号链接/副本，必须存在且非空（此前 bug 输出缺失）
            val sh = File(root, "bin/sh")
            assertTrue("解压后 bin/sh 应存在（当前为缺失）", sh.exists())
            assertTrue("bin/sh 应有内容（busybox 副本 >100KB），实际 ${sh.length()}B", sh.length() > 100_000)

            // 关键位点 2：busybox 二进制本体
            val busybox = File(root, "bin/busybox")
            assertTrue("bin/busybox 应存在", busybox.exists())
            assertTrue("bin/busybox 应有内容", busybox.length() > 500_000)

            // 关键位点 3：前向/后向符号链接统一补建（/usr/bin 下的 busybox 链接在 tar 中排于 bin/busybox 之前）
            assertTrue("usr/bin/yes 链接应已补建", File(root, "usr/bin/yes").exists())
            assertTrue("bin/zcat 链接应已补建", File(root, "bin/zcat").exists())
        } finally {
            tmpTar.delete()
            root.deleteRecursively()
        }
    }
}