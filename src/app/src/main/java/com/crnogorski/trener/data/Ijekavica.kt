package com.crnogorski.trener.data

/**
 * Иекавское написание лемм словаря: `ovde` показывается как `ovdje`.
 *
 * Заведено по жалобе Кати (issue 88): «если учим именно черногорский, то вроде
 * бы „здесь“ должно переводиться как ovdje». Права полностью — курс иекавский,
 * а словарь собран из сербского источника и даёт экавские формы.
 *
 * ## Почему карта показа, а не правка лемм
 *
 * **Лемма — ключ карточки.** Переименовать `ovde` в `ovdje` значит завести
 * новую карточку и потерять весь прогресс по слову: встречи, интервалы,
 * ошибки. Поэтому ключ остаётся экавским навсегда, а меняется только то, что
 * человек видит и печатает.
 *
 * Сверка ответа при этом ничего не теряет: `LocalCheck.reflex` сводит обе
 * формы к одному виду (`ovdje` → `ovde`), так что человек, написавший любую
 * из двух, получает «верно». Карта решает другую задачу — **чему учит
 * эталон**: до неё приложение показывало сербскую форму и молча выдавало её
 * за черногорскую.
 *
 * ## Список ручной и должен таким остаться
 *
 * Правило «e → je» вывести нельзя: голое «je» сидит в `pitanje`, `rođendan`,
 * `putovanje`, где ятя нет вовсе, и обратная замена переписала бы восемьдесят
 * слов словаря в бессмыслицу. Поэтому здесь только те леммы, которые сверены
 * поимённо, — шестьдесят две из 3501.
 *
 * Пополняется **от жалоб**, а раз — сплошным проходом. По жалобе 79 (Катя про
 * `ala`) разбирался верх словаря, и заодно нашлись двадцать шесть экавских
 * лемм в первых шестистах — `čovjek`, `riječ`, `uvijek`, `pjesma`. Машина их
 * только предлагает: кандидаты ищутся подстановкой «e» → «je»/«ije» с
 * проверкой по частотному списку, а решает человек. Из двадцати восьми
 * машинных кандидатов трое оказались браком: `lošije` — сравнительная степень
 * («хуже»), а не иекавская форма; `djeda` — родительный, а черногорская лемма
 * `đed` со свёрткой не сходится; у «света» правильная форма `svjetlo`, а
 * предложенное `svijetlo` — прилагательное «светло».
 *
 * **Каждая пара обязана сводиться свёрткой** (`LocalCheck.reflex`): иначе
 * человек, написавший привычную сербскую форму, получит незаслуженное
 * «неверно». Проверка эта не теоретическая — на ней отсеялись два кандидата.
 * `beo` → `bio` выброшен совсем: свёртка их намеренно не сводит, потому что
 * `bio` это ещё и «был», и карточка «белый» учила бы омониму. У «сидеть»
 * черногорская форма `sjedjeti`, а не `sjediti`: у второго меняется ещё и
 * гласная основы, и со сербским `sedeti` он уже не сходится.
 */
object Ijekavica {

    /** Как показать лемму. Незнакомая возвращается как есть. */
    fun show(lemma: String): String = MAP[lemma] ?: lemma

    /** Сколько лемм переписывается — для отчётов и проверок. */
    val size: Int get() = MAP.size

    private val MAP: Map<String, String> = mapOf(
        "bezbednost" to "bezbjednost",
        "cena" to "cijena",
        "deliti" to "dijeliti",
        "deo" to "dio",
        "devojka" to "djevojka",
        "dečak" to "dječak",
        "dole" to "dolje",
        "gde" to "gdje",
        "greh" to "grijeh",
        "hleb" to "hljeb",
        "izveštaj" to "izvještaj",
        "lek" to "lijek",
        "lekar" to "ljekar",
        "lep" to "lijep",
        "leto" to "ljeto",
        "menjati" to "mijenjati",
        "mesec" to "mjesec",
        "mesto" to "mjesto",
        "mleko" to "mlijeko",
        "napred" to "naprijed",
        "naslediti" to "naslijediti",
        "naterati" to "natjerati",
        "nedelja" to "nedjelja",
        "negde" to "negdje",
        "odavde" to "odavdje",
        "oduvek" to "oduvijek",
        "osećaj" to "osjećaj",
        "ovde" to "ovdje",
        "pesma" to "pjesma",
        "pevati" to "pjevati",
        "pobeda" to "pobjeda",
        "posetiti" to "posjetiti",
        "poslednji" to "posljednji",
        "predsednik" to "predsjednik",
        "preći" to "prijeći",
        "primer" to "primjer",
        "promena" to "promjena",
        "razumeti" to "razumjeti",
        "reka" to "rijeka",
        "reč" to "riječ",
        "sedeti" to "sjedjeti",
        "sever" to "sjever",
        "smeh" to "smijeh",
        "smešan" to "smiješan",
        "sneg" to "snijeg",
        "strela" to "strijela",
        "sutra" to "sjutra",
        "svetlo" to "svjetlo",
        "telo" to "tijelo",
        "umeti" to "umjeti",
        "umreti" to "umrijeti",
        "uvek" to "uvijek",
        "vera" to "vjera",
        "verovati" to "vjerovati",
        "vetar" to "vjetar",
        "veštica" to "vještica",
        "videti" to "vidjeti",
        "voleti" to "voljeti",
        "vreme" to "vrijeme",
        "zvezda" to "zvijezda",
        "čovek" to "čovjek",
        "živeti" to "živjeti",
    )
}
