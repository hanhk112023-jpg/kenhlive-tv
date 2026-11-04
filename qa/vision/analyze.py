#!/usr/bin/env python3
"""Gui toan bo anh screenshot cua mot luot Porthole cho Gemini (Meteor gateway)
phan tich truc quan tung anh + so sanh cac cap anh lien tiep.
Ket qua ghi ra vision-report.md (va in ra stdout)."""
import base64, json, os, sys, urllib.request

BASE = os.environ.get("METEOR_API_BASE", "http://meteor.pikamc.vn:25155/v1")
KEY = os.environ["METEOR_API_KEY"]
SRC_RUN = os.environ.get("SOURCE_RUN_ID", "")
OUT_DIR = os.environ.get("OUT_DIR", "qa/vision-out")
os.makedirs(OUT_DIR, exist_ok=True)

def api(path, payload=None, timeout=60):
    req = urllib.request.Request(BASE + path)
    req.add_header("Authorization", "Bearer " + KEY)
    data = None
    if payload is not None:
        req.add_header("Content-Type", "application/json")
        data = json.dumps(payload).encode()
    with urllib.request.urlopen(req, data=data, timeout=timeout) as r:
        return json.loads(r.read().decode())

ids = [m.get("id", "") for m in api("/models", timeout=30).get("data", [])]
g38 = [i for i in ids if "gemini-3.8-flash" in i.lower()]
high = [i for i in g38 if "high" in i.lower()]
MODEL = high[0] if high else (g38[0] if g38 else "")
if not MODEL:
    print("ERROR: khong tim thay model gemini-3.8-flash tren gateway. Models:", ids)
    sys.exit(1)
print("Model:", MODEL)

