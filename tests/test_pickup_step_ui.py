from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WEB_ROOT = ROOT / "src" / "main" / "resources" / "stowling-web"
TASKS = (WEB_ROOT / "tasks.js").read_text(encoding="utf-8")
TASKS_HTML = (WEB_ROOT / "tasks.html").read_text(encoding="utf-8")
STYLES = (WEB_ROOT / "styles.css").read_text(encoding="utf-8")


def task_function(name: str) -> str:
    start = TASKS.index(f"    function {name}(")
    end = TASKS.find("    /**", start)
    return TASKS[start:end] if end != -1 else TASKS[start:]


def test_new_pickup_task_is_one_pure_automation_step() -> None:
    creation = task_function("createNewDefinition")
    assert 'state.draft = defaultDefinition("AUTOMATION");' in creation
    assert 'defaultDefinition("PICKUP")' not in creation
    assert creation.count('type: "RUN_PICKUP"') == 1
    assert 'regionId: ""' in creation
    assert 'idleSeconds: "2"' in creation
    assert 'waitForItems: "false"' in creation
    assert "NAVIGATE" not in creation
    assert "WAIT" not in creation
    assert "新拾取任务" in creation
    assert "只预置一个纯区域拾取步骤" in TASKS_HTML


def test_task_page_removes_shared_resource_and_base_coordinate_editors() -> None:
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
        assert f'id="{element_id}"' not in TASKS_HTML
    assert "仓库基础坐标" not in TASKS_HTML
    assert "共享资源接口" not in TASKS_HTML
    assert 'id="runtime-task-list"' in TASKS_HTML
    assert 'id="refresh-runtime-button"' in TASKS_HTML
    assert 'Stowling.request("/v1/config/task-resources"' not in TASKS
    assert "savePickupResources" not in TASKS
    assert "saveBaseCoordinates" not in TASKS


def test_run_pickup_only_exposes_region_idle_and_wait_fields() -> None:
    visible = task_function("visibleParameterDefinitions")
    assert "RUN_PICKUP_PARAMETERS" in visible
    assert "RUN_PICKUP_PARAMETERS.includes(definition.key)" in visible
    for key in ("regionId", "idleSeconds", "waitForItems"):
        assert f'"{key}"' in TASKS
    assert "standbyCoordinateId" not in visible
    assert "firstCornerCoordinateId" not in visible
    assert "cacheIds" not in visible
    assert "classificationTiming" not in visible

    controls = task_function("parameterControl")
    assert 'definition.kind === "pickup-region"' in controls
    assert "savedPickupRegions().map((region)" in controls
    assert "pickupRegionLabel(region)" in controls
    regions = task_function("savedPickupRegions")
    assert 'region.kind === "pickup-region"' in regions


def test_pickup_ui_uses_saved_regions_and_points_to_coordinates_page() -> None:
    card = task_function("createStepCard")
    assert "只选择命名坐标页面已保存的拾取区域" in card
    assert "独立的 NAVIGATE 与 WAIT 步骤" in card
    assert "选择待机点、两个角点和缓存" not in card
    assert "RUN_PICKUP 不包含缓存、分类、待机或自动存放" in card

    summary = task_function("renderPickupStepSummary")
    assert "step.parameters.regionId" in summary
    assert "pickupRegionLabel(region)" in summary
    assert 'link.href = "/coordinates.html";' in summary
    assert "保存拾取区域" in summary
    assert "firstCornerCoordinateId" not in summary
    assert "secondCornerCoordinateId" not in summary
    assert "cacheIds" not in summary
    assert "选择两个角点" not in TASKS_HTML
    assert "选择坐标、角点和缓存" not in TASKS_HTML


def test_legacy_pickup_notice_is_compact_and_preserves_values_until_confirm() -> None:
    legacy = task_function("isLegacyPickupStep")
    assert "LEGACY_PICKUP_PARAMETERS.some" in legacy
    assert '"workflowId"' in TASKS
    assert '"firstCornerCoordinateId"' in TASKS
    assert '"cacheIds"' in TASKS

    notice = task_function("legacyPickupNotice")
    assert '"step-legacy-notice"' in notice
    assert "旧自动存放" in notice
    assert "原值会完整保留" in notice
    assert '"替换为纯区域拾取"' in notice
    assert "convertLegacyPickupStep(step)" in notice
    assert "delete " not in notice
    assert "step.parameters =" not in notice

    card = task_function("createStepCard")
    assert "const legacyPickup = isLegacyPickupStep(step);" in card
    assert "const definitions = legacyPickup ? [] :" in card
    assert "parameters.append(legacyPickupNotice(step));" in card


