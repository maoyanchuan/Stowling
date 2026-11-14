from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WEB_ROOT = ROOT / "src" / "main" / "resources" / "stowling-web"
TASKS = (WEB_ROOT / "tasks.js").read_text(encoding="utf-8")
TASKS_HTML = (WEB_ROOT / "tasks.html").read_text(encoding="utf-8")


def task_function(name: str) -> str:
    start = TASKS.index(f"    function {name}(")
    end = TASKS.find("    /**", start)
    return TASKS[start:end] if end != -1 else TASKS[start:]


def test_coordinate_resources_drive_target_selection_and_summary() -> None:
    options = task_function("coordinateOptions")
    assert "state.resources.coordinatePoints" in options
    assert "pickupRegions" not in options
    assert "coordinateLabel(point)" in options

    controls = task_function("parameterControl")
    assert 'definition.kind === "coordinate"' in controls
    assert "coordinateOptions()" in controls
    assert "请选择命名坐标" in controls

    source = task_function("pickupTargetSourceControl")
    assert '"命名坐标与半径", "coordinate"' in source
    assert '"已有拾取区域（兼容）", "region"' in source
    assert "setPickupTargetMode(step, select.value)" in source

    summary = task_function("renderPickupStepSummary")
    assert "coordinateFor(coordinateId)" in summary
    assert "coordinateLabel(coordinate)" in summary
    assert "球形拾取半径" in summary
    assert "pickupCoordinateHelp()" in summary


def test_new_pickup_task_saves_coordinate_defaults_without_region() -> None:
    creation = task_function("createNewDefinition")
    assert 'type: "RUN_PICKUP"' in creation
    assert 'radius: "8"' in creation
    assert 'idleSeconds: "2"' in creation
    assert 'waitForItems: "false"' in creation
    assert "regionId" not in creation
    assert "NAVIGATE" not in creation
    assert "WAIT" not in creation


def test_switching_target_source_clears_the_other_route() -> None:
    switch = task_function("setPickupTargetMode")
    assert 'mode === "region" ? "region" : "coordinate"' in switch
    assert "pickupTargetModes.set(step, next)" in switch
    assert "delete step.parameters.regionId;" in switch
    assert "delete step.parameters.coordinateId;" in switch
    assert "delete step.parameters.radius;" in switch
    assert "markDirty(true);" in switch

    setter = task_function("setStepParameter")
    assert 'definition.key === "coordinateId"' in setter
    assert 'definition.key === "regionId"' in setter
    assert "step.parameters[definition.key] = value;" in setter
    assert "refreshPickupStepSummary(step);" in setter


def test_pickup_validation_requires_one_source_and_bounded_radius() -> None:
    visible = task_function("visibleParameterDefinitions")
    assert '"coordinateId"' in TASKS
    assert '"radius"' in TASKS
    assert '"regionId"' in TASKS
    assert 'return targetMode === "coordinate";' in visible
    assert 'return targetMode === "region";' in visible

    controls = task_function("parameterControl")
    assert 'definition.key === "radius"' in controls
    assert 'input.max = "128";' in controls

    validation = task_function("validateSteps")
    assert "不能同时选择命名坐标和已保存区域" in validation
    assert "必须选择命名坐标或已保存区域" in validation
    assert "使用已保存区域时不能设置半径" in validation
    assert 'parameter.key === "radius"' in validation
    assert "Number(value) < 1 || Number(value) > 128" in validation
    assert "引用的坐标不存在" in validation
    assert "引用的区域无效" in validation


def test_saved_region_remains_a_compatible_selection_only() -> None:
    regions = task_function("savedPickupRegions")
    assert 'region.kind === "pickup-region"' in regions
    controls = task_function("parameterControl")
    assert "savedPickupRegions().map((region)" in controls

    summary = task_function("renderPickupStepSummary")
    assert "pickupRegionLabel(region)" in summary
    assert "pickupRegionHelp()" in summary

    help_text = task_function("pickupRegionHelp")
    assert "没有已有区域，请使用命名坐标和半径。" in help_text
    assert "coordinates.html" not in help_text
    assert "保存区域" not in help_text


def test_explicit_live_refresh_reloads_resources_without_replacing_draft() -> None:
    refresh = task_function("refreshLiveState")
    assert "if (reportErrors)" in refresh
    assert "requests.push(Resources.load());" in refresh
    assert "const requests = [loadDefinitions(true), loadRuntimeTasks()];" in refresh

    callback_start = TASKS.index("Resources.onChanged")
    callback_end = TASKS.index("Stowling.onConnected", callback_start)
    callback = TASKS[callback_start:callback_end]
    assert "state.resources = Resources.getSnapshot();" in callback
    assert "if (state.draft)" in callback
    assert "renderSteps();" in callback
    assert "state.draft =" not in callback
    assert "state.dirty =" not in callback
    assert "刷新状态与资源" in TASKS_HTML
