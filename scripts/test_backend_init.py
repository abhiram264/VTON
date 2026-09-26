#!/usr/bin/env python3
"""Black-box fixtures for scripts/backend_init.py."""

from __future__ import annotations

import errno
import shutil
import subprocess
import re
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT_HOME = Path(__file__).resolve().parent.parent
BACKEND_INIT = Path(__file__).resolve().parent / "backend_init.py"

DUMMY_MOBILE = '{"name":"dummy-mobile"}\n'

FORBIDDEN_POM = (
    "jpa",
    "postgresql",
    "flyway",
    "redis",
    "testcontainers",
    "webflux",
)

REQUIRED_POM = (
    "spring-boot-starter-webmvc",
    "spring-boot-starter-security",
    "spring-boot-starter-validation",
    "spring-boot-starter-actuator",
    "spring-modulith-starter-core",
)

FORBIDDEN_FEATURE_DIRS = (
    "auth",
    "user",
    "avatar",
    "importer",
    "vault",
    "collection",
    "studio",
    "generation",
    "quality",
    "look",
    "notification",
    "privacy",
    "storage",
    "shared",
    "controller",
    "service",
    "repository",
    "entity",
)

_CACHE_TD: tempfile.TemporaryDirectory[str] | None = None
_CACHE_ROOT: Path | None = None
_ABSENT_PROC: subprocess.CompletedProcess[str] | None = None


def _run_init(root: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(BACKEND_INIT), "--root", str(root)],
        capture_output=True,
        text=True,
        cwd=str(SCRIPT_HOME),
    )


def _state_lines(stdout: str) -> list[str]:
    return [line[7:] for line in stdout.splitlines() if line.startswith("STATE: ")]


def _collision_paths(stdout: str) -> list[str]:
    return [line[11:] for line in stdout.splitlines() if line.startswith("COLLISION: ")]


def _stdout_payload(stdout: str) -> list[str]:
    return [line for line in stdout.splitlines() if line]


def _snapshot(backend: Path) -> dict[str, bytes]:
    out: dict[str, bytes] = {}
    if not backend.exists():
        return out
    for path in backend.rglob("*"):
        if not path.is_file():
            continue
        parts = path.relative_to(backend).parts
        if "target" in parts or ".git" in parts:
            continue
        out[path.relative_to(backend).as_posix()] = path.read_bytes()
    return out


def _copy_backend(src: Path, dest_root: Path) -> None:
    shutil.copytree(
        src,
        dest_root / "backend",
        dirs_exist_ok=False,
        ignore_dangling_symlinks=True,
    )


def _ensure_greenfield_cache() -> Path:
    global _CACHE_TD, _CACHE_ROOT, _ABSENT_PROC
    if _CACHE_ROOT is not None and _ABSENT_PROC is not None:
        return _CACHE_ROOT / "backend"
    _CACHE_TD = tempfile.TemporaryDirectory()
    root = Path(_CACHE_TD.name).resolve()
    live = SCRIPT_HOME.resolve()
    if live == root or live in root.parents:
        _CACHE_TD.cleanup()
        raise RuntimeError("temp root must not sit inside the live working tree")
    mobile = root / "mobile"
    mobile.mkdir()
    (mobile / "package.json").write_text(DUMMY_MOBILE, encoding="utf-8")
    _ABSENT_PROC = _run_init(root)
    _CACHE_ROOT = root
    return root / "backend"


def tearDownModule() -> None:
    global _CACHE_TD
    if _CACHE_TD is not None:
        try:
            _CACHE_TD.cleanup()
        except OSError:
            pass


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


