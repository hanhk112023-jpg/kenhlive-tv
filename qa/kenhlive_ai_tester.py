#!/usr/bin/env python3
"""
KenhLive Autonomous AI QA Studio & Inspection Suite
Hệ thống kiểm thử tự trị toàn diện dành cho AI trên Android TV & Mobile.
Cung cấp 18 công cụ chuyên sâu: Thị giác (Vision), DOM / Cây giao diện (Accessibility),
Điều khiển (Remote D-pad / Input), Đo lường (Telemetry / Jank / RAM), và Quản lý Bug.
"""

import argparse
import base64
import datetime
import io
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
import uuid

try:
    from PIL import Image, ImageStat
except ImportError:
    Image = None

PKG = 'com.kenhlive.tv'

# Tọa độ phím Android TV
KEY_CODES = {
    'UP': 19,
    'DOWN': 20,
    'LEFT': 21,
    'RIGHT': 22,
    'OK': 23,
    'ENTER': 23,
    'BACK': 4,
    'HOME': 3,
    'MENU': 82,
    'INFO': 165,
    'PLAY_PAUSE': 85,
    'VOLUME_UP': 24,
    'VOLUME_DOWN': 25,
    'MUTE': 164
}

def sh(cmd, timeout=30):
    """Chạy lệnh shell trả về chuỗi stdout + stderr."""
    try:
        r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
        return (r.stdout or '') + (r.stderr or '')
    except Exception as e:
        return f"__ERR__ {e}"

