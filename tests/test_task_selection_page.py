import json
import shutil
import subprocess
from pathlib import Path
from typing import Any

import pytest


ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "src" / "main" / "resources" / "stowling-web"
TASKS_HTML = WEB / "tasks.html"
TASKS_SCRIPT = WEB / "tasks.js"
TASK_MODEL = WEB / "v2-task-model.js"
MONITOR_MODEL = WEB / "task-monitor-model.js"
NODE = shutil.which("node")


def read(name: str) -> str:
    return (WEB / name).read_text(encoding="utf-8")


def test_task_workbench_reserves_display_before_selection() -> None:
    html = read("tasks.html")
    workbench = html.split('id="task-selection-area"', 1)[1]
    display_start = workbench.index('id="task-display-area"')
    selection_start = workbench.index('class="panel task-sidebar task-selection-panel"')

    assert 'class="task-workbench"' in html
    assert display_start < selection_start
    assert "任务展示" in workbench[display_start:selection_start]
    display = workbench[display_start:selection_start]
    styles = read("styles.css")
    placeholder = styles.split(".task-display-placeholder {", 1)[1].split("}", 1)[0]
    assert "min-height: clamp(14rem, 30dvh, 22rem);" in placeholder
    mobile = styles.rsplit("@media (max-width: 779px)", 1)[1]
    mobile = mobile.split("@media", 1)[0]
    assert ".task-display-placeholder {" in mobile
    assert "min-height: 11rem;" in mobile
    assert "task-display-placeholder" in display
    assert "<p" not in display
    assert "task-display-area" not in read("tasks.js")
    for removed in ("task-form", "task-editor-title", "task-trigger-settings"):
        assert removed not in display


def test_task_page_removes_editor_module_and_creation_entry_points() -> None:
    html = read("tasks.html")

    for removed in (
        'id="task-form"',
        'id="task-trigger-settings"',
        'id="task-module-panel"',
        'id="task-editor-title"',
        'id="task-list-message"',
        'id="new-task-button"',
        'id="new-cache-task-button"',
        'id="new-sort-task-button"',
        'id="new-marker-task-button"',
        'id="new-interruption-task-button"',
        'id="new-auto-eat-task-button"',
        'class="task-template-menu"',
    ):
        assert removed not in html
    for removed_text in ("新建任务", "快捷模板", "模块参考"):
        assert removed_text not in html

    for retained in (
        'id="task-search"',
        'data-task-filter="ALL"',
        'data-task-filter="NORMAL"',
        'data-task-filter="INTERRUPTION"',
        'id="new-folder-button"',
        'id="task-folder-form"',
        'id="task-list"',
        'id="run-task-select"',
        'id="run-task-button"',
        'id="task-queue-list"',
        'id="task-log-list"',
    ):
        assert retained in html


def test_task_page_loads_only_the_selection_script_stack() -> None:
    html = read("tasks.html")

    assert '<script src="/v2-task-model.js" defer></script>' in html
    assert '<script src="/task-monitor-model.js" defer></script>' in html
    assert '<script src="/tasks.js" defer></script>' in html
    for removed in (
        "coordinate-model.js",
        "v2-task-editor.js",
        "v2-trigger-model.js",
    ):
        assert removed not in html


def test_tasks_script_reads_selection_data_without_editor_state() -> None:
    javascript = read("tasks.js")
    loading = javascript.split("async function loadAll()", 1)[1]
    loading = loading.split("function renderTaskList()", 1)[0]

    for path in (
        '"/v1/tasks"',
        '"/v1/task-folders"',
        '"/v1/task-triggers"',
        '"/v1/task-modules"',
        '"/v1/task-runs"',
    ):
        assert path in loading
    for removed_path in (
        '"/v1/coordinates"',
        '"/v1/regions"',
        '"/v1/category-zones"',
    ):
        assert removed_path not in loading

    for removed in (
        "state.draft",
        "state.triggerDraft",
        "TriggerModel",
        "Editor.",
        "confirmDiscardForTaskChange",
        'window.location.hash === "#triggers"',
        "deleteTrigger",
    ):
        assert removed not in javascript
    assert "/v1/task-triggers/${" not in javascript
    assert "function selectTask(task, revealInList = false)" in javascript
    assert "state.runTaskId = task.id" in javascript


