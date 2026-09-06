# Разбор собранного материала: экавица против иекавицы и чего не хватает курсу.
#
# Запуск из корня репозитория:
#   PYTHONUTF8=1 python research/tools/audit.py
import io, json, os, re, glob, collections

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DATA = os.path.join(ROOT, 'research', 'data')
ASSETS = os.path.join(ROOT, 'src', 'app', 'src', 'main', 'assets')

# Пары форм: экавская — иекавская. Ими же можно и конвертировать,
# но только после проверки глазами: bregovi/brjegovi и подобное не механично.
EKAV_IJEKAV = [
    ('gde', 'gdje'), ('ovde', 'ovdje'), ('onde', 'ondje'),
    ('lepo', 'lijepo'), ('lep', 'lijep'), ('lepa', 'lijepa'),
    ('vreme', 'vrijeme'), ('mleko', 'mlijeko'), ('dete', 'dijete'),
    ('deca', 'djeca'), ('nedelja', 'nedjelja'), ('sreda', 'srijeda'),
    ('posle', 'poslije'), ('pre', 'prije'), ('sutra', 'sjutra'),
    ('razumem', 'razumijem'), ('voleo', 'volio'), ('hteo', 'htio'),
    ('mesto', 'mjesto'), ('mesec', 'mjesec'), ('reka', 'rijeka'),
    ('belo', 'bijelo'), ('sever', 'sjever'), ('verujem', 'vjerujem'),
    ('covek', 'čovjek'), ('čovek', 'čovjek'), ('devojka', 'djevojka'),
    ('uvek', 'uvijek'), ('nedeljno', 'nedjeljno'), ('celo', 'cijelo'),
    ('dve', 'dvije'), ('naravno', 'naravno'),
]


def word_re(w):
    return re.compile(r'(?<![a-zA-Zčćšžđ])' + re.escape(w) + r'(?![a-zA-Zčćšžđ])', re.I)


def audit_tatoeba():
    path = os.path.join(DATA, 'tatoeba-sr-ru.jsonl')
    rows = [json.loads(l) for l in io.open(path, encoding='utf-8')]
    lat = [r for r in rows if r['script'] == 'lat']
    print('Tatoeba srp-rus: пар всего %d, латиницей %d, кириллицей %d'
          % (len(rows), len(lat), len(rows) - len(lat)))

    ek = ij = 0
    hits = collections.Counter()
    for e, i in EKAV_IJEKAV:
        re_e, re_i = word_re(e), word_re(i)
        ce = sum(1 for r in lat if re_e.search(r['sr']))
        ci = sum(1 for r in lat if re_i.search(r['sr']))
        ek += ce
        ij += ci
        if ce or ci:
            hits[(e, i)] = (ce, ci)

    print('Строк с экавскими формами: %d, с иекавскими: %d' % (ek, ij))
    print('Топ расхождений (экавица / иекавица):')
    for (e, i), (ce, ci) in hits.most_common(12):
        print('  %-10s %-10s %4d / %d' % (e, i, ce, ci))


def audit_frequency():
    """Каких частотных слов курс ещё не знает."""
    known = set()
    gl = json.load(io.open(os.path.join(ASSETS, 'glossary.json'), encoding='utf-8'))
    known |= set(gl['me'].keys())
    for f in glob.glob(os.path.join(ASSETS, 'lessons', 'l*.json')):
        blob = io.open(f, encoding='utf-8').read().lower()
        known |= set(re.findall(r'[a-zčćšžđ]+', blob))

    rows = []
    for line in io.open(os.path.join(DATA, 'sr_50k.txt'), encoding='utf-8'):
        parts = line.split()
        if len(parts) == 2:
            rows.append((parts[0], int(parts[1])))

    gap = [(rank, w, n) for rank, (w, n) in enumerate(rows[:1500], 1) if w not in known]
    out = os.path.join(DATA, 'frequency-gap.tsv')
    with io.open(out, 'w', encoding='utf-8', newline='\n') as f:
        f.write('rank\tword\tcount\n')
        for rank, w, n in gap:
            f.write('%d\t%s\t%d\n' % (rank, w, n))

    print()
    print('Частотный список: 50 000 словоформ; из первых 1500 курсу неизвестны %d' % len(gap))
    print('Первые 40 пропусков:', ' '.join(w for _, w, _ in gap[:40]))
    print('Записано:', out)


if __name__ == '__main__':
    audit_tatoeba()
    audit_frequency()
