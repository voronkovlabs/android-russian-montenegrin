# -*- coding: utf-8 -*-
"""
Пропавшие частые слова и экавские леммы без пары — для пополнения словаря.

## Зачем

Катя, 09.10.2026: в словаре нет `kafa`, `dijete`, `kupatilo`, `tetka`… Слова
не редкие — `dete` в частотном списке субтитров 418-е, `kafu` 1218-е, — а
выпали потому, что ни в одной из двух выгрузок Викисловаря не нашлось
перевода, и сборка словаря (`build_vocab.py`) слово без перевода выбрасывает.
Значит, дыра в переводах, а не в отборе, и таких слов сотни.

## Что делает

1. **Пропавшие.** Идёт по `sr_50k` (частоты по субтитрам — те же, по которым
   собран словарь), сводит словоформы к леммам по srLex, оставляет
   знаменательные (сущ., глаг., прил., нареч.) и выкидывает то, что в словаре
   уже есть — с учётом свёртки иекавицы, чтобы `mlijeko` не числилось
   пропавшим при `mleko` в словаре. Написание предлагается иекавское, если
   srLex его знает. Переводы подтягиваются из готового: подсказки к урокам
   (`glossary.json`) и Катины списки слов к урокам. Остальное — пусто, это
   работа для проверки человеком.
2. **Экавские без пары.** Слова словаря, у которых в srLex есть иекавская
   лемма с той же свёрткой, а в `Ijekavica.kt` пары нет: приложение до сих пор
   показывает их по-сербски.

Ничего не пишет в `assets` — только два файла в `research/data/`.

## Запуск

    PYTHONUTF8=1 python research/tools/find_missing.py /путь/к/srLex_v1.3.gz
"""
import glob
import gzip
import io
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from vocab import LEMMA_FIX
from build_vocab import reflex

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..')
DATA = os.path.join(ROOT, 'research', 'data')
ASSETS = os.path.join(ROOT, 'src', 'app', 'src', 'main', 'assets')
IJEK = os.path.join(ROOT, 'src', 'app', 'src', 'main', 'java', 'com', 'crnogorski',
                    'trener', 'data', 'Ijekavica.kt')
KATYA = os.path.join(ROOT, '..', 'MonteLang-katya', 'src', 'app', 'src', 'main',
                     'assets', 'lessons')

CONTENT = {'NOUN', 'VERB', 'ADJ', 'ADV'}
POS_RU = {'NOUN': 'сущ.', 'VERB': 'глаг.', 'ADJ': 'прил.', 'ADV': 'нареч.'}


def jat(word):
    """Есть ли в слове след ятя — то, что свёртка снимает."""
    return reflex(word) != word.lower()


