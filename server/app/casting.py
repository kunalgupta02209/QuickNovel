"""Deterministic voice casting from the character map. Voice tables mirror the app's TtsModels
labels (kokoro 11 voices with genders; kitten 8 alternating m/f). Casting lives IN map.json
(characters[].casting) so the dashboard can edit it and the app just reads it."""

from __future__ import annotations

# (sid, gender, display name) — order = assignment preference (varied, high-quality first)
KOKORO = [
    (5, "male", "Adam"), (6, "male", "Michael"), (9, "male", "George"), (10, "male", "Lewis"),
    (1, "female", "Bella"), (2, "female", "Nicole"), (3, "female", "Sarah"), (7, "female", "Emma"),
    (8, "female", "Isabella"), (4, "female", "Sky"), (0, "female", "Ava"),
]
KITTEN = [
    (0, "male", "Jasper"), (2, "male", "Bruno"), (4, "male", "Hugo"), (6, "male", "Leo"),
    (1, "female", "Bella"), (3, "female", "Luna"), (5, "female", "Rosie"), (7, "female", "Kiki"),
]
NARRATOR = {"kokoro": 1, "kitten": 1}  # Bella both — warm, neutral default narrator

_REGISTER_PITCH = {"deep": 0.92, "mid": 1.0, "high": 1.06}
_PACE_SPEED = {"fast": 1.08, "measured": 1.0, "slow": 0.93}


def assign(m: dict, model_id: str = "kokoro") -> dict:
    """Assign a voice to every character (most prominent first), preserving `locked` entries.
    1st-person narration: the protagonist's voice IS the narrator voice (head-voice)."""
    table = KOKORO if model_id == "kokoro" else KITTEN
    males = [v for v in table if v[1] == "male"]
    females = [v for v in table if v[1] == "female"]
    used: dict[int, int] = {}

    def next_voice(gender: str) -> tuple[int, str]:
        pool = males if gender == "male" else females if gender == "female" else table
        best = min(pool, key=lambda v: used.get(v[0], 0))
        used[best[0]] = used.get(best[0], 0) + 1
        return best[0], best[2]

    chars = m.get("characters") or []
    for c in sorted(chars, key=lambda c: -c.get("prominence", 0)):
        existing = c.get("casting") or {}
        if existing.get("locked"):
            used[existing.get("sid", -1)] = used.get(existing.get("sid", -1), 0) + 1
            continue
        sid, vname = next_voice(c.get("gender") or "unknown")
        voice = c.get("voice") or {}
        c["casting"] = {
            "model": model_id, "sid": sid, "voice_name": vname,
            "pitch": _REGISTER_PITCH.get((voice.get("register") or "mid"), 1.0),
            "speed": _PACE_SPEED.get((voice.get("pace") or "measured"), 1.0),
            "locked": False,
        }

    pov = (m.get("narration") or {}).get("pov") or "third"
    protagonist = next((c for c in chars if c.get("role") == "protagonist"), None)
    if pov == "first" and protagonist is not None:
        narrator = dict(protagonist["casting"])  # head-voice narration
        narrator["note"] = "protagonist head-voice (1st person)"
    else:
        narrator = {"model": model_id, "sid": NARRATOR.get(model_id, 0), "voice_name": "Bella",
                    "pitch": 1.0, "speed": 1.0, "note": "neutral narrator (3rd person)"}
    m["narration"] = {**(m.get("narration") or {}), "casting": narrator}
    m["casting_meta"] = {"model": model_id,
                         "cast_version": int((m.get("casting_meta") or {}).get("cast_version", 0)) + 1}
    return m


# ---- script-aware cast alignment (server-side mirror of the app's CueRenderer) ----------------

def _norm(s: str) -> str:
    return "".join(ch for ch in s.lower() if ch.isalnum())


def build_speaker_cast(m: dict, model_id: str) -> tuple[dict, tuple[int | None, float]]:
    """speaker "char:<id>" -> (sid, speed) for the ACTIVE model, replicating the app's re-cast
    algorithm exactly (same tables, prominence order, first-minimal round-robin) so server-generated
    audio hashes into the same cache slots the app's playback resolver looks in."""
    table = KOKORO if model_id == "kokoro" else KITTEN
    males = [v for v in table if v[1] == "male"]
    females = [v for v in table if v[1] == "female"]
    used: dict[int, int] = {}

    def next_by_gender(gender: str | None) -> int:
        pool = males if gender == "male" else females if gender == "female" else list(table)
        pool = pool or list(table)
        best = min(pool, key=lambda v: used.get(v[0], 0))
        used[best[0]] = used.get(best[0], 0) + 1
        return best[0]

    out: dict = {}
    chars = sorted(m.get("characters") or [], key=lambda c: -(c.get("prominence") or 0))
    for c in chars:
        cast = c.get("casting") or {}
        speed = float(cast.get("speed") or 1.0)
        sid = cast.get("sid") if cast.get("model") == model_id else next_by_gender(c.get("gender"))
        out[f"char:{c.get('id')}"] = (sid, speed)
    ncast = (m.get("narration") or {}).get("casting") or {}
    narrator = (ncast.get("sid") if ncast.get("model") == model_id else None,
                float(ncast.get("speed") or 1.0))
    return out, narrator


def cast_lines(book_id: str, model_id: str, chapter_index: int, lines: list[str]) -> list[dict] | None:
    """Assign a (sid, speed) to each sentence line by aligning it to the chapter's performance
    ScriptDoc spans (normalized equality, then containment — the app's ScriptAligner logic).
    Returns None when the chapter has no ScriptDoc."""
    import json as _json
    from pathlib import Path

    doc_path = Path("data/scripts") / book_id / "performance" / f"c{chapter_index}.json"
    if not doc_path.exists():
        return None
    from . import charmap as _charmap
    m = _charmap.load_map(book_id) or {}
    speaker_cast, narrator = build_speaker_cast(m, model_id)
    try:
        doc = _json.loads(doc_path.read_text(encoding="utf-8"))
    except Exception:  # noqa: BLE001
        return None
    spans = []
    for p in doc or []:
        for span in p.get("spans") or []:
            sp = span.get("speaker") or "narrator"
            sid, speed = narrator if sp == "narrator" else speaker_cast.get(sp, narrator)
            spans.append((_norm(span.get("text") or ""), sid, speed))
    out = []
    for line in lines:
        n = _norm(line)
        hit = next((s for s in spans if s[0] == n), None) or \
            (next((s for s in spans if n and n in s[0]), None) if n else None)
        sid, speed = (hit[1], hit[2]) if hit else narrator
        out.append({"text": line, "sid": sid, "speed": speed if speed != 1.0 else None})
    return out
