# -*- coding: utf-8 -*-
"""
Примеры в словарных карточках: про то ли они слово.

Словарь собирался подбором предложений по совпадению **словоформы**, без учёта
части речи. Отсюда беда, найденная по жалобе на `priča`: статья про
существительное «рассказ», а оба примера — `On priča glasno` («он разговаривает
громко»), то есть форма глагола `pričati`, случайно совпавшая по написанию.

Признак подозрительности — косвенный, прямого разбора морфологии у нас нет:
**русский перевод примера не содержит ни одного корня из толкования**. Для
«рассказ» и «Он разговаривает громко» это срабатывает, для честного примера —
нет. Ложные срабатывания неизбежны (служебные слова, «быть» → «является»),
поэтому скрипт печатает выборку для глаз, а не приговор.

Запуск из корня репозитория:  python research/tools/audit_examples.py
"""
import io
import json
import re
import sys

WORDS = 'src/app/src/main/assets/vocab/words.json'
STEM = 4          # сколько букв корня считаем достаточным совпадением
SAMPLE = 12


def variants(gloss):
    s = re.sub(r'\([^)]*\)', ' ', gloss)
    s = re.sub(r'\b[а-яё]{2,8}\.(?=\s|$)', ' ', s)
    s = re.sub(r'\d+', ';', s)
    out = [re.sub(r'\s+', ' ', x).strip().lower() for x in re.split(r'[;,]', s)]
    return [x for x in out if x]


def stems(gloss):
    """Корни толкования: по первым буквам каждого значимого слова."""
    out = set()
    for v in variants(gloss):
        for word in v.split():
            w = word.strip('«»"')
            if len(w) >= STEM:
                out.add(w[:STEM])
    return out


def main():
    d = json.load(io.open(WORDS, encoding='utf-8'))
    words = d['words']

    with_ex = [w for w in words if w.get('ex')]
    total_ex = sum(len(w['ex']) for w in with_ex)

    suspect = []
    for w in with_ex:
        roots = stems(w['gloss'])
        if not roots:
            continue
        bad = []
        for ex in w['ex']:
            ru = ex.get('ru', '').lower()
            if not any(r in ru for r in roots):
                bad.append(ex)
        if bad and len(bad) == len(w['ex']):
            # все примеры мимо — это и есть подозрительная статья
            suspect.append((w, bad))

    print('слов в словаре:            %d' % len(words))
    print('из них с примерами:        %d (%d примеров)' % (len(with_ex), total_ex))
    print('статей, где ВСЕ примеры мимо толкования: %d (%.1f%% от статей с примерами)'
          % (len(suspect), 100.0 * len(suspect) / max(len(with_ex), 1)))
    print()
    print('Выборка для глаз:')
    for w, bad in suspect[:SAMPLE]:
        print('  %-14s «%s»' % (w['id'], w['gloss']))
        for ex in bad[:1]:
            print('        %s / %s' % (ex.get('sr', ''), ex.get('ru', '')))

    # Сколько из подозрительных — существительные с примером в форме леммы:
    # именно так выглядит столкновение с глаголом, как у priča.
    homonym = [w for w, _ in suspect
               if w.get('pos') == 'NOUN' and any(ex.get('f') == w['id'] for ex in w['ex'])]
    print()
    print('из них существительных, где пример стоит в форме самой леммы: %d' % len(homonym))
    print('  ' + ', '.join(w['id'] for w in homonym[:20]))


if __name__ == '__main__':
    sys.exit(main())
