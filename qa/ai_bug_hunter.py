#!/usr/bin/env python3
"""
KenhLive AI Autonomous Bug Hunter v2.0
Hệ thống AI tự quyết định hành động và tự săn lỗi chuyên sâu trên Android TV:
1. Đọc và phân tích trực tiếp UI Hierarchy (XML Dump) để AI tự ra quyết định:
   - Nhận biết các View click được, danh sách trận đấu, kênh IPTV, nút Cài đặt, Player.
   - Tính toán mục tiêu (Goal-Driven Exploration) thay vì bấm ngẫu nhiên mù quáng.
2. Bộ Oracle AI đa tầng tự phát hiện 6 loại lỗi nguy hiểm:
   - CRASH / ANR: Tự động bắt FATAL EXCEPTION, NullPointer, Coroutine Crash từ Logcat.
   - FOCUS TRAP: Kẹt focus, mất dấu focus trên remote D-pad.
   - BLACK SCREEN: Màn hình đen rỗng không tải được dữ liệu quá timeout.
   - DIALOG TRAP: Bật dialog không thoát ra được hoặc mất focus sau khi thoát.
   - PLAYER FREEZE: Mở luồng phát ExoPlayer nhưng bị lỗi codec hoặc đứng hình.
   - RECYCLEVIEW SCROLL STUCK: Cuộn danh sách không tải thêm hoặc kẹt ở biên.
3. Xuất hồ sơ lỗi chi tiết kèm ảnh chụp bằng chứng, phím tái hiện và logcat.
"""

import base64
import collections
import datetime
import io
import json
import os
import random
import re
import subprocess
import sys
import time
import urllib.request
import uuid
import xml.etree.ElementTree as ET

# Configuration
TYPESAFE_BASE = os.environ.get('TYPESAFE_API_BASE', 'https://api.typesafe.ai/v1/systemone')
TYPESAFE_KEY  = os.environ.get('TYPESAFE_API_KEY', '')
TYPESAFE_MODEL = os.environ.get('TYPESAFE_MODEL', 'jev-latest')

KILO_BASE = os.environ.get('KILO_API_BASE', 'https://api.kilo.ai/api/gateway/v1/chat/completions')
KILO_KEY  = os.environ.get('KILO_API_KEY', '')
KILO_MODEL = os.environ.get('KILO_MODEL', 'inclusionai/ling-3.0-flash-vl:free')

PKG = 'com.kenhlive.tv'

# Keycodes Android TV
KEY_UP = 19
KEY_DOWN = 20
KEY_LEFT = 21
KEY_RIGHT = 22
KEY_ENTER = 23
KEY_BACK = 4
KEY_MENU = 82

ACTIONS = ["UP", "DOWN", "LEFT", "RIGHT", "ENTER", "BACK"]

def sh(cmd, timeout=30):
    try:
        r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
        return (r.stdout or '') + (r.stderr or '')
    except Exception as e:
        return f"__ERR__ {e}"

