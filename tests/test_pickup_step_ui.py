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


def test_new_pickup_task_is_one_pickup_to_container_step() -> None:
    creation = task_function("createNewDefinition")
    assert 'state.draft = defaultDefinition("AUTOMATION");' in creation
    assert 'defaultDefinition("PICKUP")' not in creation
    assert creation.count('type: "PICKUP_TO_CONTAINER"') == 1
    assert 'coordinateId: ""' in creation
    assert 'destinationCoordinateId: ""' in creation
    assert 'containerType: "CHEST"' in creation
    assert 'idleSeconds: "2"' in creation
    assert 'waitForItems: "false"' in creation
    assert "radius:" not in creation
    assert "regionId:" not in creation
    assert "NAVIGATE" not in creation
    assert "WAIT" not in creation
    assert "新拾取并存入任务" in creation
    assert "只设置掉落物范围和箱体位置" in TASKS_HTML


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


def test_run_pickup_only_exposes_target_idle_and_wait_parameters() -> None:
    parameter_block = TASKS.split(
        "const RUN_PICKUP_PARAMETERS = Object.freeze([", 1
    )[1].split("]);", 1)[0]
    for key in ("coordinateId", "idleSeconds", "waitForItems"):
        assert f'"{key}"' in parameter_block
    assert '"radius"' not in parameter_block
    assert '"regionId"' not in parameter_block

    visible = task_function("visibleParameterDefinitions")
    assert "RUN_PICKUP_PARAMETERS.includes(definition.key)" in visible
    assert "pickupTargetMode" not in visible
    assert "radius" not in visible
    assert "regionId" not in visible

    controls = task_function("parameterControl")
    assert "coordinateOptions(rangeOnly, rangeOnly)" in controls
    assert "input.max" not in controls
    assert "pickupTargetSourceControl" not in TASKS
    assert "setPickupTargetMode" not in TASKS
    assert "pickupTargetModes" not in TASKS


def test_pickup_summary_uses_names_and_coordinate_page_help_only() -> None:
    card = task_function("createStepCard")
    assert "只选择坐标与范围页面中已保存的命名范围" in card
    assert "单点会显示但不可选" in card
    assert "半径、立方体和区域角点都在坐标与范围" in card
    assert "pickupTargetSourceControl" not in card

    summary = task_function("renderPickupStepSummary")
    assert "step.parameters.coordinateId" in summary
    assert "coordinateLabel(coordinate)" in summary
    assert "isRangeCoordinate(coordinate)" in summary
    assert "pickupCoordinateHelp()" in summary
    assert "step.parameters.radius" not in summary
    assert "step.parameters.regionId" not in summary
    assert "dimensionLabel" not in summary
    assert "X ${" not in summary

    coordinate_help = task_function("pickupCoordinateHelp")
    assert 'link.href = "/coordinates.html";' in coordinate_help
    assert "名称与范围参数统一在" in coordinate_help
    assert "保存后的半径或角点会在下次运行时自动生效" in coordinate_help
    assert "无需重新选择" in coordinate_help
    assert "pickupRegionHelp" not in TASKS
    assert "选择坐标、角点和缓存" not in TASKS_HTML


def test_pickup_to_container_uses_range_and_point_roles() -> None:
    controls = task_function("parameterControl")
    assert 'definition.kind === "point-coordinate"' in controls
    assert "pointCoordinateOptions()" in controls
    assert "请选择箱体坐标" in controls

    summary = task_function("renderPickupToContainerSummary")
    assert "step.parameters.destinationCoordinateId" in summary
    assert "isRangeCoordinate(pickup)" in summary
    assert 'coordinateType(destination) !== "POINT"' in summary
    assert "pickup.dimension !== destination.dimension" in summary
    assert "只存入本轮拾取新增的物品" not in summary

    validation = task_function("validatePickupToContainerStep")
    assert "pickup && !isRangeCoordinate(pickup)" in validation
    assert 'coordinateType(destination) !== "POINT"' in validation
    assert "pickup.dimension !== destination.dimension" in validation


def test_pickup_summary_describes_saved_geometry_and_auto_refresh() -> None:
    geometry = task_function("coordinateGeometrySummary")
    assert "球半径" in geometry
    assert "立方体半径" in geometry
    assert "角点" in geometry

    refresh = task_function("refreshResources")
    assert "Resources.load()" in refresh
    assert "state.resources = Resources.getSnapshot()" in refresh
    assert "state.lastResourceRefreshAt = Date.now()" in refresh

    combined = task_function("refreshTasksAndResources")
    assert "loadDefinitions()" in combined
    assert "refreshResources(true)" in combined

    assert 'document.addEventListener?.("visibilitychange"' in TASKS
    assert 'type="button">刷新任务与坐标</button>' in TASKS_HTML


