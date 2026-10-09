#!/usr/bin/env python3
"""
KenhLive Video Recorder — Quay toàn bộ video màn hình Android TV emulator bằng adb screenrecord.
Hỗ trợ quay dài liên tục (vượt qua giới hạn 3 phút của Android) bằng cách chia chunk và ghép bằng ffmpeg.
Tự động sinh video streamable (+faststart), nén tối ưu dung lượng (<50MB) và video tua nhanh Timelapse 10x.
"""

import glob
import os
import subprocess
import threading
import time


class VideoRecorder:
    def __init__(self, serial=None, out_dir="/tmp/qa_report", max_total_seconds=1200):
        self.serial = serial
        self.adb = f"adb -s {serial}" if serial else "adb"
        self.out_dir = out_dir
        os.makedirs(out_dir, exist_ok=True)
        self.max_total_seconds = max_total_seconds
        self.stop_event = threading.Event()
        self.worker_thread = None
        self.remote_chunks = []
        self.local_chunks = []
        self.final_mp4 = os.path.join(out_dir, "full_qa_tour.mp4")
        self.timelapse_mp4 = os.path.join(out_dir, "qa_timelapse.mp4")
        self.is_recording = False

    def start(self):
        print(f"📹 [VIDEO RECORDER] Bắt đầu quay toàn bộ quá trình test Android TV 1080p...", flush=True)
        # Xóa các file quay cũ còn sót trên thiết bị
        subprocess.run(f"{self.adb} shell rm -f /sdcard/qa_rec_*.mp4", shell=True, capture_output=True)
        self.stop_event.clear()
        self.is_recording = True
        self.worker_thread = threading.Thread(target=self._record_loop, daemon=True)
        self.worker_thread.start()
        time.sleep(1.5)  # Chờ screenrecord khởi động thực tế trên emulator

    def _record_loop(self):
        idx = 0
        t0 = time.time()
        while not self.stop_event.is_set() and (time.time() - t0 < self.max_total_seconds):
            idx += 1
            remote_path = f"/sdcard/qa_rec_{idx:02d}.mp4"
            self.remote_chunks.append(remote_path)
            # adb screenrecord tối đa 175s mỗi chunk, full HD 1920x1080, bitrate 4Mbps (sắc nét mà dung lượng gọn nhẹ)
            cmd = f"{self.adb} shell screenrecord --time-limit 175 --bit-rate 4000000 --size 1920x1080 {remote_path}"
            p = subprocess.Popen(cmd, shell=True)
            while p.poll() is None:
                if self.stop_event.is_set():
                    # Gửi SIGINT (signal 2) để screenrecord đóng file MP4 hợp lệ
                    subprocess.run(f"{self.adb} shell 'pkill -2 screenrecord 2>/dev/null || kill -2 $(pidof screenrecord) 2>/dev/null || true'", shell=True, capture_output=True)
                    try:
                        p.wait(timeout=6)
                    except Exception:
                        p.kill()
                    return
                time.sleep(0.5)

    def stop_and_save(self):
        if not self.is_recording:
            return None
        self.is_recording = False
        print("📹 [VIDEO RECORDER] Đang dừng quay màn hình và thu thập video từ thiết bị...", flush=True)
        self.stop_event.set()
        subprocess.run(f"{self.adb} shell 'pkill -2 screenrecord 2>/dev/null || kill -2 $(pidof screenrecord) 2>/dev/null || true'", shell=True, capture_output=True)
        if self.worker_thread:
            self.worker_thread.join(timeout=8)
        time.sleep(2.0)  # Chờ mp4 header được finalize trên Android filesystem

        # Tìm toàn bộ các chunk thực tế có trên thiết bị
        res = subprocess.run(f"{self.adb} shell 'ls /sdcard/qa_rec_*.mp4 2>/dev/null'", shell=True, capture_output=True, text=True)
        device_files = [line.strip() for line in res.stdout.splitlines() if line.strip().endswith(".mp4")]
        all_remotes = sorted(list(set(self.remote_chunks + device_files)))

        # Kéo các chunks về local
        for i, remote in enumerate(all_remotes):
            local_path = os.path.join(self.out_dir, f"chunk_{i+1:02d}.mp4")
            subprocess.run(f"{self.adb} pull {remote} {local_path}", shell=True, capture_output=True)
            if os.path.exists(local_path) and os.path.getsize(local_path) > 2000:
                self.local_chunks.append(local_path)
            subprocess.run(f"{self.adb} shell rm -f {remote}", shell=True, capture_output=True)

        if not self.local_chunks:
            print("⚠️ [VIDEO RECORDER] Không kéo được chunk video hợp lệ nào từ thiết bị.", flush=True)
            return None

        # Ghép video hoặc đóng gói có +faststart
        raw_full = os.path.join(self.out_dir, "raw_full.mp4")
        if len(self.local_chunks) == 1:
            raw_full = self.local_chunks[0]
        else:
            concat_list = os.path.join(self.out_dir, "concat.txt")
            with open(concat_list, "w") as f:
                for c in self.local_chunks:
                    f.write(f"file '{c}'\n")
            cmd_concat = f"ffmpeg -y -f concat -safe 0 -i {concat_list} -c copy {raw_full}"
            subprocess.run(cmd_concat, shell=True, capture_output=True)
            if not os.path.exists(raw_full) or os.path.getsize(raw_full) < 2000:
                raw_full = self.local_chunks[0]

        # Kiểm tra kích thước file; nếu > 45MB thì re-encode CRF 27 để đảm bảo giới hạn tải 50MB
        raw_size = os.path.getsize(raw_full) if os.path.exists(raw_full) else 0
        if raw_size > 45 * 1024 * 1024:
            print(f"🎬 [VIDEO] File raw ({raw_size//(1024*1024)}MB) vượt 45MB, đang nén tối ưu CRF 27...", flush=True)
            cmd_opt = f"ffmpeg -y -i {raw_full} -c:v libx264 -preset veryfast -crf 27 -pix_fmt yuv420p -movflags +faststart {self.final_mp4}"
            subprocess.run(cmd_opt, shell=True, capture_output=True)
        else:
            # Stream-copy nhanh kèm di chuyển moov atom lên đầu (+faststart) để xem online không cần đợi tải hết
            cmd_fast = f"ffmpeg -y -i {raw_full} -c copy -movflags +faststart {self.final_mp4}"
            res_fast = subprocess.run(cmd_fast, shell=True, capture_output=True)
            if not os.path.exists(self.final_mp4) or os.path.getsize(self.final_mp4) < 2000:
                # Nếu -c copy gặp lỗi bitstream, fallback re-encode veryfast
                cmd_reencode = f"ffmpeg -y -i {raw_full} -c:v libx264 -preset veryfast -crf 26 -pix_fmt yuv420p -movflags +faststart {self.final_mp4}"
                subprocess.run(cmd_reencode, shell=True, capture_output=True)

        if not os.path.exists(self.final_mp4):
            if os.path.exists(raw_full):
                os.rename(raw_full, self.final_mp4)

        # Tạo bản tua nhanh 10x Timelapse Preview (chuẩn android-tv-qa)
        if os.path.exists(self.final_mp4) and os.path.getsize(self.final_mp4) > 2000:
            print(f"⚡ [VIDEO] Đang tạo video tua nhanh 10x Timelapse Preview...", flush=True)
            cmd_timelapse = f"ffmpeg -y -i {self.final_mp4} -filter:v 'setpts=0.1*PTS' -c:v libx264 -preset veryfast -crf 26 -pix_fmt yuv420p -movflags +faststart {self.timelapse_mp4}"
            subprocess.run(cmd_timelapse, shell=True, capture_output=True)

        # Dọn dẹp các chunk tạm
        for c in self.local_chunks:
            if c != self.final_mp4 and os.path.exists(c):
                try: os.remove(c)
                except Exception: pass
        if raw_full != self.final_mp4 and os.path.exists(raw_full):
            try: os.remove(raw_full)
            except Exception: pass

        final_sz = os.path.getsize(self.final_mp4) if os.path.exists(self.final_mp4) else 0
        tl_sz = os.path.getsize(self.timelapse_mp4) if os.path.exists(self.timelapse_mp4) else 0
        print(f"🎬 [VIDEO TOUR HOÀN TẤT] File: {self.final_mp4} ({final_sz//1024} KB) | Timelapse: {self.timelapse_mp4} ({tl_sz//1024} KB)", flush=True)

        return {
            "full_tour": self.final_mp4,
            "timelapse": self.timelapse_mp4 if tl_sz > 1000 else None
        }
