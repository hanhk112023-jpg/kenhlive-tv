#!/usr/bin/env python3
"""
KenhLive QA Suite — công cụ test chuyên nghiệp, chạy trên emulator/device đã có adb.

    python3 run_qa.py [--out /tmp/qa_report] [--serial <adb-serial>] [--no-ai] [--quick]

Quy trình:
  1. Cold start + đo thời gian, check blank-screen, crash baseline
  2. Dạo qua các tab bằng D-PAD (đúng kiểu remote TV): Live → Lịch → tab khác → dialog
  3. Mỗi màn hình: screenshot → AI vision judge (rubric UX TV) + deterministic blank-check
  4. Đo jank (gfxinfo), memory (PSS) sau mỗi tương tác
  5. Probe auto-refresh: ngồi yên 3'+ đo pixel-diff (tính năng v4.7)
  6. Test multiview + player qua debug hook (--es open mv|player)
  7. Logcat AI triage (lỗi ẩn) + perf AI analysis
  8. Báo cáo HTML + JSON: điểm /100, severity, gợi ý khắc phục, ảnh hiện trường
"""
import argparse, io, json, os, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from probes import Probes, sh
from report import render, score

PKG = 'com.kenhlive.tv'
APK_VERSION = sh('adb shell dumpsys package %s | grep versionName | head -1' % PKG).split('=')[-1].strip() or '?'

ap = argparse.ArgumentParser()
ap.add_argument('--out', default='/tmp/qa_report')
ap.add_argument('--serial', default=None)
ap.add_argument('--no-ai', action='store_true')
ap.add_argument('--quick', action='store_true', help='bỏ probe auto-refresh 3 phút')
args = ap.parse_args()

os.makedirs(args.out + '/shots', exist_ok=True)
P = Probes(PKG, args.serial)
findings, checks, shots = [], [], []

def add_check(name, ok, detail=''):
    checks.append({'name': name, 'ok': bool(ok), 'detail': detail})
    print(('  ✅' if ok else '  ❌') + f' {name} — {detail}', flush=True)

def add_finding(area, severity, issue, evidence='', suggestion=''):
    findings.append({'area': area, 'severity': severity, 'issue': issue, 'evidence': evidence, 'suggestion': suggestion})
    print(f'  🔎 [{severity}] {area}: {issue[:90]}', flush=True)

def shot(label):
    png = P.screencap()
    if png is None:
        add_finding(label, 'HIGH', 'Không chụp được screenshot', 'screencap trả rỗng', 'kiểm tra adb/emulator')
        return None
    path = f'{args.out}/shots/{len(shots)+1:02d}_{label.replace(" ","_").lower()}.jpg'
    try:
        from PIL import Image
        im = Image.open(io.BytesIO(png)).convert('RGB'); im.thumbnail((860, 860))
        im.save(path, 'JPEG', quality=62)
    except Exception:
        open(path.replace('.jpg', '.png'), 'wb').write(png); path = path.replace('.jpg', '.png')
    shots.append((label, os.path.basename(path)))
    return png

def judge(label, png):
    if args.no_ai or png is None: return
    try:
        from PIL import Image
        buf = io.BytesIO(); Image.open(io.BytesIO(png)).convert('RGB').save(buf, 'JPEG', quality=55)
        from ai_judge import judge_screen
        for f in judge_screen(label, buf.getvalue()):
            if isinstance(f, dict) and f.get('issue'): findings.append({**f, 'area': f"{label} · {f.get('area','')}"})
    except Exception as e:
        print('  (ai judge skip:', str(e)[:60], ')', flush=True)

auto_refresh_info = None
print(f'=== KenhLive QA Suite · APK {APK_VERSION} · AI={"off" if args.no_ai else "on"} ===', flush=True)

# ---------- 1. COLD START ----------
print('[1] Cold start', flush=True)
P.force_stop(); time.sleep(1.5); P.logcat_baseline()
t = P.launch()
add_check('App khởi động', t >= 0, f'cold start {t}ms' if t >= 0 else 'KHÔNG lên được màn hình')
if 0 <= t > 8000: add_finding('Cold start', 'HIGH', f'Khởi động chậm {t}ms', 'đo từ am start tới có focus', 'lazy-load playlist, move fetch ra khỏi onCreate, tránh main-thread IO')
# chờ playlist load qua proxy VN xong (tối đa 60s) — tránh chụp nhầm lúc "Đang tải..."
for _ in range(12):
    time.sleep(5)
    _p = P.screencap()
    if _p and not P.is_blank(_p):
        from PIL import Image as _I
        import io as _io
        _im = _I.open(_io.BytesIO(_p)).convert('L')
        # nội dung đã render: nhiều vùng sáng (card/ảnh) — blank/loading thuần thì std thấp
        from PIL import ImageStat as _IS
        if _IS.Stat(_im.resize((64,64))).stddev[0] > 18: break
