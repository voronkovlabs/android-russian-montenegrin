package com.crnogorski.trener.net

import com.crnogorski.trener.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

@Serializable
data class Verdict(
    val correct: Boolean,
    val feedback: String,
    val better: String = ""
)

sealed class CheckResult {
    data class Ok(val verdict: Verdict) : CheckResult()
    data class Failed(val message: String) : CheckResult()
}

/**
 * Свободные переводы проверяет claude-haiku-4-5: он видит разницу между
 * ошибкой и допустимым синонимом, чего строковое сравнение не умеет.
 */
class HaikuChecker(
    private val apiKey: String = BuildConfig.ANTHROPIC_API_KEY
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private val systemPrompt = """
        Ты проверяешь упражнения по переводу для человека, изучающего черногорский язык.
        Родной язык ученика — русский. Черногорский только латиницей, иекавица.

        Тебе дают задание, эталонный перевод и ответ ученика.
        Эталон — один из допустимых вариантов, а не единственно верный.

        Прежде чем счесть ответ неверным, проверь, нет ли прочтения, при котором
        он верен. Русское задание бывает двусмысленным, и другая грамматическая
        конструкция — не ошибка, если она идиоматична и передаёт тот же смысл.
        Так, регулярное действие выражается творительным падежом без предлога
        (nedjeljom — «по воскресеньям») наравне с «u nedjelju».

        Засчитывай другой порядок слов, синоним, уместную иную форму вежливости,
        опущенное личное местоимение.
        Не засчитывай ошибки в падеже, роде и спряжении, пропуск диакритики
        (č, ć, š, ž, đ) и экавицу вместо иекавицы (lepo вместо lijepo).
        Диакритика и иекавица — разные вещи, не называй одно другим.

        Отвечай ТОЛЬКО одним JSON-объектом, без markdown и пояснений вокруг:
        {"correct": true|false, "feedback": "одно-два предложения по-русски", "better": "исправленный вариант или пустая строка"}
        В feedback при ошибке говори, какое слово каким заменить. Название падежа
        добавляй, только если уверен в нём: неверный термин хуже, чем его отсутствие.
        При верном ответе — короткая ремарка: нюанс употребления или более
        естественный вариант, если он есть.
    """.trimIndent()

    suspend fun check(task: String, reference: String, userAnswer: String): CheckResult =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                return@withContext CheckResult.Failed(
                    "Ключ API не задан. Добавь ANTHROPIC_API_KEY в local.properties и пересобери приложение."
                )
            }

            val userMessage = """
                Задание: $task
                Эталонный перевод: $reference
                Ответ ученика: $userAnswer
            """.trimIndent()

            val body = JSONObject().apply {
                put("model", "claude-haiku-4-5-20251001")
                put("max_tokens", 300)
                put("system", systemPrompt)
                put("temperature", 0)
                put("messages", org.json.JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", userMessage)
                    }
                ))
            }.toString()

            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext CheckResult.Failed("Сервер ответил ${response.code}")
                    }
                    val text = JSONObject(raw)
                        .getJSONArray("content")
                        .getJSONObject(0)
                        .getString("text")
                        .trim()
                        .removePrefix("```json")
                        .removePrefix("```")
                        .removeSuffix("```")
                        .trim()
                    CheckResult.Ok(json.decodeFromString<Verdict>(text))
                }
            } catch (e: Exception) {
                CheckResult.Failed(e.message ?: "Нет связи с сервером")
            }
        }
}
