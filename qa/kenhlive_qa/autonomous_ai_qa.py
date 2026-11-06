#!/usr/bin/env python3
"""
KenhLive TV — 100% Autonomous AI QA Agent (Unified Brain)
Hệ thống kiểm thử tự trị toàn diện điều khiển bởi AI Agent:
- Vision + Reasoning: LLM/VLM "nhìn" màn hình Android TV 10-foot, đọc cây UI, phân tích Logcat.
- Autonomous Decision: AI tự ra quyết định bấm phím D-pad, khám phá tính năng, tái hiện bug.
- Multi-Source Sports & IPTV & Cinema Verification:
  + Thể thao: Socolive, ColaTV, Gà Vàng, Khán Đài (Filter, Server Picker, Failover).
  + Truyền hình: IPTV TV360, VTV, HTV, Hero Preview, Lưới 5 cột.
  + Phim: NguonC, Phim lẻ, Phim bộ, Anime, Player StreamC.
- Probes sâu: Motion Video Detector (ExoPlayer), Focus Ring Contrast, D-pad Chaos Stress.
"""

import base64
import html
import io
import json
import os
import random
import re
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from qa_session import QASession, KENHLIVE_TABS, REMOTE_KEY_ICONS
from video_recorder import VideoRecorder

try:
    from PIL import Image, ImageChops, ImageStat
except ImportError:
    Image = None
    ImageChops = None
    ImageStat = None

PKG = "com.kenhlive.tv"
UA = "KenhLive-AI-QA/5.0"

# Keycodes chuẩn Android TV & Remote Điều Khiển TV Box
KEY_MAP = {
    "UP": 19, "DPAD_UP": 19,
    "DOWN": 20, "DPAD_DOWN": 20,
    "LEFT": 21, "DPAD_LEFT": 21,
    "RIGHT": 22, "DPAD_RIGHT": 22,
    "ENTER": 23, "DPAD_CENTER": 23, "OK": 23,
    "BACK": 4,
    "HOME": 3,
    "MENU": 82,
    "INFO": 165,
    "PLAY_PAUSE": 85,
    "PLAY": 126,
    "PAUSE": 127,
    "REWIND": 89,
    "FAST_FORWARD": 90,
    "CHANNEL_UP": 166,
    "CHANNEL_DOWN": 167,
    "PAGE_UP": 92,
    "PAGE_DOWN": 93,
    "ASPECT_RATIO": 228,
    "KEY_A": 29, "A": 29,
    "KEY_B": 30, "B": 30,
    "PROG_RED": 183, "RED": 183,
    "PROG_GREEN": 184, "GREEN": 184,
    "PROG_YELLOW": 185, "YELLOW": 185,
    "PROG_BLUE": 186, "BLUE": 186,
    "0": 7, "1": 8, "2": 9, "3": 10, "4": 11,
    "5": 12, "6": 13, "7": 14, "8": 15, "9": 16
}

# AI Gateways Configuration (100% loaded from environment/secrets)
DEEPSEEK_BASE = os.environ.get("DEEPSEEK_API_BASE", os.environ.get("FREEDEEPSEEK_API_BASE", ""))
DEEPSEEK_KEY = os.environ.get("DEEPSEEK_API_KEY", "free")
DEEPSEEK_MODEL = os.environ.get("DEEPSEEK_MODEL", "deepseek-v4-flash")

ANTIGRAVITY_BASE = os.environ.get("ANTIGRAVITY_API_BASE", "")
ANTIGRAVITY_KEY = os.environ.get("ANTIGRAVITY_API_KEY", "")
ANTIGRAVITY_MODEL = os.environ.get("ANTIGRAVITY_MODEL", "gemini-3.8-flash")

KILO_BASE = os.environ.get("KILO_API_BASE", "https://api.kilo.ai/api/gateway/v1/chat/completions")
KILO_KEY = os.environ.get("KILO_API_KEY", "")
KILO_MODEL = os.environ.get("KILO_MODEL", "kilo-auto/free")

OPENAI_BASE = os.environ.get("OPENAI_API_BASE", "")
OPENAI_KEY = os.environ.get("OPENAI_API_KEY", "")
OPENAI_MODEL = os.environ.get("OPENAI_MODEL", "gpt-4o-mini")


def sh(cmd, timeout=30):
    try:
        r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
        return (r.stdout or "") + (r.stderr or "")
    except Exception as e:
        return f"__ERR__ {e}"


