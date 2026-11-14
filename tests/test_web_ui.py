from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]
WEB_ROOT = ROOT / "src" / "main" / "resources" / "stowling-web"


def read_web(name: str) -> str:
    return (WEB_ROOT / name).read_text(encoding="utf-8")


PAGES = {
    "index.html": read_web("index.html"),
    "coordinates.html": read_web("coordinates.html"),
    "tasks.html": read_web("tasks.html"),
    "storage.html": read_web("storage.html"),
}
COMMON = read_web("common.js")
COORDINATES = read_web("coordinates.js")
TASKS = read_web("tasks.js")
STORAGE = read_web("storage.js")
DASHBOARD = read_web("dashboard.js")
STYLES = read_web("styles.css")
SCRIPTS = COMMON + COORDINATES + TASKS + STORAGE + DASHBOARD


def test_four_pages_are_real_urls_with_shared_assets() -> None:
    expected_titles = {
        "index.html": "状态概览",
        "coordinates.html": "命名坐标",
        "tasks.html": "任务编排",
        "storage.html": "仓库与库存",
    }

    for filename, html in PAGES.items():
        assert f"<title>{expected_titles[filename]}" in html
        assert 'id="app-nav"' in html
        assert 'id="auth-shell"' in html
        assert 'id="workspace"' in html
        assert 'src="/common.js"' in html
        assert 'href="/styles.css"' in html
        assert 'href="#' not in html

    for route in (
        "/index.html",
        "/coordinates.html",
        "/tasks.html",
        "/storage.html",
    ):
        assert f'href: "{route}"' in COMMON


def test_each_page_loads_its_focused_script() -> None:
    assert 'src="/dashboard.js"' in PAGES["index.html"]
    assert 'src="/coordinates.js"' in PAGES["coordinates.html"]
    assert 'src="/tasks.js"' in PAGES["tasks.html"]
    assert 'src="/storage.js"' in PAGES["storage.html"]
    assert "app.js" not in "".join(PAGES.values())


def test_coordinate_page_supports_named_coordinate_crud_and_ordering() -> None:
    html = PAGES["coordinates.html"]
    for element_id in (
        "coordinate-form",
        "coordinate-list",
        "add-coordinate-button",
        "discard-coordinate-button",
        "save-coordinate-button",
        "coordinate-errors",
    ):
        assert f'id="{element_id}"' in html

    for fragment in (
        "function addPoint()",
        "function removePoint(index)",
        "function movePoint(index, offset)",
        "function createPointCard(point, index)",
        'for (const axis of ["x", "y", "z"])',
        'document.createElement("select")',
        "state.draft.coordinatePoints.splice(index, 1)",
    ):
        assert fragment in COORDINATES

    assert "坐标 ID" in COORDINATES
    assert "显示名称" in COORDINATES
    assert "维度" in COORDINATES


def test_coordinate_page_uses_atomic_revision_save() -> None:
    assert 'Stowling.request("/v1/config/coordinate-points")' in COORDINATES
    assert 'method: "PUT"' in COORDINATES
    assert "revision: state.snapshot.revision" in COORDINATES
    assert "coordinatePoints:" in COORDINATES
    assert 'error.status === 409' in COORDINATES
    assert 'error?.code === "active_task_conflict"' in COORDINATES
    assert "配置已被外部修改" in COORDINATES
    assert "活动或暂停任务存在" in COORDINATES


def test_coordinate_delete_checks_pickup_and_automation_references() -> None:
    assert 'Stowling.request("/v1/config/pickup")' in COORDINATES
    assert 'Stowling.request("/v1/config/automation-workflows")' in COORDINATES
    assert "standbyCoordinateId" in COORDINATES
    assert "centerCoordinateId" in COORDINATES
    assert "firstCornerCoordinateId" in COORDINATES
    assert "secondCornerCoordinateId" in COORDINATES
    assert "仍被" in COORDINATES
    assert "请先在对应页面解除引用" in COORDINATES


def test_task_page_is_a_visual_module_builder() -> None:
    html = PAGES["tasks.html"]
    for element_id in (
        "workflow-form",
        "workflow-list",
        "workflow-editor",
        "step-list",
        "add-workflow-button",
        "add-step-button",
        "save-workflow-button",
        "launch-workflow",
        "start-task-button",
        "task-list",
    ):
        assert f'id="{element_id}"' in html

    for fragment in (
        "function addWorkflow()",
        "function addStep()",
        "function moveWorkflow(index, offset)",
        "function moveStep(workflow, index, offset)",
        "function moduleSelect(step, index, workflow)",
        "function parameterControl(step, definition, stepIndex)",
        "workflow.steps.splice(index, 1)",
    ):
        assert fragment in TASKS


def test_task_builder_exposes_all_first_release_modules() -> None:
    expected_types = (
        "NAVIGATE",
        "WAIT",
        "RUN_PICKUP",
        "TAKE_ITEMS",
        "PUT_ITEMS",
        "TRANSFER_ITEMS",
        "CHECK_INVENTORY",
        "ACQUIRE_MATERIALS",
        "CRAFT_ITEM",
    )
    for module_type in expected_types:
        assert module_type in TASKS

    for field_name in (
        "coordinateId",
        "workflowId",
        "regionId",
        "sourceContainerId",
        "destinationContainerId",
        "itemId",
        "count",
        "durationSeconds",
    ):
        assert field_name in TASKS


