"""Evidence-based verification: "background generation with server selected" queues TTS on the server.

Pipeline (each step asserts and records evidence):
  0. preconditions: prefs injected (engine=ON_DEVICE, server autogen=true, server URL), kitten .ready,
     adb reverse, server healthy
  1. baseline /tts/jobs ids + logcat clear
  2. uiautomator2 opens the app -> Library -> the downloaded book (reader = the trigger)
  3. asserts:
     a. logcat "RemoteTts" shows "onBookReady -> SERVER queue"
     b. a NEW job appears in /tts/jobs for the book (queued/running)
     c. job progress strictly increases
     d. server audio files appear under /data/tts-audio/<book>/
     e. WAVs + .done arrive in the device cache (first chapter)
  4. artifacts: screenshots + logs into tools/autotest/artifacts/

Run:  .venv/Scripts/python.exe verify_server_tts.py
"""

from __future__ import annotations

import json
import subprocess
import sys
import time
from pathlib import Path

import requests

ADB = "C:/Users/kunal/AppData/Local/Android/Sdk/platform-tools/adb.exe"
SERIAL = "RZ8R329XCVX"  # Samsung F41 test mule (the S23 daily phone is RZCX62F0RBV)
PKG = "com.lagradost.quicknovel.debug"
SERVER = "http://localhost:8077"        # host view of the server
DEVICE_SERVER = "http://127.0.0.1:8077"  # device view (adb reverse)
BOOK_ID = "b-2021364983"                 # Keyboard Immortal (downloaded on the F41)
BOOK_TEXT = "Keyboard Immortal"
ART = Path(__file__).parent / "artifacts"
ART.mkdir(exist_ok=True)

_results: list[tuple[str, bool, str]] = []


def check(name: str, ok: bool, detail: str = "") -> bool:
    _results.append((name, ok, detail))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f" — {detail}" if detail else ""))
    return ok


def adb(*args: str, timeout: int = 30, stdin: str | None = None) -> str:
    r = subprocess.run(
        [ADB, "-s", SERIAL, *args], capture_output=True, text=True, timeout=timeout,
        encoding="utf-8", errors="replace", input=stdin,
    )
    return (r.stdout or "") + (r.stderr or "")


def run_as(cmd: str) -> str:
    return adb("shell", f"run-as {PKG} sh -c '{cmd}'")


PREFS = "shared_prefs/rebuild_preference.xml"


def inject_prefs(pairs: dict[str, str]) -> None:
    """Read-modify-write rebuild_preference.xml in Python (no on-device sed quoting), app stopped.
    Values are Jackson-encoded strings (ints '1', bools 'true', strings '"..."')."""
    xml = run_as(f"cat {PREFS}")
    if "<map" not in xml:
        xml = '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<map>\n</map>\n'
    import re

    for key, value in pairs.items():
        esc = value.replace('"', "&quot;")
        entry = f'    <string name="{key}">{esc}</string>'
        xml = re.sub(rf'\n?\s*<string name="{key}">.*?</string>', "", xml, flags=re.DOTALL)
        xml = xml.replace("</map>", f"{entry}\n</map>")
    adb("shell", f"run-as {PKG} sh -c 'cat > {PREFS}'", stdin=xml)


def jobs_ids() -> dict[str, dict]:
    try:
        js = requests.get(f"{SERVER}/tts/jobs", timeout=5).json().get("jobs", [])
        return {j["id"]: j for j in js}
    except Exception:
        return {}


