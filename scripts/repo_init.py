#!/usr/bin/env python3
"""Non-destructive Phase-zero repository initializer."""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path


STATE_NEW = "NEW"
STATE_PARTIAL = "PARTIAL_COMPATIBLE"
STATE_INITIALIZED = "INITIALIZED"
STATE_COLLISION = "COLLISION"
STATE_ADVANCED = "ADVANCED"
STATE_WRONG_ROOT = "WRONG_ROOT / NESTED_REPO_RISK"

EXIT_OK = 0
EXIT_COLLISION = 2
EXIT_WRONG_ROOT = 3

OWNED = (
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

SCRIPT_HOME = Path(__file__).resolve().parent.parent


def _normalize(data: bytes) -> bytes:
    return data.replace(b"\r\n", b"\n")


def _git(root: Path, *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", *args],
        cwd=str(root),
        capture_output=True,
        text=True,
    )


def _git_toplevel(root: Path) -> Path | None:
    if not root.is_dir():
        return None
    proc = _git(root, "rev-parse", "--show-toplevel")
    if proc.returncode != 0:
        return None
    text = proc.stdout.strip()
    if not text:
        return None
    return Path(text)


def _same_dir(left: Path, right: Path) -> bool:
    return left.resolve() == right.resolve()


def _prd_template(root: Path) -> Path:
    candidates = (
        SCRIPT_HOME / "docs" / "VTON-PRD.md",
        SCRIPT_HOME / "docs" / "PRD.md",
        root / "docs" / "VTON-PRD.md",
    )
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    looked = ", ".join(str(path) for path in candidates)
    raise FileNotFoundError(f"PRD template not found. Looked for: {looked}")


def _template_for(rel: str, root: Path) -> Path:
    if rel == "docs/PRD.md":
        return _prd_template(root)
    src = SCRIPT_HOME / rel
    if not src.is_file():
        raise FileNotFoundError(f"Required template missing: {src}")
    return src


def _has_later_scaffolds(root: Path) -> bool:
    markers = (
        root / "mobile" / "package.json",
        root / "mobile" / "android",
        root / "mobile" / "ios",
        root / "mobile" / "src",
        root / "backend" / "pom.xml",
        root / "backend" / "src",
        root / "compose.yaml",
        root / "docker-compose.yml",
    )
    if any(path.exists() for path in markers):
        return True
    infra = root / "infra"
    if infra.is_dir():
        for path in infra.rglob("*"):
            if path.is_file() and path.name != ".gitkeep":
                return True
    return False


def _has_symlink_component(root: Path, rel: str) -> bool:
    current = root
    for part in rel.split("/"):
        current /= part
        if current.is_symlink():
            return True
    return False


def _inventory(root: Path) -> tuple[list[str], list[str]]:
    missing: list[str] = []
    collisions: list[str] = []
    for rel in OWNED:
        if _has_symlink_component(root, rel):
            collisions.append(rel)
            continue
        dest = root / rel
        src = _template_for(rel, root)
        if not dest.exists():
            missing.append(rel)
            continue
        if not dest.is_file():
            collisions.append(rel)
            continue
        if _normalize(dest.read_bytes()) != _normalize(src.read_bytes()):
            collisions.append(rel)
    return missing, collisions


def _rename_unborn_master(root: Path) -> None:
    head = _git(root, "symbolic-ref", "--short", "HEAD")
    if head.returncode != 0 or head.stdout.strip() != "master":
        return
    if _git(root, "rev-parse", "--verify", "HEAD").returncode == 0:
        return
    _git(root, "symbolic-ref", "HEAD", "refs/heads/main")


def _ensure_git(root: Path) -> None:
    top = _git_toplevel(root)
    if top is not None:
        _rename_unborn_master(root)
        return
    proc = _git(root, "init", "-b", "main")
    if proc.returncode != 0:
        fallback = _git(root, "init")
        if fallback.returncode != 0:
            raise RuntimeError(fallback.stderr.strip() or "git init failed")
        _git(root, "symbolic-ref", "HEAD", "refs/heads/main")


def _write_missing(root: Path, missing: list[str]) -> None:
    for rel in missing:
        dest = root / rel
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(_template_for(rel, root).read_bytes())


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Initialize the Phase-zero repository baseline.")
    parser.add_argument("--root", default=".", help="Target repository root (default: cwd)")
    args = parser.parse_args(argv)
    root = Path(args.root).expanduser()
    if not root.is_absolute():
        root = Path.cwd() / root
    root = root.resolve()

    if not root.exists():
        root.mkdir(parents=True)

    top = _git_toplevel(root)
    if top is not None and not _same_dir(top, root):
        print(f"STATE: {STATE_WRONG_ROOT}")
        return EXIT_WRONG_ROOT

    try:
        missing, collisions = _inventory(root)
    except FileNotFoundError as exc:
        print(str(exc), file=sys.stderr)
        return 1

    if collisions:
        print(f"STATE: {STATE_COLLISION}")
        for rel in collisions:
            print(f"COLLISION: {rel}")
        return EXIT_COLLISION

    _ensure_git(root)
    _write_missing(root, missing)

    if _has_later_scaffolds(root):
        state = STATE_ADVANCED
    elif len(missing) == len(OWNED):
        state = STATE_NEW
    elif missing:
        state = STATE_PARTIAL
    else:
        state = STATE_INITIALIZED

    print(f"STATE: {state}")
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main())
