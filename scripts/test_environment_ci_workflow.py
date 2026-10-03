#!/usr/bin/env python3
"""Black-box fixtures for .github/workflows/environment-ci.yml."""

from __future__ import annotations

import re
import shlex
import unittest
from pathlib import Path


REPO = Path(__file__).resolve().parent.parent
WORKFLOW = REPO / ".github" / "workflows" / "environment-ci.yml"


def load_simple_yaml(text: str) -> object:
    """Parse a GitHub Actions YAML subset. Keys stay strings (`on` is not True)."""
    if "\t" in text:
        raise ValueError("workflow YAML must not contain tabs")

    lines = text.splitlines()
    i = 0
    n = len(lines)

    def fail(msg: str) -> None:
        raise ValueError(msg)

    def skip_blank() -> None:
        nonlocal i
        while i < n:
            stripped = lines[i].strip()
            if stripped == "" or stripped.startswith("#"):
                i += 1
                continue
            break

    def indent_of(s: str) -> int:
        return len(s) - len(s.lstrip(" "))

    def parse_scalar(raw: str) -> object:
        s = raw.strip()
        if " #" in s and not (s.startswith('"') or s.startswith("'")):
            s = s.split(" #", 1)[0].rstrip()
        if s in ("null", "~", ""):
            return None
        if s in ("true", "True"):
            return True
        if s in ("false", "False"):
            return False
        if len(s) >= 2 and s[0] == s[-1] and s[0] in ("'", '"'):
            return s[1:-1]
        return s

    def parse_block(min_indent: int) -> object:
        skip_blank()
        if i >= n:
            return None
        ind = indent_of(lines[i])
        if ind < min_indent:
            return None
        content = lines[i].lstrip(" ")
        if content.startswith("- "):
            return parse_seq(ind)
        return parse_map(ind)

    def parse_map(ind: int) -> dict:
        nonlocal i
        out: dict = {}
        while i < n:
            skip_blank()
            if i >= n:
                break
            cur = indent_of(lines[i])
            if cur < ind:
                break
            if cur > ind:
                fail(f"invalid indent at line {i + 1}")
            content = lines[i].lstrip(" ")
            if content.startswith("- "):
                fail(f"unexpected list item at line {i + 1}")
            if ":" not in content:
                fail(f"expected key: at line {i + 1}")
            key, rest = content.split(":", 1)
            key = key.strip()
            if len(key) >= 2 and key[0] == key[-1] and key[0] in ("'", '"'):
                key = key[1:-1]
            rest = rest.strip()
            i += 1
            if rest in ("|", "|-", "|+", ">", ">-", ">+"):
                out[key] = parse_literal(ind)
            elif rest == "" or rest.startswith("#"):
                skip_blank()
                if i < n and indent_of(lines[i]) > ind:
                    out[key] = parse_block(ind + 1)
                else:
                    out[key] = None
            else:
                out[key] = parse_scalar(rest)
        return out

    def parse_seq(ind: int) -> list:
        nonlocal i
        out: list = []
        while i < n:
            skip_blank()
            if i >= n:
                break
            cur = indent_of(lines[i])
            if cur < ind:
                break
            if cur > ind:
                fail(f"invalid indent at line {i + 1}")
            content = lines[i].lstrip(" ")
            if not content.startswith("- "):
                break
            rest = content[2:]
            i += 1
            stripped = rest.strip()
            if stripped == "" or stripped.startswith("#"):
                out.append(parse_block(ind + 2))
            elif ":" in rest and not stripped.startswith(("'", '"')):
                key, val = rest.split(":", 1)
                key = key.strip()
                val = val.strip()
                item: dict = {}
                if val in ("|", "|-", "|+", ">", ">-", ">+"):
                    item[key] = parse_literal(ind)
                elif val == "" or val.startswith("#"):
                    skip_blank()
                    if i < n and indent_of(lines[i]) > ind:
                        item[key] = parse_block(ind + 2)
                    else:
                        item[key] = None
                else:
                    item[key] = parse_scalar(val)
                skip_blank()
                if i < n:
                    nxt = indent_of(lines[i])
                    cont = lines[i].lstrip(" ")
                    if nxt > ind and not cont.startswith("- "):
                        item.update(parse_map(nxt))
                out.append(item)
            else:
                out.append(parse_scalar(rest))
        return out

    def parse_literal(parent_indent: int) -> str:
        nonlocal i
        body: list[str] = []
        block_indent: int | None = None
        while i < n:
            raw = lines[i]
            if raw.strip() == "":
                body.append("")
                i += 1
                continue
            cur = indent_of(raw)
            if cur <= parent_indent:
                break
            if block_indent is None:
                block_indent = cur
            if cur < block_indent:
                break
            body.append(raw[block_indent:])
            i += 1
        while body and body[-1] == "":
            body.pop()
        return "\n".join(body) + ("\n" if body else "")

    skip_blank()
    if i >= n:
        fail("empty YAML")
    result = parse_block(0)
    skip_blank()
    if i < n:
        fail(f"trailing content at line {i + 1}")
    return result


