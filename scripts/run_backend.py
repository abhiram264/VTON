#!/usr/bin/env python3
"""Launch the host-run backend from the repository-root untracked .env."""

from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

FROZEN_BUCKETS = {
    "S3_AVATAR_BUCKET": "ai-fashion-vault-local-avatar-private",
    "S3_VAULT_BUCKET": "ai-fashion-vault-local-vault-assets",
    "S3_LOOK_BUCKET": "ai-fashion-vault-local-look-private",
    "S3_TEMP_BUCKET": "ai-fashion-vault-local-generation-temp",
}
QUEUE_NAME = "ai-fashion-vault-local-generation-work"
ACCOUNT = "000000000000"
DEFAULT_PG_PORT = "5432"
DEFAULT_LS_PORT = "4566"


def load_root_env(root: Path) -> dict[str, str]:
    path = root / ".env"
    if not path.is_file():
        raise SystemExit(f"missing {path}")
    out: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        out[key.strip()] = value.strip().strip('"').strip("'")
    return out


def backend_artifact_version(root: Path) -> str:
    pom = root / "backend" / "pom.xml"
    text = pom.read_text(encoding="utf-8") if pom.is_file() else ""
    match = re.search(r"<artifactId>backend</artifactId>\s*<version>([^<]+)</version>", text)
    if match:
        return match.group(1).strip()
    match = re.search(r"<version>([^<]+)</version>", text)
    return match.group(1).strip() if match else "unknown"


def _require(parsed: dict[str, str], name: str) -> str:
    value = parsed.get(name, "").strip()
    if not value:
        raise SystemExit(f"missing {name}")
    return value


def backend_env(parsed: dict[str, str], root: Path) -> dict[str, str]:
    _require(parsed, "LOCALSTACK_AUTH_TOKEN")
    password = _require(parsed, "POSTGRES_PASSWORD")
    pg_port = parsed.get("POSTGRES_PORT", "").strip() or DEFAULT_PG_PORT
    ls_port = parsed.get("LOCALSTACK_PORT", "").strip() or DEFAULT_LS_PORT
    access = parsed.get("AWS_ACCESS_KEY_ID", "").strip() or "test"
    secret = parsed.get("AWS_SECRET_ACCESS_KEY", "").strip() or "test"
    version = backend_artifact_version(root)
    child = {
        "APP_ENV": "development",
        "DB_USERNAME": "postgres",
        "DB_PASSWORD": password,
        "DB_URL": f"jdbc:postgresql://127.0.0.1:{pg_port}/postgres",
        "AWS_REGION": "us-east-1",
        "AWS_ENDPOINT_URL_S3": f"http://127.0.0.1:{ls_port}",
        "AWS_ENDPOINT_URL_SQS": f"http://127.0.0.1:{ls_port}",
        "AWS_ACCESS_KEY_ID": access,
        "AWS_SECRET_ACCESS_KEY": secret,
        "SQS_GENERATION_QUEUE_URL": f"http://127.0.0.1:{ls_port}/{ACCOUNT}/{QUEUE_NAME}",
        "OTEL_SERVICE_NAME": "ai-fashion-vault-api",
        "OTEL_RESOURCE_ATTRIBUTES": (
            "service.namespace=ai-fashion-vault,"
            "deployment.environment.name=development,"
            f"service.version={version}"
        ),
        **FROZEN_BUCKETS,
    }
    for key in (
        "FASHN_API_KEY",
        "VTON_IMAGE_API_KEY",
        "FIREBASE_PROJECT_ID",
        "OTEL_EXPORTER_OTLP_ENDPOINT",
        "JAVA_TOOL_OPTIONS",
        "SERVER_PORT",
    ):
        if parsed.get(key, "").strip():
            child[key] = parsed[key].strip()
    return child


def backend_command(root: Path) -> list[str]:
    if os.name == "nt":
        return ["cmd.exe", "/d", "/c", "mvnw.cmd", "-f", "pom.xml", "spring-boot:run"]
    mvnw = root / "backend" / "mvnw"
    return [str(mvnw), "-f", str(root / "backend" / "pom.xml"), "spring-boot:run"]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Launch host-run backend from root .env")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)
    root = args.root.resolve()
    parsed = load_root_env(root)
    child = backend_env(parsed, root)
    cmd = backend_command(root)
    if args.dry_run:
        print(" ".join(cmd))
        print(" ".join(sorted(k for k in child if k != "DB_PASSWORD")))
        return 0
    env = os.environ.copy()
    env.update(child)
    env.pop("LOCALSTACK_AUTH_TOKEN", None)
    if os.name == "nt":
        # Keep the caller-controlled root out of cmd.exe's command string; cwd
        # is passed through the native process API and cannot be reparsed.
        return subprocess.call(cmd, env=env, cwd=root / "backend")
    os.execvpe(cmd[0], cmd, env)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
