from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "src" / "main" / "resources" / "stowling-web"


def read(name: str) -> str:
    return (WEB / name).read_text(encoding="utf-8")


def between(source: str, start: str, end: str) -> str:
    start_index = source.index(start)
    content_start = start_index + len(start)
    return source[content_start:source.index(end, content_start)]


def test_inventory_management_follows_tasks_in_navigation() -> None:
    common = read("common.js")
    navigation = between(common, "const NAV_ITEMS", "]);")
    assert "库存管理" in navigation
    assert navigation.index('page: "tasks"') < navigation.index('page: "storage"')
    assert (WEB / "storage.html").exists()
    assert (WEB / "storage.js").exists()


def test_narrow_rail_has_queue_above_logs_and_move_workbench_on_the_right() -> None:
    html = read("tasks.html")
    rail = between(
        html,
        '<aside class="task-monitor-rail"',
        "</aside>",
    )
    assert rail.index('id="task-queue-list"') < rail.index('id="task-log-list"')
    assert html.index("</aside>") < html.index('id="task-selection-area"')
    assert "data-task-tab=" not in html
    assert "data-task-panel=" not in html
    assert "紧急 → 普通 → 后台" in rail
    assert 'role="log"' in rail
    assert 'aria-live="off"' in rail
    assert 'tabindex="0"' in rail


def test_monitor_uses_shared_model_and_static_task_names() -> None:
    html = read("tasks.html")
    javascript = read("tasks.js")
    queue = between(javascript, "function renderQueue()", "function railAction")

    assert html.index('src="/task-monitor-model.js"') < html.index('src="/tasks.js"')
    assert "Monitor.queueEntries(" in queue
    assert "state.runs, state.queue, state.triggerStatuses" in queue
    assert 'Stowling.element("strong", "rail-task-name", entry.taskName)' in queue
    assert "selectTask" not in queue
    assert "selectTask" not in javascript
    assert "retryInterruption(entry.run.runId)" in queue
    assert "skipInterruption(entry.run.runId)" in queue
    assert "cancelRun(entry.run.runId)" in queue
    assert 'entry.state === "QUEUED" ? "取消排队" : "取消任务"' in queue
    assert "elements.queueList.scrollTop = scrollTop" in queue
    assert 'QUEUED: "排队中"' in javascript


def test_log_panel_has_only_export_and_clear_icon_buttons() -> None:
    html = read("tasks.html")
    panel = between(html, 'class="panel task-log-panel"', "</section>")
    assert 'id="task-log-title">任务进度</h2>' in panel
    assert panel.count("<button ") == 2
    for button_id, label in (("export-task-logs", "导出日志"), ("clear-task-logs", "清空日志")):
        button = between(panel, f'<button id="{button_id}"', "</button>")
        assert f'aria-label="{label}"' in button
        assert f'title="{label}"' in button
        assert "<svg " in button
    assert "<select " not in panel
    assert "<input " not in panel


def test_log_icons_download_one_current_run_file_and_clear_the_buffer() -> None:
    javascript = read("tasks.js")
    export = between(javascript, "async function exportMonitorLogs()", "function downloadLog")
    assert "Monitor.createLogBuffer(300)" in javascript
    assert "taskLogs.ingest(state.runs, state.queue)" in javascript
    assert "ingestDiagnostics" not in javascript
    assert "/v1/task-diagnostics/since/" not in javascript
    assert 'elements.exportLogs.addEventListener("click", exportMonitorLogs)' in javascript
    assert "const [run] = Monitor.exportRuns(state.runs)" in export
    assert "/v1/task-diagnostics/run/${encodeURIComponent(runId)}" in export
    assert "Monitor.exportCurrentRun(batch, runId, run)" in export
    assert export.count("downloadLog(") == 1
    assert '"text/plain;charset=utf-8"' in export
    assert "URL.createObjectURL(blob)" in javascript
    assert "URL.revokeObjectURL(url)" in javascript
    clear = between(javascript, 'elements.clearLogs.addEventListener("click",', "});")
    assert "taskLogs.clear()" in clear
    assert "renderMonitorLogs()" in clear
    assert "? elements.logList.scrollHeight : scrollTop" in javascript
    assert "innerHTML" not in javascript