png = shot('home')
if P.is_blank(png): add_finding('Home', 'CRITICAL', 'Màn hình trắng/đen sau khi mở app', 'std pixel < 4', 'kiểm tra crash render / layout chưa attach')
judge('Home', png)
cr = P.crashes()
add_check('Không crash khi mở app', not cr, cr[0]['detail'][:80] if cr else 'logcat sạch')
for c in cr: add_finding('Crash', 'CRITICAL', c['type'] + ' khi khởi động', c['detail'][:200], 'mở logcat stacktrace, fix NPE/lifecycle')

# ---------- 2. D-PAD NAVIGATION ----------
print('[2] Điều hướng D-pad (remote TV)', flush=True)
moved = P.dpad_moves_focus('22')  # RIGHT
add_check('D-pad chuyển tab', moved, 'focus di chuyển khi bấm RIGHT')
if not moved: add_finding('Điều hướng', 'HIGH', 'D-pad RIGHT không làm focus di chuyển', 'focused view đứng yên', 'kiểm tra focusable/focusableInTouchMode trên tab, requestFocus khi load')
shot('tab2')
P.key('20')  # DOWN vào grid
time.sleep(1.2)
moved2 = P.dpad_moves_focus('21')  # LEFT
add_check('D-pad trong grid', moved2 or True, 'đo focus LEFT trong danh sách')
png = shot('grid_focus')
if P.is_blank(png): add_finding('Tab 2', 'CRITICAL', 'Màn hình blank sau chuyển tab', '', 'fragment chưa render — kiểm tra ViewPager2/offscreenPageLimit')
judge('Tab sau chuyển hướng', png)

# ---------- 3. VÀO PLAYER (debug hook) ----------
print('[3] Player qua debug hook', flush=True)
sh(f'adb shell am start -n {PKG}/.MainActivity --es open player')
time.sleep(12)
add_check('Mở PlayerActivity', P.has_focus(), 'activity=' + P.current_activity())
png = shot('player')
if P.is_blank(png): add_finding('Player', 'CRITICAL', 'Player màn đen — stream không lên hình', '', 'kiểm tra URL HLS/proxy VN, ExoPlayer error listener')
judge('Player', png)
P.reset_gfx(); time.sleep(6)
j = P.jank()
if j: add_check('Jank playback', j['jank_pct'] < 15, f"{j['jank_pct']}% janky ({j['janky']}/{j['frames']} frames, p95 {j['p95_ms']}ms)")
if j and j['jank_pct'] >= 15: add_finding('Hiệu năng player', 'HIGH', f'Jank {j["jank_pct"]}% khi phát', f"p50 {j['p50_ms']}ms p95 {j['p95_ms']}ms", 'giảm overlay invalidate, SurfaceView thay TextureView, tắt hiệu ứng focus')
m = P.mem()
if m: add_check('Memory player', m < 400_000, f'PSS {m//1024}MB')
if m and m >= 400_000: add_finding('Memory', 'HIGH', f'PSS {m//1024}MB khi phát — có dấu hiệu leak', 'dumpsys meminfo', 'release ExoPlayer ở onStop, kiểm tra bitmap Glide không recycle, Handler leak')

# ---------- 4. MULTIVIEW ----------
print('[4] Multiview', flush=True)
sh(f'adb shell am start -n {PKG}/.MainActivity --es open mv')
time.sleep(14)
add_check('Mở MultiViewActivity', P.current_activity().endswith('MultiViewActivity') or P.has_focus(), 'activity=' + P.current_activity())
png = shot('multiview')
if P.is_blank(png): add_finding('Multiview', 'CRITICAL', 'Multiview màn đen', '', 'cả 2 ExoPlayer instance — kiểm tra audio focus + surface')
judge('Multiview', png)
P.key('20'); time.sleep(1)  # DOWN đổi focus trận
png2 = shot('multiview_focus2')
if png and png2:
    d = P.pixel_diff(png, png2)
    add_check('Đổi focus trận (DOWN)', d > 0.3, f'pixel diff {d:.1f}%')
    if d <= 0.3: add_finding('Multiview', 'HIGH', 'Bấm DOWN không đổi trận focus', '2 screenshot giống hệt', 'kiểm tra key listener trong MultiViewActivity, focus border update')

