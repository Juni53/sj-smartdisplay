#!/usr/bin/env python3
"""
Smart Display PC側プログラム（Windows 用）

スマホ(Smart Display)から USB 経由で呼ばれ、次のことをします。
  ・PCの状態（CPU / メモリ / GPU / ネットワーク / 再生中の曲 / 音量）を返す
  ・メディア操作（前の曲 / 再生・一時停止 / 次の曲）と音量操作（上げる / 下げる / ミュート）
  ・Cava の棒グラフの値を流し続ける（VIS）

安全のため 127.0.0.1（このPCの中）でしか待ち受けません。スマホとは「adb reverse」で
つなぎます（このプログラムが自動で行います）。通信にはトークン（合言葉）が必要です。
"""
import argparse, ctypes, hmac, json, os, secrets, shutil, subprocess, sys, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

try:
    import psutil
except ImportError:
    print("psutil が必要です:  pip install psutil")
    sys.exit(1)

WIN = sys.platform == "win32"
HERE = os.path.dirname(os.path.abspath(__file__))
CONF_PATH = os.path.join(HERE, "agent_config.json")
CAVA_CONF = os.path.join(HERE, "cava_agent.conf")
PID_PATH = os.path.join(HERE, "cava.pid")
NOWIN = 0x08000000 if WIN else 0   # CREATE_NO_WINDOW（黒い窓を出さない）

DEFAULTS = {
    "port": 8765, "token": "", "cava": "cava", "adb": "adb",
    "bars": 32,
    "framerate": 60,           # Cava の出力フレームレート（スマホ側で補完するので 30〜60）
    "noise_reduction": 65,     # Cava の平滑化 0〜100（小さいほど機敏、大きいほどなめらか。Cava の既定は 77）
    "ascii_max_range": 1000,   # Cava の値の細かさ（1000 なら 0〜1000。細かいほど動きが滑らか）
    "cava_config": "",         # 自分の Cava 設定ファイルを使うときのパス（空なら自動生成の設定を使う）
    "config_version": 2,
}


def load_config():
    conf = dict(DEFAULTS)
    if os.path.exists(CONF_PATH):
        with open(CONF_PATH, encoding="utf-8") as f:
            conf.update(json.load(f))
    if conf.get("config_version", 1) < 2:      # 旧バージョンの設定からの移行: 30fps/100段階 → 60fps/1000段階
        conf["framerate"], conf["ascii_max_range"], conf["config_version"] = 60, 1000, 2
    if not conf.get("token"):
        conf["token"] = secrets.token_urlsafe(18)
    with open(CONF_PATH, "w", encoding="utf-8") as f:
        json.dump(conf, f, ensure_ascii=False, indent=2)
    return conf


# ---------------------------------------------------------------- 状態の集計（1秒ごと）
class Sampler(threading.Thread):
    daemon = True

    def __init__(self):
        super().__init__()
        self.lock = threading.Lock()
        self.data = {}
        self.gpu_ok = shutil.which("nvidia-smi") is not None
        psutil.cpu_percent(None)
        self._net = psutil.net_io_counters()
        self._t = time.time()

    def gpu(self):
        if not self.gpu_ok:
            return None, None
        try:
            out = subprocess.run(
                ["nvidia-smi", "--query-gpu=utilization.gpu,temperature.gpu", "--format=csv,noheader,nounits"],
                capture_output=True, text=True, timeout=3, creationflags=NOWIN).stdout.strip().splitlines()[0]
            u, t = [float(x) for x in out.split(",")[:2]]
            return u, t
        except Exception:
            return None, None

    def run(self):
        while True:
            try:
                now = time.time()
                net = psutil.net_io_counters()
                dt = max(0.001, now - self._t)
                down = (net.bytes_recv - self._net.bytes_recv) / dt / 1e6
                up = (net.bytes_sent - self._net.bytes_sent) / dt / 1e6
                self._net, self._t = net, now
                vm = psutil.virtual_memory()
                f = psutil.cpu_freq()
                gu, gt = self.gpu()
                vol, muted = audio_get()
                with self.lock:
                    self.data = {
                        "cpu": round(psutil.cpu_percent(None), 1),
                        "cpu_ghz": round(f.current / 1000, 2) if f else None,
                        "mem": round(vm.percent, 1),
                        "mem_used_gb": round((vm.total - vm.available) / 2**30, 1),
                        "mem_total_gb": round(vm.total / 2**30, 1),
                        "gpu": gu, "gpu_temp": gt,
                        "net_down": round(max(0, down), 2), "net_up": round(max(0, up), 2),
                        "volume": vol, "muted": muted,
                    }
            except Exception as e:
                print("sampler:", e)
            time.sleep(1)

    def snapshot(self):
        with self.lock:
            return dict(self.data)


