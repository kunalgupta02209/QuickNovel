"""Server-side on-device-compatible neural TTS via sherpa-onnx.

Mirrors the Android app's TtsModels: the SAME sherpa-onnx models (kitten / kokoro), the same
per-sentence WAV byte layout (16-bit PCM mono, 44-byte header, 0.01 trim) and the same voice ids
(model + speaker sid), so audio generated here drops straight into the app's on-disk TTS cache and is
byte-placeable + voice-identical to on-device playback. Only the app runs the text pipeline; here we
just turn (model_id, sid, text) into a WAV.
"""

from __future__ import annotations

import bz2
import io
import logging
import os
import struct
import tarfile
import threading
import urllib.request
import wave
from dataclasses import dataclass, field
from pathlib import Path

log = logging.getLogger("tts_engine")

_TTS_BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"


@dataclass
class ModelDef:
    id: str
    kind: str            # "kitten" | "kokoro"
    dir_name: str        # extracted top-level dir
    url: str             # .tar.bz2 release asset
    speakers: int
    sample_rate: int
    required: list[str] = field(default_factory=list)  # files that must exist after extraction


# P1: kitten + kokoro (both 24 kHz). supertonic/zipvoice can be added later (extra files/paths).
MODELS: dict[str, ModelDef] = {
    "kitten": ModelDef(
        id="kitten", kind="kitten", dir_name="kitten-nano-en-v0_8-int8",
        url=f"{_TTS_BASE}/kitten-nano-en-v0_8-int8.tar.bz2",
        speakers=8, sample_rate=24000,
        required=["model.int8.onnx", "voices.bin", "tokens.txt", "espeak-ng-data"],
    ),
    "kokoro": ModelDef(
        id="kokoro", kind="kokoro", dir_name="kokoro-int8-en-v0_19",
        url=f"{_TTS_BASE}/kokoro-int8-en-v0_19.tar.bz2",
        speakers=11, sample_rate=24000,
        required=["model.int8.onnx", "voices.bin", "tokens.txt", "espeak-ng-data"],
    ),
}


def models_dir() -> Path:
    return Path(os.environ.get("TTS_MODELS_DIR", "/models"))


def model_path(defn: ModelDef) -> Path:
    return models_dir() / defn.dir_name


def _ready_marker(defn: ModelDef) -> Path:
    return model_path(defn) / ".ready"


def is_ready(defn: ModelDef) -> bool:
    d = model_path(defn)
    return _ready_marker(defn).exists() and all((d / f).exists() for f in defn.required)


# --------------------------------------------------------------------------------------------------
# Provisioning (Python twin of the app's downloadAndExtract; path-traversal guarded)
# --------------------------------------------------------------------------------------------------

_provision_lock = threading.Lock()


def _safe_extract(tar: tarfile.TarFile, dest: Path) -> None:
    dest = dest.resolve()
    for member in tar.getmembers():
        target = (dest / member.name).resolve()
        if not str(target).startswith(str(dest)):
            raise RuntimeError(f"unsafe tar entry: {member.name}")
    tar.extractall(dest)


def ensure_model(defn: ModelDef) -> None:
    """Download + extract the model tar.bz2 into models_dir if not already provisioned."""
    if is_ready(defn):
        return
    with _provision_lock:
        if is_ready(defn):
            return
        root = models_dir()
        root.mkdir(parents=True, exist_ok=True)
        log.info("provisioning TTS model %s from %s", defn.id, defn.url)
        req = urllib.request.Request(defn.url, headers={"User-Agent": "quicknovel-tts"})
        with urllib.request.urlopen(req, timeout=180) as resp:  # noqa: S310 (trusted GH release)
            raw = resp.read()
        with tarfile.open(fileobj=bz2.BZ2File(io.BytesIO(raw)), mode="r:") as tar:
            _safe_extract(tar, root)
        missing = [f for f in defn.required if not (model_path(defn) / f).exists()]
        if missing:
            raise RuntimeError(f"model {defn.id} missing files after extract: {missing}")
        _ready_marker(defn).write_text("ok")
        log.info("TTS model %s ready at %s", defn.id, model_path(defn))


