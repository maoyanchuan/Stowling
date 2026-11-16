from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "src" / "main" / "resources" / "stowling-web"


def read(name: str) -> str:
    return (WEB / name).read_text(encoding="utf-8")


def test_coordinate_editor_exposes_only_name_and_xyz() -> None:
    html = read("coordinates.html")
    javascript = read("coordinates.js")
    coordinate_points = html.split('id="coordinate-points"', 1)[1]
    coordinate_points = coordinate_points.split('id="regions"', 1)[0]
    assert "坐标管理" in html
    for field in ("名称", "X", "Y", "Z"):
        assert field in coordinate_points
    for removed in ("第二角点", "容器"):
        assert removed not in coordinate_points
    assert "new-coordinate-use-dimension" in coordinate_points
    assert "new-coordinate-use-radius" in coordinate_points
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
    assert "resource-tab-panel" in html
    assert 'id="new-coordinate-form"' in html
    assert 'id="new-region-form"' in html
    assert 'class="coordinate-create-form" hidden' in html
    assert 'id="cancel-coordinate-button"' in html
    assert 'id="cancel-region-button"' in html
    assert "hideCreateForms" in javascript
    assert ".coordinate-create-fields" in styles
    assert ".region-corner-group" in styles
    assert ".resource-tab-panel" in styles


def test_coordinate_cards_collapse_to_the_saved_name() -> None:
    javascript = read("coordinates.js")
    styles = read("styles.css")

    assert 'Stowling.element(\n            "details"' in javascript
    assert 'Stowling.element(\n            "summary"' in javascript
    assert ".coordinate-point-editor[open]" in styles
    assert ".coordinate-point-summary" in styles


def test_task_model_preserves_recursive_steps_and_string_parameters() -> None:
    model = read("v2-task-model.js")
    editor = read("v2-task-editor.js")

    assert "function normalizeStep(value)" in model
    assert "normalizeStepList(value?.steps)" in model
    assert "normalizeStepList(value?.otherwise)" in model
    assert "parameters: normalizeParameters(step?.parameters)" in model
    assert "steps: Array.isArray(step?.steps)" in model
    assert "otherwise: Array.isArray(step?.otherwise)" in model
    assert "parameters[key] = String(entry)" in model
    assert "parameters.maxIterations = String(DEFAULT_REPEAT_ITERATIONS)" in model
    assert "maxIterations: String(DEFAULT_REPEAT_ITERATIONS)" in model
    assert 'resetCacheHasMore: "true"' in model
    assert "step.parameters[key] = String(value)" in editor
    assert 'step.parameters.negate = "true"' in editor
    assert 'module.level === "BASIC"' in model
    assert "function renderStepList(" in editor
    assert "function branchEditor(" in editor
    assert "step[branchKey] = []" in editor
    assert "step.otherwise || []" in read("tasks.js")


def test_task_templates_use_recursive_basic_actions() -> None:
    model = read("v2-task-model.js")
    tasks = read("tasks.js")
    html = read("tasks.html")

    for module_id in (
        "COLLECT_DROPPED_ITEMS",
        "SCAN_CONTAINERS_IN_REGION",
        "RESOLVE_CONTAINER_CATEGORIES",
        "SELECT_CONTAINER",
        "WITHDRAW_ITEMS",
        "SELECT_STORAGE_TARGET",
        "DEPOSIT_ITEMS_TO_CONTAINERS",
        "WITHDRAW_FOOD",
        "EAT_FOOD",
        "SCAN_CATEGORY_MARKERS",
        "SELECT_MARKER_CONTAINER",
        "READ_CONTAINER",
        "SAVE_CATEGORY_MARKER_CACHE",
        "ASSERT_NO_MANAGED_ITEMS",
    ):
        assert module_id in model
    assert "function collectTask(" in model
    assert "function sortTask(" in model
    assert "function eatTask(" in model
    assert "function markerTask(" in model
    assert "function interruptionTask(" in model
    assert "composedOf" not in model
    assert "composedOf" not in tasks
    assert "PRESET" not in model
    assert "INTERNAL" not in model
    assert 'repeatStep("FOOD_BELOW_THRESHOLD"' in model
    assert 'repeatStep("HAS_MARKER_CONTAINERS"' in model
    assert 'itemSource: "managed"' in model
    assert "Model.collectTask(state.modules)" in tasks
    assert "Model.sortTask(state.modules)" in tasks
    assert "Model.eatTask(" in tasks
    collect = model.split("function collectTask(", 1)[1]
    collect = collect.split("function cacheTask(", 1)[0]
    assert "CREATE_TASK" not in collect
    assert 'id="new-marker-task-button"' in html
    assert 'id="new-interruption-task-button"' in html


