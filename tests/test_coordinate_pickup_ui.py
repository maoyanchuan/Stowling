from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WEB_ROOT = ROOT / "src" / "main" / "resources" / "stowling-web"
TASKS = (WEB_ROOT / "tasks.js").read_text(encoding="utf-8")
TASKS_HTML = (WEB_ROOT / "tasks.html").read_text(encoding="utf-8")
RESOURCES = (WEB_ROOT / "task-resources.js").read_text(encoding="utf-8")


def task_function(name: str) -> str:
    start = TASKS.index(f"    function {name}(")
    end = TASKS.find("    /**", start)
    return TASKS[start:end] if end != -1 else TASKS[start:]


def test_coordinate_options_are_name_only_and_range_filterable() -> None:
    options = task_function("coordinateOptions")
    assert "state.resources.coordinatePoints" in options
    assert "showIneligible" in options
    assert "rangeOnly && !isRangeCoordinate(point)" in options
    assert "单点（需改为半径或区域）" in options
    assert "true" in options
    assert "coordinateLabel(point)" in options
    assert "position" not in options
    assert "dimension" not in options
    assert "point.type" not in options

    label = task_function("coordinateLabel")
    assert "return coordinateName(point);" in label
    assert "X " not in label
    assert "dimension" not in label
    assert "type" not in label

    name = task_function("coordinateName")
    assert 'String(point?.name || "").trim()' in name
    assert 'String(point?.id || "").trim()' in name
    assert 'fallback = "未命名坐标"' in name

    controls = task_function("parameterControl")
    assert "coordinateOptions(rangeOnly, rangeOnly)" in controls
    assert "请选择命名范围" in controls
    assert "coordinate ? coordinateLabel(coordinate) : \"\"" in controls

    select = task_function("referenceSelect")
    assert "const retainedLabel = fallbackLabel || value;" in select
    assert "new Option(`保留原值 · ${retainedLabel}`, value)" in select


def test_typed_coordinate_helpers_treat_missing_type_as_point() -> None:
    types = TASKS.split("const RANGE_COORDINATE_TYPES =", 1)[1].split("]);", 1)[0]
    for value in ("SPHERE", "CUBE", "AREA"):
        assert f'"{value}"' in types

    coordinate_type = task_function("coordinateType")
    assert 'String(point?.type || "POINT").toUpperCase()' in coordinate_type
    assert "COORDINATE_TYPES.includes(type) ? type : \"POINT\"" in coordinate_type

    range_check = task_function("isRangeCoordinate")
    assert "RANGE_COORDINATE_TYPES.includes(coordinateType(point))" in range_check


def test_new_pickup_task_has_no_task_level_radius_or_region() -> None:
    creation = task_function("createNewDefinition")
    assert 'type: "RUN_PICKUP"' in creation
    assert 'coordinateId: ""' in creation
    assert 'idleSeconds: "2"' in creation
    assert 'waitForItems: "false"' in creation
    assert "radius:" not in creation
    assert "regionId:" not in creation
    assert "NAVIGATE" not in creation
    assert "WAIT" not in creation


def test_target_source_switching_ui_is_removed() -> None:
    assert "pickupTargetModes" not in TASKS
    assert "pickupTargetMode" not in TASKS
    assert "pickupTargetDefinitions" not in TASKS
    assert "pickupTargetSourceControl" not in TASKS
    assert "setPickupTargetMode" not in TASKS
    assert "拾取目标来源" not in TASKS

    setter = task_function("setStepParameter")
    assert "step.parameters[definition.key] = value;" in setter
    assert "step.parameters.regionId" not in setter
    assert "step.parameters.radius" not in setter
    assert "refreshPickupStepSummary(step);" in setter


def test_pickup_validation_requires_existing_range_reference() -> None:
    validation = task_function("validateSteps")
    assert "const coordinate = coordinateFor(coordinateId);" in validation
    assert "必须选择一个已保存的命名范围" in validation
    assert "引用的命名范围不存在" in validation
    assert "isRangeCoordinate(coordinate)" in validation
    assert "SPHERE、CUBE 或 AREA" in validation
    assert "parameter.key === \"radius\"" not in validation
    assert "Number(value) > 128" not in validation
    assert "使用已保存区域时不能设置半径" not in validation


def test_pickup_summary_renders_saved_geometry_without_task_fields() -> None:
    summary = task_function("renderPickupStepSummary")
    assert "拾取范围：${coordinateLabel(coordinate)}" in summary
    assert "coordinateGeometrySummary(coordinate)" in summary
    assert "pickupCoordinateHelp()" in summary
    assert "step.parameters.radius" not in summary
    assert "regionId" not in summary
    assert "dimension" not in summary
    assert "position" not in summary
    assert "SPHERE、CUBE 或 AREA" in summary

    help_text = task_function("pickupCoordinateHelp")
    assert 'link.href = "/coordinates.html";' in help_text
    assert 'Stowling.element("a", "", "坐标与范围")' in help_text
    assert "保存后的半径或角点会在下次运行时自动生效" in help_text
    assert "无需重新选择" in help_text


def test_legacy_range_inputs_require_explicit_conversion_not_point_fallback() -> None:
    legacy_parameters = TASKS.split(
        "const LEGACY_PICKUP_PARAMETERS = Object.freeze([", 1
    )[1].split("]);", 1)[0]
    assert '"radius"' in legacy_parameters
    assert '"regionId"' in legacy_parameters

    conversion = task_function("convertLegacyPickupStep")
    assert "window.confirm(warning)" in conversion
    assert "未找到唯一可匹配的命名范围" in conversion
    assert "取消会保留全部原值" in conversion

    candidate = task_function("legacyPickupCoordinateCandidate")
    assert "旧 coordinateId 指向 POINT，不会自动当作拾取范围。" in candidate
    assert "isRangeCoordinate(sameIdCoordinate)" in candidate
    assert "rangeCoordinateForRegion(savedRegion)" in candidate
    assert "未把旧 POINT 冒充为拾取范围。" in candidate


def test_resource_snapshot_preserves_typed_range_fields() -> None:
    assert "coordinatePoints: Array.isArray(" in RESOURCES
    assert "Stowling.clone(coordinateSnapshot.coordinatePoints)" in RESOURCES
    assert ".map((point)" not in RESOURCES
    assert "type:" not in RESOURCES
    assert "radius:" not in RESOURCES
    assert "secondCorner:" not in RESOURCES


def test_explicit_live_refresh_reloads_resources_without_replacing_draft() -> None:
    refresh = task_function("refreshLiveState")
    assert "if (reportErrors)" in refresh
    assert "requests.push(Resources.load());" in refresh
    assert "const requests = [loadDefinitions(true), loadRuntimeTasks()];" in refresh
    assert "state.draft =" not in refresh

    callback_start = TASKS.index("Resources.onChanged")
    callback_end = TASKS.index("Stowling.onConnected", callback_start)
    callback = TASKS[callback_start:callback_end]
    assert "const next = Resources.getSnapshot();" in callback
    assert "state.resources = next;" in callback
    assert "if (!changed)" in callback
    assert "if (state.draft)" in callback
    assert "renderSteps();" in callback
    assert "state.draft =" not in callback
    assert "state.dirty =" not in callback
    assert "刷新状态与资源" in TASKS_HTML

    polling_start = TASKS.index("window.setInterval")
    polling_end = TASKS.index("}, 5000);", polling_start)
    polling = TASKS[polling_start:polling_end]
    assert "refreshLiveState(false)" in polling
    assert "Resources.load()" not in polling