# ---------------------------------------------------------------- 再生中の曲（Windows のメディアセッション）
class Media(threading.Thread):
    daemon = True

    def __init__(self):
        super().__init__()
        self.lock = threading.Lock()
        self.info = {"available": False}
        self.supported = False
        self.loop = None
        self.mgr = None

    def run(self):
        if not WIN:
            return
        try:
            import asyncio
            try:
                from winrt.windows.media.control import (
                    GlobalSystemMediaTransportControlsSessionManager as Mgr,
                    GlobalSystemMediaTransportControlsSessionPlaybackStatus as PS)
            except ImportError:
                from winsdk.windows.media.control import (
                    GlobalSystemMediaTransportControlsSessionManager as Mgr,
                    GlobalSystemMediaTransportControlsSessionPlaybackStatus as PS)
        except ImportError:
            print("再生中の曲の取得には  pip install winrt-Windows.Media.Control winrt-Windows.Foundation winrt-Windows.Foundation.Collections  が必要です（なくても他の機能は使えます）")
            return
        self.supported = True
        secs = lambda x: x.total_seconds() if hasattr(x, "total_seconds") else float(x) / 1e7

        async def loop():
            mgr = await Mgr.request_async()
            self.mgr, self.loop = mgr, asyncio.get_running_loop()
            last = None
            while True:
                try:
                    s = mgr.get_current_session()
                    if s is None:
                        info = {"available": False}
                    else:
                        p = await s.try_get_media_properties_async()
                        tl = s.get_timeline_properties()
                        st = s.get_playback_info().playback_status
                        playing = st == PS.PLAYING
                        pos = secs(tl.position)
                        if playing:
                            # タイムラインの位置は「最後に更新された時点」の値なので、今までの経過を足して今の位置にする
                            try:
                                from datetime import datetime, timezone
                                pos += (datetime.now(timezone.utc) - tl.last_updated_time).total_seconds()
                            except Exception:
                                pass
                        dur = secs(tl.end_time) - secs(tl.start_time)
                        info = {"available": True, "title": p.title or "", "artist": p.artist or "",
                                "playing": playing, "pos": max(0.0, min(pos, dur)) if dur > 0 else 0.0, "dur": dur,
                                "start": secs(tl.start_time), "_t": time.monotonic()}
                    with self.lock:
                        self.info = info
                except Exception as e:
                    with self.lock:
                        self.info = {"available": False}
                await asyncio.sleep(1)
        try:
            asyncio.run(loop())
        except Exception as e:
            print("media:", e)

    def snapshot(self):
        with self.lock:
            d = dict(self.info)
        d.pop("start", None)
        t0 = d.pop("_t", None)
        if d.get("playing") and t0 is not None:        # 取得してからの経過分を足して、返す時点の位置にする
            d["pos"] = round(min(d.get("dur", 0) or 1e9, d["pos"] + time.monotonic() - t0), 2)
        return d

    def seek(self, pos):
        """再生位置を pos 秒にする。対応していないプレーヤーもある（その場合は (False, 理由)）"""
        if not (self.loop and self.mgr):
            return False, "再生位置の変更には winrt が必要です（requirements.txt を参照）"
        with self.lock:
            start = self.info.get("start", 0.0)
            dur = self.info.get("dur", 0.0)
        pos = max(0.0, min(float(pos), max(0.0, dur - 0.5)))

        async def go():
            s = self.mgr.get_current_session()
            if s is None:
                return False
            return bool(await s.try_change_playback_position_async(int((start + pos) * 1e7)))   # 100ナノ秒単位
        try:
            import asyncio
            ok = asyncio.run_coroutine_threadsafe(go(), self.loop).result(4)
        except Exception as e:
            return False, "再生位置を変更できませんでした"
        return (True, "") if ok else (False, "このプレーヤーは再生位置の変更に対応していません")


