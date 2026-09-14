#!/usr/bin/env python3
"""Kiem tra cheo tai nguyen UI (khong can Android SDK):
 - XML layout phai well-formed
 - Moi @id/@string/@drawable/@color/@dimen/@style/@font tham chieu trong XML phai ton tai
 - Moi R.id/R.string/... trong Kotlin phai ton tai
 - Moi id dung trong findViewById phai duoc khai bao trong mot layout nao do
 - <include layout="@layout/x"> phai ton tai
"""
import os
import re
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..')
RES = os.path.join(ROOT, 'app', 'src', 'main', 'res')
JAVA = os.path.join(ROOT, 'app', 'src', 'main', 'java')
ANDROID_NS = '{http://schemas.android.com/apk/res/android}'

TYPES = ['string', 'drawable', 'color', 'dimen', 'style', 'layout', 'font', 'id', 'array',
         'plurals', 'integer', 'bool', 'mipmap', 'anim']

defined = defaultdict(set)      # type -> {name}
refs = []                       # (file, line, type, name)
id_declared = defaultdict(set)  # type -> name (from @+id)


def res_type_of(path):
    return os.path.basename(path).split('-')[0]


# ---- 1. values/*.xml: dinh nghia name ----
def scan_values():
    for dirpath, _, files in os.walk(RES):
        if res_type_of(dirpath) != 'values':
            continue
        for fn in files:
            if not fn.endswith('.xml'):
                continue
            p = os.path.join(dirpath, fn)
            tree = ET.parse(p)
            for node in tree.getroot():
                tag = node.tag
                name = node.get('name')
                if not name:
                    continue
                if tag in ('string', 'string-array', 'integer-array', 'array'):
                    defined['string' if tag == 'string' else 'array'].add(name)
                elif tag in ('color', 'dimen', 'integer', 'bool', 'style', 'plurals'):
                    defined[tag].add(name)
                elif tag == 'item':
                    t = node.get('type', 'id')
                    defined[t].add(name)


# ---- 2. file tai nguyen khac (drawable/, layout/, font/, mipmap/) ----
def scan_files():
    for dirpath, _, files in os.walk(RES):
        t = res_type_of(dirpath)
        if t in ('values', 'xml', 'raw', 'anim'):
            if t == 'anim':
                for fn in files:
                    if fn.endswith('.xml'):
                        defined['anim'].add(fn[:-4])
            continue
        for fn in files:
            base, ext = os.path.splitext(fn)
            if ext in ('.xml', '.png', '.jpg', '.jpeg', '.webp', '.ttf', '.otf', '.9.png'):
                defined[t].add(base.replace('.9', ''))


# ---- 3. duyet layout: thu thap @+id va tham chieu ----
REF_RE = re.compile(r'@(?:\+)?(\w+):?(\w+)/([\w.\-]+)')


def scan_layouts():
    for dirpath, _, files in os.walk(RES):
        t = res_type_of(dirpath)
        if t not in ('layout', 'drawable', 'color', 'anim', 'menu'):
            continue
        for fn in sorted(files):
            if not fn.endswith('.xml'):
                continue
            p = os.path.join(dirpath, fn)
            rel = os.path.relpath(p, RES)
            try:
                tree = ET.parse(p)
            except ET.ParseError as e:
                print(f'[XML-ERROR] {rel}: {e}')
                continue
            for node in tree.iter():
                for k, v in node.attrib.items():
                    if not isinstance(v, str):
                        continue
                    if k == ANDROID_NS + 'id' and v.startswith('@+id/'):
                        id_declared['id'].add(v[5:])
                    for m in REF_RE.finditer(v):
                        pkg, rtype, name = m.groups()
                        if pkg == 'android':
                            continue
                        refs.append((rel, rtype, name))
                if node.tag.endswith('include'):
                    lay = node.get(ANDROID_NS + 'layout', '')
                    if lay.startswith('@layout/'):
                        refs.append((rel, 'layout', lay.split('/', 1)[1]))


def main():
    scan_values()
    scan_files()
    scan_layouts()
    problems = 0

    # 3a. tham chieu trong XML
    for rel, rtype, name in refs:
        if rtype not in TYPES:
            continue
        if rtype == 'id':
            if name not in id_declared['id']:
                print(f'[XML] {rel}: @id/{name} KHONG duoc khai bao trong bat ky layout nao')
                problems += 1
            continue
        if name not in defined.get(rtype, set()):
            # android:* he thong bo qua
            print(f'[XML] {rel}: @{rtype}/{name} KHONG ton tai')
            problems += 1

    # 3b. R.* trong Kotlin
    kt_refs = []
    r_re = re.compile(r'\bR\.(\w+)\.(\w+)')
    for dirpath, _, files in os.walk(JAVA):
        for fn in sorted(files):
            if not fn.endswith('.kt'):
                continue
            p = os.path.join(dirpath, fn)
            rel = os.path.relpath(p, ROOT)
            for i, line in enumerate(open(p, encoding='utf-8').read().splitlines(), 1):
                if line.strip().startswith('//'):
                    continue
                for m in r_re.finditer(line):
                    # android.R.anim.fade_in -> tai nguyen framework, bo qua
                    if line[max(0, m.start() - 8):m.start()].endswith('android.'):
                        continue
                    kt_refs.append((rel, i, m.group(1), m.group(2)))
    for rel, line, rtype, name in kt_refs:
        if rtype not in TYPES:
            continue
        pool = id_declared['id'] | defined['id'] if rtype == 'id' else defined.get(rtype, set())
        if name not in pool:
            print(f'[KT] {rel}:{line}: R.{rtype}.{name} KHONG ton tai')
            problems += 1

    # 3c. findViewById id khong co trong layout
    fv_re = re.compile(r'findViewById[^(]*\(\s*R\.id\.(\w+)')
    for dirpath, _, files in os.walk(JAVA):
        for fn in sorted(files):
            if not fn.endswith('.kt'):
                continue
            p = os.path.join(dirpath, fn)
            rel = os.path.relpath(p, ROOT)
            for i, line in enumerate(open(p, encoding='utf-8').read().splitlines(), 1):
                for m in fv_re.finditer(line):
                    if m.group(1) not in id_declared['id']:
                        print(f'[KT] {rel}:{line}: findViewById(R.id.{m.group(1)}) — id khong co trong layout')
                        problems += 1

    # 3d. id dinh nghia trong values nhung khong dung / du (chi canh bao nhe)
    print(f'---\ntong: {len(refs)} tham chieu XML, {len(kt_refs)} tham chieu Kotlin, '
          f'{len(id_declared["id"])} id khai bao')
    print(f'LOI: {problems}')
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
