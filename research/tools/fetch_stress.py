# -*- coding: utf-8 -*-
"""
Ударения для словаря: правило плюс английский Викисловарь.

Что делает:

1. правило — в штокавской норме ударение не стоит на последнем слоге, поэтому
   у слов в один-два слога оно однозначно (первый слог). Такие слова в файл не
   пишутся вовсе: приложение считает их само;
2. остальные — из `{{sh-noun|apotéka|f}}` и `{{sh-IPA|vòda}}` в статьях
   en.wiktionary. Берём только те, где снятые знаки дают ровно нашу лемму и все
   найденные варианты согласны между собой;
3. заодно проверяет правило: для одно- и двусложных сверяет его с Викисловарём и
   печатает все расхождения. Это и есть доказательство «уверены на сто».

Выход — `src/app/src/main/assets/vocab/stress.json`: слово → номер буквы, на
которую падает ударение (индекс в строке, не в слогах).

Лицензия данных: en.wiktionary, CC BY-SA. Ссылка есть в research/README.md.

Запуск из корня репозитория:  python research/tools/fetch_stress.py
"""
import io
import json
import re
import sys
import time
import unicodedata
import urllib.parse
import urllib.request

WORDS = 'src/app/src/main/assets/vocab/words.json'
OUT = 'src/app/src/main/assets/vocab/stress.json'
CACHE = 'research/data/wiktionary-stress.json'
API = 'https://en.wiktionary.org/w/api.php'
UA = 'MonteLangResearch/1.0 (personal language trainer; contact: local use)'

VOWELS = 'aeiou'
ACCENTS = '̀́̏̑'  # ̀ ́ ̏ ̑ — четыре акцента
LENGTH = '̄'                     # ̄ — заударная долгота, не ударение

HEAD = re.compile(
    r'\{\{sh-(?:noun|verb|adj|adv|pronoun|num|proper noun|particle|prep'
    r'|conj|interj|IPA)\|([^|}\n]+)')


def nuclei(word):
    """Слоговые вершины слова: индексы гласных плюс слоговое r."""
    w = word.lower()
    out = []
    for i, c in enumerate(w):
        if c in VOWELS:
            out.append(i)
        elif c == 'r':
            # Слоговое r: между согласными или с краю слова рядом с согласной
            # («mŕžnja», «masàkr»). Край — не гласная: пустую строку сравнивать
            # с VOWELS нельзя, `'' in 'aeiou'` в питоне истинно, и конечное
            # слоговое r переставало быть слогом. Kotlin-двойник в
            # `data/Stress.kt` считает ровно так же, и разойтись они не должны.
            prev = w[i - 1] if i else None
            nxt = w[i + 1] if i + 1 < len(w) else None
            around_r = (prev is None or prev not in VOWELS) and \
                       (nxt is None or nxt not in VOWELS)
            if around_r and (prev is not None or nxt is not None):
                out.append(i)
    return out


def strip_marks(s):
    return ''.join(c for c in unicodedata.normalize('NFD', s)
                   if c not in ACCENTS + LENGTH)


def accent_index(marked):
    """Номер ударной буквы в слове без знаков; None — знака нет или он не один."""
    nfd = unicodedata.normalize('NFD', marked)
    plain, hits, i = [], [], 0
    for c in nfd:
        if c in ACCENTS:
            hits.append(i - 1)
        elif c == LENGTH:
            continue
        else:
            plain.append(c)
            i += 1
    if len(hits) != 1:
        return None
    return hits[0]


def fetch(titles):
    q = {
        'action': 'query', 'format': 'json', 'formatversion': '2',
        'prop': 'revisions', 'rvprop': 'content', 'rvslots': 'main',
        'titles': '|'.join(titles),
    }
    req = urllib.request.Request(API + '?' + urllib.parse.urlencode(q),
                                 headers={'User-Agent': UA})
    # Викисловарь отвечает 429 после нескольких сотен запросов подряд. Ждём
    # долго и всё равно докачиваем: кэш на диске не даст начать сначала.
    for attempt in range(5):
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.load(r)
        except Exception as e:                                  # noqa: BLE001
            if attempt == 4:
                raise
            wait = (attempt + 1) * 20
            print('   повтор через %d с после %s' % (wait, e))
            time.sleep(wait)


def sh_section(text):
    m = re.search(r'==\s*Serbo-Croatian\s*==(.*?)(?=\n==[^=]|\Z)', text, re.S)
    return m.group(1) if m else ''


def marked_forms(page):
    """Все размеченные написания слова из статьи."""
    body = page['revisions'][0]['slots']['main']['content']
    out = []
    for head in HEAD.findall(sh_section(body)):
        head = head.split('<')[0].strip()
        if head:
            out.append(head)
    return out


def main():
    d = json.load(io.open(WORDS, encoding='utf-8'))
    words = [w['id'] for w in d['words']]

    # Кэш: слово → список размеченных написаний (пустой, если статьи нет).
    # Нужен из-за 429: докачивать остаток можно сколько угодно раз.
    try:
        marked = json.load(io.open(CACHE, encoding='utf-8'))
    except (IOError, ValueError):
        marked = {}
    todo = [w for w in words if w not in marked]
    print('в кэше:', len(marked), '· осталось забрать:', len(todo))

    for i in range(0, len(todo), 50):
        batch = todo[i:i + 50]
        data = fetch(batch)
        pages = {p['title']: p for p in data['query']['pages']}
        for w in batch:
            p = pages.get(w)
            marked[w] = marked_forms(p) if p and 'missing' not in p else []
        io.open(CACHE, 'w', encoding='utf-8', newline='\n').write(
            json.dumps(marked, ensure_ascii=False, sort_keys=True, indent=0))
        print('   %d/%d' % (min(i + 50, len(todo)), len(todo)))
        time.sleep(1.5)

    rule, lexicon, unsure, disagree = 0, {}, [], []
    broken = []
    for w in words:
        n = nuclei(w)
        variants = set()
        for m in marked.get(w, []):
            if strip_marks(m).lower() != w.lower():
                continue
            idx = accent_index(m)
            if idx is not None:
                variants.add(idx)

        if len(n) <= 2:
            # Правило: ударение на первом слоге. Сверяем с Викисловарём.
            rule += 1
            if variants and variants != {n[0]}:
                broken.append((w, sorted(variants), n[0], sorted(marked[w])))
            continue

        if len(variants) == 1:
            lexicon[w] = variants.pop()
        elif len(variants) > 1:
            disagree.append((w, sorted(variants)))
        else:
            unsure.append(w)

    print()
    print('слов всего:                 ', len(words))
    print('закрыто правилом (1-2 слога):', rule)
    print('  из них проверено словарём: ',
          sum(1 for w in words if len(nuclei(w)) <= 2 and marked.get(w)))
    print('  расхождений с правилом:    ', len(broken))
    for b in broken:
        print('     ', b)
    print('многосложных из Викисловаря:', len(lexicon))
    print('  варианты разошлись:        ', len(disagree), disagree[:10])
    print('  не нашлось:                ', len(unsure), unsure[:10])

    io.open(OUT, 'w', encoding='utf-8', newline='\n').write(
        json.dumps(lexicon, ensure_ascii=False, sort_keys=True,
                   separators=(',', ':')))
    print('записано:', OUT)


if __name__ == '__main__':
    sys.exit(main())
