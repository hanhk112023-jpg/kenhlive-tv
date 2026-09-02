#!/usr/bin/env python3
"""
QA click-through driver v2 cho KênhLive Android TV.
- Đọc text + content-desc để đặt tên nút (không còn ''
- Tap từng node clickable, chụp ảnh mỗi bước (có ảnh thật)
- Đục sâu: sau khi tap mở màn mới (Player/dialog/multiview), tự điều hướng + tap tiếp
- Bắt FATAL EXCEPTION + ANR; dò ca sâu (deep 2) để phát hiện nút chết
Output: report.json + shots/*.png

Chạy trên host có adb. python3 qa_click.py <output_dir> <deep>
"""
import json, os, re, subprocess, sys, time

OUT = sys.argv[1] if len(sys.argv) > 1 else "/tmp/qa"
DEEP = int(sys.argv[2]) if len(sys.argv) > 2 else 3
os.makedirs(OUT + "/shots", exist_ok=True)
PKG = "com.kenhlive.tv"

def sh(cmd, timeout=25):
    try:
        r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
        return r.stdout.strip()
    except Exception:
        return ""

def tap(x, y):
    sh(f"adb shell input tap {x} {y}")

def back():
    sh("adb shell input keyevent 4")

def launch():
    sh(f"adb shell am force-stop {PKG}; sleep 1")
    sh(f"adb shell am start -n {PKG}/.MainActivity"); time.sleep(9)

def uia_dump():
    """(text, cx, cy) các node clickable. text = content-desc hay text."""
    avail = sh("adb shell which uiautomator", timeout=15)
    if "uiautomator" not in avail:
        return []
    sh("adb shell uiautomator dump /sdcard/ui.xml", timeout=20)
    xml = sh("adb shell cat /sdcard/ui.xml", timeout=20)
    out = []
    for m in re.finditer(r'<node[^>]*clickable="true"[^>]*>', xml):
        n = m.group(0)
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if not b: continue
        x1,y1,x2,y2 = map(int, b.groups())
        w,h = x2-x1, y2-y1
        if w < 10 or h < 10: continue
        cx, cy = (x1+x2)//2, (y1+y2)//2
        txt = re.search(r'text="([^"]*)"', n)
        desc = re.search(r'content-desc="([^"]*)"', n)
        label = (txt.group(1) if txt and txt.group(1) else
                 (desc.group(1) if desc and desc.group(1) else "node"))
        out.append((label, cx, cy, w, h))
    return out

def crash_count(baseline):
    """(số FATAL/ANR mới, current logcat) so với baseline."""
    cur = sh("adb logcat -d -s AndroidRuntime:E")
    nf = cur.count("FATAL EXCEPTION") - baseline.count("FATAL EXCEPTION")
    na = cur.count("ANR in " + PKG) - baseline.count("ANR in " + PKG)
    return (nf + na, cur, cur.replace(baseline, "")[-1200:])

def scr(fn):
    sh(f"mkdir -p {OUT}/shots")
    sh(f"adb exec-out screencap -p > {fn}", timeout=15)

report = {"pkg": PKG, "deep": DEEP, "tests": [], "crashes": [], "dead": []}
seen = set()
launch(); time.sleep(3)
baseline = sh("adb logcat -d -s AndroidRuntime:E")

def scan_screen(depth, follow=True):
    """Tap mọi node mới trên màn hiện tại. follow=True: sau khi tap mở tầng mới thì đi tiếp."""
    nodes = uia_dump()
    found = []
    for t,cx,cy,w,h in nodes:
        key = (cx//50, cy//50)
        if key in seen: continue
        seen.add(key)
        found.append((t,cx,cy))
    crashed = False
    for t,cx,cy in found:
        print(f"[d{depth}] tap '{t[:28]}' @ {cx},{cy}", flush=True)
        tap(cx, cy); time.sleep(3)
        fn = f"{OUT}/shots/d{depth}_{t[:16].replace('/','_')}.png"
        scr(fn)
        nf, cur, tail = crash_count(baseline)
        report["tests"].append({"depth": depth, "text": t[:60], "xy": [cx,cy],
                                "crash": nf > 0, "shot": fn})
        if nf > 0:
            report["crashes"].append({"depth": depth, "text": t[:60], "xy": [cx,cy],
                                      "sig": tail})
            print("   *** CRASH/ANR sau khi tap ***", flush=True)
            crashed = True
        elif follow and depth < DEEP:
            # mở tầng mới (dialog/Player/multiview) → đi tiếp trước khi BACK
            deeper = uia_dump()
            # nếu có node mới (khác tập seen ngoài tọa độ vừa tap) → đục tiếp
            extra = [x for x in deeper if (x[1]//50, x[2]//50) not in seen]
            if extra:
                print(f"   -> mở tầng mới ({len(extra)} node), đục tiếp", flush=True)
                rc = scan_screen(depth+1, follow=True)
                if rc: crashed = True
        back(); time.sleep(1.5)
    return crashed

def navigate_and_scan():
    """Đục sâu: home -> bấm card -> dialog -> Player -> multiview."""
    for depth in range(1, DEEP+1):
        if scan_screen(depth):
            print("vòng ngừng do crash", flush=True); break
        # sau mỗi vòng, đổi màn bằng D-pad để lộ node sâu
        sh("adb shell input keyevent 20"); time.sleep(0.5)
        sh("adb shell input keyevent 22"); time.sleep(0.5)

navigate_and_scan()

json.dump(report, open(f"{OUT}/report.json","w"), ensure_ascii=False, indent=2)
print("=== QA DONE ===", flush=True)
print("tests:", len(report["tests"]), "crashes:", len(report["crashes"]), flush=True)
