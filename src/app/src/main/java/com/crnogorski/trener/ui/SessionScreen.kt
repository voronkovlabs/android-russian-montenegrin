package com.crnogorski.trener.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.asImageBitmap
import com.crnogorski.trener.data.WordPicture
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.crnogorski.trener.R
import com.crnogorski.trener.data.ComplaintReason
import com.crnogorski.trener.data.Exercise
import com.crnogorski.trener.data.LocalCheck
import com.crnogorski.trener.data.MatchPair
import com.crnogorski.trener.data.Touch
import com.crnogorski.trener.data.ParadigmCell
import com.crnogorski.trener.speech.AnswerLanguage
import com.crnogorski.trener.speech.Listener
import com.crnogorski.trener.speech.Speaker
import kotlinx.coroutines.delay

/**
 * Сколько длится поворот карточки-перевёртыша.
 *
 * Тем же числом отмеряется пауза перед тем, как оборот заговорит: голос
 * должен застать карточку уже повёрнутой, иначе слово звучит над вопросом.
 */
private const val FLIP_MS = 420

/**
 * Пауза после верного ответа перед следующей карточкой — просьба владельца.
 * Была секунда (4.18), и её не хватало: слово звучит после поворота и
 * договаривается почти к самому переходу, посмотреть на оборот некогда. С 4.22
 * две секунды.
 */
private const val AUTO_NEXT_MS = 2000L

@Composable
fun SessionScreen(
    state: SessionState,
    speaker: Speaker,
    onSubmit: (String) -> Unit,
    onSkip: () -> Unit,
    /** Отложить слово надолго — кнопка есть только у словарных карточек. */
    onSnooze: () -> Unit,
    /** 🧠 «уже знаю» — только у перевёртыша. */
    onKnown: () -> Unit,
    /** Экран пар отвечает не строкой, а списком слов, где ошиблись. */
    onMatch: (Set<String>) -> Unit,
    onParadigm: (List<String>) -> Unit,
    /** Открыта подсказка к условию выбора — см. `Exercise.Choice.hint`. */
    onPeek: () -> Unit,
    onRate: (Int) -> Unit,
    onNext: () -> Unit,
    onRetryBlock: () -> Unit,
    onComplain: (ComplaintReason, String) -> Unit,
    onNote: (String) -> Unit,
    onIdea: (String, String) -> Unit,
    onExit: () -> Unit
) {
    // Системная «Назад» должна возвращать к списку уроков, а не закрывать приложение.
    // Выход из приложения остаётся только на главном экране, где BackHandler-а нет.
    BackHandler { onExit() }

    if (state.finished) {
        FinishedView(state, onExit)
        return
    }

    val current = state.current
    if (current is Exercise.Card) {
        FlipSession(
            state, current, speaker,
            onSubmit = onSubmit,
            onSkip = onSkip,
            onSnooze = onSnooze,
            onKnown = onKnown,
            onNext = onNext,
            onNote = onNote,
            onIdea = onIdea,
            onExit = onExit
        )
        return
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        SessionHeader(state, onNote, onIdea, onExit)

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(20.dp))

            if (state.index == 0 && state.note.isNotBlank() && state.phase == Phase.Input) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Surface1)
                        .padding(14.dp)
                ) {
                    Text(
                        if (state.strict) "ТЕСТ РАЗДЕЛА" else "ГРАММАТИКА УРОКА",
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(state.note, style = MaterialTheme.typography.bodyMedium, color = Paper)
                }
                Spacer(Modifier.height(24.dp))
            }

            // Тело задания рисуется ОДИН раз, до развилки по фазам, и это не
            // косметика. Раньше каждая ветка `when` звала его сама, а ветки —
            // разные места композиции: при переходе «ввод → результат» старое
            // тело выбрасывалось вместе со всем своим состоянием. Собранная
            // фраза рассыпалась обратно в кучу, набранный текст исчезал, а
            // сложенные пары показывались несложенными. Теперь место одно, и
            // ответ остаётся на экране рядом с вердиктом.
            val phase = state.phase
            val live = phase is Phase.Input || phase is Phase.Retry
            ExerciseBody(
                state, speaker,
                enabled = live,
                onSubmit = onSubmit,
                onSkip = onSkip,
                onMatch = onMatch,
                onParadigm = onParadigm,
                onPeek = onPeek
            )

            // Оценка — под телом задания, рядом с «Отложить», и по тому же
            // доводу: одно место на все виды заданий.
            //
            // Показывается **всегда**, а не только пока задание живое: мнение
            // о задании чаще складывается после вердикта, чем до него.
            RateRow(key = state.current.id, onRate = onRate)

            // «Отложить» — одной кнопкой на все виды заданий, под телом, а не
            // внутри каждого блока ответа. Блоков семь, и своя кнопка в каждом
            // означала бы семь мест, где её забудут поправить, и семь разных
            // отступов на экране.
            //
            // Экран пар её не получает: слов на нём пять, и какое имелось в
            // виду, нажатие не говорит. Тот же довод, по которому жалоба с
            // экрана пар не откатывает карточку.
            // В строгом тесте откладывать нечего: это проверка, а не занятие,
            // и подмена задания сломала бы счёт ошибок.
            if (live && state.current !is Exercise.Match && !state.strict) {
                TextButton(onClick = onSnooze) {
                    Text("Отложить на потом", color = Muted)
                }
            }

            when (phase) {
                is Phase.Blocked -> {
                    Spacer(Modifier.height(20.dp))
                    BlockedView(phase.message, onRetryBlock, onExit)
                }
                is Phase.Checking -> {
                    Spacer(Modifier.height(24.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp),
                            strokeWidth = 2.dp,
                            color = Accent
                        )
                        Spacer(Modifier.height(0.dp))
                        Text(
                            "  Claude проверяет ответ",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Muted
                        )
                    }
                }
                is Phase.Result -> {
                    Spacer(Modifier.height(20.dp))
                    ResultView(phase, state.current, speaker, state.glossary.me)
                    AnswerTail(state, onNext, onComplain)
                }
                is Phase.Skipped -> {
                    Spacer(Modifier.height(20.dp))
                    SkippedView(phase)
                    AnswerTail(state, onNext, onComplain)
                }
                // Задание остаётся живым: кнопка записи работает, эталон не
                // показан. Вердикта ещё нет, поэтому нет и хвоста с жалобой —
                // жаловаться пока не на что, а флажок в шапке никуда не делся.
                is Phase.Retry -> {
                    Spacer(Modifier.height(20.dp))
                    RetryView(phase)
                }
                Phase.Input -> Unit
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SessionHeader(
    state: SessionState,
    onNote: (String) -> Unit,
    onIdea: (String, String) -> Unit,
    onExit: () -> Unit
) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onExit) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Выйти из урока",
                    tint = Muted
                )
            }
            Spacer(Modifier.weight(1f))
            // У бесконечного захода «из скольких» нет: показываем, сколько
            // пройдено, а полосу оставляем пустой — её высота держит место,
            // чтобы шапка не прыгала.
            Text(
                if (state.endless) "${state.index + 1}" else "${state.index + 1} / ${state.items.size}",
                style = MaterialTheme.typography.labelSmall,
                color = Muted
            )
            // Пожаловаться можно и посреди задания: диалог поверх, урок не сбивается.
            IdeaButton(onSave = onIdea)
            ComplaintButton(onSave = onNote)
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { if (state.endless) 0f else state.progress },
            modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
            color = Accent,
            trackColor = Surface2
        )
    }
}

