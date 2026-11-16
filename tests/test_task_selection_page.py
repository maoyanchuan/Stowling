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
COORDINATE_MODEL = WEB / "coordinate-model.js"
MOVE_MODEL = WEB / "move-task-model.js"
MONITOR_MODEL = WEB / "task-monitor-model.js"
NODE = shutil.which("node")


def read(name: str) -> str:
    return (WEB / name).read_text(encoding="utf-8")


def between(source: str, start: str, end: str) -> str:
    start_index = source.index(start)
    content_start = start_index + len(start)
    return source[content_start:source.index(end, content_start)]


def test_task_workbench_has_a_fixed_move_form_and_task_cards() -> None:
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
    assert 'id="move-task-form"' in display
    assert 'id="move-task-fields"' in display
    for field_id in (
        "move-task-name",
        "move-target-x",
        "move-target-y",
        "move-target-z",
        "move-saved-coordinate",
        "move-priority",
        "save-move-task",
        "run-move-task",
    ):
        assert f'id="{field_id}"' in display
    assert "data-move-source=\"manual\"" in display
    assert "data-move-source=\"saved\"" in display
    assert "保存任务" in display
    assert "保存并加入队列" in display

    selection = between(
        workbench,
        'class="panel task-selection-panel"',
        "</section>",
    )
    assert 'id="task-list-title">任务选择</h2>' in selection
    assert 'id="new-move-task"' in selection
    assert 'id="move-task-list"' in selection
    assert 'id="refresh-move-tasks"' in selection
    assert "添加移动任务" not in selection
    assert "新建一个单步移动任务" in selection


def test_task_page_keeps_only_the_fixed_move_editor_and_monitor() -> None:
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
        'id="task-editor-title"',
        'id="task-draft-status"',
    ):
        assert removed not in html
    for removed_text in (
        "选择一个任务开始",
        "新建文件夹",
        "搜索任务",
        "普通任务",
        "运行任务",
        "请选择任务",
        "编辑配置 · 不影响已在运行的任务",
    ):
        assert removed_text not in html

    for retained in (
        'id="task-queue-list"',
        'id="task-log-list"',
        'id="refresh-runs-button"',
        'id="task-run-details"',
        'id="runtime-list"',
        'id="move-task-form"',
        'id="move-task-list"',
    ):
        assert retained in html


def test_task_page_loads_model_monitor_and_fixed_move_stack() -> None:
    html = read("tasks.html")

    scripts = (
        "/v2-task-model.js",
        "/coordinate-model.js",
        "/move-task-model.js",
        "/task-monitor-model.js",
        "/tasks.js",
    )
    positions = [html.index(f'<script src="{script}" defer></script>') for script in scripts]
    assert positions == sorted(positions)
    assert '<script src="/common.js" defer></script>' in html
    for removed in (
        "v2-task-editor.js",
        "v2-trigger-model.js",
    ):
        assert removed not in html


