# -*- coding: utf-8 -*-
"""
Проверка историй перед выпуском: отрезок должен быть одним предложением.

## Зачем

Движок распознавания закрывает заход **по паузе**, а не по концу фразы. Просить
его не обрывать запись (`EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS`)
документация Android разрешает игнорировать — что он и делает; пробовали, это
записано в CLAUDE.md.

Значит отрезок, внутри которого стоит точка или восклицательный знак, читается
с паузой посередине — и движок отдаёт только начало. Владелец наткнулся на это
20.09.2026 на отрезке «„Zlo, svinjo! Eto lisice, đe vodi strašnoga bumbašira.“»:
прочитал восемь слов, движок вернул три.

**Поломка тихая.** История собирается, открывается, читается — и один отрезок
из пятидесяти оказывается непроходимым. Заметить это можно только прочитав
историю вслух до конца, то есть уже после выпуска.

## Что считается бедой, а что нет

Короткие реплики диалогов (`Razumijem. Hvala vam.` — четыре слова) ловятся тем
же правилом, но бедой не являются: пауза там приходит почти сразу за началом, и
терять нечего. Порог по длине поэтому обязателен, иначе проверка утонет в
двадцати ложных тревогах.

    python research/tools/check_stories.py

Ненулевой код возврата — есть что чинить.
"""
import glob
import io
import json
import os
import re
import sys

A = os.path.join(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
    'src', 'app', 'src', 'main', 'assets', 'stories')

# Конец предложения, за которым ещё есть слова. Кавычки и скобки между ними
# пропускаем: «svinjo!“ Eto» — та же пауза.
INNER = re.compile(r'[.!?][“„»\')\]\s]*\s+\S')

# С какой длины отрезка пауза внутри действительно вредит.
LONG = 6


def main():
    bad = []
    for path in sorted(glob.glob(os.path.join(A, '*.json'))):
        name = os.path.basename(path)
        if name == 'index.json':
            continue
        data = json.load(io.open(path, encoding='utf-8'))
        for i, chunk in enumerate(data.get('chunks', []), 1):
            sr = chunk.get('sr', '')
            if INNER.search(sr) and len(sr.split()) >= LONG:
                bad.append((name, i, sr))

    for name, i, sr in bad:
        print('%-11s отрезок %2d: %s' % (name, i, sr))
    print()
    if bad:
        print('отрезков с паузой внутри: %d — движок отдаст только начало' % len(bad))
        print('режь их по предложениям')
        return 1
    print('все отрезки — по одному предложению')
    return 0


if __name__ == '__main__':
    sys.exit(main())