def test_legacy_pickup_conversion_requires_confirmation_and_drops_execution() -> None:
    conversion = task_function("convertLegacyPickupStep")
    warning = conversion.index("window.confirm(warning)")
    replacement = conversion.index("step.parameters = parameters;")
    assert warning < replacement
    assert "缓存、分类和待机参数，它们不会执行" in conversion
    assert "取消会保留全部原值" in conversion
    assert "移动与存入请在后续独立模块中配置" in conversion
    assert "缓存、分类和待机参数已移除，不会执行" in conversion
    assert "parameters.regionId = String(candidate.region.id);" in conversion
    assert "pickupConversionNotes.set(step" in conversion


def test_conversion_matches_only_one_saved_region() -> None:
    workflow = task_function("workflowPickupRegionCandidate")
    assert "bindings.filter((binding)" in workflow
    assert "uniqueRegions(selectedBindings.map((binding)" in workflow
    assert "regions.length === 1" in workflow
    assert "regions.length > 1" in workflow
    assert "未自动选择" in workflow
    assert "命名坐标页面" in workflow
    assert "bindings[0]" not in workflow

    direct = task_function("directPickupRegionCandidate")
    assert "savedPickupRegions().filter((region)" in direct
    assert "pickupRegionBounds(region)" in direct
    assert "savedBounds.dimension === first.dimension" in direct
    assert "sameBounds(savedBounds, bounds)" in direct
    assert "regions.length === 1" in direct
    assert "regions.length > 1" in direct
    assert "未找到边界与维度都相同的已保存拾取区域" in direct
    assert "命名坐标页面保存该区域" in direct
    assert "pickupRegions[0]" not in TASKS


def test_legacy_container_fields_are_hidden_without_rewriting() -> None:
    visible = task_function("visibleParameterDefinitions")
    assert "!isLegacyContainerParameter(definition)" in visible
    detection = task_function("isLegacyContainerParameter")
    assert 'definition.kind === "container"' in detection
    assert "LEGACY_CONTAINER_PARAMETERS.includes(definition.key)" in detection

    legacy = task_function("isLegacyContainerStep")
    assert "definitions.some(isLegacyContainerParameter)" in legacy
    assert "Object.hasOwn(step.parameters, key)" in legacy
    notice = task_function("legacyContainerNotice")
    assert "仅保留原值" in notice
    assert "不会自动改写" in notice
    assert "step.parameters =" not in notice
    assert "delete " not in notice

    payload = task_function("stepPayload")
    assert "Stowling.clone(step.parameters || {})" in payload


def test_parameter_controls_are_descriptor_driven_not_fixed_per_module() -> None:
    controls = task_function("parameterControl")
    assert 'definition.kind === "choice" || definition.kind === "options"' in controls
    assert "definition.options.map((option)" in controls
    assert 'definition.kind === "boolean"' in controls
    assert 'input.min = "0";' in controls
    assert "containerTypeLabel(" not in TASKS
    assert "FIELD_DEFINITIONS" not in TASKS
    assert "FALLBACK_MODULES" not in TASKS
    assert "coordinateId:" not in TASKS
    assert "sourceCoordinateId:" not in TASKS
    assert "completionTrigger:" not in TASKS

    validation = task_function("validateSteps")
    assert "visibleParameterDefinitions(step)" in validation
    assert '["choice", "options"].includes(parameter.kind)' in validation
    assert 'parameter.kind === "nonnegative-integer"' in validation
    assert 'parameter.kind === "boolean"' in validation


def test_legacy_notice_layout_is_compact_and_full_row() -> None:
    for selector in (
        ".step-fields > .pickup-step-hint",
        ".step-fields > .pickup-step-summary",
        ".step-fields > .step-legacy-notice",
    ):
        assert selector in STYLES
    layout = STYLES.split(".step-fields > .step-legacy-notice", 1)[1].split("}", 1)[0]
    assert "grid-column: 1 / -1;" in layout
    assert ".legacy-task-notice" in STYLES
