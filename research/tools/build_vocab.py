"""Сборка словарных карточек: из отобранных слов — данные для приложения.

На выходе `assets/vocab/words.json`: по каждому слову перевод, живые формы с
пометкой падежа и примеры из пула. Три вида карточек приложение строит из этого
само — см. `kartochki.md`, часть VIII:

* **значение** — одна на слово;
* **образец склонения** — одна на слово, форма меняется от повторения к
  повторению: проверяется правило, а оно одно;
* **неожиданная форма** — по одной на каждую, где основа меняется
  (`apoteka` → `apoteci`, `pas` → `psa`). У трёх четвертей слов таких нет.

    PYTHONUTF8=1 python research/tools/build_vocab.py /tmp/srlex.gz

Карточка на каждый падеж была бы неверна дважды: половина падежей — это одно и
то же написание (пятнадцать строк парадигмы дают семь разных слов), а
остальное русский выводит сам, падежная система у него та же.
"""
import io
import json
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import vocab

ROOT = vocab.ROOT
DATA = vocab.DATA
OUT_DIR = os.path.join(ROOT, 'src', 'app', 'src', 'main', 'assets', 'vocab')

SRC = os.path.join(DATA, 'vocab-words.tsv')

# Сколько примеров держим на слово: больше в карточку всё равно не влезет,
# а файл растёт на каждом.
MAX_EXAMPLES = 2

# Падежи, по которым спрашиваем форму. Звательного тут нет, и вычёркивать его
# руками не пришлось: у него нулевая частота по корпусу, а нулевые формы
# отсеиваются сами.
CASES = {'n': 'им.', 'g': 'род.', 'd': 'дат.', 'a': 'вин.', 'l': 'мест.', 'i': 'тв.'}

# Рамки, в которые подставляется слово, когда живого предложения нет.
# Пул — 5987 фраз, и косвенная форма кандидата встречается в нём меньше чем у
# трети слов. Падеж задаёт рамка, форму даёт srLex, покрытие становится полным.
# Цена честная: «Vidim pakao» звучит странновато, но упражнение про форму.
CASE_FRAMES = {
    'n': 'Ovo je ___.',
    'g': 'Nema ___.',
    'd': 'Idem ka ___.',
    'a': 'Vidim ___.',
    'l': 'Mislim o ___.',
    'i': 'Idem sa ___.',
}

# У глагола рамка заодно учит связке с местоимением — это ровно то, что в
# сербском обычно и произносят вместе.
VERB_FRAMES = {
    'Vmn': 'Moram ___.',
    'Vmr1s': 'Ja ___.',
    'Vmr2s': 'Ti ___.',
    'Vmr3s': 'On ___.',
    'Vmr1p': 'Mi ___.',
    'Vmr3p': 'Oni ___.',
    'Vmp-sm': 'On je ___.',
    'Vmp-sf': 'Ona je ___.',
}

# Глагольные ячейки: инфинитив, настоящее и причастие прошедшего. Полная
# парадигма — сотня форм, и учить её списком незачем.
VERB_SLOTS = {
    'Vmn': 'инфинитив',
    'Vmr1s': 'я',
    'Vmr2s': 'ты',
    'Vmr3s': 'он',
    'Vmr1p': 'мы',
    'Vmr3p': 'они',
    'Vmp-sm': 'он (прош.)',
    'Vmp-sf': 'она (прош.)',
}

SPECIAL = [
    ('sjutra', 'sutra'), ('śutra', 'sutra'), ('ovđe', 'ovde'), ('onđe', 'onde'),
    ('đe', 'gde'), ('śever', 'sever'), ('iđem', 'idem'),
]


def reflex(word):
    """Свёртка иекавицы и экавицы — та же, что в приложении (`LocalCheck`)."""
    s = word.lower()
    for a, b in SPECIAL:
        s = s.replace(a, b)
    return s.replace('ije', 'e').replace('je', 'e')


