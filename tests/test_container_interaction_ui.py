from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "src" / "main" / "resources" / "stowling-web"


def read(name: str) -> str:
    return (WEB / name).read_text(encoding="utf-8")


def function_source(source: str, name: str) -> str:
    body = source.split(f"function {name}(", 1)[1]
    return body.split("\n    /**", 1)[0]


def test_detector_is_configured_before_execution_without_nested_forms() -> None:
    html = read("tasks.html")
    detector = html.index('id="task-trigger-settings"')
    trigger_form_end = html.index("</form>", detector)
    task_form = html.index('id="task-form"')
    assert detector < trigger_form_end < task_form
    assert "① 启动条件 · 添加检测器" in html
    assert "② 拾取检测点周围的物品 → ③ 存入你选择的地址" in html
    assert "新任务的检测器随任务保存" in html


def test_collect_template_stages_detector_and_preserves_priority() -> None:
    source = read("tasks.js")
    template = function_source(source, "startTaskTemplate")
    assert 'if (template === "collect")' in template
    assert 'TriggerModel.newTrigger("")' in template
    assert "state.triggerDraft.priority = draft.priority" in template
    assert "state.triggerDirty = true" in template
    new_trigger = function_source(source, "startNewTrigger")
    assert "if (!state.draft)" in new_trigger
    assert "state.triggerDraft.priority = state.draft.priority" in new_trigger


def test_saving_stages_task_id_before_detector_and_reports_partial_failure() -> None:
    source = read("tasks.js")
    saving = function_source(source, "saveTask")
    assert "const pendingTrigger = state.triggerDirty ? state.triggerDraft : null" in saving
    assert "state.triggerDraft === pendingTrigger" in saving
    assert saving.index("pendingTrigger.targetTaskId = saved.id") < saving.index(
        "detectorSaved = await saveTrigger()"
    )
    assert "任务已保存，检测器未保存，请重试保存检测器" in saving
    assert "state.draft === draftSnapshot" in saving
    assert "state.taskEditSerial === editSerial" in saving


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


def test_saved_resource_and_ambiguous_detector_validation_are_preserved() -> None:
    source = read("tasks.js")
    validation = function_source(source, "validateInteractionAction")
    assert "Model.validateInteractionStep(step)" in validation
    assert "detectors.length > 1" in validation
    assert "resource.id === reference.id" in validation
    assert "选择的地址已不存在" in validation
    rendering = function_source(source, "renderEditor")
    assert "editingTaskTriggers()" in rendering


def test_old_modules_remain_available_in_advanced_group() -> None:
    source = read("v2-task-editor.js")
    selector = function_source(source, "moduleSelect")
    assert "常用模块" in selector
    assert "高级基础动作 · 兼容旧任务" in selector
    assert "common.children.length > 0" in selector
    assert "advanced.children.length > 0" in selector
    assert 'operation: "COLLECT", areaRef: "detector"' in selector
    assert "width: min(100%, 40rem)" in read("styles.css")


def test_discarding_task_also_restores_detector_and_invalidates_pending_save() -> None:
    source = read("tasks.js")
    discard = source.split('elements.discard.addEventListener("click"', 1)[1]
    discard = discard.split("elements.run.addEventListener", 1)[0]
    assert "state.triggerDirty = false" in discard
    assert "TriggerModel.normalizeTrigger(trigger) : null" in discard
    assert "state.taskEditSerial++" in discard
    assert "state.triggerEditSerial++" in discard
    saving = function_source(source, "saveTask")
    assert "state.triggerEditSerial !== triggerSerial" in saving
    assert 'detectorSaved ? "任务与检测器已保存" : "任务已保存"' in saving


def test_interaction_output_choices_match_operation_and_find_hides_invalid_filter() -> None:
    source = read("v2-task-editor.js")
    references = function_source(source, "referenceOptions")
    assert 'Model.isInteractionStep(step) && type === "output"' in references
    assert 'options.filter((option) => option.value !== "output:storage")' in references
    editor = function_source(source, "actionEditor")
    assert 'parameter.key === "containerTypes"' in editor
    assert 'Model.referenceType(step.parameters.targetRef) === "category-zone"' in editor