def check_workflow(path: Path) -> dict:
    """Parser/check seam: YAML must load as a workflow mapping with jobs."""
    text = path.read_text(encoding="utf-8")
    data = load_simple_yaml(text)
    if not isinstance(data, dict) or "jobs" not in data:
        raise ValueError("workflow must parse to a mapping with jobs")
    return data


def _flatten(obj: object) -> str:
    if obj is None:
        return ""
    if isinstance(obj, dict):
        return "\n".join(f"{k}\n{_flatten(v)}" for k, v in obj.items())
    if isinstance(obj, list):
        return "\n".join(_flatten(v) for v in obj)
    return str(obj)


def _jobs(data: dict) -> dict:
    jobs = data.get("jobs")
    if not isinstance(jobs, dict):
        raise AssertionError("jobs mapping required")
    return jobs


def _steps(job: dict) -> list:
    steps = job.get("steps") or []
    return steps if isinstance(steps, list) else []


def _runs(job: dict) -> list[str]:
    out: list[str] = []
    for step in _steps(job):
        if isinstance(step, dict) and step.get("run"):
            out.append(str(step["run"]))
    return out


def _all_runs(jobs: dict) -> str:
    return "\n".join(
        run
        for job in jobs.values()
        if isinstance(job, dict)
        for run in _runs(job)
    )


_ENV_ASSIGNMENT = re.compile(r"[A-Za-z_][A-Za-z0-9_]*=")
_HEREDOC = re.compile(r"<<-?\s*['\"]?([A-Za-z_][A-Za-z0-9_]*)['\"]?")


def _shell_commands(jobs: dict):
    for job in jobs.values():
        if not isinstance(job, dict):
            continue
        for run in _runs(job):
            heredoc_end: str | None = None
            continuation = ""
            for raw_line in run.splitlines():
                line = raw_line.strip()
                if heredoc_end is not None:
                    if line == heredoc_end:
                        heredoc_end = None
                    continue
                if continuation:
                    line = f"{continuation} {line}"
                    continuation = ""
                if line.endswith("\\"):
                    continuation = line[:-1].rstrip()
                    continue
                if not line:
                    continue
                if "<<" in line:
                    head, marker = line.split("<<", 1)
                    match = _HEREDOC.search(marker)
                    if head.strip():
                        yield shlex.split(head)
                    if match is not None:
                        heredoc_end = match.group(1)
                    continue
                yield shlex.split(line)


def _command_argv(argv: list[str]) -> list[str]:
    index = 0
    while index < len(argv) and _ENV_ASSIGNMENT.match(argv[index]):
        index += 1
    return argv[index:]


def _is_command(argv: list[str], executable: str, *operands: str) -> bool:
    argv = _command_argv(argv)
    while argv and re.match(r"^\d*(?:>>?|<)", argv[-1]):
        argv = argv[:-1]
    name = argv[0].rsplit("/", 1)[-1] if argv else ""
    return name == executable and tuple(argv[1:]) == operands


def _has_command(jobs: dict, executable: str, *operands: str) -> bool:
    return any(_is_command(argv, executable, *operands) for argv in _shell_commands(jobs))


