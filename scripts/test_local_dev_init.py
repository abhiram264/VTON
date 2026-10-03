#!/usr/bin/env python3
"""Black-box fixtures for scripts/local_dev_init.py."""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT_HOME = Path(__file__).resolve().parent.parent
LOCAL_DEV_INIT = Path(__file__).resolve().parent / "local_dev_init.py"

OWNED = (
    "compose.yaml",
    ".env.example",
    "infra/docker/localstack_ready.py",
)

BUCKETS = (
    "ai-fashion-vault-local-avatar-private",
    "ai-fashion-vault-local-vault-assets",
    "ai-fashion-vault-local-look-private",
    "ai-fashion-vault-local-generation-temp",
)
QUEUE = "ai-fashion-vault-local-generation-work"
DLQ = "ai-fashion-vault-local-generation-work-dlq"

SECRETS = {
    "LOCALSTACK_AUTH_TOKEN": "ci-placeholder",
    "POSTGRES_PASSWORD": "ci-placeholder",
}

COMPAT_COMPOSE = """services:
  postgres:
    image: postgres:18
    ports:
      - "127.0.0.1:5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql
  localstack:
    image: localstack/localstack-pro:2026.07.2
    ports:
      - "127.0.0.1:4566:4566"
    volumes:
      - localstack_data:/var/lib/localstack
      - ./infra/docker/localstack_ready.py:/etc/localstack/init/ready.d/localstack_ready.py:ro
"""

COMPAT_ENV = """LOCALSTACK_AUTH_TOKEN=
POSTGRES_PASSWORD=
POSTGRES_PORT=5432
LOCALSTACK_PORT=4566
APP_ENV=development
AWS_REGION=us-east-1
AWS_ACCESS_KEY_ID=test
AWS_SECRET_ACCESS_KEY=test
FASHN_API_KEY=
VTON_IMAGE_API_KEY=
FIREBASE_PROJECT_ID=
OTEL_SERVICE_NAME=ai-fashion-vault-api
S3_AVATAR_BUCKET=ai-fashion-vault-local-avatar-private
S3_VAULT_BUCKET=ai-fashion-vault-local-vault-assets
S3_LOOK_BUCKET=ai-fashion-vault-local-look-private
S3_TEMP_BUCKET=ai-fashion-vault-local-generation-temp
"""

COMPAT_HOOK = """# custom compatible hook
ai-fashion-vault-local-avatar-private
ai-fashion-vault-local-vault-assets
ai-fashion-vault-local-look-private
ai-fashion-vault-local-generation-temp
ai-fashion-vault-local-generation-work
ai-fashion-vault-local-generation-work-dlq
# VisibilityTimeout and lifecycle are required policy terms
"""

TAG_COMPOSE = """services:
  postgres:
    image: postgres:18.5
  localstack:
    image: localstack/localstack-pro:2025.01.0
"""


def _run_init(root: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(LOCAL_DEV_INIT), "--root", str(root)],
        capture_output=True,
        text=True,
        cwd=str(SCRIPT_HOME),
    )


def _state_lines(stdout: str) -> list[str]:
    return [line[7:] for line in stdout.splitlines() if line.startswith("STATE: ")]


def _collision_paths(stdout: str) -> list[str]:
    return [line[11:] for line in stdout.splitlines() if line.startswith("COLLISION: ")]


def _write(root: Path, rel: str, text: str) -> None:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(text.replace("\r\n", "\n").encode("utf-8"))


def _snapshot(root: Path) -> dict[str, bytes]:
    out: dict[str, bytes] = {}
    for path in root.rglob("*"):
        if path.is_file():
            out[path.relative_to(root).as_posix()] = path.read_bytes()
    return out


def _clean_env(extra: dict[str, str] | None = None) -> dict[str, str]:
    env = os.environ.copy()
    for key in (
        "LOCALSTACK_AUTH_TOKEN",
        "POSTGRES_PASSWORD",
        "POSTGRES_PORT",
        "LOCALSTACK_PORT",
    ):
        env.pop(key, None)
    if extra:
        env.update(extra)
    return env