@Composable
private fun ExerciseBody(
    state: SessionState,
    speaker: Speaker,
    enabled: Boolean,
    onSubmit: (String) -> Unit,
    onSkip: () -> Unit,
    onMatch: (Set<String>) -> Unit,
    onParadigm: (List<String>) -> Unit,
    onPeek: () -> Unit
) {
    // Что движок расслышал в последний заход. По нему подсвечиваются слова
    // прямо в задании: строка «Услышано:» говорит, ЧТО он разобрал, но какие
    // именно слова фразы совпали, приходилось сверять глазами.
    //
    // Двух источников не избежать: до последней попытки вердикта нет и
    // расслышанное лежит в Retry, а на последней оно становится ответом, на
    // который вынесен вердикт. Для остальных заданий это набранный текст, но
    // получают его только речевые блоки.
    val heard = when (val phase = state.phase) {
        is Phase.Retry -> phase.heard
        is Phase.Result -> phase.answer
        else -> ""
    }
    // Нажатое слово произносится — как в историях: жирная буква говорит, где
    // ударение, но не говорит, как оно звучит (заметка 133).
    //
    // Рук две, потому что условие в уроке бывает русским, а голос у нас
    // sr-RS и русскую строку прочтёт кашей. Тогда произносится черногорское
    // слово из подсказки (см. [sayable]), и нажимаются только слова, у
    // которых подсказка есть: подчёркнутое слово, молчащее в ответ,
    // читалось бы как поломка.
    val sayWord: (String) -> Unit = { speaker.speak(it) }
    val sayHint: (String) -> Unit = { word ->
        sayable(state.glossary.ru[word.lowercase()])?.let { speaker.speak(it) }
    }

    when (val ex = state.current) {
        is Exercise.TranslateToTarget -> TextAnswer(
            key = ex.id,
            label = "Переведи на черногорский",
            prompt = ex.prompt,
            hint = ex.hint,
            enabled = enabled,
            language = AnswerLanguage.Target,
            // Задание по-русски: подсказка ведёт в черногорский.
            promptGloss = state.glossary.ru,
            // Условие русское: по нажатию звучит черногорское слово из
            // подсказки, и нажимается только то, у чего подсказка есть.
            onPromptWord = sayHint,
            promptTapUnknown = false,
            onSubmit = onSubmit
        )

        is Exercise.TranslateToNative -> TextAnswer(
            key = ex.id,
            label = "Переведи на русский",
            prompt = ex.prompt,
            hint = ex.hint,
            enabled = enabled,
            language = AnswerLanguage.Native,
            promptGloss = state.glossary.me,
            onPromptWord = sayWord,
            speakable = ex.prompt,
            speaker = speaker,
            onSubmit = onSubmit
        )

        is Exercise.Form -> TextAnswer(
            key = ex.id,
            label = "Поставь слово в нужную форму",
            prompt = ex.prompt,
            hint = "",
            enabled = enabled,
            language = AnswerLanguage.Target,
            promptGloss = state.glossary.me,
            onPromptWord = sayWord,
            onSubmit = onSubmit
        )

        // Подпись приходит с заданием: словарная карточка спрашивает то
        // значение, то падеж, то особую форму — механика одна, вопрос разный.
        is Exercise.Word -> TextAnswer(
            key = ex.id,
            label = ex.label,
            prompt = ex.prompt,
            icon = ex.icon,
            picture = ex.picture,
            hint = "",
            enabled = enabled,
            // Язык ответа, а не задания: у обратного перевода отвечают
            // по-русски, и распознавание с клавиатурой должны быть русскими.
            language = if (ex.native) AnswerLanguage.Native else AnswerLanguage.Target,
            promptGloss = if (ex.native) emptyMap() else state.glossary.me,
            // Обратный перевод показывает одно черногорское слово — оно и
            // нажимается целиком: слышно, как звучит, а значения звук не
            // выдаёт. В остальных карточках условие русское или это рамка
            // с пропуском, и нажимаются только знакомые словарю
            // черногорские слова: русских ключей в .me не бывает вовсе.
            onPromptWord = sayWord,
            promptTapUnknown = ex.native,
            // Обратный перевод показывает черногорское слово — там ударение и
            // нужно. В остальных карточках условие русское или это рамка с
            // пропуском, то есть фраза: во фразе ударение уезжает на предлог.
            stressPrompt = ex.native,
            // Слово проще сказать, чем набрать: микрофон включается сам, а
            // клавиатура остаётся на месте — набрать руками можно всегда.
            autoListen = true,
            onSubmit = onSubmit
        )

        // Перевёртышу нужна фаза, а не только `enabled`: карточка поворачивается
        // оборотом ровно тогда, когда вердикт вынесен. `enabled` для этого не
        // годится — он гаснет и на `Checking`, которого у речи не бывает, и
        // ничего не говорит о пропуске.
        // Перевёртыш рисуется своим экраном целиком (FlipSession): в общей
        // колонке он дёргался от плашек, появляющихся по фазе.
        is Exercise.Card -> Unit

        is Exercise.Match -> MatchAnswer(ex, speaker, enabled, onMatch)

        is Exercise.Table -> ParadigmAnswer(ex, speaker, enabled, onParadigm)

        // Строгий тест раздела идёт без подсказок: подчёркивания нет вовсе.
        is Exercise.Choice -> ChoiceAnswer(
            if (state.strict) ex.copy(hint = "") else ex, speaker, enabled, onSubmit, onPeek
        )

        is Exercise.WordBank ->
            WordBankAnswer(ex, state.glossary.ru, sayHint, enabled, onSubmit)

        is Exercise.Listening -> ListeningAnswer(ex, speaker, enabled, onSubmit)

        is Exercise.Speaking -> SpokenAnswer(
            key = ex.id,
            label = "Произнеси вслух",
            text = ex.phrase,
            translation = ex.translation,
            byEar = false,
            heard = heard,
            gloss = state.glossary.me,
            translationGloss = state.glossary.ru,
            speaker = speaker,
            enabled = enabled,
            onSubmit = onSubmit,
            onSkip = onSkip
        )

        is Exercise.Repeat -> SpokenAnswer(
            key = ex.id,
            label = "Повтори на слух",
            text = ex.phrase,
            translation = ex.translation,
            byEar = true,
            heard = heard,
            gloss = state.glossary.me,
            translationGloss = state.glossary.ru,
            speaker = speaker,
            enabled = enabled,
            onSubmit = onSubmit,
            onSkip = onSkip
        )

        is Exercise.Reading -> ReadingAnswer(
            ex = ex,
            speaker = speaker,
            gloss = state.glossary.me,
            translationGloss = state.glossary.ru,
            enabled = enabled,
            onSubmit = onSubmit,
            onSkip = onSkip
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Accent)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun Prompt(
    text: String,
    gloss: Map<String, String> = emptyMap(),
    /** Условие — одно черногорское слово: показать в нём ударение. */
    stress: Boolean = false,
    /** Номера слов, которые движок расслышал, — их показываем зелёным. */
    green: Set<Int> = emptySet(),
    /** Что делать с нажатым словом: см. [ExerciseBody]. */
    onWord: ((String) -> Unit)? = null,
    /** Нажимать ли слово без подсказки: см. [GlossedText]. */
    tapUnknown: Boolean = true
) {
    // Ударение ставит сам GlossedText — и в отдельном слове, и во фразе. Флаг
    // остался ради обратного перевода: там условие это черногорское слово, и
    // таблица подсказок к нему пустая. Но как только у нажатия появляется
    // дело, рисовать надо всё равно GlossedText: он и подчёркивает, и
    // ударение ставит, и звук по нажатию у него один на всё приложение.
    if (stress && onWord == null) {
        Text(stressed(text), style = MaterialTheme.typography.headlineSmall, color = Paper)
    } else {
        GlossedText(
            text, gloss, MaterialTheme.typography.headlineSmall, Paper,
            onWord = onWord,
            green = green,
            tapUnknown = tapUnknown
        )
    }
}

/**
 * Что произнести, когда нажали **русское** слово условия.
 *
 * Произносить само нажатое нельзя: голос у нас `sr-RS`, и русская строка
 * выйдет кашей. Произносится черногорское слово из подсказки, а пояснение к
 * форме отбрасывается: подсказки выглядят как «nema (nemam — у меня нет)» и
 * «imam — у меня есть; imate — у вас есть», и прочитанные целиком они дали бы
 * вслух русский хвост.
 *
 * Берётся голова строки до первой скобки, тире, запятой или точки с запятой,
 * и из неё только латиница. Проверено на всех 163 русских подсказках: строк,
 * из которых нечего произнести, не осталось ни одной.
 */
private fun sayable(hint: String?): String? {
    if (hint.isNullOrBlank()) return null
    val head = hint.split('(', ';', ',', '\u2014', '-')[0]
    // Латиница — это буквы ниже кириллического блока: так отсеиваются и
    // русские слова подсказки, и знаки, и цифры.
    val latin = head.split(' ').filter { word ->
        word.isNotBlank() && word.all { it.isLetter() && it.code < 0x400 }
    }
    return latin.joinToString(" ").ifBlank { null }
}

@Composable
private fun TextAnswer(
    key: String,
    label: String,
    prompt: String,
    /** Картинка к слову: эмодзи или пусто. Бывает только у словарных карточек. */
    icon: String = "",
    /** Нарисованная картинка вместо эмодзи (4.41) — имя в `assets/pictures/`. */
    picture: String = "",
    hint: String,
    enabled: Boolean,
    language: AnswerLanguage,
    promptGloss: Map<String, String> = emptyMap(),
    /** Условие — черногорское слово, а не фраза: см. [Prompt]. */
    stressPrompt: Boolean = false,
    speakable: String? = null,
    speaker: Speaker? = null,
    /** Начинать слушать сразу, не дожидаясь нажатия на микрофон. */
    autoListen: Boolean = false,
    /** Что делать с нажатым словом условия: см. [ExerciseBody]. */
    onPromptWord: ((String) -> Unit)? = null,
    /** Нажимать ли слово условия без подсказки: см. [GlossedText]. */
    promptTapUnknown: Boolean = true,
    onSubmit: (String) -> Unit
) {
    var value by remember(key) { mutableStateOf("") }
    var status by remember(key) { mutableStateOf("") }
    // След последней диктовки — см. Dictation.
    var dictation by remember(key) { mutableStateOf<Dictation?>(null) }

    // Черногорскую фразу озвучиваем сразу, как только задание появилось: слышать
    // её нужно раньше, чем разбирать. Только в Input — иначе фраза повторилась бы
    // при переходе к результату, когда тот же блок перерисовывается неактивным.
    if (speakable != null && speaker != null) {
        LaunchedEffect(key) { if (enabled) speaker.speak(speakable) }
    }

    Label(label)
    // Картинка стоит над условием и крупно: она тут вместо картинки в бумажном
    // словаре — на неё смотрят до того, как прочтут слово, а не после.
    // Нарисованная картинка стоит там же, где эмодзи, и крупнее его: 96 dp —
    // размер, на котором владелец смотрел её в пробе 4.35. Нет своей —
    // остаётся эмодзи, как раньше.
    if (picture.isNotBlank()) {
        WordPictureImage(picture, 96.dp)
        Spacer(Modifier.height(4.dp))
    } else if (icon.isNotBlank()) {
        Text(icon, fontSize = 40.sp, lineHeight = 46.sp)
        Spacer(Modifier.height(4.dp))
    }
    Prompt(
        prompt,
        promptGloss,
        stress = stressPrompt,
        onWord = onPromptWord,
        tapUnknown = promptTapUnknown
    )
    if (speakable != null && speaker != null) {
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallAction("Прослушать") { speaker.speak(speakable) }
            SmallAction("Медленнее") { speaker.speak(speakable, slow = true) }
        }
    }
    if (hint.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = Muted)
    }
    Spacer(Modifier.height(20.dp))

    OutlinedTextField(
        value = value,
        onValueChange = { value = it; Touch.note() },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Ответ", color = Muted) },
        textStyle = MaterialTheme.typography.bodyLarge,
        leadingIcon = { LanguageFlag(language) },
        trailingIcon = {
            if (enabled) {
                MicButton(
                    language = language.speech,
                    enabled = true,
                    autoKey = if (autoListen) key else null,
                    onStatus = { status = it },
                    onText = { heard ->
                        // Диктовка подряд заменяет предыдущую, набранное руками
                        // не трогает. Что из этого сейчас в поле, говорит
                        // сравнение с Dictation.after.
                        val last = dictation
                        val base = if (last != null && value == last.after) last.before else value
                        val next = appendSpoken(base, heard)
                        dictation = Dictation(base, next)
                        value = next
                    }
                )
            }
        },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            imeAction = ImeAction.Done,
            // Подсказка клавиатуре, на каком языке будет ответ: иначе раскладку
            // приходится переключать руками на каждом задании.
            hintLocales = LocaleList(language.keyboard)
        ),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Accent,
            unfocusedBorderColor = Surface2,
            focusedTextColor = Paper,
            unfocusedTextColor = Paper,
            disabledTextColor = Muted,
            cursorColor = Accent
        )
    )

    if (status.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        Text(status, style = MaterialTheme.typography.bodyMedium, color = Muted)
    }

    if (enabled) {
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Проверить", enabled = value.isNotBlank()) { onSubmit(value) }
    }

}