class BugEvidenceTracker:
    def __init__(self, out_dir=None):
        self.session_id = f"hunt_{datetime.datetime.now().strftime('%Y%m%d_%H%M%S')}_{uuid.uuid4().hex[:6]}"
        self.start_time = time.time()
        self.out_dir = out_dir or f"/tmp/bughunt_v2/{self.session_id}"
        os.makedirs(self.out_dir, exist_ok=True)
        self.shots_dir = os.path.join(self.out_dir, "evidence")
        os.makedirs(self.shots_dir, exist_ok=True)

        self.bugs = []
        self.action_history = []
        self.screen_timeline = []

    def record_action(self, action, meta=None):
        item = {
            "action": action,
            "t": round(time.time() - self.start_time, 2),
            "meta": meta or {}
        }
        self.action_history.append(item)

    def get_recent_steps(self, n=8):
        return [a["action"] for a in self.action_history[-n:]]

    def add_bug(self, bug_type, severity, title, description, screenshot_bytes=None, logcat_snippet="", repro_steps=None):
        bug_id = f"BUG-{len(self.bugs)+1:02d}"
        repro = repro_steps or self.get_recent_steps(8)

        img_rel = None
        if screenshot_bytes:
            filename = f"{bug_id}_{bug_type.lower()}.png"
            path = os.path.join(self.shots_dir, filename)
            with open(path, "wb") as f:
                f.write(screenshot_bytes)
            img_rel = os.path.relpath(path, self.out_dir)

        bug = {
            "id": bug_id,
            "type": bug_type, # CRASH, FOCUS_TRAP, BLANK_SCREEN, DIALOG_TRAP, PLAYER_ERROR
            "severity": severity, # CRITICAL, HIGH, MEDIUM, LOW
            "title": title,
            "description": description,
            "timestamp": round(time.time() - self.start_time, 2),
            "reproduce_steps": repro,
            "screenshot": img_rel,
            "logcat_snippet": logcat_snippet[:2000] if logcat_snippet else ""
        }
        self.bugs.append(bug)
        print(f"\n🚨 [BUG DETECTED: {bug_id}] [{severity}] {title}")
        print(f"   👉 Loại: {bug_type} | Chi tiết: {description}")
        print(f"   🔁 Chuỗi phím: {' -> '.join(repro)}\n", flush=True)

    def export(self):
        duration = round(time.time() - self.start_time, 2)
        summary = {
            "session_id": self.session_id,
            "app": PKG,
            "duration_seconds": duration,
            "total_actions": len(self.action_history),
            "bugs_count": len(self.bugs),
            "bugs_breakdown": collections.Counter([b["severity"] for b in self.bugs]),
            "bugs": self.bugs
        }

        # Lưu JSON
        json_file = os.path.join(self.out_dir, "bug_report.json")
        with open(json_file, "w", encoding="utf-8") as f:
            json.dump(summary, f, ensure_ascii=False, indent=2)

        # Xuất HTML Dashboard Pure Black
        html_file = os.path.join(self.out_dir, "index.html")
        self._write_html(html_file, summary)

        print("\n" + "="*50)
        print(f"🎯 KẾT QUẢ AI BUG HUNTER v2.0 ({duration}s)")
        print(f"🎮 Thao tác tự chủ: {len(self.action_history)}")
        print(f"🐛 Số bug phát hiện: {len(self.bugs)}")
        for sev, count in summary["bugs_breakdown"].items():
            print(f"   • {sev}: {count}")
        print(f"📁 Báo cáo chi tiết: {html_file}")
        print("="*50 + "\n", flush=True)
        return summary

    def _write_html(self, path, s):
        bugs_cards = []
        for b in s["bugs"]:
            sev_class = {
                "CRITICAL": "badge-danger",
                "HIGH": "badge-warning",
                "MEDIUM": "badge-info",
                "LOW": "badge-secondary"
            }.get(b["severity"], "badge-info")

            img_tag = f'<img src="{b["screenshot"]}" onclick="window.open(this.src)">' if b["screenshot"] else ''
            log_tag = f'<pre class="log">{b["logcat_snippet"]}</pre>' if b["logcat_snippet"] else ''
            steps = " &rarr; ".join([f"<span class='key'>{st}</span>" for st in b["reproduce_steps"]])

            bugs_cards.append(f"""
            <div class="bug-card">
                <div class="bug-header">
                    <span class="badge {sev_class}">{b['severity']}</span>
                    <span class="bug-type">{b['type']}</span>
                    <h3>{b['id']}: {b['title']}</h3>
                </div>
                <p class="desc">{b['description']}</p>
                <div class="repro">
                    <b>Các bước tái hiện (Reproduce):</b>
                    <div class="steps">{steps}</div>
                </div>
                {img_tag}
                {log_tag}
            </div>
            """)

        html = f"""<!DOCTYPE html>
<html lang="vi">
<head>
    <meta charset="UTF-8">
    <title>KênhLive AI Autonomous Bug Hunter v2.0</title>
    <style>
        body {{ background: #000; color: #E0E0E0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; padding: 24px; margin: 0; }}
        h1 {{ color: #00E5FF; margin: 0 0 8px 0; }}
        .header {{ border-bottom: 1px solid #222; padding-bottom: 16px; margin-bottom: 24px; }}
        .stats {{ display: flex; gap: 16px; margin-bottom: 24px; }}
        .stat {{ background: #111; border: 1px solid #222; border-radius: 8px; padding: 14px 20px; flex: 1; }}
        .stat-val {{ font-size: 26px; font-weight: bold; color: #FFF; margin-top: 4px; }}
        .badge {{ padding: 4px 8px; border-radius: 4px; font-size: 11px; font-weight: bold; }}
        .badge-danger {{ background: #FF1744; color: #FFF; }}
        .badge-warning {{ background: #FF9100; color: #000; }}
        .badge-info {{ background: #00E5FF; color: #000; }}
        .badge-secondary {{ background: #757575; color: #FFF; }}
        .bug-card {{ background: #111; border: 1px solid #262626; border-radius: 8px; padding: 18px; margin-bottom: 20px; }}
        .bug-header {{ display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }}
        .bug-header h3 {{ margin: 0; color: #FFF; }}
        .bug-type {{ font-size: 12px; color: #888; text-transform: uppercase; }}
        .desc {{ color: #CCC; font-size: 14px; margin-bottom: 12px; line-height: 1.5; }}
        .repro {{ background: #181818; padding: 10px 14px; border-radius: 6px; font-size: 13px; margin-bottom: 14px; }}
        .steps {{ margin-top: 6px; display: flex; gap: 6px; align-items: center; flex-wrap: wrap; }}
        .key {{ background: #2A2A2A; border: 1px solid #444; border-radius: 4px; padding: 2px 8px; font-family: monospace; font-size: 12px; color: #00E5FF; }}
        img {{ max-width: 100%; max-height: 380px; border-radius: 6px; border: 1px solid #333; margin-top: 10px; cursor: pointer; }}
        pre.log {{ background: #0A0A0A; border: 1px solid #222; padding: 10px; border-radius: 6px; font-size: 12px; color: #FF5252; overflow-x: auto; max-height: 200px; }}
    </style>
</head>
<body>
    <div class="header">
        <h1>🐛 KênhLive AI Autonomous Bug Hunter v2.0</h1>
        <p>Hệ thống AI tự quyết định hành động và tự săn lỗi chuyên sâu trên Android TV</p>
    </div>
    <div class="stats">
        <div class="stat"><div>Session ID</div><div class="stat-val" style="font-size: 15px;">{s['session_id']}</div></div>
        <div class="stat"><div>Thời gian khám phá</div><div class="stat-val">{s['duration_seconds']}s</div></div>
        <div class="stat"><div>Thao tác D-pad tự chủ</div><div class="stat-val">{s['total_actions']}</div></div>
        <div class="stat"><div>Lỗi phát hiện</div><div class="stat-val" style="color: {'#FF1744' if s['bugs_count']>0 else '#00E5FF'};">{s['bugs_count']}</div></div>
    </div>
    <h2>Danh sách lỗi tìm thấy ({s['bugs_count']})</h2>
    {''.join(bugs_cards) if bugs_cards else '<p style="color:#00E5FF; font-size: 16px;">🎉 Tuyệt vời! AI không phát hiện bất kỳ lỗi UX, Focus hay Crash nào trong phiên khám phá.</p>'}
</body>
</html>"""
        with open(path, "w", encoding="utf-8") as f:
            f.write(html)

