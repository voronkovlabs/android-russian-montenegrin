# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Что это

Android-приложение «Crnogorski» — персональный тренажёр черногорского языка (русский → черногорский, латиница) для одного пользователя. Kotlin + Jetpack Compose, Room, Claude Haiku для проверки свободных переводов. Без геймификации, без аккаунтов, без сервера: уроки лежат в `assets`, прогресс — в локальной Room-базе.

Документация проекта на русском; комментарии в коде тоже. Пиши в том же стиле.

- `README.md` (в `src/`) — сборка, форматы заданий, устройство проверки, речь.
- `STATUS.md` (в корне) — принятые решения, что ещё не проверено, ближайшие задачи. **Обновляй его по ходу работы** — это способ передачи контекста между сессиями.

## Структура и сборка

Корень Gradle-проекта — подпапка **`src/`**, а не корень репозитория. Все команды выполняются оттуда:

```
cd src
./gradlew assembleDebug          # сборка
./gradlew installDebug           # установка на подключённое устройство
./gradlew :app:compileDebugKotlin  # быстрая проверка компиляции
```

Перед первой сборкой нужен `src/local.properties` (шаблон — `src/local.properties.example`):

```
sdk.dir=/путь/к/Android/sdk
ANTHROPIC_API_KEY=sk-ant-...
```

Ключ подставляется в `BuildConfig.ANTHROPIC_API_KEY` из `app/build.gradle.kts` и в git не попадает.

**Gradle Wrapper отсутствует** (`src/gradle/` пуста, `gradlew` нет) — его нужно сгенерировать (`gradle wrapper`) или собирать из Android Studio. Тестов в проекте нет.

`minSdk = 35` — обратная совместимость сознательно не поддерживается, единственная цель — телефон владельца.

### Несостыковка namespace ↔ пакет

`namespace`/`applicationId` в `app/build.gradle.kts` — `com.montelearn`, а исходники лежат в `com.crnogorski.trener`. Из-за этого:

- `HaikuChecker.kt` импортирует `com.crnogorski.trener.BuildConfig`, а сгенерирован будет `com.montelearn.BuildConfig`;
- `android:name=".MainActivity"` в манифесте разрешается в `com.montelearn.MainActivity`.

Это первое, обо что споткнётся сборка. Чинится выбором одного варианта: либо `namespace = "com.crnogorski.trener"`, либо переезд пакетов на `com.montelearn`.

## Архитектура

Один Activity, один ViewModel, два экрана, никакой навигационной библиотеки: `MainActivity` показывает `HomeScreen` или `SessionScreen` в зависимости от того, `null` ли `AppViewModel.session`.

```
assets/lessons/*.json ──> LessonRepository ──┐
                                             ├──> AppViewModel ──> HomeScreen / SessionScreen
Room (cards, lesson_progress) ──> AppDao ────┤
                          Scheduler (SM-2) ──┘
                          HaikuChecker (api.anthropic.com)
                          Speaker / Listener (TTS, распознавание речи)
```

`AppViewModel` — единственное место, где сходится вся логика: сборка сессии (урок или повторение), выбор способа проверки, запись карточек в SRS, завершение урока. `SessionState`/`Phase` (`Input` → `Checking` → `Result` | `Blocked`) описывают весь экран задания.

### Два способа проверки — ключевое разделение

`Exercise` — sealed class с семью подтипами, дискриминатор в JSON — поле `type` (`classDiscriminator = "type"` в `LessonRepository`).

- **Локально, офлайн** (`LocalCheck` в `LessonRepository.kt`): `choice`, `word_bank`, `form`, `listening`, `speaking`. Нормализация регистра, пунктуации и пробелов; **диакритика значима** (č/ć/š/ž/đ различают слова). Исключение — `speaking`: `matchesSpoken` сплющивает диакритику, потому что движок распознавания её теряет.
- **Через Claude Haiku** (`HaikuChecker`): `ru_to_me` и `me_to_ru` — свободные переводы, где допустимы синонимы и другой порядок слов. Расширение `Exercise.needsModelCheck` — единственный источник истины о том, какие типы требуют сети.

Если в сессии есть задания с `needsModelCheck`, а сети нет, `guardNetwork` сразу переводит экран в `Phase.Blocked` и не даёт начать урок. Это осознанное решение (см. STATUS.md), а не недоделка.

`HaikuChecker` дёргает Messages API напрямую через OkHttp (модель `claude-haiku-4-5-20251001`, `temperature = 0`), просит вернуть один JSON-объект `{correct, feedback, better}` и парсит его в `Verdict`. Промпт **не откалиброван на реальных ответах** — если вердикты придирчивы или, наоборот, слишком мягкие, правится системный промпт в `HaikuChecker.kt`, а не логика приложения. Парсер снимает markdown-обёртку, но при неожиданном формате задание уходит в `Phase.Blocked`.

### SRS

`Scheduler` — упрощённый SM-2 с бинарной оценкой: верно → интервал 1 → 3 → `interval × ease` (дни), ease растёт до 3.0; неверно → сброс, ease −0.2 (не ниже 1.3), карточка возвращается через 10 минут, то есть ещё в текущей сессии. Карточка привязана к `exerciseId`, поэтому прогресс переживает добавление уроков.

Блок «Повторение» на главном экране собирает все просроченные карточки из всех уроков (`startReview` сопоставляет `dueCards` с `repo.allExercises()`).

### Речь

Локали «черногорский» в Android нет — используется `sr-RS` с латиницей (`Speech.kt`). `Speaker` (TTS) озвучивает `listening`-задания; `Listener` (SpeechRecognizer) распознаёт `speaking`. Качество произношения не оценивается: достаточно того, что движок распознал фразу. `Speaker` живёт в `MainActivity` и передаётся в `SessionScreen` параметром.

## Добавление уроков

1. Новый файл в `src/app/src/main/assets/lessons/`, строка в `index.json`, `versionCode` +1.
2. **`id` заданий уникальны по всему курсу и не меняются между версиями** — иначе теряется история повторений. Схема: `l09e01`.
3. Форматы всех семи типов заданий — в `src/README.md`; канонические типы — в `data/Model.kt`.

При добавлении нового типа задания нужно тронуть четыре места: `Exercise` в `Model.kt`, `referenceAnswer`, `submitText` в `AppViewModel.kt` и рендер в `SessionScreen.kt`.

## Оформление

Тёмная тема, серифные заголовки, золотой акцент — `ui/Theme.kt`. Цвета берутся оттуда (`Ink`, `Paper`, `Gold`, `Crimson`…), не хардкодятся по месту.
