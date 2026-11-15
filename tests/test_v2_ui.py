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
    for removed in ("第二角点", "容器"):
        assert removed not in html
    assert "new-coordinate-use-dimension" in html
    assert "new-coordinate-use-radius" in html
    assert "/v1/coordinates/${encodeURIComponent(coordinate.id)}" in javascript
    assert "coordinate.name" in javascript
    assert "coordinate.x" in javascript
    assert "coordinate.y" in javascript
    assert "coordinate.z" in javascript
    assert "dimension: values.useDimension.checked" in javascript
    assert "radius: values.useRadius.checked" in javascript


def test_region_editor_accepts_two_three_dimensional_corners() -> None:
    html = read("coordinates.html")
    javascript = read("coordinates.js")
    model = read("region-model.js")

    assert 'id="add-region-button"' in html
    assert 'id="new-region-form"' in html
    for field in (
        "new-region-first-x",
        "new-region-first-y",
        "new-region-first-z",
        "new-region-second-x",
        "new-region-second-y",
        "new-region-second-z",
    ):
        assert field in html
    assert "/v1/regions" in javascript
    assert "normalizeRegions" in model


def test_coordinate_inputs_hide_spinners_and_expand_pasted_triples() -> None:
    common = read("common.js")
    coordinates = read("coordinates.js")
    category_zones = read("category-zones.js")
    styles = read("styles.css")

    assert "function parseCoordinateTriple(value)" in common
    assert "function bindCoordinateInputs(...inputs)" in common
    assert "matches.length !== 3" in common
    assert "event.preventDefault()" in common
    assert 'input[type="number"]::-webkit-inner-spin-button' in styles
    assert "appearance: textfield" in styles
    assert "Stowling.bindCoordinateInputs(x, y, z)" in coordinates
    assert (
        "Stowling.bindCoordinateInputs(\n"
        "        elements.regionFirstX,\n"
        "        elements.regionFirstY,\n"
        "        elements.regionFirstZ\n"
        "    );"
    ) in coordinates
    assert "Stowling.bindCoordinateInputs(x, y, z)" in category_zones


def test_coordinate_page_uses_progressive_creation_layout() -> None:
    html = read("coordinates.html")
    javascript = read("coordinates.js")
    styles = read("styles.css")

    assert 'class="resource-create-grid"' in html
    assert 'class="resource-list-grid"' in html
    assert 'id="new-coordinate-form"' in html
    assert 'id="new-region-form"' in html
    assert 'class="coordinate-create-form" hidden' in html
    assert 'id="cancel-coordinate-button"' in html
    assert 'id="cancel-region-button"' in html
    assert "hideCreateForms" in javascript
    assert ".coordinate-create-fields" in styles
    assert ".region-corner-group" in styles
    assert ".resource-list-grid" in styles


def test_coordinate_cards_collapse_to_the_saved_name() -> None:
    javascript = read("coordinates.js")
    styles = read("styles.css")

    assert 'Stowling.element(\n            "details"' in javascript
    assert 'Stowling.element(\n            "summary"' in javascript
    assert ".coordinate-point-editor[open]" in styles
    assert ".coordinate-point-summary" in styles


def test_task_editor_uses_module_catalog_without_hardcoded_parameters() -> None:
    html = read("tasks.html")
    model = read("v2-task-model.js")
    editor = read("v2-task-editor.js")
    tasks = read("tasks.js")
    assert "模块步骤" in html
    assert "/v1/task-modules" in tasks
    assert "module.parameters" in editor
    assert "parameter.kind" in editor
    assert "基础模块" in editor
    assert "复合模块" in editor
    assert "coordinate-point" in editor
    assert "region-point" in editor
    assert "integer" in editor
    assert "select" in editor
    assert "task.steps.splice" in editor
    assert "index + offset" in editor
    assert "modules[0]" in model
    assert "MOVE_TO_COORDINATE" not in model
    assert "coordinateId" not in model
    assert "crypto.randomUUID" not in model
    assert "payloadStep" in model
    assert "defaultModule" in model
    assert "taskLevel" in model


def test_task_page_lists_available_modules_from_the_backend_catalog() -> None:
    html = read("tasks.html")
    tasks = read("tasks.js")

    assert 'id="task-module-panel"' in html
    assert 'id="task-module-catalog"' in html
    assert "renderModuleCatalog()" in tasks
    assert "state.modules" in tasks
    assert "module.description" in tasks
    assert "parameter.label" in tasks
    assert "基础任务" in tasks
    assert "复合任务" in tasks
    assert "module-group" in tasks


def test_task_editor_loads_regions_and_builds_contract_driven_controls() -> None:
    tasks = read("tasks.js")
    editor = read("v2-task-editor.js")

    assert 'Stowling.request("/v1/regions")' in tasks
    assert "state.regions" in tasks
    assert "state.regions," in tasks
    assert "referenceSelect(" in editor
    assert 'parameter.kind === "region-point"' in editor
    assert 'parameter.kind === "integer"' in editor
    assert 'parameter.kind === "select"' in editor
    assert 'input.type = "number"' in editor
    assert "parameter.options" in editor
    assert "parameter.min" in editor
    assert "parameter.max" in editor
    assert "defaultParameters(selected)" in editor


