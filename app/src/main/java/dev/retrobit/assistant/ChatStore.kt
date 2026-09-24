package dev.retrobit.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Riwayat chat di penyimpanan internal aplikasi (tidak ikut backup, tidak keluar dari HP). */
class ChatStore(context: Context) {
    private val file = File(context.filesDir, "chat_history.json")

    fun load(): List<ChatMessage> = try {
        if (!file.exists()) {
            emptyList()
        } else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ChatMessage(
                    id = o.getLong("id"),
                    role = if (o.getString("role") == "user") Role.USER else Role.ASSISTANT,
                    text = o.getString("text"),
                    origin = o.optString("origin", ""),
                )
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun save(messages: List<ChatMessage>) {
        val arr = JSONArray()
        for (m in messages.takeLast(MAX)) {
            arr.put(
                JSONObject()
                    .put("id", m.id)
                    .put("role", if (m.role == Role.USER) "user" else "assistant")
                    .put("text", m.text)
                    .put("origin", m.origin),
            )
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }

    fun clear() {
        file.delete()
    }

    companion object {
        const val MAX = 200
    }
}