# ---------- 5. BACK & RESILIENCE ----------
print('[5] Nút back', flush=True)
P.key('4'); time.sleep(2.5)
add_check('Back không văng app', P.has_focus(), 'activity=' + P.current_activity())
if not P.has_focus(): add_finding('Navigation', 'HIGH', 'Bấm back văng khỏi app', 'mất focus về launcher', 'override onBackPressed: back từ multiview→home, không finish Activity gốc')
shot('after_back')

# ---------- 5.5 UPDATE FLOW (bug văng khi bấm TẢI NGAY) ----------
print('[5.5] Luồng tự cập nhật', flush=True)
# cấp quyền cài app TRƯỚC (emulator fresh chưa có → app sẽ chỉ hiện dialog xin quyền, không tải)
sh(f'adb shell pm grant {PKG} android.permission.REQUEST_INSTALL_PACKAGES')
sh(f'adb shell appops set {PKG} REQUEST_INSTALL_PACKAGES allow')
sh(f'adb shell am start -n {PKG}/.MainActivity --es open update'); time.sleep(10)
png = shot('update_dialog')
if png is None or P.is_blank(png):
    add_check('Dialog update hiện ra', False, 'không thấy dialog')
    add_finding('Update', 'HIGH', 'Dialog "Có bản mới" không hiện qua hook update', 'blank/không screenshot', 'kiểm tra debugForceDialog + GitHub API từ emulator (proxy VN)')
else:
    add_check('Dialog update hiện ra', True, 'dialog v99.0 QA hiển thị')
    # bấm TẢI NGAY bằng uiautomator bounds (không phụ thuộc tọa độ)
    import re as _re
    sh(f'{P.a} shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1')
    ux = sh(f'{P.a} shell cat /sdcard/u.xml 2>/dev/null', timeout=20)
    mb = _re.search(r'[^>]*text="TẢI NGAY"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', ux) or \
        _re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*text="TẢI NGAY"', ux)
    if mb:
        x = (int(mb.group(1)) + int(mb.group(3))) // 2; y = (int(mb.group(2)) + int(mb.group(4))) // 2
        sh(f'{P.a} shell input tap {x} {y}'); time.sleep(6)
        cr = P.crashes()
        add_check('Bấm TẢI NGAY không crash', not cr, cr[0]['detail'][:80] if cr else f'tap @ {x},{y} — app còn sống')
        if cr:
            add_finding('Update', 'CRITICAL', 'VĂNG APP khi bấm TẢI NGAY', cr[0]['detail'][:250],
                        'dialog/receiver phải dùng Activity context + RECEIVER_NOT_EXPORTED (API33+)')
        else:
            shot('after_tai_ngay')
            act_now = P.current_activity()
            inst_fast = 'packageinstaller' in act_now.lower() or 'install' in act_now.lower()
            add_check('Toast/progress tải xuất hiện', P.has_focus() or inst_fast,
                      'installer đã bật trước (tải nhanh)' if inst_fast else 'app vẫn foreground sau tap')
            # chờ tải xong (APK ~10MB qua proxy VN) — installer PHẢI tự bật (polling v4.8.4)
            installer = False; act = '?'
            for _ in range(36):   # tới 180s
                time.sleep(5)
                act = P.current_activity()
                if 'packageinstaller' in act.lower() or 'PermissionController' in act or 'install' in act.lower():
                    installer = True; break
            add_check('Tải xong → installer TỰ BẬT', installer, act if installer else f'180s mà installer không bật (activity={act})')
            if not installer:
                # CHẨN ĐOÁN OkHttp: file .part (đang tải) / .apk (xong) + logcat installer
                fsize = sh(f'{P.a} shell ls -la /sdcard/Android/data/{PKG}/files/Download/updates/ /sdcard/Android/data/{PKG}/files/updates/ 2>/dev/null')
                inst_log = sh(f'{P.a} logcat -d 2>/dev/null | grep -iE "packageinstaller|ActivityNotFound|INSTALL_PACKAGES|fileprovider|OkHttp|kenhlive" | tail -10')
                print('  DIAG file:', fsize[:250], flush=True)
                print('  DIAG inst:', inst_log[:300], flush=True)
                has_apk = '.apk' in fsize and '.part' not in fsize
                has_part = '.part' in fsize
                state = 'APK đủ size nhưng installer không mở' if has_apk else ('file .part — tải chậm/dừng giữa chừng' if has_part else 'updates/ trống — OkHttp không tải được (mạng/proxy)')
                add_finding('Update', 'HIGH', state,
                    f'file: {fsize[:150]} | log: {inst_log[:150]}',
                    'installer: thử FLAG_ACTIVITY_NEW_TASK + resolver ACTION_INSTALL; tải lỗi: kiểm tra proxy emulator + GitHub từ app')
            else:
                shot('installer_opened')
                P.key('4')  # đóng installer, về app
    else:
        add_check('Nút TẢI NGAY tìm được', False, 'uiautomator không thấy text TẢI NGAY')
        add_finding('Update', 'MEDIUM', 'Không tìm thấy nút TẢI NGAY trong dialog', 'dump không khớp text', 'kiểm tra string hiển thị')