# ==============================================================================
# BỘ CÔNG CỤ HOÀN CHỈNH CHO AI (18 TOOLS)
# ==============================================================================
class AIToolkit:
    def __init__(self, pkg=PKG, serial=None, out_dir=None):
        self.pkg = pkg
        self.serial = serial
        self.adb = f"adb {f'-s {serial}' if serial else ''}"
        self.out_dir = out_dir or f"/tmp/ai_qa_studio_{int(time.time())}"
        os.makedirs(self.out_dir, exist_ok=True)
        self.shots_dir = os.path.join(self.out_dir, "shots")
        os.makedirs(self.shots_dir, exist_ok=True)
        self.bugs = []
        self.checks = []
        self.history = []
        self.step_idx = 0

    # --- NHÓM 1: THỊ GIÁC & HÌNH ẢNH (VISION TOOLS) ---
    def tool_screencap(self, label="screenshot"):
        """Chụp toàn màn hình full-res PNG, lưu file và trả metadata + bytes."""
        self.step_idx += 1
        safe_name = re.sub(r'[^a-zA-Z0-9_.-]', '_', label.lower())
        filename = f"{self.step_idx:02d}_{safe_name}.png"
        path = os.path.join(self.shots_dir, filename)
        
        # Chụp trực tiếp từ adb
        cmd = f"{self.adb} exec-out screencap -p"
        try:
            r = subprocess.run(cmd.split(), capture_output=True, timeout=15)
            png_bytes = r.stdout
            if len(png_bytes) < 1000:
                return {"error": "Screencap rỗng hoặc thiết bị chưa sẵn sàng", "bytes": None}
            with open(path, "wb") as f:
                f.write(png_bytes)
            
            res_meta = {"width": 1920, "height": 1080}
            if Image:
                im = Image.open(io.BytesIO(png_bytes))
                res_meta = {"width": im.width, "height": im.height}

            self.history.append({"action": "screencap", "label": label, "file": filename})
            return {
                "success": True,
                "label": label,
                "file_path": path,
                "relative_path": f"shots/{filename}",
                "resolution": res_meta,
                "size_bytes": len(png_bytes),
                "bytes": png_bytes
            }
        except Exception as e:
            return {"error": str(e), "bytes": None}

    def tool_zoom_crop(self, image_path, x, y, w, h, scale=3):
        """Crop một vùng chữ nhật [x, y, w, h] và phóng đại Nx để soi viền / text nhỏ."""
        if not Image:
            return {"error": "Thư viện PIL chưa được cài đặt"}
        try:
            im = Image.open(image_path).convert('RGB')
            W, H = im.size
            cx = max(0, min(int(x), W - 2))
            cy = max(0, min(int(y), H - 2))
            cw = max(4, min(int(w), W - cx))
            ch = max(4, min(int(h), H - cy))
            scale = max(1, min(int(scale), 8))
            
            cropped = im.crop((cx, cy, cx + cw, cy + ch))
            resized = cropped.resize((cw * scale, ch * scale), Image.LANCZOS)
            
            out_name = f"zoom_x{cx}_y{cy}_{cw}x{ch}.png"
            out_path = os.path.join(self.shots_dir, out_name)
            resized.save(out_path)
            return {
                "success": True,
                "zoom_file": out_path,
                "relative_path": f"shots/{out_name}",
                "original_box": [cx, cy, cw, ch],
                "scale": scale,
                "new_size": [cw * scale, ch * scale]
            }
        except Exception as e:
            return {"error": str(e)}

    def tool_get_pixel(self, image_path, x, y):
        """Lấy giá trị màu RGB và mã HEX của pixel tại toạ độ (x, y)."""
        if not Image:
            return {"error": "PIL missing"}
        try:
            im = Image.open(image_path).convert('RGB')
            if not (0 <= x < im.width and 0 <= y < im.height):
                return {"error": f"Tọa độ ({x}, {y}) nằm ngoài kích thước {im.size}"}
            rgb = im.load()[x, y]
            hex_color = f"#{rgb[0]:02X}{rgb[1]:02X}{rgb[2]:02X}"
            return {"x": x, "y": y, "rgb": rgb, "hex": hex_color}
        except Exception as e:
            return {"error": str(e)}

    def tool_measure_border(self, image_path, x1, y1, x2, y2, target_color="cyan"):
        """Đo độ dày px của viền focus hoặc đường kẻ cắt ngang đoạn thẳng."""
        if not Image:
            return {"error": "PIL missing"}
        try:
            im = Image.open(image_path).convert('RGB')
            px = im.load()
            color_map = {
                'cyan': (0, 229, 255),
                'red': (255, 59, 48),
                'white': (255, 255, 255),
                'black': (0, 0, 0)
            }
            target_rgb = color_map.get(target_color.lower(), (0, 229, 255))
            
            # Quét pixel dọc theo đoạn nối
            matched = 0
            steps = max(abs(x2 - x1), abs(y2 - y1), 1)
            for s in range(steps + 1):
                cur_x = int(x1 + (x2 - x1) * (s / steps))
                cur_y = int(y1 + (y2 - y1) * (s / steps))
                if 0 <= cur_x < im.width and 0 <= cur_y < im.height:
                    p = px[cur_x, cur_y]
                    # Khoảng cách màu euclidean
                    dist = ((p[0]-target_rgb[0])**2 + (p[1]-target_rgb[1])**2 + (p[2]-target_rgb[2])**2)**0.5
                    if dist < 80:
                        matched += 1
            return {"success": True, "measured_thickness_px": matched, "target": target_color}
        except Exception as e:
            return {"error": str(e)}

    # --- NHÓM 2: CẤU TRÚC GIAO DIỆN & ACCESSIBILITY (DOM TOOLS) ---
    def tool_dump_ui(self):
        """Lấy toàn bộ cây uiautomator XML, bóc tách các node quan trọng (focus, text, id, bounds)."""
        sh(f"{self.adb} shell uiautomator dump /sdcard/ui_dump.xml >/dev/null 2>&1")
        xml_content = sh(f"{self.adb} shell cat /sdcard/ui_dump.xml", timeout=15)
        
        nodes = []
        focused_node = None
        # Parse regex các node
        pattern = re.compile(r'<node[^>]*text="([^"]*)"[^>]*resource-id="([^"]*)"[^>]*focused="([^"]*)"[^>]*bounds="([^"]*)"', re.DOTALL)
        for m in pattern.finditer(xml_content):
            item = {
                "text": m.group(1),
                "id": m.group(2),
                "focused": m.group(3) == "true",
                "bounds": m.group(4)
            }
            nodes.append(item)
            if item["focused"]:
                focused_node = item

        return {
            "total_nodes": len(nodes),
            "focused_node": focused_node,
            "sample_nodes": nodes[:20],
            "has_iptv_grid": 'iptvGrid' in xml_content,
            "has_player": 'PlayerActivity' in self.tool_current_activity(),
            "raw_xml_length": len(xml_content)
        }

    def tool_find_element(self, text_or_id):
        """Tìm nhanh vị trí và trạng thái của một phần tử theo từ khóa text hoặc resource-id."""
        ui = self.tool_dump_ui()
        matches = []
        for n in ui.get("sample_nodes", []):
            if text_or_id.lower() in n["text"].lower() or text_or_id.lower() in n["id"].lower():
                matches.append(n)
        return {"query": text_or_id, "found": len(matches), "elements": matches}

    # --- NHÓM 3: ĐIỀU KHIỂN REMOTE & NHẬP LIỆU (ACTUATORS) ---
    def tool_press_key(self, key_name, delay=0.5):
        """Bấm một phím điều khiển từ xa Android TV (UP, DOWN, LEFT, RIGHT, OK, BACK, HOME, v.v.)."""
        k = KEY_CODES.get(key_name.upper())
        if not k:
            if key_name.isdigit():
                k = int(key_name)
            else:
                return {"error": f"Phím không hợp lệ: {key_name}"}
        sh(f"{self.adb} shell input keyevent {k}")
        time.sleep(delay)
        self.history.append({"action": "press_key", "key": key_name.upper()})
        return {"success": True, "key": key_name.upper(), "keycode": k}

    def tool_press_sequence(self, sequence, interval=0.4):
        """Gửi một chuỗi thao tác phím remote liên tục có độ trễ giữa các phím."""
        results = []
        for key in sequence:
            r = self.tool_press_key(key, delay=interval)
            results.append(r)
        return {"success": True, "count": len(sequence), "details": results}

    def tool_type_text(self, text):
        """Gõ chuỗi văn bản vào ô nhập liệu đang focus (hỗ trợ tìm kiếm)."""
        safe_text = text.replace(" ", "%s")
        sh(f"{self.adb} shell input text '{safe_text}'")
        time.sleep(1.0)
        self.history.append({"action": "type_text", "text": text})
        return {"success": True, "typed": text}

    def tool_open_screen(self, target, extra_arg=None):
        """Deep-link mở thẳng màn hình mong muốn (Tab 0..4, MultiView, Player, PIP)."""
        target = str(target).lower()
        if target.startswith("tab"):
            tab_num = target.replace("tab", "").strip() or "0"
            cmd = f"{self.adb} shell am start -n {self.pkg}/.MainActivity --ei tab {tab_num}"
        elif target in ["player", "stream"]:
            cmd = f"{self.adb} shell am start -n {self.pkg}/.MainActivity --es open player"
        elif target in ["multiview", "mv"]:
            layout = extra_arg if extra_arg in ["0", "1"] else "0"
            cmd = f"{self.adb} shell am start -n {self.pkg}/.MultiViewActivity --ei mv_layout {layout}"
        elif target == "pip":
            cmd = f"{self.adb} shell am start -n {self.pkg}/.MainActivity --es open pip"
        else:
            return {"error": f"Mục tiêu không hỗ trợ: {target}"}

        sh(cmd)
        time.sleep(3.0)
        act = self.tool_current_activity()
        return {"success": True, "target": target, "current_activity": act}

    # --- NHÓM 4: ĐO LƯỜNG HỆ THỐNG & CHẨN ĐOÁN (TELEMETRY) ---
    def tool_current_activity(self):
        """Lấy tên Activity đang hiển thị trên cùng."""
        res = sh(f"{self.adb} shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")
        match = re.search(r'([a-zA-Z0-9_.]+/.[a-zA-Z0-9_.]+)', res)
        return match.group(1) if match else res.strip()

    def tool_check_crashes(self):
        """Quét logcat tìm Crash, Exception, ANR, NPE kể từ lúc khởi động."""
        log = sh(f"{self.adb} shell logcat -d -v brief *:E", timeout=15)
        crashes = []
        for line in log.splitlines():
            if any(term in line for term in ["FATAL EXCEPTION", "NullPointerException", "AndroidRuntime", "ANR in"]):
                if self.pkg in line or "com.kenhlive" in line:
                    crashes.append(line.strip())
        return {
            "has_crash": len(crashes) > 0,
            "crash_count": len(crashes),
            "errors": crashes[:10]
        }

    def tool_measure_perf(self):
        """Đo chỉ số giật lag (Jank %), Frame Drops và Dung lượng RAM chiếm dụng (PSS)."""
        gfx = sh(f"{self.adb} shell dumpsys gfxinfo {self.pkg} | grep 'Janky frames'")
        mem = sh(f"{self.adb} shell dumpsys meminfo {self.pkg} | grep 'TOTAL PSS:'")
        
        jank_pct = 0.0
        match_gfx = re.search(r'Janky frames:\s*\d+\s*\((\d+\.?\d*)%\)', gfx)
        if match_gfx:
            jank_pct = float(match_gfx.group(1))

        pss_mb = 0
        match_mem = re.search(r'TOTAL PSS:\s*(\d+)', mem)
        if match_mem:
            pss_mb = int(match_mem.group(1)) // 1024

        return {
            "jank_percent": jank_pct,
            "memory_pss_mb": pss_mb,
            "is_smooth": jank_pct < 15.0,
            "is_memory_healthy": pss_mb < 350
        }

    def tool_switch_device_profile(self, profile="tv_1080p"):
        """Đổi độ phân giải màn hình kiểm thử (TV 1080p, TV 4K, Phone Portrait, Phone Landscape)."""
        if profile == "tv_1080p":
            sh(f"{self.adb} shell wm size 1920x1080 && {self.adb} shell wm density 320")
        elif profile == "tv_4k":
            sh(f"{self.adb} shell wm size 3840x2160 && {self.adb} shell wm density 640")
        elif profile == "phone_portrait":
            sh(f"{self.adb} shell wm size 1080x2400 && {self.adb} shell wm density 420")
        elif profile == "phone_landscape":
            sh(f"{self.adb} shell wm size 2400x1080 && {self.adb} shell wm density 420")
        elif profile == "reset":
            sh(f"{self.adb} shell wm size reset && {self.adb} shell wm density reset")
        time.sleep(1.5)
        return {"success": True, "profile": profile}

    def tool_chaos_stress(self, count=40):
        """Stress test ngẫu nhiên (Monkey/Chaos D-pad) để kiểm tra độ ổn định."""
        import random
        keys = ['UP', 'DOWN', 'LEFT', 'RIGHT', 'OK', 'BACK']
        actions = [random.choice(keys) for _ in range(count)]
        for k in actions:
            self.tool_press_key(k, delay=0.15)
        time.sleep(1.0)
        crashes = self.tool_check_crashes()
        return {
            "success": True,
            "total_keys_sent": count,
            "crashed": crashes["has_crash"],
            "details": crashes["errors"]
        }

    # --- NHÓM 5: QUẢN LÝ BUG & XUẤT BÁO CÁO (REPORTING) ---
    def tool_record_bug(self, severity, area, title, evidence, suggestion=""):
        """Ghi nhận một lỗi kiểm thử chính thức kèm bằng chứng."""
        bug = {
            "id": f"BUG-{len(self.bugs) + 1:03d}",
            "severity": severity.upper(), # CRITICAL, HIGH, MEDIUM, LOW
            "area": area,
            "title": title,
            "evidence": evidence,
            "suggestion": suggestion,
            "timestamp": datetime.datetime.now().strftime("%H:%M:%S")
        }
        self.bugs.append(bug)
        print(f"  🚨 [{bug['severity']}] {area}: {title}", flush=True)
        return {"success": True, "bug_id": bug["id"]}

    def tool_export_dashboard(self):
        """Xuất toàn bộ báo cáo ra file HTML Dashboard & JSON."""
        summary = {
            "report_id": f"ai_qa_{int(time.time())}",
            "timestamp": datetime.datetime.now().isoformat(),
            "package": self.pkg,
            "total_checks": len(self.checks),
            "total_bugs": len(self.bugs),
            "bugs": self.bugs,
            "history": self.history
        }

        json_path = os.path.join(self.out_dir, "qa_report.json")
        with open(json_path, "w", encoding="utf-8") as f:
            json.dump(summary, f, ensure_ascii=False, indent=2)

        # Xuất HTML Dashboard trực quan
        html_path = os.path.join(self.out_dir, "index.html")
        bug_rows = "".join([
            f"""<tr class="sev-{b['severity'].lower()}">
                <td><b>{b['id']}</b></td>
                <td><span class="badge badge-{b['severity'].lower()}">{b['severity']}</span></td>
                <td><b>{b['area']}</b></td>
                <td>{b['title']}</td>
                <td><code>{b['evidence']}</code></td>
                <td>{b['suggestion']}</td>
            </tr>""" for b in self.bugs
        ])

        html_content = f"""<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<title>KenhLive AI QA Studio Dashboard</title>
<style>
body {{ font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #0F172A; color: #F8FAFC; margin: 0; padding: 24px; }}
.header {{ display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid #334155; padding-bottom: 16px; margin-bottom: 24px; }}
.header h1 {{ margin: 0; color: #38BDF8; }}
.card {{ background: #1E293B; border-radius: 8px; padding: 16px; margin-bottom: 20px; border: 1px solid #334155; }}
table {{ width: 100%; border-collapse: collapse; margin-top: 12px; }}
th, td {{ padding: 10px 14px; text-align: left; border-bottom: 1px solid #334155; }}
th {{ background: #0F172A; color: #94A3B8; font-size: 13px; text-transform: uppercase; }}
.badge {{ padding: 4px 8px; border-radius: 4px; font-size: 11px; font-weight: bold; }}
.badge-critical {{ background: #EF4444; color: white; }}
.badge-high {{ background: #F97316; color: white; }}
.badge-medium {{ background: #FBBF24; color: #0F172A; }}
.badge-low {{ background: #3B82F6; color: white; }}
code {{ background: #0F172A; padding: 2px 6px; border-radius: 4px; color: #E2E8F0; font-size: 12px; }}
</style>
</head>
<body>
<div class="header">
    <h1>KenhLive AI QA Studio Dashboard</h1>
    <div>Báo cáo hoàn tất: {datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}</div>
</div>
<div class="card">
    <h2>Tổng quan kiểm thử</h2>
    <p>Tổng số Bug phát hiện: <b>{len(self.bugs)}</b> | Tổng số bước thực hiện: <b>{len(self.history)}</b></p>
</div>
<div class="card">
    <h2>Danh sách Bug ghi nhận</h2>
    <table>
        <thead>
            <tr>
                <th>Mã Bug</th><th>Mức độ</th><th>Phân hệ</th><th>Mô tả</th><th>Bằng chứng</th><th>Khắc phục gợi ý</th>
            </tr>
        </thead>
        <tbody>
            {bug_rows if bug_rows else '<tr><td colspan="6" style="text-align:center; color:#10B981;">🎉 Không phát hiện lỗi nào! Hệ thống hoạt động hoàn hảo.</td></tr>'}
        </tbody>
    </table>
</div>
</body>
</html>"""
        with open(html_path, "w", encoding="utf-8") as f:
            f.write(html_content)

        return {"json_report": json_path, "html_dashboard": html_path}


