# -*- coding: utf-8 -*-
"""Все Катины уроки подряд одним вордовским файлом: Lessons/Уроки.docx.

Катя попросила 13.10.2026: видеть уроки последовательно в одном файле и чтобы
он дополнялся после каждой сборки. Поэтому его пересобирает `release.py` сам,
в конце выпуска; руками — `python tools/katya_docx.py`.

Берётся то, что едет в приложение, — `assets/lessons/*.json` по порядку
`index.json`, а не `katya_lessons.py`: там лежат и заготовки, и убранные
задания, и файл описывал бы не тот курс, который у человека на телефоне.

Только стандартная библиотека: .docx — это zip с XML, и для заголовков,
абзацев и жирного шрифта зависимостей не нужно. Заголовки уроков сделаны
встроенными стилями Word («Заголовок 1/2»), поэтому по ним работает панель
навигации.

Файл в git не идёт (`.gitignore`): он целиком выводится из уроков.
"""
import datetime
import io
import json
import os
import re
import sys
import zipfile
from xml.sax.saxutils import escape

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LESSONS = os.path.join(ROOT, 'src', 'app', 'src', 'main', 'assets', 'lessons')
GRADLE = os.path.join(ROOT, 'src', 'app', 'build.gradle.kts')
OUT = os.path.join(ROOT, 'Lessons', 'Уроки.docx')

GREEN = '0B6A3A'
MUTED = '5D6B78'


def version():
    text = io.open(GRADLE, encoding='utf-8').read()
    return re.search(r'versionName\s*=\s*"([^"]+)"', text).group(1)


# --- разметка ---

def run(text, bold=False, italic=False, color=None, size=None):
    props = ''
    if bold:
        props += '<w:b/>'
    if italic:
        props += '<w:i/>'
    if color:
        props += '<w:color w:val="%s"/>' % color
    if size:
        props += '<w:sz w:val="%d"/><w:szCs w:val="%d"/>' % (size, size)
    rpr = '<w:rPr>%s</w:rPr>' % props if props else ''
    # Перенос строки внутри задания — <w:br/>, а не \n: Word \n не понимает.
    parts = text.split('\n')
    body = '<w:br/>'.join('<w:t xml:space="preserve">%s</w:t>' % escape(p) for p in parts)
    return '<w:r>%s%s</w:r>' % (rpr, body)


def para(runs, style=None, indent=0, hanging=0, after=None, keep=False, newpage=False):
    ppr = ''
    if style:
        ppr += '<w:pStyle w:val="%s"/>' % style
    if newpage:
        ppr += '<w:pageBreakBefore/>'
    if keep:
        ppr += '<w:keepNext/>'
    if after is not None:
        ppr += '<w:spacing w:after="%d"/>' % after
    if indent or hanging:
        ppr += '<w:ind w:left="%d" w:hanging="%d"/>' % (indent, hanging)
    ppr = '<w:pPr>%s</w:pPr>' % ppr if ppr else ''
    return '<w:p>%s%s</w:p>' % (ppr, ''.join(runs))


# --- задания ---

def label_of(e):
    if e.get('label'):
        return e['label']
    return {
        'choice': 'На слух' if e.get('byEar') else 'Выбор',
        'match': 'Пары',
        'word_bank': 'Собери',
        'speaking': 'Произнеси вслух',
        'repeat': 'Повтори на слух',
    }.get(e['type'], e['type'])


def exercise(n, e):
    """Задание: строка с номером и подписью, ниже варианты и пояснение."""
    out = []
    head = [run('%d. ' % n), run(label_of(e) + ': ', bold=True)]
    t = e['type']
    if t == 'choice':
        if e.get('hint'):
            head.append(run(e['hint']))
        else:
            head.append(run(e['prompt']))
        out.append(para(head, indent=420, hanging=420, after=40, keep=True))
        opts = []
        for i, o in enumerate(e['options']):
            if i:
                opts.append(run('  ·  ', color=MUTED))
            if o == e['answer']:
                opts.append(run('✓ ' + o, bold=True, color=GREEN))
            else:
                opts.append(run(o))
        out.append(para(opts, indent=420, after=40))
    elif t == 'match':
        pairs = '; '.join('%s — %s' % (p['ru'], p['me']) for p in e['pairs'])
        head.append(run(pairs))
        out.append(para(head, indent=420, hanging=420, after=40))
    elif t == 'word_bank':
        head.append(run('«%s» → ' % e['prompt']))
        head.append(run(e['answer'], bold=True, color=GREEN))
        out.append(para(head, indent=420, hanging=420, after=40))
    elif t in ('speaking', 'repeat'):
        head.append(run(e['phrase'], bold=True))
        if e.get('translation'):
            head.append(run(' — ' + e['translation']))
        out.append(para(head, indent=420, hanging=420, after=40))
    else:
        head.append(run(json.dumps(e, ensure_ascii=False)))
        out.append(para(head, indent=420, hanging=420, after=40))
    if e.get('explanation'):
        out.append(para([run('Пояснение: ', italic=True, color=MUTED),
                          run(e['explanation'], italic=True, color=MUTED)],
                         indent=420, after=40))
    return out


def lesson_heading(ref):
    if ref.get('test'):
        return ref['title']
    m = re.match(r'k(\d+)$', ref['id'])
    return 'Урок %d. %s' % (int(m.group(1)), ref['title']) if m else ref['title']


