# -*- coding: utf-8 -*-
"""
Слова из интернет-магазинов и досок объявлений, которых нет в словаре.

## Зачем

Гипотеза Кати, 09.10.2026: в названиях товаров и категорий у больших
магазинов и досок объявлений должны найтись бытовые слова, которые словарь не
учёл. Частоты словаря взяты из субтитров, где кухни и огорода почти нет, а
дерево категорий магазина — готовый перечень бытовых предметов.

## Что делает

Для каждого сайта: читает `robots.txt` и уважает его, берёт главную и до
`PER_SITE` страниц категорий с того же сайта, с паузой между запросами.
Вынимает текст (вместе со строками внутри встроенного JSON — у части сайтов
меню собирается скриптом), режет на слова латиницей, сводит к леммам по
srLex и считает, **на скольких сайтах** слово встретилось: слово с одного
сайта — чаще всего бренд или жаргон этого магазина, с трёх — общее.

Страницы кэшируются в `research/data/shops/` (в репозиторий не едут).
Итог — `research/data/vocab-shops.tsv`.

## Запуск

    PYTHONUTF8=1 python research/tools/shop_words.py research/data/srLex_v1.3.gz
"""
import gzip
import hashlib
import html
import io
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request
import urllib.robotparser
from collections import Counter, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import vocab
from build_vocab import reflex

ROOT = vocab.ROOT
DATA = vocab.DATA
CACHE = os.path.join(DATA, 'shops')
UA = 'Mozilla/5.0 (crnogorski-vocab research; low volume)'
PER_SITE = 40
PAUSE = 1.5

SITES = {
    'ikea.rs': 'https://www.ikea.com/rs/sr/',
    'jysk.rs': 'https://jysk.rs/',
    'jysk.me': 'https://www.jysk.me/',
    'emmezeta.rs': 'https://www.emmezeta.rs/',
    'voli.me': 'https://www.voli.me/',
    'idea.rs': 'https://www.idea.rs/online/',
    'maxi.rs': 'https://www.maxi.rs/online/',
    'gigatron.me': 'https://www.gigatron.me/',
    'oglasi.me': 'https://www.oglasi.me/',
    'kupujemprodajem': 'https://www.kupujemprodajem.com/',
    'ananas.rs': 'https://ananas.rs/',
}

WORD = re.compile(r'[a-zčćšžđ]+(?:-[a-zčćšžđ]+)?')
CYR = str.maketrans({
    'а': 'a', 'б': 'b', 'в': 'v', 'г': 'g', 'д': 'd', 'ђ': 'đ', 'е': 'e', 'ж': 'ž', 'з': 'z',
    'и': 'i', 'ј': 'j', 'к': 'k', 'л': 'l', 'љ': 'lj', 'м': 'm', 'н': 'n', 'њ': 'nj', 'о': 'o',
    'п': 'p', 'р': 'r', 'с': 's', 'т': 't', 'ћ': 'ć', 'у': 'u', 'ф': 'f', 'х': 'h', 'ц': 'c',
    'ч': 'č', 'џ': 'dž', 'ш': 'š'})


def fetch(url):
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, hashlib.sha1(url.encode()).hexdigest() + '.html')
    if os.path.exists(path):
        return io.open(path, encoding='utf-8', errors='replace').read(), url
    req = urllib.request.Request(url, headers={'User-Agent': UA, 'Accept-Language': 'sr,sr-Latn;q=0.9'})
    try:
        with urllib.request.urlopen(req, timeout=25) as r:
            body = r.read().decode('utf-8', errors='replace')
            final = r.geturl()
    except Exception as e:
        print('  нет:', url, type(e).__name__)
        return '', url
    io.open(path, 'w', encoding='utf-8').write(body)
    time.sleep(PAUSE)
    return body, final


def text_of(page):
    page = re.sub(r'(?is)<(style)[^>]*>.*?</\1>', ' ', page)
    # Скрипты не выбрасываем целиком: у части сайтов меню лежит в JSON.
    strings = re.findall(r'"([^"\\]{2,80})"', ' '.join(re.findall(r'(?is)<script[^>]*>(.*?)</script>', page)))
    page = re.sub(r'(?is)<script[^>]*>.*?</script>', ' ', page)
    page = re.sub(r'(?s)<[^>]+>', ' ', page)
    return html.unescape(page + ' ' + ' '.join(s for s in strings if ' ' in s or len(s) > 3))


