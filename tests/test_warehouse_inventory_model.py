import json
import shutil
import subprocess
from pathlib import Path
from typing import Any

import pytest


WEB = Path(__file__).resolve().parents[1] / "src/main/resources/stowling-web"
NODE = shutil.which("node")
pytestmark = pytest.mark.skipif(NODE is None, reason="Node.js is required")
FIXTURES = r"""
require(process.argv[1]);
const model = globalThis.StowlingWarehouseModel;
const first = {id: 'chest', name: '箱子', dimension: 'minecraft:overworld',
    positions: [{x: 10,y: 64,z: 10}, {x: 11,y: 64,z: 10}],
    items: [{key: 'stone', itemId: 'minecraft:stone', name: '石头', loose: 64, boxed: 1728,
        icon: 'stowling:custom_stone'},
        {key: 'stone#named', itemId: 'minecraft:stone', name: '自定义石头', loose: 1, boxed: 0}]};
const second = {id: 'barrel', name: '木桶', dimension: 'minecraft:overworld',
    positions: [{x: 30,y: 64,z: 10}],
    items: [{key: 'stone', itemId: 'minecraft:stone', name: '石头', loose: 32, boxed: 0}]};
const nether = {...first, id: 'nether', dimension: 'minecraft:the_nether'};
const snapshot = {state: 'READY', containers: [first, first, second, nether], areas: [{id: 'region',
    dimension: 'minecraft:overworld', bounds: [{firstX: 12, firstY: 65, firstZ: 11,
        secondX: 11, secondY: 63, secondZ: 9}]}]};
"""
MODEL_RUNNER = FIXTURES + r"""
process.stdout.write(JSON.stringify(eval(process.argv[2])));
"""
UI_RUNNER = FIXTURES + r"""
class Element {
    /** @param {string} text */
    constructor(text = "") {
        this.textContent = text;
        this.value = "";
        this.children = [];
        this.listeners = {};
        this.classList = new Set();
        this.classList.remove = this.classList.delete.bind(this.classList);
    }

    /** @param {...Element} nodes @returns {void} */
    append(...nodes) { this.children.push(...nodes); }
    /** @param {...Element} nodes @returns {void} */
    replaceChildren(...nodes) { this.children = nodes; }
    /** @param {string} name @param {string} value @returns {void} */
    setAttribute(name, value) { this[name] = value; }
    /** @param {string} type @param {Function} callback @returns {void} */
    addEventListener(type, callback) { this.listeners[type] = callback; }
}

const controls = new Map();
const models = [];
const png = "data:image/png;base64,test";
globalThis.Option = Element;
globalThis.document = {
    /** @returns {Element} */
    createElement() { return new Element(); },
    /** @param {string} selector @returns {Element} */
    querySelector(selector) {
        if (!controls.has(selector)) controls.set(selector, new Element());
        return controls.get(selector);
    }
};
globalThis.window = {
    /** @returns {void} */
    setInterval() {},
    Stowling: {
        /** @param {string} tag @param {string} className @param {string} text @returns {Element} */
        element(tag, className, text) { return new Element(text); },
        /** @returns {boolean} */
        isConnected() { return true; },
        /** @returns {Promise<object>} */
        async request() { return snapshot; },
        /** @param {string} model @returns {Promise<string>} */
        async itemIcon(model) {
            models.push(model);
            return model === "stowling:custom_stone" ? png : "";
        },
        /** @returns {string} */
        formatDate() { return ""; },
        /** @param {Function} callback @returns {void} */
        onConnected(callback) { callback(); },
        /** @returns {void} */
        mountPage() {}
    }
};
require(process.argv[2]);
setImmediate(() => {
    const list = controls.get("#inventory-list");
    const [stone, named] = list.children;
    const [image, hiddenFallback] = stone.children[0].children[0].children[0].children;
    const [missingImage, fallback] = named.children[0].children[0].children[0].children;
    const locations = stone.children[1].children.map(row => row.children[1].textContent);
    const requests = models.slice();
    const area = controls.get("#inventory-area");
    area.value = "region";
    area.listeners.change();
    const scopedLocations = list.children[0].children[1].children;
    process.stdout.write(JSON.stringify({
        requests, image: image.src, imageHidden: image.hidden, hiddenFallback: hiddenFallback.hidden,
        missingImageHidden: missingImage.hidden, fallback: fallback.textContent,
        locations, scopedLocations: scopedLocations.map(row => row.children[1].textContent)
    }));
});
"""


def evaluate(expression: str) -> Any:
    result = subprocess.run(
        [str(NODE), "-e", MODEL_RUNNER, str(WEB / "warehouse-model.js"), expression],
        check=True, capture_output=True, encoding="utf-8",
    )
    return json.loads(result.stdout)


def test_default_is_entire_bank_and_container_duplicates_are_counted_once() -> None:
    assert evaluate("model.containers(snapshot).map(value => value.id)") == [
        "chest", "barrel", "nether",
    ]


def test_region_uses_linked_chest_half_inclusive_bounds_and_current_dimension() -> None:
    assert evaluate("model.containers(snapshot, {areaId: 'region'}).map(value => value.id)") == [
        "chest",
    ]


def test_counts_combine_loose_and_box_contents_without_merging_different_components() -> None:
    rows = evaluate("model.aggregate(model.containers(snapshot))")
    assert len(rows) == 2
    assert rows[0]["loose"] == 160
    assert rows[0]["boxed"] == 3456
    assert rows[0]["total"] == 3616
    assert len(rows[0]["locations"]) == 3
    assert rows[1]["total"] == 2


def test_item_search_supports_localized_name_and_registry_identifier() -> None:
    assert len(evaluate("model.aggregate([first], '自定义')")) == 1
    assert len(evaluate("model.aggregate([first], 'minecraft:stone')")) == 2


def test_storage_shows_item_icons_and_coordinates_only() -> None:
    assert "inventory-dimension" not in (WEB / "storage.html").read_text(encoding="utf-8")
    result = subprocess.run(
        [str(NODE), "-e", UI_RUNNER, str(WEB / "warehouse-model.js"), str(WEB / "storage.js")],
        check=True, capture_output=True, encoding="utf-8",
    )
    assert json.loads(result.stdout) == {
        "requests": ["stowling:custom_stone", "minecraft:stone"],
        "image": "data:image/png;base64,test",
        "imageHidden": False,
        "hiddenFallback": True,
        "missingImageHidden": True,
        "fallback": "自定",
        "locations": ["10, 64, 10", "30, 64, 10", "10, 64, 10"],
        "scopedLocations": ["10, 64, 10"],
    }
