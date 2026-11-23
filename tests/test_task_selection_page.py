import re
from collections.abc import Iterator
from pathlib import Path
from typing import Any

import pytest


ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "src" / "main" / "resources" / "stowling-web"


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
        "move-saved-coordinate",
        "move-priority",
    ):
        assert f'id="{field_id}"' in display
    assert "data-move-source=" not in display
    assert 'id="move-target-x"' not in display
    assert 'type="submit"' not in display
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
    assert 'id="new-robot-warehouse"' not in html
    assert 'id="robot-warehouse-draft"' not in html
    assert "机器人仓库" not in selection
    assert "移动到坐标" in selection
    assert 'id="new-classification-task"' in selection
    assert "掉落物拾取" in selection
    assert "＋ 分类" in selection
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
        "/task-autosave.js",
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
    assert load_move.count("Stowling.request(") == 3
    assert "const definitions = Array.isArray(tasks?.tasks)" in load_move
    assert "? tasks.tasks.map(Model.normalizeTask) : [];" in load_move
    assert "state.moveTasks = definitions.filter(Move.isMoveTask);" in load_move
    assert "Coordinates.normalizeCoordinates(coordinates).coordinates" in load_move
    assert "if (state.moveDraft.id && !state.moveDirty)" in load_move
    assert "state.moveTasks.find(" in load_move
    assert "state.moveDraft = Move.draftFromTask(refreshed);" in load_move
    assert "state.moveDraftActive = false;" in load_move
    assert "state.moveFormOpen = false;" in load_move
    assert "syncMoveForm();" in load_move
    assert "renderMoveTaskList();" in load_move
    select_move = between(
        javascript,
        "function selectMoveTask(",
        "function toggleMoveDraft()",
    )
    assert "Move.newDraft()" in select_move
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
    assert "state.moveTasks = tasks;" in apply_save
    assert "applyMoveSave(response, task.id);" in save
    assert '"PUT",' in save and '"POST", payload' in save
    assert "{...payload, revision: state.moveDraftRevision}" in save
    assert "/runs" not in save
    assert "`/v1/tasks/${encodeURIComponent(id)}/runs`" in queue
    assert "body: JSON.stringify({})" in queue
    assert "/v1/coordinates" not in save
    assert 'method: "POST"' in queue
    assert '"/v1/task-folders"' not in javascript
    assert '"/v1/task-triggers"' not in javascript


def test_move_form_preserves_saved_coordinate_and_cleanup_fields() -> None:
    javascript = read("tasks.js")
    read_form = between(javascript, "function readMoveForm()", "function updateMoveTarget()")

    assert 'elements.moveForm.addEventListener("input", () => {' in javascript
    assert "coordinateId: elements.moveCoordinate.value" in read_form
    assert "priority: elements.movePriority.value" in read_form
    assert "cleanupPolicy: cleanup.read()" in read_form
    assert "...state.moveDraft" in read_form
    assert "moveSources" not in javascript
    assert "moveManual" not in javascript


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
    assert "StowlingSettings.reveal(elements.moveDraftBody," in disclosure
    assert "elements.movePlaceholder.hidden = savedCount > 0 || newDraft" in disclosure
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
    assert "state.movePendingRuns.get(id)" in controls
    assert "(!running && (state.moveBusy || unsaved))" in controls
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


