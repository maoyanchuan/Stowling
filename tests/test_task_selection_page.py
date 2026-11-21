import json
import re
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


def opening_tag(source: str, element_id: str) -> str:
    pattern = rf'<[^>]*\bid="{re.escape(element_id)}"[^>]*>'
    match = re.search(pattern, source)
    assert match is not None
    return match.group(0)


def test_task_workbench_has_saved_task_disclosure_and_new_task_entry() -> None:
    html = read("tasks.html")
    workbench = between(
        html,
        'id="task-selection-area"',
        '<dialog id="task-run-details"',
    )

    assert workbench.index('id="task-display-area"') < workbench.index(
        'class="panel task-selection-panel"'
    )
    display = between(
        workbench,
        'id="task-display-area"',
        '<section class="panel task-selection-panel"',
    )
    assert 'id="task-display-title">已保存任务</h2>' in display
    for retained in (
        'id="move-task-list"',
        'id="refresh-move-tasks"',
        'id="move-resources-message"',
        'id="move-task-count"',
        'id="move-task-placeholder"',
        'id="move-task-draft"',
        'id="toggle-move-draft"',
        'id="move-draft-body"',
        'id="move-task-form"',
        'id="auto-task-list"',
        'id="auto-resources-message"',
        'id="auto-task-draft"',
        'id="toggle-auto-draft"',
        'id="auto-draft-body"',
        'id="auto-task-form"',
    ):
        assert retained in display
    assert 'id="new-move-task"' not in display
    assert 'id="move-task-fields"' in display
    for field_id in (
        "move-task-name",
        "move-target-x",
        "move-target-y",
        "move-target-z",
        "move-saved-coordinate",
        "move-priority",
        "save-move-task",
    ):
        assert f'id="{field_id}"' in display
    assert "data-move-source=\"manual\"" in display
    assert "data-move-source=\"saved\"" in display
    assert "保存任务" in display
    assert "保存并加入队列" not in display
    assert "hidden" in opening_tag(display, "move-task-form")
    assert "hidden" in opening_tag(display, "move-task-draft")
    assert "hidden" in opening_tag(display, "move-draft-body")
    toggle = opening_tag(display, "toggle-move-draft")
    assert 'aria-expanded="false"' in toggle

    selection = between(
        workbench,
        'class="panel task-selection-panel"',
        "</section>",
    )
    assert 'id="task-list-title">任务选择</h2>' in selection
    assert 'id="new-move-task"' in selection
    assert 'id="new-auto-task"' in selection
    warehouse_card = between(selection, 'id="new-robot-warehouse"', "</button>")
    assert 'class="move-task-option is-new"' in warehouse_card
    assert 'aria-controls="robot-warehouse-draft"' in warehouse_card
    assert "＋ 机器人仓库" in warehouse_card
    assert selection.index('id="new-material-task"') < selection.index('id="new-robot-warehouse"')
    assert "移动到坐标" in selection
    assert "自动分类" in selection
    for moved in (
        'id="move-task-list"',
        'id="refresh-move-tasks"',
        'id="move-resources-message"',
        'id="move-task-form"',
        'id="auto-task-list"',
        'id="auto-task-draft"',
        'id="auto-task-form"',
    ):
        assert moved not in selection
    assert "添加移动任务" not in selection
    assert "设置目标坐标，保存或运行" in selection


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
        'id="move-task-count"',
        'id="move-task-draft"',
        'id="toggle-move-draft"',
        'id="move-draft-body"',
        'id="move-task-form"',
        'id="move-task-list"',
        'id="auto-task-list"',
        'id="auto-task-draft"',
        'id="auto-task-form"',
    ):
        assert retained in html


def test_task_page_loads_model_monitor_and_fixed_move_stack() -> None:
    html = read("tasks.html")

    scripts = (
        "/v2-task-model.js",
        "/coordinate-model.js",
        "/move-task-model.js",
        "/auto-classify-task-model.js",
        "/task-monitor-model.js",
        "/tasks.js",
        "/auto-classify-ui.js",
    )
    positions = [
        html.index(f'<script src="{script}" defer></script>')
        for script in scripts
    ]
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
        "moveAllTasks: []",
        "coordinates: []",
        "moveDraft: Move.newDraft()",
        "moveDraftActive: false",
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
    assert "const allTasks = Array.isArray(tasks?.tasks)" in load_move
    assert "? tasks.tasks.map(Model.normalizeTask) : [];" in load_move
    assert "state.moveAllTasks = allTasks;" in load_move
    assert "state.moveTasks = allTasks.filter(Move.isMoveTask);" in load_move
    assert "Coordinates.normalizeCoordinates(coordinates).coordinates" in load_move
    assert "if (state.moveDraft.id && !state.moveDirty)" in load_move
    assert "state.moveTasks.find(" in load_move
    assert "state.moveDraft = Move.draftFromTask(refreshed);" in load_move
    assert "state.moveDraft = Move.newDraft(state.moveAllTasks);" in load_move
    assert "state.moveDraftActive = false;" in load_move
    assert "state.moveFormOpen = false;" in load_move
    assert "syncMoveForm();" in load_move
    assert "renderMoveTaskList();" in load_move
    select_move = between(
        javascript,
        "function selectMoveTask(",
        "function toggleMoveDraft()",
    )
    assert "Move.newDraft(state.moveAllTasks)" in select_move
    assert 'Stowling.onConnected(async () => {\n' in javascript
    assert "await Promise.all([loadAll(), loadMoveResources()]);" in javascript