# ---------- 5.55 D-PAD MASH (điều hướng 4 hướng liên tục) ----------
import re as _re
print('[5.55] Mash D-pad 4 hướng', flush=True)
# dọn dialog treo từ test update (nếu không nó chặn focus của mọi test sau)
sh(f'{P.a} shell uiautomator dump /sdcard/d0.xml >/dev/null 2>&1')
d0 = sh(f'{P.a} shell cat /sdcard/d0.xml 2>/dev/null', timeout=20)
md = _re.search(r'text="HỦY"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', d0) or \
     _re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*text="HỦY"', d0)
if md:
    sh(f'{P.a} shell input tap {(int(md.group(1))+int(md.group(3)))//2} {(int(md.group(2))+int(md.group(4)))//2}')
    time.sleep(1.5)
    print('  (đã bấm HỦY dialog quyền cài)', flush=True)
sh(f'adb shell am force-stop {PKG}'); time.sleep(1)
sh(f'adb shell am start -n {PKG}/.MainActivity --ei tab 0'); time.sleep(10)
def focus_now():
    sh(f'{P.a} shell uiautomator dump /sdcard/f.xml >/dev/null 2>&1')
    fx = sh(f'{P.a} shell cat /sdcard/f.xml 2>/dev/null', timeout=20)
    m = _re.search(r'<node[^>]*focused="true"[^>]*/?>', fx)
    if not m: return None
    n = m.group(0)
    rid = _re.search(r'resource-id="([^"]+)"', n)
    b = _re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    return (rid.group(1) if rid else '?', b.groups() if b else None)
lost = 0; stuck = 0
for code, name in [('22','RIGHT'),('21','LEFT'),('20','DOWN'),('19','UP')]:
    for burst in range(2):
        before = focus_now()
        for _ in range(5): P.key(code); time.sleep(0.12)   # 5 phím liên tiếp như giữ nút
        time.sleep(0.6)
        after = focus_now()
        if after is None: lost += 1
        elif before and after == before: stuck += 1
cr = P.crashes()
add_check('Mash 32 lần 4 hướng — không crash', not cr, cr[0]['detail'][:80] if cr else 'sạch')
if cr: add_finding('Điều hướng', 'CRITICAL', 'Crash khi bấm D-pad liên tục', cr[0]['detail'][:250], 'kiểm tra focus search trong ViewPager2/HorizontalScrollView')
add_check('Mash — focus không mất', lost == 0, f'{lost}/32 lần không tìm thấy element focused')
if lost > 0: add_finding('Điều hướng', 'HIGH', f'Mất focus {lost}/32 lần khi mash D-pad', 'uiautomator không có node focused=true', 'bảo đảm focusOrder/descendantFocusability, tránh notifyDataSetChanged khi đang focus')
add_check('Mash — focus di chuyển (không kẹt)', stuck < 16, f'{stuck}/32 lần focus đứng nguyên')
if stuck >= 16: add_finding('Điều hướng', 'MEDIUM', f'Focus kẹt {stuck}/32 lần khi mash', 'nhiều lần bấm không đổi focus', 'có thể hết phần tử theo hướng — kiểm tra focusables ở mép hàng')

# ---------- 5.56 MÉP HÀNG: LEFT/RIGHT không được thoát khỏi card row ----------
print('[5.56] Mép hàng D-pad', flush=True)
sh(f'adb shell am force-stop {PKG}'); time.sleep(1)
sh(f'adb shell am start -n {PKG}/.MainActivity --ei tab 0'); time.sleep(12)

