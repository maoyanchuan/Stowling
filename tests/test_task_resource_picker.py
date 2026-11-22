import json
import shutil
import subprocess
from pathlib import Path

import pytest


WEB = Path(__file__).resolve().parents[1] / "src/main/resources/stowling-web"
NODE = shutil.which("node")


@pytest.mark.skipif(NODE is None, reason="Node.js is required")
@pytest.mark.parametrize("multiple", [False, True])
@pytest.mark.parametrize("resource_type,panel", [
    ("coordinate", "coordinate-points"),
    ("category-zone", "category-zones"),
    (None, "regions"),
])
def test_task_picker_has_no_search_and_preserves_resource_choices(
    resource_type: str | None, panel: str, multiple: bool,
) -> None:
    script = r"""
const assert = require("node:assert/strict");
const {type, panel, multiple} = JSON.parse(process.argv[2]);
const created = [];
const siblings = [];
/** @param {string} tag @param {string} css @param {string} text @returns {object} */
function element(tag, css = "", text = "") {
    const node = {tag, className: css, textContent: text, children: [], hidden: false,
        /** @param {...object} nodes @returns {void} */
        append(...nodes) {this.children.push(...nodes);}};
    created.push(node);
    return node;
}
/** @param {string} value @param {boolean} selected @param {boolean} disabled
 * @returns {object} */
function option(value, selected = false, disabled = false) {
    return {value, selected, disabled, textContent: value || "请选择",
        /** @returns {object} */
        cloneNode() {return option(this.value, this.selected, this.disabled);}};
}
const chosen = type ? "chosen" : "region:chosen";
const other = type ? "other" : "warehouse:other";
const ref = type ? type + ":" + chosen : chosen;
const select = {parentElement: {}, isConnected: true, multiple, children: [],
    /** @returns {object[]} */
    get options() {
        return this.children.flatMap(node => node.tag === "optgroup" ? node.children : [node]);
    },
    /** @returns {object[]} */
    get selectedOptions() {return this.options.filter(node => node.selected);},
    /** @returns {string} */
    get value() {return this.selectedOptions[0]?.value || "";},
    /** @returns {null} */
    closest() {return null;},
    /** @param {...object} nodes @returns {void} */
    after(...nodes) {siblings.push(...nodes);},
    /** @param {...object} nodes @returns {void} */
    replaceChildren(...nodes) {this.children = nodes;}
};
globalThis.document = {
    createElement: element,
    /** @returns {null} */
    querySelector() {return null;}
};
globalThis.window = {Stowling: {element,
    /** @returns {Promise<object>} */
    request: async () => ({catalog: {
        folders: [{id: "folder", name: "常用坐标"}], placements: {[ref]: "folder"}
    }})
}};
require(process.argv[1]);
/** @returns {Promise<void>} */
async function exercise() {
    const picker = window.StowlingResourcePicker;
    select.replaceChildren(option(""), option(chosen, true), option(other, multiple));
    picker.group(select, type, panel);
    await picker.load();
    assert.equal(siblings.length, 1);
    const controls = siblings[0];
    assert.equal(controls.hidden, true);
    assert.deepEqual(controls.children.map(node => node.tag), ["a"]);
    assert.deepEqual(select.options.map(node => node.value), ["", chosen, other]);
    assert.deepEqual(select.selectedOptions.map(node => node.value),
        multiple ? [chosen, other] : [chosen]);
    assert.deepEqual(select.children.filter(node => node.tag === "optgroup")
        .map(node => node.label), ["常用坐标", "未分组"]);

    select.replaceChildren(option(""), option(chosen), option(other, true));
    picker.group(select, type, panel);
    await picker.load();
    assert.equal(siblings.length, 1);
    assert.deepEqual(select.selectedOptions.map(node => node.value), [other]);
    assert.deepEqual(select.options.map(node => node.value), ["", chosen, other]);

    select.replaceChildren(option("", true), option("deleted", false, true));
    picker.group(select, type, panel);
    await picker.load();
    assert.equal(controls.hidden, false);
    assert.equal(controls.children[0].textContent, "去坐标页添加");
    assert.equal(controls.children[0].href, "/coordinates.html#" + panel);
    assert.equal(select.options.find(node => node.value === "deleted").disabled, true);

    select.replaceChildren(option(""), option(chosen, true));
    picker.group(select, type, panel);
    await picker.load();
    assert.equal(controls.hidden, true);
    assert.equal(siblings.length, 1);
    assert.equal(created.some(node => node.tag === "input" || node.tag === "button"), false);
}
exercise().catch(error => {console.error(error); process.exitCode = 1;});
"""
    subprocess.run(
        [str(NODE), "-e", script, str(WEB / "resource-picker.js"), json.dumps({
            "type": resource_type, "panel": panel, "multiple": multiple,
        })],
        check=True,
        capture_output=True,
        encoding="utf-8",
    )
