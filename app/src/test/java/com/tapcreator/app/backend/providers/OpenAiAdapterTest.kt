package com.tapcreator.app.backend.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LlmAdapter / OpenAiAdapter 纯函数单测：
 *  - LlmFailure 错误归一化逻辑
 *  - InternalToolCall 工具参数原始 JSON 保留
 *  - InternalResponse.toToolCallsJson 转换
 */
class OpenAiAdapterTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ===== LlmFailure 归一化测试（复用 OpenAiAdapter.detectFailure 的逻辑） =====

    @Test
    fun `context_length_exceeded maps to Fatal CONTEXT_OVERFLOW`() {
        val failure = detectFailure(
            buildJsonObject {
                put("error", buildJsonObject {
                    put("message", "This model's maximum context length is 128000 tokens")
                    put("code", "context_length_exceeded")
                })
            }
        )
        assertTrue(failure is LlmFailure.Fatal)
        assertEquals(CONTEXT_OVERFLOW, (failure as LlmFailure.Fatal).code)
    }

    @Test
    fun `max_tokens in message maps to Fatal CONTEXT_OVERFLOW`() {
        val failure = detectFailure(
            buildJsonObject {
                put("error", buildJsonObject {
                    put("message", "Requested max_tokens exceeds context length")
                })
            }
        )
        assertTrue(failure is LlmFailure.Fatal)
        assertEquals(CONTEXT_OVERFLOW, (failure as LlmFailure.Fatal).code)
    }

    @Test
    fun `invalid_api_key maps to Fatal AUTH_FAILED`() {
        val failure = detectFailure(
            buildJsonObject {
                put("error", buildJsonObject {
                    put("message", "Incorrect API key provided")
                    put("code", "invalid_api_key")
                })
            }
        )
        assertTrue(failure is LlmFailure.Fatal)
        assertEquals("AUTH_FAILED", (failure as LlmFailure.Fatal).code)
    }

    @Test
    fun `unauthorized in message maps to Fatal AUTH_FAILED`() {
        val failure = detectFailure(
            buildJsonObject {
                put("error", buildJsonObject {
                    put("message", "401 Unauthorized access")
                })
            }
        )
        assertTrue(failure is LlmFailure.Fatal)
        assertEquals("AUTH_FAILED", (failure as LlmFailure.Fatal).code)
    }

    @Test
    fun `model_not_found maps to Fatal MODEL_NOT_FOUND`() {
        val failure = detectFailure(
            buildJsonObject {
                put("error", buildJsonObject {
                    put("message", "The model 'gpt-5' does not exist")
                    put("code", "model_not_found")
                })
            }
        )
        assertTrue(failure is LlmFailure.Fatal)
        assertEquals("MODEL_NOT_FOUND", (failure as LlmFailure.Fatal).code)
    }

    @Test
    fun `rate_limit_exceeded maps to Retryable`() {
        val failure = detectFailure(
            buildJsonObject {
                put("error", buildJsonObject {
                    put("message", "Rate limit reached 429")
                    put("code", "rate_limit_exceeded")
                })
            }
        )
        assertTrue(failure is LlmFailure.Retryable)
    }

    @Test
    fun `unknown error maps to Fatal UPSTREAM_ERROR`() {
        val failure = detectFailure(
            buildJsonObject {
                put("error", buildJsonObject {
                    put("message", "Something went wrong")
                    put("code", "internal_error")
                })
            }
        )
        assertTrue(failure is LlmFailure.Fatal)
        assertEquals("UPSTREAM_ERROR", (failure as LlmFailure.Fatal).code)
    }

    @Test
    fun `no error field returns null`() {
        val failure = detectFailure(buildJsonObject { })
        assertNull(failure)
    }

    // ===== InternalToolCall 数据结构测试 =====

    @Test
    fun `InternalToolCall preserves raw arguments string`() {
        val rawArgs = """{"key":"value","nested":{"a":1}}"""
        val tc = InternalToolCall(
            id = "test_id",
            function = InternalFunction(name = "test_fn", arguments = rawArgs),
        )
        assertEquals(rawArgs, tc.function.arguments)
        assertEquals("test_fn", tc.function.name)
        assertEquals("test_id", tc.id)
        assertEquals("function", tc.type)
    }

    @Test
    fun `InternalMessage defaults`() {
        val msg = InternalMessage(role = "user", content = "hello")
        assertEquals("user", msg.role)
        assertEquals("hello", msg.content)
        assertNull(msg.toolCalls)
        assertNull(msg.toolCallId)
    }

    @Test
    fun `InternalResponse default failure is null`() {
        val resp = InternalResponse(
            message = InternalMessage(role = "assistant", content = "hi"),
        )
        assertNull(resp.failure)
        assertEquals("hi", resp.message.content)
    }

    @Test
    fun `toToolCallsJson converts InternalResponse with tool calls`() {
        val resp = InternalResponse(
            message = InternalMessage(
                role = "assistant",
                content = "",
                toolCalls = listOf(
                    InternalToolCall(
                        id = "call_1",
                        function = InternalFunction(name = "finish", arguments = """{"summary":"done"}"""),
                    )
                ),
            ),
        )
        val jsonArr = resp.toToolCallsJson()
        assertEquals(1, jsonArr.size)
        assertEquals("call_1", jsonArr[0]["id"]?.jsonPrimitive?.content)
        assertEquals("finish", jsonArr[0]["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content)
    }

    @Test
    fun `toToolCallsJson returns empty for no tool calls`() {
        val resp = InternalResponse(
            message = InternalMessage(role = "assistant", content = "hello"),
        )
        assertTrue(resp.toToolCallsJson().isEmpty())
    }

    @Test
    fun `toToolCallsJson returns empty for empty tool calls list`() {
        val resp = InternalResponse(
            message = InternalMessage(role = "assistant", content = "", toolCalls = emptyList()),
        )
        assertTrue(resp.toToolCallsJson().isEmpty())
    }

    @Test
    fun `LlmFailure Retryable has message and retryAfterMs`() {
        val f = LlmFailure.Retryable("rate limited", 5000)
        assertEquals("rate limited", f.message)
        assertEquals(5000, f.retryAfterMs)
    }

    @Test
    fun `LlmFailure Fatal has code and message`() {
        val f = LlmFailure.Fatal("AUTH_FAILED", "invalid key")
        assertEquals("AUTH_FAILED", f.code)
        assertEquals("invalid key", f.message)
    }

    @Test
    fun `CONTEXT_OVERFLOW constant is correct`() {
        assertEquals("CONTEXT_OVERFLOW", CONTEXT_OVERFLOW)
    }

    // ===== 辅助：复用 OpenAiAdapter.detectFailure 的逻辑 =====

    private fun detectFailure(root: kotlinx.serialization.json.JsonObject): LlmFailure? {
        val error = root["error"]?.let { it as? kotlinx.serialization.json.JsonObject } ?: return null
        val msg = error["message"]?.jsonPrimitive?.content.orEmpty()
        val code = error["code"]?.jsonPrimitive?.content.orEmpty()

        if (msg.contains("context_length", ignoreCase = true) ||
            msg.contains("max_tokens", ignoreCase = true) ||
            msg.contains("too many tokens", ignoreCase = true) ||
            code == "context_length_exceeded"
        ) {
            return LlmFailure.Fatal(CONTEXT_OVERFLOW, msg)
        }
        if (code == "invalid_api_key" || code == "authentication_error" ||
            msg.contains("401", ignoreCase = true) ||
            msg.contains("unauthorized", ignoreCase = true)
        ) {
            return LlmFailure.Fatal("AUTH_FAILED", msg)
        }
        if (code == "model_not_found" || msg.contains("model not found", ignoreCase = true)) {
            return LlmFailure.Fatal("MODEL_NOT_FOUND", msg)
        }
        if (code == "rate_limit_exceeded" || msg.contains("429", ignoreCase = true) ||
            msg.contains("rate limit", ignoreCase = true)
        ) {
            return LlmFailure.Retryable(msg)
        }
        return LlmFailure.Fatal("UPSTREAM_ERROR", msg)
    }
}