def test_move_save_and_queue_routes_use_explicit_task_ids() -> None:
    javascript = read("tasks.js")
    save = between(javascript, "async function saveMoveTask()", "async function queueMoveTask(")
    queue = between(javascript, "async function queueMoveTask(", "async function deleteMoveTask()")
    apply_save = between(
        javascript,
        "function applyMoveSave(response, existingId)",
        "async function saveMoveTask(",
    )

    assert "const id = existingId || String(response?.taskId || \"\");" in apply_save
    assert "const allTasks = Array.isArray(response?.tasks)" in apply_save
    assert "response.tasks.map(Model.normalizeTask)" in apply_save
    assert "const tasks = allTasks.filter(Move.isMoveTask);" in apply_save
    assert "tasks.find((task) => task.id === id)" in apply_save
    assert "state.moveAllTasks = allTasks;" in apply_save
    assert "state.moveTasks = tasks;" in apply_save
    assert "function isTaskNameConflict(error)" in javascript
    assert "return /^task name must be unique:\\s+.+$/.test(message);" in javascript
    assert "任务名称已存在，请使用其他名称" in save
    assert "state.moveAllTasks" in save
    assert "applyMoveSave(response, task.id);" in save
    assert "method: task.id ? \"PUT\" : \"POST\"" in save
    assert "{ ...payload, revision: state.moveRevision }" in save
    assert "/runs" not in save
    assert "`/v1/tasks/${encodeURIComponent(id)}/runs`" in queue
    assert "body: JSON.stringify({})" in queue
    assert "/v1/coordinates" not in save
    assert 'method: "POST"' in queue
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
        "elements.removeMove.addEventListener",
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


def test_move_disclosure_uses_row_controls_and_guards_disconnected_actions() -> None:
    javascript = read("tasks.js")
    disclosure = between(
        javascript,
        "function syncMoveDisclosure()",
        "function selectMoveTask(task = null)",
    )
    selection = between(
        javascript,
        "function selectMoveTask(task = null)",
        "function syncMoveForm()",
    )
    controls = between(
        javascript,
        "function updateMoveControls()",
        "function showMoveMessage(",
    )

    assert "elements.moveDraftCard.hidden = !newDraft;" in disclosure
    assert "elements.moveDraftBody.hidden = !newDraft || !state.moveFormOpen;" in disclosure
    placeholder = "elements.movePlaceholder.hidden = state.moveTasks.length > 0"
    assert placeholder + " || newDraft;" in disclosure
    same_task = selection.index("if (task && state.moveDraft.id === task.id)")
    dirty_confirm = selection.index(
        "if (state.moveDirty && !window.confirm("
    )
    assert same_task < dirty_confirm
    for retained in (
        "syncMoveDisclosure();",
        "state.moveDraftActive = !task;",
        "elements.moveDraftToggle.focus();",
        "function toggleMoveDraft()",
        "|| !Stowling.isConnected()",
    ):
        assert retained in selection
    assert "for (const [id, row] of moveRows)" in controls
    assert "row.button.disabled = disabled;" in controls
    assert "row.run.disabled = disabled || unsaved;" in controls
    assert "elements.newMove.disabled = disabled;" in controls
    assert "elements.moveDraftToggle.disabled = disabled;" in controls
    assert "elements.refreshMove.disabled = state.moveBusy || state.moveLoading" in controls
    assert 'elements.moveList.querySelectorAll("button")' not in javascript


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
const toasts = [];
const confirmRequests = [];
let runReads = 0;
let taskSequence = 0;
let failNextRun = false;
let failNextTaskWriteWithDuplicateName = false;
let confirmResult = true;
let revision = "rev-1";
let tasksState = [];