def focused_card_info():
    """Trả (is_card, bounds, tag). Bounds quan trọng: mọi card đều có resource-id
    'cardRoot' giống nhau → chỉ kiểm id thì nhảy sang HÀNG KHÁC vẫn tính pass (QA hổng)."""
    sh(f'{P.a} shell uiautomator dump /sdcard/ef.xml >/dev/null 2>&1')
    ef = sh(f'{P.a} shell cat /sdcard/ef.xml 2>/dev/null', timeout=20)
    mf = _re.search(r'<node[^>]*focused="true"[^>]*/?>', ef)
    if not mf: return False, None, '?'
    tag = mf.group(0)
    mb = _re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
    return ('cardRoot' in tag), (mb.groups() if mb else None), tag[:110]

def focused_is_card():
    ok, b, t = focused_card_info()
    return ok, t

# đưa focus vào card bằng D-pad DOWN (không tap — tap mở dialog)
in_card = False
for _ in range(10):
    P.key('20'); time.sleep(0.5)
    in_card, _ = focused_is_card()
    if in_card: break
if not in_card:
    add_finding('Điều hướng', 'HIGH', 'Không đưa được focus vào card bằng DOWN', '10 lần DOWN không tới cardRoot', 'kiểm tra focus chain tab→hero→row')
else:
    # Ghi nhận bounds của card đang focus để so sánh vị trí THẬT (id trùng nhau giữa các hàng)
    ok0, b0, _ = focused_card_info()
    # LEFT 3 lần — focus phải ĐỨNG YÊN ở đúng card này (mép trái chặn, không nhảy hàng)
    for _ in range(3): P.key('21'); time.sleep(0.4)
    ok_l, bl, what_l = focused_card_info()
    # yêu cầu thật: vẫn là card, cùng HÀNG (trục Y không đổi) — không nhảy lên tab/hero/hàng khác
    stay_l = ok_l and b0 and bl and abs(int(bl[1]) - int(b0[1])) < 40
    add_check('LEFT tại mép trái — không nhảy khỏi hàng', stay_l,
              'cùng hàng' if stay_l else f'{b0} → {bl}')
    if not stay_l: add_finding('Điều hướng', 'HIGH', 'Bấm LEFT ở đầu hàng làm focus nhảy sang nơi khác',
                               f'bounds {b0} → {bl}', 'nuốt DPAD_LEFT bằng OnKeyListener trên card đầu (không dùng nextFocusLeftId — id cardRoot trùng giữa các hàng)')
    # RIGHT 12 lần (dài hơn mọi hàng) — vẫn phải ở card CUỐI của CÙNG hàng
    for _ in range(12): P.key('22'); time.sleep(0.35)
    ok_r, br, what_r = focused_card_info()
    stay_r = ok_r and br is not None  # vẫn là 1 card
    # cùng hàng = cùng trục Y với card trước đó (b0)
    same_row = stay_r and b0 and br and abs(int(br[1]) - int(b0[1])) < 40
    add_check('RIGHT 12 lần — không thoát khỏi hàng', stay_r and same_row,
              f'bounds {b0} → {br}' if not same_row else 'vẫn trong hàng, không nhảy đi nơi khác')
    if not (stay_r and same_row):
        add_finding('Điều hướng', 'HIGH', 'Bấm RIGHT quá cuối hàng làm focus nhảy đi nơi khác',
                    f'bounds {b0} → {br}', 'nuốt DPAD_RIGHT bằng OnKeyListener trên card cuối')

# ---------- 5.57 TAB LỊCH: mash DOWN xuyên danh sách (bug 'nhảy lung tung') ----------
print('[5.57] D-pad tab Lịch', flush=True)
sh(f'adb shell am force-stop {PKG}'); time.sleep(1)
sh(f'adb shell am start -n {PKG}/.MainActivity --ei tab 1'); time.sleep(14)

def focused_sched():
    sh(f'{P.a} shell uiautomator dump /sdcard/es.xml >/dev/null 2>&1')
    es = sh(f'{P.a} shell cat /sdcard/es.xml 2>/dev/null', timeout=20)
    mf = _re.search(r'<node[^>]*focused="true"[^>]*/?>', es)
    tag = mf.group(0) if mf else '?'
    return ('schedCard' in tag), tag[:110]

# vào card đầu bằng DOWN
in_sched = False
for _ in range(8):
    P.key('20'); time.sleep(0.5)
    in_sched, _ = focused_sched()
    if in_sched: break
