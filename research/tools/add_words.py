# -*- coding: utf-8 -*-
"""
Дописать в словарь проверенные слова — без пересборки, старое байт в байт.

## Зачем

Порция пропавших частых слов (`vocab-batch1.tsv`, черновик переводов —
`find_missing.py`) проверена Катей на странице с отметками 09.10.2026. Сборка
словаря (`build_vocab.py`) их взять не может: перевода у них нет ни в одном
источнике, поэтому дописываются они отдельным шагом, как звательный падеж
(`add_vocative.py`).

## Что делает

* **Ключ экавский** (решение Кати: на случай сербской версии), иекавское
  написание — парой в `Ijekavica.kt`, это код и едет своим коммитом.
* **`se`** — глагол живёт только с ним; приложение показывает его целиком.
* **`n`** — хвостовой номер (3502…): по нему ищется полоса форм. Старые номера
  не трогаются никогда.
* **`o`** — место в порядке ввода по частоте субтитров: дробное число между
  номерами старых слов. Без него `biti` пришёл бы после всех 3501 слова.
* **Формы** — из srLex тем же отбором, что у сборки (`build_vocab.pick`), и
  звательный — `add_vocative.apply`.
* Примеров нет: пул предложений сербский, и без вычитки экавицы
  (`ijekavise_examples.py`) их брать нельзя.

Перед записью проверяет, что ни одна старая лемма не пропала.

## Запуск

    PYTHONUTF8=1 python research/tools/add_words.py <srLex.gz> <каталог отметок>
"""
import glob
import gzip
import io
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import vocab
import add_vocative
from build_vocab import pick, odd_forms, reflex
from split_forms import BAND, ASSETS

DATA = vocab.DATA
POS = {'глаг.': 'VERB', 'сущ.': 'NOUN', 'прил.': 'ADJ', 'нареч.': 'ADV'}

# Переводы, которых не оказалось у слов, возвращённых при проверке пустыми.
FILL = {
    'veličanstvo': 'величество',
    'curiti': 'течь, капать',
}

# Где ключ словаря расходится с леммой srLex: парадигма лежит под её леммой.
SRC_LEMMA = {'đavo': 'đavol', 'pravi': 'prav', 'ceo': 'cijel', 'trudna': 'trudan',
             'mobilni': 'mobilan', 'takođe': 'također'}


def batch(marks_dir):
    rows = [l.rstrip('\n').split('\t') for l in
            io.open(os.path.join(DATA, 'vocab-batch1.tsv'), encoding='utf-8')][1:]
    marks = {}
    for p in glob.glob(os.path.join(marks_dir, '*.json')):
        d = json.load(io.open(p, encoding='utf-8'))
        d = d.get('data', d)
        marks[int(d['rank'])] = d
    out, skipped = [], []
    for r in rows:
        r += [''] * (7 - len(r))
        rank, key, show, pos, ru, verdict = int(r[0]), r[1], r[2], r[3], r[4], r[5]
        m = marks.get(rank)
        keep = (m['verdict'] == 'keep') if m else verdict == '+'
        if not keep:
            continue
        gloss = (m and m.get('ru')) or ru or FILL.get(key, '')
        if not gloss:
            skipped.append(key)
            continue
        out.append({'rank': rank, 'id': key, 'pos': POS[pos], 'gloss': gloss,
                    'se': show.endswith(' se')})
    return out, skipped


def subtitle_ranks(lexpath, lemmas):
    """Ранг по `sr_50k` для лемм — той же раскладкой, что в `find_missing`."""
    rank = {}
    for n, line in enumerate(io.open(os.path.join(DATA, 'sr_50k.txt'), encoding='utf-8'), 1):
        f = line.split()
        if len(f) == 2:
            rank.setdefault(f[0], n)
    top = {}
    for line in gzip.open(lexpath, 'rt', encoding='utf-8'):
        p = line.rstrip('\n').split('\t')
        if len(p) < 8:
            continue
        form = p[0].lower()
        if form in rank:
            freq = int(p[6])
            if form not in top or freq > top[form][0]:
                top[form] = (freq, vocab.LEMMA_FIX.get(p[1], p[1]))
    best = {}
    for form, (_, lemma) in top.items():
        if lemma in lemmas:
            best[lemma] = min(best.get(lemma, 10 ** 9), rank[form])
    return best


def main(lexpath, marks_dir):
    path = os.path.join(ASSETS, 'words.json')
    data = json.load(io.open(path, encoding='utf-8'))
    old = data['words']
    old_ids = [w['id'] for w in old]
    have = set(old_ids)

    new, skipped = batch(marks_dir)
    new = [w for w in new if w['id'] not in have]
    print('к добавлению: %d; пропущено без перевода: %s' % (len(new), ', '.join(skipped) or '—'))

    # Место в порядке ввода: после стольких старых слов, сколько их частотнее.
    ranks = subtitle_ranks(lexpath, have)
    old_ranks = sorted(ranks.values())
    import bisect
    for i, w in enumerate(sorted(new, key=lambda x: x['rank'])):
        c = bisect.bisect_right(old_ranks, w['rank'])
        w['o'] = round(c + 0.5 + i * 1e-4, 4)

    # Парадигмы.
    src = {w['id']: SRC_LEMMA.get(w['id'], w['id']) for w in new}
    par = vocab.read_paradigms(lexpath, set(src.values()))
    start = max(w['n'] for w in old) + 1
    bands = {}
    entries = []
    for n, w in enumerate(sorted(new, key=lambda x: x['rank']), start):
        forms = pick(w['id'], par.get(src[w['id']], []), w['pos'])
        odd = odd_forms(w['id'], w['pos'], forms)
        entry = {'id': w['id'], 'n': n, 'pos': w['pos'], 'gloss': w['gloss'],
                 'nf': len(forms), 'o': w['o']}
        if odd:
            entry['odd'] = odd
        if w['se']:
            entry['se'] = True
        entries.append(entry)
        if forms:
            bands.setdefault((n - 1) // BAND, {})[w['id']] = [
                {'f': f['f'], 's': f['slot']} for f in forms]

    data['words'] = old + entries
    after = [w['id'] for w in data['words']]
    assert after[:len(old_ids)] == old_ids, 'старые слова сдвинулись'
    assert len(set(after)) == len(after), 'дубли ключей'

    io.open(path, 'w', encoding='utf-8', newline='').write(
        json.dumps(data, ensure_ascii=False, separators=(',', ':')))
    for band, forms in bands.items():
        bp = os.path.join(ASSETS, 'forms-%d.json' % band)
        body = json.load(io.open(bp, encoding='utf-8')) if os.path.exists(bp) else {'forms': {}}
        body['forms'].update(forms)
        io.open(bp, 'w', encoding='utf-8', newline='').write(
            json.dumps(body, ensure_ascii=False, separators=(',', ':')))
    print('слов было %d, стало %d; с формами %d' % (len(old), len(data['words']),
                                                     sum(len(f) for f in bands.values())))
    add_vocative.apply(lexpath, ASSETS)


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
