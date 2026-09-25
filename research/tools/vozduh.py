# -*- coding: utf-8 -*-
"""Ноут говорит — телефон слушает. Обратное направление стенда.

Прогон корпуса проверяет наш синтезатор нашим же распознавателем, и потому
«зачёт там не значит ничего». Здесь наоборот: говорит чужой голос (edge-tts),
слушает телефон. Проверяется распознаватель, а не текст.

    python research/tools/vozduh.py                  # «Prvi dan», 12 отрезков
    python research/tools/vozduh.py --upto 4
    python research/tools/vozduh.py --voice hr-HR-SreckoNeural

Перед опытом телефон должен стоять на экране чтения вслух нужной истории, с
первого непройденного отрезка; громкость ноутбука — на максимуме.

**Сперва замкни круг на одной машине** (`krug.py`) и только потом ставь воздух:
он добавляет комнату, громкость и подавление эха, и сломанный источник через них
не разглядеть. Так мы 24.09.2026 целый вечер винили телефон в том, что сербский
голос Microsoft читает латиницу бессмыслицей.

Две вещи, без которых опыт врёт:

* **тишина в начале файла** (`--lead`, 0,9 с). Движок начинает слушать не
  мгновенно, и первое слово уходит в его раскачку: «Ja sam iz Rusije, a sada
  živim u Podgorici» приходило как «A sada živim u Podgorici» — ниже порога
  чтения;
* **сдача видна по кнопке «Продолжить», а не по счётчику «N / 12».** Счётчик
  двигает сама кнопка: сданный отрезок ждёт человека (`StoryState.passed`).
  Считая по счётчику, погонщик печатал «сдано 0 из 12» там, где экран показывал
  12 / 12.
"""
import argparse
import asyncio
import io
import os
import re
import subprocess
import sys
import time
import wave

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from krug import PHRASES  # noqa: E402

ADB = os.environ.get('ADB', 'D:/Android/Sdk/platform-tools/adb.exe')
FFMPEG = os.environ.get('FFMPEG', 'ffmpeg')
ZAHODOV = 3          # столько же попыток, сколько даёт само приложение

# «Prvi dan» целиком: десять фраз из krug плюс два последних отрезка.
TEXTS = dict(enumerate(PHRASES, 1))
TEXTS[11] = 'Hvala lijepo!'
TEXTS[12] = 'Doviđenja, vidimo se sjutra.'


def sh(*a):
    return subprocess.run([ADB] + list(a), capture_output=True)


def dump():
    sh('shell', 'uiautomator', 'dump', '/sdcard/ui.xml')
    return sh('exec-out', 'cat', '/sdcard/ui.xml').stdout.decode('utf-8', 'replace')


