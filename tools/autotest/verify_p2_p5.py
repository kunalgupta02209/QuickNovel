"""On-device verification of P2 (script modes feed TTS — the D9 fix) and P5 (multi-voice cue
rendering), run on the F41 after verify_server_tts.py passes.

P2/D9 assert: with reader script mode = grammar and a sentinel fixed chapter planted in the app's
llm-fixed cache, opening the book must submit /tts/batch sentences hashed from the FIXED text (the
sentinel appears in the server's stored chapter text for that book).

P5 assert: with a performance ScriptDoc + charmap casting planted (two different cast sids), starting
TTS playback must produce OnDeviceTtsEngine render logs with BOTH sids (per-line voice switching).

Run:  .venv/Scripts/python.exe verify_p2_p5.py
"""

from __future__ import annotations

import json
import subprocess
import sys
import time
from pathlib import Path

import requests

ADB = "C:/Users/kunal/AppData/Local/Android/Sdk/platform-tools/adb.exe"
SERIAL = "RZ8R329XCVX"  # Samsung F41 test mule
PKG = "com.lagradost.quicknovel.debug"
SERVER = "http://localhost:8077"
DEVICE_SERVER = "http://127.0.0.1:8077"
BOOK_ID = "b-2021364983"  # Keyboard Immortal
LLM_MODEL = "qwen2.5-1.5b"  # the app default llm_fix_model pref
PROMPT_V = 1
SENTINEL = "The zephyr quartz beacon flickered exactly twice."
ART = Path(__file__).parent / "artifacts"
ART.mkdir(exist_ok=True)

_results: list[tuple[str, bool, str]] = []


def check(name: str, ok: bool, detail: str = "") -> bool:
    _results.append((name, ok, detail))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f" — {detail}" if detail else ""))
    return ok


def adb(*args: str, timeout: int = 60, stdin: str | None = None) -> str:
    r = subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, text=True,
                       timeout=timeout, encoding="utf-8", errors="replace", input=stdin)
    return (r.stdout or "") + (r.stderr or "")


def run_as(cmd: str) -> str:
    return adb("shell", f"run-as {PKG} sh -c '{cmd}'")


def push_app_file(rel_path: str, content: str) -> None:
    run_as(f"mkdir -p $(dirname files/{rel_path})")
    adb("shell", f"run-as {PKG} sh -c 'cat > files/{rel_path}'", stdin=content)


PREFS = "shared_prefs/rebuild_preference.xml"


def inject_prefs(pairs: dict[str, str]) -> None:
    import re
    xml = run_as(f"cat {PREFS}")
    if "<map" not in xml:
        xml = '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<map>\n</map>\n'
    for key, value in pairs.items():
        esc = value.replace('"', "&quot;")
        entry = f'    <string name="{key}">{esc}</string>'
        xml = re.sub(rf'\n?\s*<string name="{key}">.*?</string>', "", xml, flags=re.DOTALL)
        xml = xml.replace("</map>", f"{entry}\n</map>")
    adb("shell", f"run-as {PKG} sh -c 'cat > {PREFS}'", stdin=xml)


def open_book() -> None:
    author = "Monk Of The Six Illusions"
    name = "Keyboard Immortal by Monk Of The Six Illusions - 2841 Chapters"
    qs = {
        "meta": {"author": author, "name": name, "apiName": "LightNovelWorld"},
        "poster": None,
        "data": [{"name": f"Chapter {i + 1}", "url": f"local://{i}", "dateOfRelease": None, "views": None}
                 for i in range(205)],
    }
    adb("shell", f"run-as {PKG} sh -c 'cat > cache/verify_quickstream.json'", stdin=json.dumps(qs))
    adb("shell", "am", "start", "-n", f"{PKG}/com.lagradost.quicknovel.ReadActivity2",
        "-d", f"file:///data/data/{PKG}/cache/verify_quickstream.json", "-t", "quickstream")


