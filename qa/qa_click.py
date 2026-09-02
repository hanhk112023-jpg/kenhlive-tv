#!/usr/bin/env python3
"""
QA click-through driver cho KênhLive Android TV.
Quét mọi node clickable/focusable trong UI (UIAutomator dump), tap từng cái,
chụp ảnh, bấm BACK để quay lại, kiểm tra logcat bắt crash. Ghi báo cáo.

Chạy trên host đã có adb (sau khi emulator-runner khởi động).
Cách dùng: python3 qa_click.py <output_dir>
"""
import json, os, re, subprocess, sys, time

OUT = sys.argv[1] if len(sys.argv) > 1 else "/tmp/qa"
os.makedirs(OUT + "/shots", exist_ok=True)

PKG = "com.kenhlive.tv"

def sh(cmd, timeout=25):
    try:
        r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
        return r.stdout.strip()
    except Exception as e:
        return ""

def tap(x, y):
    sh(f"adb shell input tap {x} {y}")

def back():
    sh("adb shell input keyevent 4")

def home():
    sh(f"adb shell am force-stop {PKG}; sleep 1")

def launch(extra=""):
    sh(f"adb shell am start -n {PKG}/.MainActivity {extra}"); time.sleep(8)

def uia_dump():
    # trả về list (text,cx,cy) các node clickable; [] nếu uiautomator không có
    avail = sh("adb shell which uiautomator", timeout=15)
    if "uiautomator" not in avail:
        return []
    sh("adb shell uiautomator dump /sdcard/ui.xml", timeout=20)
    xml = sh("adb shell cat /sdcard/ui.xml", timeout=20)
    out = []
    for m in re.finditer(r'<node[^>]*clickable="true"[^>]*>', xml):
        n = m.group(0)
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        txt = re.search(r'text="([^"]*)"', n)
        if not b: continue
        x1,y1,x2,y2 = map(int, b.groups())
        w,h = x2-x1, y2-y1
        if w < 10 or h < 10 or x2 < 0 or y2 < 0: continue
        cx, cy = (x1+x2)//2, (y1+y2)//2
        out.append((txt.group(1) if txt else "no-text", cx, cy, w, h))
    return out

def dpad_sweep():
    """Fallback: quét D-pad — đi phải rồi xuống, bấm CENTER mỗi vị trí mới."""
    results = []
    for row in range(3):
        for col in range(8):
            sh("adb shell input keyevent 22"); time.sleep(0.35)   # RIGHT
        sh("adb shell input keyevent 20"); time.sleep(0.35)        # DOWN
    # quay về góc trên để reset focus
    sh("adb shell input keyevent 19"); time.sleep(0.3)
    return results

def fresh_fatal_seq():
    # đếm số dòng FATAL/số PID crash trong logcat chưa xử lý
    s = sh("adb logcat -d -s AndroidRuntime:E")
    # lấy danh sách "FATAL EXCEPTION" block đã thấy lần trước
    return s

def crash_count(since):
    current = sh("adb logcat -d -s AndroidRuntime:E")
    new = current.replace(since, "")
    # số lần "FATAL EXCEPTION" xuất hiện mới
    return new.count("FATAL EXCEPTION"), current

def reported(crash_sig):
    # cắt ngắn để signature nhẹ
    return crash_sig[-2000:]

report = {"pkg": PKG, "tests": [], "crashes": []}
seen = set()
baseline = ""

launch()
time.sleep(4)
baseline = fresh_fatal_seq()

dumps = 0
uia_ok = True
while dumps < 4:  # duyệt tối đa 4 màn hình (home → rows → player → multiview)
    dumps += 1
    nodes = uia_dump()
    if not nodes:
        if uia_ok:
            uia_ok = False
            print("[!] uiautomator rỗng — chuyển D-pad sweep", flush=True)
        dpad_sweep()
        # sau sweep, chụp 1 tấm để xem có crash
        cr = crash_count(baseline)
        report["tests"].append({"screen": dumps, "text": "D-PAD sweep", "xy": [0,0], "crash": cr[0]>0})
        if cr[0] > 0:
            report["crashes"].append({"screen": dumps, "text": "D-PAD sweep", "sig": reported(cr[1])})
        continue
    found = []
    for t,cx,cy,w,h in nodes:
        key = (cx//60, cy//60)
        if key in seen: continue
        seen.add(key)
        found.append((t,cx,cy))
    if not found:
        print(f"[{dumps}] hết nút mới trên màn này", flush=True)
        break
    for t,cx,cy in found:
        print(f"[{dumps}] tap '{t[:24]}' @ {cx},{cy}", flush=True)
        tap(cx, cy)
        time.sleep(2.5)
        # chụp ảnh trước khi quay lại
        fn = f"{OUT}/shots/click_{dumps}_{len(report['tests'])}.png"
        sh(f"adb exec-out screencap -p > {fn}")
        nc, cur = crash_count(baseline)
        report["tests"].append({"screen": dumps, "text": t[:50], "xy": [cx,cy], "crash": nc>0})
        if nc > 0:
            report["crashes"].append({"screen": dumps, "text": t[:50], "xy": [cx,cy], "sig": reported(cur)})
            print("   *** CRASH sau khi tap ***", flush=True)
            break
        back(); time.sleep(1.2)
    # sau vòng này thử xuống + sang phải để lộ hàng/thêm nút
    sh("adb shell input keyevent 20"); time.sleep(0.6)
    sh("adb shell input keyevent 22"); time.sleep(0.6)

json.dump(report, open(f"{OUT}/report.json","w"), ensure_ascii=False, indent=2)
print("=== QA DONE ===", flush=True)
print("tests:", len(report["tests"]), "crashes:", len(report["crashes"]), flush=True)