@pytest.fixture
def move_page() -> Iterator[Any]:
    playwright = pytest.importorskip("playwright.sync_api")
    with playwright.sync_playwright() as runtime:
        try:
            browser = runtime.chromium.launch()
        except playwright.Error as error:
            pytest.skip(f"Playwright Chromium is required: {error}")
        page = browser.new_page(viewport={"width": 1440, "height": 1000})
        html = re.sub(r"<script\b[^>]*>[\s\S]*?</script>", "", read("tasks.html"))
        html = re.sub(r"<link\b[^>]*>", "", html)
        page.set_content(html)
        for name in ("styles.css", "workbench.css", "pickup-task-settings.css"):
            page.add_style_tag(content=read(name))
        page.add_script_tag(content=r"""
            window.backend = {
                requests: [], errors: [], handlers: [], revision: "1", runs: [],
                failStart: false, holdStart: false,
                tasks: [{id: "move/1", name: "移动到坐标", priority: "NORMAL",
                    steps: [{id: "step-1", kind: "ACTION", moduleId: "MOVE_TO_COORDINATE",
                        parameters: {targetRef: "coordinate:home"}, steps: [], otherwise: []}],
                    cleanupPolicy: {destinationRef: "coordinate:home", resumeMode: "CONTINUE"}}]
            };
            window.Stowling = {
                /** @param {unknown} value @returns {unknown} */
                clone(value) {return JSON.parse(JSON.stringify(value));},
                /** @param {string} tag @param {string} css @param {string} text
                 * @returns {HTMLElement} */
                element(tag, css = "", text = "") {
                    const node = document.createElement(tag);
                    node.className = css;
                    node.textContent = text;
                    return node;
                },
                /** @param {string} text @returns {HTMLElement} */
                emptyState(text) {return this.element("p", "empty-state", text);},
                /** @returns {string} */
                formatDate() {return "";},
                /** @returns {boolean} */
                isConnected() {return true;},
                /** @returns {void} */
                showToast() {},
                /** @param {Error} error @returns {void} */
                handleError(error) {backend.errors.push(error.message);},
                /** @param {Function} handler @returns {void} */
                onConnected(handler) {backend.handlers.push(handler);},
                /** @param {string} path @param {object} options @returns {Promise<object>} */
                async request(path, options = {}) {
                    const method = options.method || "GET";
                    const body = options.body ? JSON.parse(options.body) : null;
                    backend.requests.push({path, method, body});
                    if (method === "GET") {
                        if (path === "/v1/tasks") return {
                            tasks: backend.tasks, revision: backend.revision};
                        if (path === "/v1/coordinates") return {coordinates: [
                            {id: "home", name: "仓库", x: 10, y: 64, z: -20},
                            {id: "mine", name: "矿区", x: 120, y: 70, z: 300}]};
                        if (path === "/v1/regions") return {regions: []};
                        if (path === "/v1/task-modules") return {modules: []};
                        if (path === "/v1/task-runs") return {
                            runs: backend.runs, queue: [], triggers: []};
                    }
                    if (path.endsWith("/runs")) {
                        if (backend.holdStart) await new Promise(resolve => {
                            backend.release = resolve;
                        });
                        if (backend.failStart) throw new Error("start failed");
                        backend.runs.push({runId: "run/1", taskId: "move/1",
                            taskName: "移动到坐标", state: "QUEUED", priority: "NORMAL"});
                        return {state: "QUEUED"};
                    }
                    if (path.endsWith("/cancel")) {
                        const runId = decodeURIComponent(path.split("/").at(-2));
                        backend.runs = backend.runs.filter(run => run.runId !== runId);
                        return {};
                    }
                    if (method === "PUT") {
                        backend.tasks = backend.tasks.map(task => task.id === "move/1"
                            ? {...task, ...body} : task);
                        backend.revision = String(Number(backend.revision) + 1);
                        return {tasks: backend.tasks, revision: backend.revision};
                    }
                    if (method === "POST" && path === "/v1/tasks") {
                        backend.tasks.push({...body, id: "created", name: "移动到坐标 2"});
                        backend.revision = String(Number(backend.revision) + 1);
                        return {taskId: "created", tasks: backend.tasks,
                            revision: backend.revision};
                    }
                    throw new Error(`Unexpected request: ${method} ${path}`);
                }
            };
            document.querySelector("#workspace").setAttribute("aria-hidden", "false");
            document.querySelector("#auth-shell").hidden = true;
            window.setInterval = () => 0;
        """)
        for name in (
            "settings-ui.js", "task-select-ui.js", "v2-task-model.js", "coordinate-model.js",
            "move-task-model.js", "task-monitor-model.js", "task-cleanup-settings.js",
            "task-autosave.js", "tasks.js",
        ):
            page.add_script_tag(content=read(name))
        page.evaluate("async () => {for (const handler of backend.handlers) await handler();}")
        yield page
        browser.close()


