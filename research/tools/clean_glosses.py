# -*- coding: utf-8 -*-
"""
Убрать из толкований служебные пометки Викисловаря.

Катя, 09.10.2026: в перевёртыше встретилось «вода 1 и 2». У 199 слов толкование
пришло с пометкой «(аналогично русскому слову)» — она значит лишь, что перевод
совпадает с русским словом, — а у части ещё и с номерами значений «[1]»,
«[2]». В карточке это мусор: «жена (аналогично русскому слову)».

Чистится механически: пометка и номера уходят, повторы склеиваются. Пометки в
другом виде («аналог. русск. град II») — руками, списком `MANUAL`. Ключи слов
не трогаются, прогресс тоже; `build_vocab.py` толкования живущих слов при
пересборке сохраняет, так что чистка не откатится.

    PYTHONUTF8=1 python research/tools/clean_glosses.py
"""
import io
import json
import os
import re

ASSETS = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..',
                      'src', 'app', 'src', 'main', 'assets', 'vocab')

MANUAL = {
    'voda': 'вода',
    'grad': 'город, град',
    'advokat': 'адвокат',
    'strah': 'страх',
    'ptica': 'птица',
    'demon': 'демон',
    'maska': 'маска',
    'požar': 'пожар',
    'marka': 'марка',
    'koncert': 'концерт',
    'rosa': 'роса',
    'organ': 'орган',
    'slon': 'слон',
    'bič': 'бич',
    'šah': 'шах',
    'kum': 'кум',
    'krokodil': 'крокодил',
    'korpus': 'корпус',
    'akcent': 'акцент',
    'podvig': 'подвиг',
    'anus': 'анус',
    'lavanda': 'лаванда',
    'uranijum': 'уран (химический элемент)',
    'hram': 'храм',
    'prorok': 'пророк',
    'belo': 'белый; героин, кокаин (жарг.)',
}


# 4.53, Катя: «почисть метки». Предметные пометки Викисловаря («зоол.»,
# «шахм.») ученику ничего не говорят и убираются; стилевые говорят, как слово
# звучит, и переезжают в скобки после слова: «разг. трус» → «трус (разг.)».
STYLE = {'разг.', 'перен.', 'бран.', 'вульг.', 'обсц.', 'жарг.', 'устар.',
         'рег.', 'книжн.'}
LABELS = re.compile(r'^((?:[а-яё]{2,10}\.(?:-[а-яё]+\.)?,?\s*)+)(?:\((?:sr|hr|bs|me)\)\s*)?')


def unlabel(part):
    m = LABELS.match(part)
    if not m:
        return part
    labels = re.findall(r'[а-яё]{2,10}\.(?:-[а-яё]+\.)?', m.group(1))
    rest = part[m.end():].strip()
    style = [l for l in labels if l in STYLE]
    if not rest:
        return part
    return rest + (' (%s)' % ', '.join(style) if style else '')


def clean(gloss):
    g = re.sub(r'\s*\(аналогично русскому слову\)', '', gloss)
    g = re.sub(r'\s*\[\d+\]', '', g)
    if LABELS.match(g):
        # Латынь вида и номер омонима бывают только у помеченных статей:
        # «зоол. волк (Canis lupus)», «зоол. рак I». Без пометки в скобках
        # латиницей стоят наши же подсказки — «(vratiti se)», их не трогаем.
        g = re.sub(r'\s*\((?:лат\. )?[A-Za-z][A-Za-z .()]*\)', '', g)
        g = re.sub(r'\s+(?:I|II|III|IV)\b', '', g)
    parts = []
    for p in (unlabel(x.strip()) for x in g.split(';')):
        if p and p not in parts:
            parts.append(p)
    return '; '.join(parts)


def main():
    path = os.path.join(ASSETS, 'words.json')
    data = json.load(io.open(path, encoding='utf-8'))
    changed = 0
    for w in data['words']:
        new = MANUAL.get(w['id']) or clean(w['gloss'])
        if new != w['gloss']:
            w['gloss'] = new
            changed += 1
    io.open(path, 'w', encoding='utf-8', newline='').write(
        json.dumps(data, ensure_ascii=False, separators=(',', ':')))
    left = [w['id'] for w in data['words'] if 'аналог' in w['gloss'] or re.search(r'\[\d', w['gloss'])]
    print('толкований исправлено: %d; осталось с пометкой: %s' % (changed, ', '.join(left) or '—'))


if __name__ == '__main__':
    main()
