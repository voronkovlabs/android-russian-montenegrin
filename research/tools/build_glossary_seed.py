# Заготовка сербско-русского словаря из русского Викисловаря (выгрузка kaikki.org).
#
# Зачем: приложению нужен glossary.json вида «словоформа → перевод на русский»,
# а Викисловарь даёт готовые толкования по-русски. Две оговорки, обе важные:
#   * заголовки статей там кириллицей — транслитерируем, для сербского это
#     однозначное соответствие один-в-один с диграфами;
#   * статьи экавские (lepo, gde), а курс иекавский — пометка ekav ставится
#     здесь, но замену делает человек: brjegovi и подобное механикой не берётся.
#
# Запуск:
#   curl -sSL -o /tmp/sr.jsonl \
#     "https://kaikki.org/ruwiktionary/%D0%A1%D0%B5%D1%80%D0%B1%D1%81%D0%BA%D0%B8%D0%B9/kaikki.org-dictionary-%D0%A1%D0%B5%D1%80%D0%B1%D1%81%D0%BA%D0%B8%D0%B9.jsonl"
#   PYTHONUTF8=1 python research/tools/build_glossary_seed.py /tmp/sr.jsonl
import io, json, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DATA = os.path.join(ROOT, 'research', 'data')

# Сербская кириллица → латиница. Диграфы идут первыми, иначе њ развалится на н+ь.
TRANSLIT = [
    ('Њ', 'Nj'), ('Љ', 'Lj'), ('Џ', 'Dž'),
    ('њ', 'nj'), ('љ', 'lj'), ('џ', 'dž'),
]
TABLE = {
    'а': 'a', 'б': 'b', 'в': 'v', 'г': 'g', 'д': 'd', 'ђ': 'đ', 'е': 'e', 'ж': 'ž',
    'з': 'z', 'и': 'i', 'ј': 'j', 'к': 'k', 'л': 'l', 'м': 'm', 'н': 'n', 'о': 'o',
    'п': 'p', 'р': 'r', 'с': 's', 'т': 't', 'ћ': 'ć', 'у': 'u', 'ф': 'f', 'х': 'h',
    'ц': 'c', 'ч': 'č', 'ш': 'š',
}
TABLE.update({k.upper(): v.upper() for k, v in TABLE.items()})

# Маркеры экавицы: строка попадёт в отчёт с пометкой, а не молча в словарь.
EKAV = re.compile(
    r'(?<![a-zčćšžđ])(gde|ovde|onde|lep\w*|vreme\w*|mleko|dete|deca|nedelja|sreda|'
    r'posle|sutra|razume\w*|mesto|mesec|reka|belo|sever|veru\w*|čovek|devojka|uvek|'
    r'dve|celo|del\w*|sme\w*)(?![a-zčćšžđ])', re.I)


def to_latin(text):
    for a, b in TRANSLIT:
        text = text.replace(a, b)
    return ''.join(TABLE.get(c, c) for c in text)


def main(path):
    rows = []
    for line in io.open(path, encoding='utf-8'):
        entry = json.loads(line)
        word = entry.get('word', '')
        if not word:
            continue
        latin = to_latin(word)
        glosses = []
        for sense in entry.get('senses', []):
            for g in sense.get('glosses', []):
                g = g.strip()
                if g and g not in glosses:
                    glosses.append(g)
        if not glosses:
            continue
        rows.append({
            'lat': latin,
            'cyr': word,
            'pos': entry.get('pos', ''),
            'gloss': '; '.join(glosses[:2]),
            'ekav': 'ekav' if EKAV.search(latin) else '',
        })

    rows.sort(key=lambda r: (r['lat'].lower(), r['pos']))
    out = os.path.join(DATA, 'rjecnik-sr-ru.tsv')
    with io.open(out, 'w', encoding='utf-8', newline='\n') as f:
        f.write('lat\tcyr\tpos\tekav\tgloss\n')
        for r in rows:
            f.write('%s\t%s\t%s\t%s\t%s\n' % (r['lat'], r['cyr'], r['pos'], r['ekav'], r['gloss']))

    print('статей с толкованием: %d' % len(rows))
    print('из них помечено экавицей: %d' % sum(1 for r in rows if r['ekav']))
    print('записано:', out)
    print()
    print('--- образцы ---')
    for r in rows[:5] + [r for r in rows if r['ekav']][:5]:
        print('  %-14s %-12s %-10s %s' % (r['lat'], r['cyr'], r['pos'], r['gloss'][:60]))


if __name__ == '__main__':
    main(sys.argv[1])
