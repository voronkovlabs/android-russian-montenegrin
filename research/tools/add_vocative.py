# -*- coding: utf-8 -*-
"""
Звательный падеж в парадигмы словаря — без пересборки словаря целиком.

## Зачем

Владелец, 02.10.2026: «надо включить все падежи». Перевёртыши форм спрашивали
все падежи, кроме звательного, а его не было в самих данных: сборка словаря
(`build_vocab.pick`) берёт только падежи из `CASES` и только формы с ненулевой
частотой по корпусу, а звательного в `CASES` не было вовсе.

## Почему отдельным шагом, а не правкой `pick`

Пересборка словаря целиком — самая рискованная операция с данными в проекте:
однажды она молча потеряла 88 лемм, а лемма — ключ карточки. Звательный же
ничего, кроме парадигм, не трогает. Этот скрипт дописывает одну ячейку в
`forms-*.json` и одну подпись с рамкой в `words.json`, а слова, толкования и
порядок оставляет байт в байт. `build_vocab.py` зовёт его в конце, так что
пересборка звательный не потеряет.

## Что берётся

* **только единственное число.** Звательный множественного у всех слов
  совпадает с именительным множественного («prijatelji!», «ljudi!»), а тот в
  перевёртышах уже есть;
* **и нулевая частота тоже** — в отличие от прочих падежей. Звательный в
  корпусе — редкость по природе: в газетном тексте никто никого не окликает.
  Ненулевая частота нашлась у 738 слов из 2573 (`majko`, `gospodine`,
  `prijatelju`, `sine`); остальные выведены лексиконом по образцу склонения
  (`grade`, `kućo`). Форма при этом верная грамматически — она просто редко
  нужна; так решил владелец («все падежи»);
* **иекавская форма вперёд сербской**, как везде в словаре: `čovječe`, а не
  `čoveče`.

## Как выбирается форма, когда вариантов несколько

У 311 слов из 2562 лексикон даёт несколько разных звательных, и **ни частота,
ни правило тут не годятся** — проверено на этом же прогоне:

* частоту набирают омонимы: `metak → meče` (143 — это «матч»), `početak →
  počeče` и `predak → preče` (глагол и наречие), `otac → otče` («Otče naš»),
  `student → studentu`;
* правило окончаний чинит одно и ломает другое: чередования (`vitez → viteže`)
  и беглое «а» (`policajac → policajče`) в простое правило не укладываются.

Поэтому спорное решается **только рукой** (`FIX`) — и только для тех, к кому в
жизни обращаются: людей и животных. Остальные спорные слова — неодушевлённые
вроде `metak`, `početak`, `kontinent` — остаются **без звательного**: пустая
ячейка лучше неверной, а окликать их никто не станет.

Где вариант один, он берётся как есть: это форма, выведенная лексиконом по
образцу склонения, без всякого угадывания.

## Запуск

    PYTHONUTF8=1 python research/tools/add_vocative.py /путь/к/srLex_v1.3.gz

Идемпотентно: у кого `v-s` уже есть, тому не дописывается.
"""
import glob
import gzip
import io
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from vocab import LEMMA_FIX
from build_vocab import reflex

ASSETS = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                      '..', '..', 'src', 'app', 'src', 'main', 'assets', 'vocab')

SLOT = 'v-s'
LABEL = 'зв. ед.'
# Рамка — приветствие, а не «Hej»: короткое «hej» движок распознавания слышит
# то как «ej», то никак, а «zdravo» берёт уверенно. Окликают именно так.
FRAME = 'Zdravo, ___!'


def nouns(where):
    """Леммы с падежными формами — по самим полосам, а не по `words.json`."""
    out = set()
    for path in glob.glob(os.path.join(where, 'forms-*.json')):
        for lemma, forms in json.load(io.open(path, encoding='utf-8'))['forms'].items():
            if any(not f['s'].startswith('V') for f in forms):
                out.add(lemma)
    return out


def read(lexpath, lemmas):
    """Звательный единственного из лексикона: лемма → [(форма, частота)]."""
    found = {}
    for line in gzip.open(lexpath, 'rt', encoding='utf-8'):
        p = line.rstrip('\n').split('\t')
        if len(p) < 7:
            continue
        msd = p[2]
        # N c <род> <число> <падеж>: существительное, единственное, звательный.
        if len(msd) < 5 or msd[0] != 'N' or msd[3] != 's' or msd[4] != 'v':
            continue
        lemma = LEMMA_FIX.get(p[1].lower(), p[1].lower())
        if lemma in lemmas:
            found.setdefault(lemma, []).append((p[0].lower(), int(p[6])))
    return found


