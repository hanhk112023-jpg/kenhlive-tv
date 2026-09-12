"""KenhLive QA — AI vision judge.
Chính: Kilo gateway — nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free (vision + reasoning, free).
Fallback: glm-5.3-flash qua llm-key-proxy.
Chấm từng screenshot theo rubric UX Android TV + đọc logcat tìm lỗi ẩn. Trả về finding có severity + gợi ý."""
import base64, json, os, re, time, urllib.request

BASE = os.environ.get('QA_PROXY', 'https://llm-key-proxy.htuananh153.workers.dev/v1/chat/completions')
KEY  = os.environ.get('QA_PROXY_KEY', 'anhdz')
UA   = 'curl/8.5.0'

KILO_BASE = os.environ.get('KILO_API_BASE', 'https://api.kilo.ai/api/gateway/v1/chat/completions')
KILO_KEY  = os.environ.get('KILO_API_KEY', '')
KILO_MODELS_URL = KILO_BASE.rsplit('/v1/',1)[0] + '/v1/models'
# model vision ưu tiên. Kilo hay đổi danh sách free trong ngày
# → resolve động từ /v1/models mỗi phiên, cache tại chỗ; env KILO_MODEL vẫn override được.
PREF = ['stepfun/step-3.7-flash:free',
        'nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free',
        'nvidia/nemotron-3-ultra-550b-a55b:free',
        'nvidia/nemotron-3-super-120b-a12b:free',
        'thinkingmachines/inkling:free',
        'nex-agi/nex-n2.5-pro:free',
        'stepfun/step-3.7-flash:free']
_model_cache = None

def kilo_vision_models():
    """[model…] còn sống trên Kilo lúc này, theo thứ tự ưu tiên; lỗi → trả PREF."""
    global _model_cache
    if _model_cache: return _model_cache
    try:
        req = urllib.request.Request(KILO_MODELS_URL,
            headers={'Authorization':'***' + KILO_KEY, 'User-Agent': UA})
        data = json.loads(urllib.request.urlopen(req, timeout=25).read().decode()).get('data', [])
        byid = {m.get('id'): m for m in data}
        def has_vision(m):
            a = m.get('architecture') or {}
            mods = a.get('input_modalities') or a.get('modality') or []
            if isinstance(mods, str): mods = [mods]
            return any('image' in str(x) for x in mods)
        free_vision = [mid for mid, m in byid.items() if m.get('isFree') and has_vision(m)]
        cands = [m for m in PREF if m in byid and has_vision(byid[m])]
        # ưu tiên :free trước, model free vision khác nối尾部
        cands += [m for m in free_vision if m not in cands]
        _model_cache = cands or PREF
    except Exception as e:
        print(f'  (kilo /models fail: {str(e)[:50]} → dùng PREF)', flush=True)
        _model_cache = PREF
    return _model_cache

def _msgs(prompt, imgs):
    content = [{"type": "text", "text": prompt}]
    for b in (imgs or []):
        content.append({"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{base64.b64encode(b).decode()}"}})
    return [{"role": "user", "content": content}]

def _post(url, key, payload, timeout):
    req = urllib.request.Request(url, data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + key, "User-Agent": UA})
    for attempt in range(3):
        try:
            r = json.loads(urllib.request.urlopen(req, timeout=timeout).read().decode())
        except Exception:
            if attempt == 2: raise
            time.sleep(3 * (attempt + 1))
            continue
        return (r['choices'][0]['message'].get('content') or '').strip()
    return ''

def _chat_kilo(prompt, imgs=None, max_tokens=4000, temperature=0.1, timeout=170):
    # model reasoning: completion tokens gồm cả reasoning nháp → để max_tokens rộng
    env = os.environ.get('KILO_MODEL')
    cands = [env] if env else kilo_vision_models()
    last = None; t0 = time.time()
    for m in cands[:3]:
        left = timeout - (time.time() - t0)
        if left < 20: break          # hết ngân sách thời gian -> trả về cho fallback glm
        try:
            return _post(KILO_BASE, KILO_KEY, {"model": m, "temperature": temperature,
                "max_tokens": max_tokens, "messages": _msgs(prompt, imgs)}, int(left))
        except Exception as e:
            last = e
            print(f'  (kilo {m[:40]} fail: {str(e)[:50]} → model next)', flush=True)
    raise last or Exception('kilo hết model/thời gian thử')

