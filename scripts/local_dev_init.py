#!/usr/bin/env python3
"""Non-destructive local development environment initializer."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

STATE_ABSENT = "ABSENT"
STATE_PARTIAL = "PARTIAL_COMPATIBLE"
STATE_INITIALIZED = "INITIALIZED"
STATE_LEGACY = "LEGACY_FILENAME"
STATE_AMBIGUOUS = "AMBIGUOUS_COMPOSE"
STATE_COLLISION = "COLLISION"

EXIT_OK = 0
EXIT_BLOCK = 2

SCRIPT_HOME = Path(__file__).resolve().parent.parent

RECOGNIZED_COMPOSE = (
    "compose.yaml",
    "compose.yml",
    "docker-compose.yaml",
    "docker-compose.yml",
)
CANONICAL_COMPOSE = "compose.yaml"
OWNED = (
    "compose.yaml",
    ".env.example",
    "infra/docker/localstack_ready.py",
)
ALLOWED_SERVICES = frozenset({"postgres", "localstack"})
ENV_NAMES = frozenset(
    {
        "LOCALSTACK_AUTH_TOKEN",
        "POSTGRES_PASSWORD",
        "POSTGRES_PORT",
        "LOCALSTACK_PORT",
        "APP_ENV",
        "AWS_REGION",
        "AWS_ACCESS_KEY_ID",
        "AWS_SECRET_ACCESS_KEY",
        "FASHN_API_KEY",
        "FIREBASE_PROJECT_ID",
        "OTEL_SERVICE_NAME",
        "S3_AVATAR_BUCKET",
        "S3_VAULT_BUCKET",
        "S3_LOOK_BUCKET",
        "S3_TEMP_BUCKET",
    }
)
READY_NAMES = (
    "ai-fashion-vault-local-avatar-private",
    "ai-fashion-vault-local-vault-assets",
    "ai-fashion-vault-local-look-private",
    "ai-fashion-vault-local-generation-temp",
    "ai-fashion-vault-local-generation-work",
    "ai-fashion-vault-local-generation-work-dlq",
)
READY_FORBIDDEN = ("fifo",)
POSTGRES_MAJOR_18 = re.compile(r"^18([.-].*)?$")


def _unquote(value: str) -> str:
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
        return value[1:-1]
    return value


def _strip_inline_comment(content: str) -> str:
    in_single = False
    in_double = False
    for i, ch in enumerate(content):
        if ch == "'" and not in_double:
            in_single = not in_single
        elif ch == '"' and not in_single:
            in_double = not in_double
        elif ch == "#" and not in_single and not in_double:
            return content[:i].rstrip()
    return content


def _iter_lines(text: str):
    for raw in text.splitlines():
        line = raw.replace("\t", "  ")
        stripped = line.lstrip(" ")
        if not stripped or stripped.startswith("#"):
            continue
        indent = len(line) - len(stripped)
        stripped = _strip_inline_comment(stripped)
        if not stripped:
            continue
        yield indent, stripped


def _image_name_tag(image: str) -> tuple[str, str]:
    image = image.strip()
    if "@" in image:
        image = image.split("@", 1)[0]
    if ":" in image:
        name, tag = image.rsplit(":", 1)
        if "/" not in tag:
            return name, tag
    return image, ""


def _postgres_major_18(image: str) -> bool:
    name, tag = _image_name_tag(image)
    return name.split("/")[-1] == "postgres" and bool(POSTGRES_MAJOR_18.match(tag))


def _localstack_pro(image: str) -> bool:
    name, _tag = _image_name_tag(image)
    return name == "localstack/localstack-pro" or name.endswith(
        "/localstack/localstack-pro"
    )


def _scan_services(text: str) -> dict[str, dict] | None:
    in_services = False
    saw_services = False
    services_indent = 0
    service_indent: int | None = None
    body_indent: int | None = None
    current: str | None = None
    section: str | None = None
    services: dict[str, dict] = {}

    for indent, content in _iter_lines(text):
        if in_services and indent <= services_indent:
            in_services = False
            current = None
            section = None
            service_indent = None
            body_indent = None

        if not in_services:
            if content == "services:" or content.startswith("services:"):
                in_services = True
                saw_services = True
                services_indent = indent
            continue

        if service_indent is None:
            if content.startswith("-") or ":" not in content:
                continue
            key = content.split(":", 1)[0].strip()
            service_indent = indent
            current = key
            services[current] = {"image": None, "ports": []}
            body_indent = None
            section = None
            continue

        if indent == service_indent:
            if content.startswith("-") or ":" not in content:
                continue
            key = content.split(":", 1)[0].strip()
            current = key
            services[current] = {"image": None, "ports": []}
            body_indent = None
            section = None
            continue

        if current is None:
            continue

        if content.startswith("- "):
            item = content[2:].strip()
            if section == "ports":
                services[current]["ports"].append(item)
            continue

        if ":" not in content:
            continue

        key, _, rest = content.partition(":")
        key = key.strip()
        rest = rest.strip()
        if body_indent is None:
            body_indent = indent
        if indent == body_indent:
            section = key
            if key == "image" and rest:
                services[current]["image"] = _unquote(rest)

    if not saw_services:
        return None
    return services


def _compose_collides(text: str) -> bool:
    if "docker.sock" in text:
        return True
    services = _scan_services(text)
    if services is None:
        return True
    if set(services) != ALLOWED_SERVICES:
        return True
    postgres = services.get("postgres")
    if postgres is not None:
        image = postgres.get("image")
        if not image or not _postgres_major_18(str(image)):
            return True
        for item in postgres.get("ports") or []:
            if not _unquote(str(item)).startswith("127.0.0.1"):
                return True
    localstack = services.get("localstack")
    if localstack is not None:
        image = localstack.get("image")
        if not image or not _localstack_pro(str(image)):
            return True
        for item in localstack.get("ports") or []:
            if not _unquote(str(item)).startswith("127.0.0.1"):
                return True
    return False


def _env_collides(text: str) -> bool:
    names: list[str] = []
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if "=" not in stripped:
            return True
        names.append(stripped.split("=", 1)[0].strip())
    return set(names) != ENV_NAMES


def _hook_collides(text: str) -> bool:
    if any(name not in text for name in READY_NAMES):
        return True
    lowered = text.lower()
    return any(token in lowered for token in READY_FORBIDDEN)


def _found_compose(root: Path) -> list[str]:
    return [name for name in RECOGNIZED_COMPOSE if (root / name).is_file()]


def _file_collides(rel: str, path: Path) -> bool:
    if not path.is_file():
        return True
    try:
        text = path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError):
        return True
    if rel == "compose.yaml":
        return _compose_collides(text)
    if rel == ".env.example":
        return _env_collides(text)
    return _hook_collides(text)


def _classify(root: Path) -> tuple[str, list[str]]:
    found = _found_compose(root)
    if len(found) >= 2:
        return STATE_AMBIGUOUS, [f"COMPOSE: {name}" for name in found]
    if len(found) == 1 and found[0] != CANONICAL_COMPOSE:
        return STATE_LEGACY, [f"COMPOSE: {found[0]}"]

    collisions: list[str] = []
    present: list[str] = []
    for rel in OWNED:
        path = root / rel
        if not path.exists():
            continue
        present.append(rel)
        if _file_collides(rel, path):
            collisions.append(rel)
    if collisions:
        return STATE_COLLISION, [f"COLLISION: {rel}" for rel in collisions]
    if len(present) == len(OWNED):
        return STATE_INITIALIZED, []
    if present:
        return STATE_PARTIAL, []
    return STATE_ABSENT, []


def _copy_owned(root: Path, rel: str) -> None:
    src = SCRIPT_HOME / rel
    if not src.is_file():
        raise FileNotFoundError(f"Required template missing: {src}")
    dest = root / rel
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(src.read_bytes())


def _write_missing(root: Path) -> None:
    for rel in OWNED:
        if not (root / rel).exists():
            _copy_owned(root, rel)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Initialize the local development environment."
    )
    parser.add_argument("--root", default=".", help="Target repository root (default: cwd)")
    args = parser.parse_args(argv)
    root = Path(args.root).expanduser()
    if not root.is_absolute():
        root = Path.cwd() / root
    root = root.resolve()
    if not root.exists():
        root.mkdir(parents=True)

    state, extra = _classify(root)
    if state in {STATE_LEGACY, STATE_AMBIGUOUS, STATE_COLLISION}:
        print(f"STATE: {state}")
        for line in extra:
            print(line)
        return EXIT_BLOCK

    try:
        if state == STATE_ABSENT:
            _write_missing(root)
        elif state == STATE_PARTIAL:
            _write_missing(root)
    except FileNotFoundError as exc:
        print(str(exc), file=sys.stderr)
        return 1

    print(f"STATE: {state}")
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main())