PAGE_RUNNER = r"""
const fs = require("node:fs");
const vm = require("node:vm");

const [htmlPath, tasksPath, modelPath, monitorPath] = process.argv.slice(1);
const html = fs.readFileSync(htmlPath, "utf8");

let scrollCount = 0;

class FakeElement {
    constructor(tagName = "div", id = "") {
        this.tagName = String(tagName).toUpperCase();
        this.id = id;
        this.parent = null;
        this.children = [];
        this.listeners = new Map();
        this.attributes = new Map();
        this.dataset = {};
        this.className = "";
        this.value = "";
        this.checked = false;
        this.disabled = false;
        this.hidden = false;
        this.open = false;
        this.scrollTop = 0;
        this.scrollHeight = 0;
        this._text = "";
        const classes = new Set();
        this.classList = {
            add: (...names) => names.forEach((name) => classes.add(name)),
            contains: (name) => classes.has(name),
            remove: (...names) => names.forEach((name) => classes.delete(name)),
            toggle: (name, force) => {
                const enabled = force === undefined ? !classes.has(name) : force;
                if (enabled) {
                    classes.add(name);
                } else {
                    classes.delete(name);
                }
                return enabled;
            },
        };
    }

    get textContent() {
        return this._text + this.children.map((child) => child.textContent).join("");
    }

    set textContent(value) {
        this._text = String(value);
        this.children = [];
    }

    get options() {
        return this.children.filter((child) => child.tagName === "OPTION");
    }

    get childElementCount() {
        return this.children.length;
    }


    append(...nodes) {
        for (const node of nodes) {
            const child = typeof node === "string"
                ? Object.assign(new FakeElement("#text"), { _text: node })
                : node;
            child.parent = this;
            this.children.push(child);
        }
    }

    replaceChildren(...nodes) {
        this.children = [];
        this.append(...nodes);
    }

    addEventListener(type, listener) {
        const entries = this.listeners.get(type) || [];
        entries.push(listener);
        this.listeners.set(type, entries);
    }

    dispatch(type, init = {}) {
        const event = {
            type,
            target: this,
            currentTarget: this,
            clientX: 0,
            clientY: 0,
            preventDefault() {},
            stopPropagation() {},
            ...init,
        };
        for (const listener of this.listeners.get(type) || []) {
            listener(event);
        }
        return event;
    }

    click() {
        return this.dispatch("click");
    }

    setAttribute(name, value) {
        this.attributes.set(name, String(value));
        if (name === "id") {
            this.id = String(value);
        }
    }

    getAttribute(name) {
        return this.attributes.get(name) ?? null;
    }


    querySelector(selector) {
        return this.querySelectorAll(selector)[0] || null;
    }

    querySelectorAll(selector) {
        const matches = [];
        const visit = (element) => {
            if (element.matches(selector)) {
                matches.push(element);
            }
            element.children.forEach(visit);
        };
        this.children.forEach(visit);
        return matches;
    }

    matches(selector) {
        if (selector.startsWith("#")) {
            return this.id === selector.slice(1);
        }
        if (selector.startsWith(".")) {
            return this.className.split(/\s+/).includes(selector.slice(1));
        }
        if (selector.startsWith("[data-")) {
            const name = selector.slice(6, -1).replace(/-([a-z])/g, (_, c) => c.toUpperCase());
            return this.dataset[name] !== undefined;
        }
        return this.tagName === selector.toUpperCase();
    }

    remove() {
        if (this.parent) {
            this.parent.children = this.parent.children.filter((child) => child !== this);
        }
    }

    scrollIntoView() {
        scrollCount += 1;
    }

    select() {}

    focus() {}
    showModal() { this.open = true; }
    close() { this.open = false; }
    getBoundingClientRect() {
        return { left: 0, top: 0, right: 100, bottom: 100 };
    }
}

class FakeDocument {
    constructor(source) {
        this.byId = new Map();
        this.body = new FakeElement("body");
        const ids = [...source.matchAll(/\bid="([^"]+)"/g)].map((match) => match[1]);
        ids.forEach((id) => this.byId.set(id, new FakeElement("div", id)));
        this.filters = [
            ["task-filter-all", "ALL"],
            ["task-filter-normal", "NORMAL"],
            ["task-filter-interruption", "INTERRUPTION"],
        ].filter(([id]) => this.byId.has(id)).map(([id, value]) => {
            const element = this.byId.get(id);
            element.dataset.taskFilter = value;
            return element;
        });
        this.seedOptions();
    }

    seedOptions() {
        const priority = this.byId.get("run-priority-select");
        if (priority) {
            priority.append(
                new Option("跟随任务", ""),
                new Option("后台", "BACKGROUND"),
                new Option("普通", "NORMAL"),
                new Option("紧急", "URGENT"),
            );
        }
        const runSelect = this.byId.get("run-task-select");
        if (runSelect) {
            runSelect.append(new Option("请选择任务", ""));
        }
    }

    querySelector(selector) {
        if (selector.startsWith("#")) {
            const id = selector.slice(1);
            if (!this.byId.has(id)) {
                throw new Error(`tasks.js requested missing DOM node #${id}`);
            }
            return this.byId.get(id);
        }
        if (selector === ".task-selection-panel") {
            const panel = new FakeElement("section");
            panel.className = "panel task-sidebar task-selection-panel";
            return panel;
        }
        return null;
    }

    querySelectorAll(selector) {
        if (selector === "[data-task-filter]") {
            return this.filters;
        }
        return [];
    }

    createElement(tagName) {
        return new FakeElement(tagName);
    }

}

function Option(text = "", value = "") {
    const option = new FakeElement("option");
    option.textContent = text;
    option.value = value;
    return option;
}

const document = new FakeDocument(html);
const requests = [];
const connectedHandlers = [];
const errors = [];
let confirmCount = 0;

const tasks = [{
    id: "task-1",
    name: "已有任务",
    folderId: "folder-1",
    priority: "NORMAL",
    steps: [],
    interruptionMode: "NONE",
}];
const folders = [{ id: "folder-1", name: "常用" }];
const triggers = [{ id: "trigger-1", targetTaskId: "task-1", enabled: true }];
const modules = [{ id: "MOVE_TO_COORDINATE", name: "移动到坐标", kind: "ACTION" }];
const runs = {
    runs: [{
        runId: "run-1",
        taskId: "task-1",
        taskName: "已有任务",
        state: "SUCCEEDED",
        priority: "NORMAL",
        completedSteps: 0,
        totalSteps: 0,
    }],
    queue: [],
    triggers: [],
};

async function request(path, options = {}) {
    const method = String(options.method || "GET").toUpperCase();
    requests.push({ path, method });
    await Promise.resolve();
    if (method === "POST" && path === "/v1/tasks/task-1/runs") {
        return { runId: "run-2", taskId: "task-1", state: "QUEUED" };
    }
    if (path === "/v1/tasks") {
        return { tasks, revision: "7" };
    }
    if (path === "/v1/task-folders") {
        return { folders, revision: "7" };
    }
    if (path === "/v1/task-triggers") {
        return { triggers, revision: "7" };
    }
    if (path === "/v1/task-modules") {
        return { modules };
    }
    if (path === "/v1/task-runs") {
        return runs;
    }
    return {};
}

const Stowling = {
    request,
    clone: (value) => JSON.parse(JSON.stringify(value)),
    isConnected: () => true,
    onConnected: (handler) => connectedHandlers.push(handler),
    mountPage: () => {},
    element: (tagName, className = "", text = "") => {
        const element = new FakeElement(tagName);
        element.className = className;
        element.textContent = text;
        return element;
    },
    emptyState: (text) => Object.assign(new FakeElement("p"), { textContent: text }),
    formatDate: (value) => String(value || ""),
    showToast: () => {},
    handleError: (error, context) => errors.push(`${context}: ${error.message}`),
};

const windowObject = {
    Stowling,
    document,
    setInterval: () => 1,
    confirm: () => {
        confirmCount += 1;
        throw new Error("selection must not request discard confirmation");
    },
};

globalThis.window = windowObject;
globalThis.document = document;
globalThis.Option = Option;

for (const path of [modelPath, monitorPath, tasksPath]) {
    vm.runInThisContext(fs.readFileSync(path, "utf8"), { filename: path });
}

const tick = () => new Promise((resolve) => setTimeout(resolve, 0));
const findButton = (text, parent = document.body) => {
    const candidates = [];
    const visit = (element) => {
        if (element.listeners.has("click") && element.textContent.includes(text)) {
            candidates.push(element);
        }
        element.children.forEach(visit);
    };
    visit(parent);
    return candidates[0] || null;
};

(async () => {
    for (const handler of connectedHandlers) {
        await handler();
    }
    await tick();
    await tick();

    const initialRequests = requests.map((entry) => entry.path);
    const runSelect = document.byId.get("run-task-select");
    const runButton = document.byId.get("run-task-button");
    const taskList = document.byId.get("task-list");
    const runtime = document.byId.get("runtime-list");
    const runDetails = document.byId.get("task-run-details");
    const selectionBefore = runSelect.value;

    runDetails.open = true;
    const runtimeSelect = findButton("选择任务", runtime);
    if (!runtimeSelect) {
        throw new Error("runtime task selection button was not rendered");
    }
    const scrollBeforeReveal = scrollCount;
    runtimeSelect.click();
    await tick();
    const revealClosedDetails = runDetails.open === false;
    const revealScrolled = scrollCount > scrollBeforeReveal;

    runSelect.value = "";
    runSelect.dispatch("change");
    await tick();
    const clearedSelection = runSelect.value;

    runSelect.value = "task-1";
    runSelect.dispatch("change");
    await tick();
    const dropdownSelection = runSelect.value;
    const selectedTaskButton = findButton("已有任务", taskList);
    if (!selectedTaskButton) {
        throw new Error("existing task button was not rendered after dropdown selection");
    }
    const dropdownCardPressed = selectedTaskButton.getAttribute("aria-pressed") === "true";
    selectedTaskButton.click();
    await tick();
    const cardSelection = runSelect.value;
    const enabledAfterSelection = !runButton.disabled;

    runButton.click();
    await tick();
    await tick();
    const runRequest = requests.find((entry) => (
        entry.method === "POST" && entry.path === "/v1/tasks/task-1/runs"
    ));
    const runsReadsBeforeRefresh = requests.filter((entry) => (
        entry.path === "/v1/task-runs"
    )).length;
    document.byId.get("refresh-runs-button").click();
    await tick();
    await tick();
    const runsReadsAfterRefresh = requests.filter((entry) => (
        entry.path === "/v1/task-runs"
    )).length;
    const postRequests = requests.filter((entry) => entry.method === "POST");

    process.stdout.write(JSON.stringify({
        initialRequests,
        selectionBefore,
        clearedSelection,
        dropdownSelection,
        dropdownCardPressed,
        cardSelection,
        revealClosedDetails,
        revealScrolled,
        enabledAfterSelection,
        runRequested: Boolean(runRequest),
        postRequests,
        runsReadsBeforeRefresh,
        runsReadsAfterRefresh,
        confirmCount,
        errors,
    }));
})().catch((error) => {
    process.stderr.write(error.stack || String(error));
    process.exitCode = 1;
});
"""


