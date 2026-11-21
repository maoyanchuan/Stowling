import json
import shutil
import subprocess
from pathlib import Path
from typing import Any

import pytest


WEB = Path(__file__).resolve().parents[1] / "src/main/resources/stowling-web"
NODE = shutil.which("node")
pytestmark = pytest.mark.skipif(NODE is None, reason="Node.js is required")
COLORS = (
    "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
    "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black",
)
BOXES = {"minecraft:shulker_box", *(f"minecraft:{color}_shulker_box" for color in COLORS)}
CARTS = {f"minecraft:{cart}" for cart in (
    "minecart", "chest_minecart", "hopper_minecart", "furnace_minecart",
    "tnt_minecart", "command_block_minecart",
)}
MATERIALS = dict.fromkeys(BOXES | CARTS | {"minecraft:stone", "example:red_shulker_box"}, 2)
RUNNER = r"""
const fs = require('node:fs');
const vm = require('node:vm');
class Element {
    /** @param {string} tag @param {string} text */
    constructor(tag = 'div', text = '') {
        this.tagName = tag.toUpperCase(); this.textContent = text;
        this.children = []; this.listeners = {}; this.attributes = {};
        this.value = ''; this.checked = false; this.disabled = false;
    }
    /** @param {...Element} children @returns {void} */
    append(...children) {this.children.push(...children);}
    /** @param {...Element} children @returns {void} */
    replaceChildren(...children) {this.children = children;}
    /** @param {string} key @param {string} value @returns {void} */
    setAttribute(key, value) {this.attributes[key] = value;}
    /** @param {string} type @param {Function} handler @returns {void} */
    addEventListener(type, handler) {this.listeners[type] = handler;}
    /** @param {string} type @returns {void} */
    emit(type) {
        this.listeners[type]?.({target: this, /** @returns {void} */ preventDefault() {}});
    }
    /** @param {Element} option @returns {void} */
    add(option) {this.append(option);}
    /** @returns {Element} */
    querySelector() {return element('fieldset');}
    /** @returns {void} */
    focus() {}
}
const elements = new Map();
/** @param {string} id @returns {Element} */
function element(id) {
    if (!elements.has(id)) elements.set(id, new Element());
    return elements.get(id);
}
globalThis.Option = class extends Element {
    /** @param {string} text @param {string} value */
    constructor(text, value) {super('option', text); this.value = value;}
};
globalThis.CustomEvent = class {
    /** @param {string} type @param {object} options */
    constructor(type, options) {this.type = type; this.detail = options.detail;}
};
globalThis.document = {
    getElementById: element,
    /** @returns {void} */ addEventListener() {},
    /** @returns {void} */ dispatchEvent() {}
};
const overrides = JSON.parse(process.argv[3]);
const parameters = {targetRef: 'region:delivery', boxedDelivery: 'true', ...overrides};
let saved = {id: 'saved', name: '自动收集材料',
    steps: [{id: 'step', moduleId: 'COLLECT_SCHEMATIC_MATERIALS', parameters}]};
const posts = [];
let connected;
globalThis.window = {
    /** @returns {boolean} */ confirm: () => true,
    Stowling: {
        /** @returns {boolean} */ isConnected: () => true,
        /** @param {Function} callback @returns {void} */
        onConnected(callback) {connected = callback;},
        /** @param {string} tag @param {string} css @param {string} text @returns {Element} */
        element: (tag, css, text) => new Element(tag, text),
        /** @returns {void} */ showToast() {},
        /** @param {Error} error @returns {void} */ handleError(error) {throw error;},
        /** @param {string} path @param {object} options @returns {Promise<object>} */
        async request(path, options) {
            if (options) {
                const payload = JSON.parse(options.body);
                posts.push(payload);
                saved = {id: 'saved', ...payload};
            }
            if (path === '/v1/coordinates') return {coordinates: []};
            if (path === '/v1/regions') return {regions: [{id: 'delivery', name: '自动收材料'}]};
            return {revision: 'current', tasks: [saved]};
        }
    },
    StowlingSettings: {
        /** @param {Element} control @returns {Element} */ switchControl: control => control,
        /** @param {Element} body @param {boolean} expanded @returns {Promise<void>} */
        async reveal(body, expanded) {body.hidden = !expanded;},
        /** @param {Element} root @param {Element} summary @param {Element} body
         * @returns {{setExpanded: (value: boolean) => void}} */
        bindDisclosure(root, summary, body) {
            /** @param {boolean} value @returns {void} */
            function setExpanded(value) {root.open = value; body.hidden = !value;}
            setExpanded(false);
            return {setExpanded};
        }
    }
};
/** @returns {Array<string>} */
function preview() {return element('material-preview-items').children.map(row => row.textContent);}
/** @param {string} id @param {boolean} checked @returns {void} */
function toggle(id, checked) {element(id).checked = checked; element(id).emit('change');}
/** @returns {Promise<void>} */
async function save() {
    element('material-task-form').emit('submit');
    await new Promise(resolve => setImmediate(resolve));
}
(async () => {
    vm.runInThisContext(fs.readFileSync(process.argv[1], 'utf8'));
    await connected();
    element('material-task-list').children[0].children[0].children[0].emit('click');
    return await eval(process.argv[2]);
})().then(result => process.stdout.write(JSON.stringify(result))).catch(error => {
    process.stderr.write(error.stack); process.exitCode = 1;
});
"""


