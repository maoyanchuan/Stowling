import json
from typing import Any

import pytest

from test_coordinate_binding_options_editor import editor_page, MOCK_COMMON
from test_coordinate_binding_options_sample import sample_page
from test_coordinate_editor import run_model, NODE


SETUP = """
    const markers = {firstX: 0, firstY: 64, firstZ: 0,
        secondX: 0, secondY: 66, secondZ: 9};
    const storage = {...markers, firstX: 1, secondX: 1};
    const raw = {id: 'zone', name: 'Warehouse',
        categoryOptions: {scopeMode: 'selection', recognitionMode: 'container-item',
            storageMethod: 'bulk'},
        bindings: [{id: 'group', markerArea: markers, storageArea: storage,
            ruleId: 'MARKER_CONTAINER_CONTENTS', wholeStorage: true,
            standingPlan: {enabled: true, points: [
                {position: {x: 2, y: 63, z: 2}, markerArea: markers,
                    storageArea: {...storage, secondZ: 4}, subregionsEnabled: true},
                {position: {x: 2, y: 63, z: 7}, markerArea: markers,
                    storageArea: {...storage, firstZ: 5}, subregionsEnabled: true}]}}]};
    const resource = model.resources({categoryZones: [raw]})[0];
"""


@pytest.mark.skipif(NODE is None, reason="Node.js is required")
def test_two_stances_round_trip_without_enabling_independent_business_settings() -> None:
    result = run_model(SETUP + """
        const payload = model.payload(resource);
        console.log(JSON.stringify(payload.bindings[0]));
    """)
    assert result["optionsEnabled"] is False
    assert result["standingArea"] is None
    assert [point["position"]["z"] for point in result["standingPlan"]["points"]] == [2, 7]
    assert [point["storageArea"]["firstZ"]
            for point in result["standingPlan"]["points"]] == [0, 5]


@pytest.mark.skipif(NODE is None, reason="Node.js is required")
@pytest.mark.parametrize("invalid", ["overlap", "outside", "incomplete"])
def test_active_plan_rejects_invalid_spatial_configuration(invalid: str) -> None:
    result = run_model(SETUP + f"""
        const points = resource.bindings[0].standingPlan.points;
        if ('{invalid}' === 'overlap') points[1].storageArea.firstZ = 4;
        if ('{invalid}' === 'outside') points[1].storageArea.secondZ = 10;
        if ('{invalid}' === 'incomplete') points[1].position.y = null;
        let message = '';
        try {{ model.payload(resource); }} catch (error) {{ message = error.message; }}
        console.log(JSON.stringify({{message}}));
    """)
    assert result["message"]


@pytest.mark.skipif(NODE is None, reason="Node.js is required")
def test_disabled_plan_preserves_incomplete_points_and_subregion_drafts() -> None:
    result = run_model(SETUP + """
        const plan = resource.bindings[0].standingPlan;
        plan.enabled = false;
        plan.points[0].position.y = null;
        plan.points[0].subregionsEnabled = false;
        console.log(JSON.stringify(model.payload(resource).bindings[0].standingPlan));
    """)
    assert result["enabled"] is False
    assert result["points"][0]["position"]["y"] is None
    assert result["points"][0]["storageArea"]["secondZ"] == 4


@pytest.mark.skipif(NODE is None, reason="Node.js is required")
def test_migrated_stance_round_trips_as_a_group_owned_support_block() -> None:
    result = run_model(SETUP + """
        raw.bindings[0].standingPlan = {enabled: true, points: [
            {position: {x: 2, y: 63, z: 7}, standingId: 'migrated-standing:g'}]};
        const loaded = model.resources({categoryZones: [raw]})[0];
        const cloned = model.copy(loaded);
        console.log(JSON.stringify({loaded: loaded.bindings[0].standingPlan,
            saved: model.payload(cloned).bindings[0].standingPlan}));
    """)
    assert result["loaded"] == result["saved"]
    assert result["loaded"]["points"][0]["position"] == {"x": 2, "y": 63, "z": 7}


