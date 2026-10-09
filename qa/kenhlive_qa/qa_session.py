#!/usr/bin/env python3
"""
KenhLive TV — AI QA Session Manager & Replay Engine
Quản lý toàn diện phiên kiểm thử tự trị của AI QA Agent:
- Ghi nhận chi tiết từng hành động theo trình tự thời gian (Chronological Action Journal)
- Theo dõi độ bao phủ 6 Tab đa năng của KenhLive TV (Tab Exploration Matrix)
- Thống kê chi tiết phím điều khiển Android TV D-pad (D-pad Controller Analytics)
- Bắt trọn suy nghĩ & lý do ra quyết định của AI (Cognition & Intent Tracking)
- Xuất dữ liệu phiên chuẩn hóa: qa_session.json, qa_session_summary.md
- Cung cấp giao diện tương tác Replay trực quan cho qa_report.html
"""

import datetime
import html
import json
import os
import re
import subprocess
import time
import uuid


KENHLIVE_TABS = {
    0: {"name": "Trực Tiếp (Live)", "icon": "⚽", "desc": "Socolive, ColaTV, Gà Vàng, Khán Đài"},
    1: {"name": "Lịch Thi Đấu (Schedule)", "icon": "📅", "desc": "Lịch phát sóng và tỷ số thể thao"},
    2: {"name": "Truyền Hình (IPTV)", "icon": "📺", "desc": "TV360 HLS, VTV, HTV, Lưới 5 cột"},
    3: {"name": "Tìm Kiếm (Search)", "icon": "🔍", "desc": "Tìm kiếm kênh, trận đấu, phim ảnh"},
    4: {"name": "Cài Đặt (Settings)", "icon": "⚙️", "desc": "Cấu hình ứng dụng, cập nhật OTA"},
    5: {"name": "Kho Phim (NguonC)", "icon": "🎬", "desc": "Phim mới, Phim bộ, Phim lẻ, Anime"}
}

REMOTE_KEY_ICONS = {
    "DPAD_UP": ("⬆️", "D-pad Lên"),
    "UP": ("⬆️", "D-pad Lên"),
    "DPAD_DOWN": ("⬇️", "D-pad Xuống"),
    "DOWN": ("⬇️", "D-pad Xuống"),
    "DPAD_LEFT": ("⬅️", "D-pad Trái"),
    "LEFT": ("⬅️", "D-pad Trái"),
    "DPAD_RIGHT": ("➡️", "D-pad Phải"),
    "RIGHT": ("➡️", "D-pad Phải"),
    "DPAD_CENTER": ("🆗", "OK / Center"),
    "OK": ("🆗", "OK"),
    "ENTER": ("🆗", "Enter"),
    "BACK": ("🔙", "Back / Quay Lại"),
    "HOME": ("🏠", "Home"),
    "MENU": ("📋", "Menu Tùy Chọn"),
    "PLAY_PAUSE": ("⏯️", "Phát / Dừng"),
    "REWIND": ("⏪", "Tua Lùi"),
    "FAST_FORWARD": ("⏩", "Tua Tới")
}


