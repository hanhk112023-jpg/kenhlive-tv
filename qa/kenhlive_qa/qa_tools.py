"""KenhLive QA — toolset cho AI vision judge (agentic loop kiểu benchmark V*:
model tự cầm tool crop/zoom/đo màu thay vì đoán 1-shot).
Mọi tool chạy local bằng PIL/adb, không tốn token, timeout ngắn, lỗi → trả text mô tả
để model biết đường xử tiếp (không exception ra ngoài)."""
import io, json, os, re

try:
    from PIL import Image
except Exception:
    Image = None

IMG_EXT = ('.png', '.jpg', '.jpeg')
_zoom_seq = [0]

def _out_dir():
    d = os.environ.get('QA_TOOLS_OUT', '/tmp/qa_report/tools')
    os.makedirs(d, exist_ok=True)
    return d

def _load(a):
    im = Image.open(a['path']).convert('RGB')
    return im

COLORS = {'red': (255, 59, 48), 'white': (255, 255, 255), 'black': (0, 0, 0), 'yellow': (255, 184, 0)}

def _is_c(px, rgb, tol):
    return all(abs(px[i] - rgb[i]) <= tol for i in range(3))

# ---------------- tool implementations (trả dict text + ảnh đính kèm) ----------------

def t_zoom(a):
    im = _load(a)
    W, H = im.size
    x = max(0, min(int(a.get('x', 0)), W - 2)); y = max(0, min(int(a.get('y', 0)), H - 2))
    w = max(4, min(int(a.get('w', 120)), W - x)); h = max(4, min(int(a.get('h', 120)), H - y))
    s = max(1, min(float(a.get('scale', 3)), 8))
    c = im.crop((x, y, x + w, y + h))
    c = c.resize((int(c.width * s), int(c.height * s)), Image.LANCZOS)
    p = f"{_out_dir()}/zoom_{_zoom_seq[0]}.png"; _zoom_seq[0] += 1
    c.save(p)
    return {"text": f"Đã crop ({x},{y},{w}x{h}) và zoom {s}x từ ảnh {W}x{H} -> {c.size}. Xem ảnh đính kèm; chi tiết trong ảnh zoom = gấp {s} lần px gốc.", "image": p}

def t_pixel(a):
    im = _load(a)
    x, y = int(a['x']), int(a['y'])
    if not (0 <= x < im.width and 0 <= y < im.height): return {"text": f"({x},{y}) ngoài ảnh {im.size}"}
    return {"text": f"pixel({x},{y}) RGB = {im.load()[x, y]}"}

