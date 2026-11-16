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


def between(source: str, start: str, end: str) -> str:
    start_index = source.index(start)
    content_start = start_index + len(start)
    return source[content_start:source.index(end, content_start)]


def test_task_workbench_keeps_two_blank_panels() -> None:
    html = read("tasks.html")
    workbench = between(
        html,
        'id="task-selection-area"',
        '<dialog id="task-run-details"',
    )

    assert workbench.index('id="task-display-area"') < workbench.index(
        'class="panel task-selection-panel"'
    )
    display = between(workbench, 'id="task-display-area"', "</section>")
    assert 'id="task-display-title">任务展示</h2>' in display
    assert "<p" not in display
    for removed in ("<button", "<input", "<select", "<textarea"):
        assert removed not in display

    selection = between(
        workbench,
        'class="panel task-selection-panel"',
        "</section>",
    )
    assert selection.count("<div") == 1
    assert selection.count("<h2") == 1
    assert '<div class="panel-heading">' in selection
    assert 'id="task-list-title">任务选择</h2>' in selection
    for removed in ("<button", "<input", "<select", "<textarea", "<h3"):
        assert removed not in selection

    styles = read("styles.css")
    display_style = between(styles, ".task-display-placeholder {", "}")
    selection_style = between(styles, ".task-selection-panel {", "}")
    headings = between(
        styles,
        ".task-display-placeholder .panel-heading,",
        "}",
    )
    assert "min-height: clamp(14rem, 30dvh, 22rem);" in display_style
    assert "min-height: 10rem;" in selection_style
    assert "margin-bottom: 0;" in headings


def test_task_page_removes_task_management_and_launch_controls() -> None:
    html = read("tasks.html")

    for removed in (
        'id="task-list-count"',
        'id="task-folder-list"',
        'id="new-folder-button"',
        'id="task-folder-form"',
        'id="task-folder-name"',
        'id="delete-folder-button"',
        'id="task-search"',
        'id="task-filter-all"',
        'id="task-filter-normal"',
        'id="task-filter-interruption"',
        'data-task-filter="ALL"',
        'data-task-filter="NORMAL"',
        'data-task-filter="INTERRUPTION"',
        'id="task-list"',
        'id="task-run-panel"',
        'id="run-task-select"',
        'id="run-priority-select"',
        'id="run-task-button"',
        'id="task-launch-note"',
    ):
        assert removed not in html
    for removed_text in (
        "选择一个任务开始",
        "新建文件夹",
        "搜索任务",
        "普通任务",
        "加入运行队列",
        "运行任务",
        "请选择任务",
    ):
        assert removed_text not in html

    for retained in (
        'id="task-queue-list"',
        'id="task-log-list"',
        'id="refresh-runs-button"',
        'id="task-run-details"',
        'id="runtime-list"',
    ):
        assert retained in html


def test_task_page_loads_only_the_monitor_script_stack() -> None:
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


def test_tasks_script_is_read_only_monitoring() -> None:
    javascript = read("tasks.js")
    state = between(javascript, "const state = {", "};")

    for retained in (
        "triggerStatuses: []",
        "modules: []",
        "runs: []",
        "queue: []",
        "logScrollPaused: false",
    ):
        assert retained in state
    for removed in (
        "tasks: []",
        "folders: []",
        "triggers: []",
        "selectedId",
        "runTaskId",
        "query:",
        "taskFilter",
        "folderFilter",
        "folderEditorId",
    ):
        assert removed not in state

    for removed_path in (
        '"/v1/tasks"',
        '"/v1/tasks/',
        '"/v1/task-folders"',
        '"/v1/task-triggers"',
    ):
        assert removed_path not in javascript
    for removed_function in (
        "selectTask",
        "renderTaskList",
        "renderTaskFolders",
        "saveFolder",
        "deleteFolder",
        "renderRunControls",
        "updateRunActions",
        "runTask",
        "isInterruptionTask",
        "matchesTaskFilter",
    ):
        assert removed_function not in javascript
    assert "Model.priorityRank" not in javascript
    assert "Model.priorityLabel(" in javascript

    load_all = between(javascript, "async function loadAll()", "function isActiveRun")
    assert "Promise.all([" in load_all
    assert '"/v1/task-modules"' in load_all
    assert '"/v1/task-runs"' in load_all
    assert load_all.count("Stowling.request(") == 2
    for retained in ("renderRuntime();", "renderQueue();", "appendMonitorLogs();"):
        assert retained in load_all

    load_runs = between(javascript, "async function loadRuns()", "function renderRuntime")
    assert 'Stowling.request("/v1/task-runs")' in load_runs
    assert load_runs.count("Stowling.request(") == 1
    for retained in ("renderRuntime();", "renderQueue();", "appendMonitorLogs();"):
        assert retained in load_runs
    assert "updateRunActions" not in javascript


def test_queue_and_details_use_static_task_names_and_keep_recovery() -> None:
    javascript = read("tasks.js")
    queue = between(javascript, "function renderQueue()", "function railAction")
    runtime = between(javascript, "function runtimeCard(run)", "function renderQueue")

    assert 'Stowling.element("strong", "rail-task-name", entry.taskName)' in queue
    assert 'Stowling.element("strong", "", run.taskName)' in runtime
    assert '"选择任务"' not in javascript
    for retained in (
        "retryInterruption(entry.run.runId)",
        "skipInterruption(entry.run.runId)",
        "cancelRun(entry.run.runId)",
        'entry.state === "QUEUED" ? "取消排队" : "取消任务"',
    ):
        assert retained in queue
    for retained in (
        "失败原因：",
        "retryInterruption(run.runId)",
        "skipInterruption(run.runId)",
        "cancelRun(run.runId)",
    ):
        assert retained in runtime


