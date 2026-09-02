#!/usr/bin/env python3
"""
QA AI-driven driver cho KênhLive Android TV.
Model (qwen3.8-flash qua Cloudflare Worker) NHÌN ảnh → quyết định tap nút nào →
predict kết quả → sau tap: model so sánh trước/sau → xác nhận hay bắt NÚT CHẾT.
Cũng bắt FATAL/ANR từ logcat. Không cần API key — Worker public, chỉ cần User-Agent.

python3 qa_ai.py <output_dir> <max_steps>
"""
import base64, io, json, os, re, subprocess, sys, time, urllib.request, urllib.error
from PIL import Image

OUT = sys.argv[1] if len(sys.argv) > 1 else "/tmp/qa"
MAX_STEPS = int(sys.argv[2]) if len(sys.argv) > 2 else 12
os.makedirs(OUT + "/shots", exist_ok=True)
PKG = "com.kenhlive.tv"
BASE = "https://llm-key-proxy.htuananh153.workers.dev/v1/chat/completions"
MODEL = "qwen3.8-flash"
UA = "curl/8.5.0"

def sh(cmd, timeout=25):
    try:
        r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
        return r.stdout.strip()
    except Exception:
        return ""

def tap(x, y): sh(f"adb shell input tap {x} {y}")
def key(k): sh(f"adb shell input keyevent {k}")
def back(): key("4")
def is_focused():
    return PKG in sh("adb shell dumpsys window | grep mCurrentFocus")

def launch():
    sh(f"adb shell am force-stop {PKG}"); time.sleep(1)
    sh(f"adb shell am start -n {PKG}/.MainActivity"); time.sleep(9)
    if not is_focused():
        time.sleep(4); sh(f"adb shell am start -n {PKG}/.MainActivity"); time.sleep(6)

def screenshot():
    """chụp ảnh -> JPEG xuống ~768px, base64."""
    raw = subprocess.run("adb exec-out screencap -p".split(), capture_output=True, timeout=20).stdout
    if len(raw) < 100000: return None, None
    img = Image.open(io.BytesIO(raw)).convert("RGB")
    img.thumbnail((768, 768))
    buf = io.BytesIO(); img.save(buf, format="JPEG", quality=55)
    return buf.getvalue(), img.size

def chat(system, prompt, img_jpeg):
    payload = {"model": MODEL, "temperature": 0.2, "max_tokens": 500,
        "messages": [{"role":"user","content":[
            {"type":"text","text":system + "\n\n" + prompt},
            {"type":"image_url","image_url":{"url":f"data:image/jpeg;base64,{base64.b64encode(img_jpeg).decode()}"}}
        ]}]}
    req = urllib.request.Request(BASE, data=json.dumps(payload).encode(),
        headers={"Content-Type":"application/json","User-Agent":UA})
    r = json.loads(urllib.request.urlopen(req, timeout=180).read().decode())
    return r['choices'][0]['message']['content']

DECIDE_SYS = """Bạn là QA driver cho app Android TV KênhLive (xem bóng đá, nền đen).
Đây là ảnh màn hình hiện tại. QUAN SÁT kỹ và chọn ĐÚNG 1 nút bấm có ý nghĩa nhất mà BẠN CHƯA thấy trong lịch sử (các label trước: ###HISTORY###).
Trả về JSON thuần, KHÔNG giải thích ngoài JSON:
{"x":<int>,"y":<int>,"label":"<tên nút ngắn tiếng Việt>","expect":"<điều sẽ xảy ra sau bấm, ngắn>","note":"<quan sát ngắn>"}
QUY TẮC CHỌN NÚT (quan trọng):
- Chỉ chọn nút THẬT SỰ BẤM ĐƯỢC: nút CTA (Xem ngay), card trận, tab (Trực tiếp/Lịch trình), mục trong dialog, nút phòng/BLV.
- KHÔNG chọn: dòng chữ trạng thái như "N phòng đang live", "đang tải", tiêu đề trận, tên giải, chữ trang trí KHÔNG có khung nút/viền nổi bật.
- Nếu 1 vùng là dòng text nằm trên nền, không có nền nút rõ ràng → coi là KHÔNG bấm được, đừng chọn.
- x,y phải nằm TRONG vùng nút (không chọn text kế bên).
- Nếu đã bấm hết nút đáng giá hoặc chỉ còn text trạng thái → trả {"done":true,"reason":"..."}."""

def decide(img, hist):
    vis = [h['label'] for h in hist[-8:]]
    # ưu tiên từ chối bấm lại các text trạng thái đã bấm nhầm
    sys = DECIDE_SYS.replace("###HISTORY###", "; ".join(vis) or "không có")
    raw = chat(sys, "Trả JSON duy nhất.", img)
    return raw

