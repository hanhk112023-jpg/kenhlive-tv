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
    order = {'CRITICAL': 0, 'HIGH': 1, 'MEDIUM': 2, 'LOW': 3, 'INFO': 4}
    for f in sorted(r['findings'], key=lambda x: order.get(x.get('severity'), 4))[:15]:
        s.write(f"- **[{f.get('severity')}]** {f.get('area')} — {f.get('issue')}\n  - 💡 {f.get('suggestion','')}\n")
    if session_summary_md:
        s.write(f"\n---\n{session_summary_md}\n")
if any(f.get('severity') == 'CRITICAL' for f in r['findings']):
    print('::error::Có CRITICAL finding')
    sys.exit(1)
