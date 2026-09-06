"""Проверка языка в новых уроках через Haiku.

Смотрит грамматику, управление, порядок слов и опечатки во всех черногорских
фразах указанных уроков. Одна пачка на урок-другой, около цента за прогон.

    PYTHONUTF8=1 python research/tools/vet_lessons.py l10 l11 l12

Знаки конца предложения из проверки исключены намеренно: в заданиях на сборку
фразы и на аудирование их нет по устройству приложения — ответ сверяется без
пунктуации, а в банке слов нет фишки с точкой. Без этой оговорки модель находит
два десятка «ошибок» подряд и настоящие среди них теряются.
"""
import io, json, glob, os, re, sys, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
os.chdir(ROOT)
ASSETS = 'src/app/src/main/assets'
key = ''
for line in io.open('src/local.properties', encoding='utf-8'):
    if line.startswith('ANTHROPIC_API_KEY='):
        key = line.split('=', 1)[1].strip()

def tidy(s):
    """Убирает пробелы перед знаками — их оставляет подстановка вместо ___."""
    return re.sub(r'\s+([,.!?])', r'', re.sub(r'\s+', ' ', s)).strip()


WANT = set(sys.argv[1:]) or {'l10', 'l11', 'l12', 'l13', 'l14'}
items = []
for f in sorted(glob.glob(os.path.join(ASSETS, 'lessons', 'l*.json'))):
    d = json.load(io.open(f, encoding='utf-8'))
    if d['id'] not in WANT:
        continue
    for e in d['exercises']:
        t = e['type']
        if t == 'form':
            filled = e['prompt'].split('(')[0].replace('___', e['answer'])
            items.append((e['id'], tidy(filled), 'подстановка'))
        elif t == 'choice':
            items.append((e['id'], e['answer'], 'верный вариант'))
        elif t == 'word_bank':
            items.append((e['id'], e['answer'], 'сборка фразы'))
        elif t == 'ru_to_me':
            items.append((e['id'], e['reference'], 'эталон перевода «%s»' % e['prompt']))
        elif t == 'me_to_ru':
            items.append((e['id'], e['prompt'], 'фраза для перевода на русский'))
        elif t == 'listening':
            items.append((e['id'], e['audioText'], 'аудирование'))
        elif t in ('speaking', 'repeat'):
            items.append((e['id'], e['phrase'], 'произношение'))
        elif t == 'reading':
            items.append((e['id'], e['text'], 'чтение вслух'))

SYS = (
    "Ты редактор учебника черногорского языка для русскоязычных начинающих. "
    "Дают список фраз из заданий. По каждой скажи, правильна ли она: грамматика, "
    "управление, порядок слов, естественность. Черногорская норма — иекавица и латиница, "
    "но сербские экавские формы ошибкой не считаются. "
    "Опечатки и пропуск диакритики (č, ć, š, ž, đ) — ошибка. "
    "Знаки конца предложения НЕ проверяй: их отсутствие тут намеренно. "
    "Отсутствие точки, запятой или вопросительного знака ошибкой не считай. "
    "Отвечай одним JSON-массивом объектов {\"id\": \"...\", \"ok\": true|false, "
    "\"fix\": \"исправленная фраза или пустая строка\", \"why\": \"коротко по-русски\"}. "
    "Только JSON, без markdown. Для правильных фраз why — пустая строка."
)

user = "\n".join("%s | %s | %s" % (i, s, kind) for i, s, kind in items)

body = json.dumps({
    "model": "claude-haiku-4-5-20251001",
    "max_tokens": 4000,
    "system": SYS,
    "temperature": 0,
    "messages": [{"role": "user", "content": user},
                 {"role": "assistant", "content": "["}],
}).encode('utf-8')

req = urllib.request.Request(
    "https://api.anthropic.com/v1/messages", data=body,
    headers={"x-api-key": key, "anthropic-version": "2023-06-01",
             "content-type": "application/json"})
with urllib.request.urlopen(req) as r:
    out = json.load(r)

usage = out["usage"]
cost = usage["input_tokens"] / 1e6 + usage["output_tokens"] * 5 / 1e6
text = "[" + out["content"][0]["text"]
# отрезаем хвост после закрывающей скобки массива
depth = 0
end = len(text)
in_str = False
esc = False
for i, c in enumerate(text):
    if esc:
        esc = False
    elif in_str and c == '\\':
        esc = True
    elif c == '"':
        in_str = not in_str
    elif in_str:
        pass
    elif c == '[':
        depth += 1
    elif c == ']':
        depth -= 1
        if depth == 0:
            end = i + 1
            break
rows = json.loads(text[:end])

bad = [r for r in rows if not r.get('ok', True)]
print("проверено фраз: %d" % len(items))
print("расход: %d ввод, %d вывод, %.4f$" % (usage["input_tokens"], usage["output_tokens"], cost))
print("замечаний: %d" % len(bad))
print()
for r in bad:
    src = next((s for i, s, _ in items if i == r['id']), '?')
    print("  %s  %s" % (r['id'], src))
    print("      → %s" % r.get('fix', ''))
    print("      %s" % r.get('why', ''))