def test_legacy_pickup_detection_keeps_old_geometry_until_confirmation() -> None:
    legacy_parameters = TASKS.split(
        "const LEGACY_PICKUP_PARAMETERS = Object.freeze([", 1
    )[1].split("]);", 1)[0]
    for key in (
        "workflowId",
        "firstCornerCoordinateId",
        "cacheIds",
        "radius",
        "regionId",
    ):
        assert f'"{key}"' in legacy_parameters

    legacy = task_function("isLegacyPickupStep")
    assert "LEGACY_PICKUP_PARAMETERS.some" in legacy
    notice = task_function("legacyPickupNotice")
    assert '"step-legacy-notice"' in notice
    assert "原值不会在任务页静默改写" in notice
    assert "请先在坐标与范围页面确认并保存范围" in notice
    assert '"检查旧几何并转换"' in notice
    assert "convertLegacyPickupStep(step)" in notice
    assert "step.parameters =" not in notice

    card = task_function("createStepCard")
    assert "const legacyPickup = isLegacyPickupStep(step);" in card
    assert "const definitions = legacyPickup ? [] :" in card
    assert "parameters.append(legacyPickupNotice(step));" in card


def test_legacy_pickup_conversion_requires_confirmation_and_range_match() -> None:
    conversion = task_function("convertLegacyPickupStep")
    assert "legacyPickupCoordinateCandidate(step)" in conversion
    warning = conversion.index("window.confirm(warning)")
    replacement = conversion.index("step.parameters = parameters;")
    assert warning < replacement
    assert "转换会移除旧半径、区域、缓存、分类和待机参数" in conversion
    assert "取消会保留全部原值" in conversion
    assert "parameters.coordinateId = String(candidate.coordinate.id);" in conversion
    assert "parameters.regionId" not in conversion
    assert "未自动匹配旧几何" in conversion
    assert "pickupConversionNotes.set(step" in conversion

    candidate = task_function("legacyPickupCoordinateCandidate")
    assert "isRangeCoordinate(referenced)" in candidate
    assert "rangeCoordinateForRegion(regionCandidate.region)" in candidate
    assert "不会自动当作拾取范围" in candidate
    assert "未把旧 POINT 冒充为拾取范围" in candidate


def test_conversion_matches_only_one_named_range() -> None:
    match = task_function("rangeCoordinateForRegion")
    assert "coordinateFor(region.id)" in match
    assert "isRangeCoordinate(point)" in match
    assert "matches.length === 1" in match

    workflow = task_function("workflowPickupRegionCandidate")
    assert "bindings.filter((binding)" in workflow
    assert "uniqueRegions(selectedBindings.map((binding)" in workflow
    assert "regions.length === 1" in workflow
    assert "regions.length > 1" in workflow
    assert "未自动选择" in workflow
    assert "请到坐标与范围页面保存范围，再在任务中选择。" in workflow
    assert "bindings[0]" not in workflow

    direct = task_function("directPickupRegionCandidate")
    assert "savedPickupRegions().filter((region)" in direct
    assert "pickupRegionBounds(region)" in direct
    assert "savedBounds.dimension === first.dimension" in direct
    assert "sameBounds(savedBounds, bounds)" in direct
    assert "regions.length === 1" in direct
    assert "regions.length > 1" in direct
    assert "请到坐标与范围页面保存区域，再在任务中选择。" in direct
    assert "pickupRegions[0]" not in TASKS


def test_legacy_pickup_payload_is_preserved_exactly() -> None:
    payload = task_function("stepPayload")
    assert "Stowling.clone(step.parameters || {})" in payload
    assert "delete " not in payload


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


def test_parameter_controls_remain_descriptor_driven() -> None:
    controls = task_function("parameterControl")
    assert 'definition.kind === "choice" || definition.kind === "options"' in controls
    assert "definition.options.map((option)" in controls
    assert 'definition.kind === "boolean"' in controls
    assert 'input.min = "0";' in controls
    assert "FIELD_DEFINITIONS" not in TASKS
    assert "FALLBACK_MODULES" not in TASKS
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