# ---------------------------------------------------------------- 音量・メディアキー（Windows）
VK = {"prev": 0xB1, "next": 0xB0, "playpause": 0xB3, "vol_up": 0xAF, "vol_down": 0xAE, "mute": 0xAD}


def press(key):
    if not WIN:
        print("[dry-run] key", key)
        return
    u = ctypes.windll.user32
    u.keybd_event(VK[key], 0, 0, 0)
    u.keybd_event(VK[key], 0, 2, 0)   # KEYEVENTF_KEYUP


def _endpoint():
    """pycaw で既定の出力デバイスの音量を扱う。入っていなければ None（キー操作だけで動く）"""
    try:
        from ctypes import POINTER, cast
        import comtypes
        from comtypes import CLSCTX_ALL
        from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume
        comtypes.CoInitialize()
        dev = AudioUtilities.GetSpeakers()
        if hasattr(dev, "EndpointVolume"):
            return dev.EndpointVolume
        iface = dev.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None)
        return cast(iface, POINTER(IAudioEndpointVolume))
    except Exception:
        return None


def audio_get():
    if not WIN:
        return None, None
    ep = _endpoint()
    if ep is None:
        return None, None
    try:
        return int(round(ep.GetMasterVolumeLevelScalar() * 100)), bool(ep.GetMute())
    except Exception:
        return None, None


def audio_set(level):
    """音量を 0〜100 で指定する（pycaw が必要）。ミュート中なら解除する"""
    ep = _endpoint() if WIN else None
    if ep is None:
        return False
    try:
        ep.SetMasterVolumeLevelScalar(max(0.0, min(1.0, level / 100.0)), None)
        if level > 0 and ep.GetMute():
            ep.SetMute(0, None)
        return True
    except Exception:
        return False


def audio_do(action):
    ep = _endpoint() if WIN else None
    if ep is not None:
        try:
            if action == "mute":
                ep.SetMute(0 if ep.GetMute() else 1, None)
            else:
                v = ep.GetMasterVolumeLevelScalar() + (0.05 if action == "up" else -0.05)
                ep.SetMasterVolumeLevelScalar(max(0.0, min(1.0, v)), None)
                if v > 0 and ep.GetMute():
                    ep.SetMute(0, None)
            return
        except Exception:
            pass
    press({"up": "vol_up", "down": "vol_down", "mute": "mute"}[action])


