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
 * поимённо, — тридцать семь из 3501.
 *
 * Пополняется **от жалоб**: заметили экавскую форму в карточке — строчка сюда.
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
        "deo" to "dio",
        "devojka" to "djevojka",
        "gde" to "gdje",
        "greh" to "grijeh",
        "hleb" to "hljeb",
        "lekar" to "ljekar",
        "lep" to "lijep",
        "leto" to "ljeto",
        "mesec" to "mjesec",
        "mesto" to "mjesto",
        "mleko" to "mlijeko",
        "naslediti" to "naslijediti",
        "nedelja" to "nedjelja",
        "negde" to "negdje",
        "ovde" to "ovdje",
        "primer" to "primjer",
        "razumeti" to "razumjeti",
        "reka" to "rijeka",
        "sedeti" to "sjedjeti",
        "sever" to "sjever",
        "smeh" to "smijeh",
        "sneg" to "snijeg",
        "strela" to "strijela",
        "sutra" to "sjutra",
        "telo" to "tijelo",
        "umeti" to "umjeti",
        "vera" to "vjera",
        "verovati" to "vjerovati",
        "vetar" to "vjetar",
        "videti" to "vidjeti",
        "voleti" to "voljeti",
        "vreme" to "vrijeme",
        "zvezda" to "zvijezda",
        "živeti" to "živjeti",
    )
}
