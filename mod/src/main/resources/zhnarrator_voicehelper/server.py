"""Local speech helper for the Learn or Die: Language Teacher mod.

Runs next to the game and owns the microphone, so the game itself never asks for it:

    uv run python server.py            # listens on http://127.0.0.1:17645

    GET  /health?language=es     -> {"ok": true, "ready": true, "languages": [...], "recording": false}
                                    (asking for a language the helper has not loaded makes it load that model)
    GET  /devices                -> {"devices": ["Headset Microphone", ...], "default": "..."}
    POST /start  {"device": "Headset Microphone", "pause": 2.0}
                                 -> starts recording (push-to-talk pressed); the device is optional, a name from
                                    /devices; without it, or if it is gone, the system's default input is used.
                                    pause: seconds of quiet that end the take (default 0.9; longer for speaking
                                    slowly, word by word). /stop then also tells "span": seconds from the first
                                    to the last sound of speech, pauses included
    POST /stop   {"expected": "我在走路。", "language": "zh"}
                                 -> stops, transcribes, compares; {"text", "reading", "pinyin", "confidence",
                                    "seconds", "compare": {...}} (compare only when expected is given)

The language(s) to load at start come from ZH_HELPER_LANG (comma separated, default zh).
macOS asks the terminal that started this script for microphone access the first time.
"""

from __future__ import annotations

import gc
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import numpy as np
import sounddevice as sd
import soundfile as sf

from recognizer import MODELS, SAMPLE_RATE, Recognizer

PORT = int(os.environ.get("ZH_HELPER_PORT", "17645"))  # the mod passes its configured port
LANGUAGES = [l for l in os.environ.get("ZH_HELPER_LANG", "zh").split(",") if l.strip()] or ["zh"]
LOG_FILE = Path(__file__).resolve().parent / "helper.log"  # what the window shows, kept for a look afterwards


def log(message: str) -> None:
    line = f"{time.strftime('%H:%M:%S')} {message}"
    sys.stderr.write(line + "\n")
    sys.stderr.flush()
    try:
        with LOG_FILE.open("a", encoding="utf-8") as f:
            f.write(line + "\n")
    except OSError:
        pass
LAST_TAKE = Path(__file__).resolve().parent / "last_take.wav"


def input_devices() -> list[dict]:
    """Input devices of the default host API (Windows lists every microphone once per API otherwise)."""
    try:
        hostapi = sd.query_devices(kind="input")["hostapi"]
    except Exception:  # noqa: BLE001 - no default input at all
        hostapi = None
    seen, out = set(), []
    for d in sd.query_devices():
        if d["max_input_channels"] > 0 and (hostapi is None or d["hostapi"] == hostapi) and d["name"] not in seen:
            seen.add(d["name"])
            out.append(d)
    return out


def default_input_name() -> str:
    try:
        return sd.query_devices(kind="input")["name"]
    except Exception:  # noqa: BLE001
        return ""


def resolve_device(name: str | None) -> int | None:
    """The index of the input device with this name, or None for the system default."""
    if not name:
        return None
    for d in input_devices():
        if d["name"] == name:
            return d["index"]
    log(f"microphone {name!r} not found, using the default input {default_input_name()!r}")
    return None


