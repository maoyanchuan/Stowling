import json
import shutil
import subprocess
from pathlib import Path

import pytest


WEB = Path(__file__).resolve().parents[1] / "src/main/resources/stowling-web"
NODE = shutil.which("node")


@pytest.mark.skipif(NODE is None, reason="Node.js is required")
def test_saved_layout_modules_and_every_binding_start_closed_in_both_modes() -> None:
    script = r"""
const fs = require('node:fs');
const vm = require('node:vm');
class Element {
    /** @param {string} tag @param {string} css @param {string} text */
    constructor(tag = 'div', css = '', text = '') {
        this.tagName = tag; this.className = css; this.textContent = text;
        this.children = []; this.listeners = {}; this.attributes = {}; this.dataset = {};
        this.classList = {/** @param {...string} names @returns {void} */
            add:(...names) => {this.className += ' ' + names.join(' ');},
            /** @returns {void} */ toggle() {}};
    }
    /** @param {...Element} children @returns {void} */
    append(...children) {
        for (const child of children) {child.parentElement = this; this.children.push(child);}
    }
    /** @returns {void} */ replaceChildren() {this.children = [];}
    /** @param {string} key @param {string} value @returns {void} */
    setAttribute(key, value) {this.attributes[key] = value;}
    /** @param {string} type @param {Function} callback @returns {void} */
    addEventListener(type, callback) {this.listeners[type] = callback;}
    /** @param {string} type @returns {void} */
    emit(type) {this.listeners[type]?.({target:this, preventDefault:() => {}});}
    /** @returns {null} */ closest() {return null;}
}
globalThis.HTMLElement = Element;
globalThis.Option = class extends Element {
    /** @param {string} text @param {string} value */
    constructor(text, value) {super('option', '', text); this.value = value;}
};
globalThis.document = {
    /** @param {string} tag @returns {Element} */ createElement(tag) {return new Element(tag);},
    /** @returns {void} */ addEventListener() {}
};
const Stowling = {/** @returns {Element} */
    element:(tag, css, text) => new Element(tag, css, text)};
globalThis.window = {Stowling, confirm:() => true};
vm.runInThisContext(fs.readFileSync(process.argv[1], 'utf8'));
const Settings = window.StowlingSettings;
const source = fs.readFileSync(process.argv[2], 'utf8');
/** @param {string} name @returns {Function} */
function load(name) {
    const start = source.indexOf('function ' + name + '(');
    const end = source.indexOf('\n    /**', start);
    return eval('(' + source.slice(start, end).trim() + ')');
}
const Model = {/** @param {object} value @returns {object} */ normalizeBinding:value => value};
/** @param {string} label @param {Element} control @returns {Element} */
function field(label, control) {const row = new Element('label'); row.append(control); return row;}
/** @param {number} value @returns {Element} */
function numberInput(value) {const input = new Element('input'); input.value = value; return input;}
/** @param {string} label @param {string} kind @param {object} value @returns {object} */
function cornerFields(label, kind, value) {
    return {root:new Element('div'), read:() => ({areaFirstX:value.areaFirstX})};
}
/** @returns {object} */
function expansionStats() {return {valid:true, message:'2 slices'};}
/** @param {Element} host @param {object} binding @param {boolean} expanded */
function appendBinding(host, binding, expanded) {
    const card = new Element('details', 'category-binding-card');
    card.open = expanded; host.append(card); return () => binding;
}
const appendExpansionEditor = load('appendExpansionEditor');
const createBindingEditor = load('createBindingEditor');
/** @param {Element} root @returns {Array<Element>} */
function nodes(root) {return [root, ...root.children.flatMap(nodes)];}
const bindings = [{markerFirstX:13}, {markerFirstX:14}];
const expansion = {direction:'X_POSITIVE', step:1, templateCount:2, areaFirstX:13};
const automatic = createBindingEditor(bindings, expansion);
const initial = nodes(automatic.root).filter(node => node.tagName === 'details')
    .map(node => ({css:node.className, open:node.open}));
const rangeMode = nodes(automatic.root).find(node =>
    node.className.includes('category-range-mode') && node.tagName === 'details');
const expansionRoot = nodes(automatic.root).find(node =>
    node.className.includes('category-expansion-editor'));
const expansionNested = nodes(rangeMode).includes(expansionRoot);
const saved = automatic.read();
const add = nodes(automatic.root).find(node => node.textContent === '添加分类物');
add.emit('click');
const afterAdd = nodes(automatic.root).filter(node =>
    node.className.includes('category-binding-card')).map(node => node.open);
const mode = nodes(automatic.root).find(node =>
    node.className.includes('category-zone-mode-field')).children[0];
mode.value = 'MANUAL'; mode.emit('change');
const manualExpansionHidden = nodes(rangeMode).find(node =>
    node.className.includes('category-expansion-host')).hidden;
const afterSwitch = nodes(automatic.root).filter(node =>
    node.className.includes('category-binding-card')).map(node => node.open);
const manual = createBindingEditor(bindings, null);
const manualOpen = nodes(manual.root).filter(node => node.tagName === 'details')
    .map(node => node.open);
process.stdout.write(JSON.stringify({initial, saved, afterAdd, afterSwitch, manualOpen,
    expansionNested, manualExpansionHidden}));
"""
    result = subprocess.run(
        [str(NODE), "-e", script, str(WEB / "settings-ui.js"),
         str(WEB / "category-zones.js")],
        check=True, capture_output=True, encoding="utf-8",
    )
    value = json.loads(result.stdout)
    assert len(value["initial"]) == 5
    assert all(item["open"] is False for item in value["initial"])
    assert value["expansionNested"] is True
    assert value["manualExpansionHidden"] is True
    assert value["saved"]["bindings"] == [{"markerFirstX": 13}, {"markerFirstX": 14}]
    assert value["saved"]["expansion"]["templateCount"] == 2
    assert value["saved"]["expansion"]["step"] == 1
    assert value["afterAdd"] == [False, False, True]
    assert value["afterSwitch"] == [False, False, False]
    assert value["manualOpen"] == [False, False, False, False]


def test_advanced_coordinate_and_cleanup_settings_use_shared_closed_disclosures() -> None:
    coordinates = (WEB / "coordinates.js").read_text(encoding="utf-8")
    cleanup = (WEB / "task-cleanup-settings.js").read_text(encoding="utf-8")
    assert '"更多选项", "维度与到达半径", optionFields)' in coordinates
    assert 'options.open = useDimension.checked || useRadius.checked' not in coordinates
    assert 'StowlingSettings.disclosure("中断与收尾设置"' in cleanup
    assert 'section.setExpanded(false)' in cleanup
    assert coordinates.count('bindDisclosure(card, heading, fields)') == 2
