import json
import shutil
import subprocess
from pathlib import Path
from typing import Any

import pytest


ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "src/main/resources/stowling-web"
NODE = shutil.which("node")
RUNNER = r"""
const fs = require("node:fs");
const vm = require("node:vm");
const [file, initial, unavailable] = process.argv.slice(1);
const stored = new Map(initial === "absent" ? [] : [["stowling.taskDebugMode", initial]]);
const events = [];
const toggle = {checked: false, listeners: {},
    /** @param {string} type @param {Function} callback @returns {void} */
    addEventListener(type, callback) { this.listeners[type] = callback; }};
globalThis.window = {localStorage: {
    /** @param {string} key @returns {string | null} */
    getItem(key) { if (unavailable === "true") throw Error("blocked");
        return stored.get(key) ?? null; },
    /** @param {string} key @param {string} value @returns {void} */
    setItem(key, value) { if (unavailable === "true") throw Error("blocked");
        stored.set(key, value); }
}};
globalThis.document = {
    /** @returns {object} */
    querySelector() { return toggle; },
    /** @param {object} event @returns {void} */
    dispatchEvent(event) { events.push(event.type); }
};
globalThis.CustomEvent = class {
    /** @param {string} type */
    constructor(type) { this.type = type; }
};
vm.runInThisContext(fs.readFileSync(file, "utf8"));
const states = [window.StowlingTaskDebugMode.enabled()];
for (const enabled of [true, false, true]) {
    toggle.checked = enabled;
    toggle.listeners.change();
    states.push(window.StowlingTaskDebugMode.enabled());
}
process.stdout.write(JSON.stringify({states, events,
    stored: stored.get("stowling.taskDebugMode")}));
"""


@pytest.mark.skipif(NODE is None, reason="Node.js is required")
@pytest.mark.parametrize("initial,expected", [("absent", False), ("false", False),
                                             ("true", True), ("invalid", False)])
@pytest.mark.parametrize("unavailable", [False, True])
def test_debug_mode_defaults_off_and_survives_storage_failure(
    initial: str, expected: bool, unavailable: bool,
) -> None:
    completed = subprocess.run(
        [NODE, "-e", RUNNER, str(WEB / "task-debug-mode.js"), initial,
         str(unavailable).lower()], check=True, capture_output=True, encoding="utf-8",
    )
    result: dict[str, Any] = json.loads(completed.stdout)
    assert result["states"] == [False if unavailable else expected, True, False, True]
    assert result["events"] == ["stowling:task-debug-mode-changed"] * 3
    if not unavailable:
        assert result["stored"] == "true"


def test_task_debug_option_is_hidden_and_preserves_the_saved_value() -> None:
    page = (WEB / "tasks.html").read_text(encoding="utf-8")
    ui = (WEB / "auto-classify-ui.js").read_text(encoding="utf-8")
    template = (ROOT / "docs/ui/task-settings-standard.html").read_text(encoding="utf-8")
    server = (ROOT / "src/main/java/dev/stowling/v2/api/V2ControlServer.java").read_text(
        encoding="utf-8",
    )
    assert page.index('/task-debug-mode.js') < page.index('/auto-classify-ui.js')
    assert 'class="settings-row" hidden inert' in page
    assert 'task-debug-badge' in page and "调试选项" in page
    assert 'const showBatching = !pickup && window.StowlingTaskDebugMode?.enabled()' in ui
    assert 'elements.batchingRow.hidden = !showBatching' in ui
    assert 'elements.batchingRow.inert = !showBatching' in ui
    assert 'elements.batching.checked = draft.sameCategoryBoxBatching' in ui
    assert 'sameCategoryBoxBatching: elements.batching.checked' in ui
    assert '"stowling:task-debug-mode-changed", updateControls' in ui
    assert '"/task-debug-mode.js"' in server
    assert 'id="sample-batching-row" class="sample-row" hidden inert' in template
    assert 'debugMode: false, sameCategoryBoxBatching: true' in template
