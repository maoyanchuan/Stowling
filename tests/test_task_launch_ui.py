from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "src" / "main" / "resources" / "stowling-web"


def read(name: str) -> str:
    return (WEB / name).read_text(encoding="utf-8")


def between(source: str, start: str, end: str) -> str:
    start_index = source.index(start)
    content_start = start_index + len(start)
    return source[content_start:source.index(end, content_start)]


def test_task_page_has_only_the_fixed_move_launch_flow() -> None:
    html = read("tasks.html")

    for removed in (
        'id="task-run-panel"',
        'id="run-task-select"',
        'id="run-priority-select"',
        'id="run-task-button"',
        'id="task-launch-note"',
        'class="task-launch-grid"',
    ):
        assert removed not in html
    for removed_text in ("跟随任务", "运行任务", "请选择任务"):
        assert removed_text not in html
    for retained in (
        'id="move-task-form"',
        'id="save-move-task"',
        'id="run-move-task"',
        'id="new-move-task"',
    ):
        assert retained in html
    assert "保存并加入队列" in html


def test_fixed_move_launcher_does_not_restore_generic_task_selection() -> None:
    javascript = read("tasks.js")

    for removed in (
        "function runTask(",
        "renderRunControls",
        "updateRunActions",
        "state.runTaskId",
        "selectTask",
        "/v1/task-folders",
        "/v1/task-triggers",
    ):
        assert removed not in javascript
    for retained in (
        "function selectMoveTask(",
        "async function saveMoveTask(",
        "method: task.id ? \"PUT\" : \"POST\"",
        "`/v1/tasks/${encodeURIComponent(state.moveDraft.id)}/runs`",
    ):
        assert retained in javascript
    assert "Model.priorityLabel(" in javascript
    assert "Monitor.queueEntries(" in javascript


def test_refresh_only_rereads_task_runs() -> None:
    javascript = read("tasks.js")
    refresh = between(
        javascript,
        "async function loadRuns()",
        "function renderRuntime",
    )

    assert 'Stowling.request("/v1/task-runs")' in refresh
    assert refresh.count("Stowling.request(") == 1
    for rendered in ("renderRuntime();", "renderQueue();", "appendMonitorLogs();"):
        assert rendered in refresh
    assert "POST" not in refresh
    assert "cancel" not in refresh
