import json
import shutil
import subprocess
from pathlib import Path
from typing import Any

import pytest

from test_coordinate_binding_options_editor import editor_page
from test_coordinate_scan_editor import scan_mode


WEB = Path(__file__).resolve().parents[1] / "src/main/resources/stowling-web"
NODE = shutil.which("node")
ENTRY = {
    "formatVersion": 1,
    "summary": {
        "id": "scan-a", "operation": "PREVIEW", "state": "PARTIAL",
        "finishedAt": "2026-11-28T10:00:00Z", "elapsedMillis": 18,
        "message": "9 / 10 片有效",
        "area": {
            "firstX": 220, "firstY": -25, "firstZ": -74,
            "secondX": 225, "secondY": -22, "secondZ": -65,
        },
    },
    "versions": {"stowling": "0.1.0-alpha.343"},
    "world": {
        "blocks": [{
            "position": {"x": 222, "y": -24, "z": -73},
            "blockId": "minecraft:stripped_warped_hyphae", "facing": "north",
        }],
        "markers": [{
            "position": {"x": 222, "y": -23, "z": -74},
            "itemId": "minecraft:oak_pressure_plate", "source": "PLACED_ITEM",
        }],
        "attachments": [{
            "position": {"x": 222, "y": -23, "z": -74},
            "anchor": {"x": 222, "y": -24, "z": -74},
        }],
    },
    "stages": [{
        "stage": "MARKER_CANDIDATES",
        "data": {
            "group": 1, "ambiguous": True,
            "selected": [{"anchor": {"x": 222, "y": -24, "z": -74}}],
            "choices": [{
                "anchor": {"x": 222, "y": -24, "z": -74},
                "score": 999999, "slices": [0, 1],
            }],
        },
    }, {
        "stage": "SLICE_RESULTS", "data": [{
            "index": 2, "origin": {"x": 222, "y": -24, "z": -72},
            "reason": "标记同时落入多个组 <img src=x onerror=alert(1)>",
        }],
    }],
    "result": {"bindings": []},
}


@pytest.mark.skipif(NODE is None, reason="Node.js is required")
def test_formatters_keep_real_ids_and_filter_physical_positions() -> None:
    script = """
        globalThis.window = {};
        require(process.argv[1]);
        const model = window.StowlingScanDiagnostics;
        const entry = JSON.parse(process.argv[2]);
        const file = model.output(entry);
        process.stdout.write(JSON.stringify({file, stages: model.stages(entry),
            rows: model.worldRows(entry, '222 -23 -74'),
            label: model.label(entry.summary)}));
    """
    completed = subprocess.run(
        [str(NODE), "-e", script, str(WEB / "scan-diagnostics.js"), json.dumps(ENTRY)],
        check=True, capture_output=True, encoding="utf-8",
    )
    result = json.loads(completed.stdout)
    assert json.loads(result["file"]["text"]) == ENTRY
    assert "alpha.343" in result["file"]["filename"]
    assert result["file"]["filename"].endswith(".json")
    assert "归属有歧义" in result["stages"][0]["title"]
    assert "(222, -24, -74)" in result["stages"][0]["text"]
    assert "undefined" not in result["stages"][0]["text"]
    assert len(result["rows"]) == 2
    assert "minecraft:oak_pressure_plate" in result["rows"][0]
    assert "部分跳过" in result["label"]


@pytest.mark.parametrize("width,theme", [(1440, "green"), (390, "gray")])
def test_scan_log_displays_filters_exports_and_preserves_failure_text(
    editor_page: Any, width: int, theme: str,
) -> None:
    page = editor_page
    page.set_viewport_size({"width": width, "height": 1100})
    page.evaluate("theme => document.documentElement.dataset.theme = theme", theme)
    page.evaluate("entry => {" + """
        const original = window.Stowling.request;
        window.Stowling.element = (tag, className = '', text = '') => {
            const node = document.createElement(tag);
            node.className = className;
            node.textContent = text;
            return node;
        };
        window.Stowling.request = async (path, options) => {
            if (path.endsWith('/scan-diagnostics')) {
                return {session: 'session-a', capacity: 3, scans: [entry.summary]};
            }
            if (path.endsWith('/scan-diagnostics/scan-a')) return entry;
            return original(path, options);
        };
    }""", ENTRY)
    scan_mode(page)
    page.locator("#scan-log-toggle").click()
    page.wait_for_function("document.querySelector('#scan-log-detail').textContent"
                           ".includes('标记同时落入多个组')")
    assert page.locator("#scan-log-history button").count() == 1
    assert page.locator("#scan-log-detail img").count() == 0
    page.locator("#scan-log-filter").fill("222 -23 -74")
    assert "minecraft:oak_pressure_plate" in page.locator(".scan-log-world-rows").inner_text()
    assert "stripped_warped_hyphae" not in page.locator(".scan-log-world-rows").inner_text()
    with page.expect_download() as download_info:
        page.locator("#scan-log-export").click()
    download = download_info.value
    assert "stowling-scan-log-scan-a" in download.suggested_filename
    assert json.loads(Path(download.path()).read_text(encoding="utf-8")) == ENTRY
    page.locator("#scan-log-toggle").click()
    assert page.locator("#scan-log-content").is_hidden()
    assert page.locator("#scan-log-content").evaluate("element => element.inert")


def test_expired_record_displays_server_error_instead_of_exporting_another_scan(
    editor_page: Any,
) -> None:
    page = editor_page
    page.evaluate("entry => {" + """
        window.Stowling.element = (tag, className = '', text = '') => {
            const node = document.createElement(tag);
            node.className = className;
            node.textContent = text;
            return node;
        };
        window.Stowling.request = async path => {
            if (path.endsWith('/scan-diagnostics')) {
                return {session: 'session-a', scans: [entry.summary]};
            }
            throw new Error('扫描日志不存在或已轮换，请重新扫描后导出');
        };
    }""", ENTRY)
    scan_mode(page)
    page.locator("#scan-log-toggle").click()
    page.wait_for_function("document.querySelector('#scan-log-message').textContent"
                           ".includes('已轮换')")
    assert page.locator("#scan-log-detail").inner_text() == ""
