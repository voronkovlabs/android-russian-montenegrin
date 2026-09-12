package com.crnogorski.trener.net

import com.crnogorski.trener.BuildConfig
import com.crnogorski.trener.data.Complaint
import com.crnogorski.trener.data.IDEA_REASON
import com.crnogorski.trener.data.NOTE_REASON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Жалоба уезжает в issue на GitHub.
 *
 * Раньше жалобы копились в файле, а файл владелец пересылал руками. Теперь
 * приложение заводит issue само, и разбирать их можно прямо из репозитория.
 * Файл при этом никуда не делся и остаётся **очередью отправки**: жалоба нужна
 * ровно тогда, когда что-то сломалось, в том числе в метро, — и терять её из-за
 * отсутствия сети нельзя (см. `ComplaintStore`).
 *
 * **Каждая жалоба — своя issue**, даже если на одно задание пожаловались
 * дважды: так видно, когда именно жаловались, а склеивать их — работа разбора,
 * а не отправки.
 *
 * Токен — мелкий (fine-grained), выданный на один этот репозиторий с правом
 * `Issues: write` и ничем больше. Он лежит в `local.properties` и в git не
 * попадает, но **из APK извлекается так же легко, как ключ Anthropic**: имея
 * файл приложения, можно писать issue в этот репозиторий. Ущерб ограничен
 * ровно этим — ни кода, ни других репозиториев такой токен не открывает.
 */
