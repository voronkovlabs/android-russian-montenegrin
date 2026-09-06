# Сколько заданий реально получится, если принять сербский как есть.
#
# Считает пул пригодных пар Tatoeba после транслитерации кириллицы,
# раскладывает их по грамматическим темам уроков 9-20 и проверяет,
# хватит ли словаря на подсказки.
#
#   PYTHONUTF8=1 python research/tools/feasibility.py
import io, json, os, re, collections

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DATA = os.path.join(ROOT, 'research', 'data')
ASSETS = os.path.join(ROOT, 'src', 'app', 'src', 'main', 'assets')

TRANSLIT = [('Њ', 'Nj'), ('Љ', 'Lj'), ('Џ', 'Dž'), ('њ', 'nj'), ('љ', 'lj'), ('џ', 'dž')]
TABLE = {'а': 'a', 'б': 'b', 'в': 'v', 'г': 'g', 'д': 'd', 'ђ': 'đ', 'е': 'e', 'ж': 'ž',
         'з': 'z', 'и': 'i', 'ј': 'j', 'к': 'k', 'л': 'l', 'м': 'm', 'н': 'n', 'о': 'o',
         'п': 'p', 'р': 'r', 'с': 's', 'т': 't', 'ћ': 'ć', 'у': 'u', 'ф': 'f', 'х': 'h',
         'ц': 'c', 'ч': 'č', 'ш': 'š'}
TABLE.update({k.upper(): v.upper() for k, v in TABLE.items()})


def to_latin(t):
    for a, b in TRANSLIT:
        t = t.replace(a, b)
    return ''.join(TABLE.get(c, c) for c in t)


WORD = re.compile(r'[a-zA-ZčćšžđČĆŠŽĐ]+')
OK_CHARS = re.compile(r'^[a-zA-ZčćšžđČĆŠŽĐ\s.,!?\'-]+$')

# Грамматические цели уроков 9-20: чем ловим подходящие предложения.
TARGETS = [
    ('l09 перфект', re.compile(r'(?<![a-zčćšžđ])(sam|si|smo|ste|su|je)(?![a-zčćšžđ])'
                               r'.{0,30}(?<![a-zčćšžđ])\w+(ao|io|la|lo|li|le)(?![a-zčćšžđ])', re.I)),
    ('l10 родительный', re.compile(r'(?<![a-zčćšžđ])(nema|nemam|nemaš|iz|od|kod|bez|do)(?![a-zčćšžđ])', re.I)),
    ('l11 множественное', re.compile(r'(?<![a-zčćšžđ])\w+(ovi|evi|ima|ama)(?![a-zčćšžđ])', re.I)),
    ('l12 еда и стол', re.compile(r'(?<![a-zčćšžđ])(jelo|hljeb|hleb|voda|vodu|kafa|kafu|sir|meso|'
                                  r'jesti|jedem|pijem|piti|restoran|račun|gladan|žedan)(?![a-zčćšžđ])', re.I)),
    ('l13 будущее', re.compile(r'(?<![a-zčćšžđ])(ću|ćeš|će|ćemo|ćete)(?![a-zčćšžđ])', re.I)),
    ('l14 жильё и местный', re.compile(r'(?<![a-zčćšžđ])(soba|sobi|kuća|kući|stan|stanu|sto|stolu|'
                                       r'pored|ispod|iznad|kod)(?![a-zčćšžđ])', re.I)),
    ('l15 дательный/творительный', re.compile(r'(?<![a-zčćšžđ])(mi|ti|mu|joj|nam|vam|im|sa|s)(?![a-zčćšžđ])', re.I)),
    ('l16 здоровье', re.compile(r'(?<![a-zčćšžđ])(boli|bolestan|doktor|ljekar|lekar|bolnica|'
                                r'glava|glavu|stomak|zub|umoran)(?![a-zčćšžđ])', re.I)),
    ('l17 погода и сравнение', re.compile(r'(?<![a-zčćšžđ])(hladno|toplo|kiša|snijeg|sneg|sunce|vjetar|vetar|'
                                          r'bolje|gore|više|manje|\w+iji)(?![a-zčćšžđ])', re.I)),
    ('l18 повелительное', re.compile(r'(?<![a-zčćšžđ])(idi|idite|dođi|dođite|čekaj|čekajte|reci|recite|'
                                     r'daj|dajte|molim|izvinite|pogledaj|pogledajte|stani|požuri)(?![a-zčćšžđ])', re.I)),
    ('l19 работа и биография', re.compile(r'(?<![a-zčćšžđ])(radim|radiš|radi|posao|posla|škola|školu|'
                                          r'učim|studira|godina|godine|rođen)(?![a-zčćšžđ])', re.I)),
    ('l20 условное и союзы', re.compile(r'(?<![a-zčćšžđ])(bih|bismo|biste|ako|kada|dok|jer|zato)(?![a-zčćšžđ])', re.I)),
]


