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
        'Autosave.write(endpoint, "POST", payload)',
        "`/v1/tasks/${encodeURIComponent(id)}/runs`",
    ):
        assert retained in javascript
    assert "Model.priorityLabel(" in javascript
    assert "Monitor.queueEntries(" in javascript


def test_saved_task_summary_actions_are_separate_from_disclosure_and_save() -> None:
    move = read("tasks.js")
    classify = read("auto-classify-ui.js")
    eat = read("auto-eat-ui.js")
    assert "header.append(title, button, run);" in move
    assert "StowlingSettings.switchControl(" in move
    assert "queueMoveTask(task.id, run.checked)" in move
    for source in (classify, eat):
        assert 'switchControl(' in source
    assert "header.append(button, listening);" in classify
    assert "header.append(title, button, listening);" in eat

    eat_save = between(eat, "async function save(deleting)", "newTask.addEventListener")
    eat_switch = between(eat, "async function setListening(", "async function save(deleting)")
    assert "enabled:" not in eat_save
    assert 'const operation = enabled ? "start" : "stop";' in eat_switch
    assert "${endpoint}/${encodeURIComponent(id)}/${operation}" in eat_switch


def test_material_task_uses_inline_disclosure_and_shared_switch() -> None:
    html = read("tasks.html")
    draft = between(html, 'id="material-editor"', 'id="auto-eat-draft"')
    assert 'id="toggle-material-draft"' in draft
    assert 'aria-expanded="false"' in draft
    assert 'aria-controls="material-draft-body"' in draft
    assert 'id="material-draft-body" class="move-task-body" hidden' in draft
    assert 'aria-describedby="material-boxed-note"' in draft

    source = read("material-collection-ui.js")
    select = between(source, "function selectTask(task)", "function closeEditor()")
    toggle, switch_task = select.split("} else {", 1)
    assert "current.expanded = !current.expanded;" in toggle
    assert "fillForm(task)" not in toggle
    assert "row.body.append(form)" in switch_task
    assert "Settings.reveal(body, expanded)" in source
    assert 'Settings.switchControl(byId("material-boxed-delivery"))' in source


def test_material_task_card_keeps_delete_in_editor_and_reports_autosave() -> None:
    html = read("tasks.html")
    editor = between(html, 'id="material-task-form"', "</form>")
    assert 'id="delete-material-task"' in editor
    assert "task-delete-action" in editor
    assert 'id="material-status"' not in html

    source = read("material-collection-ui.js")
    rows = between(source, "function renderRows()", "function applyTasks(")
    assert "summary.append(kind, overview, state, chevron);" in rows
    assert "head.append(summary, run);" in rows
    assert '"secondary move-task-run material-task-run"' in rows
    assert 'showStatus("已自动保存")' in source
    assert 'S.showToast("材料收集任务已删除")' in source


def test_material_unstackable_submenu_keeps_liquids_and_adds_collection_switches() -> None:
    html = read("tasks.html")
    submenu = between(html, 'id="material-unstackable-settings"', "</details>")
    assert 'id="material-unstackable-summary"' in submenu
    assert "不可堆叠材料" in submenu
    for option in (
        "material-ice-instead-of-water", "material-skip-water", "material-skip-lava",
        "material-skip-shulker-boxes", "material-skip-minecarts",
    ):
        assert f'id="{option}"' in submenu
    for label in ("不收集水桶", "不收集岩浆桶", "不收集潜影盒", "不收集矿车"):
        assert f"<span>{label}</span>" in submenu

    source = read("material-collection-ui.js")
    assert "if (iceInsteadOfWater.checked) skipWater.checked = false;" in source
    assert "if (skipWater.checked) iceInsteadOfWater.checked = false;" in source
    assert 'skipLava.addEventListener("change", renderPreview);' in source


def test_material_editor_groups_source_delivery_and_unstackable_settings() -> None:
    editor = between(read("tasks.html"), 'id="material-task-form"', "</form>")
    sections = (
        ("source", "投影与材料", ("material-file", "material-preview-status", "material-preview")),
        ("delivery", "交付设置", ("material-destination", "material-boxed-delivery")),
        ("unstackable", "不可堆叠材料", ("material-skip-minecarts",)),
    )
    assert editor.count("<details ") == 3
    assert 'id="material-name"' not in editor
    for key, title, fields in sections:
        section = between(editor, f'id="material-{key}-settings"', "</details>")
        assert 'class="settings-disclosure"' in section
        assert f"<strong>{title}</strong>" in section
        assert f'id="material-{key}-description"' in section
        for field in fields:
            assert f'id="{field}"' in section
    assert editor.index('id="material-save-status"') > editor.rindex("</details>")


def test_material_boxed_delivery_has_a_switchable_submenu_and_two_radio_choices() -> None:
    html = read("tasks.html")
    options = between(html, 'id="material-boxed-options"', "</div>")
    assert 'id="material-mixed-boxes"' in options
    assert 'id="material-pure-boxes"' in options
    assert options.count('type="radio"') == 2
    assert options.count('name="material-boxed-mode"') == 2
    assert 'role="radiogroup"' in options
    assert "混杂盒交付" in options
    assert "纯净盒交付" in options

    source = read("material-collection-ui.js")
    assert 'Settings.optionalSection("按潜影盒交付"' in source
    assert 'byId("material-boxed-settings").replaceChildren(boxedSettings.root);' in source
    assert ('pureBoxes.checked = '
            'params?.singleMaterialBoxes?.toLowerCase() === "true"') in source
    assert "singleMaterialBoxes: String(pureBoxes.checked)" in source


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
