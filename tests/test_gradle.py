from __future__ import annotations

import os
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BUILD_GRADLE = ROOT / "build.gradle"


def _gradle_command() -> list[str]:
    wrapper = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    tasks = ["--no-daemon", "test", "checkstyleMain", "checkstyleTest"]
    if os.name == "nt":
        return ["cmd", "/d", "/c", str(wrapper), *tasks]
    return [str(wrapper), *tasks]


def test_java_tests_and_quality_checks() -> None:
    result = subprocess.run(
        _gradle_command(),
        cwd=ROOT,
        check=False,
        text=True,
        capture_output=True,
        encoding="utf-8",
        errors="replace",
    )

    output = "\n".join(
        part for part in (result.stdout, result.stderr) if part is not None
    )
    assert result.returncode == 0, output or "Gradle produced no captured output"


def test_sources_jar_is_not_loadable_as_a_fabric_mod() -> None:
    build = BUILD_GRADLE.read_text(encoding="utf-8")
    assert "tasks.named('sourcesJar')" in build
    assert "exclude 'fabric.mod.json'" in build