@pytest.mark.parametrize("fixture", ["sample_page", "editor_page"])
def test_keyboard_standing_controls_are_independent_of_business_override(
    request: pytest.FixtureRequest, fixture: str,
) -> None:
    page = request.getfixturevalue(fixture)
    card = page.locator(".category-binding-card").first
    main = page.locator("#category-standing-settings")
    assert card.locator(".category-standing-plan").count() == 0
    enabled = main.locator('input[role="switch"]').first
    enabled.focus()
    page.keyboard.press("Space")
    for axis, value in [("x", "2"), ("y", "63"), ("z", "7")]:
        main.locator(f'[data-standing-axis="{axis}"]').fill(value)
    card.locator(".category-binding-enabled").check()
    card.locator(".category-binding-enabled").uncheck()
    assert enabled.is_checked()
    assert main.locator('[data-standing-axis="z"]').input_value() == "7"
    enabled.uncheck()
    assert main.locator(".category-standing-body").is_hidden()
    assert main.locator(".category-standing-body").evaluate("node => node.inert")
    enabled.check()
    assert main.locator('[data-standing-axis="y"]').input_value() == "63"
    assert card.locator('[id$="category-specify-standing"]').count() == 0
    assert page.locator("#category-specify-standing").count() == 0


@pytest.mark.parametrize("fixture", ["sample_page", "editor_page"])
def test_new_standing_controls_use_unique_ids_in_the_main_menu(
    request: pytest.FixtureRequest, fixture: str,
) -> None:
    page: Any = request.getfixturevalue(fixture)
    page.locator(".category-standing-plan input[role='switch']").first.check()
    page.locator(".category-standing-add").first.click()
    assert page.evaluate("""() => {
        const ids = Array.from(document.querySelectorAll('[id]'), node => node.id);
        return ids.length === new Set(ids).size;
    }""")


def select_standing_group(page: Any, index: int, name: str) -> None:
    picker = page.locator('[data-category-select="standingTarget"]').nth(index)
    picker.locator(".category-select-trigger").click()
    picker.locator(".category-select-option").filter(has_text=name).click()
    page.keyboard.press("Escape")


def test_main_menu_selection_saves_to_the_selected_groups_and_survives_copy(
    editor_page: Any,
) -> None:
    page = editor_page
    scope = page.locator('.category-options [data-category-select="scopeMode"]').first
    scope.locator(".category-select-trigger").click()
    scope.locator('[data-category-value="selection"]').click()
    first = page.locator(".category-binding-card").first
    first.locator(".category-binding-name").fill("前半区域")
    page.locator("#category-standing-enabled").check()
    for axis, value in [("x", "2"), ("y", "63"), ("z", "2")]:
        page.locator(f'[data-standing-axis="{axis}"]').fill(value)
    page.locator("#add-category-binding").click()
    second = page.locator(".category-binding-card").nth(1)
    second.locator(".category-binding-name").fill("后半区域")
    page.locator(".category-standing-add").click()
    for axis, value in [("x", "2"), ("y", "63"), ("z", "7")]:
        page.locator(f'[data-standing-axis="{axis}"]').nth(1).fill(value)
    select_standing_group(page, 1, "后半区域")
    page.locator('#coordinate-form button[type="submit"]').click()
    page.wait_for_function("window.__editorState.categoryZones.length === 1")
    saved = page.evaluate("window.__editorState.categoryZones[0]")
    assert [group["standingPlan"]["points"][0]["position"]["z"]
            for group in saved["bindings"]] == [2, 7]
    assert all(group["standingPlan"]["enabled"] for group in saved["bindings"])
    assert all(not group["optionsEnabled"] for group in saved["bindings"])
    page.locator("#duplicate-coordinate").click()
    page.locator(".category-standing-plan > .category-option-row button").click()
    assert page.locator('[data-standing-axis="z"]').nth(1).input_value() == "7"
    assert "后半区域" in page.locator('[data-category-select="standingTarget"]').nth(1).inner_text()
    assert page.locator(".category-binding-card .category-standing-plan").count() == 0


