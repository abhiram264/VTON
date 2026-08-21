#!/usr/bin/env python3
"""Black-box fixtures for scripts/secret_content_scan.py. Does not embed secret literals."""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


REPO = Path(__file__).resolve().parent.parent
SCANNER = Path(__file__).resolve().parent / "secret_content_scan.py"

PEM_BODY = "MIIEfakeSecretContentScanFixtureValueNotForCommit"
SA_BODY = "sa-fixture-secret-value-not-for-commit"


def _pem() -> str:
    return "-----BEGIN " + "PRIVATE KEY-----\n" + PEM_BODY + "\n-----END " + "PRIVATE KEY-----\n"


def _sa_json() -> str:
    payload = {
        "type": "service_" + "account",
        "private_" + "key": SA_BODY,
        "client_email": "ci@example.iam.gserviceaccount.com",
    }
    return json.dumps(payload)


def _scan(*args: str, cwd: Path | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(SCANNER), *args],
        capture_output=True,
        text=True,
        cwd=str(cwd or REPO),
    )


class SecretContentScanTests(unittest.TestCase):
    def test_clean_repo_succeeds(self) -> None:
        proc = _scan()
        self.assertEqual(proc.returncode, 0, proc.stderr + proc.stdout)

    def test_private_key_fixture_fails_without_leaking(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "notes.txt").write_text(_pem(), encoding="utf-8")
            proc = _scan("--root", str(root))
        combined = proc.stdout + proc.stderr
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("notes.txt", combined)
        self.assertIn("private-key", combined)
        self.assertNotIn(PEM_BODY, combined)
        self.assertNotIn(_pem().strip(), combined)

    def test_service_account_fixture_fails_without_leaking(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "config.json").write_text(_sa_json(), encoding="utf-8")
            proc = _scan("--root", str(root))
        combined = proc.stdout + proc.stderr
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("config.json", combined)
        self.assertIn("google-service-account", combined)
        self.assertNotIn(SA_BODY, combined)

    def test_detection_is_content_based_not_filename_based(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "id_rsa").write_text("not a secret\n", encoding="utf-8")
            proc = _scan("--root", str(root))
        self.assertEqual(proc.returncode, 0, proc.stderr + proc.stdout)


if __name__ == "__main__":
    unittest.main()