def test_move_header_uses_template_order_and_separate_controls(move_page: Any) -> None:
    page = move_page
    card = page.locator(".coordinate-task-module")
    assert card.locator(".pickup-task-title").inner_text() == "移动到坐标"
    assert card.locator(".pickup-task-title svg").count() == 1
    assert card.locator(".move-task-summary, .move-task-overview").count() == 0
    assert card.locator(".pickup-task-header").evaluate(
        "node => [...node.children].map(child => child.tagName)"
    ) == ["H3", "BUTTON", "INPUT"]
    assert not card.get_by_role("switch").is_checked()
    assert card.locator(".pickup-task-body").is_hidden()
    card.get_by_role("button", name="展开任务设置").click()
    page.wait_for_function("!document.querySelector('#move-task-fields').disabled")
    assert page.locator("#move-task-form").is_visible()
    card.get_by_role("button", name="收起任务设置").click()
    page.wait_for_function("document.querySelector('.coordinate-task-module .pickup-task-body')"
                           ".hidden")
    assert not card.get_by_role("switch").is_checked()
    assert not page.evaluate("backend.requests.some(request => request.path.endsWith('/runs')"
                             " && request.method === 'POST')")


def test_move_run_switch_preserves_open_configuration_and_reports_failures(move_page: Any) -> None:
    page = move_page
    card = page.locator(".coordinate-task-module")
    card.get_by_role("button", name="展开任务设置").click()
    page.evaluate("backend.holdStart = true")
    card.get_by_role("switch").check()
    page.wait_for_function("Boolean(backend.release)")
    assert not page.locator("#move-task-fields").is_disabled()
    assert page.locator("#move-task-form").is_visible()
    assert page.locator("#move-saved-coordinate").input_value() == "home"
    page.evaluate("backend.release()")
    page.wait_for_function("document.querySelector('.coordinate-task-module input')"
                           ".getAttribute('aria-busy') === 'false'")
    assert card.get_by_role("switch").is_checked()
    card.get_by_role("switch").uncheck()
    page.wait_for_function("backend.runs.length === 0")
    page.wait_for_function("!document.querySelector('.coordinate-task-module input').disabled")
    assert page.locator("#move-task-form").is_visible()
    assert not page.locator("#move-task-fields").is_disabled()
    assert page.evaluate("backend.requests.some(request => "
                         "request.path === '/v1/task-runs/run%2F1/cancel')")
    page.evaluate("backend.holdStart = false; backend.failStart = true")
    card.get_by_role("switch").check()
    page.wait_for_function("backend.errors.includes('start failed')")
    page.wait_for_function("!document.querySelector('.coordinate-task-module input').checked")
    assert page.locator("#move-task-form").is_visible()
    assert page.locator("#move-saved-coordinate").input_value() == "home"


def test_move_stop_cancels_its_active_runs_and_keeps_dirty_settings(move_page: Any) -> None:
    page = move_page
    page.evaluate("""() => {
        backend.runs = [
            {runId:'active', taskId:'move/1', state:'RUNNING', priority:'NORMAL'},
            {runId:'queued', taskId:'move/1', state:'QUEUED', priority:'NORMAL'},
            {runId:'done', taskId:'move/1', state:'SUCCEEDED', priority:'NORMAL'},
            {runId:'other', taskId:'other', state:'RUNNING', priority:'NORMAL'}];
        document.dispatchEvent(new Event('stowling:monitor-refresh'));
    }""")
    card = page.locator(".coordinate-task-module")
    page.wait_for_function("document.querySelector('.coordinate-task-module input').checked")
    card.get_by_role("button", name="展开任务设置").click()
    page.locator("#move-priority").evaluate("""select => {
        select.value = 'URGENT';
        select.dispatchEvent(new Event('input', {bubbles:true}));
    }""")
    card.get_by_role("switch").uncheck()
    page.wait_for_function("backend.requests.filter(r => r.path.endsWith('/cancel')).length === 2")
    assert page.evaluate("backend.requests.filter(r => r.path.endsWith('/cancel'))"
                         ".map(r => r.path)") == [
        "/v1/task-runs/active/cancel", "/v1/task-runs/queued/cancel",
    ]
    assert page.locator("#move-priority").input_value() == "URGENT"
    assert page.locator("#move-task-form").is_visible()


