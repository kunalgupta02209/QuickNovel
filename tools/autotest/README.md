# F41 automation rig (uiautomator2)

Dedicated test mule: Samsung F41 `RZ8R329XCVX` over USB, server reached via `adb reverse tcp:8077`.

- `verify_server_tts.py` — evidence-based verification that "background generation with server
  selected" queues TTS on the server: injects prefs (run-as), opens the book via a synthesized
  quickstream intent, then asserts logcat decision -> new /tts/jobs entry -> progress -> server
  audio files -> WAVs+.done landing in the device cache. Artifacts (screenshots, logcat, job
  snapshots) in `artifacts/`.

Setup: `python -m venv .venv && .venv/Scripts/pip install uiautomator2 requests pillow`
Run:   `.venv/Scripts/python.exe verify_server_tts.py`

Findings encoded here: an old install can lose `downloads_total/<id>` while chapters remain on
disk -> downloadInfo() returns null and the book silently disappears from the library + autogen
(backlog: fall back to the on-disk count).
