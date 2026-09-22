#!/usr/bin/env python3
"""
KenhLive Mobile Remote & AI Controller Server.
Cung cấp API điều khiển Android TV từ xa và quản lý AI Bug Hunter qua điện thoại.
"""
import http.server
import json
import os
import subprocess
import threading
import time

PORT = 8765
PKG = 'com.kenhlive.tv'
DIR = os.path.dirname(os.path.abspath(__file__))
HUNT_STATE = {
    "running": False,
    "step": 0,
    "total_steps": 20,
    "bugs_found": 0
}

def sh(cmd):
    try:
        r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=10)
        return (r.stdout or '') + (r.stderr or '')
    except Exception as e:
        return str(e)

class MobileHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        pass

    def do_GET(self):
        if self.path == '/' or self.path.startswith('/?'):
            self.send_response(200)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.end_headers()
            with open(os.path.join(DIR, 'index.html'), 'rb') as f:
                self.wfile.write(f.read())
            return

        elif self.path.startswith('/api/screen'):
            # Lấy screenshot từ adb hoặc ảnh cache mới nhất
            raw = None
            try:
                r = subprocess.run(['adb', 'exec-out', 'screencap', '-p'], capture_output=True, timeout=5)
                if len(r.stdout) > 8000:
                    raw = r.stdout
            except Exception:
                pass

            if not raw:
                # Fallback lấy ảnh cache test gần nhất
                fallback_path = '/root/.hermes/cache/images/tab0_home.png'
                if os.path.exists(fallback_path):
                    with open(fallback_path, 'rb') as f:
                        raw = f.read()

            if raw:
                self.send_response(200)
                self.send_header('Content-Type', 'image/png')
                self.send_header('Cache-Control', 'no-cache, no-store')
                self.end_headers()
                self.wfile.write(raw)
            else:
                self.send_response(404)
                self.end_headers()
            return

        elif self.path == '/api/hunt_status':
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps(HUNT_STATE).encode())
            return

        elif self.path == '/api/bugs':
            # Đọc danh sách bug từ /tmp/ai_bughunt_report/bug_report.json
            report_file = '/tmp/ai_bughunt_report/bug_report.json'
            bugs = []
            if os.path.exists(report_file):
                try:
                    with open(report_file, 'r', encoding='utf-8') as f:
                        data = json.load(f)
                        bugs = data.get('bugs', [])
                except Exception:
                    pass
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps(bugs, ensure_ascii=False).encode())
            return

        self.send_response(404)
        self.end_headers()

    def do_POST(self):
        length = int(self.headers.get('Content-Length', 0))
        body = json.loads(self.rfile.read(length).decode()) if length else {}

        if self.path == '/api/key':
            act = body.get('action', '').upper()
            key_codes = {
                'UP': 19, 'DOWN': 20, 'LEFT': 21, 'RIGHT': 22,
                'ENTER': 23, 'BACK': 4, 'HOME': 3
            }
            if act in key_codes:
                sh(f"adb shell input keyevent {key_codes[act]}")
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(b'{"ok": true}')
            return

        elif self.path == '/api/tab':
            tab_idx = body.get('tab', 0)
            sh(f"adb shell am start -n {PKG}/.MainActivity --ei tab {tab_idx}")
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(b'{"ok": true}')
            return

        elif self.path == '/api/hunt':
            if not HUNT_STATE['running']:
                threading.Thread(target=self._run_hunt_thread, daemon=True).start()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(b'{"status": "started"}')
            return

        self.send_response(404)
        self.end_headers()

    def _run_hunt_thread(self):
        HUNT_STATE['running'] = True
        HUNT_STATE['step'] = 0
        HUNT_STATE['total_steps'] = 20
        HUNT_STATE['bugs_found'] = 0

        try:
            # Chạy ai_bug_hunter.py
            cmd = f"python3 /root/SocoliveTV/qa/ai_bug_hunter.py /tmp/ai_bughunt_report 20"
            p = subprocess.Popen(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            if p.stdout:
                for line in p.stdout:
                    if 'Bước' in line:
                        HUNT_STATE['step'] += 1
                    if 'PHÁT HIỆN LỖI' in line:
                        HUNT_STATE['bugs_found'] += 1
            p.wait()
        except Exception:
            pass
        finally:
            HUNT_STATE['running'] = False

def run_server():
    server = http.server.ThreadingHTTPServer(('0.0.0.0', PORT), MobileHandler)
    print(f"🚀 Mobile Remote Server listening on http://0.0.0.0:{PORT}")
    server.serve_forever()

if __name__ == '__main__':
    run_server()