def test_task_editor_loads_category_zones_for_contract_controls() -> None:
    tasks = read("tasks.js")
    editor = read("v2-task-editor.js")

    assert 'Stowling.request("/v1/category-zones")' in tasks
    assert "state.categoryZones" in tasks
    assert 'kind === "category-zone-point"' in tasks
    assert 'parameter.kind === "category-zone-point"' in editor
    assert '"category-zone-point"' in editor


def test_task_validation_uses_module_parameter_contracts() -> None:
    tasks = read("tasks.js")

    assert "validateReference(parameter.kind, value)" in tasks
    assert 'kind === "coordinate-point"' in tasks
    assert 'kind === "region-point"' in tasks
    assert "validateInteger(parameter, value)" in tasks
    assert "selectOptions(parameter).includes(value)" in tasks
    assert "parameter.required" in tasks


def test_task_module_catalog_exposes_pickup_and_storage_modules() -> None:
    tasks = read("tasks.js")
    catalog = (
        ROOT
        / "src"
        / "main"
        / "java"
        / "dev"
        / "stowling"
        / "v2"
        / "module"
        / "TaskModuleCatalog.java"
    ).read_text(encoding="utf-8")
    assert "renderModuleCatalog()" in tasks
    for module_id in (
        "WAIT_FOR_DROPPED_ITEMS",
        "COLLECT_DROPPED_ITEMS",
        "COLLECT_DROPPED_ITEMS_IN_REGION",
        "SCAN_CONTAINERS_IN_REGION",
        "MATCH_CONTAINER_CATEGORIES",
        "SORT_CACHE_ITEMS",
        "SORT_CACHE_ITEMS_BY_CATEGORY_ZONE",
        "DEPOSIT_ITEMS_TO_CONTAINERS",
    ):
        assert module_id in catalog
        assert module_id not in tasks
    assert "state.modules.length" in tasks


def test_task_page_offers_cache_and_sorting_templates() -> None:
    html = read("tasks.html")
    model = read("v2-task-model.js")
    tasks = read("tasks.js")

    assert 'id="new-cache-task-button"' in html
    assert 'id="new-sort-task-button"' in html
    assert "CACHE_TASK_MODULES" in model
    assert "SORT_TASK_MODULES" in model
    assert '"COLLECT_DROPPED_ITEMS_IN_REGION"' in model
    assert '"SORT_CACHE_ITEMS_BY_CATEGORY_ZONE"' in model
    assert "Model.cacheTask(state.modules)" in tasks
    assert "Model.sortTask(state.modules)" in tasks


def test_coordinate_choices_only_show_the_saved_name() -> None:
    model = read("coordinate-model.js")

    assert 'return String(value?.name || "");' in model
    assert "value.x" not in model
    assert "value.y" not in model
    assert "value.z" not in model
    assert "rawRadius === null" in model


def test_task_ui_supports_save_run_refresh_and_cancel() -> None:
    html = read("tasks.html")
    tasks = read("tasks.js")
    for text in ("保存任务", "运行所选任务", "刷新状态", "取消任务"):
        assert text in html or text in tasks
    assert "/v1/tasks/${encodeURIComponent(state.runTaskId)}/runs" in tasks
    assert "/v1/task-runs" in tasks
    assert "/v1/task-runs/${encodeURIComponent(runId)}/cancel" in tasks
    assert 'run.state === "RUNNING"' in tasks
    assert 'run.state === "WAITING"' in tasks
    assert "isActiveRun(run)" in tasks
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


def test_task_page_uses_a_wide_editor_with_a_compact_sidebar() -> None:
    styles = read("styles.css")

    assert 'body[data-page="tasks"] .workspace' in styles
    assert "grid-template-columns: 280px minmax(0, 1fr)" in styles
    assert 'body[data-page="tasks"] .task-sidebar' in styles
    assert 'body[data-page="tasks"] .task-editor-panel' in styles
    assert "position: sticky" in styles


def test_dashboard_uses_new_resource_endpoints_only() -> None:
    dashboard = read("dashboard.js")
    assert "/v1/status" in dashboard
    assert "/v1/coordinates" in dashboard
    assert "/v1/tasks" in dashboard
    assert "/v1/task-runs" in dashboard
    for old in ("/v1/config/", "/v1/pickup", "/v1/containers"):
        assert old not in dashboard


def test_category_zone_page_uses_automatic_display_bindings() -> None:
    html = read("category-zones.html")
    javascript = read("category-zones.js")
    model = read("category-zone-model.js")

    assert "分类物识别区" in javascript
    assert "储存区" in javascript
    assert "自动读取展示框、展示架和摆放方块" in javascript
    assert "忽略支撑方块" in javascript
    assert "容器例外规则" in javascript
    assert "追加允许物品" in javascript
    assert "替换自动分类" in javascript
    assert "markerFirstX" in model
    assert "storageSecondZ" in model
    assert "ignoredMarkerBlockIds" in model
    assert "containerOverrides" in model
    assert "normalizeContainerOverride" in model
    assert 'Stowling.request("/v1/category-zones")' in javascript
    assert 'Stowling.request("/v1/category-rules")' in javascript
    assert 'id="new-category-zone-form"' in html
    assert 'id="add-new-binding-button"' in html


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
