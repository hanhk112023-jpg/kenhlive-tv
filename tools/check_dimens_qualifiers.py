#!/usr/bin/env python3
"""Mô phỏng đúng thuật toán chọn resource của Android (Table 2) cho res/values-*dimens,
rồi in ra giá trị thực tế mà từng cấu hình thiết bị nhận được.

Mục đích: bắt lớp lỗi "qualifier này đè qualifier kia" mà mắt thường không thấy —
ví dụ `land` có độ ưu tiên CAO HƠN `television`, nên values-land đè values-television
ngay trên Android TV (TV luôn ở chế độ ngang).

Chạy:  python3 tools/check_dimens_qualifiers.py [--before]
  --before : bỏ qua values-land-television/ để tái hiện hành vi TRƯỚC khi sửa
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'app', 'src', 'main', 'res')

# Thứ tự ưu tiên Table 2 (chỉ liệt kê những qualifier repo này dùng + khung để mở rộng).
PRECEDENCE = ['mcc', 'locale', 'gender', 'ldrtl', 'sw', 'w', 'h', 'size', 'aspect',
              'round', 'widecg', 'hdr', 'orientation', 'uimode', 'night', 'density', 'touch']


def parse_dir(name):
    """values-land-television -> {'orientation':'land','uimode':'television'}"""
    q = {}
    if name == 'values':
        return q
    for part in name.split('-')[1:]:
        if part in ('land', 'port'):
            q['orientation'] = part
        elif part == 'television':
            q['uimode'] = 'television'
        elif part.startswith('sw') and part.endswith('dp'):
            q['sw'] = int(part[2:-2])
        elif re.fullmatch(r'[wh]\d+dp', part):
            q[part[0]] = int(part[1:-2])
        elif part in ('small', 'normal', 'large', 'xlarge'):
            q['size'] = part
        elif part == 'night':
            q['night'] = 'yes'
    return q


def matches(qual, cfg):
    """Directory qualifier có khớp cấu hình thiết bị không?"""
    for k, v in qual.items():
        if k == 'sw':
            if cfg['sw'] < v:
                return False
        elif k in ('w', 'h'):
            if cfg[k] < v:
                return False
        elif cfg.get(k) != v:
            return False
    return True


def resolve(name, cands, cfg):
    """cands: {qualifier_tuple: value}. Trả về (value, dir) theo đúng luật loại dần của Android."""
    alive = [q for q in cands if matches(parse_dir(q), cfg)]
    if not alive:
        return None, None
    for key in PRECEDENCE:
        declared = [q for q in alive if key in parse_dir(q)]
        if declared:
            alive = declared          # loại mọi dir không khai báo qualifier này
            if len(alive) == 1:
                break
    # cùng qualifier sw/w/h: lấy cái lớn nhất không vượt quá thiết bị
    if len(alive) > 1:
        alive.sort(key=lambda q: parse_dir(q).get('sw', parse_dir(q).get('w', -1)))
    return cands[alive[0]], alive[0]


def main():
    before = '--before' in sys.argv
    tables = {}   # dimen -> {dir: value}
    for d in sorted(os.listdir(RES)):
        if not d.startswith('values'):
            continue
        if before and d == 'values-land-television':
            continue
        p = os.path.join(RES, d, 'dimens.xml')
        if not os.path.isfile(p):
            continue
        for node in ET.parse(p).getroot():
            if node.tag == 'dimen' and node.get('name'):
                tables.setdefault(node.get('name'), {})[d] = (node.text or '').strip()

    configs = {
        'PHONE dọc  ': {'orientation': 'port', 'uimode': 'phone', 'sw': 360, 'w': 360, 'h': 640},
        'PHONE ngang': {'orientation': 'land', 'uimode': 'phone', 'sw': 360, 'w': 640, 'h': 360},
        'TABLET ngang': {'orientation': 'land', 'uimode': 'phone', 'sw': 600, 'w': 960, 'h': 600},
        'ANDROID TV': {'orientation': 'land', 'uimode': 'television', 'sw': 540, 'w': 960, 'h': 540},
    }

    watch = ['hero_h', 'card_w', 'card_thumb_h', 'topbar_h', 'bottomnav_h',
             'sp_screen_margin', 'ts_display']
    bad = 0
    print(f"{'dimen':<18}" + ''.join(f'{c:>16}' for c in configs))
    for name in watch:
        row = f'{name:<18}'
        for cfg_name, cfg in configs.items():
            val, d = resolve(name, tables.get(name, {}), cfg)
            cell = f'{str(val):>10}'
            tv_dirs = ('values-television', 'values-land-television')
            wrong = (cfg_name == 'ANDROID TV' and name in tables
                     and 'values-television' in tables[name] and d not in tv_dirs)
            row += cell + ('  ! ' if wrong else '    ')
            if wrong:
                bad += 1
        print(row)
    print()
    # chi tiết nguồn cho ANDROID TV
    tv = configs['ANDROID TV']
    print('ANDROID TV lấy giá trị từ đâu:')
    for name in watch:
        val, d = resolve(name, tables.get(name, {}), tv)
        print(f'  {name:<18} = {str(val):<8} <- {d}')
    print()
    if bad:
        print(f'LỖI: {bad} dimen trên ANDROID TV không lấy từ values-television/'
              f'values-land-television (đang bị qualifier khác đè)')
    else:
        print('OK: mọi dimen theo dõi trên ANDROID TV đều lấy đúng bản TV')
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
