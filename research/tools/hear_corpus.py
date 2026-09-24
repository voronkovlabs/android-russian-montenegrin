# -*- coding: utf-8 -*-
"""Слушать наш синтезатор чужим ухом.

Телефон выписывает корпус в звуковые файлы («Записать корпус в файлы» на экране
прогона), а расшифровывает их **Whisper на этой машине**. Смысл ровно в этом: в
самом телефоне и голос, и распознаватель от Google, на одной языковой модели, —
потому в правилах прогона и сказано, что зачёт там не значит ничего. Здесь
слушает тот, кто фразу не произносил.

Особенно это важно заданиям «на слух»: там источник звука — наш синтезатор, и
если он коверкает слово, задание учит неверному произношению. Внутри телефона
заметить это нечем.

    python research/tools/hear_corpus.py --pull
    python research/tools/hear_corpus.py --dir ПАПКА --model medium
    python research/tools/hear_corpus.py --pull --limit 40

**Провал — улика против синтезатора, зачёт про живой голос не говорит ничего.**
Whisper слушает чисто и без комнаты; человек услышит хуже. Это сито, а не
оценка.

Сверка — перенос `LocalCheck` из приложения (normalize → flatten → reflex →
close → наибольшая общая подпоследовательность), чтобы число значило то же
самое, что на телефоне. Чего в переносе нет: таблицы числительных `CANON`. Она
заведена под то, что распознаватель Google сам пишет числа цифрами; Whisper
делает так же, но не всегда, — поэтому строки с цифрами помечаются, чтобы
разницу было видно, а не списывать её на текст.
"""
import argparse
import os
import re
import subprocess
import sys
from datetime import datetime

ADB = os.environ.get('ADB', 'adb')
PHONE_DIR = '/sdcard/Android/data/com.montelearn/files/korpus-audio'
LIST_NAME = 'spisok.tsv'
PASS = 0.75          # story.readingPass — тот же порог, что в приложении
GLUED_PASS = 0.85    # посимвольное сходство: им и выносится вердикт
SLACK = 2            # speech.spokenSlack

SPECIAL = [
    ('sjutra', 'sutra'), ('śutra', 'sutra'), ('ovđe', 'ovde'),
    ('onđe', 'onde'), ('đe', 'gde'), ('śever', 'sever'),
    ('iđem', 'idem'),
]
WHOLE = [(re.compile(r'\bdio\b'), 'deo'), (re.compile(r'\bcio\b'), 'ceo')]


# Сербский двуписьменный, и Whisper расшифровывает то латиницей, то кириллицей:
# «Моје отец је из Никшића» — верная расшифровка, но нашей латинской сверке она
# даёт ноль из пяти. Поэтому до всего прочего кириллица переводится в латиницу;
# соответствие однозначно, спорных мест нет.
CYR = {
    'а': 'a', 'б': 'b', 'в': 'v', 'г': 'g', 'д': 'd', 'ђ': 'đ', 'е': 'e',
    'ж': 'ž', 'з': 'z', 'и': 'i', 'ј': 'j', 'к': 'k', 'л': 'l', 'љ': 'lj',
    'м': 'm', 'н': 'n', 'њ': 'nj', 'о': 'o', 'п': 'p', 'р': 'r', 'с': 's',
    'т': 't', 'ћ': 'ć', 'у': 'u', 'ф': 'f', 'х': 'h', 'ц': 'c', 'ч': 'č',
    'џ': 'dž', 'ш': 'š',
}


def latin(s):
    return ''.join(CYR.get(ch, CYR.get(ch.lower(), ch)) for ch in s)


def normalize(s):
    """Оставляем только буквы и цифры, всё прочее — пробел (см. LocalCheck)."""
    s = latin(s)
    out = []
    for ch in s.strip().lower():
        out.append(ch if (ch.isalpha() or ch.isdigit()) else ' ')
    return ' '.join(''.join(out).split())


def flatten(s):
    s = normalize(s)
    for a, b in (('č', 'c'), ('ć', 'c'), ('š', 's'),
                 ('ž', 'z'), ('đ', 'dj')):
        s = s.replace(a, b)
    return s


