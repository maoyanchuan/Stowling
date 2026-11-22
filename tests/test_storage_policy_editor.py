import json
import shutil
import subprocess
from pathlib import Path

import pytest


WEB = Path(__file__).resolve().parents[1] / "src/main/resources/stowling-web"
NODE = shutil.which("node")


@pytest.mark.skipif(NODE is None, reason="Node.js is required")
def test_modes_hide_irrelevant_controls_and_save_exact_slots_and_destination() -> None:
    script = r"""
const fs = require('node:fs');
const vm = require('node:vm');
class Element {
    /** @param {string} tag @param {string} text */
    constructor(tag = '', text = '') {
        this.tagName = tag; this.textContent = text; this.children = [];
        this.listeners = {}; this.attributes = {}; this.value = ''; this.hidden = false;
        this.classList = {/** @returns {void} */ add() {}};
    }
    /** @param {...Element} children @returns {void} */
    append(...children) {
        children.forEach(child => { child.parentElement = this; this.children.push(child); });
    }
    /** @param {string} key @param {string} value @returns {void} */
    setAttribute(key, value) { this.attributes[key] = value; }
    /** @param {string} type @param {Function} callback @returns {void} */
    addEventListener(type, callback) { (this.listeners[type] ||= []).push(callback); }
    /** @param {object} event @returns {void} */
    dispatchEvent(event) {
        (this.listeners[event.type] || []).forEach(callback => callback(event));
        if (event.bubbles && this.parentElement) this.parentElement.dispatchEvent(event);
    }
    /** @returns {Array<Element>} */
    get options() { return this.children; }
    /** @returns {Array<Element>} */
    get selectedOptions() { return this.children.filter(child => child.value === this.value); }
}
globalThis.Option = class extends Element {
    /** @param {string} text @param {string} value */
    constructor(text, value) { super('option', text); this.value = value; }
};
globalThis.Event = class {
    /** @param {string} type @param {object} options */
    constructor(type, options = {}) { this.type = type; this.bubbles = options.bubbles; }
};
globalThis.document = {/** @param {string} tag @returns {Element} */
    createElement(tag) { return new Element(tag); }};
globalThis.window = {
    Stowling: {/** @param {string} tag @param {string} css @param {string} text */
        element(tag, css, text) { return new Element(tag, text); }},
    StowlingSettings: {
        /** @param {Element} control @returns {Element} */
        switchControl(control) { return control; },
        /** @param {string} title @param {string} note @param {Element} content */
        disclosure(title, note, content) {
            const root = new Element('details'); root.open = false; root.append(content);
            return {root, description: new Element(),
                /** @param {boolean} value @returns {void} */
                setExpanded(value) { root.open = value; }};
        }
    }
};
vm.runInThisContext(fs.readFileSync(process.argv[1], 'utf8'));
const editor = window.StowlingStoragePolicyEditor.create(
    {mode:'MIXED', loosePercent:50, allowPartialBoxes:true, refillLoose:true},
    {coordinates:[{id:'box', name:'Partial boxes'}],
        regions:[{id:'partials', name:'Partial region'}]});
const content = editor.root.children[0];
const [modeRow, slotsRow, grid, partialRow, destinationRow, refillRow] = content.children;
const mode = modeRow.children[1];
const slots = slotsRow.children[1].children[0];
const destination = destinationRow.children[0].children[1];
/** @param {Element} control @param {string} value @returns {void} */
function input(control, value) {
    control.value = value; control.dispatchEvent(new Event('input', {bubbles:true}));
}
/** @returns {Array<boolean>} */
function hidden() {
    return [slotsRow, grid, partialRow, destinationRow, refillRow].map(row => row.hidden);
}
const initial = {open:editor.root.open, slots:slots.value, hidden:hidden()};
const empty = window.StowlingStoragePolicyEditor.create();
grid.children[18].dispatchEvent(new Event('click'));
input(destination, 'coordinate:box');
const mixed = editor.read();
input(mode, 'BOX_ONLY');
input(destination, 'region:partials');
const boxed = {policy:editor.read(), hidden:hidden()};
partialRow.children[1].checked = false;
partialRow.children[1].dispatchEvent(new Event('input', {bubbles:true}));
const partialOff = {policy:editor.read(), hidden:destinationRow.hidden};
input(mode, 'NORMAL');
const normal = {policy:editor.read(), hidden:hidden()};
const legacy = window.StowlingStoragePolicyEditor.create(
    {mode:'BOX_ONLY', allowPartialBoxes:false, partialBoxDestinationRef:'region:partials'},
    {regions:[{id:'partials', name:'Partial region'}]});
const pureInitial = {policy:legacy.read(),
    destinationVisible:!legacy.root.children[0].children[4].hidden};
process.stdout.write(JSON.stringify({initial, mixed, boxed, partialOff, normal, pureInitial,
    emptyMode:empty.read().mode}));
"""
    result = subprocess.run(
        [str(NODE), "-e", script, str(WEB / "storage-policy-editor.js")],
        check=True, capture_output=True, encoding="utf-8",
    )
    value = json.loads(result.stdout)
    assert value["emptyMode"] == "NORMAL"
    assert value["initial"] == {
        "open": False, "slots": "27", "hidden": [False] * 5,
    }
    assert value["mixed"]["looseSlots"] == 19
    assert value["mixed"]["partialBoxDestinationRef"] == "coordinate:box"
    assert value["mixed"]["refillLoose"] is True
    assert value["boxed"]["hidden"] == [True, True, True, False, True]
    assert value["boxed"]["policy"]["allowPartialBoxes"] is True
    assert value["boxed"]["policy"]["partialBoxDestinationRef"] == "region:partials"
    assert value["boxed"]["policy"]["looseSlots"] is None
    assert value["boxed"]["policy"]["refillLoose"] is False
    assert value["partialOff"]["hidden"] is False
    assert value["partialOff"]["policy"]["allowPartialBoxes"] is True
    assert value["partialOff"]["policy"]["partialBoxDestinationRef"] == "region:partials"
    assert value["pureInitial"]["destinationVisible"] is True
    assert value["pureInitial"]["policy"]["allowPartialBoxes"] is True
    assert value["pureInitial"]["policy"]["partialBoxDestinationRef"] == "region:partials"
    assert value["normal"]["hidden"] == [True] * 5
    assert value["normal"]["policy"]["allowPartialBoxes"] is False