/**
 * Флаг языка, на котором ждут ответ.
 *
 * Слева в поле, симметрично микрофону справа: смотрят туда перед тем, как
 * начать печатать. Без него направление перевода приходится держать в голове —
 * особенно в словарных карточках, где задание меняет сторону каждые несколько
 * ответов.
 *
 * Значки свои, а не эмодзи: на части прошивок черногорского флага в шрифте нет
 * вовсе, и вместо него показались бы две буквы «ME». Свои весят 1,4 КБ на оба и
 * выглядят одинаково везде. Герб на такой высоте — золотое пятно, и это
 * нормально: флаг узнают по сочетанию красного с золотой каймой.
 */
@Composable
private fun LanguageFlag(language: AnswerLanguage) {
    val native = language == AnswerLanguage.Native
    Image(
        painter = painterResource(if (native) R.drawable.flag_ru else R.drawable.flag_me),
        contentDescription = if (native) "Ответ по-русски" else "Ответ по-черногорски",
        modifier = Modifier.size(width = 24.dp, height = 17.dp)
    )
}

/**
 * След последней диктовки: каким поле было до неё ([before]) и каким стало
 * после ([after]).
 *
 * Нужен, чтобы **вторая диктовка подряд заменяла первую**, а не дописывалась к
 * ней. Само по себе дописывание правильно — набранное руками затирать нельзя,
 * — но между двумя заходами подряд ничего не набирают: второй заход значит «не
 * так расслышалось, скажу ещё раз», и склейка двух попыток давала строку, из
 * которой ответ приходилось выковыривать руками.
 *
 * Одно от другого отличается сравнением, а не флагом: если поле с прошлой
 * диктовки не трогали, оно равно [after], и заменить надо ровно
 * продиктованное, вернувшись к [before]. Любая правка руками сравнение рушит —
 * и тогда снова дописываем. Хранить сам распознанный кусок для этого мало:
 * заменять надо то место, где он стоит, а стоит он в конце того, что было.
 */
private data class Dictation(val before: String, val after: String)

/**
 * Продиктованное дописывается к набранному, а не затирает его.
 *
 * Первая буква поднимается в заглавную: движок распознавания отдаёт всё
 * строчными, и ответ выглядел неряшливо. На проверку это не влияет вовсе —
 * `matchesTyped` регистр не различает, — но читать своё же предложение
 * приятнее.
 *
 * Точку в конце **не ставим**. Вопрос это или утверждение, здесь неизвестно, а
 * подсмотреть знак у эталона значило бы подсказать: «?» в конце сразу говорит,
 * что ответ — вопрос. Пунктуация при сверке всё равно отбрасывается.
 */
private fun appendSpoken(current: String, heard: String): String {
    val said = heard.trim()
    val text = if (current.isBlank()) {
        said.replaceFirstChar { it.uppercase() }
    } else {
        current.trimEnd() + " " + said
    }
    return text
}

@Composable
private fun ChoiceAnswer(
    ex: Exercise.Choice,
    speaker: Speaker,
    enabled: Boolean,
    onSubmit: (String) -> Unit,
    onPeek: () -> Unit
) {
    var peeked by remember(ex.id) { mutableStateOf(false) }
    var revealed by remember(ex.id) { mutableStateOf(false) }
    // На слух текст закрыт, пока его не открыли кнопкой; после ответа он
    // открывается сам — иначе не с чем сверить услышанное.
    val showText = !ex.byEar || revealed || !enabled
    // Звучит только черногорское: голос у нас sr-RS, и русская строка вышла бы
    // кашей. Условие Катиных заданий бывает и русским («собака»).
    val sayPrompt = !hasCyrillic(ex.prompt)

    if (ex.byEar) {
        LaunchedEffect(ex.id) { if (enabled) speaker.speak(ex.prompt) }
    }

    Label(ex.label.ifBlank { if (ex.byEar) "Выбери, что прозвучало" else "Выбери вариант" })
    when {
        !showText -> Text(
            "Текст закрыт — слушай.",
            style = MaterialTheme.typography.headlineSmall,
            color = Muted
        )
        // Условие с подсказкой подчёркнуто целиком и нажимается целиком: это
        // одно новое слово или одна готовая фраза, делить её на слова незачем.
        ex.hint.isNotBlank() -> Text(
            stressedPhrase(ex.prompt),
            style = MaterialTheme.typography.headlineSmall.copy(
                textDecoration = TextDecoration.Underline
            ),
            color = Paper,
            modifier = Modifier.clickable {
                if (!peeked) {
                    peeked = true
                    if (enabled) onPeek()
                }
                if (sayPrompt) speaker.speak(ex.prompt)
            }
        )
        else -> Prompt(ex.prompt)
    }
    if (showText && ex.hint.isNotBlank() && (peeked || !enabled)) {
        Spacer(Modifier.height(8.dp))
        Text(ex.hint, style = MaterialTheme.typography.bodyLarge, color = Muted)
    }
    if (ex.byEar) {
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallAction("Прослушать") { speaker.speak(ex.prompt) }
            SmallAction("Медленнее") { speaker.speak(ex.prompt, slow = true) }
            if (!showText) SmallAction("Показать текст") { revealed = true }
        }
    }
    Spacer(Modifier.height(20.dp))

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ex.options.forEach { option ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface1)
                    .border(1.dp, Surface2, RoundedCornerShape(12.dp))
                    // Вариант проговаривается вслух: выбор глазами не даёт
                    // услышать, как выбранное звучит.
                    .clickable(enabled = enabled) {
                        // Русский вариант не зачитывается: голос sr-RS
                        // прочёл бы его кашей.
                        if (!hasCyrillic(option)) speaker.speak(option)
                        onSubmit(option)
                    }
                    .padding(16.dp)
            ) {
                Text(option, style = MaterialTheme.typography.bodyLarge, color = Paper)
            }
        }
    }
}

/** Есть ли в строке кириллица — то есть русский ли это текст. */
private fun hasCyrillic(text: String): Boolean = text.any { it in 'Ѐ'..'ӿ' }

/**
 * Пары слов: слева значения, справа черногорские слова.
 *
 * Задание на узнавание, и в этом его место в курсе: назвать слово с нуля
 * труднее, чем узнать его среди пяти, — поэтому пары идут первыми, знакомством
 * с тем, что через минуту спросят набором. До 1.38 первой встречей со словом
 * было сразу требование его напечатать.
 *
 * **Столбцы перемешиваются независимо**, иначе пара стояла бы напротив пары и
 * складывать было бы нечего. Порядок держится в `remember` на всё задание:
 * пересобирать его при перерисовке значило бы тасовать плашки под пальцем.
 *
 * **Ошибка не раскрывает пару.** Обе плашки краснеют, снимаются, и слово
 * приходится искать дальше — экран не закрыть, пока не сложено всё. Раскрывать
 * значило бы отбирать у человека ровно ту работу, ради которой задание есть,
 * а зачёт при этом честный: верным считается сложенное с первой попытки.
 *
 * Сложенная пара **остаётся на месте**, погашенной. Убирать её значило бы
 * перекладывать все остальные под пальцем; погашенная же плашка показывает,
 * сколько сделано, и не мешает искать.
 *
 * Черногорское слово звучит, когда пара сошлась, — но не когда его выбирают:
 * иначе экран озвучивал бы каждое касание при переборе, а слышать слово
 * полезно вместе с его значением, а не отдельно.
 */
