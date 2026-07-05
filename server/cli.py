#!/usr/bin/env python3
"""CLI playground (point 8): run the SAME fix flow the API uses, to iterate on system prompts.

Examples:
    python cli.py "He walk to the store. She give him a apple."
    python cli.py --file chapter.txt --model qwen2.5-7b
    python cli.py --file chapter.txt --prompt prompts/system_prompt.md
    cat chapter.txt | python cli.py                       # read from stdin
"""
import argparse
import asyncio
import sys
from pathlib import Path

from app.fixer import fix_text
from app.logging_config import setup_logging


async def main() -> None:
    ap = argparse.ArgumentParser(description="QuickNovel LLM fix playground")
    ap.add_argument("text", nargs="?", help="text to fix (or use --file / stdin)")
    ap.add_argument("--file", help="read input from a file")
    ap.add_argument("--model", help="model id (see config.yaml)")
    ap.add_argument("--prompt", help="path to a system-prompt .md to test (overrides default)")
    ap.add_argument("--memory", default="", help="character memory block to inject")
    ap.add_argument("--quiet", action="store_true", help="only print the fixed text")
    args = ap.parse_args()

    if not args.quiet:
        setup_logging()

    if args.file:
        text = Path(args.file).read_text(encoding="utf-8")
    elif args.text:
        text = args.text
    else:
        text = sys.stdin.read()
    if not text.strip():
        print("no input text", file=sys.stderr)
        sys.exit(1)

    system = Path(args.prompt).read_text(encoding="utf-8") if args.prompt else None
    out = await fix_text(text, args.model, character_memory=args.memory, system_prompt=system)
    print("\n" + ("=" * 60) + "\n" if not args.quiet else "", end="")
    print(out)


if __name__ == "__main__":
    asyncio.run(main())
