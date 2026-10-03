#!/usr/bin/env python3
"""Non-destructive React Native mobile initializer."""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path


STATE_ABSENT = "ABSENT"
STATE_EMPTY = "EMPTY"
STATE_PARTIAL = "PARTIAL"
STATE_INITIALIZED = "INITIALIZED"
STATE_COLLISION = "COLLISION"

EXIT_OK = 0
EXIT_COLLISION = 2

PROJECT_NAME = "AIFashionVault"
APP_TITLE = "AI Fashion Vault"
PACKAGE_NAME = "com.aifashionvault.app"
RN_VERSION = "0.86.2"
NODE_ENGINES = "^22.13.0 || >=24"

IGNORE_NAMES = {".gitkeep", ".DS_Store"}
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

SHELL_APP = """import AppShell from './src/app/AppShell';

export default AppShell;
"""

SHELL_APP_SHELL = """import { NavigationContainer } from '@react-navigation/native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import RootNavigator from '../navigation/RootNavigator';

const initialMetrics = {
  frame: { x: 0, y: 0, width: 0, height: 0 },
  insets: { top: 0, left: 0, right: 0, bottom: 0 },
};

export default function AppShell() {
  return (
    <SafeAreaProvider initialMetrics={initialMetrics}>
      <NavigationContainer>
        <RootNavigator />
      </NavigationContainer>
    </SafeAreaProvider>
  );
}
"""

SHELL_NAVIGATOR = """import { createNativeStackNavigator } from '@react-navigation/native-stack';
import StartupScreen from '../screens/StartupScreen';

export type RootStackParamList = {
  Startup: undefined;
};

const Stack = createNativeStackNavigator<RootStackParamList>();

export default function RootNavigator() {
  return (
    <Stack.Navigator>
      <Stack.Screen name="Startup" component={StartupScreen} />
    </Stack.Navigator>
  );
}
"""

SHELL_STARTUP = """import { Text, View } from 'react-native';

export default function StartupScreen() {
  return (
    <View>
      <Text>Application ready</Text>
    </View>
  );
}
"""

SHELL_TEST = """import { render, screen } from '@testing-library/react-native';
import App from '../App';

test('renders the startup screen', async () => {
  await render(<App />);
  expect(screen.getByText('Application ready')).toBeTruthy();
});
"""

STOCK_JEST_CONFIG = """module.exports = {
  preset: '@react-native/jest-preset',
};
"""

JEST_CONFIG = """module.exports = {
  preset: '@react-native/jest-preset',
  transformIgnorePatterns: [
    'node_modules/(?!((jest-)?react-native|@react-native(-community)?|@react-navigation|react-native-screens|react-native-safe-area-context)/)',
  ],
};
"""

_JSON_ARRAY_BLOCK = re.compile(r"\[\n(?:[ \t]*[^\[\]{}\n]+\n)+[ \t]*\]")

PRETTIERIGNORE = """android/
ios/
*.kt
*.java
*.swift
*.m
*.mm
*.gradle
*.pbxproj
*.xcscheme
Pods/
"""

OWNED_EXACT = {
    "src/app/AppShell.tsx": SHELL_APP_SHELL,
    "src/navigation/RootNavigator.tsx": SHELL_NAVIGATOR,
    "src/screens/StartupScreen.tsx": SHELL_STARTUP,
}

MANAGED_PATHS = (
    "App.tsx",
    *OWNED_EXACT,
    "__tests__/App.test.tsx",
    ".prettierignore",
    "jest.config.js",
    "package.json",
    "app.json",
    "tsconfig.json",
    ".git",
)

REQUIRED_SCRIPTS = {
    "typecheck": "tsc --noEmit",
    "lint": "eslint .",
    "format:check": "prettier --check .",
    "format-fix": "prettier --write .",
    "test": "jest",
}

NAV_PACKAGES = (
    "@react-navigation/native",
    "@react-navigation/native-stack",
    "react-native-screens",
    "react-native-safe-area-context",
)
RNTL_PACKAGE = "@testing-library/react-native"
RNTL_VERSION = "14.0.1"
RENDERER_PACKAGE = "test-renderer"

APPLICATION_ID_RE = re.compile(r'applicationId\s*(?:=\s*)?["\']([^"\']+)["\']')
BUNDLE_ID_RE = re.compile(r"PRODUCT_BUNDLE_IDENTIFIER\s*=\s*([^;]+);")