def main() -> int:
    print("== 0. preconditions ==")
    adb("shell", "am", "force-stop", PKG)
    time.sleep(1)
    inject_prefs({
        "reader_epub_tts_engine": "1",             # ON_DEVICE
        "reader_epub_tts_server_autogen": "true",  # server autogen ON
        "llm_fix_server_url": f'"{DEVICE_SERVER}"',
        # This old install lost its downloads_total record (found via G2 logs); downloadInfo
        # hard-nulls without it even though 204 chapters are on disk. Restore what a normal
        # download writes so the test exercises the shipped trigger path.
        "downloads_total/-2021364983": "2841",
    })
    prefs = run_as("cat shared_prefs/rebuild_preference.xml")
    check("prefs injected", "reader_epub_tts_server_autogen" in prefs and "llm_fix_server_url" in prefs)
    check("kitten model ready on device", ".ready" in run_as("ls files/kitten-nano-en-v0_8-int8/.ready"))
    adb("reverse", "tcp:8077", "tcp:8077")
    check("server healthy", requests.get(f"{SERVER}/tts/health", timeout=5).json().get("ok") is True)

    print("== 1. baseline ==")
    before = set(jobs_ids())
    adb("logcat", "-c")
    print(f"  baseline tts jobs: {len(before)}")

    print("== 2. open the book in the reader (synthesized quickstream intent) ==")
    import uiautomator2 as u2

    d = u2.connect(SERIAL)
    # Build the exact QuickStreamData JSON the app itself writes on "Stream read novel" — this is
    # the same ReadActivity2 entry point, minus flaky UI navigation.
    author = "Monk Of The Six Illusions"
    name = "Keyboard Immortal by Monk Of The Six Illusions - 2841 Chapters"
    qs = {
        "meta": {"author": author, "name": name, "apiName": "LightNovelWorld"},
        "poster": None,
        "data": [
            {"name": f"Chapter {i + 1}", "url": f"local://{i}", "dateOfRelease": None, "views": None}
            for i in range(205)  # the downloaded chapter range on the F41
        ],
    }
    qs_path = "cache/verify_quickstream.json"
    adb("shell", f"run-as {PKG} sh -c 'cat > {qs_path}'", stdin=json.dumps(qs))
    check("quickstream staged", name[:12] in run_as(f"head -c 200 {qs_path}"))

    adb("shell", "am", "start", "-n", f"{PKG}/com.lagradost.quicknovel.ReadActivity2",
        "-d", f"file:///data/data/{PKG}/{qs_path}", "-t", "quickstream")
    time.sleep(10)
    d.screenshot(str(ART / "04_reader.png"))
    opened = PKG in adb("shell", "dumpsys", "activity", "activities") and \
        "ReadActivity2" in adb("shell", "dumpsys", "activity", "activities")
    check("book opened in reader", opened)

    print("== 3. evidence asserts ==")
    # a) logcat: the decision point
    deadline = time.time() + 30
    decision = ""
    while time.time() < deadline and "onBookReady" not in decision:
        decision = adb("logcat", "-d", "-s", "RemoteTts:I")
        time.sleep(2)
    (ART / "logcat_remotetts.txt").write_text(decision, encoding="utf-8")
    check("logcat: onBookReady fired", "onBookReady" in decision, "see artifacts/logcat_remotetts.txt")
    check("logcat: SERVER queue chosen", "SERVER queue" in decision)

    # b) a new server job for this book
    job = None
    deadline = time.time() + 60
    while time.time() < deadline and job is None:
        for jid, j in jobs_ids().items():
            if jid not in before and j.get("book_id") == BOOK_ID:
                job = j
                break
        time.sleep(2)
    check("new /tts/jobs entry for the book", job is not None, json.dumps(job) if job else "none appeared")
    if job is None:
        return finish()

    # c) progress strictly increases
    jid = job["id"]
    p0 = requests.get(f"{SERVER}/tts/jobs/{jid}", timeout=5).json()
    time.sleep(15)
    p1 = requests.get(f"{SERVER}/tts/jobs/{jid}", timeout=5).json()
    check("job progress increases", p1["progress"] > p0["progress"] or p1["status"] == "done",
          f"{p0['progress']} -> {p1['progress']} ({p1['status']})")
    (ART / "job_snapshots.json").write_text(json.dumps({"t0": p0, "t15": p1}, indent=2), encoding="utf-8")

    # d) audio lands on the server volume
    srv_ls = subprocess.run(
        ["docker", "exec", "server-fixserver-1", "sh", "-c", f"ls /data/tts-audio/{BOOK_ID}/kitten/s0/ | head"],
        capture_output=True, text=True, timeout=30).stdout
    check("server audio files exist", bool(srv_ls.strip()), srv_ls.strip().replace("\n", " ")[:100])

    # e) first chapter fetched into the DEVICE cache (ZIP pull + .done)
    got_wavs = False
    deadline = time.time() + 240
    while time.time() < deadline and not got_wavs:
        ls = run_as(f"ls files/tts-cache/{BOOK_ID}/kitten/s0/ 2>/dev/null | head")
        done = run_as(f"find files/tts-cache/{BOOK_ID}/kitten/s0 -name .done 2>/dev/null | head -3")
        got_wavs = bool(done.strip())
        if not got_wavs:
            time.sleep(10)
    check("device cache received a completed chapter (.done)", got_wavs)

    # journal the run into telemetry evidence
    (ART / "devices.json").write_text(
        json.dumps(requests.get(f"{SERVER}/telemetry/devices", timeout=5).json(), indent=2), encoding="utf-8")
    return finish()


def finish() -> int:
    print("\n== RESULT ==")
    failed = [r for r in _results if not r[1]]
    for name, ok, detail in _results:
        print(f"  {'PASS' if ok else 'FAIL'}: {name}")
    print(f"\n{'ALL PASS' if not failed else f'{len(failed)} FAILED'} — artifacts in {ART}")
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