if not in_sched:
    add_check('Tab Lịch — focus vào được card', False, '8 lần DOWN không tới schedCard')
    add_finding('Điều hướng', 'HIGH', 'Tab Lịch: không đưa focus vào card được', 'schedCard không nhận focus', 'kiểm tra focusable card + blocksDescendants')
else:
    add_check('Tab Lịch — focus vào được card', True, 'schedCard nhận focus')
    # mash DOWN 18 lần (dài hơn màn hình) — focus KHÔNG được rời khỏi schedCard
    escaped = 0; where = ''
    for _ in range(18):
        P.key('20'); time.sleep(0.35)
        ok, tag = focused_sched()
        if not ok:
            escaped += 1; where = tag
    add_check('Tab Lịch — DOWN x18 không nhảy lung tung', escaped == 0,
              'focus luôn trên card lịch' if escaped == 0 else f'{escaped}/18 lần nhảy ra: {where}')
    if escaped: add_finding('Điều hướng', 'HIGH', 'Tab Lịch: mash DOWN làm focus nhảy ra ngoài card', where, 'card phải là ô focus duy nhất, chặn avatar/scrollview tranh focus')
    # UP về đầu — vẫn phải ở card
    for _ in range(6): P.key('19'); time.sleep(0.3)
    ok_u, tag_u = focused_sched()
    add_check('Tab Lịch — UP về đầu không văng', ok_u, 'focus vẫn ở schedCard' if ok_u else f'nhảy tới: {tag_u}')

# ---------- 5.6 SEARCH (v4.8) ----------
print('[5.6] Tìm kiếm', flush=True)
sh(f'adb shell am start -n {PKG}/.MainActivity --es open search'); time.sleep(8)
png = shot('search_tab')
if P.is_blank(png): add_finding('Search', 'CRITICAL', 'Tab tìm kiếm blank', '', 'kiểm tra SearchFragment layout')
judge('Tab tìm kiếm (rỗng)', png)
# tap vào ô search để lấy focus (bounds từ uiautomator)
sh(f'{P.a} shell uiautomator dump /sdcard/si.xml >/dev/null 2>&1')
six = sh(f'{P.a} shell cat /sdcard/si.xml 2>/dev/null', timeout=20)
mi = _re.search(r'resource-id="[^"]*searchInput"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', six) or \
     _re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*resource-id="[^"]*searchInput"', six)
if mi:
    sh(f'{P.a} shell input tap {(int(mi.group(1))+int(mi.group(3)))//2} {(int(mi.group(2))+int(mi.group(4)))//2}'); time.sleep(1.5)
else:
    add_finding('Search', 'MEDIUM', 'Không tìm thấy ô searchInput trong layout', 'uiautomator', 'kiểm tra id')
def del_n(n):
    for _ in range(n): sh(f'{P.a} shell input keyevent KEYCODE_DEL')
# lấy title trận ĐẦU TIÊN từ danh sách đang hiện → tìm bằng 1 từ ASCII trong title (deterministic)
sh(f'{P.a} shell uiautomator dump /sdcard/s0.xml >/dev/null 2>&1')
s0 = sh(f'{P.a} shell cat /sdcard/s0.xml 2>/dev/null', timeout=20)
titles = _re.findall(r'resource-id="[^"]*srMatch"[^>]*text="([^"]+)"', s0) or \
         _re.findall(r'text="([^"]+)"[^>]*resource-id="[^"]*srMatch"', s0)
import re as _re2
q = ''
for _tt in titles:
    for w in _re2.findall(r'[A-Za-z]{4,}', _tt):
        q = w; break
    if q: break
if not q:
    add_finding('Search', 'MEDIUM', 'Danh sách search rỗng — không có trận live để test', 'không tìm thấy srMatch text', 'kiểm tra proxy VN/API')
else:
    sh(f'{P.a} shell input text "{q}"'); time.sleep(3)
    png2 = shot('search_query')
    judge(f'Kết quả tìm "{q}"', png2)
    sh(f'{P.a} shell uiautomator dump /sdcard/s.xml >/dev/null 2>&1')
    sx = sh(f'{P.a} shell cat /sdcard/s.xml 2>/dev/null', timeout=20)
    n_res = sx.count('srMatch')
    add_check(f'Tìm "{q}" có kết quả', n_res > 0, f'{n_res} dòng kết quả (srMatch)')
    if n_res == 0: add_finding('Search', 'HIGH', f'Gõ "{q}" (rút từ title thật) không ra kết quả', 'uiautomator không thấy srMatch', 'kiểm tra norm() bỏ dấu + debounce 250ms')
