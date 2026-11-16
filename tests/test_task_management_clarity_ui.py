from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "src" / "main" / "resources" / "stowling-web"


def read(name: str) -> str:
    return (WEB / name).read_text(encoding="utf-8")


def test_reference_select_explains_required_producer_without_changing_targets() -> None:
    editor = read("v2-task-editor.js")
    control = editor.split("function referenceSelect(", 1)[1]
    control = control.split("function normalizeLegacyReference(", 1)[0]

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


def test_task_list_separates_purpose_and_summarizes_folder_and_activation() -> None:
    javascript = read("tasks.js")
    listing = javascript.split("function renderTaskList()", 1)[1]
    listing = listing.split("function taskListSummary(task)", 1)[0]

    assert '"中断收尾" : "日常任务"' in listing
    assert 'state.taskFilter === "ALL"' in listing
    assert "taskListSummary(task)" in listing
    assert "&& matchesTaskFolder(task)" in listing
    assert "|| compareTasks(left, right)" in listing
    summary = javascript.split("function taskListSummary(task)", 1)[1]
    summary = summary.split("function compareTasks", 1)[0]
    assert "item.id === task.folderId" in summary
    assert "trigger.targetTaskId === task.id" in summary
    assert "${boundTriggers} 个触发器" in summary
    assert "trigger.enabled" not in summary
    assert "task.repeatWhenIdle" in summary


def test_runtime_separates_active_history_and_preserves_expansion() -> None:
    javascript = read("tasks.js")
    runtime = javascript.split("function renderRuntime()", 1)[1]
    runtime = runtime.split("function runtimeCard", 1)[0]

    assert "const activeRuns = runs.filter(isActiveRun)" in runtime
    assert "const historyRuns = runs.filter((run) => !isActiveRun(run))" in runtime
    assert "history.open = wasOpen ?? activeRuns.length === 0" in runtime
    assert "当前执行" in runtime
    assert "当前空闲，没有正在执行的任务" in runtime
    assert "最近记录" in runtime
    active = javascript.split("function isActiveRun(run)", 1)[1]
    active = active.split("function runStateLabel", 1)[0]
    assert '"INTERRUPTION_FAILED"' in active


def test_failure_card_keeps_reason_progress_and_task_selection() -> None:
    javascript = read("tasks.js")
    card = javascript.split("function runtimeCard(run)", 1)[1]
    card = card.split("function renderQueue", 1)[0]

    assert "失败原因：" in card
    assert "runStateMessage(run)" in card
    assert "${run.completedSteps} / ${run.totalSteps} 个根步骤" in card
    assert "item.id === run.taskId" in card
    assert "if (task)" in card
    assert "selectTask(task, true)" in card
    assert '"选择任务"' in card
    assert "retryInterruption(run.runId)" in card
    assert "skipInterruption(run.runId)" in card
    assert "cancelRun(run.runId)" in card
    assert "confirmDiscardForTaskChange" not in card
    styles = read("styles.css")
    assert ".task-run-card.is-failed" in styles
    assert ".task-reference-hint[hidden]" in styles
    assert "overflow-wrap: anywhere;" in styles


def test_display_area_is_reserved_without_an_editor_or_draft_state() -> None:
    javascript = read("tasks.js")
    html = read("tasks.html")

    assert 'id="task-display-area"' in html
    assert "task-display-placeholder" in html
    assert "任务展示" in html
    assert 'id="task-editor-title"' not in html
    assert 'id="task-draft-status"' not in html
    assert "编辑配置 · 不影响已在运行的任务" not in html
    assert "state.draft" not in javascript
    assert "有未保存修改" not in javascript


def test_monitor_task_selection_is_pure_and_reveals_existing_task() -> None:
    javascript = read("tasks.js")
    select = javascript.split("function selectTask(task, revealInList = false)", 1)[1]
    select = select.split("function renderRunControls", 1)[0]

    assert "confirmDiscardForTaskChange" not in select
    assert "state.selectedId = task.id" in select
    assert "state.runTaskId = task.id" in select
    assert 'state.query = ""' in select
    assert "state.folderFilter = FOLDER_FILTER_ALL" in select
    assert "renderTaskList()" in select
    assert "renderRunControls()" in select


def test_runtime_uses_catalog_names_and_keeps_interruption_failure_detail() -> None:
    javascript = read("tasks.js")
    message = javascript.split("function runStateMessage(run)", 1)[1]
    message = message.split("async function runTask()", 1)[0]

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
