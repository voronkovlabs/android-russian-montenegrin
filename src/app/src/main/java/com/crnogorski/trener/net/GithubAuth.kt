package com.crnogorski.trener.net

import com.crnogorski.trener.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Вход через GitHub без единого секрета в приложении — device flow.
 *
 * Заведено 23.09.2026 по просьбе владельца: «надо сделать авторизацию
 * опциональной с фоллбэком на вшитый ключ». Вошёл — жалобы заводятся от твоего
 * имени; не вошёл — работает общий токен из сборки, ровно как раньше.
 *
 * ## Почему именно device flow
 *
 * Обычный веб-поток требует `client_secret` для обмена кода на токен, а
 * положить секрет в приложение, которое скачивают, нельзя: он перестаёт быть
 * секретом. Device flow придуман ровно для таких клиентов — консолей, телевизоров,
 * железок, — и документация говорит прямо: «the `client_secret` is not needed
 * for the device flow». В APK едет только [CLIENT_ID], а он не секрет вовсе.
 *
 * Порядок такой: просим у GitHub пару кодов, показываем человеку короткий и
 * адрес, он вводит его в браузере на любом устройстве, а мы тем временем
 * спрашиваем GitHub, не подтвердили ли уже.
 *
 * ## Три вещи, на которых тут легко ошибиться
 *
 * * **`Accept: application/json` обязателен.** Без него оба эти адреса отвечают
 *   не JSON-ом, а строкой вида `a=1&b=2`, и разбор молча развалится;
 * * **`authorization_pending` — это не ошибка**, а обычный ответ «ещё не
 *   ввели». Ошибками тут являются только `expired_token`, `access_denied` и
 *   `unsupported_grant_type`;
 * * **`slow_down` обязан замедлять.** GitHub присылает его, когда мы спрашиваем
 *   чаще, чем он разрешил, и вместе с ним новый `interval`. Проигнорировать
 *   его — получить отказ вместо токена.
 *
 * ## Чего здесь нет
 *
 * Своего таймера на всё про всё: ограничение ставит сам GitHub (`expires_in`,
 * обычно пятнадцать минут), и дублировать его своим числом значило бы завести
 * двойник, который однажды разойдётся.
 */
object GithubAuth {

    /**
     * Публичный идентификатор приложения на GitHub. **Не секрет** — он виден
     * каждому, кто открывает страницу входа, и в открытом репозитории ему
     * ничто не угрожает.
     *
     * Пусто — вход не предлагается вовсе, и это штатное состояние: в сборке из
     * исходников своего приложения GitHub нет, а жалобы всё равно работают
     * общим токеном.
     */
    val CLIENT_ID: String get() = BuildConfig.GITHUB_CLIENT_ID

    /** Есть ли кому входить. */
    val possible: Boolean get() = CLIENT_ID.isNotBlank()

    /** Куда человек идёт вводить код. Короткий нарочно — его диктуют вслух. */
    const val VERIFY_URL = "https://github.com/login/device"

    /**
     * Права, которые просим у OAuth App.
     *
     * `public_repo` — минимальное, при котором можно завести issue в чужом
     * публичном репозитории. Просит больше, чем нам нужно (запись во все
     * публичные репозитории человека), и это цена выбора OAuth App.
     *
     * **GitHub App этот параметр не использует вовсе** — там права заданы у
     * самого приложения (`Issues: write`), и строка просто не влияет ни на что.
     * Поэтому она одна на оба случая.
     */
    private const val SCOPE = "public_repo"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Пара кодов: короткий человеку, длинный нам. */
    data class Code(
        val userCode: String,
        val deviceCode: String,
        val intervalSeconds: Int,
        val expiresInSeconds: Int
    )

    /** Что вышло из ожидания. */
    sealed class Outcome {
        data class Ok(val token: String, val refresh: String, val user: String) : Outcome()
        data class Failed(val message: String) : Outcome()
    }

