# -*- coding: utf-8 -*-
"""
Разделить словарь: слова отдельно, парадигмы форм отдельно.

## Зачем

Разбор `words.json` — самая дорогая работа приложения при запуске: по десяти
отчётам диагностики 1,5–7,8 секунды, в среднем около четырёх. А с 1.92 стало
известно, что почти каждый запуск **холодный**: телефон выгружает приложение из
памяти по нескольку раз в неделю, и словарь разбирается заново каждый раз.

Формы — **73% веса файла** (889 КБ из 1212). Нужны они при этом только тогда,
когда строится карточка склонения или особой формы, то есть для одного слова, а
не для всех 3501.

## Как разложено

* `words.json` — всё, кроме форм, плюс `nf`: сколько форм у слова. Одного этого
  числа хватает отбору, чтобы знать, можно ли завести карточку склонения, — а
  ради него и приходилось держать в памяти все парадигмы;
* `forms-0.json` … `forms-6.json` — сами формы, полосами по 500 слов в порядке
  частоты. Полоса весит около 130 КБ.

**Полосами, а не одним файлом**, потому что слова вводятся по частоте: пул
незаученных — двести слов, и до третьей тысячи ученик дойдёт не скоро. На деле
читается полоса-другая вместо 889 КБ.

Полоса считается по `n` — рангу слова, который плотно идёт от 1 до 3501 и
совпадает с позицией в файле.

## Запуск

    python research/tools/split_forms.py

Идемпотентно: файл уже разделён — скрипт это увидит и ничего не сделает.
Тот же раскладкой пишет и `build_vocab.py`, чтобы пересборка не вернула
толстый файл молча.
"""
import io
import json
import os
import sys

BAND = 500
ASSETS = os.path.join(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
    'src', 'app', 'src', 'main', 'assets', 'vocab')


def split(words):
    """Из списка слов сделать (слова без форм, {полоса: {лемма: формы}})."""
    bands = {}
    slim = []
    for w in words:
        forms = w.get('forms') or []
        if forms:
            bands.setdefault((w['n'] - 1) // BAND, {})[w['id']] = forms
        lean = dict(w)
        lean.pop('forms', None)
        # Число форм остаётся в слове: по нему отбор понимает, можно ли завести
        # карточку склонения, не читая парадигм вовсе.
        lean['nf'] = len(forms)
        slim.append(lean)
    return slim, bands


def write(slim, bands, where=ASSETS):
    body = {'version': 1, 'words': slim}
    src = os.path.join(where, 'words.json')
    old = json.load(io.open(src, encoding='utf-8'))
    for key in ('frames', 'slots'):
        if key in old:
            body[key] = old[key]
    # Порядок ключей тот же, что был: version, frames, slots, words.
    ordered = {k: body[k] for k in ('version', 'frames', 'slots', 'words') if k in body}
    io.open(src, 'w', encoding='utf-8', newline='').write(
        json.dumps(ordered, ensure_ascii=False, separators=(',', ':')))
    for band, forms in sorted(bands.items()):
        io.open(os.path.join(where, 'forms-%d.json' % band), 'w',
                encoding='utf-8', newline='').write(
            json.dumps({'forms': forms}, ensure_ascii=False, separators=(',', ':')))
    return len(bands)


def main():
    src = os.path.join(ASSETS, 'words.json')
    data = json.load(io.open(src, encoding='utf-8'))
    words = data['words']
    if not any(w.get('forms') for w in words):
        print('уже разделено: форм в words.json нет')
        return 0
    before = os.path.getsize(src)
    slim, bands = split(words)
    n = write(slim, bands)
    after = os.path.getsize(src)
    print('words.json: %d КБ -> %d КБ' % (before / 1024, after / 1024))
    print('полос форм: %d' % n)
    for band in sorted(bands):
        size = os.path.getsize(os.path.join(ASSETS, 'forms-%d.json' % band))
        print('  forms-%d.json — %d слов, %d КБ' % (band, len(bands[band]), size / 1024))
    return 0


if __name__ == '__main__':
    sys.exit(main())
