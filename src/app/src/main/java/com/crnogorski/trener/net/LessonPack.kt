package com.crnogorski.trener.net

import android.content.Context
import com.crnogorski.trener.BuildConfig
import com.crnogorski.trener.data.Content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * Пакет уроков из релизов GitHub (0.51): новые уроки без нового APK.
 *
 * Релиз `katya-lessons-N` с вложением `lessons-N.zip` выкладывает
 * `tools/release.py --lessons`. В теле релиза метка `<!--minApp V-->` —
 * versionCode приложения, которое эти уроки умеет показать: новый тип задания
 * старое приложение не разберёт, и такой пакет не ставится.
 *
 * Ставится сам, без кнопки (решение Кати): весит сотни килобайт, и решать тут
 * нечего — в отличие от шестидесяти мегабайт APK.
 */
class LessonPack(private val context: Context) {

    /** Чем кончилась проверка. */
    sealed interface Outcome {
        /** Свежее нет или спросить не вышло. */
        data object Nothing : Outcome
        /** Пакет новее, но приложению он не по силам. */
        data class NeedsApp(val version: Int) : Outcome
        /** Пакет скачан и поставлен. */
        data class Installed(val version: Int) : Outcome
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun update(): Outcome = withContext(Dispatchers.IO) {
        if (BuildConfig.GITHUB_TOKEN.isBlank()) return@withContext Outcome.Nothing
        runCatching {
            val request = Request.Builder()
                .url("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases?per_page=30")
                .addHeader("Authorization", "Bearer ${BuildConfig.GITHUB_TOKEN}")
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("X-GitHub-Api-Version", "2022-11-28")
                .build()
            val text = client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return@runCatching Outcome.Nothing
                r.body?.string().orEmpty()
            }
            val list = JSONArray(text)
            val fresh = (0 until list.length()).map { list.getJSONObject(it) }
                .mapNotNull { r ->
                    r.optString("tag_name").removePrefix(TAG_PREFIX)
                        .takeIf { r.optString("tag_name").startsWith(TAG_PREFIX) }
                        ?.toIntOrNull()?.let { it to r }
                }
                .maxByOrNull { it.first } ?: return@runCatching Outcome.Nothing
            val (version, json) = fresh
            if (version <= Content.version(context)) return@runCatching Outcome.Nothing
            val minApp = MIN_APP.find(json.optString("body"))?.groupValues?.get(1)?.toInt() ?: 0
            if (minApp > BuildConfig.VERSION_CODE) return@runCatching Outcome.NeedsApp(version)

            val assets = json.optJSONArray("assets") ?: return@runCatching Outcome.Nothing
            val zip = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".zip") }
                ?: return@runCatching Outcome.Nothing
            // Скачивание — тем же путём, что у APK: с редиректом на хранилище
            // без нашего заголовка.
            val release = Release(
                version = version.toString(),
                assetId = zip.optLong("id"),
                assetName = zip.optString("name"),
                sizeMb = 0,
                sizeBytes = zip.optLong("size"),
                notes = "",
                news = "",
                newer = true
            )
            val file = Updater(context).download(release, dirName = "lessons-pack").getOrThrow()
            Content.install(context, file, version)
            file.delete()
            Outcome.Installed(version)
        }.getOrDefault(Outcome.Nothing)
    }

    private companion object {
        /** Двойник `LESSONS_PREFIX` в `tools/release.py`. */
        const val TAG_PREFIX = "katya-lessons-"
        val MIN_APP = Regex("<!--minApp (\\d+)-->")
    }
}
