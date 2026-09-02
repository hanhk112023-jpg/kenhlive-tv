"""KenhLive QA — AI vision judge qua llm-key-proxy (model vision, key anhdz).
Chấm từng screenshot theo rubric UX Android TV + đọc logcat tìm lỗi ẩn. Trả về finding có severity + gợi ý."""
import base64, json, os, re, time, urllib.request

BASE = os.environ.get('QA_PROXY', 'https://llm-key-proxy.htuananh153.workers.dev/v1/chat/completions')
KEY  = os.environ.get('QA_PROXY_KEY', 'anhdz')
UA   = 'curl/8.5.0'

def _chat(model, prompt, imgs=None, max_tokens=2500, temperature=0.1, timeout=180):
    content = [{"type": "text", "text": prompt}]
    for b in (imgs or []):
        content.append({"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{base64.b64encode(b).decode()}"}})
    payload = {"model": model, "temperature": temperature, "max_tokens": max_tokens,
               "messages": [{"role": "user", "content": content}]}
    req = urllib.request.Request(BASE, data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + KEY, "User-Agent": UA})
    for attempt in range(3):
        try:
            r = json.loads(urllib.request.urlopen(req, timeout=timeout).read().decode())
        except Exception as e:
            # 502/503/524 (provider blip) hoặc timeout → backoff rồi thử lại
            if attempt == 2: raise
            time.sleep(3 * (attempt + 1))
            continue
        c = (r['choices'][0]['message'].get('content') or '').strip()
        if c: return c
        # reasoning model có thể trả rỗng khi hết token → retry với budget gấp đôi
        payload['max_tokens'] = max(payload['max_tokens'] * 2, 4000)
        req = urllib.request.Request(BASE, data=json.dumps(payload).encode(),
            headers={"Content-Type": "application/json", "Authorization": "Bearer " + KEY, "User-Agent": UA})
    return ''

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
Trả JSON thuần (mảng, không markdown):
[{"area":"<màn hình/element>","severity":"CRITICAL|HIGH|MEDIUM|LOW","issue":"<vấn đề cụ thể quan sát thấy>","evidence":"<bằng chứng nhìn thấy trong ảnh>","suggestion":"<gợi ý sửa cụ thể>"}]
Nếu màn hình hoàn hảo trả []. CHỈ báo lỗi NHÌN THẤY thật trong ảnh, không đoán mò."""

def judge_screen(label, jpeg):
    """AI chấm 1 screenshot → list findings."""
    try:
        raw = _chat('deepseek-v4-flash-vision-exp', JUDGE_SYS + f"\n\nĐây là màn hình '{label}' của app. Chấm theo rubric.", [jpeg])
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
        raw = _chat('deepseek-v4-flash-vision-exp', LOG_SYS + "\n\nLOGCAT:\n" + txt, max_tokens=800)
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
        raw = _chat('deepseek-v4-flash-vision-exp', PERF_SYS + "\n\nSỐ LIỆU:\n" + json.dumps(metrics, ensure_ascii=False)[:6000], max_tokens=600)
        f = _jclean(raw)
        return f if isinstance(f, list) else []
    except Exception:
        return []
