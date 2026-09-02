"""KenhLive QA — báo cáo HTML chuyên nghiệp: điểm tổng, severity, ảnh chụp, gợi ý khắc phục."""
import html, json, time

SEV_ORDER = {'CRITICAL': 0, 'HIGH': 1, 'MEDIUM': 2, 'LOW': 3, 'INFO': 4}
SEV_COLOR = {'CRITICAL': '#ff4d4d', 'HIGH': '#ff8e3c', 'MEDIUM': '#ffd166', 'LOW': '#4cc9f0', 'INFO': '#6b7490'}
SEV_ICON  = {'CRITICAL': '🛑', 'HIGH': '⚠️', 'MEDIUM': '🔸', 'LOW': 'ℹ️', 'INFO': '·'}

def score(findings, checks_passed, checks_total):
    """0-100: trừ theo severity + tỉ lệ check fail."""
    pen = {'CRITICAL': 25, 'HIGH': 12, 'MEDIUM': 5, 'LOW': 1, 'INFO': 0}
    s = 100 - sum(pen.get(f.get('severity', 'LOW'), 1) for f in findings)
    if checks_total:
        s -= round((checks_total - checks_passed) / checks_total * 20)
    return max(0, min(100, s))

def grade(sc):
    return 'A' if sc >= 90 else 'B' if sc >= 75 else 'C' if sc >= 60 else 'D' if sc >= 40 else 'F'