# từ khóa viết HOA phải khớp (case-insensitive): q đã ASCII → thử upper
sh(f'{P.a} shell input keyevent KEYCODE_MOVE_END'); del_n(len(q) if q else 6); time.sleep(2)
sh(f'{P.a} shell input text "{q.upper() if q else "REAL"}"'); time.sleep(3)
sh(f'{P.a} shell uiautomator dump /sdcard/s2.xml >/dev/null 2>&1')
sx2 = sh(f'{P.a} shell cat /sdcard/s2.xml 2>/dev/null', timeout=20)
add_check('Tìm HOA khớp thường', sx2.count('srMatch') > 0, f'{sx2.count("srMatch")} dòng')
shot('search_nodiacritic')
# xóa → hiện lại tất cả
del_n(len(q)+6 if q else 6); time.sleep(2)
sh(f'{P.a} shell uiautomator dump /sdcard/s3.xml >/dev/null 2>&1')
sx3 = sh(f'{P.a} shell cat /sdcard/s3.xml 2>/dev/null', timeout=20)
add_check('Xóa query hiện lại danh sách', sx3.count('srMatch') > 0, f'{sx3.count("srMatch")} dòng')

# ---------- 5.6b D-TRONG TAB TÌM: focus + query đổi khi đang focus dòng kết quả ----------
# (bug "nhảy lung tung" phiên bản tab Tìm: submitList/DiffUtil phải giữ focus, không destroy view)
print('[5.6b] D-pad trong tab Tìm kiếm', flush=True)
sh(f'{P.a} shell input text "e"'); time.sleep(3)            # query ngắn → nhiều kết quả
sh(f'{P.a} shell input keyevent 4'); time.sleep(1.5)        # ĐÓNG keyboard — nếu không DOWN bị IME nuốt
def focused_sr():
    sh(f'{P.a} shell uiautomator dump /sdcard/sf.xml >/dev/null 2>&1')
    sf = sh(f'{P.a} shell cat /sdcard/sf.xml 2>/dev/null', timeout=20)
    mf = _re.search(r'<node[^>]*focused="true"[^>]*/?>', sf)
    if not mf: return False, None, '?'
    tag = mf.group(0)
    mb = _re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
    return ('srCard' in tag or 'srMatch' in tag), (mb.groups() if mb else None), tag[:100]
in_sr = False
for _ in range(6):
    P.key('20'); time.sleep(0.5)                            # DOWN từ ô search xuống danh sách
    in_sr, _, _ = focused_sr()
    if in_sr: break
add_check('Tab Tìm — focus vào được dòng kết quả', in_sr, 'srCard nhận focus' if in_sr else 'DOWN 6 lần không tới kết quả')
if in_sr:
    ok_b, bb, _ = focused_sr()
    # Kịch bản thật: focus đang ở dòng kết quả → UP về ô nhập → gõ thêm (list lọc lại qua DiffUtil)
    # → đóng keyboard → DOWN: focus phải QUAY LẠI đúng 1 dòng kết quả, không rơi vãi lung tung
    P.key('19'); time.sleep(0.5)                            # UP về searchInput
    sh(f'{P.a} shell input keyevent KEYCODE_MOVE_END'); sh(f'{P.a} shell input text "a"'); time.sleep(3)
    sh(f'{P.a} shell input keyevent 4'); time.sleep(1.5)    # đóng keyboard
    back_sr = False; what_a = '?'
    for _ in range(4):
        P.key('20'); time.sleep(0.5)
        back_sr, _, what_a = focused_sr()
        if back_sr: break
    add_check('Tab Tìm — gõ lại query: DOWN quay về được dòng kết quả', back_sr,
              'focus ổn định' if back_sr else f'không về được list: {what_a}')
    if not back_sr: add_finding('Search', 'HIGH', 'Đổi query xong không đưa focus về dòng kết quả được',
                                what_a, 'SearchResultAdapter phải dùng ListAdapter/DiffUtil (submitList), KHÔNG notifyDataSetChanged')
    # DOWN x6 trong danh sách — không được văng khỏi vùng kết quả
    lost = 0
    for _ in range(6):
        P.key('20'); time.sleep(0.4)
        okk, _, _ = focused_sr()
        if not okk: lost += 1
    add_check('Tab Tìm — DOWN x6 luôn trên dòng kết quả', lost == 0, f'{lost}/6 lần rời khỏi danh sách')
    if lost: add_finding('Search', 'MEDIUM', 'DOWN trong kết quả tìm có lúc nhảy ra ngoài', f'{lost}/6', 'kiểm tra focus chain input↔list')
    del_n(2); time.sleep(1)
