from typing import Any

import pytest

from test_coordinate_binding_options_editor import choose, editor_page, selected
from test_coordinate_editor import NODE, ZONE_SETUP, run_model


@pytest.mark.skipif(NODE is None, reason="Node.js is required")
def test_global_unstackable_mode_preserves_independent_draft_and_destination() -> None:
    result = run_model(ZONE_SETUP + """
        const value = model.resources(directory)[0];
        value.categoryOptions.unstackableOverflowRef = 'region:overflow';
        const group = value.bindings[0];
        group.optionsEnabled = true;
        group.categoryOptions = {...value.categoryOptions, recognitionMode: 'container-item',
            multiItemBindingsEnabled: true, multiItemBindings: []};
        const dedicated = model.payload(value);
        const copied = model.payload(model.copy(value));
        value.categoryOptions.recognitionMode = 'marker';
        group.categoryOptions.multiItemBindingsEnabled = false;
        const normal = model.payload(value);
        process.stdout.write(JSON.stringify({dedicated, copied, normal}));
    """)
    assert result["dedicated"]["bindings"][0]["ruleId"] == "UNSTACKABLE_ITEMS"
    draft = result["dedicated"]["bindings"][0]["categoryOptions"]
    assert draft["recognitionMode"] == "container-item"
    assert draft["multiItemBindingsEnabled"] is True
    assert result["copied"]["categoryOptions"]["unstackableOverflowRef"] == "region:overflow"
    assert result["normal"]["bindings"][0]["ruleId"] == "MARKER_CONTAINER_CONTENTS"
    assert result["normal"]["categoryOptions"]["unstackableOverflowRef"] == "region:overflow"


@pytest.mark.parametrize("theme", ["green", "gray"])
@pytest.mark.parametrize("width", [1440, 390])
def test_new_zone_can_select_whole_zone_mode_and_persist_overflow(
    editor_page: Any, theme: str, width: int,
) -> None:
    page = editor_page
    page.set_viewport_size({"width": width, "height": 1000})
    page.evaluate("theme => document.documentElement.dataset.theme = theme", theme)
    root = page.locator(".category-options").first
    card = page.locator(".category-binding-card").first
    card.locator(".category-binding-enabled").check()
    independent = card.locator(".category-options")
    choose(independent, "recognitionMode", "container-item")
    choose(root, "recognitionMode", "unstackable")
    assert independent.locator('[data-category-select="recognitionMode"]').is_hidden()
    assert root.locator('[id$="recognition-expand"]').is_hidden()
    assert root.locator('[id$="bindings-option-row"]').is_hidden()
    choose(root, "unstackableOverflowRef", "coordinate:cache")
    choose(root, "recognitionMode", "marker")
    assert root.locator("#category-unstackable-overflow-row").is_hidden()
    assert selected(independent, "recognitionMode") == "container-item"
    choose(root, "recognitionMode", "unstackable")
    assert selected(root, "unstackableOverflowRef") == "coordinate:cache"
    page.locator('#coordinate-form button[type="submit"]').click()
    page.wait_for_function("window.__editorState.categoryZones.length === 1")
    saved = page.evaluate("window.__editorState.categoryZones[0]")
    assert saved["categoryOptions"]["unstackableOverflowRef"] == "coordinate:cache"
    assert saved["bindings"][0]["ruleId"] == "UNSTACKABLE_ITEMS"
    assert saved["bindings"][0]["categoryOptions"]["recognitionMode"] == "container-item"
    page.locator("#duplicate-coordinate").click()
    assert selected(page.locator(".category-options").first, "unstackableOverflowRef") == (
        "coordinate:cache"
    )