@Composable
private fun MatchAnswer(
    ex: Exercise.Match,
    speaker: Speaker,
    enabled: Boolean,
    onDone: (Set<String>) -> Unit
) {
    val left = remember(ex.id) { ex.pairs.shuffled() }
    val right = remember(ex.id) { ex.pairs.shuffled() }

    // Выбранная плашка: сторона важна не меньше слова — сложить пару можно
    // только из разных столбцов.
    var picked by remember(ex.id) { mutableStateOf<MatchPair?>(null) }
    var pickedLeft by remember(ex.id) { mutableStateOf(false) }
    var solved by remember(ex.id) { mutableStateOf(setOf<String>()) }
    var wrong by remember(ex.id) { mutableStateOf(setOf<String>()) }
    // Ровно две плашки, которые сейчас краснеют, — по ключу «сторона + слово»:
    // ошиблись именно этими двумя, а не всеми плашками этих слов.
    var flash by remember(ex.id) { mutableStateOf(setOf<String>()) }

    fun key(pair: MatchPair, isLeft: Boolean) =
        (if (isLeft) "L:" else "R:") + pair.cardId

    LaunchedEffect(flash) {
        if (flash.isNotEmpty()) {
            delay(FLASH_MS)
            flash = emptySet()
        }
    }

    fun tap(pair: MatchPair, isLeft: Boolean) {
        // Нажатие во время красной подсветки не теряется, а гасит её и
        // засчитывается как обычное (Катя, жалоба 172). Раньше экран его молча
        // глотал: при быстрой игре первое нажатие после ошибки пропадало,
        // второе становилось первым, и следующая пара складывалась со сдвигом —
        // одна ошибка тянула за собой ещё.
        if (!enabled || pair.cardId in solved) return
        if (flash.isNotEmpty()) flash = emptySet()
        val chosen = picked
        if (chosen == null || pickedLeft == isLeft) {
            // Нажатие по уже выбранной плашке снимает выбор: передумать надо
            // уметь, не складывая заведомо неверную пару.
            picked = if (chosen?.cardId == pair.cardId && pickedLeft == isLeft) null else pair
            pickedLeft = isLeft
            return
        }
        picked = null
        if (chosen.cardId == pair.cardId) {
            speaker.speak(pair.me)
            val next = solved + pair.cardId
            solved = next
            if (next.size == ex.pairs.size) onDone(wrong)
        } else {
            wrong = wrong + chosen.cardId + pair.cardId
            flash = setOf(key(chosen, !isLeft), key(pair, isLeft))
        }
    }

    Label("Сложи пары")
    Spacer(Modifier.height(20.dp))

    // Сетка ровная (из main 4.34, владелец: «боксы во второй колонке должны
    // находиться напротив боксов в первой»): строка — две плашки, и высота у
    // них общая, по более высокой. Двумя независимыми столбцами длинное
    // русское толкование уходило в две строки и сдвигало вниз всё под собой,
    // а справа плашки оставались прежней высоты.
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        left.indices.forEach { row ->
            val l = left[row]
            val r = right[row]
            Row(
                Modifier.height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    MatchTile(
                        text = AnnotatedString(l.ru),
                        done = l.cardId in solved,
                        chosen = picked?.cardId == l.cardId && pickedLeft,
                        failed = key(l, true) in flash
                    ) { tap(l, true) }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    MatchTile(
                        text = stressed(r.me),
                        done = r.cardId in solved,
                        chosen = picked?.cardId == r.cardId && !pickedLeft,
                        failed = key(r, false) in flash
                    ) { tap(r, false) }
                }
            }
        }
    }
}

/**
 * Плашка экрана пар.
 *
 * Состояний четыре, и цвет у каждого свой, потому что читаются они издалека и
 * мгновенно: обычная, выбранная (акцент), не сошлась (красная), сложена
 * (погашенная). Погашенная остаётся кликабельной формально, но `tap` её
 * отсекает — убирать `clickable` значило бы дёргать разметку.
 */
@Composable
private fun MatchTile(
    text: AnnotatedString,
    done: Boolean,
    chosen: Boolean,
    failed: Boolean,
    onClick: () -> Unit
) {
    val border = when {
        failed -> Crimson
        chosen -> Accent
        done -> Jade.copy(alpha = 0.35f)
        else -> Surface2
    }
    val ink = when {
        failed -> Crimson
        done -> Muted
        else -> Paper
    }
    Box(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .heightIn(min = 64.dp)
            // Сложенная пара гаснет, но остаётся на месте. Убрать её нельзя:
            // остальные плашки перепрыгнули бы под пальцем, а гашение и так
            // отвечает на вопрос «что уже сделано» — по жалобе владельца.
            .alpha(if (done) 0.32f else 1f)
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1)
            .border(if (chosen || failed) 2.dp else 1.dp, border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = ink,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Сколько держать красную подсветку неверной пары.
 *
 * Достаточно, чтобы глаз успел прочитать обе плашки, и мало, чтобы не
 * превратиться в наказание паузой.
 */
private const val FLASH_MS = 700L

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordBankAnswer(
    ex: Exercise.WordBank,
    gloss: Map<String, String>,
    /** Условие русское: по нажатию звучит слово из подсказки. */
    onWord: (String) -> Unit,
    enabled: Boolean,
    onSubmit: (String) -> Unit
) {
    var picked by remember(ex.id) { mutableStateOf(listOf<String>()) }
    // Слова тасуются, а не идут как в файле: там верный ответ стоит первым по
    // порядку, и задание решалось нажатием слева направо. Тасуем один раз на
    // задание — иначе плашки прыгали бы под пальцем при каждой перерисовке, —
    // но при каждом новом появлении заново: порядок, выученный на повторении,
    // такое же решение по памяти.
    val bank = remember(ex.id) { ex.bank.shuffled() }
    val remaining = remember(picked, bank) {
        val counts = picked.groupingBy { it }.eachCount().toMutableMap()
        bank.filter { word ->
            val left = counts[word] ?: 0
            if (left > 0) { counts[word] = left - 1; false } else true
        }
    }

    Label("Собери фразу")
    Prompt(ex.prompt, gloss, onWord = onWord, tapUnknown = false)
    Spacer(Modifier.height(20.dp))

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1)
            .padding(14.dp)
    ) {
        if (picked.isEmpty()) {
            Text("Нажимай на слова ниже", color = Muted, style = MaterialTheme.typography.bodyMedium)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                picked.forEachIndexed { i, word ->
                    Chip(word, filled = true, enabled = enabled) {
                        picked = picked.toMutableList().also { it.removeAt(i) }
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        remaining.forEach { word ->
            Chip(word, filled = false, enabled = enabled) { picked = picked + word }
        }
    }

    if (enabled) {
        Spacer(Modifier.height(20.dp))
        PrimaryButton("Проверить", enabled = picked.isNotEmpty()) {
            onSubmit(picked.joinToString(" "))
        }
    }
}

@Composable
private fun ListeningAnswer(
    ex: Exercise.Listening,
    speaker: Speaker,
    enabled: Boolean,
    onSubmit: (String) -> Unit
) {
    var value by remember(ex.id) { mutableStateOf("") }

    // Фраза звучит сразу: задание в том, чтобы её записать, а не в том,
    // чтобы догадаться нажать кнопку. Кнопка остаётся — послушать ещё раз.
    LaunchedEffect(ex.id) { if (enabled) speaker.speak(ex.audioText) }

    Label("Запиши услышанное")
    Text(
        "Набери фразу так, как её произносят. Можно послушать ещё раз.",
        style = MaterialTheme.typography.bodyMedium,
        color = Muted
    )
    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SmallAction("Прослушать") { speaker.speak(ex.audioText) }
        SmallAction("Медленнее") { speaker.speak(ex.audioText, slow = true) }
    }

    if (speaker.voiceUnavailable) {
        Spacer(Modifier.height(10.dp))
        Text(
            "Голос сербского не установлен. Настройки → Система → Языки → Синтез речи.",
            style = MaterialTheme.typography.bodyMedium,
            color = Crimson
        )
    }

    Spacer(Modifier.height(20.dp))
    OutlinedTextField(
        value = value,
        onValueChange = { value = it; Touch.note() },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Что ты услышал", color = Muted) },
        textStyle = MaterialTheme.typography.bodyLarge,
        leadingIcon = { LanguageFlag(AnswerLanguage.Target) },
        // Микрофона здесь нет намеренно: продиктовать услышанное — значит
        // поручить распознавателю ровно ту работу, ради которой задание и есть.
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            imeAction = ImeAction.Done,
            hintLocales = LocaleList(AnswerLanguage.Target.keyboard)
        ),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Accent,
            unfocusedBorderColor = Surface2,
            focusedTextColor = Paper,
            unfocusedTextColor = Paper,
            disabledTextColor = Muted,
            cursorColor = Accent
        )
    )

    if (enabled) {
        Spacer(Modifier.height(16.dp))
        PrimaryButton("Проверить", enabled = value.isNotBlank()) { onSubmit(value) }
    }
}

/**
 * Сказать вслух и сверить с распознанным — фраза, текст или повтор на слух.
 *
 * [byEar] — фраза звучит сама, а текст закрыт, пока его не откроют: опереться
 * должно быть не на что, кроме услышанного. Длинный текст читается не здесь,
 * а в ReadingAnswer: один заход распознавания — одна короткая фраза.
 */
/**
 * Парадигма одним экраном: либо показана, либо спрошена.
 *
 * Два состояния одного блока, а не два блока: таблица та же самая, меняется
 * только, вписывает человек формы или читает их. Разводить это на два экрана
 * значило бы дважды написать одну разметку и однажды поправить её в одном
 * месте из двух.
 *
 * **Окончание выделено цветом, а основа нет** — ради этого всё и затевалось.
 * Список форм без выделения читается как список слов; система становится
 * видна ровно тогда, когда видно, что у `kuću`, `kuće`, `kući`, `kućom` общее
 * начало и разные хвосты.
 */