class Mic:
    """Records the chosen (or default) input; notices on its own when the speaker has finished (speech, then silence)."""

    SPEECH_MIN_S = 0.3   # this much sound above the noise floor counts as "started talking"
    SILENCE_END_S = 0.9  # this much quiet after talking ends the take
    FRAME_S = 0.05

    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.frames: list[np.ndarray] = []
        self.stream: sd.InputStream | None = None
        self.started_at = 0.0
        self.done = False
        self._floor = 0.0
        self._floor_frames = 0
        self._speech_s = 0.0
        self._silence_s = 0.0
        self.device_name = ""
        self.peak = 0.0
        self.silence_end_s = self.SILENCE_END_S
        self._t = 0.0
        self._first_talk: float | None = None
        self._last_talk = 0.0

    @property
    def span(self) -> float:
        """Seconds from the first to the last sound of speech in the take, pauses between words included."""
        return 0.0 if self._first_talk is None else max(0.0, self._last_talk - self._first_talk)

    def start(self, device: str | None = None, pause: float | None = None) -> None:
        with self.lock:
            if self.stream is not None:
                return
            self.frames = []
            self.done = False
            self._floor, self._floor_frames, self._speech_s, self._silence_s = 0.0, 0, 0.0, 0.0
            self.peak = 0.0
            self.silence_end_s = pause if pause and pause > 0 else self.SILENCE_END_S
            self._t, self._first_talk, self._last_talk = 0.0, None, 0.0
            index = resolve_device(device)
            try:
                self.stream = self._open(index)
            except Exception as e:  # noqa: BLE001 - a chosen device that fails falls back to the default
                if index is None:
                    raise
                log(f"could not open microphone {device!r} ({e}), using the default input")
                index = None
                self.stream = self._open(None)
            self.device_name = sd.query_devices(index, "input")["name"] if index is not None else default_input_name()
            self.stream.start()
            self.started_at = time.time()

    def _open(self, index: int | None) -> sd.InputStream:
        return sd.InputStream(device=index, samplerate=SAMPLE_RATE, channels=1, dtype="float32",
                              blocksize=int(SAMPLE_RATE * self.FRAME_S), callback=self._on_audio)

    def _on_audio(self, indata, frames, time_info, status) -> None:  # noqa: ANN001 - sounddevice callback
        if self.done:
            return
        chunk = indata[:, 0].copy()
        self.frames.append(chunk)
        self.peak = max(self.peak, float(np.max(np.abs(chunk))) if chunk.size else 0.0)
        rms = float(np.sqrt(np.mean(chunk * chunk)))
        seconds = len(chunk) / SAMPLE_RATE
        start_t = self._t
        self._t += seconds
        if self._floor_frames < 4:  # the first 0.2 s set the noise floor
            self._floor = max(self._floor, rms)
            self._floor_frames += 1
            return
        talking = rms > max(3.0 * self._floor, 0.004)
        if talking:
            self._speech_s += seconds
            self._silence_s = 0.0
            if self._first_talk is None:
                self._first_talk = start_t
            self._last_talk = start_t + seconds
        elif self._speech_s >= self.SPEECH_MIN_S:
            self._silence_s += seconds
            if self._silence_s >= self.silence_end_s:
                self.done = True

    def stop(self) -> tuple[np.ndarray, float]:
        with self.lock:
            if self.stream is None:
                return np.zeros(0, dtype=np.float32), 0.0
            t0 = time.time()
            self.stream.stop()
            self.stream.close()
            self.stream = None
            seconds = t0 - self.started_at
            if time.time() - t0 > 0.5:
                log(f"closing the microphone took {time.time() - t0:.1f}s")
            audio = np.concatenate(self.frames) if self.frames else np.zeros(0, dtype=np.float32)
            self.frames = []
            return audio, seconds

    @property
    def recording(self) -> bool:
        return self.stream is not None


class Models:
    """The current language's recogniser, loaded on request in the background (the first load downloads ~1.2 GB).

    Only one stays in memory: a model takes about 1.2 GB, and four of them next to the game slowed answers from
    0.3 s to 10 s (30.10). The others stay on disk; switching back loads one in about 10 s.
    """

    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.ready: dict[str, Recognizer] = {}
        self.loading: set[str] = set()
        self.failed: dict[str, str] = {}
        self.current = ""

    def request(self, language: str) -> None:
        language = language if language in MODELS else "zh"
        with self.lock:
            self.current = language
            if language in self.ready or language in self.loading:
                return
            self.loading.add(language)
        self._unload_others()
        threading.Thread(target=self._load, args=(language,), daemon=True).start()

    def _unload_others(self) -> None:
        with self.lock:
            dropped = [language for language in self.ready if language != self.current]
            for language in dropped:
                del self.ready[language]
        if dropped:
            gc.collect()
            try:
                import torch

                if torch.backends.mps.is_available():
                    torch.mps.empty_cache()  # give the GPU memory back, not just Python's
            except Exception:  # noqa: BLE001 - freeing memory is a nicety
                pass
            log(f"unloaded the {', '.join(dropped)} speech model to free memory (it stays on disk)")

    def _load(self, language: str) -> None:
        t0 = time.time()
        log(f"loading the {language} speech model {MODELS[language]} (the first start downloads about 1.2 GB, please wait)")
        try:
            r = Recognizer(language)
            r.warm_up()  # one pass per input shape, otherwise the first answers wait seconds
            with self.lock:
                self.ready[language] = r
            log(f"{language} model ready in {time.time() - t0:.1f}s on {r.device}")
            self._unload_others()  # the player may have switched again while this one loaded
        except Exception as e:  # noqa: BLE001
            with self.lock:
                self.failed[language] = str(e)
            log(f"the {language} model could not be loaded: {e}")
        finally:
            with self.lock:
                self.loading.discard(language)

    def get(self, language: str) -> Recognizer | None:
        language = language if language in MODELS else "zh"
        with self.lock:
            return self.ready.get(language)