def document():
    index = json.load(io.open(os.path.join(LESSONS, 'index.json'), encoding='utf-8'))['lessons']
    body = []
    today = datetime.date.today().strftime('%d.%m.%Y')
    lessons = [r for r in index if not r.get('test')]
    body.append(para([run('Crnogorski od nule — уроки')], style='Title'))
    body.append(para([run('Сборка %s · обновлено %s · уроков: %d, тестов: %d'
                          % (version(), today, len(lessons), len(index) - len(lessons)),
                          color=MUTED)], after=240))
    body.append(para([run('Верный вариант отмечен ✓ и выделен зелёным. Слова урока — те, '
                          'что после его прохождения появляются на вкладке «Слова».',
                          italic=True, color=MUTED)], after=240))

    section = None
    for ref in index:
        if ref.get('section', '') != section:
            section = ref.get('section', '')
            # Раздел — с новой страницы, кроме первого: тот идёт сразу под шапкой.
            body.append(para([run(section or 'Без раздела')], style='Heading1',
                             newpage=ref is not index[0]))
        doc = json.load(io.open(os.path.join(LESSONS, ref['file']), encoding='utf-8'))
        body.append(para([run(lesson_heading(ref))], style='Heading2'))
        for n, e in enumerate(doc['exercises'], 1):
            body.extend(exercise(n, e))
        words = doc.get('words') or []
        if words:
            body.append(para([run('Слова урока: ', bold=True),
                              run('; '.join('%s — %s' % (w['me'], w['ru']) for w in words))],
                             after=200))
    sect = ('<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>'
            '<w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134" '
            'w:header="708" w:footer="708" w:gutter="0"/></w:sectPr>')
    return ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
            '<w:body>%s%s</w:body></w:document>' % (''.join(body), sect))


STYLES = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:docDefaults><w:rPrDefault><w:rPr>
<w:rFonts w:ascii="Calibri" w:hAnsi="Calibri" w:cs="Calibri" w:eastAsia="Calibri"/>
<w:sz w:val="22"/><w:szCs w:val="22"/><w:lang w:val="ru-RU"/>
</w:rPr></w:rPrDefault>
<w:pPrDefault><w:pPr><w:spacing w:after="80" w:line="264" w:lineRule="auto"/></w:pPr></w:pPrDefault>
</w:docDefaults>
<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style>
<w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/>
<w:qFormat/><w:pPr><w:spacing w:after="80"/></w:pPr>
<w:rPr><w:rFonts w:ascii="Cambria" w:hAnsi="Cambria" w:cs="Cambria"/><w:b/>
<w:color w:val="0B5E86"/><w:sz w:val="44"/><w:szCs w:val="44"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/>
<w:next w:val="Normal"/><w:qFormat/>
<w:pPr><w:keepNext/><w:spacing w:before="240" w:after="120"/><w:outlineLvl w:val="0"/></w:pPr>
<w:rPr><w:rFonts w:ascii="Cambria" w:hAnsi="Cambria" w:cs="Cambria"/><w:b/>
<w:color w:val="0B5E86"/><w:sz w:val="36"/><w:szCs w:val="36"/></w:rPr></w:style>
<w:style w:type="paragraph" w:styleId="Heading2"><w:name w:val="heading 2"/><w:basedOn w:val="Normal"/>
<w:next w:val="Normal"/><w:qFormat/>
<w:pPr><w:keepNext/><w:spacing w:before="360" w:after="120"/><w:outlineLvl w:val="1"/>
<w:pBdr><w:bottom w:val="single" w:sz="6" w:space="2" w:color="D6E0EA"/></w:pBdr></w:pPr>
<w:rPr><w:rFonts w:ascii="Cambria" w:hAnsi="Cambria" w:cs="Cambria"/><w:b/>
<w:color w:val="142231"/><w:sz w:val="28"/><w:szCs w:val="28"/></w:rPr></w:style>
</w:styles>'''

CONTENT_TYPES = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
<Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/>
</Types>'''

RELS = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>'''

DOC_RELS = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings" Target="settings.xml"/>
</Relationships>'''

# Без режима совместимости Word открывает файл с пометкой «Compatibility Mode»
# в заголовке окна: считает его документом старой версии.
SETTINGS = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:settings xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:compat><w:compatSetting w:name="compatibilityMode" w:uri="http://schemas.microsoft.com/office/word" w:val="15"/></w:compat>
</w:settings>'''


def main():
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    tmp = OUT.replace('.docx', ' (свежая).docx')
    with zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('[Content_Types].xml', CONTENT_TYPES)
        z.writestr('_rels/.rels', RELS)
        z.writestr('word/_rels/document.xml.rels', DOC_RELS)
        z.writestr('word/styles.xml', STYLES)
        z.writestr('word/settings.xml', SETTINGS)
        z.writestr('word/document.xml', document())
    # Файл может быть открыт в Word — тогда заменить его нельзя. Выпуск от
    # этого не должен падать: говорим прямо и оставляем свежую копию рядом,
    # под именем, которое Word откроет двойным щелчком.
    try:
        os.replace(tmp, OUT)
        print('уроки в Word:', OUT)
    except PermissionError:
        print('Уроки.docx открыт в Word, свежая версия лежит рядом: ' + tmp)
        print('Закрой Word и запусти python tools/katya_docx.py ещё раз.')
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
