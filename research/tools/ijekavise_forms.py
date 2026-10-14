# -*- coding: utf-8 -*-
"""
Формы словаря — иекавицей, где srLex знает иекавский двойник.

## Зачем

Жалобы 158 и 196 (Сергей, Катя): в таблице склонения заголовок `mjesto`, а
формы `mesta`, `mestu`, `mestom`. Курс иекавский, и таблица учила экавице.

Причина в сборке: srLex хранит иекавские формы в той же парадигме, что и
экавские (`mjesta` под леммой `mesto`), но с **нулевой частотой** — корпус
сербский. `build_vocab.pick` отбрасывает нулевую частоту раньше, чем выбирает
иекавский двойник, и правило «из двух норм берём иекавскую» до таких форм не
доходило вовсе.

## Что делает

Для каждой формы в полосах `forms-*.json` ищет в srLex у той же леммы форму с
той же разметкой (msd), которая после свёртки ятя совпадает с нашей и содержит
«je». Нашлась — заменяет. Слова, ключи и всё прочее не трогаются; формы, у
которых двойника нет (`vremena` — у короткого ятя он и не нужен), остаются.

    PYTHONUTF8=1 python research/tools/ijekavise_forms.py research/data/srLex_v1.3.gz
"""
import glob
import gzip
import io
import json
import os
import sys
from collections import defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import vocab
from build_vocab import reflex
from add_words import SRC_LEMMA
from split_forms import ASSETS


# Где srLex порождает неверный двойник. Проверено глазами по всем 155 словам
# (14.10.2026): ятя нет вовсе (`garderobijer`), хорватская норма вместо
# черногорской (`grješka`, `prijezir`), неверное чередование (`belježiti`
# вместо `bilježiti`, `sjedela` вместо `sjedjela`).
SKIP = {'garderober', 'greška', 'pogreška', 'prezir', 'beležiti', 'sedeti',
        # `pevac` у нас «певец» (pjevac), а srLex даёт `pijevac` — «петух»;
        # `povijest`, `vjerojatnost` — хорватская норма.
        'pevac', 'povest', 'verojatnost'}


def fits(slot, msd):
    """Та ли это ячейка: у глагола слот — сама разметка, у имени — «падеж-число»."""
    if slot.startswith('V'):
        return msd == slot
    case, _, num = slot.partition('-')
    return len(msd) > 4 and msd[0] in 'NA' and msd[4] == case and msd[3] == num


def main(lexpath, write=True):
    bands = sorted(glob.glob(os.path.join(ASSETS, 'forms-*.json')))
    data = {p: json.load(io.open(p, encoding='utf-8')) for p in bands}
    keys = {k for d in data.values() for k in d['forms']}
    src = {k: SRC_LEMMA.get(k, k) for k in keys}
    want = set(src.values())
    # лемма -> форма -> множество msd; и лемма -> msd -> формы
    by_form = defaultdict(lambda: defaultdict(set))
    by_msd = defaultdict(lambda: defaultdict(set))
    for line in gzip.open(lexpath, 'rt', encoding='utf-8'):
        p = line.rstrip('\n').split('\t')
        if len(p) < 8:
            continue
        lemma = vocab.LEMMA_FIX.get(p[1].lower(), p[1].lower())
        if lemma not in want:
            continue
        f = p[0].lower()
        by_form[lemma][f].add(p[2])
        by_msd[lemma][p[2]].add(f)
    changed, words = 0, set()
    sample = []
    for path, d in data.items():
        for key, forms in d['forms'].items():
            lemma = src[key]
            if key in SKIP:
                continue
            for cell in forms:
                f = cell['f']
                if 'je' in f:
                    continue
                # Двойник ищется строго в той же ячейке: у `vide` (они видят)
                # в аористе есть `vidje`, а в настоящем его нет и быть не должно.
                twins = {v for msd in by_form[lemma].get(f, ()) if fits(cell['s'], msd)
                         for v in by_msd[lemma][msd]
                         if v != f and 'je' in v and reflex(v) == reflex(f)}
                # Причастие на -eo в иекавице кончается на -io (`sjedio`), а srLex
                # порождает `sjedeo` — такой формы нет, лучше оставить экавскую.
                twins = {v for v in twins if not (f.endswith('eo') and v.endswith('eo'))}
                if len(twins) == 1:
                    v = twins.pop()
                    cell['f'] = v
                    changed += 1
                    words.add(key)
                    if True:
                        sample.append(f'{key}: {f} → {v}')
    for path, d in (data.items() if write else ()):
        io.open(path, 'w', encoding='utf-8', newline='').write(
            json.dumps(d, ensure_ascii=False, separators=(',', ':')))
    print('форм заменено: %d, слов: %d' % (changed, len(words)))
    print('\n'.join(sample))
    return words


if __name__ == '__main__':
    main(sys.argv[1])
