import re
import shutil
import subprocess
from pathlib import Path

import pytest


WEB = Path(__file__).resolve().parents[1] / "src/main/resources/stowling-web"
NODE = shutil.which("node")


def test_dashboard_reuses_the_login_palette_for_its_surfaces() -> None:
    css = (WEB / "workbench.css").read_text(encoding="utf-8")
    rules = re.findall(r"([^{}]+)\{([^{}]*)\}", css)
    for component, background in (
        (".auth-panel", "var(--panel)"),
        (".hero", "var(--panel)"),
        (".metric", "var(--panel-solid)"),
        (".task-card", "var(--panel-solid)"),
    ):
        selector_pattern = rf"{re.escape(component)}\s*(?=[,)]|$)"
        backgrounds = [
            value
            for selector, declarations in rules
            if re.search(selector_pattern, selector)
            for value in re.findall(r"\bbackground(?:-color)?:\s*([^;]+);", declarations)
        ]
        assert backgrounds == [background], component


@pytest.mark.skipif(NODE is None, reason="Node.js is required")
def test_theme_defaults_to_green_and_restores_gray_on_another_page() -> None:
    script = """
        const assert = require("node:assert/strict");
        const fs = require("node:fs");
        const vm = require("node:vm");
        const source = fs.readFileSync(process.argv[1], "utf8");
        const saved = new Map();
        function openPage(name) {
            const root = {dataset: {}};
            const connection = {before() {}};
            let select;
            const window = {
                localStorage: {getItem: key => saved.get(key),
                    setItem: (key, value) => saved.set(key, value)},
                addEventListener() {},
            };
            const document = {
                documentElement: root,
                body: {dataset: {}},
                querySelector: selector => selector === ".connection-state" ? connection : null,
                createElement(tag) {
                    const el = {append() {},
                        addEventListener: (event, listener) => { el[event] = listener; }};
                    if (tag === "select") select = el;
                    return el;
                },
            };
            vm.runInNewContext(source, {window, document, sessionStorage: {getItem: () => null},
                Option: class {constructor(text, value) { this.value = value; }}});
            window.Stowling.mountPage(name);
            return {root, select};
        }
        const tasks = openPage("tasks");
        assert.equal(tasks.root.dataset.theme, "green");
        assert.equal(tasks.select.value, "green");
        tasks.select.value = "gray";
        tasks.select.change();
        assert.equal(tasks.root.dataset.theme, "gray");
        const coordinates = openPage("coordinates");
        assert.equal(coordinates.root.dataset.theme, "gray");
        assert.equal(coordinates.select.value, "gray");
        coordinates.select.value = "green";
        coordinates.select.change();
        assert.equal(openPage("storage").root.dataset.theme, "green");
    """
    subprocess.run(
        [str(NODE), "-e", script, str(WEB / "common.js")],
        check=True,
        capture_output=True,
        encoding="utf-8",
    )
