"""KenhLive QA — deterministic probes (không cần AI): crash, jank, mem, network, focus, blank-screen, auto-refresh, D-pad."""
import io, re, subprocess, time

def sh(cmd, timeout=30):
    try:
        r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
        return (r.stdout or '') + (r.stderr or '')
    except Exception as e:
        return f"__ERR__ {e}"

def adb(serial=None):
    return f"adb -s {serial}" if serial else "adb"

class Probes:
    def __init__(self, pkg, serial=None):
        self.pkg = pkg; self.a = adb(serial)
        self._logcat_baseline = ""

    # ---------- lifecycle ----------
    def force_stop(self): sh(f"{self.a} shell am force-stop {self.pkg}")
    def launch(self):
        t0 = time.time()
        sh(f"{self.a} shell am start -n {self.pkg}/.MainActivity")
        # poll tới khi có focus (đo cold start)
        while time.time() - t0 < 30:
            if self.has_focus(): return round((time.time() - t0) * 1000)
            time.sleep(0.2)
        return -1
    def has_focus(self):
        return self.pkg in sh(f"{self.a} shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus")
    def current_activity(self):
        out = sh(f"{self.a} shell dumpsys activity activities 2>/dev/null | grep -m1 -E 'mResumedActivity|topResumedActivity|mFocusedApp'")
        m = re.search(r'([A-Za-z0-9_.]+)/([.A-Za-z0-9_]+)', out)
        return f"{m.group(1)}/{m.group(2)}" if m else "?"

    # ---------- logcat ----------
    def logcat_baseline(self):
        self._logcat_baseline = sh(f"{self.a} logcat -d -v brief", timeout=40)
    def _logcat_new(self):
        cur = sh(f"{self.a} logcat -d -v brief", timeout=40)
        if self._logcat_baseline and self._logcat_baseline in cur:
            return cur.split(self._logcat_baseline, 1)[1]
        return cur
    def crashes(self):
        """FATAL EXCEPTION / ANR của pkg từ baseline tới giờ."""
        new = self._logcat_new()
        out = []
        for m in re.finditer(r'FATAL EXCEPTION.*?(?=\n\d|\Z)', new, re.S):
            blk = m.group(0)[:600]
            if self.pkg.split('.')[-1] in blk or 'AndroidRuntime' in blk:
                out.append({'type': 'CRASH', 'detail': blk})
        for m in re.finditer(r'ANR in ' + re.escape(self.pkg) + r'.{0,300}', new, re.S):
            out.append({'type': 'ANR', 'detail': m.group(0)[:400]})
        return out

    def network_errors(self):
        """Lỗi network của app trong logcat mới (ExoPlayer/OkHttp/Socket)."""
        new = self._logcat_new()
        pats = [
            (r'SocketTimeoutException', 'timeout gọi API'),
            (r'UnknownHostException', 'DNS fail (cần proxy VN cho vnres.co)'),
            (r'SSLHandshakeException|SSLException', 'lỗi SSL'),
            (r'HTTP (40[0-9]|50[0-9])', 'HTTP lỗi từ server'),
            (r'ExoPlayer.*?\berror\b|PlaybackFailure', 'lỗi playback stream'),
            (r'connect timed out', 'connect timeout'),
        ]
        out = []
        for p, desc in pats:
            hits = re.findall(p, new, re.I)
            if hits: out.append({'pattern': p, 'count': len(hits), 'desc': desc})
        return out

    # ---------- gfx / jank ----------
    def reset_gfx(self): sh(f"{self.a} shell dumpsys gfxinfo {self.pkg} reset")
    def jank(self):
        out = sh(f"{self.a} shell dumpsys gfxinfo {self.pkg}")
        m = re.search(r'Total frames rendered: (\d+)', out)
        j = re.search(r'Janky frames: (\d+) \(([\d.]+)%\)', out)
        p50 = re.search(r'50th percentile: (\d+)ms', out); p95 = re.search(r'95th percentile: (\d+)ms', out)
        if not m or not j: return None
        frames = int(m.group(1)); janky = int(j.group(1)); pct = float(j.group(2))
        return {'frames': frames, 'janky': janky, 'jank_pct': pct,
                'p50_ms': int(p50.group(1)) if p50 else None,
                'p95_ms': int(p95.group(1)) if p95 else None}

    # ---------- memory ----------
    def mem(self):
        sh(f"{self.a} shell am dumpheap {self.pkg} /data/local/tmp/q.hprof >/dev/null 2>&1")  # trigger GC nhẹ
        out = sh(f"{self.a} shell dumpsys meminfo {self.pkg}")
        m = re.search(r'TOTAL PSS:\s*([\d,]+)', out) or re.search(r'TOTAL\s+([\d,]+)\s+[\d,]+\s+[\d,]+\s+[\d,]+', out)
        if not m: return None
        return int(m.group(1).replace(',', ''))  # KB

    # ---------- screen ----------
    def screencap(self):
        raw = subprocess.run(f"{self.a} exec-out screencap -p".split(), capture_output=True, timeout=25).stdout
        return raw if len(raw) > 10000 else None

    def is_blank(self, png):
        """Màn trắng/đen bất thường: std pixel cực thấp (PIL thuần, không numpy)."""
        if png is None: return True
        try:
            from PIL import Image, ImageStat
            im = Image.open(io.BytesIO(png)).convert('L').resize((64, 64))
            return ImageStat.Stat(im).stddev[0] < 4.0
        except Exception:
            return False

    def pixel_diff(self, png_a, png_b):
        """% khác biệt giữa 2 screenshot (PIL ImageChops, không numpy)."""
        try:
            from PIL import Image, ImageChops, ImageStat
            A = Image.open(io.BytesIO(png_a)).convert('RGB').resize((160, 90))
            B = Image.open(io.BytesIO(png_b)).convert('RGB').resize((160, 90))
            diff = ImageChops.difference(A, B).convert('L')
            # mean absolute diff trên thang 0-255
            mean = ImageStat.Stat(diff).mean[0]
            return float(mean / 255 * 100)
        except Exception:
            return -1.0

    # ---------- D-pad ----------
    def key(self, code): sh(f"{self.a} shell input keyevent {code}")
    def focused_view(self):
        """View đang focus BÊN TRONG app (uiautomator) — mCurrentFocus chỉ là window, không đổi khi di chuyển trong app."""
        sh(f"{self.a} shell uiautomator dump /sdcard/fv.xml >/dev/null 2>&1")
        out = sh(f"{self.a} shell cat /sdcard/fv.xml 2>/dev/null", timeout=20)
        m = re.search(r'<node[^>]*focused="true"[^>]*/?>', out)
        if not m: return "?"
        n = m.group(0)
        rid = re.search(r'resource-id="([^"]*)"', n)
        txt = re.search(r'text="([^"]*)"', n)
        cls = re.search(r'class="([^"]*)"', n)
        return (rid.group(1) if rid else (cls.group(1) if cls else '?')) + '|' + (txt.group(1)[:20] if txt else '')

    def dpad_moves_focus(self, code='22', wait=0.8):
        """RIGHT/D-pad có làm focus di chuyển không (điều hướng TV được không)."""
        before = self.focused_view()
        self.key(code); time.sleep(wait)
        return self.focused_view() != before

    # ---------- auto-refresh probe ----------
    def auto_refresh(self, wait_s=190, interval_s=10):
        """Chờ wait_s trên tab Live, đo pixel diff giữa các shot — phải có thay đổi (refresh 3')."""
        shots = []
        t0 = time.time()
        while time.time() - t0 < wait_s:
            p = self.screencap()
            if p: shots.append(p)
            time.sleep(interval_s)
        if len(shots) < 3: return {'ok': False, 'reason': 'không đủ screenshot'}
        maxd = 0.0
        for i in range(1, len(shots)):
            maxd = max(maxd, self.pixel_diff(shots[i-1], shots[i]))
        return {'ok': maxd > 0.5, 'max_diff_pct': round(maxd, 2),
                'shots': len(shots),
                'reason': 'màn hình đứng yên — auto-refresh KHÔNG chạy' if maxd <= 0.5 else 'UI thay đổi → refresh hoạt động'}