# ==============================================================================
# HỆ THỐNG KIỂM THỬ TỰ HÀNH TOÀN DIỆN (FULL SUITE EXECUTOR)
# ==============================================================================
def run_autonomous_full_suite(toolkit):
    print("\n🚀 [AI QA STUDIO] BẮT ĐẦU CHUỖI KIỂM THỬ TỰ ĐỘNG TOÀN DIỆN CHO KENHLIVE...", flush=True)

    # 1. Cold Start & Khởi động
    print("\n[Mục 1] Kiểm tra Khởi động lạnh (Cold Start)...", flush=True)
    toolkit.tool_switch_device_profile("tv_1080p")
    sh(f"{toolkit.adb} shell am force-stop {toolkit.pkg}")
    time.sleep(1)
    toolkit.tool_open_screen("tab0")
    t0 = toolkit.tool_screencap("cold_start_home")
    crashes = toolkit.tool_check_crashes()
    if crashes["has_crash"]:
        toolkit.tool_record_bug("CRITICAL", "Khởi động", "Crash khi vừa mở app", str(crashes["errors"]))

    # 2. Tab 0: Home & Live Banner
    print("\n[Mục 2] Kiểm tra Màn hình Trang Chủ (Home Live)...", flush=True)
    ui_home = toolkit.tool_dump_ui()
    if not ui_home.get("has_iptv_grid"):
        print("  ✅ Màn hình Home phân tách chuẩn với IPTV", flush=True)
    
    # 3. Tab 1: Lịch thi đấu
    print("\n[Mục 3] Kiểm tra Tab 1 Lịch thi đấu...", flush=True)
    toolkit.tool_open_screen("tab1")
    toolkit.tool_press_sequence(["DOWN", "DOWN", "UP"])
    toolkit.tool_screencap("tab1_schedule")

    # 4. Tab 2: Lưới Truyền Hình IPTV (5 Cột & Hero Preview & EPG)
    print("\n[Mục 4] Kiểm tra Tab 2 Truyền hình IPTV (Leanback 5 Cột & Hero Preview)...", flush=True)
    toolkit.tool_open_screen("tab2")
    time.sleep(2)
    shot_iptv = toolkit.tool_screencap("tab2_iptv_landing")
    
    # Kiểm tra lưới 5 cột
    ui_iptv = toolkit.tool_dump_ui()
    if not ui_iptv.get("has_iptv_grid"):
        toolkit.tool_record_bug("HIGH", "IPTV", "Không tìm thấy iptvGrid trong cây giao diện Tab 2", "")

    # Di chuyển D-pad qua 5 cột
    toolkit.tool_press_sequence(["DOWN", "RIGHT", "RIGHT", "RIGHT", "RIGHT"])
    shot_focus = toolkit.tool_screencap("tab2_iptv_column5_focused")
    
    # Đo viền focus bằng công cụ đo lường
    if shot_focus.get("file_path"):
        border = toolkit.tool_measure_border(shot_focus["file_path"], 1500, 700, 1550, 700, "cyan")
        print(f"  📏 Độ dày viền focus đo được: {border.get('measured_thickness_px', 0)}px", flush=True)

    # 5. Phát luồng truyền hình trong Player Pro
    print("\n[Mục 5] Mở Player Pro phát kênh truyền hình...", flush=True)
    toolkit.tool_press_key("OK")
    time.sleep(5)
    toolkit.tool_screencap("player_streaming")
    perf = toolkit.tool_measure_perf()
    print(f"  📊 Hiệu năng phát: Jank={perf['jank_percent']}%, RAM={perf['memory_pss_mb']}MB", flush=True)
    if perf["jank_percent"] > 20:
        toolkit.tool_record_bug("MEDIUM", "Player", f"Jank cao khi phát trực tiếp ({perf['jank_percent']}%)", "dumpsys gfxinfo")

    # Thao tác OSD trong Player
    toolkit.tool_press_key("UP") # Quick OSD
    toolkit.tool_screencap("player_quick_osd")
    toolkit.tool_press_key("BACK")
    toolkit.tool_press_key("BACK") # Thoát về IPTV

    # 6. MultiView (Đa màn hình)
    print("\n[Mục 6] Kiểm tra MultiView đa luồng...", flush=True)
    toolkit.tool_open_screen("mv", "0")
    toolkit.tool_screencap("multiview_2x")
    toolkit.tool_press_key("BACK")

    # 7. Stress test D-pad (Chaos monkey)
    print("\n[Mục 7] Stress Test D-pad (Chaos monkey)...", flush=True)
    chaos = toolkit.tool_chaos_stress(count=25)
    if chaos["crashed"]:
        toolkit.tool_record_bug("CRITICAL", "Stability", "App crash khi nhận chuỗi phím D-pad dồn dập", str(chaos["details"]))
    else:
        print("  ✅ Ứng dụng vững vàng trước chuỗi phím bấm dồn dập", flush=True)

    # Xuất báo cáo Dashboard
    print("\n[Mục 8] Xuất Báo cáo Dashboard...", flush=True)
    res = toolkit.tool_export_dashboard()
    print(f"\n🎉 HOÀN TẤT TOÀN BỘ KIỂM THỬ! Xem Dashboard tại:\n{res['html_dashboard']}\n", flush=True)
    return res

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="KenhLive AI QA Studio")
    parser.add_argument("--pkg", default=PKG)
    parser.add_argument("--serial", default=None)
    parser.add_argument("--out", default=None)
    parser.add_argument("--full", action="store_true", help="Chạy toàn bộ kịch bản tự trị")
    args = parser.parse_args()

    studio = AIToolkit(pkg=args.pkg, serial=args.serial, out_dir=args.out)
    if args.full:
        run_autonomous_full_suite(studio)
    else:
        print("🛠️ KenhLive AI QA Studio đã sẵn sàng! Chạy với cờ --full để thực thi toàn bộ kịch bản.")