class FakeElement {
    constructor(tagName = "div", id = "") {
        this.tagName = String(tagName).toUpperCase();
        this.id = id;
        this.className = "";
        this.children = [];
        this.parentElement = null;
        this.listeners = new Map();
        this.dataset = {};
        this.attributes = new Map();
        this.disabled = false;
        this.hidden = false;
        this.focused = false;
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
        for (const child of this.children) {
            child.parentElement = null;
        }
        this._text = String(value);
        this.children = [];
    }

    get childElementCount() {
        return this.children.length;
    }

    /**
     * @param {FakeElement | string} node
     * @returns {FakeElement}
     */
    adopt(node) {
        const child = typeof node === "string"
            ? Object.assign(new FakeElement("#text"), { _text: node })
            : node;
        if (child.parentElement) {
            child.parentElement.removeChild(child);
        }
        child.parentElement = this;
        return child;
    }

    append(...nodes) {
        this.children.push(...nodes.map((node) => this.adopt(node)));
    }

    prepend(...nodes) {
        const added = nodes.map((node) => this.adopt(node));
        this.children.unshift(...added);
    }

    replaceChildren(...nodes) {
        for (const child of this.children) {
            child.parentElement = null;
        }
        this.children = [];
        this.append(...nodes);
    }

    /**
     * @param {FakeElement} node
     * @returns {FakeElement}
     */
    removeChild(node) {
        const index = this.children.indexOf(node);
        if (index < 0) {
            throw new Error("removeChild called with a node that is not a child");
        }
        this.children.splice(index, 1);
        node.parentElement = null;
        return node;
    }