/**
 * Лайк и дизлайк, как у видео: выбран один, второй его снимает, повторное
 * нажатие на тот же снимает выбор вовсе.
 *
 * Состояние живёт по ключу задания, поэтому переход «ввод → результат» его не
 * теряет: тело задания рисуется один раз, до развилки по фазам.
 *
 * Нажатие уходит в журнал прохождений сразу, а не копится до конца задания:
 * задание можно и бросить, а мнение уже высказано.
 */
@Composable
private fun RateRow(key: String, onRate: (Int) -> Unit) {
    var rate by remember(key) { mutableIntStateOf(0) }

    fun tap(value: Int) {
        rate = if (rate == value) 0 else value
        onRate(rate)
    }

    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = { tap(1) }) {
            Icon(
                Icons.Default.ThumbUp,
                contentDescription = "Хорошее задание",
                tint = if (rate == 1) Jade else Muted
            )
        }
        IconButton(onClick = { tap(-1) }) {
            Icon(
                Icons.Default.ThumbDown,
                contentDescription = "Так себе задание",
                tint = if (rate == -1) Crimson else Muted
            )
        }
    }
}

@Composable
private fun ParadigmAnswer(
    ex: Exercise.Table,
    speaker: Speaker,
    enabled: Boolean,
    onSubmit: (List<String>) -> Unit
) {
    var answers by remember(ex.id) { mutableStateOf(List(ex.cells.size) { "" }) }

    // Слова таблицы по порядку: в обычной парадигме одно, в серии по образцу
    // три. По ним же идёт показ — по слову за нажатие.
    val lemmas = remember(ex.id) { ex.cells.map { it.lemma }.distinct() }

    // Сколько слов открыто. Показ выдаёт их по одному (жалоба 122): «прочитал
    // первое, появилась кнопка Продолжить и надо читать следующее». Читать по
    // одной **ячейке** было бы двенадцать нажатий на серию и заодно убило бы
    // всю затею: система видна ровно тогда, когда четыре формы стоят рядом.
    //
    // Заодно это чинит учёт времени на показе (жалоба 121): экран перестал
    // быть страницей, на которую можно смотреть неизвестно сколько.
    var open by remember(ex.id) { mutableIntStateOf(if (ex.ask) lemmas.size else 1) }
    val shown = if (ex.ask) lemmas else lemmas.take(open)
    val last = open >= lemmas.size

    Label(if (ex.ask) "Заполни таблицу" else "Посмотри, как это устроено")
    Text(ex.title, style = MaterialTheme.typography.headlineSmall, color = Paper)
    if (ex.note.isNotBlank()) {
        Spacer(Modifier.height(6.dp))
        Text(ex.note, style = MaterialTheme.typography.bodyMedium, color = Muted)
    }
    Spacer(Modifier.height(18.dp))

    var lemma = ""
    ex.cells.forEachIndexed { i, cell ->
        if (cell.lemma !in shown) return@forEachIndexed
        // Заголовок слова — только когда слово сменилось: в серии по образцу
        // их несколько, в обычной таблице одно.
        if (cell.lemma != lemma) {
            if (i > 0) Spacer(Modifier.height(16.dp))
            lemma = cell.lemma
            WordHead(cell, speaker)
            Spacer(Modifier.height(8.dp))
        }
        CellRow(
            cell = cell,
            ask = ex.ask,
            enabled = enabled,
            value = answers.getOrElse(i) { "" },
            onValue = { text -> answers = answers.toMutableList().also { it[i] = text } }
        )
        Spacer(Modifier.height(8.dp))
    }

    Spacer(Modifier.height(20.dp))
    PrimaryButton(
        when {
            ex.ask -> "Проверить"
            last -> "Понятно"
            else -> "Продолжить"
        },
        // У показа нажимать нечего, кроме «Понятно»: пустых полей там нет.
        enabled = enabled && (!ex.ask || answers.any { it.isNotBlank() })
    ) {
        if (!ex.ask && !last) open++ else onSubmit(answers)
    }
}

/**
 * Заголовок слова в таблице — нажимается и переводится (жалоба 120).
 *
 * Идёт через [GlossedText] нарочно: тот сам подчёркивает знакомое слово, сам
 * показывает перевод строкой ниже и сам ставит ударение. Правило «подчёркнуто
 * — значит нажимается» тем самым держится без единой новой строки, а вторая
 * своя реализация подсказки однажды разошлась бы с общей.
 */
@Composable
private fun WordHead(cell: ParadigmCell, speaker: Speaker) {
    GlossedText(
        text = cell.lemma,
        gloss = if (cell.gloss.isBlank()) emptyMap()
        else mapOf(cell.lemma.lowercase() to cell.gloss),
        style = MaterialTheme.typography.titleMedium,
        color = Accent,
        // Слово таблицы ещё и произносится: смотреть на «način» в шести
        // падежах полезнее, когда слышно, как он звучит. Микрофона тут нет
        // вовсе, глушить нечего.
        onWord = { speaker.speak(it) }
    )
}

@Composable
private fun CellRow(
    cell: ParadigmCell,
    ask: Boolean,
    enabled: Boolean,
    value: String,
    onValue: (String) -> Unit
) {
    // Разобранная ячейка красится сама: зелёным верная, красным нет
    // (жалобы 117 и 118). Ничего протаскивать для этого не надо — ячейка
    // знает и свой ответ, и свою верную форму, а `enabled` гаснет ровно
    // тогда, когда вердикт вынесен.
    //
    // Довод тот же, что у зелёных слов в читаемом тексте (2.4): счёт говорит
    // сколько, но не говорит какие, — а тут поля прямо на экране.
    val verdict: Color? = when {
        !ask || enabled -> null
        LocalCheck.matchesTyped(value, cell.form) -> Jade
        else -> Crimson
    }

    Column(Modifier.fillMaxWidth()) {
        Text(
            // Подпись и рамка вместе: название падежа говорит, как форма
            // зовётся, а рамка — ради чего она нужна.
            if (cell.frame.isBlank()) cell.label else "${cell.label}  ·  ${cell.frame}",
            style = MaterialTheme.typography.labelSmall,
            color = verdict ?: Muted
        )
        Spacer(Modifier.height(4.dp))
        if (ask) {
            OutlinedTextField(
                value = value,
                onValueChange = { Touch.note(); onValue(it) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Next,
                    hintLocales = LocaleList(AnswerLanguage.Target.keyboard)
                ),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Surface2,
                    focusedTextColor = Paper,
                    unfocusedTextColor = Paper,
                    // Разобранная ячейка: и рамка, и сам ответ цветом вердикта.
                    // Гаснуть до `Muted`, как у остальных полей, ей нельзя —
                    // именно её и пришли посмотреть.
                    disabledBorderColor = verdict ?: Surface2,
                    disabledTextColor = verdict ?: Muted,
                    cursorColor = Accent
                )
            )
        } else {
            Text(
                // Цвет читается только из @Composable-кода — правило
                // проекта, поэтому акцент передаётся внутрь параметром.
                endingMarked(cell.lemma, cell.form, Accent),
                style = MaterialTheme.typography.headlineSmall,
                color = Paper
            )
        }
    }
}

/** Основа обычная, окончание акцентом: без этого таблица — просто список слов. */
private fun endingMarked(lemma: String, form: String, accent: Color) = buildAnnotatedString {
    var i = 0
    while (i < minOf(lemma.length, form.length) && lemma[i] == form[i]) i++
    append(form.substring(0, i))
    withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) {
        append(form.substring(i))
    }
}

@Composable
private fun SpokenAnswer(
    key: String,
    label: String,
    text: String,
    translation: String,
    byEar: Boolean,
    /** Расслышанное движком в последний заход — пусто, пока не отвечали. */
    heard: String,
    gloss: Map<String, String>,
    translationGloss: Map<String, String>,
    speaker: Speaker,
    enabled: Boolean,
    onSubmit: (String) -> Unit,
    onSkip: () -> Unit
) {
    val context = LocalContext.current
    val listener = remember { Listener(context) }
    var status by remember(key) { mutableStateOf("") }
    var listening by remember(key) { mutableStateOf(false) }
    var revealed by remember(key) { mutableStateOf(false) }

    // Уходя с экрана, распознаватель надо отпустить: он держит системный сервис
    // и заглушку на звуке, поставленную на время записи.
    DisposableEffect(Unit) { onDispose { listener.stop() } }

    // После ответа текст открывается сам: иначе не с чем сверить услышанное.
    val showText = !byEar || revealed || !enabled

    // Расслышанные слова — зелёными прямо во фразе. Считаются заново на каждый
    // заход, поэтому вторая попытка сама сбрасывает подсветку первой.
    val green = remember(key, heard) {
        if (heard.isBlank()) emptySet() else LocalCheck.matchedWords(heard, text)
    }

    if (byEar) {
        LaunchedEffect(key) { if (enabled) speaker.speak(text) }
    }

    fun start() {
        listening = true
        status = "Говори…"
        listener.listen(
            onResult = { heard ->
                listening = false
                status = ""
                onSubmit(heard)
            },
            onError = { message ->
                listening = false
                status = message
            }
        )
    }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) start() else status = "Без доступа к микрофону задание не проверить"
    }

    Label(label)
    if (showText) {
        Prompt(
            text, gloss, green = green,
            // Слушать и говорить одновременно нельзя: микрофон подхватил бы
            // собственный голос и засчитал его за ответ. Тот же порядок, что
            // у кнопки «Прослушать» ниже.
            onWord = { word ->
                listener.cancel()
                listening = false
                status = ""
                speaker.speak(word)
            }
        )
    } else {
        Text(
            "Текст закрыт — слушай и повторяй.",
            style = MaterialTheme.typography.headlineSmall,
            color = Muted
        )
    }
    Spacer(Modifier.height(8.dp))
    GlossedText(translation, translationGloss, MaterialTheme.typography.bodyMedium, Muted)
    Spacer(Modifier.height(16.dp))
    // Образец обрывает запись: слушать и говорить одновременно нельзя —
    // микрофон подхватил бы голос синтезатора и засчитал его за ответ.
    fun sample(slow: Boolean) {
        listener.cancel()
        listening = false
        status = ""
        speaker.speak(text, slow = slow)
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SmallAction(if (byEar) "Ещё раз" else "Послушать образец") { sample(slow = false) }
        SmallAction("Медленнее") { sample(slow = true) }
        if (!showText) {
            SmallAction("Показать текст") { revealed = true }
        }
    }

    Spacer(Modifier.height(24.dp))
    PrimaryButton(if (listening) "Слушаю…" else "Записать", enabled = enabled && !listening) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) start() else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    if (status.isNotBlank()) {
        Spacer(Modifier.height(12.dp))
        Text(status, style = MaterialTheme.typography.bodyMedium, color = Muted)
    }

    if (enabled) {
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onSkip) {
            Text("Пропустить", color = Muted)
        }
    }
}