class TestBackendInit(IsolatedTempRoot):
    def _symlink(self, link: Path, target: Path, *, directory: bool) -> None:
        try:
            link.symlink_to(target, target_is_directory=directory)
        except OSError as exc:
            if (
                exc.errno in {errno.EACCES, errno.EPERM}
                or getattr(exc, "winerror", None) == 1314
            ):
                self.skipTest("directory symlinks are unavailable")
            raise

    def _assert_collision(self, proc: subprocess.CompletedProcess[str]) -> None:
        self.assertEqual(proc.returncode, 2, proc.stderr)
        self._assert_stdout_state(proc, "COLLISION")
        self.assertNotIn("ValueError", proc.stderr)

    def _assert_stdout_state(self, proc: subprocess.CompletedProcess[str], state: str) -> None:
        payload = _stdout_payload(proc.stdout)
        self.assertTrue(payload, proc.stderr)
        self.assertTrue(payload[0].startswith("STATE: "), proc.stdout)
        self.assertEqual(_state_lines(proc.stdout), [state], proc.stderr + "\n" + proc.stdout)
        for line in payload[1:]:
            self.assertTrue(line.startswith("COLLISION: "), proc.stdout)

    def _assert_greenfield(self, root: Path) -> None:
        backend = root / "backend"
        pom_path = backend / "pom.xml"
        self.assertTrue(pom_path.is_file())
        pom = pom_path.read_text(encoding="utf-8")
        self.assertIn("<java.version>21</java.version>", pom)
        self.assertRegex(pom, r"<version>4\.[^<]+</version>")
        self.assertIn("com.aifashionvault", pom)
        self.assertIn("<artifactId>backend</artifactId>", pom)
        for needle in REQUIRED_POM:
            self.assertIn(needle, pom, needle)
        lowered = pom.lower()
        for needle in FORBIDDEN_POM:
            self.assertNotIn(needle, lowered, needle)
        self.assertTrue((backend / "mvnw").is_file())
        self.assertTrue((backend / "mvnw.cmd").is_file())
        self.assertTrue((backend / ".mvn" / "wrapper").is_dir())
        self.assertTrue((backend / "src" / "main" / "resources" / "application.yaml").is_file())
        self.assertFalse((backend / ".env").exists())
        self.assertFalse((backend / ".env.example").exists())
        self.assertFalse((backend / ".git").exists())

        java_root = backend / "src" / "main" / "java"
        self.assertTrue((java_root / "com" / "aifashionvault").is_dir())
        for path in java_root.rglob("*.java"):
            text = path.read_text(encoding="utf-8")
            self.assertNotIn("@RestController", text, str(path))
            self.assertNotIn("@Controller", text, str(path))
        pkg = java_root / "com" / "aifashionvault"
        for name in FORBIDDEN_FEATURE_DIRS:
            self.assertFalse((pkg / name).exists(), name)
            self.assertFalse((java_root / name).exists(), name)

    def test_absent_generates_complete_contract(self) -> None:
        backend = _ensure_greenfield_cache()
        self.assertIsNotNone(_ABSENT_PROC)
        proc = _ABSENT_PROC
        assert proc is not None
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self._assert_stdout_state(proc, "ABSENT")
        self.assertEqual(_collision_paths(proc.stdout), [])
        self.assertTrue(backend.is_dir())
        self._assert_greenfield(backend.parent)

    def test_dummy_mobile_does_not_block_absent(self) -> None:
        backend = _ensure_greenfield_cache()
        self.assertTrue(backend.is_dir(), getattr(_ABSENT_PROC, "stderr", ""))
        mobile_pkg = backend.parent / "mobile" / "package.json"
        self.assertTrue(mobile_pkg.is_file())
        self.assertEqual(mobile_pkg.read_text(encoding="utf-8"), DUMMY_MOBILE)
        self._assert_greenfield(backend.parent)

    def test_empty_shell_then_generate(self) -> None:
        marker = self.root / "outside.txt"
        marker.write_text("keep\n", encoding="utf-8")
        backend = self.root / "backend"
        backend.mkdir()
        (backend / ".gitkeep").write_text("", encoding="utf-8")
        nested = backend / "empty" / "dir"
        nested.mkdir(parents=True)
        proc = _run_init(self.root)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self._assert_stdout_state(proc, "EMPTY")
        self.assertEqual(marker.read_text(encoding="utf-8"), "keep\n")
        self._assert_greenfield(self.root)

    def test_not_really_empty_is_collision(self) -> None:
        notes = self.root / "backend" / "notes.txt"
        notes.parent.mkdir()
        original = b"not a spring boot app\n"
        notes.write_bytes(original)
        proc = _run_init(self.root)
        self.assertEqual(proc.returncode, 2, proc.stderr)
        self._assert_stdout_state(proc, "COLLISION")
        self.assertIn("backend/notes.txt", _collision_paths(proc.stdout))
        self.assertEqual(notes.read_bytes(), original)
        self.assertFalse((self.root / "backend" / "pom.xml").exists())
        self.assertFalse((self.root / "backend" / "mvnw").exists())

    def test_partial_restores_only_missing_safe_artifact(self) -> None:
        src = _ensure_greenfield_cache()
        self.assertTrue(src.is_dir(), getattr(_ABSENT_PROC, "stderr", ""))
        _copy_backend(src, self.root)
        target = self.root / "backend" / "src" / "main" / "resources" / "application.yaml"
        pom = self.root / "backend" / "pom.xml"
        original_yaml = target.read_bytes()
        pom_bytes = pom.read_bytes()
        target.unlink()
        before = _snapshot(self.root / "backend")
        proc = _run_init(self.root)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self._assert_stdout_state(proc, "PARTIAL_COMPATIBLE")
        self.assertTrue(target.is_file())
        self.assertEqual(pom.read_bytes(), pom_bytes)
        after = _snapshot(self.root / "backend")
        for rel, data in before.items():
            self.assertEqual(after[rel], data, rel)
        self.assertIn("src/main/resources/application.yaml", after)
        self.assertEqual(after["src/main/resources/application.yaml"], original_yaml)

    def test_partial_keeps_existing_application_properties(self) -> None:
        src = _ensure_greenfield_cache()
        self.assertTrue(src.is_dir(), getattr(_ABSENT_PROC, "stderr", ""))
        _copy_backend(src, self.root)
        resources = self.root / "backend" / "src" / "main" / "resources"
        yaml_path = resources / "application.yaml"
        props_path = resources / "application.properties"
        pom = self.root / "backend" / "pom.xml"
        pom_bytes = pom.read_bytes()
        yaml_path.unlink()
        props_bytes = b"server.port=8081\n"
        props_path.write_bytes(props_bytes)
        proc = _run_init(self.root)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self._assert_stdout_state(proc, "PARTIAL_COMPATIBLE")
        self.assertTrue(yaml_path.is_file())
        self.assertTrue(props_path.is_file())
        self.assertEqual(props_path.read_bytes(), props_bytes)
        self.assertEqual(pom.read_bytes(), pom_bytes)

    def test_initialized_rerun_is_noop(self) -> None:
        src = _ensure_greenfield_cache()
        self.assertTrue(src.is_dir(), getattr(_ABSENT_PROC, "stderr", ""))
        _copy_backend(src, self.root)
        before = _snapshot(self.root / "backend")
        proc = _run_init(self.root)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self._assert_stdout_state(proc, "INITIALIZED")
        self.assertEqual(_snapshot(self.root / "backend"), before)

    def test_external_git_symlink_is_normal_collision(self) -> None:
        backend = self.root / "backend"
        backend.mkdir()
        external_git = self.root / "external-git"
        external_git.mkdir()
        (external_git / "marker").write_bytes(b"keep\n")
        self._symlink(backend / ".git", external_git, directory=True)

        proc = _run_init(self.root)

        self._assert_collision(proc)
        self.assertEqual(_collision_paths(proc.stdout), ["backend/.git"])
        self.assertEqual((external_git / "marker").read_bytes(), b"keep\n")

    def test_external_wrapper_symlink_is_collision_without_write(self) -> None:
        src = _ensure_greenfield_cache()
        self.assertTrue(src.is_dir(), getattr(_ABSENT_PROC, "stderr", ""))
        _copy_backend(src, self.root)
        wrapper = self.root / "backend" / ".mvn" / "wrapper"
        shutil.rmtree(wrapper)
        external_wrapper = self.root / "external-wrapper"
        external_wrapper.mkdir()
        (external_wrapper / "marker").write_bytes(b"keep\n")
        self._symlink(wrapper, external_wrapper, directory=True)

        proc = _run_init(self.root)

        self._assert_collision(proc)
        self.assertIn(
            "backend/.mvn/wrapper/maven-wrapper.properties",
            _collision_paths(proc.stdout),
        )
        self.assertEqual((external_wrapper / "marker").read_bytes(), b"keep\n")
        self.assertFalse((external_wrapper / "maven-wrapper.properties").exists())

    def test_external_src_symlink_is_collision_without_write(self) -> None:
        src = _ensure_greenfield_cache()
        self.assertTrue(src.is_dir(), getattr(_ABSENT_PROC, "stderr", ""))
        _copy_backend(src, self.root)
        backend_src = self.root / "backend" / "src"
        shutil.rmtree(backend_src)
        external_src = self.root / "external-src"
        external_src.mkdir()
        (external_src / "marker").write_bytes(b"keep\n")
        self._symlink(backend_src, external_src, directory=True)

        proc = _run_init(self.root)

        self._assert_collision(proc)
        self.assertIn(
            "backend/src/main/resources/application.yaml",
            _collision_paths(proc.stdout),
        )
        self.assertEqual((external_src / "marker").read_bytes(), b"keep\n")
        self.assertFalse((external_src / "main").exists())

    def test_external_mvnw_symlink_is_collision_without_write(self) -> None:
        src = _ensure_greenfield_cache()
        self.assertTrue(src.is_dir(), getattr(_ABSENT_PROC, "stderr", ""))
        _copy_backend(src, self.root)
        mvnw = self.root / "backend" / "mvnw"
        mvnw.unlink()
        external_mvnw = self.root / "external-mvnw"
        external_mvnw.write_bytes(b"keep\n")
        self._symlink(mvnw, external_mvnw, directory=False)

        proc = _run_init(self.root)

        self._assert_collision(proc)
        self.assertIn("backend/mvnw", _collision_paths(proc.stdout))
        self.assertEqual(external_mvnw.read_bytes(), b"keep\n")

    def test_gradle_boot3_nested_git_are_collisions(self) -> None:
        gradle_root = self.root / "gradle_case"
        gradle_root.mkdir()
        gradle_backend = gradle_root / "backend"
        gradle_backend.mkdir()
        gradle_file = gradle_backend / "build.gradle"
        gradle_bytes = b"plugins { id 'java' }\n"
        gradle_file.write_bytes(gradle_bytes)
        proc = _run_init(gradle_root)
        self.assertEqual(proc.returncode, 2, proc.stderr)
        self._assert_stdout_state(proc, "COLLISION")
        self.assertEqual(gradle_file.read_bytes(), gradle_bytes)
        self.assertFalse((gradle_backend / "pom.xml").exists())

        src = _ensure_greenfield_cache()
        self.assertTrue(src.is_dir(), getattr(_ABSENT_PROC, "stderr", ""))
        boot3_root = self.root / "boot3_case"
        boot3_root.mkdir()
        _copy_backend(src, boot3_root)
        pom = boot3_root / "backend" / "pom.xml"
        edited = re.sub(
            r"(<artifactId>spring-boot-starter-parent</artifactId>\s*<version>)[^<]+(</version>)",
            r"\g<1>3.4.5\g<2>",
            pom.read_text(encoding="utf-8"),
            count=1,
        )
        pom.write_text(edited, encoding="utf-8")
        before = _snapshot(boot3_root / "backend")
        proc = _run_init(boot3_root)
        self.assertEqual(proc.returncode, 2, proc.stderr)
        self._assert_stdout_state(proc, "COLLISION")
        self.assertEqual(pom.read_text(encoding="utf-8"), edited)
        self.assertEqual(_snapshot(boot3_root / "backend"), before)

        git_root = self.root / "git_case"
        git_root.mkdir()
        git_backend = git_root / "backend"
        git_dir = git_backend / ".git"
        git_dir.mkdir(parents=True)
        head = git_dir / "HEAD"
        head_bytes = b"ref: refs/heads/main\n"
        head.write_bytes(head_bytes)
        proc = _run_init(git_root)
        self.assertEqual(proc.returncode, 2, proc.stderr)
        self._assert_stdout_state(proc, "COLLISION")
        self.assertEqual(head.read_bytes(), head_bytes)
        self.assertFalse((git_backend / "pom.xml").exists())

    def test_boot4_other_minor_not_upgraded(self) -> None:
        src = _ensure_greenfield_cache()
        self.assertTrue(src.is_dir(), getattr(_ABSENT_PROC, "stderr", ""))
        _copy_backend(src, self.root)
        pom = self.root / "backend" / "pom.xml"
        edited = re.sub(
            r"(<artifactId>spring-boot-starter-parent</artifactId>\s*<version>)[^<]+(</version>)",
            r"\g<1>4.0.0\g<2>",
            pom.read_text(encoding="utf-8"),
            count=1,
        )
        pom.write_text(edited, encoding="utf-8")
        before = _snapshot(self.root / "backend")
        proc = _run_init(self.root)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertNotEqual(_state_lines(proc.stdout), ["COLLISION"])
        self.assertEqual(pom.read_text(encoding="utf-8"), edited)
        self.assertIn("<version>4.0.0</version>", pom.read_text(encoding="utf-8"))
        self.assertEqual(_snapshot(self.root / "backend"), before)

    def test_java_25_is_collision(self) -> None:
        src = _ensure_greenfield_cache()
        self.assertTrue(src.is_dir(), getattr(_ABSENT_PROC, "stderr", ""))
        _copy_backend(src, self.root)
        pom = self.root / "backend" / "pom.xml"
        edited = pom.read_text(encoding="utf-8").replace(
            "<java.version>21</java.version>", "<java.version>25</java.version>", 1
        )
        pom.write_text(edited, encoding="utf-8")
        before = _snapshot(self.root / "backend")
        proc = _run_init(self.root)
        self.assertEqual(proc.returncode, 2, proc.stderr)
        self._assert_stdout_state(proc, "COLLISION")
        self.assertEqual(pom.read_text(encoding="utf-8"), edited)
        self.assertEqual(_snapshot(self.root / "backend"), before)

    def test_pom_omits_deferred_integrations(self) -> None:
        backend = _ensure_greenfield_cache()
        self.assertTrue(backend.is_dir(), getattr(_ABSENT_PROC, "stderr", ""))
        pom = (backend / "pom.xml").read_text(encoding="utf-8").lower()
        for needle in FORBIDDEN_POM:
            self.assertNotIn(needle, pom, needle)


if __name__ == "__main__":
    unittest.main()
