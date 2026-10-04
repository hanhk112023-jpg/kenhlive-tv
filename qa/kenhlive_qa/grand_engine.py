"""
KenhLive Grand Autonomous AI QA Suite v4.0 - Core Engine
Ket hop:
1. Deterministic Probes: Crash, ANR, Jank (gfxinfo), RAM PSS, Network Triage, Blank Screen
2. State Transition Graph & TV D-Pad Focus Oracle: Phat hien Focus Trap, Focus Loss, Deadlock
3. Special Feature Verifiers: MultiView layout, Picture-in-Picture, IPTV 5-column grid, Auto-Refresh
4. AI Vision Oracle & Reflex Decision: Kilo Gateway (Ling-VL), TypeSafe (Jev System One), Heuristic
"""

import base64
import io
import json
import os
import random
import re
import subprocess
import time
import urllib.request
import xml.etree.ElementTree as ET

try:
    from PIL import Image, ImageChops, ImageStat
except ImportError:
    Image = None
    ImageChops = None
    ImageStat = None

PKG = "com.kenhlive.tv"

# Keycodes Android TV
KEY_UP = 19
KEY_DOWN = 20
KEY_LEFT = 21
KEY_RIGHT = 22
KEY_ENTER = 23
KEY_BACK = 4
KEY_HOME = 3
KEY_MENU = 82

ACTIONS = ["UP", "DOWN", "LEFT", "RIGHT", "ENTER", "BACK"]

KILO_BASE = os.environ.get("KILO_API_BASE", "https://api.kilo.ai/api/gateway/v1/chat/completions")
KILO_KEY = os.environ.get("KILO_API_KEY", "")
KILO_MODEL = os.environ.get("KILO_MODEL", "kilo-auto/free")

TYPESAFE_BASE = os.environ.get("TYPESAFE_API_BASE", "https://api.typesafe.ai/v1/systemone")
TYPESAFE_KEY = os.environ.get("TYPESAFE_API_KEY", "")
TYPESAFE_MODEL = os.environ.get("TYPESAFE_MODEL", "jev-latest")


def sh(cmd, timeout=30):
    try:
        r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
        return (r.stdout or "") + (r.stderr or "")
    except Exception as e:
        return f"__ERR__ {e}"