class QASession:
    """Quản lý một phiên kiểm thử AI QA hoàn chỉnh."""

    def __init__(self, out_dir="/tmp/qa_report", serial=None, pkg="com.kenhlive.tv"):
        self.out_dir = out_dir
        self.shots_dir = os.path.join(out_dir, "shots")
        os.makedirs(self.shots_dir, exist_ok=True)

        now = datetime.datetime.now()
        short_id = uuid.uuid4().hex[:6]
        self.session_id = f"QA-SESS-{now.strftime('%Y%m%d-%H%M%S')}-{short_id}"
        self.start_epoch = time.time()
        self.start_time_iso = now.isoformat()
        self.end_epoch = None
        self.duration_sec = 0

        self.pkg = pkg
        self.serial = serial
        self.git_commit, self.git_branch = self._resolve_git_meta()
        self.version_name, self.version_code = self._resolve_app_version()
        self.ci_run_id = os.environ.get("GITHUB_RUN_ID", "")

        # Trạng thái theo dõi
        self.global_step_counter = 0
        self.steps = []
        self.tab_visits = {0: 0, 1: 0, 2: 0, 3: 0, 4: 0, 5: 0}
        self.dpad_key_counts = {}
        self.action_counts = {}
        self.video_motion_checks = []
        self.failover_events = []
        self.defects_found = []
        self.final_score = 0
        self.ai_provider = "Unknown"

    def _resolve_git_meta(self):
        try:
            c = subprocess.check_output(["git", "rev-parse", "--short", "HEAD"], stderr=subprocess.DEVNULL).decode().strip()
            b = subprocess.check_output(["git", "rev-parse", "--abbrev-ref", "HEAD"], stderr=subprocess.DEVNULL).decode().strip()
            return c or "unknown", b or "unknown"
        except Exception:
            return "unknown", "unknown"

    def _resolve_app_version(self):
        gradle_path = os.path.join(os.getcwd(), "app/build.gradle")
        if os.path.exists(gradle_path):
            try:
                with open(gradle_path, "r", encoding="utf-8") as f:
                    txt = f.read()
                vn = re.search(r'versionName\s+["\']([^"\']+)["\']', txt)
                vc = re.search(r'versionCode\s+(\d+)', txt)
                return (vn.group(1) if vn else "?"), (vc.group(1) if vc else "?")
            except Exception:
                pass
        return "?", "?"

    def set_ai_provider(self, provider_name):
        self.ai_provider = provider_name

    def record_step(self, mission_name, mission_title, step_in_mission, activity,
                    active_tab_idx, ui_state, state_bundle, decision,
                    screenshot_rel=None):
        """Ghi nhận một bước hành động hoàn chỉnh của AI Agent."""
        self.global_step_counter += 1
        now_epoch = time.time()
        time_offset = round(now_epoch - self.start_epoch, 1)

        act_type = decision.get("action_type", "KEY")
        act_param = str(decision.get("action_param", "")).strip()
        thought = decision.get("thought", "")
        provider = decision.get("_provider", self.ai_provider)
        defect = decision.get("defect_detected")

        # Cập nhật thống kê hành động
        self.action_counts[act_type] = self.action_counts.get(act_type, 0) + 1

        # Cập nhật thống kê phím D-pad
        key_label = ""
        key_icon = "🎮"
        if act_type == "KEY":
            k = act_param or "DPAD_DOWN"
            self.dpad_key_counts[k] = self.dpad_key_counts.get(k, 0) + 1
            icon, desc = REMOTE_KEY_ICONS.get(k, ("🎮", k))
            key_icon = icon
            key_label = f"{icon} {k} ({desc})"
        elif act_type == "SWITCH_TAB":
            try:
                t_idx = int(act_param)
                self.tab_visits[t_idx] = self.tab_visits.get(t_idx, 0) + 1
                tab_info = KENHLIVE_TABS.get(t_idx, {"name": f"Tab {t_idx}", "icon": "🔀"})
                key_icon = tab_info["icon"]
                key_label = f"🔀 Chuyển {tab_info['icon']} {tab_info['name']}"
            except Exception:
                key_label = f"🔀 Chuyển Tab {act_param}"
        elif act_type == "VERIFY_STREAM":
            key_icon = "📹"
            key_label = "📹 Kiểm Tra Luồng Phát Video"
        elif act_type == "TEXT":
            key_icon = "⌨️"
            key_label = f"⌨️ Nhập Text: \"{act_param}\""
        elif act_type == "WAIT":
            key_icon = "⏳"
            key_label = f"⏳ Chờ {act_param}s"
        elif act_type == "COMPLETE_MISSION":
            key_icon = "🏁"
            key_label = "🏁 Hoàn Thành Nhiệm Vụ"
        else:
            key_label = f"{act_type} {act_param}"

        # Đánh dấu tab hiện tại nếu nhận diện được
        if active_tab_idx is not None and active_tab_idx in self.tab_visits:
            self.tab_visits[active_tab_idx] += 1

        focused_sig = ui_state.get("focused_sig", "")
        focused_text = ui_state.get("focused_text", "")
        focused_bounds = ui_state.get("focused_bounds")
        has_ring = state_bundle.get("has_focus_ring", False)
        ring_col = state_bundle.get("focus_ring_color", "")
        is_playing = state_bundle.get("video_motion_playing", False)
        motion_delta = state_bundle.get("video_motion_delta", 0.0)

        step_record = {
            "step_id": self.global_step_counter,
            "step_in_mission": step_in_mission,
            "time_offset_sec": time_offset,
            "mission": {
                "id": mission_name,
                "title": mission_title
            },
            "activity": activity,
            "active_tab": {
                "index": active_tab_idx,
                "name": KENHLIVE_TABS.get(active_tab_idx, {}).get("name", "Không rõ") if active_tab_idx is not None else "Không rõ",
                "icon": KENHLIVE_TABS.get(active_tab_idx, {}).get("icon", "📱") if active_tab_idx is not None else "📱"
            },
            "ui_state": {
                "focused_view": focused_sig,
                "focused_text": focused_text,
                "focused_bounds": focused_bounds,
                "has_focus_ring": has_ring,
                "focus_ring_color": ring_col
            },
            "playback_state": {
                "is_playing": is_playing,
                "motion_delta": round(motion_delta, 2)
            },
            "ai_cognition": {
                "provider": provider,
                "thought": thought
            },
            "action": {
                "type": act_type,
                "param": act_param,
                "label": key_label,
                "icon": key_icon
            },
            "defect": defect,
            "screenshot": screenshot_rel
        }

        self.steps.append(step_record)

        if defect:
            self.defects_found.append({
                "step": self.global_step_counter,
                "mission": mission_title,
                "defect": defect
            })

        return step_record

    def record_video_verification(self, is_playing, motion_delta, activity, channel_info=""):
        """Ghi nhận lượt kiểm tra video playback."""
        rec = {
            "step": self.global_step_counter,
            "timestamp": round(time.time() - self.start_epoch, 1),
            "is_playing": is_playing,
            "motion_delta": round(motion_delta, 2),
            "activity": activity,
            "channel_info": channel_info
        }
        self.video_motion_checks.append(rec)
        return rec

    def record_auto_failover(self, source_url, backup_url, reason=""):
        """Ghi nhận sự kiện tự động chuyển luồng failover thành công."""
        rec = {
            "step": self.global_step_counter,
            "timestamp": round(time.time() - self.start_epoch, 1),
            "source": source_url,
            "backup": backup_url,
            "reason": reason
        }
        self.failover_events.append(rec)
        return rec

    def finish_session(self, final_score=100, video_tour=None, timelapse=None):
        """Kết thúc phiên kiểm thử và tính toán các chỉ số tổng hợp."""
        self.end_epoch = time.time()
        self.duration_sec = round(self.end_epoch - self.start_epoch, 1)
        self.final_score = final_score
        self.video_tour = video_tour
        self.timelapse = timelapse

        # Tính toán độ bao phủ 6 tabs
        explored_tabs = [t for t, count in self.tab_visits.items() if count > 0]
        tab_coverage_pct = round(len(explored_tabs) / len(KENHLIVE_TABS) * 100, 1)

        summary_manifest = {
            "session_id": self.session_id,
            "created_at": self.start_time_iso,
            "duration_sec": self.duration_sec,
            "final_score": self.final_score,
            "video_tour": video_tour,
            "timelapse": timelapse,
            "app": {
                "package": self.pkg,
                "version_name": self.version_name,
                "version_code": self.version_code
            },
            "git": {
                "commit": self.git_commit,
                "branch": self.git_branch
            },
            "ci_run_id": self.ci_run_id,
            "ai_brain": {
                "provider": self.ai_provider
            },
            "stats": {
                "total_steps": self.global_step_counter,
                "tabs_explored": len(explored_tabs),
                "tabs_total": len(KENHLIVE_TABS),
                "tab_coverage_pct": tab_coverage_pct,
                "dpad_keypresses_total": sum(self.dpad_key_counts.values()),
                "dpad_breakdown": self.dpad_key_counts,
                "action_breakdown": self.action_counts,
                "video_motion_checks": len(self.video_motion_checks),
                "auto_failovers_observed": len(self.failover_events),
                "defects_count": len(self.defects_found)
            },
            "tabs_coverage": [
                {
                    "tab_index": t_idx,
                    "name": info["name"],
                    "icon": info["icon"],
                    "visits_or_actions": self.tab_visits.get(t_idx, 0),
                    "tested": self.tab_visits.get(t_idx, 0) > 0
                }
                for t_idx, info in KENHLIVE_TABS.items()
            ],
            "timeline": self.steps
        }

        # Lưu qa_session.json
        json_path = os.path.join(self.out_dir, "qa_session.json")
        try:
            with open(json_path, "w", encoding="utf-8") as f:
                json.dump(summary_manifest, f, ensure_ascii=False, indent=2)
            print(f"📦 [QA Session] Đã lưu dữ liệu phiên chi tiết tại: {json_path}", flush=True)
        except Exception as e:
            print(f"⚠️ [QA Session] Không thể lưu qa_session.json: {e}", flush=True)

        # Lưu qa_session_summary.md
        md_path = os.path.join(self.out_dir, "qa_session_summary.md")
        try:
            md_content = self.generate_markdown_summary(summary_manifest)
            with open(md_path, "w", encoding="utf-8") as f:
                f.write(md_content)
            print(f"📝 [QA Session] Đã lưu tóm tắt phiên tại: {md_path}", flush=True)
        except Exception as e:
            print(f"⚠️ [QA Session] Không thể lưu qa_session_summary.md: {e}", flush=True)

        return summary_manifest

    def generate_markdown_summary(self, m):
        """Tạo bản tóm tắt markdown thân thiện với CI Step Summary."""
        st = m["stats"]
        app = m["app"]
        git = m["git"]

        lines = [
            f"### 🤖 Phiên Kiểm Thử AI QA: `{m['session_id']}`",
            f"- **Phiên bản ứng dụng:** KenhLive TV `{app['version_name']}` (code `{app['version_code']}`)",
            f"- **Git Commit:** `{git['commit']}` ({git['branch']}) | **AI Brain:** {m['ai_brain']['provider']}",
            f"- **Thời gian chạy:** `{m['duration_sec']}s` | **Điểm QA:** **{m['final_score']}/100**",
            f"- **Độ bao phủ Tab:** **{st['tabs_explored']}/6 Tabs ({st['tab_coverage_pct']}%)**",
            f"- **Tổng số bước AI:** `{st['total_steps']}` | **Lượt bấm D-pad:** `{st['dpad_keypresses_total']}` | **Kiểm tra Video:** `{st['video_motion_checks']}`",
        ]
        if m.get("video_tour"):
            vt = m["video_tour"]
            tl = m.get("timelapse")
            lines.append(f"- **🎬 Video Toàn Bộ Quá Trình:** `{vt}`" + (f" | **⚡ Timelapse 10x:** `{tl}`" if tl else ""))

        lines.extend([
            "",
            "#### 🗺️ Bản Đồ Bao Phủ 6 Tab Đa Năng",
            "| Tab | Biểu Tượng | Tên Phân Hệ | Thao Tác | Trạng Thái |",
            "|:---:|:---:|:---|:---:|:---:|"
        ])

        for tc in m["tabs_coverage"]:
            status = "✅ ĐÃ DUYỆT" if tc["tested"] else "⚪ CHƯA DUYỆT"
            lines.append(f"| {tc['tab_index']} | {tc['icon']} | {tc['name']} | {tc['visits_or_actions']} | {status} |")

        lines.extend([
            "",
            "#### 🎮 Thống Kê Phím Điều Khiển TV D-pad",
            ", ".join(f"`{k}: {v}`" for k, v in st["dpad_breakdown"].items()) or "Không có",
            ""
        ])

        if self.defects_found:
            lines.extend([
                "#### 🚨 Lỗi Phát Hiện Trong Phiên",
                "".join(f"- **[Bước {d['step']}]** [{d['defect'].get('severity')}] {d['defect'].get('issue')}\n" for d in self.defects_found)
            ])
        else:
            lines.append("🎉 **Phiên hoàn thành trọn vẹn, không phát hiện lỗi phát sinh!**\n")

        return "\n".join(lines)

    def render_session_html_section(self):
        """Tạo đoạn mã HTML phong phú cho phân hệ Session Replay & Dashboard."""
        esc = html.escape
        explored_tabs = sum(1 for c in self.tab_visits.values() if c > 0)
        coverage_pct = round(explored_tabs / 6 * 100)

        # Tab cards HTML
        tab_cards_html = ""
        for t_idx, info in KENHLIVE_TABS.items():
            cnt = self.tab_visits.get(t_idx, 0)
            is_active = cnt > 0
            border_col = "#38bdf8" if is_active else "rgba(255,255,255,0.08)"
            bg_col = "rgba(56,189,248,0.06)" if is_active else "#0e111a"
            status_badge = f"<span style='color:#3ddc97;font-size:11px;font-weight:700;'>✅ {cnt} thao tác</span>" if is_active else "<span style='color:#64748b;font-size:11px;'>⚪ Chưa chạm</span>"

            tab_cards_html += f"""
            <div style='background:{bg_col};border:1px solid {border_col};border-radius:10px;padding:12px;display:flex;flex-direction:column;justify-content:space-between;'>
              <div style='display:flex;align-items:center;gap:8px;'>
                <span style='font-size:20px;'>{info['icon']}</span>
                <div>
                  <div style='font-weight:700;font-size:13px;color:#f8fafc;'>Tab {t_idx}: {esc(info['name'])}</div>
                  <div style='font-size:11px;color:#94a3b8;'>{esc(info['desc'])}</div>
                </div>
              </div>
              <div style='margin-top:10px;display:flex;justify-content:space-between;align-items:center;'>
                {status_badge}
                <span style='font-family:monospace;font-size:11px;color:#64748b;'>#{t_idx}</span>
              </div>
            </div>"""

        # D-pad controller pills HTML
        dpad_pills_html = ""
        for k, cnt in sorted(self.dpad_key_counts.items(), key=lambda x: -x[1]):
            icon, desc = REMOTE_KEY_ICONS.get(k, ("🎮", k))
            dpad_pills_html += f"""
            <div style='background:#10131c;border:1px solid rgba(255,255,255,0.1);padding:6px 12px;border-radius:20px;font-size:12px;display:inline-flex;align-items:center;gap:6px;'>
              <span>{icon}</span>
              <span style='color:#cbd5e1;font-weight:600;'>{esc(k)}</span>
              <span style='background:#ff6500;color:#fff;font-size:10px;font-weight:800;padding:1px 6px;border-radius:10px;'>{cnt}</span>
            </div>"""

        # Steps Table / Replay entries HTML
        steps_rows_html = ""
        for s in self.steps:
            st_id = s["step_id"]
            m_title = s["mission"]["title"]
            t_name = s["active_tab"]["name"]
            t_icon = s["active_tab"]["icon"]
            act_icon = s["action"]["icon"]
            act_lbl = s["action"]["label"]
            thought = s["ai_cognition"]["thought"]
            focused_view = s["ui_state"]["focused_view"]
            shot = s.get("screenshot")
            offset_s = s["time_offset_sec"]
            defect = s.get("defect")

            shot_thumb = ""
            if shot:
                shot_thumb = f"<a href='{esc(shot)}' target='_blank'><img src='{esc(shot)}' style='width:52px;height:30px;object-fit:cover;border-radius:4px;border:1px solid rgba(255,255,255,0.2);vertical-align:middle;' title='Xem ảnh chụp bước {st_id}'></a>"

            row_bg = "rgba(255,77,77,0.08)" if defect else "#0c0f17" if st_id % 2 == 0 else "#10131c"
            defect_marker = f"<span style='color:#ff4d4d;font-weight:700;margin-left:6px;'>🚨 [{esc(defect.get('severity','BUG'))}]</span>" if defect else ""

            empty_thumb = "<span style='color:#475569;'>—</span>"
            shot_display = shot_thumb if shot_thumb else empty_thumb

            steps_rows_html += f"""
            <tr style='background:{row_bg};border-bottom:1px solid rgba(255,255,255,0.04);' class='qa-step-row' data-tab='{s["active_tab"]["index"]}'>
              <td style='padding:10px 12px;font-family:monospace;font-weight:700;color:#38bdf8;'>#{st_id:02d}<br><small style='color:#64748b;'>{offset_s}s</small></td>
              <td style='padding:10px 12px;'>
                <div style='font-weight:600;color:#f1f5f9;'>{esc(m_title)}</div>
                <div style='font-size:11px;color:#94a3b8;'>{t_icon} {esc(t_name)} · <code>{esc(s["activity"].split('/')[-1])}</code></div>
              </td>
              <td style='padding:10px 12px;'>
                <span style='background:#1e293b;border:1px solid rgba(255,255,255,0.1);padding:4px 10px;border-radius:6px;font-size:12px;font-weight:700;color:#fbbf24;display:inline-block;'>
                  {esc(act_lbl)}
                </span>
                {defect_marker}
              </td>
              <td style='padding:10px 12px;font-size:12px;color:#cbd5e1;max-width:320px;'>
                <div><span style='color:#ff6500;font-weight:700;'>Brain:</span> {esc(thought)}</div>
                <div style='font-size:11px;color:#64748b;margin-top:4px;'>Focus: <code>{esc(focused_view or 'none')}</code></div>
              </td>
              <td style='padding:10px 12px;text-align:center;'>
                {shot_display}
              </td>
            </tr>"""

        video_download_btn = ""
        if getattr(self, "video_tour", None):
            video_download_btn = f'<a href="{esc(str(self.video_tour or ""))}" download style="background:#1e293b;color:#38bdf8;border:1px solid #38bdf844;padding:6px 14px;border-radius:8px;font-size:12px;text-decoration:none;font-weight:600;">🎬 Tải Video Tour</a>'

        section_html = f"""
    <!-- PHÂN HỆ AI QA SESSION JOURNAL & REPLAY -->
    <div class="card" style="border:1px solid #ff650044;box-shadow:0 8px 30px rgba(255,101,0,0.06);">
      <div style="display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:12px;border-bottom:1px solid rgba(255,255,255,0.08);padding-bottom:14px;margin-bottom:16px;">
        <div>
          <div style="display:flex;align-items:center;gap:10px;">
            <span style="background:#ff6500;color:#fff;padding:3px 10px;border-radius:6px;font-weight:800;font-size:12px;letter-spacing:0.5px;">AI QA SESSION</span>
            <code style="color:#f8fafc;font-size:15px;font-weight:700;">{esc(self.session_id)}</code>
          </div>
          <div style="font-size:12px;color:#94a3b8;margin-top:6px;">
            KênhLive TV <b>v{esc(self.version_name)}</b> (code {esc(self.version_code)}) · Git: <code>{esc(self.git_commit)}</code> ({esc(self.git_branch)}) · Thời lượng: <b>{self.duration_sec}s</b>
          </div>
        </div>
        <div style="display:flex;gap:10px;flex-wrap:wrap;">
          {video_download_btn}
          <a href="qa_session.json" download style="background:#1e293b;color:#38bdf8;border:1px solid #38bdf844;padding:6px 14px;border-radius:8px;font-size:12px;text-decoration:none;font-weight:600;">📥 Tải qa_session.json</a>
          <a href="qa_session_summary.md" download style="background:#1e293b;color:#a3e635;border:1px solid #a3e63544;padding:6px 14px;border-radius:8px;font-size:12px;text-decoration:none;font-weight:600;">📝 Tóm Tắt Markdown</a>
        </div>
      </div>

      <!-- METRICS CHÍNH CỦA SESSION -->
      <div style="display:grid;grid-template-columns:repeat(auto-fit, minmax(180px, 1fr));gap:12px;margin-bottom:18px;">
        <div style="background:#10131c;border:1px solid rgba(255,255,255,0.06);border-radius:10px;padding:12px;text-align:center;">
          <div style="font-size:11px;color:#94a3b8;text-transform:uppercase;">Tổng Thao Tác AI</div>
          <div style="font-size:26px;font-weight:800;color:#38bdf8;margin-top:4px;">{self.global_step_counter}</div>
          <div style="font-size:11px;color:#64748b;">bước hành động tự trị</div>
        </div>
        <div style="background:#10131c;border:1px solid rgba(255,255,255,0.06);border-radius:10px;padding:12px;text-align:center;">
          <div style="font-size:11px;color:#94a3b8;text-transform:uppercase;">Độ Phủ 6 Tab Đa Năng</div>
          <div style="font-size:26px;font-weight:800;color:#3ddc97;margin-top:4px;">{explored_tabs}/6 <span style="font-size:15px;">({coverage_pct}%)</span></div>
          <div style="font-size:11px;color:#64748b;">bao phủ toàn diện app</div>
        </div>
        <div style="background:#10131c;border:1px solid rgba(255,255,255,0.06);border-radius:10px;padding:12px;text-align:center;">
          <div style="font-size:11px;color:#94a3b8;text-transform:uppercase;">Phím Remote D-pad</div>
          <div style="font-size:26px;font-weight:800;color:#fbbf24;margin-top:4px;">{sum(self.dpad_key_counts.values())}</div>
          <div style="font-size:11px;color:#64748b;">lượt bấm 4 hướng & OK</div>
        </div>
        <div style="background:#10131c;border:1px solid rgba(255,255,255,0.06);border-radius:10px;padding:12px;text-align:center;">
          <div style="font-size:11px;color:#94a3b8;text-transform:uppercase;">Kiểm Tra Video Stream</div>
          <div style="font-size:26px;font-weight:800;color:#a855f7;margin-top:4px;">{len(self.video_motion_checks)}</div>
          <div style="font-size:11px;color:#64748b;">lần kiểm motion video</div>
        </div>
      </div>

      <!-- MA TRẬN 6 TAB -->
      <h3 style="font-size:14px;color:#94a3b8;text-transform:uppercase;margin:16px 0 10px;letter-spacing:0.5px;">🗺️ Ma Trận Khám Phá 6 Tab Đa Năng Của KenhLive TV</h3>
      <div style="display:grid;grid-template-columns:repeat(auto-fit, minmax(160px, 1fr));gap:10px;margin-bottom:18px;">
        {tab_cards_html}
      </div>

      <!-- THỐNG KÊ PHÍM REMOTE -->
      <h3 style="font-size:14px;color:#94a3b8;text-transform:uppercase;margin:16px 0 10px;letter-spacing:0.5px;">🎮 Phân Bố Phím Remote Android TV D-pad Đã Bấm</h3>
      <div style="display:flex;flex-wrap:wrap;gap:8px;margin-bottom:20px;">
        {dpad_pills_html or "<span style='color:#64748b;'>Chưa có phím bấm nào</span>"}
      </div>

      <!-- NHẬT KÝ TỪNG BƯỚC (SESSION JOURNAL & REPLAY) -->
      <h3 style="font-size:14px;color:#94a3b8;text-transform:uppercase;margin:20px 0 10px;letter-spacing:0.5px;">
        📜 Nhật Ký Chi Tiết Toàn Bộ {self.global_step_counter} Bước Của AI Agent (Step-by-Step Journal)
      </h3>
      <div style="max-height:480px;overflow-y:auto;border:1px solid rgba(255,255,255,0.08);border-radius:10px;">
        <table style="margin:0;">
          <thead style="position:sticky;top:0;background:#0f172a;z-index:2;">
            <tr>
              <th style="width:60px;">Bước</th>
              <th style="width:190px;">Nhiệm Vụ & Phân Hệ</th>
              <th style="width:160px;">Hành Động Remote</th>
              <th>AI Suy Nghĩ & Phân Tích (Cognition)</th>
              <th style="width:70px;text-align:center;">Ảnh Chụp</th>
            </tr>
          </thead>
          <tbody>
            {steps_rows_html or "<tr><td colspan='5' style='text-align:center;padding:20px;color:#64748b;'>Chưa có bước nào được ghi nhận.</td></tr>"}
          </tbody>
        </table>
      </div>
    </div>
"""
        return section_html
