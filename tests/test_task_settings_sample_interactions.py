import json
import shutil
import subprocess
from pathlib import Path
from typing import Any

import pytest


ROOT = Path(__file__).resolve().parents[1]
SAMPLE = ROOT / "docs" / "ui" / "task-settings-standard.html"
NODE = shutil.which("node")
pytestmark = pytest.mark.skipif(NODE is None, reason="Node.js is required")
RUNNER = r"""
const fs = require("node:fs");
const vm = require("node:vm");
const source = fs.readFileSync(process.argv[1], "utf8");
const scripts = Array.from(source.matchAll(/<script>([\s\S]*?)<\/script>/g));
const nodes = new Map();
const animations = [];
const saved = new Map();
let reduced = false;
class Element {
    /** @param {string} id */
    constructor(id) {
        this.id = id;
        this.attributes = {};
        this.dataset = {};
        this.listeners = {};
        this.style = {};
        this.hidden = false;
        this.disabled = false;
        this.inert = false;
        this.value = "";
        this.checked = false;
        this.scrollHeight = 100;
    }
    /** @param {string} selector @returns {Element} */
    querySelector(selector) {
        return nodes.get(selector === ".sample-select-wrap" ? "wrap" : selector.slice(1));
    }
    /** @param {string} selector @returns {Array<Element>} */
    querySelectorAll(selector) {
        return selector === "[data-strategy]" ? choices : [];
    }
    /** @param {string} key @param {string} value @returns {void} */
    setAttribute(key, value) {this.attributes[key] = value;}
    /** @param {string} type @param {Function} callback @returns {void} */
    addEventListener(type, callback) {this.listeners[type] = callback;}
    /** @param {string} type @param {object} [extra] @returns {void} */
    emit(type, extra = {}) {
        this.listeners[type]?.({target: this, ...extra,
            /** @returns {void} */ preventDefault() {}});
    }
    /** @returns {void} */
    focus() {document.activeElement = this;}
    /** @returns {{height: number, top: number, bottom: number}} */
    getBoundingClientRect() {return {height: 50, top: 400, bottom: 450};}
    /** @param {Element} target @returns {boolean} */
    contains(target) {return target === this || choices.includes(target);}
    /** @param {Array<object>} frames @param {object} options @returns {object} */
    animate(frames, options) {
        let finish;
        let cancel;
        const finished = new Promise((resolve, reject) => {finish = resolve; cancel = reject;});
        const animation = {frames, options, finished, finish, cancel};
        animations.push(animation);
        return animation;
    }
}
for (const match of source.matchAll(/\bid="([^"]+)"/g)) {
    nodes.set(match[1], new Element(match[1]));
}
nodes.set("wrap", new Element("wrap"));
const choices = ["price", "stock", "all"].map(value => {
    const node = nodes.get(`sample-strategy-${value}`);
    node.dataset.strategy = value;
    return node;
});
const themes = ["green", "gray"].map(value => {
    const node = new Element(value);
    node.dataset.themeChoice = value;
    return node;
});
const document = {
    documentElement: {dataset: {theme: "green"}},
    listeners: {},
    /** @param {string} id @returns {Element} */
    getElementById(id) {return nodes.get(id);},
    /** @param {string} selector @returns {Array<Element>} */
    querySelectorAll(selector) {return themes;},
    /** @param {string} type @param {Function} callback @returns {void} */
    addEventListener(type, callback) {this.listeners[type] = callback;}
};
const window = {
    innerHeight: 800,
    localStorage: {
        /** @param {string} key @returns {string | null} */
        getItem(key) {return saved.get(key) ?? null;},
        /** @param {string} key @param {string} value @returns {void} */
        setItem(key, value) {saved.set(key, value);}
    },
    /** @returns {{matches: boolean}} */
    matchMedia() {return {matches: reduced};},
    /** @returns {object} */
    getComputedStyle() {return {opacity: "0.5", transform: "none"};}
};
/** @param {string} id @returns {Element} */
function node(id) {return nodes.get(`sample-${id}`);}
/** @returns {void} */
function load() {vm.runInNewContext(scripts.at(-1)[1], {document, window});}
Promise.resolve(eval(process.argv[2])).then(result => {
    process.stdout.write(JSON.stringify(result));
});
"""


