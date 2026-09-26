#!/usr/bin/env python3
"""Black-box fixtures for scripts/mobile_init.py."""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT_HOME = Path(__file__).resolve().parent.parent
MOBILE_INIT = Path(__file__).resolve().parent / "mobile_init.py"

FORBIDDEN_ROOT = (
    "package.json",
    "package-lock.json",
    "pnpm-workspace.yaml",
    "yarn.lock",
    "nx.json",
    "turbo.json",
    "WORKSPACE",
    "WORKSPACE.bazel",
)

FORBIDDEN_PACKAGES = (
    "axios",
    "ky",
    "got",
    "superagent",
    "@reduxjs/toolkit",
    "redux",
    "zustand",
    "recoil",
    "jotai",
    "mobx",
    "@tanstack/react-query",
    "swr",
    "firebase",
    "react-native-image-picker",
    "expo-image-picker",
    "react-native-vision-camera",
    "expo-camera",
    "react-native-keychain",
    "expo-secure-store",
    "react-native-mmkv",
    "@react-native-async-storage/async-storage",
    "@react-native-firebase/messaging",
    "expo-notifications",
    "react-native-branch",
    "expo-linking",
    "react-native-config",
    "react-native-dotenv",
    "expo-router",
    "@react-navigation/bottom-tabs",
)

FORBIDDEN_SRC_DIRS = (
    "src/features",
    "src/api",
    "src/hooks",
    "src/state",
    "src/analytics",
    "src/native",
    "src/utils",
    "src/components",
    "src/design-system",
)

PRODUCT_NAMES = (
    "Authentication",
    "Avatar",
    "Import",
    "Vault",
    "Collections",
    "Studio",
    "Generation",
    "Looks",
    "Settings",
)

REQUIRED_SCRIPTS = (
    "typecheck",
    "lint",
    "format:check",
    "format-fix",
    "test",
)

PRETTIER_EXCLUDES = (
    "android/",
    "ios/",
    "*.kt",
    "*.java",
    "*.swift",
    "*.m",
    "*.mm",
    "*.gradle",
    "*.pbxproj",
    "*.xcscheme",
    "Pods/",
)

_CACHE_TD: tempfile.TemporaryDirectory[str] | None = None
_CACHE_ROOT: Path | None = None
_ABSENT_PROC: subprocess.CompletedProcess[str] | None = None


def _run_init(root: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(MOBILE_INIT), "--root", str(root)],
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


def _snapshot(mobile: Path) -> dict[str, bytes]:
    out: dict[str, bytes] = {}
    if not mobile.exists():
        return out
    for path in mobile.rglob("*"):
        if not path.is_file():
            continue
        parts = path.relative_to(mobile).parts
        if "node_modules" in parts or ".git" in parts:
            continue
        out[path.relative_to(mobile).as_posix()] = path.read_bytes()
    return out


def _copy_mobile(src: Path, dest_root: Path) -> None:
    dest = dest_root / "mobile"
    shutil.copytree(
        src,
        dest,
        ignore=shutil.ignore_patterns("node_modules"),
        dirs_exist_ok=False,
        ignore_dangling_symlinks=True,
    )


def _load_pkg(mobile: Path) -> dict:
    return json.loads((mobile / "package.json").read_text(encoding="utf-8"))


def _deps(pkg: dict) -> dict[str, str]:
    out: dict[str, str] = {}
    for bucket in ("dependencies", "devDependencies", "peerDependencies"):
        section = pkg.get(bucket)
        if isinstance(section, dict):
            out.update({str(k): str(v) for k, v in section.items()})
    return out


def _major(version: str) -> str:
    return version.lstrip("^~>=< ").split(".")[0]


def _ensure_greenfield_cache() -> Path:
    global _CACHE_TD, _CACHE_ROOT, _ABSENT_PROC
    if _CACHE_ROOT is not None and _ABSENT_PROC is not None:
        return _CACHE_ROOT / "mobile"
    _CACHE_TD = tempfile.TemporaryDirectory()
    root = Path(_CACHE_TD.name).resolve()
    live = SCRIPT_HOME.resolve()
    if live == root or live in root.parents:
        _CACHE_TD.cleanup()
        raise RuntimeError("temp root must not sit inside the live working tree")
    _ABSENT_PROC = _run_init(root)
    _CACHE_ROOT = root
    return root / "mobile"


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


    def _symlink(self, link: Path, target: Path, *, directory: bool = False) -> None:
        try:
            link.symlink_to(target, target_is_directory=directory)
        except (OSError, NotImplementedError) as exc:
            self.skipTest(f"symlinks unavailable: {exc}")

