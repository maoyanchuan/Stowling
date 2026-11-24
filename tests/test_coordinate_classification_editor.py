import json
from typing import Any

import pytest

from test_coordinate_binding_options_editor import editor_page
from test_coordinate_editor import NODE, ZONE_SETUP, run_model


@pytest.mark.skipif(NODE is None, reason="Node.js is required")
def test_rules_round_trip_global_and_disabled_independent_drafts_without_example_data() -> None:
    result = run_model(ZONE_SETUP + """
        const value = model.resources(directory)[0];
        const legacy = model.payload(value).categoryOptions;
        value.categoryOptions = {...value.categoryOptions,
            ignoreMarkerPositions: true, ignoredMarkerPositions: [{x: 44, y: 64, z: 39}],
            ignoreRepeatedBlocks: true, multiItemBindingsEnabled: true,
            multiItemBindings: [{name: 'Sculk', targetType: 'marker-id',
                markerId: 'minecraft:sculk', position: {x: 0, y: 64, z: 0},
                itemIds: 'minecraft:sculk_sensor\\nminecraft:sculk_catalyst\\n', expanded: true}]};
        const local = model.copy(value.categoryOptions);
        local.ignoreMarkerPositions = false;
        local.ignoredMarkerPositions[0].y = null;
        local.multiItemBindingsEnabled = false;
        local.multiItemBindings[0].itemIds = 'unfinished draft';
        value.bindings[0].categoryOptions = local;
        value.bindings[0].optionsEnabled = false;
        const saved = model.payload(value);
        const cloned = model.payload(model.copy(value));
        const server = {...raw, categoryOptions: saved.categoryOptions,
            bindings: [{...binding, categoryOptions: saved.bindings[0].categoryOptions,
                optionsEnabled: false}]};
        const reloaded = model.resources({categoryZones: [server]})[0];
        process.stdout.write(JSON.stringify({legacy, saved, cloned, reloaded}));
    """)
    assert result["legacy"]["ignoredMarkerPositions"] == []
    assert result["legacy"]["multiItemBindings"] == []
    assert result["legacy"]["ignoreRepeatedBlocks"] is False
    saved = result["saved"]
    assert saved == result["cloned"]
    assert saved["categoryOptions"]["multiItemBindings"][0]["itemIds"].endswith("\n")
    for draft in (saved["bindings"][0], result["reloaded"]["bindings"][0]):
        assert draft["optionsEnabled"] is False
        assert draft["categoryOptions"]["ignoredMarkerPositions"][0]["y"] is None
        assert draft["categoryOptions"]["multiItemBindings"][0]["itemIds"] == "unfinished draft"


@pytest.mark.skipif(NODE is None, reason="Node.js is required")
@pytest.mark.parametrize("target", ["marker-id", "marker-position", "container"])
def test_three_target_types_preserve_inputs_and_only_excluded_marker_targets_conflict(
    target: str,
) -> None:
    result = run_model(ZONE_SETUP + """
        const value = model.resources(directory)[0];
        value.categoryOptions.ignoreMarkerPositions = true;
        value.categoryOptions.ignoredMarkerPositions = [{x: 1, y: 64, z: 2}];
        value.categoryOptions.multiItemBindingsEnabled = true;
        value.categoryOptions.multiItemBindings = [{name: 'Rules',
            targetType: """ + json.dumps(target) + """, markerId: 'minecraft:stone',
            position: {x: 1, y: 64, z: 2}, itemIds: 'minecraft:dirt', expanded: false}];
        try { process.stdout.write(JSON.stringify({saved: model.payload(value)})); }
        catch (error) { process.stdout.write(JSON.stringify({error: error.message})); }
    """)
    if target == "marker-position":
        assert "目标标记已被坐标排除" in result["error"]
    else:
        rule = result["saved"]["categoryOptions"]["multiItemBindings"][0]
        assert rule["markerId"] == "minecraft:stone"
        assert rule["position"] == {"x": 1, "y": 64, "z": 2}


def field(root: Any, key: str) -> Any:
    return root.locator(f'[id$="category-{key}"]')


@pytest.mark.parametrize("scope", ["global", "binding"])
@pytest.mark.parametrize("width,theme", [(1440, "green"), (390, "gray")])
def test_real_editor_rules_keep_drafts_keyboard_navigation_and_saved_state(
    editor_page: Any, scope: str, width: int, theme: str,
) -> None:
    page = editor_page
    page.set_viewport_size({"width": width, "height": 1100})
    page.evaluate("theme => document.documentElement.dataset.theme = theme", theme)
    root = page.locator(".category-options").first
    if scope == "binding":
        card = page.locator(".category-binding-card").first
        card.locator(".category-binding-enabled").check()
        root = card.locator(".category-options")
    assert field(root, "recognition-expand").get_attribute("aria-expanded") == "false"
    field(root, "recognition-expand").click()
    field(root, "ignore-repeated").check()
    assert field(root, "recognition-summary").inner_text() == "已启用 1 项"
    field(root, "ignore-positions").check()
    assert field(root, "recognition-summary").inner_text() == "已启用 2 项"
    field(root, "multi-bindings").check()
    assert field(root, "bindings-summary").inner_text() == "1 条规则"
    rule = field(root, "rules-list").locator(".category-rule-form").first
    assert rule.locator(".category-rule-toggle").get_attribute("aria-expanded") == "true"
    rule.locator('[data-rule-field="name"]').fill("Real rule")
    rule.locator('[data-rule-field="markerId"]').fill("minecraft:sculk")
    draft = "minecraft:sculk_sensor\nminecraft:sculk_catalyst\n"
    rule.locator("textarea").fill(draft)
    select = rule.locator('[data-category-select="bindingTarget"]')
    select.locator(".category-select-trigger").press("ArrowDown")
    page.keyboard.press("End")
    page.keyboard.press("Enter")
    assert select.locator("select").input_value() == "container"
    rule.locator('.category-rule-toggle').click()
    assert rule.locator(".category-rule-body").is_hidden()
    field(root, "multi-bindings").uncheck()
    assert field(root, "bindings-summary").inner_text() == "已关闭 · 1 条规则"
    page.locator('#coordinate-form button[type="submit"]').click()
    page.wait_for_function("window.__editorState.categoryZones.length === 1")
    saved = page.evaluate("window.__editorState.categoryZones[0]")
    options = (saved["categoryOptions"] if scope == "global"
               else saved["bindings"][0]["categoryOptions"])
    assert options["ignoreRepeatedBlocks"] is True
    assert options["ignoredMarkerPositions"] == [{"x": 0, "y": 64, "z": 0}]
    assert options["multiItemBindingsEnabled"] is False
    assert options["multiItemBindings"][0]["itemIds"] == draft
    assert options["multiItemBindings"][0]["targetType"] == "container"
    page.locator("#duplicate-coordinate").click()
    if scope == "binding":
        card = page.locator(".category-binding-card").first
        card.locator(".category-binding-toggle").click()
        root = card.locator(".category-options")
    else:
        root = page.locator(".category-options").first
    field(root, "multi-bindings").check()
    rule = field(root, "rules-list").locator(".category-rule-form").first
    assert rule.locator(".category-rule-toggle").get_attribute("aria-expanded") == "false"
    rule.locator(".category-rule-toggle").click()
    assert rule.locator("textarea").input_value() == draft
    assert rule.locator('[data-rule-field="markerId"]').input_value() == "minecraft:sculk"
    bounds = rule.locator("textarea").bounding_box()
    assert bounds is not None and bounds["x"] >= 0
    assert bounds["x"] + bounds["width"] <= width
