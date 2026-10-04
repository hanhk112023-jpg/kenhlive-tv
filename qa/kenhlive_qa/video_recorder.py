#!/usr/bin/env python3
"""
KenhLive Video Recorder — Quay toàn bộ video màn hình Android TV emulator bằng adb screenrecord.
Hỗ trợ quay dài liên tục (vượt qua giới hạn 3 phút của Android) bằng cách chia chunk và ghép bằng ffmpeg.
"""

import os
import subprocess
import threading
import time


class VideoRecorder:
    def __init__(self, serial=None, out_dir="/tmp/qa_report/video", max_total_seconds=900):
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

    def start(self):
        print(f"📹 [VIDEO RECORDER] Bắt đầu quay toàn bộ quá trình test từ trước khi mở app...", flush=True)
        # Don cac file quay cu tren thiet bi
        subprocess.run(f"{self.adb} shell rm -f /sdcard/qa_rec_*.mp4", shell=True, capture_output=True)
        self.stop_event.clear()
        self.worker_thread = threading.Thread(target=self._record_loop, daemon=True)
        self.worker_thread.start()
        time.sleep(1.5)  # Cho screenrecord thuc su khoi dong tren thiet bi

    def _record_loop(self):
        idx = 0
        t0 = time.time()
        while not self.stop_event.is_set() and (time.time() - t0 < self.max_total_seconds):
            idx += 1
            remote_path = f"/sdcard/qa_rec_{idx:02d}.mp4"
            self.remote_chunks.append(remote_path)
            # adb screenrecord toi da 175s moi chunk, full resolution 1920x1080 6Mbps
            cmd = f"{self.adb} shell screenrecord --time-limit 175 --bit-rate 6000000 --size 1920x1080 {remote_path}"
            p = subprocess.Popen(cmd, shell=True)
            while p.poll() is None:
                if self.stop_event.is_set():
                    # Dung record bang cach kill process tren emulator
                    subprocess.run(f"{self.adb} shell pkill -2 screenrecord", shell=True, capture_output=True)
                    try:
                        p.wait(timeout=5)
                    except Exception:
                        p.kill()
                    return
                time.sleep(0.5)

    def stop_and_save(self):
        print("📹 [VIDEO RECORDER] Đang dừng quay và kéo video về máy...", flush=True)
        self.stop_event.set()
        subprocess.run(f"{self.adb} shell pkill -2 screenrecord", shell=True, capture_output=True)
        if self.worker_thread:
            self.worker_thread.join(timeout=8)
        time.sleep(2.0)  # Cho mp4 finalize header tren Android

        # Keo cac chunks ve local
        for i, remote in enumerate(self.remote_chunks):
            local_path = os.path.join(self.out_dir, f"chunk_{i+1:02d}.mp4")
            subprocess.run(f"{self.adb} pull {remote} {local_path}", shell=True, capture_output=True)
            if os.path.exists(local_path) and os.path.getsize(local_path) > 1000:
                self.local_chunks.append(local_path)
            subprocess.run(f"{self.adb} shell rm -f {remote}", shell=True, capture_output=True)

        if not self.local_chunks:
            print("⚠️ Không lấy được chunk video nào từ screenrecord.", flush=True)
            return None

        # Ghep hoac rename thanh final_mp4
        if len(self.local_chunks) == 1:
            os.rename(self.local_chunks[0], self.final_mp4)
            print(f"🎬 [VIDEO EXPORTED] Video đầy đủ (1 chunk): {self.final_mp4} ({os.path.getsize(self.final_mp4)//1024} KB)", flush=True)
            return self.final_mp4

        # Neu co nhieu chunk, dung ffmpeg concat
        concat_list = os.path.join(self.out_dir, "concat.txt")
        with open(concat_list, "w") as f:
            for c in self.local_chunks:
                f.write(f"file '{c}'\n")

        cmd_concat = f"ffmpeg -y -f concat -safe 0 -i {concat_list} -c copy {self.final_mp4}"
        res = subprocess.run(cmd_concat, shell=True, capture_output=True, text=True)
        if os.path.exists(self.final_mp4) and os.path.getsize(self.final_mp4) > 1000:
            print(f"🎬 [VIDEO EXPORTED] Đã ghép {len(self.local_chunks)} video chunks: {self.final_mp4} ({os.path.getsize(self.final_mp4)//1024} KB)", flush=True)
            for c in self.local_chunks:
                try:
                    os.remove(c)
                except Exception:
                    pass
            try:
                os.remove(concat_list)
            except Exception:
                pass
            return self.final_mp4
        else:
            # Fallback tra ve chunk 1
            os.rename(self.local_chunks[0], self.final_mp4)
            return self.final_mp4