# ---------------------------------------------------------------- Cava（棒グラフ）
class Cava:
    """VIS を見ている人がいる間だけ Cava を起動し、いなくなって5秒たったら止める。"""

    def __init__(self, conf):
        self.conf = conf
        self.exe = shutil.which(conf["cava"]) or (conf["cava"] if os.path.exists(conf["cava"]) else None)
        self.cond = threading.Condition()
        self.proc = None
        self.viewers = 0
        self.frame = None
        self.seq = 0
        self.idle_since = None
        threading.Thread(target=self._reaper, daemon=True).start()

    @property
    def ok(self):
        return self.exe is not None

    GEN_MARK = "; smartdisplay-generated v2"

    def _write_conf(self):
        c = self.conf
        if c.get("cava_config"):
            self.conf_path = c["cava_config"]            # 自分の設定ファイルを使う（raw 出力の設定が必要。README 参照）
            return
        self.conf_path = CAVA_CONF
        if os.path.exists(CAVA_CONF):
            with open(CAVA_CONF, encoding="utf-8") as f:
                if not f.readline().startswith(self.GEN_MARK):   # 手で書き換えたものは壊さず退避
                    shutil.copyfile(CAVA_CONF, CAVA_CONF + ".bak")
        with open(CAVA_CONF, "w", encoding="utf-8") as f:
            f.write(self.GEN_MARK + """ （起動のたびに agent_config.json から作り直します。自分の設定を使うときは agent_config.json の cava_config にパスを書きます）
[general]
bars = %d
framerate = %d

; 既定の再生デバイスの音を取り込みます。別のデバイスにしたいときは agent_config.json の cava_config で自分の設定を使ってください。

[smoothing]
noise_reduction = %d

[output]
method = raw
raw_target = /dev/stdout
data_format = ascii
ascii_max_range = %d
bar_delimiter = 59
frame_delimiter = 10
""" % (c["bars"], c["framerate"], c["noise_reduction"], c["ascii_max_range"]))

    def _start(self):
        self._write_conf()
        self.proc = subprocess.Popen([self.exe, "-p", self.conf_path], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                     stdin=subprocess.DEVNULL, creationflags=NOWIN)
        try:
            with open(PID_PATH, "w") as f:      # 万一エージェントが強制終了されても、次回起動時に残った Cava を片付けられるように
                f.write(str(self.proc.pid))
        except OSError:
            pass
        threading.Thread(target=self._read, args=(self.proc,), daemon=True).start()

    def _read(self, proc):
        for raw in proc.stdout:
            line = raw.decode("ascii", "ignore").strip()
            if not line:
                continue
            vals = [x for x in line.split(";") if x != ""]
            if len(vals) < 2 or not all(x.isdigit() for x in vals):
                continue
            rng = max(1, int(self.conf["ascii_max_range"]))
            vals = [str(min(1000, int(x) * 1000 // rng)) for x in vals]      # スマホには 0〜1000 で渡す
            with self.cond:
                self.frame = ";".join(vals)
                self.seq += 1
                self.cond.notify_all()
        with self.cond:
            if self.proc is proc:
                self.proc = None
                self.cond.notify_all()

    def _reaper(self):
        while True:
            time.sleep(1)
            with self.cond:
                if self.proc and self.viewers == 0:
                    self.idle_since = self.idle_since or time.time()
                    if time.time() - self.idle_since > 5:
                        self.proc.terminate()
                        self.proc = None
                        self.idle_since = None
                else:
                    self.idle_since = None

    def kill(self):
        """エージェントの終了時に Cava も止める"""
        with self.cond:
            p, self.proc = self.proc, None
        if p is not None:
            try:
                p.terminate()
                p.wait(2)
            except Exception:
                try:
                    p.kill()
                except Exception:
                    pass
        try:
            os.remove(PID_PATH)
        except OSError:
            pass

    def open(self):
        with self.cond:
            self.viewers += 1
            if self.proc is None:
                self._start()

    def close(self):
        with self.cond:
            self.viewers -= 1

    def wait_frame(self, last_seq, timeout=2.0):
        with self.cond:
            self.cond.wait_for(lambda: self.seq != last_seq or self.proc is None, timeout)
            return self.seq, self.frame, self.proc is not None


# ---------------------------------------------------------------- HTTP
def make_handler(conf, sampler, media, cava):
    token = conf["token"]

    class H(BaseHTTPRequestHandler):
        server_version = "SmartDisplayAgent"

        def log_message(self, *a):
            pass

        def _auth(self):
            t = self.headers.get("X-Token", "")
            if hmac.compare_digest(t.encode(), token.encode()):
                return True
            self._json(401, {"error": "unauthorized"})
            return False

        def _json(self, code, obj):
            b = json.dumps(obj, ensure_ascii=False).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(b)))
            self.end_headers()
            self.wfile.write(b)

        def do_GET(self):
            if not self._auth():
                return
            if self.path == "/status":
                d = sampler.snapshot()
                d["media"] = media.snapshot()
                d["vis_ok"] = cava.ok
                return self._json(200, d)
            if self.path == "/vis":
                return self._vis()
            if self.path == "/google":       # google_auth.py が作った設定を、スマホの設定画面が取り込むための窓口
                try:
                    with open(os.path.join(HERE, "google_config.json"), encoding="utf-8") as f:
                        g = json.load(f)
                    return self._json(200, {k: str(g.get(k, "")) for k in ("client_id", "client_secret", "refresh_token")})
                except Exception:
                    return self._json(404, {"error": "google_config.json がありません（google_auth.py を先に実行してください）"})
            self._json(404, {"error": "not found"})

        def do_POST(self):
            if not self._auth():
                return
            try:
                n = int(self.headers.get("Content-Length", "0"))
                body = json.loads(self.rfile.read(min(n, 4096)) or b"{}")
                action = str(body.get("action", ""))
            except Exception:
                return self._json(400, {"error": "bad request"})
            if self.path == "/media" and action in ("prev", "next", "playpause"):
                press(action)
                return self._json(200, {"ok": True})
            if self.path == "/media" and action == "seek":
                try:
                    pos = float(body.get("pos"))
                    if not (0 <= pos < 86400 * 2):
                        raise ValueError
                except (TypeError, ValueError):
                    return self._json(400, {"error": "bad position"})
                ok, why = media.seek(pos)
                return self._json(200, {"ok": True}) if ok else self._json(409, {"error": why})
            if self.path == "/volume" and action in ("up", "down", "mute"):
                audio_do(action)
                return self._json(200, {"ok": True})
            if self.path == "/volume" and action == "set":
                try:
                    level = int(body.get("level"))
                    if not (0 <= level <= 100):
                        raise ValueError
                except (TypeError, ValueError):
                    return self._json(400, {"error": "bad level"})
                if audio_set(level):
                    return self._json(200, {"ok": True})
                return self._json(409, {"error": "音量の指定には pycaw が必要です"})
            self._json(400, {"error": "unknown action"})

        def _vis(self):
            if not cava.ok:
                return self._json(503, {"error": "cava not found"})
            try:
                self.connection.setsockopt(__import__("socket").IPPROTO_TCP, __import__("socket").TCP_NODELAY, 1)   # 1フレームずつ即送る
            except OSError:
                pass
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            cava.open()
            try:
                seq = -1
                while True:
                    seq, frame, alive = cava.wait_frame(seq)
                    if frame is not None:
                        self.wfile.write((frame + "\n").encode("ascii"))
                        self.wfile.flush()
                    if not alive:
                        time.sleep(0.5)
            except (BrokenPipeError, ConnectionResetError, OSError):
                pass
            finally:
                cava.close()
    return H


def cleanup_stale_cava():
    """前回このエージェントが起動した Cava が残っていたら終わらせる（pid ファイルに記録した1つだけ）"""
    try:
        with open(PID_PATH) as f:
            pid = int(f.read().strip())
        p = psutil.Process(pid)
        if p.name().lower().startswith("cava"):
            p.terminate()
            print("前回残っていた Cava を終了しました (pid %d)" % pid)
    except Exception:
        pass
    try:
        os.remove(PID_PATH)
    except OSError:
        pass


def kill_children_with_me():
    """Windows: このプログラムが終わる（強制終了・黒い窓を閉じる・ログオフ含む）と、起動した Cava も OS が一緒に終わらせる"""
    if not WIN:
        return
    try:
        from ctypes import wintypes

        class BASIC(ctypes.Structure):
            _fields_ = [("PerProcessUserTimeLimit", ctypes.c_int64), ("PerJobUserTimeLimit", ctypes.c_int64),
                        ("LimitFlags", wintypes.DWORD), ("MinimumWorkingSetSize", ctypes.c_size_t),
                        ("MaximumWorkingSetSize", ctypes.c_size_t), ("ActiveProcessLimit", wintypes.DWORD),
                        ("Affinity", ctypes.c_size_t), ("PriorityClass", wintypes.DWORD), ("SchedulingClass", wintypes.DWORD)]

        class IOC(ctypes.Structure):
            _fields_ = [(n, ctypes.c_uint64) for n in ("ReadOperationCount", "WriteOperationCount", "OtherOperationCount",
                                                         "ReadTransferCount", "WriteTransferCount", "OtherTransferCount")]

        class EXT(ctypes.Structure):
            _fields_ = [("BasicLimitInformation", BASIC), ("IoInfo", IOC), ("ProcessMemoryLimit", ctypes.c_size_t),
                        ("JobMemoryLimit", ctypes.c_size_t), ("PeakProcessMemoryUsed", ctypes.c_size_t),
                        ("PeakJobMemoryUsed", ctypes.c_size_t)]
        k = ctypes.windll.kernel32
        k.CreateJobObjectW.restype = wintypes.HANDLE
        k.GetCurrentProcess.restype = wintypes.HANDLE
        k.AssignProcessToJobObject.argtypes = [wintypes.HANDLE, wintypes.HANDLE]
        k.SetInformationJobObject.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD]
        job = k.CreateJobObjectW(None, None)
        info = EXT()
        info.BasicLimitInformation.LimitFlags = 0x2000          # JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        k.SetInformationJobObject(job, 9, ctypes.byref(info), ctypes.sizeof(info))   # 9 = 拡張の制限情報
        if not k.AssignProcessToJobObject(job, k.GetCurrentProcess()):
            print("（注意）Cava を一緒に終了する仕組みを有効にできませんでした")
        globals()["_JOB"] = job                                  # ハンドルを閉じない（プログラム終了で閉じる）
    except Exception as e:
        print("（注意）Cava を一緒に終了する仕組みを有効にできませんでした:", e)