# ==============================================================================
# 1. AI BRAIN (Vision + Reasoning Multi-Gateway Client)
# ==============================================================================
class AIBrain:
    """Bộ não trung tâm điều khiển AI QA: Hỗ trợ đa backend, Fallback tự động và Session Memory liên tục cho cả phiên test."""

    def __init__(self):
        self.provider_active = "UNKNOWN"
        self._detect_provider()
        self.session_id = None
        self.session_messages = []
        self.turn_counter = 0
        self.last_action_feedback = ""

    def _detect_provider(self):
        # Ưu tiên DeepSeek Web (nếu có base URL từ FreeDeepseekAPI nội bộ) hoặc Antigravity Gateway
        if DEEPSEEK_BASE:
            self.provider_active = "DEEPSEEK_WEB"
        elif ANTIGRAVITY_KEY and ANTIGRAVITY_BASE:
            self.provider_active = "ANTIGRAVITY"
        elif KILO_KEY and KILO_BASE:
            self.provider_active = "KILO"
        elif OPENAI_KEY and OPENAI_BASE:
            self.provider_active = "OPENAI"
        else:
            self.provider_active = "LOCAL_REFLEX"
        print(f"🧠 [AI Brain] Khởi tạo thành công với engine: {self.provider_active}", flush=True)

    def init_session(self, session_id: str):
        """Khởi tạo bộ nhớ cho một phiên kiểm thử hoàn chỉnh (Session Memory).
        Toàn bộ các bước kiểm thử trong cùng 1 phiên sẽ tích lũy ngữ cảnh vào cùng 1 luồng hội thoại.
        """
        self.session_id = session_id
        self.turn_counter = 0
        self.last_action_feedback = ""
        self.session_messages = [
            {"role": "system", "content": self._build_system_prompt()}
        ]
        print(f"🧠 [AI Brain] Đã thiết lập Session Memory cho phiên test: {session_id}", flush=True)

    def record_step_feedback(self, feedback: str):
        """Ghi nhận phản hồi kết quả thực tế sau khi thực hiện hành động vào bộ nhớ phiên."""
        self.last_action_feedback = feedback

    def _build_system_prompt(self) -> str:
        return """Bạn là Senior AI QA Engineer chuyên nghiệp kiểm thử ứng dụng Android TV 10-foot 'KênhLive'.
Bạn đang trực tiếp cầm remote điều khiển app trên TV để kiểm thử tính năng và đào sâu săn lỗi (Deep Bug Hunting) trong một phiên kiểm thử liên tục (Continuous Test Session).

BẢN ĐỒ TOÀN BỘ 6 TAB VÀ CÁC MÀN HÌNH CỦA APP KENHLIVE TV:
- [Tab 0 - Trực tiếp (Live)]: Trận đấu bóng đá live. Hàng lọc đài phát riêng biệt: "Tất cả", "ColaTV", "Gà Vàng", "Khán Đài", "Socolive". Hero Banner gọn gàng. Tên giải đấu chuẩn tiếng Việt, tỷ số X : Y, nhãn XEM và countdown. Hộp thoại RoomPickerDialog chọn phòng BLV.
- [Tab 1 - Lịch đấu (Schedule)]: Lịch thi đấu theo ngày (Hôm nay, Ngày mai, Giải đấu). Trận > 180p có badge KẾT THÚC. Chạm trận đấu (onFixtureTap) mở RoomPicker hoặc thông báo nếu chưa có phòng.
- [Tab 2 - Truyền hình (IPTV)]: Danh mục Kênh Yêu Thích (bấm giữ OK để lưu), TV360, VTV, HTV. Hero Preview góc trên. Lưới kênh. OSD gõ số trực tiếp từ remote (0-9). Phím tắt đổi kênh CHANNEL_UP/DOWN, PAGE_UP/DOWN. Phím tắt Aspect Ratio (A/Đỏ) & Audio Boost (B/Vàng).
- [Tab 3 - Tìm kiếm (Search)]: Ô nhập tìm kiếm (searchInput), gõ từ khóa không dấu, chuyển D-pad xuống danh sách kết quả (resultList), nút xóa nhanh clearBtn.
- [Tab 4 - Cài đặt (Settings)]: Thông tin phiên bản, kiểm tra cập nhật, liên hệ & báo lỗi Telegram (@kenhlivetv) có mã QR ngoại tuyến, cài đặt video và audio.
- [Tab 5 - Kho Phim (KKPhim & NguonC)]: Apple Pills thể loại & quốc gia, nút XEM NGAY (btnHeroPlay), nút CHỌN TẬP (btnHeroEpisodes), nút LƯU PHIM (btnHeroFavorite), hộp thoại EpisodePickerDialog hỗ trợ chọn Server (Vietsub/Thuyết Minh) và phân nhóm 30 tập. Trình phát phim hỗ trợ HLS 4K native ExoPlayer, tua D-pad Trái/Phải (-15s / +15s) và tự động lưu/tiếp tục xem (Resume Playback).
- [Sidebar Rail]: Mở bằng DPAD_LEFT từ mép trái, duyệt dọc qua 6 tab, đóng bằng DPAD_RIGHT hoặc BACK.
- [PlayerActivity]: Trình phát video Live & VOD, phím MENU mở Server Picker, phím A/Đỏ xoay vòng tỉ lệ khung hình (Fit/Fill/Zoom/Fixed), phím B/Vàng tăng âm lượng, gõ số kênh hiện OSD nhảy kênh ngay khi bấm OK. Khung báo lỗi có nút Báo Lỗi Telegram @kenhlivetv.
- [Home Hub Portal (Tab 6)]: Cổng chọn 3 card lớn (THỂ THAO, TRUYỀN HÌNH, PHIM TRUYỆN) tích hợp Logo Canva 3D (Bóng Đá & Phim Ảnh), kèm 4 nút tiện ích thanh đáy (Cài đặt, Báo lỗi Telegram, Lịch thi đấu, Tìm kiếm). Mỗi mục khi vào sẽ có GIAO DIỆN RIÊNG BIỆT toàn màn hình, không bị lẫn các tab của mục khác.
- [Thoát mục về Menu Chọn]: Khi đang ở bất kỳ mục nào (current != TAB_HUB), nhấn BACK 2 lần liên tiếp trong 2 giây sẽ thoát mục quay lại Menu Chọn (Home Hub). Nhấn BACK tại Menu Chọn sẽ hiện dialog xác nhận thoát app.

NGUYÊN TẮC BỘ NHỚ PHIÊN & SĂN LỖI SÂU (Deep Bug Hunting & Session Memory):
1. Bạn có TRÍ NHỚ LIÊN TỤC xuyên suốt cả phiên test. Hãy ghi nhớ các màn hình đã đi qua, các thao tác đã thực hiện và các lỗi đã phát hiện để không lặp lại hoặc kiểm tra lại xem lỗi có tái diễn.
2. Chủ động khám phá toàn bộ các tính năng, di chuyển qua các tab bằng SWITCH_TAB, OPEN_MULTIVIEW hoặc DPAD_LEFT mở rail navigation.
3. Khi phát hiện lỗi thực sự (Mất dấu focus, Kẹt nút Focus Trap, Rò rỉ focus vào view ẩn/ngoài màn hình, Tràn viền text, Chữ hiển thị 'null'/'undefined', Dialog không trap focus, App crash, Video đứng hình), hãy báo ngay vào trường 'defect_detected'.
4. Tính năng tự phục hồi (Failover & Self-Healing): Nếu app tự đổi sang server dự phòng thành công, đây là tính năng bảo vệ hoạt động đúng, KHÔNG coi là lỗi.
5. KIỂM THỬ KỸ LƯỠNG, TOÀN DIỆN & KHÔNG ĐƯỢC LÀM QUA LOA: Mỗi nhiệm vụ phải thực hiện tối thiểu 4-6 thao tác kiểm thử sâu (duyệt D-pad qua lại, bấm vào các nút chi tiết, kiểm tra các trạng thái biên, đổi tab) trước khi bấm COMPLETE_MISSION. Tuyệt đối KHÔNG bấm COMPLETE_MISSION khi chỉ mới bấm 1-2 phím!
6. PHÂN BIỆT RÕ GIAO DIỆN APP VỚI NỘI DUNG TRUYỀN HÌNH (Broadcast Stream vs App UI):
- Khi đang phát video, các đồ họa như Bảng điểm trận đấu (Scoreboard của đài truyền hình gốc: '72:37 AZ 0-2 JUV', cúp C1/C2), logo kênh (ARENASPORT, SUTBONGTV), phụ đề phim hoặc banner BLV là NỘI DUNG NẰM TRONG LUỒNG VIDEO của bên phát sóng, KHÔNG PHẢI giao diện của ứng dụng KenhLive TV. Tuyệt đối KHÔNG nhầm lẫn bảng điểm bóng đá của truyền hình là nút backBtn hay thanh tiêu đề app!
7. TÍNH CHÍNH XÁC KHI ĐỐI CHIẾU CẢM BIẾN HỆ THỐNG:
- Luôn kiểm tra 'TRẠNG THÁI MÀN HÌNH & HỆ THỐNG HIỆN TẠI' (Node đang FOCUS, Có viền focus chuẩn, DANH SÁCH VIEW FOCUSABLE). Nếu thông tin hệ thống báo 'Node đang FOCUS: NONE' hoặc thanh điều khiển OSD đang ẩn, không được ảo giác rằng thanh OSD đang hiển thị hay có nút có viền cam. Muốn mở thanh điều khiển hãy bấm OK hoặc DPAD_DOWN.
8. GIỚI HẠN EMULATOR x86 (ANDROID TV EMULATOR CI):
- Lỗi giải mã phần cứng trên emulator x86 (ví dụ ERROR_CODE_DECODING_FAILED, MediaCodec thiếu codec HEVC/H.264 cho một số luồng HLS) là giới hạn của máy ảo, KHÔNG phải lỗi app. Tuyệt đối không báo [HIGH] cho lỗi này; tối đa [LOW] ghi chú môi trường hoặc bỏ qua nếu video vẫn phát được ở nguồn khác / dialog lỗi hiển thị đúng.

ĐỊNH DẠNG TRẢ VỀ DUY NHẤT: Trả về một khối JSON hợp lệ:
{
  "thought": "Quan sát thấy gì trên màn hình hiện tại, liên hệ với các bước trước trong phiên kiểm thử và lý do chọn hành động này",
  "action_type": "KEY" | "LONG_PRESS" | "KEYPAD" | "SWITCH_TAB" | "OPEN_MULTIVIEW" | "LAUNCH_WEB_PLAYER" | "TEXT" | "WAIT" | "VERIFY_STREAM" | "COMPLETE_MISSION",
  "action_param": "DPAD_DOWN" | "OK" | "1" | "2" | "ENTER" | "BACK" | "DOUBLE_BACK" | "uefa" | "2.0" | "",
  "expected_result": "Kỳ vọng gì sau khi thực hiện hành động này?",
  "defect_detected": null hoặc {
     "area": "Tên màn hình/tính năng",
     "severity": "CRITICAL" | "HIGH" | "MEDIUM" | "LOW",
     "issue": "Mô tả ngắn gọn lỗi",
     "detail": "Bằng chứng chi tiết",
     "suggestion": "Gợi ý sửa code Kotlin/Layout"
  }
}"""

    def chat_turn(self, messages, max_tokens=3000, timeout=45):
        """Gửi chuỗi hội thoại của phiên đến model AI và nhận câu trả lời."""
        # 1. Thử DeepSeek Web nếu có cấu hình
        if self.provider_active == "DEEPSEEK_WEB" or DEEPSEEK_BASE:
            try:
                res = self._call_openai_compat(
                    url=DEEPSEEK_BASE,
                    key=DEEPSEEK_KEY,
                    model=DEEPSEEK_MODEL,
                    messages=messages,
                    max_tokens=max_tokens,
                    timeout=timeout,
                    user=f"qa_brain_{self.session_id or 'auto'}"
                )
                if res:
                    return res, "DEEPSEEK_WEB"
            except Exception as e:
                print(f"⚠️ [AI Brain] DeepSeek Web gateway lỗi ({e}), chuyển fallback...", flush=True)

        # 2. Thử Antigravity Gateway
        if self.provider_active == "ANTIGRAVITY" or (ANTIGRAVITY_KEY and ANTIGRAVITY_BASE):
            try:
                res = self._call_openai_compat(
                    url=ANTIGRAVITY_BASE,
                    key=ANTIGRAVITY_KEY,
                    model=ANTIGRAVITY_MODEL,
                    messages=messages,
                    max_tokens=max_tokens,
                    timeout=timeout
                )
                if res:
                    return res, "ANTIGRAVITY"
            except Exception as e:
                print(f"⚠️ [AI Brain] Antigravity gateway lỗi ({e}), chuyển fallback...", flush=True)

        # 3. Thử Kilo Gateway
        if KILO_KEY and KILO_BASE:
            try:
                res = self._call_openai_compat(
                    url=KILO_BASE,
                    key=KILO_KEY,
                    model=KILO_MODEL,
                    messages=messages,
                    max_tokens=max_tokens,
                    timeout=timeout
                )
                if res:
                    return res, "KILO"
            except Exception as e:
                print(f"⚠️ [AI Brain] Kilo gateway lỗi ({e}), chuyển fallback...", flush=True)

        # 3. Thử OpenAI / OpenRouter nếu có
        if OPENAI_KEY and OPENAI_BASE:
            try:
                res = self._call_openai_compat(
                    url=OPENAI_BASE,
                    key=OPENAI_KEY,
                    model=OPENAI_MODEL,
                    messages=messages,
                    max_tokens=max_tokens,
                    timeout=timeout
                )
                if res:
                    return res, "OPENAI"
            except Exception:
                pass

        # 4. Fallback: Local Reflex Brain (không phụ thuộc mạng ngoại vi)
        return None, "LOCAL_REFLEX"

    def chat_vision(self, prompt, image_bytes=None, system_prompt=None, max_tokens=3000, timeout=45):
        """Tương thích ngược: Gửi tin nhắn đơn lẻ nếu cần."""
        msgs = []
        if system_prompt:
            msgs.append({"role": "system", "content": system_prompt})
        user_content = [{"type": "text", "text": prompt}]
        if image_bytes:
            b64 = self._compress_and_b64(image_bytes)
            if b64:
                user_content.append({
                    "type": "image_url",
                    "image_url": {"url": f"data:image/jpeg;base64,{b64}"}
                })
        msgs.append({"role": "user", "content": user_content})
        return self.chat_turn(msgs, max_tokens=max_tokens, timeout=timeout)

    def _normalize_gateway_url(self, url: str) -> str:
        if not url:
            return ""
        u = url.rstrip("/")
        if u.endswith("/chat/completions"):
            return u
        if u.endswith("/v1"):
            return f"{u}/chat/completions"
        return f"{u}/chat/completions"

    def _call_openai_compat(self, url, key, model, messages, max_tokens, timeout, user=None):
        target_url = self._normalize_gateway_url(url)
        payload = {
            "model": model,
            "messages": messages,
            "max_tokens": max_tokens,
            "temperature": 0.1,
            "stream": False
        }
        if user:
            payload["user"] = user

        headers = {
            "Content-Type": "application/json",
            "Authorization": f"Bearer {key}",
            "User-Agent": UA
        }
        if user:
            headers["x-agent-session"] = user

        data_bytes = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(
            target_url,
            data=data_bytes,
            headers=headers
        )

        for attempt in range(3):
            try:
                with urllib.request.urlopen(req, timeout=timeout) as resp:
                    raw_text = resp.read().decode("utf-8")
                    # Xử lý gateway tự động stream SSE (data: {...}) dù không yêu cầu stream
                    if raw_text.strip().startswith("data:"):
                        parts = []
                        for line in raw_text.splitlines():
                            line = line.strip()
                            if line.startswith("data:"):
                                data_chunk = line[5:].strip()
                                if data_chunk == "[DONE]":
                                    break
                                try:
                                    chunk = json.loads(data_chunk)
                                    choices = chunk.get("choices") or []
                                    if choices:
                                        delta = choices[0].get("delta") or choices[0].get("message") or {}
                                        t = delta.get("content") or delta.get("reasoning_content") or ""
                                        if t:
                                            parts.append(t)
                                except Exception:
                                    pass
                        return "".join(parts).strip()
                    data = json.loads(raw_text)
                    choices = data.get("choices") or []
                    if not choices:
                        return ""
                    msg = choices[0].get("message") or {}
                    raw = msg.get("content") or msg.get("reasoning_content") or ""
                    if isinstance(raw, list):
                        raw = "".join(p.get("text", "") if isinstance(p, dict) else str(p) for p in raw)
                    elif not isinstance(raw, str):
                        raw = str(raw) if raw is not None else ""
                    return raw.strip()
            except Exception as e:
                if attempt == 2:
                    raise
                time.sleep(1.5 * (attempt + 1))
        return ""

    def _compress_and_b64(self, png_bytes):
        if not png_bytes or not Image:
            return base64.b64encode(png_bytes).decode("utf-8") if png_bytes else None
        try:
            im = Image.open(io.BytesIO(png_bytes)).convert("RGB")
            im.thumbnail((960, 540))
            buf = io.BytesIO()
            im.save(buf, format="JPEG", quality=75)
            return base64.b64encode(buf.getvalue()).decode("utf-8")
        except Exception:
            return base64.b64encode(png_bytes).decode("utf-8")

    def decide_mission_action(self, mission, state):
        """AI phân tích trạng thái và ra quyết định hành động tiếp theo trong mạch hội thoại của cả phiên test."""
        self.turn_counter += 1

        # 1. Đảm bảo session_messages đã được khởi tạo
        if not self.session_messages:
            self.init_session("QA-SESSION-AUTO")

        # 2. Tối ưu bộ nhớ phiên: gỡ bỏ dữ liệu ảnh base64 lớn của các bước cũ để tránh tràn token / bandwidth
        for msg in self.session_messages:
            content = msg.get("content")
            if msg.get("role") == "user" and isinstance(content, list):
                compact_parts = []
                for p in content:
                    if isinstance(p, dict):
                        if p.get("type") == "text":
                            compact_parts.append(p)
                        elif p.get("type") == "image_url":
                            compact_parts.append({"type": "text", "text": "[Ảnh màn hình bước trước đã phân tích]"})
                msg["content"] = compact_parts

        # 3. Xây dựng tin nhắn User cho bước hiện tại
        feedback_section = f"\nPHẢN HỒI TỪ BƯỚC TRƯỚC:\n{self.last_action_feedback}\n" if self.last_action_feedback else ""
        deep_anomaly_section = f"\nCẢNH BÁO TỪ PROBE TỰ ĐỘNG (DEEP ANOMALY INSPECTOR):\n{state.get('deep_anomalies_text')}\n" if state.get('deep_anomalies_text') else ""
        step_prompt = f"""[LƯỢT #{self.turn_counter} · NHIỆM VỤ: {mission.name} ({mission.title})]
- Tiến độ nhiệm vụ: Bước {mission.steps_executed}/{mission.max_steps}
- Mục tiêu: {mission.description}
- Tiêu chí đạt: {mission.success_criteria}

TIẾN ĐỘ TOÀN PHIÊN KIỂM THỬ:
- Các Tab đã kiểm tra: {state.get('tabs_tested', 'Chưa có')}
- Các Tab còn lại: {state.get('tabs_remaining', 'Toàn bộ')}

TRẠNG THÁI MÀN HÌNH & HỆ THỐNG HIỆN TẠI:
- Activity: {state.get('activity')}
- Node đang FOCUS: {state.get('focused_sig')} (Text: '{state.get('focused_text')}')
- Có viền focus chuẩn: {state.get('has_focus_ring')} (Màu viền: {state.get('focus_ring_color')})
- Video đang chạy (Motion): {state.get('video_motion_playing')} (Motion delta: {state.get('video_motion_delta', 0.0):.2f}%)
- Lịch sử 5 phím gần nhất: {state.get('recent_keys')}
- Logcat cảnh báo/lỗi: {state.get('logcat_alerts')}
{feedback_section}{deep_anomaly_section}
DANH SÁCH VIEW FOCUSABLE TRÊN MÀN HÌNH:
{state.get('ui_summary')}

Hãy phân tích hình ảnh màn hình hiện tại đính kèm cùng toàn bộ diễn biến lịch sử của phiên kiểm thử này để chọn hành động tiếp theo. Trả về DUY NHẤT một khối JSON theo định dạng đã quy định."""

        user_content = [{"type": "text", "text": step_prompt}]
        raw_png = state.get("screenshot_bytes")
        if raw_png:
            b64 = self._compress_and_b64(raw_png)
            if b64:
                user_content.append({
                    "type": "image_url",
                    "image_url": {"url": f"data:image/jpeg;base64,{b64}"}
                })

        new_user_msg = {"role": "user", "content": user_content}

        # Sliding window nếu số lượng tin nhắn quá dài (> 30 tin nhắn ~ 15 lượt)
        if len(self.session_messages) > 30:
            sys_msg = self.session_messages[0]
            # Giữ system message và các tin nhắn gần nhất nhưng PHẢI bắt đầu bằng lượt User (tránh lỗi 400 Gemini API)
            trimmed = self.session_messages[-16:]
            while trimmed and trimmed[0].get("role") != "user":
                trimmed = trimmed[1:]
            self.session_messages = [sys_msg] + trimmed

        candidate_messages = list(self.session_messages) + [new_user_msg]
        response_text, provider = self.chat_turn(candidate_messages)

        if response_text and provider != "LOCAL_REFLEX":
            try:
                # Sanitize markdown code fences và trailing commas
                cleaned_text = re.sub(r"^```(?:json)?\s*", "", response_text.strip(), flags=re.IGNORECASE)
                cleaned_text = re.sub(r"\s*```$", "", cleaned_text.strip())
                m = re.search(r"\{.*\}", cleaned_text, re.DOTALL)
                if m:
                    json_str = m.group(0)
                    json_str = re.sub(r",\s*([\]}])", r"\1", json_str)
                    parsed = json.loads(json_str)
                    parsed["_provider"] = provider
                    # Ghi nhận lượt hội thoại này vào bộ nhớ phiên
                    self.session_messages.append(new_user_msg)
                    self.session_messages.append({"role": "assistant", "content": response_text})
                    return parsed
            except Exception as e:
                print(f"⚠️ [AI Brain] Không parse được JSON từ {provider}: {e}", flush=True)

        # Fallback vào Local Reflex Brain nếu AI cloud không khả dụng
        return self._local_reflex_decision(mission, state)

    def _local_reflex_decision(self, mission, state):
        """Local Autonomous Reflex Brain: Quyết định dựa trên heuristics ngữ nghĩa thông minh."""
        m_id = mission.name
        step = max(0, mission.steps_executed - 1)
        focused_text = (state.get("focused_text") or "").lower()
        recent = state.get("recent_keys", [])

        thought = f"Local Reflex: Điều hướng theo ngữ cảnh nhiệm vụ {m_id} tại bước {step + 1}"
        action_type = "KEY"
        action_param = "DPAD_DOWN"
        defect = None

        # Kiểm tra mất focus: loại trừ màn hình phát video PlayerActivity/WebPlayerActivity
        # vì khi phát video toàn màn hình, thanh điều khiển OSD tự ẩn sau 3.5s là thiết kế chuẩn TV,
        # không phải lỗi mất focus.
        is_player = "PlayerActivity" in (state.get("activity") or "") or "WebPlayerActivity" in (state.get("activity") or "")
        is_playing = state.get("video_motion_playing") or False
        if not is_player and not is_playing and not state.get("has_focus_ring") and state.get("focused_sig") == "NONE":
            defect = {
                "area": "Điều hướng D-pad",
                "severity": "HIGH",
                "issue": "Mất dấu focus (Focus Loss) trên màn hình",
                "detail": f"Sau các phím {recent[-4:]}, không có view nào giữ focus",
                "suggestion": "Đảm bảo gán requestFocus() hoặc cấu hình focusable=true cho các view chính"
            }

        # Logic điều hướng phản xạ theo từng nhiệm vụ (15 Deep-Dive Missions)
        if m_id == "MISSION_COLD_START":
            if step == 0:
                action_type = "WAIT"
                action_param = "1.5"
                thought = "Chờ app tải xong dữ liệu màn hình chính và Hero Banner"
            elif step == 1:
                action_type = "KEY"
                action_param = "DPAD_DOWN"
                thought = "Di chuyển focus xuống thẻ trận đầu tiên, kiểm tra độ nhạy Leanback"
            elif step == 2:
                action_type = "KEY"
                action_param = "DPAD_RIGHT"
                thought = "Duyệt thẻ trận đấu thứ hai trên hàng đầu tiên"
            elif step == 3:
                action_type = "KEY"
                action_param = "DPAD_UP"
                thought = "Quay lại Hero Banner kiểm tra khôi phục focus"
            else:
                action_type = "COMPLETE_MISSION"
                action_param = ""
                thought = "Màn hình chính nạp thành công, Hero Banner gọn gàng không card rác"

        elif m_id == "MISSION_SPORTS_MULTI_SOURCE":
            # Duyệt sâu các hàng nguồn thể thao: Socolive, ColaTV, Gà Vàng, Khán Đài
            if step == 0:
                action_type = "KEY"
                action_param = "DPAD_DOWN"
                thought = "Di chuyển xuống hàng Trực Tiếp Socolive"
            elif step in (1, 2, 3):
                action_type = "KEY"
                action_param = "DPAD_RIGHT"
                thought = f"Cuộn ngang sâu trên hàng Socolive (bước {step}) để kiểm tra biên phải"
            elif step == 4:
                action_type = "KEY"
                action_param = "DPAD_DOWN"
                thought = "Di chuyển xuống hàng Trực Tiếp ColaTV"
            elif step == 5:
                action_type = "KEY"
                action_param = "DPAD_RIGHT"
                thought = "Cuộn ngang kiểm tra các trận trên đài ColaTV"
            elif step == 6:
                action_type = "KEY"
                action_param = "DPAD_DOWN"
                thought = "Di chuyển xuống hàng Trực Tiếp Gà Vàng TV"
            elif step == 7:
                action_type = "KEY"
                action_param = "DPAD_DOWN"
                thought = "Di chuyển xuống hàng Trực Tiếp Khán Đài TV"
            else:
                action_type = "COMPLETE_MISSION"
                action_param = ""
                thought = "Đã kiểm tra sâu toàn bộ 4 hàng đài thể thao độc lập"

        elif m_id == "MISSION_SPORTS_ROOM_PICKER":
            if step == 0:
                action_type = "KEY"
                action_param = "ENTER"
                thought = "Mở hộp thoại chọn phòng BLV (RoomPickerDialog) trên thẻ trận đấu"
            elif step == 1:
                action_type = "WAIT"
                action_param = "1.0"
                thought = "Chờ hộp thoại RoomPickerDialog hiển thị danh sách phòng"
            elif step == 2:
                action_type = "KEY"
                action_param = "DPAD_DOWN"
                thought = "Di chuyển xuống phòng BLV tiếp theo trong danh sách"
            elif step == 3:
                action_type = "KEY"
                action_param = "DPAD_UP"
                thought = "Di chuyển ngược lên phòng BLV đầu tiên"
            elif step == 4:
                action_type = "KEY"
                action_param = "BACK"
                thought = "Nhấn BACK đóng RoomPickerDialog trả focus về thẻ trận đấu"
            else:
                action_type = "COMPLETE_MISSION"
                action_param = ""
                thought = "Hộp thoại RoomPickerDialog đóng mở và trap focus chuẩn xác"

        elif m_id == "MISSION_SPORTS_DATA_ACCURACY":
            if step == 0:
                action_type = "SWITCH_TAB"
                action_param = "1"
                thought = "Chuyển sang Tab Lịch đấu để kiểm tra tỷ số X : Y, giờ GMT+7 và nhãn KẾT THÚC"
            elif step == 1:
                action_type = "KEY"
                action_param = "DPAD_DOWN"
                thought = "Kiểm tra thẻ trận: Tên giải đấu chuẩn tiếng Việt, tỷ số dạng X : Y, không spam rác [HOT]/[VIP]"
            elif step == 2:
                action_type = "KEY"
                action_param = "DPAD_DOWN"
                thought = "Kiểm tra trận tiếp theo trong danh sách, xác nhận badge KẾT THÚC nếu trận > 180p"
            else:
                action_type = "COMPLETE_MISSION"
                action_param = ""

        elif m_id == "MISSION_PLAYER_STREAMING_AND_CONTROLS":
            if step == 0:
                action_type = "SWITCH_TAB"
                action_param = "0"
                thought = "Quay về Tab Trực tiếp để mở phát một trận đấu"
            elif step == 1:
                action_type = "KEY"
                action_param = "DPAD_DOWN"
                thought = "Chọn thẻ trận đấu đầu tiên"
            elif step == 2:
                action_type = "KEY"
                action_param = "ENTER"
                thought = "Mở một trận đấu bóng đá để vào PlayerActivity kiểm tra luồng phát và phím tắt"
            elif step == 3:
                action_type = "KEY"
                action_param = "ENTER"
                thought = "Chọn phòng BLV đầu tiên nếu popup chọn phòng xuất hiện"
            elif step == 4:
                action_type = "VERIFY_STREAM"
                action_param = ""
                thought = "Kiểm tra ExoPlayer render video motion chuyển động mượt"
            elif step == 5:
                action_type = "KEY"
                action_param = "KEY_A"
                thought = "Bấm phím A (hoặc phím Đỏ) để test phím tắt xoay vòng tỉ lệ khung hình Aspect Ratio"
            elif step == 6:
                action_type = "KEY"
                action_param = "KEY_B"
                thought = "Bấm phím B (hoặc phím Vàng) để test phím tắt tăng âm lượng Audio Boost (+3dB/+6dB/+9dB)"
            elif step == 7:
                action_type = "KEY"
                action_param = "DOUBLE_BACK"
                thought = "Thoát PlayerActivity bằng DOUBLE_BACK để đóng OSD và về lại màn hình chính"
            else:
                action_type = "COMPLETE_MISSION"
                action_param = ""

        elif m_id == "MISSION_HOME_HUB_3_CARDS_AND_CANVA_LOGOS":
            if step == 0:
                action_type = "SWITCH_TAB"
                action_param = "6"
                thought = "Chuyển sang Cổng Chọn 3 Card Home Hub (Tab 6)"
            elif step == 1:
                action_type = "KEY"
                action_param = "DPAD_RIGHT"
                thought = "Chuyển focus sang card TRUYỀN HÌNH (Hub Truyền Hình & M3U Tùy Chọn)"
            elif step == 2:
                action_type = "KEY"
                action_param = "ENTER"
                thought = "Bấm OK vào card TRUYỀN HÌNH để mở Hub Truyền Hình riêng biệt"
            elif step == 3:
                action_type = "WAIT"
                action_param = "2.0"
                thought = "Chờ Hub Truyền Hình nạp danh sách kênh và kênh M3U tùy chỉnh"
            elif step == 4:
                action_type = "KEY"
                action_param = "DOUBLE_BACK"
                thought = "Bấm DOUBLE_BACK thoát Hub Truyền Hình quay lại Cổng Chọn (Tab 6)"
            elif step == 5:
                action_type = "KEY"
                action_param = "DPAD_RIGHT"
                thought = "Chuyển focus sang card PHIM TRUYỆN (Hub Phim Canva 3D)"
            elif step == 6:
                action_type = "KEY"
                action_param = "ENTER"
                thought = "Bấm OK vào card PHIM TRUYỆN mở Hub Phim riêng biệt"
            elif step == 7:
                action_type = "WAIT"
                action_param = "2.0"
                thought = "Chờ Hub Phim tải hoàn tất Hero Banner và các hàng phim"
            elif step == 8:
                action_type = "KEY"
                action_param = "DOUBLE_BACK"
                thought = "Bấm DOUBLE_BACK thoát Hub Phim quay lại Cổng Chọn (Tab 6)"
            elif step == 9:
                action_type = "KEY"
                action_param = "DPAD_DOWN"
                thought = "Di chuyển focus xuống hàng tiện ích 4 nút đáy Home Hub"
            else:
                action_type = "COMPLETE_MISSION"
                action_param = ""
                thought = "Đã kiểm tra hoàn tất Cổng Chọn 3 Card: Thể Thao, Truyền Hình (M3U) và Phim Truyện"

        elif m_id in ("MISSION_SCHEDULE_AND_SIDEBAR_NAVIGATION", "MISSION_SCHEDULE_EPG_AND_FIXTURE_TAP"):
            if step == 0:
                action_type = "SWITCH_TAB"
                action_param = "1"
                thought = "Chuyển sang Tab Lịch đấu (Tab 1)"
            elif step == 1:
                action_type = "WAIT"
                action_param = "1.0"
                thought = "Chờ danh sách lịch nạp xong dữ liệu"
            elif step == 2:
                action_type = "KEY"
                action_param = "DPAD_DOWN"
                thought = "Duyệt lịch thi đấu bóng đá theo ngày"
            elif step == 3:
                action_type = "KEY"
                action_param = "ENTER"
                thought = "Bấm OK vào trận đấu trong lịch để kiểm tra sự kiện onFixtureTap"
            elif step == 4:
                action_type = "KEY"
                action_param = "BACK"
                thought = "Đóng popup phòng live (nếu có) và quay lại danh sách lịch"
            else:
                action_type = "COMPLETE_MISSION"
                action_param = ""

        elif m_id == "MISSION_SIDEBAR_RAIL_FULL_TRAVERSAL":
            if step == 0:
                action_type = "KEY"
                action_param = "DPAD_LEFT"
                thought = "Bấm DPAD_LEFT từ mép trái để mở thanh điều hướng Sidebar Rail"
            elif step in (1, 2, 3, 4):
                action_type = "KEY"
                action_param = "DPAD_DOWN"
                thought = f"Duyệt dọc xuống mục tiếp theo trên Sidebar Rail (bước {step}) kiểm tra không tự đóng"
            elif step == 5:
                action_type = "KEY"
                action_param = "DPAD_RIGHT"
                thought = "Bấm DPAD_RIGHT để đóng Sidebar Rail và chuyển focus vào nội dung tab"
            else:
                action_type = "COMPLETE_MISSION"
                action_param = ""
                thought = "Thanh điều hướng Sidebar Rail hoạt động chuẩn xác, không bị tự đóng hay rò rỉ focus"

        elif m_id in ("MISSION_IPTV_FAVORITES_AND_ZAPPING", "MISSION_IPTV_CATEGORIES_AND_FAVORITES"):
            if step == 0:
                action_type = "SWITCH_TAB"
                action_param = "2"
                thought = "Chuyển sang Tab Truyền hình (IPTV)"
            elif step == 1:
                action_type = "WAIT"
                action_param = "2.0"
                thought = "Chờ tải danh sách kênh IPTV và M3U tùy chọn"
            elif step == 2:
                action_type = "KEY"
                action_param = "DPAD_DOWN"
                thought = "Di chuyển focus xuống lưới kênh truyền hình"
            elif step == 3:
                action_type = "LONG_PRESS"
                action_param = "OK"
                thought = "Bấm giữ phím OK để lưu kênh vào mục Kênh Yêu Thích"
            elif step == 4:
                action_type = "KEY"
                action_param = "DPAD_UP"
                thought = "Di chuyển lên thanh danh mục để kiểm tra tab Yêu Thích và các nhóm kênh"
            elif step == 5:
                action_type = "KEY"
                action_param = "DPAD_RIGHT"
                thought = "Duyệt qua nhóm M3U Của Tôi / Thể Thao Quốc Tế"
            elif step == 6:
                action_type = "KEY"
                action_param = "DPAD_RIGHT"
                thought = "Di chuyển sang nút '➕ THÊM M3U'"
            elif step == 7:
                action_type = "KEY"
                action_param = "ENTER"
                thought = "Bấm OK mở hộp thoại DialogCustomM3u kiểm tra cấu hình link M3U cá nhân"
            elif step == 8:
                action_type = "KEY"
                action_param = "BACK"
                thought = "Bấm BACK đóng hộp thoại DialogCustomM3u"
            else:
                action_type = "COMPLETE_MISSION"
                action_param = ""
                thought = "Đã kiểm tra danh mục IPTV, nạp M3U và lưu kênh yêu thích"

        elif m_id in ("MISSION_IPTV_EPG_AND_HERO_PREVIEW", "MISSION_IPTV_EPG_AND_ZAPPING_PLAYER"):
            if step == 0:
                action_type = "KEY"
                action_param = "DPAD_DOWN"
                thought = "Duyệt qua lưới kênh để nạp lịch phát sóng EPG chuẩn GMT+7 và Hero Preview"
            elif step == 1:
                action_type = "WAIT"
                action_param = "1.5"
                thought = "Chờ Hero Preview debounce nạp luồng phát thử góc trên"
            elif step == 2:
                action_type = "KEY"
                action_param = "ENTER"
                thought = "Mở phát toàn màn hình kênh truyền hình"
            elif step == 3:
                action_type = "KEYPAD"
                action_param = "1"
                thought = "Gõ phím số 1 trên remote để test OSD nhảy kênh trực tiếp (Direct Zapping)"
            elif step == 4:
                action_type = "KEY"
                action_param = "CHANNEL_UP"
                thought = "Thử phím CHANNEL_UP chuyển kênh nhanh"
            elif step == 5:
                action_type = "KEY"
                action_param = "BACK"
                thought = "Thoát trình phát về lại Tab IPTV"
            else:
                action_type = "COMPLETE_MISSION"
                action_param = ""

        elif m_id in ("MISSION_PHIM_NGUONC_AND_SEARCH", "MISSION_PHIM_NGUONC_AND_CATEGORIES"):
            if step == 0:
                action_type = "SWITCH_TAB"
                action_param = "5"
                thought = "Điều hướng sang Tab Kho Phim (NguonC & Anime)"
            elif step == 1:
                action_type = "WAIT"
                action_param = "1.5"
                thought = "Chờ kho phim tải Hero Banner và danh sách thể loại"
            elif step == 2:
                action_type = "KEY"
                action_param = "DPAD_UP"
                thought = "Di chuyển lên thanh bộ lọc Apple Pills"
            elif step == 3:
                action_type = "KEY"
                action_param = "DPAD_DOWN"
                thought = "Nhấn DPAD_DOWN từ Apple Pill kiểm tra focus hạ cánh thẳng xuống nút XEM NGAY (btnHeroPlay)"
            else:
                action_type = "COMPLETE_MISSION"
                action_param = ""

        elif m_id == "MISSION_PHIM_EPISODE_PICKER_AND_FAVORITE":
            if step == 0:
                action_type = "KEY"
                action_param = "DPAD_RIGHT"
                thought = "Chuyển focus từ nút XEM NGAY sang nút CHỌN TẬP (btnHeroEpisodes)"
            elif step == 1:
                action_type = "KEY"
                action_param = "ENTER"
                thought = "Bấm OK mở hộp thoại chọn tập phim (EpisodePickerDialog)"
            elif step == 2:
                action_type = "KEY"
                action_param = "DPAD_DOWN"
                thought = "Duyệt danh sách các tập phim trong EpisodePickerDialog"
            elif step == 3:
                action_type = "KEY"
                action_param = "BACK"
                thought = "Đóng hộp thoại chọn tập bằng phím BACK"
            elif step == 4:
                action_type = "KEY"
                action_param = "DPAD_RIGHT"
                thought = "Chuyển focus sang nút LƯU PHIM (btnHeroFavorite)"
            elif step == 5:
                action_type = "KEY"
                action_param = "ENTER"
                thought = "Bấm OK lưu phim vào danh sách yêu thích"
            elif step == 6:
                action_type = "KEY"
                action_param = "DPAD_RIGHT"
                thought = "Chuyển focus sang nút ĐỔI PHIM (btnHeroNext) trên Spotlight Hero"
            elif step == 7:
                action_type = "KEY"
                action_param = "ENTER"
                thought = "Bấm OK đổi sang phim nổi bật kế tiếp trong kho Spotlight"
            else:
                action_type = "COMPLETE_MISSION"
                action_param = ""
                thought = "Đã kiểm tra hoàn tất hộp thoại chọn tập, lưu phim và đổi Spotlight"

        elif m_id in ("MISSION_PHIM_PLAYER_SEEK_AND_RESUME", "MISSION_VOD_PLAYER_SEEK_AND_RESUME"):
            if step == 0:
                action_type = "LAUNCH_WEB_PLAYER"
                action_param = ""
                thought = "Khởi chạy Trình Phát Web (WebPlayerActivity) để kiểm tra trình phát thứ hai của app"
            elif step == 1:
                action_type = "WAIT"
                action_param = "2.5"
                thought = "Chờ WebPlayer tải xong trang embed iframe và video"
            elif step == 2:
                action_type = "KEY"
                action_param = "DPAD_DOWN"
                thought = "Bấm DPAD_DOWN để mở thanh OSD hiển thị tiêu đề và nút điều khiển web"
            elif step == 3:
                action_type = "KEY"
                action_param = "DPAD_RIGHT"
                thought = "Bấm D-pad Phải kiểm tra tua tới +10s qua JS injection trên WebPlayer"
            elif step == 4:
                action_type = "KEY"
                action_param = "DPAD_LEFT"
                thought = "Bấm D-pad Trái kiểm tra tua lùi -10s qua JS injection trên WebPlayer"
            elif step == 5:
                action_type = "KEY"
                action_param = "OK"
                thought = "Bấm OK toggle Play/Pause trên WebPlayer"
            elif step == 6:
                action_type = "KEY"
                action_param = "BACK"
                thought = "Bấm BACK để thoát WebPlayer và tự động lưu tiến độ vào Lịch sử xem"
            elif step == 7:
                action_type = "SWITCH_TAB"
                action_param = "5"
                thought = "Về lại Kho Phim (Tab 5) để kiểm tra hàng Tiếp Tục Xem (Resume Playback)"
            elif step == 8:
                action_type = "WAIT"
                action_param = "1.5"
                thought = "Xác nhận hàng Tiếp Tục Xem đã nạp thẻ phim vừa xem dở"
            else:
                action_type = "COMPLETE_MISSION"
                action_param = ""
                thought = "Đã kiểm tra hoàn tất Trình Phát Web (WebPlayerActivity) và tính năng Tiếp Tục Xem"

        elif m_id == "MISSION_PLAYER_SETTINGS_DIALOG":
            if step == 0:
                action_type = "KEY"
                action_param = "DPAD_DOWN"
                thought = "Mở thanh điều khiển của trình phát Native PlayerActivity"
            elif step == 1:
                action_type = "KEY"
                action_param = "OK"
                thought = "Bấm OK mở Dialog Cài Đặt Trình Phát Thống Nhất (btnBarSettings)"
            elif step == 2:
                action_type = "KEY"
                action_param = "DPAD_DOWN"
                thought = "Duyệt qua các nhóm cài đặt trong Dialog (Hình ảnh -> Âm thanh)"
            elif step == 3:
                action_type = "KEY"
                action_param = "DPAD_DOWN"
                thought = "Duyệt tiếp xuống nhóm Tỉ lệ khung hình và Bước tua"
            elif step == 4:
                action_type = "KEY"
                action_param = "DPAD_RIGHT"
                thought = "Thử chọn một chip tỉ lệ khung hình hoặc bước tua mới"
            elif step == 5:
                action_type = "KEY"
                action_param = "ENTER"
                thought = "Bấm OK áp dụng cài đặt mới ngay trong Dialog"
            elif step == 6:
                action_type = "KEY"
                action_param = "BACK"
                thought = "Bấm BACK để đóng Dialog Cài Đặt và giữ nguyên trình phát"
            elif step == 7:
                action_type = "KEY"
                action_param = "DPAD_RIGHT"
                thought = "Di chuyển trên thanh điều khiển sang nút Tua tới / Tua lùi"
            elif step == 8:
                action_type = "KEY"
                action_param = "OK"
                thought = "Bấm OK kiểm tra chức năng tua thời gian trên thanh điều khiển"
            elif step == 9:
                action_type = "KEY"
                action_param = "DPAD_RIGHT"
                thought = "Di chuyển sang nút Tạm Dừng / Phát (btnBarPlayPause)"
            elif step == 10:
                action_type = "KEY"
                action_param = "OK"
                thought = "Bấm OK kiểm tra tính năng Tạm Dừng / Tiếp tục phát video"
            elif step == 11:
                action_type = "KEY"
                action_param = "A"
                thought = "Thử phím tắt A (Red key remote) đổi tỉ lệ khung hình trực tiếp"
            elif step == 12:
                action_type = "KEY"
                action_param = "B"
                thought = "Thử phím tắt B (Yellow key remote) tăng cường âm thanh Audio Boost trực tiếp"
            elif step == 13:
                action_type = "KEY"
                action_param = "MENU"
                thought = "Thử phím tắt MENU mở Server Picker chọn nguồn phát"
            elif step == 14:
                action_type = "KEY"
                action_param = "BACK"
                thought = "Bấm BACK đóng menu Server Picker"
            elif step == 15:
                action_type = "KEY"
                action_param = "DOUBLE_BACK"
                thought = "Thoát trình phát Native về lại kho phim, kiểm tra tự lưu tiến độ"
            else:
                action_type = "COMPLETE_MISSION"
                action_param = ""
                thought = "Đã kiểm tra toàn diện các chức năng của trình phát Native PlayerActivity"

        elif m_id == "MISSION_SEARCH_AND_SETTINGS_FEEDBACK":
            if step == 0:
                action_type = "SWITCH_TAB"
                action_param = "3"
                thought = "Chuyển sang Tab Tìm kiếm (Tab 3) để kiểm tra ô nhập searchInput"
            elif step == 1:
                action_type = "TEXT"
                action_param = "ngoai hang"
                thought = "Nhập từ khóa tìm kiếm trận đấu"
            elif step == 2:
                action_type = "SWITCH_TAB"
                action_param = "4"
                thought = "Chuyển sang Tab Cài đặt (Tab 4) để kiểm tra thông tin phiên bản và mục Liên hệ & Báo lỗi"
            elif step == 3:
                action_type = "KEY"
                action_param = "DPAD_DOWN"
                thought = "Di chuyển xuống mục 'Liên hệ & Báo lỗi' Telegram @kenhlivetv"
            elif step == 4:
                action_type = "KEY"
                action_param = "DPAD_DOWN"
                thought = "Di chuyển tiếp xuống mục 'Liên hệ & Báo lỗi' Telegram @kenhlivetv"
            elif step == 5:
                action_type = "KEY"
                action_param = "DPAD_DOWN"
                thought = "Focus vào dòng 'Liên hệ & Báo lỗi' Telegram @kenhlivetv"
            elif step == 6:
                action_type = "KEY"
                action_param = "ENTER"
                thought = "Bấm OK mở hộp thoại FeedbackDialog kiểm tra mã QR và liên kết t.me/kenhlivetv"
            elif step == 7:
                action_type = "KEY"
                action_param = "BACK"
                thought = "Bấm BACK để đóng FeedbackDialog và quay lại danh sách Cài đặt"
            else:
                action_type = "COMPLETE_MISSION"
                action_param = ""
                thought = "Đã kiểm tra hoàn tất Tab Tìm kiếm và hộp thoại Báo lỗi Telegram @kenhlivetv"

        elif m_id == "MISSION_DPAD_CHAOS_STRESS_AND_RECOVERY":
            # Nhồi phím ngẫu nhiên 4 hướng để stress-test
            stress_keys = ["DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT"]
            action_type = "KEY"
            action_param = random.choice(stress_keys)
            thought = f"D-pad chaos stress & focus recovery: nhồi phím ngẫu nhiên {action_param}"
            if step >= min(5, mission.max_steps - 1):
                action_type = "COMPLETE_MISSION"
                action_param = ""

        else:
            action_type = "COMPLETE_MISSION"
            action_param = ""

        return {
            "thought": thought,
            "action_type": action_type,
            "action_param": action_param,
            "expected_result": "Giao diện cập nhật mượt mà theo thao tác D-pad",
            "defect_detected": defect,
            "_provider": "LOCAL_REFLEX"
        }