/**
 * Перевёртыши — свой экран, а не тело задания в общей колонке (4.20).
 *
 * Владелец: «интерфейс всё время скачет, кнопки снизу создают визуальный
 * шум». Причина была в устройстве, а не в отступах: общий экран заданий под
 * телом показывает «Отложить», лайки, плашку попыток, плашку вердикта,
 * «Дальше» и жалобу — и каждая из них появляется и пропадает по фазе. Для
 * урока это терпимо, там над заданием думают; перевёртыш же меняет фазу
 * каждые три секунды, и экран дёргался на каждой.
 *
 * Поэтому здесь **ничего не появляется и не пропадает**: шапка, карточка на
 * всю середину и одна кнопка внизу, всегда на одном месте. Всё, что раньше
 * жило под карточкой, переехало на неё саму, как в виджете:
 *
 * * **флаг** в углу — на каком языке сторона;
 * * **💤 отложить** и **🧠 уже знаю** — на лицевой стороне, там, где решают,
 *   нужно ли слово вообще;
 * * **🔊** — на обороте, где есть что слушать;
 * * **строка состояния** — внизу карточки, в своём месте фиксированной высоты:
 *   «говори», что расслышано, «верно». Меняется текст, а не раскладка.
 *
 * Карточка нажимается: лицом — «не помню, покажи» (пропуск без штрафа),
 * оборотом — дальше. Кнопка внизу та же самая и на том же месте: лицом она
 * микрофон, оборотом — «Дальше». Две руки на одно и то же действие —
 * намеренно: палец лежит то на карточке, то у кнопки, и искать не надо.
 *
 * Чего тут нет: лайков (журнал оценок перевёртышу ничего не добавит — оценивать
 * тут нечего, слово одно) и категорийной жалобы на вердикт. Жалоба остаётся
 * флажком в шапке, как и везде.
 */