def test_move_resources_are_loaded_separately_from_monitoring() -> None:
    javascript = read("tasks.js")
    state = between(javascript, "const state = {", "};")
    load_all = between(javascript, "async function loadAll()", "function isActiveRun")
    load_move = between(
        javascript,
        "async function loadMoveResources()",
        "function renderMoveCoordinates()",
    )

    for retained in (
        "triggerStatuses: []",
        "modules: []",
        "runs: []",
        "queue: []",
        "moveRevision: \"\"",
        "moveTasks: []",
        "coordinates: []",
        "moveDraft: Move.newDraft()",
    ):
        assert retained in state
    for removed in (
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

    assert "Promise.all([" in load_all
    assert '"/v1/task-modules"' in load_all
    assert '"/v1/task-runs"' in load_all
    assert load_all.count("Stowling.request(") == 2
    for removed in ('"/v1/tasks"', '"/v1/coordinates"'):
        assert removed not in load_all
    for retained in ("renderRuntime();", "renderQueue();", "appendMonitorLogs();"):
        assert retained in load_all

    assert "Promise.all([" in load_move
    assert '"/v1/tasks"' in load_move
    assert '"/v1/coordinates"' in load_move
    assert load_move.count("Stowling.request(") == 2
    assert ".map(Model.normalizeTask).filter(Move.isMoveTask)" in load_move
    assert "Coordinates.normalizeCoordinates(coordinates).coordinates" in load_move
    assert "if (state.moveDraft.id && !state.moveDirty)" in load_move
    assert "state.moveTasks.find(" in load_move
    assert "state.moveDraft = Move.draftFromTask(refreshed);" in load_move
    assert "syncMoveForm();" in load_move
    assert "renderMoveTaskList();" in load_move
    assert 'Stowling.onConnected(async () => {\n' in javascript
    assert "await Promise.all([loadAll(), loadMoveResources()]);" in javascript


def test_move_save_and_queue_routes_use_explicit_task_ids() -> None:
    javascript = read("tasks.js")
    save = between(javascript, "async function saveMoveTask(", "async function loadAll()")
    apply_save = between(
        javascript,
        "function applyMoveSave(response, existingId)",
        "async function saveMoveTask(",
    )

    assert "const id = existingId || String(response?.taskId || \"\");" in apply_save
    assert "response.tasks.map(Model.normalizeTask).filter(Move.isMoveTask)" in apply_save
    assert "tasks.find((task) => task.id === id)" in apply_save
    assert "state.moveTasks = tasks;" in apply_save
    assert "applyMoveSave(response, task.id);" in save
    assert "method: task.id ? \"PUT\" : \"POST\"" in save
    assert "{ ...payload, revision: state.moveRevision }" in save
    assert "`/v1/tasks/${encodeURIComponent(state.moveDraft.id)}/runs`" in save
    assert "body: JSON.stringify({})" in save
    assert "/v1/coordinates" not in save
    assert "method: \"POST\"" in save
    assert '"/v1/task-folders"' not in javascript
    assert '"/v1/task-triggers"' not in javascript


def test_move_form_uses_input_events_and_preserves_both_source_values() -> None:
    javascript = read("tasks.js")
    read_form = between(
        javascript,
        "function readMoveForm()",
        "function updateMoveTarget()",
    )
    source_handler = between(
        javascript,
        "for (const button of elements.moveSources)",
        "elements.saveMove.addEventListener",
    )

    assert 'elements.moveForm.addEventListener("input", () => {' in javascript
    assert 'elements.moveX.value = draft.x.replace(/^\+/, "");' in javascript
    assert "readMoveForm();" in source_handler
    assert "state.moveDraft.source = button.dataset.moveSource;" in source_handler
    assert "x: elements.moveX.value" in read_form
    assert "y: elements.moveY.value" in read_form
    assert "z: elements.moveZ.value" in read_form
    assert "coordinateId: elements.moveCoordinate.value" in read_form
    assert ".moveAxisGrid" not in read_form
    assert "elements.moveX.value = \"\";" not in source_handler
    assert "elements.moveCoordinate.value = \"\";" not in source_handler


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


PAGE_RUNNER = r"""
const fs = require("node:fs");
const vm = require("node:vm");

const [
    htmlPath,
    tasksPath,
    taskModelPath,
    coordinateModelPath,
    moveModelPath,
    monitorPath,
] = process.argv.slice(1);
const html = fs.readFileSync(htmlPath, "utf8");
const requests = [];
const connectedHandlers = [];
const sourceButtons = [];
const errors = [];
let runReads = 0;
let taskSequence = 0;
let failNextRun = false;
let revision = "rev-1";
let tasksState = [];

class FakeElement {
    constructor(tagName = "div", id = "") {
        this.tagName = String(tagName).toUpperCase();
        this.id = id;
        this.className = "";
        this.children = [];
        this.listeners = new Map();
        this.dataset = {};
        this.attributes = new Map();
        this.disabled = false;
        this.hidden = false;
        this.open = false;
        this.value = "";
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

    prepend(...nodes) {
        const added = nodes.map((node) => typeof node === "string"
            ? Object.assign(new FakeElement("#text"), { _text: node })
            : node);
        this.children.unshift(...added);
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

    dispatchEvent(type, values = {}) {
        let defaultPrevented = false;
        const event = {
            target: this,
            currentTarget: this,
            ...values,
            preventDefault() {
                defaultPrevented = true;
            },
        };
        for (const listener of this.listeners.get(type) || []) {
            listener(event);
        }
        return { ...event, defaultPrevented };
    }

    click() {
        this.dispatchEvent("click");
    }

    setAttribute(name, value) {
        this.attributes.set(name, String(value));
    }

    getAttribute(name) {
        return this.attributes.get(name) ?? null;
    }

    querySelector(selector) {
        if (!selector.startsWith(".")) {
            return null;
        }
        const className = selector.slice(1);
        return this.children.find((child) => child.className.split(" ").includes(className))
            || null;
    }

    querySelectorAll(selector) {
        if (selector !== "button") {
            return [];
        }
        return this.children.filter((child) => child.tagName === "BUTTON");
    }

    showModal() {
        this.open = true;
    }

    close() {
        this.open = false;
    }

    getBoundingClientRect() {
        return { left: 0, right: 0, top: 0, bottom: 0 };
    }
}

const ids = [...html.matchAll(/\bid="([^"]+)"/g)].map((match) => match[1]);
const byId = new Map(ids.map((id) => [id, new FakeElement("div", id)]));
for (const source of ["manual", "saved"]) {
    const button = new FakeElement("button");
    button.dataset.moveSource = source;
    sourceButtons.push(button);
}
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
    querySelectorAll: (selector) => selector === "[data-move-source]"
        ? sourceButtons : [],
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

function moveTask(id, name, parameters, priority = "NORMAL", mode = "DEFAULT") {
    return {
        id,
        name,
        folderId: "folder-1",
        priority,
        repeatWhenIdle: false,
        interruptionMode: mode,
        interruptionTaskRef: "",
        defaultInterruptionFlow: false,
        requeueAfterInterruption: false,
        steps: [{
            id: `step-${id}`,
            kind: "ACTION",
            moduleId: "MOVE_TO_COORDINATE",
            parameters,
            steps: [],
            otherwise: [],
        }],
    };
}

tasksState = [
    moveTask("existing-manual", "已有手动移动", { x: "3", y: "4", z: "5" }),
    moveTask("existing-saved", "已有保存移动", {
        targetRef: "coordinate:coordinate-1",
    }, "URGENT", "NONE"),
    {
        id: "complex-task",
        name: "复杂任务",
        priority: "NORMAL",
        steps: [{
            kind: "ACTION",
            moduleId: "ASSERT_NO_MANAGED_ITEMS",
            parameters: {},
            steps: [],
            otherwise: [],
        }],
    },
];

const Stowling = {
    clone: (value) => JSON.parse(JSON.stringify(value)),
    element: (tagName, className = "", text = "") => {
        const element = new FakeElement(tagName);
        element.className = className;
        element.textContent = text;
        return element;
    },
    emptyState: (text) => Stowling.element("p", "empty-state", text),
    formatDate: () => "2026-11-16 00:00",
    handleError: (error, context) => errors.push(`${context}: ${error}`),
    showToast: () => {},
    request: async (path, options = {}) => {
        const method = String(options.method || "GET").toUpperCase();
        const body = options.body ? JSON.parse(options.body) : null;
        requests.push({ path, method, body });
        if (method === "GET") {
            if (path === "/v1/task-modules") {
                return { modules: [{ id: "MOVE_TO_COORDINATE", name: "移动到坐标" }] };
            }
            if (path === "/v1/task-runs") {
                const response = runResponses[Math.min(runReads, runResponses.length - 1)];
                runReads += 1;
                return response;
            }
            if (path === "/v1/tasks") {
                return { revision, tasks: tasksState };
            }
            if (path === "/v1/coordinates") {
                return {
                    revision,
                    coordinates: [{
                        id: "coordinate-1",
                        name: "保存点",
                        x: 10,
                        y: 64,
                        z: 20,
                    }],
                };
            }
            throw new Error(`unexpected ${method} request ${path}`);
        }
        if (method === "POST" && path === "/v1/tasks") {
            taskSequence += 1;
            const taskId = `server-created-id-${taskSequence}`;
            revision = `rev-${revision.slice(4) ? Number(revision.slice(4)) + 1 : 2}`;
            tasksState = [...tasksState, { id: taskId, ...body }];
            return { taskId, revision, tasks: tasksState };
        }
        if (method === "PUT" && path.startsWith("/v1/tasks/")) {
            const taskId = decodeURIComponent(path.split("/").pop());
            revision = `rev-${revision.slice(4) ? Number(revision.slice(4)) + 1 : 2}`;
            tasksState = tasksState.map((task) => task.id === taskId
                ? { id: taskId, ...body } : task);
            return { revision, tasks: tasksState };
        }
        if (method === "POST" && path.endsWith("/runs")) {
            if (failNextRun) {
                failNextRun = false;
                throw new Error("queue unavailable");
            }
            return { state: "QUEUED" };
        }
        throw new Error(`unexpected ${method} request ${path}`);
    },
    onConnected: (handler) => connectedHandlers.push(handler),
    isConnected: () => true,
    mountPage: () => {},
};

global.document = document;
global.window = {
    Stowling,
    setInterval: () => 1,
    setTimeout: () => 1,
    confirm: () => true,
};

for (const path of [
    taskModelPath,
    coordinateModelPath,
    moveModelPath,
    monitorPath,
    tasksPath,
]) {
    vm.runInThisContext(fs.readFileSync(path, "utf8"), { filename: path });
}

const tick = () => new Promise((resolve) => setTimeout(resolve, 0));
const settle = async () => {
    for (let index = 0; index < 6; index += 1) {
        await tick();
    }
};
const node = (id) => document.querySelector(`#${id}`);
const fill = (name, x, y, z, priority = "NORMAL") => {
    node("move-task-name").value = name;
    node("move-target-x").value = x;
    node("move-target-y").value = y;
    node("move-target-z").value = z;
    node("move-priority").value = priority;
    node("move-task-form").dispatchEvent("input");
};
const sourceButton = (source) => sourceButtons.find(
    (button) => button.dataset.moveSource === source
);
const moveButton = (taskId) => node("move-task-list").querySelectorAll("button")
    .find((button) => button.dataset.taskId === taskId);

(async () => {
    for (const handler of connectedHandlers) {
        await handler();
    }
    await settle();

    const initialRequests = requests.map((entry) => `${entry.method} ${entry.path}`);
    const initialQueueText = node("task-queue-list").textContent;
    const initialListIds = node("move-task-list").querySelectorAll("button")
        .map((button) => button.dataset.taskId);

    node("new-move-task").click();
    fill("新移动任务", "+1", "-2", "3", "URGENT");
    node("move-task-form").dispatchEvent("submit");
    await settle();
    const createdWrite = requests.find(
        (entry) => entry.method === "POST" && entry.path === "/v1/tasks"
    );
    const createRun = requests.find(
        (entry) => entry.method === "POST" && entry.path.endsWith("/runs")
    );
    const createdListIds = node("move-task-list").querySelectorAll("button")
        .map((button) => button.dataset.taskId);
    const createdFormX = node("move-target-x").value;

    node("new-move-task").click();
    fill("保存坐标移动", "11", "65", "21", "NORMAL");
    node("move-saved-coordinate").value = "coordinate-1";
    node("move-task-form").dispatchEvent("input");
    sourceButton("saved").click();
    const savedCoordinateAfterSwitch = node("move-saved-coordinate").value;
    sourceButton("manual").click();
    const manualAfterSwitch = {
        x: node("move-target-x").value,
        y: node("move-target-y").value,
        z: node("move-target-z").value,
        source: sourceButton("manual").getAttribute("aria-pressed"),
    };
    sourceButton("saved").click();
    const savedAfterSwitch = {
        coordinateId: node("move-saved-coordinate").value,
        source: sourceButton("saved").getAttribute("aria-pressed"),
    };
    node("save-move-task").click();
    await settle();
    const savedWrite = requests.filter(
        (entry) => entry.method === "POST" && entry.path === "/v1/tasks"
    ).at(-1);

    node("new-move-task").click();
    fill("无效移动任务", "bad", "", "1.5", "NORMAL");
    const writesBeforeInvalid = requests.filter(
        (entry) => entry.method !== "GET"
    ).length;
    node("save-move-task").click();
    await settle();
    const invalidMessage = node("move-task-message").textContent;
    const writesAfterInvalid = requests.filter(
        (entry) => entry.method !== "GET"
    ).length;

    moveButton("existing-saved").click();
    node("move-task-name").value = "更新后的保存移动";
    node("move-task-form").dispatchEvent("input");
    node("save-move-task").click();
    await settle();
    const putWrite = requests.find((entry) => entry.method === "PUT");

    node("new-move-task").click();
    fill("入队失败任务", "7", "8", "9", "BACKGROUND");
    const taskWritesBeforeQueueFailure = requests.filter(
        (entry) => entry.method === "POST" && entry.path === "/v1/tasks"
    ).length;
    failNextRun = true;
    node("move-task-form").dispatchEvent("submit");
    await settle();
    const queueFailureMessage = node("move-task-message").textContent;
    const taskWritesAfterQueueFailure = requests.filter(
        (entry) => entry.method === "POST" && entry.path === "/v1/tasks"
    ).length;
    const runRequestsBeforeRetry = requests.filter(
        (entry) => entry.method === "POST" && entry.path.endsWith("/runs")
    ).length;
    node("move-task-form").dispatchEvent("submit");
    await settle();
    const taskWritesAfterQueueRetry = requests.filter(
        (entry) => entry.method === "POST" && entry.path === "/v1/tasks"
    ).length;
    const runRequestsAfterRetry = requests.filter(
        (entry) => entry.method === "POST" && entry.path.endsWith("/runs")
    ).length;
    const queueRetryMessage = node("move-task-message").textContent;

    moveButton("existing-manual").click();
    const cleanSourceTask = tasksState.find((task) => task.id === "existing-manual");
    cleanSourceTask.steps[0].parameters.x = "30";
    revision = "rev-move-refresh";
    node("refresh-move-tasks").click();
    await settle();
    const refreshedCleanX = node("move-target-x").value;

    node("move-target-x").value = "42";
    node("move-task-form").dispatchEvent("input");
    cleanSourceTask.steps[0].parameters.x = "99";
    revision = "rev-move-refresh-dirty";
    node("refresh-move-tasks").click();
    await settle();
    const refreshedDirtyX = node("move-target-x").value;
    const refreshedDirtyMessage = node("move-task-message").textContent;

    const requestCountBeforeRefresh = requests.length;
    node("refresh-runs-button").click();
    await settle();
    const refreshRequests = requests.slice(requestCountBeforeRefresh)
        .map((entry) => `${entry.method} ${entry.path}`);

    const finalListIds = node("move-task-list").querySelectorAll("button")
        .map((button) => button.dataset.taskId);
    const writeRequests = requests.filter((entry) => entry.method !== "GET")
        .map((entry) => `${entry.method} ${entry.path}`);
    process.stdout.write(JSON.stringify({
        initialRequests,
        initialQueueText,
        initialListIds,
        createdWrite,
        createRun,
        createdListIds,
        createdFormX,
        savedCoordinateAfterSwitch,
        manualAfterSwitch,
        savedAfterSwitch,
        savedWrite,
        invalidMessage,
        writesBeforeInvalid,
        writesAfterInvalid,
        putWrite,
        taskWritesBeforeQueueFailure,
        taskWritesAfterQueueFailure,
        taskWritesAfterQueueRetry,
        runRequestsBeforeRetry,
        runRequestsAfterRetry,
        queueFailureMessage,
        queueRetryMessage,
        refreshedCleanX,
        refreshedDirtyX,
        refreshedDirtyMessage,
        writeRequests,
        refreshRequests,
        finalListIds,
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
            str(COORDINATE_MODEL),
            str(MOVE_MODEL),
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
    reason="Node.js is required to exercise the fixed move task page",
)
def test_fixed_move_page_save_run_switch_invalid_put_and_queue_retry() -> None:
    result = _run_page_scenario()

    assert len(result["errors"]) == 1
    assert result["errors"][0].startswith("加入队列失败:")
    initial = result["initialRequests"]
    for expected in (
        "GET /v1/task-modules",
        "GET /v1/task-runs",
        "GET /v1/tasks",
        "GET /v1/coordinates",
    ):
        assert initial.count(expected) == 1
    assert result["initialListIds"] == ["existing-manual", "existing-saved"]
    assert "初始任务" in result["initialQueueText"]

    assert result["createdWrite"]["method"] == "POST"
    assert "id" not in result["createdWrite"]["body"]
    assert result["createdWrite"]["body"]["name"] == "新移动任务"
    assert result["createdWrite"]["body"]["steps"][0]["parameters"] == {
        "x": "+1",
        "y": "-2",
        "z": "3",
    }
    assert result["createRun"]["path"] == "/v1/tasks/server-created-id-1/runs"
    assert result["createRun"]["body"] == {}
    assert "server-created-id-1" in result["createdListIds"]
    assert result["createdFormX"] == "1"
    assert "complex-task" not in result["createdListIds"]

    assert result["savedCoordinateAfterSwitch"] == "coordinate-1"
    assert result["manualAfterSwitch"] == {
        "x": "11",
        "y": "65",
        "z": "21",
        "source": "true",
    }
    assert result["savedAfterSwitch"] == {
        "coordinateId": "coordinate-1",
        "source": "true",
    }
    assert result["savedWrite"]["body"]["steps"][0]["parameters"] == {
        "targetRef": "coordinate:coordinate-1",
    }

    assert result["writesBeforeInvalid"] == result["writesAfterInvalid"]
    assert "X 坐标需为整数" in result["invalidMessage"]
    assert "请填写 Y 坐标" in result["invalidMessage"]
    assert "Z 坐标需为整数" in result["invalidMessage"]

    assert result["putWrite"]["method"] == "PUT"
    assert result["putWrite"]["path"] == "/v1/tasks/existing-saved"
    assert result["putWrite"]["body"]["revision"] == "rev-3"
    assert result["putWrite"]["body"]["name"] == "更新后的保存移动"

    assert (
        result["taskWritesAfterQueueFailure"]
        == result["taskWritesBeforeQueueFailure"] + 1
    )
    assert result["taskWritesAfterQueueRetry"] == result["taskWritesAfterQueueFailure"]
    assert "任务已保存，但加入队列失败" in result["queueFailureMessage"]
    assert result["runRequestsAfterRetry"] == result["runRequestsBeforeRetry"] + 1
    assert "任务已加入队列" in result["queueRetryMessage"]
    assert result["refreshedCleanX"] == "30"
    assert result["refreshedDirtyX"] == "42"
    assert "未保存修改" in result["refreshedDirtyMessage"]
    assert result["refreshRequests"] == ["GET /v1/task-runs"]
    assert "complex-task" not in result["finalListIds"]