def test_sort_task_navigates_to_cache_inside_each_cache_iteration() -> None:
    model = read("v2-task-model.js")
    sort = model.split("function sortTask(", 1)[1].split("function eatTask(", 1)[0]

    assert (
        '''repeatStep("CACHE_HAS_MORE", [
                actionStep("MOVE_TO_COORDINATE", {
                    targetRef: "output:container"
                }),
                withdraw,
                deposit
            ])'''
    ) in sort
    assert (
        '''sortContainers,
            actionStep("ASSERT_NO_MANAGED_ITEMS", {})'''
    ) in sort
    cache_start = sort.index('repeatStep("CACHE_HAS_MORE"')
    assert sort.index(
        'actionStep("MOVE_TO_COORDINATE"',
        cache_start
    ) < sort.index("withdraw,", cache_start)


def test_task_editor_supports_recursive_conditions_loops_and_task_creation() -> None:
    html = read("tasks.html")
    editor = read("v2-task-editor.js")
    model = read("v2-task-model.js")

    for identifier in (
        "add-if-step-button",
        "add-repeat-step-button",
        "add-create-task-step",
        "task-interruption-mode",
        "task-interruption-task-ref",
        "task-default-interruption",
        "task-requeue-after-interruption",
    ):
        assert f'id="{identifier}"' in html
    assert '"ACTION", "IF", "REPEAT", "CREATE_TASK"' in model
    assert '"ALWAYS"' in model
    assert '"FOOD_BELOW_THRESHOLD"' in model
    assert '"HAS_CONTAINERS"' in model
    assert '"HAS_CARRIED_ITEMS"' in model
    assert '"HAS_MANAGED_ITEMS"' in model
    assert '"CACHE_HAS_MORE"' in model
    assert '"HAS_MARKER_CONTAINERS"' in model
    assert "function createStepCard(" in editor
    assert "function conditionSelect(" in editor
    assert "function createTaskPrioritySelect(" in editor
    assert 'branchEditor(context, step, "steps", "满足时", path)' in editor
    assert 'branchEditor(context, step, "otherwise", "否则", path)' in editor
    assert "新任务会独立按所选优先级入队" in editor


def test_task_references_are_unified_and_show_resource_types() -> None:
    model = read("v2-task-model.js")
    editor = read("v2-task-editor.js")
    tasks = read("tasks.js")

    for value in (
        "coordinate:",
        "region:",
        "category-zone:",
        "task:",
        "output:container",
        "output:storage",
        "output:marker",
    ):
        assert value in model
    assert "function coordinateRef(" in model
    assert "function regionRef(" in model
    assert "function categoryZoneRef(" in model
    assert "function taskRef(" in model
    assert "function referenceSelect(" in editor
    assert 'parameter.kind === "resource-reference"' in editor
    assert 'parameter.kind === "coordinate-point"' in editor
    assert 'parameter.kind === "coordinate-reference"' in editor
    assert 'parameter.kind === "region-point"' in editor
    assert 'parameter.kind === "region-reference"' in editor
    assert 'parameter.kind === "category-zone-reference"' in editor
    assert 'parameter.kind === "container-reference"' in editor
    assert 'select.append(new Option("请选择已保存资源或前序选择结果", ""))' in editor
    assert 'label: "坐标"' in editor or '"coordinate": "坐标"' in editor
    assert '"category-zone": "分类区"' in editor
    assert 'task: "任务"' in editor
    assert 'output: "前序步骤结果"' in editor
    assert "normalizeLegacyReference" in editor
    assert "validateReference(parameter, value, step.moduleId)" in tasks


def test_task_interruption_contract_and_default_selection() -> None:
    html = read("tasks.html")
    model = read("v2-task-model.js")
    tasks = read("tasks.js")

    assert 'id="task-interruption-mode"' in html
    assert 'id="task-interruption-task-ref"' in html
    assert 'id="task-default-interruption"' in html
    assert 'id="task-requeue-after-interruption"' in html
    assert '"DEFAULT"' in model
    assert '"CUSTOM"' in model
    assert '"NONE"' in model
    assert 'interruptionMode: isDefault ? "NONE" : mode' in model
    assert 'interruptionTaskRef: mode === "CUSTOM"' in model
    assert "defaultInterruptionFlow" in model
    assert "requeueAfterInterruption" in model
    assert "function validateInterruption(" in tasks
    assert "已有默认中断收尾任务" in tasks
    assert "中断收尾不能引用当前任务自身" in tasks
    assert "默认中断收尾任务不能自动重新排队" in tasks
    assert "默认中断收尾任务不能创建其他任务" in tasks
    assert "function containsCreateTask(" in tasks
    assert "state.draft.defaultInterruptionFlow" in tasks
    assert 'state.draft.interruptionMode = "NONE"' in tasks


