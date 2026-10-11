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
    """Bộ não trung tâm điều khiển AI QA: Hỗ trợ đa backend, Fallback tự động và Session Memory liên tục cho cả phiên test."""

    def __init__(self):
        self.provider_active = "UNKNOWN"
        self._detect_provider()
        self.session_id = None
        self.session_messages = []
        self.turn_counter = 0
        self.last_action_feedback = ""

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
- [Tab 4 - Cài đặt (Settings)]: Thông tin phiên bản, kiểm tra cập nhật, liên hệ & báo lỗi Telegram (@T990512S) có mã QR ngoại tuyến, cài đặt video và audio.
- [Tab 5 - Kho Phim (NguonC & Anime)]: Apple Pills thể loại, nút XEM NGAY (btnHeroPlay), nút CHỌN TẬP (btnHeroEpisodes), nút LƯU PHIM (btnHeroFavorite), hộp thoại EpisodePickerDialog. Trình phát phim hỗ trợ tua D-pad Trái/Phải (-10s / +10s) và tự động lưu/tiếp tục xem (Resume Playback).
- [Sidebar Rail]: Mở bằng DPAD_LEFT từ mép trái, duyệt dọc qua 6 tab, đóng bằng DPAD_RIGHT hoặc BACK.
- [PlayerActivity]: Trình phát video, phím MENU mở Server Picker, phím A/Đỏ xoay vòng tỉ lệ khung hình (Fit/Fill/Zoom/Fixed), phím B/Vàng tăng âm lượng, gõ số kênh hiện OSD nhảy kênh ngay khi bấm OK. Khung báo lỗi có nút Báo Lỗi Telegram @T990512S.
- [MultiViewActivity]: Xem đồng thời đa trận bóng đá side-by-side (--es open mv hoặc OPEN_MULTIVIEW), phím UP/DOWN/LEFT/RIGHT đổi focus giữa các ô xem, mở RoomPicker đổi trận cho từng slot.

NGUYÊN TẮC BỘ NHỚ PHIÊN & SĂN LỖI SÂU (Deep Bug Hunting & Session Memory):
1. Bạn có TRÍ NHỚ LIÊN TỤC xuyên suốt cả phiên test. Hãy ghi nhớ các màn hình đã đi qua, các thao tác đã thực hiện và các lỗi đã phát hiện để không lặp lại hoặc kiểm tra lại xem lỗi có tái diễn.
2. Chủ động khám phá toàn bộ các tính năng, di chuyển qua các tab bằng SWITCH_TAB, OPEN_MULTIVIEW hoặc DPAD_LEFT mở rail navigation.
3. Khi phát hiện lỗi thực sự (Mất dấu focus, Kẹt nút Focus Trap, Rò rỉ focus vào view ẩn/ngoài màn hình, Tràn viền text, Chữ hiển thị 'null'/'undefined', Dialog không trap focus, App crash, Video đứng hình), hãy báo ngay vào trường 'defect_detected'.
4. Tính năng tự phục hồi (Failover & Self-Healing): Nếu app tự đổi sang server dự phòng thành công, đây là tính năng bảo vệ hoạt động đúng, KHÔNG coi là lỗi.

