import json
import shutil
import subprocess
from pathlib import Path

import pytest


WEB = Path(__file__).resolve().parents[1] / "src/main/resources/stowling-web"
NODE = shutil.which("node")
RUNNER = r"""
const fs = require('node:fs');
const vm = require('node:vm');
const nodes = [];
class Element {
    /** @param {string} tag */
    constructor(tag) {
        this.tagName = tag.toUpperCase();
        this.children = [];
        this.listeners = {};
        this.value = '';
        this.classList = {/** @returns {void} */ add() {}};
        nodes.push(this);
    }
    /** @param {...Element} children @returns {void} */
    append(...children) {this.children.push(...children);}
    /** @param {...Element} children @returns {void} */
    replaceChildren(...children) {this.children = children;}
    /** @param {string} key @param {string} value @returns {void} */
    setAttribute(key, value) {this[key] = value;}
    /** @param {string} type @param {Function} callback @returns {void} */
    addEventListener(type, callback) {this.listeners[type] = callback;}
    /** @returns {Array<Element>} */
    get selectedOptions() {return this.children.filter(child => child.selected);}
    /** @returns {void} */
    focus() {this.focused = true;}
    /** @param {string} type @returns {Promise<void>} */
    async emit(type) {await this.listeners[type]?.({preventDefault() {}});}
}
const host = new Element('div');
const listeners = {};
const events = [];
globalThis.document = {
    /** @returns {Element} */
    querySelector() {return host;},
    /** @param {string} tag @returns {Element} */
    createElement(tag) {return new Element(tag);},
    /** @param {string} type @param {Function} callback @returns {void} */
    addEventListener(type, callback) {(listeners[type] ||= []).push(callback);},
    /** @param {object} event @returns {void} */
    dispatchEvent(event) {
        events.push({type: event.type, detail: event.detail});
        for (const callback of listeners[event.type] || []) callback(event);
    }
};
globalThis.CustomEvent = class {
    /** @param {string} type @param {object} options */
    constructor(type, options) {this.type = type; this.detail = options.detail;}
};
globalThis.Option = class extends Element {
    /** @param {string} text @param {string} value */
    constructor(text, value) {super('option'); this.textContent = text; this.value = value;}
};
let connect;
let saved;
globalThis.window = {Stowling: {
    /** @param {string} tag @param {string} css @param {string} text @returns {Element} */
    element(tag, css, text) {const node = new Element(tag); node.textContent = text; return node;},
    /** @param {Function} callback @returns {void} */
    onConnected(callback) {connect = callback;},
    /** @returns {void} */
    showToast() {},
    /** @param {Error} error @returns {never} */
    handleError(error) {throw error;},
    /** @param {string} path @param {object} options @returns {Promise<object>} */
    async request(path, options) {
        if (options) {saved = JSON.parse(options.body); return {revision: 'next'};}
        if (path === '/v1/coordinates') return {coordinates: [{id: 'old', name: 'Old', x: 1,y: 2,z: 3}]};
        if (path === '/v1/regions') return {regions: [{id: 'area', name: 'Staging',
            firstX: 1, firstY: 2, firstZ: 3, secondX: 4, secondY: 5, secondZ: 6},
            {id: 'unstackable', name: 'Unstackable',
                firstX: 7, firstY: 2, firstZ: 3, secondX: 8, secondY: 5, secondZ: 6}]};
        return {revision: 'old', settings: {cleanupEnabled: true, temporaryCoordinateIds: ['old'],
            temporaryRegionIds: ['area']}};
    }
}};
vm.runInThisContext(fs.readFileSync(process.argv[1], 'utf8'));
(async () => {
    connect();
    await new Promise(resolve => setImmediate(resolve));
    await nodes.find(node => node.textContent === '空潜影盒供应箱' && node.tagName === 'BUTTON')
        .emit('click');
    await nodes.find(node => node.id === 'add-facility-coordinate').emit('click');
    const request = events.find(event => event.type === 'stowling:request-coordinate-create');
    document.dispatchEvent(new CustomEvent('stowling:coordinate-created', {detail: {
        coordinate: {id: 'new', name: 'Supply', x: 4, y: 5, z: 6}
    }}));
    await nodes.find(node => node.tagName === 'FORM').emit('submit');
    process.stdout.write(JSON.stringify({request, saved, mounted: host.children.length}));
})();
"""


@pytest.mark.skipif(NODE is None, reason="Node.js is required")
def test_facilities_mount_and_new_point_is_selected_without_losing_other_references() -> None:
    result = subprocess.run(
        [str(NODE), "-e", RUNNER, str(WEB / "storage-settings.js")],
        check=True, capture_output=True, encoding="utf-8",
    )
    value = json.loads(result.stdout)
    assert value["mounted"] == 2
    assert value["request"]["detail"] == {
        "returnPanel": "facilities", "name": "空盒供应箱",
    }
    assert value["saved"]["settings"]["temporaryCoordinateIds"] == ["old"]
    assert value["saved"]["settings"]["temporaryRegionIds"] == ["area"]
    assert value["saved"]["settings"]["emptyBoxCoordinateIds"] == ["new"]
    assert value["saved"]["revision"] == "old"


@pytest.mark.skipif(NODE is None, reason="Node.js is required")
def test_overflow_facility_keeps_regions_when_adding_a_coordinate() -> None:
    runner = RUNNER.replace("'空潜影盒供应箱'", "'机器人仓库'").replace(
        "temporaryRegionIds: ['area']}};",
        "temporaryRegionIds: ['area'], overflowRegionIds: ['area'], "
        "unstackableOverflowRegionId: 'unstackable'}};",
    )
    result = subprocess.run(
        [str(NODE), "-e", runner, str(WEB / "storage-settings.js")],
        check=True, capture_output=True, encoding="utf-8",
    )
    value = json.loads(result.stdout)
    assert value["request"]["detail"] == {"returnPanel": "robot-warehouse", "name": "溢出箱"}
    settings = value["saved"]["settings"]
    assert settings["overflowCoordinateIds"] == ["new"]
    assert settings["overflowRegionIds"] == ["area"]
    assert settings["unstackableOverflowRegionId"] == "unstackable"
    assert settings["temporaryCoordinateIds"] == ["old"]
    assert settings["temporaryRegionIds"] == ["area"]
