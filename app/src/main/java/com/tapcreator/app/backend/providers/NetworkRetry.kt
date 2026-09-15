package com.tapcreator.app.backend.providers

import com.tapcreator.app.data.model.TapcreatorException
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 上游网络重试与错误映射（从 ProviderGateway 抽出，避免 God File）。
 * 指数退避 1s→2s，共 3 次尝试；等待用 delay 不阻塞 IO 线程。
 */
internal object NetworkRetry {
    suspend fun callWithRetry(client: OkHttpClient, builder: Request.Builder): Response {
        var last: IOException? = null
        repeat(3) { i ->
            try {
                currentCoroutineContext().ensureActive()
                return withContext(Dispatchers.IO) { client.newCall(builder.build()).execute() }
            } catch (e: IOException) {
                last = e
                if (i < 2) delay(1_000L shl i)
            }
        }
        throw friendlyNetworkError(checkNotNull(last))
    }

    fun friendlyNetworkError(e: IOException): TapcreatorException {
        val msg = e.message.orEmpty().lowercase()
        val friendly = when {
            e is java.net.SocketTimeoutException -> "网络超时：${e.message ?: "请求超时"}"
            e is java.net.ConnectException -> "无法连接服务器，请检查网络与接口地址"
            msg.contains("abort") || msg.contains("reset") || msg.contains("broken pipe") ||
                msg.contains("connection") -> "网络连接被中断，请检查网络后重试"
            msg.contains("timeout") -> "网络请求超时，请稍后重试"
            else -> "网络请求失败：${e.message}"
        }
        return TapcreatorException(friendly, "NETWORK")
    }
}
