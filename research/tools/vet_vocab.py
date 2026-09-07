"""Вычитка кандидатов в словарные карточки через Haiku.

Автоматический отбор (`vocab.py`) снимает то, что решается разбором: чтения
берутся самые частые по корпусу, служебные части речи и пройденное курсом
выбрасываются. Остаётся то, что разбором не решается, — неверные статьи
Викисловаря («priča — сербская фамилия», хотя это «рассказ») и слова, которые
русскому учить незачем. Это и спрашиваем у модели.

    PYTHONUTF8=1 python research/tools/vet_vocab.py            # весь список
    PYTHONUTF8=1 python research/tools/vet_vocab.py --limit 1  # одна пачка

Результат дописывается в `data/vocab-vetted.tsv` пачками, и повторный запуск
продолжает с того места, где остановился: за проверенное платить второй раз
незачем. **Если `vocab-candidates.tsv` пересобран, файл вычитки надо удалить**
— нумерация в нём своя, и продолжение легло бы не на те слова.

Модель отвечает строкой только про те слова, с которыми что-то не так, а в
конце — числом просмотренных. JSON-объект на каждое слово стоил бы втрое
дороже самих вердиктов: обёртка длиннее ответа. Весь список выходит центов в
шесть.
"""
import io
import json
import os
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
os.chdir(ROOT)
DATA = os.path.join('research', 'data')

SRC = os.path.join(DATA, 'vocab-candidates.tsv')
OUT = os.path.join(DATA, 'vocab-vetted.tsv')

BATCH = 80
MODEL = 'claude-haiku-4-5-20251001'

VERDICTS = {'gloss', 'trap', 'drop_transparent', 'drop_name', 'drop_rare', 'drop_grammar'}

SYS = (
    "Ты редактор словаря черногорского языка. Ученик — русскоязычный "
    "начинающий, живёт в Черногории, хочет разговаривать. Дают список слов: "
    "номер, лемма, часть речи, толкование из Викисловаря.\n"
    "\n"
    "Суди о слове, а не о толковании. Толкование — подсказка, и оно нередко "
    "неверно: сперва вспомни, что слово значит само по себе. Худший случай — "
    "правдоподобная ложь вроде «priča — сербская фамилия» (на самом деле "
    "«рассказ») или «beba — сербская фамилия» («младенец»). Такое слово надо "
    "не выбросить, а исправить.\n"
    "\n"
    "Толкование «?» значит, что его нет: в словаре у слова нашлась одна "
    "лишь статья про фамилию. Дай перевод сам — вердикт gloss.\n"
    "\n"
    "Отвечай строкой ТОЛЬКО про те слова, с которыми что-то не так. Формат "
    "строки: номер|вердикт|исправление|почему\n"
    "\n"
    "gloss — слово годится, но толкование неверное или сбивает с толку. "
    "В исправлении короткий верный перевод: одно-три слова, без помет.\n"
    "trap — ложный друг: похоже на русское слово, а значит другое. "
    "В исправлении верный перевод, в «почему» — с чем путается.\n"
    "drop_transparent — русский поймёт сходу, без обучения и без контекста: "
    "тот же корень, то же значение. Сомневаешься — не выбрасывай.\n"
    "drop_name — имя собственное и только оно: фамилия, имя, топоним, у "
    "которого нет нарицательного значения.\n"
    "drop_rare — узкоспециальное, устаревшее или диалектное: начинающему "
    "не нужно.\n"
    "drop_grammar — служебное слово: предлог, союз, частица, местоимение, "
    "вспомогательный глагол. Наречия времени, места, меры и образа действия "
    "(nikad, odmah, negde, ovamo, naravno) служебными НЕ считай — это обычные "
    "слова, и учить их надо.\n"
    "\n"
    "Слова, с которыми всё в порядке, не упоминай вовсе.\n"
    "Последней строкой напиши TOTAL|<сколько слов было в списке>.\n"
    "Никаких вступлений, пояснений и markdown — только строки."
)


def key():
    for line in io.open('src/local.properties', encoding='utf-8'):
        if line.startswith('ANTHROPIC_API_KEY='):
            return line.split('=', 1)[1].strip()
    return ''


def candidates():
    rows = []
    with io.open(SRC, encoding='utf-8') as f:
        head = next(f).rstrip('\n').split('\t')
        i = {name: n for n, name in enumerate(head)}
        for line in f:
            p = line.rstrip('\n').split('\t')
            if len(p) < len(head):
                continue
            rows.append({
                'order': int(p[i['order']]),
                'lemma': p[i['lemma']],
                'pos': p[i['pos']],
                'gloss': p[i['gloss']],
            })
    return rows


def done_ids():
    """Что уже проверено: повторный прогон не платит за это второй раз."""
    if not os.path.exists(OUT):
        return set()
    seen = set()
    with io.open(OUT, encoding='utf-8') as f:
        next(f, None)
        for line in f:
            p = line.split('\t')
            if p and p[0].isdigit():
                seen.add(int(p[0]))
    return seen


def ask(api_key, batch):
    user = '\n'.join(
        '%d | %s | %s | %s' % (r['order'], r['lemma'], r['pos'], r['gloss'][:90])
        for r in batch
    )
    body = json.dumps({
        'model': MODEL,
        'max_tokens': 2000,
        'system': SYS,
        'temperature': 0,
        'messages': [{'role': 'user', 'content': user}],
    }).encode('utf-8')
    req = urllib.request.Request(
        'https://api.anthropic.com/v1/messages', data=body,
        headers={'x-api-key': api_key, 'anthropic-version': '2023-06-01',
                 'content-type': 'application/json'})
    with urllib.request.urlopen(req) as r:
        out = json.load(r)
    return out['content'][0]['text'], out['usage']