@Composable
private fun FlipSession(
    state: SessionState,
    ex: Exercise.Card,
    speaker: Speaker,
    onSubmit: (String) -> Unit,
    onSkip: () -> Unit,
    onSnooze: () -> Unit,
    onKnown: () -> Unit,
    onNext: () -> Unit,
    onNote: (String) -> Unit,
    onIdea: (String, String) -> Unit,
    onExit: () -> Unit
) {
    val phase = state.phase
    val turned = phase is Phase.Result || phase is Phase.Skipped
    val live = phase is Phase.Input || phase is Phase.Retry
    val attempt = (phase as? Phase.Retry)?.attempts ?: 0
    val correct = (phase as? Phase.Result)?.correct == true

    val context = LocalContext.current
    val listener = remember { Listener(context) }
    var status by remember(ex.id) { mutableStateOf("") }
    var listening by remember(ex.id) { mutableStateOf(false) }
    var askKnown by remember(ex.id) { mutableStateOf(false) }

    // Уходя с экрана, распознаватель надо отпустить: он держит системный
    // сервис и заглушку на звуке, поставленную на время записи.
    DisposableEffect(Unit) { onDispose { listener.stop() } }

    // Угол свой у каждой карточки (4.18): общий после «Дальше» стоял бы на
    // 180°, и оборот успевал показать **следующий** ответ раньше вопроса —
    // беда виджета из жалобы 165. Новая карточка появляется сразу лицом.
    val angle = remember(ex.id) { Animatable(0f) }
    LaunchedEffect(ex.id, turned) {
        angle.animateTo(if (turned) 180f else 0f, tween(FLIP_MS))
    }

    fun start() {
        // Слушать и говорить одновременно нельзя: предыдущая карточка могла
        // ещё договаривать свой оборот, и микрофон подхватил бы синтезатор.
        speaker.silence()
        listening = true
        status = ""
        listener.listen(
            onResult = { heard ->
                listening = false
                onSubmit(heard)
            },
            onError = { message ->
                listening = false
                status = message
            }
        )
    }

    fun quiet() {
        listener.cancel()
        listening = false
        status = ""
    }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) start() else status = "Без доступа к микрофону карточку не проверить"
    }

    fun record() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) start() else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Микрофон включается сам — и после каждого промаха тоже: «не совпало»
    // чаще значит «не расслышал», чем «не знаешь». Попыток три, круга не
    // выйдет. Разрешение отсюда не спрашиваем — диалог выскочил бы без
    // нажатия; нет доступа — остаётся кнопка.
    LaunchedEffect(ex.id, attempt) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (live && granted) start()
    }

    // У формы звучит и пишется вся рамка, «Vidim kuću.»: падеж слышен в
    // связке со словом, которое его требует.
    val said = if (ex.form && ex.frame.contains("___")) {
        ex.frame.replace("___", ex.answer)
    } else {
        ex.answer
    }

    // Голос ждёт конца поворота: слово над лицевой стороной читалось бы как
    // подсказка, а не как ответ.
    LaunchedEffect(ex.id, turned) {
        if (turned) {
            delay(FLIP_MS.toLong())
            speaker.speak(said)
        }
    }

    // Верно — через секунду следующая карточка сама (4.18). Ключ — карточка:
    // ушли раньше паузы — эффект отменится, лишнего перехода не будет.
    LaunchedEffect(ex.id, correct) {
        if (correct) {
            delay(FLIP_MS + AUTO_NEXT_MS)
            onNext()
        }
    }

    // Строка состояния. Одна на все фазы и в одном месте: меняется текст, а
    // не раскладка экрана.
    val line: Pair<String, Color> = when (phase) {
        is Phase.Retry -> "Услышано «${phase.heard}» · ещё ${phase.left}" to Muted
        is Phase.Result -> if (phase.correct) {
            "Верно" to Jade
        } else {
            (if (phase.answer.isNotBlank()) "Не совпало: «${phase.answer}»" else "Не совпало") to Crimson
        }
        is Phase.Skipped -> "Без штрафа · нажми — дальше" to Muted
        else -> when {
            listening -> "Говори…" to Accent
            status.isNotBlank() -> status to Muted
            else -> "Не помнишь — нажми на карточку" to Muted
        }
    }

    Column(Modifier.fillMaxSize()) {
        SessionHeader(state, onNote, onIdea, onExit)

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .graphicsLayer {
                    rotationY = angle.value
                    // Без этого поворот выглядит плоским сжатием: перспектива
                    // у Compose по умолчанию такая дальняя, что её не видно.
                    cameraDistance = 14f * density
                }
                .clip(RoundedCornerShape(24.dp))
                .background(Surface1)
                .border(
                    1.dp,
                    if (turned) Accent.copy(alpha = 0.5f) else Surface2,
                    RoundedCornerShape(24.dp)
                )
                .clickable(enabled = turned || live) {
                    if (turned) {
                        onNext()
                    } else {
                        quiet()
                        onSkip()
                    }
                }
        ) {
            // Середина поворота — когда карточка стоит ребром: менять сторону
            // надо там, иначе видно, как текст подменяется.
            if (angle.value <= 90f) {
                FlipFace(
                    flag = R.drawable.flag_ru,
                    line = line,
                    corner = {
                        // Отложить и «уже знаю» — на лице: решают, нужно ли
                        // слово, до того как его вспоминать.
                        if (live) {
                            CardIcon("💤", "Отложить на потом") {
                                quiet()
                                onSnooze()
                            }
                            // У формы «уже знаю» значило бы «знаю слово», а
                            // спрашивают падеж: кнопки там нет.
                            if (!ex.form) {
                                CardIcon("🧠", "Уже знаю") {
                                    quiet()
                                    askKnown = true
                                }
                            }
                        }
                    }
                ) {
                    if (ex.form) {
                        Text(ex.label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Accent)
                        Spacer(Modifier.height(14.dp))
                        Text(
                            ex.frame.ifBlank { "___" },
                            style = MaterialTheme.typography.headlineLarge,
                            color = Paper,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "${ex.lemma} — ${ex.prompt}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Muted,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        // 140 dp — как в пробе 4.35, которую владелец смотрел на
                        // телефоне: карточка во весь экран, места хватает.
                        if (ex.picture.isNotBlank()) {
                            WordPictureImage(ex.picture, 140.dp)
                            Spacer(Modifier.height(16.dp))
                        } else if (ex.icon.isNotBlank()) {
                            Text(ex.icon, fontSize = 52.sp, lineHeight = 58.sp)
                            Spacer(Modifier.height(16.dp))
                        }
                        Text(
                            ex.prompt,
                            style = MaterialTheme.typography.headlineLarge,
                            color = Paper,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                // Оборот перевернулся вместе с карточкой: доворачиваем его
                // обратно, иначе текст был бы зеркальным.
                Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }) {
                    FlipFace(
                        flag = R.drawable.flag_me,
                        line = line,
                        corner = { CardIcon("🔊", "Послушать ещё раз") { speaker.speak(said) } }
                    ) {
                        if (ex.form) {
                            Text(ex.label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Muted)
                            Spacer(Modifier.height(14.dp))
                            // Форма выделена в рамке цветом — ради неё карточка.
                            val accent = Accent
                            val parts = ex.frame.split("___", limit = 2)
                            Text(
                                if (parts.size == 2) {
                                    buildAnnotatedString {
                                        append(parts[0])
                                        withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) {
                                            append(ex.answer)
                                        }
                                        append(parts[1])
                                    }
                                } else {
                                    stressed(ex.answer)
                                },
                                style = MaterialTheme.typography.headlineLarge,
                                color = Paper,
                                textAlign = TextAlign.Center
                            )
                        } else {
                            Text(
                                stressed(ex.answer),
                                style = MaterialTheme.typography.headlineLarge,
                                color = Paper,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }

        // Одна кнопка, всегда на одном месте и одной высоты. Лицом — микрофон,
        // оборотом — «Дальше»: то же, что нажатие на карточку.
        Button(
            onClick = { if (turned) onNext() else record() },
            enabled = turned || (live && !listening),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp)
                .height(64.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink)
        ) {
            if (turned) {
                Text("Дальше", style = MaterialTheme.typography.titleMedium)
            } else {
                Icon(
                    if (listening) Icons.Filled.Mic else Icons.Outlined.Mic,
                    contentDescription = null
                )
                Text(
                    if (listening) "  Слушаю…" else "  Сказать",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }

    // Подтверждение — как в виджете: значок маленький, а цена случайного
    // касания — слово, пропавшее из занятий на два месяца.
    if (askKnown) {
        AlertDialog(
            onDismissRequest = { askKnown = false },
            title = { Text("🧠 Уже знаю") },
            text = {
                Text("«${ex.answer}» уйдёт в выученные и вернётся на проверку через два месяца.")
            },
            confirmButton = {
                TextButton(onClick = {
                    askKnown = false
                    onKnown()
                }) { Text("Знаю", color = Accent) }
            },
            dismissButton = {
                TextButton(onClick = { askKnown = false }) { Text("Отмена", color = Muted) }
            }
        )
    }
}

/**
 * Сторона карточки: флаг слева сверху, значки справа сверху, содержимое по
 * центру, строка состояния снизу. Места у всех частей постоянные — меняется
 * только их текст.
 */
@Composable
private fun FlipFace(
    flag: Int,
    line: Pair<String, Color>,
    corner: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        Image(
            painterResource(flag),
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(16.dp)
                .height(20.dp)
        )
        Row(
            Modifier.align(Alignment.TopEnd).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) { corner() }
        Column(
            Modifier
                .align(Alignment.Center)
                .padding(horizontal = 24.dp, vertical = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) { content() }
        Text(
            line.first,
            style = MaterialTheme.typography.bodyMedium,
            color = line.second,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp)
        )
    }
}

/** Значок на карточке: эмодзи, как в виджете, с полем под палец. */
@Composable
private fun CardIcon(emoji: String, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(emoji, fontSize = 22.sp)
    }
}

/**
 * Эталон в карточке результата.
 *
 * Черногорский эталон **нажимается**: по нажатию видно перевод слова и
 * слышно, как оно звучит (заметка 133). После ответа это ровно то место,
 * куда смотрят, — а до правки оно было единственным черногорским текстом в
 * уроке, с которым нельзя было сделать ничего.
 *
 * Подпись стоит своей строкой, а не «Правильно: » перед фразой: нажимаемая
 * фраза в одной строке с подписью переносилась бы по живому.
 *
 * Русский эталон остаётся обычным текстом — голос у нас `sr-RS`, и
 * произносить там нечего. Ударение в обоих случаях ставит сам
 * [GlossedText], поэтому `stressedPhrase` тут больше не нужен.
 */
@Composable
private fun Reference(
    label: String,
    text: String,
    target: Boolean,
    gloss: Map<String, String>,
    speaker: Speaker,
    dim: Boolean
) {
    val color = if (dim) Muted else Paper
    val style =
        if (dim) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge
    if (!target) {
        Text("$label: $text", style = style, color = color)
        return
    }
    Text(label, style = MaterialTheme.typography.labelSmall, color = Muted)
    Spacer(Modifier.height(4.dp))
    GlossedText(text, gloss, style, color, onWord = { speaker.speak(it) })
}

@Composable
private fun ResultView(
    phase: Phase.Result,
    exercise: Exercise,
    speaker: Speaker,
    gloss: Map<String, String>
) {
    val accent = if (phase.correct) Jade else Crimson

    // Точное совпадение с эталоном показывать незачем — строка дублировала бы «Правильно».
    val differs = !LocalCheck.matches(phase.answer, phase.expected)
    val showAnswer = phase.answer.isNotBlank() && (!phase.correct || differs)
    // Для речи это не то, что ты сказал, а то, что расслышал движок.
    val spoken = exercise is Exercise.Speaking ||
        exercise is Exercise.Repeat ||
        exercise is Exercise.Reading ||
        exercise is Exercise.Card
    val answerLabel = if (spoken) "Услышано" else "Твой ответ"
    // Черногорский ли эталон. Решает две вещи: ставить ли ударение (размечать
    // русское слово по сербской норме значило бы врать) и нажимается ли
    // эталон — произносить русскую строку голосом sr-RS нечего.
    val target = when (exercise) {
        is Exercise.TranslateToNative -> false
        is Exercise.Word -> !exercise.native
        else -> true
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1)
            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Text(
            if (phase.correct) "ВЕРНО" else "ОШИБКА",
            style = MaterialTheme.typography.labelSmall,
            color = accent
        )
        Spacer(Modifier.height(10.dp))
        if (showAnswer) {
            Text(
                "$answerLabel: ${phase.answer}",
                style = MaterialTheme.typography.bodyLarge,
                color = Paper
            )
            Spacer(Modifier.height(8.dp))
        }
        // Эталон бывает пустым — у экрана пар его нет вовсе: он раскрыл себя
        // сам, пока его собирали.
        if (!phase.correct && phase.expected.isNotBlank()) {
            Reference("Правильно", phase.expected, target, gloss, speaker, dim = false)
            Spacer(Modifier.height(8.dp))
        } else if (showAnswer) {
            // Ответ засчитан, но не совпал с эталоном: «Правильно» тут вводило бы в заблуждение.
            Reference("Эталон", phase.expected, target, gloss, speaker, dim = true)
            Spacer(Modifier.height(8.dp))
        }
        if (phase.feedback.isNotBlank()) {
            Text(phase.feedback, style = MaterialTheme.typography.bodyMedium, color = Muted)
        }
        if (phase.better.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text("Естественнее: ${phase.better}", style = MaterialTheme.typography.bodyMedium, color = Accent)
        }
    }
}

/**
 * Чтение вслух — по одному предложению за заход.
 *
 * Целым текстом не выходит: движок распознавания на длинной фразе возвращает
 * «ничего не расслышал», а просьбы не обрывать запись на паузе документация
 * Android разрешает игнорировать — что он и делает. Короткая фраза
 * распознаётся надёжно, поэтому текст читается по предложению, услышанное
 * склеивается и оценивается целиком, уже в AppViewModel.
 *
 * Ошибка распознавания стоит одного предложения, а не всего текста: сорванное
 * перечитывается на месте, прочитанное раньше не теряется.
 */
@Composable
private fun ReadingAnswer(
    ex: Exercise.Reading,
    speaker: Speaker,
    gloss: Map<String, String>,
    translationGloss: Map<String, String>,
    enabled: Boolean,
    onSubmit: (String) -> Unit,
    onSkip: () -> Unit
) {
    val context = LocalContext.current
    val listener = remember { Listener(context) }
    val sentences = remember(ex.id) { LocalCheck.sentences(ex.text) }
    var heard by remember(ex.id) { mutableStateOf(listOf<String>()) }
    var status by remember(ex.id) { mutableStateOf("") }
    var listening by remember(ex.id) { mutableStateOf(false) }

    DisposableEffect(Unit) { onDispose { listener.stop() } }

    val index = heard.size.coerceAtMost(sentences.size - 1)
    val current = sentences.getOrElse(index) { ex.text }

    // Прочитанное предложение до сих пор красилось зелёным целиком — а это
    // неправда: порог чтения 75%, то есть текст сдаётся, теряя каждое
    // четвёртое слово, и какое именно потеряно, видно не было. Теперь зелёные
    // ровно те слова, которые движок разобрал.
    val green = remember(ex.id, heard) {
        sentences.indices.map { i ->
            val said = heard.getOrNull(i)
            if (said == null) emptySet() else LocalCheck.matchedWords(said, sentences[i])
        }
    }

    fun start() {
        listening = true
        status = ""
        listener.listen(
            onResult = { text ->
                listening = false
                val collected = heard + text
                if (collected.size >= sentences.size) {
                    onSubmit(collected.joinToString(" "))
                } else {
                    heard = collected
                }
            },
            onError = { message ->
                listening = false
                status = "$message. Это предложение можно перечитать."
            }
        )
    }

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) start() else status = "Без доступа к микрофону задание не проверить"
    }

    Label("Прочитай вслух")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        sentences.forEachIndexed { i, sentence ->
            GlossedText(
                text = sentence,
                gloss = gloss,
                style = MaterialTheme.typography.headlineSmall,
                color = when {
                    !enabled -> Paper
                    i == index || i < heard.size -> Paper
                    else -> Muted
                },
                green = green.getOrElse(i) { emptySet() },
                // Слушать и говорить одновременно нельзя: микрофон подхватил бы
                // собственный голос и засчитал его за ответ. Тот же порядок,
                // что у кнопки «Прослушать» ниже.
                onWord = { word ->
                    listener.cancel()
                    listening = false
                    status = ""
                    speaker.speak(word)
                }
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    GlossedText(ex.translation, translationGloss, MaterialTheme.typography.bodyMedium, Muted)

    if (enabled) {
        Spacer(Modifier.height(16.dp))
        Text(
            "Предложение ${index + 1} из ${sentences.size} — читается по одному.",
            style = MaterialTheme.typography.labelSmall,
            color = Muted
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallAction("Послушать") {
                listener.cancel(); listening = false; speaker.speak(current)
            }
            SmallAction("Медленнее") {
                listener.cancel(); listening = false; speaker.speak(current, slow = true)
            }
            if (heard.isNotEmpty()) {
                SmallAction("Сначала") {
                    heard = emptyList()
                    status = ""
                }
            }
        }
    }

    Spacer(Modifier.height(24.dp))
    PrimaryButton(
        if (listening) "Слушаю…" else "Записать предложение",
        enabled = enabled && !listening
    ) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) start() else permission.launch(Manifest.permission.RECORD_AUDIO)
    }

    if (status.isNotBlank()) {
        Spacer(Modifier.height(12.dp))
        Text(status, style = MaterialTheme.typography.bodyMedium, color = Muted)
    }

    if (enabled) {
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onSkip) {
            Text("Пропустить", color = Muted)
        }
    }
}