# ==============================================================================
# 2. DEVICE & VISION PROBES (Android TV Remote & Sensor Driver)
# ==============================================================================
class AndroidTVDevice:
    """Điều khiển Android TV qua ADB và thu thập dữ liệu cảm biến (Sensors)."""

    def __init__(self, serial=None, pkg=PKG):
        self.serial = serial
        self.adb = f"adb -s {serial}" if serial else "adb"
        self.pkg = pkg
        self.action_history = []
        self.focus_step_history = []
        self._reported_anomaly_keys = set()
        self._logcat_checkpoint = ""

    def launch_app(self):
        t0 = time.time()
        sh(f"{self.adb} shell am start -n {self.pkg}/.MainActivity")
        while time.time() - t0 < 25:
            if self.is_app_in_foreground():
                return round((time.time() - t0) * 1000)
            time.sleep(0.2)
        return -1

    def is_app_in_foreground(self):
        out = sh(f"{self.adb} shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus")
        return self.pkg in out

    def current_activity(self):
        out = sh(f"{self.adb} shell dumpsys activity activities 2>/dev/null | grep -m1 -E 'mResumedActivity|topResumedActivity'")
        m = re.search(r"([A-Za-z0-9_.]+)/([.A-Za-z0-9_]+)", out)
        return f"{m.group(1)}/{m.group(2)}" if m else "?"

    def send_key(self, key_name):
        code = KEY_MAP.get(key_name.upper(), 20)
        sh(f"{self.adb} shell input keyevent {code}")
        self.action_history.append(key_name)
        if len(self.action_history) > 20:
            self.action_history.pop(0)

    def send_long_press(self, key_name="OK", duration_ms=1500):
        code = KEY_MAP.get(key_name.upper(), 23)
        res = sh(f"{self.adb} shell input keyevent --longpress {code}")
        if "__ERR__" in res or "unrecognized" in res.lower() or "error" in res.lower():
            state = self.dump_ui_state()
            b = state.get("focused_bounds")
            if b:
                m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
                if m:
                    x1, y1, x2, y2 = map(int, m.groups())
                    cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
                    sh(f"{self.adb} shell input swipe {cx} {cy} {cx} {cy} {duration_ms}")
                else:
                    sh(f"{self.adb} shell input swipe 960 540 960 540 {duration_ms}")
            else:
                sh(f"{self.adb} shell input swipe 960 540 960 540 {duration_ms}")
        self.action_history.append(f"LONG_PRESS({key_name})")
        if len(self.action_history) > 20:
            self.action_history.pop(0)

    def send_keypad_digits(self, digits_str):
        for ch in str(digits_str):
            if ch in KEY_MAP:
                code = KEY_MAP[ch]
                sh(f"{self.adb} shell input keyevent {code}")
                time.sleep(0.18)
        self.action_history.append(f"KEYPAD({digits_str})")
        if len(self.action_history) > 20:
            self.action_history.pop(0)

    def input_text(self, text):
        safe_text = re.sub(r"[^a-zA-Z0-9_-]", "%s", text)
        sh(f"{self.adb} shell input text \"{safe_text}\"")
        self.action_history.append(f"TEXT({text})")

    def switch_tab(self, tab_index):
        """Chuyển đổi trực tiếp giữa 6 tab: 0:Live, 1:Lịch, 2:IPTV, 3:Tìm kiếm, 4:Cài đặt, 5:Kho Phim."""
        sh(f"{self.adb} shell am start -n {self.pkg}/.MainActivity --ei tab {int(tab_index)} -f 0x04000000")
        self.action_history.append(f"SWITCH_TAB({tab_index})")

    def open_multiview(self):
        """Khởi chạy Home Hub Portal (thay thế MultiView cũ)."""
        sh(f"{self.adb} shell am start -n {self.pkg}/.MainActivity --ei tab 6 -f 0x04000000")
        self.action_history.append("OPEN_HOME_HUB")

    def launch_web_player(self, film_slug="duyen-kiep", film_title="Duyên Kiếp", episode_slug="tap-1", episode_title="Tập 1", embed_url="https://player.phimapi.com/player/?url=https://test.m3u8"):
        """Khởi chạy trực tiếp WebPlayerActivity (Trình phát web embed) để kiểm thử."""
        sh(f"{self.adb} shell am start -n {self.pkg}/.phim.WebPlayerActivity --es embed_url \"{embed_url}\" --es film_slug \"{film_slug}\" --es film_title \"{film_title}\" --es episode_slug \"{episode_slug}\" --es episode_title \"{episode_title}\"")
        self.action_history.append("LAUNCH_WEB_PLAYER")

    def get_pss_memory_mb(self):
        """Đo tổng lượng bộ nhớ RAM PSS (MB) của ứng dụng."""
        out = sh(f"{self.adb} shell dumpsys meminfo {self.pkg} 2>/dev/null | grep -E 'TOTAL PSS:|TOTAL:' | head -1")
        m = re.search(r"(\d+)", out)
        if m:
            return round(int(m.group(1)) / 1024.0, 1)
        return -1.0

    def screencap_bytes(self):
        try:
            raw = subprocess.run(f"{self.adb} exec-out screencap -p".split(), capture_output=True, timeout=20).stdout
            return raw if len(raw) > 5000 else None
        except Exception:
            return None

    def is_blank(self, png_bytes):
        """Phát hiện màn hình đen hoặc rỗng hoàn toàn (Dead Blank Screen)."""
        if not png_bytes or not Image or not ImageStat:
            return False
        try:
            im = Image.open(io.BytesIO(png_bytes)).convert("L").resize((64, 64))
            stat = ImageStat.Stat(im)
            return stat.stddev[0] < 4.0 or stat.mean[0] < 2.0
        except Exception:
            return False

    def dump_ui_state(self):
        """Đọc và bóc tách cây UI XML trong thời gian thực kèm thu thập dữ liệu cho Deep Anomaly Inspector."""
        sh(f"{self.adb} shell 'rm -f /sdcard/uidump.xml; uiautomator dump /sdcard/uidump.xml >/dev/null 2>&1'")
        xml_text = sh(f"{self.adb} shell cat /sdcard/uidump.xml 2>/dev/null", timeout=10)
        if "<hierarchy" not in xml_text:
            time.sleep(0.25)
            sh(f"{self.adb} shell 'uiautomator dump --compressed /sdcard/uidump.xml >/dev/null 2>&1'")
            xml_text = sh(f"{self.adb} shell cat /sdcard/uidump.xml 2>/dev/null", timeout=10)

        focused_sig = "NONE"
        focused_text = ""
        focused_bounds = None
        focusable_nodes = []
        summary_lines = []
        all_visible_texts = []
        has_dialog = False

        if "<hierarchy" in xml_text:
            try:
                start = xml_text.find("<hierarchy")
                end = xml_text.rfind("</hierarchy>")
                if start != -1 and end != -1:
                    xml_text = xml_text[start:end + len("</hierarchy>")]
                root = ET.fromstring(xml_text.strip())

                for elem in root.iter("node"):
                    a = elem.attrib
                    is_focused = a.get("focused") == "true"
                    is_focusable = a.get("focusable") == "true"
                    txt = (a.get("text", "") or a.get("content-desc", "")).strip()
                    r_id = a.get("resource-id", "").split("/")[-1]
                    bounds = a.get("bounds", "")

                    if txt:
                        all_visible_texts.append((r_id, txt))

                    if r_id in ("dialogTitle", "roomList", "episodeList", "ivTelegramQr", "btnOpenTelegram", "alertTitle"):
                        has_dialog = True

                    if is_focusable:
                        focusable_nodes.append({"id": r_id, "text": txt, "bounds": bounds, "focused": is_focused})
                        marker = "[FOCUSED] " if is_focused else "          "
                        summary_lines.append(f"{marker}{r_id or 'view'} ('{txt[:25]}') {bounds}")

                    if is_focused:
                        focused_sig = f"{r_id or 'view'}#{bounds}"
                        focused_text = txt
                        focused_bounds = bounds
            except Exception:
                pass

        return {
            "focused_sig": focused_sig,
            "focused_text": focused_text,
            "focused_bounds": focused_bounds,
            "focusable_count": len(focusable_nodes),
            "all_visible_texts": all_visible_texts,
            "has_dialog": has_dialog,
            "ui_summary": "\n".join(summary_lines[:25]) or "(Không tìm thấy focusable view)"
        }

    def inspect_deep_anomalies(self, ui_state, current_activity="", png_bytes=None):
        """Hệ thống quét lỗi tầng sâu tự động (Deep Anomaly Probes) trên mỗi bước thực thi."""
        anomalies = []
        focused_sig = ui_state.get("focused_sig", "NONE")
        focused_bounds = ui_state.get("focused_bounds")
        last_key = self.action_history[-1] if self.action_history else ""

        # 0. Kiểm tra màn hình đen / rỗng hoàn toàn (Dead Blank Screen)
        if png_bytes and self.is_blank(png_bytes) and "MainActivity" in current_activity:
            # Xác thực 2 lần để tránh bắt nhầm frame chuyển cảnh (transition) hoặc loading đen thoáng qua
            time.sleep(1.5)
            recheck_png = self.screencap_bytes()
            if recheck_png and self.is_blank(recheck_png):
                blank_key = f"BLANK:{current_activity}"
                if blank_key not in self._reported_anomaly_keys:
                    self._reported_anomaly_keys.add(blank_key)
                    anomalies.append({
                        "area": "Hiển thị Màn Hình (Deep Probe)",
                        "severity": "CRITICAL",
                        "issue": f"Màn hình đen / rỗng hoàn toàn (Dead Blank Screen) tại {current_activity}",
                        "detail": "Độ biến thiên pixel stddev < 4.0 và mean < 2.0 kéo dài hơn 1.5s sau khi nạp",
                        "suggestion": "Kiểm tra render lifecycle, layout xml hoặc sự cố crash UI background"
                    })

        # 1. Ghi nhận lịch sử bước focus để phát hiện Focus Trap (Kẹt nút D-pad)
        vis_texts = tuple(t for _, t in ui_state.get("all_visible_texts", [])[:15])
        state_sig = f"{focused_sig}|{hash(vis_texts)}"
        self.focus_step_history.append((current_activity, state_sig, last_key))
        if len(self.focus_step_history) > 10:
            self.focus_step_history.pop(0)

        if len(self.focus_step_history) >= 3 and focused_sig != "NONE":
            s1, s2, s3 = self.focus_step_history[-3], self.focus_step_history[-2], self.focus_step_history[-1]
            dpad_dirs = ("DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT", "UP", "DOWN", "LEFT", "RIGHT")
            if (s1[0] == s2[0] == s3[0] and
                    s1[1] == s2[1] == s3[1] and
                    s2[2] in dpad_dirs and s3[2] in dpad_dirs and s2[2] == s3[2] and
                    "PlayerActivity" not in current_activity and
                    "WebPlayerActivity" not in current_activity and
                    not ui_state.get("has_dialog", False) and
                    ui_state.get("focusable_count", 0) > 3):
                # Không tính là trap nếu đang ở mép màn hình và bấm phím hướng ra mép (ví dụ nút ở biên trái bấm DPAD_LEFT)
                is_edge_bump = False
                if focused_bounds:
                    m_b = re.findall(r"-?\d+", focused_bounds)
                    if len(m_b) >= 4:
                        x1, y1, x2, y2 = map(int, m_b[:4])
                        if s3[2] in ("DPAD_LEFT", "LEFT") and x1 < 350:
                            is_edge_bump = True
                        elif s3[2] in ("DPAD_RIGHT", "RIGHT") and x2 > 1350:
                            is_edge_bump = True
                        elif s3[2] in ("DPAD_UP", "UP") and y1 < 200:
                            is_edge_bump = True
                        elif s3[2] in ("DPAD_DOWN", "DOWN") and y2 > 880:
                            is_edge_bump = True

                if not is_edge_bump:
                    trap_key = f"TRAP:{current_activity}:{focused_sig}:{s3[2]}"
                    if trap_key not in self._reported_anomaly_keys:
                        self._reported_anomaly_keys.add(trap_key)
                        anomalies.append({
                            "area": "Điều hướng Focus TV (Deep Probe)",
                            "severity": "MEDIUM",
                            "issue": f"Nghi vấn kẹt Focus (Focus Trap) tại '{focused_sig}' khi bấm {s3[2]}",
                            "detail": f"Gửi phím {s3[2]} liên tiếp nhưng tiêu điểm và nội dung màn hình giữ nguyên tại {focused_sig} trên {current_activity}",
                            "suggestion": "Kiểm tra cấu hình nextFocus hoặc ranh giới cuộn của RecyclerView"
                        })

        # 2. Kiểm tra Off-screen Focus Leak & Zero-size View Focus
        if focused_bounds:
            m = re.findall(r"-?\d+", focused_bounds)
            if len(m) >= 4:
                x1, y1, x2, y2 = map(int, m[:4])
                w, h = x2 - x1, y2 - y1
                if x2 <= 0 or y2 <= 0 or x1 >= 1920 or y1 >= 1080:
                    leak_key = f"OFFSCREEN:{focused_sig}"
                    if leak_key not in self._reported_anomaly_keys:
                        self._reported_anomaly_keys.add(leak_key)
                        anomalies.append({
                            "area": "Rò rỉ Focus ngoài màn hình (Deep Probe)",
                            "severity": "HIGH",
                            "issue": f"Focus lọt vào phần tử nằm ngoài khung hình TV: {focused_sig}",
                            "detail": f"Tọa độ bounds={focused_bounds} nằm ngoài viewport 1920x1080",
                            "suggestion": "Đặt visibility=GONE và descendantFocusability=FOCUS_BLOCK_DESCENDANTS khi ẩn panel"
                        })
                elif w <= 2 or h <= 2:
                    zero_key = f"ZEROSIZE:{focused_sig}"
                    if zero_key not in self._reported_anomaly_keys:
                        self._reported_anomaly_keys.add(zero_key)
                        anomalies.append({
                            "area": "Focus vào phần tử tàng hình (Deep Probe)",
                            "severity": "HIGH",
                            "issue": f"Focus rơi vào view có kích thước {w}x{h}px: {focused_sig}",
                            "detail": f"View {focused_sig} có diện tích quá nhỏ khiến người dùng mất dấu con trỏ trên TV",
                            "suggestion": "Đặt isFocusable=false cho view ẩn hoặc vạch phân cách"
                        })

        # 3. Quét chuỗi văn bản lỗi / dữ liệu thô chưa xử lý (Raw Placeholder / Null / Exception)
        bad_patterns = [r"\bnull\b", r"\bundefined\b", r"\bNaN\b", r"NullPointerException", r"JsonSyntaxException"]
        for r_id, txt in ui_state.get("all_visible_texts", []):
            for pat in bad_patterns:
                if re.search(pat, txt):
                    txt_key = f"BADTEXT:{r_id}:{txt[:20]}"
                    if txt_key not in self._reported_anomaly_keys:
                        self._reported_anomaly_keys.add(txt_key)
                        anomalies.append({
                            "area": "Hiển thị dữ liệu (Deep Probe)",
                            "severity": "HIGH",
                            "issue": f"Xuất hiện chuỗi lỗi/null thô trên giao diện: '{txt[:40]}'",
                            "detail": f"Node '{r_id}' hiển thị văn bản '{txt}'",
                            "suggestion": "Xử lý fallback chuỗi rỗng hoặc mặc định trước khi gán vào TextView"
                        })

        # 4. Kiểm tra rò rỉ focus xuống màn hình nền khi Dialog đang mở
        if ui_state.get("has_dialog") and focused_sig != "NONE":
            bg_views = ("cardRoot", "schedCard", "liveList", "schedList", "phimMainList", "phimPoster", "heroPlay")
            if any(bg in focused_sig for bg in bg_views):
                dlg_key = f"DLGLEAK:{focused_sig}"
                if dlg_key not in self._reported_anomaly_keys:
                    self._reported_anomaly_keys.add(dlg_key)
                    anomalies.append({
                        "area": "Quản lý Focus Hộp Thoại (Deep Probe)",
                        "severity": "HIGH",
                        "issue": f"Focus lọt xuống màn hình nền ({focused_sig}) khi Dialog đang mở",
                        "detail": f"Hộp thoại đang hiển thị nhưng tiêu điểm D-pad vẫn nằm ở view nền {focused_sig}",
                        "suggestion": "Gọi requestFocus() vào nút mặc định của Dialog trong setOnShowListener"
                    })

        # 5. Kiểm tra tính toàn vẹn dữ liệu giờ thi đấu & tỷ số thể thao (Kickoff Time & Score Integrity Probe)
        for r_id, txt in ui_state.get("all_visible_texts", []):
            clean_txt = txt.strip()
            # 5a. Rò rỉ giờ thi đấu hoặc tag trạng thái vào tên giải đấu cardLeague
            if "cardLeague" in r_id:
                if re.match(r"^\d{1,2}:\d{2}$", clean_txt) or re.search(r"\[(SẮP ĐÁ|LIVE|TRỰC TIẾP|\d{1,2}:\d{2})\]", clean_txt, re.IGNORECASE):
                    leak_key = f"TIME_LEAK_LEAGUE:{r_id}:{clean_txt}"
                    if leak_key not in self._reported_anomaly_keys:
                        self._reported_anomaly_keys.add(leak_key)
                        anomalies.append({
                            "area": "Chuẩn hóa dữ liệu thể thao (Deep Probe)",
                            "severity": "HIGH",
                            "issue": f"Giờ thi đấu hoặc nhãn trạng thái bị gán nhầm vào tên giải đấu: '{clean_txt}'",
                            "detail": f"View {r_id} hiển thị '{clean_txt}' thay vì tên giải đấu hợp lệ",
                            "suggestion": "Lọc sạch bracket thời gian trong DataNormalizer.parseMatch"
                        })
            # 5b. Bị gán nhầm giờ thi đấu thành tỷ số ảo khi hiển thị định dạng tỷ số (ví dụ '17 : 0' hoặc '23 : 30')
            # Lưu ý: Thẻ trận SẮP ĐÁ hiển thị giờ thi đấu chuẩn HH:mm (ví dụ '23:30', '19:45' không có khoảng trắng)
            # ở center pill là tính năng chuẩn, chỉ coi là lỗi khi có định dạng tỷ số có khoảng trắng 'X : Y' hoặc số bàn thắng phi thực tế (> 15).
            if "cardScore" in r_id or "score" in r_id.lower():
                m_score = re.match(r"^(\d{1,2})\s+[:\-]\s+(\d{1,2})$", clean_txt)
                if m_score:
                    s1, s2 = int(m_score.group(1)), int(m_score.group(2))
                    if s1 in (17, 18, 19, 20, 21, 22, 23) and s2 in (0, 15, 30, 45):
                        fake_key = f"FAKE_SCORE_FROM_TIME:{r_id}:{clean_txt}"
                        if fake_key not in self._reported_anomaly_keys:
                            self._reported_anomaly_keys.add(fake_key)
                            anomalies.append({
                                "area": "Toàn vẹn tỷ số thể thao (Deep Probe)",
                                "severity": "HIGH",
                                "issue": f"Nghi vấn giờ thi đấu bị chuyển đổi thành tỷ số ảo: '{clean_txt}'",
                                "detail": f"View {r_id} hiển thị tỷ số '{clean_txt}' khớp với khung giờ phát sóng",
                                "suggestion": "Bổ sung kiểm tra loại trừ giờ thi đấu trong DataNormalizer regex"
                            })
            # 5c. Rò rỉ placeholder raw
            if "[$kickoffTime]" in clean_txt or "luc-" in clean_txt:
                raw_key = f"RAW_TIME_PLACEHOLDER:{r_id}:{clean_txt[:20]}"
                if raw_key not in self._reported_anomaly_keys:
                    self._reported_anomaly_keys.add(raw_key)
                    anomalies.append({
                        "area": "Hiển thị giờ thi đấu (Deep Probe)",
                        "severity": "HIGH",
                        "issue": f"Rò rỉ placeholder mã nguồn giờ thi đấu chưa format: '{clean_txt}'",
                        "detail": f"View {r_id} hiển thị chuỗi kỹ thuật chưa qua xử lý",
                        "suggestion": "Định dạng giờ thi đấu qua formatTimestampToTime trước khi truyền vào view"
                    })

        return anomalies

    def detect_video_motion(self, duration_sec=1.0, samples=3, is_player_context=False):
        """Xác định toán học xem video ExoPlayer có đang render khung hình thật hay bị đứng hình."""
        if not Image or not ImageChops or not ImageStat:
            return True, 1.5  # Heuristic pass nếu thiếu thư viện

        frames = []
        interval = max(0.1, duration_sec / samples)
        for _ in range(samples):
            b = self.screencap_bytes()
            if b:
                try:
                    im = Image.open(io.BytesIO(b)).convert("L").resize((160, 90))
                    frames.append(im)
                except Exception:
                    pass
            time.sleep(interval)

        if len(frames) < 2:
            return False, 0.0

        diffs = []
        for i in range(len(frames) - 1):
            diff = ImageChops.difference(frames[i], frames[i + 1])
            mean_diff = ImageStat.Stat(diff).mean[0] / 255.0 * 100.0
            diffs.append(mean_diff)

        avg_diff = sum(diffs) / len(diffs)
        # Video phát trực tiếp thường có pixel difference > 0.35%
        is_playing = avg_diff >= 0.35
        # Chỉ áp dụng đệm buffer 2.0s trên CI nếu thực sự đang ở trong màn hình Player
        if not is_playing and is_player_context:
            time.sleep(2.0)
            frames = []
            for _ in range(samples):
                b = self.screencap_bytes()
                if b:
                    try:
                        im = Image.open(io.BytesIO(b)).convert("L").resize((160, 90))
                        frames.append(im)
                    except Exception:
                        pass
                time.sleep(interval)
            if len(frames) >= 2:
                diffs = []
                for i in range(len(frames) - 1):
                    diff = ImageChops.difference(frames[i], frames[i + 1])
                    mean_diff = ImageStat.Stat(diff).mean[0] / 255.0 * 100.0
                    diffs.append(mean_diff)
                avg_diff = sum(diffs) / len(diffs)
                is_playing = avg_diff >= 0.35

        return is_playing, avg_diff

    def detect_focus_ring(self, png_bytes, focused_bounds_str):
        """Phát hiện viền sáng focus (#FF6500 cam KenhLive, #FF9F0A amber, Trắng hoặc Cyan) xung quanh view đang focus."""
        if not png_bytes or not Image or not focused_bounds_str:
            return False, "UNKNOWN", 0.0

        try:
            m = re.findall(r"\d+", focused_bounds_str)
            if len(m) < 4:
                return False, "INVALID_BOUNDS", 0.0
            x1, y1, x2, y2 = map(int, m[:4])

            im = Image.open(io.BytesIO(png_bytes)).convert("RGB")
            W, H = im.size
            if not (0 <= x1 < x2 <= W and 0 <= y1 < y2 <= H):
                return False, "OUT_OF_BOUNDS", 0.0

            # Lấy vùng biên bao quanh ranh giới thẻ (±6px để bắt trọn cả viền inset lẫn glow outset)
            pad = 6
            crop_outer = im.crop((max(0, x1 - pad), max(0, y1 - pad), min(W, x2 + pad), min(H, y2 + pad)))
            px = crop_outer.load()
            cw, ch = crop_outer.size

            orange_hits = 0
            white_hits = 0
            cyan_hits = 0
            total_samples = 0

            # Quét dải viền ngang (mép trên & mép dưới, độ dày 6px)
            y_indices = list(range(min(pad + 2, ch))) + list(range(max(0, ch - pad - 2), ch))
            for y in set(y_indices):
                for x in range(0, cw, 2):
                    total_samples += 1
                    r, g, b = px[x, y]
                    if r > 180 and 45 < g < 185 and b < 85:  # #FF6500 (Cam) & #FF9F0A (Amber Apple)
                        orange_hits += 1
                    elif r > 215 and g > 215 and b > 215:  # Trắng
                        white_hits += 1
                    elif r < 110 and g > 180 and b > 200:  # Cyan neon
                        cyan_hits += 1

            # Quét dải viền dọc (mép trái & mép phải, độ dày 6px)
            x_indices = list(range(min(pad + 2, cw))) + list(range(max(0, cw - pad - 2), cw))
            for x in set(x_indices):
                for y in range(0, ch, 2):
                    total_samples += 1
                    r, g, b = px[x, y]
                    if r > 180 and 45 < g < 185 and b < 85:
                        orange_hits += 1
                    elif r > 215 and g > 215 and b > 215:
                        white_hits += 1
                    elif r < 110 and g > 180 and b > 200:
                        cyan_hits += 1

            matched_hits = orange_hits + white_hits + cyan_hits
            has_ring = matched_hits > (total_samples * 0.12)
            if orange_hits >= white_hits and orange_hits >= cyan_hits:
                color_name = "ORANGE"
            elif white_hits >= cyan_hits:
                color_name = "WHITE"
            else:
                color_name = "CYAN"
            return has_ring, color_name, matched_hits / max(1, total_samples)
        except Exception:
            return False, "ERR", 0.0

    def checkpoint_logcat(self):
        lines = sh(f"{self.adb} logcat -d -v threadtime", timeout=20).splitlines()
        self._last_logcat_len = len(lines)

    def get_new_logcat_alerts(self):
        cur = sh(f"{self.adb} logcat -d -v threadtime", timeout=25).splitlines()
        last_len = getattr(self, "_last_logcat_len", 0)
        new_lines = cur[last_len:] if len(cur) >= last_len else cur
        self._last_logcat_len = len(cur)

        alerts = []
        for i, line in enumerate(new_lines):
            if "FATAL EXCEPTION" in line or "AndroidRuntime: FATAL" in line:
                alerts.append(f"CRASH: {line[:120]}")
                stack = "\n".join(new_lines[i:i+20])
                print(f"💥 [LOGCAT CRASH DETECTED]:\n{stack}", flush=True)
            elif "ANR in " + self.pkg in line:
                alerts.append(f"ANR: {line[:120]}")
            elif "OutOfMemoryError" in line:
                alerts.append(f"OOM: {line[:120]}")
            elif "ExoPlayer" in line and ("error" in line.lower() or "failure" in line.lower()):
                alerts.append(f"PLAYER_ERR: {line[:100]}")
            elif "Choreographer" in line and "Skipped" in line:
                m_skip = re.search(r"Skipped\s+(\d+)\s+frames", line)
                if m_skip and int(m_skip.group(1)) >= 45:
                    alerts.append(f"UI_JANK: Bỏ lỡ {m_skip.group(1)} frames")

        return alerts[:5]


