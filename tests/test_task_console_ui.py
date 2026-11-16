from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "src" / "main" / "resources" / "stowling-web"


def read(name: str) -> str:
    return (WEB / name).read_text(encoding="utf-8")


def between(source: str, start: str, end: str) -> str:
    start_index = source.index(start)
    content_start = start_index + len(start)
    return source[content_start:source.index(end, content_start)]


def test_inventory_is_not_exposed_in_navigation_but_sources_are_preserved() -> None:
    common = read("common.js")
    navigation = between(common, "const NAV_ITEMS", "]);")
    assert "库存查询" not in navigation
    assert "/storage.html" not in navigation
    assert (WEB / "storage.html").exists()
    assert (WEB / "storage.js").exists()


def test_narrow_rail_has_queue_above_logs_and_blank_selection_on_the_right() -> None:
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
    for name in (
        "export-task-logs",
        "clear-task-logs",
        "pause-task-log-scroll",
        "show-run-details",
    ):
        assert f'id="{name}"' in rail
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


def test_log_controls_export_clear_and_pause_without_stopping_tasks() -> None:
    javascript = read("tasks.js")
    assert "Monitor.createLogBuffer(300)" in javascript
    assert "taskLogs.ingest(" in javascript
    assert "taskLogs.exportText()" in javascript
    assert "URL.createObjectURL(blob)" in javascript
    assert "URL.revokeObjectURL(url)" in javascript
    assert "taskLogs.clear()" in javascript
    assert "state.logScrollPaused = !state.logScrollPaused" in javascript
    assert "? scrollTop : elements.logList.scrollHeight" in javascript
    assert "text/plain;charset=utf-8" in javascript
    assert "innerHTML" not in javascript


def test_task_details_use_static_names_and_keep_interruption_recovery() -> None:
    html = read("tasks.html")
    javascript = read("tasks.js")
    runtime = between(javascript, "function runtimeCard(run)", "function renderQueue")

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


def test_initial_load_reads_only_modules_and_runs() -> None:
    javascript = read("tasks.js")
    loading = between(
        javascript,
        "async function loadAll()",
        "function isActiveRun",
    )

    assert '"/v1/task-modules"' in loading
    assert '"/v1/task-runs"' in loading
    assert loading.count("Stowling.request(") == 2
    for removed in (
        '"/v1/tasks"',
        '"/v1/task-folders"',
        '"/v1/task-triggers"',
        "renderTaskList()",
        "renderRunControls()",
    ):
        assert removed not in loading
    assert 'Stowling.onConnected(() => loadAll());' in javascript
    assert 'elements.refreshRuns.addEventListener("click", () => void loadRuns());' in (
        javascript
    )
    assert "updateRunActions" not in javascript