def test_main_switch_does_not_enable_an_unassigned_empty_group(editor_page: Any) -> None:
    page = editor_page
    page.locator("#category-standing-enabled").check()
    for axis, value in [("x", "2"), ("y", "63"), ("z", "2")]:
        page.locator(f'[data-standing-axis="{axis}"]').fill(value)
    page.locator("#add-category-binding").click()
    page.locator('#coordinate-form button[type="submit"]').click()
    page.wait_for_function("window.__editorState.categoryZones.length === 1")
    saved = page.evaluate("window.__editorState.categoryZones[0].bindings")
    assert saved[0]["standingPlan"]["enabled"]
    assert saved[1]["standingPlan"] == {"enabled": False, "points": []}


def test_referenced_group_requires_rebinding_before_deletion(editor_page: Any) -> None:
    page = editor_page
    page.locator("#category-standing-enabled").check()
    page.locator("#add-category-binding").click()
    first = page.locator(".category-binding-card").first
    first.locator(".category-binding-remove").click()
    assert page.locator(".category-binding-card").count() == 2
    assert "更换或删除此组的站位绑定" in page.locator("#editor-status").inner_text()
    page.locator(".category-standing-remove").click()
    first.locator(".category-binding-remove").click()
    assert page.locator(".category-binding-card").count() == 1


def test_unselected_region_pair_reports_the_missing_binding_before_save(editor_page: Any) -> None:
    page = editor_page
    page.locator("#add-category-binding").click()
    page.locator("#category-standing-enabled").check()
    for axis, value in [("x", "2"), ("y", "63"), ("z", "7")]:
        page.locator(f'[data-standing-axis="{axis}"]').fill(value)
    page.locator('#coordinate-form button[type="submit"]').click()
    assert "为每个站位选择有效的识别区与储存区" in page.locator("#editor-status").inner_text()
    assert page.evaluate("window.__editorState.categoryZones.length") == 0


def test_disabled_main_switch_saves_points_without_enabling_business_overrides(
    editor_page: Any,
) -> None:
    page = editor_page
    page.locator("#category-standing-enabled").check()
    for axis, value in [("x", "2"), ("y", "63"), ("z", "7")]:
        page.locator(f'[data-standing-axis="{axis}"]').fill(value)
    page.locator("#category-standing-enabled").uncheck()
    page.locator('#coordinate-form button[type="submit"]').click()
    page.wait_for_function("window.__editorState.categoryZones.length === 1")
    group = page.evaluate("window.__editorState.categoryZones[0].bindings[0]")
    assert group["standingPlan"]["enabled"] is False
    assert group["standingPlan"]["points"][0]["position"] == {"x": 2, "y": 63, "z": 7}
    assert group["optionsEnabled"] is False


def test_previous_custom_subregions_stay_selected_in_the_main_menu(editor_page: Any) -> None:
    page = editor_page
    area = {"firstX": 0, "firstY": 64, "firstZ": 0,
            "secondX": 0, "secondY": 66, "secondZ": 9}
    points = [{"position": {"x": 2, "y": 63, "z": z}, "subregionsEnabled": True,
               "markerArea": area, "storageArea": {**area, "firstZ": low, "secondZ": high},
               "expanded": True} for z, low, high in [(2, 0, 4), (7, 5, 9)]]
    raw = {"id": "legacy", "name": "旧版子区", "categoryOptions": {"scopeMode": "selection"},
           "bindings": [{"id": "g", "name": "旧组", "markerArea": area, "storageArea": area,
                         "ruleId": "DIRECT_BLOCK_ID", "standingPlan": {
                             "enabled": True, "points": points}}]}
    mock = MOCK_COMMON.replace("categoryZones: [],", f"categoryZones: {json.dumps([raw])},")
    page.route("**/common.js", lambda route: route.fulfill(
        body=mock, content_type="text/javascript"))
    page.reload()
    page.wait_for_function("window.__editorConnected === true")
    page.get_by_text("旧版子区", exact=True).click()
    page.locator(".category-standing-plan > .category-option-row button").click()
    assert page.locator(".category-binding-card .category-standing-plan").count() == 0
    for picker in page.locator('[data-category-select="standingTarget"]').all():
        assert "已保存子区" in picker.locator(".category-select-current").inner_text()
    page.locator('#coordinate-form button[type="submit"]').click()
    page.wait_for_function("window.__editorState.revision !== 'r0'")
    saved = page.evaluate("window.__editorState.categoryZones[0].bindings[0].standingPlan")
    assert [{key: value for key, value in point.items() if key != "standingId"}
            for point in saved["points"]] == points
    assert all(point["standingId"] for point in saved["points"])


