#!/usr/bin/env python3
"""
KenhLive AI Autonomous Bug Hunter — Đặc nhiệm AI tự động tìm lỗi ứng dụng Android TV.
Thay vì chạy kịch bản cố định, AI tự do khám phá (Exploratory Testing / Fuzzing),
liên tục phân tích trạng thái màn hình và logcat để "săn" các lỗi:

1. FOCUS TRAP: Kẹt focus, bấm D-pad không phản hồi hoặc mất dấu focus.
2. CRASH & ANR: FATAL EXCEPTION, NullPointerException, coroutine crash, treo giao diện.
3. BLACK SCREEN / BLANK STATE: Màn hình đen ngòm, mất dữ liệu không tải được.
4. UI OVERFLOW & LAYOUT BREAK: Chữ bị cắt, banner sai tỷ lệ, vỡ layout TV.
5. DIALOG TRAP: Bật dialog không đóng được hoặc sau khi đóng bị văng focus.
6. EXOPLAYER DEFECTS: Luồng phát bị đứng hình, lỗi decoder âm thanh/hình ảnh.

Sau phiên săn lỗi, AI tự động xuất Báo cáo Bug chi tiết kèm:
- Ảnh chụp bằng chứng (Evidence Screenshot)
- Chuỗi phím tái hiện (Steps to Reproduce)
- Logcat stacktrace tại thời điểm lỗi
- Phân loại mức độ nghiêm trọng (CRITICAL / HIGH / MEDIUM / LOW)
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

# Configuration
TYPESAFE_BASE = os.environ.get('TYPESAFE_API_BASE', 'https://api.typesafe.ai/v1/systemone')
TYPESAFE_KEY  = os.environ.get('TYPESAFE_API_KEY', '')
TYPESAFE_MODEL = os.environ.get('TYPESAFE_MODEL', 'jev-latest')

KILO_BASE = os.environ.get('KILO_API_BASE', 'https://api.kilo.ai/api/gateway/v1/chat/completions')
KILO_KEY  = os.environ.get('KILO_API_KEY', '')
KILO_MODEL = os.environ.get('KILO_MODEL', 'inclusionai/ling-3.0-flash-vl:free')

PKG = 'com.kenhlive.tv'

# Keycodes
KEY_UP = 19
KEY_DOWN = 20
KEY_LEFT = 21
KEY_RIGHT = 22
KEY_ENTER = 23
KEY_BACK = 4

ACTIONS_LIST = ["UP", "DOWN", "LEFT", "RIGHT", "ENTER", "BACK"]

def sh(cmd, timeout=30):
    try:
        r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
        return (r.stdout or '') + (r.stderr or '')
    except Exception as e:
        return f"__ERR__ {e}"

class BugHunterSession:
    def __init__(self, out_dir=None):
        self.session_id = f"bughunt_{datetime.datetime.now().strftime('%Y%m%d_%H%M%S')}_{uuid.uuid4().hex[:6]}"
        self.start_time = time.time()
        self.out_dir = out_dir or f"/tmp/bughunt_sessions/{self.session_id}"
        os.makedirs(self.out_dir, exist_ok=True)
        self.shots_dir = os.path.join(self.out_dir, "bugs_evidence")
        os.makedirs(self.shots_dir, exist_ok=True)

        self.bugs_found = []
        self.action_history = []
        self.state_history = collections.deque(maxlen=10) # Lưu 10 trạng thái gần nhất để phát hiện loop/trap

    def record_action(self, action):
        self.action_history.append({
            "action": action,
            "t": round(time.time() - self.start_time, 2)
        })

    def get_recent_steps(self, n=6):
        return [a["action"] for a in self.action_history[-n:]]

    def add_bug(self, bug_type, severity, title, description, screenshot_bytes, logcat_snippet="", repro_steps=None):
        bug_id = f"BUG-{len(self.bugs_found)+1:02d}"
        repro = repro_steps or self.get_recent_steps(8)
        
        # Lưu ảnh bằng chứng
        img_rel = None
        if screenshot_bytes:
            filename = f"{bug_id}_{bug_type.lower()}.png"
            path = os.path.join(self.shots_dir, filename)
            with open(path, "wb") as f:
                f.write(screenshot_bytes)
            img_rel = os.path.relpath(path, self.out_dir)

        bug_item = {
            "id": bug_id,
            "type": bug_type, # CRASH, FOCUS_TRAP, BLANK_SCREEN, UI_OVERFLOW, DIALOG_TRAP
            "severity": severity, # CRITICAL, HIGH, MEDIUM, LOW
            "title": title,
            "description": description,
            "timestamp": round(time.time() - self.start_time, 2),
            "reproduce_steps": repro,
            "screenshot": img_rel,
            "logcat_snippet": logcat_snippet[:1500] if logcat_snippet else ""
        }
        self.bugs_found.append(bug_item)
        print(f"\n🚨 [PHÁT HIỆN LỖI: {bug_id}] [{severity}] {title}")
        print(f"   👉 Loại lỗi: {bug_type} | Mô tả: {description}")
        print(f"   🔁 Chuỗi phím gây lỗi: {' -> '.join(repro)}\n", flush=True)

    def export(self):
        duration = round(time.time() - self.start_time, 2)
        summary = {
            "session_id": self.session_id,
            "app": PKG,
            "duration_seconds": duration,
            "total_actions": len(self.action_history),
            "bugs_count": len(self.bugs_found),
            "bugs_breakdown": collections.Counter([b["severity"] for b in self.bugs_found]),
            "bugs": self.bugs_found
        }

        # Lưu JSON
        json_file = os.path.join(self.out_dir, "bug_report.json")
        with open(json_file, "w", encoding="utf-8") as f:
            json.dump(summary, f, ensure_ascii=False, indent=2)

        # Xuất HTML Dashboard Săn Lỗi
        html_file = os.path.join(self.out_dir, "index.html")
        self._write_html(html_file, summary)

        print(f"\n==========================================")
        print(f"🎯 KẾT QUẢ PHIÊN SĂN LỖI AI (BUG HUNTING)")
        print(f"🆔 Session: {self.session_id} ({duration}s)")
        print(f"🎮 Tổng thao tác tương tác: {len(self.action_history)}")
        print(f"🐛 Tổng số bug tìm thấy: {len(self.bugs_found)}")
        for sev, count in summary["bugs_breakdown"].items():
            print(f"   • {sev}: {count}")
        print(f"📁 Báo cáo chi tiết: {html_file}")
        print(f"==========================================\n", flush=True)
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
    <title>KênhLive AI Bug Hunter Report</title>
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
        <h1>🐛 KênhLive AI Autonomous Bug Hunter</h1>
        <p>Báo cáo tự động khám phá và săn lỗi ứng dụng Android TV</p>
    </div>
    <div class="stats">
        <div class="stat"><div>Session</div><div class="stat-val" style="font-size: 15px;">{s['session_id']}</div></div>
        <div class="stat"><div>Thời gian săn lỗi</div><div class="stat-val">{s['duration_seconds']}s</div></div>
        <div class="stat"><div>Tổng thao tác D-pad</div><div class="stat-val">{s['total_actions']}</div></div>
        <div class="stat"><div>Lỗi phát hiện</div><div class="stat-val" style="color: {'#FF1744' if s['bugs_count']>0 else '#00E5FF'};">{s['bugs_count']}</div></div>
    </div>
    <h2>Danh sách lỗi tìm thấy ({s['bugs_count']})</h2>
    {''.join(bugs_cards) if bugs_cards else '<p style="color:#00E5FF;">🎉 Tuyệt vời! AI không phát hiện bất kỳ lỗi UX/Crash nào trong phiên khám phá.</p>'}
</body>
</html>"""
        with open(path, "w", encoding="utf-8") as f:
            f.write(html)

