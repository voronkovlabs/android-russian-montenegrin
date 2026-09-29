# -*- coding: utf-8 -*-
"""Что наш синтезатор выговаривает не так — при том что отрезок зачтён.

Прогон корпуса нарочно снисходителен: свёртка ятя, скидка по знакам, доля слов.
Для сверки ответа человека это правильно, но ровно поэтому «зачёт не значит
ничего»: отрезок проходит, а слово внутри могло прозвучать неверно. В заданиях
«на слух» синтезатор — **единственный** источник звука, и такая ошибка учит
неправде молча.

Работает по сохранённым расшифровкам прогона (`hear_corpus --pull` оставляет
рядом с отчётом `.json`), то есть ничего не слушает заново и ничего не стоит.

    python research/tools/vygovor.py research/data/sluh-....txt.json
    python research/tools/vygovor.py ... --jat        # только про иекавицу

Два разбора, и они отвечают на разные вопросы.

**По словам** (`--words`, по умолчанию). Сколько раз слово встретилось и сколько
раз чужое ухо услышало его **дословно**, без свёрток и скидок. Слово, ни разу не
услышанное дословно в отрезках, зачтённых в целом, — подозреваемое. Берутся
только зачтённые отрезки: там слушатель справился, и пропажа одного слова
говорит о слове, а не о провале записи.

**Про иекавицу** (`--jat`). Отдельно, потому что это главный риск заданий «на
слух»: курс иекавский, а локаль у синтезатора сербская.

Чего этот разбор **не** делает: он не выносит вердикт сам. Провал у одного
слушателя — не улика (см. `hear_corpus --phone`), и привычку слушателя от
выговора синтезатора отличает только другой поставщик голоса. Порядок такой:
разбор называет подозреваемых, `krug.py` проверяет их чужим голосом.

Так 25.09.2026 нашлись `sjutra` и `nedjelju` — и так же отсеялись `majstor` и
`doviđenja`, которых Whisper пишет «maistor» и «do viđenja» с любого голоса.
"""
import argparse
import io
import json
import os
import re
import sys
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import hear_corpus as hc  # noqa: E402

MIN_LEN = 4      # короткие слова слушатель склеивает с соседними сам
MIN_RAZ = 3      # три встречи, чтобы это не было единичной осечкой

# Слово целиком -> его экавская пара. Границы слова здесь есть по построению.
#
# Именно целиком, а не подстрокой, и это важнее, чем кажется: в приложении
# `SPECIAL` заменяет подстроки, и пара «đe» -> «gde» калечит всякое слово, внутри
# которого есть «đe» (в CLAUDE.md про это отдельный раздел). Первый заход этого
# разбора повторил ту же ошибку с парой «dje» -> «gde»: `nedjelju` превращалось
# в `negdelju`, такой формы нет нигде, и настоящая экавица уходила в графу «не
# расслышано». Поэтому здесь экавская форма **порождается** из иекавской, а
# исходное слово не трогается вовсе.
CELIKOM = {
    'sjutra': 'sutra', 'ovdje': 'ovde', 'ondje': 'onde', 'gdje': 'gde',
    'dio': 'deo', 'cio': 'ceo', 'đe': 'gde', 'ovđe': 'ovde',
}
SOGLASNYE = 'bcčćdđfghjklmnpqrsštvwxzž'


def toks(s):
    """Слова в нижнем регистре; диакритика сохранена — к ятю она не относится."""
    s = hc.latin(s).lower()
    s = re.sub(r'[^0-9a-zà-ÿčćšžđ]+', ' ', s)
    return [w for w in s.split() if w]


def ekavica(word, shiroko):
    """Какие экавские формы могло бы дать это слово."""
    out = set()
    if word in CELIKOM:
        out.add(CELIKOM[word])
    if 'ije' in word:
        out.add(word.replace('ije', 'e'))
    if shiroko:
        # «je» после согласной: nedjelju -> nedelju, djevojka -> devojka.
        out.add(re.sub(r'([' + SOGLASNYE + r'])je', r'\1e', word))
    out.discard(word)
    return out


