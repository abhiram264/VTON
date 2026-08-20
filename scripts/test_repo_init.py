#!/usr/bin/env python3
"""Black-box idempotency fixtures for scripts/repo_init.py."""

from __future__ import annotations

import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT_HOME = Path(__file__).resolve().parent.parent
REPO_INIT = Path(__file__).resolve().parent / "repo_init.py"

REQUIRED = (
    "README.md",
    ".gitignore",
    ".gitattributes",
    ".editorconfig",
    "docs/PRD.md",
    "docs/ARCHITECTURE.md",
    "docs/DATA_MODEL.md",
    "docs/API_CONTRACTS.md",
    "docs/PRIVACY.md",
    "docs/AI_EVALUATION.md",
    "docs/ADR/README.md",
    ".github/workflows/repo-sanity.yml",
)

GREENFIELD_FORBIDDEN = (
    "mobile/package.json",
    "mobile/android",
    "mobile/ios",
    "mobile/src",
    "backend/pom.xml",
    "backend/src",
    "compose.yaml",
    "docker-compose.yml",
)


def _run_init(root: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(REPO_INIT), "--root", str(root)],
        capture_output=True,
        text=True,
        cwd=str(SCRIPT_HOME),
    )


def _state_lines(stdout: str) -> list[str]:
    return [line[7:] for line in stdout.splitlines() if line.startswith("STATE: ")]


def _collision_paths(stdout: str) -> list[str]:
    return [line[11:] for line in stdout.splitlines() if line.startswith("COLLISION: ")]


def _snapshot(root: Path) -> dict[str, bytes]:
    out: dict[str, bytes] = {}
    for rel in REQUIRED:
        path = root / rel
        if path.is_file():
            out[rel] = path.read_bytes()
    return out


def _git(root: Path, *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", *args],
        cwd=str(root),
        capture_output=True,
        text=True,
    )


class IsolatedTempRoot(unittest.TestCase):
    def setUp(self) -> None:
        self._td = tempfile.TemporaryDirectory()
        self.root = Path(self._td.name).resolve()
        live = SCRIPT_HOME.resolve()
        if live == self.root or live in self.root.parents:
            self._td.cleanup()
            self.fail("temp root must not sit inside the live working tree")

    def tearDown(self) -> None:
        try:
            self._td.cleanup()
        except OSError:
            pass