def words():
    rows = []
    with io.open(SRC, encoding='utf-8') as f:
        head = next(f).rstrip('\n').split('\t')
        i = {name: n for n, name in enumerate(head)}
        for line in f:
            p = line.rstrip('\n').split('\t')
            if len(p) < len(head):
                continue
            rows.append({
                'order': int(p[i['order']]),
                'lemma': p[i['lemma']],
                'pos': p[i['pos']],
                'gloss': p[i['gloss']],
                'doubt': p[i['doubt']],
            })
    return rows


def pick(lemma, rows, pos):
    """
    Живые формы слова: по одной на ячейку, без мёртвых и без двойников.

    Три отсева подряд:

    1. **нулевая частота** — форма есть в таблице, но в языке не встречается;
    2. **двойники по рефлексу** — srLex сербский и держит `bes` и `bijes` в
       одной парадигме. Это не две формы, а две нормы одного слова; берём
       иекавскую, курс черногорский;
    3. **ячейки не по части речи** — существительному падежи, глаголу
       несколько ключевых форм, прилагательному ничего: его склонение — общий
       образец, а не свойство слова.
    """
    # Именительный единственного у существительного и инфинитив у глагола —
    # это сама словарная форма. Спрашивать её бессмысленно: лемма стоит в
    # задании подсказкой, и ответ виден прямо в вопросе.
    if pos == 'NOUN':
        wanted = {msd: msd[4] for _, msd, _, _ in rows
                  if len(msd) > 4 and msd[0] == 'N' and msd[4] in CASES
                  and not (msd[4] == 'n' and msd[3] == 's')}
    elif pos == 'VERB':
        wanted = {msd: msd for _, msd, _, _ in rows
                  if msd in VERB_SLOTS and msd != 'Vmn'}
    else:
        return []

    best = {}
    for form, msd, feat, freq in rows:
        if msd not in wanted or freq <= 0:
            continue
        # Ключ — ячейка плюс свёрнутое написание: две нормы одного слова
        # в одной ячейке должны схлопнуться в одну форму.
        key = (msd, reflex(form))
        prev = best.get(key)
        prefers = 'ije' in form or 'je' in form
        if prev is None or (prefers and not prev[2]) or (
                prefers == prev[2] and freq > prev[1]):
            best[key] = (form, freq, prefers, msd)

    # На ячейку — одна форма, самая частая из уцелевших.
    by_slot = {}
    for form, freq, _, msd in best.values():
        if msd not in by_slot or freq > by_slot[msd][1]:
            by_slot[msd] = (form, freq)

    out = []
    for msd, (form, freq) in by_slot.items():
        if pos == 'NOUN':
            slot = '%s-%s' % (msd[4], msd[3])          # падеж и число
            label = '%s %s' % (CASES[msd[4]], 'мн.' if msd[3] == 'p' else 'ед.')
        else:
            slot, label = msd, VERB_SLOTS[msd]
        out.append({'f': form, 'slot': slot, 'label': label, 'freq': freq})
    out.sort(key=lambda x: -x['freq'])
    return out


def stem_of(lemma, pos):
    """
    Основа слова: то, что не меняется при словоизменении.

    У существительного отрезается конечная гласная (`apoteka` → `apotek`), у
    глагола — инфинитивное окончание и тематическая гласная (`tražiti` →
    `traži` → `traž`). Без второго шага «traže» выглядело бы чередованием: `i`
    и `e` тут спряжение, а не смена основы.
    """
    s = lemma
    if pos == 'VERB':
        if s.endswith('ti') or s.endswith('ći'):
            s = s[:-2]
        if s and s[-1] in 'aeiou':
            s = s[:-1]
    elif s and s[-1] in 'aeiou':
        s = s[:-1]
    return s