def run_sample(expression: str) -> Any:
    if NODE is None:
        raise RuntimeError("Node.js is unavailable")
    result = subprocess.run(
        [NODE, "-e", RUNNER, str(SAMPLE), expression],
        check=True,
        capture_output=True,
        encoding="utf-8",
    )
    return json.loads(result.stdout)


def test_stopping_task_preserves_editable_settings_and_open_submenu() -> None:
    result = run_sample("""(() => {
        load();
        node('wuling-expand').emit('click');
        node('maximum').value = '180';
        node('maximum').emit('input');
        node('enabled').checked = true;
        node('enabled').emit('change');
        node('enabled').checked = false;
        node('enabled').emit('change');
        return {disabled: node('maximum').disabled, value: node('maximum').value,
            submenu: node('wuling-detail').inert, reserve: node('wuling-reserve').disabled,
            dropdown: node('strategy-trigger').disabled,
            status: node('run-status').textContent};
    })()""")
    assert result == {
        "disabled": False, "value": "180", "submenu": False, "reserve": False,
        "dropdown": False, "status": "任务未启动",
    }


def test_previous_disabled_form_draft_migrates_without_starting_task() -> None:
    result = run_sample("""(() => {
        saved.set('stowling.task-settings-sample.state', JSON.stringify({version: 1,
            ui: {enabled: false, wulingReserve: '96', maximum: '200'}}));
        load();
        return {running: node('enabled').checked, disabled: node('maximum').disabled,
            reserve: node('wuling-reserve').value, maximum: node('maximum').value};
    })()""")
    assert result == {"running": False, "disabled": False, "reserve": "96", "maximum": "200"}


def test_strategy_keyboard_selection_updates_units_and_returns_focus() -> None:
    result = run_sample("""(async () => {
        window.innerHeight = 480;
        load();
        node('strategy-trigger').emit('keydown', {key: 'ArrowDown'});
        node('strategy-menu').emit('keydown', {key: 'ArrowDown'});
        const focused = document.activeElement.id;
        document.activeElement.emit('click');
        animations.at(-1).finish();
        await Promise.resolve();
        return {focused, selection: node('strategy').value,
            unit: node('threshold-unit').textContent,
            selected: node('strategy-stock').attributes['aria-selected'],
            hidden: node('strategy-menu').hidden, focus: document.activeElement.id,
            placement: node('strategy-menu').dataset.placement};
    })()""")
    assert result == {
        "focused": "sample-strategy-stock", "selection": "stock", "unit": "件",
        "selected": "true", "hidden": True, "focus": "sample-strategy-trigger",
        "placement": "above",
    }


def test_interrupted_close_does_not_hide_reopened_submenu() -> None:
    result = run_sample("""(async () => {
        load();
        node('wuling-expand').emit('click');
        node('wuling-expand').emit('click');
        const closing = animations.at(-1);
        closing.finish();
        node('wuling-expand').emit('click');
        animations.at(-1).finish();
        await Promise.resolve();
        return {hidden: node('wuling-detail').hidden, inert: node('wuling-detail').inert};
    })()""")
    assert result == {"hidden": False, "inert": False}


def test_reduced_motion_switches_submenus_without_spatial_animation() -> None:
    result = run_sample("""(() => {
        reduced = true;
        load();
        node('wuling-expand').emit('click');
        const opened = !node('wuling-detail').hidden;
        node('wuling-expand').emit('click');
        return {opened, closed: node('wuling-detail').hidden, count: animations.length};
    })()""")
    assert result == {"opened": True, "closed": True, "count": 0}


def test_main_menu_animates_both_directions_without_changing_task_or_settings() -> None:
    result = run_sample("""(async () => {
        load();
        node('maximum').value = '180';
        node('maximum').emit('input');
        node('collapse').emit('click');
        const beforeFinish = node('module-body').hidden;
        animations.at(-1).finish();
        await Promise.resolve();
        const closed = node('module-body').hidden;
        node('collapse').emit('click');
        animations.at(-1).finish();
        await Promise.resolve();
        return {beforeFinish, closed, opened: !node('module-body').hidden,
            timings: animations.map(animation => animation.options.duration),
            value: node('maximum').value, running: node('enabled').checked};
    })()""")
    assert result == {
        "beforeFinish": False, "closed": True, "opened": True,
        "timings": [150, 200], "value": "180", "running": False,
    }