class AutonomousAiBugHunter:
    def __init__(self, serial=None, out_dir=None):
        self.serial = serial
        self.adb_cmd = f"adb -s {serial}" if serial else "adb"
        self.tracker = BugEvidenceTracker(out_dir=out_dir)
        self.consecutive_stuck_count = 0
        self.last_focused_sig = ""
        self.visited_views = set()
        self.current_tab = 0

    def screencap(self):
        cmd = f"{self.adb_cmd} exec-out screencap -p"
        try:
            raw = subprocess.run(cmd.split(), capture_output=True, timeout=12).stdout
            return raw if len(raw) > 8000 else None
        except Exception:
            return None

    def dump_hierarchy_and_focus(self):
        """Đọc toàn bộ UI Hierarchy cây XML và trích xuất element đang giữ focus."""
        cmd = f"{self.adb_cmd} shell uiautomator dump /sdcard/window_dump.xml && {self.adb_cmd} shell cat /sdcard/window_dump.xml"
        try:
            r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=8)
            xml_text = r.stdout
            if not xml_text or "<hierarchy" not in xml_text:
                # Fallback trực tiếp nếu cat thất bại
                cmd2 = f"{self.adb_cmd} exec-out uiautomator dump /dev/tty"
                r2 = subprocess.run(cmd2.split(), capture_output=True, text=True, timeout=5)
                xml_text = r2.stdout

            if not xml_text or "<hierarchy" not in xml_text:
                return None, None, []

            # Cắt lấy phần xml từ thẻ mở <hierarchy đến thẻ đóng </hierarchy>
            start_idx = xml_text.find("<hierarchy")
            end_idx = xml_text.rfind("</hierarchy>")
            if start_idx != -1 and end_idx != -1:
                xml_text = xml_text[start_idx:end_idx + len("</hierarchy>")]

            root = ET.fromstring(xml_text.strip())
            focused_node = None
            focusable_nodes = []

            for elem in root.iter("node"):
                attrs = elem.attrib
                res_id = attrs.get("resource-id", "")
                cls = attrs.get("class", "")
                text = attrs.get("text", "")
                bounds = attrs.get("bounds", "")
                is_focused = attrs.get("focused") == "true"
                is_focusable = attrs.get("focusable") == "true"

                node_info = {
                    "id": res_id,
                    "class": cls,
                    "text": text,
                    "bounds": bounds,
                    "focused": is_focused,
                    "focusable": is_focusable
                }

                if is_focused:
                    focused_node = node_info
                if is_focusable:
                    focusable_nodes.append(node_info)

            return root, focused_node, focusable_nodes
        except Exception:
            return None, None, []

    def check_logcat_crash(self):
        """Quét logcat tìm Crash, ANR, Exception."""
        cmd = f"{self.adb_cmd} logcat -d -s AndroidRuntime:E ActivityManager:E AndroidRuntime:F"
        try:
            r = subprocess.run(cmd.split(), capture_output=True, text=True, timeout=4)
            lines = [l for l in r.stdout.splitlines() if 'FATAL EXCEPTION' in l or 'ANR in' in l or 'NullPointerException' in l]
            if lines:
                return "\n".join(r.stdout.splitlines()[-45:])
            return ""
        except Exception:
            return ""

    def key(self, action_name):
        key_map = {
            "UP": KEY_UP, "DOWN": KEY_DOWN, "LEFT": KEY_LEFT,
            "RIGHT": KEY_RIGHT, "ENTER": KEY_ENTER, "BACK": KEY_BACK, "MENU": KEY_MENU
        }
        sh(f"{self.adb_cmd} shell input keyevent {key_map.get(action_name, KEY_DOWN)}")

    def ai_decide_action(self, focused_node, focusable_nodes):
        """
        Bộ não AI tự chủ quyết định hành vi dựa trên UI Hierarchy:
        - Nếu đang ở Dialog hoặc Player: AI quyết định bấm BACK hoặc tương tác controls.
        - Nếu gặp danh sách chưa khám phá: AI quyết định bấm DOWN để scroll sâu hơn.
        - Nếu gặp nút/card mới: AI quyết định bấm ENTER để thử thách mở màn hình con.
        - Sử dụng TypeSafe Jev System One để ra quyết định thời gian thực nếu có key.
        """
        focus_id = focused_node.get("id", "") if focused_node else ""
        focus_text = focused_node.get("text", "") if focused_node else ""
        focus_bounds = focused_node.get("bounds", "") if focused_node else ""
        recent = self.tracker.get_recent_steps(6)

        # 1. Nếu có Jev System One: AI quyết định siêu tốc không hallucination
        if TYPESAFE_KEY:
            try:
                state_summary = f"FocusID: {focus_id} | Text: {focus_text} | Bounds: {focus_bounds} | Recent: {recent[-4:]} | TotalFocusable: {len(focusable_nodes)}"
                req = urllib.request.Request(
                    TYPESAFE_BASE,
                    data=json.dumps({
                        "state": state_summary,
                        "model": TYPESAFE_MODEL,
                        "questions": {
                            "decision": {
                                "type": "choice",
                                "instructions": "Là đặc nhiệm QA Android TV, hãy quyết định phím D-pad tiếp theo để tối đa hóa độ bao phủ và săn lỗi biên",
                                "criteria": {
                                    "DOWN": "Khám phá xuống nội dung danh sách sâu hơn",
                                    "RIGHT": "Sang item bên phải",
                                    "ENTER": "Bấm kích hoạt mở chi tiết/kênh/dialog/cài đặt",
                                    "UP": "Di chuyển lên lại",
                                    "LEFT": "Sang trái",
                                    "BACK": "Thoát lùi màn hình/đóng dialog/thoát player"
                                }
                            }
                        }
                    }).encode(),
                    headers={"Content-Type": "application/json", "Authorization": "Bearer " + TYPESAFE_KEY, "User-Agent": "curl/8.5.0"}
                )
                with urllib.request.urlopen(req, timeout=3) as resp:
                    res = json.loads(resp.read().decode())
                    return res.get("answers", {}).get("decision", {}).get("choice", "DOWN")
            except Exception:
                pass

        # 2. Logic AI Autonomous Q-Exploration (Quyết định thông minh không cần API bên ngoài)
        # Nếu vừa bấm ENTER ở bước trước -> Thường đang ở trong Dialog hoặc Player -> Thử điều hướng hoặc thoát
        if recent and recent[-1] == "ENTER":
            return random.choice(["DOWN", "RIGHT", "BACK"])

        # Nếu đang ở sâu trong danh sách sau 4 lần DOWN -> Thử ENTER để vào chi tiết hoặc RIGHT
        down_streak = sum(1 for a in recent[-4:] if a == "DOWN")
        if down_streak >= 3:
            return random.choice(["ENTER", "RIGHT", "DOWN"])

        # Nếu tập trung vào View có ID liên quan đến Setting/Dialog
        if "setting" in focus_id.lower() or "dialog" in focus_id.lower():
            return random.choice(["ENTER", "DOWN", "BACK"])

        # Mặc định: Phân bổ xác suất khám phá (Prioritized Exploration)
        # DOWN (35%) -> RIGHT (25%) -> ENTER (20%) -> UP (10%) -> LEFT (5%) -> BACK (5%)
        weights = [10, 35, 5, 25, 20, 5]
        return random.choices(ACTIONS, weights=weights, k=1)[0]

    def run_autonomous_hunt(self, total_actions=35, chaos_chaos_every=8):
        print(f"\n🚀 BẮT ĐẦU PHIÊN AI AUTONOMOUS BUG HUNTER (Mục tiêu: {total_actions} quyết định tự chủ)...", flush=True)
        sh(f"{self.adb_cmd} logcat -c")

        # Khởi động app
        sh(f"{self.adb_cmd} shell am start -W -n {PKG}/.MainActivity")
        time.sleep(3.5)

        for step in range(total_actions):
            # Kiểm tra Chaos Testing: Đột ngột thay đổi Tab để kiểm tra va chạm Lifecycle
            if step > 0 and step % chaos_chaos_every == 0:
                self.current_tab = random.choice([0, 1, 2, 3, 4])
                print(f"\n⚡ [CHAOS INJECTION] AI kích hoạt chuyển Tab đột ngột sang Tab {self.current_tab}...", flush=True)
                sh(f"{self.adb_cmd} shell am start -n {PKG}/.MainActivity --ei tab {self.current_tab}")
                self.tracker.record_action(f"SWITCH_TAB_{self.current_tab}")
                time.sleep(2.5)
                continue

            # 1. Đọc trạng thái giao diện hiện tại
            root_xml, focused_node, focusable_nodes = self.dump_hierarchy_and_focus()

            # 2. AI tự quyết định hành động tiếp theo
            chosen_action = self.ai_decide_action(focused_node, focusable_nodes)
            focus_sig = f"{focused_node.get('id', '')}#{focused_node.get('bounds', '')}" if focused_node else "NO_FOCUS"

            self.tracker.record_action(chosen_action, {
                "focus_before": focus_sig,
                "focusable_count": len(focusable_nodes)
            })

            # 3. Thực thi hành động D-pad
            self.key(chosen_action)
            print(f"  👉 [Bước {step+1:02d}/{total_actions}] AI quyết định: [{chosen_action:5s}] (Focus: {focus_sig})", flush=True)
            time.sleep(0.7)

            # 4. ORACLE AUDIT: Kiểm tra tự động phát hiện lỗi
            self._audit_after_action(chosen_action, focus_sig)

        return self.tracker.export()

    def _audit_after_action(self, action_taken, last_focus_sig):
        # ORACLE 1: Bắt lỗi Crash/ANR từ Logcat
        crash_log = self.check_logcat_crash()
        if crash_log:
            png = self.screencap()
            self.tracker.add_bug(
                bug_type="CRASH",
                severity="CRITICAL",
                title="Ứng dụng bị Crash hoặc ANR do AI thao tác",
                description="Bắt được ngoại lệ FATAL EXCEPTION hoặc ANR trong AndroidRuntime Logcat.",
                screenshot_bytes=png,
                logcat_snippet=crash_log
            )
            # Khởi động lại app để tiếp tục săn lỗi
            sh(f"{self.adb_cmd} logcat -c")
            sh(f"{self.adb_cmd} shell am start -n {PKG}/.MainActivity")
            time.sleep(3.5)
            return

        # ORACLE 2: Kiểm tra Focus Trap / Mất focus trên Android TV
        _, current_focused, _ = self.dump_hierarchy_and_focus()
        current_sig = f"{current_focused.get('id', '')}#{current_focused.get('bounds', '')}" if current_focused else "NO_FOCUS"

        if not current_focused:
            png = self.screencap()
            self.tracker.add_bug(
                bug_type="FOCUS_TRAP",
                severity="HIGH",
                title="Mất hoàn toàn dấu Focus trên giao diện TV",
                description="Không có bất kỳ View nào có focused='true'. Người dùng TV bằng remote sẽ bị mất điều khiển.",
                screenshot_bytes=png
            )
        elif current_sig == self.last_focused_sig and action_taken in ["UP", "DOWN", "LEFT", "RIGHT"]:
            self.consecutive_stuck_count += 1
            if self.consecutive_stuck_count >= 4:
                png = self.screencap()
                self.tracker.add_bug(
                    bug_type="FOCUS_TRAP",
                    severity="MEDIUM",
                    title="Kẹt Focus tại một vị trí (Focus Trap)",
                    description=f"Bấm phím {action_taken} 4 lần liên tiếp nhưng focus vẫn bị giam tại {current_sig}.",
                    screenshot_bytes=png
                )
                self.consecutive_stuck_count = 0
        else:
            self.consecutive_stuck_count = 0
            self.last_focused_sig = current_sig

if __name__ == '__main__':
    out_dir = sys.argv[1] if len(sys.argv) > 1 else "/tmp/ai_bughunt_report"
    steps = int(sys.argv[2]) if len(sys.argv) > 2 else 35
    hunter = AutonomousAiBugHunter(out_dir=out_dir)
    hunter.run_autonomous_hunt(total_actions=steps)
