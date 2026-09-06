# Оценка сложности предложений без обращения к модели.
#
# Готовых меток уровня для сербского не существует — CEFR-размеченных корпусов
# нет ни для сербского, ни для хорватского. Зато есть из чего сложить оценку
# самому, и она объяснимая, воспроизводимая и бесплатная:
#
#   лексика   — частота самого редкого слова в предложении (srLex, веб-корпус);
#   морфология — какие падежи, времена и наклонения встретились;
#   синтаксис  — длина и число простых предложений внутри.
#
# Сложное задание не выбрасывается, а получает уровень и уезжает в конец курса.
#
# Подготовка (57 МБ, качается один раз):
#   curl -sSL -o /tmp/srlex.gz \
#     "https://www.clarin.si/repository/xmlui/bitstream/handle/11356/1233/srLex_v1.3.gz?sequence=3&isAllowed=y"
#   PYTHONUTF8=1 python research/tools/difficulty.py /tmp/srlex.gz
import gzip, io, json, math, os, re, sys, collections

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DATA = os.path.join(ROOT, 'research', 'data')
WORD = re.compile(r'[a-zA-ZčćšžđČĆŠŽĐ]+')

# Цена грамматики в баллах. Порядок падежей — по тому, когда их дают в курсе:
# именительный и винительный уже пройдены, творительный и звательный впереди.
CASE_COST = {'Nom': 0, 'Acc': 0, 'Loc': 1, 'Gen': 2, 'Dat': 3, 'Ins': 3, 'Voc': 4}
FEATURE_COST = [
    ('Mood=Cnd', 4),        # условное: bih, bismo
    ('Tense=Imp', 4),       # имперфект — книжное
    ('VerbForm=Part', 1),   # причастие: основа перфекта
    ('Mood=Imp', 1),        # повелительное
    ('Degree=Cmp', 1),      # сравнительная степень
    ('Degree=Sup', 2),      # превосходная
    ('Voice=Pass', 3),      # страдательный залог
    ('Number=Plur', 1),     # множественное
]


def load_lexicon(path, needed):
    """Читаем 57 МБ один раз и оставляем только формы из нашего пула."""
    best = {}
    for line in gzip.open(path, 'rt', encoding='utf-8'):
        p = line.rstrip('\n').split('\t')
        if len(p) < 8:
            continue
        form = p[0].lower()
        if form not in needed:
            continue
        freq = int(p[6])
        # У формы бывает несколько разборов: берём самый частый, читатель
        # тоже поймёт слово в его обычном значении.
        if form not in best or freq > best[form][2]:
            best[form] = (p[1], p[5], freq)
    return best


def sentence_score(sr, lex):
    words = [w.lower() for w in WORD.findall(sr)]
    if not words:
        return None

    known = [lex[w] for w in words if w in lex]
    coverage = len(known) / len(words)

    # лексика: чем реже самое редкое слово, тем труднее
    freqs = [f for _, _, f in known if f > 0]
    rarest = min(freqs) if freqs else 1
    vocab = max(0.0, math.log10(1_000_000.0 / max(rarest, 1)))

    # морфология
    morph = 0
    seen = set()
    for _, feats, _ in known:
        for f in feats.split('|'):
            if f.startswith('Case='):
                morph = max(morph, CASE_COST.get(f[5:], 0))
                seen.add(f)
        for marker, cost in FEATURE_COST:
            if marker in feats and marker not in seen:
                morph += cost
                seen.add(marker)

    # синтаксис
    clauses = 1 + len(re.findall(r'[,;]|(?<![a-zčćšžđ])(ali|jer|ako|kada|dok|da|što)(?![a-zčćšžđ])',
                                 sr, re.I))
    length = len(words)

    score = 1.2 * vocab + 0.8 * morph + 0.5 * max(0, length - 3) + 1.0 * (clauses - 1)
    return {'score': round(score, 2), 'vocab': round(vocab, 2), 'morph': morph,
            'len': length, 'clauses': clauses, 'coverage': round(coverage, 2)}


def main(lexpath):
    rows = []
    for line in io.open(os.path.join(DATA, 'pool-lat-clean.tsv'), encoding='utf-8'):
        p = line.rstrip('\n').split('\t')
        if len(p) >= 5 and p[0] != 'sr':
            rows.append({'sr': p[0], 'ru': p[1], 'id': p[4]})
    print('предложений в пуле:', len(rows))

    needed = set()
    for r in rows:
        needed |= {w.lower() for w in WORD.findall(r['sr'])}
    print('различных словоформ:', len(needed))

    print('читаю srLex…')
    lex = load_lexicon(lexpath, needed)
    print('найдено в лексиконе: %d (%.0f%%)' % (len(lex), 100.0 * len(lex) / len(needed)))

    scored = []
    for r in rows:
        s = sentence_score(r['sr'], lex)
        if s and s['coverage'] >= 0.7:
            r.update(s)
            scored.append(r)
    print('оценено (покрытие лексиконом от 70%%): %d' % len(scored))

    scored.sort(key=lambda r: r['score'])
    # пять уровней равными долями: внутри курса важен порядок, а не абсолют
    n = len(scored)
    for i, r in enumerate(scored):
        r['level'] = min(5, 1 + (i * 5) // n)

    dist = collections.Counter(r['level'] for r in scored)
    print()
    print('по уровням:', dict(sorted(dist.items())))
    for lvl in range(1, 6):
        band = [r for r in scored if r['level'] == lvl]
        lo, hi = band[0]['score'], band[-1]['score']
        print()
        print('--- уровень %d (балл %.1f…%.1f, %d шт.) ---' % (lvl, lo, hi, len(band)))
        step = max(1, len(band) // 4)
        for r in band[::step][:4]:
            print('   %-42s %s' % (r['sr'][:42], r['ru'][:38]))

    out = os.path.join(DATA, 'pool-ranked.tsv')
    with io.open(out, 'w', encoding='utf-8', newline='\n') as f:
        f.write('level\tscore\tvocab\tmorph\tlen\tclauses\tsr\tru\tsr_id\n')
        for r in scored:
            f.write('%d\t%.2f\t%.2f\t%d\t%d\t%d\t%s\t%s\t%s\n'
                    % (r['level'], r['score'], r['vocab'], r['morph'], r['len'],
                       r['clauses'], r['sr'], r['ru'], r['id']))
    print()
    print('записано:', out)


if __name__ == '__main__':
    main(sys.argv[1])