def _normalize(data: bytes) -> bytes:
    return data.replace(b"\r\n", b"\n")


def _canonical_bytes(text: str) -> bytes:
    data = text.replace("\r\n", "\n")
    if not data.endswith("\n"):
        data += "\n"
    return data.encode("utf-8")


def _write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(_canonical_bytes(text))


def _rel(root: Path, path: Path) -> str:
    return path.resolve().relative_to(root.resolve()).as_posix()



def _has_symlink_component(root: Path, rel: str) -> bool:
    current = root
    for part in rel.split("/"):
        current /= part
        if current.is_symlink():
            return True
    return False

def _tool(name: str) -> str:
    found = shutil.which(name)
    if found:
        return found
    if os.name == "nt":
        for suffix in (".cmd", ".exe", ".bat"):
            found = shutil.which(name + suffix)
            if found:
                return found
    raise RuntimeError(f"{name} is required on PATH")


def _run(cmd: list[str], cwd: Path, env: dict[str, str] | None = None) -> None:
    proc = subprocess.run(
        cmd,
        cwd=str(cwd),
        capture_output=True,
        text=True,
        env=env,
    )
    if proc.stdout:
        sys.stderr.write(proc.stdout)
        if not proc.stdout.endswith("\n"):
            sys.stderr.write("\n")
    if proc.stderr:
        sys.stderr.write(proc.stderr)
        if not proc.stderr.endswith("\n"):
            sys.stderr.write("\n")
    if proc.returncode != 0:
        raise RuntimeError(f"command failed ({proc.returncode}): {' '.join(cmd)}")


def _load_json(path: Path) -> dict | None:
    if not path.is_file():
        return None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError, UnicodeDecodeError):
        return None
    return data if isinstance(data, dict) else None


def _dump_json(path: Path, data: dict) -> None:
    path.write_bytes((json.dumps(data, indent=2) + "\n").encode("utf-8"))


def _collapse_json_arrays(text: str) -> str:
    def repl(match: re.Match[str]) -> str:
        inner = match.group(0)[1:-1]
        items = [line.strip().rstrip(",") for line in inner.splitlines() if line.strip()]
        return "[" + ", ".join(items) + "]"

    return _JSON_ARRAY_BLOCK.sub(repl, text)


def _dump_json_prettier(path: Path, data: dict) -> None:
    path.write_bytes(_canonical_bytes(_collapse_json_arrays(json.dumps(data, indent=2))))


def _dep_map(pkg: dict) -> dict[str, str]:
    out: dict[str, str] = {}
    for bucket in ("dependencies", "devDependencies", "peerDependencies"):
        section = pkg.get(bucket)
        if isinstance(section, dict):
            for name, version in section.items():
                out[str(name)] = str(version)
    return out


def _has_rn_dep(pkg: dict | None) -> bool:
    return pkg is not None and "react-native" in _dep_map(pkg)


def _has_native_hosts(mobile: Path) -> bool:
    return (mobile / "android").is_dir() and (mobile / "ios").is_dir()


def _recognizable_rn(mobile: Path, pkg: dict | None) -> bool:
    return _has_rn_dep(pkg) or _has_native_hosts(mobile)


def _substantive_files(mobile: Path) -> list[Path]:
    files: list[Path] = []
    if not mobile.is_dir():
        return files
    for path in mobile.rglob("*"):
        if path.is_file() and path.name not in IGNORE_NAMES:
            files.append(path)
    return files


def _is_stock_rtr_test(path: Path) -> bool:
    try:
        text = path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError):
        return False
    return "react-test-renderer" in text and "@testing-library/react-native" not in text


def _is_stock_jest_config(path: Path) -> bool:
    if not path.is_file():
        return False
    try:
        data = path.read_bytes()
    except OSError:
        return False
    return _normalize(data) == _canonical_bytes(STOCK_JEST_CONFIG)


