package dev.whynet.app.service

import dev.whynet.core.engine.RemoteSessionCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

enum class RemoteRole(val wire: String) {
    GUEST("guest"),   // Телефон близкого (показывает код и выполняет проверки)
    HELPER("helper"), // Телефон помощника (вводит код и отправляет команды)
}

data class RemoteMessage(
    val seq: Int,
    val sender: String,
    val type: String,
    val target: String = "",
    val title: String = "",
    val body: String = "",
    val outcome: String = "",
)

/**
 * Минимальный HTTP/HTTPS клиент для сеанса помощи по 6-значному коду.
 * Все полезные сообщения шифруются AES-256-GCM до отправки, relay видит только roomId и ciphertext.
 */
object RemoteHelpRelayClient {

    suspend fun sendEncrypted(
        baseUrl: String,
        code: String,
        role: RemoteRole,
        type: String,
        target: String = "",
        title: String = "",
        body: String = "",
        outcome: String = "",
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = baseUrl.trim().trimEnd('/')
            require(cleanBase.startsWith("http://") || cleanBase.startsWith("https://")) {
                "Укажите корректный адрес relay-сервера (https://... или http://...)"
            }
            val roomId = RemoteSessionCrypto.deriveRoomId(code)
            val plainObj = JSONObject().apply {
                put("type", type)
                if (target.isNotEmpty()) put("target", target)
                if (title.isNotEmpty()) put("title", title)
                if (body.isNotEmpty()) put("body", body)
                if (outcome.isNotEmpty()) put("outcome", outcome)
            }
            val cipher = RemoteSessionCrypto.encrypt(code, plainObj.toString())
            val envelope = JSONObject().apply {
                put("sender", role.wire)
                put("payload", cipher)
            }.toString()

            val conn = (URL("$cleanBase/room/$roomId/send").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 5000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            try {
                conn.outputStream.use { it.write(envelope.toByteArray(Charsets.UTF_8)) }
                val httpCode = conn.responseCode
                val respText = (if (httpCode in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (httpCode !in 200..299) {
                    throw IllegalStateException("Relay HTTP $httpCode: ${respText.take(120)}")
                }
                JSONObject(respText.ifBlank { "{}" }).optInt("seq", 0)
            } finally {
                conn.disconnect()
            }
        }
    }

    suspend fun pollEncrypted(
        baseUrl: String,
        code: String,
        role: RemoteRole,
        afterSeq: Int,
    ): Result<List<RemoteMessage>> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = baseUrl.trim().trimEnd('/')
            require(cleanBase.startsWith("http://") || cleanBase.startsWith("https://")) {
                "Некорректный адрес relay-сервера"
            }
            val roomId = RemoteSessionCrypto.deriveRoomId(code)
            val url = URL("$cleanBase/room/$roomId/poll?after=$afterSeq&role=${role.wire}")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
            }
            try {
                val httpCode = conn.responseCode
                if (httpCode == 404 || httpCode == 410) {
                    return@runCatching emptyList()
                }
                val respText = (if (httpCode in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (httpCode !in 200..299) {
                    throw IllegalStateException("Relay HTTP $httpCode: ${respText.take(120)}")
                }
                val root = JSONObject(respText.ifBlank { """{"messages":[]}""" })
                val arr = root.optJSONArray("messages") ?: JSONArray()
                buildList {
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val seq = item.optInt("seq", 0)
                        val sender = item.optString("sender")
                        val payload = item.optString("payload")
                        val plain = RemoteSessionCrypto.decrypt(code, payload) ?: continue
                        val obj = runCatching { JSONObject(plain) }.getOrNull() ?: continue
                        add(
                            RemoteMessage(
                                seq = seq,
                                sender = sender,
                                type = obj.optString("type"),
                                target = obj.optString("target"),
                                title = obj.optString("title"),
                                body = obj.optString("body"),
                                outcome = obj.optString("outcome"),
                            ),
                        )
                    }
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    suspend fun closeRoom(baseUrl: String, code: String) = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = baseUrl.trim().trimEnd('/')
            if (!cleanBase.startsWith("http://") && !cleanBase.startsWith("https://")) return@runCatching
            val roomId = RemoteSessionCrypto.deriveRoomId(code)
            val conn = (URL("$cleanBase/room/$roomId/close").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3000
                readTimeout = 3000
            }
            try {
                conn.responseCode
            } finally {
                conn.disconnect()
            }
        }
    }
}