models = Models()
mic = Mic()


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args) -> None:  # noqa: ANN001
        line = fmt % args
        if "/health" in line or "/status" in line:
            return  # the game polls these constantly; that is noise, not news
        log(line)

    def _json(self, code: int, body: dict) -> None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        try:
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            pass  # the game gave up waiting (the helper is busy loading a model) and will simply ask again

    def _body(self) -> bytes:
        n = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(n) if n else b""

    def do_GET(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        if path == "/health":
            language = parse_qs(urlparse(self.path).query).get("language", [LANGUAGES[0]])[0] or LANGUAGES[0]
            models.request(language)
            ready = models.get(language) is not None
            self._json(200, {"ok": True, "ready": ready, "language": language, "languages": sorted(models.ready),
                             "loading": sorted(models.loading), "failed": models.failed, "recording": mic.recording})
        elif path == "/devices":
            self._json(200, {"devices": [d["name"] for d in input_devices()], "default": default_input_name()})
        elif path == "/status":
            self._json(200, {"recording": mic.recording, "done": mic.done,
                             "seconds": round(time.time() - mic.started_at, 2) if mic.recording else 0})
        else:
            self._json(404, {"error": "unknown endpoint"})

    def do_POST(self) -> None:  # noqa: N802
        url = urlparse(self.path)
        try:
            if url.path == "/quit":  # a newer helper is taking over
                self._json(200, {"ok": True})
                threading.Thread(target=self.server.shutdown, daemon=True).start()
                return
            if url.path == "/start":
                raw = self._body()
                body = json.loads(raw.decode("utf-8")) if raw else {}
                mic.start(body.get("device") or None, float(body.get("pause") or 0))
                log(f"recording from {mic.device_name!r}")
                self._json(200, {"ok": True, "device": mic.device_name})
            elif url.path == "/stop":
                raw = self._body()
                body = json.loads(raw.decode("utf-8")) if raw else {}
                t_stop = time.time()
                audio, seconds = mic.stop()
                if time.time() - t_stop > 0.3:
                    log(f"stopping the microphone took {time.time() - t_stop:.2f}s")
                result = self._recognize(audio, seconds, body.get("expected"), float(body.get("threshold", 0.6)),
                                         body.get("language") or LANGUAGES[0])
                result["device"] = mic.device_name
                result["peak"] = round(mic.peak, 4)
                result["span"] = round(mic.span, 2)
                self._json(200, result)
            else:
                self._json(404, {"error": "unknown endpoint"})
        except Exception as e:  # noqa: BLE001 - report to the game instead of dying
            self._json(500, {"error": str(e)})

    @staticmethod
    def _recognize(audio: np.ndarray, seconds: float, expected: str | None, threshold: float, language: str) -> dict:
        t0 = time.time()
        recognizer = models.get(language)
        if recognizer is None:
            models.request(language)
            raise RuntimeError(f"the {language} speech model is still loading")
        if audio.size:
            try:  # keep the last take so a weak microphone can be judged by ear
                sf.write(str(LAST_TAKE), audio, SAMPLE_RATE)
            except Exception as e:  # noqa: BLE001
                log(f"could not save {LAST_TAKE}: {e}")
        r = recognizer.recognize(audio)
        silent = seconds > 0.2 and (r.rms < 0.002 or (not r.text and r.rms < 0.015))  # nothing said, only room noise
        out = {"text": r.text, "reading": r.reading, "pinyin": r.reading, "language": language,
               "confidence": round(r.confidence, 3), "rms": round(r.rms, 4),
               "silent": silent, "seconds": round(seconds, 2), "took": round(time.time() - t0, 2)}
        if expected:
            out["compare"] = recognizer.compare(expected, r, threshold)
        log(f"heard {r.text!r} {' '.join(r.reading)} conf {r.confidence:.2f} rms {r.rms:.4f} {seconds:.1f}s"
            f" recognised in {out['took']}s"
            + (f" -> {out['compare']['verdict']} score {out['compare']['score']} thr {threshold} (expected {expected})" if expected else "")
            + (" [SILENT: microphone permission?]" if silent else ""))
        return out


def replace_previous_instance() -> None:
    """If an older helper still holds the port, ask it to quit and wait for the port to free up."""
    import urllib.request

    try:
        urllib.request.urlopen(urllib.request.Request(f"http://127.0.0.1:{PORT}/quit", method="POST"), timeout=2).read()
    except Exception:  # noqa: BLE001 - nothing listening, or not ours
        return
    print("asked the previous helper to quit", flush=True)
    import socket

    for _ in range(100):  # up to 10 s for the old process to let go of the port
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            try:
                probe.bind(("127.0.0.1", PORT))
                return
            except OSError:
                time.sleep(0.1)
    print("the previous helper did not release the port; stop it by hand (Ctrl+C in its window)", flush=True)


def main() -> None:
    replace_previous_instance()
    server = None
    for attempt in range(50):  # bind first; a just-closed port can linger for a few seconds
        try:
            server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
            break
        except OSError as e:
            if attempt == 49:
                sys.exit(f"port {PORT} is busy ({e}); stop the other helper (Ctrl+C in its window) and try again")
            time.sleep(0.2)
    try:
        dev = sd.query_devices(kind="input")
        where = {"darwin": "System Settings → Sound → Input", "win32": "Settings → System → Sound → Input"}.get(
            sys.platform, "your system's sound settings")
        print(f"microphone: {dev['name']} ({dev['default_samplerate']:.0f} Hz); change it in {where}"
              " or in the game's settings (Microphone)", flush=True)
        print("microphones: " + ", ".join(d["name"] for d in input_devices()), flush=True)
    except Exception as e:  # noqa: BLE001
        print(f"no input device found: {e}", flush=True)
    try:
        LOG_FILE.write_text("")  # a fresh log per start
    except OSError:
        pass
    for language in LANGUAGES:
        models.request(language)
    log(f"listening on http://127.0.0.1:{PORT}; languages at start: {', '.join(LANGUAGES)}")
    try:
        server.serve_forever()
    finally:
        server.server_close()  # release the port at once so a replacement can bind


if __name__ == "__main__":
    main()