def test_task_queue_creation_priority_and_interruption_run_states() -> None:
    html = read("tasks.html")
    model = read("v2-task-model.js")
    tasks = read("tasks.js")
    editor = read("v2-task-editor.js")

    assert "function priorityRank(" in model
    assert "Model.priorityRank(left.priority)" in tasks
    assert "MAX_ROOT_STEPS = 128" in model
    assert "MAX_STEP_DEPTH = 16" in model
    assert "MAX_TOTAL_STEPS = 512" in model
    assert "MAX_REPEAT_ITERATIONS = 1_000_000" in model
    assert "function taskStepStats(" in tasks
    assert "至少需要一个子步骤" in tasks
    assert "循环步骤不能配置否则分支" in tasks
    assert "不能包含子步骤" in tasks
    assert "CREATE_TASK" in model
    assert '沿用目标任务优先级' in editor
    assert 'taskRef: ""' in model
    assert "未指定优先级时沿用目标任务配置" in editor
    assert "if (priority && !Model.PRIORITIES.includes(priority))" in tasks
    assert "delete payload.parameters.priority" in model
    assert "delete step.parameters.priority" in editor
    assert "任务按优先级进入统一队列" in html
    assert 'INTERRUPTING: "正在执行中断收尾"' in tasks
    assert 'INTERRUPTION_FAILED: "中断收尾失败，队列暂停"' in tasks
    assert "function runStateMessage(" in tasks
    assert "正在执行中断收尾，队列暂停" in tasks
    assert "请重试，或先确认导航停止" in tasks
    assert '"INTERRUPTING",\n            "INTERRUPTION_FAILED"' in tasks
    assert "重试收尾" in tasks
    assert "尝试跳过收尾" in tasks
    assert "/retry-interruption" in tasks
    assert "/skip-interruption" in tasks
    assert "任务管理物品已处理完毕" in tasks
    assert "后端才允许跳过中断收尾" in tasks
    assert "确认风险后跳过" not in tasks


def test_task_target_outputs_match_runtime_contract() -> None:
    editor = read("v2-task-editor.js")
    tasks = read("tasks.js")

    outputs = editor.split("function outputTypesFor(", 1)[1]
    outputs = outputs.split("function resourceTypesFromParameter(", 1)[0]
    deposit = outputs.split("DEPOSIT_ITEMS_TO_CONTAINERS: [", 1)[1]
    deposit = deposit.split("],", 1)[0]
    assert 'output-container' in deposit
    assert 'output-storage' in deposit
    assert 'output-marker' in deposit
    assert 'READ_CONTAINER: ["output-marker"]' in outputs
    target_types = editor.split("function targetReferenceTypesFor(", 1)[1]
    target_types = target_types.split("function outputTypesFor(", 1)[0]
    assert 'moduleId === "MOVE_TO_COORDINATE"' in target_types
    assert 'return ["coordinate", ...OUTPUT_TYPES]' in target_types
    assert 'moduleId === "READ_CONTAINER"' in target_types
    assert 'return ["output-marker"]' in target_types
    assert "DEPOSIT_ITEMS_TO_CONTAINERS: Model.OUTPUT_REFERENCES" in tasks
    assert 'READ_CONTAINER: ["output:marker"]' in tasks


def test_task_editor_keeps_revision_save_and_auto_eat_flow() -> None:
    html = read("tasks.html")
    tasks = read("tasks.js")

    assert 'id="auto-eat-preset-form"' in html
    assert 'id="auto-eat-coordinate"' in html
    assert 'id="auto-eat-food"' in html
    assert 'id="auto-eat-threshold"' in html
    assert "function createAutoEatPreset()" in tasks
    assert "function syncAutoEatDraft()" in tasks
    assert 'step.parameters.condition === "FOOD_BELOW_THRESHOLD"' in tasks
    assert "state.draft = Model.eatTask(" in tasks
    assert "revision: state.revision" in tasks
    assert "state.revision = String(response?.revision || state.revision)" in tasks
    assert 'Stowling.request("/v1/tasks"' in tasks
    assert '/runs`' in tasks
    assert "/cancel`" in tasks
    assert "Authorization" not in tasks


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
    assert '"RUNNING",\n            "WAITING",' in tasks
    assert '"INTERRUPTING",\n            "INTERRUPTION_FAILED"' in tasks
    assert "isActiveRun(run)" in tasks
    assert 'run.state === "INTERRUPTION_FAILED"' in tasks
    failed_actions = tasks.rsplit(
        'if (run.state === "INTERRUPTION_FAILED")',
        1
    )[1].split("else if (isActiveRun(run))", 1)[0]
    assert "retryInterruption(run.runId)" in failed_actions
    assert "skipInterruption(run.runId)" in failed_actions
    assert "取消任务" not in failed_actions
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