    /**
     * @returns {void}
     */
    remove() {
        if (this.parentElement) {
            this.parentElement.removeChild(this);
        }
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

    /**
     * @returns {void}
     */
    focus() {
        this.focused = true;
    }

    setAttribute(name, value) {
        this.attributes.set(name, String(value));
    }

    getAttribute(name) {
        return this.attributes.get(name) ?? null;
    }

    /**
     * @param {string} selector
     * @returns {boolean}
     */
    matches(selector) {
        const tag = selector.match(/^[a-z][\w-]*/i)?.[0];
        if (tag && this.tagName !== tag.toUpperCase()) {
            return false;
        }
        for (const match of selector.matchAll(/\.([\w-]+)/g)) {
            if (!this.className.split(" ").includes(match[1])) {
                return false;
            }
        }
        return true;
    }

    /**
     * @returns {FakeElement[]}
     */
    descendants() {
        const found = [];
        for (const child of this.children) {
            found.push(child, ...child.descendants());
        }
        return found;
    }

    querySelector(selector) {
        return this.descendants().find((child) => child.matches(selector)) || null;
    }

    querySelectorAll(selector) {
        return this.descendants().filter((child) => child.matches(selector));
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

const byId = new Map();
const markupPattern = /<([a-z][\w-]*)([^>]*\bid="([^"]+)"[^>]*)>/gi;
for (const match of html.matchAll(markupPattern)) {
    const [, tagName, attributes, id] = match;
    const element = new FakeElement(tagName, id);
    const className = attributes.match(/\bclass="([^"]*)"/i)?.[1];
    if (className) {
        element.className = className;
    }
    element.hidden = /(?:^|\s)hidden(?:\s|=|$)/i.test(attributes);
    element.disabled = /(?:^|\s)disabled(?:\s|=|$)/i.test(attributes);
    for (const attribute of attributes.matchAll(/(aria-[\w-]+)="([^"]*)"/g)) {
        element.setAttribute(attribute[1], attribute[2]);
    }
    byId.set(id, element);
}
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
        name: " 移动到坐标 ",
        priority: "NORMAL",
        steps: [{
            kind: "ACTION",
            moduleId: "ASSERT_NO_MANAGED_ITEMS",
            parameters: {},
            steps: [],
            otherwise: [],
        }],
    },
    {
        id: "complex-task-2",
        name: "移动到坐标 2",
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
    showToast: (message) => toasts.push(message),
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
            if (path === "/v1/regions") {
                return { regions: [] };
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
            if (failNextTaskWriteWithDuplicateName) {
                failNextTaskWriteWithDuplicateName = false;
                throw new Error("task name must be unique: 并发冲突移动");
            }
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
    StowlingSettings: {reveal: (element, expanded) => { element.hidden = !expanded; }},
    StowlingTaskCleanupSettings: {create: () => ({
        resources() {},
        set() {},
        read() { return {}; },
    })},
    setInterval: () => 1,
    setTimeout: () => 1,
    confirm: (message) => {
        confirmRequests.push(message);
        return confirmResult;
    },
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
/**
 * @returns {FakeElement[]}
 */
const savedRows = () => node("move-task-list")
    .querySelectorAll("article.move-saved-task");
/**
 * @param {string} taskId
 * @returns {FakeElement | undefined}
 */
const savedRow = (taskId) => savedRows()
    .find((row) => row.dataset.taskId === taskId);
/**
 * @param {string} taskId
 * @returns {FakeElement | undefined}
 */
const savedSummary = (taskId) => savedRow(taskId)
    ?.querySelector("button.move-task-summary");
/**
 * @param {string} taskId
 * @returns {FakeElement | undefined}
 */
const savedRun = (taskId) => savedRow(taskId)?.querySelector("button.move-task-run");
/**
 * @param {string} taskId
 * @returns {FakeElement | undefined}
 */
const savedBody = (taskId) => savedRow(taskId)
    ?.querySelector(".move-task-body");
/**
 * @returns {string[]}
 */
const savedIds = () => savedRows().map(
    (row) => row.dataset.taskId || row.querySelector(".move-task-summary")?.dataset.taskId
);
/**
 * @param {string} taskId
 * @returns {object}
 */
const disclosure = (taskId) => {
    const summary = savedSummary(taskId);
    const body = savedBody(taskId);
    return {
        expanded: summary?.getAttribute("aria-expanded"),
        controls: summary?.getAttribute("aria-controls"),
        bodyId: body?.id,
        hidden: body?.hidden,
        formInside: body?.children.includes(node("move-task-form")),
    };
};
const moveButton = savedSummary;

(async () => {
    for (const handler of connectedHandlers) {
        await handler();
    }
    await settle();

    const initialRequests = requests.map((entry) => `${entry.method} ${entry.path}`);
    const initialQueueText = node("task-queue-list").textContent;
    const initialListIds = savedIds();
    const initialDisclosures = Object.fromEntries(savedRows().map((row) => [
        row.dataset.taskId,
        disclosure(row.dataset.taskId),
    ]));
    const initialFormHidden = node("move-task-form").hidden;
    const initialDraftHidden = node("move-task-draft").hidden;
    const initialPlaceholderHidden = node("move-task-placeholder").hidden;
    const initialCount = node("move-task-count").textContent;

    const confirmBeforeTaskToggle = confirmRequests.length;
    moveButton("existing-manual").click();
    const manualExpanded = {
        disclosure: disclosure("existing-manual"),
        savedDisclosure: disclosure("existing-saved"),
        formHidden: node("move-task-form").hidden,
        summaryText: savedSummary("existing-manual").textContent,
        focused: savedSummary("existing-manual").focused,
        confirmDelta: confirmRequests.length - confirmBeforeTaskToggle,
    };

    node("move-task-name").value = "未保存手动移动";
    node("move-target-x").value = "777";
    node("move-task-form").dispatchEvent("input");
    const confirmBeforeSameTaskToggle = confirmRequests.length;
    moveButton("existing-manual").click();
    const manualCollapsedDirty = {
        disclosure: disclosure("existing-manual"),
        formHidden: node("move-task-form").hidden,
        name: node("move-task-name").value,
        x: node("move-target-x").value,
    };
    moveButton("existing-manual").click();
    const manualReexpandedDirty = {
        disclosure: disclosure("existing-manual"),
        formHidden: node("move-task-form").hidden,
        name: node("move-task-name").value,
        x: node("move-target-x").value,
    };
    const sameTaskConfirmDelta = confirmRequests.length - confirmBeforeSameTaskToggle;

    confirmResult = false;
    const confirmBeforeBlockedSwitch = confirmRequests.length;
    moveButton("existing-saved").click();
    const blockedDirtySwitch = {
        manual: disclosure("existing-manual"),
        saved: disclosure("existing-saved"),
        x: node("move-target-x").value,
        confirmDelta: confirmRequests.length - confirmBeforeBlockedSwitch,
    };
    confirmResult = true;
    moveButton("existing-saved").click();
    const acceptedDirtySwitch = {
        manual: disclosure("existing-manual"),
        saved: disclosure("existing-saved"),
        formInside: disclosure("existing-saved").formInside,
    };

    const requestsBeforeNewDraft = requests.length;
    node("new-move-task").click();
    const newDraft = {
        name: node("move-task-name").value,
        cardHidden: node("move-task-draft").hidden,
        expanded: node("toggle-move-draft").getAttribute("aria-expanded"),
        bodyHidden: node("move-draft-body").hidden,
        formHidden: node("move-task-form").hidden,
        formInside: node("move-draft-body").children.includes(node("move-task-form")),
        focused: node("toggle-move-draft").focused,
        placeholderHidden: node("move-task-placeholder").hidden,
        count: node("move-task-count").textContent,
    };
    fill("新移动任务", "+1", "-2", "3", "URGENT");
    const confirmBeforeDraftToggle = confirmRequests.length;
    node("toggle-move-draft").click();
    const newDraftCollapsed = {
        expanded: node("toggle-move-draft").getAttribute("aria-expanded"),
        bodyHidden: node("move-draft-body").hidden,
        formHidden: node("move-task-form").hidden,
        name: node("move-task-name").value,
        x: node("move-target-x").value,
    };
    node("toggle-move-draft").click();
    const newDraftReexpanded = {
        expanded: node("toggle-move-draft").getAttribute("aria-expanded"),
        bodyHidden: node("move-draft-body").hidden,
        formHidden: node("move-task-form").hidden,
        name: node("move-task-name").value,
        x: node("move-target-x").value,
    };
    const newDraftToggleConfirmDelta = confirmRequests.length - confirmBeforeDraftToggle;
    const requestsBeforeFirstSave = requests.length;
    node("move-task-form").dispatchEvent("submit");
    await settle();
    const createdWrite = requests.find(
        (entry) => entry.method === "POST" && entry.path === "/v1/tasks"
    );
    const savedOnly = !requests.some((entry) => entry.path.endsWith("/runs"));
    savedRun("server-created-id-1").click();
    await settle();
    const createRun = requests.find(
        (entry) => entry.method === "POST" && entry.path.endsWith("/runs")
    );
    const createdListIds = savedIds();
    const createdFormX = node("move-target-x").value;
    const createdDisclosure = disclosure("server-created-id-1");
    const createdDraftHidden = node("move-task-draft").hidden;
    const createdFormHidden = node("move-task-form").hidden;
    const createdCount = node("move-task-count").textContent;

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
    node("move-task-form").dispatchEvent("submit");
    await settle();
    const savedWrite = requests.filter(
        (entry) => entry.method === "POST" && entry.path === "/v1/tasks"
    ).at(-1);

    node("new-move-task").click();
    fill("无效移动任务", "bad", "", "1.5", "NORMAL");
    const writesBeforeInvalid = requests.filter(
        (entry) => entry.method !== "GET"
    ).length;
    node("move-task-form").dispatchEvent("submit");
    await settle();
    const invalidMessage = node("move-task-message").textContent;
    const writesAfterInvalid = requests.filter(
        (entry) => entry.method !== "GET"
    ).length;

    moveButton("existing-saved").click();
    node("move-task-name").value = "更新后的保存移动";
    node("move-task-form").dispatchEvent("input");
    node("move-task-form").dispatchEvent("submit");
    await settle();
    const putWrite = requests.find((entry) => entry.method === "PUT");

    node("new-move-task").click();
    fill("入队失败任务", "7", "8", "9", "BACKGROUND");
    const taskWritesBeforeQueueFailure = requests.filter(
        (entry) => entry.method === "POST" && entry.path === "/v1/tasks"
    ).length;
    node("move-task-form").dispatchEvent("submit");
    await settle();
    failNextRun = true;
    savedRun("server-created-id-3").click();
    await settle();
    const queueFailureDisclosure = disclosure("server-created-id-3");
    const queueFailureFormHidden = node("move-task-form").hidden;
    const taskWritesAfterQueueFailure = requests.filter(
        (entry) => entry.method === "POST" && entry.path === "/v1/tasks"
    ).length;
    const runRequestsBeforeRetry = requests.filter(
        (entry) => entry.method === "POST" && entry.path.endsWith("/runs")
    ).length;
    savedRun("server-created-id-3").click();
    await settle();
    const taskWritesAfterQueueRetry = requests.filter(
        (entry) => entry.method === "POST" && entry.path === "/v1/tasks"
    ).length;
    const runRequestsAfterRetry = requests.filter(
        (entry) => entry.method === "POST" && entry.path.endsWith("/runs")
    ).length;
    const queueRetryMessage = toasts.at(-1);
    const queueRetryDisclosure = disclosure("server-created-id-3");
    const queueRetryFormHidden = node("move-task-form").hidden;

    moveButton("existing-manual").click();
    const cleanSourceTask = tasksState.find((task) => task.id === "existing-manual");
    cleanSourceTask.steps[0].parameters.x = "30";
    revision = "rev-move-refresh";
    node("refresh-move-tasks").click();
    await settle();
    const refreshedCleanX = node("move-target-x").value;
    const refreshedCleanDisclosure = {
        manual: disclosure("existing-manual"),
        saved: disclosure("existing-saved"),
        created: disclosure("server-created-id-1"),
    };
    const refreshedCleanExpandedIds = savedRows()
        .filter((row) => row.querySelector("button.move-task-summary")
            .getAttribute("aria-expanded") === "true")
        .map((row) => row.dataset.taskId);
    const refreshedCleanDraftHidden = node("move-task-draft").hidden;

    moveButton("existing-manual").click();
    revision = "rev-move-refresh-collapsed";
    node("refresh-move-tasks").click();
    await settle();
    const refreshedCollapsedDisclosure = disclosure("existing-manual");
    const refreshedCollapsedExpandedIds = savedRows()
        .filter((row) => row.querySelector("button.move-task-summary")
            .getAttribute("aria-expanded") === "true")
        .map((row) => row.dataset.taskId);
    const refreshedCollapsedFormHidden = node("move-task-form").hidden;
    moveButton("existing-manual").click();

    node("move-target-x").value = "42";
    node("move-task-form").dispatchEvent("input");
    cleanSourceTask.steps[0].parameters.x = "99";
    revision = "rev-move-refresh-dirty";
    node("refresh-move-tasks").click();
    await settle();
    const refreshedDirtyX = node("move-target-x").value;
    const refreshedDirtyMessage = node("move-task-message").textContent;
    const refreshedDirtyDisclosure = disclosure("existing-manual");
    const refreshedDirtyExpandedIds = savedRows()
        .filter((row) => row.querySelector("button.move-task-summary")
            .getAttribute("aria-expanded") === "true")
        .map((row) => row.dataset.taskId);

    node("new-move-task").click();
    fill("更新后的保存移动", "12", "13", "14");
    const writesBeforeNewDuplicate = requests.filter(
        (entry) => entry.method !== "GET"
    ).length;
    node("move-task-form").dispatchEvent("submit");
    await settle();
    const localNewDuplicate = {
        message: node("move-task-message").textContent,
        name: node("move-task-name").value,
        cardHidden: node("move-task-draft").hidden,
        formHidden: node("move-task-form").hidden,
        writesDelta: requests.filter((entry) => entry.method !== "GET").length
            - writesBeforeNewDuplicate,
    };

    moveButton("existing-manual").click();
    node("move-task-name").value = "更新后的保存移动";
    node("move-task-form").dispatchEvent("input");
    const writesBeforeEditDuplicate = requests.filter(
        (entry) => entry.method !== "GET"
    ).length;
    node("move-task-form").dispatchEvent("submit");
    await settle();
    const localEditDuplicate = {
        message: node("move-task-message").textContent,
        name: node("move-task-name").value,
        cardHidden: node("move-task-draft").hidden,
        formHidden: node("move-task-form").hidden,
        writesDelta: requests.filter((entry) => entry.method !== "GET").length
            - writesBeforeEditDuplicate,
    };

    node("new-move-task").click();
    fill("并发冲突移动", "55", "66", "77");
    failNextTaskWriteWithDuplicateName = true;
    const writesBeforeConcurrentDuplicate = requests.filter(
        (entry) => entry.method !== "GET"
    ).length;
    const taskWritesBeforeConcurrentDuplicate = requests.filter(
        (entry) => entry.method === "POST" && entry.path === "/v1/tasks"
    ).length;
    node("move-task-form").dispatchEvent("submit");
    await settle();
    const concurrentDuplicate = {
        message: node("move-task-message").textContent,
        name: node("move-task-name").value,
        x: node("move-target-x").value,
        cardHidden: node("move-task-draft").hidden,
        formHidden: node("move-task-form").hidden,
        writesDelta: requests.filter((entry) => entry.method !== "GET").length
            - writesBeforeConcurrentDuplicate,
        taskWritesDelta: requests.filter(
            (entry) => entry.method === "POST" && entry.path === "/v1/tasks"
        ).length - taskWritesBeforeConcurrentDuplicate,
    };

    const requestCountBeforeRefresh = requests.length;
    node("refresh-runs-button").click();
    await settle();
    const refreshRequests = requests.slice(requestCountBeforeRefresh)
        .map((entry) => `${entry.method} ${entry.path}`);

    const finalListIds = savedIds();
    const writeRequests = requests.filter((entry) => entry.method !== "GET")
        .map((entry) => `${entry.method} ${entry.path}`);
    process.stdout.write(JSON.stringify({
        initialRequests,
        initialQueueText,
        initialListIds,
        initialDisclosures,
        initialFormHidden,
        initialDraftHidden,
        initialPlaceholderHidden,
        initialCount,
        manualExpanded,
        manualCollapsedDirty,
        manualReexpandedDirty,
        sameTaskConfirmDelta,
        blockedDirtySwitch,
        acceptedDirtySwitch,
        requestsBeforeNewDraft,
        newDraft,
        newDraftCollapsed,
        newDraftReexpanded,
        newDraftToggleConfirmDelta,
        requestsBeforeFirstSave,
        createdWrite,
        savedOnly,
        createRun,
        createdListIds,
        createdFormX,
        createdDisclosure,
        createdDraftHidden,
        createdFormHidden,
        createdCount,
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
        queueFailureDisclosure,
        queueFailureFormHidden,
        queueRetryMessage,
        queueRetryDisclosure,
        queueRetryFormHidden,
        refreshedCleanX,
        refreshedCleanDisclosure,
        refreshedCleanExpandedIds,
        refreshedCleanDraftHidden,
        refreshedCollapsedDisclosure,
        refreshedCollapsedExpandedIds,
        refreshedCollapsedFormHidden,
        refreshedDirtyX,
        refreshedDirtyMessage,
        refreshedDirtyDisclosure,
        refreshedDirtyExpandedIds,
        localNewDuplicate,
        localEditDuplicate,
        concurrentDuplicate,
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


@pytest.fixture(scope="module")
def page_scenario() -> dict[str, Any]:
    return _run_page_scenario()


@pytest.mark.skipif(
    NODE is None,
    reason="Node.js is required to exercise the fixed move task page",
)
def test_fixed_move_page_save_run_switch_invalid_put_and_queue_retry(
    page_scenario: dict[str, Any],
) -> None:
    result = page_scenario

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
    assert result["savedOnly"] is True
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
    assert result["runRequestsAfterRetry"] == result["runRequestsBeforeRetry"] + 1
    assert "任务已加入队列" in result["queueRetryMessage"]
    assert result["refreshedCleanX"] == "30"
    assert result["refreshedDirtyX"] == "42"
    assert "未保存修改" in result["refreshedDirtyMessage"]
    assert result["refreshRequests"] == ["GET /v1/task-runs"]
    assert "complex-task" not in result["finalListIds"]


@pytest.mark.skipif(
    NODE is None,
    reason="Node.js is required to exercise move task name conflicts",
)
def test_move_names_use_full_snapshot_and_preserve_duplicate_drafts(
    page_scenario: dict[str, Any],
) -> None:
    result = page_scenario

    local_new = result["localNewDuplicate"]
    assert "任务名称已存在，请使用其他名称" in local_new["message"]
    assert "task name must be unique" not in local_new["message"]
    assert local_new["name"] == "更新后的保存移动"
    assert local_new["writesDelta"] == 0
    assert local_new["cardHidden"] is False
    assert local_new["formHidden"] is False

    local_edit = result["localEditDuplicate"]
    assert "任务名称已存在，请使用其他名称" in local_edit["message"]
    assert "task name must be unique" not in local_edit["message"]
    assert local_edit["name"] == "更新后的保存移动"
    assert local_edit["writesDelta"] == 0
    assert local_edit["formHidden"] is False

    concurrent = result["concurrentDuplicate"]
    assert "任务名称已存在，请使用其他名称" in concurrent["message"]
    assert "task name must be unique" not in concurrent["message"]
    assert concurrent["name"] == "并发冲突移动"
    assert concurrent["x"] == "55"
    assert concurrent["cardHidden"] is False
    assert concurrent["formHidden"] is False
    assert concurrent["writesDelta"] == 1
    assert concurrent["taskWritesDelta"] == 1


@pytest.mark.skipif(
    NODE is None,
    reason="Node.js is required to exercise saved task disclosure",
)
def test_saved_tasks_start_collapsed_and_preserve_disclosure_and_dirty_input(
    page_scenario: dict[str, Any],
) -> None:
    result = page_scenario

    assert result["initialCount"] == "2"
    assert result["initialFormHidden"] is True
    assert result["initialDraftHidden"] is True
    assert result["initialPlaceholderHidden"] is True
    for state in result["initialDisclosures"].values():
        assert state["expanded"] == "false"
        assert state["hidden"] is True
        assert state["formInside"] is False
        assert state["controls"] == state["bodyId"]

    expanded = result["manualExpanded"]
    assert expanded["disclosure"]["expanded"] == "true"
    assert expanded["disclosure"]["hidden"] is False
    assert expanded["disclosure"]["formInside"] is True
    assert expanded["savedDisclosure"]["expanded"] == "false"
    assert expanded["savedDisclosure"]["hidden"] is True
    assert expanded["formHidden"] is False
    assert expanded["focused"] is True
    assert expanded["confirmDelta"] == 0
    for text in ("已有手动移动", "3, 4, 5", "普通"):
        assert text in expanded["summaryText"]

    collapsed = result["manualCollapsedDirty"]
    assert collapsed["disclosure"]["expanded"] == "false"
    assert collapsed["disclosure"]["hidden"] is True
    assert collapsed["formHidden"] is True
    assert collapsed["name"] == "未保存手动移动"
    assert collapsed["x"] == "777"

    reexpanded = result["manualReexpandedDirty"]
    assert reexpanded["disclosure"]["expanded"] == "true"
    assert reexpanded["disclosure"]["hidden"] is False
    assert reexpanded["formHidden"] is False
    assert reexpanded["name"] == "未保存手动移动"
    assert reexpanded["x"] == "777"
    assert result["sameTaskConfirmDelta"] == 0

    blocked = result["blockedDirtySwitch"]
    assert blocked["confirmDelta"] == 1
    assert blocked["manual"]["expanded"] == "true"
    assert blocked["saved"]["expanded"] == "false"
    assert blocked["x"] == "777"

    accepted = result["acceptedDirtySwitch"]
    assert accepted["manual"]["expanded"] == "false"
    assert accepted["manual"]["hidden"] is True
    assert accepted["saved"]["expanded"] == "true"
    assert accepted["saved"]["hidden"] is False
    assert accepted["formInside"] is True

    assert result["requestsBeforeNewDraft"] == result["requestsBeforeFirstSave"]
    draft = result["newDraft"]
    assert draft["name"] == "移动到坐标 3"
    assert draft["cardHidden"] is False
    assert draft["expanded"] == "true"
    assert draft["bodyHidden"] is False
    assert draft["formHidden"] is False
    assert draft["formInside"] is True
    assert draft["focused"] is True
    assert draft["placeholderHidden"] is True
    assert draft["count"] == "2"

    collapsed_draft = result["newDraftCollapsed"]
    assert collapsed_draft["expanded"] == "false"
    assert collapsed_draft["bodyHidden"] is True
    assert collapsed_draft["formHidden"] is True
    assert collapsed_draft["name"] == "新移动任务"
    assert collapsed_draft["x"] == "+1"
    reexpanded_draft = result["newDraftReexpanded"]
    assert reexpanded_draft["expanded"] == "true"
    assert reexpanded_draft["bodyHidden"] is False
    assert reexpanded_draft["formHidden"] is False
    assert reexpanded_draft["name"] == "新移动任务"
    assert reexpanded_draft["x"] == "+1"
    assert result["newDraftToggleConfirmDelta"] == 0

    created = result["createdDisclosure"]
    assert created["expanded"] == "true"
    assert created["hidden"] is False
    assert created["formInside"] is True
    assert created["controls"] == created["bodyId"]
    assert result["createdDraftHidden"] is True
    assert result["createdFormHidden"] is False
    assert result["createdCount"] == "3"
    assert "server-created-id-1" in result["createdListIds"]

    for state in (
        result["queueFailureDisclosure"],
        result["queueRetryDisclosure"],
    ):
        assert state["expanded"] == "true"
        assert state["hidden"] is False
        assert state["formInside"] is True
    assert result["queueFailureFormHidden"] is False
    assert result["queueRetryFormHidden"] is False

    clean_refresh = result["refreshedCleanDisclosure"]
    assert clean_refresh["manual"]["expanded"] == "true"
    assert clean_refresh["manual"]["hidden"] is False
    assert clean_refresh["saved"]["expanded"] == "false"
    assert clean_refresh["saved"]["hidden"] is True
    assert clean_refresh["created"]["expanded"] == "false"
    assert clean_refresh["created"]["hidden"] is True
    assert result["refreshedCleanExpandedIds"] == ["existing-manual"]
    assert result["refreshedCleanDraftHidden"] is True

    collapsed_refresh = result["refreshedCollapsedDisclosure"]
    assert collapsed_refresh["expanded"] == "false"
    assert collapsed_refresh["hidden"] is True
    assert collapsed_refresh["formInside"] is True
    assert result["refreshedCollapsedExpandedIds"] == []
    assert result["refreshedCollapsedFormHidden"] is True

    assert result["refreshedDirtyX"] == "42"
    assert result["refreshedDirtyMessage"] == "未保存修改"
    assert result["refreshedDirtyDisclosure"]["expanded"] == "true"
    assert result["refreshedDirtyDisclosure"]["hidden"] is False
    assert result["refreshedDirtyExpandedIds"] == ["existing-manual"]
