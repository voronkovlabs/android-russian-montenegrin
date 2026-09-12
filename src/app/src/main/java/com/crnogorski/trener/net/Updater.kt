package com.crnogorski.trener.net

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.crnogorski.trener.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Что известно про свежий релиз.
 *
 * [newer] — есть ли смысл обновляться; сравниваются номера версий, а не строки:
 * «1.9» и «1.50» как текст сравниваются неправильно.
 */
data class Release(
    val version: String,
    val assetId: Long,
    val assetName: String,
    val sizeMb: Int,
    /**
     * Размер вложения в байтах — им меряется полоса скачивания.
     *
     * Не выводится из [sizeMb]: там целые мегабайты, и полоса дёргалась бы
     * с шагом в полтора процента. А главное — размер известен **до** запроса,
     * поэтому полоса определённая с первого байта, а не «крутится, пока что-то
     * идёт».
     */
    val sizeBytes: Long,
    val notes: String,
    val newer: Boolean
)

/**
 * Обновление приложения из релизов GitHub.
 *
 * APK и так лежит вложением в релизе, версия видна в теге — значит проверить
 * обновление это один запрос, а поставить его можно, не ходя в OneDrive и не
 * подключая кабель.
 *
 * Работает тем же токеном, что и жалобы, но требует у него права
 * `Contents: read`: вложения релизов приватного репозитория — те же данные
 * репозитория.
 *
 * **Подпись должна совпадать.** APK собирается отладочным ключом с машины
 * владельца; пока ключ тот же, обновление ставится поверх с сохранением
 * прогресса. Сменится ключ — Android откажет («приложение не установлено»), и
 * придётся сносить старое, потеряв базу. Поэтому `~/.android/debug.keystore`
 * стоит беречь наравне с кодом.
 */
class Updater(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // Вложение отдаётся редиректом на хранилище, и туда наш заголовок
        // Authorization слать нельзя — хранилище ответит ошибкой. Ходим по
        // редиректу сами, вторым запросом и без заголовков.
        .followRedirects(false)
        .build()

    /** Есть ли свежий релиз. `null` — не удалось спросить. */
    suspend fun check(): Result<Release> = withContext(Dispatchers.IO) {
        if (BuildConfig.GITHUB_TOKEN.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Токен GitHub не задан"))
        }
        runCatching {
            val request = Request.Builder()
                .url("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
                .addHeader("Authorization", "Bearer ${BuildConfig.GITHUB_TOKEN}")
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("X-GitHub-Api-Version", "2022-11-28")
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error("GitHub ${response.code}: ${message(text)}")
                }
                val json = JSONObject(text)
                val version = json.optString("tag_name").removePrefix("v")
                val assets = json.optJSONArray("assets")
                    ?: error("в релизе нет вложений")
                var apk: JSONObject? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) {
                        apk = a
                        break
                    }
                }
                val asset = apk ?: error("в релизе нет APK")
                Release(
                    version = version,
                    assetId = asset.optLong("id"),
                    assetName = asset.optString("name"),
                    sizeMb = (asset.optLong("size") / 1_048_576).toInt(),
                    sizeBytes = asset.optLong("size"),
                    notes = json.optString("body").lineSequence().firstOrNull().orEmpty(),
                    newer = isNewer(version, BuildConfig.VERSION_NAME)
                )
            }
        }
    }

    /**
     * Скачивает вложение и отдаёт файл.
     *
     * Кладём в кэш: система вычистит его сама, а держать шестьдесят мегабайт
     * рядом с прогрессом незачем.
     *
     * [onProgress] зовётся долей от нуля до единицы — по ней рисуется полоса.
     * Шестьдесят мегабайт по мобильной сети идут минуту и дольше, и молчащее
     * приложение в это время неотличимо от зависшего: до 1.72 единственным
     * признаком жизни была всплывающая строка «Скачиваю 58 МБ…», сказанная
     * один раз в самом начале.
     */
    suspend fun download(
        release: Release,
        onProgress: (Float) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "update").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, release.assetName)

            val first = Request.Builder()
                .url(
                    "https://api.github.com/repos/${BuildConfig.GITHUB_REPO}" +
                        "/releases/assets/${release.assetId}"
                )
                .addHeader("Authorization", "Bearer ${BuildConfig.GITHUB_TOKEN}")
                .addHeader("Accept", "application/octet-stream")
                .addHeader("X-GitHub-Api-Version", "2022-11-28")
                .build()

            client.newCall(first).execute().use { response ->
                val location = response.header("Location")
                val body = when {
                    // Редирект на хранилище: второй запрос уже без токена.
                    location != null -> client.newCall(
                        Request.Builder().url(location).build()
                    ).execute()
                    response.isSuccessful -> response
                    else -> error("GitHub ${response.code}")
                }
                body.use { file ->
                    if (!file.isSuccessful) error("скачивание: ${file.code}")
                    val payload = file.body ?: error("пустой ответ")
                    // Сколько всего. Хранилище обычно говорит само, но если
                    // ответ придёт кусками, длины в нём не будет вовсе — тогда
                    // берём размер вложения, он известен из релиза.
                    val total = payload.contentLength().takeIf { it > 0 }
                        ?: release.sizeBytes
                    val stream = payload.byteStream()
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var shown = -1
                    target.outputStream().use { out ->
                        while (true) {
                            val read = stream.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            done += read
                            if (total <= 0) continue
                            // Сообщаем, только когда сменился процент: кусков
                            // тут около тысячи, а разных чисел на полосе сто, и
                            // девятьсот лишних перерисовок экрана — это работа,
                            // отнятая у самого скачивания.
                            val percent = (done * 100 / total).toInt()
                            if (percent != shown) {
                                shown = percent
                                onProgress(percent / 100f)
                            }
                        }
                    }
                }
            }
            if (target.length() < 1_000_000) {
                target.delete()
                error("файл скачался не целиком")
            }
            target
        }
    }

    /**
     * Отдаёт APK системному установщику.
     *
     * Ставит не приложение, а Android: наше дело — показать файл. Разрешение
     * «установка из этого источника» человек даёт один раз, и просить его
     * заранее нельзя — система показывает свой экран.
     */
    fun install(file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context, "${context.packageName}.files", file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Разрешено ли нам вообще предлагать установку. */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Экран системы, где это разрешение выдают. */
    fun askForInstallRights() {
        val intent = Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun message(text: String): String =
        runCatching { JSONObject(text).optString("message") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: text.take(100)

    private companion object {
        /**
         * Сравнение версий по числам, а не по строкам: «1.9» и «1.50» как текст
         * сравниваются неправильно, и обновление молча не предлагалось бы.
         */
        fun isNewer(remote: String, local: String): Boolean {
            val a = parts(remote)
            val b = parts(local)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        fun parts(v: String): List<Int> =
            v.split('.', '-').mapNotNull { it.filter(Char::isDigit).toIntOrNull() }
    }
}
