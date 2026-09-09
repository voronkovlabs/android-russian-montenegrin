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
        r = run('release', 'create', tag, apk,
                '--repo', REPO, '--title', title, '--notes', notes)
    if r.returncode != 0:
        sys.exit((r.stderr or r.stdout).strip())
    return (r.stdout or '').strip()


def prune(keep):
    """
    Удаляет всё, что старше последних [keep] релизов.

    Порядок берём по дате создания, а не по имени тега: «1.9» и «1.10»
    сортируются как текст неправильно, и прополка выкосила бы свежее.
    """
    r = run('release', 'list', '--repo', REPO, '--limit', '100',
            '--json', 'tagName,createdAt')
    if r.returncode != 0:
        sys.exit((r.stderr or r.stdout).strip())
    releases = sorted(json.loads(r.stdout), key=lambda x: x['createdAt'], reverse=True)
    for old in releases[keep:]:
        tag = old['tagName']
        # Метку сносим вместе с релизом: без --cleanup-tag в репозитории
        # копятся теги, за которыми уже ничего нет.
        d = run('release', 'delete', tag, '--repo', REPO, '--yes', '--cleanup-tag')
        print(('удалён ' if d.returncode == 0 else 'не удалился ') + tag)
    return len(releases[keep:])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--keep', type=int, default=KEEP)
    ap.add_argument('--notes', default='')
    args = ap.parse_args()

    if not os.path.exists(APK):
        sys.exit('нет собранного APK: ' + APK + '\nсперва cd src && ./gradlew assembleDebug')

    name, code = version()
    tag = 'v' + name

    # Имя вложения с версией: «app-debug.apk» в папке загрузок телефона не
    # говорит ничего, а по «crnogorski-1.59.apk» сразу видно, что ставишь.
    tmp = os.path.join(tempfile.gettempdir(), 'crnogorski-%s.apk' % name)
    shutil.copyfile(APK, tmp)

    notes = args.notes or subprocess.run(
        ['git', 'log', '-1', '--pretty=%s'], capture_output=True, text=True,
        encoding='utf-8').stdout.strip()

    url = publish(tag, name, notes, tmp)
    os.remove(tmp)
    print('релиз:', url or tag)
    print('прополото:', prune(args.keep))


if __name__ == '__main__':
    main()