VERIFY_SYS = """Bạn là QA verifier cho app Android TV KênhLive. Ảnh TRƯỚC là trạng thái trước khi bấm nút X, ẢNH SAU là sau khi bấm.
Kỳ vọng khi bấm là: ###EXPECT###
Quan sát 2 ảnh và trả JSON thuần:
{"changed":true/false,"ok":true/false,"reason":"<vì sao>","crash_flag":true/false}
- ok=true nếu kết quả khớp kỳ vọng (mở màn mới/dialog/nội dung thay đổi).
- changed=false nếu màn hình gần như giống hệt trước → NÚT CHẾT (không phản hồi). Đây là bug.
- crash_flag=true nếu thấy màn hình trắng/đen, thoát về launcher, hoặc có thông báo lỗi."""

def verify(before, after, expect):
    if not before or not after: return {"ok":None,"changed":None,"reason":"không có ảnh"}
    sys = VERIFY_SYS.replace("###EXPECT###", expect)
    # gửi 2 ảnh trong 1 prompt
    payload = {"model": MODEL, "temperature": 0.1, "max_tokens": 300,
        "messages":[{"role":"user","content":[
            {"type":"text","text":sys},
            {"type":"image_url","image_url":{"url":f"data:image/jpeg;base64,{base64.b64encode(before).decode()}"}},
            {"type":"text","text":"[ẢNH TRƯỚC ^ / ẢNH SAU v]"},
            {"type":"image_url","image_url":{"url":f"data:image/jpeg;base64,{base64.b64encode(after).decode()}"}}
        ]}]}
    req = urllib.request.Request(BASE, data=json.dumps(payload).encode(),
        headers={"Content-Type":"application/json","User-Agent":UA})
    r = json.loads(urllib.request.urlopen(req, timeout=180).read().decode())
    return r['choices'][0]['message']['content']

def crash_count(baseline):
    cur = sh("adb logcat -d -s AndroidRuntime:E")
    nf = cur.count("FATAL EXCEPTION") - baseline.count("FATAL EXCEPTION")
    na = cur.count("ANR in " + PKG) - baseline.count("ANR in " + PKG)
    return nf + na, cur.replace(baseline, "")[-1200:]

def jclean(s):
    """bóc JSON khỏi chuỗi có thể kèm markdown."""
    m = re.search(r'\{.*\}', s, re.S)
    if not m: return ""
    try: return m.group(0)
    except: return ""

report = {"pkg":PKG,"model":MODEL,"steps":[],"dead_buttons":[],"crashes":[]}
launch(); time.sleep(3)
baseline = sh("adb logcat -d -s AndroidRuntime:E")
hist = []

for step in range(MAX_STEPS):
    if not is_focused():
        print(f"[{step}] mất focus → relaunch", flush=True); launch(); time.sleep(2)
    before, size = screenshot()
    if before is None:
        print(f"[{step}] screencap fail", flush=True); time.sleep(2); continue
    # quyết định
    try:
        raw = decide(before, hist)
        dec = json.loads(jclean(raw))
    except Exception as e:
        print(f"[{step}] decide err: {str(e)[:80]}", flush=True); time.sleep(2); continue
    if dec.get("done"):
        print("DONE:", dec.get("reason"), flush=True); report["done_reason"]=dec.get("reason"); break
    x, y = int(dec.get("x",0)), int(dec.get("y",0))
    label = dec.get("label","?"); expect = dec.get("expect","?"); note = dec.get("note","")
    if (x,y) == (0,0):
        print(f"[{step}] label rỗng tọa độ 0,0", flush=True); continue
    hist.append(dec)
    print(f"[{step}] tap '{label}' @ {x},{y} | expect: {expect[:34]}", flush=True)
    tap(x, y); time.sleep(3.5)
    after, _ = screenshot()
    fn = f"{OUT}/shots/step{step}.png"
    if after:
        try:
            Image.open(io.BytesIO(after)).save(fn)
        except Exception:
            pass
    # crash/ANR
    nc, tail = crash_count(baseline)
    vres = None
    try:
        vraw = verify(before, after, expect)
        vres = json.loads(jclean(vraw))
        print(f"   verify: {vres}", flush=True)
    except Exception as e:
        print(f"   verify err: {str(e)[:70]}", flush=True)
    step_rec = {"step":step,"label":label,"xy":[x,y],"expect":expect,"verify":vres,"note":note,"crash":nc>0}
    report["steps"].append(step_rec)
    if nc > 0:
        report["crashes"].append(step_rec); print("   *** CRASH/ANR ***", flush=True); break
    if vres and vres.get("changed") is False:
        report["dead_buttons"].append(step_rec)
        print("   *** NÚT CHẾT: bấm không đổi gì ***", flush=True)
    back(); time.sleep(1.5)
    if not is_focused():
        launch(); time.sleep(2)

json.dump(report, open(f"{OUT}/report.json","w"), ensure_ascii=False, indent=2)
print("=== AI QA DONE === steps:", len(report["steps"]),
      "dead:", len(report["dead_buttons"]), "crashes:", len(report["crashes"]), flush=True)
