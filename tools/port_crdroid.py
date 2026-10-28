#!/usr/bin/env python3
"""Перенос стилей часов экрана блокировки crDroid (Apache-2.0) в модуль faces.

Берёт разметки keyguard_clock_* / keyguard_anci_* / keyguard_spark* из SystemUI crDroid и всё, на что они ссылаются
(шрифты, картинки, анимации Lottie, размеры, цвета), рекурсивно. Ресурсы получают префикс crd_ и имена только из
[a-z0-9_] (Gradle не принимает «Bebas.ttf»). Классы crDroid заменяются своими (dev.novacain.ntclocks.faces.crd.*).
Системный шрифт часов (@*android:string/config_clockFontFamily) убирается, а такие тексты помечаются тегом
«clockfont» — туда подставляется шрифт, выбранный в приложении.

Использование: port_crdroid.py CRDROID_SYSTEMUI_DIR FACES_RES_DIR STYLES_OUT_JAVA
"""
import os
import re
import shutil
import sys
import xml.etree.ElementTree as ET

A = '{http://schemas.android.com/apk/res/android}'
NS = {'android': 'http://schemas.android.com/apk/res/android', 'app': 'http://schemas.android.com/apk/res-auto',
      'tools': 'http://schemas.android.com/tools'}
for k, v in NS.items():
    ET.register_namespace(k, v)

CLASS_MAP = {
    'com.android.systemui.weather.WeatherTextView': 'dev.novacain.ntclocks.faces.crd.WeatherTextView',
    'com.android.systemui.weather.WeatherImageView': 'dev.novacain.ntclocks.faces.crd.WeatherImageView',
    'com.android.systemui.clocks.WordClockView': 'dev.novacain.ntclocks.faces.crd.WordClockView',
    'com.android.systemui.clocks.AnalogClockView': 'dev.novacain.ntclocks.faces.crd.AnalogClockView',
    'com.android.systemui.clocks.NothingAnalogClockView': 'dev.novacain.ntclocks.faces.crd.NothingAnalogClockView',
}
REF = re.compile(r'@(drawable|font|raw|dimen|color)/([A-Za-z0-9_.\-]+)')


def norm(name):
    return 'crd_' + re.sub(r'[^a-z0-9_]', '_', name.lower())


class Porter:
    def __init__(self, src, out):
        self.src, self.out = src, out
        self.res_dirs = [os.path.join(src, d) for d in sorted(os.listdir(src)) if d.startswith('res')]
        self.done, self.missing = set(), []
        self.values = {'dimen': {}, 'color': {}}

    def find(self, kind, name):
        """Все файлы ресурса по всем квалификаторам: [(папка_с_квалификатором, путь)]."""
        hits = []
        folder = {'drawable': ('drawable', 'mipmap'), 'font': ('font',), 'raw': ('raw',)}[kind]
        for rd in self.res_dirs:
            for d in sorted(os.listdir(rd)):
                if not d.startswith(folder):
                    continue
                for f in os.listdir(os.path.join(rd, d)):
                    if f.split('.')[0] == name:
                        hits.append((d, os.path.join(rd, d, f)))
        return hits

    def value(self, kind, name):
        for rd in self.res_dirs:
            vd = os.path.join(rd, 'values')
            if not os.path.isdir(vd):
                continue
            for f in os.listdir(vd):
                try:
                    root = ET.parse(os.path.join(vd, f)).getroot()
                except ET.ParseError:
                    continue
                for el in root:
                    if el.tag == kind and el.get('name') == name:
                        return el.text
        return None

    def rewrite(self, text):
        """Переписать ссылки в тексте ресурса, попутно перенести то, на что они указывают."""
        def sub(m):
            kind, name = m.group(1), m.group(2)
            self.port(kind, name)
            return f'@{kind}/{norm(name)}'
        return REF.sub(sub, text)

    def port(self, kind, name):
        key = (kind, name)
        if key in self.done:
            return
        self.done.add(key)
        if kind in ('dimen', 'color'):
            v = self.value(kind, name)
            if v is None:
                self.missing.append(f'{kind}/{name}')
                return
            self.values[kind][norm(name)] = self.rewrite(v)
            return
        hits = self.find(kind, name)
        if not hits:
            self.missing.append(f'{kind}/{name}')
            return
        for qual, path in hits:
            ext = path[path.index('.', len(os.path.dirname(path)) + 1):]       # .xml / .png / .9.png / .ttf
            dst_dir = os.path.join(self.out, qual)
            os.makedirs(dst_dir, exist_ok=True)
            dst = os.path.join(dst_dir, norm(name) + ext.lower())
            if ext == '.xml':
                open(dst, 'w').write(self.rewrite(open(path, encoding='utf-8').read()))
            else:
                shutil.copyfile(path, dst)

    def layout(self, path):
        tree = ET.parse(path)
        lottie = []
        for el in tree.iter():
            if el.tag in CLASS_MAP:
                el.tag = CLASS_MAP[el.tag]
            if el.tag == 'com.airbnb.lottie.LottieAnimationView':
                raw = el.get('{%s}lottie_rawRes' % NS['app'])
                # атрибуты Lottie внутри SystemUI не прочитаются (там своя копия библиотеки) — анимацию ставит код
                for a in list(el.attrib):
                    if a.startswith('{%s}lottie_' % NS['app']):
                        del el.attrib[a]
                if raw:
                    name = raw.split('/')[-1]
                    self.port('raw', name)
                    tag = el.get(A + 'tag')
                    el.set(A + 'tag', (tag + '|' if tag else '') + 'lottie:' + norm(name))
                lottie.append(raw)
            ff = el.get(A + 'fontFamily')
            if ff == '@*android:string/config_clockFontFamily':
                del el.attrib[A + 'fontFamily']
                tag = el.get(A + 'tag')
                el.set(A + 'tag', (tag + '|' if tag else '') + 'clockfont')
            for a, v in list(el.attrib.items()):
                if '@' in v:
                    el.set(a, self.rewrite(v))
        return tree


