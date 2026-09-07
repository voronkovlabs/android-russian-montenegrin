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
FRAMES = [
    {'case': 'n', 'pattern': 'Ovo je ___.'},
    {'case': 'g', 'pattern': 'Nema ___.'},
    {'case': 'd', 'pattern': 'Idem ka ___.'},
    {'case': 'a', 'pattern': 'Vidim ___.'},
    {'case': 'l', 'pattern': 'Mislim o ___.'},
    {'case': 'i', 'pattern': 'Idem sa ___.'},
]

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
    if pos == 'NOUN':
        wanted = {msd: msd[4] for _, msd, _, _ in rows
                  if len(msd) > 4 and msd[0] == 'N' and msd[4] in CASES}
    elif pos == 'VERB':
        wanted = {msd: msd for _, msd, _, _ in rows if msd in VERB_SLOTS}
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


def odd_forms(lemma, forms):
    """
    Формы, у которых поменялась основа, — единственные, что учат по отдельности.

    Основа считается по самим формам, а не отрубанием гласной у леммы: лемма
    глагола — инфинитив, и `pričam` не начинается с `pričat`, отчего «неожиданной»
    оказывалась каждая глагольная форма. Берём самый длинный префикс, общий хотя
    бы для большинства форм: у `ići` это `id` (idem, ideš, ide…), и `išao`
    справедливо остаётся исключением.

    Сравниваются свёрнутые написания, иначе второй рефлекс (`bes` → `bijes`)
    выглядел бы чередованием, и мы бы выпустили карточку, учащую одну норму
    как исключение из другой.
    """
    flat = [reflex(f['f']) for f in forms]
    if len(flat) < 2:
        return []
    need = max(2, int(len(flat) * 0.6))
    stem = ''
    for size in range(min(len(x) for x in flat), 1, -1):
        counts = {}
        for word in flat:
            counts[word[:size]] = counts.get(word[:size], 0) + 1
        top, hits = max(counts.items(), key=lambda kv: kv[1])
        if hits >= need:
            stem = top
            break
    if not stem:
        return []
    # Словарная форма исключением быть не может: она и есть то, от чего
    # отличаются остальные. У `pas` чередование настоящее (psa, psi), но учить
    # надо косвенные формы, а не сам именительный падеж.
    out = []
    for f, flatted in zip(forms, flat):
        if flatted.startswith(stem) or f['f'] == lemma or f['f'] in out:
            continue
        out.append(f['f'])
    return out


def main(lexpath):
    rows = words()
    print('слов: %d' % len(rows))

    print('читаю парадигмы…')
    par = vocab.read_paradigms(lexpath, {r['lemma'] for r in rows})

    print('читаю пул…')
    sentences = vocab.pool()

    forms_of = {}
    for r in rows:
        r['forms'] = pick(r['lemma'], par.get(r['lemma'], []), r['pos'])
        r['odd'] = odd_forms(r['lemma'], r['forms'])
        for f in r['forms']:
            forms_of.setdefault(f['f'], set()).add(r['lemma'])

    examples = defaultdict(list)
    for level, sr, ru, sr_id in sentences:
        for token in {m.group(0).lower() for m in vocab.WORD.finditer(sr)}:
            for lemma in forms_of.get(token, ()):
                if len(examples[lemma]) < MAX_EXAMPLES:
                    examples[lemma].append({'sr': sr, 'ru': ru, 'f': token,
                                            'id': sr_id, 'level': level})

    out = {
        'version': 1,
        'frames': FRAMES,
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
