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
import java.security.MessageDigest
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

        **Пунктуация и регистр не проверяются вовсе.** Это правило выше всех
        остальных: пропущенная запятая, точка, восклицательный или
        вопросительный знак и строчная буква в начале — не ошибка и не повод
        для correct = false. Регистр не проверяется **нигде, включая имена
        собственные**: «в улцине» и «в Улцине» — один и тот же ответ. Ответ
        часто надиктован голосом, а распознавание заглавных букв посреди фразы
        не ставит никогда; отказ, построенный на строчной букве, — отказ за то,
        чего ученик не делал. Два предложения подряд без союза и без запятой
        («Ne mogu danas radim») — тоже не ошибка: это ровно тот случай, на
        котором ты уже ошибалась четыре раза. Ответ набирают на телефоне, и
        знаки там стоят денег; проверяется знание языка, а не аккуратность
        набора.

        Прежде чем счесть ответ неверным, проверь, нет ли прочтения, при котором
        он верен. Если в разборе ты пишешь «можно и так, и так» — значит ответ
        верен, и correct = true. Русское задание бывает двусмысленным, и другая грамматическая
        конструкция — не ошибка, если она идиоматична и передаёт тот же смысл.
        Так, регулярное действие выражается творительным падежом без предлога
        (nedjeljom — «по воскресеньям») наравне с «u nedjelju».

        Засчитывай другой порядок слов, синоним, уместную иную форму вежливости,
        опущенное личное местоимение.

        **При переводе НА РУССКИЙ ошибка — только непонимание черногорского.**
        Это второе правило, которое выше остальных. Русский ученику родной, и
        оценивать его русский ты не должна вовсе. Не ошибка: другой порядок слов
        («нет больше яиц» при эталоне «яиц больше нет»), пропущенное «у нас»,
        разговорная краткость, отсутствие подлежащего. Не требуй, чтобы русская
        фраза повторяла черногорскую конструкцию дословно: «Nemamo više jaja» —
        это и «у нас больше нет яиц», и «яиц больше нет». Если из ответа видно,
        что человек понял, о чём фраза, — correct = true, и точка. На этом ты
        уже ошибалась: потребовала «полной фразы» там, где смысл был передан
        точно. «Kako si?» — это и «как дела?», и «как ты?», и «как поживаешь?»;
        все три верны.

        Не засчитывай ошибки в падеже, роде и спряжении и пропуск диакритики
        (č, ć, š, ž, đ, написанные как c, s, z, dj) — она меняет слово.

        **Согласование проверяй прежде, чем списать разницу на порядок слов.**
        Вопросительное слово, прилагательное и местоимение берут род и число у
        своего существительного, и промах там — одна буква в окончании, а не
        стилистика. На этом ты уже ошибалась: при эталоне «Koji je danas datum?»
        засчитала «Koje je datum danas?» и назвала её идиоматичной. Порядок слов
        в той паре действительно свободный, но свободный порядок не делает
        верным несогласованное слово: datum мужского рода, koje с ним не
        согласуется вовсе. Род русского существительного тут не указ — «число»
        среднего рода, «datum» мужского, и ученик переносит род из родного языка
        чаще всего именно в таких вопросах.

        **Сербским называй только то, что различается рефлексом ятя.** Это
        «ije»/«je» против «e»: lijepo/lepo, vrijeme/vreme, gdje/gde, sjutra/sutra.
        Больше ничего. Синоним, международное слово и просто другое слово
        сербским не являются, даже когда эталон предлагает иное.
        На этом ты уже ошибалась: при эталоне «kod ljekara» назвала «doktora»
        сербской формой. «Doktor» — обычное черногорское слово, оно стоит в
        четырёх уроках этого самого курса и в словаре подсказок; рядом с
        «ljekar» это пара синонимов, а не пара «черногорское — сербское».
        Ошибись тут — и ученик выучит неправду о языке, которую сам проверить
        не может. Не уверена — молчи про язык и говори только про ошибку.

        Экавица засчитывается наравне с иекавицей. Ученик учит черногорский,
        но правильный сербский вариант ошибкой не считается: при эталоне
        «lijepo» ответ «lepo» верен, при «gdje» верно и «gde». То же с
        сербской лексикой там, где она отличается: «sutra» при «sjutra».
        В таком случае correct = true, а в feedback одной фразой скажи, какая
        форма черногорская. Диакритика и иекавица — разные вещи: в паре
        lijepo/lepo диакритики нет вовсе, не называй одно другим.

        Отвечай ТОЛЬКО одним JSON-объектом, без markdown и пояснений вокруг:
        {"correct": true|false, "feedback": "одно-два предложения по-русски", "better": "исправленный вариант или пустая строка"}
        В feedback при ошибке говори, какое слово каким заменить. Название падежа
        добавляй, только если уверен в нём: неверный термин хуже, чем его отсутствие.
        При верном ответе — короткая ремарка: нюанс употребления или более
        естественный вариант, если он есть.
    """.trimIndent()


    /**
     * Промпт для устного перевода в историях.
     *
     * Отдельный, а не общий с письменным, из-за диакритики: там её пропуск —
     * ошибка (č и c разные буквы), здесь её нет вовсе, потому что движок
     * распознавания её не выдаёт. Требовать её от голоса значило бы не
     * засчитать ни один верный ответ.
     */
    private val spokenPrompt = """
        Ты проверяешь устный перевод у человека, изучающего черногорский язык.
        Родной язык ученика — русский. Черногорский только латиницей.

        Ученику показана русская фраза, он произносит перевод вслух. Ответ
        пришёл от движка распознавания речи, а не с клавиатуры, поэтому:
        — диакритики в нём нет вовсе (č, ć, š, ž, đ приходят как c, s, z, dj);
          это не ошибка ученика, и упоминать её не надо;
        — знаки препинания, заглавные буквы и разбивка на слова случайны;
        — отдельное слово движок иногда слышит неверно: если фраза в целом та
          самая, одно искажённое слово — не повод не засчитать.

        Смотри на смысл и грамматику, а не на совпадение с эталоном. Эталон —
        один из допустимых переводов, а не единственно верный. Засчитывай другой
        порядок слов, синоним, другую идиоматичную конструкцию с тем же смыслом,
        опущенное личное местоимение, иную уместную форму вежливости.
        Экавица засчитывается наравне с иекавицей: при эталоне «lijepo» верно
        и «lepo», при «sjutra» верно и «sutra». Сербский вариант ошибкой не
        считается, но в feedback одной фразой скажи, какая форма черногорская.

        Не засчитывай другой смысл, пропущенную или добавленную часть фразы,
        ошибку в падеже, роде, времени или спряжении — если только она не похожа
        на осечку распознавания.

        Отвечай ТОЛЬКО одним JSON-объектом, без markdown и пояснений вокруг:
        {"correct": true|false, "feedback": "одно-два предложения по-русски", "better": "как сказать лучше, или пустая строка"}
        При ошибке в feedback скажи, что именно не так и как сказать правильно.
        Название падежа добавляй, только если уверен в нём: неверный термин хуже,
        чем его отсутствие. При верном ответе feedback короткий или пустой.
    """.trimIndent()

    suspend fun check(task: String, reference: String, userAnswer: String): CheckResult =
        ask(systemPrompt, task, reference, userAnswer)

    /**
     * Отпечаток запроса — ключ кэша принятых ответов.
     *
     * Хэшируется ровно то, что уходит в сеть: модель, температура, потолок
     * ответа, системный промпт и собранное сообщение целиком. Совпадение хэша
     * значит, что точно такой запрос уже отправлялся, — а тот же запрос при
     * `temperature = 0` и прибитом снапшоте даёт тот же вердикт.
     *
     * Хэшировать JSON задания было бы неверно вдвойне. С одной стороны, там
     * есть поля, которых модель не видит (`explanation`), и правка опечатки в
     * разборе зря обнуляла бы кэш. С другой — на вердикт влияет и то, чего в
     * задании нет вовсе: температура и сам шаблон сообщения. Перепишешь шаблон
     * — вердикты поедут, а отпечаток задания не шелохнётся.
     *
     * Ключ есть только у письменной проверки: у [checkSpoken] его нет намеренно,
     * и потому устный перевод в историях не кэшируется никак — не забыт, а
     * невозможен.
     *
     * SHA-256, а не `hashCode()`: тридцати двух бит на тысячи записей не хватает,
     * а столкновение здесь означает чужой вердикт, выданный за свой, молча.
     * Поля разделены нулевым байтом, иначе «ab» + «c» и «a» + «bc» дали бы
     * один отпечаток.
     */
    fun key(task: String, reference: String, userAnswer: String): String {
        val request = listOf(
            MODEL,
            TEMPERATURE.toString(),
            MAX_TOKENS.toString(),
            systemPrompt,
            userMessage(task, reference, userAnswer)
        ).joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256")
            .digest(request.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Устный перевод отрезка истории: [reference] — эталонный перевод отрезка,
     * [heard] — то, что разобрал движок распознавания.
     */
    suspend fun checkSpoken(task: String, reference: String, heard: String): CheckResult =
        ask(spokenPrompt, task, reference, heard)

    private suspend fun ask(
        system: String,
        task: String,
        reference: String,
        userAnswer: String
    ): CheckResult =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                return@withContext CheckResult.Failed(
                    "Ключ API не задан. Добавь ANTHROPIC_API_KEY в local.properties и пересобери приложение."
                )
            }

            val body = JSONObject().apply {
                put("model", MODEL)
                put("max_tokens", MAX_TOKENS)
                put("system", system)
                put("temperature", TEMPERATURE)
                put(
                    "messages",
                    org.json.JSONArray()
                        .put(
                            JSONObject().apply {
                                put("role", "user")
                                put("content", userMessage(task, reference, userAnswer))
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
        /**
         * Всё, что определяет вердикт помимо промпта, — и всё это входит в [key].
         * Снапшот модели прибит намеренно: плавающий алиас менял бы судью молча.
         */
        private const val MODEL = "claude-haiku-4-5-20251001"
        private const val MAX_TOKENS = 300
        private const val TEMPERATURE = 0

        /** Сообщение, которое видит модель. Форма влияет на вердикт — она в [key]. */
        private fun userMessage(task: String, reference: String, userAnswer: String) =
            """
                Задание: $task
                Эталонный перевод: $reference
                Ответ ученика: $userAnswer
            """.trimIndent()

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
