# Отбор слов для словарных карточек.
#
# Что делает: берёт частотный разрыв (слова, которых курс не знает), сводит их
# к леммам по srLex, выбрасывает служебные части речи, уже пройденное и —
# главное — прозрачное для русского уха. Прозрачное слово учить не надо: у нас
# с сербским до 70% лексики узнаётся без обучения, и частотный список без
# этого фильтра выродится в заучивание слова «voda».
#
# Дальше по каждому кандидату собирает парадигму и предложения из пула, где
# слово встречается в косвенной форме, — заготовку для морфологического
# пропуска.
#
# Запуск (srLex в репозиторий не кладём, 57 МБ):
#   curl -sSL -o /tmp/srlex.gz \
#     "https://www.clarin.si/repository/xmlui/bitstream/handle/11356/1233/srLex_v1.3.gz?sequence=3&isAllowed=y"
#   PYTHONUTF8=1 python research/tools/vocab.py /tmp/srlex.gz

import gzip
import io
import json
import os
import re
import sys
from collections import defaultdict

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
DATA = os.path.join(ROOT, 'research', 'data')
ASSETS = os.path.join(ROOT, 'src', 'app', 'src', 'main', 'assets')

WORD = re.compile(r"[a-zžćčšđ]+", re.IGNORECASE)

# Части речи, из которых бывают словарные карточки. Предлоги и союзы сюда не
# входят: это грамматика, их место в уроке, а не в карточке «слово ↔ перевод».
CONTENT = {'NOUN', 'VERB', 'ADJ', 'ADV'}


# --------------------------------------------------------------- прозрачность

CYR = {
    'а': 'a', 'б': 'b', 'в': 'v', 'г': 'g', 'д': 'd', 'е': 'e', 'ё': 'e',
    'ж': 'z', 'з': 'z', 'и': 'i', 'й': 'j', 'к': 'k', 'л': 'l', 'м': 'm',
    'н': 'n', 'о': 'o', 'п': 'p', 'р': 'r', 'с': 's', 'т': 't', 'у': 'u',
    'ф': 'f', 'х': 'h', 'ц': 'c', 'ч': 'c', 'ш': 's', 'щ': 's', 'ъ': '',
    'ы': 'i', 'ь': '', 'э': 'e', 'ю': 'u', 'я': 'a',
}

LAT = {'č': 'c', 'ć': 'c', 'š': 's', 'ž': 'z', 'đ': 'd'}


def flat_ru(word):
    """Русское слово в латинский костяк: «аптека» → apteka."""
    return ''.join(CYR.get(c, '') for c in word.lower())


def flat_sr(word):
    """Сербское слово в тот же костяк: диакритика и иекавица сплющены."""
    s = ''.join(LAT.get(c, c) for c in word.lower())
    s = s.replace('lj', 'l').replace('nj', 'n').replace('dj', 'd')
    return s.replace('ije', 'e').replace('je', 'e')


def distance(a, b):
    """Расстояние Левенштейна. Слова короткие, полная матрица не нужна."""
    if a == b:
        return 0
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def transparent(lemma, gloss):
    """
    Узнаётся ли слово русским без обучения.

    Сравниваются костяки — сербский и русский, оба сплющенные до общего
    алфавита. Порог зависит от длины: у коротких слов одна замена — это уже
    другое слово, у длинных две правки ещё оставляют слово узнаваемым.

    Метод грубый заведомо. Он ошибается на ложных друзьях, похожих написанием
    (`stolica` — стул, а не столица), поэтому окончательный отсев — глазами;
    задача фильтра сократить полторы тысячи кандидатов до сотен.
    """
    sr = flat_sr(lemma)
    if not sr:
        return False
    for variant in re.split(r'[;,]', gloss):
        variant = re.sub(r'\([^)]*\)', ' ', variant)          # пометы в скобках
        variant = re.sub(r'\b[а-я]+\.\s', ' ', variant)        # «гастрон.», «мед.»
        for word in re.findall(r'[а-яёА-ЯЁ]+', variant):
            ru = flat_ru(word)
            if not ru:
                continue
            limit = 1 if max(len(sr), len(ru)) <= 5 else 2
            # Окончания у языков разные, поэтому сравниваем и обрубленные концы.
            if distance(sr, ru) <= limit:
                return True
            if len(sr) > 4 and len(ru) > 4 and distance(sr[:-1], ru[:-1]) <= limit:
                return True
    return False


# ------------------------------------------------------------------ источники

def course_tokens():
    """Всё, что курс уже показывал: уроки, истории, словарь подсказок."""
    seen = set()
    for sub in ('lessons', 'stories'):
        folder = os.path.join(ASSETS, sub)
        for name in sorted(os.listdir(folder)):
            if not name.endswith('.json'):
                continue
            raw = io.open(os.path.join(folder, name), encoding='utf-8').read()
            seen.update(m.group(0).lower() for m in WORD.finditer(raw))
    glossary = json.load(io.open(os.path.join(ASSETS, 'glossary.json'), encoding='utf-8'))
    seen.update(k.lower() for k in glossary.get('me', {}))
    return seen


def gap_words(limit=50000):
    """
    Частотный список целиком, в порядке частоты.

    Не `frequency-gap.tsv`: там всего 1343 слова, и после сведения к леммам от
    них остаются десятки — на словарную программу этого не хватает. Известное
    отсеивается ниже и по леммам, а не по написанию, так что заранее вычитать
    курс из списка незачем.
    """
    out = []
    with io.open(os.path.join(DATA, 'sr_50k.txt'), encoding='utf-8') as f:
        for rank, line in enumerate(f, 1):
            if rank > limit:
                break
            parts = line.split()
            if len(parts) == 2:
                out.append((parts[0].lower(), rank, int(parts[1])))
    return out