def parse(text, batch):
    """
    Строки вердиктов и признак того, что модель прошла список целиком.

    Молчание про слово значит «всё в порядке», поэтому важно убедиться, что
    модель вообще досмотрела до конца: без строки TOTAL пропуск половины
    списка выглядел бы как полсотни безупречных слов.
    """
    ids = {r['order'] for r in batch}
    total = None
    found = {}
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        parts = [p.strip() for p in line.split('|')]
        if parts[0].upper() == 'TOTAL' and len(parts) > 1:
            total = int(parts[1]) if parts[1].isdigit() else None
            continue
        if len(parts) < 2 or not parts[0].isdigit():
            continue
        order, verdict = int(parts[0]), parts[1]
        if order not in ids or verdict not in VERDICTS:
            continue
        found[order] = (
            verdict,
            parts[2] if len(parts) > 2 else '',
            parts[3] if len(parts) > 3 else '',
        )
    return found, total


FINAL = os.path.join(DATA, 'vocab-words.tsv')


def merge():
    """
    Свести отбор и вычитку в готовый список слов.

    Из вердиктов модели берутся **только исправленные толкования**. Вердикты
    «выбросить» и «ложный друг» в список не применяются, а переносятся в
    колонку `doubt` как подозрение — удалять по ним нельзя. Проверка на глаз
    показала, что здесь модель ошибается примерно в каждом десятом случае, и
    ошибается в обе стороны: `zahvaljivati` («благодарить») и `kovač`
    («кузнец») она объявила служебным словом и фамилией, а из трёх найденных
    «ложных друзей» неверны все три — `olovka` это как раз карандаш, `uzak`
    это «узкий», а `bilion` не миллиард.

    Молча терять живое слово хуже, чем оставить в списке мусор: мусор видно
    при первой же встрече, а пропажу — никогда.
    """
    fixes, doubts = {}, {}
    with io.open(OUT, encoding='utf-8') as f:
        next(f)
        for line in f:
            p = line.rstrip('\n').split('\t')
            if len(p) < 7 or not p[0].isdigit():
                continue
            order, verdict, fix, why = int(p[0]), p[3], p[4], p[5]
            if verdict == 'gloss' and fix:
                fixes[order] = fix
            elif verdict != 'ok':
                doubts[order] = verdict + (': ' + why if why else '')

    src = io.open(SRC, encoding='utf-8')
    head = next(src).rstrip('\n').split('\t')
    i = {name: n for n, name in enumerate(head)}
    rows = []
    for line in src:
        p = line.rstrip('\n').split('\t')
        if len(p) < len(head):
            continue
        order = int(p[i['order']])
        rows.append((order, p[i['lemma']], p[i['pos']], p[i['spoken']],
                     p[i['forms']], p[i['sentences']],
                     fixes.get(order, p[i['gloss']]), doubts.get(order, '')))
    src.close()

    with io.open(FINAL, 'w', encoding='utf-8', newline='\n') as f:
        f.write('order\tlemma\tpos\tfreq\tforms\tsentences\tgloss\tdoubt\n')
        for r in rows:
            f.write('\t'.join(str(x) for x in r) + '\n')

    print('слов в списке: %d, толкований исправлено: %d, под сомнением: %d'
          % (len(rows), len(fixes), len(doubts)))
    print('записано: %s' % FINAL)


def main():
    limit = None
    if '--limit' in sys.argv:
        limit = int(sys.argv[sys.argv.index('--limit') + 1])
    if '--merge' in sys.argv:
        merge()
        return

    api_key = key()
    if not api_key:
        sys.exit('ANTHROPIC_API_KEY не найден в src/local.properties')

    rows = candidates()
    seen = done_ids()
    todo = [r for r in rows if r['order'] not in seen]
    batches = [todo[i:i + BATCH] for i in range(0, len(todo), BATCH)]
    if limit is not None:
        batches = batches[:limit]

    print('проверено раньше: %d, осталось: %d, пачек сейчас: %d'
          % (len(seen), len(todo), len(batches)))

    new = not os.path.exists(OUT)
    f = io.open(OUT, 'a', encoding='utf-8', newline='\n')
    if new:
        f.write('order\tlemma\tpos\tverdict\tfix\twhy\tgloss\n')

    spent = 0.0
    tally = {}
    for n, batch in enumerate(batches, 1):
        text, usage = ask(api_key, batch)
        spent += usage['input_tokens'] / 1e6 + usage['output_tokens'] * 5 / 1e6
        found, total = parse(text, batch)
        note = '' if total == len(batch) else \
            '  ВНИМАНИЕ: модель насчитала %s из %d' % (total, len(batch))
        for row in batch:
            verdict, fix, why = found.get(row['order'], ('ok', '', ''))
            tally[verdict] = tally.get(verdict, 0) + 1
            f.write('%d\t%s\t%s\t%s\t%s\t%s\t%s\n' % (
                row['order'], row['lemma'], row['pos'], verdict, fix, why,
                row['gloss'].replace('\t', ' ')))
        f.flush()
        print('  пачка %d/%d: замечаний %d из %d, потрачено %.4f$%s'
              % (n, len(batches), len(found), len(batch), spent, note))
    f.close()

    print()
    print('расход: %.4f$' % spent)
    for verdict, count in sorted(tally.items(), key=lambda kv: -kv[1]):
        print('  %-18s %4d' % (verdict, count))
    print('записано: %s' % OUT)
    merge()


if __name__ == '__main__':
    main()
