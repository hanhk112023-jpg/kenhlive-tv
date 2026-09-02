#!/usr/bin/env python3
"""
QA click-through driver v3 cho KênhLive Android TV — KHÓA TRONG APP.
- Chỉ tap node thuộc cửa sổ app (mất focus → relaunch ngay)
- Bỏ qua node hệ thống (launcher/settings/notification)
- Text + content-desc để đặt tên nút
- Ảnh mỗi bước (screencap -> file)
- Bắt FATAL EXCEPTION + ANR
Output: report.json + shots/*.png

python3 qa_click.py <output_dir> <deep>
"""
import json, os, re, subprocess, sys, time

OUT = sys.argv[1] if len(sys.argv) > 1 else "/tmp/qa"
DEEP = int(sys.argv[2]) if len(sys.argv) > 2 else 4
os.makedirs(OUT + "/shots", exist_ok=True)
PKG = "com.kenhlive.tv"

# text node hệ thống cần bỏ (launcher Android TV / search / setup wizard)
SYSTEM_TEXTS = ("search", "sign in", "notification", "system", "settings",
                "apps", "youtube", "dismiss", "google", "favorites", "home")

def sh(cmd, timeout=25):
    try:
        r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
        return r.stdout.strip()
    except Exception:
        return ""

def tap(x, y): sh(f"adb shell input tap {x} {y}")

def key(k): sh(f"adb shell input keyevent {k}")

def back(): key("4")

def is_app_focused():
    f = sh("adb shell dumpsys window | grep mCurrentFocus")
    return f and PKG in f

def launch():
    sh(f"adb shell am force-stop {PKG}")
    time.sleep(1)
    sh(f"adb shell am start -n {PKG}/.MainActivity")
    time.sleep(9)
    if not is_app_focused():
        time.sleep(4)
        sh(f"adb shell am start -n {PKG}/.MainActivity")
        time.sleep(6)

def uia_dump():
    """(text,cx,cy) các node clickable của APP; [] nếu rỗng/khoá không vào."""
    if not is_app_focused():
        launch()
        time.sleep(2)
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
        low = label.lower()
        # bỏ node thuộc launcher hệ thống (tránh thoát app)
        if any(s in low for s in SYSTEM_TEXTS):
            continue
        # bỏ node quá nhỏ / ở sát mép (thanh trạng thái/notification)
        if cy < 60 or w < 20:
            continue
        out.append((label, cx, cy, w, h))
    return out

def crash_count(baseline):
    cur = sh("adb logcat -d -s AndroidRuntime:E")
    nf = cur.count("FATAL EXCEPTION") - baseline.count("FATAL EXCEPTION")
    na = cur.count("ANR in " + PKG) - baseline.count("ANR in " + PKG)
    return (nf + na, cur.replace(baseline, "")[-1500:])

def scr(fn):
    sh(f"mkdir -p {OUT}/shots")
    # exec-out sang file (tránh mang \r\n qua shell)
    with open(fn, "wb") as f:
        subprocess.run("adb exec-out screencap -p".split(), stdout=f, timeout=20)

report = {"pkg": PKG, "deep": DEEP, "tests": [], "crashes": []}
seen = set()
launch(); time.sleep(2)
baseline = sh("adb logcat -d -s AndroidRuntime:E")

def scan_screen(depth):
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
        nf, tail = crash_count(baseline)
        report["tests"].append({"depth": depth, "text": t[:60], "xy": [cx,cy],
                                "crash": nf > 0, "shot": fn})
        if nf > 0:
            report["crashes"].append({"depth": depth, "text": t[:60], "xy": [cx,cy], "sig": tail})
            print("   *** CRASH/ANR ***", flush=True)
            crashed = True
        else:
            # đục sâu nếu mở tầng mới và vẫn trong app
            if depth < DEEP and is_app_focused():
                extra = [x for x in uia_dump() if (x[1]//50, x[2]//50) not in seen]
                if extra:
                    print(f"   -> tầng mới {len(extra)} node", flush=True)
                    if scan_screen(depth+1): crashed = True
        back(); time.sleep(1.5)
        if not is_app_focused():
            launch(); time.sleep(2)
    return crashed

for depth in range(1, DEEP+1):
    if scan_screen(depth): break
    key("20"); time.sleep(0.5)   # D-pad xuống lộ hàng
    key("22"); time.sleep(0.5)

json.dump(report, open(f"{OUT}/report.json","w"), ensure_ascii=False, indent=2)
print("=== QA DONE === tests:", len(report["tests"]), "crashes:", len(report["crashes"]), flush=True)