def test_task_page_supports_task_bound_triggers_inside_the_editor() -> None:
    html = read("tasks.html")
    model = read("v2-task-model.js")
    trigger_model = read("v2-trigger-model.js")
    tasks = read("tasks.js")
    styles = read("styles.css")

    assert '<script src="/v2-trigger-model.js" defer></script>' in html
    assert 'id="task-trigger-settings"' in html
    assert 'id="new-trigger-button"' in html
    assert 'id="trigger-bound-task"' in html
    assert 'id="trigger-target-task"' not in html
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
    assert "function currentTaskTriggers()" in tasks
    assert "trigger.targetTaskId === state.selectedId" in tasks
    assert "function renderTriggerList" in tasks
    assert "function renderTriggerEditor" in tasks
    assert "function renderTriggerStatuses" in tasks
    assert ".trigger-workbench" in styles
    assert "按设定优先级把该任务加入现有队列" in html


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
    for removed in ("任务 ID", "步骤 ID"):
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


def test_task_page_separates_orchestration_and_monitoring() -> None:
    html = read("tasks.html")
    orchestration = html.split('id="task-panel-orchestration"', 1)[1]
    orchestration = orchestration.split('id="task-panel-monitor"', 1)[0]
    monitor = html.split('id="task-panel-monitor"', 1)[1]

    assert html.count('data-task-tab="') == 2
    assert html.count('data-task-panel="') == 2
    for tab in ("orchestration", "monitor"):
        assert f'data-task-tab="{tab}"' in html
        assert f'data-task-panel="{tab}"' in html
    assert 'data-task-tab="triggers"' not in html
    assert 'data-task-panel="triggers"' not in html
    assert 'id="task-panel-orchestration"' in html
    assert 'aria-selected="true"' in html.split(
        'data-task-tab="orchestration"', 1
    )[0]
    assert 'data-task-panel="monitor" hidden' in html

    for marker in (
        "task-list",
        "task-editor-panel",
        "task-module-panel",
        "task-trigger-settings",
        "trigger-form",
    ):
        assert marker in orchestration
    assert 'id="task-run-panel"' not in orchestration
    assert 'id="trigger-status-list"' not in orchestration
    assert 'id="task-run-panel"' in monitor
    for marker in ("trigger-status-list", "task-queue-list", "runtime-list"):
        assert marker in monitor


def test_task_tabs_use_hash_and_polling_never_changes_partitions() -> None:
    javascript = read("tasks.js")

    assert 'orchestration: "#orchestration"' in javascript
    assert 'triggers: "#triggers"' not in javascript
    assert 'monitor: "#monitor"' in javascript
    assert 'if (hash === "triggers")' in javascript
    assert "function hasLegacyTriggerHash()" in javascript
    assert "panel.hidden = panel.dataset.taskPanel !== next" in javascript
    assert 'window.history.replaceState(null, "", hash)' in javascript
    assert 'window.addEventListener("hashchange"' in javascript
    assert "state.preserveTabOnSelect = true" in javascript
    assert "openTriggersAfterTaskReady: false" in javascript
    assert "state.openTriggersAfterTaskReady = true" in javascript
    route = javascript.split("function applyTaskHashRoute()", 1)[1]
    route = route.split("function handleTaskTabKeydown", 1)[0]
    assert "if (state.openTriggersAfterTaskReady)" in route
    assert "openCurrentTaskTriggers()" in route
    editor = javascript.split("function renderEditor()", 1)[1]
    editor = editor.split("function renderInterruptionControls", 1)[0]
    assert "if (state.openTriggersAfterTaskReady)" in editor
    assert "openCurrentTaskTriggers()" in editor
    opener = javascript.split("function openCurrentTaskTriggers()", 1)[1]
    opener = opener.split("function applyTaskHashRoute()", 1)[0]
    assert "state.openTriggersAfterTaskReady = false" in opener

    selection = javascript.split("function selectTask(task, revealInList = false)", 1)[1]
    selection = selection.split("function renderRunControls", 1)[0]
    assert 'showTaskTab("orchestration", true)' in selection
    assert 'showTaskTab("triggers", true)' not in javascript
    assert "openCurrentTaskTriggers()" in javascript
    assert 'showTaskTab("monitor", true)' in javascript

    polling = javascript.split("async function loadRuns()", 1)[1]
    polling = polling.split("function renderTriggerStatuses()", 1)[0]
    assert "showTaskTab(" not in polling
    assert "renderTriggerStatuses()" in polling
    assert "renderRuntime()" in polling
    assert "renderQueue()" in polling

    saving = javascript.split("async function saveTask()", 1)[1]
    saving = saving.split("async function deleteTask()", 1)[0]
    assert "showTaskTab(" not in saving
    assert "renderEditor()" in saving
    assert "renderTaskList()" in saving