def main():
    rows = [json.loads(l) for l in io.open(os.path.join(DATA, 'tatoeba-sr-ru.jsonl'), encoding='utf-8')]
    print('пар в выгрузке:', len(rows))

    # 1. транслитерация и чистка
    seen = set()
    pool = []
    for r in rows:
        sr = to_latin(r['sr']).strip()
        ru = r['ru'].strip()
        key = (sr.lower(), ru.lower())
        if key in seen:
            continue
        seen.add(key)
        n = len(WORD.findall(sr))
        if not (2 <= n <= 8):
            continue
        if not OK_CHARS.match(sr):
            continue
        pool.append({'sr': sr, 'ru': ru, 'n': n, 'id': r['sr_id']})
    print('после транслитерации, чистки и дедупликации:', len(pool))
    print('  из них 2-4 слова:', sum(1 for p in pool if p['n'] <= 4))
    print('  из них 5-6 слов:', sum(1 for p in pool if 5 <= p['n'] <= 6))

    # 2. знакомая лексика: доля слов из первых 3000 по частоте
    freq = {}
    for i, line in enumerate(io.open(os.path.join(DATA, 'sr_50k.txt'), encoding='utf-8')):
        parts = line.split()
        if len(parts) == 2:
            freq[parts[0]] = i + 1
    top2000 = {w for w, r in freq.items() if r <= 2000}
    top5000 = {w for w, r in freq.items() if r <= 5000}

    def covered(sr, vocab):
        ws = [w.lower() for w in WORD.findall(sr)]
        return all(w in vocab for w in ws) if ws else False

    easy = [p for p in pool if covered(p['sr'], top2000)]
    mid = [p for p in pool if covered(p['sr'], top5000)]
    print('  все слова в первых 2000 по частоте:', len(easy))
    print('  все слова в первых 5000 по частоте:', len(mid))

    # 3. раскладка по темам уроков
    print()
    print('Пригодных предложений на тему (из «лёгкого» пула %d):' % len(easy))
    used = set()
    for name, rx in TARGETS:
        hits = [p for p in easy if rx.search(p['sr'])]
        fresh = [p for p in hits if p['sr'] not in used]
        for p in fresh[:60]:
            used.add(p['sr'])
        print('  %-30s %5d  (уникальных сверх предыдущих: %d)' % (name, len(hits), len(fresh)))

    # 4. хватит ли словаря на подсказки
    gloss = {}
    for line in io.open(os.path.join(DATA, 'rjecnik-sr-ru.tsv'), encoding='utf-8'):
        parts = line.rstrip('\n').split('\t')
        if len(parts) >= 5 and parts[0] != 'lat':
            gloss.setdefault(parts[0].lower(), parts[4])
    known = json.load(io.open(os.path.join(ASSETS, 'glossary.json'), encoding='utf-8'))['me']

    forms = collections.Counter()
    for p in easy:
        for w in WORD.findall(p['sr']):
            forms[w.lower()] += 1
    have = sum(1 for w in forms if w in known or w in gloss)
    print()
    print('Словоформ в лёгком пуле: %d' % len(forms))
    print('  уже в словаре курса или в выгрузке Викисловаря: %d (%.0f%%)'
          % (have, 100.0 * have / max(1, len(forms))))
    print('  без перевода: %d' % (len(forms) - have))

    # 5. что уйдёт в файл
    out = os.path.join(DATA, 'pool-lat-clean.tsv')
    with io.open(out, 'w', encoding='utf-8', newline='\n') as f:
        f.write('sr\tru\twords\ttop2000\tsr_id\n')
        for p in sorted(pool, key=lambda x: (x['n'], x['sr'].lower())):
            f.write('%s\t%s\t%d\t%s\t%s\n'
                    % (p['sr'], p['ru'], p['n'], '1' if covered(p['sr'], top2000) else '0', p['id']))
    print()
    print('записано:', out)


if __name__ == '__main__':
    main()