def run_ui(expression: str, parameters: dict[str, str] | None = None) -> Any:
    if NODE is None:
        raise RuntimeError("Node.js is unavailable")
    payload = {"materials": json.dumps(MATERIALS), **(parameters or {})}
    completed = subprocess.run(
        [NODE, "-e", RUNNER, str(WEB / "material-collection-ui.js"),
         expression, json.dumps(payload)],
        check=True, capture_output=True, encoding="utf-8",
    )
    return json.loads(completed.stdout)


def test_existing_tasks_keep_both_collections_and_show_saved_delivery_summary() -> None:
    result = run_ui("""({
        boxes: element('material-collect-shulker-boxes').checked,
        carts: element('material-collect-minecarts').checked,
        delivery: element('material-delivery-description').textContent,
        rules: element('material-unstackable-description').textContent,
        closed: ['source', 'delivery', 'unstackable'].every(key =>
            !element(`material-${key}-settings`).open),
        rows: preview()
    })""")
    assert result["boxes"] is True
    assert result["carts"] is True
    assert result["closed"] is True
    assert result["delivery"] == "区域 · 自动收材料 · 混合盒装"
    assert result["rules"] == "全部收集"
    assert set(result["rows"]) == {f"{material} × 2" for material in MATERIALS}


@pytest.mark.parametrize("boxes,carts", [(False, True), (True, False), (False, False)])
def test_switches_filter_every_variant_and_preserve_original_materials_on_save(
    boxes: bool, carts: bool
) -> None:
    result = run_ui(f"""(async () => {{
        toggle('material-collect-shulker-boxes', {str(boxes).lower()});
        toggle('material-collect-minecarts', {str(carts).lower()});
        const rows = preview();
        const count = element('material-source-description').textContent;
        await save();
        return {{rows, count, saved: posts[0].steps[0].parameters}};
    }})()""")
    excluded = (set() if boxes else BOXES) | (set() if carts else CARTS)
    expected = set(MATERIALS) - excluded
    assert set(result["rows"]) == {f"{material} × 2" for material in expected}
    assert result["count"] == f"{len(expected)} 种材料"
    assert result["saved"]["collectShulkerBoxes"] == str(boxes).lower()
    assert result["saved"]["collectMinecarts"] == str(carts).lower()
    assert result["saved"]["boxedDelivery"] == "true"
    assert json.loads(result["saved"]["materials"]) == MATERIALS


def test_saved_switches_restore_and_reenabling_restores_the_preview() -> None:
    result = run_ui("""(() => {
        const before = preview();
        toggle('material-collect-shulker-boxes', true);
        toggle('material-collect-minecarts', true);
        return {before, after: preview(), rules:
            element('material-unstackable-description').textContent};
    })()""", {"collectShulkerBoxes": "false", "collectMinecarts": "false"})
    assert set(result["before"]) == {"minecraft:stone × 2", "example:red_shulker_box × 2"}
    assert set(result["after"]) == {f"{material} × 2" for material in MATERIALS}
    assert result["rules"] == "全部收集"


def test_disabling_all_requested_materials_shows_an_empty_preview_message() -> None:
    result = run_ui("""(() => {
        toggle('material-collect-shulker-boxes', false);
        toggle('material-collect-minecarts', false);
        return {rows: preview(), count: element('material-source-description').textContent};
    })()""", {"materials": json.dumps({"minecraft:shulker_box": 1, "minecraft:minecart": 2})})
    assert result == {"rows": ["按当前设置无需收集材料"], "count": "0 种材料"}
