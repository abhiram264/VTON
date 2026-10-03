#!/usr/bin/env python3
"""Non-destructive Spring Boot backend initializer."""

from __future__ import annotations

import argparse
import io
import json
import os
import re
import shutil
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET

STATE_ABSENT = "ABSENT"
STATE_EMPTY = "EMPTY"
STATE_PARTIAL = "PARTIAL_COMPATIBLE"
STATE_INITIALIZED = "INITIALIZED"
STATE_COLLISION = "COLLISION"

EXIT_OK = 0
EXIT_COLLISION = 2

GROUP_ID = "com.aifashionvault"
ARTIFACT_ID = "backend"
PACKAGE = "com.aifashionvault"
JAVA_VERSION = "21"
BOOT_PIN = "4.1.0"
INITIALIZR = "https://start.spring.io"
DEPS = ("web", "security", "validation", "actuator", "modulith")
IGNORE_NAMES = {".gitkeep", ".DS_Store"}
APPLICATION_YAML = """management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      show-details: never
"""

CONTEXT_TEST = """package com.aifashionvault;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ApplicationContextTest {

	@Test
	void contextLoads() {
	}
}
"""

HEALTH_TEST = """package com.aifashionvault;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ActuatorHealthTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void healthIsReachableAndHealthy() throws Exception {
		mockMvc.perform(get("/actuator/health"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"));
	}
}
"""

MODULITH_TEST = """package com.aifashionvault;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModulithVerificationTest {

	@Test
	void applicationModulesVerify() {
		ApplicationModules.of(BackendApplication.class).verify();
	}
}
"""

SECURITY_TEST = """package com.aifashionvault;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecurityPresenceTest {

	@Test
	void springSecurityIsOnClasspath() {
		assertNotNull(Authentication.class);
	}

	@Test
	void firebaseTypesAreAbsent() {
		assertThrows(ClassNotFoundException.class, () -> Class.forName("com.google.firebase.FirebaseApp"));
		assertThrows(ClassNotFoundException.class, () -> Class.forName("com.google.firebase.auth.FirebaseAuth"));
	}
}
"""

POM_RE_PARENT = re.compile(
    r"<artifactId>\s*spring-boot-starter-parent\s*</artifactId>.*?<version>\s*([^<]+)\s*</version>",
    re.DOTALL,
)
POM_RE_JAVA = re.compile(r"<java\.version>\s*([^<]+)\s*</java\.version>")

BASELINE_TESTS = {
    "ApplicationContextTest.java": CONTEXT_TEST,
    "ActuatorHealthTest.java": HEALTH_TEST,
    "ModulithVerificationTest.java": MODULITH_TEST,
    "SecurityPresenceTest.java": SECURITY_TEST,
}


def _rel(root: Path, path: Path) -> str:
    return path.relative_to(root).as_posix()


def _within(root: Path, path: Path) -> bool:
    try:
        path.resolve().relative_to(root.resolve())
    except (OSError, RuntimeError, ValueError):
        return False
    return True


def _require_contained(root: Path, path: Path) -> None:
    if not _within(root, path):
        raise RuntimeError(f"Refusing to write outside backend: {_rel(root, path)}")


def _write_paths(backend: Path) -> tuple[Path, ...]:
    test_root = backend / "src" / "test" / "java" / "com" / "aifashionvault"
    return (
        backend / "mvnw",
        backend / "mvnw.cmd",
        backend / ".mvn" / "wrapper" / "maven-wrapper.properties",
        backend / "src" / "main" / "resources" / "application.yaml",
        backend / "src" / "main" / "resources" / "application.properties",
        backend / "src" / "main" / "java",
        *(test_root / name for name in BASELINE_TESTS),
    )


def _substantive_files(backend: Path) -> list[Path]:
    files: list[Path] = []
    if not backend.is_dir():
        return files
    for path in backend.rglob("*"):
        if path.is_file() and path.name not in IGNORE_NAMES:
            files.append(path)
    return files


def _read_text(path: Path) -> str | None:
    try:
        return path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError):
        return None


def _pom_text(backend: Path) -> str | None:
    return _read_text(backend / "pom.xml")


