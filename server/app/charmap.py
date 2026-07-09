"""Character-map pipeline: per-chapter LLM extraction (cached) -> fuzzy merge -> book-level map with
voice casting. Replaces the broken on-device CharacterGraph. Powers: character_memory backfill for
fixes, performance-script casting, the dashboard card, and the reader's character/world search
("where did X appear, how did X interact").

Storage: data/charmaps/<book_id>/extractions/c<i>.json (immutable per-chapter cache) + map.json.
"""

from __future__ import annotations

import asyncio
import difflib
import hashlib
import json
import logging
import os
import re
import time
from pathlib import Path

import litellm

from . import budget, chapter_texts
from .config import config
from .prompts import prompts

log = logging.getLogger("charmap")

ROOT = Path("data/charmaps")
_SAFE = re.compile(r"[^A-Za-z0-9_.-]")
_MATCH_THRESHOLD = 0.86


def _dir(book_id: str) -> Path:
    return ROOT / _SAFE.sub("_", book_id)[:64]


def _extraction_path(book_id: str, idx: int) -> Path:
    return _dir(book_id) / "extractions" / f"c{idx}.json"


def map_path(book_id: str) -> Path:
    return _dir(book_id) / "map.json"


def char_id(name: str) -> str:
    return hashlib.sha1(name.strip().lower().encode("utf-8")).hexdigest()[:12]


# ---- LLM extraction ------------------------------------------------------------------------


def _slice_json(text: str) -> dict | None:
    """Parse the model output as JSON, tolerating fences/preamble via bracket slicing."""
    for candidate in (text, text[text.find("{"): text.rfind("}") + 1]):
        try:
            v = json.loads(candidate)
            if isinstance(v, dict):
                return v
        except Exception:  # noqa: BLE001
            continue
    return None


async def _llm_json(system: str, user: str, task: str = "character_summary") -> dict | None:
    """One JSON-mode completion through the task routing with cloud->fallback, budget-recorded."""
    routing = config.task_model(task)
    for model_id in [routing["model"], routing.get("fallback")]:
        if not model_id:
            continue
        entry = config.model_entry(model_id) or {}
        is_cloud = entry.get("kind") == "cloud"
        try:
            kwargs = dict(
                model=entry.get("litellm") or config.litellm_for(model_id),
                messages=[{"role": "system", "content": system}, {"role": "user", "content": user}],
                num_retries=1, timeout=180, temperature=0.2, max_tokens=4096,
            )
            if is_cloud:
                kwargs["api_base"] = config.api_base_for(model_id)
                kwargs["api_key"] = config.api_key_for(model_id)
                if not kwargs["api_key"]:
                    continue
                kwargs["response_format"] = {"type": "json_object"}
                effort = config.reasoning_effort(task)
                if effort:
                    kwargs["extra_body"] = {"reasoning_effort": effort}
            else:
                kwargs["api_base"] = config.ollama_base_url
            t0 = time.time()
            resp = await litellm.acompletion(**kwargs)
            usage = getattr(resp, "usage", None)
            if is_cloud:
                budget.record(task, model_id, getattr(usage, "prompt_tokens", 0) or 0,
                              getattr(usage, "completion_tokens", 0) or 0, 0.0)
            out = _slice_json(resp.choices[0].message.content or "")
            if out is not None:
                log.info("charmap llm ok model=%s %.1fs", model_id, time.time() - t0)
                return out
            log.warning("charmap llm returned non-JSON (model=%s)", model_id)
        except Exception as e:  # noqa: BLE001
            log.warning("charmap llm failed (model=%s): %s", model_id, str(e)[:160])
    return None


async def extract_chapter(book_id: str, idx: int, force: bool = False) -> dict | None:
    """Extract characters/interactions/locations from one chapter (cached; one LLM call each)."""
    cache = _extraction_path(book_id, idx)
    if cache.exists() and not force:
        try:
            return json.loads(cache.read_text(encoding="utf-8"))
        except Exception:  # noqa: BLE001
            pass
    text = chapter_texts.get_text(book_id, idx)
    if not text:
        return None
    out = await _llm_json(prompts.get("charmap_extract"), text[:14000])
    if out is None:
        return None
    out = {
        "characters": out.get("characters") or [],
        "interactions": out.get("interactions") or [],
        "locations": out.get("locations") or [],
        "pov": out.get("pov") or "third",
        "chapter": idx,
    }
    cache.parent.mkdir(parents=True, exist_ok=True)
    cache.write_text(json.dumps(out, ensure_ascii=False), encoding="utf-8")
    return out


