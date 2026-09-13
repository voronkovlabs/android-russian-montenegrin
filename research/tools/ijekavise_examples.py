# -*- coding: utf-8 -*-
"""
Экавица в примерах словаря: чиним предложения, но не леммы.

Заведено по жалобе (issue 69, 13.09.2026): «проверь, почему здесь слова в
сербском варианте». Пул предложений собран из сербского источника, поэтому в
примерах попадалось «Uvek postoji sutra» вместо «Uvijek postoji sjutra».

**Почему это отдельный шаг, а не правка пула.** Соблазн исправить сам
`pool-ranked.tsv` велик, и он неверен: пул — не текст для показа, а подложка
для поиска. Примеры находятся сопоставлением слов предложения с формами из
srLex, а srLex сербский и экавский. Переписав пул в иекавицу, мы бы оторвали
его от srLex, и у слов просто пропали бы примеры.

Поэтому чистка идёт **после** `build_vocab.py`, по готовому `words.json`, и
входит в конвейер:

    PYTHONUTF8=1 python research/tools/build_vocab.py путь/к/srlex.gz
    PYTHONUTF8=1 python research/tools/ijekavise_examples.py --write

Без `--write` печатает, что собирается сделать, и ничего не трогает.

**Чего этот шаг не делает и не должен.** Не трогает леммы: лемма — ключ
карточки, смена ключа обнуляет прогресс по слову. Не трогает целевую форму
примера (`ex.f`): карточка строит задание вокруг неё, и подменив её в
предложении, мы оторвали бы пример от вопроса. Отсюда смешанные предложения у
слов с экавской леммой — «Uvijek sam voleo knjige»; это граница возможного, а
про саму лемму приложению есть что сказать отдельно (`LocalCheck.reflexNote`).

Замена — **словом целиком по списку**, не подстрокой: «deo» сидит внутри
«odeo», «pre» внутри «prema».
"""
import io
import json
import re
import sys

W = 'D:/Projects/voronkov/MonteLang/src/app/src/main/assets/vocab/words.json'

