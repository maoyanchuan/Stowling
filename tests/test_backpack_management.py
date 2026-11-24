import json
import shutil
import subprocess
from pathlib import Path
from typing import Any

import pytest


WEB = Path(__file__).resolve().parents[1] / "src/main/resources/stowling-web"
NODE = shutil.which("node")


def run_model(expression: str) -> Any:
    if NODE is None:
        pytest.skip("Node.js is required")
    script = r"""
const fs = require('node:fs');
const vm = require('node:vm');
globalThis.window = {};
vm.runInThisContext(fs.readFileSync(process.argv[1], 'utf8'));
const model = window.StowlingBackpackModel;
const item = {key: 'minecraft:diamond_pickaxe#exact', count: 5, taskCount: 2};
const snapshot = {revision: '42', available: true, busy: false};
process.stdout.write(JSON.stringify(eval(process.argv[2])));
"""
    result = subprocess.run(
        [NODE, "-e", script, str(WEB / "backpack-model.js"), expression],
        check=True,
        capture_output=True,
        encoding="utf-8",
    )
    return json.loads(result.stdout)


def test_locking_an_empty_position_preserves_index_and_revision() -> None:
    assert run_model("model.lockChange(snapshot, 8, true)") == {
        "revision": "42",
        "slotIndex": 8,
        "locked": True,
    }
    assert run_model("model.lockChange(snapshot, 8, false)")["locked"] is False


def test_slot_order_places_quickbar_below_the_three_inventory_rows() -> None:
    assert run_model("model.slotOrder()") == list(range(9, 36)) + list(range(9))


def test_tooltip_keeps_item_details_and_describes_the_slot_lock() -> None:
    result = run_model(
        "model.slotDetails("
        "{count: 1, tooltip: ['经验修补'], maxDurability: 100, remainingDurability: 70}, true)"
    )
    assert result == ["经验修补", "耐久 70 / 100", "栏位已锁定 · 自动搬运跳过此格"]
    assert run_model("model.slotDetails({tooltip: [], count: 0}, true)") == [
        "栏位已锁定 · 自动搬运跳过此格"
    ]


@pytest.mark.parametrize(
    "expression",
    [
        "model.lockChange({...snapshot, busy: true}, 0, true)",
        "model.lockChange({...snapshot, available: false}, 0, true)",
        "model.lockChange(snapshot, 36, true)",
        "model.lockChange(snapshot, -1, true)",
        "model.lockChange(snapshot, 0.5, true)",
        "model.lockChange(snapshot, 0, 'true')",
    ],
)
def test_invalid_slot_locks_are_rejected(expression: str) -> None:
    result = run_model(
        f"(() => {{try {{ {expression}; return false; }} catch (error) {{return true;}}}})()"
    )
    assert result is True


def test_backpack_controls_are_available_from_the_main_navigation() -> None:
    navigation = (WEB / "common.js").read_text(encoding="utf-8")
    page = (WEB / "backpack.html").read_text(encoding="utf-8")
    assert 'href: "/backpack.html", label: "背包管理"' in navigation
    assert 'id="backpack-lock"' in page
    assert 'id="backpack-unlock"' in page


def test_empty_slots_remain_selectable_and_keyboard_navigation_keeps_position() -> None:
    if NODE is None:
        pytest.skip("Node.js is required")
    script = r"""
const fs = require('node:fs');
const vm = require('node:vm');
class Element {
    constructor() {
        this.children = []; this.events = {}; this.attributes = {}; this.style = {};
        this.hidden = false; this.textContent = ''; this.id = '';
    }
    append(...children) { this.children.push(...children); }
    replaceChildren(...children) { this.children = children; }
    addEventListener(name, callback) { this.events[name] = callback; }
    setAttribute(name, value) { this.attributes[name] = value; }
    removeAttribute(name) { delete this.attributes[name]; }
    getAttribute(name) { return this.attributes[name]; }
    matches() { return false; }
    focus() { globalThis.focused = this; }
    getBoundingClientRect() { return {left: 0, top: 0, bottom: 0}; }
}
const elements = new Map();
const requests = [];
const snapshot = {revision: '1', available: true, busy: false,
    classificationAvailable: true, items: [], lockedSlots: [8],
    slots: Array.from({length: 36}, (_, index) => ({index, count: 0, key: '', tooltip: []}))};
globalThis.document = {
    querySelector(id) {
        if (!elements.has(id)) { const value = new Element(); value.id = id; elements.set(id, value); }
        return elements.get(id);
    },
    addEventListener() {}, visibilityState: 'visible'
};
let connected;
globalThis.window = {
    addEventListener() {}, setInterval() {}, Stowling: {
        element(tag, style, text) { const value = new Element(); value.textContent = text; return value; },
        isConnected() { return true; }, mountPage() {},
        onConnected(callback) { connected = callback; }, itemIcon() { return Promise.resolve(''); },
        async request(path, options) {
            if (options) requests.push(JSON.parse(options.body));
            return structuredClone(snapshot);
        }
    }
};
vm.runInThisContext(fs.readFileSync(process.argv[1], 'utf8'));
vm.runInThisContext(fs.readFileSync(process.argv[2], 'utf8'));
(async () => {
    connected(); await Promise.resolve(); await Promise.resolve();
    const squares = elements.get('#backpack-items').children;
    const buttons = squares.map(square => square.children[0]);
    buttons[0].events.click();
    const title = elements.get('#backpack-item-name').textContent;
    buttons[0].events.keydown({key: 'ArrowDown', preventDefault() {}});
    elements.get('#backpack-lock').events.click();
    await Promise.resolve(); await Promise.resolve();
    process.stdout.write(JSON.stringify({
        visible: buttons.every(button => !button.hidden), count: buttons.length, title,
        focusedNextRow: globalThis.focused === buttons[9],
        lockedEmptyBadge: !buttons[35].children[3].hidden, request: requests[0]
    }));
})();
"""
    result = subprocess.run(
        [NODE, "-e", script, str(WEB / "backpack-model.js"), str(WEB / "backpack.js")],
        check=True,
        capture_output=True,
        encoding="utf-8",
    )
    value = json.loads(result.stdout)
    assert value["count"] == 36
    assert value["visible"] is True
    assert value["lockedEmptyBadge"] is True
    assert value["focusedNextRow"] is True
    assert value["title"] == "栏位 10 · 空栏位"
    assert value["request"] == {"revision": "1", "slotIndex": 9, "locked": True}