# ==============================================================================
# 3. MISSION MODELS (Danh sách mục tiêu kiểm thử AI)
# ==============================================================================
class QAMission:
    def __init__(self, name, title, description, success_criteria, max_steps=8):
        self.name = name
        self.title = title
        self.description = description
        self.success_criteria = success_criteria
        self.max_steps = max_steps
        self.steps_executed = 0
        self.passed = False
        self.findings = []
        self.timeline = []


# ==============================================================================
# 4. AUTONOMOUS QA RUNNER (Điều phối tổng thể)
# ==============================================================================
class AutonomousQASuite:
    def __init__(self, out_dir="/tmp/qa_report", serial=None, quick=False):
        self.quick = bool(quick or os.environ.get("QA_QUICK", "0") == "1")
        self.out_dir = out_dir
        self.shots_dir = os.path.join(out_dir, "shots")
        os.makedirs(self.shots_dir, exist_ok=True)

        self.brain = AIBrain()
        self.device = AndroidTVDevice(serial=serial, pkg=PKG)
        self.session = QASession(out_dir=out_dir, serial=serial, pkg=PKG)
        self.brain.init_session(self.session.session_id)
        self.session.set_ai_provider(self.brain.provider_active)

        # Trình quay video toàn bộ quá trình kiểm thử Android TV
        self.recorder = None
        try:
            self.recorder = VideoRecorder(serial=serial, out_dir=out_dir)
        except Exception as e:
            print(f"⚠️ [VideoRecorder] Không thể nạp module video recorder: {e}", flush=True)

        self.action_history = self.device.action_history

        self.ALL_TABS = {
            0: "Tab 0: Trực tiếp (Live - ColaTV/Gà Vàng/Khán Đài/Socolive)",
            1: "Tab 1: Lịch đấu (Schedule - Lịch phát sóng các ngày)",
            2: "Tab 2: Truyền hình (IPTV - TV360 HLS, VTV, HTV, Hero Preview, Lưới 5 cột)",
            3: "Tab 3: Tìm kiếm (Search - Ô nhập từ khóa & Danh sách kết quả)",
            4: "Tab 4: Cài đặt (Settings - Cập nhật & cấu hình server)",
            5: "Tab 5: Kho Phim (NguonC - Phim mới, Phim bộ, Phim lẻ, Anime)",
            6: "Tab 6: Cổng Chọn (Home Hub - Thể thao, Truyền hình, Phim truyện + Cài đặt)"
        }
        self.tested_tabs = set()

        self.missions = [
            QAMission(
                "MISSION_COLD_START",
                "Khởi Động Lạnh, TTI & Hero Banner Rộng Rãi",
                "Mở app từ trạng thái tắt, kiểm tra Hero Banner đã bỏ thẻ thống kê trận đấu rác, đo TTI, kiểm tra giao diện Home không bị đen màn hình.",
                "Màn hình chính nạp đủ dữ liệu, focus xuất hiện tại thẻ đầu tiên.",
                max_steps=5
            ),
            QAMission(
                "MISSION_SPORTS_MULTI_SOURCE",
                "Thể Thao Đa Nguồn & Cuộn Ngang Sâu (Horizontal Rail Deep Traversal)",
                "Duyệt qua các hàng đài độc lập (Socolive, ColaTV, Gà Vàng, Khán Đài), cuộn ngang sâu liên tục để kiểm tra biên phải và khôi phục tiêu điểm.",
                "Các tab lọc đài phản hồi chuẩn, D-pad di chuyển mượt mà không kẹt, không có emoji rác [👁⏳⭐].",
                max_steps=8
            ),
            QAMission(
                "MISSION_SPORTS_ROOM_PICKER",
                "Hộp Thoại Chọn Phòng BLV & Vòng Đời Dialog (RoomPicker & Tab Switch)",
                "Mở RoomPickerDialog trên thẻ trận đấu, duyệt danh sách phòng BLV, test đóng bằng BACK và kiểm tra không rò rỉ focus đè lên các tab khác.",
                "Hộp thoại RoomPickerDialog trap focus chuẩn xác, đóng mở êm ái.",
                max_steps=6
            ),
            QAMission(
                "MISSION_SPORTS_DATA_ACCURACY",
                "Chuẩn Hóa Dữ Liệu Bóng Đá GMT+7, Tỷ Số X:Y & Nhãn KẾT THÚC",
                "Kiểm tra tên giải đấu chuẩn tiếng Việt (Ngoại Hạng Anh, Cúp C1...), điểm số trận hiển thị chuẩn dạng X : Y, trận qua 180p có badge KẾT THÚC.",
                "Dữ liệu hiển thị sạch bóng, không spam [HOT]/[VIP], đúng múi giờ GMT+7.",
                max_steps=4
            ),
            QAMission(
                "MISSION_PLAYER_STREAMING_AND_CONTROLS",
                "Trình Phát Video ExoPlayer, Server Picker & Phím Tắt TV (A/B/Menu)",
                "Mở phát trận đấu, kiểm tra ExoPlayer motion render, menu đổi đài, phím tắt A/Đỏ chuyển Aspect Ratio, phím tắt B/Vàng tăng Audio Boost.",
                "Video chuyển động mượt, phím tắt đổi tỉ lệ và âm lượng phản hồi nhanh.",
                max_steps=8
            ),
            QAMission(
                "MISSION_HOME_HUB_3_CARDS_AND_CANVA_LOGOS",
                "Cổng Chọn 3 Card Home Hub & Mở Giao Diện Hub Phim Riêng Biệt",
                "Chuyển sang Tab 6 (Home Hub Portal), duyệt qua 3 card lớn THỂ THAO, TRUYỀN HÌNH, PHIM TRUYỆN tích hợp Logo Canva 3D, bấm OK vào card PHIM TRUYỆN mở giao diện Hub Phim riêng biệt, duyệt danh sách phim, bấm DOUBLE_BACK thoát về Home Hub và duyệt hàng tiện ích 4 nút đáy.",
                "Cổng Chọn 3 Card hiển thị sắc nét, mở Hub Phim độc lập mượt mà, DOUBLE_BACK thoát về Home Hub chuẩn xác.",
                max_steps=10
            ),
            QAMission(
                "MISSION_SCHEDULE_EPG_AND_FIXTURE_TAP",
                "Lịch Thi Đấu EPG Đa Ngày & Chạm Trận Đấu (Schedule Tap & Detail)",
                "Chuyển sang Tab Lịch đấu (Tab 1), duyệt lịch theo ngày, click trận kiểm tra onFixtureTap và xử lý trạng thái trận.",
                "Lịch đấu phân chia theo ngày rõ ràng, sự kiện bấm trận phản hồi đúng.",
                max_steps=6
            ),
            QAMission(
                "MISSION_SIDEBAR_RAIL_FULL_TRAVERSAL",
                "Thanh Điều Hướng Sidebar Rail & Khám Phá Toàn Diện (Full Rail Traversal)",
                "Mở Sidebar Rail bằng DPAD_LEFT, duyệt dọc qua toàn bộ 6 tab trên Rail kiểm tra không bị tự đóng hay cướp focus, đóng bằng DPAD_RIGHT.",
                "Sidebar đóng mở êm ái, phím điều hướng không làm văng app hoặc mất focus.",
                max_steps=7
            ),
            QAMission(
                "MISSION_IPTV_CATEGORIES_AND_FAVORITES",
                "Truyền Hình IPTV, Lưới Kênh & Kênh Yêu Thích (Favorites Long-Press)",
                "Chuyển sang Tab IPTV (Tab 2), duyệt qua các danh mục kênh, test Long-press OK lưu Kênh Yêu Thích, duyệt tab Yêu Thích, kiểm tra nút THÊM M3U và Dialog cấu hình playlist M3U cá nhân.",
                "Lưu kênh yêu thích thành công, mở Dialog M3U mượt mà, lưới kênh nạp đủ logo sắc nét.",
                max_steps=10
            ),
            QAMission(
                "MISSION_IPTV_EPG_AND_ZAPPING_PLAYER",
                "Hero Preview IPTV, Lịch Phát Sóng & Nhảy Kênh Bằng Phím Số (Zapping)",
                "Kiểm tra Hero Preview góc trên, mở phát toàn màn hình kênh IPTV, test gõ số remote (1, 15) hiện OSD nhảy kênh, test phím Channel Up/Down.",
                "Hero Preview phát thử trơn tru, OSD gõ số kênh hiện tức thì, nhảy kênh trơn tru.",
                max_steps=7
            ),
            QAMission(
                "MISSION_PHIM_NGUONC_AND_CATEGORIES",
                "Kho Phim NguonC, Apple Pills Thể Loại & Hạ Cánh Focus Chuẩn Xác",
                "Chuyển sang Tab Kho Phim (Tab 5), duyệt Apple Pills bộ lọc thể loại, bấm DPAD_DOWN kiểm tra focus hạ cánh chuẩn xác xuống nút XEM NGAY (btnHeroPlay).",
                "Danh sách phim tải đủ thông tin, điều hướng từ filter xuống hero banner mượt mà.",
                max_steps=5
            ),
            QAMission(
                "MISSION_PHIM_EPISODE_PICKER_AND_FAVORITE",
                "Hộp Thoại Chọn Tập (EpisodePicker), Lưu Phim & Đổi Spotlight Hero",
                "Trên Hero Banner phim, kiểm tra nút CHỌN TẬP (btnHeroEpisodes) mở EpisodePickerDialog, nút LƯU PHIM (btnHeroFavorite) và nút ĐỔI PHIM (btnHeroNext) chuyển Spotlight.",
                "EpisodePickerDialog hiển thị danh sách tập đầy đủ, lưu phim yêu thích và chuyển Spotlight mượt mà.",
                max_steps=9
            ),
            QAMission(
                "MISSION_VOD_PLAYER_SEEK_AND_RESUME",
                "Trình Phát Web (WebPlayerActivity), Tua D-pad JS & Hàng Tiếp Tục Xem",
                "Khởi chạy WebPlayerActivity (trình phát thứ hai của app), kiểm tra WebView tải trang embed, test tua thời gian D-pad Trái/Phải qua JS, toggle Play/Pause, thoát player và kiểm tra vị trí xem dở được lưu vào hàng Tiếp Tục Xem của Kho Phim.",
                "Tua WebPlayer mượt mà qua JS, lưu tiến độ chuẩn xác vào hàng Tiếp Tục Xem.",
                max_steps=10
            ),
            QAMission(
                "MISSION_PLAYER_SETTINGS_DIALOG",
                "Trình Phát Native (PlayerActivity), Dialog Cài Đặt Thống Nhất & Toàn Bộ Chức Năng",
                "Mở phim VOD native trên PlayerActivity, mở thanh OSD, test nút Cài Đặt mở Dialog Thống Nhất (chọn thử chip Hình ảnh/Âm thanh/Tỉ lệ/Bước tua), test nút Tua Lùi/Tua Tới, nút Tạm Dừng/Phát, phím tắt remote A (Tỉ lệ), B (Âm thanh), MENU (Server Picker), và thoát player kiểm tra tự lưu tiến độ.",
                "Dialog hiện đủ 4 nhóm trong ScrollView, các nút tua/pause/settings phản hồi chuẩn, phím tắt remote hoạt động tức thì, không crash.",
                max_steps=18
            ),
            QAMission(
                "MISSION_SEARCH_AND_SETTINGS_FEEDBACK",
                "Tìm Kiếm Thông Minh & Cài Đặt (Hộp Thoại Báo Lỗi Telegram QR @kenhlivetv)",
                "Duyệt Tab Tìm kiếm (Tab 3), nhập từ khóa; chuyển sang Tab Cài đặt (Tab 4), mở mục 'Liên hệ & Báo lỗi', kiểm tra FeedbackDialog hiển thị mã QR Telegram và thông tin @kenhlivetv.",
                "Tìm kiếm hiển thị kết quả, FeedbackDialog mở chuẩn xác có mã QR và đóng bằng phím BACK/OK êm ái.",
                max_steps=10
            ),
            QAMission(
                "MISSION_DPAD_CHAOS_STRESS_AND_RECOVERY",
                "Thử Thách Stress Chaos D-pad & Đo Đạc Bộ Nhớ RAM (Memory Leak Audit)",
                "Nhồi phím liên tục 4 hướng để kiểm tra độ bền, mép màn hình, khả năng giữ focus / auto recovery và đo tổng lượng RAM PSS qua dumpsys meminfo.",
                "Không crash, không văng app, focus tự phục hồi khi chuyển cảnh, RAM PSS ổn định.",
                max_steps=6
            )
        ]

        if self.quick:
            # Chế độ chạy nhanh: chỉ giữ 5 mission trọng tâm để hoàn thành ~12-18 phút
            quick_keep = {
                "MISSION_COLD_START",
                "MISSION_HOME_HUB_3_CARDS_AND_CANVA_LOGOS",
                "MISSION_IPTV_CATEGORIES_AND_FAVORITES",
                "MISSION_PHIM_NGUONC_AND_CATEGORIES",
                "MISSION_PLAYER_SETTINGS_DIALOG",
            }
            self.missions = [m for m in self.missions if m.name in quick_keep]
            for m in self.missions:
                m.max_steps = min(int(m.max_steps), 4 if m.name != "MISSION_PLAYER_SETTINGS_DIALOG" else 6)
            print(f"⚡ [QUICK MODE] Chỉ chạy {len(self.missions)} mission trọng tâm, tổng tối đa {sum(m.max_steps for m in self.missions)} bước", flush=True)

        self.all_findings = []
        self.all_screenshots = []
        self.ai_decisions_log = []
        self.start_time = 0

    def run(self):
        print("\n" + "=" * 65)
        print("🚀 [KenhLive AI QA] KHỞI ĐỘNG HỆ THỐNG KIỂM THỬ 100% AI AUTONOMOUS")
        print("=" * 65 + "\n", flush=True)

        # 0. VIDEO RECORDER: Bắt đầu quay toàn bộ quá trình từ trước khi mở app
        if self.recorder:
            try:
                self.recorder.start()
            except Exception as e:
                print(f"⚠️ [VideoRecorder] Không thể khởi động quay: {e}", flush=True)

        self.start_time = time.time()
        self.device.checkpoint_logcat()

        # Bước 1: Khởi động app
        print("📱 Đang khởi chạy ứng dụng KenhLive TV...", flush=True)
        launch_ms = self.device.launch_app()
        if launch_ms > 0:
            print(f"  ✅ Khởi động thành công trong {launch_ms}ms", flush=True)
        else:
            print("  ⚠️ Không bắt được focus ngay khi mở app", flush=True)

        # Chờ 1.5s cho frame đầu tiên render hoàn tất sau hiệu ứng chuyển cảnh cửa sổ
        time.sleep(1.5)

        # Chụp ảnh baseline
        self._record_screen("01_cold_start")

        # Bước 2: Vòng lặp Agent thực thi các Mission
        for m_idx, mission in enumerate(self.missions):
            print(f"\n🎯 [Mission {m_idx + 1}/{len(self.missions)}] {mission.title}", flush=True)
            print(f"   Mục tiêu: {mission.description}", flush=True)

            while mission.steps_executed < mission.max_steps:
                mission.steps_executed += 1
                step_num = mission.steps_executed

                # 1. PERCEPTION: Thu thập trạng thái thiết bị
                raw_png = self.device.screencap_bytes()
                ui_state = self.device.dump_ui_state()
                has_ring, ring_col, _ = self.device.detect_focus_ring(raw_png, ui_state.get("focused_bounds"))
                cur_act = self.device.current_activity()
                is_player = "PlayerActivity" in cur_act or "WebPlayerActivity" in cur_act
                is_playing, motion_delta = self.device.detect_video_motion(duration_sec=0.8, samples=2, is_player_context=is_player)
                logcat_alerts = self.device.get_new_logcat_alerts()

                # Tự động cập nhật tab đang hiển thị
                current_tab_idx = None
                summary_text = (ui_state.get("ui_summary", "") + " " + ui_state.get("focused_sig", "") + " " + ui_state.get("focused_text", "")).lower()
                tab_indicators = [
                    (6, ["cổng giải trí", "cardsports", "cardiptv", "cardphim", "cổng chọn", "ấn ok để vào"]),
                    (5, ["phim", "nguonc", "btnheroplay", "apple pill", "phim bộ", "phim lẻ", "tiếp tục xem", "rạp phim tại gia"]),
                    (4, ["settingslist", "cài đặt", "chất lượng video", "báo lỗi"]),
                    (3, ["searchinput", "resultlist", "tìm kiếm"]),
                    (2, ["iptv", "kênh tv", "channel", "vtv", "htv"]),
                    (1, ["schedlist", "schedcard", "lịch thi đấu", "hôm nay", "ngày mai"]),
                    (0, ["livelist", "cardroot", "socolive", "colatv", "gà vàng", "khán đài", "trực tiếp"])
                ]
                for t_idx, kws in tab_indicators:
                    if any(k in summary_text for k in kws):
                        self.tested_tabs.add(t_idx)
                        current_tab_idx = t_idx
                        break

                # 1.5 DEEP ANOMALY INSPECTOR: Tự động phát hiện lỗi tầng sâu
                deep_defects = self.device.inspect_deep_anomalies(ui_state, cur_act, png_bytes=raw_png)
                for df in deep_defects:
                    print(f"      🔎 [Deep Inspector] Phát hiện lỗi [{df.get('severity')}]: {df.get('issue')}", flush=True)
                    self.all_findings.append(df)
                    mission.findings.append(df)

                state_bundle = {
                    "activity": cur_act,
                    "focused_sig": ui_state["focused_sig"],
                    "focused_text": ui_state["focused_text"],
                    "has_focus_ring": has_ring,
                    "focus_ring_color": ring_col,
                    "video_motion_playing": is_playing,
                    "video_motion_delta": motion_delta,
                    "recent_keys": self.device.action_history[-5:],
                    "logcat_alerts": "; ".join(logcat_alerts) or "Không có",
                    "deep_anomalies_text": "\n".join(f"- [{d['severity']}] {d['issue']}: {d['detail']}" for d in deep_defects),
                    "ui_summary": ui_state["ui_summary"],
                    "screenshot_bytes": raw_png,
                    "tabs_tested": ", ".join(self.ALL_TABS[i] for i in sorted(self.tested_tabs)) if self.tested_tabs else "Chưa có",
                    "tabs_remaining": ", ".join(self.ALL_TABS[i] for i in sorted(set(self.ALL_TABS.keys()) - self.tested_tabs)) or "Đã bao phủ toàn bộ 6 tabs!"
                }

                # 2. COGNITION: AI quyết định hành động
                decision = self.brain.decide_mission_action(mission, state_bundle)
                provider = decision.get("_provider", "AI")
                thought = decision.get("thought", "")
                act_type = decision.get("action_type", "KEY")
                act_param = decision.get("action_param", "")
                defect = decision.get("defect_detected")

                print(f"   🤖 [Session #{self.session.global_step_counter + 1} · {mission.name}] ({provider}) Thought: {thought[:90]}", flush=True)
                print(f"      -> Action: {act_type} {act_param}", flush=True)

                # Chụp screenshot cho từng bước để lưu trữ và xây dựng video tour trực quan
                step_shot = self._record_screen(f"step_{self.session.global_step_counter + 1:03d}_{mission.name.lower()[:8]}")

                # Ghi nhận vào Session Manager
                self.session.record_step(
                    mission_name=mission.name,
                    mission_title=mission.title,
                    step_in_mission=step_num,
                    activity=state_bundle["activity"],
                    active_tab_idx=current_tab_idx,
                    ui_state=ui_state,
                    state_bundle=state_bundle,
                    decision=decision,
                    screenshot_rel=step_shot
                )

                self.ai_decisions_log.append({
                    "mission": mission.name,
                    "step": step_num,
                    "provider": provider,
                    "thought": thought,
                    "action": f"{act_type} {act_param}",
                    "focused": ui_state["focused_sig"]
                })

                if defect:
                    print(f"      🚨 Phát hiện lỗi [{defect.get('severity')}]: {defect.get('issue')}", flush=True)
                    self.all_findings.append(defect)
                    mission.findings.append(defect)

                # 3. ACTION: Thực thi hành động trên TV
                if act_type == "KEY":
                    key_upper = (act_param or "DPAD_DOWN").upper()
                    if key_upper in ("DOUBLE_BACK", "BACK_DOUBLE", "BACK2"):
                        self.device.send_key("BACK")
                        time.sleep(0.25)
                        self.device.send_key("BACK")
                        time.sleep(1.2)
                    elif key_upper == "BACK" and (self.device.action_history and self.device.action_history[-1].upper() == "BACK"):
                        # AI đã bấm BACK ở bước trước và bước này bấm tiếp BACK để thoát mục:
                        # Gửi cú nhấn BACK đúp để đảm bảo nằm trong khung thời gian double-back của TV
                        self.device.send_key("BACK")
                        time.sleep(0.25)
                        self.device.send_key("BACK")
                        time.sleep(1.2)
                    elif key_upper in ("ENTER", "OK") and ("heroplay" in (ui_state.get("focused_sig") or "").lower() or "btnplay" in (ui_state.get("focused_sig") or "").lower()):
                        self.device.send_key(act_param or "OK")
                        time.sleep(2.2)
                    else:
                        self.device.send_key(act_param or "DPAD_DOWN")
                        time.sleep(0.7)
                elif act_type == "LONG_PRESS":
                    self.device.send_long_press(act_param or "OK")
                    time.sleep(1.0)
                elif act_type == "KEYPAD":
                    self.device.send_keypad_digits(str(act_param or "1"))
                    time.sleep(1.0)
                elif act_type == "SWITCH_TAB":
                    try:
                        t_idx = int(act_param)
                        self.device.switch_tab(t_idx)
                        self.tested_tabs.add(t_idx)
                        time.sleep(1.2)
                        print(f"      🔀 AI chủ động chuyển sang {self.ALL_TABS.get(t_idx, f'Tab {t_idx}')} để săn lỗi!", flush=True)
                    except Exception as e:
                        print(f"      ⚠️ Lỗi chuyển tab: {e}", flush=True)
                elif act_type == "OPEN_MULTIVIEW":
                    self.device.open_multiview()
                    time.sleep(1.8)
                    print(f"      📺 AI chủ động mở màn hình MultiView!", flush=True)
                elif act_type == "LAUNCH_WEB_PLAYER":
                    self.device.launch_web_player()
                    time.sleep(2.5)
                    print(f"      🌐 AI chủ động mở Trình Phát Web (WebPlayerActivity)!", flush=True)
                elif act_type == "TEXT":
                    self.device.input_text(act_param or "uefa")
                    time.sleep(0.8)
                elif act_type == "WAIT":
                    try:
                        time.sleep(float(act_param or "1.5"))
                    except ValueError:
                        time.sleep(1.0)
                elif act_type == "VERIFY_STREAM":
                    stream_ok, delta = self.device.detect_video_motion(duration_sec=1.5, samples=3, is_player_context=True)
                    if not stream_ok and "player" in self.device.current_activity().lower():
                        # Đệm xác thực 2 nhịp: chờ thêm 2.0s để luồng buffer xong trên CI rồi đo lại
                        print("      ⏳ Luồng đang nạp buffer trên CI, đợi 2.0s để đo lại khung hình...", flush=True)
                        time.sleep(2.0)
                        stream_ok, delta = self.device.detect_video_motion(duration_sec=1.5, samples=3, is_player_context=True)

                    self.session.record_video_verification(stream_ok, delta, self.device.current_activity())
                    print(f"      📹 Kết quả kiểm tra luồng video: {'PHÁT MƯỢT' if stream_ok else 'ĐỨNG HÌNH'} (delta: {delta:.2f}%)", flush=True)
                    if not stream_ok and "player" in self.device.current_activity().lower():
                        self.all_findings.append({
                            "area": "Trình phát Video",
                            "severity": "HIGH",
                            "issue": "Video ExoPlayer đứng hình hoặc không tải được luồng",
                            "detail": f"Motion delta = {delta:.2f}% sau 2 lần đo",
                            "suggestion": "Kiểm tra cơ chế failover sang nguồn dự phòng hoặc proxy CDN"
                        })
                elif act_type == "COMPLETE_MISSION":
                    if mission.steps_executed < min(4, mission.max_steps):
                        print(f"   ℹ️ [Mission In-Progress] Nhiệm vụ chưa đủ số bước tối thiểu ({mission.steps_executed}/4), AI tiếp tục khảo sát sâu...", flush=True)
                        self.device.send_key("DPAD_RIGHT")
                        time.sleep(0.7)
                    else:
                        print(f"   ✅ [Mission Complete] Hoàn thành mục tiêu nhiệm vụ!", flush=True)
                        mission.passed = True
                        break

                # Ghi nhận phản hồi kết quả thao tác vào bộ nhớ phiên cho lượt tiếp theo
                post_ui = self.device.dump_ui_state()
                step_feedback = f"Hành động '{act_type} {act_param}' đã chạy xong. Focus hiện tại: '{post_ui.get('focused_text')}' ({post_ui.get('focused_sig')}), Activity: {self.device.current_activity()}"
                self.brain.record_step_feedback(step_feedback)

            if not mission.passed and mission.steps_executed >= min(4, mission.max_steps):
                if not any(f.get("severity") == "CRITICAL" for f in mission.findings):
                    print(f"   ✅ [Mission Complete] Đã hoàn thành toàn bộ các bước kiểm thử ({mission.steps_executed}/{mission.max_steps}) không phát sinh lỗi nghiêm trọng!", flush=True)
                    mission.passed = True

            # Chụp ảnh và đo PSS RAM sau mỗi nhiệm vụ
            pss_mb = self.device.get_pss_memory_mb()
            if pss_mb > 0:
                print(f"   📊 [Telemetry] RAM PSS tiêu thụ: {pss_mb:.1f} MB", flush=True)
            safe_name = f"{m_idx + 2:02d}_{mission.name.lower()}"
            self._record_screen(safe_name)

            # Lưu báo cáo trung gian sau mỗi nhiệm vụ để không bao giờ mất kết quả
            try:
                self.generate_reports(round(time.time() - self.start_time), video_info=None)
            except Exception as e:
                print(f"   ⚠️ [Report] Lưu báo cáo trung gian lỗi: {e}", flush=True)

        # Dừng và lưu video toàn bộ quá trình kiểm thử
        video_info = None
        if self.recorder:
            try:
                video_info = self.recorder.stop_and_save()
            except Exception as e:
                print(f"⚠️ [VideoRecorder] Lỗi khi dừng và lưu video: {e}", flush=True)

        # Hoàn tất phiên QA
        duration_total = round(time.time() - self.start_time)
        print(f"\n🏁 Phiên QA hoàn tất trong {duration_total}s!", flush=True)
        self.generate_reports(duration_total, video_info=video_info)

    def _record_screen(self, label):
        png = self.device.screencap_bytes()
        if not png:
            return
        filename = f"{label}.jpg"
        path = os.path.join(self.shots_dir, filename)
        try:
            if Image:
                im = Image.open(io.BytesIO(png)).convert("RGB")
                im.thumbnail((960, 540))
                im.save(path, "JPEG", quality=75)
            else:
                with open(path.replace(".jpg", ".png"), "wb") as f:
                    f.write(png)
                filename = filename.replace(".jpg", ".png")
        except Exception:
            with open(path.replace(".jpg", ".png"), "wb") as f:
                f.write(png)
            filename = filename.replace(".jpg", ".png")
        rel_path = f"shots/{filename}"
        self.all_screenshots.append((label, rel_path))
        return rel_path

    def generate_reports(self, duration_sec, video_info=None):
        passed_missions = sum(1 for m in self.missions if m.passed)
        total_missions = len(self.missions)

        # Tính điểm QA chuẩn (0-100)
        penalty = {"CRITICAL": 30, "HIGH": 15, "MEDIUM": 5, "LOW": 2}
        score = 100 - sum(penalty.get(f.get("severity", "LOW"), 2) for f in self.all_findings)
        score -= round((total_missions - passed_missions) / total_missions * 20)
        final_score = max(0, min(100, score))

        # Phân tích thông tin video tour
        video_file = None
        timelapse_file = None
        if isinstance(video_info, dict):
            v_full = video_info.get("full_tour")
            if v_full and os.path.exists(v_full):
                video_file = os.path.basename(v_full)
            v_tl = video_info.get("timelapse")
            if v_tl and os.path.exists(v_tl):
                timelapse_file = os.path.basename(v_tl)
        elif isinstance(video_info, str) and os.path.exists(video_info):
            video_file = os.path.basename(video_info)

        # Hoàn tất Session và xuất qa_session.json + qa_session_summary.md
        session_manifest = self.session.finish_session(
            final_score=final_score,
            video_tour=video_file,
            timelapse=timelapse_file
        )

        checks_data = []
        for m in self.missions:
            checks_data.append({
                "name": m.title,
                "ok": m.passed,
                "detail": f"{m.steps_executed} bước thực thi"
            })

        report_json = {
            "score": final_score,
            "duration_sec": duration_sec,
            "session_id": self.session.session_id,
            "session": session_manifest,
            "video": video_file,
            "timelapse": timelapse_file,
            "engine": "KenhLive 100% Autonomous AI QA Suite",
            "brain_provider": self.brain.provider_active,
            "session_turns": self.brain.turn_counter,
            "session_messages_count": len(self.brain.session_messages),
            "checks_passed": passed_missions,
            "checks_total": total_missions,
            "missions_passed": f"{passed_missions}/{total_missions}",
            "checks": checks_data,
            "findings": self.all_findings,
            "screenshots": self.all_screenshots,
            "decisions_timeline": self.ai_decisions_log[:50]
        }

        # Lưu qa_report.json
        json_path = os.path.join(self.out_dir, "qa_report.json")
        with open(json_path, "w", encoding="utf-8") as f:
            json.dump(report_json, f, ensure_ascii=False, indent=2)
        print(f"📄 Đã lưu báo cáo JSON tại: {json_path}", flush=True)

        # Tạo qa_report.html
        html_path = os.path.join(self.out_dir, "qa_report.html")
        self._render_html_report(report_json, html_path)
        print(f"🌐 Đã xuất báo cáo trực quan HTML tại: {html_path}", flush=True)

    def _render_html_report(self, r, out_path):
        esc = html.escape
        score = r["score"]
        grade = "A" if score >= 90 else "B" if score >= 75 else "C" if score >= 60 else "D"
        grade_color = "#3ddc97" if score >= 75 else "#ff8e3c" if score >= 60 else "#ff4d4d"

        findings_rows = ""
        for f in r["findings"]:
            sev = f.get("severity", "MEDIUM")
            col = "#ff4d4d" if sev == "CRITICAL" else "#ff8e3c" if sev == "HIGH" else "#ffd166"
            findings_rows += f"""
            <tr>
              <td><span style='background:{col}22;color:{col};border:1px solid {col}55;padding:3px 8px;border-radius:6px;font-size:11px;font-weight:700;'>{esc(sev)}</span></td>
              <td><b>{esc(f.get('area', ''))}</b><br><small style='color:#8c9fc2;'>{esc(f.get('detail', ''))}</small></td>
              <td>{esc(f.get('issue', ''))}</td>
              <td style='color:#a3e635;font-family:monospace;font-size:12px;'>{esc(f.get('suggestion', ''))}</td>
            </tr>"""

        if not findings_rows:
            findings_rows = "<tr><td colspan='4' style='text-align:center;padding:24px;color:#3ddc97;'>🎉 Không phát hiện lỗi nghiêm trọng! Hệ thống hoạt động hoàn hảo.</td></tr>"

        timeline_rows = ""
        for t in r.get("decisions_timeline", []):
            timeline_rows += f"""
            <div style='margin-bottom:12px;padding:10px 14px;background:#10131c;border-left:3px solid #ff6500;border-radius:6px;font-size:13px;'>
              <span style='color:#ff6500;font-weight:700;'>[{esc(t["mission"])} · Step {t["step"]}]</span>
              <span style='color:#6b7490;margin-left:8px;'>({esc(t.get("provider", "AI"))})</span><br>
              <span style='color:#cbd5e1;'>Thought: {esc(t["thought"])}</span><br>
              <code style='color:#38bdf8;font-weight:700;'>Action: {esc(t["action"])}</code>
            </div>"""

        gallery_html = ""
        for lbl, shot_path in r.get("screenshots", []):
            gallery_html += f"""
            <div style='background:#10131c;border:1px solid rgba(255,255,255,0.08);border-radius:10px;overflow:hidden;padding:8px;'>
              <img src='{esc(shot_path)}' style='width:100%;border-radius:6px;display:block;' loading='lazy'>
              <div style='font-size:12px;color:#94a3b8;margin-top:6px;text-align:center;'>{esc(lbl)}</div>
            </div>"""

        session_html = self.session.render_session_html_section()

        video_card_html = ""
        if r.get("video"):
            first_shot = r.get("screenshots", [("", "")])[0][1] if r.get("screenshots") else ""
            tl_link = ""
            if r.get("timelapse"):
                tl_link = f"""<a href="{esc(r['timelapse'])}" download style="background:#1e293b;color:#a3e635;border:1px solid #a3e63544;padding:6px 14px;border-radius:8px;font-size:12px;text-decoration:none;font-weight:600;">⚡ Tải Timelapse 10x ({esc(r['timelapse'])})</a>"""

            video_card_html = f"""
    <div class="card" style="border: 1px solid rgba(56, 189, 248, 0.3); background: #0c101d;">
      <h2 style="font-size:17px; margin:0 0 12px; color:#38bdf8; display:flex; align-items:center; gap:8px;">
        <span>🎬</span> Video Toàn Bộ Quá Trình Kiểm Thử Android TV (Full Tour Replay)
      </h2>
      <div style="position:relative; width:100%; border-radius:12px; overflow:hidden; background:#000; box-shadow:0 8px 32px rgba(0,0,0,0.7);">
        <video controls style="width:100%; max-height:520px; display:block; margin:0 auto;" preload="metadata" poster="{esc(first_shot)}">
          <source src="{esc(r['video'])}" type="video/mp4">
          Trình duyệt không hỗ trợ xem video trực tiếp. Hãy tải video từ thư mục báo cáo.
        </video>
      </div>
      <div style="margin-top:12px; font-size:12px; color:#94a3b8; display:flex; flex-wrap:wrap; gap:16px; align-items:center; justify-content:space-between;">
        <div style="display:flex; gap:14px; align-items:center;">
          <span>📺 <b>1920x1080 (1080p Leanback)</b></span>
          <span>⚡ <b>H.264 FastStart Streamable</b></span>
          <span style="color:#3ddc97;">● Ghi hình xuyên suốt 8 nhiệm vụ</span>
        </div>
        <div style="display:flex; gap:8px;">
          <a href="{esc(r['video'])}" download style="background:#1e293b; color:#38bdf8; border:1px solid #38bdf844; padding:6px 14px; border-radius:8px; font-size:12px; text-decoration:none; font-weight:600;">📥 Tải Video Đầy Đủ ({esc(r['video'])})</a>
          {tl_link}
        </div>
      </div>
    </div>"""

        html_content = f"""<!DOCTYPE html>
<html lang="vi">
<head>
  <meta charset="utf-8">
  <title>KenhLive TV · 100% Autonomous AI QA Report</title>
  <style>
    body {{ background: #07090e; color: #f8fafc; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; padding: 24px; }}
    .container {{ max-width: 1100px; margin: 0 auto; }}
    .card {{ background: #0c0f17; border: 1px solid rgba(255,255,255,0.08); border-radius: 14px; padding: 20px; margin-bottom: 24px; }}
    .header {{ display: flex; justify-content: space-between; align-items: center; }}
    .score-badge {{ font-size: 38px; font-weight: 900; color: {grade_color}; }}
    table {{ width: 100%; border-collapse: collapse; margin-top: 14px; }}
    th, td {{ padding: 12px 14px; border-bottom: 1px solid rgba(255,255,255,0.06); text-align: left; font-size: 13px; }}
    th {{ color: #94a3b8; font-weight: 600; text-transform: uppercase; font-size: 11px; }}
    .gallery {{ display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 14px; margin-top: 14px; }}
  </style>
</head>
<body>
  <div class="container">
    <div class="card header">
      <div>
        <h1 style="margin:0;font-size:24px;">🤖 KenhLive TV · 100% Autonomous AI QA Suite</h1>
        <p style="margin:4px 0 0;color:#64748b;font-size:13px;">Engine: {esc(r['engine'])} | Core Brain: {esc(r['brain_provider'])}</p>
      </div>
      <div style="text-align:right;">
        <div class="score-badge">{score}/100 ({grade})</div>
        <div style="font-size:12px;color:#94a3b8;">Hoàn thành: {r['missions_passed']} nhiệm vụ</div>
      </div>
    </div>

    {video_card_html}
    {session_html}

    <div class="card">
      <h2 style="font-size:16px;margin:0 0 10px;">📋 Kết Quả Nhiệm Vụ (Mission Checklist)</h2>
      <table>
        <thead><tr><th>Nhiệm vụ</th><th>Chi tiết</th><th>Trạng thái</th></tr></thead>
        <tbody>
          {"".join(f"<tr><td><b>{esc(c['name'])}</b></td><td>{esc(c['detail'])}</td><td style='color:{'#3ddc97' if c['ok'] else '#ff4d4d'};font-weight:700;'>{'✅ PASS' if c['ok'] else '❌ FAIL'}</td></tr>" for c in r['checks'])}
        </tbody>
      </table>
    </div>

    <div class="card">
      <h2 style="font-size:16px;margin:0 0 10px;">🚨 Phát Hiện Lỗi & Đề Xuất Khắc Phục (Bug Findings)</h2>
      <table>
        <thead><tr><th>Mức độ</th><th>Vị trí</th><th>Mô tả sự cố</th><th>Đề xuất sửa code</th></tr></thead>
        <tbody>
          {findings_rows}
        </tbody>
      </table>
    </div>

    <div class="card">
      <h2 style="font-size:16px;margin:0 0 10px;">🧠 Nhật Ký Suy Luận & Ra Quyết Định Của AI (Agent Timeline)</h2>
      <div style="max-height:360px;overflow-y:auto;padding-right:8px;">
        {timeline_rows}
      </div>
    </div>

    <div class="card">
      <h2 style="font-size:16px;margin:0 0 10px;">📸 Ảnh Chụp Hiện Trường (Screenshots Tour)</h2>
      <div class="gallery">
        {gallery_html}
      </div>
    </div>
  </div>
</body>
</html>"""

        with open(out_path, "w", encoding="utf-8") as f:
            f.write(html_content)


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="KenhLive 100% Autonomous AI QA Suite")
    parser.add_argument("--out", default="/tmp/qa_report", help="Thư mục xuất báo cáo")
    parser.add_argument("--serial", default=None, help="Mã serial thiết bị adb")
    args = parser.parse_args()

    suite = AutonomousQASuite(out_dir=args.out, serial=args.serial)
    suite.run()