def _identity_mismatch(mobile: Path) -> bool:
    app_json = _load_json(mobile / "app.json")
    if app_json is not None:
        title = app_json.get("displayName")
        if isinstance(title, str) and title != APP_TITLE:
            return True
    app_gradle = mobile / "android" / "app" / "build.gradle"
    app_kts = mobile / "android" / "app" / "build.gradle.kts"
    for path in (app_gradle, app_kts):
        if not path.is_file():
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        for match in APPLICATION_ID_RE.finditer(text):
            if match.group(1) != PACKAGE_NAME:
                return True
    ios = mobile / "ios"
    if ios.is_dir():
        for path in ios.rglob("*.pbxproj"):
            try:
                text = path.read_text(encoding="utf-8")
            except (OSError, UnicodeDecodeError):
                continue
            for match in BUNDLE_ID_RE.finditer(text):
                value = match.group(1).strip().strip('"')
                if not value or value == PACKAGE_NAME or "com.facebook" in value:
                    continue
                if value.endswith("Tests") or value.endswith("UITests"):
                    continue
                if "$(" in value:
                    continue
                return True
    return False


def _owned_collisions(root: Path, mobile: Path) -> list[str]:
    hits: list[str] = []
    for rel, content in OWNED_EXACT.items():
        dest = mobile / rel
        if not dest.exists():
            continue
        if not dest.is_file() or _normalize(dest.read_bytes()) != _canonical_bytes(content):
            hits.append(_rel(root, dest))
    test_path = mobile / "__tests__" / "App.test.tsx"
    if test_path.is_file() and not _is_stock_rtr_test(test_path):
        if _normalize(test_path.read_bytes()) != _canonical_bytes(SHELL_TEST):
            hits.append(_rel(root, test_path))
    return hits


def _complete(mobile: Path, pkg: dict | None) -> bool:
    if not _recognizable_rn(mobile, pkg) or not _has_native_hosts(mobile):
        return False
    required = (
        "App.tsx",
        "src/app/AppShell.tsx",
        "src/navigation/RootNavigator.tsx",
        "src/screens/StartupScreen.tsx",
        "__tests__/App.test.tsx",
        ".prettierignore",
        "package.json",
        "app.json",
        "tsconfig.json",
    )
    if any(not (mobile / rel).is_file() for rel in required):
        return False
    test_path = mobile / "__tests__" / "App.test.tsx"
    if _is_stock_rtr_test(test_path):
        return False
    if pkg is None:
        return False
    scripts = pkg.get("scripts") if isinstance(pkg.get("scripts"), dict) else {}
    if any(name not in scripts for name in REQUIRED_SCRIPTS):
        return False
    deps = _dep_map(pkg)
    if any(name not in deps for name in NAV_PACKAGES):
        return False
    if deps.get(RNTL_PACKAGE) != RNTL_VERSION:
        return False
    tsconfig = _load_json(mobile / "tsconfig.json")
    if tsconfig is None:
        return False
    compiler = tsconfig.get("compilerOptions")
    if not isinstance(compiler, dict):
        return False
    if compiler.get("strict") is not True:
        return False
    conditions = compiler.get("customConditions")
    if not isinstance(conditions, list) or "react-native-strict-api" not in conditions:
        return False
    return True


def _classify(root: Path) -> tuple[str, list[str]]:
    mobile = root / "mobile"
    if mobile.is_symlink():
        return STATE_COLLISION, ["mobile"]
    if not mobile.exists():
        return STATE_ABSENT, []
    if not mobile.is_dir():
        return STATE_COLLISION, ["mobile"]
    collisions = [
        f"mobile/{rel}"
        for rel in MANAGED_PATHS
        if _has_symlink_component(root, f"mobile/{rel}")
    ]
    if collisions:
        return STATE_COLLISION, collisions
    files = _substantive_files(mobile)
    if not files:
        return STATE_EMPTY, []
    pkg = _load_json(mobile / "package.json")
    if not _recognizable_rn(mobile, pkg):
        return STATE_COLLISION, [_rel(root, path) for path in files]
    collisions = _owned_collisions(root, mobile)
    if collisions:
        return STATE_COLLISION, collisions
    if _identity_mismatch(mobile) or not _complete(mobile, pkg):
        return STATE_PARTIAL, []
    return STATE_INITIALIZED, []


def _ensure_scripts(pkg: dict) -> bool:
    scripts = pkg.get("scripts")
    if not isinstance(scripts, dict):
        scripts = {}
        pkg["scripts"] = scripts
    changed = False
    for name, value in REQUIRED_SCRIPTS.items():
        if name not in scripts:
            scripts[name] = value
            changed = True
    return changed


def _ensure_engines(pkg: dict) -> bool:
    engines = pkg.get("engines")
    if not isinstance(engines, dict):
        engines = {}
        pkg["engines"] = engines
    if engines.get("node") == NODE_ENGINES:
        return False
    engines["node"] = NODE_ENGINES
    return True


