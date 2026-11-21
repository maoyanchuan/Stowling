import re
import shutil
import subprocess
from pathlib import Path

import pytest


WEB = Path(__file__).resolve().parents[1] / "src/main/resources/stowling-web"
NODE = shutil.which("node")
RUNNER = r"""
const assert = require("node:assert/strict");
const fs = require("node:fs");
const vm = require("node:vm");
const source = fs.readFileSync(process.argv[1], "utf8");
const saved = new Map();
/** @param {string} page @param {boolean} blocked @param {boolean} header @returns {object} */
function openPage(page, blocked = false, header = true) {
    const root = {dataset: {}};
    const nodes = new Map();
    const events = {};
    /** @param {string} tag @returns {object} */
    function element(tag) {
        return {
            tagName: tag,
            attributes: {},
            listeners: {},
            children: [],
            /** @param {...object} children @returns {void} */
            append(...children) { this.children.push(...children); },
            /** @param {string} name @param {string} value @returns {void} */
            setAttribute(name, value) { this.attributes[name] = value; },
            /** @param {string} type @param {Function} handler @returns {void} */
            addEventListener(type, handler) { this.listeners[type] = handler; },
            /** @param {string} value @returns {void} */
            set id(value) { nodes.set(`#${value}`, this); },
        };
    }
    const connection = {
        /** @param {object} controls @returns {void} */
        before(controls) { this.controls = controls; },
    };
    const document = {
        documentElement: root,
        body: {dataset: {}},
        createElement: element,
        /** @param {string} selector @returns {object | null} */
        querySelector(selector) {
            if (selector === ".connection-state") return header ? connection : null;
            return nodes.get(selector) || null;
        },
    };
    const window = {
        localStorage: {
            /** @param {string} key @returns {string | null} */
            getItem(key) {
                if (blocked) throw new Error("Storage blocked");
                return saved.get(key) ?? null;
            },
            /** @param {string} key @param {string} value @returns {void} */
            setItem(key, value) {
                if (blocked) throw new Error("Storage blocked");
                saved.set(key, value);
            },
        },
        /** @param {string} type @param {Function} handler @returns {void} */
        addEventListener(type, handler) { events[type] = handler; },
    };
    const sessionStorage = {
        /** @returns {null} */
        getItem() { return null; },
    };
    vm.runInNewContext(source, {window, document, sessionStorage});
    window.Stowling.mountPage(page);
    return {root, button: nodes.get("#theme-toggle"), events, connection};
}
eval(process.argv[2]);
"""


def run_theme(expression: str) -> None:
    if NODE is None:
        pytest.skip("Node.js is required")
    subprocess.run(
        [str(NODE), "-e", RUNNER, str(WEB / "common.js"), expression],
        check=True,
        capture_output=True,
        encoding="utf-8",
    )


def test_theme_button_switches_without_auth_and_remembers_across_pages() -> None:
    run_theme("""
        const tasks = openPage("tasks");
        assert.equal(tasks.root.dataset.theme, "green");
        assert.equal(tasks.button.textContent, "主题：浅绿");
        assert.equal(tasks.button.type, "button");
        assert.equal(tasks.button.attributes["aria-pressed"], "true");
        assert.equal(tasks.button.attributes["aria-label"], "浅绿色主题");
        assert.deepEqual(tasks.connection.controls.children, [tasks.button, tasks.connection]);
        tasks.button.listeners.click();
        assert.equal(tasks.root.dataset.theme, "gray");
        assert.equal(tasks.button.textContent, "主题：深灰");
        assert.equal(tasks.button.attributes["aria-pressed"], "false");
        assert.equal(saved.get("stowling.theme"), "gray");
        const storage = openPage("storage");
        assert.equal(storage.root.dataset.theme, "gray");
        storage.button.listeners.click();
        for (const page of ["dashboard", "coordinates", "category-zones", "backpack"]) {
            assert.equal(openPage(page).root.dataset.theme, "green");
        }
    """)


def test_theme_follows_changes_and_removal_in_other_tabs() -> None:
    run_theme("""
        const page = openPage("tasks");
        page.events.storage({key: "unrelated", newValue: "gray"});
        assert.equal(page.root.dataset.theme, "green");
        page.events.storage({key: "stowling.theme", newValue: "gray"});
        assert.equal(page.root.dataset.theme, "gray");
        assert.equal(page.button.attributes["aria-pressed"], "false");
        page.events.storage({key: "stowling.theme", newValue: null});
        assert.equal(page.root.dataset.theme, "green");
        page.events.storage({key: "stowling.theme", newValue: "gray"});
        page.events.storage({key: null, newValue: null});
        assert.equal(page.root.dataset.theme, "green");
    """)


def test_invalid_saved_theme_and_blocked_storage_allow_switching() -> None:
    run_theme("""
        saved.set("stowling.theme", "invalid");
        assert.equal(openPage("tasks").root.dataset.theme, "green");
        const page = openPage("coordinates", true);
        assert.equal(page.root.dataset.theme, "green");
        page.button.listeners.click();
        assert.equal(page.root.dataset.theme, "gray");
        page.button.listeners.click();
        assert.equal(page.root.dataset.theme, "green");
        const withoutHeader = openPage("dashboard", false, false);
        assert.equal(withoutHeader.button, undefined);
    """)


def test_light_palette_keeps_original_colors_and_page_gradients() -> None:
    legacy = (WEB / "styles.css").read_text(encoding="utf-8")
    workbench = (WEB / "workbench.css").read_text(encoding="utf-8")
    light = workbench.split(':root[data-theme="green"] {', 1)[1].split("}", 1)[0]
    original = legacy.split(":root {", 1)[1].split("}", 1)[0]
    colors = re.findall(r"(--[\w-]+): ([^;]+);", original)
    for name, value in colors:
        if name in {"--page-max-width", "--page-gutter"}:
            continue
        assert f"{name}: {value};" in light
    for gradient in (
        "radial-gradient(circle at 8% 5%, rgba(108, 158, 122, 0.24), transparent 31rem)",
        "radial-gradient(circle at 92% 4%, rgba(220, 189, 113, 0.19), transparent 27rem)",
        "linear-gradient(145deg, var(--bg), var(--bg-accent))",
        "linear-gradient(135deg, #194a32, #2f7a52 62%, #275e43)",
    ):
        assert gradient in light
        assert gradient in legacy
    assert "color-scheme: light;" in light
    assert "--theme-grid: block;" in light
    assert "background: var(--theme-page, var(--bg));" in workbench
    assert "background: var(--theme-hero, #24272a);" in workbench
