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
        'id="run-move-task"',
        'id="start-auto-task"',
        'id="stop-auto-task"',
        'id="auto-eat-enabled"',
        'class="task-launch-grid"',
    ):
        assert removed not in html
    for removed_text in ("跟随任务", "运行任务", "请选择任务"):
        assert removed_text not in html
    for retained in (
        'id="move-task-form"',
        'id="save-move-task"',
        'id="new-move-task"',
    ):
        assert retained in html
    assert "保存并加入队列" not in html
    assert "保存并开始监听" not in html


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
        "`/v1/tasks/${encodeURIComponent(id)}/runs`",
    ):
        assert retained in javascript
    assert "Model.priorityLabel(" in javascript
    assert "Monitor.queueEntries(" in javascript


def test_saved_task_summary_actions_are_separate_from_disclosure_and_save() -> None:
    move = read("tasks.js")
    classify = read("auto-classify-ui.js")
    eat = read("auto-eat-ui.js")
    assert "header.append(button, run);" in move
    assert 'run.type = "button";' in move
    assert "queueMoveTask(task.id)" in move
    for source in (classify, eat):
        assert 'switchControl(' in source
        assert "header.append(button, listening);" in source

    eat_save = between(eat, "async function save(deleting)", "newTask.addEventListener")
    eat_switch = between(eat, "async function setListening(", "async function save(deleting)")
    assert "enabled:" not in eat_save
    assert 'const operation = enabled ? "start" : "stop";' in eat_switch
    assert "${endpoint}/${encodeURIComponent(id)}/${operation}" in eat_switch


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