def links(page, base):
    host = urllib.parse.urlparse(base).netloc
    out = []
    for href in re.findall(r'href="([^"#]+)"', page):
        u = urllib.parse.urljoin(base, html.unescape(href))
        p = urllib.parse.urlparse(u)
        if p.netloc != host or p.query or re.search(r'\.(jpg|png|webp|css|js|pdf|svg)$', p.path):
            continue
        # Страницы категорий: короткий путь без цифровых идентификаторов товаров.
        depth = len([s for s in p.path.split('/') if s])
        if 1 <= depth <= 4 and not re.search(r'\d{5,}', p.path):
            out.append(u.rstrip('/') + '/')
    return list(dict.fromkeys(out))


def crawl(name, start):
    robots = urllib.robotparser.RobotFileParser()
    p = urllib.parse.urlparse(start)
    robots.set_url('%s://%s/robots.txt' % (p.scheme, p.netloc))
    try:
        robots.read()
    except Exception:
        pass
    first, final = fetch(start)
    if not first:
        return []
    pages = [first]
    for u in links(first, final)[:PER_SITE * 3]:
        if len(pages) > PER_SITE:
            break
        if not robots.can_fetch(UA, u):
            continue
        body, _ = fetch(u)
        if body:
            pages.append(body)
    print('%s: страниц %d' % (name, len(pages)))
    return pages


def main(lexpath):
    words_json = os.path.join(ROOT, 'src', 'app', 'src', 'main', 'assets', 'vocab', 'words.json')
    ids = {w['id'] for w in json.load(io.open(words_json, encoding='utf-8'))['words']}
    ijek = os.path.join(ROOT, 'src', 'app', 'src', 'main', 'java', 'com', 'crnogorski', 'trener', 'data', 'Ijekavica.kt')
    pairs = dict(re.findall(r'"([^"]+)" to "([^"]+)"', io.open(ijek, encoding='utf-8').read()))
    have = {reflex(i) for i in ids} | {reflex(v) for v in pairs.values()}

    seen = defaultdict(set)       # форма → сайты
    count = Counter()
    for name, url in SITES.items():
        for page in crawl(name, url):
            for w in WORD.findall(text_of(page).lower().translate(CYR)):
                seen[w].add(name)
                count[w] += 1

    # Форма → самая частая лемма srLex, только знаменательные.
    content = {'NOUN', 'VERB', 'ADJ', 'ADV'}
    top = {}
    for line in gzip.open(lexpath, 'rt', encoding='utf-8'):
        p = line.rstrip('\n').split('\t')
        if len(p) < 8 or p[0].lower() not in seen:
            continue
        freq = int(p[6])
        f = p[0].lower()
        if f not in top or freq > top[f][0]:
            top[f] = (freq, vocab.LEMMA_FIX.get(p[1], p[1]), p[4], p[2])

    lemmas = defaultdict(lambda: {'sites': set(), 'count': 0, 'forms': Counter(), 'pos': ''})
    for f, (freq, lemma, upos, msd) in top.items():
        if upos not in content or not lemma[:1].islower() or msd.startswith('Np') or len(lemma) < 3:
            continue
        L = lemmas[lemma]
        L['sites'] |= seen[f]
        L['count'] += count[f]
        L['forms'][f] += count[f]
        L['pos'] = upos

    rows = []
    for lemma, L in lemmas.items():
        if reflex(lemma) in have:
            continue
        rows.append((len(L['sites']), L['count'], lemma, L['pos'],
                     ','.join(sorted(L['sites'])), ' '.join(f for f, _ in L['forms'].most_common(3))))
    rows.sort(key=lambda r: (-r[0], -r[1]))
    out = os.path.join(DATA, 'vocab-shops.tsv')
    with io.open(out, 'w', encoding='utf-8', newline='\n') as fh:
        fh.write('sites\tcount\tlemma\tpos\twhere\tforms\n')
        for r in rows:
            fh.write('\t'.join(str(x) for x in r) + '\n')
    print('нет в словаре: %d лемм; на 3+ сайтах: %d, на 2: %d'
          % (len(rows), sum(1 for r in rows if r[0] >= 3), sum(1 for r in rows if r[0] == 2)))
    print(out)


if __name__ == '__main__':
    main(sys.argv[1])