def odd_forms(lemma, pos, forms):
    """
    Формы, у которых поменялась основа, — единственные, что учат по отдельности.

    Форма считается особой, если расходится с основой раньше, чем основа
    кончилась: `apoteka` → `apoteci` (k → c), `otac` → `oca` (беглое «а»).
    Обычное окончание, приросшее к целой основе, особым не считается.

    Свёртки иекавицы тут нет намеренно, хотя в первой версии была. Правило
    `je` → `e` бьёт по окончанию не хуже, чем по корню: `prijatelje` после
    свёртки перестаёт начинаться с `prijatelj`, и совершенно правильная форма
    винительного падежа объявлялась чередованием. Две нормы одного слова
    схлопываются раньше, в [pick], и до этого места не доходят.
    """
    stem = stem_of(lemma, pos)
    if not stem:
        return []
    out = []
    for f in forms:
        word = f['f']
        if word == lemma or word in out:
            continue
        common = 0
        while common < len(word) and common < len(stem) and word[common] == stem[common]:
            common += 1
        if common < len(stem):
            out.append(word)

    # Чередование — один факт, а не столько фактов, сколько форм. У `pas`
    # основа меняется во всех косвенных (psa, psi, psu, psima), и шесть
    # карточек учили бы одному и тому же беглому «а». Когда особой оказалась
    # большая часть парадигмы, хватает одной формы — самой частой; когда
    # выбивается одна-две, они и есть исключения (`apoteka` → `apoteci`).
    if len(out) * 2 > len(forms):
        return out[:1]
    return out[:2]


# Толкования, где автоматический источник выбрал **не то значение**.
#
# Это не «улучшенные переводы», а починка редкого, но противного случая:
# Викисловарь даёт у слова одно значение, и оно не то, которым слово живёт.
# `vrata` там «ворота» — верно и почти бесполезно, потому что в Черногории это
# прежде всего дверь, и словарь подсказок самого курса переводит его именно
# так.
#
# Список **ручной и должен расти от жалоб**, а не от догадок: выводить
# «главное значение» правилом не из чего, а подменять толкования оптом по
# глоссарию курса нельзя — там попадаются заметки к форме («sam — есть, от
# biti»), и они затёрли бы настоящие статьи.
GLOSS_FIX = {
    'vrata': 'дверь',
    'ključ': 'ключ',
    'krov': 'крыша',
    'kola': 'машина, автомобиль',
    'posao': 'работа, дело',
    'bilo': 'было',
}


def previous():
    """
    Прежний `words.json`, если он есть: словарь обязан только расти.

    Без этого пересборка **теряет слова**, и потеря молчаливая. Отбор
    (`vocab.py`) выбрасывает всё, что курс уже показывал, а курс растёт: когда
    словарь собирали, уроков было тридцать четыре, сейчас шестьдесят. При
    пересборке 13.09.2026 из списка выпали 88 лемм — `samo`, `možda`,
    `pričati`, `gledati`, — попавших тем временем в новые уроки.

    Цена такой потери не «стало на 88 карточек меньше». **Лемма — ключ
    карточки**: исчезло слово — исчез весь прогресс по нему, десять встреч,
    интервалы, счёт ошибок. Восстановить это неоткуда.

    Поэтому правило жёсткое: **лемма, однажды попавшая в словарь, остаётся в
    нём навсегда**, даже если сегодняшний отбор её бы не выбрал. Отбор решает,
    что добавить, а не что выбросить.
    """
    path = os.path.join(OUT_DIR, 'words.json')
    if not os.path.exists(path):
        return {}
    with io.open(path, encoding='utf-8') as f:
        return {w['id']: w for w in json.load(f).get('words', [])}


