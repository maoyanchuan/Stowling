import json
import shutil
import subprocess
from pathlib import Path
from typing import Any

import pytest


WEB = Path(__file__).resolve().parents[1] / "src/main/resources/stowling-web"
NODE = shutil.which("node")
pytestmark = pytest.mark.skipif(NODE is None, reason="Node.js is required")
RUNNER = r"""
const fs = require("node:fs");
const vm = require("node:vm");
class Element {
    /** @param {string} tag */
    constructor(tag) {
        this.tagName = tag.toUpperCase();
        this.children = [];
        this.listeners = {};
        this.attributes = {};
        this.dataset = {};
        this.classList = {
            /** @returns {void} */
            add() {}
        };
        this.disabled = false;
        this.checked = false;
        this.value = "";
    }
    /** @param {...Element} nodes @returns {void} */
    append(...nodes) {
        for (const node of nodes) {
            node.parentElement = this;
            this.children.push(node);
        }
    }
    /** @param {string} key @param {string} value @returns {void} */
    setAttribute(key, value) { this.attributes[key] = value; }
    /** @param {string} type @param {Function} callback @returns {void} */
    addEventListener(type, callback) { this.listeners[type] = callback; }
    /** @param {string} type @returns {void} */
    emit(type) {
        this.listeners[type]?.({target: this, /** @returns {void} */ preventDefault() {}});
    }
    /** @param {string} selector @returns {Array<Element>} */
    querySelectorAll(selector) {
        const tags = selector.split(",").map(value => value.trim().toUpperCase());
        return this.children.flatMap(child => [child, ...child.querySelectorAll(selector)])
            .filter(child => tags.includes(child.tagName));
    }
}
const events = [];
globalThis.document = {
    listeners: {},
    /** @param {string} type @param {Function} callback @returns {void} */
    addEventListener(type, callback) {this.listeners[type] = callback;},
    /** @param {Event} event @returns {void} */
    dispatchEvent(event) {events.push(event.type);},
};
globalThis.window = {Stowling: {
    /** @param {string} tag @param {string} className @param {string} text @returns {Element} */
    element(tag, className, text) {
        const node = new Element(tag);
        node.textContent = text;
        return node;
    }
}};
vm.runInThisContext(fs.readFileSync(process.argv[1], "utf8"));
const settings = window.StowlingSettings;
let state;
let requests = [];
let responseItem;
let failRequest = false;
let renders = 0;
const Stowling = {
    /** @param {string} url @param {object} options @returns {Promise<object>} */
    async request(url, options) {
        requests.push({url, ...options});
        if (failRequest) throw new Error("offline");
        return {revision: "next", item: responseItem};
    },
    /** @returns {void} */
    showToast() {},
    /** @returns {void} */
    handleError() {},
};
/** @returns {boolean} */
function unavailable() {return state.busy || state.loading || !state.ready;}
/** @returns {void} */
function updateControls() {}
/** @returns {void} */
function renderList() {renders++;}
const source = fs.readFileSync(process.argv[2], "utf8");
const start = source.indexOf("async function setListening(");
const end = source.indexOf("\n    }", start) + 6;
const setListening = eval("(" + source.slice(start, end) + ")");
Promise.resolve(eval(process.argv[3])).then(result => process.stdout.write(JSON.stringify(result)));
"""


def run_ui(expression: str) -> Any:
    if NODE is None:
        raise RuntimeError("Node.js is unavailable")
    completed = subprocess.run(
        [NODE, "-e", RUNNER, str(WEB / "settings-ui.js"),
         str(WEB / "auto-classify-ui.js"), expression],
        check=True,
        capture_output=True,
        encoding="utf-8",
    )
    return json.loads(completed.stdout)


