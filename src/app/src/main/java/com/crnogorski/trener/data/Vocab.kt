package com.crnogorski.trener.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * Форма слова: написание и ячейка парадигмы (`a-s` — винительный единственного,
 * `Vmr1s` — настоящее время, первое лицо единственного).
 */
@Serializable
data class VocabForm(val f: String, val s: String)

/** Живое предложение из пула, где слово стоит в форме [f]. */
@Serializable
data class VocabExample(
    val sr: String,
    val ru: String,
    val f: String,
    val id: String = "",
    val level: Int = 0
)

/**
 * Слово словаря: перевод, живые формы, примеры.
 *
 * [n] — место по частоте: слова вводятся сверху вниз. [odd] — формы с
 * изменённой основой (`apoteka` → `apoteci`), единственные, что учат
 * поштучно. [doubt] — подозрение вычитки, слово при этом остаётся в списке.
 */
@Serializable
data class VocabWord(
    val id: String,
    val n: Int = 0,
    val pos: String = "",
    val gloss: String = "",
    val forms: List<VocabForm> = emptyList(),
    val odd: List<String> = emptyList(),
    val ex: List<VocabExample> = emptyList(),
    val doubt: String = ""
)

/**
 * Словарь целиком.
 *
 * [frames] — рамки «ячейка → образец фразы», [slots] — их же человеческие
 * подписи. И то и другое лежит в файле, а не в коде: это данные языка, и
 * пересобираются они тем же скриптом, что и сами слова.
 */
@Serializable
data class VocabFile(
    val version: Int = 0,
    val frames: Map<String, String> = emptyMap(),
    val slots: Map<String, String> = emptyMap(),
    val words: List<VocabWord> = emptyList()
)

/** Что именно спрашивает карточка. Ключ уходит в `exerciseId` и не меняется. */
enum class VocabKind(val key: String) {
    /** Значение: «аптека» → `apoteka`. Одна на слово. */
    Meaning("mean"),

    /**
     * Образец склонения: форма меняется от повторения к повторению.
     *
     * Это не «карточка на каждый падеж»: половина падежей — одно и то же
     * написание, а остальное русский выводит сам, падежная система у него та
     * же. Проверяется правило, а правило одно, поэтому и карточка одна.
     */
    Pattern("decl"),

    /** Неожиданная форма: основа поменялась, вывести её нельзя. */
    Odd("form"),

    /**
     * Обратный перевод: `apoteka` → «аптека».
     *
     * Отдельная карточка, а не оборот той же: узнавание и порождение — разное
     * знание, и держатся они по-разному. Узнавание проще, поэтому и заводится
     * само, как только слово вообще заведено.
     */
    Recall("back")
}

/**
 * Словарные карточки: три вида заданий вокруг одной леммы.
 *
 * Единица — слово, а не словоформа. Форм у сербского существительного
 * пятнадцать, разных написаний семь, а непредсказуемых — одно; карточка на
 * каждую ячейку была бы курсом, который никто не закончит.
 *
 * Данные готовит `research/tools/build_vocab.py` из srLex и размеченного пула
 * предложений. В приложение едут только сами слова и формы — 439 КБ, — а не
 * лексикон, из которого они добыты.
 */
class VocabRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var cached: VocabFile? = null

    /** Отсутствие файла — не ошибка: раздел просто окажется пустым. */
    suspend fun load(): VocabFile = withContext(Dispatchers.IO) {
        cached ?: runCatching {
            json.decodeFromString<VocabFile>(
                context.assets.open(PATH).bufferedReader().use { it.readText() }
            )
        }.getOrDefault(VocabFile()).also { cached = it }
    }

    /**
     * Сколько новых слов уже взято сегодня.
     *
     * Дневной потолок — единственное, что удерживает словарь от превращения в
     * долг: карточке нужно 8–10 встреч, и каждое взятое сегодня слово это
     * работа, назначенная себе будущему.
     *
     * Считается по дате, а не по скользящим суткам: «сегодня» человек понимает
     * как календарный день, а не как «за последние 24 часа».
     */
    fun introducedToday(): Int =
        if (prefs.getString(KEY_DAY, "") == today()) prefs.getInt(KEY_COUNT, 0) else 0

    /**
     * Отметить взятые слова.
     *
     * Отмечаем при сборке сессии, а не по факту ответа: бросить сессию на
     * полуслове можно, и тогда день потратится зря. Ошибка эта в одну сторону —
     * новых слов будет меньше обещанного, а не больше, — и она безопаснее
     * обратной, при которой потолок бы не работал вовсе.
     */
    fun noteIntroduced(count: Int) {
        if (count <= 0) return
        prefs.edit()
            .putString(KEY_DAY, today())
            .putInt(KEY_COUNT, introducedToday() + count)
            .apply()
    }

    private fun today(): String = LocalDate.now().toString()

    companion object {
        private const val PATH = "vocab/words.json"

        // Те же настройки, что у копии прогресса: файл один на приложение.
        private const val PREFS = "crnogorski"
        private const val KEY_DAY = "vocab_day"
        private const val KEY_COUNT = "vocab_today"

        /** Все словарные карточки лежат в SRS под этим «уроком». */
        const val LESSON_ID = "vocab"

        /**
         * Сколько верных ответов считаем достаточным, чтобы назвать слово выученным.
         *
         * Десять — не круглое число наугад. Saragi, Nation и Meister (1978)
         * нашли примерно десять встреч как порог, за которым слово
         * закрепляется; Webb (2007) намерил, что при десяти и более
         * разнесённых встречах припоминание через неделю держится выше 80%, а
         * при менее чем шести падает ниже 30%. Порог считается по общему счёту
         * верных ответов, а не по серии подряд: интервалы растут, и десять
         * подряд набегали бы годами.
         *
         * Выученное из очереди не пропадает — просто перестаёт быть срочным.
         */
        const val LEARNED = 10

        /**
         * Идентификатор карточки: `w-apoteka-mean`, `w-apoteka-form-apoteci`.
         *
         * Он уходит в базу и в жалобы, поэтому не меняется никогда — как и
         * `exerciseId` у заданий уроков. Леммы латинские и без дефисов, так
         * что разобрать такой ключ обратно можно однозначно.
         */
        fun cardId(lemma: String, kind: VocabKind, form: String = ""): String =
            if (kind == VocabKind.Odd) "w-$lemma-${kind.key}-$form"
            else "w-$lemma-${kind.key}"

        fun isVocab(exerciseId: String): Boolean = exerciseId.startsWith("w-")

        /** Лемма из идентификатора карточки, или null, если это не она. */
        fun lemmaOf(exerciseId: String): String? {
            if (!isVocab(exerciseId)) return null
            val rest = exerciseId.removePrefix("w-")
            return VocabKind.entries
                .firstNotNullOfOrNull { kind ->
                    val mark = "-${kind.key}"
                    val cut = rest.indexOf(mark)
                    if (cut > 0) rest.take(cut) else null
                }
        }
    }
}

/**
 * Задание по карточке.
 *
 * [repetitions] — сколько раз карточку уже проходили: по нему у образца
 * склонения выбирается ячейка. Не случайно, а по счётчику — иначе одно и то же
 * повторение показывало бы разное при каждой перерисовке экрана.
 */
fun VocabFile.exerciseFor(
    word: VocabWord,
    kind: VocabKind,
    form: String = "",
    repetitions: Int = 0
): Exercise? = when (kind) {
    VocabKind.Recall -> Exercise.Word(
        id = VocabRepository.cardId(word.id, kind),
        label = "Что это значит?",
        prompt = word.id,
        answer = word.gloss,
        explanation = "",
        native = true
    )

    VocabKind.Meaning -> Exercise.Word(
        id = VocabRepository.cardId(word.id, kind),
        label = "Как это по-черногорски?",
        prompt = word.gloss,
        answer = word.id,
        explanation = ""
    )

    VocabKind.Pattern -> {
        val slot = word.forms.getOrNull(
            if (word.forms.isEmpty()) 0 else repetitions % word.forms.size
        )
        slot?.let {
            // Живое предложение лучше рамки, но оно есть меньше чем у трети
            // слов: пул — 5987 фраз. Рамка добирает остальных, и в ней падеж
            // задан предлогом, а не подписью.
            val sample = word.ex.firstOrNull { ex -> ex.f == it.f }
            val prompt = sample?.let { s -> blank(s.sr, s.f) }
                ?: frames[it.s]
                ?: return null
            Exercise.Word(
                id = VocabRepository.cardId(word.id, kind),
                label = slots[it.s]?.let { name -> "Поставь в нужную форму: $name" }
                    ?: "Поставь в нужную форму",
                prompt = "$prompt  (${word.id})",
                answer = it.f,
                explanation = sample?.ru.orEmpty()
            )
        }
    }

    VocabKind.Odd -> {
        val slot = word.forms.firstOrNull { it.f == form }
        val pattern = slot?.let { frames[it.s] }
        Exercise.Word(
            id = VocabRepository.cardId(word.id, kind, form),
            label = slot?.s?.let { slots[it] }?.let { "Особая форма: $it" }
                ?: "Особая форма",
            prompt = "${pattern ?: "___"}  (${word.id})",
            answer = form,
            // Основа тут меняется, и сказать об этом стоит прямо: иначе
            // выглядит как опечатка в задании.
            explanation = "Основа меняется: ${word.id} → $form"
        )
    }
}

/** Прячет слово в предложении под прочерк — из примера получается задание. */
private fun blank(sentence: String, form: String): String =
    Regex("(?<![\\p{L}])${Regex.escape(form)}(?![\\p{L}])", RegexOption.IGNORE_CASE)
        .replace(sentence, "___")