def test_move_autosave_preserves_coordinate_and_cleanup_fields(move_page: Any) -> None:
    page = move_page
    page.locator(".coordinate-task-module .pickup-task-expand").click()
    form = page.locator("#move-task-form")
    target = form.locator("#move-saved-target .task-select-trigger")
    target.click()
    menu = page.locator(".task-select-menu:visible")
    assert menu.get_by_role("option").count() == 2
    menu.get_by_role("option", name="矿区", exact=False).click()
    page.wait_for_function("backend.requests.some(r => r.method === 'PUT')")
    task = page.evaluate("backend.tasks[0]")
    assert task["steps"][0]["parameters"] == {"targetRef": "coordinate:mine"}
    assert task["cleanupPolicy"]["destinationRef"] == "coordinate:home"
    assert task["cleanupPolicy"]["resumeMode"] == "CONTINUE"
    page.locator(".coordinate-task-module .pickup-task-expand").click()
    page.locator(".coordinate-task-module .pickup-task-expand").click()
    assert page.locator("#move-saved-coordinate").input_value() == "mine"
    page.locator("#move-cleanup-settings summary").click()
    assert page.locator("#move-cleanup-settings .settings-detail").is_visible()
    assert page.locator("#move-cleanup-settings select").count() == 2


def test_move_new_draft_keeps_empty_target_until_selection(move_page: Any) -> None:
    page = move_page
    page.locator("#new-move-task").click()
    assert page.locator("#move-task-draft").is_visible()
    assert page.locator("#move-draft-run").is_disabled()
    assert page.locator("#move-saved-coordinate").input_value() == ""
    target = page.locator("#move-saved-target .task-select-trigger")
    target.click()
    page.locator(".task-select-menu:visible").get_by_role("option", name="仓库").click()
    page.wait_for_function("backend.tasks.length === 2")
    page.wait_for_function("document.querySelector('#move-task-draft').hidden")
    created = page.locator(".coordinate-task-module[data-task-id='created']")
    assert created.locator("#move-task-form").is_visible()
    assert created.get_by_role("switch").is_enabled()
    assert not created.get_by_role("switch").is_checked()


@pytest.mark.parametrize("theme", ["green", "gray"])
@pytest.mark.parametrize("width", [390, 1440])
def test_move_template_layout_keyboard_and_reveal(
    move_page: Any, theme: str, width: int,
) -> None:
    page = move_page
    page.set_viewport_size({"width": width, "height": 1000})
    page.evaluate("theme => document.documentElement.dataset.theme = theme", theme)
    card = page.locator(".coordinate-task-module")
    card.locator(".pickup-task-expand").click()
    page.wait_for_function("document.querySelector('#move-task-form').getAnimations()"
                           ".length === 0")
    target = page.locator("#move-saved-target .task-select-trigger")
    target.focus()
    target.press("ArrowDown")
    menu = page.locator(".task-select-menu:visible")
    assert menu.is_visible()
    assert menu.get_by_role("option", name="仓库").get_attribute("aria-selected") == "true"
    menu.get_by_role("option", name="仓库").press("ArrowDown")
    menu.get_by_role("option", name="矿区").press("Escape")
    assert target.evaluate("node => node === document.activeElement")
    assert page.locator("#move-saved-coordinate").input_value() == "home"
    assert target.bounding_box()["height"] == 42
    assert card.get_by_role("switch").bounding_box()["width"] == 56
    assert page.evaluate("document.documentElement.scrollWidth <= innerWidth")
    card.locator(".pickup-task-expand").click()
    card.locator(".pickup-task-expand").click()
    page.wait_for_function("""() => {
        const body = document.querySelector('.coordinate-task-module .pickup-task-body');
        return !body.hidden && body.getAnimations().length === 0;
    }""")
    assert page.locator("#move-task-form").is_visible()


def test_move_template_respects_reduced_motion(move_page: Any) -> None:
    page = move_page
    page.emulate_media(reduced_motion="reduce")
    toggle = page.locator(".coordinate-task-module .pickup-task-expand")
    toggle.click()
    body = page.locator(".coordinate-task-module .pickup-task-body")
    assert body.is_visible()
    assert body.evaluate("node => node.getAnimations().length") == 0
    toggle.click()
    assert body.is_hidden()


