import json
from pathlib import Path
import re
import struct


ROOT = Path(__file__).resolve().parents[1]
WEB_ROOT = ROOT / "src" / "main" / "resources" / "stowling-web"
RESOURCE_ROOT = ROOT / "src" / "main" / "resources"
ICON_SVG = RESOURCE_ROOT / "assets" / "stowling" / "icon.svg"
ICON_PNG = RESOURCE_ROOT / "assets" / "stowling" / "icon.png"
ICON_SCRIPT = ROOT / "scripts" / "generate-brand-icon.ps1"
FABRIC_MOD = RESOURCE_ROOT / "fabric.mod.json"
HTTP_SERVER = (
    ROOT / "src" / "main" / "java" / "dev" / "stowling" / "api"
    / "StowlingHttpServer.java"
)


def read_web(name: str) -> str:
    return (WEB_ROOT / name).read_text(encoding="utf-8")


def read_resource(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def png_dimensions(path: Path) -> tuple[int, int]:
    data = path.read_bytes()
    assert data.startswith(b"\x89PNG\r\n\x1a\n")
    assert data[12:16] == b"IHDR"
    assert data[25] == 6
    return struct.unpack(">II", data[16:24])


PAGES = {
    "index.html": read_web("index.html"),
    "coordinates.html": read_web("coordinates.html"),
    "tasks.html": read_web("tasks.html"),
    "storage.html": read_web("storage.html"),
}
COMMON = read_web("common.js")
COORDINATES = read_web("coordinates.js")
TASKS = read_web("tasks.js")
RESOURCES = read_web("task-resources.js")
STORAGE = read_web("storage.js")
DASHBOARD = read_web("dashboard.js")
STYLES = read_web("styles.css")
SCRIPTS = COMMON + COORDINATES + TASKS + RESOURCES + STORAGE + DASHBOARD


def test_four_pages_are_real_urls_with_shared_assets() -> None:
    expected_titles = {
        "index.html": "状态概览",
        "coordinates.html": "命名坐标",
        "tasks.html": "任务编排",
        "storage.html": "库存查询",
    }

    for filename, html in PAGES.items():
        assert f"<title>{expected_titles[filename]}" in html
        assert 'id="app-nav"' in html
        assert 'id="auth-shell"' in html
        assert 'id="workspace"' in html
        assert '<span class="brand-mark" aria-hidden="true">S</span>' in html
        assert 'rel="icon" type="image/svg+xml" href="/icon.svg"' in html
        assert 'rel="icon" type="image/png" href="/icon.png"' in html
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


def test_project_icon_matches_brand_mark_and_is_reproducible() -> None:
    for declaration in (
        "width: 46px;",
        "height: 46px;",
        "border-radius: 14px 14px 14px 4px;",
        "color: #ffffff;",
        "font-size: 1.42rem;",
        "font-weight: 800;",
        "background: linear-gradient(145deg, #3f8c64, #1c5138);",
    ):
        assert declaration in STYLES

    svg = read_resource(ICON_SVG)
    for fragment in (
        'viewBox="0 0 46 46"',
        'x1="0.1495" y1="0" x2="0.8505" y2="1"',
        'stop-color="#3f8c64"',
        'stop-color="#1c5138"',
        'font-size="22.72"',
        'font-weight="800"',
        "Inter, 'Segoe UI', 'Microsoft YaHei UI', system-ui, sans-serif",
        ">S</text>",
    ):
        assert fragment in svg
    assert "<filter" not in svg
    assert "<rect" not in svg
    assert "stroke=" not in svg

    assert png_dimensions(ICON_PNG) == (512, 512)
    fabric_manifest = json.loads(read_resource(FABRIC_MOD))
    assert fabric_manifest["icon"] == "assets/stowling/icon.png"

    script = read_resource(ICON_SCRIPT)
    assert "src/main/resources/stowling-web/styles.css" in script
    assert "linear-gradient(145deg, #3f8c64, #1c5138);" in script
    assert "Assert-PngMetadata" in script

    for readme in ("README.md", "README_zh-CN.md"):
        header = read_resource(ROOT / readme).splitlines()[:4]
        assert any("assets/stowling/icon.svg" in line for line in header)


def test_each_page_loads_its_focused_scripts() -> None:
    assert 'src="/dashboard.js"' in PAGES["index.html"]
    assert 'src="/coordinates.js"' in PAGES["coordinates.html"]
    assert 'src="/task-resources.js"' in PAGES["tasks.html"]
    assert 'src="/tasks.js"' in PAGES["tasks.html"]
    assert 'src="/storage.js"' in PAGES["storage.html"]
    assert "app.js" not in "".join(PAGES.values())


def test_task_resources_script_has_a_static_route() -> None:
    server = read_resource(HTTP_SERVER)
    assert '"/task-resources.js"' in server
    assert '"stowling-web/task-resources.js"' in server


def test_tasks_page_unifies_pickup_and_automation_with_filters() -> None:
    html = PAGES["tasks.html"]
    for element_id in (
        "task-filter-name",
        "task-filter-kind",
        "task-list",
        "add-task-button",
        "task-form",
        "task-kind",
        "task-priority",
        "save-task-button",
        "delete-task-button",
        "run-task-button",
        "enable-task-button",
        "disable-task-button",
    ):
        assert f'id="{element_id}"' in html

    assert '<option value="PICKUP">自动拾取</option>' in html
    assert '<option value="AUTOMATION">自定义任务</option>' in html
    assert "function renderTaskList()" in TASKS
    assert "elements.filterName.value.trim().toLowerCase()" in TASKS
    assert "const matchesKind = !kind || entry.kind === kind;" in TASKS
    assert "function selectDefinition(kind, id)" in TASKS
    assert "function saveTask()" in TASKS
    assert "function deleteSelectedTask()" in TASKS


def test_task_definition_http_contract_is_atomic_and_single_item() -> None:
    assert '"/v1/task-definitions"' in TASKS
    assert (
        "`/v1/task-definitions/${encodeURIComponent(kind)}`"
        in TASKS
    )
    assert "`/${encodeURIComponent(id)}`" in TASKS
    assert "state.revision ? \"PUT\" : \"POST\"" in TASKS
    assert "{ revision: state.revision, definition }" in TASKS
    assert 'method: "DELETE"' in TASKS
    assert "body: JSON.stringify({ revision: state.revision })" in TASKS
    assert (
        "const suffix = enabled ? \"enable\" : \"disable\";"
        in TASKS
    )
    assert "state.schedules = scheduleMap(result?.scheduling);" in TASKS
    assert "`/${encodeURIComponent(draft.id)}/run`" in TASKS
    assert "任务已保存；调度保持当前启用状态" in TASKS
    assert "/v1/config/automation-workflows" not in TASKS


def test_task_editor_covers_settings_triggers_and_interruptions() -> None:
    for trigger in (
        "MANUAL",
        "FIXED_INTERVAL",
        "DAILY",
        "AFTER_COMPLETION",
    ):
        assert f'"{trigger}"' in TASKS
        assert f'value="{trigger}"' in PAGES["tasks.html"]

    assert '"ITEMS_PRESENT"' in TASKS
    assert 'value="ITEMS_PRESENT"' not in PAGES["tasks.html"]
    assert "function renderTriggerKindOptions(kind)" in TASKS
    assert "旧配置 · 区域有物品时" in TASKS
    assert "旧任务的“区域有物品时”配置仅作兼容显示" in PAGES["tasks.html"]
    assert "intervalSeconds:" in TASKS
    assert "trigger.kind === \"FIXED_INTERVAL\"" in TASKS
    assert "|| trigger.kind === \"AFTER_COMPLETION\"" in TASKS
    assert '"该触发器必须填写大于等于 1 的间隔秒数"' in TASKS
    assert "times: [...draft.settings.trigger.times]" in TASKS
    assert 'zoneId: "Asia/Shanghai"' in TASKS
    assert "regionIds: [...draft.settings.trigger.regionIds]" in TASKS
    assert "autoStartOnRestart: draft.settings.autoStartOnRestart" in TASKS
    assert "kind: \"NONE\"" in TASKS
    assert '["NONE", "INLINE", "TASK_REFERENCE"]' in TASKS
    assert "entry.kind === \"AUTOMATION\" && entry.id !== state.draft.id" \
        in TASKS
    assert "旧配置兼容：留空时沿用旧任务区域" in TASKS
    assert "旧配置兼容：自定义任务需要明确填写区域 ID" in TASKS
    assert "taskId: draft.settings.interruption.kind === \"TASK_REFERENCE\"" \
        in TASKS


def test_task_modules_and_parameters_come_from_descriptors() -> None:
    route = re.search(
        r'AUTOMATION_MODULES\("GET", "([^"]+)"\)',
        read_resource(HTTP_SERVER),
    )
    assert route is not None
    assert f'Stowling.request("{route.group(1)}")' in TASKS
    assert '"/v1/automation/modules"' not in TASKS
    assert "FIELD_DEFINITIONS" not in TASKS
    assert "FALLBACK_MODULES" not in TASKS
    assert "const modules = Array.isArray(payload) ? payload : payload?.modules;" \
        in TASKS
    assert "parameters: (Array.isArray(module?.parameters)" in TASKS
    assert "moduleId: String(module?.moduleId || type)" in TASKS
    assert "aliases: Array.isArray(parameter?.aliases)" in TASKS
    assert "const definitions = moduleFor(step.type)?.parameters || [];" in TASKS
    assert "function moduleSelect(step, steps, scope)" in TASKS
    assert "function parameterControl(steps, step, definition, index, scope)" \
        in TASKS
    assert "step.parameters = {};" in TASKS
    assert "const option = new Option(module.name, module.moduleId);" in TASKS
    assert "step.type = moduleById(select.value)?.type || select.value;" in TASKS
    assert "function parameterValue(step, definition)" in TASKS
    assert "for (const name of [definition.key, ...(definition.aliases || [])])" \
        in TASKS
    assert "function appendParameterAliases(editor, aliases)" in TASKS
    assert "function renderModuleCatalog()" in TASKS
    assert "Array.isArray(parameter?.options)" in TASKS
    assert 'definition.kind === "choice"' in TASKS
    assert 'definition.kind === "options"' in TASKS
    assert 'definition.kind === "nonnegative-integer"' in TASKS


def test_new_task_entry_prefers_direct_rules_and_keeps_legacy_editing() -> None:
    html = PAGES["tasks.html"]
    assert 'id="new-task-dialog"' in html
    assert 'id="new-custom-task-button"' in html
    assert 'id="new-pickup-task-button"' in html
    assert "只预置一个纯区域拾取步骤" in html
    assert "选择坐标、角点和缓存" not in html
    selector = html.split('<select id="task-kind">', 1)[1].split("</select>", 1)[0]
    assert 'value="AUTOMATION"' in selector
    assert 'value="PICKUP"' not in selector
    assert 'state.draft = defaultDefinition("AUTOMATION");' in TASKS
    assert 'type: "RUN_PICKUP"' in TASKS
    assert 'waitForItems: "false"' in TASKS
    assert "function renderTaskKindOptions(kind)" in TASKS
    assert "旧拾取任务（兼容编辑）" in TASKS


def test_pickup_and_automation_task_shapes_match_contract() -> None:
    assert "pickup: null," in TASKS
    assert "steps: draft.kind === \"AUTOMATION\"" in TASKS
    assert "id: result.id," in TASKS
    assert "name: result.name," in TASKS
    assert "regions: draft.pickup.regions.map((binding)" in TASKS
    assert "cacheIds: [...draft.pickup.cacheIds]" in TASKS
    assert "completionIdleDelay: Number(draft.pickup.completionIdleDelay)" \
        in TASKS
    assert "function validatePickupTask(errors)" not in TASKS
    assert "拾取任务至少选择一个区域" not in TASKS
    assert "拾取任务至少选择一个缓存" not in TASKS
    assert "自定义任务至少需要一个步骤" in TASKS
    assert "const legacyPickup = isLegacyPickupStep(step);" in TASKS


def test_schedule_information_and_runtime_actions_are_visible() -> None:
    for fragment in (
        "enabled: Boolean(value?.enabled)",
        'state: String(value?.state || "UNKNOWN")',
        'priority: String(value?.priority || "MEDIUM")',
        "nextRunAt: value?.nextRunAt || null",
        "runId: value?.runId || null",
        'message: String(value?.message || "")',
    ):
        assert fragment in TASKS

    assert "Stowling.scheduleStateLabel(schedule.state)" in TASKS
    assert "Stowling.formatDate(schedule.nextRunAt)" in TASKS
    assert "Stowling.priorityLabel(schedule.priority)" in TASKS
    assert "schedule?.message" in TASKS
    assert "function scheduleStateBadge(schedule)" in TASKS
    assert "function scheduleCountdown(value)" in TASKS
    assert "`倒计时：${scheduleCountdown(schedule.nextRunAt)}`" in TASKS
    assert '["UNSAFE", "未安全中断"]' in COMMON
    assert "function runSelectedTask()" in TASKS
    assert "function changeSchedule(kind, id, enabled)" in TASKS


def test_task_polling_refreshes_schedules_without_replacing_draft() -> None:
    definition_start = TASKS.index("async function loadDefinitions")
    definition_end = TASKS.index("function scheduleMap", definition_start)
    definition_refresh = TASKS[definition_start:definition_end]
    assert "async function loadDefinitions(background = false)" in TASKS
    assert "state.schedules = scheduleMap(payload?.scheduling);" in TASKS
    assert "state.draft" not in definition_refresh

    assert "async function refreshLiveState(reportErrors = false)" in TASKS
    assert "loadDefinitions(true)," in TASKS
    assert "loadRuntimeTasks()" in TASKS
    assert "void refreshLiveState(false);" in TASKS
    assert "void refreshLiveState(true);" in TASKS
    assert 'elements.triggerKind.addEventListener("change"' in TASKS
    assert "async function saveTask()" in TASKS


def test_task_page_uses_read_only_resource_facade() -> None:
    assert 'Stowling.request("/v1/config/task-resources")' in RESOURCES
    assert 'method: "PUT"' not in RESOURCES
    assert "document.querySelector" not in RESOURCES
    assert "window.StowlingResources = Object.freeze({" in RESOURCES

    assert "Resources.load()" in TASKS
    assert "Resources.getSnapshot()" in TASKS
    assert "Resources.onChanged(" in TASKS
    assert 'Stowling.request("/v1/config/task-resources"' not in TASKS
    assert 'Stowling.request("/v1/config/coordinates"' not in TASKS


def test_task_page_removes_shared_editors_and_keeps_runtime_queue() -> None:
    html = PAGES["tasks.html"]
    removed_ids = (
        "pickup-task-editor",
        "pickup-dimension",
        "pickup-binding-list",
        "pickup-cache-options",
        "pickup-completion-mode",
        "pickup-region-list",
        "cache-list",
        "add-pickup-region-button",
        "add-cache-button",
        "save-pickup-resources-button",
        "pickup-resource-errors",
        "base-coordinate-list",
        "save-base-coordinates-button",
        "discard-base-coordinates-button",
        "base-coordinate-errors",
    )
    for element_id in removed_ids:
        assert f'id="{element_id}"' not in html
    assert "仓库基础坐标" not in html
    assert "共享资源接口" not in html
    assert "仓库同步" not in html
    assert 'id="runtime-task-list"' in html
    assert 'id="refresh-runtime-button"' in html


def test_storage_is_inventory_only_and_never_starts_an_audit() -> None:
    html = PAGES["storage.html"]
    assert 'id="inventory-list"' in html
    assert 'id="refresh-storage-button"' in html
    assert 'id="audit-button"' not in html
    assert 'id="container-list"' not in html
    assert 'id="pickup-form"' not in html
    assert 'id="base-coordinate-form"' not in html
    assert "刷新库存" in html
    assert "不会发起盘点或改变游戏状态" in html
    assert 'id="inventory-filter-item"' in html
    assert 'id="inventory-filter-container"' in html

    assert 'Stowling.request("/v1/inventory")' in STORAGE
    assert STORAGE.count("Stowling.request(") == 1
    assert "const inventory = Array.isArray(payload)" in STORAGE
    assert "/v1/audits" not in STORAGE
    assert "/v1/containers" not in STORAGE
    assert "/v1/config/" not in STORAGE
    assert '"POST"' not in STORAGE
    assert "来源" in STORAGE
    assert "采集时间" in STORAGE
    assert "有效性" in STORAGE
    assert '["FRESH", { text: "有效", className: "badge" }]' in STORAGE
    assert '["STALE", { text: "已过期", className: "badge warning" }]' \
        in STORAGE
    assert "本次操作未发起盘点" in STORAGE
    assert "function filterSnapshot(snapshot)" in STORAGE
    assert "snapshot.containerId," in STORAGE
    assert "snapshot.sourceId," in STORAGE
    assert "matchingItems.length === 0" in STORAGE
    assert "本地筛选已更新；未发起新请求" in STORAGE
    assert "已有库存尚未写入" in STORAGE
    assert "等待盘点任务" not in STORAGE


def test_coordinates_remain_independent_and_use_current_references() -> None:
    html = PAGES["coordinates.html"]
    assert 'id="coordinate-form"' in html
    assert 'id="coordinate-list"' in html
    assert "function addPoint()" in COORDINATES
    assert "function removePoint(index)" in COORDINATES
    assert "function movePoint(index, offset)" in COORDINATES
    assert "dragHandle.draggable = true" in COORDINATES
    assert "function createPointCard(point, index)" in COORDINATES
    assert "state.draft.coordinatePoints.splice(index, 1)" in COORDINATES
    assert 'Stowling.request("/v1/config/coordinate-points")' in COORDINATES
    assert 'Stowling.request("/v1/config/task-resources")' in COORDINATES
    assert 'Stowling.request("/v1/task-definitions")' in COORDINATES
    assert "/v1/config/pickup" not in COORDINATES
    assert "/v1/config/automation-workflows" not in COORDINATES


def test_dashboard_has_no_pause_or_resume_controls() -> None:
    html = PAGES["index.html"]
    assert 'id="pause-button"' not in html
    assert 'id="resume-button"' not in html
    assert 'id="status-paused"' not in html
    assert "/v1/pause" not in DASHBOARD
    assert "/v1/resume" not in DASHBOARD
    assert "elements.pause" not in DASHBOARD
    assert "elements.resume" not in DASHBOARD
    assert 'Stowling.request("/v1/task-definitions")' in DASHBOARD
    assert 'id="cancel-task-button"' in html
    assert "function renderTaskOverview(tasks)" in DASHBOARD
    assert "每 5 秒自动同步机器人状态" in html
    assert "}, 5000);" in DASHBOARD


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


def test_javascript_functions_have_jsdoc_type_annotations() -> None:
    pattern = re.compile(
        r"(?m)^(?P<indent>[ ]*)(?:async\s+)?function\s+"
        r"(?P<name>[A-Za-z0-9_]+)\s*"
        r"\((?P<params>[^)]*)\)"
    )
    for source_name, source in (
        ("common.js", COMMON),
        ("coordinates.js", COORDINATES),
        ("tasks.js", TASKS),
        ("task-resources.js", RESOURCES),
        ("storage.js", STORAGE),
        ("dashboard.js", DASHBOARD),
    ):
        for match in pattern.finditer(source):
            prefix = source[:match.start()].rstrip()
            assert prefix.endswith("*/"), f"{source_name}:{match.group('name')}"
            block_start = prefix.rfind("/**")
            assert block_start >= 0, f"{source_name}:{match.group('name')}"
            block = prefix[block_start:]
            assert "@returns" in block, f"{source_name}:{match.group('name')}"
            params = [
                value for value in match.group("params").split(",")
                if value.strip()
            ]
            if params:
                assert "@param" in block, f"{source_name}:{match.group('name')}"


def test_layout_is_responsive_and_supports_dark_mode() -> None:
    assert ".page-workspace" in STYLES
    assert ".builder-grid" in STYLES
    assert ".module-catalog" in STYLES
    assert ".task-definition-list" in STYLES
    assert "@media (max-width: 760px)" in STYLES
    assert "@media (prefers-color-scheme: dark)" in STYLES


def test_all_web_sources_follow_line_length_rule() -> None:
    files = [path for path in WEB_ROOT.iterdir() if path.is_file()]
    violations = []
    for path in files:
        content = path.read_text(encoding="utf-8")
        assert "\t" not in content, path.name
        for number, line in enumerate(content.splitlines(), 1):
            if len(line) > 100:
                violations.append(f"{path.name}:{number}:{len(line)}")
    assert violations == []


def test_pages_do_not_retain_hash_anchor_navigation() -> None:
    assert re.search(r'href=["\']#', "".join(PAGES.values())) is None
    assert "data-workspace-target" not in COMMON
    assert "hashchange" not in SCRIPTS