def po_slovam(rows):
    vstrech, tochno, gde = Counter(), Counter(), {}
    zachteno = 0
    for r in rows:
        if hc.glued(r['heard'], r['text']) < hc.GLUED_PASS:
            continue
        zachteno += 1
        heard = set(toks(r['heard']))
        for w in set(toks(r['text'])):
            if len(w) < MIN_LEN or w.isdigit():
                continue
            vstrech[w] += 1
            if w in heard:
                tochno[w] += 1
            else:
                gde.setdefault(w, []).append((r['id'], r['text'], r['heard']))

    podozr = sorted(((w, n) for w, n in vstrech.items()
                     if n >= MIN_RAZ and tochno[w] == 0), key=lambda p: -p[1])
    print('зачтённых отрезков: %d из %d' % (zachteno, len(rows)))
    print('слов длиннее %d знаков, встреченных %d+ раз: %d'
          % (MIN_LEN - 1, MIN_RAZ,
             sum(1 for w, n in vstrech.items() if n >= MIN_RAZ)))
    print('из них ни разу не услышаны дословно: %d' % len(podozr))
    print()
    for w, n in podozr:
        print('=== %s — %d встреч, дословно ни разу' % (w, n))
        for rid, text, heard in gde[w][:3]:
            print('    %-9s текст:    %s' % (rid, text))
            print('    %-9s услышано: %s' % ('', heard))
        print()
    if podozr:
        print('Дальше — чужим голосом: те же фразы через krug.py. Слово,')
        print('услышанное дословно у другого поставщика, обвиняет наш голос;')
        print('услышанное так же — это привычка слушателя, и дела нет.')


def pro_jat(rows):
    for shiroko, imya in ((False, 'узкий круг — как считает приложение'),
                          (True, 'широкий круг — вся иекавица на деле')):
        iek, ek, gluho, gde = Counter(), Counter(), Counter(), {}
        for r in rows:
            heard = set(toks(r['heard']))
            for w in toks(r['text']):
                formy = ekavica(w, shiroko)
                if not formy:
                    continue
                if w in heard:
                    iek[w] += 1
                elif formy & heard:
                    ek[w] += 1
                    gde.setdefault(w, []).append((r['id'], r['text'], r['heard']))
                else:
                    gluho[w] += 1

        vsego = sum(iek.values()) + sum(ek.values()) + sum(gluho.values())
        sudimo = sum(iek.values()) + sum(ek.values())
        print()
        print('=== %s' % imya)
        print('  слов с ятем (вхождений): %d' % vsego)
        print('  услышано иекавицей: %d' % sum(iek.values()))
        print('  услышано ЭКАВИЦЕЙ:  %d' % sum(ek.values()))
        print('  не расслышано:      %d  (вывода нет)' % sum(gluho.values()))
        if sudimo:
            print('  доля иекавицы среди расслышанных: %.1f%%'
                  % (100.0 * sum(iek.values()) / sudimo))
        for w, c in ek.most_common():
            print('    %-14s %d раз -> %s'
                  % (w, c, ', '.join(sorted(ekavica(w, shiroko)))))
            for rid, t, h in gde[w][:2]:
                print('       %-9s %s' % (rid, t))
                print('       %-9s %s' % ('', h))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('json', help='сохранённые расшифровки прогона (.txt.json)')
    ap.add_argument('--jat', action='store_true', help='только разбор иекавицы')
    ap.add_argument('--words', action='store_true', help='только разбор по словам')
    args = ap.parse_args()

    rows = json.load(io.open(args.json, encoding='utf-8'))
    print('расшифровок: %d  (%s)' % (len(rows), os.path.basename(args.json)))
    print()
    if not args.jat:
        po_slovam(rows)
    if not args.words:
        pro_jat(rows)


if __name__ == '__main__':
    main()