# Спорное — только рукой: те, к кому обращаются. У `-ar` берётся `-aru`
# (норма допускает и `-are`), иекавское написание — как в остальном словаре.
FIX = {
    'otac': 'oče',          # у лексикона только книжное «otče»
    'pas': 'pse',           # у лексикона «psu», «pase», «pasu»
    'dečak': 'dječače',
    'mladić': 'mladiću',
    'bratić': 'bratiću',
    'burazer': 'burazeru',
    'vitez': 'viteže',
    'policajac': 'policajče',
    'domorodac': 'domorodče',
    'očevidac': 'očevidče',
    'talac': 'talče',
    'predak': 'preče',
    'student': 'studente',
    'pacijent': 'pacijente',
    'protestant': 'protestante',
    'mutant': 'mutante',
    'baron': 'barone',
    'pastir': 'pastiru',
    'lekar': 'ljekaru',
    'pijanica': 'pijanice',
    'prodavačica': 'prodavačice',
    'pevac': 'pijevče',
    'medved': 'medvjede',
    'zver': 'zvijeri',
    'tigar': 'tigre',
    'dabar': 'dabre',
    'ris': 'rise',
    'kamila': 'kamilo',
    # Не люди, но к ним обращаются — в песне и в сердцах: «Narode moj!»
    'narod': 'narode',
    'zvezda': 'zvijezdo',
    'gora': 'goro',
}
for _w in ('alhemičar apotekar bankar bibliotekar bolničar bubnjar fizičar '
           'gospodar gusar hemičar jaguar kockar kolekcionar komičar krijumčar '
           'kuhar kuvar lešinar mađioničar matematičar mesar musketar muzičar '
           'političar poštar revolucionar ribar sisar slikar stanar stolar '
           'takmičar vrtlar zubar').split():
    FIX[_w] = _w + 'u'


def pick(lemma, variants):
    """Одна форма или `None`, если вариантов несколько, а руки до слова нет."""
    if lemma in FIX:
        return FIX[lemma]
    # Двойники по рефлексу (čoveče/čovječe) — одно слово в двух нормах:
    # берём иекавское, курс черногорский.
    best = {}
    for form, freq in variants:
        key = reflex(form)
        if key not in best or ('je' in form, freq) > ('je' in best[key][0], best[key][1]):
            best[key] = (form, freq)
    if len(best) == 1:
        return next(iter(best.values()))[0]
    return None


def apply(lexpath, where=ASSETS):
    lemmas = nouns(where)
    found = read(lexpath, lemmas)
    added = 0
    dropped = []
    for path in sorted(glob.glob(os.path.join(where, 'forms-*.json'))):
        band = json.load(io.open(path, encoding='utf-8'))
        changed = False
        for lemma, forms in band['forms'].items():
            if lemma not in found or any(f['s'] == SLOT for f in forms):
                continue
            form = pick(lemma, found[lemma])
            if form is None:
                dropped.append(lemma)
                continue
            forms.append({'f': form, 's': SLOT})
            added += 1
            changed = True
        if changed:
            io.open(path, 'w', encoding='utf-8', newline='').write(
                json.dumps(band, ensure_ascii=False, separators=(',', ':')))

    # Подпись и рамка — в `words.json`, рядом с остальными. Порядок ключей
    # сохраняется: слова в файле не трогаются вовсе.
    path = os.path.join(where, 'words.json')
    data = json.load(io.open(path, encoding='utf-8'))
    data['frames'][SLOT] = FRAME
    data['slots'][SLOT] = LABEL
    io.open(path, 'w', encoding='utf-8', newline='').write(
        json.dumps(data, ensure_ascii=False, separators=(',', ':')))

    print('звательный дописан: %d из %d существительных' % (added, len(lemmas)))
    if dropped:
        print('без звательного, спорные и не в списке FIX: %d — %s'
              % (len(dropped), ', '.join(sorted(dropped))))
    missing = sorted(lemmas - set(found))
    if missing:
        print('нет в лексиконе: %d — %s' % (len(missing), ', '.join(missing[:20])))
    return added


if __name__ == '__main__':
    apply(sys.argv[1] if len(sys.argv) > 1 else '/tmp/srlex.gz')