# --------------------------------------------------------------------------------------------------
# Engine cache (one OfflineTts per model, built lazily)
# --------------------------------------------------------------------------------------------------

_engines: dict[str, object] = {}
_engine_lock = threading.Lock()


def _build_config(defn: ModelDef, num_threads: int):
    import sherpa_onnx  # imported lazily so the module loads even without the dep during unit checks

    d = model_path(defn)

    def p(name: str) -> str:
        return str(d / name)

    if defn.kind == "kokoro":
        model = sherpa_onnx.OfflineTtsModelConfig(
            kokoro=sherpa_onnx.OfflineTtsKokoroModelConfig(
                model=p("model.int8.onnx"), voices=p("voices.bin"),
                tokens=p("tokens.txt"), data_dir=p("espeak-ng-data"),
            ),
            num_threads=num_threads, provider="cpu",
        )
    elif defn.kind == "kitten":
        model = sherpa_onnx.OfflineTtsModelConfig(
            kitten=sherpa_onnx.OfflineTtsKittenModelConfig(
                model=p("model.int8.onnx"), voices=p("voices.bin"),
                tokens=p("tokens.txt"), data_dir=p("espeak-ng-data"),
            ),
            num_threads=num_threads, provider="cpu",
        )
    else:
        raise ValueError(f"unsupported model kind {defn.kind}")

    return sherpa_onnx.OfflineTtsConfig(model=model, max_num_sentences=1)


def get_engine(defn: ModelDef, num_threads: int):
    eng = _engines.get(defn.id)
    if eng is not None:
        return eng
    with _engine_lock:
        eng = _engines.get(defn.id)
        if eng is not None:
            return eng
        import sherpa_onnx

        ensure_model(defn)
        eng = sherpa_onnx.OfflineTts(_build_config(defn, num_threads))
        _engines[defn.id] = eng
        log.info("TTS engine loaded: %s (sr=%d)", defn.id, defn.sample_rate)
        return eng


def invalidate_engines() -> None:
    with _engine_lock:
        _engines.clear()


# --------------------------------------------------------------------------------------------------
# Synthesis + WAV encode (byte-identical to the app's TtsAudioCache.save/trimSilence)
# --------------------------------------------------------------------------------------------------

_SILENCE = 0.01


def _trim_silence(samples) -> list[float]:
    n = len(samples)
    start = 0
    while start < n and abs(samples[start]) < _SILENCE:
        start += 1
    end = n
    while end > start and abs(samples[end - 1]) < _SILENCE:
        end -= 1
    if start >= end:
        return []
    return list(samples[start:end])


def to_wav_bytes(samples, sample_rate: int, trim: bool = True) -> bytes:
    """Encode float samples [-1,1] as 16-bit PCM mono WAV with the canonical 44-byte header."""
    data = _trim_silence(samples) if trim else list(samples)
    pcm = bytearray()
    for s in data:
        v = int(max(-1.0, min(1.0, s)) * 32767.0)
        pcm += struct.pack("<h", v)
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sample_rate)
        w.writeframes(bytes(pcm))
    return buf.getvalue()


def synth_wav(model_id: str, sid: int, text: str, num_threads: int) -> bytes:
    """Synthesize one sentence -> canonical WAV bytes. Raises on unknown model / bad sid."""
    defn = MODELS.get(model_id)
    if defn is None:
        raise KeyError(f"unknown model {model_id}")
    if sid < 0 or sid >= defn.speakers:
        raise ValueError(f"sid {sid} out of range for {model_id} (0..{defn.speakers - 1})")
    eng = get_engine(defn, num_threads)
    audio = eng.generate(text, sid=sid, speed=1.0)  # text is positional (pybind builtin)
    return to_wav_bytes(audio.samples, audio.sample_rate, trim=True)