def _compose_config(
    root: Path, extra: dict[str, str] | None = None
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["docker", "compose", "-f", "compose.yaml", "config", "--format", "json"],
        capture_output=True,
        text=True,
        cwd=str(root),
        env=_clean_env(extra),
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


class TestLocalDevInit(IsolatedTempRoot):
    def test_absent_creates_artifacts_and_compose_model(self) -> None:
        proc = _run_init(self.root)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(_state_lines(proc.stdout), ["ABSENT"])
        for rel in OWNED:
            dest = self.root / rel
            self.assertTrue(dest.is_file(), rel)
            self.assertEqual(dest.read_bytes(), (SCRIPT_HOME / rel).read_bytes(), rel)
        self.assertFalse((self.root / ".env").exists())
        self.assertFalse((self.root / "backend").exists())
        self.assertFalse((self.root / "mobile").exists())
        self.assertFalse((self.root / "Dockerfile").exists())
        self.assertFalse((self.root / "backend" / "Dockerfile").exists())

        hook = (self.root / "infra" / "docker" / "localstack_ready.py").read_text(
            encoding="utf-8"
        )
        for name in BUCKETS:
            self.assertIn(name, hook)
        self.assertIn(QUEUE, hook)
        self.assertIn(DLQ, hook)
        self.assertIn("ReceiveMessageWaitTimeSeconds", hook)
        self.assertIn("VisibilityTimeout", hook)
        self.assertIn("345600", hook)
        self.assertIn("1209600", hook)
        self.assertIn("RedrivePolicy", hook)
        self.assertIn("maxReceiveCount", hook)
        self.assertIn('"maxReceiveCount": "5"', hook)
        self.assertIn("put_bucket_lifecycle_configuration", hook)
        self.assertIn("AbortIncompleteMultipartUpload", hook)
        lowered = hook.lower()
        self.assertIn("dlq", lowered)
        self.assertIn("visibility", lowered)
        self.assertIn("lifecycle", lowered)
        self.assertNotIn("fifo", lowered)
        self.assertNotIn("redis_url", lowered)
        self.assertNotIn("s3_region", lowered)
        self.assertNotIn("24 hour", lowered)
        self.assertNotIn("24h", lowered)
        self.assertNotIn("delete within", lowered)

        env_example = (self.root / ".env.example").read_text(encoding="utf-8")
        self.assertEqual(
            [line.split("=", 1)[0] for line in env_example.splitlines() if "=" in line],
            [
                "LOCALSTACK_AUTH_TOKEN",
                "POSTGRES_PASSWORD",
                "POSTGRES_PORT",
                "LOCALSTACK_PORT",
                "APP_ENV",
                "AWS_REGION",
                "AWS_ACCESS_KEY_ID",
                "AWS_SECRET_ACCESS_KEY",
                "FASHN_API_KEY",
                "VTON_IMAGE_API_KEY",
                "FIREBASE_PROJECT_ID",
                "OTEL_SERVICE_NAME",
                "S3_AVATAR_BUCKET",
                "S3_VAULT_BUCKET",
                "S3_LOOK_BUCKET",
                "S3_TEMP_BUCKET",
            ],
        )
        env_norm = env_example.replace("\r\n", "\n")
        self.assertIn("LOCALSTACK_AUTH_TOKEN=\n", env_norm)
        self.assertIn("POSTGRES_PASSWORD=\n", env_norm)
        self.assertIn("POSTGRES_PORT=5432", env_example)
        self.assertIn("LOCALSTACK_PORT=4566", env_example)
        self.assertIn("APP_ENV=development", env_example)
        self.assertIn("AWS_REGION=us-east-1", env_example)
        self.assertIn("AWS_ACCESS_KEY_ID=test", env_example)
        self.assertIn("AWS_SECRET_ACCESS_KEY=test", env_example)
        self.assertIn("FASHN_API_KEY=\n", env_norm)
        self.assertIn("VTON_IMAGE_API_KEY=\n", env_norm)
        self.assertIn("FIREBASE_PROJECT_ID=\n", env_norm)
        self.assertIn("OTEL_SERVICE_NAME=ai-fashion-vault-api", env_example)
        self.assertNotIn("REDIS_URL", env_example)
        self.assertNotIn("S3_REGION", env_example)
        self.assertNotIn("DB_PASSWORD", env_example)

        missing = _compose_config(self.root)
        self.assertNotEqual(missing.returncode, 0, missing.stdout + missing.stderr)

        resolved = _compose_config(self.root, SECRETS)
        self.assertEqual(resolved.returncode, 0, resolved.stderr)
        model = json.loads(resolved.stdout)
        services = model["services"]
        self.assertEqual(set(services), {"postgres", "localstack"})
        self.assertNotIn("redis", services)
        self.assertNotIn("backend", services)
        self.assertNotIn("mobile", services)

        pg = services["postgres"]
        ls = services["localstack"]
        self.assertEqual(pg["image"], "postgres:18")
        self.assertEqual(ls["image"], "localstack/localstack-pro:2026.07.2")
        self.assertEqual(ls["environment"]["SERVICES"], "s3,sqs")
        self.assertEqual(ls["environment"]["PERSISTENCE"], "1")
        self.assertEqual(pg["environment"]["POSTGRES_PASSWORD"], "ci-placeholder")
        self.assertEqual(ls["environment"]["LOCALSTACK_AUTH_TOKEN"], "ci-placeholder")

        pg_ports = pg["ports"]
        ls_ports = ls["ports"]
        self.assertEqual(len(pg_ports), 1)
        self.assertEqual(len(ls_ports), 1)
        self.assertEqual(pg_ports[0]["host_ip"], "127.0.0.1")
        self.assertEqual(ls_ports[0]["host_ip"], "127.0.0.1")
        self.assertEqual(str(pg_ports[0]["published"]), "5432")
        self.assertEqual(str(ls_ports[0]["published"]), "4566")
        self.assertEqual(pg_ports[0]["target"], 5432)
        self.assertEqual(ls_ports[0]["target"], 4566)

        pg_vol_targets = {v["target"] for v in pg["volumes"] if v.get("type") == "volume"}
        ls_vol_targets = {v["target"] for v in ls["volumes"] if v.get("type") == "volume"}
        self.assertEqual(pg_vol_targets, {"/var/lib/postgresql"})
        self.assertEqual(ls_vol_targets, {"/var/lib/localstack"})

        pg_health = " ".join(pg["healthcheck"]["test"])
        ls_health = " ".join(ls["healthcheck"]["test"])
        self.assertIn("pg_isready", pg_health)
        self.assertIn("/_localstack/init/ready", ls_health)
        self.assertIn("completed", ls_health)
        self.assertIn("ERROR", ls_health)

        overridden = _compose_config(
            self.root,
            {
                **SECRETS,
                "POSTGRES_PORT": "15432",
                "LOCALSTACK_PORT": "14566",
            },
        )
        self.assertEqual(overridden.returncode, 0, overridden.stderr)
        over_model = json.loads(overridden.stdout)
        self.assertEqual(
            str(over_model["services"]["postgres"]["ports"][0]["published"]), "15432"
        )
        self.assertEqual(
            str(over_model["services"]["localstack"]["ports"][0]["published"]), "14566"
        )

        src = LOCAL_DEV_INIT.read_text(encoding="utf-8")
        self.assertNotIn("compose up", src)
        self.assertNotIn("compose down", src)
        self.assertNotIn("volume rm", src)
        self.assertNotIn("down -v", src)

    def test_partial_compatible_adds_only_missing_file(self) -> None:
        _write(self.root, "compose.yaml", COMPAT_COMPOSE)
        _write(self.root, ".env.example", COMPAT_ENV)
        before_compose = (self.root / "compose.yaml").read_bytes()
        before_env = (self.root / ".env.example").read_bytes()
        proc = _run_init(self.root)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(_state_lines(proc.stdout), ["PARTIAL_COMPATIBLE"])
        self.assertTrue((self.root / "infra" / "docker" / "localstack_ready.py").is_file())
        self.assertEqual((self.root / "compose.yaml").read_bytes(), before_compose)
        self.assertEqual((self.root / ".env.example").read_bytes(), before_env)
        self.assertFalse((self.root / ".env").exists())

    def test_owned_symlinks_fail_closed(self) -> None:
        outside = self.root / "outside"
        outside.mkdir()
        nested = self.root / "nested"
        nested.mkdir()
        dangling = self.root / "dangling"
        dangling.mkdir()
        try:
            (nested / "infra").symlink_to(outside, target_is_directory=True)
            (dangling / ".env.example").symlink_to(self.root / "missing-env")
        except OSError as exc:
            self.skipTest(f"symlinks unavailable: {exc}")

        nested_proc = _run_init(nested)
        self.assertEqual(nested_proc.returncode, 2, nested_proc.stderr)
        self.assertEqual(_state_lines(nested_proc.stdout), ["COLLISION"])
        self.assertIn(
            "infra/docker/localstack_ready.py", _collision_paths(nested_proc.stdout)
        )
        self.assertFalse((outside / "docker" / "localstack_ready.py").exists())

        dangling_proc = _run_init(dangling)
        self.assertEqual(dangling_proc.returncode, 2, dangling_proc.stderr)
        self.assertEqual(_state_lines(dangling_proc.stdout), ["COLLISION"])
        self.assertIn(".env.example", _collision_paths(dangling_proc.stdout))
        self.assertFalse((self.root / "missing-env").exists())

    def test_initialized_noop_preserves_compatible_image_tags(self) -> None:
        _write(self.root, "compose.yaml", TAG_COMPOSE)
        _write(self.root, ".env.example", COMPAT_ENV)
        _write(self.root, "infra/docker/localstack_ready.py", COMPAT_HOOK)
        before = _snapshot(self.root)
        proc = _run_init(self.root)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(_state_lines(proc.stdout), ["INITIALIZED"])
        self.assertEqual(_snapshot(self.root), before)
        compose = (self.root / "compose.yaml").read_text(encoding="utf-8")
        self.assertIn("postgres:18.5", compose)
        self.assertIn("localstack/localstack-pro:2025.01.0", compose)
        self.assertNotIn("postgres:18\n", compose.replace("\r\n", "\n"))
        self.assertNotIn("2026.07.2", compose)

    def test_legacy_filename_is_preserved(self) -> None:
        original = "services:\n  redis:\n    image: redis:7\n"
        _write(self.root, "docker-compose.yml", original)
        proc = _run_init(self.root)
        self.assertEqual(proc.returncode, 2, proc.stderr)
        self.assertEqual(_state_lines(proc.stdout), ["LEGACY_FILENAME"])
        self.assertIn("docker-compose.yml", proc.stdout)
        self.assertEqual(
            (self.root / "docker-compose.yml").read_text(encoding="utf-8"), original
        )
        self.assertFalse((self.root / "compose.yaml").exists())
        self.assertFalse((self.root / ".env.example").exists())
        self.assertFalse((self.root / "infra" / "docker" / "localstack_ready.py").exists())

    def test_ambiguous_compose_preserves_both(self) -> None:
        canonical = COMPAT_COMPOSE
        legacy = "services:\n  redis:\n    image: redis:7\n"
        _write(self.root, "compose.yaml", canonical)
        _write(self.root, "docker-compose.yml", legacy)
        proc = _run_init(self.root)
        self.assertEqual(proc.returncode, 2, proc.stderr)
        self.assertEqual(_state_lines(proc.stdout), ["AMBIGUOUS_COMPOSE"])
        self.assertIn("compose.yaml", proc.stdout)
        self.assertIn("docker-compose.yml", proc.stdout)
        self.assertEqual((self.root / "compose.yaml").read_text(encoding="utf-8"), canonical)
        self.assertEqual(
            (self.root / "docker-compose.yml").read_text(encoding="utf-8"), legacy
        )
        self.assertFalse((self.root / ".env.example").exists())

    def test_collisions_are_preserved(self) -> None:
        cases = {
            "redis": """services:
  postgres:
    image: postgres:18
  localstack:
    image: localstack/localstack-pro:2026.07.2
  redis:
    image: redis:7
""",
            "extra-service": """services:
  postgres:
    image: postgres:18
  localstack:
    image: localstack/localstack-pro:2026.07.2
  backend:
    image: example/backend:1
""",
            "non-loopback": """services:
  postgres:
    image: postgres:18
    ports:
      - "5432:5432"
  localstack:
    image: localstack/localstack-pro:2026.07.2
""",
            "docker.sock": """services:
  postgres:
    image: postgres:18
  localstack:
    image: localstack/localstack-pro:2026.07.2
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
""",
            "postgres:16": """services:
  postgres:
    image: postgres:16
  localstack:
    image: localstack/localstack-pro:2026.07.2
""",
            "community-localstack": """services:
   postgres:
     image: postgres:18
   localstack:
     image: localstack/localstack:3.0.0
 """,
            "postgres-only": """services:
   postgres:
     image: postgres:18
 """,
            "localstack-only": """services:
   localstack:
     image: localstack/localstack-pro:2026.07.2
 """,
            "privileged": TAG_COMPOSE.replace(
                "image: postgres:18.5", "image: postgres:18.5\n    privileged: true"
            ),
            "host-network": TAG_COMPOSE.replace(
                "image: postgres:18.5", "image: postgres:18.5\n    network_mode: host"
            ),
            "host-pid": TAG_COMPOSE.replace(
                "image: postgres:18.5", "image: postgres:18.5\n    pid: host"
            ),
            "cap-add": TAG_COMPOSE.replace(
                "image: postgres:18.5", "image: postgres:18.5\n    cap_add:\n      - SYS_ADMIN"
            ),
            "host-bind": TAG_COMPOSE.replace(
                "image: postgres:18.5",
                "image: postgres:18.5\n    volumes:\n      - /tmp/host:/container",
            ),
            "flow-ports": TAG_COMPOSE.replace(
                "image: postgres:18.5", "image: postgres:18.5\n    ports: [\"0.0.0.0:5432:5432\"]"
            ),
            "flow-bind": TAG_COMPOSE.replace(
                "image: postgres:18.5", "image: postgres:18.5\n    volumes: [\"/:/host:rw\"]"
            ),
            "merge-privileged": TAG_COMPOSE.replace(
                "image: postgres:18.5", "image: postgres:18.5\n    <<: *unsafe"
            ),
            "host-ipc": TAG_COMPOSE.replace(
                "image: postgres:18.5", "image: postgres:18.5\n    ipc: host"
            ),
            "alias-port": TAG_COMPOSE.replace(
                "image: postgres:18.5", "image: postgres:18.5\n    ports: *host"
            ),
            "windows-drive-bind": TAG_COMPOSE.replace(
                "image: postgres:18.5",
                "image: postgres:18.5\n    volumes:\n      - C:/Users/test/host:/var/lib/postgresql",
            ),
        }
        for label, body in cases.items():
            with self.subTest(label):
                for child in self.root.iterdir():
                    if child.is_file():
                        child.unlink()
                    else:
                        shutil.rmtree(child)
                _write(self.root, "compose.yaml", body)
                original = (self.root / "compose.yaml").read_bytes()
                proc = _run_init(self.root)
                self.assertEqual(proc.returncode, 2, proc.stderr + proc.stdout)
                self.assertEqual(_state_lines(proc.stdout), ["COLLISION"])
                self.assertIn("compose.yaml", _collision_paths(proc.stdout))
                self.assertEqual((self.root / "compose.yaml").read_bytes(), original)
                self.assertFalse((self.root / ".env.example").exists())
                self.assertFalse(
                    (self.root / "infra" / "docker" / "localstack_ready.py").exists()
                )

    def test_env_example_collisions_are_preserved(self) -> None:
        cases = {
            "extra-var": COMPAT_ENV + "REDIS_URL=redis://localhost\n",
            "s3-region": COMPAT_ENV + "S3_REGION=us-east-1\n",
            "missing-token": """POSTGRES_PASSWORD=
POSTGRES_PORT=5432
LOCALSTACK_PORT=4566
""",
        }
        for label, body in cases.items():
            with self.subTest(label):
                for child in self.root.iterdir():
                    if child.is_file():
                        child.unlink()
                    else:
                        shutil.rmtree(child)
                _write(self.root, ".env.example", body)
                original = (self.root / ".env.example").read_bytes()
                proc = _run_init(self.root)
                self.assertEqual(proc.returncode, 2, proc.stderr + proc.stdout)
                self.assertEqual(_state_lines(proc.stdout), ["COLLISION"])
                self.assertIn(".env.example", _collision_paths(proc.stdout))
                self.assertEqual((self.root / ".env.example").read_bytes(), original)
                self.assertFalse((self.root / "compose.yaml").exists())
                self.assertFalse(
                    (self.root / "infra" / "docker" / "localstack_ready.py").exists()
                )

    def test_ready_hook_collisions_are_preserved(self) -> None:
        missing_queue = """# custom compatible hook
ai-fashion-vault-local-avatar-private
ai-fashion-vault-local-vault-assets
ai-fashion-vault-local-look-private
ai-fashion-vault-local-generation-temp
"""
        missing_dlq = """# custom compatible hook
ai-fashion-vault-local-avatar-private
ai-fashion-vault-local-vault-assets
ai-fashion-vault-local-look-private
ai-fashion-vault-local-generation-temp
ai-fashion-vault-local-generation-work
"""
        cases = {
            "missing-queue": missing_queue,
            "missing-dlq": missing_dlq,
            "fifo": COMPAT_HOOK + "fifo\n",
        }
        for label, body in cases.items():
            with self.subTest(label):
                for child in self.root.iterdir():
                    if child.is_file():
                        child.unlink()
                    else:
                        shutil.rmtree(child)
                _write(self.root, "infra/docker/localstack_ready.py", body)
                original = (self.root / "infra" / "docker" / "localstack_ready.py").read_bytes()
                proc = _run_init(self.root)
                self.assertEqual(proc.returncode, 2, proc.stderr + proc.stdout)
                self.assertEqual(_state_lines(proc.stdout), ["COLLISION"])
                self.assertIn(
                    "infra/docker/localstack_ready.py", _collision_paths(proc.stdout)
                )
                self.assertEqual(
                    (self.root / "infra" / "docker" / "localstack_ready.py").read_bytes(),
                    original,
                )
                self.assertFalse((self.root / "compose.yaml").exists())
                self.assertFalse((self.root / ".env.example").exists())

    def test_ready_hook_is_tracked_executable(self) -> None:
        listing = subprocess.check_output(
            ["git", "ls-files", "-s", "--", "infra/docker/localstack_ready.py"],
            cwd=str(SCRIPT_HOME),
            text=True,
        )
        self.assertTrue(listing.startswith("100755"), listing)
        hook = SCRIPT_HOME / "infra" / "docker" / "localstack_ready.py"
        data = hook.read_bytes()
        self.assertTrue(data.startswith(b"#!/usr/bin/env python3\n"), data[:48])
        self.assertNotIn(b"\r\n", data)
        self.assertRegex(
            data.decode("utf-8"),
            r"(?m)^main\(\)\s*$",
            "READY hook must invoke main() at module level for LocalStack's Python init runner",
        )


if __name__ == "__main__":
    unittest.main()
