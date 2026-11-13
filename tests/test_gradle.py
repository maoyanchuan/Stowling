from __future__ import annotations

import os
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


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
    )

    assert result.returncode == 0, result.stdout + result.stderr