def reflex(s):
    for a, b in SPECIAL:
        s = s.replace(a, b)
    for rx, b in WHOLE:
        s = rx.sub(b, s)
    return s.replace('ije', 'e').replace('je', 'e')


def words(s):
    return [w for w in reflex(flatten(s)).split(' ') if w]


def distance(a, b):
    prev = list(range(len(b) + 1))
    for i in range(1, len(a) + 1):
        cur = [i] + [0] * len(b)
        for j in range(1, len(b) + 1):
            cur[j] = min(prev[j - 1] + (a[i - 1] != b[j - 1]),
                         prev[j] + 1, cur[j - 1] + 1)
        prev = cur
    return prev[len(b)]


def close(a, b):
    """Скидка по знакам — та же, что у речи в приложении."""
    if a == b:
        return True
    longest = max(len(a), len(b))
    slack = 0 if longest < 4 else (1 if longest < 7 else SLACK)
    if slack == 0 or abs(len(a) - len(b)) > slack:
        return False
    return distance(a, b) <= slack


def table(want, got):
    dp = [[0] * (len(got) + 1) for _ in range(len(want) + 1)]
    for i in range(len(want)):
        for j in range(len(got)):
            if close(want[i], got[j]):
                dp[i + 1][j + 1] = dp[i][j] + 1
            else:
                dp[i + 1][j + 1] = max(dp[i][j + 1], dp[i + 1][j])
    return dp


def glued(heard, expected):
    """Сходство без пробелов: границы слов ставит слушатель, а не говорящий.

    Главная мера этого стенда, и вот почему. Whisper расставляет пробелы
    по-своему: «Moja majka je iz Bara» он отдаёт как «Mojamajka je izbara», а
    «Danas idem u grad» — как «Danas i demograd». Звуки при этом расслышаны
    верно, но сверка по словам обнуляет два слова разом и объявляет провал.

    Это улика против расшифровки, а не против произношения: склеенные строки
    различаются одной буквой. Спрашиваем мы «внятно ли сказано», а границы слов
    к внятности отношения не имеют.

    Счёт по словам при этом остаётся в отчёте: он сравним с телефоном, и по
    нему видно, где разошлись именно границы.
    """
    a = reflex(flatten(expected)).replace(' ', '')
    b = reflex(flatten(heard)).replace(' ', '')
    if not a:
        return 0.0
    return 1.0 - distance(a, b) / float(max(len(a), len(b)))


def score(heard, expected):
    """Доля слов эталона, прозвучавших по порядку."""
    want, got = words(expected), words(heard)
    if not want:
        return 0, 0
    return table(want, got)[len(want)][len(got)], len(want)


def missed(heard, expected):
    """Какие слова эталона не прозвучали — та же таблица, обратным ходом."""
    want, got = words(expected), words(heard)
    if not want:
        return []
    dp = table(want, got)
    out, i, j = [], len(want), len(got)
    while i > 0:
        if j > 0 and close(want[i - 1], got[j - 1]) and dp[i][j] == dp[i - 1][j - 1] + 1:
            i, j = i - 1, j - 1
        elif j == 0 or dp[i - 1][j] >= dp[i][j - 1]:
            out.append(want[i - 1])
            i -= 1
        else:
            j -= 1
    return list(reversed(out))