@pytest.mark.skipif(NODE is None, reason="Node.js is required")
def test_animation_cancels_stale_transitions_and_ignores_periodic_refresh() -> None:
    script = r"""
const fs = require('node:fs');
const vm = require('node:vm');
globalThis.window = {Stowling:{}, matchMedia:() => ({matches:false})};
globalThis.document = {/** @returns {void} */ addEventListener() {}};
vm.runInThisContext(fs.readFileSync(process.argv[1], 'utf8'));
const animations = [];
const body = {
    hidden:true, style:{}, scrollHeight:100,
    /** @returns {object} */ getBoundingClientRect() { return {height:this.hidden ? 0 : 100}; },
    /** @returns {object} */ animate() {
        let finish;
        let reject;
        const finished = new Promise((resolve, failure) => { finish=resolve; reject=failure; });
        const animation = {finished, finish, /** @returns {void} */ cancel() { reject(); }};
        animations.push(animation);
        return animation;
    }
};
/** @returns {Promise<void>} */
async function main() {
    const reveal = window.StowlingSettings.reveal;
    await reveal(body, false);
    const opening = reveal(body, true);
    const repeated = reveal(body, true);
    const samePromise = opening === repeated;
    const closing = reveal(body, false);
    const duringClose = body.hidden;
    animations[1].finish();
    await Promise.all([opening, closing]);
    const closed = body.hidden;
    const reopening = reveal(body, true);
    animations[2].finish();
    await reopening;
    process.stdout.write(JSON.stringify({samePromise, duringClose, closed,
        reopened:!body.hidden, animations:animations.length, overflow:body.style.overflow}));
}
void main();
"""
    result = subprocess.run(
        [str(NODE), "-e", script, str(WEB / "settings-ui.js")],
        check=True, capture_output=True, encoding="utf-8",
    )
    assert json.loads(result.stdout) == {
        "samePromise": True, "duringClose": False, "closed": True,
        "reopened": True, "animations": 3, "overflow": "",
    }


def test_task_areas_and_storage_are_nested_collapsed_disclosures() -> None:
    source = (WEB / "auto-classify-ui.js").read_text(encoding="utf-8")
    html = (WEB / "tasks.html").read_text(encoding="utf-8")
    css = (WEB / "workbench.css").read_text(encoding="utf-8")
    assert 'StowlingSettings.disclosure(' in source
    assert 'selectedZone?.name || "请选择分类范围", fields)' in source
    assert 'areaReaders.forEach(reader => reader.collapse())' in source
    assert '.auto-config-section[data-enabled="false"] .move-task-chevron' in css
    assert 'transform: rotate(-45deg)' in css
    assert 'transform: rotate(45deg)' in css
    assert html.index('/storage-policy-editor.js') < html.index('/auto-classify-ui.js')
