from __future__ import annotations

import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent
MAIN_JAVA = ROOT / "src" / "main" / "java"
TEST_JAVA = ROOT / "src" / "test" / "java"
RESOURCES = ROOT / "src" / "main" / "resources"


def fail(errors: list[str], message: str) -> None:
    errors.append(message)
    print(f"ERROR: {message}")


def check_conflict_markers(errors: list[str], paths: list[Path]) -> None:
    marker_re = re.compile(r"(?m)^(?:<<<<<<< .+|=======|>>>>>>> .+)\s*$")
    for path in paths:
        text = path.read_text(encoding="utf-8")
        match = marker_re.search(text)
        if match:
            fail(errors, f"merge-conflict marker {match.group(0)!r} found in {path.relative_to(ROOT)}")


def check_project_version(errors: list[str]) -> str:
    pom = ET.parse(ROOT / "pom.xml").getroot()
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    version = (pom.findtext("m:version", namespaces=ns) or "").strip()
    if not version:
        fail(errors, "pom.xml has no project version")
        return ""

    config = (RESOURCES / "config.yml").read_text(encoding="utf-8")
    match = re.search(r"SERVER ASSISTANT\s+([^\s]+)\s+-\s+RUNTIME", config, re.IGNORECASE)
    if not match:
        fail(errors, "config.yml version header was not found")
    elif match.group(1) != version:
        fail(errors, f"version mismatch: pom.xml={version}, config.yml header={match.group(1)}")

    plugin_yml = (RESOURCES / "plugin.yml").read_text(encoding="utf-8")
    if not re.search(r"(?m)^\s*version:\s*['\"]?\$\{project\.version\}['\"]?\s*$", plugin_yml):
        fail(errors, "plugin.yml must inherit version from ${project.version}")

    return version


def check_duplicate_test_methods(errors: list[str], tests: list[Path]) -> int:
    total = 0
    # @Test can have annotations/whitespace before the method. We intentionally keep
    # this check simple and strict for the project's package-private JUnit style.
    pattern = re.compile(
        r"@Test\s+(?:@[\w.()\"', =-]+\s+)*"
        r"(?:public\s+|protected\s+|private\s+)?(?:static\s+)?"
        r"(?:void|[A-Za-z_$][\w$<>?, .\[\]]*)\s+([A-Za-z_$][\w$]*)\s*\(",
        re.MULTILINE,
    )
    for path in tests:
        text = path.read_text(encoding="utf-8")
        names = pattern.findall(text)
        total += len(names)
        duplicates = [name for name, count in Counter(names).items() if count > 1]
        for name in duplicates:
            fail(errors, f"duplicate @Test method {name} in {path.relative_to(ROOT)}")
    return total


def _balanced_call(text: str, start: int) -> tuple[str, int] | None:
    depth = 1
    i = start
    in_string = False
    escaped = False
    while i < len(text):
        ch = text[i]
        if in_string:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                in_string = False
        else:
            if ch == '"':
                in_string = True
            elif ch == '(':
                depth += 1
            elif ch == ')':
                depth -= 1
                if depth == 0:
                    return text[start:i], i + 1
        i += 1
    return None


def check_set_of_duplicates(errors: list[str], java_files: list[Path]) -> None:
    string_re = re.compile(r'"((?:\\.|[^"\\])*)"')
    for path in java_files:
        text = path.read_text(encoding="utf-8")
        cursor = 0
        while True:
            pos = text.find("Set.of(", cursor)
            if pos < 0:
                break
            parsed = _balanced_call(text, pos + len("Set.of("))
            if parsed is None:
                fail(errors, f"unterminated Set.of(...) in {path.relative_to(ROOT)}")
                break
            body, end = parsed
            values = [bytes(v, "utf-8").decode("unicode_escape") for v in string_re.findall(body)]
            duplicates = [value for value, count in Counter(values).items() if count > 1]
            if duplicates:
                line = text.count("\n", 0, pos) + 1
                fail(errors, f"duplicate literal(s) in Set.of at {path.relative_to(ROOT)}:{line}: {duplicates}")
            cursor = end


def check_exact_duplicate_method_signatures(errors: list[str], java_files: list[Path]) -> None:
    # Catches accidental copy/paste duplicates such as the previous
    # commonPrefixLength(...) and hasSubjectlessWikiFactIntent(...). Overloads remain valid
    # because the normalized parameter-type list is part of the signature.
    method_re = re.compile(
        r"(?m)^\s*(?:public|protected|private)\s+"
        r"(?:static\s+)?(?:final\s+)?(?:synchronized\s+)?"
        r"[\w$.<>?, \[\]]+\s+([A-Za-z_$][\w$]*)\s*"
        r"\(([^;{}()]*)\)\s*(?:throws\s+[^\{]+)?\{"
    )
    for path in java_files:
        text = path.read_text(encoding="utf-8")
        signatures: list[str] = []
        for name, params in method_re.findall(text):
            types: list[str] = []
            for raw_param in [p.strip() for p in params.split(",") if p.strip()]:
                # Remove annotations/final and the variable name; this is sufficient
                # for the project's non-generic-comma method parameters.
                cleaned = re.sub(r"@[\w$.]+(?:\([^)]*\))?\s*", "", raw_param)
                cleaned = re.sub(r"\bfinal\s+", "", cleaned).strip()
                parts = cleaned.split()
                if len(parts) >= 2:
                    types.append(" ".join(parts[:-1]).replace(" ", ""))
                else:
                    types.append(cleaned.replace(" ", ""))
            signatures.append(f"{name}({','.join(types)})")
        for signature, count in Counter(signatures).items():
            if count > 1:
                fail(errors, f"duplicate exact method signature {signature} in {path.relative_to(ROOT)}")


def check_obvious_secret(errors: list[str], paths: list[Path]) -> None:
    secret_re = re.compile(r"\bsk-[A-Za-z0-9_-]{20,}\b")
    for path in paths:
        text = path.read_text(encoding="utf-8", errors="replace")
        if secret_re.search(text):
            fail(errors, f"possible OpenAI secret committed in {path.relative_to(ROOT)}")


def main() -> int:
    errors: list[str] = []
    main_java = sorted(MAIN_JAVA.rglob("*.java"))
    tests = sorted(TEST_JAVA.rglob("*.java"))
    tracked_text = main_java + tests + sorted(RESOURCES.glob("*.yml")) + [
        ROOT / "pom.xml", ROOT / "README.md", ROOT / "CHANGELOG.md"
    ]

    if not main_java:
        fail(errors, "no main Java sources found")
    if not tests:
        fail(errors, "no Java tests found")

    check_conflict_markers(errors, tracked_text)
    version = check_project_version(errors)
    check_set_of_duplicates(errors, main_java + tests)
    check_exact_duplicate_method_signatures(errors, main_java + tests)
    total_tests = check_duplicate_test_methods(errors, tests)
    check_obvious_secret(errors, tracked_text)

    print(f"Source files: {len(main_java)} main / {len(tests)} test")
    print(f"JUnit @Test methods detected: {total_tests}")
    if version:
        print(f"Project version: {version}")

    if errors:
        print(f"Source validation failed with {len(errors)} error(s).")
        return 1
    print("OK: source structure validation passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