class GithubIssues(
    private val token: String = BuildConfig.GITHUB_TOKEN,
    private val repo: String = BuildConfig.GITHUB_REPO
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Без токена отправлять нечем: жалобы просто копятся в файле. */
    val configured: Boolean get() = token.isNotBlank() && repo.isNotBlank()

    /**
     * Завести issue. Возвращает её номер или ошибку — по ошибке решают,
     * оставлять ли жалобу в очереди.
     */
    suspend fun create(complaint: Complaint, raw: String, device: String): Result<Int> =
        post(title(complaint), body(complaint, raw, device), labels(complaint))

    /**
     * Отчёт диагностики — тем же путём, что и жалоба, но со своей меткой.
     *
     * Отдельная метка «диагностика», а не «жалоба»: отчёт ничего не просит
     * починить, он сырьё для разбора, и в списке сломанного ему не место. По
     * той же причине, что и у идей.
     */
    suspend fun diagnostics(title: String, body: String): Result<Int> =
        post(title, body, listOf("диагностика"))

    /** Общий путь: завести issue с заголовком, телом и метками. */
    private suspend fun post(title: String, body: String, labels: List<String>): Result<Int> =
        withContext(Dispatchers.IO) {
            if (!configured) {
                return@withContext Result.failure(IllegalStateException("Токен GitHub не задан"))
            }
            val payload = JSONObject()
                .put("title", title)
                // Тело issue у GitHub ограничено 65536 символами; режем с запасом,
                // и режем здесь, а не в отчёте: правило про размер — про issue.
                .put("body", body.take(60_000))
                .put("labels", JSONArray(labels))

            val request = Request.Builder()
                .url("https://api.github.com/repos/$repo/issues")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("X-GitHub-Api-Version", "2022-11-28")
                .post(payload.toString().toRequestBody(JSON_TYPE))
                .build()

            runCatching {
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        // Текст ответа GitHub говорит по делу («Bad credentials»,
                        // «Not Found»), и без него причину не понять.
                        error("GitHub ${response.code}: ${short(text)}")
                    }
                    JSONObject(text).optInt("number", 0)
                }
            }
        }

    /**
     * Заголовок: по нему issue узнают в списке, не открывая.
     *
     * Впереди идентификатор задания — по нему сразу находится строка в файле
     * урока, — потом текст жалобы, если он есть. Пустой текст бывает часто:
     * категории обычно хватает, и тогда заголовком становится она.
     */
    private fun title(c: Complaint): String {
        val where = c.exerciseId.ifBlank {
            if (c.reason == IDEA_REASON) "идея" else "заметка"
        }
        val what = c.note.replace('\n', ' ').trim().ifBlank { reasonLabel(c.reason) }
        val cut = if (what.length > 70) what.take(69).trimEnd() + "…" else what
        return "[$where] $cut"
    }

    /**
     * Тело: всё, что знает жалоба, — включая вердикт модели и версию.
     *
     * Разбирать жалобу без вердикта невозможно: по нему и видно, промпт виноват
     * или эталон. Версия важна не меньше — половина жалоб приходит на то, что
     * уже починено.
     *
     * Имя телефона стоит первой строкой, а не в подписи внизу: телефонов в семье
     * два, и «от кого пришло» читают раньше, чем разбирают.
     *
     * Внизу — **исходная строка JSONL целиком**, свёрнутая в `<details>`.
     * Настоящим вложением её не сделать: загрузка файлов к issue есть только в
     * веб-интерфейсе, у API такого метода нет. А смысл у блока не тот, что
     * кажется: разметку выше человек читает глазами, но разобрать её обратно в
     * запись нельзя — пустые поля выброшены, а всё остальное перемешано с
     * подписями. Строка же разбирается `json.loads` и годится для пакетного
     * разбора жалоб, как раньше годился файл.
     *
     * Второе, и не менее важное: строка переживает **поля, о которых этот код
     * не знает**. Появится в `Complaint` новое поле — разметка его не покажет,
     * пока сюда не допишут строчку, а в JSON оно будет с первого же дня.
     */
    private fun body(c: Complaint, raw: String, device: String): String = buildString {
        appendLine("**${reasonLabel(c.reason)}** · $device · ${c.ts}")
        appendLine()
        line("Задание", c.exerciseId)
        line("Урок", c.lessonId)
        line("Тип", c.type)
        line("Ответ", c.userAnswer)
        line("Эталон", c.expected)
        c.verdict?.let {
            line("Вердикт", if (it.correct) "верно" else "неверно")
            line("Разбор", it.feedback)
            line("Естественнее", it.better)
        }
        if (c.note.isNotBlank()) {
            appendLine()
            appendLine("> ${c.note.replace("\n", "\n> ")}")
        }
        appendLine()
        appendLine("<details><summary>Запись целиком</summary>")
        appendLine()
        appendLine("```json")
        appendLine(raw.trim())
        appendLine("```")
        appendLine("</details>")
        appendLine()
        appendLine("---")
        appendLine("<sub>${c.versionName} (${c.versionCode}) · заведено приложением</sub>")
    }

    private fun StringBuilder.line(name: String, value: String) {
        if (value.isNotBlank()) appendLine("* **$name:** $value")
    }

    /**
     * Метки: общая «жалоба» плюс категория.
     *
     * Названия должны совпадать с метками репозитория — они заведены заранее,
     * с цветами и описаниями. Несуществующую метку GitHub создал бы сам, серую
     * и без описания, поэтому список тут и там держат одинаковым.
     */
    private fun labels(c: Complaint): List<String> =
        // У идеи общей метки «жалоба» нет: она ничего не ломает, и в списке
        // сломанного ей не место.
        if (c.reason == IDEA_REASON) listOf(reasonTag(c.reason))
        else listOf("жалоба", reasonTag(c.reason))

    private fun reasonTag(code: String): String = when (code) {
        "reference_wrong" -> "эталон неверен"
        "ambiguous" -> "и так верно"
        "verdict_wrong" -> "Claude ошибся"
        "audio_unclear" -> "плохо слышно"
        "typo" -> "опечатка"
        NOTE_REASON -> "заметка"
        IDEA_REASON -> "идея"
        else -> "другое"
    }

    private fun reasonLabel(code: String): String = when (code) {
        "reference_wrong" -> "Эталон неверен"
        "ambiguous" -> "Мой ответ тоже верен"
        "verdict_wrong" -> "Claude ошибся"
        "audio_unclear" -> "Плохо слышно"
        "typo" -> "Опечатка"
        NOTE_REASON -> "Заметка"
        IDEA_REASON -> "Идея"
        else -> "Другое"
    }

    /** Ответ об ошибке бывает на страницу — в уведомление столько не нужно. */
    private fun short(text: String): String =
        runCatching { JSONObject(text).optString("message") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: text.take(120)

    private companion object {
        val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