def main(lexpath):
    words = json.load(io.open(os.path.join(ASSETS, 'vocab', 'words.json'), encoding='utf-8'))['words']
    ids = {w['id'] for w in words}
    pairs = dict(re.findall(r'"([^"]+)" to "([^"]+)"', io.open(IJEK, encoding='utf-8').read()))
    have = {reflex(i) for i in ids} | {reflex(v) for v in pairs.values()}

    glossary = json.load(io.open(os.path.join(ASSETS, 'glossary.json'), encoding='utf-8'))['me']
    katya = {}
    for p in glob.glob(os.path.join(KATYA, '*.json')):
        d = json.load(io.open(p, encoding='utf-8'))
        for w in (d.get('words', []) if isinstance(d, dict) else []):
            if isinstance(w, dict) and w.get('me'):
                katya[w['me'].lower()] = w.get('ru', '')

    rank = {}
    count = {}
    for n, line in enumerate(io.open(os.path.join(DATA, 'sr_50k.txt'), encoding='utf-8'), 1):
        f = line.split()
        if len(f) == 2:
            rank.setdefault(f[0], n)
            count.setdefault(f[0], int(f[1]))

    # Каждая словоформа — только своей самой частой лемме (по частоте строки
    # srLex). Иначе «tako» дало бы лемму «taka», «mene» — «mena», а «dana» —
    # «dno»: омонимы забили бы список. Вспомогательные глаголы считаются
    # знаменательными: «biti» и «hteti» srLex пишет AUX, и сборка словаря
    # выкинула их вместе с предлогами.
    top = {}             # форма → (частота строки, лемма, часть речи)
    lemmas = {}          # все леммы srLex с частотой — для поиска иекавских пар
    for line in gzip.open(lexpath, 'rt', encoding='utf-8'):
        p = line.rstrip('\n').split('\t')
        if len(p) < 8:
            continue
        form, lemma, msd, upos = p[0].lower(), p[1], p[2], p[4]
        freq = int(p[6])
        if upos == 'AUX':
            upos = 'VERB'
        if upos in CONTENT and lemma[:1].islower() and not msd.startswith('Np'):
            lemma = LEMMA_FIX.get(lemma, lemma)
            lemmas[lemma] = lemmas.get(lemma, 0) + freq
        if form in rank and (form not in top or freq > top[form][0]):
            top[form] = (freq, LEMMA_FIX.get(lemma, lemma), upos, msd)

    best = {}
    for form, (freq, lemma, upos, msd) in top.items():
        if upos not in CONTENT or not lemma[:1].islower() or msd.startswith('Np'):
            continue
        r = rank[form]
        b = best.setdefault(lemma, {'rank': r, 'count': 0, 'pos': upos, 'form': form})
        if r < b['rank']:
            b['rank'], b['form'] = r, form
        b['count'] += count[form]

    # Иекавское написание для свёрнутого ключа — самое частое из иекавских.
    ijek = {}
    for lemma, freq in lemmas.items():
        if jat(lemma):
            k = reflex(lemma)
            if k not in ijek or freq > lemmas[ijek[k]]:
                ijek[k] = lemma

    # Экавское написание для свёрнутого ключа — самое частое из экавских.
    # Ключ словаря экавский (решение Кати, 09.10.2026: на случай сербской
    # версии), иекавица идёт отдельным списком, как у старых слов.
    ekav = {}
    for lemma, freq in lemmas.items():
        if not jat(lemma):
            k = reflex(lemma)
            if k not in ekav or freq > lemmas[ekav[k]]:
                ekav[k] = lemma

    # --- 1. Пропавшие
    seen = set()
    out = []
    for lemma, b in sorted(best.items(), key=lambda kv: kv[1]['rank']):
        key = reflex(lemma)
        if key in have or key in seen:
            continue
        seen.add(key)
        shown = ijek.get(key, lemma)
        base = ekav.get(key, lemma)
        ru = (glossary.get(shown) or glossary.get(lemma) or glossary.get(base)
              or katya.get(shown) or katya.get(lemma) or katya.get(base) or '')
        out.append((b['rank'], base, shown, POS_RU[b['pos']], b['form'], b['count'], ru))

    path = os.path.join(DATA, 'vocab-missing.tsv')
    with io.open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write('rank\tkey\tshow\tpos\tbest_form\tcount_50k\tru_ready\n')
        for row in out:
            f.write('\t'.join(str(x) for x in row) + '\n')

    # --- 2. Экавские слова словаря без пары
    eka = []
    for w in words:
        i = w['id']
        if i in pairs or jat(i):
            continue
        j = ijek.get(reflex(i))
        if j and j != i:
            eka.append((w['n'], i, j, lemmas.get(i, 0), lemmas.get(j, 0), w.get('gloss', '')[:40]))
    path2 = os.path.join(DATA, 'vocab-ekavian.tsv')
    with io.open(path2, 'w', encoding='utf-8', newline='\n') as f:
        f.write('n\tlemma\tijekavian\tfreq_lemma\tfreq_ijek\tgloss\n')
        for row in sorted(eka):
            f.write('\t'.join(str(x) for x in row) + '\n')

    floor = min(b['count'] for l, b in best.items() if l in ids) if ids else 0
    above = [r for r in out if r[5] >= 240]
    print('пропавших всего: %d, из них частотой не ниже хвоста словаря (240+): %d' % (len(out), len(above)))
    print('  с готовым переводом: %d' % sum(1 for r in above if r[6]))
    print('экавских без пары в Ijekavica.kt: %d' % len(eka))
    print(path)
    print(path2)


if __name__ == '__main__':
    main(sys.argv[1])