class GrandQAEngine:
    def __init__(self, pkg=PKG, serial=None, out_dir="/tmp/qa_report"):
        self.pkg = pkg
        self.serial = serial
        self.adb = f"adb -s {serial}" if serial else "adb"
        self.out_dir = out_dir
        self.shots_dir = os.path.join(out_dir, "shots")
        os.makedirs(self.shots_dir, exist_ok=True)

        self.checks = []
        self.findings = []
        self.screenshots = []
        self.action_history = []
        self._logcat_baseline = ""
        self.step_counter = 0

    def add_check(self, name, ok, detail=""):
        self.checks.append({"name": name, "ok": bool(ok), "detail": detail})
        print(("  ✅" if ok else "  ❌") + f" {name} — {detail}", flush=True)

    def add_finding(self, area, severity, issue, evidence="", suggestion=""):
        self.findings.append({
            "area": area,
            "severity": severity,
            "issue": issue,
            "evidence": evidence,
            "suggestion": suggestion
        })
        print(f"  🔎 [{severity}] {area}: {issue[:95]}", flush=True)

    # ---------- Device & ADB Probes ----------
    def key(self, keycode):
        sh(f"{self.adb} shell input keyevent {keycode}")

    def tap(self, x, y):
        sh(f"{self.adb} shell input tap {int(x)} {int(y)}")

    def force_stop(self):
        sh(f"{self.adb} shell am force-stop {self.pkg}")

    def launch(self):
        t0 = time.time()
        sh(f"{self.adb} shell am start -n {self.pkg}/.MainActivity")
        while time.time() - t0 < 30:
            if self.has_focus():
                return round((time.time() - t0) * 1000)
            time.sleep(0.2)
        return -1

    def has_focus(self):
        return self.pkg in sh(f"{self.adb} shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus")

    def current_activity(self):
        out = sh(f"{self.adb} shell dumpsys activity activities 2>/dev/null | grep -m1 -E 'mResumedActivity|topResumedActivity|mFocusedApp'")
        m = re.search(r"([A-Za-z0-9_.]+)/([.A-Za-z0-9_]+)", out)
        return f"{m.group(1)}/{m.group(2)}" if m else "?"

    def logcat_baseline(self):
        self._logcat_baseline = sh(f"{self.adb} logcat -d -v brief", timeout=40)

    def _logcat_new_raw(self):
        cur = sh(f"{self.adb} logcat -d -v threadtime", timeout=40)
        if self._logcat_baseline and self._logcat_baseline in cur:
            return cur.split(self._logcat_baseline, 1)[1]
        return cur

    def check_crashes(self):
        new = self._logcat_new_raw()
        out = []
        for m in re.finditer(r"FATAL EXCEPTION.*?(?=\n\d|\Z)", new, re.S):
            blk = m.group(0)[:600]
            if self.pkg.split(".")[-1] in blk or "AndroidRuntime" in blk:
                out.append({"type": "CRASH", "detail": blk})
        for m in re.finditer(r"ANR in " + re.escape(self.pkg) + r".{0,300}", new, re.S):
            out.append({"type": "ANR", "detail": m.group(0)[:400]})
        return out

    def network_errors(self):
        cur = self._logcat_new_raw()
        pats = [
            (r"SocketTimeoutException", "Timeout gọi API / Server stream"),
            (r"UnknownHostException", "DNS fail (cần proxy VN cho vnres.co)"),
            (r"SSLHandshakeException|SSLException", "Lỗi SSL"),
            (r"HTTP (40[0-9]|50[0-9])", "Lỗi HTTP từ server"),
            (r"ExoPlayer.*?\berror\b|PlaybackFailure", "Lỗi phát luồng video ExoPlayer"),
        ]
        out = []
        for p, desc in pats:
            hits = re.findall(p, cur, re.I)
            if hits:
                out.append({"pattern": p, "count": len(hits), "desc": desc})
        return out

    def screencap(self):
        try:
            raw = subprocess.run(f"{self.adb} exec-out screencap -p".split(), capture_output=True, timeout=25).stdout
            return raw if len(raw) > 8000 else None
        except Exception:
            return None

    def shot(self, label):
        png = self.screencap()
        if not png:
            self.add_finding(label, "HIGH", "Không chụp được screenshot", "screencap trả rỗng", "kiểm tra adb")
            return None
        safe = re.sub(r"[^a-z0-9_.-]", "", label.replace(" ", "_").lower())
        filename = f"{len(self.screenshots)+1:02d}_{safe}.jpg"
        path = os.path.join(self.shots_dir, filename)
        try:
            if Image:
                im = Image.open(io.BytesIO(png)).convert("RGB")
                im.thumbnail((960, 960))
                im.save(path, "JPEG", quality=65)
            else:
                with open(path.replace(".jpg", ".png"), "wb") as f:
                    f.write(png)
                filename = filename.replace(".jpg", ".png")
        except Exception:
            with open(path.replace(".jpg", ".png"), "wb") as f:
                f.write(png)
            filename = filename.replace(".jpg", ".png")
        self.screenshots.append((label, filename))
        return png

    def is_blank(self, png):
        if not png or not Image or not ImageStat:
            return False
        try:
            im = Image.open(io.BytesIO(png)).convert("L").resize((64, 64))
            stat = ImageStat.Stat(im)
            return stat.stddev[0] < 4.0 or stat.mean[0] < 2.0
        except Exception:
            return False

    def pixel_diff(self, png_a, png_b):
        if not png_a or not png_b or not Image or not ImageStat or not ImageChops:
            return 0.0
        try:
            A = Image.open(io.BytesIO(png_a)).convert("L").resize((80, 45))
            B = Image.open(io.BytesIO(png_b)).convert("L").resize((80, 45))
            diff = ImageChops.difference(A, B)
            return float(ImageStat.Stat(diff).mean[0] / 255 * 100)
        except Exception:
            return 0.0

    def dump_hierarchy(self):
        sh(f"{self.adb} shell uiautomator dump /sdcard/uidump.xml >/dev/null 2>&1")
        xml_text = sh(f"{self.adb} shell cat /sdcard/uidump.xml 2>/dev/null", timeout=15)
        if "<hierarchy" not in xml_text:
            return None, None, []
        try:
            start = xml_text.find("<hierarchy")
            end = xml_text.rfind("</hierarchy>")
            if start != -1 and end != -1:
                xml_text = xml_text[start:end + len("</hierarchy>")]
            root = ET.fromstring(xml_text.strip())
            focused_node = None
            nodes = []
            for elem in root.iter("node"):
                a = elem.attrib
                info = {
                    "id": a.get("resource-id", ""),
                    "class": a.get("class", ""),
                    "text": a.get("text", ""),
                    "bounds": a.get("bounds", ""),
                    "focused": a.get("focused") == "true",
                    "focusable": a.get("focusable") == "true"
                }
                nodes.append(info)
                if info["focused"]:
                    focused_node = info
            return root, focused_node, nodes
        except Exception:
            return None, None, []

    # ---------- AI Vision Judge ----------
    def judge_vision(self, label, png_bytes):
        if not png_bytes:
            return
        # Heuristic check truoc
        if self.is_blank(png_bytes):
            self.add_finding(label, "CRITICAL", "Màn hình rỗng/đen hoàn toàn (Dead Screen)", "std pixel < 4.0", "Kiểm tra render lifecycle hoặc dữ liệu mạng")

        # Neu co Kilo API:
        if KILO_KEY and Image:
            try:
                im = Image.open(io.BytesIO(png_bytes)).convert("RGB")
                im.thumbnail((1280, 720))
                buf = io.BytesIO()
                im.save(buf, format="JPEG", quality=75)
                b64_img = base64.b64encode(buf.getvalue()).decode()

                prompt = f"""Đánh giá màn hình Android TV 10-foot: '{label}'.
Kiểm tra nghiêm ngặt:
1. FOCUS D-PAD: View đang chọn có viền trắng/cyan nổi bật hoặc scale >1.05x không?
2. BỐ CỤC: Nền tối thuần, thẻ tỉ lệ 16:9, chữ không tràn lề/đè nhau.
3. PHÁT TRỰC TIẾP: Nếu là Player phải có luồng phát hoặc thông báo mạng rõ ràng.

Trả về duy nhất JSON:
{{"ok": true/false, "score": 0..100, "summary": "...", "findings": [{{"severity": "CRITICAL"|"HIGH"|"MEDIUM"|"LOW", "area": "{label}", "issue": "...", "suggestion": "..."}}]}}
"""
                payload = {
                    "model": KILO_MODEL,
                    "temperature": 0.1,
                    "messages": [
                        {
                            "role": "user",
                            "content": [
                                {"type": "text", "text": prompt},
                                {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{b64_img}"}}
                            ]
                        }
                    ]
                }
                req = urllib.request.Request(
                    KILO_BASE,
                    data=json.dumps(payload).encode(),
                    headers={"Content-Type": "application/json", "Authorization": f"Bearer {KILO_KEY}", "User-Agent": "curl/8.5.0"}
                )
                with urllib.request.urlopen(req, timeout=30) as resp:
                    res = json.loads(resp.read().decode())
                    content = res["choices"][0]["message"].get("content", "")
                    m = re.search(r"\{.*\}", content, re.DOTALL)
                    if m:
                        data = json.loads(m.group(0))
                        for f in data.get("findings", []):
                            self.add_finding(f.get("area", label), f.get("severity", "MEDIUM"), f.get("issue", ""), f.get("evidence", ""), f.get("suggestion", ""))
            except Exception as e:
                print(f"    (AI Vision Judge offline: {e})", flush=True)

    # ---------- Autonomous Exploration & Stress Hunter ----------
    def run_autonomous_hunter(self, steps=30):
        print(f"\n🌪️ Chạy AI Autonomous Hunter & Stress Exploration ({steps} bước)...", flush=True)
        recent_actions = []
        for s in range(steps):
            _, focused_node, focusable = self.dump_hierarchy()
            focus_sig = f"{focused_node.get('id', '')}#{focused_node.get('bounds', '')}" if focused_node else "NO_FOCUS"

            # Tự quyết định hành động thông minh
            if TYPESAFE_KEY and focused_node:
                act = self._decide_jev(focus_sig, recent_actions)
            else:
                # Weighted random có phản xạ
                if recent_actions and recent_actions[-1] == "ENTER":
                    act = random.choice(["DOWN", "RIGHT", "BACK"])
                elif len([a for a in recent_actions[-4:] if a == "DOWN"]) >= 3:
                    act = random.choice(["ENTER", "RIGHT", "DOWN"])
                else:
                    act = random.choices(ACTIONS, weights=[10, 35, 5, 25, 20, 5], k=1)[0]

            recent_actions.append(act)
            if len(recent_actions) > 8:
                recent_actions.pop(0)

            # Thực thi phím
            key_map = {"UP": KEY_UP, "DOWN": KEY_DOWN, "LEFT": KEY_LEFT, "RIGHT": KEY_RIGHT, "ENTER": KEY_ENTER, "BACK": KEY_BACK}
            self.key(key_map.get(act, KEY_DOWN))
            time.sleep(0.6)

            # Kiểm tra crash lập tức
            cr = self.check_crashes()
            if cr:
                png = self.shot(f"crash_step_{s+1}")
                for c in cr:
                    self.add_finding("Crash", "CRITICAL", f"Crash ở bước hunter {s+1} khi bấm {act}", c["detail"][:250], "Fix stacktrace lifecycle")
                break

            # Kiểm tra mất focus (Focus Loss)
            if (s + 1) % 6 == 0:
                _, new_foc, _ = self.dump_hierarchy()
                if not new_foc and self.has_focus():
                    self.add_finding("Điều hướng", "HIGH", f"Mất dấu focus (không view nào focused) sau chuỗi phím", f"recent: {recent_actions}", "Bảo đảm focus chain, requestFocus()")

    def _decide_jev(self, focus_sig, recent):
        try:
            req = urllib.request.Request(
                TYPESAFE_BASE,
                data=json.dumps({
                    "state": f"Focus: {focus_sig} | Recent: {recent[-4:]}",
                    "model": TYPESAFE_MODEL,
                    "questions": {
                        "decision": {
                            "type": "choice",
                            "instructions": "Quyết định phím D-pad tiếp theo cho Android TV",
                            "criteria": {"DOWN": "Xuống", "RIGHT": "Phải", "ENTER": "Mở", "BACK": "Lùi", "UP": "Lên", "LEFT": "Trái"}
                        }
                    }
                }).encode(),
                headers={"Content-Type": "application/json", "Authorization": f"Bearer {TYPESAFE_KEY}"}
            )
            with urllib.request.urlopen(req, timeout=3) as r:
                res = json.loads(r.read().decode())
                return res.get("answers", {}).get("decision", {}).get("choice", "DOWN")
        except Exception:
            return "DOWN"