def _boot_version(pom: str) -> str | None:
    match = POM_RE_PARENT.search(pom)
    if match:
        return match.group(1).strip()
    return None


def _java_version(pom: str) -> str | None:
    match = POM_RE_JAVA.search(pom)
    return match.group(1).strip() if match else None


def _project_coords(pom: str) -> tuple[str | None, str | None]:
    # First groupId/artifactId after <project> that are not parent
    try:
        root = ET.fromstring(pom)
    except ET.ParseError:
        return None, None
    tag = root.tag
    ns = {"m": tag.split("}")[0].strip("{")} if tag.startswith("{") else {}

    def child(el: ET.Element, name: str) -> str | None:
        if ns:
            node = el.find(f"m:{name}", ns)
        else:
            node = el.find(name)
        return node.text.strip() if node is not None and node.text else None

    group = child(root, "groupId")
    artifact = child(root, "artifactId")
    if group is None:
        parent = root.find("m:parent", ns) if ns else root.find("parent")
        if parent is not None:
            group = child(parent, "groupId") if False else (
                (parent.find("m:groupId", ns) if ns else parent.find("groupId"))
            )
            if group is not None and not isinstance(group, str):
                group = group.text.strip() if group.text else None
    return group, artifact


def _has_gradle(backend: Path) -> bool:
    names = (
        "build.gradle",
        "build.gradle.kts",
        "settings.gradle",
        "settings.gradle.kts",
        "gradlew",
        "gradlew.bat",
    )
    return any((backend / name).exists() for name in names)


def _nested_git(backend: Path) -> bool:
    return (backend / ".git").is_symlink() or (backend / ".git").exists()


def _is_boot4(version: str) -> bool:
    return version.startswith("4.")


def _is_boot3(version: str) -> bool:
    return version.startswith("3.")


def _wrapper_present(backend: Path) -> bool:
    return (
        (backend / "mvnw").is_file()
        and (backend / "mvnw.cmd").is_file()
        and (backend / ".mvn" / "wrapper" / "maven-wrapper.properties").is_file()
    )


def _app_yaml_present(backend: Path) -> bool:
    return (backend / "src" / "main" / "resources" / "application.yaml").is_file()


def _application_class(backend: Path) -> Path | None:
    base = backend / "src" / "main" / "java" / "com" / "aifashionvault"
    if not base.is_dir():
        return None
    for path in base.glob("*.java"):
        text = _read_text(path) or ""
        if "@SpringBootApplication" in text:
            return path
    return None


def _tests_present(backend: Path) -> bool:
    test_root = backend / "src" / "test" / "java" / "com" / "aifashionvault"
    return all((test_root / name).is_file() for name in BASELINE_TESTS)


def _complete(backend: Path) -> bool:
    pom = _pom_text(backend)
    if not pom:
        return False
    boot = _boot_version(pom)
    java = _java_version(pom)
    if not boot or not _is_boot4(boot):
        return False
    if java != JAVA_VERSION:
        return False
    group, artifact = _project_coords(pom)
    if group != GROUP_ID or artifact != ARTIFACT_ID:
        return False
    if not _wrapper_present(backend):
        return False
    if not _app_yaml_present(backend):
        return False
    if _application_class(backend) is None:
        return False
    if not _tests_present(backend):
        return False
    return True


def _recognizable(backend: Path) -> bool:
    pom = _pom_text(backend)
    if not pom:
        return False
    boot = _boot_version(pom)
    return boot is not None and "spring-boot" in pom


def _identity_collision(backend: Path, pom: str) -> bool:
    group, artifact = _project_coords(pom)
    if artifact and artifact != ARTIFACT_ID:
        return True
    if group and group not in {GROUP_ID, "org.springframework.boot"}:
        if group != GROUP_ID:
            return True
    java_src = backend / "src" / "main" / "java"
    if java_src.is_dir():
        for path in java_src.rglob("*.java"):
            text = _read_text(path) or ""
            if "package com.company.fashionvault" in text:
                return True
            if "package " in text and "package com.aifashionvault" not in text:
                if "@SpringBootApplication" in text:
                    return True
    return False


