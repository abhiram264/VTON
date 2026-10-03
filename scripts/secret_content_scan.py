#!/usr/bin/env python3
"""Content-based secret scan. Reports path and rule only; never prints secret values."""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path


REPO = Path(__file__).resolve().parent.parent
SKIP_DIRS = {
    ".cache", ".cxx", ".expo", ".expo-shared", ".git", ".gradle", ".idea",
    ".npm", ".nyc_output", ".opencode", ".scratch", ".terraform", ".venv", ".vscode",
    ".yarn", "__pycache__", "Pods", "DerivedData", "build", "captures", "coverage",
    "dist", "metro-cache", "node_modules", "target", "venv", "xcuserdata",
}
_SA = "service_" + "account"
_PK = "private_" + "key"
_PEM = re.compile("-----BEGIN " + r"(?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----")
RULE_PEM = "private-key"
RULE_SA = "google-service-account"


def _walk(root: Path) -> list[Path]:
    out: list[Path] = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [name for name in dirnames if name not in SKIP_DIRS]
        out.extend(Path(dirpath) / name for name in filenames)
    return out


def iter_scan_files(root: Path) -> list[Path]:
    return _walk(root)


def _dicts(text: str) -> list[dict]:
    found: list[dict] = []
    dec = json.JSONDecoder()
    i = 0
    while i < len(text):
        start = text.find("{", i)
        if start < 0:
            break
        try:
            obj, end = dec.raw_decode(text, start)
        except json.JSONDecodeError:
            i = start + 1
            continue
        if isinstance(obj, dict):
            found.append(obj)
        i = max(end, start + 1)
    return found


def scan_text(text: str) -> list[str]:
    rules: list[str] = []
    if _PEM.search(text):
        rules.append(RULE_PEM)
    if any(obj.get("type") == _SA and _PK in obj for obj in _dicts(text)):
        rules.append(RULE_SA)
    return rules


def scan(root: Path) -> list[tuple[str, str]]:
    hits: list[tuple[str, str]] = []
    for path in iter_scan_files(root):
        if not path.is_file():
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        rel = path.relative_to(root).as_posix()
        for rule in scan_text(text):
            hits.append((rel, rule))
    hits.sort()
    return hits


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Content-based secret scan (stdlib).")
    parser.add_argument("--root", default=None)
    args = parser.parse_args(argv)
    root = Path(args.root).resolve() if args.root else REPO
    hits = scan(root)
    for rel, rule in hits:
        print(f"{rel}: {rule}", file=sys.stderr)
    return 1 if hits else 0


if __name__ == "__main__":
    raise SystemExit(main())