def test_interruption_recovery_and_monitor_endpoints_are_retained() -> None:
    javascript = read("tasks.js")

    assert "/v1/task-runs/${encodeURIComponent(runId)}/cancel" in javascript
    assert "/retry-interruption" in javascript
    assert "/skip-interruption" in javascript
    assert "window.confirm(" in javascript
    assert "确认现在尝试跳过？" in javascript
    assert "同步失败 · 显示上次状态" in javascript
    assert 'Stowling.onConnected(() => loadAll());' in javascript


PAGE_RUNNER = r"""
const fs = require("node:fs");
const vm = require("node:vm");

const [htmlPath, tasksPath, modelPath, monitorPath] = process.argv.slice(1);
const html = fs.readFileSync(htmlPath, "utf8");
const requests = [];
const connectedHandlers = [];
const errors = [];
let runReads = 0;

class FakeElement {
    constructor(tagName = "div", id = "") {
        this.tagName = String(tagName).toUpperCase();
        this.id = id;
        this.className = "";
        this.children = [];
        this.listeners = new Map();
        this.dataset = {};
        this.disabled = false;
        this.open = false;
        this.scrollTop = 0;
        this.scrollHeight = 0;
        this._text = "";
        const classes = new Set();
        this.classList = {
            add: (...names) => names.forEach((name) => classes.add(name)),
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

    get childElementCount() {
        return this.children.length;
    }

    append(...nodes) {
        for (const node of nodes) {
            const child = typeof node === "string"
                ? Object.assign(new FakeElement("#text"), { _text: node })
                : node;
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

    click() {
        const event = { target: this, currentTarget: this };
        for (const listener of this.listeners.get("click") || []) {
            listener(event);
        }
    }

    querySelector() {
        return null;
    }
}

const ids = [...html.matchAll(/\bid="([^"]+)"/g)].map((match) => match[1]);
const byId = new Map(ids.map((id) => [id, new FakeElement("div", id)]));
const document = {
    body: new FakeElement("body"),
    createElement: (tagName) => new FakeElement(tagName),
    querySelector: (selector) => {
        if (!selector.startsWith("#")) {
            return null;
        }
        const id = selector.slice(1);
        if (!byId.has(id)) {
            throw new Error(`tasks.js requested missing DOM node #${id}`);
        }
        return byId.get(id);
    },
};

const runResponses = [
    {
        runs: [{ runId: "run-1", taskName: "初始任务", state: "RUNNING",
            priority: "URGENT", completedSteps: 1, totalSteps: 2 }],
        queue: [],
        triggers: [],
    },
    {
        runs: [{ runId: "run-2", taskName: "刷新任务", state: "WAITING",
            priority: "NORMAL", completedSteps: 0, totalSteps: 1 }],
        queue: [],
        triggers: [],
    },
];

const Stowling = {
    element: (tagName, className = "", text = "") => {
        const element = new FakeElement(tagName);
        element.className = className;
        element.textContent = text;
        return element;
    },
    emptyState: (text) => Stowling.element("p", "empty-state", text),
    formatDate: () => "2026-11-16 00:00",
    handleError: (error, context) => errors.push(`${context}: ${error}`),
    request: async (path, options = {}) => {
        const method = String(options.method || "GET").toUpperCase();
        if (method !== "GET") {
            throw new Error(`unexpected ${method} request ${path}`);
        }
        requests.push({ path, method });
        if (path === "/v1/task-modules") {
            return { modules: [{ id: "MOVE", name: "移动" }] };
        }
        if (path === "/v1/task-runs") {
            const response = runResponses[Math.min(runReads, runResponses.length - 1)];
            runReads += 1;
            return response;
        }
        throw new Error(`unexpected request ${path}`);
    },
    onConnected: (handler) => connectedHandlers.push(handler),
    isConnected: () => true,
    mountPage: () => {},
    showToast: () => {},
};

global.document = document;
global.window = {
    Stowling,
    setInterval: () => 1,
    setTimeout: () => 1,
    confirm: () => true,
};

for (const path of [modelPath, monitorPath, tasksPath]) {
    vm.runInThisContext(fs.readFileSync(path, "utf8"), { filename: path });
}

const tick = () => new Promise((resolve) => setTimeout(resolve, 0));

(async () => {
    for (const handler of connectedHandlers) {
        await handler();
    }
    await tick();

    const initialRequests = requests.map((entry) => `${entry.method} ${entry.path}`);
    const queueList = document.querySelector("#task-queue-list");
    const initialQueueText = queueList.textContent;
    const syncMessage = document.querySelector("#task-monitor-message").textContent;

    document.querySelector("#refresh-runs-button").click();
    await tick();
    await tick();

    const refreshedQueueText = queueList.textContent;
    const postRequests = requests.filter((entry) => entry.method !== "GET");
    process.stdout.write(JSON.stringify({
        initialRequests,
        runReads,
        initialQueueText,
        refreshedQueueText,
        syncMessage,
        postRequests,
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
    reason="Node.js is required to exercise the task monitor page",
)
def test_monitor_initializes_and_refreshes_with_run_reads_only() -> None:
    result = _run_page_scenario()

    assert result["errors"] == []
    assert result["initialRequests"] == [
        "GET /v1/task-modules",
        "GET /v1/task-runs",
    ]
    assert result["runReads"] == 2
    assert "初始任务" in result["initialQueueText"]
    assert "刷新任务" in result["refreshedQueueText"]
    assert result["syncMessage"].startswith("已同步 · ")
    assert result["postRequests"] == []