class TestMobileInit(IsolatedTempRoot):
    def _assert_stdout_state(self, proc: subprocess.CompletedProcess[str], state: str) -> None:
        payload = _stdout_payload(proc.stdout)
        self.assertTrue(payload, proc.stderr)
        self.assertTrue(payload[0].startswith("STATE: "), proc.stdout)
        self.assertEqual(_state_lines(proc.stdout), [state], proc.stderr + "\n" + proc.stdout)
        for line in payload[1:]:
            self.assertTrue(line.startswith("COLLISION: "), proc.stdout)

    def _assert_greenfield(self, root: Path) -> None:
        mobile = root / "mobile"
        self.assertTrue((mobile / "package.json").is_file())
        self.assertTrue((mobile / "package-lock.json").is_file())
        self.assertTrue((mobile / "android").is_dir())
        self.assertTrue((mobile / "ios").is_dir())
        self.assertTrue((mobile / "App.tsx").is_file())
        self.assertTrue((mobile / "src" / "app" / "AppShell.tsx").is_file())
        self.assertTrue((mobile / "src" / "navigation" / "RootNavigator.tsx").is_file())
        self.assertTrue((mobile / "src" / "screens" / "StartupScreen.tsx").is_file())
        self.assertTrue((mobile / "__tests__" / "App.test.tsx").is_file())
        self.assertTrue((mobile / ".prettierignore").is_file())
        self.assertTrue((mobile / "tsconfig.json").is_file())
        self.assertFalse((mobile / ".git").exists())
        self.assertFalse((mobile / ".env").exists())
        self.assertFalse((mobile / ".env.example").exists())
        for name in FORBIDDEN_ROOT:
            self.assertFalse((root / name).exists(), name)

        pkg = _load_pkg(mobile)
        deps = _deps(pkg)
        self.assertEqual(deps.get("react-native"), "0.86.2")
        self.assertEqual(pkg.get("engines", {}).get("node"), "^22.13.0 || >=24")
        scripts = pkg.get("scripts", {})
        for name in REQUIRED_SCRIPTS:
            self.assertIn(name, scripts, name)
        self.assertIn("tsc --noEmit", scripts["typecheck"])
        self.assertIn("eslint", scripts["lint"])
        self.assertIn("prettier --check", scripts["format:check"])
        self.assertIn("prettier --write", scripts["format-fix"])
        self.assertIn("jest", scripts["test"])
        self.assertEqual(deps.get("@testing-library/react-native"), "14.0.1")
        self.assertIn("test-renderer", deps)
        for name in NAV_PACKAGES_LOCAL:
            self.assertIn(name, deps, name)
            if name.startswith("@react-navigation/"):
                self.assertEqual(_major(deps[name]), "7", name)
        for name, version in deps.items():
            self.assertFalse(name.startswith("expo"), name)
            self.assertNotIn("expo-router", name)
            if name.startswith("@react-navigation/"):
                self.assertNotEqual(_major(version), "8", name)
            self.assertNotIn(name, FORBIDDEN_PACKAGES)
            self.assertFalse(name.startswith("@react-native-firebase/"), name)

        app_json = json.loads((mobile / "app.json").read_text(encoding="utf-8"))
        self.assertEqual(app_json.get("displayName"), "AI Fashion Vault")
        self.assertNotEqual(app_json.get("name"), "VTON")
        self.assertNotEqual(app_json.get("name"), "OurApp")
        android_blob = _concat_files(mobile / "android", {".gradle", ".kts", ".xml"})
        ios_blob = _concat_files(mobile / "ios", {".pbxproj", ".plist"})
        self.assertIn("com.aifashionvault.app", android_blob)
        self.assertIn("com.aifashionvault.app", ios_blob)
        self.assertNotIn("newArchEnabled=false", android_blob)
        self.assertNotIn("RCT_NEW_ARCH_ENABLED=0", ios_blob)

        tsconfig = json.loads((mobile / "tsconfig.json").read_text(encoding="utf-8"))
        compiler = tsconfig["compilerOptions"]
        self.assertIs(compiler["strict"], True)
        self.assertIn("react-native-strict-api", compiler["customConditions"])

        jest_cfg = (mobile / "jest.config.js").read_text(encoding="utf-8")
        self.assertIn("transformIgnorePatterns", jest_cfg)
        self.assertIn("@react-navigation", jest_cfg)
        self.assertRegex(
            ios_blob,
            r"<key>CFBundleDisplayName</key>\s*<string>AI Fashion Vault</string>",
        )

        ignore = (mobile / ".prettierignore").read_text(encoding="utf-8")
        for pattern in PRETTIER_EXCLUDES:
            self.assertIn(pattern, ignore, pattern)

        app_tsx = (mobile / "App.tsx").read_text(encoding="utf-8")
        self.assertIn("AppShell", app_tsx)
        startup = (mobile / "src" / "screens" / "StartupScreen.tsx").read_text(encoding="utf-8")
        self.assertIn("Application ready", startup)
        for word in ("Vault", "Studio", "Try-On", "Authentication"):
            self.assertNotIn(word, startup)
        test_src = (mobile / "__tests__" / "App.test.tsx").read_text(encoding="utf-8")
        self.assertIn("@testing-library/react-native", test_src)
        self.assertIn("render", test_src)
        self.assertIn("screen", test_src)
        self.assertNotIn("react-test-renderer", test_src)
        nav = (mobile / "src" / "navigation" / "RootNavigator.tsx").read_text(encoding="utf-8")
        self.assertIn("createNativeStackNavigator", nav)
        self.assertIn("StartupScreen", nav)

        for rel in FORBIDDEN_SRC_DIRS:
            self.assertFalse((mobile / rel).exists(), rel)
        for name in PRODUCT_NAMES:
            matches = [p for p in mobile.rglob("*") if p.name == name and "node_modules" not in p.parts]
            self.assertEqual(matches, [], name)

        sources = [
            mobile / "App.tsx",
            mobile / "src" / "app" / "AppShell.tsx",
            mobile / "src" / "navigation" / "RootNavigator.tsx",
            mobile / "src" / "screens" / "StartupScreen.tsx",
        ]
        for path in sources:
            text = path.read_text(encoding="utf-8")
            self.assertNotIn("react-native/Libraries", text)

    def test_absent_generates_complete_contract(self) -> None:
        mobile = _ensure_greenfield_cache()
        self.assertIsNotNone(_ABSENT_PROC)
        proc = _ABSENT_PROC
        assert proc is not None
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self._assert_stdout_state(proc, "ABSENT")
        self.assertEqual(_collision_paths(proc.stdout), [])
        self.assertTrue(mobile.is_dir())
        self._assert_greenfield(mobile.parent)

    def test_initialized_rerun_is_noop(self) -> None:
        src = _ensure_greenfield_cache()
        self.assertTrue(src.is_dir(), getattr(_ABSENT_PROC, "stderr", ""))
        _copy_mobile(src, self.root)
        before = _snapshot(self.root / "mobile")
        proc = _run_init(self.root)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self._assert_stdout_state(proc, "INITIALIZED")
        self.assertEqual(_snapshot(self.root / "mobile"), before)

    def test_missing_app_json_repairs_to_partial(self) -> None:
        src = _ensure_greenfield_cache()
        self.assertTrue(src.is_dir(), getattr(_ABSENT_PROC, "stderr", ""))
        _copy_mobile(src, self.root)
        (self.root / "mobile" / "app.json").unlink()
        proc = _run_init(self.root)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self._assert_stdout_state(proc, "PARTIAL")
        app_json = json.loads(
            (self.root / "mobile" / "app.json").read_text(encoding="utf-8")
        )
        self.assertEqual(app_json["name"], "AIFashionVault")
        self.assertEqual(app_json["displayName"], "AI Fashion Vault")

    def test_empty_shell_then_generate(self) -> None:
        marker = self.root / "outside.txt"
        marker.write_text("keep\n", encoding="utf-8")
        mobile = self.root / "mobile"
        mobile.mkdir()
        (mobile / ".gitkeep").write_text("", encoding="utf-8")
        nested = mobile / "empty" / "dir"
        nested.mkdir(parents=True)
        proc = _run_init(self.root)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self._assert_stdout_state(proc, "EMPTY")
        self.assertEqual(marker.read_text(encoding="utf-8"), "keep\n")
        self.assertFalse((self.root / "backend").exists())
        self.assertFalse((self.root / "infra").exists())
        self._assert_greenfield(self.root)

    def test_partial_restores_only_missing_safe_artifact(self) -> None:
        src = _ensure_greenfield_cache()
        self.assertTrue(src.is_dir(), getattr(_ABSENT_PROC, "stderr", ""))
        _copy_mobile(src, self.root)
        target = self.root / "mobile" / "src" / "app" / "AppShell.tsx"
        original_neighbors = _snapshot(self.root / "mobile")
        target.unlink()
        before = _snapshot(self.root / "mobile")
        proc = _run_init(self.root)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self._assert_stdout_state(proc, "PARTIAL")
        self.assertTrue(target.is_file())
        after = _snapshot(self.root / "mobile")
        for rel, data in before.items():
            self.assertEqual(after[rel], data, rel)
        self.assertIn("src/app/AppShell.tsx", after)
        self.assertEqual(after["src/app/AppShell.tsx"], original_neighbors["src/app/AppShell.tsx"])

    def test_incompatible_owned_edit_is_collision(self) -> None:
        src = _ensure_greenfield_cache()
        self.assertTrue(src.is_dir(), getattr(_ABSENT_PROC, "stderr", ""))
        _copy_mobile(src, self.root)
        target = self.root / "mobile" / "src" / "app" / "AppShell.tsx"
        original = target.read_bytes()
        edited = original + b"\n// local fork\n"
        target.write_bytes(edited)
        before = _snapshot(self.root / "mobile")
        proc = _run_init(self.root)
        self.assertEqual(proc.returncode, 2, proc.stderr)
        self._assert_stdout_state(proc, "COLLISION")
        self.assertIn("mobile/src/app/AppShell.tsx", _collision_paths(proc.stdout))
        self.assertEqual(target.read_bytes(), edited)
        self.assertEqual(_snapshot(self.root / "mobile"), before)

    def test_mobile_root_symlink_is_collision(self) -> None:
        with tempfile.TemporaryDirectory() as outside_name:
            outside = Path(outside_name)
            marker = outside / "keep.txt"
            marker.write_text("untouched\n", encoding="utf-8")
            self._symlink(self.root / "mobile", outside, directory=True)
            proc = _run_init(self.root)
            self.assertEqual(proc.returncode, 2, proc.stderr)
            self._assert_stdout_state(proc, "COLLISION")
            self.assertIn("mobile", _collision_paths(proc.stdout))
            self.assertEqual(marker.read_text(encoding="utf-8"), "untouched\n")

    def test_managed_parent_symlink_is_collision_before_reconcile(self) -> None:
        with tempfile.TemporaryDirectory() as outside_name:
            outside = Path(outside_name)
            mobile = self.root / "mobile"
            (mobile / "android").mkdir(parents=True)
            (mobile / "ios").mkdir()
            (mobile / "package.json").write_text(
                json.dumps(
                    {"name": "legacy", "dependencies": {"react-native": "0.86.2"}},
                    indent=2,
                )
                + "\n",
                encoding="utf-8",
            )
            self._symlink(mobile / "src", outside, directory=True)
            proc = _run_init(self.root)
            self.assertEqual(proc.returncode, 2, proc.stderr)
            self._assert_stdout_state(proc, "COLLISION")
            self.assertIn("mobile/src/app/AppShell.tsx", _collision_paths(proc.stdout))
            self.assertEqual(list(outside.iterdir()), [])
            self.assertFalse((mobile / "App.tsx").exists())

    def test_unrelated_mobile_content_is_collision(self) -> None:
        notes = self.root / "mobile" / "notes.txt"
        notes.parent.mkdir()
        original = b"not a react native app\n"
        notes.write_bytes(original)
        proc = _run_init(self.root)
        self.assertEqual(proc.returncode, 2, proc.stderr)
        self._assert_stdout_state(proc, "COLLISION")
        self.assertIn("mobile/notes.txt", _collision_paths(proc.stdout))
        self.assertEqual(notes.read_bytes(), original)
        self.assertFalse((self.root / "mobile" / "package.json").exists())
        self.assertFalse((self.root / "mobile" / "android").exists())

    def test_advanced_content_preserved_mobile_state_independent(self) -> None:
        src = _ensure_greenfield_cache()
        self.assertTrue(src.is_dir(), getattr(_ABSENT_PROC, "stderr", ""))
        _copy_mobile(src, self.root)
        backend = self.root / "backend" / "pom.xml"
        backend.parent.mkdir()
        backend.write_text("<project/>\n", encoding="utf-8")
        infra = self.root / "infra" / "main.tf"
        infra.parent.mkdir()
        infra.write_text("resource \"null_resource\" \"x\" {}\n", encoding="utf-8")
        compose = self.root / "compose.yaml"
        compose.write_text("services: {}\n", encoding="utf-8")
        docs = self.root / "docs" / "keep.md"
        docs.parent.mkdir()
        docs.write_text("untouched\n", encoding="utf-8")
        proc = _run_init(self.root)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self._assert_stdout_state(proc, "INITIALIZED")
        self.assertEqual(backend.read_text(encoding="utf-8"), "<project/>\n")
        self.assertEqual(infra.read_text(encoding="utf-8"), "resource \"null_resource\" \"x\" {}\n")
        self.assertEqual(compose.read_text(encoding="utf-8"), "services: {}\n")
        self.assertEqual(docs.read_text(encoding="utf-8"), "untouched\n")

    def test_other_rn_version_not_downgraded(self) -> None:
        mobile = self.root / "mobile"
        mobile.mkdir()
        pkg = {
            "name": "legacy",
            "dependencies": {"react-native": "0.80.0"},
            "scripts": {},
        }
        (mobile / "package.json").write_text(json.dumps(pkg, indent=2) + "\n", encoding="utf-8")
        (mobile / "android").mkdir()
        (mobile / "ios").mkdir()
        (mobile / "android" / "placeholder.txt").write_text("android\n", encoding="utf-8")
        (mobile / "ios" / "placeholder.txt").write_text("ios\n", encoding="utf-8")
        proc = _run_init(self.root)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(_state_lines(proc.stdout), ["PARTIAL"])
        after = _load_pkg(mobile)
        self.assertEqual(after["dependencies"]["react-native"], "0.80.0")

    def test_different_native_id_preserved(self) -> None:
        mobile = self.root / "mobile"
        (mobile / "android" / "app").mkdir(parents=True)
        (mobile / "ios" / "App.xcodeproj").mkdir(parents=True)
        pkg = {
            "name": "other",
            "dependencies": {"react-native": "0.86.2"},
            "scripts": {},
        }
        (mobile / "package.json").write_text(json.dumps(pkg, indent=2) + "\n", encoding="utf-8")
        (mobile / "app.json").write_text(
            json.dumps({"name": "OtherApp", "displayName": "Other App"}, indent=2) + "\n",
            encoding="utf-8",
        )
        gradle = 'android {\n    defaultConfig {\n        applicationId "com.other.app"\n    }\n}\n'
        (mobile / "android" / "app" / "build.gradle").write_text(gradle, encoding="utf-8")
        pbx = 'PRODUCT_BUNDLE_IDENTIFIER = com.other.app;\n'
        (mobile / "ios" / "App.xcodeproj" / "project.pbxproj").write_text(pbx, encoding="utf-8")
        proc = _run_init(self.root)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertEqual(_state_lines(proc.stdout), ["PARTIAL"])
        self.assertEqual((mobile / "android" / "app" / "build.gradle").read_text(encoding="utf-8"), gradle)
        self.assertEqual((mobile / "ios" / "App.xcodeproj" / "project.pbxproj").read_text(encoding="utf-8"), pbx)
        app_json = json.loads((mobile / "app.json").read_text(encoding="utf-8"))
        self.assertEqual(app_json["displayName"], "Other App")

    def test_no_nested_git(self) -> None:
        src = _ensure_greenfield_cache()
        self.assertTrue(src.is_dir(), getattr(_ABSENT_PROC, "stderr", ""))
        self.assertFalse((src / ".git").exists())
        _copy_mobile(src, self.root)
        proc = _run_init(self.root)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertFalse((self.root / "mobile" / ".git").exists())

    def test_no_destructive_directory_replacement(self) -> None:
        mobile = self.root / "mobile"
        (mobile / "android").mkdir(parents=True)
        (mobile / "ios").mkdir()
        pkg = {
            "name": "kept",
            "dependencies": {"react-native": "0.86.2"},
            "scripts": {"start": "react-native start"},
        }
        (mobile / "package.json").write_text(json.dumps(pkg, indent=2) + "\n", encoding="utf-8")
        marker = mobile / "USER_MARKER.txt"
        marker.write_text("user work\n", encoding="utf-8")
        start_script = json.loads((mobile / "package.json").read_text(encoding="utf-8"))["scripts"]["start"]
        proc = _run_init(self.root)
        self.assertEqual(proc.returncode, 0, proc.stderr)
        self.assertNotEqual(_state_lines(proc.stdout), ["COLLISION"])
        self.assertEqual(marker.read_text(encoding="utf-8"), "user work\n")
        after = _load_pkg(mobile)
        self.assertEqual(after["scripts"]["start"], start_script)
        self.assertEqual(after["dependencies"]["react-native"], "0.86.2")
        self.assertTrue((mobile / "src" / "app" / "AppShell.tsx").is_file())


NAV_PACKAGES_LOCAL = (
    "@react-navigation/native",
    "@react-navigation/native-stack",
    "react-native-screens",
    "react-native-safe-area-context",
)


def _concat_files(root: Path, suffixes: set[str]) -> str:
    chunks: list[str] = []
    if not root.is_dir():
        return ""
    for path in root.rglob("*"):
        if path.is_file() and path.suffix in suffixes and "node_modules" not in path.parts:
            chunks.append(path.read_text(encoding="utf-8", errors="replace"))
    return "\n".join(chunks)


if __name__ == "__main__":
    unittest.main()
