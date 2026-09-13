package com.crnogorski.trener.net

import com.crnogorski.trener.BuildConfig
import com.crnogorski.trener.data.Complaint
import com.crnogorski.trener.data.IDEA_REASON
import com.crnogorski.trener.data.NOTE_REASON
import com.crnogorski.trener.data.MY_PHRASE_REASON
import com.crnogorski.trener.data.PHRASE_REASON
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
    suspend fun create(
        complaint: Complaint,
        raw: String,
        device: String,
        person: String? = null
    ): Result<Int> =
        post(title(complaint), body(complaint, raw, device), labels(complaint) + person(person))

    /**
     * Отчёт диагностики — тем же путём, что и жалоба, но со своей меткой.
     *
     * Отдельная метка «диагностика», а не «жалоба»: отчёт ничего не просит
     * починить, он сырьё для разбора, и в списке сломанного ему не место. По
     * той же причине, что и у идей.
     */
    suspend fun diagnostics(title: String, body: String, person: String? = null): Result<Int> =
        post(title, body, listOf("диагностика") + person(person))

    /**
     * Метка с именем человека, если мы знаем, чей это телефон.
     *
     * Имя стоит и в теле, но метка видна **в списке, не открывая issue**, и по
     * ней фильтруют: «покажи всё, на что жаловалась Катя». Берётся оно из
     * карты в подтягиваемых настройках (`Tuning.people`), поэтому новое имя
     * заводится правкой файла, а не сборкой.
     *
     * Метку под незнакомое имя GitHub создаст сам — серую и без описания, как
     * и всякую, которой в репозитории нет. Это терпимо: цвет дописывается
     * руками один раз, а молчаливая потеря метки была бы хуже.
     */
    private fun person(name: String?): List<String> =
        name?.trim()?.takeIf { it.isNotBlank() }?.let { listOf(it) }.orEmpty()

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
     * Закрытая issue: что с ней стало и как она называлась.
     *
     * [completed] — закрыта **как выполненная**, а не «не буду чинить». Это
     * единственное, по чему машина отличает «починили» от «посмотрели и
     * решили не трогать», и на этом держится всё уведомление (см. [Replies]).
     */
    data class Closed(
        val number: Int,
        val title: String,
        val completed: Boolean,
        val idea: Boolean
    )

    /**
     * Какие из **наших** issue уже закрыты.
     *
     * Одним запросом на всё: берём последние закрытые issue репозитория и
     * оставляем те, чьи номера телефон помнит за собой. Спрашивать про каждую
     * свою по отдельности значило бы слать десяток запросов ради ответа
     * «ничего не изменилось», а он такой почти всегда.
     *
     * Плата за экономию честная: если закрытых чужих issue набежит больше
     * [PAGE] раньше, чем телефон заглянет сюда, свою он в этой странице не
     * найдёт. Для репозитория, куда пишут три телефона, это не случается.
     *
     * Пустая причина закрытия считается **не** выполнением: соврать «починили»
     * хуже, чем промолчать.
     */
    suspend fun closedAmong(mine: Set<Int>): Result<List<Closed>> = withContext(Dispatchers.IO) {
        if (!configured || mine.isEmpty()) return@withContext Result.success(emptyList())
        val url = "https://api.github.com/repos/$repo/issues" +
            "?state=closed&sort=updated&direction=desc&per_page=$PAGE"
        runCatching {
            client.newCall(read(url)).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("GitHub ${response.code}: ${short(text)}")
                val list = JSONArray(text)
                buildList {
                    for (i in 0 until list.length()) {
                        val item = list.optJSONObject(i) ?: continue
                        val number = item.optInt("number", 0)
                        if (number !in mine) continue
                        val labels = item.optJSONArray("labels") ?: JSONArray()
                        add(
                            Closed(
                                number = number,
                                title = item.optString("title"),
                                completed = item.optString("state_reason") == "completed",
                                idea = (0 until labels.length()).any {
                                    labels.optJSONObject(it)?.optString("name") == "идея"
                                }
                            )
                        )
                    }
                }
            }
        }
    }

    /**
     * Последний комментарий к issue — то есть тот, которым её закрывали.
     *
     * Он и объясняет человеку, что сделали. Берётся первая непустая строка:
     * комментарий писан для разбора и бывает на экран, а в уведомлении места
     * на абзац. Разметка снимается грубо — в шторке она бы просто мозолила
     * глаза звёздочками.
     */
    suspend fun lastComment(number: Int): String? = withContext(Dispatchers.IO) {
        if (!configured) return@withContext null
        val url = "https://api.github.com/repos/$repo/issues/$number/comments?per_page=100"
        runCatching {
            client.newCall(read(url)).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val list = JSONArray(response.body?.string().orEmpty())
                val body = list.optJSONObject(list.length() - 1)?.optString("body").orEmpty()
                body.lineSequence()
                    .map { it.replace("**", "").replace("`", "").trim() }
                    .firstOrNull { it.isNotBlank() }
                    ?.take(200)
            }
        }.getOrNull()
    }

    private fun read(url: String): Request = Request.Builder()
        .url(url)
        .addHeader("Authorization", "Bearer $token")
        .addHeader("Accept", "application/vnd.github+json")
        .addHeader("X-GitHub-Api-Version", "2022-11-28")
        .get()
        .build()

    /**
     * Заголовок: по нему issue узнают в списке, не открывая.
     *
     * Впереди идентификатор задания — по нему сразу находится строка в файле
     * урока, — потом текст жалобы, если он есть. Пустой текст бывает часто:
     * категории обычно хватает, и тогда заголовком становится она.
     */
    private fun title(c: Complaint): String {
        val where = c.exerciseId.ifBlank {
            when (c.reason) {
                IDEA_REASON -> "идея"
                PHRASE_REASON -> "фраза"
                MY_PHRASE_REASON -> "моя фраза"
                else -> "заметка"
            }
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
        when (c.reason) {
            // Общая метка у идей своя — «идея»: по ней они и разбираются
            // скопом. Живая фраза получает вдобавок собственную, потому что
            // разбирают её отдельно и в другое время.
            IDEA_REASON -> listOf("идея")
            PHRASE_REASON -> listOf("идея", "живая фраза")
            MY_PHRASE_REASON -> listOf("идея", "мои фразы")
            else -> listOf("жалоба", reasonTag(c.reason))
        }

    private fun reasonTag(code: String): String = when (code) {
        "reference_wrong" -> "эталон неверен"
        "ambiguous" -> "и так верно"
        "verdict_wrong" -> "Claude ошибся"
        "audio_unclear" -> "плохо слышно"
        "typo" -> "опечатка"
        NOTE_REASON -> "заметка"
        IDEA_REASON -> "идея"
        PHRASE_REASON -> "живая фраза"
        MY_PHRASE_REASON -> "мои фразы"
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
        PHRASE_REASON -> "Живая фраза"
        MY_PHRASE_REASON -> "Моя фраза"
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

        /** Сколько последних закрытых issue просматриваем за один запрос. */
        const val PAGE = 50
    }
}