def extraction_indices(book_id: str) -> list[int]:
    d = _dir(book_id) / "extractions"
    if not d.is_dir():
        return []
    out = []
    for f in d.glob("c*.json"):
        try:
            out.append(int(f.stem[1:]))
        except ValueError:
            pass
    return sorted(out)


def _load_extraction(book_id: str, idx: int) -> dict | None:
    p = _extraction_path(book_id, idx)
    try:
        return json.loads(p.read_text(encoding="utf-8")) if p.exists() else None
    except Exception:  # noqa: BLE001
        return None


# ---- merge ---------------------------------------------------------------------------------


def _similar(a: str, b: str) -> float:
    return difflib.SequenceMatcher(None, a.lower().strip(), b.lower().strip()).ratio()


def _find_merged(merged: dict, name: str) -> str | None:
    """Match a raw name against merged characters: exact, alias, given-name-vs-full-name token
    containment ("Mira" == "Mira Chen"), then fuzzy."""
    lname = name.lower().strip()
    ltokens = set(lname.split())
    for cid, c in merged.items():
        if c["name"].lower() == lname or lname in [a.lower() for a in c.get("aliases", [])]:
            return cid
    for cid, c in merged.items():
        for cand in [c["name"], *c.get("aliases", [])]:
            ctokens = set(cand.lower().split())
            # one name is a token-subset of the other (given vs full name), tokens long enough to trust
            if (ltokens <= ctokens or ctokens <= ltokens) and \
                    all(len(t) > 2 for t in (ltokens & ctokens or ltokens)):
                return cid
    best, best_score = None, 0.0
    for cid, c in merged.items():
        for cand in [c["name"], *c.get("aliases", [])]:
            s = _similar(cand, name)
            if s > best_score:
                best, best_score = cid, s
    return best if best_score >= _MATCH_THRESHOLD else None


def merge_extractions(book_id: str, through_chapter: int | None = None) -> dict:
    """Fold per-chapter extractions into a spoiler-gated book map (no LLM; deterministic)."""
    merged: dict[str, dict] = {}
    interactions: list[dict] = []
    locations: dict[str, dict] = {}
    pov_votes: dict[str, int] = {}
    idxs = [i for i in extraction_indices(book_id)
            if through_chapter is None or i <= through_chapter]
    for i in idxs:
        ex = _load_extraction(book_id, i)
        if not ex:
            continue
        pov_votes[ex.get("pov") or "third"] = pov_votes.get(ex.get("pov") or "third", 0) + 1
        for ch in ex.get("characters") or []:
            name = (ch.get("name") or "").strip()
            if not name or len(name) > 60:
                continue
            cid = _find_merged(merged, name)
            if cid is None:
                cid = char_id(name)
                merged[cid] = {
                    "id": cid, "name": name, "aliases": [], "gender": {}, "role": ch.get("role") or "minor",
                    "personality": [], "voice": {}, "visual": {}, "occurrences": [], "first_chapter": i,
                }
            c = merged[cid]
            c["occurrences"] = sorted(set(c["occurrences"] + [i]))
            for a in (ch.get("aliases") or []) + ([name] if name != c["name"] else []):
                if a and a not in c["aliases"] and a != c["name"] and len(c["aliases"]) < 8:
                    c["aliases"].append(a)
            g = (ch.get("gender") or "").lower()
            if g in ("male", "female"):
                c["gender"][g] = c["gender"].get(g, 0) + 1
            for p in (ch.get("personality") or [])[:4]:
                if p and p not in c["personality"] and len(c["personality"]) < 10:
                    c["personality"].append(p)
            for k, v in (ch.get("voice") or {}).items():
                if v and k not in c["voice"]:
                    c["voice"][k] = v
            for k, v in (ch.get("visual") or {}).items():
                if v and k not in c["visual"]:
                    c["visual"][k] = v
            if (ch.get("role") or "") == "protagonist":
                c["role"] = "protagonist"
        for it in ex.get("interactions") or []:
            if it.get("a") and it.get("b") and it.get("summary"):
                interactions.append({**it, "chapter": i})
        for lo in ex.get("locations") or []:
            n = (lo.get("name") or "").strip()
            if n and n.lower() not in locations:
                locations[n.lower()] = {"name": n, "description": lo.get("description") or "",
                                        "first_chapter": i}
    for c in merged.values():
        votes = c["gender"]
        c["gender"] = max(votes, key=votes.get) if votes else "unknown"
        c["prominence"] = len(c["occurrences"])
    chars = sorted(merged.values(), key=lambda c: -c["prominence"])
    pov = max(pov_votes, key=pov_votes.get) if pov_votes else "third"
    return {
        "book_id": book_id,
        "book_name": chapter_texts.meta(book_id).get("book_name") or book_id,
        "built_at": time.time(),
        "through_chapter": idxs[-1] if idxs else -1,
        "narration": {"pov": pov},
        "characters": chars,
        "interactions": interactions[-500:],
        "locations": list(locations.values()),
    }


