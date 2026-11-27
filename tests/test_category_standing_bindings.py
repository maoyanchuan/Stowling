from typing import Any

import pytest

from test_coordinate_binding_options_editor import editor_page
from test_coordinate_binding_options_sample import sample_page
from test_coordinate_editor import run_model, NODE


SETUP = """
    const markers = {firstX: 0, firstY: 64, firstZ: 0,
        secondX: 0, secondY: 66, secondZ: 9};
    const storage = {...markers, firstX: 1, secondX: 1};
    const raw = {id: 'zone', name: 'Warehouse',
        categoryOptions: {scopeMode: 'selection', recognitionMode: 'container-item',
            storageMethod: 'bulk', specifyStanding: false},
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
def test_legacy_effective_stance_initializes_one_group_owned_support_block() -> None:
    result = run_model(SETUP + """
        delete raw.bindings[0].standingPlan;
        raw.bindings[0].standingArea = {firstX: 2, secondX: 2,
            firstY: 64, secondY: 64, firstZ: 7, secondZ: 7};
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
    enabled = card.locator('.category-standing-plan input[role="switch"]').first
    enabled.focus()
    page.keyboard.press("Space")
    for axis, value in [("x", "2"), ("y", "63"), ("z", "7")]:
        card.locator(f'[data-standing-axis="{axis}"]').fill(value)
    card.locator(".category-binding-enabled").check()
    card.locator(".category-binding-enabled").uncheck()
    assert enabled.is_checked()
    assert card.locator('[data-standing-axis="z"]').input_value() == "7"
    enabled.uncheck()
    assert card.locator(".category-standing-body").is_hidden()
    assert card.locator(".category-standing-body").evaluate("node => node.inert")
    enabled.check()
    assert card.locator('[data-standing-axis="y"]').input_value() == "63"
    assert card.locator('[id$="category-specify-standing"]').is_hidden()
    assert page.locator("#category-specify-standing").is_hidden()


@pytest.mark.parametrize("fixture", ["sample_page", "editor_page"])
def test_new_standing_controls_use_group_unique_ids(
    request: pytest.FixtureRequest, fixture: str,
) -> None:
    page: Any = request.getfixturevalue(fixture)
    page.locator(".category-standing-plan input[role='switch']").first.check()
    page.locator(".category-standing-add").first.click()
    assert page.evaluate("""() => {
        const ids = Array.from(document.querySelectorAll('[id]'), node => node.id);
        return ids.length === new Set(ids).size;
    }""")
