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
    val feedback: String = "",
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
        При переводе на русский проверяется понимание черногорского, а не стиль:
        к порядку слов и естественности русской фразы не придирайся, русский —
        родной язык ученика.
        Не засчитывай ошибки в падеже, роде и спряжении, пропуск диакритики
        и экавицу вместо иекавицы.
        Это разные вещи, и подменять их в объяснении нельзя. Диакритика — это
        č, ć, š, ž, đ, написанные как c, s, z, dj. Разница ije/je и e
        (lijepo против lepo) — иекавица против экавицы, никакой диакритики
        в этих словах нет.

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
                put(
                    "messages",
                    org.json.JSONArray()
                        .put(
                            JSONObject().apply {
                                put("role", "user")
                                put("content", userMessage)
                            }
                        )
                        // Ответ за модель начат открывающей скобкой: так она не
                        // может предварить его ```json-обёрткой или вступлением.
                        .put(
                            JSONObject().apply {
                                put("role", "assistant")
                                put("content", JSON_PREFILL)
                            }
                        )
                )
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
                    val continued = JSONObject(raw)
                        .getJSONArray("content")
                        .getJSONObject(0)
                        .getString("text")
                    // Обычно модель продолжает с «"correct": ...», но иногда
                    // повторяет скобку сама — тогда вторая была бы лишней.
                    val text = if (continued.trimStart().startsWith(JSON_PREFILL)) {
                        continued
                    } else {
                        JSON_PREFILL + continued
                    }

                    val payload = firstJsonObject(text)
                        ?: return@withContext CheckResult.Failed("модель ответила не JSON-ом")
                    val verdict = runCatching { json.decodeFromString<Verdict>(payload) }.getOrNull()
                        ?: return@withContext CheckResult.Failed("ответ модели не разобрать")
                    CheckResult.Ok(verdict)
                }
            } catch (e: Exception) {
                CheckResult.Failed(e.message ?: "нет связи с сервером")
            }
        }

    companion object {
        /** Начало ответа, написанное за модель, — она продолжает с этого места. */
        private const val JSON_PREFILL = "{"

        /**
         * Вырезает первый полный JSON-объект из ответа модели.
         *
         * Даже с prefill модель иногда дописывает что-нибудь после закрывающей
         * скобки — обёртку ``` или извинение с новой формулировкой. Парсер на
         * таком хвосте падал с «Expected EOF», и задание уходило в Blocked,
         * хотя вердикт был получен целиком.
         *
         * Скобки считаются с оглядкой на строки и экранирование: фигурная
         * скобка внутри feedback не должна закрывать объект.
         */
        internal fun firstJsonObject(raw: String): String? {
            val start = raw.indexOf('{')
            if (start < 0) return null

            var depth = 0
            var inString = false
            var escaped = false
            for (i in start until raw.length) {
                val c = raw[i]
                when {
                    escaped -> escaped = false
                    inString && c == '\\' -> escaped = true
                    c == '"' -> inString = !inString
                    inString -> Unit
                    c == '{' -> depth++
                    c == '}' -> {
                        depth--
                        if (depth == 0) return raw.substring(start, i + 1)
                    }
                }
            }
            return null
        }
    }
}
