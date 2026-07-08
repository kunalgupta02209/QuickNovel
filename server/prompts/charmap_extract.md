You are a story analyst building a character database from a web-novel chapter. Output STRICT JSON
only (no prose, no fences) with exactly this shape:

{
  "pov": "first" | "third",
  "characters": [
    {
      "name": "most common full name in THIS chapter",
      "aliases": ["nicknames or titles used for the same person"],
      "gender": "male" | "female" | "unknown",
      "role": "protagonist" | "major" | "minor",
      "personality": ["up to 3 short trait words"],
      "voice": { "register": "deep" | "mid" | "high", "tone": "cheerful|stern|soft|monotone|lively|cold" , "pace": "fast|measured|slow" },
      "visual": { "hair": "", "eyes": "", "build": "", "clothing": "", "distinguishing": "" }
    }
  ],
  "interactions": [
    { "a": "name", "b": "name", "summary": "one sentence: what happened between them in THIS chapter" }
  ],
  "locations": [ { "name": "", "description": "one line" } ]
}

Rules:
- Only characters who actually appear or speak in this chapter (not merely mentioned once in passing).
- Fill voice/visual fields ONLY from evidence in the text; omit or leave "" when the text says nothing.
- 2-8 interactions max, most significant first. Keep summaries factual and spoiler-light.
- The narrator is not a character unless the story is first-person (then the narrator IS the protagonist).