def main(lexpath):
    rows = words()
    было = previous()

    # Толкования уже живущих слов не трогаем. Они прошли через жалобы и
    # правились руками: «proliv» это «понос», а не «пролив», и пересборка
    # вернула бы словарную статью вместо починки. Новые слова берут
    # толкование из конвейера, старые — то, с которым их учат.
    kept = 0
    for r in rows:
        old = было.get(r['lemma'])
        if old and old.get('gloss') and old['gloss'] != r['gloss']:
            r['gloss'] = old['gloss']
            kept += 1
    if kept:
        print('сохранено прежних толкований: %d' % kept)

    # Ручные толкования — последними: они старше любого источника и вычитки.
    for r in rows:
        fixed = GLOSS_FIX.get(r['lemma'])
        if fixed:
            r['gloss'] = fixed

    # Всё, что было в словаре, но сегодняшний отбор не выбрал, — возвращаем.
    have = {r['lemma'] for r in rows}
    back = [w for lemma, w in было.items() if lemma not in have]
    for w in back:
        rows.append({
            'lemma': w['id'],
            'pos': w.get('pos', ''),
            'gloss': w.get('gloss', ''),
            'doubt': w.get('doubt', ''),
        })
    if back:
        print('возвращено в словарь (курс успел их показать): %d' % len(back))

    print('слов: %d' % len(rows))

    print('читаю парадигмы…')
    par = vocab.read_paradigms(lexpath, {r['lemma'] for r in rows})

    print('читаю пул…')
    sentences = vocab.pool()

    forms_of = {}
    for r in rows:
        r['forms'] = pick(r['lemma'], par.get(r['lemma'], []), r['pos'])
        r['odd'] = odd_forms(r['lemma'], r['pos'], r['forms'])
        for f in r['forms']:
            forms_of.setdefault(f['f'], set()).add(r['lemma'])

    examples = defaultdict(list)
    for level, sr, ru, sr_id in sentences:
        for token in {m.group(0).lower() for m in vocab.WORD.finditer(sr)}:
            for lemma in forms_of.get(token, ()):
                if len(examples[lemma]) < MAX_EXAMPLES:
                    examples[lemma].append({'sr': sr, 'ru': ru, 'f': token,
                                            'id': sr_id, 'level': level})

    # Рамки плоской картой «ячейка → образец»: приложению не надо ничего
    # выводить из кода ячейки, а множественное число берёт ту же рамку, что
    # единственное, — падеж в ней задан предлогом и глаголом, а не числом.
    frames = dict(VERB_FRAMES)
    for case, pattern in CASE_FRAMES.items():
        frames['%s-s' % case] = pattern
        frames['%s-p' % case] = pattern

    out = {
        'version': 1,
        'frames': frames,
        # Подписи ячеек — словарём на весь файл: при каждой форме они занимали
        # бы больше места, чем сами формы.
        'slots': dict(
            [('%s-s' % c, '%s ед.' % name) for c, name in CASES.items()] +
            [('%s-p' % c, '%s мн.' % name) for c, name in CASES.items()] +
            list(VERB_SLOTS.items())
        ),
        'words': [],
    }
    for n, r in enumerate(rows, 1):
        entry = {
            'id': r['lemma'],
            'n': n,
            'pos': r['pos'],
            'gloss': r['gloss'],
            'forms': [{'f': f['f'], 's': f['slot']} for f in r['forms']],
        }
        if r['odd']:
            entry['odd'] = r['odd']
        if examples.get(r['lemma']):
            entry['ex'] = examples[r['lemma']]
        if r['doubt']:
            entry['doubt'] = r['doubt']
        out['words'].append(entry)

    if not os.path.isdir(OUT_DIR):
        os.makedirs(OUT_DIR)
    path = os.path.join(OUT_DIR, 'words.json')
    with io.open(path, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(out, f, ensure_ascii=False, separators=(',', ':'))

    # --- сводка ---
    with_forms = [r for r in rows if r['forms']]
    with_odd = [r for r in rows if r['odd']]
    with_ex = [r for r in rows if examples.get(r['lemma'])]
    cards = len(rows) + len(with_forms) + sum(len(r['odd']) for r in rows)
    print()
    print('форм на слово (у кого они есть): %.1f'
          % (sum(len(r['forms']) for r in with_forms) / max(1, len(with_forms))))
    print('слов с формами: %d, с неожиданной формой: %d, с примером: %d'
          % (len(with_forms), len(with_odd), len(with_ex)))
    print('карточек всего: %d (значение %d + склонение %d + форма %d)'
          % (cards, len(rows), len(with_forms), sum(len(r['odd']) for r in rows)))
    print('размер: %.0f КБ' % (os.path.getsize(path) / 1024.0))
    print('записано: %s' % path)


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else '/tmp/srlex.gz')
