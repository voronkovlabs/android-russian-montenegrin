package com.crnogorski.trener.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Ключ Anthropic: единственный секрет, который живёт на телефоне, а не в APK.
 *
 * Заведено 23.09.2026, когда владелец решил открыть репозиторий. До этого ключ
 * подставлялся в `BuildConfig` из `local.properties` — и приватность
 * репозитория была единственным, что его держало: APK лежит в релизах
 * вложением, а оба ключа достаются из него распаковкой за минуту.
 *
 * ## Почему ключ, а не шифрование
 *
 * Первым замыслом было зашифровать приватное паролем, который вводят в
 * настройках. Шифровать оказалось нечего: `local.properties` не коммитился ни
 * разу, и ни `sk-ant-`, ни `github_pat_` не встречаются ни в одном коммите за
 * всю историю. Утечка была ровно одна — сам APK.
 *
 * А для неё шифрование не окупается никогда, и довод простой: **посторонний
 * обязан принести свой ключ в любом случае**, потому что чужой ключ — это чужие
 * деньги. Раз своим ключом посторонний пользоваться не должен, то и общая
 * сборка «и себе, и всем» не нужна, и пароль защищал бы то, чем всё равно никто
 * не должен пользоваться.
 *
 * ## Токенов GitHub два, и это не путаница
 *
 * **Общий** остался в `BuildConfig` намеренно открытым: цель владельца —
 * «сохранить возможность делать жалобы кому угодно», а без токена в сборке
 * посторонний написать не сможет вовсе. Чем за это плачено, перечислено в
 * CLAUDE.md; отзывается он одним нажатием.
 *
 * **Свой** появляется здесь, когда человек вошёл через GitHub
 * (`net/GithubAuth.kt`, device flow): тогда его жалобы заводятся от его имени,
 * а не от имени владельца с именной меткой.
 *
 * Вход **необязателен и с фоллбэком**, и это снимает ровно то возражение,
 * из-за которого авторизацию сперва отложили: сама по себе она требует
 * аккаунта GitHub, то есть «кто угодно» превратилось бы в «у кого есть
 * аккаунт». Не вошёл — работает общий токен, как и раньше; вошёл — свой.
 *
 * Сюда же кладётся **имя** (`githubUser`): в настройках надо показать не
 * «вы вошли», а **кем** — аккаунтов у людей больше одного.
 *
 * ## Где лежит
 *
 * В обычных `SharedPreferences` приложения — тот же файл `crnogorski`, в
 * котором живут `Pace`, `News`, `Replies` и настройки. Шифровать его нечем и
 * незачем: каталог приложения читает только само приложение, а от человека с
 * рутом не спасёт и `EncryptedSharedPreferences` (мастер-ключ лежит там же, на
 * том же устройстве).
 *
 * **В Auto Backup ключ не попадает**, и это надо помнить при правке
 * `res/xml/data_extraction_rules.xml`: там включён только `domain="database"`.
 * Добавит кто-нибудь однажды `sharedpref` ради настроек — и ключ молча уедет в
 * Google Drive вместе с копией прогресса.
 *
 * ## Состояние, а не геттер
 *
 * Ключ читает не только проверка переводов, но и замок на входе
 * (`ui/KeyGate.kt`), который обязан пропасть сразу после ввода. Поэтому здесь
 * `StateFlow`, а не чтение настроек по требованию: иначе экран не узнал бы, что
 * ключ появился, и остался бы стоять поверх приложения.
 */
object Secrets {

    private const val PREFS = "crnogorski"
    private const val KEY = "anthropic_key"
    private const val GH_TOKEN = "github_token"
    private const val GH_REFRESH = "github_refresh"
    private const val GH_USER = "github_user"

    /**
     * По чему узнаётся ключ.
     *
     * Проверка нарочно тупая — по виду, а не запросом в сеть. Настоящая
     * проверка стоит денег владельца и не проходит в метро, а замок на входе
     * должен закрываться и без интернета. Ошибиться она даёт только в одну
     * сторону: пропустит похожую на ключ ерунду, о которой человек узнает на
     * первом же свободном переводе.
     */
    private const val PREFIX = "sk-ant-"

    private val _key = MutableStateFlow("")

    /** Нынешний ключ; пустая строка значит «не задан». */
    val key: StateFlow<String> = _key

    private val _ghUser = MutableStateFlow("")

    /** Под каким именем вошли через GitHub; пусто — не входили. */
    val githubUser: StateFlow<String> = _ghUser

    private var ghToken = ""
    private var ghRefresh = ""

    /**
     * Контекст приложения — нужен там, где вход приходится забывать в ответ на
     * отказ GitHub, а под рукой одна сетевая обёртка (`net/GithubIssues.kt`).
     *
     * Именно `applicationContext`, а не тот, что передали: сюда доходит
     * Activity, и держать ссылку на неё в объекте, живущем весь процесс,
     * значит держать мёртвый экран в памяти до конца работы приложения.
     */
    private var app: Context? = null

    /**
     * Поднять с диска. Зовётся один раз при создании `MainActivity`, до
     * первого кадра: замок решает, показываться ли, по уже прочитанному
     * значению, а не по пустоте, которая потом окажется ключом.
     */
    fun load(context: Context) {
        app = context.applicationContext
        val p = prefs(context)
        _key.value = p.getString(KEY, "").orEmpty()
        ghToken = p.getString(GH_TOKEN, "").orEmpty()
        ghRefresh = p.getString(GH_REFRESH, "").orEmpty()
        _ghUser.value = p.getString(GH_USER, "").orEmpty()
    }

    /** Свой токен GitHub; пусто — жалобы пойдут общим. */
    fun githubToken(): String = ghToken

    /** Чем обновлять свой токен, если он окажется временным. */
    fun githubRefresh(): String = ghRefresh

    fun saveGithub(context: Context, token: String, refresh: String, user: String) {
        ghToken = token
        ghRefresh = refresh
        _ghUser.value = user
        prefs(context).edit()
            .putString(GH_TOKEN, token)
            .putString(GH_REFRESH, refresh)
            .putString(GH_USER, user)
            .apply()
    }

    /**
     * Забыть вход — по кнопке «Выйти» и **сам, когда токен перестал приниматься**.
     *
     * Второе важнее первого: устаревший токен иначе ронял бы каждую жалобу, а
     * так она уезжает общим токеном, и в настройках видно, что вход слетел.
     * На стороне GitHub разрешение при этом остаётся — отзывают его там же, в
     * списке приложений аккаунта; мы лишь перестаём им пользоваться.
     */
    fun forgetGithub(context: Context) {
        saveGithub(context, "", "", "")
    }

    /** То же, но из места, где Context взять неоткуда. */
    fun forgetGithub() {
        app?.let { forgetGithub(it) }
    }

    /** Сохранить обновлённый токен оттуда же. */
    fun saveGithub(token: String, refresh: String, user: String) {
        app?.let { saveGithub(it, token, refresh, user) }
    }

    /**
     * Ключ для тех, кому нужна строка, а не состояние, — например `HaikuChecker`.
     *
     * Называется не `key()`, хотя просилось: рядом стоит свойство [key], и
     * ссылка `Secrets::key` стала бы двусмысленной — свойство или функция.
     */
    fun current(): String = _key.value

    fun save(context: Context, value: String) {
        val clean = value.trim()
        prefs(context).edit().putString(KEY, clean).apply()
        _key.value = clean
    }

    /** Похоже ли это вообще на ключ. См. [PREFIX]. */
    fun looksReal(value: String): Boolean {
        val clean = value.trim()
        return clean.startsWith(PREFIX) && clean.length > PREFIX.length + 10
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
