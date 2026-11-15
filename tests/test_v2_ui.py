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
    assert "area-reference" in editor
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
    assert "visibleModules" in model


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
    assert "deprecated" in catalog
    assert "state.modules.length" in tasks


def test_task_page_offers_cache_and_sorting_templates() -> None:
    html = read("tasks.html")
    model = read("v2-task-model.js")
    tasks = read("tasks.js")

    assert 'id="new-cache-task-button"' in html
    assert 'id="new-sort-task-button"' in html
    assert "CACHE_TASK_MODULES" in model
    assert "SORT_TASK_MODULES" in model
    assert '"COLLECT_DROPPED_ITEMS"' in model
    assert '"SORT_CACHE_ITEMS_BY_CATEGORY_ZONE"' in model
    assert "task.repeatWhenIdle = false" in model
    assert "Model.cacheTask(state.modules)" in tasks
    assert "Model.sortTask(state.modules)" in tasks


def test_task_modules_hide_deprecated_entries_from_new_tasks() -> None:
    model = read("v2-task-model.js")
    editor = read("v2-task-editor.js")
    tasks = read("tasks.js")

    assert "module?.deprecated" in model
    assert "module.deprecated" in editor
    assert "旧任务兼容模块" in editor
    assert "Model.visibleModules(state.modules)" in tasks


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


def test_task_priority_queue_and_background_repeat_controls() -> None:
    html = read("tasks.html")
    model = read("v2-task-model.js")
    tasks = read("tasks.js")

    assert 'id="task-priority"' in html
    assert 'id="task-repeat"' in html
    assert 'id="run-priority-select"' in html
    assert 'id="task-queue-list"' in html
    assert '"BACKGROUND"' in model
    assert '"NORMAL"' in model
    assert '"URGENT"' in model
    assert "repeatWhenIdle" in model
    assert "priority: task.priority" in model
    assert "priorityOverride" not in tasks
    assert 'priority: elements.runPriority.value' in tasks
    assert "state.queue" in tasks
    assert "renderQueue()" in tasks
    assert "被中断任务不会续接" in html


def test_task_page_supports_independent_task_triggers() -> None:
    html = read("tasks.html")
    model = read("v2-task-model.js")
    trigger_model = read("v2-trigger-model.js")
    tasks = read("tasks.js")
    styles = read("styles.css")

    assert '<script src="/v2-trigger-model.js" defer></script>' in html
    assert 'id="new-trigger-button"' in html
    assert 'id="trigger-target-task"' in html
    assert 'id="trigger-priority"' in html
    assert 'id="trigger-coordinate"' in html
    assert 'id="trigger-food-threshold"' in html
    assert 'id="trigger-cache-region"' in html
    assert 'id="trigger-list"' in html
    assert 'id="trigger-status-list"' in html
    assert 'id="task-watch-enabled"' not in html
    assert "watchTask" not in model
    assert "trigger: task.trigger" not in model
    assert "FOOD_LEVEL_AT_OR_BELOW" in trigger_model
    assert "DROPPED_ITEMS" in trigger_model
    assert "CACHE_UPDATED" in trigger_model
    assert "cacheRegionId" in trigger_model
    assert "触发防抖（秒）" in tasks
    assert "payloadTrigger" in trigger_model
    assert "state.triggerStatuses" in tasks
    assert '"/v1/task-triggers"' in tasks
    assert "function renderTriggerList" in tasks
    assert "function renderTriggerEditor" in tasks
    assert "function renderTriggerStatuses" in tasks
    assert ".trigger-workbench" in styles
    assert "按指定优先级加入现有队列" in html


def test_dashboard_has_an_independent_trigger_status_panel() -> None:
    html = read("index.html")
    dashboard = read("dashboard.js")

    assert 'id="dashboard-trigger-status"' in html
    assert "触发器状态" in html
    assert "renderTriggerStatuses" in dashboard
    assert "runs.triggers" in dashboard


def test_task_page_uses_a_wide_editor_with_a_compact_sidebar() -> None:
    styles = read("styles.css")

    assert 'body[data-page="tasks"] .workspace' in styles
    assert ".task-workbench" in styles
    assert "grid-template-columns: 280px minmax(0, 1fr)" in styles
    assert 'body[data-page="tasks"] .task-sidebar' in styles
    assert ".task-main" in styles
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
    ):
        assert removed not in retained


def test_pages_share_console_heading_with_contextual_subtitles() -> None:
    pages = (
        ("index.html", "状态概览"),
        ("coordinates.html", "坐标管理"),
        ("tasks.html", "任务管理"),
        ("category-zones.html", "分类区管理"),
        ("storage.html", "库存查询"),
    )
    for name, label in pages:
        html = read(name)
        header = html.split('<header class="topbar">', 1)[1].split("</header>", 1)[0]
        assert "<h1>Stowling 控制台</h1>" in header
        assert f"<p>{label} · " in header
        assert f"<title>{label} · Stowling</title>" in html


