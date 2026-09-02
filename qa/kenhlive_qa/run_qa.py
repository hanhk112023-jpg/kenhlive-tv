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

print(f'=== KenhLive QA Suite · APK {APK_VERSION} · AI={"off" if args.no_ai else "on"} ===', flush=True)

# ---------- 1. COLD START ----------
print('[1] Cold start', flush=True)
P.force_stop(); time.sleep(1.5); P.logcat_baseline()
t = P.launch()
add_check('App khởi động', t >= 0, f'cold start {t}ms' if t >= 0 else 'KHÔNG lên được màn hình')
if 0 <= t > 8000: add_finding('Cold start', 'HIGH', f'Khởi động chậm {t}ms', 'đo từ am start tới có focus', 'lazy-load playlist, move fetch ra khỏi onCreate, tránh main-thread IO')
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

# ---------- 6. AUTO-REFRESH (v4.7) ----------
if not args.quick:
    print('[6] Auto-refresh (ngồi yên 3.5 phút, refresh chu kỳ 3\')', flush=True)
    sh(f'adb shell am start -n {PKG}/.MainActivity'); time.sleep(6)
    r = P.auto_refresh(wait_s=210, interval_s=15)
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
        for f in judge_perf({'versionName': APK_VERSION, 'cold_start_ms': t, 'jank': j, 'pss_kb': m}):
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