def render(report, out_path):
    f = report['findings']
    f_sorted = sorted(f, key=lambda x: (SEV_ORDER.get(x.get('severity', 'LOW'), 9), x.get('area', '')))
    sc = report['score']
    sev_count = {}
    for x in f: sev_count[x.get('severity', 'LOW')] = sev_count.get(x.get('severity', 'LOW'), 0) + 1

    def esc(s): return html.escape(str(s or ''))
    checks_rows = ''.join(
        f"<tr><td>{esc(c['name'])}</td><td>{esc(c.get('detail',''))}</td>"
        f"<td class='{'pass' if c['ok'] else 'fail'}'>{'✅ PASS' if c['ok'] else '❌ FAIL'}</td></tr>"
        for c in report['checks'])
    finding_rows = ''.join(
        f"<tr><td><span class='sev' style='background:{SEV_COLOR.get(x.get('severity','LOW'),'#666')}20;color:{SEV_COLOR.get(x.get('severity','LOW'),'#666')}"
        f";border:1px solid {SEV_COLOR.get(x.get('severity','LOW'),'#666')}55'>{SEV_ICON.get(x.get('severity','LOW'),'')} {esc(x.get('severity','LOW'))}</span></td>"
        f"<td><b>{esc(x.get('area'))}</b><div class='ev'>{esc(x.get('evidence'))}</div></td>"
        f"<td>{esc(x.get('issue'))}</td><td class='sug'>{esc(x.get('suggestion'))}</td></tr>"
        for x in f_sorted) or "<tr><td colspan='4' class='empty'>Không phát hiện vấn đề 🎉</td></tr>"
    shots_html = ''.join(
        f"<figure><img src='{esc(p)}' loading='lazy'><figcaption>{esc(lbl)}</figcaption></figure>"
        for lbl, p in report.get('screenshots', []))
    metrics_rows = ''.join(f"<tr><td>{esc(k)}</td><td class='mono'>{esc(v)}</td></tr>" for k, v in report.get('metrics', {}).items())

    doc = f"""<!DOCTYPE html><html lang="vi"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>KênhLive · QA Report</title>
<style>
:root{{--bg:#05060a;--s1:#0b0d14;--s2:#10131c;--line:rgba(255,255,255,.07);--tx:#f2f5fa;--dim:#6b7490;--green:#3ddc97;--red:#ff4d4d}}
*{{margin:0;padding:0;box-sizing:border-box}}
body{{background:var(--bg);color:var(--tx);font-family:system-ui,-apple-system,'Segoe UI',sans-serif;padding:28px}}
.wrap{{max-width:1100px;margin:0 auto}}
h1{{font-size:22px;font-weight:900}} .sub{{color:var(--dim);font-size:13px;margin:4px 0 22px}}
.hero{{display:flex;gap:22px;align-items:center;background:linear-gradient(160deg,var(--s2),var(--s1));border:1px solid var(--line);border-radius:18px;padding:22px 26px;margin-bottom:22px}}
.score{{font-size:56px;font-weight:900;line-height:1}} .grade{{font-size:20px;font-weight:800;padding:4px 14px;border-radius:10px;background:#ffffff12}}
.sevbar{{display:flex;gap:10px;margin-left:auto;flex-wrap:wrap}}
.sevchip{{padding:8px 14px;border-radius:12px;font-size:12px;font-weight:800;background:#ffffff08;border:1px solid var(--line)}}
h2{{font-size:14px;text-transform:uppercase;letter-spacing:1.2px;color:var(--dim);margin:26px 0 12px}}
table{{width:100%;border-collapse:collapse;font-size:13.5px;background:linear-gradient(160deg,var(--s2),var(--s1));border:1px solid var(--line);border-radius:14px;overflow:hidden}}
th{{text-align:left;font-size:10.5px;text-transform:uppercase;letter-spacing:1px;color:var(--dim);padding:11px 14px;border-bottom:1px solid var(--line)}}
td{{padding:11px 14px;border-bottom:1px solid var(--line);vertical-align:top}} tr:last-child td{{border-bottom:none}}
.pass{{color:var(--green);font-weight:700}} .fail{{color:var(--red);font-weight:700}}
.sev{{padding:3px 9px;border-radius:999px;font-size:10.5px;font-weight:800;white-space:nowrap}}
.ev{{color:var(--dim);font-size:11.5px;margin-top:3px}} .sug{{color:#a8b6ff}}
.mono{{font-family:ui-monospace,monospace}} .empty{{text-align:center;color:var(--dim);padding:22px}}
.shots{{display:grid;grid-template-columns:repeat(auto-fill,minmax(260px,1fr));gap:14px}}
figure{{background:var(--s1);border:1px solid var(--line);border-radius:12px;overflow:hidden}}
figure img{{width:100%;display:block}} figcaption{{padding:8px 12px;font-size:12px;color:var(--dim)}}
footer{{margin-top:30px;color:#454e66;font-size:11.5px;text-align:center}}
</style></head><body><div class="wrap">
<h1>📺 KênhLive — QA Report</h1>
<div class="sub">Bản {esc(report.get('version','?'))} · {esc(report.get('date'))} · deterministic probes + AI vision judge (deepseek-v4-flash-vision-exp)</div>
<div class="hero">
  <div><div class="score" style="color:{'#3ddc97' if sc>=75 else '#ff8e3c' if sc>=50 else '#ff4d4d'}">{sc}</div><div class="sub" style="margin:2px 0 0">/100</div></div>
  <div class="grade">{grade(sc)}</div>
  <div style="font-size:13px;color:var(--dim)">Checks: <b style="color:var(--tx)">{report['checks_passed']}/{report['checks_total']} pass</b><br>Findings: <b style="color:var(--tx)">{len(f)}</b></div>
  <div class="sevbar">{''.join(f"<div class='sevchip' style='color:{SEV_COLOR[k]}'>{SEV_ICON[k]} {k}: {v}</div>" for k, v in sorted(sev_count.items(), key=lambda x: SEV_ORDER.get(x[0], 9)))}</div>
</div>
<h2>🧪 Kết quả kiểm tra</h2>
<table><thead><tr><th>Check</th><th>Chi tiết</th><th>Kết quả</th></tr></thead><tbody>{checks_rows}</tbody></table>
<h2>🔎 Vấn đề phát hiện &amp; gợi ý ({len(f)})</h2>
<table><thead><tr><th>Mức độ</th><th>Khu vực</th><th>Vấn đề</th><th>Gợi ý khắc phục</th></tr></thead><tbody>{finding_rows}</tbody></table>
<h2>📐 Số liệu đo được</h2>
<table><tbody>{metrics_rows or '<tr><td class=empty>—</td></tr>'}</tbody></table>
<h2>📸 Ảnh hiện trường</h2>
<div class="shots">{shots_html or '<div class="empty">không có ảnh</div>'}</div>
<footer>KênhLive QA Suite · probes: crash/ANR/jank/mem/network/blank/D-pad/auto-refresh · AI: vision judge + logcat triage + perf analysis</footer>
</div></body></html>"""
    with open(out_path, 'w') as fh: fh.write(doc)
    return out_path