def configure_shared_standing(page: Any) -> None:
    scope = page.locator('.category-options [data-category-select="scopeMode"]').first
    scope.locator(".category-select-trigger").click()
    scope.locator('[data-category-value="selection"]').click()
    page.locator(".category-binding-name").first.fill("前区")
    page.locator("#category-standing-enabled").check()
    for axis, value in [("x", "2"), ("y", "63"), ("z", "2")]:
        page.locator(f'[data-standing-axis="{axis}"]').fill(value)
    page.locator("#add-category-binding").click()
    page.locator(".category-binding-name").nth(1).fill("后区")
    select_standing_group(page, 0, "后区")


def test_shared_standing_reopens_and_copies_as_one_editor_then_updates_all_groups(
    editor_page: Any,
) -> None:
    page = editor_page
    configure_shared_standing(page)
    assert page.locator(".category-standing-point").count() == 1
    picker = page.locator('[data-category-select="standingTarget"]')
    assert picker.locator("select").evaluate("node => node.selectedOptions.length") == 2
    page.locator('#coordinate-form button[type="submit"]').click()
    page.wait_for_function("window.__editorState.categoryZones.length === 1")
    saved = page.evaluate("window.__editorState.categoryZones[0].bindings")
    first = saved[0]["standingPlan"]["points"][0]
    second = saved[1]["standingPlan"]["points"][0]
    assert first["standingId"] == second["standingId"]
    assert first["position"] == second["position"]
    assert page.locator(".category-standing-point").count() == 1
    page.locator(".category-standing-plan > .category-option-row button").click()
    page.locator('[data-standing-axis="z"]').fill("7")
    page.locator('#coordinate-form button[type="submit"]').click()
    page.wait_for_function("window.__editorState.categoryZones[0].bindings[0]"
                           ".standingPlan.points[0].position.z === 7")
    latest = page.evaluate("window.__editorState.categoryZones[0].bindings")
    assert all(group["standingPlan"]["points"][0]["position"]["z"] == 7 for group in latest)
    page.locator("#duplicate-coordinate").click()
    page.locator(".category-standing-plan > .category-option-row button").click()
    assert page.locator(".category-standing-point").count() == 1
    assert page.locator('[data-standing-axis="z"]').input_value() == "7"
    assert page.locator(".category-standing-target").evaluate(
        "node => node.selectedOptions.length") == 2


def test_unselecting_one_region_keeps_the_other_shared_binding(editor_page: Any) -> None:
    page = editor_page
    configure_shared_standing(page)
    select_standing_group(page, 0, "前区")
    page.locator('#coordinate-form button[type="submit"]').click()
    page.wait_for_function("window.__editorState.categoryZones.length === 1")
    groups = page.evaluate("window.__editorState.categoryZones[0].bindings")
    assert groups[0]["standingPlan"] == {"enabled": False, "points": []}
    assert groups[1]["standingPlan"]["points"][0]["position"]["z"] == 2
    assert page.locator(".category-standing-point").count() == 1


@pytest.mark.parametrize("fixture", ["sample_page", "editor_page"])
def test_multi_select_keyboard_keeps_menu_open_and_marks_each_selected_region(
    request: pytest.FixtureRequest, fixture: str,
) -> None:
    page = request.getfixturevalue(fixture)
    page.locator("#category-standing-enabled").check()
    page.locator("#add-category-binding").click()
    picker = page.locator('[data-category-select="standingTarget"]')
    picker.locator(".category-select-trigger").press("ArrowDown")
    page.keyboard.press("End")
    page.keyboard.press("Space")
    assert picker.locator(".category-select-menu").is_visible()
    assert picker.locator('[role="option"][aria-selected="true"]').count() == 2
    assert picker.locator('[role="listbox"]').get_attribute("aria-multiselectable") == "true"
    page.keyboard.press("Escape")
    assert picker.locator(".category-select-menu").is_hidden()