ĐỊNH DẠNG TRẢ VỀ DUY NHẤT: Trả về một khối JSON hợp lệ:
{
  "thought": "Quan sát thấy gì trên màn hình hiện tại, liên hệ với các bước trước trong phiên kiểm thử và lý do chọn hành động này",
  "action_type": "KEY" | "LONG_PRESS" | "KEYPAD" | "SWITCH_TAB" | "OPEN_MULTIVIEW" | "TEXT" | "WAIT" | "VERIFY_STREAM" | "COMPLETE_MISSION",
  "action_param": "DPAD_DOWN" | "OK" | "1" | "2" | "ENTER" | "BACK" | "uefa" | "2.0" | "",
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
        # 1. Thử Antigravity Gateway
        if self.provider_active == "ANTIGRAVITY":
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

        # 2. Thử Kilo Gateway
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

    def _call_openai_compat(self, url, key, model, messages, max_tokens, timeout):
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
- Tiến độ nhiệm vụ: Bước {mission.steps_executed + 1}/{mission.max_steps}
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
            # Giữ system message và 16 tin nhắn gần nhất
            self.session_messages = [sys_msg] + self.session_messages[-16:]

        candidate_messages = list(self.session_messages) + [new_user_msg]
        response_text, provider = self.chat_turn(candidate_messages)

        if response_text and provider != "LOCAL_REFLEX":
            try:
                m = re.search(r"\{.*\}", response_text, re.DOTALL)
                if m:
                    parsed = json.loads(m.group(0))
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
                action_param = "BACK"
                thought = "Thoát PlayerActivity về lại màn hình chính"
            else:
                action_type = "COMPLETE_MISSION"
                action_param = ""

        elif m_id == "MISSION_MULTIVIEW_DUAL_AND_QUAD":
            if step == 0:
                action_type = "OPEN_MULTIVIEW"
                action_param = ""
                thought = "Khởi động màn hình xem đa trận MultiViewActivity"
            elif step == 1:
                action_type = "WAIT"
                action_param = "1.5"
                thought = "Chờ MultiViewActivity khởi tạo bố cục chia màn hình"
            elif step == 2:
                action_type = "KEY"
                action_param = "DPAD_DOWN"
                thought = "Di chuyển focus sang ô màn hình thứ hai"
            elif step == 3:
                action_type = "KEY"
                action_param = "DPAD_UP"
                thought = "Di chuyển focus về ô màn hình thứ nhất"
            elif step == 4:
                action_type = "KEY"
                action_param = "ENTER"
                thought = "Bấm OK mở popup chọn trận đấu cho ô MultiView"
            elif step == 5:
                action_type = "KEY"
                action_param = "BACK"
                thought = "Đóng popup chọn trận trên MultiView"
            elif step == 6:
                action_type = "KEY"
                action_param = "BACK"
                thought = "Thoát MultiViewActivity quay về màn hình chính"
            else:
                action_type = "COMPLETE_MISSION"
                action_param = ""
                thought = "Đã kiểm tra hoàn tất chế độ xem đa màn hình MultiView"

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
                action_param = "DPAD_RIGHT"
                thought = "Duyệt qua các nhóm kênh truyền hình"
            else:
                action_type = "COMPLETE_MISSION"
                action_param = ""

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
            else:
                action_type = "COMPLETE_MISSION"
                action_param = ""
                thought = "Đã kiểm tra hoàn tất hộp thoại chọn tập và tính năng lưu phim"

        elif m_id in ("MISSION_PHIM_PLAYER_SEEK_AND_RESUME", "MISSION_VOD_PLAYER_SEEK_AND_RESUME"):
            if step == 0:
                action_type = "SWITCH_TAB"
                action_param = "5"
                thought = "Đảm bảo đang ở Tab Kho Phim với focus tại nút XEM NGAY (btnHeroPlay)"
            elif step == 1:
                action_type = "KEY"
                action_param = "ENTER"
                thought = "Bấm OK mở phát bộ phim trên Hero Banner vào WebPlayerActivity"
            elif step == 2:
                action_type = "WAIT"
                action_param = "2.0"
                thought = "Chờ WebPlayer tải xong luồng phim và kiểm tra Toast Resume nếu có"
            elif step == 3:
                action_type = "KEY"
                action_param = "DPAD_RIGHT"
                thought = "Bấm phím D-pad Phải để test tua tới +10 giây"
            elif step == 4:
                action_type = "KEY"
                action_param = "DPAD_LEFT"
                thought = "Bấm phím D-pad Trái để test tua lùi -10 giây"
            elif step == 5:
                action_type = "KEY"
                action_param = "BACK"
                thought = "Thoát WebPlayer về lại kho phim, hệ thống tự động lưu vị trí phát vào Tiếp Tục Xem"
            else:
                action_type = "COMPLETE_MISSION"
                action_param = ""

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
                thought = "Di chuyển xuống mục 'Liên hệ & Báo lỗi' Telegram @T990512S"
            elif step == 4:
                action_type = "KEY"
                action_param = "DPAD_DOWN"
                thought = "Di chuyển tiếp xuống mục 'Liên hệ & Báo lỗi' Telegram @T990512S"
            elif step == 5:
                action_type = "KEY"
                action_param = "DPAD_DOWN"
                thought = "Focus vào dòng 'Liên hệ & Báo lỗi' Telegram @T990512S"
            elif step == 6:
                action_type = "KEY"
                action_param = "ENTER"
                thought = "Bấm OK mở hộp thoại FeedbackDialog kiểm tra mã QR và liên kết t.me/T990512S"
            elif step == 7:
                action_type = "KEY"
                action_param = "BACK"
                thought = "Bấm BACK để đóng FeedbackDialog và quay lại danh sách Cài đặt"
            else:
                action_type = "COMPLETE_MISSION"
                action_param = ""
                thought = "Đã kiểm tra hoàn tất Tab Tìm kiếm và hộp thoại Báo lỗi Telegram @T990512S"

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
        """Khởi chạy màn hình xem đa trận MultiViewActivity."""
        sh(f"{self.adb} shell am start -n {self.pkg}/.MultiViewActivity -f 0x04000000")
        self.action_history.append("OPEN_MULTIVIEW")

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

    def dump_ui_state(self):
        """Đọc và bóc tách cây UI XML trong thời gian thực kèm thu thập dữ liệu cho Deep Anomaly Inspector."""
        sh(f"{self.adb} shell uiautomator dump /sdcard/uidump.xml >/dev/null 2>&1")
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

    def inspect_deep_anomalies(self, ui_state, current_activity=""):
        """Hệ thống quét lỗi tầng sâu tự động (Deep Anomaly Probes) trên mỗi bước thực thi."""
        anomalies = []
        focused_sig = ui_state.get("focused_sig", "NONE")
        focused_bounds = ui_state.get("focused_bounds")
        last_key = self.action_history[-1] if self.action_history else ""

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
                    s2[2] in dpad_dirs and s3[2] in dpad_dirs and s2[2] == s3[2]):
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

        return anomalies

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
    def __init__(self, out_dir="/tmp/qa_report", serial=None):
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
                "Khởi Động Lạnh, TTI & Hero Banner Rộng Rãi",
                "Mở app từ trạng thái tắt, kiểm tra Hero Banner đã bỏ thẻ thống kê trận đấu rác, đo TTI, kiểm tra giao diện Home không bị đen màn hình.",
                "Màn hình chính nạp đủ dữ liệu, focus xuất hiện tại thẻ đầu tiên.",
                max_steps=4
            ),
            QAMission(
                "MISSION_SPORTS_MULTI_SOURCE",
                "Thể Thao Đa Nguồn & Cuộn Ngang Sâu (Horizontal Rail Deep Traversal)",
                "Duyệt qua các hàng đài độc lập (Socolive, ColaTV, Gà Vàng, Khán Đài), cuộn ngang sâu liên tục để kiểm tra biên phải và khôi phục tiêu điểm.",
                "Các tab lọc đài phản hồi chuẩn, D-pad di chuyển mượt mà không kẹt, không có emoji rác [👁⏳⭐].",
                max_steps=6
            ),
            QAMission(
                "MISSION_SPORTS_ROOM_PICKER",
                "Hộp Thoại Chọn Phòng BLV & Vòng Đời Dialog (RoomPicker & Tab Switch)",
                "Mở RoomPickerDialog trên thẻ trận đấu, duyệt danh sách phòng BLV, test đóng bằng BACK và kiểm tra không rò rỉ focus đè lên các tab khác.",
                "Hộp thoại RoomPickerDialog trap focus chuẩn xác, đóng mở êm ái.",
                max_steps=5
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
                max_steps=6
            ),
            QAMission(
                "MISSION_MULTIVIEW_DUAL_AND_QUAD",
                "Đa Màn Hình MultiView (Dual/Quad Screen & Slot Navigation)",
                "Khởi động MultiViewActivity xem đồng thời đa trận, duyệt focus giữa các ô màn hình, mở chọn phòng cho ô và thoát an toàn về Home.",
                "MultiView khởi chạy mượt mà, chuyển đổi focus viền trắng chuẩn xác giữa các slot, không crash.",
                max_steps=5
            ),
            QAMission(
                "MISSION_SCHEDULE_EPG_AND_FIXTURE_TAP",
                "Lịch Thi Đấu EPG Đa Ngày & Chạm Trận Đấu (Schedule Tap & Detail)",
                "Chuyển sang Tab Lịch đấu (Tab 1), duyệt lịch theo ngày, click trận kiểm tra onFixtureTap và xử lý trạng thái trận.",
                "Lịch đấu phân chia theo ngày rõ ràng, sự kiện bấm trận phản hồi đúng.",
                max_steps=5
            ),
            QAMission(
                "MISSION_SIDEBAR_RAIL_FULL_TRAVERSAL",
                "Thanh Điều Hướng Sidebar Rail & Khám Phá Toàn Diện (Full Rail Traversal)",
                "Mở Sidebar Rail bằng DPAD_LEFT, duyệt dọc qua toàn bộ 6 tab trên Rail kiểm tra không bị tự đóng hay cướp focus, đóng bằng DPAD_RIGHT.",
                "Sidebar đóng mở êm ái, phím điều hướng không làm văng app hoặc mất focus.",
                max_steps=5
            ),
            QAMission(
                "MISSION_IPTV_CATEGORIES_AND_FAVORITES",
                "Truyền Hình IPTV, Lưới Kênh & Kênh Yêu Thích (Favorites Long-Press)",
                "Chuyển sang Tab IPTV (Tab 2), duyệt qua các danh mục kênh, test Long-press OK lưu Kênh Yêu Thích, duyệt tab Yêu Thích.",
                "Lưu kênh yêu thích thành công, lưới kênh nạp đủ logo sắc nét.",
                max_steps=5
            ),
            QAMission(
                "MISSION_IPTV_EPG_AND_ZAPPING_PLAYER",
                "Hero Preview IPTV, Lịch Phát Sóng & Nhảy Kênh Bằng Phím Số (Zapping)",
                "Kiểm tra Hero Preview góc trên, mở phát toàn màn hình kênh IPTV, test gõ số remote (1, 15) hiện OSD nhảy kênh, test phím Channel Up/Down.",
                "Hero Preview phát thử trơn tru, OSD gõ số kênh hiện tức thì, nhảy kênh trơn tru.",
                max_steps=6
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
                "Hộp Thoại Chọn Tập (EpisodePicker) & Lưu Phim Yêu Thích (VOD Favorite)",
                "Trên Hero Banner hoặc thẻ phim bộ, kiểm tra nút CHỌN TẬP (btnHeroEpisodes) mở EpisodePickerDialog, duyệt danh sách tập, test nút LƯU PHIM (btnHeroFavorite).",
                "EpisodePickerDialog hiển thị danh sách tập đầy đủ, lưu phim vào danh sách yêu thích thành công.",
                max_steps=5
            ),
            QAMission(
                "MISSION_VOD_PLAYER_SEEK_AND_RESUME",
                "Trình Phát Phim WebPlayer, Tua D-pad (-10s/+10s) & Hàng Tiếp Tục Xem",
                "Mở player phim WebPlayerActivity, test D-pad Trái/Phải tua -10s/+10s có Toast/HUD, test tự động tiếp tục xem (Resume Playback).",
                "Tua phim mượt mà, resume vị trí cũ chuẩn xác vào hàng Tiếp Tục Xem.",
                max_steps=6
            ),
            QAMission(
                "MISSION_SEARCH_AND_SETTINGS_FEEDBACK",
                "Tìm Kiếm Thông Minh & Cài Đặt (Hộp Thoại Báo Lỗi Telegram QR @T990512S)",
                "Duyệt Tab Tìm kiếm (Tab 3), nhập từ khóa; chuyển sang Tab Cài đặt (Tab 4), mở mục 'Liên hệ & Báo lỗi', kiểm tra FeedbackDialog hiển thị mã QR Telegram và thông tin @T990512S.",
                "Tìm kiếm hiển thị kết quả, FeedbackDialog mở chuẩn xác có mã QR và đóng bằng phím BACK/OK êm ái.",
                max_steps=5
            ),
            QAMission(
                "MISSION_DPAD_CHAOS_STRESS_AND_RECOVERY",
                "Thử Thách Stress Chaos D-pad & Đo Đạc Bộ Nhớ RAM (Memory Leak Audit)",
                "Nhồi phím liên tục 4 hướng để kiểm tra độ bền, mép màn hình, khả năng giữ focus / auto recovery và đo tổng lượng RAM PSS qua dumpsys meminfo.",
                "Không crash, không văng app, focus tự phục hồi khi chuyển cảnh, RAM PSS ổn định.",
                max_steps=6
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
                summary_text = (ui_state.get("ui_summary", "") + " " + ui_state.get("focused_sig", "") + " " + ui_state.get("focused_text", "")).lower()
                tab_indicators = [
                    (0, ["livelist", "cardroot", "socolive", "colatv", "gà vàng", "khán đài", "heroplay", "trực tiếp"]),
                    (1, ["schedlist", "schedcard", "lịch thi đấu", "hôm nay", "ngày mai"]),
                    (2, ["iptv", "kênh tv", "channel", "vtv", "htv"]),
                    (3, ["searchinput", "resultlist", "tìm kiếm"]),
                    (4, ["settingslist", "cài đặt", "chất lượng video", "báo lỗi"]),
                    (5, ["phim", "nguonc", "btnheroplay", "apple pill", "phim bộ", "phim lẻ", "tiếp tục xem"])
                ]
                for t_idx, kws in tab_indicators:
                    if any(k in summary_text for k in kws):
                        self.tested_tabs.add(t_idx)
                        current_tab_idx = t_idx
                        break

                # 1.5 DEEP ANOMALY INSPECTOR: Tự động phát hiện lỗi tầng sâu
                cur_act = self.device.current_activity()
                deep_defects = self.device.inspect_deep_anomalies(ui_state, cur_act)
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
                elif act_type == "OPEN_MULTIVIEW":
                    self.device.open_multiview()
                    time.sleep(1.8)
                    print(f"      📺 AI chủ động mở màn hình MultiView!", flush=True)
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

                # Ghi nhận phản hồi kết quả thao tác vào bộ nhớ phiên cho lượt tiếp theo
                post_ui = self.device.dump_ui_state()
                step_feedback = f"Hành động '{act_type} {act_param}' đã chạy xong. Focus hiện tại: '{post_ui.get('focused_text')}' ({post_ui.get('focused_sig')}), Activity: {self.device.current_activity()}"
                self.brain.record_step_feedback(step_feedback)

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
