from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "src" / "main" / "resources" / "stowling-web"


def read(name: str) -> str:
    return (WEB / name).read_text(encoding="utf-8")


def function_source(source: str, name: str) -> str:
    body = source.split(f"function {name}(", 1)[1]
    return body.split("\n    /**", 1)[0]


def test_interaction_editor_uses_operation_specific_model_contract() -> None:
    source = read("v2-task-editor.js")
    editor = function_source(source, "actionEditor")
    assert "Model.interactionParameters(step.parameters.operation)" in editor
    assert "interactionOperationSelect(step, context.changed)" in editor
    assert "Model.isInteractionStep(step)" in editor
    operations = function_source(source, "interactionOperationSelect")
    assert "step.parameters = parameters" in operations
    assert "delete parameters.targetRef" in operations
    assert 'parameters.itemSource = "collected"' in operations
    assert 'parameters.itemSource = "classified"' in operations


def test_unified_deposit_keeps_region_address_and_explains_analysis() -> None:
    source = read("v2-task-editor.js")
    types = function_source(source, "interactionTargetTypes")
    assert '["coordinate", "region", "category-zone", "output"]' in types
    control = function_source(source, "referenceSelect")
    assert 'Model.isInteractionStep(step, "COLLECT")' in control
    assert 'new Option("沿用掉落物检测器的范围", "detector")' in control
    assert "已识别为区域：按需遍历区域内的容器" in control
    assert "已识别为分类区：按物品匹配容器" in control
    assert 'const isRegionDeposit = step.moduleId === "DEPOSIT_ITEMS_TO_CONTAINERS"' in control
    assert "setParameter(step, parameter.key, select.value, context.changed)" in control


def test_old_modules_remain_available_in_advanced_group() -> None:
    source = read("v2-task-editor.js")
    selector = function_source(source, "moduleSelect")
    assert "常用模块" in selector
    assert "高级基础动作 · 兼容旧任务" in selector
    assert "common.children.length > 0" in selector
    assert "advanced.children.length > 0" in selector
    assert 'operation: "COLLECT", areaRef: "detector"' in selector
    assert "width: min(100%, 40rem)" in read("styles.css")


def test_interaction_output_choices_match_operation_and_find_hides_invalid_filter() -> None:
    source = read("v2-task-editor.js")
    references = function_source(source, "referenceOptions")
    assert 'Model.isInteractionStep(step) && type === "output"' in references
    assert 'options.filter((option) => option.value !== "output:storage")' in references
    editor = function_source(source, "actionEditor")
    assert 'parameter.key === "containerTypes"' in editor
    assert 'Model.referenceType(step.parameters.targetRef) === "category-zone"' in editor