def download_artifact(run_id, dest_zip):
    import subprocess
    tok_req = urllib.request.Request(
        f"https://api.github.com/repos/hanhk112023-jpg/kenhlive-tv/actions/runs/{run_id}/artifacts",
        headers={"Authorization": "Bearer " + os.environ["GH_TOKEN"],
                 "Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(tok_req, timeout=60) as r:
        arts = json.loads(r.read().decode()).get("artifacts", [])
    art = next((a for a in arts if a["name"] == "porthole-report"), None)
    if not art:
        print("ERROR: khong thay artifact porthole-report cua run", run_id); sys.exit(1)
    dl = urllib.request.Request(art["archive_download_url"],
        headers={"Authorization": "Bearer " + os.environ["GH_TOKEN"]})
    with urllib.request.urlopen(dl, timeout=300) as r, open(dest_zip, "wb") as f:
        f.write(r.read())

import glob, zipfile
img_dir = "/tmp/imgs"
os.makedirs(img_dir, exist_ok=True)
if SRC_RUN:
    z = "/tmp/artifact.zip"
    download_artifact(SRC_RUN, z)
    zipfile.ZipFile(z).extractall(img_dir)
else:
    import shutil
    for f in glob.glob("qa/porthole-out/*.png"):
        shutil.copy(f, img_dir)

CONTEXT = {
 "01-hub.png": "Man hinh hub chinh cua app Android TV KenhLive ngay sau khi mo app va dong 2 hop thoai (cap nhat + thong bao). Chua dieu huong di dau.",
 "02-phim-hub.png": "Hub Phim vua tai xong: hero phim 'Xom Nho' o tren, duoi la hang pill DANH MUC (Tat Ca dang duoc chon = nen cam), duoi nua la muc 'Phim Moi Cap Nhat' voi cac the poster.",
 "03-pill.png": "Hub Phim, focus D-pad dang o pill 'Tat Ca' (pill dang chon).",
 "04-pill-next.png": "Ngay sau khi bam D-pad PHAI tu anh 03: focus phai sang pill 'Lich Su Xem'.",
 "05-card.png": "Ngay sau khi bam D-pad XUONG tu hang pill: focus phai xuong the phim dau tien 'Xom Nho' trong muc Phim Moi Cap Nhat.",
 "06-dialog.png": "Hop thoai chi tiet phim 'Xom Nho' vua mo bang phim OK tu the phim: poster, ten phim, ten goc, nam, mo ta, luoi tap 1-9, nut LUU PHIM va DONG.",
 "07-after-back.png": "Ngay sau khi bam BACK dong hop thoai: focus phai quay ve dung the phim 'Xom Nho' nhu anh 05.",
}
ORDER = ["01-hub.png","02-phim-hub.png","03-pill.png","04-pill-next.png","05-card.png","06-dialog.png","07-after-back.png"]
imgs = [f for f in ORDER if os.path.exists(os.path.join(img_dir, f))]
imgs += sorted(glob.glob(os.path.join(img_dir, "*.png")))
imgs = list(dict.fromkeys(imgs))
print("Anh se phan tich:", [os.path.basename(i) for i in imgs])

PROMPT = """Ban la chuyen gia QA giao dien Android TV. Hay phan tich KY anh screenshot nay cua app KenhLive (TV, 1920x1080) va bao cao bang tieng Viet:
1. Mo ta ngan: man hinh dang hien gi, thanh phan nao dang duoc focus (dau hieu focus: vien sang/cam, phong to, doi nen) — chi ro vi tri.
2. Liet ke MOI van de truc quan thay duoc, xep muc do (Nghiem trong / Trung binh / Nhe), moi van de kem: vi tri cu the trong anh + bang chung nhin thay. Chu y dac biet:
   - Tuong phan chu: chu trang/tren nen sang, chu chim vao anh nen, chu bi cat khuất.
   - Anh nen/backdrop chay tran xuong sau cac thanh phan khac (pill, tieu de muc) lam giam do doc.
   - Phan biet duoc trang thai FOCUS vs trang thai DANG CHON hay khong (nhin tu xa 3 met co phan biet duoc khong).
   - Poster/the phim: trong khung, sai ti le, meo, bi cat, vien khung lam, chat luong anh.
   - Noi dung bat thuong: mo ta thieu/sai, ten la, nam/badge la, du lieu trong co ve bi bia.
   - Bo cuc: le, khoang trang, thanh phan bi che khuat, tran man hinh.
3. Neu khong thay van de o mot muc, ghi ro 'khong thay'.
Tra loi theo dung cau truc: TOM TAT / FOCUS / VAN DE (danh sach) / KET LUAN. Chi bao cao dieu MAT THAY trong anh, khong doan mo."""

def chat(parts, max_tokens=1800):
    payload = {"model": MODEL,
               "messages": [{"role": "user", "content": parts}],
               "max_tokens": max_tokens}
    d = api("/chat/completions", payload, timeout=300)
    return d["choices"][0]["message"]["content"].strip()

def img_part(path):
    with open(path, "rb") as f:
        b64 = base64.b64encode(f.read()).decode()
    return {"type": "image_url", "image_url": {"url": "data:image/png;base64," + b64}}

report = [f"# Bao cao phan tich truc quan bang Gemini — model {MODEL}\n"]
for path in imgs:
    name = os.path.basename(path)
    print("Dang phan tich", name, "...", flush=True)
    parts = [{"type": "text", "text": PROMPT + "\n\nNgu canh anh nay: " + CONTEXT.get(name, "Anh screenshot app KenhLive TV.")},
             img_part(path)]
    try:
        ans = chat(parts)
    except Exception as e:
        ans = f"(Loi goi model: {e})"
    report.append(f"\n## {name}\n\n{ans}\n")

pairs = [("03-pill.png", "04-pill-next.png",
          "So sanh 2 anh: focus D-pad co thay doi NHIN THAY DUOC bang mat thuong khong? O anh 1 focus o pill nao, anh 2 o pill nao? Phan biet focus voi pill dang chon (nen cam 'Tat Ca') co ro khong? Tra loi ngan gon, chi dua tren dieu nhin thay."),
         ("05-card.png", "07-after-back.png",
          "So sanh 2 anh: chung co giong nhau ve vi tri focus va noi dung khong? Khac biet nao nhin thay duoc? Tra loi ngan gon.")]
for a, b, q in pairs:
    pa, pb = os.path.join(img_dir, a), os.path.join(img_dir, b)
    if os.path.exists(pa) and os.path.exists(pb):
        print(f"So sanh {a} vs {b} ...", flush=True)
        try:
            ans = chat([{"type": "text", "text": q}, img_part(pa), img_part(pb)], max_tokens=900)
        except Exception as e:
            ans = f"(Loi goi model: {e})"
        report.append(f"\n## SO SANH {a} ↔ {b}\n\n{ans}\n")

final = "\n".join(report)
with open(os.path.join(OUT_DIR, "vision-report.md"), "w") as f:
    f.write(final)
rep_path = os.path.join(img_dir, "report.txt")
if os.path.exists(rep_path):
    import shutil; shutil.copy(rep_path, os.path.join(OUT_DIR, "report.txt"))
print(final)
print("\n=== VISION DONE ===")
