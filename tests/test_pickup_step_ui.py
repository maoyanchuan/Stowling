from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WEB_ROOT = ROOT / "src" / "main" / "resources" / "stowling-web"
TASKS = (WEB_ROOT / "tasks.js").read_text(encoding="utf-8")
TASKS_HTML = (WEB_ROOT / "tasks.html").read_text(encoding="utf-8")


def test_legacy_items_present_trigger_is_compatible_and_dynamic() -> None:
    assert '"ITEMS_PRESENT"' in TASKS
    assert "TRIGGER_KINDS.includes(trigger.kind)" in TASKS
    assert 'value="ITEMS_PRESENT"' not in TASKS_HTML
    assert "function renderTriggerKindOptions(kind)" in TASKS
    assert 'if (kind === "ITEMS_PRESENT" && !legacyOption)' in TASKS
    assert '"旧配置 · 区域有物品时"' in TASKS
    assert "legacyOption.remove();" in TASKS
    assert "renderTriggerKindOptions(trigger.kind);" in TASKS
    assert 'trigger.regionIds = [];' in TASKS
    assert 'id="trigger-regions-field"' in TASKS_HTML


def test_pickup_region_options_only_use_selected_workflow_regions() -> None:
    scoped_region_branch = (
        'if (step.type === "RUN_PICKUP"\n'
        '                && definition.kind === "pickup-region") {'
    )
    assert "function workflowRegionIds(workflowId)" in TASKS
    assert 'entry.kind === "PICKUP" && entry.id === id' in TASKS
    assert "function pickupRegionOptions(workflowId)" in TASKS
    assert "const allowed = new Set(workflowRegionIds(workflowId));" \
        in TASKS
    assert ".filter((region) => allowed.has(String(region.id || \"\")))" \
        in TASKS
    assert "const options = pickupRegionOptions(workflowId);" in TASKS
    assert "全部区域（所选拾取流程）" in TASKS
    assert scoped_region_branch in TASKS
    assert TASKS.count('definition.kind === "pickup-region"') == 2


def test_workflow_change_clears_only_out_of_scope_region() -> None:
    assert "function setPickupWorkflow(step, definition, workflowId)" \
        in TASKS
    assert "const regionId = String(step.parameters.regionId || \"\");" \
        in TASKS
    assert "!workflowRegionIds(workflowId).includes(regionId)" in TASKS
    assert "delete step.parameters.regionId;" in TASKS
    assert "`保留原值 · ${value}`" in TASKS


def test_pickup_step_hint_and_descriptor_boolean_match_contract() -> None:
    assert "无物品时保持等待、不巡逻，也不会主动加载区域。" in TASKS
    assert "等待发生在任务执行中，可被高优先级任务打断。" in TASKS
    assert "区域下拉只列出所选拾取流程包含的区域。" in TASKS
    assert 'if (definition.kind === "boolean")' in TASKS
    assert 'checkbox.checked = value === "true";' in TASKS
    assert "waitForItems" not in TASKS