def _classify(root: Path) -> tuple[str, list[str]]:
    backend = root / "backend"
    if backend.is_symlink():
        return STATE_COLLISION, ["backend"]
    if not backend.exists():
        return STATE_ABSENT, []
    if not backend.is_dir():
        return STATE_COLLISION, ["backend"]
    if _nested_git(backend):
        return STATE_COLLISION, [_rel(root, backend / ".git")]
    unsafe_writes = [
        path for path in _write_paths(backend) if not _within(backend, path)
    ]
    if unsafe_writes:
        return STATE_COLLISION, [_rel(root, path) for path in unsafe_writes]
    files = _substantive_files(backend)
    if not files:
        return STATE_EMPTY, []
    collisions: list[str] = []
    if _has_gradle(backend):
        for name in ("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "gradlew"):
            p = backend / name
            if p.exists():
                collisions.append(_rel(root, p))
    pom = _pom_text(backend)
    if pom is None:
        if not collisions:
            return STATE_COLLISION, [_rel(root, path) for path in files]
        return STATE_COLLISION, collisions
    boot = _boot_version(pom)
    java = _java_version(pom)
    if boot and _is_boot3(boot):
        collisions.append(_rel(root, backend / "pom.xml"))
    if java and java != JAVA_VERSION:
        collisions.append(_rel(root, backend / "pom.xml"))
    if _identity_collision(backend, pom):
        collisions.append(_rel(root, backend / "pom.xml"))
    if collisions:
        # unique preserve order
        seen: set[str] = set()
        ordered: list[str] = []
        for item in collisions:
            if item not in seen:
                seen.add(item)
                ordered.append(item)
        return STATE_COLLISION, ordered
    if not _recognizable(backend):
        return STATE_COLLISION, [_rel(root, backend / "pom.xml")]
    if _complete(backend):
        return STATE_INITIALIZED, []
    return STATE_PARTIAL, []


def _http_json(url: str) -> dict:
    req = urllib.request.Request(url, headers={"Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8"))


def _confirm_initializr() -> str:
    meta = _http_json(f"{INITIALIZR}/metadata/client")
    boot_ids = [v["id"] for v in meta["bootVersion"]["values"]]
    exact = [v for v in boot_ids if v == BOOT_PIN or v.startswith(f"{BOOT_PIN}.")]
    boot4 = [v for v in boot_ids if v.startswith("4.")]
    if exact:
        boot_id = exact[0]
    elif boot4:
        boot_id = boot4[0]
        print(
            f"WARNING: Spring Boot {BOOT_PIN} is unavailable; using live Boot 4 version {boot_id}",
            file=sys.stderr,
        )
    else:
        print("CLARIFICATION_REQUIRED:", file=sys.stderr)
        print(f"live boot versions: {boot_ids}", file=sys.stderr)
        raise RuntimeError("Spring Boot 4 is unavailable from start.spring.io")
    dep_ids: list[str] = []
    for group in meta["dependencies"]["values"]:
        for item in group.get("values", []):
            dep_ids.append(item["id"])
    missing = [d for d in DEPS if d not in dep_ids]
    if missing:
        print("CLARIFICATION_REQUIRED:", file=sys.stderr)
        print(f"missing dependency ids: {missing}", file=sys.stderr)
        raise RuntimeError("Required Initializr dependency IDs unavailable")
    return boot_id


def _download_zip(boot_id: str) -> bytes:
    query = urllib.parse.urlencode(
        {
            "type": "maven-project",
            "language": "java",
            "bootVersion": boot_id,
            "baseDir": ARTIFACT_ID,
            "groupId": GROUP_ID,
            "artifactId": ARTIFACT_ID,
            "name": ARTIFACT_ID,
            "packageName": PACKAGE,
            "javaVersion": JAVA_VERSION,
            "dependencies": ",".join(DEPS),
        }
    )
    url = f"{INITIALIZR}/starter.zip?{query}"
    req = urllib.request.Request(url)
    with urllib.request.urlopen(req, timeout=120) as resp:
        return resp.read()


def _extract_zip(data: bytes, dest: Path) -> None:
    dest.mkdir(parents=True, exist_ok=True)
    dest = dest.resolve()
    with zipfile.ZipFile(io.BytesIO(data)) as zf:
        for info in zf.infolist():
            name = info.filename.replace("\\", "/")
            parts = name.split("/")
            if parts and parts[0] == ARTIFACT_ID:
                rel = "/".join(parts[1:])
            else:
                rel = name
            target = (dest / rel).resolve()
            if target != dest and dest not in target.parents:
                raise RuntimeError("Initializr archive contains an unsafe path")
            if not rel or rel.endswith("/"):
                if rel:
                    target.mkdir(parents=True, exist_ok=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            with zf.open(info) as src, open(target, "wb") as out:
                out.write(src.read())
            if rel in {"mvnw"} or rel.endswith("/mvnw"):
                os.chmod(target, 0o755)


def _write_text(backend: Path, path: Path, text: str) -> None:
    _require_contained(backend, path)
    path.parent.mkdir(parents=True, exist_ok=True)
    data = text.replace("\r\n", "\n")
    if not data.endswith("\n"):
        data += "\n"
    path.write_bytes(data.encode("utf-8"))


def _application_simple_name(backend: Path) -> str:
    path = _application_class(backend)
    if path is None:
        return "BackendApplication"
    return path.stem


def _write_baseline_tests(backend: Path) -> None:
    test_root = backend / "src" / "test" / "java" / "com" / "aifashionvault"
    app_name = _application_simple_name(backend)
    mapping = dict(BASELINE_TESTS)
    mapping["ModulithVerificationTest.java"] = MODULITH_TEST.replace(
        "BackendApplication", app_name
    )
    for name, content in mapping.items():
        dest = test_root / name
        if not dest.exists():
            _write_text(backend, dest, content)


def _ensure_yaml(backend: Path) -> None:
    path = backend / "src" / "main" / "resources" / "application.yaml"
    if not path.exists():
        _write_text(backend, path, APPLICATION_YAML)


def _strip_help_controller(backend: Path) -> None:
    src = backend / "src" / "main" / "java"
    _require_contained(backend, src)
    if not src.is_dir():
        return
    for path in src.rglob("*.java"):
        text = _read_text(path) or ""
        if "@RestController" in text or "@Controller" in text:
            path.unlink()


def _generate(root: Path) -> None:
    backend = root / "backend"
    boot_id = _confirm_initializr()
    data = _download_zip(boot_id)
    if backend.exists():
        shutil.rmtree(backend)
    _extract_zip(data, backend)
    _ensure_yaml(backend)
    props = backend / "src" / "main" / "resources" / "application.properties"
    if props.is_file():
        props.unlink()
    _strip_help_controller(backend)
    _write_baseline_tests(backend)


def _copy_missing_wrapper(backend: Path, generated: Path) -> None:
    for rel in ("mvnw", "mvnw.cmd"):
        dest = backend / rel
        src = generated / rel
        if not dest.exists() and src.is_file():
            _require_contained(backend, dest)
            shutil.copy2(src, dest)
            if rel == "mvnw":
                os.chmod(dest, 0o755)
    src_wrap = generated / ".mvn" / "wrapper"
    dest_wrap = backend / ".mvn" / "wrapper"
    src_properties = src_wrap / "maven-wrapper.properties"
    dest_properties = dest_wrap / "maven-wrapper.properties"
    if src_properties.is_file() and not dest_properties.exists():
        _require_contained(backend, dest_properties)
        dest_wrap.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src_properties, dest_properties)


def _reconcile_partial(backend: Path) -> None:
    _ensure_yaml(backend)
    _write_baseline_tests(backend)
    if not _wrapper_present(backend):
        boot_id = _confirm_initializr()
        data = _download_zip(boot_id)
        with tempfile.TemporaryDirectory() as tmp:
            gen = Path(tmp) / "gen"
            _extract_zip(data, gen)
            _copy_missing_wrapper(backend, gen)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Initialize the Spring Boot backend application.")
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
            _generate(root)
        elif state == STATE_PARTIAL:
            _reconcile_partial(root / "backend")
    except urllib.error.URLError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    except RuntimeError as exc:
        print(str(exc), file=sys.stderr)
        return 1

    print(f"STATE: {state}")
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main())