class EnvironmentCiWorkflowTests(unittest.TestCase):
    def setUp(self) -> None:
        self.raw = WORKFLOW.read_text(encoding="utf-8")
        self.data = check_workflow(WORKFLOW)
        self.jobs = _jobs(self.data)

    def test_workflow_yaml_is_syntactically_valid(self) -> None:
        self.assertIsInstance(self.data, dict)
        self.assertIn("jobs", self.data)
        self.assertIn("on", self.data)

    def test_pull_request_trigger(self) -> None:
        on = self.data.get("on")
        if isinstance(on, dict):
            self.assertIn("pull_request", on)
        else:
            self.assertIn("pull_request", str(on))

    def test_checks_java_21(self) -> None:
        found_setup = False
        found_pom = False
        for job in self.jobs.values():
            if not isinstance(job, dict):
                continue
            for step in _steps(job):
                if not isinstance(step, dict):
                    continue
                uses = str(step.get("uses") or "")
                with_ = step.get("with") if isinstance(step.get("with"), dict) else {}
                ver = str(with_.get("java-version") or "").strip()
                if "setup-java" in uses and ver == "21":
                    found_setup = True
                run = str(step.get("run") or "")
                if "java.version" in run and "21" in run:
                    found_pom = True
        self.assertTrue(found_setup, "workflow must set up Java 21")
        self.assertTrue(found_pom, "workflow must verify Java 21 in the backend POM")
        self.assertTrue(_has_command(self.jobs, "python3", "-"))

    def test_checks_spring_boot_4x(self) -> None:
        blob = _all_runs(self.jobs)
        self.assertTrue(_has_command(self.jobs, "python3", "-"))
        self.assertIn("spring-boot-starter-parent", blob)
        self.assertTrue(
            "startswith(\"4.\")" in blob or "startswith('4.')" in blob or "4.x" in blob,
            "workflow must verify Spring Boot parent 4.x",
        )

    def test_runs_backend_tests(self) -> None:
        self.assertTrue(_has_command(self.jobs, "mvnw", "verify"))

    def test_runs_environment_policy(self) -> None:
        self.assertTrue(
            _has_command(self.jobs, "python3", "scripts/test_environment_contract.py")
        )

    def test_runs_workflow_contract_suite(self) -> None:
        self.assertTrue(
            _has_command(
                self.jobs, "python3", "scripts/test_environment_ci_workflow.py"
            )
        )

    def test_runs_content_secret_scan(self) -> None:
        self.assertTrue(
            _has_command(self.jobs, "python3", "scripts/secret_content_scan.py")
        )
        self.assertTrue(
            _has_command(self.jobs, "python3", "scripts/test_secret_content_scan.py")
        )
        blob = _all_runs(self.jobs).lower()
        self.assertNotIn("gitleaks", blob)
        self.assertNotIn("trufflehog", blob)

    def test_renders_compose_with_placeholders(self) -> None:
        found = False
        for argv in _shell_commands(self.jobs):
            if not _is_command(
                argv, "docker", "compose", "-f", "compose.yaml", "config"
            ):
                continue
            self.assertIn("LOCALSTACK_AUTH_TOKEN=ci-placeholder", argv)
            self.assertIn("POSTGRES_PASSWORD=ci-placeholder", argv)
            self.assertFalse(any(value.startswith("secrets.") for value in argv))
            found = True
        self.assertTrue(found, "workflow must render compose.yaml with placeholders")

    def _runtime_job(self) -> dict:
        for job in self.jobs.values():
            if isinstance(job, dict) and any(
                _is_command(argv, "python3", "scripts/test_localstack_resources.py")
                for argv in _shell_commands({"runtime": job})
            ):
                return job
        self.fail("missing trusted LocalStack runtime job")

    def test_protects_localstack_secret_from_fork_prs(self) -> None:
        self.assertNotIn("pull_request_target", self.raw)
        runtime = self._runtime_job()
        cond = str(runtime.get("if") or "")
        self.assertTrue(
            ("head.repo.full_name" in cond and "github.repository" in cond)
            or "head.repo.fork" in cond
            or ("github.event_name" in cond and "pull_request" in cond),
            "trusted runtime job must skip untrusted pull_request execution",
        )
        self.assertIn("github.ref", cond)
        self.assertIn("default_branch", cond)
        self.assertIn("&&", cond)
        self.assertNotIn("||", cond)
        self.assertIn("secrets.LOCALSTACK_AUTH_TOKEN", _flatten(runtime))
        for job in self.jobs.values():
            if not isinstance(job, dict) or job is runtime:
                continue
            self.assertNotIn("secrets.LOCALSTACK_AUTH_TOKEN", _flatten(job))

    def test_trusted_runtime_does_not_pass_when_token_absent(self) -> None:
        runtime = self._runtime_job()
        cond = str(runtime.get("if") or "")
        self.assertNotIn("secrets.LOCALSTACK_AUTH_TOKEN", cond)
        self.assertNotEqual(runtime.get("continue-on-error"), True)
        for step in _steps(runtime):
            if isinstance(step, dict):
                self.assertNotEqual(step.get("continue-on-error"), True)
        commands = list(_shell_commands({"runtime": runtime}))
        self.assertTrue(
            any(
                _is_command(argv, "python3", "scripts/test_localstack_resources.py")
                for argv in commands
            )
        )
        runtime_blob = "\n".join(
            str(step.get("run") or "")
            for step in _steps(runtime)
            if isinstance(step, dict)
        )
        self.assertIn('if [ -z "${LOCALSTACK_AUTH_TOKEN}" ]; then', runtime_blob)
        self.assertIn("exit 1", runtime_blob)

    def test_does_not_install_dependencies(self) -> None:
        blob = _all_runs(self.jobs).lower()
        for needle in (
            "pip install",
            "pip3 install",
            "python -m pip",
            "python3 -m pip",
            "npm install",
            "apt-get install",
            "apt install",
        ):
            self.assertNotIn(needle, blob, f"workflow must not install dependencies ({needle})")

    def test_no_static_aws_deployment_credentials(self) -> None:
        lowered = self.raw.lower()
        self.assertNotIn("AKIA", self.raw)
        self.assertNotIn("aws-access-key-id", lowered)
        self.assertNotIn("AWS_ACCESS_KEY_ID", self.raw)
        self.assertNotIn("AWS_SECRET_ACCESS_KEY", self.raw)
        self.assertNotIn("configure-aws-credentials", lowered)
        self.assertNotIn("role-to-assume", lowered)
        self.assertNotIn("REDIS_URL", self.raw)
        self.assertNotIn("S3_REGION", self.raw)


if __name__ == "__main__":
    unittest.main()