def _run_page_scenario() -> dict[str, Any]:
    if NODE is None:
        raise RuntimeError("Node.js is unavailable")
    completed = subprocess.run(
        [
            NODE,
            "-e",
            PAGE_RUNNER,
            str(TASKS_HTML),
            str(TASKS_SCRIPT),
            str(TASK_MODEL),
            str(MONITOR_MODEL),
        ],
        check=True,
        capture_output=True,
        encoding="utf-8",
        text=True,
    )
    return json.loads(completed.stdout)


@pytest.mark.skipif(
    NODE is None,
    reason="Node.js is required to exercise the task selection page",
)
def test_selection_page_initializes_selects_runs_and_refreshes_without_launch() -> None:
    result = _run_page_scenario()

    assert result["errors"] == []
    assert result["selectionBefore"] == ""
    assert result["clearedSelection"] == ""
    assert result["dropdownSelection"] == "task-1"
    assert result["dropdownCardPressed"] is True
    assert result["cardSelection"] == "task-1"
    assert result["revealClosedDetails"] is True
    assert result["revealScrolled"] is True
    assert result["enabledAfterSelection"] is True
    assert result["runRequested"] is True
    assert result["confirmCount"] == 0
    assert result["runsReadsAfterRefresh"] > result["runsReadsBeforeRefresh"]
    assert len(result["postRequests"]) == 1
    assert result["postRequests"][0] == {
        "path": "/v1/tasks/task-1/runs",
        "method": "POST",
    }
    assert set(result["initialRequests"]) == {
        "/v1/tasks",
        "/v1/task-folders",
        "/v1/task-triggers",
        "/v1/task-modules",
        "/v1/task-runs",
    }
