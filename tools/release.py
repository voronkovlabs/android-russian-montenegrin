# -*- coding: utf-8 -*-
"""
Кладёт собранный APK в релизы GitHub и пропалывает старые.

Запуск из корня репозитория, после `cd src && ./gradlew assembleDebug`:

    python tools/release.py            # версия берётся из build.gradle.kts
    python tools/release.py --keep 5   # хранить пять релизов вместо трёх

APK уходит **как есть**, без архива: GitHub отдаёт вложение тем же файлом,
каким его положили, и телефон ставит его прямо из браузера. Zip появляется
рядом сам — это исходники, их GitHub прикладывает к каждому релизу и убрать
нельзя; на установку он не влияет.

Хранится [KEEP] последних релизов, остальные удаляются вместе с метками:
каждый APK весит около шестидесяти мегабайт, и десяток забытых сборок — это
полгигабайта в репозитории, который никто никогда не откроет.
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile

REPO = 'voronkovlabs/android-russian-montenegrin'
APK = 'src/app/build/outputs/apk/debug/app-debug.apk'
GRADLE = 'src/app/build.gradle.kts'

# Катина экспериментальная сборка (ветка katya). Её релизы — pre-release с
# этой меткой: `releases/latest` их не отдаёт, и основное приложение их не
# видит. Двойник `TAG_PREFIX` в net/Updater.kt.
TAG_PREFIX = 'katya-v'

# Сколько релизов оставлять: нынешний и два предыдущих.
KEEP = 3

# gh ставится машинно и в PATH текущей оболочки попадает не всегда.
GH = shutil.which('gh') or r'C:\Program Files\GitHub CLI\gh.exe'


def run(*args, **kw):
    return subprocess.run([GH, *args], capture_output=True, text=True,
                          encoding='utf-8', **kw)


def version():
    """Версия — из build.gradle.kts, чтобы не разъезжалась с самим APK."""
    text = open(GRADLE, encoding='utf-8').read()
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    code = re.search(r'versionCode\s*=\s*(\d+)', text)
    if not name or not code:
        sys.exit('не нашёл versionName/versionCode в ' + GRADLE)
    return name.group(1), code.group(1)


def publish(tag, title, notes, apk):
    """Создаёт релиз; если такой тег уже есть, заменяет вложение."""
    exists = run('release', 'view', tag, '--repo', REPO).returncode == 0
    if exists:
        print('релиз', tag, 'уже есть — обновляю вложение')
        r = run('release', 'upload', tag, apk, '--clobber', '--repo', REPO)
    else:
        # --target katya: без него метка встаёт на последний коммит main, и у
        # всех Катиных релизов оказывалась одна дата — порядок по дате врал.
        r = run('release', 'create', tag, apk, '--prerelease', '--target', 'katya',
                '--repo', REPO, '--title', title, '--notes', notes)
    if r.returncode != 0:
        sys.exit((r.stderr or r.stdout).strip())
    return (r.stdout or '').strip()


def prune(keep, prefix=TAG_PREFIX):
    """
    Удаляет всё, что старше последних [keep] релизов.

    Порядок берём по дате создания, а не по имени тега: «1.9» и «1.10»
    сортируются как текст неправильно, и прополка выкосила бы свежее.
    """
    r = run('release', 'list', '--repo', REPO, '--limit', '100',
            '--json', 'tagName,createdAt')
    if r.returncode != 0:
        sys.exit((r.stderr or r.stdout).strip())
    # Только свои: релизы основного приложения лежат в том же репозитории.
    releases = sorted((x for x in json.loads(r.stdout)
                       if x['tagName'].startswith(prefix)),
                      key=lambda x: version_key(x['tagName'], prefix), reverse=True)
    for old in releases[keep:]:
        tag = old['tagName']
        # Метку сносим вместе с релизом: без --cleanup-tag в репозитории
        # копятся теги, за которыми уже ничего нет.
        d = run('release', 'delete', tag, '--repo', REPO, '--yes', '--cleanup-tag')
        print(('удалён ' if d.returncode == 0 else 'не удалился ') + tag)
    return len(releases[keep:])


def version_key(tag, prefix=TAG_PREFIX):
    """Порядок Катиных релизов — по номеру версии, а не по дате: до 0.11 метки
    стояли на одном коммите main, и даты у них совпадали."""
    return [int(p) for p in tag[len(prefix):].split('.') if p.isdigit()]


def latest_tag():
    """Метка последнего выпуска — от неё и считаем, что тронуто."""
    r = subprocess.run(
        ['gh', 'release', 'list', '--repo', REPO, '--limit', '1',
         '--json', 'tagName'],
        capture_output=True, text=True, encoding='utf-8')
    if r.returncode != 0:
        return ''
    items = json.loads(r.stdout or '[]')
    return items[0]['tagName'] if items else ''


def journal():
    """
    Записи журнала прохождений со всех телефонов.

    Телефон знает, **что запускали**; что при этом менялось в коде, знает git,
    и он здесь. Поэтому вывод «обкатано» делается на этой стороне, а не в
    приложении — см. `data/Journal.kt`.
    """
    r = subprocess.run(
        ['gh', 'issue', 'list', '--repo', REPO, '--label', 'журнал',
         '--state', 'all', '--limit', '40', '--json', 'body'],
        capture_output=True, text=True, encoding='utf-8')
    if r.returncode != 0:
        return []
    rows = []
    for issue in json.loads(r.stdout or '[]'):
        for block in re.findall(r'```json\n(.*?)\n```', issue.get('body', ''), re.S):
            for line in block.splitlines():
                line = line.strip()
                if not line.startswith('{'):
                    continue
                try:
                    rows.append(json.loads(line))
                except ValueError:
                    pass
    return rows


def touched(tag):
    """Какие единицы курса менялись с метки [tag]."""
    # Метки заводит `gh release create` **на сервере**, и локально их нет.
    # Без этого `git diff` не находил ревизию, тихо возвращал пустоту — и
    # сверка вечно сообщала бы «всё тронутое кто-то прогнал». Ровно тот род
    # поломки, ради которого сверка и затевалась.
    subprocess.run(['git', 'fetch', '--tags', '--quiet'], capture_output=True)
    r = subprocess.run(['git', 'diff', '--name-only', tag + '..HEAD'],
                       capture_output=True, text=True, encoding='utf-8')
    if r.returncode != 0:
        print('журнал: метка %s не нашлась, сверять не с чем' % tag)
        return set()
    units = set()
    for path in r.stdout.splitlines():
        m = re.search(r'assets/lessons/([a-z0-9]+)\.json$', path)
        if m and m.group(1) != 'index':
            units.add(m.group(1))
        m = re.search(r'assets/stories/([a-z0-9]+)\.json$', path)
        if m and m.group(1) != 'index':
            units.add(m.group(1))
    return units


def coverage(prev_tag):
    """
    Что тронуто с прошлого выпуска и кем из троих с тех пор не запускалось.

    Печатает и молчит про остальное: это подсказка перед выпуском, а не
    проверка, которая что-то запрещает. Запрещать тут нечего — выпуск всё
    равно уйдёт, просто будет видно, что прогнать руками.
    """
    units = touched(prev_tag)
    if not units:
        return
    rows = journal()
    if not rows:
        print('журнал: записей ещё нет, сверять не с чем')
        return

    # Последняя сборка, на которой единицу хоть кто-то запускал.
    last = {}
    for row in rows:
        unit, code = row.get('u'), row.get('v')
        if row.get('t') == 'run' and unit and isinstance(code, int):
            if code > last.get(unit, 0):
                last[unit] = code

    _, code = version()
    cold = sorted(u for u in units if last.get(u, 0) < int(code))
    print('тронуто с %s: %s' % (prev_tag, ', '.join(sorted(units))))
    if cold:
        print('НИКТО НЕ ЗАПУСКАЛ после правки: ' + ', '.join(cold))
    else:
        print('всё тронутое кто-то уже прогнал')


NEWS_MARK = '<!--news-->'

# Пакет уроков (0.51): новые уроки без нового APK. Двойник `TAG_PREFIX` в
# net/LessonPack.kt. Метка minApp — двойник `MIN_APP` там же.
LESSONS_PREFIX = 'katya-lessons-'
ASSETS = 'src/app/src/main/assets'
PACK = ['lessons', 'stories', 'glossary.json']
INFO = os.path.join(ASSETS, 'content.json')


def lessons(keep):
    """
    Выпустить пакет уроков: zip из assets, релиз `katya-lessons-N`.

    Номер N — следующий после последнего пакета и после номера уроков в APK.
    Он же записывается в `assets/content.json`, чтобы следующий APK знал, какие
    уроки у него внутри: иначе свежий APK уступил бы старому пакету.

    minApp — versionCode из gradle: уроки могут опираться на код этой версии.
    Если такого APK ещё нет в релизах, пакет будет ждать приложения, которого
    ни у кого нет, — об этом предупреждаем.
    """
    name, code = version()
    if run('release', 'view', TAG_PREFIX + name, '--repo', REPO).returncode != 0:
        print('ВНИМАНИЕ: APK %s ещё не выпущен — пакет поставится только после него' % name)
    r = run('release', 'list', '--repo', REPO, '--limit', '100', '--json', 'tagName')
    if r.returncode != 0:
        sys.exit((r.stderr or r.stdout).strip())
    packs = [int(x['tagName'][len(LESSONS_PREFIX):]) for x in json.loads(r.stdout)
             if x['tagName'].startswith(LESSONS_PREFIX)
             and x['tagName'][len(LESSONS_PREFIX):].isdigit()]
    bundled = json.load(open(INFO, encoding='utf-8')).get('version', 0)
    n = max(packs + [bundled]) + 1
    with open(INFO, 'w', encoding='utf-8') as f:
        json.dump({'version': n}, f)
        f.write('\n')

    import zipfile
    zpath = os.path.join(tempfile.gettempdir(), 'lessons-%d.zip' % n)
    with zipfile.ZipFile(zpath, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('content.json', json.dumps({'version': n, 'minApp': int(code)}))
        for item in PACK:
            top = os.path.join(ASSETS, item)
            if os.path.isfile(top):
                z.write(top, item)
                continue
            for folder, _, files in os.walk(top):
                for fname in files:
                    full = os.path.join(folder, fname)
                    z.write(full, os.path.relpath(full, ASSETS).replace(os.sep, '/'))
    tag = LESSONS_PREFIX + str(n)
    url = publish(tag, 'Уроки %d' % n, '<!--minApp %s-->' % code, zpath)
    print('пакет уроков:', url or tag, '· %d КБ' % (os.path.getsize(zpath) // 1024))
    os.remove(zpath)
    print('прополото:', prune(keep, LESSONS_PREFIX))
    print('assets/content.json → %d: закоммить его вместе с уроками' % n)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--keep', type=int, default=KEEP)
    ap.add_argument('--notes', default='')
    ap.add_argument(
        '--news', default='',
        help='текст для телефона: строка на главном и уведомление после '
             'установки. Нет ключа — нет и уведомления')
    ap.add_argument(
        '--lessons', action='store_true',
        help='выпустить только пакет уроков, без APK')
    args = ap.parse_args()

    if args.lessons:
        lessons(args.keep)
        return

    if not os.path.exists(APK):
        sys.exit('нет собранного APK: ' + APK + '\nсперва cd src && ./gradlew assembleDebug')

    name, code = version()
    tag = TAG_PREFIX + name

    # Имя вложения с версией: «app-debug.apk» в папке загрузок телефона не
    # говорит ничего, а по «crnogorski-1.59.apk» сразу видно, что ставишь.
    tmp = os.path.join(tempfile.gettempdir(), 'crnogorski-katya-%s.apk' % name)
    shutil.copyfile(APK, tmp)

    # Раньше здесь по умолчанию бралась строка последнего коммита — и это
    # врало: скрипт запускается **до** коммита версии, так что в тело релиза
    # попадал заголовок предыдущего выпуска. Пока тело никто не читал, это была
    # мелочь; с уведомлениями о новостях оно стало бы неправдой на телефоне.
    notes = args.notes

    # Новости отделены меткой, невидимой на странице релиза: приложение берёт
    # ровно то, что между ней и концом, а человек видит обычный текст.
    if args.news:
        notes = (notes + '\n\n' + NEWS_MARK + '\n' + args.news).strip()

    # Сверка журнала прохождений — до выпуска, пока метка прошлого релиза
    # ещё последняя. Печатает подсказку и ничего не запрещает.
    # Сверки журнала в Катиной сборке нет: её журнал уезжает под меткой
    # «катя-эксперимент», а не «журнал», и сверять тут не с чем.

    url = publish(tag, name, notes, tmp)
    os.remove(tmp)
    print('релиз:', url or tag)
    print('прополото:', prune(args.keep))

    # Катя попросила все уроки подряд одним вордовским файлом, обновляемым
    # после каждой сборки (13.10.2026). Файл открыт в Word — выпуск не падает,
    # свежая копия ложится рядом.
    import katya_docx
    katya_docx.main()


if __name__ == '__main__':
    main()