def glosses():
    """lemma → русское толкование. Первая статья на слово, они дублируются."""
    out = {}
    with io.open(os.path.join(DATA, 'rjecnik-sr-ru.tsv'), encoding='utf-8') as f:
        next(f)
        for line in f:
            p = line.rstrip('\n').split('\t')
            if len(p) < 5:
                continue
            lat, pos, gloss = p[0].lower(), p[2], p[4]
            if lat and gloss and lat not in out:
                out[lat] = (pos, gloss)
    return out


def pool():
    """Предложения пула с уровнем сложности."""
    rows = []
    with io.open(os.path.join(DATA, 'pool-ranked.tsv'), encoding='utf-8') as f:
        head = next(f).rstrip('\n').split('\t')
        i = {name: n for n, name in enumerate(head)}
        for line in f:
            p = line.rstrip('\n').split('\t')
            if len(p) < len(head):
                continue
            rows.append((int(p[i['level']]), p[i['sr']], p[i['ru']], p[i['sr_id']]))
    return rows


# ---------------------------------------------------------------------- srLex

def read_lexicon(path, wanted_forms):
    """
    Проход первый: по нужным словоформам — лемма и часть речи.

    Читаем только то, что спрашивали: в лексиконе 6,9 млн строк, и держать их
    все в памяти незачем.
    """
    form2lemma = {}
    for line in gzip.open(path, 'rt', encoding='utf-8'):
        p = line.rstrip('\n').split('\t')
        if len(p) < 7:
            continue
        form, lemma, upos = p[0].lower(), p[1].lower(), p[4]
        if form in wanted_forms and form not in form2lemma:
            form2lemma[form] = (lemma, upos)
    return form2lemma


def read_paradigms(path, lemmas):
    """Проход второй: полные парадигмы отобранных лемм."""
    out = defaultdict(list)
    for line in gzip.open(path, 'rt', encoding='utf-8'):
        p = line.rstrip('\n').split('\t')
        if len(p) < 8:
            continue
        lemma = p[1].lower()
        if lemma in lemmas:
            out[lemma].append((p[0].lower(), p[2], p[3], int(p[6])))
    return out


# ----------------------------------------------------------------------- ход

def main(lexpath):
    print('читаю курс…')
    known = course_tokens()
    gap = gap_words()
    gloss = glosses()
    sentences = pool()
    pool_forms = set()
    for _, sr, _, _ in sentences:
        pool_forms.update(m.group(0).lower() for m in WORD.finditer(sr))

    print('читаю srLex (проход 1)…')
    wanted = known | {w for w, _, _ in gap} | pool_forms
    form2lemma = read_lexicon(lexpath, wanted)
    print('  словоформ опознано: %d из %d' % (len(form2lemma), len(wanted)))

    known_lemmas = {form2lemma[w][0] for w in known if w in form2lemma}
    print('  лемм в курсе: %d' % len(known_lemmas))

    # --- отбор кандидатов ---
    candidates = []
    dropped = defaultdict(int)
    seen = set()
    for word, rank, count in gap:
        entry = form2lemma.get(word)
        if entry is None:
            dropped['нет в srLex'] += 1
            continue
        lemma, upos = entry
        if lemma in seen:
            dropped['форма уже учтённой леммы'] += 1
            continue
        seen.add(lemma)
        if upos not in CONTENT:
            dropped['служебное слово'] += 1
            continue
        if lemma in known_lemmas:
            dropped['курс уже знает'] += 1
            continue
        pos, text = gloss.get(lemma, ('', ''))
        if not text:
            dropped['нет перевода'] += 1
            continue
        if transparent(lemma, text):
            dropped['прозрачно для русского'] += 1
            continue
        candidates.append((rank, lemma, upos, count, text))

    # --- предложения пула, где кандидат стоит в косвенной форме ---
    print('читаю srLex (проход 2)…')
    paradigms = read_paradigms(lexpath, {c[1] for c in candidates})
    form_of = defaultdict(set)
    for lemma, forms in paradigms.items():
        for form, _, _, _ in forms:
            form_of[form].add(lemma)

    examples = defaultdict(list)
    for level, sr, ru, sr_id in sentences:
        for token in {m.group(0).lower() for m in WORD.finditer(sr)}:
            for lemma in form_of.get(token, ()):
                examples[lemma].append((level, token, sr, ru, sr_id))

    out = os.path.join(DATA, 'vocab-candidates.tsv')
    with io.open(out, 'w', encoding='utf-8', newline='\n') as f:
        f.write('rank\tlemma\tpos\tfreq\tforms\tsentences\tgloss\n')
        for rank, lemma, upos, count, text in candidates:
            f.write('%d\t%s\t%s\t%d\t%d\t%d\t%s\n' % (
                rank, lemma, upos, count,
                len(paradigms.get(lemma, ())),
                len(examples.get(lemma, ())),
                text.replace('\t', ' ')[:120],
            ))

    print()
    print('отсеяно:')
    for reason, n in sorted(dropped.items(), key=lambda kv: -kv[1]):
        print('  %-28s %5d' % (reason, n))
    print()
    print('кандидатов: %d' % len(candidates))
    by_pos = defaultdict(int)
    for _, _, upos, _, _ in candidates:
        by_pos[upos] += 1
    print('  по частям речи: %s' % ', '.join(
        '%s %d' % (k, v) for k, v in sorted(by_pos.items(), key=lambda kv: -kv[1])))
    withex = sum(1 for _, lemma, _, _, _ in candidates if examples.get(lemma))
    print('  с примером в пуле: %d' % withex)
    print('записано: %s' % out)


if __name__ == '__main__':
    main(sys.argv[1] if len(sys.argv) > 1 else '/tmp/srlex.gz')
