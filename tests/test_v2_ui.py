from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "src" / "main" / "resources" / "stowling-web"


def read(name: str) -> str:
    return (WEB / name).read_text(encoding="utf-8")


def test_coordinate_editor_exposes_only_name_and_xyz() -> None:
    html = read("coordinates.html")
    javascript = read("coordinates.js")
    assert "坐标管理" in html
    for field in ("名称", "X", "Y", "Z"):
        assert field in html
    for removed in ("维度", "半径", "区域", "第二角点", "容器"):
        assert removed not in html
    assert "/v1/coordinates/${encodeURIComponent(coordinate.id)}" in javascript
    assert "coordinate.name" in javascript
    assert "coordinate.x" in javascript
    assert "coordinate.y" in javascript
    assert "coordinate.z" in javascript


def test_task_editor_uses_module_catalog_without_hardcoded_parameters() -> None:
    html = read("tasks.html")
    model = read("v2-task-model.js")
    editor = read("v2-task-editor.js")
    tasks = read("tasks.js")
    assert "模块步骤" in html
    assert "/v1/task-modules" in tasks
    assert "module.parameters" in editor
    assert "parameter.kind" in editor
    assert "coordinate-point" in editor
    assert "task.steps.splice" in editor
    assert "index + offset" in editor
    assert "modules[0]" in model
    assert "MOVE_TO_COORDINATE" not in model
    assert "coordinateId" not in model
    assert "crypto.randomUUID" not in model
    assert "payloadStep" in model


def test_coordinate_choices_only_show_the_saved_name() -> None:
    model = read("coordinate-model.js")

    assert 'return String(value?.name || "");' in model
    assert "value.x" not in model
    assert "value.y" not in model
    assert "value.z" not in model


def test_task_ui_supports_save_run_refresh_and_cancel() -> None:
    html = read("tasks.html")
    tasks = read("tasks.js")
    for text in ("保存任务", "运行所选任务", "刷新状态", "取消任务"):
        assert text in html or text in tasks
    assert "/v1/tasks/${encodeURIComponent(state.runTaskId)}/runs" in tasks
    assert "/v1/task-runs" in tasks
    assert "/v1/task-runs/${encodeURIComponent(runId)}/cancel" in tasks
    assert "run.state === \"RUNNING\"" in tasks
    assert "run.completedSteps" in tasks
    assert "run.totalSteps" in tasks


def test_task_execution_is_separate_from_the_editor() -> None:
    html = read("tasks.html")
    tasks = read("tasks.js")
    editor = html.split('id="task-form"', 1)[1].split("</form>", 1)[0]

    assert 'id="run-task-button"' not in editor
    assert 'id="task-run-panel"' in html
    assert 'id="run-task-select"' in html
    assert "state.runTaskId" in tasks
    assert "renderRunControls()" in tasks


def test_dashboard_uses_new_resource_endpoints_only() -> None:
    dashboard = read("dashboard.js")
    assert "/v1/status" in dashboard
    assert "/v1/coordinates" in dashboard
    assert "/v1/tasks" in dashboard
    assert "/v1/task-runs" in dashboard
    for old in ("/v1/config/", "/v1/pickup", "/v1/containers"):
        assert old not in dashboard


def test_retained_pages_hide_internal_ids_and_removed_controls() -> None:
    pages = "\n".join(
        read(name)
        for name in ("coordinates.html", "tasks.html")
    )
    scripts = "\n".join(
        read(name)
        for name in (
            "coordinate-model.js",
            "coordinates.js",
            "v2-task-model.js",
            "v2-task-editor.js",
            "tasks.js",
        )
    )
    for removed in (
        "任务 ID",
        "步骤 ID",
        "UUID",
        "半径",
        "角点",
        "区域",
        "容器",
        "触发器",
    ):
        assert removed not in pages
    assert "任务 ID" not in scripts
    assert "步骤 ID" not in scripts


def test_legacy_pickup_region_container_and_schedule_entries_are_gone() -> None:
    assert not (WEB / "coordinate-regions.js").exists()
    assert not (WEB / "task-resources.js").exists()
    assert not (WEB / "pickup.html").exists()
    assert not (WEB / "containers.html").exists()
    retained = "\n".join(
        path.read_text(encoding="utf-8")
        for path in sorted(WEB.glob("*"))
        if path.is_file()
    )
    for removed in (
        "/v1/config/",
        "/v1/task-definitions",
        "/v1/pickup",
        "/v1/containers",
        "PICKUP",
        "启用调度",
        "触发器",
        "优先级",
    ):
        assert removed not in retained
