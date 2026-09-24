package dev.retrobit.assistant.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class GeminiException(val httpCode: Int, message: String, val retryAfterSec: Long? = null) : Exception(message)

data class Turn(val role: String, val text: String) // role: "user" | "model"

/**
 * Gemini API (tier gratis) via streamGenerateContent?alt=sse. Tanpa SDK supaya APK kecil
 * dan perilakunya terlihat jelas.
 */
class GeminiClient {
    fun stream(
        apiKey: String,
        model: String,
        system: String,
        turns: List<Turn>,
        grounding: Boolean,
    ): Flow<String> = flow {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse")
        val body = JSONObject().apply {
            put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            put("contents", JSONArray().apply {
                for (t in turns) {
                    put(JSONObject().put("role", t.role).put("parts", JSONArray().put(JSONObject().put("text", t.text))))
                }
            })
            put("generationConfig", JSONObject().put("thinkingConfig", JSONObject().put("thinkingLevel", "low")))
            if (grounding) put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        }
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = FIRST_CHUNK_TIMEOUT_MS // lihat docs/architecture.md §4.10 (failover)
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", apiKey)
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = try { conn.errorStream?.bufferedReader()?.readText().orEmpty() } catch (_: Exception) { "" }
                throw parseError(code, err)
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val json = JSONObject(line.substring(5).trim())
                    json.optJSONObject("error")?.let { throw GeminiException(it.optInt("code", 500), it.optString("message")) }
                    val parts = json.optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts") ?: continue
                    for (i in 0 until parts.length()) {
                        val p = parts.optJSONObject(i) ?: continue
                        if (p.optBoolean("thought", false)) continue
                        val text = p.optString("text", "")
                        if (text.isNotEmpty()) emit(text)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    private fun parseError(code: Int, body: String): GeminiException {
        return try {
            val e = JSONObject(body).getJSONObject("error")
            var retry: Long? = null
            val details = e.optJSONArray("details")
            if (details != null) {
                for (i in 0 until details.length()) {
                    val d = details.optJSONObject(i) ?: continue
                    if (d.optString("@type").endsWith("RetryInfo")) {
                        retry = d.optString("retryDelay").removeSuffix("s").toDoubleOrNull()?.toLong()
                    }
                }
            }
            GeminiException(code, "${e.optString("status")}: ${e.optString("message").take(200)}", retry)
        } catch (_: Exception) {
            GeminiException(code, "HTTP $code ${body.take(200)}")
        }
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val FIRST_CHUNK_TIMEOUT_MS = 10_000
    }
}
