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
./gradlew pullComplaints         # забрать жалобы с телефона в app/build/complaints.jsonl
```

Перед первой сборкой нужен `src/local.properties` (шаблон — `src/local.properties.example`):

```
sdk.dir=/путь/к/Android/sdk
ANTHROPIC_API_KEY=sk-ant-...
```

Ключ подставляется в `BuildConfig.ANTHROPIC_API_KEY` из `app/build.gradle.kts` и в git не попадает.

Wrapper в репозитории есть — **Gradle 8.11.1**, зафиксирован сознательно: AGP 8.7.2 требует Gradle 8.9+ и с веткой Gradle 9.x не работает. Не обновляй wrapper, не подняв заодно AGP. Тестов в проекте нет.

`minSdk = 35` — обратная совместимость сознательно не поддерживается, единственная цель — телефон владельца.

### namespace ≠ applicationId — так и задумано

`namespace = "com.crnogorski.trener"` (пакет исходников, от него считаются `BuildConfig` и `.MainActivity` в манифесте), а `applicationId = "com.montelearn"` — идентификатор установки на устройстве. Расхождение намеренное: раньше `namespace` был `com.montelearn`, из-за чего не разрешался `com.crnogorski.trener.BuildConfig` и сборка падала. Если решишь свести всё к одному имени — переноси пакеты исходников, а `namespace` держи равным пакету.

### Локальная среда (эта машина)

| | |
|---|---|
| Android SDK | `D:\Android\Sdk` (platform 35, build-tools 35.0.0, platform-tools) |
| JDK | `C:\Program Files\Java\jdk-21` (Oracle 21) |
| Кэш Gradle | `D:\GradleHome` — вынесен с C:, там мало места |

`ANDROID_HOME`, `ANDROID_SDK_ROOT`, `JAVA_HOME`, `GRADLE_USER_HOME` прописаны в пользовательских переменных среды; `platform-tools` и `cmdline-tools\latest\bin` — в `PATH`. `src/local.properties` создан, но **`ANTHROPIC_API_KEY` в нём пустой** — без него свободные переводы вернут «Ключ API не задан».

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

### Жалобы на задания

Кнопка «Пожаловаться на задание» на экране результата. Жалоба — заметка себе о том, что задание кривое, и попадает в JSONL: `ComplaintStore` дописывает строку в `complaints.jsonl` в каталоге приложения на внешней памяти (`/sdcard/Android/data/com.montelearn/files/`). Оттуда её забирает `./gradlew pullComplaints` — root и разрешения не нужны, сети тоже: жалоба нужна ровно тогда, когда задание сломано, в том числе офлайн.

Ключ записи — `exerciseId`: он уникален по курсу и не меняется между версиями, поэтому по нему сразу находится строка в файле урока.

Главное поле — **категория** (`ComplaintReason`), а не свободный текст: она говорит, что чинить. `verdict_wrong` — системный промпт в `HaikuChecker.kt`; `reference_wrong`, `ambiguous`, `typo` — JSON урока; `audio_unclear` — само задание. Коды категорий уходят в файл и не должны меняться, иначе старые жалобы не сгруппируются с новыми.

Жалоба заодно **откатывает карточку SRS** к состоянию до ответа (`AppViewModel.cardBeforeAnswer`): ответ на сломанное задание ничего не говорит о знаниях, а лапс возвращал бы карточку каждые 10 минут до самой починки урока.

Схема Room при этом не менялась — жалобы живут в файле, миграция не нужна.

### Речь

Локали «черногорский» в Android нет — используется `sr-RS` с латиницей (`Speech.kt`). `Speaker` (TTS) озвучивает `listening`-задания; `Listener` (SpeechRecognizer) распознаёт `speaking`. Качество произношения не оценивается: достаточно того, что движок распознал фразу. `Speaker` живёт в `MainActivity` и передаётся в `SessionScreen` параметром.

## Добавление уроков

1. Новый файл в `src/app/src/main/assets/lessons/`, строка в `index.json`, `versionCode` +1.
2. **`id` заданий уникальны по всему курсу и не меняются между версиями** — иначе теряется история повторений. Схема: `l09e01`.
3. Форматы всех семи типов заданий — в `src/README.md`; канонические типы — в `data/Model.kt`.

При добавлении нового типа задания нужно тронуть пять мест: `Exercise` в `Model.kt`, `referenceAnswer`, `typeName`, `submitText` в `AppViewModel.kt` и рендер в `SessionScreen.kt`.

## Оформление

Тёмная тема, серифные заголовки, золотой акцент — `ui/Theme.kt`. Цвета берутся оттуда (`Ink`, `Paper`, `Gold`, `Crimson`…), не хардкодятся по месту.
