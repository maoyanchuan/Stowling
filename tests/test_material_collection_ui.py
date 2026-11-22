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
        this.dataset = {}; this.style = {}; this.className = '';
        this.classList = {
            /** @param {...string} names @returns {void} */
            add: (...names) => {this.className += ' ' + names.join(' ');},
            /** @returns {void} */
            toggle() {}
        };
    }
    /** @param {...Element} children @returns {void} */
    append(...children) {
        for (const child of children) {
            child.parentElement = this;
            this.children.push(child);
        }
    }
    /** @param {...Element} children @returns {void} */
    replaceChildren(...children) {this.children = children;}
    /** @param {string} key @param {string} value @returns {void} */
    setAttribute(key, value) {this.attributes[key] = value;}
    /** @param {string} type @param {Function} handler @returns {void} */
    addEventListener(type, handler) {(this.listeners[type] ||= []).push(handler);}
    /** @param {string} type @returns {void} */
    emit(type) {
        for (const handler of this.listeners[type] || []) {
            handler({target: this, /** @returns {void} */ preventDefault() {}});
        }
    }
    /** @param {Element} option @returns {void} */
    add(option) {this.append(option);}
    /** @returns {Element} */
    querySelector() {return element('fieldset');}
    /** @param {string} selector @returns {Array<Element>} */
    querySelectorAll(selector) {
        const tags = selector.split(',').map(tag => tag.trim().toUpperCase());
        return this.children.flatMap(child => [child, ...child.querySelectorAll(selector)])
            .filter(child => tags.includes(child.tagName));
    }
    /** @returns {void} */
    focus() {}
}
const elements = new Map();
/** @param {string} id @returns {Element} */
function element(id) {
    if (!elements.has(id)) {
        const input = ['material-mixed-boxes', 'material-pure-boxes'].includes(id);
        const node = new Element(input ? 'input' : 'div');
        node.id = id;
        elements.set(id, node);
    }
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
let pendingAutosave;
globalThis.window = {
    /** @returns {boolean} */ confirm: () => true,
    /** @returns {void} */ addEventListener() {},
    /** @param {Function} callback @returns {number} */
    setTimeout(callback) { pendingAutosave = callback; return 1; },
    /** @returns {void} */
    clearTimeout() { pendingAutosave = undefined; },
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
    }
};
element('material-boxed-options').append(
    element('material-mixed-boxes'), element('material-pure-boxes'));
/** @returns {Element} */
function boxedHeader() {
    return element('material-boxed-settings').children[0].children[0].children[0];
}
/** @returns {Array<string>} */
function preview() {return element('material-preview-items').children.map(row => row.textContent);}
/** @param {string} id @param {boolean} checked @returns {void} */
function toggle(id, checked) {
    element(id).checked = checked;
    element(id).emit('change');
    element('material-task-form').emit('input');
}
/** @returns {Promise<void>} */
async function waitForAutosave() {
    pendingAutosave();
    await new Promise(resolve => setImmediate(resolve));
}
(async () => {
    vm.runInThisContext(fs.readFileSync(process.argv[4], 'utf8'));
    vm.runInThisContext(fs.readFileSync(process.argv[5], 'utf8'));
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
         expression, json.dumps(payload), str(WEB / "settings-ui.js"),
         str(WEB / "task-autosave.js")],
        check=True, capture_output=True, encoding="utf-8",
    )
    return json.loads(completed.stdout)


def test_existing_tasks_keep_both_collections_and_show_saved_delivery_summary() -> None:
    result = run_ui("""({
        skipBoxes: element('material-skip-shulker-boxes').checked,
        skipCarts: element('material-skip-minecarts').checked,
        delivery: element('material-delivery-description').textContent,
        rules: element('material-unstackable-description').textContent,
        closed: ['source', 'delivery', 'unstackable'].every(key =>
            !element(`material-${key}-settings`).open),
        rows: preview()
    })""")
    assert result["skipBoxes"] is False
    assert result["skipCarts"] is False
    assert result["closed"] is True
    assert result["delivery"] == "区域 · 自动收材料 · 混杂盒交付"
    assert result["rules"] == "全部收集"
    assert set(result["rows"]) == {f"{material} × 2" for material in MATERIALS}


@pytest.mark.parametrize("skip_boxes,skip_carts", [(True, False), (False, True), (True, True)])
def test_switches_filter_every_variant_and_preserve_original_materials_on_save(
    skip_boxes: bool, skip_carts: bool
) -> None:
    result = run_ui(f"""(async () => {{
        toggle('material-skip-shulker-boxes', {str(skip_boxes).lower()});
        toggle('material-skip-minecarts', {str(skip_carts).lower()});
        const rows = preview();
        const count = element('material-source-description').textContent;
        await waitForAutosave();
        return {{rows, count, saved: posts[0].steps[0].parameters}};
    }})()""")
    excluded = (BOXES if skip_boxes else set()) | (CARTS if skip_carts else set())
    expected = set(MATERIALS) - excluded
    assert set(result["rows"]) == {f"{material} × 2" for material in expected}
    assert result["count"] == f"{len(expected)} 种材料"
    assert result["saved"]["collectShulkerBoxes"] == str(not skip_boxes).lower()
    assert result["saved"]["collectMinecarts"] == str(not skip_carts).lower()
    assert result["saved"]["boxedDelivery"] == "true"
    assert json.loads(result["saved"]["materials"]) == MATERIALS


