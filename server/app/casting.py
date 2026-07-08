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
