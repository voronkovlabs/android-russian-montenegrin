# -*- coding: utf-8 -*-
"""
Словарь sr→ru из **блоков перевода** русского Викисловаря.

Зачем ещё один словарь, когда есть `rjecnik-sr-ru.tsv`. Тот собран из статей,
**заголовок которых сербский**, — их в русском Викисловаре 15 135, ровно
столько, сколько волонтёры написали про сербские слова. Покрытие базовой
лексики там дырявое: нет статей для `kuća`, `stan`, `kola`, `plafon`,
`garaža`, `kupatilo`. Из первой тысячи частотных сербских словоформ **475 не
имеют перевода вовсе** — это и есть главная причина, по которой в курсе не
оказалось «пола», «потолка» и «автомобиля».

Здесь берётся другой пласт того же источника: статьи про **русские** слова,
внутри каждой блок переводов, и в нём сербская строка. Русских статей 585 917
против 15 135 сербских — в тридцать девять раз больше, и это ровно те слова,
которые описаны лучше всего, потому что они обиходные.

## Почему берём и хорватский с боснийским

Курс черногорский, то есть **иекавский**: `vrijeme`, а не `vreme`. Сербская
строка перевода даёт экавицу, хорватская и боснийская — иекавицу. Поэтому
правило такое: **слово берём сербское, а написание предпочитаем иекавское, и
только если варианты различаются одним рефлексом ятя.**

Оговорка существенная: хорватский отличается от черногорского не только ятем,
но и лексикой (`tisuća` против `hiljada`, `kruh` против `hljeb`, `vlak`
против `voz`). Взять хорватское слово целиком значило бы натащить в курс
хорватизмов. Поэтому проверка на совпадение после свёртки ятя обязательна:
`vreme`/`vrijeme` — одно слово в двух написаниях, `hljeb`/`kruh` — два разных,
и второе отбрасывается.

    PYTHONUTF8=1 python research/tools/build_gloss_from_translations.py путь/к/dump.jsonl

На выходе `research/data/rjecnik-ru-sr.tsv` в том же виде, что и старый
словарь, чтобы `vocab.glosses()` читал оба одинаково.
"""
import io
import json
import os
import re
import sys
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(os.path.dirname(HERE), 'data')
OUT = os.path.join(DATA, 'rjecnik-ru-sr.tsv')

# Коды, которые для нас один язык в разных написаниях.
CODES = {'sr', 'sh', 'hbs', 'bs', 'hr'}
# Иекавские по норме: из них берём написание, если слово то же самое.
IJEKAV = {'hr', 'bs'}

CYR = {
    'а': 'a', 'б': 'b', 'в': 'v', 'г': 'g', 'д': 'd', 'ђ': 'đ', 'е': 'e',
    'ж': 'ž', 'з': 'z', 'и': 'i', 'ј': 'j', 'к': 'k', 'л': 'l', 'љ': 'lj',
    'м': 'm', 'н': 'n', 'њ': 'nj', 'о': 'o', 'п': 'p', 'р': 'r', 'с': 's',
    'т': 't', 'ћ': 'ć', 'у': 'u', 'ф': 'f', 'х': 'h', 'ц': 'c', 'ч': 'č',
    'џ': 'dž', 'ш': 'š',
}

WORD = re.compile(r'^[a-zA-ZčćžšđČĆŽŠĐ\- ]+$')

# Матерные и грубые толкования. Викисловарь их даёт честно — «sranje» там
# переведено словом, которого в семейном тренажёре быть не должно, а другого
# варианта у статьи нет. Отсеиваем **толкование**, а не слово: если у слова
# остаётся хоть один пристойный вариант, слово живёт с ним. Не осталось —
# выпадает вместе с ним, и это правильный исход: курс учат жена и сын.
RUDE = re.compile(
    r'(?i)(ху[ийё]|пизд|[еёje]ба|бля|муд[ао]к|сра[тнь]|говн|жоп|дроч|залуп'
    r'|шлюх|уёб|уеб|дерьм|трах|мраз|ублюд)')