def adb_loop(conf):
    """USB をつなぎ直すと adb reverse が消えるので、数秒おきに張り直す。"""
    exe = shutil.which(conf["adb"]) or conf["adb"]
    warned = False
    while True:
        try:
            subprocess.run([exe, "reverse", "tcp:%d" % conf["port"], "tcp:%d" % conf["port"]],
                           capture_output=True, timeout=10, creationflags=NOWIN)
        except FileNotFoundError:
            if not warned:
                print("adb が見つかりません。agent_config.json の \"adb\" に adb.exe のフルパスを書いてください。")
                warned = True
        except Exception:
            pass
        time.sleep(5)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-adb", action="store_true", help="adb reverse を自動で行わない")
    args = ap.parse_args()
    conf = load_config()
    cleanup_stale_cava()
    kill_children_with_me()
    sampler, media, cava = Sampler(), Media(), Cava(conf)
    import atexit, signal
    atexit.register(cava.kill)
    for sig in ("SIGTERM", "SIGBREAK"):          # 終了要求でも後始末（Cava の停止）が走るようにする
        if hasattr(signal, sig):
            signal.signal(getattr(signal, sig), lambda *a: sys.exit(0))
    sampler.start()
    media.start()
    if not args.no_adb:
        threading.Thread(target=adb_loop, args=(conf,), daemon=True).start()
    srv = ThreadingHTTPServer(("127.0.0.1", conf["port"]), make_handler(conf, sampler, media, cava))
    srv.daemon_threads = True
    print("Smart Display PC側プログラム 起動")
    print("  ポート : %d" % conf["port"])
    print("  トークン: %s   ← スマホアプリの設定（PC連携）に貼り付けます" % conf["token"])
    print("  Cava   : %s" % (cava.exe or "見つかりません（VISは使えません）"))
    print("  終了は Ctrl+C")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        cava.kill()


if __name__ == "__main__":
    main()
