from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / "scripts"

pytestmark = pytest.mark.skipif(
    os.name != "nt",
    reason="HeadlessMC supervisor scripts are Windows PowerShell scripts",
)


def _powershell_executable() -> str:
    executable = shutil.which("powershell.exe")
    if executable is None:
        pytest.skip("Windows PowerShell is unavailable")
    return executable


def _run_script(
    script: Path,
    *arguments: str,
    cwd: Path,
) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [
            _powershell_executable(),
            "-NoLogo",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(script),
            *arguments,
        ],
        cwd=cwd,
        check=False,
        text=True,
        capture_output=True,
        encoding="utf-8",
        errors="replace",
    )


def _combined_output(result: subprocess.CompletedProcess[str]) -> str:
    return "\n".join(
        part for part in (result.stdout, result.stderr) if part is not None
    )


def _output_values(result: subprocess.CompletedProcess[str]) -> dict[str, str]:
    assert result.returncode == 0, _combined_output(result)
    return dict(
        line.split("=", 1)
        for line in result.stdout.splitlines()
        if "=" in line
    )


def test_start_dry_run_reports_configuration_without_launching(
    tmp_path: Path,
) -> None:
    executable = tmp_path / "HeadlessMC.exe"
    working_directory = tmp_path / "work"
    log_directory = tmp_path / "logs"

    result = _run_script(
        SCRIPTS / "Start-StowlingHeadless.ps1",
        "-DryRun",
        "-HeadlessMcPath",
        str(executable),
        "-WorkingDirectory",
        str(working_directory),
        "-LogDirectory",
        str(log_directory),
        cwd=tmp_path,
    )

    values = _output_values(result)
    assert values["dryRun"] == "true"
    assert os.path.normcase(values["executable"]) == os.path.normcase(
        str(executable.resolve())
    )
    assert os.path.normcase(values["workingDirectory"]) == os.path.normcase(
        str(working_directory.resolve())
    )
    assert os.path.normcase(values["logDirectory"]) == os.path.normcase(
        str(log_directory.resolve())
    )
    assert values["argumentCount"] == "0"
    assert values["localProxy"] == "false"
    assert not log_directory.exists()


def test_start_status_reports_not_running_without_pid(tmp_path: Path) -> None:
    log_directory = tmp_path / "status-logs"

    result = _run_script(
        SCRIPTS / "Start-StowlingHeadless.ps1",
        "-Status",
        "-LogDirectory",
        str(log_directory),
        cwd=tmp_path,
    )

    assert result.returncode == 0, _combined_output(result)
    assert result.stdout.splitlines() == ["running=false"]


def test_stop_returns_safely_without_pid(tmp_path: Path) -> None:
    log_directory = tmp_path / "stop-logs"

    result = _run_script(
        SCRIPTS / "Stop-StowlingHeadless.ps1",
        "-LogDirectory",
        str(log_directory),
        cwd=tmp_path,
    )

    assert result.returncode == 0, _combined_output(result)
    assert result.stdout.splitlines() == ["running=false"]
