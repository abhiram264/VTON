#!/usr/bin/env python3
"""Black-box policy: REDIS_URL and S3_REGION must not be active V1 config."""

from __future__ import annotations

import re
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent

FORBIDDEN = ("REDIS_URL", "S3_REGION")
TOKEN = re.compile(r"\b(?:REDIS_URL|S3_REGION)\b")

SCAN = (
    Path("backend") / "src" / "main",
    Path("compose.yaml"),
    Path(".env.example"),
    Path("infra"),
    Path(".github"),
)


def _iter_files(root: Path) -> list[Path]:
    if not root.exists():
        return []
    if root.is_file():
        return [root]
    out: list[Path] = []
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        if "target" in path.parts:
            continue
        out.append(path)
    return out


class ForbiddenConfigPolicy(unittest.TestCase):
    def test_forbidden_variables_absent_from_active_config(self) -> None:
        hits: list[str] = []
        for rel in SCAN:
            for path in _iter_files(REPO / rel):
                try:
                    text = path.read_text(encoding="utf-8")
                except UnicodeDecodeError:
                    continue
                rel_posix = path.relative_to(REPO).as_posix()
                if rel_posix.endswith("VaultEnvironmentValidator.java"):
                    continue
                if TOKEN.search(text):
                    hits.append(rel_posix)
        self.assertEqual(
            hits,
            [],
            f"forbidden {FORBIDDEN} in active config: {hits}",
        )


if __name__ == "__main__":
    unittest.main()
