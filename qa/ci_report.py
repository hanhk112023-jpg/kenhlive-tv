#!/usr/bin/env python3
"""Đọc qa_out/qa_report.json → in tóm tắt + GitHub Step Summary → exit 1 nếu có CRITICAL."""
import json, os, sys

p = 'qa_out/qa_report.json'
if not os.path.exists(p):
    print('::error::QA không tạo được report (emulator/crash sớm?)')
    sys.exit(1)
r = json.load(open(p))
print(f"### KênhLive QA: {r['score']}/100 · {r['checks_passed']}/{r['checks_total']} checks · {len(r['findings'])} findings")

# Hiển thị tóm tắt Session của AI QA nếu có
sess = r.get('session')
if sess:
    st = sess.get('stats', {})
    print(f"🤖 [AI QA Session] ID: {sess.get('session_id')} · {st.get('total_steps', 0)} bước · Phủ {st.get('tabs_explored', 0)}/6 Tabs ({st.get('tab_coverage_pct', 0)}%) · {st.get('dpad_keypresses_total', 0)} phím D-pad")

# Thông tin Video ghi hình toàn bộ quá trình
video_file = r.get('video')
timelapse_file = r.get('timelapse')
video_summary_lines = []
if video_file:
    for v_dir in ['qa_out', '/tmp/qa_report']:
        v_full = os.path.join(v_dir, video_file)
        if os.path.exists(v_full):
            sz_mb = f"{os.path.getsize(v_full)/(1024*1024):.1f}"
            print(f"🎬 [Video Recording] Đã quay toàn bộ quá trình: {video_file} ({sz_mb} MB) [1080p Streamable]")
            video_summary_lines.append(f"- 📹 **Full Tour Video (1080p):** `{video_file}` ({sz_mb} MB) — Ghi hình liên tục toàn bộ quá trình kiểm thử.")
            break
if timelapse_file:
    for v_dir in ['qa_out', '/tmp/qa_report']:
        tl_full = os.path.join(v_dir, timelapse_file)
        if os.path.exists(tl_full):
            tl_mb = f"{os.path.getsize(tl_full)/(1024*1024):.1f}"
            print(f"⚡ [Video Timelapse] Đã tạo bản xem nhanh 10x: {timelapse_file} ({tl_mb} MB)")
            video_summary_lines.append(f"- ⚡ **Timelapse 10x Preview:** `{timelapse_file}` ({tl_mb} MB) — Xem tóm tắt nhanh ~60s.")
            break

crit = [f for f in r['findings'] if f.get('severity') in ('CRITICAL', 'HIGH')]
for f in crit[:10]:
    print(f"- [{f['severity']}] {f['area']}: {f['issue'][:120]}")

session_summary_md = ""
for sm_path in ['qa_out/qa_session_summary.md', '/tmp/qa_report/qa_session_summary.md']:
    if os.path.exists(sm_path):
        try:
            with open(sm_path, 'r', encoding='utf-8') as f_sm:
                session_summary_md = f_sm.read()
            break
        except Exception:
            pass

with open(os.environ.get('GITHUB_STEP_SUMMARY', '/dev/null'), 'w', encoding='utf-8') as s:
    s.write(f"## KênhLive QA — **{r['score']}/100**\n\n{r['checks_passed']}/{r['checks_total']} checks pass · {len(r['findings'])} findings\n\n")
    if video_summary_lines:
        s.write("### 🎬 Video Ghi Hình Toàn Bộ Quá Trình\n" + "\n".join(video_summary_lines) + "\n\n")
    order = {'CRITICAL': 0, 'HIGH': 1, 'MEDIUM': 2, 'LOW': 3, 'INFO': 4}
    for f in sorted(r['findings'], key=lambda x: order.get(x.get('severity'), 4))[:15]:
        s.write(f"- **[{f.get('severity')}]** {f.get('area')} — {f.get('issue')}\n  - 💡 {f.get('suggestion','')}\n")
    if session_summary_md:
        s.write(f"\n---\n{session_summary_md}\n")
if any(f.get('severity') == 'CRITICAL' for f in r['findings']):
    print('::error::Có CRITICAL finding')
    sys.exit(1)
