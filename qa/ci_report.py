#!/usr/bin/env python3
"""Đọc qa_out/qa_report.json → in tóm tắt + GitHub Step Summary → exit 1 nếu có CRITICAL."""
import json, os, sys

p = 'qa_out/qa_report.json'
if not os.path.exists(p):
    print('::error::QA không tạo được report (emulator/crash sớm?)')
    sys.exit(1)
r = json.load(open(p))
print(f"### KênhLive QA: {r['score']}/100 · {r['checks_passed']}/{r['checks_total']} checks · {len(r['findings'])} findings")
crit = [f for f in r['findings'] if f.get('severity') in ('CRITICAL', 'HIGH')]
for f in crit[:10]:
    print(f"- [{f['severity']}] {f['area']}: {f['issue'][:120]}")
with open(os.environ.get('GITHUB_STEP_SUMMARY', '/dev/null'), 'w') as s:
    s.write(f"## KênhLive QA — **{r['score']}/100**\n\n{r['checks_passed']}/{r['checks_total']} checks pass · {len(r['findings'])} findings\n\n")
    order = {'CRITICAL': 0, 'HIGH': 1, 'MEDIUM': 2, 'LOW': 3, 'INFO': 4}
    for f in sorted(r['findings'], key=lambda x: order.get(x.get('severity'), 4))[:15]:
        s.write(f"- **[{f.get('severity')}]** {f.get('area')} — {f.get('issue')}\n  - 💡 {f.get('suggestion','')}\n")
if any(f.get('severity') == 'CRITICAL' for f in r['findings']):
    print('::error::Có CRITICAL finding')
    sys.exit(1)