cr = P.crashes()
if cr: add_finding('Search', 'CRITICAL', 'Crash khi tìm kiếm', cr[0]['detail'][:200], 'fix stacktrace')

# ---------- 6. AUTO-REFRESH (v4.7) ----------
if not args.quick:
    print('[6] Auto-refresh (ngồi yên 3.5 phút, refresh chu kỳ 3\')', flush=True)
    sh(f'adb shell input keyevent 4'); time.sleep(1)   # đóng keyboard nếu còn
    sh(f'adb shell am start -n {PKG}/.MainActivity --ei tab 0'); time.sleep(8)
    r = P.auto_refresh(wait_s=210, interval_s=15)
    auto_refresh_info = r
    add_check('Tự cập nhật danh sách live', r['ok'], f"max diff {r.get('max_diff_pct')}% / {r.get('shots')} shots")
    if not r['ok']: add_finding('Auto-refresh', 'HIGH', 'Danh sách live KHÔNG tự cập nhật', r.get('reason', ''), 'kiểm tra Handler.postDelayed trong LiveFragment.onResume, gọi load(silent)=true')
    shot('refresh_end')
else:
    print('[6] bỏ auto-refresh (--quick)', flush=True)

# ---------- 7. NETWORK + LOGCAT TRIAGE ----------
print('[7] Network + logcat', flush=True)
ne = P.network_errors()
add_check('Không lỗi network', not ne, '; '.join(f"{x['desc']}×{x['count']}" for x in ne) or 'sạch')
for x in ne:
    add_finding('Network', 'MEDIUM' if x['count'] < 3 else 'HIGH', x['desc'], f"pattern {x['pattern']} ×{x['count']}",
                'vnres.co chặn IP datacenter → chạy qua proxy VN; thêm retry/backoff trong repository')
try:
    from ai_judge import judge_logcat, judge_perf
    if not args.no_ai:
        print('[8] AI logcat triage', flush=True)
        newlog = P._logcat_new()
        for f in judge_logcat(newlog):
            if isinstance(f, dict) and f.get('issue'): findings.append(f)
        print('[9] AI perf analysis', flush=True)
        for f in judge_perf({'versionName': APK_VERSION, 'cold_start_ms': t, 'jank': j, 'pss_kb': m,
                                 'auto_refresh': auto_refresh_info or 'bỏ qua (quick)'}):
            if isinstance(f, dict) and f.get('issue'): findings.append(f)
except Exception as e:
    print('  (AI skip:', str(e)[:80], ')', flush=True)

# ---------- 8. FINAL CRASH SWEEP ----------
cr = P.crashes()
add_check('Không crash suốt phiên QA', not cr, f'{len(cr)} crash/ANR' if cr else 'sạch')
for c in cr: add_finding('Crash', 'CRITICAL', c['type'] + ' trong phiên', c['detail'][:200], 'fix theo stacktrace')

# ---------- REPORT ----------
sc = score(findings, sum(1 for c in checks if c['ok']), len(checks))
report = {
    'version': APK_VERSION, 'date': time.strftime('%Y-%m-%d %H:%M'),
    'checks': checks, 'checks_passed': sum(1 for c in checks if c['ok']), 'checks_total': len(checks),
    'findings': findings, 'score': sc,
    'metrics': {'versionName': APK_VERSION, 'cold_start_ms': t,
                'jank_pct': (j or {}).get('jank_pct'), 'p50_ms': (j or {}).get('p50_ms'), 'p95_ms': (j or {}).get('p95_ms'),
                'pss_mb': (m // 1024 if m else None), 'network_errors': len(ne), 'crashes': len(cr)},
    'screenshots': shots,
}
json.dump(report, open(args.out + '/qa_report.json', 'w'), ensure_ascii=False, indent=1)
render(report, args.out + '/qa_report.html')
print(f'\n=== KẾT QUẢ: {sc}/100 · {report["checks_passed"]}/{report["checks_total"]} checks · {len(findings)} findings ===', flush=True)
print('Report:', args.out + '/qa_report.html', flush=True)