def pull(dest):
    subprocess.run([ADB, 'pull', PHONE_DIR, dest], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return os.path.join(dest, os.path.basename(PHONE_DIR))


def read_list(folder):
    rows = []
    with open(os.path.join(folder, LIST_NAME), encoding='utf-8') as f:
        for n, line in enumerate(f):
            if n == 0 or not line.strip():
                continue
            parts = line.rstrip('\n').split('\t')
            if len(parts) >= 4:
                rows.append(dict(file=parts[0], id=parts[1],
                                 source=parts[2], text=parts[3]))
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--pull', action='store_true', help='забрать файлы с телефона')
    ap.add_argument('--dir', help='папка с файлами и описью')
    ap.add_argument('--model', default='small', help='tiny | base | small | medium | large-v3')
    ap.add_argument('--limit', type=int, default=0, help='слушать только первые N')
    ap.add_argument('--out', default='', help='куда положить отчёт')
    ap.add_argument('--device', default='auto', help='auto | cuda | cpu')
    args = ap.parse_args()

    folder = args.dir
    if args.pull:
        dest = os.path.join(os.environ.get('TEMP', '.'), 'korpus-pull')
        os.makedirs(dest, exist_ok=True)
        folder = pull(dest)
    if not folder or not os.path.isdir(folder):
        sys.exit('нет папки с файлами: укажи --dir или --pull')

    rows = read_list(folder)
    if args.limit:
        rows = rows[:args.limit]
    if not rows:
        sys.exit('опись пуста — телефон ничего не записал')

    from faster_whisper import WhisperModel
    print('модель %s, строк %d' % (args.model, len(rows)))
    # На процессоре medium идёт по девятнадцать секунд на строку — это три
    # часа на корпус. Видеокарта снимает вопрос; нет её — работаем как есть.
    device = args.device
    if device == 'auto':
        import ctranslate2
        device = 'cuda' if ctranslate2.get_cuda_device_count() > 0 else 'cpu'
    kind = 'float16' if device == 'cuda' else 'int8'
    print('устройство %s (%s)' % (device, kind))
    model = WhisperModel(args.model, device=device, compute_type=kind)

    results = []
    for n, row in enumerate(rows, 1):
        path = os.path.join(folder, row['file'])
        if not os.path.exists(path):
            continue
        segs, _ = model.transcribe(path, language='sr', beam_size=5)
        heard = ' '.join(s.text for s in segs).strip()
        ok, total = score(heard, row['text'])
        sim = glued(heard, row['text'])
        results.append(dict(row, heard=heard, ok=ok, total=total, sim=sim,
                            passed=sim >= GLUED_PASS,
                            digits=bool(re.search(r'\d', heard + row['text']))))
        if n % 10 == 0 or n == len(rows):
            print('  %d из %d, не прошло %d'
                  % (n, len(rows), sum(1 for r in results if not r['passed'])))

    bad = [r for r in results if not r['passed']]
    bad.sort(key=lambda r: r['sim'])

    out = args.out or os.path.join(
        os.path.dirname(os.path.abspath(__file__)), '..', 'data',
        'sluh-%s.txt' % datetime.now().strftime('%Y-%m-%d_%H%M'))
    with open(out, 'w', encoding='utf-8') as f:
        f.write('Наш синтезатор чужим ухом · %s\n'
                % datetime.now().strftime('%d.%m.%Y %H:%M'))
        f.write('Whisper %s, язык sr, порог %d%%\n' % (args.model, int(PASS * 100)))
        f.write('Прослушано %d, не прошло %d\n\n' % (len(results), len(bad)))
        f.write('Провал — улика против синтезатора. Зачёт про живой голос не\n'
                'говорит ничего: Whisper слушает чисто и без комнаты.\n\n')
        f.write('=== НЕ ПРОШЛО ===\n\n')
        for r in bad:
            f.write('%s  %d/%d  —  %s\n' % (r['id'], r['ok'], r['total'], r['source']))
            f.write('  текст:    %s\n' % r['text'])
            f.write('  услышано: %s\n' % (r['heard'] or '(тишина)'))
            lost = missed(r['heard'], r['text'])
            if lost:
                f.write('  потеряно: %s\n' % ', '.join(lost))
            if r['digits']:
                f.write('  (в строке цифры — таблицы числительных в переносе нет)\n')
            f.write('\n')
        f.write('=== ВСЁ ПОДРЯД ===\n\n')
        for r in results:
            f.write('%-10s %d/%d  %s\n' % (r['id'], r['ok'], r['total'], r['text']))
    # Расшифровки рядом с отчётом: слушать шесть минут ради смены порога
    # незачем, а менять его придётся.
    import json
    with open(out + '.json', 'w', encoding='utf-8') as f:
        json.dump([{k: r[k] for k in ('id', 'source', 'text', 'heard')} for r in results],
                  f, ensure_ascii=False, indent=1)
    print('отчёт:', os.path.normpath(out))
    print('не прошло %d из %d' % (len(bad), len(results)))


if __name__ == '__main__':
    main()