# Экавская форма -> черногорская, словом целиком. Подстрокой нельзя: «deo»
# сидит внутри «odeo», «pre» внутри «prema».
PAIRS = {
    'uvek': 'uvijek', 'covek': 'covjek', 'čovek': 'čovjek', 'čoveka': 'čovjeka',
    'čoveku': 'čovjeku', 'ljudi': 'ljudi',
    'vreme': 'vrijeme', 'vremenu': 'vremenu', 'mesto': 'mjesto', 'mesta': 'mjesta',
    'mestu': 'mjestu', 'lepo': 'lijepo', 'lep': 'lijep', 'lepa': 'lijepa',
    'lepu': 'lijepu', 'lepe': 'lijepe', 'lepi': 'lijepi',
    'gde': 'gdje', 'ovde': 'ovdje', 'nigde': 'nigdje', 'negde': 'negdje',
    'posle': 'poslije', 'pre': 'prije', 'deca': 'djeca', 'decu': 'djecu',
    'dece': 'djece', 'deci': 'djeci', 'dete': 'dijete', 'deteta': 'djeteta',
    'devojka': 'djevojka', 'devojku': 'djevojku', 'devojke': 'djevojke',
    'nedelja': 'nedjelja', 'nedelje': 'nedjelje', 'nedelju': 'nedjelju',
    'mleko': 'mlijeko', 'mleka': 'mlijeka', 'sever': 'sjever',
    'rešenje': 'rješenje', 'rešenja': 'rješenja', 'rešenju': 'rješenju',
    'sutra': 'sjutra', 'deo': 'dio', 'dela': 'dijela', 'delo': 'djelo',
    'delove': 'dijelove', 'delova': 'dijelova',
    'ceo': 'cio', 'cela': 'cijela', 'celo': 'cijelo', 'celu': 'cijelu',
    'cena': 'cijena', 'cene': 'cijene', 'cenu': 'cijenu',
    'verovati': 'vjerovati', 'verujem': 'vjerujem', 'veruje': 'vjeruje',
    'vera': 'vjera', 'veru': 'vjeru', 'vetar': 'vjetar', 'vetra': 'vjetra',
    'vest': 'vijest', 'vesti': 'vijesti',
    'svet': 'svijet', 'sveta': 'svijeta', 'svetu': 'svijetu',
    'sneg': 'snijeg', 'snega': 'snijega', 'reka': 'rijeka', 'reke': 'rijeke',
    'reku': 'rijeku', 'telo': 'tijelo', 'tela': 'tijela',
    'smeh': 'smijeh', 'smeje': 'smije', 'seo': 'sio',
    'razumeti': 'razumjeti', 'razumem': 'razumijem', 'razume': 'razumije',
    'razumeo': 'razumio',
    'voleti': 'voljeti', 'volela': 'voljela', 'voleo': 'volio',
    'živeti': 'živjeti', 'živeo': 'živio', 'živela': 'živjela',
    'videti': 'vidjeti', 'video': 'vidio', 'videla': 'vidjela',
    'hteti': 'htjeti', 'hteo': 'htio', 'htela': 'htjela',
    'umeti': 'umjeti', 'umeo': 'umio', 'uspeti': 'uspjeti', 'uspeo': 'uspio',
    'leto': 'ljeto', 'leta': 'ljeta', 'lekar': 'ljekar', 'lekara': 'ljekara',
    'mesec': 'mjesec', 'meseca': 'mjeseca', 'meseci': 'mjeseci',
    'promeniti': 'promijeniti', 'promenio': 'promijenio',
    'primer': 'primjer', 'primera': 'primjera', 'primere': 'primjere',
    'beo': 'bio', 'bela': 'bijela', 'belo': 'bijelo', 'beli': 'bijeli',
    'hleb': 'hljeb', 'hleba': 'hljeba', 'hlebom': 'hljebom',
    'sede': 'sjede', 'sedi': 'sjedi', 'sedeo': 'sjedio',
    'nedeljom': 'nedjeljom', 'obećanje': 'obećanje',
    'uspeh': 'uspjeh', 'uspeha': 'uspjeha', 'uspehu': 'uspjehu',
    'greh': 'grijeh', 'presek': 'presjek', 'odeljenje': 'odjeljenje',
    'stepen': 'stepen', 'zvezda': 'zvijezda', 'zvezde': 'zvijezde',
    'strela': 'strijela', 'sredina': 'sredina', 'nasledio': 'naslijedio',
    'poreklo': 'porijeklo', 'pomera': 'pomjera', 'pomeriti': 'pomjeriti',
    'nasleđe': 'nasljeđe', 'razumevanje': 'razumijevanje',
    'obaveštenje': 'obavještenje', 'primeti': 'primijeti',
    'primetio': 'primijetio', 'sledeći': 'sljedeći', 'sledeće': 'sljedeće', 'korist': 'korist', 'nedostaje': 'nedostaje',
}


def keep_case(src, repl):
    return repl.capitalize() if src[:1].isupper() else repl


def fix(sentence, protect):
    """Заменить экавские слова, не трогая целевую форму карточки."""
    changed = []

    def sub(m):
        w = m.group(0)
        low = w.lower()
        if low in protect:
            return w
        r = PAIRS.get(low)
        if not r or r == low:
            return w
        changed.append((w, keep_case(w, r)))
        return keep_case(w, r)

    out = re.sub(r"[A-Za-zČčĆćŠšŽžĐđ]+", sub, sentence)
    return out, changed


d = json.load(io.open(W, encoding='utf-8'))
report = []
touched = 0

for x in d['words']:
    # Целевые формы слова трогать нельзя: карточка строит задание вокруг
    # конкретной формы (`ex.f`), и заменив её в предложении, мы оторвём
    # пример от того, что спрашивают. Сама лемма — ключ карточки, её тем
    # более не трогаем: смена ключа обнулила бы прогресс.
    protect = {x['id'].lower()}
    protect.update(f['f'].lower() for f in (x.get('forms') or []) if f.get('f'))
    for ex in (x.get('ex') or []):
        if ex.get('f'):
            protect.add(ex['f'].lower())
        new, changed = fix(ex['sr'], protect)
        if changed:
            touched += 1
            report.append((x['id'], ex['sr'], new, changed))
            ex['sr'] = new

print('исправлено примеров: %d' % touched)
for wid, old, new, ch in report:
    print('  %-14s %s' % (wid, old))
    print('  %-14s %s   [%s]' % ('', new, ', '.join('%s→%s' % c for c in ch)))

if '--write' in sys.argv:
    io.open(W, 'w', encoding='utf-8', newline='\n').write(
        json.dumps(d, ensure_ascii=False, separators=(',', ':')))
    print('\nзаписано')