def t_scan_color(a):
    """Tìm các dòng/cột chứa nhiều pixel màu chỉ định -> phát hiện viền/đường thẳng."""
    im = _load(a); W, H = im.size; px = im.load()
    rgb = COLORS.get(str(a.get('color', 'red')).lower()) or tuple(json.loads(a['color']))
    tol = int(a.get('tolerance', 60)); minr = float(a.get('ratio', 0.35))
    rows = [y for y in range(0, H, 2) if sum(_is_c(px[x, y], rgb, tol) for x in range(0, W, 3)) > (W // 3) * minr]
    cols = [x for x in range(0, W, 2) if sum(_is_c(px[x, y], rgb, tol) for y in range(0, H, 3)) > (H // 3) * minr]
    def group(v):
        g = []; s = None; p = None
        for n in v:
            if s is None: s = p = n
            elif n - p <= 6: p = n
            else: g.append((s, p)); s = p = n
        if s is not None: g.append((s, p))
        return g
    hint = []
    if rows: hint.append(f"Đo ĐỘ DÀY dải ngang tại {rows[0]}: goi measure(axis='x', pos=<cột cắt ngang dải vd {W//2}>)")
    if cols: hint.append(f"Đo ĐỘ DÀY dải dọc tại {cols[0]}: goi measure(axis='y', pos=<hàng cắt dọc dải vd {H//2}>)")
    return {"text": f"màu {rgb} tol {tol}: các HÀNG ngang chứa >{minr:.0%} rộng: {group(rows)}; các CỘT dọc chứa >{minr:.0%} cao: {group(cols)} (toạ độ px gốc, bước quét 2px). " + " | ".join(hint)}

def t_measure(a):
    """Độ dày dải màu tại 1 dòng/cột cụ thể (sau khi scan_color biết nó nằm đâu)."""
    im = _load(a); W, H = im.size; px = im.load()
    rgb = COLORS.get(str(a.get('color', 'red')).lower()) or tuple(json.loads(a['color']))
    tol = int(a.get('tolerance', 60))
    axis = a.get('axis', 'y'); pos = int(a.get('pos', H // 2))
    rng = range(W) if axis == 'y' else range(H)
    runs = []; c = 0
    for i in rng:
        q = px[i, pos] if axis == 'y' else px[pos, i]
        if _is_c(q, rgb, tol): c += 1
        elif c: runs.append(c); c = 0
    if c: runs.append(c)
    return {"text": f"độ dài dải màu liên tiếp tại {axis}={pos}: {runs} px (0 = không thấy)"}

def t_diff(a):
    """% khác biệt pixel giữa 2 ảnh (kiểm tra màn hình có đổi sau khi bấm phím)."""
    im1 = _load(a); im2 = Image.open(a['path2']).convert('RGB')
    if im2.size != im1.size: im2 = im2.resize(im1.size)
    p1, p2 = im1.load(), im2.load(); W, H = im1.size
    n = sum(1 for y in range(0, H, 3) for x in range(0, W, 3) if sum(abs(p1[x, y][i] - p2[x, y][i]) for i in range(3)) > 40)
    tot = len(range(0, H, 3)) * len(range(0, W, 3))
    return {"text": f"pixel-diff = {100.0 * n / tot:.2f}% (0% = 2 ảnh giống hệt)"}

def t_logcat(a):
    try:
        import subprocess
        r = subprocess.run("adb logcat -d -v brief", shell=True, capture_output=True, text=True, timeout=25)
        lines = [l for l in (r.stdout or '').splitlines() if re.search(str(a.get('pattern', 'E/|FATAL|Exception')), l)]
        return {"text": "logcat khớp pattern (100 dòng cuối):\n" + "\n".join(lines[-100:]) or '(không dòng nào khớp)'}
    except Exception as e:
        return {"text": f"logcat không khả dụng: {str(e)[:80]}"}

def t_ui_dump(a):
    """Cấu trúc UI thật qua uiautomator: text + bounds + focused — đọc chính xác hơn OCR."""
    try:
        import subprocess, tempfile
        adb = 'adb' + (f" -s {a['serial']}" if a.get('serial') else '')
        subprocess.run(f"{adb} shell uiautomator dump /sdcard/ui.xml", shell=True, capture_output=True, timeout=25)
        xml = subprocess.run(f"{adb} shell cat /sdcard/ui.xml", shell=True, capture_output=True, text=True, timeout=25).stdout
        nodes = re.findall(r'<node[^>]*>', xml or '')
        keep = []
        for n in nodes:
            txt = re.search(r'text="([^"]*)"', n); desc = re.search(r'content-desc="([^"]*)"', n)
            cls = re.search(r'class="([^"]*)"', n); b = re.search(r'bounds="([^"]*)"', n)
            f_ = 'F' if 'focused="true"' in n else ' '
            label = (txt.group(1) if txt and txt.group(1) else (desc.group(1) if desc else ''))[:60]
            if label or f_ == 'F':
                keep.append(f"{f_} {(cls.group(1).split('.')[-1] if cls else '?'):18} {b.group(1) if b else '':26} {label}")
        return {"text": ("UI (F=focused):\n" + "\n".join(keep[:120])) if keep else '(uiautomator không trả node có text/focus)'}
    except Exception as e:
        return {"text": f"ui_dump không khả dụng: {str(e)[:80]}"}

TOOLS = {
    'zoom':        (t_zoom,    "Crop vùng (x,y,w,h) của ảnh rồi phóng to `scale` lần (LANCZOS) để đọc chi tiết mảnh (viền, chữ nhỏ, icon). Ảnh zoom sẽ được gửi lại cho bạn."),
    'pixel':       (t_pixel,   "Đọc giá trị RGB chính xác của 1 pixel (x,y)."),
    'scan_color':  (t_scan_color, "Quét ảnh tìm các hàng/cột chứa chủ yếu 1 màu ('red'|'white'|'black'|'yellow'|[r,g,b]); tol dung sai; ratio ngưỡng 0..1. Dùng định vị viền/đường kẻ."),
    'measure':     (t_measure, "Đo ĐỘ DÀY px của 1 đường viền. axis='x': quét DỌC theo cột pos (dùng đo viền NGANG — pos chọn là 1 hàng giữa viền, vd dòng quét qua mép trên video). axis='y': quét NGANG theo hàng pos (đo viền DỌC). Kết quả = list độ dài các đoạn màu liên tiếp theo px."),
    'diff':        (t_diff,    "So 2 screenshot (path + path2), trả % pixel khác nhau — xác nhận màn hình có đổi sau thao tác."),
    'logcat':      (t_logcat,  "100 dòng logcat gần nhất khớp regex pattern (mặc định E/|FATAL|Exception)."),
    'ui_dump':     (t_ui_dump, "UI hierarchy thật của thiết bị đang adb kết nối: class, bounds, text, focus. Đọc text/nút chính xác thay vì OCR."),
}

SCHEMAS = [{"type": "function", "function": {
    "name": k, "description": v[1],
    "parameters": {"type": "object", "properties": {
        "path": {"type": "string", "description": "đường dẫn file ảnh (bắt đầu từ danh sách ảnh được cấp)"},
        "path2": {"type": "string", "description": "ảnh thứ 2 (chỉ tool diff)"},
        "x": {"type": "integer"}, "y": {"type": "integer"}, "w": {"type": "integer"}, "h": {"type": "integer"},
        "scale": {"type": "number", "description": "1..8, mặc định 3"},
        "axis": {"type": "string", "enum": ["x", "y"]}, "pos": {"type": "integer"},
        "color": {"type": "string", "description": "red|white|black|yellow hoặc '[r,g,b]'"},
        "tolerance": {"type": "integer"}, "ratio": {"type": "number"},
        "pattern": {"type": "string"}, "serial": {"type": "string"}},
        "required": ["path"] if k in ('zoom', 'pixel', 'scan_color', 'measure', 'diff') else []},
}} for k, v in TOOLS.items()]
for t in SCHEMAS:
    if t['function']['name'] == 'diff':
        t['function']['parameters']['required'] = ['path', 'path2']
    if t['function']['name'] in ('logcat', 'ui_dump'):
        t['function']['parameters']['properties'] = {
            'pattern': {'type': 'string'}, 'serial': {'type': 'string'}}

def run(name, args):
    """-> {'text':…, 'image': path|None}. Không bao giờ raise."""
    fn = TOOLS.get(name)
    if not fn: return {"text": f"tool '{name}' không tồn tại"}
    if Image and isinstance(args, dict) and args.get('path') and not os.path.isfile(args['path']):
        return {"text": f"không tìm thấy file {args['path']} — dùng đúng đường dẫn được cấp"}
    try:
        return fn[0](args or {})
    except Exception as e:
        return {"text": f"tool {name} lỗi: {str(e)[:120]}"}
