from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "src" / "main" / "resources" / "stowling-web"


def read(name: str) -> str:
    return (WEB / name).read_text(encoding="utf-8")


def function_source(source: str, name: str) -> str:
    body = source.split(f"function {name}(", 1)[1]
    return body.split("\n    function ", 1)[0]


def test_deposit_to_region_steps_reuse_basic_actions() -> None:
    model = read("v2-task-model.js")
    steps = function_source(model, "depositToRegionSteps")
    exports = model.split("window.StowlingTaskModel =", 1)[1]

    assert "DEPOSIT_ITEM_SOURCES," in exports
    assert "depositToRegionSteps," in exports
    assert 'function depositToRegionSteps(regionRef, itemSource = "collected")' in model
    assert "if (!DEPOSIT_ITEM_SOURCES.includes(itemSource))" in steps
    assert "throw new RangeError" in steps
    assert 'itemSource || "collected"' not in steps
    sources = model.split("const DEPOSIT_ITEM_SOURCES =", 1)[1]
    sources = sources.split("]);", 1)[0]
    for item_source in ("collected", "carried", "managed"):
        assert f'"{item_source}"' in sources

    assert 'actionStep("SCAN_CONTAINERS_IN_REGION", { regionRef })' in steps
    assert 'repeatStep("HAS_CONTAINERS", [' in steps
    assert 'actionStep("SELECT_CONTAINER", {' in steps
    assert 'resetCacheHasMore: "true"' in steps
    assert 'actionStep("MOVE_TO_COORDINATE", { targetRef })' in steps
    assert 'actionStep("DEPOSIT_ITEMS_TO_CONTAINERS", {' in steps
    assert 'const targetRef = "output:container";' in steps
    assert 'actionStep("ASSERT_NO_MANAGED_ITEMS", { itemSource })' in steps

    deposit = steps.split('actionStep("DEPOSIT_ITEMS_TO_CONTAINERS", {', 1)[1]
    deposit = deposit.split("})", 1)[0]
    assert "targetRef," in deposit
    assert "itemSource" in deposit
    assert "regionRef" not in deposit


def test_collect_and_interruption_tasks_reuse_region_deposit_flow() -> None:
    model = read("v2-task-model.js")
    collect = function_source(model, "collectTask")
    interruption = function_source(model, "interruptionTask")

    assert 'actionStep("COLLECT_DROPPED_ITEMS", { areaRef: "" })' in collect
    assert '...depositToRegionSteps("", "collected")' in collect
    assert 'repeatStep("HAS_CONTAINERS"' not in collect
    assert 'actionStep("ASSERT_NO_MANAGED_ITEMS"' not in collect
    assert 'depositToRegionSteps("", "managed")' in interruption
    assert 'repeatStep("HAS_CONTAINERS"' not in interruption
    assert 'actionStep("ASSERT_NO_MANAGED_ITEMS"' not in interruption


def test_deposit_region_option_opens_multi_container_flow() -> None:
    editor = read("v2-task-editor.js")
    target = function_source(editor, "targetReferenceTypesFor")
    select = function_source(editor, "referenceSelect")
    deposit = target.split(
        'moduleId === "DEPOSIT_ITEMS_TO_CONTAINERS"',
        1,
    )[1].split("}", 1)[0]

    assert 'return ["coordinate", "region", ...outputTypesFor(moduleId)];' in deposit
    assert 'step.moduleId === "DEPOSIT_ITEMS_TO_CONTAINERS"' in select
    assert 'parameter.key === "targetRef"' in select
    assert 'groupType === "region" && isRegionDeposit' in select
    assert '"已保存区域 · 多箱存入"' in select
    assert "选区域会将本步骤展开为扫描、逐箱存入和结果确认" in select
    assert "保留物品来源" in select
    assert "生成后可继续编辑基础步骤" in select


def test_action_editor_receives_only_current_node_siblings() -> None:
    editor = read("v2-task-editor.js")
    node = function_source(editor, "createStepNode")

    assert "actionEditor(" in node
    assert "{ ...context, siblings, stepIndex: index }" in node
    assert node.count("{ ...context") == 1


def test_optional_item_source_defaults_and_clears_without_empty_value() -> None:
    editor = read("v2-task-editor.js")
    control = function_source(editor, "itemSourceSelect")

    assert 'step.parameters[parameter.key] ?? defaultValue(parameter) ?? ""' in control
    assert "if (!select.value && !parameter.required)" in control
    assert "delete step.parameters[parameter.key];" in control
    assert "changed();" in control
    assert "return;" in control
    assert "setParameter(step, parameter.key, select.value, changed)" in control


def test_replace_deposit_with_region_validates_and_splices() -> None:
    editor = read("v2-task-editor.js")
    replace = function_source(editor, "replaceDepositWithRegion")

    assert "const regionId = Model.referenceId(regionRef);" in replace
    assert "context.regions.some((region) => region.id === regionId)" in replace
    assert 'const itemSource = step.parameters.itemSource || "collected";' in replace
    assert "Model.DEPOSIT_ITEM_SOURCES.includes(itemSource)" in replace
    assert "Model.depositToRegionSteps(regionRef, itemSource)" in replace
    assert "Model.missingModules(" in replace
    assert "Model.taskModuleIds({ steps: replacement })" in replace
    assert "context.siblings.splice(context.stepIndex, 1, ...replacement)" in replace
    assert "forgetStepState(step)" in replace
    assert "context.changed(true)" in replace
    assert 'return "";' in replace
    assert "setParameter(" not in replace
    assert "task.steps" not in replace


def test_region_change_stops_before_single_box_target_write() -> None:
    editor = read("v2-task-editor.js")
    select = function_source(editor, "referenceSelect")
    change = select.split('select.addEventListener("change"', 1)[1]

    assert 'Model.referenceType(select.value) === "region"' in change
    assert "const error = replaceDepositWithRegion(" in change
    assert 'select.value = String(step.parameters[parameter.key] || "");' in change
    assert "hint.textContent = error;" in change
    assert "hint.hidden = false;" in change
    assert change.index("replaceDepositWithRegion") < change.index("setParameter(")
    assert "return;" in change
    assert 'step.parameters[parameter.key] = select.value' not in change
    assert "setParameter(step, parameter.key, select.value, context.changed)" in change
