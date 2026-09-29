# -*- coding: utf-8 -*-
"""Круг на одной машине: edge-tts говорит, Whisper слушает.

Нужен перед всяким опытом «ноут говорит — телефон слушает». Между динамиком и
микрофоном стоят три подозреваемых сразу — комната, громкость, подавление эха, —
и проверять через них сломанный источник значит искать не там. Здесь воздуха
нет: файл идёт слушателю прямо, и вопрос один — разборчив ли сам синтез.

Так и нашлось (25.09.2026), что `sr-RS-NicholasNeural` на латинице выдаёт
бессмыслицу, а на кириллице читает дословно: 0 из 10 против 10 из 10. Целый
вечер до этого мы винили телефон.

    python research/tools/krug.py                       # наши голоса, латиницей
    python research/tools/krug.py --cyr                  # то же кириллицей
    python research/tools/krug.py --voices bs-BA-GoranNeural --model large-v3

Мера — та же, что у прогона корпуса (`hear_corpus`), иначе числа не сравнить с
телефоном.
"""
import argparse
import asyncio
import io
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import hear_corpus as hc  # noqa: E402

VOICES = ['sr-RS-NicholasNeural', 'sr-RS-SophieNeural',
          'hr-HR-SreckoNeural', 'bs-BA-GoranNeural']

# Десять фраз первой истории: короткие, обиходные, с ятем в четвёртой.
PHRASES = [
    'Dobro jutro!',
    'Zovem se Ivan.',
    'Ja sam iz Rusije, a sada živim u Podgorici.',
    'Još uvijek slabo govorim crnogorski.',
    'Danas idem u grad.',
    'Autobuska stanica je blizu.',
    'Idem pravo, pa lijevo.',
    'U centru je kafe.',
    'Molim vas, jednu kafu.',
    'Koliko košta?',
]

# Латиница -> кириллица. Двузнаки первыми, иначе «lj» станет «лј».
# Ловушка, которой перевод не видит: `nj`, `lj`, `dž` бывают стыком, а не
# двузнаком — `injekcija` это и-н-ј, `nadživjeti` это д-ж. В нынешнем корпусе
# таких слов нет, новый текст принесёт их молча.
PAIRS = [('Lj', 'Љ'), ('LJ', 'Љ'), ('lj', 'љ'), ('Nj', 'Њ'), ('NJ', 'Њ'),
         ('nj', 'њ'), ('Dž', 'Џ'), ('DŽ', 'Џ'), ('dž', 'џ'),
         ('A', 'А'), ('B', 'Б'), ('V', 'В'), ('G', 'Г'), ('D', 'Д'),
         ('Đ', 'Ђ'), ('E', 'Е'), ('Ž', 'Ж'), ('Z', 'З'), ('I', 'И'),
         ('J', 'Ј'), ('K', 'К'), ('L', 'Л'), ('M', 'М'), ('N', 'Н'),
         ('O', 'О'), ('P', 'П'), ('R', 'Р'), ('S', 'С'), ('T', 'Т'),
         ('Ć', 'Ћ'), ('U', 'У'), ('F', 'Ф'), ('H', 'Х'), ('C', 'Ц'),
         ('Č', 'Ч'), ('Š', 'Ш'),
         ('a', 'а'), ('b', 'б'), ('v', 'в'), ('g', 'г'), ('d', 'д'),
         ('đ', 'ђ'), ('e', 'е'), ('ž', 'ж'), ('z', 'з'), ('i', 'и'),
         ('j', 'ј'), ('k', 'к'), ('l', 'л'), ('m', 'м'), ('n', 'н'),
         ('o', 'о'), ('p', 'п'), ('r', 'р'), ('s', 'с'), ('t', 'т'),
         ('ć', 'ћ'), ('u', 'у'), ('f', 'ф'), ('h', 'х'), ('c', 'ц'),
         ('č', 'ч'), ('š', 'ш')]


def cyr(s):
    for lat, c in PAIRS:
        s = s.replace(lat, c)
    return s


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--voices', nargs='*', default=VOICES)
    ap.add_argument('--cyr', action='store_true',
                    help='говорить кириллицей (нужно голосам sr-RS)')
    ap.add_argument('--model', default='medium')
    ap.add_argument('--dir', default=os.path.join(
        os.environ.get('TEMP', '.'), 'krug'))
    ap.add_argument('--out', default='')
    args = ap.parse_args()

    import edge_tts
    os.makedirs(args.dir, exist_ok=True)
    tag = 'cyr' if args.cyr else 'lat'
    for voice in args.voices:
        for n, text in enumerate(PHRASES, 1):
            path = os.path.join(args.dir, '%s-%s-%02d.mp3' % (tag, voice, n))
            if not os.path.exists(path):
                said = cyr(text) if args.cyr else text
                asyncio.run(edge_tts.Communicate(said, voice).save(path))
        print('сказано: %s' % voice)

    from faster_whisper import WhisperModel
    import ctranslate2
    device = 'cuda' if ctranslate2.get_cuda_device_count() > 0 else 'cpu'
    kind = 'float16' if device == 'cuda' else 'int8'
    print('\nWhisper %s на %s (%s), %s\n' % (args.model, device, kind,
                                             'кириллицей' if args.cyr else 'латиницей'))
    model = WhisperModel(args.model, device=device, compute_type=kind)

    rows = []
    for voice in args.voices:
        good = 0
        print('=== %s' % voice)
        for n, want in enumerate(PHRASES, 1):
            path = os.path.join(args.dir, '%s-%s-%02d.mp3' % (tag, voice, n))
            segs, _ = model.transcribe(path, language='sr', beam_size=5)
            heard = ' '.join(s.text for s in segs).strip()
            sim = hc.glued(heard, want)
            ok, total = hc.score(heard, want)
            passed = sim >= hc.GLUED_PASS
            good += passed
            rows.append(dict(voice=voice, letters=tag, n=n, want=want,
                             heard=heard, ok=ok, total=total, sim=sim,
                             passed=passed))
            print('%s%2d %-44s -> %-46s %.2f'
                  % ('OK ' if passed else '   ', n, want, heard[:46], sim))
        print('    итог: %d из %d\n' % (good, len(PHRASES)))

    if args.out:
        with io.open(args.out, 'w', encoding='utf-8') as f:
            json.dump(rows, f, ensure_ascii=False, indent=1)
        print('подробности: %s' % args.out)


if __name__ == '__main__':
    main()
