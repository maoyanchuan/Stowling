from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
WEB_ROOT = ROOT / "src" / "main" / "resources" / "stowling-web"
TASKS = (WEB_ROOT / "tasks.js").read_text(encoding="utf-8")
TASKS_HTML = (WEB_ROOT / "tasks.html").read_text(encoding="utf-8")
STYLES = (WEB_ROOT / "styles.css").read_text(encoding="utf-8")


def task_function(name: str) -> str:
    start = TASKS.index(f"    function {name}(")
    end = TASKS.find("    /**", start)
    return TASKS[start:end] if end != -1 else TASKS[start:]


def test_legacy_items_present_trigger_is_compatible_and_dynamic() -> None:
    assert '"ITEMS_PRESENT"' in TASKS
    assert "TRIGGER_KINDS.includes(trigger.kind)" in TASKS
    assert 'value="ITEMS_PRESENT"' not in TASKS_HTML
    options = task_function("renderTriggerKindOptions")
    assert 'if (kind === "ITEMS_PRESENT" && !legacyOption)' in options
    assert '"旧配置 · 区域有物品时"' in options
    assert "legacyOption.remove();" in options
    assert "renderTriggerKindOptions(trigger.kind);" in TASKS
    assert "trigger.regionIds = [];" in TASKS
    assert 'id="trigger-regions-field"' in TASKS_HTML


def test_new_task_choices_both_create_automation_not_legacy_pickup() -> None:
    creation = task_function("createNewDefinition")
    assert 'state.draft = defaultDefinition("AUTOMATION");' in creation
    assert 'defaultDefinition("PICKUP")' not in creation
    assert 'if (pickup)' in creation
    assert 'type: "RUN_PICKUP"' in creation
    assert 'parameters: { idleSeconds: "2" }' in creation
    assert "workflowId" not in creation
    assert "regionId" not in creation
    assert "elements.newTaskDialog.showModal();" in task_function("startNewDefinition")
    assert "createNewDefinition(false)" in TASKS
    assert "createNewDefinition(true)" in TASKS
    assert 'id="new-custom-task-button"' in TASKS_HTML
    assert 'id="new-pickup-task-button"' in TASKS_HTML
    selector = TASKS_HTML.split('<select id="task-kind">', 1)[1].split("</select>", 1)[0]
    assert 'value="AUTOMATION"' in selector
    assert 'value="PICKUP"' not in selector
    assert 'value="PICKUP"' in TASKS_HTML.split('<select id="task-filter-kind">', 1)[1]
    options = task_function("renderTaskKindOptions")
    assert 'if (kind === "PICKUP" && !legacyOption)' in options
    assert "旧拾取任务（兼容编辑）" in options
    assert "legacyOption.remove();" in options


def test_pickup_legacy_controls_are_hidden_without_rewriting_saved_parameters() -> None:
    card = task_function("createStepCard")
    assert 'const definitions = moduleFor(step.type)?.parameters || [];' in card
    assert 'const legacyPickup = isLegacyPickupStep(step);' in card
    assert '(legacyPickup\n' in card
    assert '["workflowId", "regionId"].includes(definition.key)' in card
    assert "parameters.append(legacyPickupNotice(step));" in card
    assert "definitions.forEach((definition)" in card
    assert "definition.required || directRequired" in card
    assert "delete step.parameters" not in card
    assert "step.parameters =" not in card
    legacy = task_function("isLegacyPickupStep")
    assert "Boolean(step.parameters.workflowId) && !hasDirectRules" in legacy
    assert "Object.hasOwn(step.parameters, definition.key)" in legacy
    assert '["coordinate", "containers", "nonnegative-integer", "choice"]' in legacy
    assert '"boolean"' not in legacy
    notice = task_function("legacyPickupNotice")
    assert "改为自定义规则" in notice
    assert 'button.addEventListener("click", () => convertLegacyPickupStep(step));' in notice
    assert "转换前保留原值" in notice
    assert "delete " not in notice


def test_conversion_is_explicit_and_never_picks_an_arbitrary_region() -> None:
    conversion = task_function("convertLegacyPickupStep")
    assert "const parameters = { ...step.parameters };" in conversion
    assert "delete parameters.workflowId;" in conversion
    assert "delete parameters.regionId;" in conversion
    assert "bindings.find((candidate) => String(candidate.regionId) === regionId)" in conversion
    assert "bindings.length === 1 ? bindings[0] : null" in conversion
    assert "!regionId && bindings.length > 1" in conversion
    assert "未自动选择其中一个" in conversion
    assert "const region = binding ? resourceRegion(binding.regionId) : null;" in conversion
    assert "pickupRegions[0]" not in TASKS
    assert "pickupRegionOptions" not in TASKS
    assert "setPickupWorkflow" not in TASKS
    assert "workflow?.regions || []" in conversion