    /** Попросить коды. */
    suspend fun start(): Result<Code> = withContext(Dispatchers.IO) {
        runCatching {
            val body = FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("scope", SCOPE)
                .build()
            val request = Request.Builder()
                .url("https://github.com/login/device/code")
                .addHeader("Accept", "application/json")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("GitHub ${response.code}")
                val json = JSONObject(text)
                json.optString("error").takeIf { it.isNotBlank() }?.let { error(it) }
                Code(
                    userCode = json.getString("user_code"),
                    deviceCode = json.getString("device_code"),
                    // Значения по умолчанию из документации — на случай, если
                    // поля не придут: лучше спросить реже, чем получить отказ.
                    intervalSeconds = json.optInt("interval", 5),
                    expiresInSeconds = json.optInt("expires_in", 900)
                )
            }
        }
    }

    /**
     * Ждать, пока человек подтвердит вход. Возвращается либо с токеном, либо с
     * объяснением; сама решает, когда перестать.
     */
    suspend fun await(code: Code): Outcome = withContext(Dispatchers.IO) {
        var interval = code.intervalSeconds
        var waited = 0
        while (waited < code.expiresInSeconds) {
            delay(interval * 1000L)
            waited += interval
            val answer = poll(code.deviceCode) ?: continue
            val error = answer.optString("error")
            when {
                error.isBlank() -> {
                    val token = answer.optString("access_token")
                    if (token.isBlank()) return@withContext Outcome.Failed("GitHub не дал токен")
                    val who = whoami(token)
                        ?: return@withContext Outcome.Failed("токен есть, а имя не читается")
                    return@withContext Outcome.Ok(
                        token = token,
                        refresh = answer.optString("refresh_token"),
                        user = who
                    )
                }
                // Обычное «ещё не ввели» — ждём дальше.
                error == "authorization_pending" -> Unit
                // Спросили слишком часто: GitHub присылает новый интервал.
                error == "slow_down" -> interval = answer.optInt("interval", interval + 5)
                error == "expired_token" ->
                    return@withContext Outcome.Failed("код устарел, начни заново")
                error == "access_denied" ->
                    return@withContext Outcome.Failed("вход отклонён")
                else -> return@withContext Outcome.Failed(error)
            }
        }
        Outcome.Failed("код устарел, начни заново")
    }

    /**
     * Обновить истёкший токен.
     *
     * Нужно только у GitHub App с включённым сроком жизни: там пользовательский
     * токен живёт восемь часов. У OAuth App токен не истекает вовсе, и
     * `refresh_token` не приходит — тогда здесь просто нечего обновлять.
     */
    suspend fun refresh(token: String): Outcome = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext Outcome.Failed("обновлять нечем")
        runCatching {
            val body = FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .add("grant_type", "refresh_token")
                .add("refresh_token", token)
                .build()
            val request = Request.Builder()
                .url("https://github.com/login/oauth/access_token")
                .addHeader("Accept", "application/json")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                val json = JSONObject(response.body?.string().orEmpty())
                val fresh = json.optString("access_token")
                if (fresh.isBlank()) {
                    Outcome.Failed(json.optString("error").ifBlank { "обновить не вышло" })
                } else {
                    val who = whoami(fresh) ?: ""
                    Outcome.Ok(fresh, json.optString("refresh_token"), who)
                }
            }
        }.getOrElse { Outcome.Failed(it.message ?: "нет связи с GitHub") }
    }

    private fun poll(deviceCode: String): JSONObject? = runCatching {
        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("device_code", deviceCode)
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .build()
        val request = Request.Builder()
            .url("https://github.com/login/oauth/access_token")
            .addHeader("Accept", "application/json")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            JSONObject(response.body?.string().orEmpty())
        }
    }.getOrNull()

    /** Как зовут вошедшего. Нужно настройкам: показывать надо имя, а не «вы вошли». */
    private fun whoami(token: String): String? = runCatching {
        val request = Request.Builder()
            .url("https://api.github.com/user")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("X-GitHub-Api-Version", "2022-11-28")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@runCatching null
            JSONObject(response.body?.string().orEmpty())
                .optString("login")
                .takeIf { it.isNotBlank() }
        }
    }.getOrNull()
}
