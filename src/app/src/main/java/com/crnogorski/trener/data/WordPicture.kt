package com.crnogorski.trener.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache

/**
 * Нарисованная картинка к слову (4.41) — `assets/pictures/<лемма>.webp`.
 *
 * Встаёт **там же, где стоял эмодзи** ([WordEmoji]): над условием карточки
 * значения и особой формы, на лице перевёртыша, в виджетах. Где своей картинки
 * нет, остаётся эмодзи — так что слово без рисунка выглядит ровно как раньше.
 *
 * Картинки рисовались в ComfyUI в одном пластилиновом стиле и прошли оценку
 * владельцем на телефоне (экран [PictureReview]); как и почему — в
 * `research/kartinki-process.md`.
 *
 * **Синонимы берут картинку соседа** (`assets/pictures/synonyms.txt`, строки
 * `лемма=чья_картинка`): `mačak` рисовать незачем, раз есть `mačka`, видовую
 * пару `kupovati` — раз есть `kupiti`. Генерировать их не стали намеренно —
 * было бы два разных рисунка на одно и то же.
 *
 * Список картинок читается один раз ([init]): это `assets.list` и маленький
 * файл, миллисекунды. Сами картинки декодируются по требованию и держатся в
 * небольшом кэше — карточки листаются, и одна картинка нужна по нескольку раз.
 */
object WordPicture {
    private const val DIR = "pictures"

    @Volatile private var names: Set<String> = emptySet()
    @Volatile private var aliases: Map<String, String> = emptyMap()

    /** Картинки 256 px по ~250 КБ в памяти: тридцать штук — меньше 8 МБ. */
    private val cache = LruCache<String, Bitmap>(30)

    fun init(context: Context) {
        if (names.isNotEmpty()) return
        val assets = context.assets
        val have = assets.list(DIR).orEmpty()
            .filter { it.endsWith(".webp") }
            .map { it.removeSuffix(".webp") }
            .toSet()
        val syn = runCatching {
            assets.open("$DIR/synonyms.txt").bufferedReader().readLines()
                .filter { '=' in it && !it.startsWith("#") }
                .associate { line ->
                    val (a, b) = line.split('=', limit = 2)
                    a.trim() to b.trim()
                }
                .filterValues { it in have }
        }.getOrDefault(emptyMap())
        aliases = syn
        names = have
    }

    /** Имя картинки для леммы — своей или соседа-синонима; `null`, если нет. */
    fun of(lemma: String): String? = when {
        lemma in names -> lemma
        else -> aliases[lemma]
    }

    /** Картинка по имени из [of]; `null`, если файл не прочитался. */
    fun bitmap(context: Context, name: String): Bitmap? {
        cache.get(name)?.let { return it }
        val bmp = runCatching {
            context.assets.open("$DIR/$name.webp").use { BitmapFactory.decodeStream(it) }
        }.getOrNull() ?: return null
        cache.put(name, bmp)
        return bmp
    }
}