def spots(xml):
    out = []
    for m in re.finditer(
            r'(?:text|content-desc)="([^"]{1,120})"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',
            xml):
        a, b, c, d = map(int, m.groups()[1:])
        out.append((m.group(1), (a + c) // 2, (b + d) // 2))
    return out


def at(xml, label):
    for t, x, y in spots(xml):
        if t == label:
            return x, y
    return None


def counter(xml):
    for t, _, _ in spots(xml):
        m = re.match(r'^(\d+) / (\d+)$', t)
        if m:
            return int(m.group(1))
    return None


def notes(xml):
    out = [t for t, _, _ in spots(xml)
           if t.startswith('Услышано') or 'расслышал' in t or 'совпало' in t]
    return ' | '.join(out)


def listening(xml):
    return any('Слушаю' in t for t, _, _ in spots(xml))


def tap(xml, label, pause=2.0):
    spot = at(xml, label)
    if spot:
        sh('shell', 'input', 'tap', str(spot[0]), str(spot[1]))
        time.sleep(pause)
        return True
    return False


def make(folder, voice, lead, tail):
    """Синтезировать фразы и дописать тишину по краям."""
    import edge_tts
    import numpy as np
    os.makedirs(folder, exist_ok=True)
    for n, text in sorted(TEXTS.items()):
        out = os.path.join(folder, '%02d.wav' % n)
        if os.path.exists(out):
            continue
        mp3 = os.path.join(folder, '%02d.mp3' % n)
        if not os.path.exists(mp3):
            asyncio.run(edge_tts.Communicate(text, voice).save(mp3))
        raw = os.path.join(folder, '%02d-raw.wav' % n)
        subprocess.run([FFMPEG, '-y', '-loglevel', 'error', '-i', mp3,
                        '-ac', '1', '-ar', '44100', raw], check=True)
        w = wave.open(raw)
        rate = w.getframerate()
        a = np.frombuffer(w.readframes(w.getnframes()),
                          dtype=np.int16).astype(np.float32)
        w.close()
        os.remove(raw)
        # Пик на 90% шкалы: громко, но срезать нечего. «Выровнять уровень» и
        # «упереть в потолок» разные вещи — на этом я уже обжигался.
        a = a * ((0.90 * 32767) / max(1.0, float(abs(a).max())))
        b = np.concatenate([np.zeros(int(lead * rate)), a,
                            np.zeros(int(tail * rate))]).astype('int16')
        o = wave.open(out, 'wb')
        o.setnchannels(1)
        o.setsampwidth(2)
        o.setframerate(rate)
        o.writeframes(b.tobytes())
        o.close()
    print('звук готов: %s (%s)' % (folder, voice))


def play(folder, n):
    wav = os.path.join(folder, '%02d.wav' % n).replace('/', chr(92))
    subprocess.run(['powershell', '-NoProfile', '-Command',
                    "(New-Object Media.SoundPlayer '%s').PlaySync()" % wav],
                   capture_output=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--voice', default='bs-BA-GoranNeural')
    ap.add_argument('--upto', type=int, default=12)
    ap.add_argument('--lead', type=float, default=0.9)
    ap.add_argument('--tail', type=float, default=0.4)
    ap.add_argument('--dir', default=os.path.join(
        os.environ.get('TEMP', '.'), 'vozduh'))
    ap.add_argument('--log', default='')
    args = ap.parse_args()

    log = io.open(args.log, 'w', encoding='utf-8') if args.log else None

    def say(s):
        print(s)
        if log:
            log.write(s + chr(10))
            log.flush()

    make(args.dir, args.voice, args.lead, args.tail)

    sdano, provaleno, slyshno = [], [], {}
    for _ in range(60):
        xml = dump()
        if counter(xml) is None:
            say('экран истории потерян')
            break
        if at(xml, 'Продолжить'):
            tap(xml, 'Продолжить')
            xml = dump()

        pos = counter(xml)
        if pos is None:
            break
        n = pos + 1              # номер отрезка берётся с экрана, не из цикла
        if n > args.upto or n not in TEXTS:
            say('дошли до конца заказанного')
            break

        say('--- отрезок %d: %s' % (n, TEXTS[n]))
        ok = False
        for zahod in range(1, ZAHODOV + 1):
            xml = dump()
            if at(xml, 'Продолжить'):
                ok = True
                break
            if not listening(xml):
                for lbl in ('Читать вслух', 'Записать', 'Ещё раз',
                            'Послушать и повторить'):
                    if tap(xml, lbl, 2.5):
                        break
                xml = dump()
            if not listening(xml):
                say('    заход %d: микрофон закрыт, играю всё равно' % zahod)
            play(args.dir, n)

            seen = set()
            for t in range(24):
                time.sleep(1.0)
                x2 = dump()
                nt = notes(x2)
                if nt and nt not in seen:
                    seen.add(nt)
                    say('    заход %d: %s' % (zahod, nt))
                    slyshno.setdefault(n, []).append(nt)
                if at(x2, 'Продолжить'):
                    ok = True
                    break
            if ok:
                break
            say('    заход %d: не сдано' % zahod)

        if ok:
            say('    ЗАЧТЕНО')
            sdano.append(n)
        else:
            say('    ПРОВАЛЕНО после %d заходов' % ZAHODOV)
            provaleno.append(n)
            x3 = dump()
            if not (tap(x3, 'Дальше') or tap(x3, 'Оставить отрезок')
                    or tap(x3, 'Пропустить') or tap(x3, 'Продолжить')):
                say('    выйти из отрезка нечем — останавливаюсь')
                break

    say(chr(10) + 'сдано %d, провалено %d' % (len(sdano), len(provaleno)))
    if sdano:
        say('сдано: %s' % ', '.join(str(x) for x in sdano))
    for n in provaleno:
        say('провалено %d: %s' % (n, TEXTS[n]))
        for h in slyshno.get(n, []):
            say('    %s' % h)
    if log:
        log.close()


if __name__ == '__main__':
    main()