def main() -> int:
    print("== 0. preconditions ==")
    adb("shell", "am", "force-stop", PKG)
    time.sleep(1)
    adb("reverse", "tcp:8077", "tcp:8077")
    check("server healthy", requests.get(f"{SERVER}/tts/health", timeout=5).json().get("ok") is True)

    # ---------------- P2 / D9: script mode feeds TTS ----------------
    print("== 1. P2/D9: grammar script feeds the TTS pipeline ==")
    fixed_dir = f"llm-fixed/{BOOK_ID}/{LLM_MODEL}-v{PROMPT_V}"
    push_app_file(f"{fixed_dir}/c0.txt", f"{SENTINEL}\n\nA second fixed paragraph for chapter one.")
    inject_prefs({
        "reader_epub_tts_engine": "1",
        "reader_epub_tts_server_autogen": "true",
        "llm_fix_server_url": f'"{DEVICE_SERVER}"',
        "llm_fix_script_mode": "1",  # grammar mode ON
        "downloads_total/-2021364983": "2841",
    })
    # wipe chapter 0 TTS cache so its sentences resubmit
    run_as(f"rm -rf files/tts-cache/{BOOK_ID}/kitten/s0/c0")
    baseline_jobs = {j["id"] for j in requests.get(f"{SERVER}/tts/jobs", timeout=5).json()["jobs"]}
    open_book()
    time.sleep(10)

    job = None
    deadline = time.time() + 90
    while time.time() < deadline and job is None:
        for j in requests.get(f"{SERVER}/tts/jobs", timeout=5).json()["jobs"]:
            if j["id"] not in baseline_jobs and j.get("book_id") == BOOK_ID:
                job = j
        time.sleep(3)
    check("new tts job after open", job is not None)

    # The server stores chapter text from the submitted sentences — the sentinel must be there.
    ok = False
    deadline = time.time() + 30
    while time.time() < deadline and not ok:
        try:
            r = subprocess.run(["docker", "exec", "server-fixserver-1", "sh", "-c",
                                f"cat /app/data/chapters/{BOOK_ID}/c0.txt 2>/dev/null | head -c 400"],
                               capture_output=True, text=True, timeout=20)
            ok = "zephyr quartz beacon" in (r.stdout or "")
        except Exception:
            pass
        if not ok:
            time.sleep(3)
    check("D9: TTS sentences are the FIXED text (sentinel found server-side)", ok)

    # ---------------- P5: multi-voice render ----------------
    print("== 2. P5: per-line voice switching ==")
    adb("shell", "am", "force-stop", PKG)
    time.sleep(1)
    # performance ScriptDoc for chapter 0: narrator span + one character span (distinct sid)
    doc = [
        {"i": 0, "src": "llm", "spans": [
            {"text": SENTINEL, "speaker": "narrator", "delivery": "neutral", "events": []}]},
        {"i": 1, "src": "llm", "spans": [
            {"text": "A second fixed paragraph for chapter one.", "speaker": "char:testcast0001",
             "delivery": "excited", "events": [{"tag": "laugh", "pos": "before"}]}]},
    ]
    push_app_file(f"llm-fixed/{BOOK_ID}/{LLM_MODEL}-v{PROMPT_V}-perf/c0.txt",
                  f"{SENTINEL}\n\nA second fixed paragraph for chapter one.")
    push_app_file(f"llm-fixed/{BOOK_ID}/{LLM_MODEL}-v{PROMPT_V}-perf/c0.json", json.dumps(doc))
    charmap = {
        "narration": {"pov": "third", "casting": {"model": "kitten", "sid": 1, "voice_name": "Bella"}},
        "characters": [{"id": "testcast0001", "name": "Test Cast",
                        "casting": {"model": "kitten", "sid": 6, "voice_name": "Leo", "speed": 1.05}}],
    }
    push_app_file(f"charmap/{BOOK_ID}.json", json.dumps(charmap))
    inject_prefs({"llm_fix_script_mode": "2", "reader_epub_tts_casting": "true",
                  "reader_epub_tts_server_autogen": "false", "reader_epub_tts_autogen": "false"})
    adb("logcat", "-c")
    open_book()
    time.sleep(8)
    # start playback via the media key (the reader registers a media session when TTS starts) —
    # fall back to tapping the TTS play button by resource id via uiautomator2.
    try:
        import uiautomator2 as u2
        d = u2.connect(SERIAL)
        d.screenshot(str(ART / "p5_reader.png"))
        # tap content once to reveal the bottom bar, then hit the TTS button
        d.click(0.5, 0.5)
        time.sleep(1)
        for rid in ("read_action_tts", "readAction_tts", "read_tts_start"):
            el = d(resourceId=f"{PKG}:id/{rid}")
            if el.exists(timeout=1):
                el.click()
                break
        else:
            # last resort: bottom-center where the TTS button lives
            d.click(0.5, 0.93)
    except Exception as e:
        print("  (uiautomator fallback failed:", e, ")")
    time.sleep(25)  # let a few lines render
    logs = adb("logcat", "-d", "-s", "OnDeviceTts:D", "TtsEngine:D", "OnDeviceTtsEngine:D")
    (ART / "p5_logcat.txt").write_text(logs, encoding="utf-8")
    sids = set()
    for line in logs.splitlines():
        if "render" in line and "sid=" in line:
            try:
                sids.add(int(line.split("sid=")[1].split()[0]))
            except (ValueError, IndexError):
                pass
    check("P5: renders with >1 distinct sid (multi-voice)", len(sids) > 1, f"sids seen: {sorted(sids)}")

    print("\n== RESULT ==")
    failed = [r for r in _results if not r[1]]
    for name, ok, _ in _results:
        print(f"  {'PASS' if ok else 'FAIL'}: {name}")
    print(f"\n{'ALL PASS' if not failed else f'{len(failed)} FAILED'} — artifacts in {ART}")
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
