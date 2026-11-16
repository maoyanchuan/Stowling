from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "src" / "main" / "resources" / "stowling-web"


def read(name: str) -> str:
    return (WEB / name).read_text(encoding="utf-8")


def between(source: str, start: str, end: str) -> str:
    start_index = source.index(start)
    content_start = start_index + len(start)
    return source[content_start:source.index(end, content_start)]


def test_reference_select_explains_required_producer_without_changing_targets() -> None:
    editor = read("v2-task-editor.js")
    control = between(
        editor,
        "function referenceSelect(",
        "function normalizeLegacyReference(",
    )

    assert "OUTPUT_REFERENCE_HINTS[select.value]" in control
    assert "hint.hidden = !hint.textContent" in control
    assert "updateHint();" in control
    assert "setParameter(step, parameter.key, select.value, context.changed)" in control
    assert "前序选择 · 标记容器" in editor
    assert "收集掉落物不会产生此结果" in editor
    assert "请选择已保存资源或前序选择结果" in editor
    assert "select.value = current" in control
    for reference in ("container", "storage", "marker"):
        assert f'"output:{reference}"' in editor


def test_display_area_and_selection_panel_keep_the_fixed_move_workbench() -> None:
    html = read("tasks.html")
    styles = read("styles.css")
    display = between(html, 'id="task-display-area"', "</section>")
    selection = between(
        html,
        'class="panel task-selection-panel"',
        "</section>",
    )

    assert 'id="task-display-title">任务展示</h2>' in display
    assert 'id="task-list-title">任务选择</h2>' in selection
    for retained in (
        'id="move-task-form"',
        'id="move-task-name"',
        'id="move-saved-coordinate"',
        'id="save-move-task"',
        'id="run-move-task"',
    ):
        assert retained in display
    for retained in (
        'id="new-move-task"',
        'id="move-task-list"',
        'id="refresh-move-tasks"',
    ):
        assert retained in selection
    assert 'id="task-list"' not in display
    assert 'id="task-list"' not in selection
    assert 'id="task-form"' not in display
    assert ".move-task-form" in styles
    assert ".move-task-option" in styles
    assert "min-height: 10rem;" in between(styles, ".task-selection-panel {", "}")
    assert "margin-bottom: 0;" in between(
        styles,
        ".task-display-placeholder .panel-heading,",
        "}",
    )


def test_task_page_removes_editor_draft_and_task_list_state() -> None:
    javascript = read("tasks.js")
    html = read("tasks.html")

    assert 'id="task-editor-title"' not in html
    assert 'id="task-draft-status"' not in html
    assert "编辑配置 · 不影响已在运行的任务" not in html
    for removed in (
        "state.draft",
        "state.tasks",
        "state.selectedId",
        "renderTaskList",
        "taskListSummary",
        "有未保存修改",
    ):
        assert removed not in javascript


def test_runtime_separates_active_history_and_preserves_expansion() -> None:
    javascript = read("tasks.js")
    runtime = between(javascript, "function renderRuntime()", "function runtimeCard")

    assert "const activeRuns = runs.filter(isActiveRun)" in runtime
    assert "const historyRuns = runs.filter((run) => !isActiveRun(run))" in runtime
    assert "history.open = wasOpen ?? activeRuns.length === 0" in runtime
    assert "当前执行" in runtime
    assert "当前空闲，没有正在执行的任务" in runtime
    assert "最近记录" in runtime
    active = between(javascript, "function isActiveRun(run)", "function runStateLabel")
    assert '"INTERRUPTION_FAILED"' in active


def test_failure_card_keeps_reason_progress_and_recovery_without_selection() -> None:
    javascript = read("tasks.js")
    card = between(javascript, "function runtimeCard(run)", "function renderQueue")

    assert "失败原因：" in card
    assert "runStateMessage(run)" in card
    assert "${run.completedSteps} / ${run.totalSteps} 个根步骤" in card
    assert 'Stowling.element("strong", "", run.taskName)' in card
    assert "selectTask" not in card
    assert '"选择任务"' not in card
    assert "retryInterruption(run.runId)" in card
    assert "skipInterruption(run.runId)" in card
    assert "cancelRun(run.runId)" in card
    styles = read("styles.css")
    assert ".task-run-card.is-failed" in styles
    assert ".task-reference-hint[hidden]" in styles
    assert "overflow-wrap: anywhere;" in styles


def test_monitor_state_stays_separate_from_fixed_move_configuration() -> None:
    javascript = read("tasks.js")

    for removed in (
        "selectTask",
        "runTaskId",
        "selectedId",
        "state.tasks",
        '"/v1/task-folders"',
        '"/v1/task-triggers"',
    ):
        assert removed not in javascript
    loading = between(javascript, "async function loadAll()", "function isActiveRun")
    assert '"/v1/tasks"' not in loading
    assert '"/v1/coordinates"' not in loading
    assert "state.moveTasks" in javascript
    assert "Move.draftFromTask(task)" in javascript
    assert "Move.isMoveTask" in javascript
    assert '"/v1/tasks"' in javascript
    assert '"/v1/coordinates"' in javascript
    assert 'Stowling.element("strong", "rail-task-name", entry.taskName)' in javascript
    assert 'Stowling.element("strong", "", run.taskName)' in javascript


def test_runtime_uses_catalog_names_and_keeps_interruption_failure_detail() -> None:
    javascript = read("tasks.js")
    message = between(javascript, "function runStateMessage(run)", "async function loadRuns")

    assert "item.id === moduleId" in message
    assert "${module.name}" in message
    assert "String(run.message" in message
    assert '(message || "中断收尾失败")' in message
    assert "no marker container selected" in message
    assert "未选择标记容器" in message


def test_nested_steps_show_one_based_numbers_in_existing_branch_tree() -> None:
    editor = read("v2-task-editor.js")

    assert '"workflow-node-position"' in editor
    assert 'path.map((index) => index + 1).join(".")' in editor
    assert 'position.setAttribute("aria-label", `步骤 ${position.textContent}`)' in editor
    assert 'kind === "IF" ? "满足条件" : "重复执行"' in editor
    assert '"否则"' in editor