def _chat(prompt, imgs=None, max_tokens=8000, temperature=0.1, timeout=170):
    """Kilo (tự dò model vision free còn sống, rotate 3 con) → glm fallback."""
    if KILO_KEY:
        try:
            return _chat_kilo(prompt, imgs, max_tokens=max_tokens, temperature=temperature, timeout=timeout)
        except Exception as e:
            print(f'  (kilo judge fail hết candidate: {str(e)[:60]} → glm fallback)', flush=True)
    return _post(BASE, KEY, {"model": 'glm-5.3-flash', "temperature": temperature,
        "max_tokens": max_tokens, "reasoning_effort": "low",
        "messages": _msgs(prompt, imgs)}, timeout)

def detail_imgs(jpeg):
    """Mô phỏng Python-tool của benchmark V*: tự crop+zoom vùng chi tiết mảnh
    (viền focus đỏ) trước khi gửi model → nemotron đọc đúng độ dày/màu thay vì suy đoán.
    Trả [] nếu PIL lỗi hoặc không thấy viền đỏ."""
    try:
        import io
        from PIL import Image
        im = Image.open(io.BytesIO(jpeg)).convert('RGB')
        W, H = im.size; px = im.load()
        def isred(q): return q[0] > 170 and q[1] < 110 and q[2] < 120 and q[0] - max(q[1], q[2]) > 70
        def iswhite(q): return q[0] > 225 and q[1] > 225 and q[2] > 225
        # vien trang hien hanh: duong ngang doc mong >80% chieu (khong bat noi dung trang rong)
        rows = [y for y in range(0, H, 2) if sum(isred(px[x, y]) for x in range(0, W, 3)) > (W//3) * 0.10]
        cols = [x for x in range(0, W, 2) if sum(isred(px[x, y]) for y in range(0, H, 3)) > (H//3) * 0.10]
        wrows = [y for y in range(0, H, 1) if sum(iswhite(px[x, y]) for x in range(0, W, 2)) > (W//2) * 0.80 and (y == 0 or not iswhite(px[0, y-1]))]
        if not rows and not cols and wrows: rows = wrows[:6]
        out = []
        f = lambda img: (lambda b: (img.save(b, 'JPEG', quality=85), b.getvalue())[1])(io.BytesIO())
        if rows:  # dải viền ngang → phóng 2x
            y0, y1 = max(0, rows[0]-90), min(H, rows[-1]+90+1)
            c = im.crop((0, y0, W, y1)); c = c.resize((c.width*2, c.height*2), Image.LANCZOS)
            out.append(f(c))
        if cols:  # dải viền dọc
            x0, x1 = max(0, cols[0]-90), min(W, cols[-1]+90+1)
            c = im.crop((x0, 0, x1, H)); c = c.resize((int(c.width*2.5), int(c.height*1.2)), Image.LANCZOS)
            out.append(f(c))
        return out[:2]
    except Exception:
        return []

def _jclean(s):
    m = re.search(r'\[.*\]|\{.*\}', s, re.S)
    if not m: return None
    try: return json.loads(m.group(0))
    except Exception: return None

JUDGE_SYS = """Bạn là chuyên gia QA/UX Android TV đánh giá app IPTV "KênhLive" (bóng đá trực tiếp, nền ĐEN THUẦN #000, accent đỏ #FF3B30, vàng #FFB800).
Tiêu chí bắt buộc kiểm trên ẢNH MÀN HÌNH TV:
1. CRASH/BLANK: màn hình trắng/đen toàn bộ, lỗi Android, văng launcher → severity CRITICAL
2. NỘI DUNG: có trận đấu/BLV hiển thị không? "Đang tải" treo >vài phút = HIGH. Danh sách rỗng = HIGH
3. TEXT: chữ Việt có dấu hiển thị đúng (không ô vuông/tofu), không tràn/cắt chữ, không chồng lấn → MEDIUM/HIGH
4. LAYOUT: card đều nhau, ảnh avatar tròn đúng, logo đội hiển thị (ảnh vỡ/màu lạ = MEDIUM), khoảng cách chuẩn 10-foot
5. ĐIỀU KHIỂN TV: focus highlight rõ ràng trên D-pad (viền sáng), nút bấm được phân biệt rõ với text thường
6. CHẤT LƯỢNG: ảnh mờ/kéo giãn/nứt = MEDIUM
QUY TẮC NGUỒN (RẤT QUAN TRỌNG — tránh báo oan):
- App này phát stream IPTV từ nguồn thứ 3. Mọi thứ BAKE TRONG VIDEO (quảng cáo, watermark, số điện thoại/Zalo/Telegram, màn hình chờ phòng BLV, danh sách chat, "phòng đang tắt", chất lượng video mờ/thấp) là NỘI DUNG NGUỒN PHÁT, KHÔNG phải lỗi app → tối đa INFO, KHÔNG được báo CRITICAL/HIGH.
- "Màn hình đen" nếu có viền letterbox + dải video ở giữa = player đang phát bình thường, KHÔNG phải blank.
- Số card/section ÍT (1-2 card mỗi hàng, nhiều khoảng trống) khi khung giờ ít trận live = ĐÚNG hành vi, KHÔNG phải lỗi layout. Chỉ báo khi có ≥3 trận mà vẫn xếp lệch.
- Chữ cắt cụt nằm BÊN TRONG ảnh thumbnail/video (chữ meme bake sẵn) = nguồn phát, không phải text của app.
- ẢNH ĐẠI DIỆN TRẬN/BLV (avatar, cover, logo đội trong card) là data từ API nguồn — người thật/logo lạ/sai đội = NGUỒN CUNG CẤP, tối đa INFO.
- Ô FOCUS được nhận diện bằng: phóng to ~1.03–1.07 lần + viền TRẮNG mỏng 2–3dp kèm GLOW trắng mờ bao ngoài (thiết kế v6 'ring+glow', KHÔNG phải stroke 5px) + nhô elevation; multiview vẫn viền trắng 5px. KHÔNG còn viền đỏ. Không báo 'không có focus'/'viền quá mảnh'/'thiếu viền trắng 5px' nếu thấy đường kẻ trắng liền mạch + quầng sáng quanh ô; ảnh burned-in trên cover BLV (chữ vàng cắt đỉnh...) là DATA NGUỒN -> INFO, không phải lỗi layout.
- Video IPTV mờ/thấp nét = chất lượng nguồn phát → INFO, không phải lỗi app.
- Chính tả/dấu tiếng Việt: ảnh đã co nhỏ, RẤT DỄ đọc nhầm 'trận'↔'trang', 'i'↔'l'. CHỈ báo lỗi text khi chắc chắn nhìn rõ từng ký tự; nghi ngờ → bỏ qua.
- CHỈ báo lỗi app ở vùng UI của app: layout, text overlay của app, nút bấm, tab, focus, dialog, danh sách card.
Trả JSON thuần (mảng, không markdown):
[{"area":"<màn hình/element>","severity":"CRITICAL|HIGH|MEDIUM|LOW","issue":"<vấn đề cụ thể quan sát thấy>","evidence":"<bằng chứng nhìn thấy trong ảnh>","suggestion":"<gợi ý sửa cụ thể>"}]
Nếu màn hình hoàn hảo trả []. CHỈ báo lỗi NHÌN THẤY thật trong ảnh, không đoán mò."""

def judge_screen(label, jpeg):
    """AI chấm 1 screenshot → list findings."""
    try:
        imgs = [jpeg] + detail_imgs(jpeg)[:1]   # chỉ+n 1 ảnh zoom — 2 ảnh làm latency nhân đôi
        extra = " (Ảnh sau là vùng viền focus đã CROP+ZOOM — soi chi tiết bằng ảnh đó, đừng đoán từ ảnh nhỏ.)" if len(imgs) > 1 else ""
        raw = _chat(JUDGE_SYS + f"\n\nĐây là màn hình '{label}' của app. Chấm theo rubric." + extra, imgs, max_tokens=6000, timeout=320)
        f = _jclean(raw)
        return f if isinstance(f, list) else []
    except Exception as e:
        return [{"area": label, "severity": "LOW", "issue": f"AI judge lỗi: {str(e)[:80]}", "evidence": "", "suggestion": "thử lại"}]

LOG_SYS = """Bạn là chuyên gia Android đọc logcat app IPTV (ExoPlayer, OkHttp, Kotlin coroutines).
Dưới đây là logcat MỚI phát sinh trong phiên QA. Tìm các vấn đề CHẨN ĐOÁN ĐƯỢC: crash ẩn, ANR, memory leak (GC liên tục), network fail lặp lại, ExoPlayer error, StrictMode, exception không bắt được.
Trả JSON thuần (mảng): [{"area":"<component>","severity":"CRITICAL|HIGH|MEDIUM|LOW","issue":"<vấn đề>","evidence":"<dòng log chứng minh>","suggestion":"<cách sửa>"}]
Không có gì đáng báo động trả []. Không bịa lỗi từ log bình thường."""

def judge_logcat(log_text):
    """AI đọc logcat → findings."""
    if not log_text or len(log_text) < 50: return []
    # cắt còn phần đáng chú ý
    keep = [l for l in log_text.splitlines() if re.search(r'E/|W/|FATAL|ANR|Exception|error|Error|timeout|OOM|GC', l)]
    txt = "\n".join(keep[-400:])[:14000]
    if not txt.strip(): return []
    try:
        raw = _chat(LOG_SYS + "\n\nLOGCAT:\n" + txt, max_tokens=800)
        f = _jclean(raw)
        return f if isinstance(f, list) else []
    except Exception:
        return []

PERF_SYS = """Bạn là chuyên gia hiệu năng Android TV. Đây là số liệu đo được của app KênhLive qua các màn hình.
Phân tích: cold start (ms), jank %, PSS memory (KB), pixel-diff theo thời gian (auto-refresh).
Ngưỡng: cold start >8000ms = HIGH; jank >15% = HIGH, >30% = CRITICAL; PSS >400MB = HIGH; auto-refresh diff <0.5% = HIGH (không refresh).
Trả JSON thuần (mảng): [{"area":"...","severity":"...","issue":"...","evidence":"số liệu","suggestion":"..."}]. Không vấn đề trả []."""

def judge_perf(metrics):
    try:
        raw = _chat(PERF_SYS + "\n\nSỐ LIỆU:\n" + json.dumps(metrics, ensure_ascii=False)[:6000], max_tokens=600)
        f = _jclean(raw)
        return f if isinstance(f, list) else []
    except Exception:
        return []


# ================= AGENTIC JUDGE (model cam tool crop/zoom/measure) =================
# Y tuong benchmark V*: thay thay 1-shot, cho model TU vong lap: goi tool -> nhan ket qua
# (text + anh zoom) -> do lai. Tool chay local (qa_tools.py), mien phi, khong ton token upstream.
import qa_tools

AGENT_SYS = JUDGE_SYS + """

BAN CO QUYEN DUNG TOOL tren file anh duoc cap (duong dan trong tin nhan):
- zoom: cắt + phóng to vùng chứa viền/đường kẻ/chữ nhỏ TRƯỚC khi kết luận về nó
- scan_color / measure: định lượng độ dày (px) cua vien mau — dùng số đo, không ước lượng bằng mắt
- pixel: kiểm tra màu thật cua 1 diem
- diff: kiem tra 2 anh giong nhau hay khac (ảnh nhận "không đổi sau khi bấm")
- ui_dump / logcat: neu duoc cap thiet bi — doc text/focus that thay vi OCR
Quy trinh bat buoc khi danh gia vien/duong ke/chu nho: scan_color -> measure (hoac zoom scale>=4 đọc ảnh) -> RỒI mới kết luận JSON.
KET LUAN CUOI CUNG bat buoc la MANG JSON (kieu [{"area":...}]) — kh phai object don. Tối đa 5 lượt tool. Neu tool loi 2 lan cung loai -> ket luan bang mat va ghi ro "khong do duoc"."""

def _coerce(x):
    """dict bất kỳ (model hay trả key tiếng Việt/khác schema) -> finding chuẩn 5 key."""
    if not isinstance(x, dict): return None
    if x.get('issue'):
        return {"area": str(x.get("area",""))[:80], "severity": str(x.get("severity","LOW")).upper() if str(x.get("severity","")).upper() in ("CRITICAL","HIGH","MEDIUM","LOW","INFO") else "LOW",
                "issue": str(x["issue"])[:300], "evidence": str(x.get("evidence",""))[:300], "suggestion": str(x.get("suggestion",""))[:300]}
    # khong co 'issue': lap len chu + gia tri thanh text
    txt = "; ".join(f"{k}={v}" for k, v in x.items() if isinstance(v, (str, int, float, list)))
    if not txt: return None
    return {"area": str(x.get("area", x.get("khu_vuc", "tool-result")))[:80], "severity": "INFO",
            "issue": txt[:300], "evidence": "đo bằng tool", "suggestion": ""}

def _norm_findings(f):
    """list→list chuẩn hoá; dict→wrap (Step hay tra {'ket_luan':{...}}); khac→None."""
    if isinstance(f, list):
        out = [_coerce(x) for x in f]; out = [x for x in out if x]
        return out or None
    if isinstance(f, dict):
        inner = f.get('ket_luan') or f.get('findings') or f.get('result') or f.get('ketluan')
        if isinstance(inner, list): return _norm_findings(inner)
        if isinstance(inner, dict): return [_coerce(inner)] or None
        c = _coerce(f)
        return [c] if c else None
    return None

def judge_screen_agent(label, png_path, jpeg=None, max_rounds=6, timeout=None):
    """Judge agentic: tra (findings, notes). jpeg = anh toan man hinh goi y (model thay
    tong the roi moi dung tool do chi tiet). Loai model chet -> []."""
    timeout = timeout or int(os.environ.get('QA_AGENT_TIMEOUT', '260'))
    cands = ([os.environ['KILO_MODEL']] if os.environ.get('KILO_MODEL') else kilo_vision_models())[:2]
    first = [{"type": "text", "text": f"Đây là màn hình '{label}' của app. Ảnh gốc để tool đo: {png_path}. Chấm theo rubric hệ thống; khi xong trả MẢNG JSON findings (rỗng [] nếu không có lỗi app)."}]
    if jpeg:
        first.append({"type": "image_url", "image_url": {"url": "data:image/jpeg;base64," + base64.b64encode(jpeg).decode()}})
    messages = [{"role": "system", "content": AGENT_SYS}, {"role": "user", "content": first}]
    notes = []
    t0 = time.time()
    for model in cands:
        try:
            msgs = list(messages); used = 0; img_files = []
            while used <= max_rounds:
                if time.time() - t0 > timeout: raise Exception(f'het ngan sach {timeout}s')
                payload = {"model": model, "temperature": 0.1, "max_tokens": 6000, "messages": msgs,
                           "tools": qa_tools.SCHEMAS, "tool_choice": "auto"}
                out = _post_obj(KILO_BASE, KILO_KEY, payload, min(150, int(timeout - (time.time() - t0)) + 10))
                tc = out.get('tool_calls') or []
                if not tc:
                    f = _norm_findings(_jclean(out.get('content') or ''))
                    if f is not None:
                        return f, notes + [f'agent={model.split("/")[1][:24]} rounds={used}']
                    notes.append(f'{model[:24]}: tra loi khong phai findings (round {used})')
                    break
                used += 1
                msgs.append({"role": "assistant", "content": out.get('content') or "", "tool_calls":
                    [{"id": t.get('id') or f"c{i}", "type": "function",
                      "function": {"name": t['function']['name'], "arguments": t['function']['arguments']}}
                     for i, t in enumerate(tc)]})
                for i, t in enumerate(tc):
                    try:
                        args = json.loads(t['function'].get('arguments') or '{}')
                    except Exception:
                        args = {}
                    r = qa_tools.run(t['function']['name'], args)
                    content = [{"type": "text", "text": r['text']}]
                    if r.get('image'):
                        b64 = base64.b64encode(open(r['image'], 'rb').read()).decode()
                        content.append({"type": "image_url", "image_url": {"url": f"data:image/png;base64,{b64}"}})
                        img_files.append(r['image'])
                    msgs.append({"role": "tool", "tool_call_id": t.get('id') or f"c{i}", "content": content})
            # het vong tool -> ep ket luan JSON khong cho goi tool nua
            try:
                msgs.append({"role": "user", "content": "Bạn đã dùng hết lượt tool. KHÔNG gọi tool nữa — trả MẢNG JSON kết luận NGAY dựa trên số đo đã nhận được (các vòng đỏ/viền đã đo bằng measure, không đoán bằng mắt)."})
                out = _post_obj(KILO_BASE, KILO_KEY, {"model": model, "temperature": 0.1,
                    "max_tokens": 4000, "messages": msgs}, 90)
                f = _norm_findings(_jclean(out.get('content') or ''))
                if f is not None:
                    return f, notes + [f'agent={model.split("/")[1][:24]} forced-after-{max_rounds}']
            except Exception as e:
                pass
            notes.append(f'{model[:28]}: dung tool {max_rounds} luot van chua ket luan')
        except Exception as e:
            notes.append(f'{model[:28]} fail: {str(e)[:70]}')
    return [], notes

def _post_obj(url, key, payload, timeout):
    """Nhu _post nhung tra nguyen message dict (giu tool_calls)."""
    req = urllib.request.Request(url, data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json", "Authorization": "***" + key, "User-Agent": UA})
    for attempt in range(2):
        try:
            r = json.loads(urllib.request.urlopen(req, timeout=timeout).read().decode())
            if 'error' in r: raise Exception(str(r['error'])[:100])
            return r['choices'][0]['message']
        except Exception:
            if attempt: raise
            time.sleep(3)
    return {}
