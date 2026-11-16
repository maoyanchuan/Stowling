from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "src" / "main" / "resources" / "stowling-web"


def read(name: str) -> str:
    return (WEB / name).read_text(encoding="utf-8")


def test_inventory_is_not_exposed_in_navigation_but_sources_are_preserved() -> None:
    common = read("common.js")
    navigation = common.split("const NAV_ITEMS", 1)[1].split("]);", 1)[0]
    assert "库存查询" not in navigation
    assert "/storage.html" not in navigation
    assert (WEB / "storage.html").exists()
    assert (WEB / "storage.js").exists()


def test_narrow_rail_has_queue_above_logs_and_selection_on_the_right() -> None:
    html = read("tasks.html")
    rail = html.split('<aside class="task-monitor-rail"', 1)[1].split("</aside>", 1)[0]
    assert rail.index('id="task-queue-list"') < rail.index('id="task-log-list"')
    assert html.index("</aside>") < html.index('id="task-selection-area"')
    assert "data-task-tab=" not in html
    assert "data-task-panel=" not in html
    assert "紧急 → 普通 → 后台" in rail
    for name in (
        "export-task-logs", "clear-task-logs", "pause-task-log-scroll", "show-run-details"
    ):
        assert f'id="{name}"' in rail
    assert 'role="log"' in rail
    assert 'aria-live="off"' in rail
    assert 'tabindex="0"' in rail


def test_monitor_uses_shared_model_and_pure_task_selection() -> None:
    html = read("tasks.html")
    javascript = read("tasks.js")
    assert html.index('src="/task-monitor-model.js"') < html.index('src="/tasks.js"')
    queue = javascript.split("function renderQueue()", 1)[1]
    queue = queue.split("function railAction", 1)[0]
    assert "Monitor.queueEntries(" in queue
    assert "state.runs, state.queue, state.triggerStatuses" in queue
    assert "selectTask(task, true)" in queue
    assert "confirmDiscardForTaskChange" not in queue
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


def test_task_details_select_tasks_and_keep_interruption_recovery() -> None:
    html = read("tasks.html")
    javascript = read("tasks.js")
    runtime = javascript.split("function runtimeCard(run)", 1)[1]
    runtime = runtime.split("function renderQueue", 1)[0]
    queue = javascript.split("function renderQueue()", 1)[1]
    queue = queue.split("function railAction", 1)[0]

    assert '<dialog id="task-run-details"' in html
    assert 'aria-labelledby="runtime-title"' in html
    assert "elements.runDetails.showModal()" in javascript
    assert "elements.runDetails.close()" in javascript
    assert 'state === "INTERRUPTION_FAILED"' in javascript
    assert "查看任务配置" not in javascript
    assert '"选择任务"' in runtime
    assert 'name.title = "选择任务"' in queue
    assert "selectTask(task, true)" in runtime
    assert "retryInterruption(run.runId)" in runtime
    assert "skipInterruption(run.runId)" in runtime


def test_layout_keeps_scrollable_rail_and_responsive_task_cards() -> None:
    css = read("styles.css")
    console = css.split(".task-console {", 1)[1].split("}", 1)[0]
    assert "grid-template-columns: 300px minmax(0, 1fr)" in console
    rail = css.split(".task-monitor-rail {", 1)[1].split("}", 1)[0]
    assert "position: sticky" in rail
    assert "grid-template-rows: minmax(0, 1.1fr) minmax(0, 1fr)" in rail
    assert "overscroll-behavior: contain" in css
    assert "repeat(auto-fill, minmax(min(100%, 240px), 1fr))" in css
    assert "@media (max-width: 779px)" in css
    assert "@media (max-width: 520px)" in css


def test_initial_load_does_not_force_open_the_first_task() -> None:
    javascript = read("tasks.js")
    loading = javascript.split("async function loadAll()", 1)[1]
    loading = loading.split("function renderTaskList()", 1)[0]

    assert "selectTask(state.tasks[0]" not in loading
    assert "openTriggersAfterTaskReady" not in javascript