def test_coordinate_navigation_groups_spatial_resources() -> None:
    common = read("common.js")
    coordinates = read("coordinates.html")
    styles = read("styles.css")

    assert 'label: "坐标"' in common
    assert 'label: "坐标点"' in common
    assert 'href: "/coordinates.html#coordinate-points"' in common
    assert 'label: "区域"' in common
    assert 'href: "/coordinates.html#regions"' in common
    assert 'label: "分类区"' in common
    assert 'href: "/category-zones.html"' in common
    assert 'className = "workspace-nav-group"' in common
    assert 'className = "workspace-subnav"' in common
    assert 'window.addEventListener("hashchange", mountNavigation);' in common
    assert 'id="coordinate-points"' in coordinates
    assert 'id="regions"' in coordinates
    assert ".workspace-nav-group" in styles
    assert ".workspace-subnav" in styles


def test_header_and_content_share_responsive_page_width() -> None:
    styles = read("styles.css")

    assert "--page-max-width: 1420px;" in styles
    assert "--page-gutter: 1rem;" in styles
    assert (
        ".topbar,\nmain {\n"
        "    width: min(var(--page-max-width), calc(100% - 2 * var(--page-gutter)));\n"
        "    margin: 0 auto;\n}"
    ) in styles
    assert (
        "@media (max-width: 760px) {\n"
        "    :root {\n"
        "        --page-gutter: 0.5rem;\n"
        "    }"
    ) in styles
    for page in ("dashboard", "coordinates", "tasks", "category-zones", "storage"):
        assert f'body[data-page="{page}"] main' not in styles
        assert f'main[data-page="{page}"]' not in styles


def test_coordinate_editors_group_xyz_and_separate_optional_settings() -> None:
    javascript = read("coordinates.js")
    html = read("coordinates.html")

    assert 'axisGroup("位置", x, y, z)' in javascript
    assert 'axisGroup("角点 1", firstX, firstY, firstZ)' in javascript
    assert 'axisGroup("角点 2", secondX, secondY, secondZ)' in javascript
    assert 'Stowling.element("details", "resource-options")' in javascript
    assert "options.open = useDimension.checked || useRadius.checked" in javascript
    assert "resourceIdentity(" in javascript
    assert 'id="coordinate-list-count"' in html
    assert 'id="region-list-count"' in html


def test_coordinate_search_filters_without_discarding_unsaved_fields() -> None:
    javascript = read("coordinates.js")
    html = read("coordinates.html")
    search = javascript.split('elements.search.addEventListener("input"', 1)[1]
    search = search.split("});", 1)[0]

    assert 'id="resource-search"' in html
    assert "filterResourceList(elements.list)" in search
    assert "filterResourceList(elements.regionList)" in search
    assert "replaceChildren" not in search
    assert "renderCoordinates" not in search
    assert "renderRegions" not in search
    assert "card.hidden = !card.dataset.searchText" in javascript


def test_category_bindings_collapse_ranges_and_keep_rules_on_demand() -> None:
    javascript = read("category-zones.js")

    assert 'Stowling.element("details", "category-binding-card")' in javascript
    assert "function appendBinding(host, binding, expanded = false)" in javascript
    assert "card.open = expanded" in javascript
    assert 'Stowling.element("details", "category-binding-settings")' in javascript
    assert "ranges.append(marker.root, storage.root)" in javascript
    assert "settingsBody.append(recognition, overrideHeading, overrides, overrideActions)" in (
        javascript
    )
    assert "containerOverrides: overrideReaders.map((reader) => reader.read())" in javascript
    assert "bindingDescription(binding)" in javascript


def test_task_workbench_keeps_execution_with_editor_and_folds_module_reference() -> None:
    html = read("tasks.html")
    workbench = html.split('<div class="task-workbench">', 1)[1]
    workbench = workbench.split('<details id="task-module-panel"', 1)[0]

    for name in ("task-sidebar", "task-main", "task-monitor", "task-editor-panel"):
        assert name in workbench
    assert 'id="task-run-panel"' in workbench
    assert 'id="task-search"' in workbench
    assert '<details class="task-settings">' in html
    module_heading = html.split('<details id="task-module-panel"', 1)[1].split(">", 1)[0]
    assert "open" not in module_heading
    assert "从一个任务开始" in html


def test_task_selection_aligns_saved_run_target_without_using_the_draft() -> None:
    javascript = read("tasks.js")
    selection = javascript.split("function selectTask(task)", 1)[1]
    selection = selection.split("function renderRunControls", 1)[0]

    assert "state.runTaskId = task.id" in selection
    assert "renderRunControls()" in selection
    assert "!state.draft && state.tasks.length > 0" in javascript
    assert "task.name.toLocaleLowerCase().includes(state.query)" in javascript
    assert "task.steps.length" in javascript
    assert 'button.setAttribute("aria-pressed"' in javascript


def test_lightweight_animations_respect_reduced_motion_and_hidden_content() -> None:
    styles = read("styles.css")
    javascript = read("tasks.js")

    assert "@keyframes ui-reveal" in styles
    assert "::details-content" in styles
    assert "@media (prefers-reduced-motion: reduce)" in styles
    assert "animation: none !important" in styles
    assert 'window.matchMedia("(prefers-reduced-motion: reduce)").matches' in javascript
    assert "elements.form.animate(" in javascript
    assert ".task-welcome[hidden]" in styles
    assert ".coordinate-point-editor[hidden]" in styles
    assert ".search-empty[hidden]" in styles
