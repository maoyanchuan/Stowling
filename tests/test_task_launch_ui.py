from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "src" / "main" / "resources" / "stowling-web"


def read(name: str) -> str:
    return (WEB / name).read_text(encoding="utf-8")


def test_task_launcher_is_bounded_and_keeps_responsive_layouts() -> None:
    html = read("tasks.html")
    css = read("styles.css")
    panel = css.split(".task-launch-panel {", 1)[1].split("}", 1)[0]

    assert 'class="task-launch-panel"' in html
    selection = html.split('id="task-selection-area"', 1)[1]
    assert 'id="task-run-panel"' in selection
    assert "width: 100%;" in panel
    assert "max-width: 52rem;" in panel
    assert "justify-self: start;" in panel
    assert "grid-template-columns: minmax(0, 1fr) 11rem auto;" in css
    tablet = css.split("@media (max-width: 1050px)", 1)[1]
    tablet = tablet.split("@media", 1)[0]
    assert ".task-launch-grid {" in tablet
    assert "grid-template-columns: repeat(2, minmax(0, 1fr));" in tablet
    mobile = css.split("@media (max-width: 600px)", 1)[1]
    assert ".task-launch-grid {" in mobile
    assert "grid-template-columns: minmax(0, 1fr);" in mobile


def test_task_launcher_removes_repeated_priority_from_task_names() -> None:
    javascript = read("tasks.js")
    controls = javascript.split("function renderRunControls()", 1)[1]
    controls = controls.split("function updateRunActions()", 1)[0]

    assert "new Option(task.name, task.id)" in controls
    assert "Model.priorityLabel(task.priority)" not in controls
    assert "elements.runSelect.value = preserved" in controls
    assert "state.tasks[0]?.id" not in controls
    actions = javascript.split("function updateRunActions()", 1)[1]
    actions = actions.split("function isActiveRun", 1)[0]
    assert "elements.runPriority.options[0].textContent = task" in actions
    assert "跟随任务（${Model.priorityLabel(task.priority)}）" in actions
    assert "elements.runPriority.value =" not in actions
    assert "elements.runSelect.disabled = state.tasks.length === 0" in actions
    assert "elements.run.disabled = !state.runTaskId" in actions


def test_run_priority_remains_an_optional_one_time_override() -> None:
    html = read("tasks.html")
    javascript = read("tasks.js")
    panel = html.split('id="task-run-panel"', 1)[1].split("</section>", 1)[0]

    assert '<option value="">跟随任务</option>' in panel
    assert 'aria-describedby="task-launch-note"' in panel
    assert "仅使用已保存的流程" in panel
    assert "只对本次运行生效" in panel
    for priority in ("BACKGROUND", "NORMAL", "URGENT"):
        assert f'<option value="{priority}">' in panel
    running = javascript.split("async function runTask()", 1)[1]
    running = running.split("async function loadRuns()", 1)[0]
    assert "JSON.stringify(elements.runPriority.value" in running
    assert "? { priority: elements.runPriority.value }" in running
    assert ": {})" in running