def test_optional_settings_preserve_values_and_preexisting_disabled_controls() -> None:
    result = run_ui(
        """(() => {
            const toggle = new Element('input');
            const content = new Element('div');
            const coordinate = new Element('input');
            coordinate.value = '34';
            const unavailable = new Element('select');
            unavailable.disabled = true;
            content.append(coordinate, unavailable);
            const section = settings.optionalSection('站位', '操作区域', toggle, content);
            const initial = {hidden: content.hidden, disabled: coordinate.disabled};
            toggle.checked = true;
            toggle.emit('change');
            const enabled = {hidden: content.hidden, disabled: coordinate.disabled,
                unavailable: unavailable.disabled};
            const header = section.root.children[0].children[0];
            header.emit('click');
            const folded = {hidden: content.hidden, disabled: coordinate.disabled};
            toggle.checked = false;
            toggle.emit('change');
            toggle.checked = true;
            toggle.emit('change');
            return {initial, enabled, folded, value: coordinate.value,
                unavailable: unavailable.disabled, type: header.type};
        })()"""
    )

    assert result["initial"] == {"hidden": True, "disabled": True}
    assert result["enabled"] == {"hidden": False, "disabled": False, "unavailable": True}
    assert result["folded"] == {"hidden": True, "disabled": False}
    assert result["value"] == "34"
    assert result["unavailable"] is True
    assert result["type"] == "button"


def test_invalid_fields_reveal_their_collapsed_configuration() -> None:
    result = run_ui(
        """(() => {
            const content = new Element('div');
            const control = new Element('input');
            content.append(control);
            const section = settings.disclosure('识别区', '坐标', content);
            document.listeners.invalid({target: control});
            return {open: section.root.open, role: section.root.tagName};
        })()"""
    )

    assert result == {"open": True, "role": "DETAILS"}


def test_invalid_optional_fields_reopen_the_enabled_feature() -> None:
    result = run_ui(
        """(() => {
            const toggle = new Element('input');
            toggle.checked = true;
            const content = new Element('div');
            settings.optionalSection('站位', '操作区域', toggle, content);
            const before = content.hidden;
            content.emit('invalid');
            return {before, after: content.hidden};
        })()"""
    )

    assert result == {"before": True, "after": False}


@pytest.mark.parametrize("enabled,operation", [(True, "start"), (False, "stop")])
def test_task_switch_updates_listening_without_replacing_another_unsaved_draft(
    enabled: bool, operation: str
) -> None:
    result = run_ui(
        f"""(async () => {{
            state = {{ready: true, busy: false, revision: 'old',
                items: [{{id: 'task-1', enabled: {str(not enabled).lower()}}}],
                draft: {{id: 'other-task', name: 'Unsaved name'}}, dirty: true}};
            responseItem = {{id: 'task-1', enabled: {str(enabled).lower()}}};
            await setListening(state.items[0], {str(enabled).lower()});
            return {{state, requests, renders, events}};
        }})()"""
    )

    assert result["requests"][0]["url"] == f"/v1/auto-classify-tasks/task-1/{operation}"
    assert json.loads(result["requests"][0]["body"]) == {"revision": "old"}
    assert result["state"]["items"][0]["enabled"] is enabled
    assert result["state"]["draft"] == {"id": "other-task", "name": "Unsaved name"}
    assert result["state"]["dirty"] is True
    assert result["state"]["busy"] is False
    assert result["renders"] == 1
    assert result["events"] == ["stowling:auto-tasks-changed"]


def test_failed_task_switch_preserves_saved_state_and_reenables_controls() -> None:
    result = run_ui(
        """(async () => {
            state = {ready: true, busy: false, revision: 'old',
                items: [{id: 'task-1', enabled: false}], draft: {id: 'task-1', enabled: false}};
            failRequest = true;
            await setListening(state.items[0], true);
            return {state, renders};
        })()"""
    )

    assert result["state"]["items"][0]["enabled"] is False
    assert result["state"]["draft"]["enabled"] is False
    assert result["state"]["revision"] == "old"
    assert result["state"]["busy"] is False
    assert result["renders"] == 1


def test_delayed_native_toggle_does_not_reopen_a_module_after_user_closes_it() -> None:
    result = run_ui(
        """(async () => {
            const body = new Element('div');
            const section = settings.disclosure('识别区', '坐标', body);
            section.summary.emit('click');
            section.summary.emit('click');
            section.root.emit('toggle');
            await Promise.resolve();
            const closed = {open:section.root.open, hidden:body.hidden,
                expanded:section.summary.attributes['aria-expanded']};
            section.root.open = true;
            section.root.emit('toggle');
            return {closed, external:{open:section.root.open, hidden:body.hidden}};
        })()"""
    )
    assert result == {
        "closed": {"open": False, "hidden": True, "expanded": "false"},
        "external": {"open": True, "hidden": False},
    }