def test_conversion_only_copies_safe_coordinate_references_and_workflow_settings() -> None:
    conversion = task_function("convertLegacyPickupStep")
    assert 'if (region.shape?.type === "CUBOID")' in conversion
    assert "coordinateFor(region.standbyCoordinateId)" in conversion
    assert "coordinateFor(region.shape[key])" in conversion
    assert "point.dimension === workflow.dimension" in conversion
    assert "point.dimension === region.dimension" in conversion
    assert "parameters[key] = String(point.id);" in conversion
    assert '(workflow.cacheIds || []).map(String).join(",")' in conversion
    assert "option.value === workflow.classificationTiming" in conversion
    assert "binding?.idleDelay" in conversion
    assert "Number.isInteger(Number(idle)) && Number(idle) >= 0" in conversion
    assert "pickupConversionNotes.set(step" in conversion
    assert "step.parameters = parameters;" in conversion
    assert "没有可用的命名坐标引用" in conversion
    assert "原范围不是引用命名角点的长方体" in conversion


def test_coordinate_labels_include_name_xyz_dimension_and_preserve_invalid_ids() -> None:
    options = task_function("coordinateOptions")
    assert "coordinateLabel(point)" in options
    label = task_function("coordinateLabel")
    assert "point.name || point.id" in label
    for axis in ("x", "y", "z"):
        assert f"position.{axis}" in label
    assert "point.dimension" in label
    reference = task_function("referenceSelect")
    assert "`保留原值 · ${value}`" in reference
    assert "select.value = value;" in reference
    assert "setStepParameter" not in reference
    assert '"请选择命名坐标"' in task_function("parameterControl")


def test_cache_multi_select_preserves_missing_ids_until_user_removes_them() -> None:
    containers = task_function("containersControl")
    assert 'value.split(",")' in containers
    assert "for (const id of selected)" in containers
    assert "!caches.some((cache) => String(cache.id) === id)" in containers
    assert "`保留原值 · 缺失缓存 · ${id}`" in containers
    assert "checkbox.checked = selected.has(id);" in containers
    assert 'checkbox.addEventListener("change"' in containers
    assert "selected.add(id);" in containers
    assert "selected.delete(id);" in containers
    assert 'setStepParameter(step, definition, [...selected].join(","));' in containers
    assert "selected.clear()" not in containers


@pytest.mark.parametrize("kind", ["choice", "containers", "nonnegative-integer", "boolean"])
def test_controls_are_descriptor_driven(kind: str) -> None:
    assert f'definition.kind === "{kind}"' in TASKS
    normalization = task_function("normalizeModule")
    assert "Array.isArray(parameter?.options)" in normalization
    assert 'value: String(option?.value ?? "")' in normalization
    assert 'label: String(option?.label ?? option?.value ?? "")' in normalization
    assert "parameter?.defaultValue ?? parameter?.default" in normalization
    controls = task_function("parameterControl")
    assert "definition.options.map((option) => [option.value, option.label])" in controls
    assert 'input.min = "0";' in controls
    assert 'checkbox.checked = value === "true";' in controls
    assert "standbyCoordinateId" not in controls
    assert "firstCornerCoordinateId" not in controls
    assert "cacheIds" not in controls
    defaults = task_function("parameterValue")
    assert 'return definition.defaultValue || "false";' in defaults
    assert 'return "2";' in defaults
    validation = task_function("validateSteps")
    assert '["coordinate", "containers"].includes(parameter.kind)' in validation
    assert "parameter.required || directRequired" in validation
    assert 'parameter.kind === "nonnegative-integer"' in validation
    assert "!Number.isInteger(Number(value)) || Number(value) < 0" in validation


def test_pickup_summary_and_hints_span_full_row_and_explain_direct_rules() -> None:
    card = task_function("createStepCard")
    assert "角点构成长方体范围，顺序任意" in card
    assert "分类沿用现有存放路由" in card
    assert "无物品时等待、不巡逻、不主动加载区域" in card
    assert "可被高优先级任务打断，不创建周期任务" in card
    summary = task_function("renderPickupStepSummary")
    assert "coordinateLabel(standby)" in summary
    for axis in ("x", "y", "z"):
        assert f"Math.min(a.{axis}, b.{axis})" in summary
        assert f"Math.max(a.{axis}, b.{axis})" in summary
    assert 'link.href = "/coordinates.html";' in summary
    assert "待机点和两个角点必须在同一维度" in summary
    for selector in ("pickup-step-hint", "pickup-step-summary", "pickup-step-legacy"):
        assert f".step-fields > .{selector}" in STYLES
    layout = STYLES.split(".step-fields > .pickup-step-hint", 1)[1].split("}", 1)[0]
    assert "grid-column: 1 / -1;" in layout
    assert "min(100%, 250px)" in STYLES


def test_non_pickup_modules_keep_their_shared_region_selector() -> None:
    controls = task_function("parameterControl")
    assert controls.count('definition.kind === "pickup-region"') == 1
    assert '(state.resources.pickupRegions || []).map((region)' in controls
    assert 'entry.kind === "PICKUP"' in controls
    assert "setStepParameter(step, definition, next)" in controls
