#!/usr/bin/env python3
"""Black-box fixtures for scripts/run_backend.py. Does not launch the JVM."""

from __future__ import annotations

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import run_backend

SCRIPT = Path(__file__).resolve().parent / "run_backend.py"
REPO = Path(__file__).resolve().parent.parent

BUCKETS = {
    "S3_AVATAR_BUCKET": "ai-fashion-vault-local-avatar-private",
    "S3_VAULT_BUCKET": "ai-fashion-vault-local-vault-assets",
    "S3_LOOK_BUCKET": "ai-fashion-vault-local-look-private",
    "S3_TEMP_BUCKET": "ai-fashion-vault-local-generation-temp",
}

SAMPLE = """LOCALSTACK_AUTH_TOKEN=tok-secret
POSTGRES_PASSWORD=pw-secret
POSTGRES_PORT=5432
LOCALSTACK_PORT=4566
AWS_ACCESS_KEY_ID=test
AWS_SECRET_ACCESS_KEY=test
"""


def _write_env(root: Path, text: str) -> Path:
    path = root / ".env"
    path.write_text(text, encoding="utf-8")
    return path


class RunBackendTests(unittest.TestCase):
    def test_derived_values_and_no_embedded_password(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write_env(root, SAMPLE)
            parsed = run_backend.load_root_env(root)
            child = run_backend.backend_env(parsed, root)
            self.assertEqual(child["APP_ENV"], "development")
            self.assertEqual(child["DB_USERNAME"], "postgres")
            self.assertEqual(child["DB_PASSWORD"], "pw-secret")
            self.assertEqual(
                child["DB_URL"],
                "jdbc:postgresql://127.0.0.1:5432/postgres",
            )
            self.assertNotIn("pw-secret", child["DB_URL"])
            self.assertEqual(child["AWS_REGION"], "us-east-1")
            self.assertEqual(child["AWS_ENDPOINT_URL_S3"], "http://127.0.0.1:4566")
            self.assertEqual(child["AWS_ENDPOINT_URL_SQS"], "http://127.0.0.1:4566")
            self.assertEqual(child["AWS_ACCESS_KEY_ID"], "test")
            self.assertEqual(child["AWS_SECRET_ACCESS_KEY"], "test")
            self.assertEqual(
                child["SQS_GENERATION_QUEUE_URL"],
                "http://127.0.0.1:4566/000000000000/ai-fashion-vault-local-generation-work",
            )
            for key, value in BUCKETS.items():
                self.assertEqual(child[key], value)
            self.assertEqual(child["OTEL_SERVICE_NAME"], "ai-fashion-vault-api")
            attrs = child["OTEL_RESOURCE_ATTRIBUTES"]
            self.assertIn("service.namespace=ai-fashion-vault", attrs)
            self.assertIn("deployment.environment.name=development", attrs)
            self.assertIn("service.version=", attrs)
            self.assertNotIn("LOCALSTACK_AUTH_TOKEN", child)
            cmd = run_backend.backend_command(root)
            self.assertTrue(any("mvnw" in part or "spring-boot:run" in part for part in cmd))

    def test_port_overrides_propagate(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write_env(
                root,
                "LOCALSTACK_AUTH_TOKEN=tok\nPOSTGRES_PASSWORD=pw\n"
                "POSTGRES_PORT=15432\nLOCALSTACK_PORT=14566\n",
            )
            child = run_backend.backend_env(run_backend.load_root_env(root), root)
            self.assertEqual(
                child["DB_URL"],
                "jdbc:postgresql://127.0.0.1:15432/postgres",
            )
            self.assertEqual(child["AWS_ENDPOINT_URL_S3"], "http://127.0.0.1:14566")
            self.assertEqual(child["AWS_ENDPOINT_URL_SQS"], "http://127.0.0.1:14566")
            self.assertEqual(
                child["SQS_GENERATION_QUEUE_URL"],
                "http://127.0.0.1:14566/000000000000/ai-fashion-vault-local-generation-work",
            )

    def test_explicit_aws_keys_kept(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write_env(
                root,
                "LOCALSTACK_AUTH_TOKEN=tok\nPOSTGRES_PASSWORD=pw\n"
                "AWS_ACCESS_KEY_ID=custom\nAWS_SECRET_ACCESS_KEY=custom-secret\n",
            )
            child = run_backend.backend_env(run_backend.load_root_env(root), root)
            self.assertEqual(child["AWS_ACCESS_KEY_ID"], "custom")
            self.assertEqual(child["AWS_SECRET_ACCESS_KEY"], "custom-secret")

    def test_missing_env_file(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            proc = subprocess.run(
                [sys.executable, str(SCRIPT), "--root", tmp, "--dry-run"],
                capture_output=True,
                text=True,
                check=False,
            )
        self.assertNotEqual(proc.returncode, 0)
        err = proc.stderr
        self.assertIn(".env", err)
        self.assertNotIn("LOCALSTACK_AUTH_TOKEN", err.splitlines()[0] if err else "")

    def test_missing_localstack_token(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write_env(root, "POSTGRES_PASSWORD=pw\n")
            proc = subprocess.run(
                [sys.executable, str(SCRIPT), "--root", str(root), "--dry-run"],
                capture_output=True,
                text=True,
                check=False,
            )
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("LOCALSTACK_AUTH_TOKEN", proc.stderr)
        self.assertNotIn("POSTGRES_PASSWORD", proc.stderr)

    def test_missing_postgres_password(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write_env(root, "LOCALSTACK_AUTH_TOKEN=tok\n")
            proc = subprocess.run(
                [sys.executable, str(SCRIPT), "--root", str(root), "--dry-run"],
                capture_output=True,
                text=True,
                check=False,
            )
        self.assertNotEqual(proc.returncode, 0)
        self.assertIn("POSTGRES_PASSWORD", proc.stderr)
        self.assertNotIn("LOCALSTACK_AUTH_TOKEN", proc.stderr)

    def test_dry_run_does_not_print_secrets(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            _write_env(root, SAMPLE)
            proc = subprocess.run(
                [sys.executable, str(SCRIPT), "--root", str(root), "--dry-run"],
                capture_output=True,
                text=True,
                check=False,
            )
        self.assertEqual(proc.returncode, 0, proc.stderr)
        combined = proc.stdout + proc.stderr
        self.assertNotIn("tok-secret", combined)
        self.assertNotIn("pw-secret", combined)
        self.assertIn("spring-boot:run", combined)


    def test_windows_launch_keeps_root_out_of_cmd_arguments(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp) / "repo&echo-owned"
            root.mkdir()
            _write_env(root, SAMPLE)
            with mock.patch.object(run_backend.os, "name", "nt"), mock.patch.object(
                run_backend.subprocess, "call", return_value=0
            ) as call:
                self.assertEqual(run_backend.main(["--root", str(root)]), 0)

            cmd = call.call_args.args[0]
            self.assertEqual(cmd[:4], ["cmd.exe", "/d", "/c", "mvnw.cmd"])
            self.assertNotIn(str(root), cmd)
            self.assertEqual(call.call_args.kwargs["cwd"], root / "backend")
    def test_service_version_from_backend_pom(self) -> None:
        version = run_backend.backend_artifact_version(REPO)
        self.assertTrue(version)
        child = run_backend.backend_env(
            {
                "LOCALSTACK_AUTH_TOKEN": "tok",
                "POSTGRES_PASSWORD": "pw",
            },
            REPO,
        )
        self.assertIn(f"service.version={version}", child["OTEL_RESOURCE_ATTRIBUTES"])


if __name__ == "__main__":
    unittest.main()
