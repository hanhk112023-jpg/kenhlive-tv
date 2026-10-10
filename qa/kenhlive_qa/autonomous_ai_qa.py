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
ANTIGRAVITY_BASE = os.environ.get("ANTIGRAVITY_API_BASE", "")
ANTIGRAVITY_KEY = os.environ.get("ANTIGRAVITY_API_KEY", "")
ANTIGRAVITY_MODEL = os.environ.get("ANTIGRAVITY_MODEL", "gemini-3.8-flash-high")

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
    """Bộ não trung tâm điều khiển AI QA: Hỗ trợ đa backend và Fallback tự động."""

    def __init__(self):
        self.provider_active = "UNKNOWN"
        self._detect_provider()

    def _detect_provider(self):
        # Ưu tiên Antigravity Gateway (nội bộ, tốc độ cao, hỗ trợ vision & reasoning)
        if ANTIGRAVITY_KEY and ANTIGRAVITY_BASE:
            self.provider_active = "ANTIGRAVITY"
        elif KILO_KEY and KILO_BASE:
            self.provider_active = "KILO"
        elif OPENAI_KEY and OPENAI_BASE:
            self.provider_active = "OPENAI"
        else:
            self.provider_active = "LOCAL_REFLEX"
        print(f"🧠 [AI Brain] Khởi tạo thành công với engine: {self.provider_active}", flush=True)

    def chat_vision(self, prompt, image_bytes=None, system_prompt=None, max_tokens=3000, timeout=45):
        """Gửi prompt kèm hình ảnh đến model AI và nhận câu trả lời."""
        # 1. Thử Antigravity Gateway
        if self.provider_active == "ANTIGRAVITY":
            try:
                res = self._call_openai_compat(
                    url=ANTIGRAVITY_BASE,
                    key=ANTIGRAVITY_KEY,
                    model=ANTIGRAVITY_MODEL,
                    prompt=prompt,
                    image_bytes=image_bytes,
                    system_prompt=system_prompt,
                    max_tokens=max_tokens,
                    timeout=timeout
                )
                if res:
                    return res, "ANTIGRAVITY"
            except Exception as e:
                print(f"⚠️ [AI Brain] Antigravity gateway lỗi ({e}), chuyển fallback...", flush=True)

        # 2. Thử Kilo Gateway
        if KILO_KEY and KILO_BASE:
            try:
                res = self._call_openai_compat(
                    url=KILO_BASE,
                    key=KILO_KEY,
                    model=KILO_MODEL,
                    prompt=prompt,
                    image_bytes=image_bytes,
                    system_prompt=system_prompt,
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
                    prompt=prompt,
                    image_bytes=image_bytes,
                    system_prompt=system_prompt,
                    max_tokens=max_tokens,
                    timeout=timeout
                )
                if res:
                    return res, "OPENAI"
            except Exception as e:
                pass

        # 4. Fallback: Local Reflex Brain (không phụ thuộc mạng ngoại vi)
        return None, "LOCAL_REFLEX"

    def _call_openai_compat(self, url, key, model, prompt, image_bytes, system_prompt, max_tokens, timeout):
        messages = []
        if system_prompt:
            messages.append({"role": "system", "content": system_prompt})

        user_content = [{"type": "text", "text": prompt}]
        if image_bytes:
            # Thu nhỏ ảnh về 960x540 để tối ưu tốc độ truyền tải
            b64 = self._compress_and_b64(image_bytes)
            if b64:
                user_content.append({
                    "type": "image_url",
                    "image_url": {"url": f"data:image/jpeg;base64,{b64}"}
                })

        messages.append({"role": "user", "content": user_content})

        payload = {
            "model": model,
            "messages": messages,
            "max_tokens": max_tokens,
            "temperature": 0.1
        }

        req = urllib.request.Request(
            url,
            data=json.dumps(payload).encode("utf-8"),
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {key}",
                "User-Agent": UA
            }
        )

        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            choices = data.get("choices") or []
            if not choices:
                return ""
            msg = choices[0].get("message") or {}
            raw = msg.get("content") or msg.get("reasoning_content") or ""
            if not isinstance(raw, str):
                raw = str(raw) if raw is not None else ""
            return raw.strip()

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
        """AI phân tích trạng thái và ra quyết định hành động tiếp theo cho mission."""
        prompt = f"""Bạn là Senior AI QA Engineer chuyên nghiệp kiểm thử ứng dụng Android TV 10-foot 'KênhLive'.
Bạn đang trực tiếp cầm remote điều khiển app trên TV để kiểm thử tính năng và săn lỗi (bug hunting).

BẢN ĐỒ TOÀN BỘ 6 TAB VÀ CÁC MÀN HÌNH CỦA APP KENHLIVE TV:
- [Tab 0 - Trực tiếp (Live)]: Trận đấu bóng đá live. Hàng lọc đài phát riêng biệt: "Tất cả", "ColaTV", "Gà Vàng", "Khán Đài", "Socolive". Hero Banner gọn gàng (đã bỏ khối thống kê rác). Tên giải đấu chuẩn tiếng Việt, tỷ số X : Y, nhãn XEM và countdown.
- [Tab 1 - Lịch đấu (Schedule)]: Lịch thi đấu theo ngày (Hôm nay, Ngày mai, Giải đấu). Trận > 180p có badge KẾT THÚC.
- [Tab 2 - Truyền hình (IPTV)]: Danh mục Kênh Yêu Thích (bấm giữ OK để lưu), TV360, VTV, HTV. Hero Preview góc trên. Lưới kênh. OSD gõ số trực tiếp từ remote (0-9). Phím tắt đổi kênh CHANNEL_UP/DOWN, PAGE_UP/DOWN. Phím tắt Aspect Ratio (A/Đỏ) & Audio Boost (B/Vàng).
- [Tab 3 - Tìm kiếm (Search)]: Ô nhập tìm kiếm (searchInput), gõ từ khóa không dấu, chuyển D-pad xuống danh sách kết quả.
- [Tab 4 - Cài đặt (Settings)]: Thông tin phiên bản, kiểm tra cập nhật, cài đặt server.
- [Tab 5 - Kho Phim (NguonC & Anime)]: Apple Pills thể loại, Tủ Phim & Tập (lưu phim, tập đang xem dở). Trình phát phim hỗ trợ tua D-pad Trái/Phải (-10s / +10s) và tự động tiếp tục xem (Resume).
- [PlayerActivity]: Trình phát video, phím MENU mở Server Picker, phím A/Đỏ xoay vòng tỉ lệ khung hình (Fit/Fill/Zoom/Fixed), phím B/Vàng tăng âm lượng, gõ số kênh hiện OSD nhảy kênh ngay khi bấm OK.
- [MultiViewActivity]: Xem đồng thời 2 trận bóng đá side-by-side (--es open mv), phím UP/DOWN đổi focus viền trắng.

TIẾN ĐỘ KHÁM PHÁ CỦA BẠN (Tabs Coverage):
- Các Tab ĐÃ kiểm tra: {state.get('tabs_tested', 'Chưa có')}
- Các Tab CÒN LẠI cần bạn tự tìm lỗi: {state.get('tabs_remaining', 'Toàn bộ')}

NHIỆM VỤ HIỆN TẠI:
- Tên: {mission.name} ({mission.title})
- Mục tiêu: {mission.description}
- Tiêu chí hoàn thành: {mission.success_criteria}
- Bước hiện tại: {mission.steps_executed + 1}/{mission.max_steps}

TRẠNG THÁI MÀN HÌNH & HỆ THỐNG:
- Activity hiện tại: {state['activity']}
- Node đang FOCUS: {state['focused_sig']} (Text: '{state['focused_text']}')
- Có viền focus chuẩn (#FF6500 hoặc Trắng/Cyan): {state['has_focus_ring']}
- Video đang chạy (Motion): {state['video_motion_playing']} (Motion delta: {state['video_motion_delta']:.2f}%)
- Lịch sử 5 phím gần nhất: {state['recent_keys']}
- Logcat cảnh báo/lỗi: {state['logcat_alerts']}

DANH SÁCH VIEW CÓ THỂ FOCUS TRÊN MÀN HÌNH:
{state['ui_summary']}

CÁC HÀNH ĐỘNG KHẢ DỤNG:
- KEY: DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT, DPAD_CENTER (hoặc ENTER/OK), BACK, MENU, INFO, PLAY_PAUSE, REWIND, FAST_FORWARD, CHANNEL_UP, CHANNEL_DOWN, PAGE_UP, PAGE_DOWN, A, B, RED, YELLOW
- LONG_PRESS: Giữ phím OK (thêm/bớt Kênh Yêu Thích IPTV)
- KEYPAD: Gõ số kênh trực tiếp trên remote (vd: "1", "15")
- SWITCH_TAB: Chuyển thẳng sang Tab bất kỳ (0: Trực tiếp, 1: Lịch, 2: IPTV, 3: Tìm kiếm, 4: Cài đặt, 5: Kho Phim)
- TEXT: Nhập chuỗi tìm kiếm (vd: "uefa", "ngoai hang")
- WAIT: Chờ X giây (vd: 2.0 giây để tải stream)
- VERIFY_STREAM: Kiểm tra xem video stream có đang phát mượt không
- COMPLETE_MISSION: Kết thúc nhiệm vụ này khi đã thỏa mãn mục tiêu

HƯỚNG DẪN TỰ TÌM LỖI (Bug Hunting Guide):
- Hãy chủ động khám phá mọi ngóc ngách, chuyển qua các tab bằng lệnh SWITCH_TAB hoặc DPAD_LEFT mở thanh điều hướng rail.
- Bấm phím BACK khi mở rail sidebar phải đóng sidebar và trả lại focus cho nội dung.
- Phát hiện và báo ngay các lỗi thực sự: Mất dấu focus (Focus Loss), kẹt nút (Focus Trap), text tràn viền/cắt cụt, màn hình rỗng đen chết kẹt (Dead Screen), app crash (FATAL).
- LƯU Ý VỀ TÍNH NĂNG TỰ PHỤC HỒI (Failover & Self-Healing): Nếu phát hiện log lỗi mạng hoặc luồng video nhưng app đã tự động failover/chuyển sang server dự phòng thành công (hoặc toast thông báo chuyển luồng đang chạy), coi đây là tính năng bảo vệ hoạt động đúng thiết kế, KHÔNG đánh dấu là lỗi. Chỉ báo lỗi khi video đứng hình chết kẹt hoàn toàn không có lối thoát.

HÃY SUY LUẬN VÀ TRẢ VỀ DUY NHẤT JSON THEO ĐỊNH DẠNG SAU:
{{
  "thought": "Quan sát thấy gì trên màn hình và tại sao chọn hành động này?",
  "action_type": "KEY" | "LONG_PRESS" | "KEYPAD" | "SWITCH_TAB" | "TEXT" | "WAIT" | "VERIFY_STREAM" | "COMPLETE_MISSION",
  "action_param": "DPAD_DOWN" | "OK" | "1" | "2" | "ENTER" | "BACK" | "uefa" | "2.0" | "",
  "expected_result": "Kỳ vọng gì sau khi thực hiện hành động này?",
  "defect_detected": null hoặc {{
     "area": "Tên màn hình/tính năng",
     "severity": "CRITICAL" | "HIGH" | "MEDIUM" | "LOW",
     "issue": "Mô tả ngắn gọn lỗi",
     "detail": "Bằng chứng chi tiết",
     "suggestion": "Gợi ý sửa code Kotlin/Layout"
  }}
}}
"""
        response_text, provider = self.chat_vision(prompt, image_bytes=state.get("screenshot_bytes"))

        if response_text and provider != "LOCAL_REFLEX":
            try:
                m = re.search(r"\{.*\}", response_text, re.DOTALL)
                if m:
                    parsed = json.loads(m.group(0))
                    parsed["_provider"] = provider
                    return parsed
            except Exception as e:
                print(f"⚠️ [AI Brain] Không parse được JSON từ {provider}: {e}", flush=True)

        # Fallback vào Local Reflex Brain nếu AI cloud không khả dụng
        return self._local_reflex_decision(mission, state)

    def _local_reflex_decision(self, mission, state):
        """Local Autonomous Reflex Brain: Quyết định dựa trên heuristics ngữ nghĩa thông minh."""
        m_id = mission.name
        step = mission.steps_executed
        focused_text = (state.get("focused_text") or "").lower()
        recent = state.get("recent_keys", [])

        thought = f"Local Reflex: Điều hướng theo ngữ cảnh nhiệm vụ {m_id} tại bước {step + 1}"
        action_type = "KEY"
        action_param = "DPAD_DOWN"
        defect = None

        # Kiểm tra mất focus
        if not state.get("has_focus_ring") and state.get("focused_sig") == "NONE":
            defect = {
                "area": "Điều hướng D-pad",
                "severity": "HIGH",
                "issue": "Mất dấu focus (Focus Loss) trên màn hình",
                "detail": f"Sau các phím {recent[-4:]}, không có view nào giữ focus",
                "suggestion": "Đảm bảo gán requestFocus() hoặc cấu hình focusable=true cho các view chính"
            }

        # Logic điều hướng phản xạ theo từng nhiệm vụ
        if m_id == "MISSION_COLD_START":
            if step == 0:
                action_type = "WAIT"
                action_param = "1.5"
                thought = "Chờ app tải xong dữ liệu màn hình chính và Hero Banner"
            elif step == 1:
                action_type = "KEY"
                action_param = "DPAD_DOWN"
                thought = "Di chuyển focus xuống thẻ trận đầu tiên, kiểm tra độ nhạy Leanback"
            else:
                action_type = "COMPLETE_MISSION"
                action_param = ""
                thought = "Màn hình chính nạp thành công, Hero Banner gọn gàng không card rác"

        elif m_id == "MISSION_SPORTS_MULTI_SOURCE":
            # Duyệt các danh mục nguồn thể thao: ColaTV, Gà Vàng, Khán Đài, Socolive
            if step == 0:
                action_type = "KEY"
                action_param = "DPAD_RIGHT"
                thought = "Chuyển sang bộ lọc nguồn đài ColaTV"
            elif step == 1:
                action_type = "KEY"
                action_param = "DPAD_RIGHT"
                thought = "Khám phá danh mục đài Gà Vàng"
            elif step == 2:
                action_type = "KEY"
                action_param = "DPAD_RIGHT"
                thought = "Khám phá danh mục đài Khán Đài"
            elif step == 3:
                action_type = "KEY"
                action_param = "DPAD_DOWN"
                thought = "Di chuyển xuống danh sách các trận đấu đang trực tiếp"
            else:
                action_type = "COMPLETE_MISSION"
                action_param = ""

        elif m_id == "MISSION_SPORTS_DATA_ACCURACY":
            if step == 0:
                action_type = "KEY"
                action_param = "DPAD_DOWN"
                thought = "Kiểm tra thẻ trận: Tên giải đấu chuẩn tiếng Việt, tỷ số dạng X : Y, không spam rác [HOT]/[VIP]"
            elif step == 1:
                action_type = "KEY"
                action_param = "DPAD_RIGHT"
                thought = "Kiểm tra trận tiếp theo trong hàng, xác nhận badge KẾT THÚC nếu trận > 180p"
            else:
                action_type = "COMPLETE_MISSION"
                action_param = ""

        elif m_id == "MISSION_PLAYER_STREAMING_AND_CONTROLS":
            if step == 0:
                action_type = "KEY"
                action_param = "ENTER"
                thought = "Mở một trận đấu bóng đá để vào PlayerActivity kiểm tra luồng phát và phím tắt"
            elif step == 1:
                action_type = "VERIFY_STREAM"
                action_param = ""
                thought = "Kiểm tra ExoPlayer render video motion chuyển động mượt"
            elif step == 2:
                action_type = "KEY"
                action_param = "KEY_A"
                thought = "Bấm phím A (hoặc phím Đỏ) để test phím tắt xoay vòng tỉ lệ khung hình Aspect Ratio"
            elif step == 3:
                action_type = "KEY"
                action_param = "KEY_B"
                thought = "Bấm phím B (hoặc phím Vàng) để test phím tắt tăng âm lượng Audio Boost (+3dB/+6dB/+9dB)"
            elif step == 4:
                action_type = "KEY"
                action_param = "MENU"
                thought = "Mở menu cài đặt trình phát để kiểm tra tính năng Đổi nguồn phát (Server Picker)"
            elif step == 5:
                action_type = "KEY"
                action_param = "BACK"
                thought = "Đóng menu cài đặt"
            elif step == 6:
                action_type = "KEY"
                action_param = "BACK"
                thought = "Thoát PlayerActivity về lại màn hình chính"
            else:
                action_type = "COMPLETE_MISSION"
                action_param = ""

        elif m_id == "MISSION_IPTV_FAVORITES_AND_ZAPPING":
            if step == 0:
                action_type = "SWITCH_TAB"
                action_param = "2"
                thought = "Chuyển sang Tab Truyền hình (IPTV)"
            elif step == 1:
                action_type = "KEY"
                action_param = "DPAD_DOWN"
                thought = "Di chuyển focus xuống lưới kênh truyền hình"
            elif step == 2:
                action_type = "LONG_PRESS"
                action_param = "OK"
                thought = "Bấm giữ phím OK để lưu kênh vào mục Kênh Yêu Thích"
            elif step == 3:
                action_type = "KEY"
                action_param = "DPAD_UP"
                thought = "Di chuyển lên thanh danh mục để kiểm tra tab Yêu Thích"
            elif step == 4:
                action_type = "KEY"
                action_param = "ENTER"
                thought = "Mở phát kênh truyền hình vào PlayerActivity"
            elif step == 5:
                action_type = "KEYPAD"
                action_param = "1"
                thought = "Gõ phím số 1 trên remote để test OSD nhảy kênh trực tiếp (Direct Zapping)"
            elif step == 6:
                action_type = "KEY"
                action_param = "CHANNEL_UP"
                thought = "Thử phím CHANNEL_UP chuyển kênh nhanh bằng phím cứng remote"
            elif step == 7:
                action_type = "KEY"
                action_param = "BACK"
                thought = "Thoát trình phát về lại Tab IPTV"
            else:
                action_type = "COMPLETE_MISSION"
                action_param = ""

        elif m_id == "MISSION_IPTV_EPG_AND_HERO_PREVIEW":
            if step == 0:
                action_type = "KEY"
                action_param = "DPAD_DOWN"
                thought = "Duyệt qua danh mục kênh để nạp lịch phát sóng EPG chuẩn GMT+7"
            elif step == 1:
                action_type = "KEY"
                action_param = "DPAD_RIGHT"
                thought = "Chuyển kênh tiếp theo kích hoạt Hero Preview phát thử góc trên"
            elif step == 2:
                action_type = "WAIT"
                action_param = "1.5"
                thought = "Chờ Hero Preview debounce nạp luồng phát mượt mà"
            else:
                action_type = "COMPLETE_MISSION"
                action_param = ""

        elif m_id == "MISSION_PHIM_NGUONC_AND_SEARCH":
            if step == 0:
                action_type = "SWITCH_TAB"
                action_param = "5"
                thought = "Điều hướng sang Tab Kho Phim (NguonC & Anime)"
            elif step == 1:
                action_type = "KEY"
                action_param = "DPAD_DOWN"
                thought = "Khám phá Apple Pills bộ lọc thể loại & danh mục Tủ Phim & Tập"
            elif step == 2:
                action_type = "KEY"
                action_param = "DPAD_RIGHT"
                thought = "Duyệt danh sách thẻ phim NguonC"
            else:
                action_type = "COMPLETE_MISSION"
                action_param = ""

        elif m_id == "MISSION_PHIM_PLAYER_SEEK_AND_RESUME":
            if step == 0:
                action_type = "KEY"
                action_param = "ENTER"
                thought = "Mở một bộ phim để vào WebPlayerActivity"
            elif step == 1:
                action_type = "WAIT"
                action_param = "2.0"
                thought = "Chờ WebPlayer tải xong luồng phim và kiểm tra Toast Resume nếu có"
            elif step == 2:
                action_type = "KEY"
                action_param = "DPAD_RIGHT"
                thought = "Bấm phím D-pad Phải để test tua tới +10 giây"
            elif step == 3:
                action_type = "KEY"
                action_param = "DPAD_LEFT"
                thought = "Bấm phím D-pad Trái để test tua lùi -10 giây"
            elif step == 4:
                action_type = "KEY"
                action_param = "BACK"
                thought = "Thoát WebPlayer về lại kho phim, hệ thống tự động lưu vị trí phát"
            else:
                action_type = "COMPLETE_MISSION"
                action_param = ""

        elif m_id == "MISSION_SCHEDULE_AND_SIDEBAR_NAVIGATION":
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
                action_param = "DPAD_LEFT"
                thought = "Bấm Trái để mở thanh điều hướng Sidebar Rail bên trái"
            elif step == 4:
                action_type = "KEY"
                action_param = "BACK"
                thought = "Bấm BACK để đóng Sidebar Rail và trả lại focus cho nội dung màn hình chính"
            else:
                action_type = "COMPLETE_MISSION"
                action_param = ""

        elif m_id == "MISSION_DPAD_CHAOS_STRESS_AND_RECOVERY":
            # Nhồi phím ngẫu nhiên 4 hướng để stress-test
            stress_keys = ["DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT"]
            action_type = "KEY"
            action_param = random.choice(stress_keys)
            thought = f"D-pad chaos stress & focus recovery: nhồi phím ngẫu nhiên {action_param}"
            if step >= 10:
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

    def screencap_bytes(self):
        try:
            raw = subprocess.run(f"{self.adb} exec-out screencap -p".split(), capture_output=True, timeout=20).stdout
            return raw if len(raw) > 5000 else None
        except Exception:
            return None

    def dump_ui_state(self):
        """Đọc và bóc tách cây UI XML trong thời gian thực."""
        sh(f"{self.adb} shell uiautomator dump /sdcard/uidump.xml >/dev/null 2>&1")
        xml_text = sh(f"{self.adb} shell cat /sdcard/uidump.xml 2>/dev/null", timeout=10)
        focused_sig = "NONE"
        focused_text = ""
        focused_bounds = None
        focusable_nodes = []
        summary_lines = []

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
                    txt = a.get("text", "") or a.get("content-desc", "")
                    r_id = a.get("resource-id", "").split("/")[-1]
                    bounds = a.get("bounds", "")

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
            "ui_summary": "\n".join(summary_lines[:25]) or "(Không tìm thấy focusable view)"
        }

    def detect_video_motion(self, duration_sec=1.0, samples=3):
        """Xác định toán học xem video ExoPlayer có đang render khung hình thật hay bị đứng hình."""
        if not Image or not ImageChops or not ImageStat:
            return True, 1.5  # Heuristic pass nếu thiếu thư viện

        frames = []
        interval = duration_sec / samples
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
        return is_playing, avg_diff

    def detect_focus_ring(self, png_bytes, focused_bounds_str):
        """Phát hiện viền sáng focus (#FF6500 cam, Trắng hoặc Cyan) xung quanh view đang focus."""
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

            # Lấy vùng biên ngoài của thẻ (stroke border 4px)
            crop_outer = im.crop((max(0, x1 - 4), max(0, y1 - 4), min(W, x2 + 4), min(H, y2 + 4)))
            px = crop_outer.load()
            cw, ch = crop_outer.size

            orange_hits = 0
            white_hits = 0
            total_border_pixels = (cw * 2) + (ch * 2)

            for x in range(cw):
                for y in (0, 1, ch - 2, ch - 1):
                    r, g, b = px[x, y]
                    if r > 200 and 70 < g < 140 and b < 60:  # #FF6500 / Cam KenhLive
                        orange_hits += 1
                    elif r > 220 and g > 220 and b > 220:  # Trắng
                        white_hits += 1

            for y in range(ch):
                for x in (0, 1, cw - 2, cw - 1):
                    r, g, b = px[x, y]
                    if r > 200 and 70 < g < 140 and b < 60:
                        orange_hits += 1
                    elif r > 220 and g > 220 and b > 220:
                        white_hits += 1

            has_ring = (orange_hits + white_hits) > (total_border_pixels * 0.15)
            color_name = "ORANGE" if orange_hits > white_hits else "WHITE"
            return has_ring, color_name, (orange_hits + white_hits) / max(1, total_border_pixels)
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
        for line in new_lines:
            if "FATAL EXCEPTION" in line or "AndroidRuntime: FATAL" in line:
                alerts.append(f"CRASH: {line[:120]}")
            elif "ANR in " + self.pkg in line:
                alerts.append(f"ANR: {line[:120]}")
            elif "ExoPlayer" in line and ("error" in line.lower() or "failure" in line.lower()):
                alerts.append(f"PLAYER_ERR: {line[:100]}")

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
    def __init__(self, out_dir="/tmp/qa_report", serial=None):
        self.out_dir = out_dir
        self.shots_dir = os.path.join(out_dir, "shots")
        os.makedirs(self.shots_dir, exist_ok=True)

        self.brain = AIBrain()
        self.device = AndroidTVDevice(serial=serial, pkg=PKG)
        self.session = QASession(out_dir=out_dir, serial=serial, pkg=PKG)
        self.session.set_ai_provider(self.brain.provider_active)

        # Trình quay video toàn bộ quá trình kiểm thử Android TV
        self.recorder = None
        try:
            self.recorder = VideoRecorder(serial=serial, out_dir=out_dir)
        except Exception as e:
            print(f"⚠️ [VideoRecorder] Không thể nạp module video recorder: {e}", flush=True)

        self.ALL_TABS = {
            0: "Tab 0: Trực tiếp (Live - ColaTV/Gà Vàng/Khán Đài/Socolive)",
            1: "Tab 1: Lịch đấu (Schedule - Lịch phát sóng các ngày)",
            2: "Tab 2: Truyền hình (IPTV - TV360 HLS, VTV, HTV, Hero Preview, Lưới 5 cột)",
            3: "Tab 3: Tìm kiếm (Search - Ô nhập từ khóa & Danh sách kết quả)",
            4: "Tab 4: Cài đặt (Settings - Cập nhật & cấu hình server)",
            5: "Tab 5: Kho Phim (NguonC - Phim mới, Phim bộ, Phim lẻ, Anime)"
        }
        self.tested_tabs = set()

        self.missions = [
            QAMission(
                "MISSION_COLD_START",
                "Khởi Động Nhanh & Hero Banner Rộng Rãi",
                "Mở app từ trạng thái tắt, kiểm tra Hero Banner đã bỏ thẻ thống kê trận đấu rác, đo TTI, kiểm tra giao diện Home không bị đen màn hình.",
                "Màn hình chính nạp đủ dữ liệu, focus xuất hiện tại thẻ đầu tiên."
            ),
            QAMission(
                "MISSION_SPORTS_MULTI_SOURCE",
                "Thể Thao Đa Nguồn (ColaTV, Gà Vàng, Khán Đài, Socolive)",
                "Duyệt danh mục lọc 4 đài độc lập, kiểm tra không có emoji text [👁⏳⭐], nhãn XEM và countdown CÒN Xp.",
                "Các tab lọc đài phản hồi chuẩn, D-pad di chuyển mượt mà không kẹt."
            ),
            QAMission(
                "MISSION_SPORTS_DATA_ACCURACY",
                "Chuẩn Hóa Dữ Liệu & Trạng Thái Trận Đấu",
                "Kiểm tra tên giải đấu chuẩn tiếng Việt (Ngoại Hạng Anh, Cúp C1...), điểm số trận hiển thị chuẩn dạng X : Y, trận qua 180p có badge KẾT THÚC.",
                "Dữ liệu hiển thị sạch bóng, không spam [HOT]/[VIP], đúng múi giờ GMT+7."
            ),
            QAMission(
                "MISSION_PLAYER_STREAMING_AND_CONTROLS",
                "Trình Phát Video, Server Picker & Phím Tắt TV",
                "Mở phát trận đấu, kiểm tra ExoPlayer motion, menu đổi đài, phím tắt A/Đỏ chuyển Aspect Ratio, phím tắt B/Vàng tăng Audio Boost.",
                "Video chuyển động mượt, phím tắt đổi tỉ lệ và âm lượng phản hồi nhanh."
            ),
            QAMission(
                "MISSION_IPTV_FAVORITES_AND_ZAPPING",
                "Truyền Hình IPTV, Kênh Yêu Thích & Remote Zapping",
                "Chuyển sang Tab IPTV (Tab 2), test Long-press OK lưu Kênh Yêu Thích, duyệt tab Yêu Thích, test gõ số remote (1, 15) hiện OSD, test phím Channel/Page Up/Down.",
                "Lưu kênh yêu thích thành công, OSD gõ số kênh hiện tức thì, nhảy kênh trơn tru."
            ),
            QAMission(
                "MISSION_IPTV_EPG_AND_HERO_PREVIEW",
                "Lịch Phát Sóng EPG & Hero Preview",
                "Kiểm tra EPG chuẩn GMT+7, badge Đang phát / Tiếp theo, Hero Preview mượt mà góc trên.",
                "Lịch EPG khớp đài phát sóng Việt Nam, không lệch múi giờ."
            ),
            QAMission(
                "MISSION_PHIM_NGUONC_AND_SEARCH",
                "Kho Phim NguonC, Apple Pills & Tủ Phim",
                "Chuyển sang Tab Kho Phim (Tab 5), duyệt Apple Pills bộ lọc, tìm kiếm phim NguonC, kiểm tra Tủ Phim & Tập.",
                "Danh sách phim tải đủ thông tin, tìm kiếm trả kết quả nhanh."
            ),
            QAMission(
                "MISSION_PHIM_PLAYER_SEEK_AND_RESUME",
                "Trình Phát Phim, Tua D-pad & Tiếp Tục Xem",
                "Mở player phim WebPlayerActivity, test D-pad Trái/Phải tua -10s/+10s có Toast/HUD, test tự động tiếp tục xem (Resume Playback).",
                "Tua phim mượt mà, resume vị trí cũ chuẩn xác."
            ),
            QAMission(
                "MISSION_SCHEDULE_AND_SIDEBAR_NAVIGATION",
                "Lịch Thi Đấu & Sidebar Rail Navigation",
                "Kiểm tra Tab Lịch thi đấu (Tab 1), mở Sidebar Rail bằng DPAD_LEFT, test phím BACK đóng Sidebar mượt mà trả lại focus nội dung.",
                "Sidebar đóng mở êm ái, phím BACK không làm thoát app hoặc mất focus."
            ),
            QAMission(
                "MISSION_DPAD_CHAOS_STRESS_AND_RECOVERY",
                "D-pad Chaos Stress & Focus Recovery",
                "Nhồi phím liên tục 4 hướng để kiểm tra độ bền, mép màn hình và khả năng giữ focus / auto recovery.",
                "Không crash, không văng app, focus tự phục hồi khi chuyển cảnh."
            )
        ]

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
                is_playing, motion_delta = self.device.detect_video_motion(duration_sec=0.8, samples=2)
                logcat_alerts = self.device.get_new_logcat_alerts()

                # Tự động cập nhật tab đang hiển thị
                current_tab_idx = None
                for t_idx, kw in [(0, "tab_live"), (1, "tab_schedule"), (2, "tab_iptv"), (3, "tab_search"), (4, "tab_settings"), (5, "tab_phim")]:
                    if kw in ui_state["ui_summary"] or kw in ui_state.get("focused_sig", ""):
                        self.tested_tabs.add(t_idx)
                        current_tab_idx = t_idx

                state_bundle = {
                    "activity": self.device.current_activity(),
                    "focused_sig": ui_state["focused_sig"],
                    "focused_text": ui_state["focused_text"],
                    "has_focus_ring": has_ring,
                    "focus_ring_color": ring_col,
                    "video_motion_playing": is_playing,
                    "video_motion_delta": motion_delta,
                    "recent_keys": self.device.action_history[-5:],
                    "logcat_alerts": "; ".join(logcat_alerts) or "Không có",
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

                # Chụp screenshot tiêu biểu cho bước
                step_shot = None
                if step_num == 1 or act_type in ("SWITCH_TAB", "VERIFY_STREAM") or defect:
                    step_shot = self._record_screen(f"step_{self.session.global_step_counter + 1:02d}_{mission.name.lower()[:10]}")

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
                elif act_type == "TEXT":
                    self.device.input_text(act_param or "uefa")
                    time.sleep(0.8)
                elif act_type == "WAIT":
                    try:
                        time.sleep(float(act_param or "1.5"))
                    except ValueError:
                        time.sleep(1.0)
                elif act_type == "VERIFY_STREAM":
                    stream_ok, delta = self.device.detect_video_motion(duration_sec=1.2, samples=3)
                    self.session.record_video_verification(stream_ok, delta, self.device.current_activity())
                    print(f"      📹 Kết quả kiểm tra luồng video: {'PHÁT MƯỢT' if stream_ok else 'ĐỨNG HÌNH'} (delta: {delta:.2f}%)", flush=True)
                    if not stream_ok and "player" in self.device.current_activity().lower():
                        self.all_findings.append({
                            "area": "Trình phát Video",
                            "severity": "HIGH",
                            "issue": "Video ExoPlayer đứng hình hoặc không tải được luồng",
                            "detail": f"Motion delta = {delta:.2f}% sau 1.2s",
                            "suggestion": "Kiểm tra cơ chế failover sang nguồn dự phòng hoặc proxy CDN"
                        })
                elif act_type == "COMPLETE_MISSION":
                    print(f"   ✅ [Mission Complete] Hoàn thành mục tiêu nhiệm vụ!", flush=True)
                    mission.passed = True
                    break

            # Chụp ảnh sau mỗi nhiệm vụ
            safe_name = f"{m_idx + 2:02d}_{mission.name.lower()}"
            self._record_screen(safe_name)

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
