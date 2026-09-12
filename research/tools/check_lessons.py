# -*- coding: utf-8 -*-
"""
Вычитка уроков через Haiku: язык, иекавица, переводы.

Проверяет то, чего не поймает ни компилятор, ни схема: правильный ли
черногорский, не сербская ли форма затесалась, отвечает ли перевод оригиналу и
не ошибочен ли разбор в `explanation`. Один запрос на урок — так модель видит
задания в контексте друг друга, а не по одной строке.

Цена: около 7 центов на 26 уроков. **Это деньги владельца**, поэтому скрипт
всегда печатает оценку и требует `--yes`, чтобы начать.

Запуск из корня репозитория:

    python research/tools/check_lessons.py --from 35 --to 60 --yes
"""
import argparse
import io
import json
import os
import sys
import time
import urllib.request

API = 'https://api.anthropic.com/v1/messages'
MODEL = 'claude-haiku-4-5-20251001'
LESSONS = 'src/app/src/main/assets/lessons/'

SYSTEM = """Ты придирчивый редактор учебника черногорского языка для русскоязычных.
Курс черногорский: иекавица (vrijeme, lijep, sjutra, ovdje, gdje) и латиница.
Сербские экавские формы (vreme, lep, sutra, ovde) — ошибка в эталоне, хотя в
ответе ученика они допускаются.

Тебе дают один урок целиком в JSON. Найди настоящие ошибки:
1. неверный черногорский: грамматика, падеж, порядок слов, несуществующее слово;
2. экавица или сербизм там, где должна быть иекавская форма;
3. перевод на русский не соответствует черногорскому;
4. объяснение в поле explanation неверно по сути;
5. в задании word_bank ответ нельзя собрать из банка слов;
6. задание невозможно решить однозначно (два варианта одинаково верны).

Не придирайся к стилю, к выбору лексики и к тому, что фраза простая: это
учебник для начинающих. Если урок в порядке, так и скажи.

Ответь одним JSON-объектом без обёрток:
{"problems": [{"id": "l35e01", "what": "коротко в чём ошибка", "fix": "как надо"}]}
Пустой список — значит ошибок нет."""


def key():
    for line in io.open('src/local.properties', encoding='utf-8'):
        if line.startswith('ANTHROPIC_API_KEY'):
            return line.split('=', 1)[1].strip()
    sys.exit('ANTHROPIC_API_KEY не задан в src/local.properties')


def ask(api_key, lesson_json):
    payload = {
        'model': MODEL,
        'max_tokens': 1200,
        'temperature': 0,
        'system': SYSTEM,
        'messages': [
            {'role': 'user', 'content': lesson_json},
            # Ответ начат за модель: так она не сможет выдать обёртку ```json —
            # тот же приём, что в самом приложении.
            {'role': 'assistant', 'content': '{'},
        ],
    }
    req = urllib.request.Request(
        API,
        data=json.dumps(payload, ensure_ascii=False).encode('utf-8'),
        headers={
            'content-type': 'application/json',
            'x-api-key': api_key,
            'anthropic-version': '2023-06-01',
        },
    )
    with urllib.request.urlopen(req, timeout=120) as r:
        body = json.load(r)
    text = '{' + body['content'][0]['text']
    usage = body.get('usage', {})
    return text, usage.get('input_tokens', 0), usage.get('output_tokens', 0)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--from', dest='first', type=int, required=True)
    p.add_argument('--to', dest='last', type=int, required=True)
    p.add_argument('--yes', action='store_true', help='согласие на расход')
    args = p.parse_args()

    files = ['%sl%d.json' % (LESSONS, i) for i in range(args.first, args.last + 1)]
    files = [f for f in files if os.path.exists(f)]
    cents = len(files) * 0.27
    print('уроков: %d · оценка расхода: около %.0f центов' % (len(files), cents))
    if not args.yes:
        sys.exit('без --yes ничего не отправляю: это деньги владельца')

    api_key = key()
    total_in = total_out = 0
    found = []
    for f in files:
        raw = io.open(f, encoding='utf-8').read()
        try:
            text, ti, to = ask(api_key, raw)
        except Exception as e:                                   # noqa: BLE001
            print('%s: запрос не прошёл — %s' % (f, e))
            time.sleep(2)
            continue
        total_in += ti
        total_out += to
        try:
            problems = json.loads(text).get('problems', [])
        except ValueError:
            print('%s: ответ не разобрался: %s' % (f, text[:200]))
            continue
        mark = '·' if not problems else '!'
        print('%s %s %d' % (mark, os.path.basename(f), len(problems)))
        for pr in problems:
            found.append((os.path.basename(f), pr))
        time.sleep(0.3)

    print()
    print('=== замечаний: %d ===' % len(found))
    for name, pr in found:
        print('%s %s: %s' % (name, pr.get('id', '?'), pr.get('what', '')))
        if pr.get('fix'):
            print('      → %s' % pr['fix'])

    cost = total_in / 1e6 * 1.0 + total_out / 1e6 * 5.0
    print()
    print('токенов: %d на вход, %d на выход · потрачено около %.1f цента'
          % (total_in, total_out, cost * 100))


if __name__ == '__main__':
    main()