def test_task_tabs_support_roving_keyboard_navigation() -> None:
    javascript = read("tasks.js")

    assert "button.tabIndex = selected ? 0 : -1" in javascript
    assert "function handleTaskTabKeydown(event)" in javascript
    assert 'event.key === "ArrowRight"' in javascript
    assert 'event.key === "ArrowLeft"' in javascript
    assert 'event.key === "Home"' in javascript
    assert 'event.key === "End"' in javascript
    assert "event.preventDefault()" in javascript
    assert "showTaskTab(target.dataset.taskTab, true)" in javascript
    assert "target.focus()" in javascript
    assert 'button.addEventListener("keydown", handleTaskTabKeydown)' in javascript


def test_task_list_filters_interruption_roles_without_a_saved_category() -> None:
    html = read("tasks.html")
    javascript = read("tasks.js")

    for value in ("ALL", "NORMAL", "INTERRUPTION"):
        assert f'data-task-filter="{value}"' in html
    for label in ("全部", "普通任务", "中断收尾"):
        assert label in html
    assert 'taskFilter: "ALL"' in javascript
    assert "function isInterruptionTask(task)" in javascript
    assert "task.defaultInterruptionFlow" in javascript
    assert "candidate.interruptionTaskRef" in javascript
    assert "Model.parseReference(" in javascript
    assert "candidate.id === task.id" in javascript
    assert "function matchesTaskFilter(task)" in javascript
    assert "state.taskFilter = button.dataset.taskFilter" in javascript
    assert "task.category" not in javascript


def test_task_templates_are_folded_into_the_create_menu() -> None:
    html = read("tasks.html")

    menu = html.split('<details class="task-template-menu">', 1)[1]
    menu = menu.split("</details>", 1)[0]
    for button_id in (
        "new-cache-task-button",
        "new-sort-task-button",
        "new-marker-task-button",
        "new-interruption-task-button",
        "new-auto-eat-task-button",
    ):
        assert f'id="{button_id}"' in menu
    assert '<details class="task-settings">' in html
    module_heading = html.split('<details id="task-module-panel"', 1)[1].split(">", 1)[0]
    assert "open" not in module_heading
    assert "从一个任务开始" in html


def test_shared_tabs_and_resource_partitions_hide_inactive_content() -> None:
    styles = read("styles.css")

    assert ".page-subnav {" in styles
    assert '.page-subnav-tab[aria-selected="true"]' in styles
    assert ".page-subnav-tab:focus-visible" in styles
    assert (
        ".page-tab-panel[hidden],\n"
        ".resource-tab-panel[hidden] {\n"
        "    display: none !important;\n"
        "}"
    ) in styles
    assert (
        ".resource-tab-panel {\n"
        "    display: grid;\n"
        "    grid-template-columns: minmax(0, 1fr);"
    ) in styles


def test_workflow_tree_styles_support_nested_details_and_mobile_indent() -> None:
    styles = read("styles.css")

    for selector in (
        ".workflow-tree",
        ".workflow-node-details",
        ".workflow-node-tools",
        ".workflow-node-summary",
        ".workflow-node-body",
        ".workflow-node-config",
        ".workflow-config-meta",
        ".workflow-action-editor",
        ".workflow-node-help",
        ".workflow-branch-heading",
        ".workflow-step-list",
        ".workflow-branch-empty",
        ".workflow-node-invalid",
        ".workflow-node-unsupported",
        ".workflow-depth-4",
    ):
        assert selector in styles
    assert ".workflow-node-summary:focus-visible" in styles
    assert (
        ".workflow-node-tools {\n"
        "    position: absolute;"
    ) in styles
    assert 'workflow-node[data-depth="4"]' in styles
    mobile = styles.split("@media (max-width: 600px) {", 1)[1]
    assert ".workflow-branch," in mobile
    assert "--workflow-branch-offset: 0;" in mobile


def test_task_selection_aligns_saved_run_target_without_using_the_draft() -> None:
    javascript = read("tasks.js")
    selection = javascript.split("function selectTask(task, revealInList = false)", 1)[1]
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
