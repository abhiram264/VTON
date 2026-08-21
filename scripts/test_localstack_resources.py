#!/usr/bin/env python3
"""Runtime proof of the local LocalStack S3/SQS contract. Does not print secrets."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
COMPOSE_FILE = ROOT / "compose.yaml"

BUCKETS = (
    "ai-fashion-vault-local-avatar-private",
    "ai-fashion-vault-local-vault-assets",
    "ai-fashion-vault-local-look-private",
    "ai-fashion-vault-local-generation-temp",
)
TEMP_BUCKET = "ai-fashion-vault-local-generation-temp"
QUEUE_NAME = "ai-fashion-vault-local-generation-work"
DLQ_NAME = "ai-fashion-vault-local-generation-work-dlq"

READY_WAIT_S = 120
POLL_S = 2


def _fail(msg: str, code: int = 1) -> None:
    print(msg, file=sys.stderr)
    raise SystemExit(code)


def _require_env() -> None:
    for name in ("LOCALSTACK_AUTH_TOKEN", "POSTGRES_PASSWORD"):
        if not os.environ.get(name):
            _fail(name)


def _port() -> int:
    raw = os.environ.get("LOCALSTACK_PORT", "4566")
    try:
        return int(raw)
    except ValueError:
        _fail("LOCALSTACK_PORT is invalid")


def _endpoint(port: int) -> str:
    return f"http://127.0.0.1:{port}"


def _compose(*args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["docker", "compose", "-f", str(COMPOSE_FILE), *args],
        cwd=str(ROOT),
        capture_output=True,
        text=True,
        check=check,
    )


def _compose_running() -> bool:
    proc = _compose("ps", "--status", "running", "--format", "json", check=False)
    if proc.returncode != 0:
        return False
    text = proc.stdout.strip()
    if not text:
        return False
    try:
        payload = json.loads(text)
    except json.JSONDecodeError:
        return bool(text)
    if isinstance(payload, list):
        names = {item.get("Service") or item.get("Name") for item in payload}
        return "localstack" in names or any(
            "localstack" in str(n) for n in names if n
        )
    return "localstack" in text


def _ready(endpoint: str) -> bool:
    url = f"{endpoint}/_localstack/init/ready"
    try:
        with urllib.request.urlopen(url, timeout=3) as resp:
            data = json.load(resp)
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, OSError):
        return False
    if data.get("completed") is not True:
        return False
    scripts = data.get("scripts") or []
    return not any(s.get("state") == "ERROR" for s in scripts)


def _wait_ready(endpoint: str) -> None:
    deadline = time.monotonic() + READY_WAIT_S
    while time.monotonic() < deadline:
        if _ready(endpoint):
            return
        time.sleep(POLL_S)
    _fail("LocalStack ready signal not observed")


def _awslocal(*args: str) -> str:
    proc = _compose("exec", "-T", "localstack", "awslocal", *args, check=False)
    if proc.returncode != 0:
        err = (proc.stderr or proc.stdout or "awslocal failed").strip()
        _fail(err.splitlines()[-1] if err else "awslocal failed")
    return proc.stdout


def _awslocal_json(*args: str) -> dict:
    text = _awslocal(*args).strip()
    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        _fail("awslocal returned non-JSON")
    if not isinstance(data, dict):
        _fail("awslocal JSON must be an object")
    return data


def _assert_eq(actual, expected, label: str) -> None:
    if str(actual) != str(expected):
        _fail(f"{label}: expected {expected}, got {actual}")


def _verify() -> None:
    listing = _awslocal("s3", "ls")
    missing = [name for name in BUCKETS if name not in listing]
    if missing:
        _fail(f"missing buckets: {', '.join(missing)}")

    life = _awslocal_json(
        "s3api", "get-bucket-lifecycle-configuration", "--bucket", TEMP_BUCKET
    )
    rules = life.get("Rules") or []
    if not rules:
        _fail("temp bucket lifecycle missing")
    enabled = [r for r in rules if r.get("Status") == "Enabled"]
    if not enabled:
        _fail("temp bucket lifecycle not enabled")
    if not any(
        r.get("Expiration", {}).get("Days") == 1
        and r.get("AbortIncompleteMultipartUpload", {}).get("DaysAfterInitiation")
        == 1
        for r in enabled
    ):
        _fail("temp bucket lifecycle must expire in 1 day and abort multipart in 1 day")

    source_url = _awslocal_json("sqs", "get-queue-url", "--queue-name", QUEUE_NAME)[
        "QueueUrl"
    ]
    dlq_url = _awslocal_json("sqs", "get-queue-url", "--queue-name", DLQ_NAME)[
        "QueueUrl"
    ]
    wanted = [
        "FifoQueue",
        "VisibilityTimeout",
        "ReceiveMessageWaitTimeSeconds",
        "MessageRetentionPeriod",
        "RedrivePolicy",
        "QueueArn",
    ]
    source = _awslocal_json(
        "sqs",
        "get-queue-attributes",
        "--queue-url",
        source_url,
        "--attribute-names",
        *wanted,
    ).get("Attributes") or {}
    dlq = _awslocal_json(
        "sqs",
        "get-queue-attributes",
        "--queue-url",
        dlq_url,
        "--attribute-names",
        *wanted,
    ).get("Attributes") or {}
    if source.get("FifoQueue", "false").lower() == "true":
        _fail("generation queue must be Standard")
    if dlq.get("FifoQueue", "false").lower() == "true":
        _fail("DLQ must be Standard")
    _assert_eq(source.get("VisibilityTimeout"), "120", "source VisibilityTimeout")
    _assert_eq(
        source.get("ReceiveMessageWaitTimeSeconds"),
        "20",
        "source ReceiveMessageWaitTimeSeconds",
    )
    _assert_eq(
        dlq.get("ReceiveMessageWaitTimeSeconds"),
        "20",
        "DLQ ReceiveMessageWaitTimeSeconds",
    )
    _assert_eq(
        source.get("MessageRetentionPeriod"), "345600", "source MessageRetentionPeriod"
    )
    _assert_eq(dlq.get("MessageRetentionPeriod"), "1209600", "DLQ MessageRetentionPeriod")
    policy_raw = source.get("RedrivePolicy")
    if not policy_raw:
        _fail("source RedrivePolicy missing")
    policy = json.loads(policy_raw)
    _assert_eq(policy.get("maxReceiveCount"), "5", "maxReceiveCount")
    if policy.get("deadLetterTargetArn") != dlq.get("QueueArn"):
        _fail("source redrive target is not the DLQ")


def main() -> None:
    _require_env()
    if not COMPOSE_FILE.is_file():
        _fail("compose.yaml is required")
    port = _port()
    endpoint = _endpoint(port)
    owned = False
    if not _ready(endpoint):
        if not _compose_running():
            up = _compose("up", "-d", check=False)
            if up.returncode != 0:
                err = (up.stderr or up.stdout or "docker compose up failed").strip()
                _fail(err.splitlines()[-1] if err else "docker compose up failed")
            owned = True
        try:
            _wait_ready(endpoint)
            _verify()
        finally:
            if owned:
                _compose("down", check=False)
    else:
        _verify()


if __name__ == "__main__":
    main()