def test_task_details_use_static_names_and_keep_interruption_recovery() -> None:
    html = read("tasks.html")
    javascript = read("tasks.js")
    runtime = between(javascript, "function runtimeCard(run)", "function renderQueue")

    queue = between(html, 'class="panel task-queue-panel"', "</section>")
    assert 'id="show-run-details"' in queue
    assert '<dialog id="task-run-details"' in html
    assert 'aria-labelledby="runtime-title"' in html
    assert "elements.runDetails.showModal()" in javascript
    assert "elements.runDetails.close()" in javascript
    assert 'state === "INTERRUPTION_FAILED"' in javascript
    assert "查看任务配置" not in javascript
    assert 'Stowling.element("strong", "", run.taskName)' in runtime
    assert '"选择任务"' not in runtime
    assert "retryInterruption(run.runId)" in runtime
    assert "skipInterruption(run.runId)" in runtime
    assert "cancelRun(run.runId)" in runtime


def test_force_stop_is_available_for_active_runs_in_both_monitor_views() -> None:
    javascript = read("tasks.js")
    runtime = between(javascript, "function runtimeCard(run)", "function renderQueue")
    queue = between(javascript, "function renderQueue()", "function railAction")
    button = between(javascript, "function forceStopButton(runId)", "function listeningStateLabel")
    action = between(javascript, "async function forceStopRun(runId)", "document.addEventListener")

    assert "if (isActiveRun(run)) {\n            actions.append(forceStopButton(run.runId))" in (
        runtime
    )
    assert "if (entry.run && isActiveRun(entry.run))" in queue
    assert "actions.append(forceStopButton(entry.run.runId))" in queue
    assert 'railAction("强制停止", () => void forceStopRun(runId))' in button
    assert action.index("if (!confirmed) return;") < action.index("Stowling.request(")
    assert "/v1/task-runs/${encodeURIComponent(runId)}/force-stop" in action
    assert '{ method: "POST" }' in action
    assert "await loadRuns();" in action


def test_layout_keeps_a_scrollable_monitor_rail_and_responsive_columns() -> None:
    css = read("styles.css")
    console = between(css, ".task-console {", "}")
    rail = between(css, ".task-monitor-rail {", "}")
    assert "grid-template-columns: 300px minmax(0, 1fr)" in console
    assert "position: sticky" in rail
    assert "grid-template-rows: minmax(0, 1.1fr) minmax(0, 1fr)" in rail
    assert "overscroll-behavior: contain" in css
    assert "@media (max-width: 779px)" in css
    assert "@media (max-width: 520px)" in css


def test_monitor_and_move_resources_load_independently_then_connect_together() -> None:
    javascript = read("tasks.js")
    loading = between(
        javascript,
        "async function loadAll()",
        "function isActiveRun",
    )
    move_loading = between(
        javascript,
        "async function loadMoveResources()",
        "function renderMoveCoordinates()",
    )

    assert '"/v1/task-modules"' in loading
    assert '"/v1/task-runs"' in loading
    assert loading.count("Stowling.request(") == 2
    for removed in (
        '"/v1/tasks"',
        '"/v1/coordinates"',
        '"/v1/task-folders"',
        '"/v1/task-triggers"',
        "renderTaskList()",
        "renderRunControls()",
    ):
        assert removed not in loading
    assert '"/v1/tasks"' in move_loading
    assert '"/v1/coordinates"' in move_loading
    assert move_loading.count("Stowling.request(") == 2
    assert "if (state.moveDraft.id && !state.moveDirty)" in move_loading
    assert "state.moveDraft = Move.draftFromTask(refreshed);" in move_loading
    assert "syncMoveForm();" in move_loading
    assert "await Promise.all([loadAll(), loadMoveResources()]);" in javascript
    assert 'elements.refreshRuns.addEventListener("click", () => void loadRuns());' in (
        javascript
    )
    assert "updateRunActions" not in javascript