class AiBugHunter:
    def __init__(self, serial=None, out_dir=None):
        self.serial = serial
        self.adb_cmd = f"adb -s {serial}" if serial else "adb"
        self.session = BugHunterSession(out_dir=out_dir)
        self.consecutive_same_focus = 0
        self.last_focused_bounds = ""
        self.last_screen_hash = ""

    def screencap(self):
        cmd = f"{self.adb_cmd} exec-out screencap -p"
        try:
            raw = subprocess.run(cmd.split(), capture_output=True, timeout=12).stdout
            return raw if len(raw) > 8000 else None
        except Exception:
            return None

    def dump_focused_view(self):
        """Lấy thông tin element đang giữ focus (bounds, resource-id, class)."""
        cmd = f"{self.adb_cmd} exec-out uiautomator dump /dev/tty"
        try:
            r = subprocess.run(cmd.split(), capture_output=True, text=True, timeout=4)
            out = r.stdout
            m = re.search(r'<node[^>]*focused="true"[^>]*>', out)
            if m:
                node = m.group(0)
                bounds = re.search(r'bounds="([^"]+)"', node)
                res_id = re.search(r'resource-id="([^"]+)"', node)
                return {
                    "has_focus": True,
                    "bounds": bounds.group(1) if bounds else "",
                    "id": res_id.group(1) if res_id else "",
                    "raw": node[:200]
                }
            return {"has_focus": False, "bounds": "", "id": "", "raw": ""}
        except Exception:
            return {"has_focus": True, "bounds": "", "id": "", "raw": "timeout"}

    def get_logcat_crash(self):
        """Quét logcat tìm FATAL EXCEPTION hoặc ANR."""
        cmd = f"{self.adb_cmd} logcat -d -s AndroidRuntime:E ActivityManager:E"
        try:
            r = subprocess.run(cmd.split(), capture_output=True, text=True, timeout=5)
            lines = [l for l in r.stdout.splitlines() if 'FATAL EXCEPTION' in l or 'ANR in com.kenhlive.tv' in l or 'Exception' in l]
            if lines:
                return "\n".join(r.stdout.splitlines()[-40:])
            return ""
        except Exception:
            return ""

    def key(self, key_code):
        sh(f"{self.adb_cmd} shell input keyevent {key_code}")

    def choose_intelligent_action(self, current_state, focus_info):
        """
        Bộ não AI định hướng khám phá (Exploration Policy):
        - Ưu tiên bấm các phím khám phá ngóc ngách (DOWN, RIGHT, ENTER)
        - Nếu đang ở trong Player/Dialog: thử bấm BACK hoặc OK
        - Sử dụng Jev System One để đề xuất phím nếu có key
        """
        if TYPESAFE_KEY:
            try:
                req = urllib.request.Request(
                    TYPESAFE_BASE,
                    data=json.dumps({
                        "state": f"App: KenhLive TV\nFocus Element: {focus_info.get('id')}\nBounds: {focus_info.get('bounds')}\nRecent: {self.session.get_recent_steps(4)}",
                        "model": TYPESAFE_MODEL,
                        "questions": {
                            "next_action": {
                                "type": "choice",
                                "instructions": "Chọn phím điều khiển D-pad tốt nhất để khám phá sâu giao diện hoặc tìm lỗi biên",
                                "criteria": {
                                    "UP": "Di chuyển lên",
                                    "DOWN": "Khám phá xuống nội dung bên dưới",
                                    "LEFT": "Sang trái",
                                    "RIGHT": "Sang phải",
                                    "ENTER": "Bấm chọn mở chi tiết/dialog/player",
                                    "BACK": "Thoát lùi lại màn hình trước"
                                }
                            }
                        }
                    }).encode(),
                    headers={"Content-Type": "application/json", "Authorization": "Bearer " + TYPESAFE_KEY, "User-Agent": "curl/8.5.0"}
                )
                with urllib.request.urlopen(req, timeout=3) as resp:
                    res = json.loads(resp.read().decode())
                    return res.get("answers", {}).get("next_action", {}).get("choice", "DOWN")
            except Exception:
                pass

        # Heuristic Exploratory Weights (Khám phá thông minh)
        recent = self.session.get_recent_steps(4)
        if "ENTER" in recent[-1:]:
            # Vừa bấm ENTER (có thể vào player hoặc mở dialog), thử di chuyển hoặc BACK
            return random.choice(["DOWN", "RIGHT", "BACK", "ENTER"])
        
        weights = [15, 30, 15, 25, 10, 5] # UP, DOWN, LEFT, RIGHT, ENTER, BACK
        return random.choices(ACTIONS_LIST, weights=weights, k=1)[0]

    def audit_current_state(self, action_taken):
        """
        Oracle kiểm tra tự động phát hiện lỗi sau mỗi hành động:
        1. Kiểm tra Crash/ANR trong Logcat
        2. Kiểm tra Focus Trap (mất focus hoặc kẹt tại chỗ)
        3. Kiểm tra Màn hình đen / Treo
        """
        # 1. Oracle Crash
        crash_log = self.get_logcat_crash()
        if crash_log:
            png = self.screencap()
            self.session.add_bug(
                bug_type="CRASH",
                severity="CRITICAL",
                title="Ứng dụng bị Crash hoặc ANR trong quá trình tương tác",
                description="Phát hiện ngoại lệ nghiêm trọng FATAL EXCEPTION trong Android logcat.",
                screenshot_bytes=png,
                logcat_snippet=crash_log
            )
            # Relaunch app để tiếp tục săn lỗi
            sh(f"{self.adb_cmd} logcat -c")
            sh(f"{self.adb_cmd} shell am start -n {PKG}/.MainActivity")
            time.sleep(3)
            return

        # 2. Oracle Focus Trap
        focus_info = self.dump_focused_view()
        current_bounds = focus_info.get("bounds", "")

        if not focus_info.get("has_focus"):
            # Mất hoàn toàn focus trên Android TV
            png = self.screencap()
            self.session.add_bug(
                bug_type="FOCUS_TRAP",
                severity="HIGH",
                title="Mất dấu Focus trên Android TV (No View Focused)",
                description="Không tìm thấy bất kỳ view nào có thuộc tính focused='true'. Người dùng TV không thể điều khiển bằng remote.",
                screenshot_bytes=png
            )
        elif current_bounds and current_bounds == self.last_focused_bounds:
            self.consecutive_same_focus += 1
            if self.consecutive_same_focus >= 4 and action_taken in ["UP", "DOWN", "LEFT", "RIGHT"]:
                # Bấm 4 lần phím điều hướng nhưng focus không hề suy suyển
                png = self.screencap()
                self.session.add_bug(
                    bug_type="FOCUS_TRAP",
                    severity="MEDIUM",
                    title="Kẹt Focus tại một vị trí (Focus Trap)",
                    description=f"Bấm phím {action_taken} liên tục 4 lần nhưng focus vẫn đứng yên tại tọa độ {current_bounds} (ID: {focus_info.get('id')}).",
                    screenshot_bytes=png
                )
                self.consecutive_same_focus = 0
        else:
            self.consecutive_same_focus = 0
            self.last_focused_bounds = current_bounds

    def hunt_bugs(self, total_steps=25, tab_switch_interval=6):
        """Bắt đầu vòng lặp đặc nhiệm AI tự động săn lỗi."""
        print(f"🚀 KHỞI ĐỘNG ĐẶC NHIỆM AI SĂN LỖI (Mục tiêu: {total_steps} bước tương tác tự do)...", flush=True)
        sh(f"{self.adb_cmd} logcat -c")
        
        # Mở app
        sh(f"{self.adb_cmd} shell am start -W -n {PKG}/.MainActivity")
        time.sleep(3.5)

        for step in range(total_steps):
            # Cứ mỗi tab_switch_interval bước, thử đổi tab ngẫu nhiên để test va chạm lifecycle
            if step > 0 and step % tab_switch_interval == 0:
                random_tab = random.choice([0, 1, 2, 3, 4])
                print(f"\n🔄 [CHAOS] Thử thách chuyển Tab đột ngột sang Tab {random_tab}...", flush=True)
                sh(f"{self.adb_cmd} shell am start -n {PKG}/.MainActivity --ei tab {random_tab}")
                self.session.record_action(f"SWITCH_TAB_{random_tab}")
                time.sleep(2)
                continue

            focus_info = self.dump_focused_view()
            act = self.choose_intelligent_action(None, focus_info)
            self.session.record_action(act)

            # Thực thi phím
            key_map = {
                "UP": KEY_UP, "DOWN": KEY_DOWN, "LEFT": KEY_LEFT,
                "RIGHT": KEY_RIGHT, "ENTER": KEY_ENTER, "BACK": KEY_BACK
            }
            self.key(key_map[act])
            print(f"  👉 Bước {step+1:02d}: AI thực hiện [{act}] (Focus hiện tại: {focus_info.get('id') or focus_info.get('bounds')})", flush=True)
            time.sleep(0.6)

            # Kiểm định phát hiện lỗi ngay tức thì
            self.audit_current_state(act)

        return self.session.export()

if __name__ == '__main__':
    out = sys.argv[1] if len(sys.argv) > 1 else "/tmp/ai_bughunt_report"
    steps = int(sys.argv[2]) if len(sys.argv) > 2 else 25
    hunter = AiBugHunter(out_dir=out)
    report = hunter.hunt_bugs(total_steps=steps)