def save_map(book_id: str, m: dict) -> None:
    p = map_path(book_id)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(m, ensure_ascii=False, indent=1), encoding="utf-8")


def load_map(book_id: str) -> dict | None:
    p = map_path(book_id)
    try:
        return json.loads(p.read_text(encoding="utf-8")) if p.exists() else None
    except Exception:  # noqa: BLE001
        return None


def list_maps() -> list[dict]:
    out = []
    if ROOT.is_dir():
        for d in ROOT.iterdir():
            m = load_map(d.name)
            if m:
                out.append({"book_id": d.name, "book_name": m.get("book_name"),
                            "characters": len(m.get("characters") or []),
                            "through_chapter": m.get("through_chapter"),
                            "built_at": m.get("built_at")})
    return out


# ---- consumers ------------------------------------------------------------------------------


def prompt_block(book_id: str, through_chapter: int | None = None, max_chars: int = 1200) -> str:
    """CAST/NARRATION block injected into fix/performance prompts (spoiler-gated)."""
    m = load_map(book_id)
    if not m:
        return ""
    lines = [f"NARRATION: {m['narration']['pov']}-person"]
    for c in m.get("characters") or []:
        if through_chapter is not None and c.get("first_chapter", 0) > through_chapter:
            continue
        traits = ", ".join(filter(None, [c.get("gender"), *(c.get("personality") or [])[:2],
                                         (c.get("voice") or {}).get("tone")]))
        lines.append(f"CAST char:{c['id']} {c['name']} ({traits})")
        if sum(len(x) for x in lines) > max_chars:
            break
    return "\n".join(lines)


def search(book_id: str, q: str, through_chapter: int | None = None) -> dict:
    """Reader search: who/what is <q>? -> character card + occurrences + interactions + locations."""
    m = load_map(book_id) or {}
    ql = q.lower().strip()
    hits = []
    for c in m.get("characters") or []:
        if through_chapter is not None and c.get("first_chapter", 0) > through_chapter:
            continue
        names = [c["name"], *c.get("aliases", [])]
        score = max((_similar(n, q) for n in names), default=0.0)
        if ql and (any(ql in n.lower() for n in names) or score >= 0.72):
            occ = [i for i in c.get("occurrences", []) if through_chapter is None or i <= through_chapter]
            inter = [it for it in m.get("interactions") or []
                     if (through_chapter is None or it.get("chapter", 0) <= through_chapter)
                     and (ql in (it.get("a") or "").lower() or ql in (it.get("b") or "").lower()
                          or any(_similar(n, it.get("a") or "") >= _MATCH_THRESHOLD
                                 or _similar(n, it.get("b") or "") >= _MATCH_THRESHOLD for n in names))]
            hits.append({**c, "occurrences": occ, "interactions": inter[-40:], "match": round(score, 2)})
    hits.sort(key=lambda h: (-h["match"], -h.get("prominence", 0)))
    locs = [lo for lo in m.get("locations") or []
            if ql in lo.get("name", "").lower()
            and (through_chapter is None or lo.get("first_chapter", 0) <= through_chapter)]
    return {"query": q, "characters": hits[:5], "locations": locs[:5]}