# Пометы, по которым видно, что «перевод» — не слово, а пояснение.
JUNK = re.compile(r'[()\[\]0-9]|\.\.\.')


def to_latin(word):
    """Кириллица → латиница. Латинское слово возвращается как есть."""
    if not any(ch in CYR for ch in word.lower()):
        return word
    out = []
    for ch in word:
        low = ch.lower()
        rep = CYR.get(low)
        if rep is None:
            out.append(ch)
            continue
        out.append(rep.upper() if ch.isupper() and len(rep) == 1 else rep)
    return ''.join(out)


def fold(word):
    """Свёртка ятя: `vrijeme` и `vreme` сходятся в один вид."""
    return word.lower().replace('ije', 'e').replace('je', 'e')


def clean(word):
    word = word.strip().strip('.,;:!?')
    if not word or JUNK.search(word) or not WORD.match(word):
        return ''
    return word


def collect(path):
    """ru → {код: множество написаний}."""
    pairs = defaultdict(lambda: defaultdict(set))
    pos_of = {}
    seen = 0
    with io.open(path, encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                entry = json.loads(line)
            except ValueError:
                continue
            seen += 1
            ru = (entry.get('word') or '').strip().lower()
            if not ru or ' ' in ru:
                continue
            for tr in (entry.get('translations') or []):
                code = (tr.get('lang_code') or tr.get('code') or '').lower()
                if code not in CODES:
                    continue
                word = clean(to_latin((tr.get('word') or '')))
                if not word or ' ' in word:
                    continue
                pairs[ru][code].add(word.lower())
                pos_of.setdefault(ru, entry.get('pos') or '')
    return pairs, pos_of, seen


def pick(by_code):
    """
    Какие сербские написания оставить.

    Основа — сербский список. Каждое его слово получает иекавское написание,
    если хорватский или боснийский дают **то же самое слово** (совпадение
    после свёртки ятя) в ином виде. Слов, которых в сербском нет вовсе, из
    хорватского не берём: это был бы хорватизм, а не черногорское слово.
    """
    serb = by_code.get('sr') or by_code.get('sh') or by_code.get('hbs') or set()
    if not serb:
        return []
    ijekav = set()
    for code in IJEKAV:
        ijekav |= by_code.get(code, set())

    out = []
    for word in sorted(serb):
        best = word
        for other in ijekav:
            if other != word and fold(other) == fold(word) and len(other) > len(word):
                best = other      # иекавская форма длиннее экавской: ije > e
                break
        out.append(best)
    return out


def main(path):
    pairs, pos_of, seen = collect(path)
    print('статей прочитано: %d' % seen)
    print('русских слов с переводом на сербский: %d' % len(pairs))

    # Переворачиваем: сербское слово → русские толкования.
    back = defaultdict(list)
    for ru, by_code in sorted(pairs.items()):
        for word in pick(by_code):
            if ru not in back[word] and not RUDE.search(ru):
                back[word].append(ru)

    print('сербских слов получилось: %d' % len(back))

    rows = 0
    with io.open(OUT, 'w', encoding='utf-8', newline='\n') as f:
        f.write('lat\tcyr\tpos\tekav\tgloss\n')
        for word in sorted(back):
            if not back[word]:
                continue
            # Толкований у слова бывает много; берём до пяти, чаще всего
            # хватает одного — остальные это синонимы того же значения.
            gloss = ', '.join(back[word][:5])
            f.write('%s\t\t\t\t%s\n' % (word, gloss))
            rows += 1
    print('записано строк: %d -> %s' % (rows, OUT))


if __name__ == '__main__':
    if len(sys.argv) < 2:
        sys.exit('нужен путь к выгрузке kaikki.org (русский раздел, .jsonl)')
    main(sys.argv[1])