def test_saved_switches_restore_and_reenabling_restores_the_preview() -> None:
    result = run_ui("""(() => {
        const before = preview();
        toggle('material-skip-shulker-boxes', false);
        toggle('material-skip-minecarts', false);
        return {before, after: preview(), rules:
            element('material-unstackable-description').textContent};
    })()""", {"collectShulkerBoxes": "false", "collectMinecarts": "false"})
    assert set(result["before"]) == {"minecraft:stone × 2", "example:red_shulker_box × 2"}
    assert set(result["after"]) == {f"{material} × 2" for material in MATERIALS}
    assert result["rules"] == "全部收集"


def test_disabling_all_requested_materials_shows_an_empty_preview_message() -> None:
    result = run_ui("""(() => {
        toggle('material-skip-shulker-boxes', true);
        toggle('material-skip-minecarts', true);
        return {rows: preview(), count: element('material-source-description').textContent};
    })()""", {"materials": json.dumps({"minecraft:shulker_box": 1, "minecraft:minecart": 2})})
    assert result == {"rows": ["按当前设置无需收集材料"], "count": "0 种材料"}


def test_boxed_submenu_folds_without_changing_the_selected_delivery_mode() -> None:
    result = run_ui("""(async () => {
        const header = boxedHeader();
        const initial = element('material-boxed-options').hidden;
        header.emit('click');
        const expanded = header.attributes['aria-expanded'];
        toggle('material-pure-boxes', true);
        const pure = {mixed: element('material-mixed-boxes').checked,
            pure: element('material-pure-boxes').checked};
        toggle('material-mixed-boxes', true);
        const mixed = {mixed: element('material-mixed-boxes').checked,
            pure: element('material-pure-boxes').checked};
        header.emit('click');
        const folded = {hidden: element('material-boxed-options').hidden,
            enabled: element('material-boxed-delivery').checked};
        await waitForAutosave();
        return {initial, expanded, pure, mixed, folded, saved: posts[0].steps[0].parameters};
    })()""")
    assert result["initial"] is True
    assert result["expanded"] == "true"
    assert result["pure"] == {"mixed": False, "pure": True}
    assert result["mixed"] == {"mixed": True, "pure": False}
    assert result["folded"] == {"hidden": True, "enabled": True}
    assert result["saved"]["boxedDelivery"] == "true"
    assert result["saved"]["singleMaterialBoxes"] == "false"


@pytest.mark.parametrize("single", [False, True])
def test_boxed_switch_preserves_saved_mode_and_disables_both_choices_when_off(single: bool) -> None:
    result = run_ui("""(async () => {
        const restored = {mixed: element('material-mixed-boxes').checked,
            pure: element('material-pure-boxes').checked};
        toggle('material-boxed-delivery', false);
        const off = {hidden: element('material-boxed-options').hidden,
            mixedDisabled: element('material-mixed-boxes').disabled,
            pureDisabled: element('material-pure-boxes').disabled,
            summary: element('material-delivery-description').textContent};
        toggle('material-boxed-delivery', true);
        const on = {hidden: element('material-boxed-options').hidden,
            mixedDisabled: element('material-mixed-boxes').disabled,
            pureDisabled: element('material-pure-boxes').disabled};
        await waitForAutosave();
        return {restored, off, on, saved: posts[0].steps[0].parameters};
    })()""", {"singleMaterialBoxes": str(single).lower()})
    assert result["restored"] == {"mixed": not single, "pure": single}
    assert result["off"] == {
        "hidden": True, "mixedDisabled": True, "pureDisabled": True,
        "summary": "区域 · 自动收材料 · 散装交付",
    }
    assert result["on"] == {"hidden": False, "mixedDisabled": False, "pureDisabled": False}
    assert result["saved"]["boxedDelivery"] == "true"
    assert result["saved"]["singleMaterialBoxes"] == str(single).lower()


def test_all_exclusion_switches_use_the_same_direction_and_keep_water_options_exclusive() -> None:
    materials = {"minecraft:water_bucket": 2, "minecraft:lava_bucket": 1,
                 "minecraft:shulker_box": 3, "minecraft:minecart": 4}
    result = run_ui("""(async () => {
        toggle('material-ice-instead-of-water', true);
        toggle('material-skip-water', true);
        toggle('material-skip-lava', true);
        toggle('material-skip-shulker-boxes', true);
        toggle('material-skip-minecarts', true);
        const excluded = {rows: preview(), ice: element('material-ice-instead-of-water').checked,
            summary: element('material-unstackable-description').textContent};
        toggle('material-ice-instead-of-water', true);
        const ice = {rows: preview(), skipWater: element('material-skip-water').checked};
        await waitForAutosave();
        return {excluded, ice, saved: posts[0].steps[0].parameters};
    })()""", {"materials": json.dumps(materials)})
    assert result["excluded"] == {
        "rows": ["按当前设置无需收集材料"], "ice": False,
        "summary": "不收集水桶 · 不收集岩浆桶 · 不收集潜影盒 · 不收集矿车",
    }
    assert result["ice"] == {"rows": ["minecraft:ice × 2"], "skipWater": False}
    assert result["saved"]["iceInsteadOfWater"] == "true"
    assert result["saved"]["skipWaterBuckets"] == "false"
    assert result["saved"]["collectShulkerBoxes"] == "false"
    assert result["saved"]["collectMinecarts"] == "false"