/** Общий хвост под ответом и под пропуском: «Дальше» плюс жалоба. */
@Composable
private fun AnswerTail(
    state: SessionState,
    onNext: () -> Unit,
    onComplain: (ComplaintReason, String) -> Unit
) {
    Spacer(Modifier.height(20.dp))
    Button(
        onClick = onNext,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Ink)
    ) { Text("Дальше", style = MaterialTheme.typography.titleMedium) }
    Spacer(Modifier.height(4.dp))
    ComplaintBlock(
        exerciseId = state.current.id,
        filed = state.complaintFiled,
        onComplain = onComplain
    )
}

/**
 * Пропуск — не ошибка, поэтому нейтральные цвета и никакого «ОШИБКА»:
 * карточка только отодвинута, ease и счётчик повторений не тронуты.
 */
@Composable
private fun SkippedView(phase: Phase.Skipped) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1)
            .border(1.dp, Surface2, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        Text("ПРОПУЩЕНО", style = MaterialTheme.typography.labelSmall, color = Muted)
        Spacer(Modifier.height(10.dp))
        Text(phase.expected, style = MaterialTheme.typography.bodyLarge, color = Paper)
        Spacer(Modifier.height(8.dp))
        Text(
            "Без штрафа: карточка вернётся через несколько часов.",
            style = MaterialTheme.typography.bodyMedium,
            color = Muted
        )
    }
}

/**
 * Жалоба на задание. Свёрнута в одну строчку, пока не понадобится:
 * это инструмент правки курса, а не часть учебного потока.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ComplaintBlock(
    exerciseId: String,
    filed: Boolean,
    onComplain: (ComplaintReason, String) -> Unit
) {
    // Ключ по заданию: разворот и выбранная причина не должны переезжать на следующее.
    var open by remember(exerciseId) { mutableStateOf(false) }
    var reason by remember(exerciseId) { mutableStateOf<ComplaintReason?>(null) }
    var note by remember(exerciseId) { mutableStateOf("") }

    if (filed) {
        Spacer(Modifier.height(8.dp))
        Text(
            "Жалоба записана. Карточка не пойдёт в повторение из-за этого ответа.",
            style = MaterialTheme.typography.bodyMedium,
            color = Muted
        )
        return
    }

    if (!open) {
        TextButton(onClick = { open = true }) {
            Text("Пожаловаться на задание", color = Muted)
        }
        return
    }

    Spacer(Modifier.height(8.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1)
            .padding(14.dp)
    ) {
        Text("ЧТО НЕ ТАК", style = MaterialTheme.typography.labelSmall, color = Muted)
        Spacer(Modifier.height(12.dp))

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ComplaintReason.entries.forEach { option ->
                Chip(option.label, filled = reason == option, enabled = true) {
                    reason = option
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Комментарий, если нужен", color = Muted) },
            textStyle = MaterialTheme.typography.bodyMedium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent,
                unfocusedBorderColor = Surface2,
                focusedTextColor = Paper,
                unfocusedTextColor = Paper,
                cursorColor = Accent
            )
        )

        Spacer(Modifier.height(14.dp))
        // Категория важнее текста, но текст без категории — тоже жалоба: по
        // жалобе владельца, который написал комментарий к верно решённому
        // заданию и не смог его сохранить. Тогда причина «другое»: она и значит
        // «ни одна категория не подошла».
        PrimaryButton(
            "Записать жалобу",
            enabled = reason != null || note.isNotBlank()
        ) {
            onComplain(reason ?: ComplaintReason.Other, note)
        }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = { open = false }) { Text("Отмена", color = Muted) }
    }
}

/**
 * Не совпало, но попытки остались.
 *
 * Главное здесь — расслышанное. Чаще всего исправлять надо не произношение, а
 * то, что движок услышал соседнее слово, и увидеть это можно только так.
 * Эталона нет намеренно: он на экране и есть задание.
 */
@Composable
private fun RetryView(phase: Phase.Retry) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface1)
            .padding(14.dp)
    ) {
        Text("НЕ СОВПАЛО", style = MaterialTheme.typography.labelSmall, color = Muted)
        Spacer(Modifier.height(10.dp))
        Text(
            "Услышано: ${phase.heard}",
            style = MaterialTheme.typography.bodyLarge,
            color = Paper
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (phase.left > 1) "Осталось попыток: ${phase.left}" else "Последняя попытка",
            style = MaterialTheme.typography.bodyMedium,
            color = Muted
        )
    }
}

@Composable
private fun BlockedView(message: String, onRetry: () -> Unit, onExit: () -> Unit) {
    Column {
        Text("НЕ ПОЛУЧИЛОСЬ", style = MaterialTheme.typography.labelSmall, color = Crimson)
        Spacer(Modifier.height(12.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, color = Paper)
        Spacer(Modifier.height(24.dp))
        PrimaryButton("Повторить попытку") { onRetry() }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onExit) { Text("Вернуться к списку", color = Muted) }
    }
}

@Composable
private fun FinishedView(state: SessionState, onExit: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            when {
                state.failed -> "ТЕСТ НЕ СДАН"
                state.strict -> "ТЕСТ СДАН"
                else -> "ГОТОВО"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (state.failed) Crimson else Accent
        )
        Spacer(Modifier.height(12.dp))
        Text(state.title, style = MaterialTheme.typography.displaySmall, color = Paper)
        Spacer(Modifier.height(16.dp))
        Text(
            when {
                state.failed -> "Больше $STRICT_MISTAKES ошибок. Можно попробовать ещё раз " +
                    "или пройти уроки раздела по порядку."
                state.strict -> "Ошибок: ${state.mistakes}. Следующий раздел открыт, " +
                    "слова пропущенных уроков появились в словаре."
                else -> "${state.correct} из ${state.items.size} с первого раза. " +
                    "Ошибки вернутся в повторении."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = Muted
        )
        Spacer(Modifier.height(32.dp))
        PrimaryButton("К списку уроков") { onExit() }
    }
}

@Composable
internal fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Accent,
            contentColor = Ink,
            disabledContainerColor = Surface2,
            disabledContentColor = Muted
        )
    ) { Text(text, style = MaterialTheme.typography.titleMedium) }
}

@Composable
internal fun SmallAction(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, Surface2, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Accent)
    }
}

@Composable
private fun Chip(text: String, filled: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (filled) Accent.copy(alpha = 0.18f) else Surface2)
            .border(
                1.dp,
                if (filled) Accent.copy(alpha = 0.5f) else Surface2,
                RoundedCornerShape(10.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Paper)
    }
}

/**
 * Нарисованная картинка к слову ([WordPicture]). Не прочиталась — места не
 * занимает: пустой квадрат читался бы как сломанная карточка.
 */
@Composable
private fun WordPictureImage(name: String, size: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    val bitmap = remember(name) { WordPicture.bitmap(context, name)?.asImageBitmap() } ?: return
    Image(bitmap, contentDescription = null, modifier = Modifier.size(size))
}