def _ensure_tsconfig(mobile: Path, *, overwrite_conditions: bool) -> None:
    path = mobile / "tsconfig.json"
    data = _load_json(path) or {}
    compiler = data.get("compilerOptions")
    if not isinstance(compiler, dict):
        compiler = {}
        data["compilerOptions"] = compiler
    changed = False
    if compiler.get("strict") is not True:
        compiler["strict"] = True
        changed = True
    conditions = compiler.get("customConditions")
    if overwrite_conditions or not isinstance(conditions, list) or "react-native-strict-api" not in conditions:
        if conditions != ["react-native-strict-api"]:
            compiler["customConditions"] = ["react-native-strict-api"]
            changed = True
    if changed or not path.is_file():
        _dump_json_prettier(path, data)


def _missing_packages(pkg: dict | None) -> tuple[list[str], list[str], list[str]]:
    deps = _dep_map(pkg or {})
    prod: list[str] = []
    dev: list[str] = []
    exact: list[str] = []
    nav_specs = {
        "@react-navigation/native": "@react-navigation/native@^7",
        "@react-navigation/native-stack": "@react-navigation/native-stack@^7",
        "react-native-screens": "react-native-screens",
        "react-native-safe-area-context": "react-native-safe-area-context",
    }
    for name, spec in nav_specs.items():
        if name not in deps:
            prod.append(spec)
    rntl = deps.get(RNTL_PACKAGE)
    if rntl is None:
        exact.append(f"{RNTL_PACKAGE}@{RNTL_VERSION}")
    if RENDERER_PACKAGE not in deps:
        dev.append(f"{RENDERER_PACKAGE}@^1.0.0")
    return prod, dev, exact


def _npm_install(mobile: Path, packages: list[str], *, dev: bool, exact: bool = False) -> None:
    if not packages:
        return
    cmd = [_tool("npm"), "install"]
    if dev:
        cmd.append("--save-dev")
    if exact:
        cmd.append("--save-exact")
    cmd.extend(packages)
    _run(cmd, mobile)


def _drop_rtr(mobile: Path, pkg: dict | None) -> None:
    deps = _dep_map(pkg or {})
    drop = [name for name in ("react-test-renderer", "@types/react-test-renderer") if name in deps]
    if drop:
        _run([_tool("npm"), "uninstall", *drop], mobile)


def _apply_display_name(mobile: Path, *, overwrite: bool = False) -> None:
    path = mobile / "app.json"
    if not path.is_file():
        data = {"name": PROJECT_NAME, "displayName": APP_TITLE}
    else:
        data = _load_json(path) or {"name": PROJECT_NAME}
        if not overwrite:
            return
    data["displayName"] = APP_TITLE
    _dump_json(path, data)
    strings = mobile / "android" / "app" / "src" / "main" / "res" / "values" / "strings.xml"
    if strings.is_file():
        text = strings.read_text(encoding="utf-8")
        updated = text.replace(f">{PROJECT_NAME}<", f">{APP_TITLE}<")
        if updated != text:
            _write_text(strings, updated)
    ios = mobile / "ios"
    if ios.is_dir():
        display_re = re.compile(
            r"(<key>CFBundleDisplayName</key>\s*<string>)[^<]*(</string>)"
        )
        for path in ios.rglob("Info.plist"):
            if "Pods" in path.parts:
                continue
            try:
                text = path.read_text(encoding="utf-8")
            except (OSError, UnicodeDecodeError):
                continue
            updated = display_re.sub(rf"\g<1>{APP_TITLE}\g<2>", text, count=1)
            if updated != text:
                _write_text(path, updated)
        for path in ios.rglob("LaunchScreen.storyboard"):
            if "Pods" in path.parts:
                continue
            try:
                text = path.read_text(encoding="utf-8")
            except (OSError, UnicodeDecodeError):
                continue
            updated = text.replace(f'text="{PROJECT_NAME}"', f'text="{APP_TITLE}"')
            if updated != text:
                _write_text(path, updated)