class TestRepoInitIdempotency(IsolatedTempRoot):
    def test_new_creates_phase_zero_baseline_only(self) -> None:
        proc = _run_init(self.root)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(_state_lines(proc.stdout), ["NEW"])
        for rel in REQUIRED:
            self.assertTrue((self.root / rel).is_file(), rel)
        for rel in GREENFIELD_FORBIDDEN:
            self.assertFalse((self.root / rel).exists(), rel)
        self.assertFalse((self.root / "LICENSE").exists())
        self.assertFalse((self.root / "package.json").exists())
        self.assertFalse((self.root / "mobile").exists())
        self.assertFalse((self.root / "backend").exists())
        self.assertFalse((self.root / "infra").exists())
        self.assertFalse(list(self.root.glob("docs/ADR/ADR-*.md")))
        prd = (self.root / "docs" / "PRD.md").read_text(encoding="utf-8")
        self.assertIn("Executive Summary", prd)
        self.assertTrue((self.root / ".git").exists())
        self.assertNotEqual(_git(self.root, "rev-parse", "--verify", "HEAD").returncode, 0)

    def test_initialized_rerun_is_noop(self) -> None:
        first = _run_init(self.root)
        self.assertEqual(first.returncode, 0, first.stderr)
        before = _snapshot(self.root)
        second = _run_init(self.root)
        self.assertEqual(second.returncode, 0, second.stderr)
        self.assertEqual(_state_lines(second.stdout), ["INITIALIZED"])
        self.assertEqual(_snapshot(self.root), before)

    def test_partial_compatible_restores_only_missing_file(self) -> None:
        first = _run_init(self.root)
        self.assertEqual(first.returncode, 0, first.stderr)
        target = self.root / "docs" / "ARCHITECTURE.md"
        target.unlink()
        before = _snapshot(self.root)
        second = _run_init(self.root)
        self.assertEqual(second.returncode, 0, second.stderr)
        self.assertEqual(_state_lines(second.stdout), ["PARTIAL_COMPATIBLE"])
        self.assertTrue(target.is_file())
        after = _snapshot(self.root)
        for rel, data in before.items():
            self.assertEqual(after[rel], data, rel)
        self.assertIn("docs/ARCHITECTURE.md", after)

    def test_incompatible_edit_is_collision(self) -> None:
        first = _run_init(self.root)
        self.assertEqual(first.returncode, 0, first.stderr)
        readme = self.root / "README.md"
        original = readme.read_bytes()
        readme.write_bytes(original + b"\n# local fork\n")
        second = _run_init(self.root)
        self.assertEqual(second.returncode, 2, second.stderr)
        self.assertEqual(_state_lines(second.stdout), ["COLLISION"])
        self.assertIn("README.md", _collision_paths(second.stdout))
        self.assertEqual(readme.read_bytes(), original + b"\n# local fork\n")

    def test_advanced_preserves_later_scaffolds(self) -> None:
        first = _run_init(self.root)
        self.assertEqual(first.returncode, 0, first.stderr)
        mobile = self.root / "mobile" / "package.json"
        mobile.parent.mkdir()
        mobile.write_text('{"name":"mobile"}\n', encoding="utf-8")
        pom = self.root / "backend" / "pom.xml"
        pom.parent.mkdir()
        pom.write_text("<project/>\n", encoding="utf-8")
        compose = self.root / "compose.yaml"
        compose.write_text("services: {}\n", encoding="utf-8")
        second = _run_init(self.root)
        self.assertEqual(second.returncode, 0, second.stderr)
        self.assertEqual(_state_lines(second.stdout), ["ADVANCED"])
        self.assertEqual(mobile.read_text(encoding="utf-8"), '{"name":"mobile"}\n')
        self.assertEqual(pom.read_text(encoding="utf-8"), "<project/>\n")
        self.assertEqual(compose.read_text(encoding="utf-8"), "services: {}\n")

    def test_existing_git_root_and_nested_wrong_root(self) -> None:
        init = _git(self.root, "init", "-b", "master")
        if init.returncode != 0:
            self.assertEqual(_git(self.root, "init").returncode, 0, init.stderr)
            self.assertEqual(
                _git(self.root, "symbolic-ref", "HEAD", "refs/heads/master").returncode,
                0,
            )
        remote = "https://example.invalid/ai-fashion-vault.git"
        self.assertEqual(_git(self.root, "remote", "add", "origin", remote).returncode, 0)
        nested = self.root / "nested"
        nested.mkdir()
        marker = nested / "keep-me.txt"
        marker.write_text("untouched\n", encoding="utf-8")

        wrong = _run_init(nested)
        self.assertEqual(wrong.returncode, 3, wrong.stderr)
        self.assertEqual(_state_lines(wrong.stdout), ["WRONG_ROOT / NESTED_REPO_RISK"])
        self.assertFalse((nested / ".git").exists())
        self.assertEqual(marker.read_text(encoding="utf-8"), "untouched\n")
        for rel in REQUIRED:
            self.assertFalse((nested / rel).exists(), rel)

        ok = _run_init(self.root)
        self.assertEqual(ok.returncode, 0, ok.stderr)
        self.assertEqual(_state_lines(ok.stdout), ["NEW"])
        self.assertTrue((self.root / ".git").exists())
        self.assertFalse((nested / ".git").exists())
        remotes = _git(self.root, "remote", "get-url", "origin")
        self.assertEqual(remotes.returncode, 0, remotes.stderr)
        self.assertEqual(remotes.stdout.strip(), remote)
        self.assertNotEqual(_git(self.root, "rev-parse", "--verify", "HEAD").returncode, 0)
        branch = _git(self.root, "symbolic-ref", "--short", "HEAD")
        self.assertEqual(branch.stdout.strip(), "main")

    def test_existing_prd_collision_is_not_overwritten(self) -> None:
        prd = self.root / "docs" / "PRD.md"
        prd.parent.mkdir()
        original = b"# leftover product notes\n"
        prd.write_bytes(original)
        proc = _run_init(self.root)
        self.assertEqual(proc.returncode, 2, proc.stderr)
        self.assertEqual(_state_lines(proc.stdout), ["COLLISION"])
        self.assertIn("docs/PRD.md", _collision_paths(proc.stdout))
        self.assertEqual(prd.read_bytes(), original)
        self.assertFalse((self.root / "README.md").exists())


if __name__ == "__main__":
    unittest.main()