def test_task_requests_match_backend_parameter_contract() -> None:
    assert 'type === "WAIT"' in TASKS
    assert "result.seconds" in TASKS
    assert "result.containerId" in TASKS
    assert "result.destination" in TASKS
    assert 'String(parameters.durationSeconds ?? "")' in TASKS
    assert 'String(parameters.sourceContainerId || "")' in TASKS
    assert 'String(parameters.destinationContainerId || "")' in TASKS
    assert "delete result[key]" in TASKS


def test_task_page_uses_expected_automation_apis() -> None:
    assert '"/v1/config/automation-workflows"' in TASKS
    assert '"/v1/automation-modules"' in TASKS
    assert '"/v1/tasks/automation"' in TASKS
    assert '"/v1/tasks"' in TASKS
    assert "`/v1/tasks/${encodeURIComponent(taskId)}/cancel`" in TASKS
    assert '"/v1/pause"' in TASKS
    assert '"/v1/resume"' in TASKS
    assert "workflowId" in TASKS
    assert "currentStepId" in TASKS
    assert "moduleState" in TASKS


def test_task_save_is_atomic_and_validation_is_explicit() -> None:
    assert "revision: state.snapshot.revision" in TASKS
    assert "automationWorkflows:" in TASKS
    assert 'method: "PUT"' in TASKS
    assert "function validateDraft()" in TASKS
    assert "至少需要一个步骤" in TASKS
    assert "重复任务 ID" in TASKS
    assert "不能为空" in TASKS
    assert "配置版本冲突或任务正在运行" in TASKS


def test_storage_page_groups_containers_inventory_and_warehouse_config() -> None:
    html = PAGES["storage.html"]
    for element_id in (
        "container-list",
        "inventory-list",
        "audit-button",
        "base-coordinate-form",
        "pickup-form",
        "region-list",
        "cache-list",
        "pickup-workflow-list",
        "save-pickup-button",
    ):
        assert f'id="{element_id}"' in html

    for path in (
        "/v1/containers",
        "/v1/inventory",
        "/v1/audits",
        "/v1/config/coordinates",
        "/v1/config/coordinate-points",
        "/v1/config/pickup",
    ):
        assert path in STORAGE


def test_pickup_editor_preserves_multi_region_resource_workflow() -> None:
    for fragment in (
        "function addRegion()",
        "function addCache()",
        "function addPickupWorkflow()",
        "function removePickupResource(kind, index)",
        "function movePickupResource(kind, index, offset)",
        "binding.priority",
        "binding.idleDelay",
        "classificationTiming",
        "completionMode",
        "cacheIds",
        "hydratePickupRequest()",
    ):
        assert fragment in STORAGE

    assert "至少选择一个区域" in STORAGE
    assert "至少选择一个缓存" in STORAGE
    assert "第一角点不能大于第二角点" in STORAGE
    assert "半径必须为 1-128" in STORAGE
    assert "跨维度" in STORAGE
    assert "活动或暂停任务存在" in STORAGE


def test_authentication_is_shared_bearer_middleware() -> None:
    assert COMMON.count("fetch(") == 1
    assert SCRIPTS.count("fetch(") == 1
    assert 'headers.set("Authorization", `Bearer ${authToken}`)' in COMMON
    assert 'fetch("/v1/status"' not in SCRIPTS
    assert 'sessionStorage.setItem(TOKEN_KEY, token)' in COMMON
    assert 'sessionStorage.getItem(TOKEN_KEY)' in COMMON
    assert "localStorage" not in SCRIPTS
    assert "token=" not in SCRIPTS.lower()


def test_user_content_uses_safe_dom_apis() -> None:
    assert "innerHTML" not in SCRIPTS
    assert "eval(" not in SCRIPTS
    assert "document.write" not in SCRIPTS
    assert ".textContent" in SCRIPTS
    assert ".replaceChildren" in SCRIPTS
    assert "JSON.stringify" in SCRIPTS
    assert "JSON.parse" in SCRIPTS


def test_scripts_do_not_log_sensitive_values() -> None:
    assert "console." not in SCRIPTS
    assert "console.log" not in SCRIPTS
    assert "accessToken" not in SCRIPTS
    assert "chat" not in SCRIPTS.lower()


def test_dashboard_is_status_overview_not_a_configuration_page() -> None:
    html = PAGES["index.html"]
    assert 'id="status-state"' in html
    assert 'id="active-task-count"' in html
    assert 'id="recent-tasks"' in html
    assert 'id="coordinate-form"' not in html
    assert 'id="workflow-form"' not in html
    assert 'href="/tasks.html"' in html
    assert 'href="/coordinates.html"' in html


def test_layout_is_responsive_and_supports_dark_mode() -> None:
    assert ".page-workspace" in STYLES
    assert ".builder-grid" in STYLES
    assert ".module-catalog" in STYLES
    assert ".task-launch-grid" in STYLES
    assert "@media (max-width: 760px)" in STYLES
    assert "@media (prefers-color-scheme: dark)" in STYLES


def test_all_web_sources_follow_line_length_rule() -> None:
    files = [path for path in WEB_ROOT.iterdir() if path.is_file()]
    violations = []
    for path in files:
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if len(line) > 100:
                violations.append(f"{path.name}:{number}:{len(line)}")
    assert violations == []


def test_pages_do_not_retain_hash_anchor_navigation() -> None:
    assert re.search(r'href=["\']#', "".join(PAGES.values())) is None
    assert "data-workspace-target" not in COMMON
    assert "hashchange" not in SCRIPTS