def _apply_shell(mobile: Path, *, overwrite: bool) -> None:
    mapping = {
        "App.tsx": SHELL_APP,
        **OWNED_EXACT,
    }
    for rel, content in mapping.items():
        dest = mobile / rel
        if dest.exists() and not overwrite:
            continue
        _write_text(dest, content)
    test_path = mobile / "__tests__" / "App.test.tsx"
    if overwrite or not test_path.exists() or _is_stock_rtr_test(test_path):
        _write_text(test_path, SHELL_TEST)
    ignore = mobile / ".prettierignore"
    if overwrite or not ignore.exists():
        _write_text(ignore, PRETTIERIGNORE)
    jest_path = mobile / "jest.config.js"
    if overwrite or not jest_path.exists() or _is_stock_jest_config(jest_path):
        _write_text(jest_path, JEST_CONFIG)


def _patch_package(mobile: Path, *, greenfield: bool) -> dict | None:
    path = mobile / "package.json"
    pkg = _load_json(path)
    if pkg is None:
        return None
    changed = _ensure_scripts(pkg)
    if greenfield:
        changed = _ensure_engines(pkg) or changed
    if changed:
        _dump_json(path, pkg)
    return _load_json(path)


def _remove_nested_git(mobile: Path) -> None:
    git_dir = mobile / ".git"
    if git_dir.exists():
        if git_dir.is_dir():
            shutil.rmtree(git_dir)
        else:
            git_dir.unlink()


def _scrub_new_root_js(root: Path, preexisting: set[str]) -> None:
    for name in FORBIDDEN_ROOT:
        if name in preexisting:
            continue
        path = root / name
        if path.is_file():
            path.unlink()


def _generate(root: Path) -> None:
    mobile = root / "mobile"
    preexisting = {name for name in FORBIDDEN_ROOT if (root / name).exists()}
    cmd = [
        _tool("npx"),
        "--yes",
        "@react-native-community/cli",
        "init",
        PROJECT_NAME,
        "--version",
        RN_VERSION,
        "--directory",
        str(mobile),
        "--title",
        APP_TITLE,
        "--package-name",
        PACKAGE_NAME,
        "--pm",
        "npm",
        "--skip-git-init",
    ]
    if sys.platform != "darwin":
        cmd.extend(["--install-pods", "false"])
    env = os.environ.copy()
    env["CI"] = "1"
    _run(cmd, root, env=env)
    _apply_shell(mobile, overwrite=True)
    _apply_display_name(mobile, overwrite=True)
    _ensure_tsconfig(mobile, overwrite_conditions=True)
    pkg = _patch_package(mobile, greenfield=True)
    prod, dev, exact = _missing_packages(pkg)
    if pkg is not None and _dep_map(pkg).get(RNTL_PACKAGE) != RNTL_VERSION:
        exact = [f"{RNTL_PACKAGE}@{RNTL_VERSION}"]
    _npm_install(mobile, prod, dev=False)
    _npm_install(mobile, dev, dev=True)
    _npm_install(mobile, exact, dev=True, exact=True)
    _drop_rtr(mobile, _load_json(mobile / "package.json"))
    _patch_package(mobile, greenfield=True)
    _remove_nested_git(mobile)
    _scrub_new_root_js(root, preexisting)


def _reconcile_partial(mobile: Path) -> None:
    _apply_shell(mobile, overwrite=False)
    _apply_display_name(mobile)
    _ensure_tsconfig(mobile, overwrite_conditions=False)
    pkg = _patch_package(mobile, greenfield=False)
    prod, dev, exact = _missing_packages(pkg)
    _npm_install(mobile, prod, dev=False)
    _npm_install(mobile, dev, dev=True)
    _npm_install(mobile, exact, dev=True, exact=True)
    _remove_nested_git(mobile)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Initialize the mobile React Native application.")
    parser.add_argument("--root", default=".", help="Target repository root (default: cwd)")
    args = parser.parse_args(argv)
    root = Path(args.root).expanduser()
    if not root.is_absolute():
        root = Path.cwd() / root
    root = root.resolve()

    if not root.exists():
        root.mkdir(parents=True)

    state, collisions = _classify(root)
    if state == STATE_COLLISION:
        print(f"STATE: {state}")
        for rel in collisions:
            print(f"COLLISION: {rel}")
        return EXIT_COLLISION

    try:
        if state in {STATE_ABSENT, STATE_EMPTY}:
            mobile = root / "mobile"
            if mobile.exists():
                shutil.rmtree(mobile)
            _generate(root)
        elif state == STATE_PARTIAL:
            _reconcile_partial(root / "mobile")
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        return 1

    print(f"STATE: {state}")
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main())
