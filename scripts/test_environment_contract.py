#!/usr/bin/env python3
"""Black-box policy: REDIS_URL and S3_REGION must not be active V1 config."""

from __future__ import annotations

import re
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
VALIDATOR = (
    Path("backend") / "src" / "main" / "java" / "com" / "aifashionvault"
    / "platform" / "environment" / "VaultEnvironmentValidator.java"
)

FORBIDDEN = ("REDIS_URL", "S3_REGION")
TOKEN = re.compile(r"\b(?:REDIS_URL|S3_REGION)\b")

RETIRED_VALIDATOR_LINES = {
    'if (active(environment, "REDIS_URL", "redis.url")) {',
    'reject("REDIS_URL is not an active V1 configuration");',
    'if (active(environment, "S3_REGION", "s3.region")) {',
    'reject("S3_REGION is not an active V1 configuration");',
}

SCAN = (
    Path("backend") / "src" / "main",
    Path("compose.yaml"),
    Path(".env.example"),
    Path("infra"),
    Path(".github"),
)


def _java_method(text: str, signature: str) -> str | None:
    start = text.find(signature)
    if start < 0:
        return None
    opening = text.find("{", start + len(signature))
    if opening < 0:
        return None
    depth = 0
    for index in range(opening, len(text)):
        if text[index] == "{":
            depth += 1
        elif text[index] == "}":
            depth -= 1
            if depth == 0:
                return text[opening + 1:index]
    return None


def _retired_validator_errors(text: str) -> list[str]:
    errors: list[str] = []
    validate = _java_method(text, "static void validate(")
    if validate is None or re.search(
        r"\brejectRetired\s*\(\s*environment\s*\)\s*;", validate
    ) is None:
        errors.append("validate must invoke rejectRetired(environment)")
    if validate is not None and (
        re.search(r"\btry\s*\{[^{}]*\brejectRetired", validate, re.S) is not None
        or re.search(r"\bcatch\s*\(\s*IllegalStateException", validate) is not None
    ):
        errors.append("validate must not swallow rejectRetired failures")
    retired = _java_method(text, "private static void rejectRetired(")
    for required in (
        'active(environment, "REDIS_URL", "redis.url")',
        'reject("REDIS_URL is not an active V1 configuration")',
        'active(environment, "S3_REGION", "s3.region")',
        'reject("S3_REGION is not an active V1 configuration")',
    ):
        if retired is None or required not in retired:
            errors.append(f"rejectRetired missing {required}")
    reject = _java_method(text, "private static void reject(")
    if reject is None or re.search(
        r"\bthrow\s+new\s+IllegalStateException\s*\(\s*message\s*\)\s*;", reject
    ) is None:
        errors.append("reject must throw IllegalStateException(message)")
    return errors


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
        validator = (REPO / VALIDATOR).read_text(encoding="utf-8")
        self.assertEqual(_retired_validator_errors(validator), [])
        self.assertNotEqual(
            _retired_validator_errors(
                validator.replace("rejectRetired(environment);", "", 1)
            ),
            [],
            "oracle must fail when retired rejection is removed",
        )
        self.assertNotEqual(
            _retired_validator_errors(
                validator.replace(
                    "throw new IllegalStateException(message);", "", 1
                )
            ),
            [],
            "oracle must fail when reject is neutered",
        )
        self.assertNotEqual(
            _retired_validator_errors(
                validator.replace(
                    "rejectRetired(environment);",
                    "try { rejectRetired(environment); } catch (IllegalStateException ignored) { }",
                    1,
                )
            ),
            [],
            "oracle must fail when rejectRetired failure is swallowed",
        )
        hits: list[str] = []
        for rel in SCAN:
            for path in _iter_files(REPO / rel):
                try:
                    text = path.read_text(encoding="utf-8")
                except UnicodeDecodeError:
                    continue
                rel_posix = path.relative_to(REPO).as_posix()
                if rel_posix.endswith("VaultEnvironmentValidator.java"):
                    text = "\n".join(
                        line
                        for line in text.splitlines()
                        if line.strip() not in RETIRED_VALIDATOR_LINES
                    )
                if TOKEN.search(text):
                    hits.append(rel_posix)
        self.assertEqual(
            hits,
            [],
            f"forbidden {FORBIDDEN} in active config: {hits}",
        )


if __name__ == "__main__":
    unittest.main()