def main():
    src, out, java_out = sys.argv[1:4]
    p = Porter(src, out)
    lay_dir = os.path.join(src, 'res-keyguard', 'layout')
    order_src = open(os.path.join(src, 'src/com/android/systemui/clocks/ClockStyle.kt')).read()
    styles = re.findall(r'R\.layout\.(\w+),\s*//\s*(\d+)', order_src)       # порядок как в crDroid (номера стилей)
    os.makedirs(os.path.join(out, 'layout'), exist_ok=True)
    rows = []
    for name, num in styles:
        tree = p.layout(os.path.join(lay_dir, name + '.xml'))
        dst = norm(name.replace('keyguard_', ''))
        tree.write(os.path.join(out, 'layout', dst + '.xml'), encoding='utf-8', xml_declaration=True)
        rows.append((int(num), name, dst))
    # все шрифты crDroid — для выбора шрифта, даже если ни одна разметка их не использует
    fd = os.path.join(src, 'res-keyguard', 'font')
    for f in sorted(os.listdir(fd)):
        p.port('font', f.split('.')[0])
    vals = os.path.join(out, 'values')
    os.makedirs(vals, exist_ok=True)
    with open(os.path.join(vals, 'crd_values.xml'), 'w') as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<!-- перенесено из crDroid (Apache-2.0) -->\n<resources>\n')
        for kind in ('dimen', 'color'):
            for n, v in sorted(p.values[kind].items()):
                f.write(f'    <{kind} name="{n}">{v}</{kind}>\n')
        f.write('</resources>\n')
    rows.sort()
    with open(java_out, 'w') as f:
        f.write('// Сгенерировано tools/port_crdroid.py: стили часов crDroid в их порядке (номер, разметка).\n')
        f.write('package dev.novacain.ntclocks.faces.crd;\n\nimport dev.novacain.ntclocks.faces.R;\n\n')
        f.write('public final class CrdStyles {\n    private CrdStyles() {\n    }\n\n')
        f.write('    public static final int[] NUMBERS = {' + ', '.join(str(n) for n, _, _ in rows) + '};\n')
        f.write('    public static final String[] NAMES = {' + ', '.join(f'"{s}"' for _, s, _ in rows) + '};\n')
        f.write('    public static final int[] LAYOUTS = {\n')
        for n, s, d in rows:
            f.write(f'            R.layout.{d},  // {n} {s}\n')
        f.write('    };\n}\n')
    print(f'разметок: {len(rows)}, ресурсов: {len(p.done)}')
    for m in p.missing:
        print('  НЕ НАЙДЕНО:', m)


if __name__ == '__main__':
    main()
