from collections.abc import Iterator
from pathlib import Path
from typing import Any

import pytest


SAMPLE = Path(__file__).resolve().parents[1] / "docs/ui/coordinate-management-standard.html"


@pytest.fixture
def sample_page() -> Iterator[Any]:
    playwright = pytest.importorskip("playwright.sync_api")
    with playwright.sync_playwright() as runtime:
        try:
            browser = runtime.chromium.launch()
        except playwright.Error as error:
            pytest.skip(f"Playwright Chromium is required: {error}")
        page = browser.new_page(viewport={"width": 1552, "height": 1253})
        page.emulate_media(reduced_motion="reduce")
        page.goto(SAMPLE.as_uri())
        page.locator("#add-coordinate").click()
        page.locator("#coordinate-kind-trigger").click()
        page.locator('[data-coordinate-kind="category-zone"]').click()
        page.locator(".category-binding-toggle").click()
        yield page
        browser.close()


def choose(root: Any, key: str, value: str) -> None:
    control = root.locator(f'[data-category-select="{key}"]')
    control.locator(".category-select-trigger").click()
    control.locator(f'[data-category-value="{value}"]').click()


def selected(root: Any, key: str) -> str:
    return root.locator(f'[data-category-select="{key}"] select').input_value()


def test_binding_defaults_to_global_with_editable_regions(sample_page: Any) -> None:
    card = sample_page.locator(".category-binding-card").first
    assert not card.locator(".category-binding-enabled").is_checked()
    assert card.locator(".category-binding-override-status").inner_text() == "独立设置"
    assert card.locator(".category-binding-options").is_hidden()
    assert card.locator('[data-binding-axis="x"]').first.is_editable()
    assert sample_page.locator("#category-scope-mode-trigger").is_visible()


def test_each_binding_has_its_own_options_and_unique_menu_ids(sample_page: Any) -> None:
    page = sample_page
    first = page.locator(".category-binding-card").first
    first.locator(".category-binding-enabled").check()
    choose(first, "scopeMode", "selection")
    choose(first, "recognitionMode", "container-item")
    choose(first, "storageMethod", "box")
    first.locator('[id$="category-ignore-support"]').check()
    first.locator('[id$="category-support-block"]').fill("minecraft:stone")
    page.locator("#add-category-binding").click()
    second = page.locator(".category-binding-card").nth(1)
    second.locator(".category-binding-enabled").check()
    assert selected(second, "scopeMode") == "single"
    assert selected(second, "recognitionMode") == "marker"
    assert selected(second, "storageMethod") == "bulk"
    assert page.locator("#category-storage-method").input_value() == "bulk"
    assert selected(first, "storageMethod") == "box"
    assert page.evaluate("""() => {
        const ids = Array.from(document.querySelectorAll('[id]'), node => node.id);
        return ids.length === new Set(ids).size;
    }""")


def test_first_enable_uses_current_global_settings(sample_page: Any) -> None:
    page = sample_page
    global_options = page.locator(".category-options").first
    choose(global_options, "storageMethod", "mixed")
    choose(global_options, "recognitionMode", "container-item")
    global_options.locator('[id$="category-bulk-stacks"]').fill("18")
    global_options.locator('[id$="category-specify-standing"]').check()
    global_options.locator('[id$="category-standing-x"]').fill("-120")
    card = page.locator(".category-binding-card").first
    card.locator(".category-binding-enabled").check()
    assert selected(card, "storageMethod") == "mixed"
    assert selected(card, "recognitionMode") == "container-item"
    assert card.locator('[id$="category-no-cache-row"]').is_visible()
    assert card.locator('[id$="category-bulk-stacks"]').input_value() == "18"
    assert card.locator('[id$="category-specify-standing"]').is_checked()
    assert card.locator('[id$="category-standing-x"]').input_value() == "-120"


def test_disabling_overrides_preserves_draft_and_binding_expansion(sample_page: Any) -> None:
    page = sample_page
    card = page.locator(".category-binding-card").first
    enabled = card.locator(".category-binding-enabled")
    enabled.check()
    assert card.locator(".category-binding-override-status").inner_text() == "独立设置"
    choose(card, "storageMethod", "box")
    choose(card, "noCacheLocation", "specified")
    card.locator('[id$="category-ignore-support"]').check()
    card.locator('[id$="category-support-block"]').fill("minecraft:stone")
    enabled.uncheck()
    assert card.locator(".category-binding-override-status").inner_text() == "独立设置"
    assert card.locator(".category-binding-options").is_hidden()
    assert card.get_attribute("data-collapsed") == "false"
    choose(page.locator(".category-options").first, "storageMethod", "mixed")
    enabled.check()
    assert selected(card, "storageMethod") == "box"
    assert selected(card, "noCacheLocation") == "specified"
    assert card.locator('[id$="category-support-block"]').input_value() == "minecraft:stone"


def test_delete_action_is_only_available_inside_an_expanded_binding(sample_page: Any) -> None:
    page = sample_page
    page.locator("#add-category-binding").click()
    card = page.locator(".category-binding-card").nth(1)
    assert card.locator(".category-binding-summary .category-binding-remove").count() == 0
    remove = card.locator(".category-binding-card-body .category-binding-remove")
    assert remove.is_visible()
    assert remove.is_enabled()
    card.locator(".category-binding-toggle").click()
    assert remove.is_hidden()
    card.locator(".category-binding-toggle").click()
    remove.click()
    assert page.locator(".category-binding-card").count() == 1
    assert page.locator(".category-binding-remove").is_disabled()


@pytest.mark.parametrize("enabled", [False, True])
def test_saved_and_copied_bindings_retain_independent_drafts(
    sample_page: Any, enabled: bool,
) -> None:
    page = sample_page
    card = page.locator(".category-binding-card").first
    card.locator(".category-binding-enabled").check()
    choose(card, "storageMethod", "box")
    card.locator(".category-binding-enabled").set_checked(enabled)
    page.locator('button[type="submit"]').filter(has_text="保存坐标").click()
    page.locator("#duplicate-coordinate").click()
    card = page.locator(".category-binding-card").first
    assert card.locator(".category-binding-enabled").is_checked() is enabled
    card.locator(".category-binding-toggle").click()
    card.locator(".category-binding-enabled").check()
    assert selected(card, "storageMethod") == "box"
    assert page.locator("#category-storage-method").input_value() == "bulk"


def test_fast_override_toggles_leave_the_last_requested_state_visible(sample_page: Any) -> None:
    page = sample_page
    page.emulate_media(reduced_motion="no-preference")
    card = page.locator(".category-binding-card").first
    card.locator(".category-binding-enabled").check()
    choose(card, "storageMethod", "box")
    card.evaluate("""async card => {
        const enabled = card.querySelector('.category-binding-enabled');
        for (const checked of [false, true, false, true]) {
            enabled.checked = checked;
            enabled.dispatchEvent(new Event('change', {bubbles: true}));
        }
        for (const animation of document.getAnimations()) animation.finish();
        await Promise.resolve();
    }""")
    assert card.locator(".category-binding-options").is_visible()
    assert card.locator(".category-binding-options").evaluate("node => !node.inert")
    assert selected(card, "storageMethod") == "box"


def test_binding_dropdown_keyboard_and_collapse_leave_global_untouched(sample_page: Any) -> None:
    page = sample_page
    card = page.locator(".category-binding-card").first
    card.locator(".category-binding-enabled").check()
    trigger = card.locator('[data-category-select="storageMethod"] .category-select-trigger')
    trigger.focus()
    page.keyboard.press("ArrowDown")
    page.keyboard.press("End")
    page.keyboard.press("Enter")
    assert selected(card, "storageMethod") == "box"
    assert trigger.evaluate("node => node === document.activeElement")
    trigger.click()
    card.locator(".category-binding-enabled").uncheck()
    assert card.locator(".category-select-menu:visible").count() == 0
    assert page.locator("#category-storage-method").input_value() == "bulk"


@pytest.mark.parametrize("width", [1552, 390])
@pytest.mark.parametrize("theme", ["green", "gray"])
def test_binding_submenus_are_not_clipped_or_offset(
    sample_page: Any, width: int, theme: str,
) -> None:
    page = sample_page
    page.set_viewport_size({"width": width, "height": 1253})
    page.locator(f'[data-theme-choice="{theme}"]').click()
    card = page.locator(".category-binding-card").first
    card.locator(".category-binding-enabled").check()
    choose(card, "storageMethod", "box")
    control = card.locator('[data-category-select="noCacheLocation"]')
    control.locator(".category-select-trigger").click()
    assert control.locator(".category-select-option").first.is_visible()
    assert control.evaluate("""wrap => {
        const trigger = wrap.querySelector('button').getBoundingClientRect();
        const menu = wrap.querySelector('[role=listbox]');
        const bounds = menu.getBoundingClientRect();
        const option = menu.querySelector('[role=option]').getBoundingClientRect();
        const hit = document.elementFromPoint(option.x + 10, option.y + 10);
        const above = menu.dataset.placement === 'above';
        const gap = above ? trigger.top - bounds.bottom : bounds.top - trigger.bottom;
        return Math.abs(gap - 6) < 1 && Math.abs(trigger.right - bounds.right) < 1
            && bounds.left >= 0 && bounds.right <= window.innerWidth && menu.contains(hit);
    }""")


def options_root(page: Any, scope: str) -> Any:
    if scope == "global":
        return page.locator(".category-options").first
    card = page.locator(".category-binding-card").first
    card.locator(".category-binding-enabled").check()
    return card


@pytest.mark.parametrize("scope", ["global", "binding"])
def test_standing_switch_keyboard_disclosure_and_coordinate_draft(
    sample_page: Any, scope: str,
) -> None:
    page = sample_page
    root = options_root(page, scope)
    enabled = root.locator('[id$="category-specify-standing"]')
    expand = root.locator('[id$="category-standing-expand"]')
    coordinates = root.locator('[id$="category-standing-row"]')
    assert not enabled.is_checked()
    assert coordinates.is_hidden()
    enabled.focus()
    page.keyboard.press("Space")
    assert coordinates.is_visible()
    assert enabled.get_attribute("aria-expanded") == "true"
    for axis, value in (("x", "-123"), ("y", "70"), ("z", "456")):
        coordinates.locator(f'[id$="category-standing-{axis}"]').fill(value)
    expand.click()
    assert enabled.is_checked()
    assert coordinates.is_hidden()
    assert enabled.get_attribute("aria-expanded") == "false"
    expand.click()
    assert coordinates.is_visible()
    enabled.uncheck()
    assert coordinates.is_hidden()
    assert coordinates.locator("input").first.is_disabled()
    enabled.check()
    assert coordinates.is_visible()
    for axis, value in (("x", "-123"), ("y", "70"), ("z", "456")):
        assert coordinates.locator(f'[id$="category-standing-{axis}"]').input_value() == value
    assert page.locator(".category-binding-card").first.get_attribute("data-collapsed") == "false"


@pytest.mark.parametrize("scope", ["global", "binding"])
def test_bulk_ratio_only_appears_in_mixed_storage_and_retains_its_value(
    sample_page: Any, scope: str,
) -> None:
    root = options_root(sample_page, scope)
    row = root.locator('[id$="category-bulk-ratio-row"]')
    stacks = row.locator("input")
    assert row.is_hidden()
    choose(root, "storageMethod", "mixed")
    assert row.is_visible()
    assert stacks.input_value() == "27"
    assert row.locator("span").inner_text() == "/54 组"
    stacks.fill("36")
    for method in ("box", "bulk"):
        choose(root, "storageMethod", method)
        assert row.is_hidden()
        assert stacks.is_disabled()
        choose(root, "storageMethod", "mixed")
        assert row.is_visible()
        assert stacks.input_value() == "36"


@pytest.mark.parametrize("enabled", [False, True])
def test_saved_copied_options_preserve_standing_and_bulk_ratio_drafts(
    sample_page: Any, enabled: bool,
) -> None:
    page = sample_page
    global_options = page.locator(".category-options").first
    choose(global_options, "storageMethod", "mixed")
    global_options.locator('[id$="category-bulk-stacks"]').fill("18")
    global_options.locator('[id$="category-specify-standing"]').check()
    global_options.locator('[id$="category-standing-x"]').fill("-120")
    card = options_root(page, "binding")
    card.locator('[id$="category-bulk-stacks"]').fill("9")
    card.locator('[id$="category-standing-x"]').fill("300")
    card.locator('[id$="category-specify-standing"]').set_checked(enabled)
    card.locator(".category-binding-enabled").set_checked(enabled)
    global_options.locator('[id$="category-specify-standing"]').set_checked(enabled)
    page.locator('button[type="submit"]').filter(has_text="保存坐标").click()
    assert page.locator("#editor-status").inner_text() == "已保存分类区。"
    page.locator("#duplicate-coordinate").click()
    global_options = page.locator(".category-options").first
    assert global_options.locator('[id$="category-bulk-stacks"]').input_value() == "18"
    assert global_options.locator('[id$="category-specify-standing"]').is_checked() is enabled
    global_options.locator('[id$="category-specify-standing"]').check()
    assert global_options.locator('[id$="category-standing-x"]').input_value() == "-120"
    card = page.locator(".category-binding-card").first
    assert card.locator(".category-binding-enabled").is_checked() is enabled
    card.locator(".category-binding-toggle").click()
    card.locator(".category-binding-enabled").check()
    assert card.locator('[id$="category-bulk-stacks"]').input_value() == "9"
    assert card.locator('[id$="category-specify-standing"]').is_checked() is enabled
    card.locator('[id$="category-specify-standing"]').check()
    assert card.locator('[id$="category-standing-x"]').input_value() == "300"


@pytest.mark.parametrize("scope", ["global", "binding"])
def test_empty_active_options_prevent_saving(sample_page: Any, scope: str) -> None:
    page = sample_page
    root = options_root(page, scope)
    choose(root, "storageMethod", "mixed")
    stacks = root.locator('[id$="category-bulk-stacks"]')
    stacks.fill("")
    save = page.locator('button[type="submit"]').filter(has_text="保存坐标")
    save.click()
    assert "散货占比请填写" in page.locator("#editor-status").inner_text()
    stacks.fill("27")
    root.locator('[id$="category-specify-standing"]').check()
    root.locator('[id$="category-standing-y"]').fill("")
    root.locator('[id$="category-standing-expand"]').click()
    save.click()
    assert "请填写站立位置" in page.locator("#editor-status").inner_text()


@pytest.mark.parametrize("scope", ["global", "binding"])
def test_fast_standing_toggles_keep_the_latest_state(sample_page: Any, scope: str) -> None:
    page = sample_page
    root = options_root(page, scope)
    page.emulate_media(reduced_motion="no-preference")
    root.locator('[id$="category-specify-standing"]').evaluate("""async toggle => {
        for (const checked of [true, false, true, false, true]) {
            toggle.checked = checked;
            toggle.dispatchEvent(new Event('change', {bubbles: true}));
        }
        for (const animation of document.getAnimations()) animation.finish();
        await Promise.resolve();
    }""")
    coordinates = root.locator('[id$="category-standing-row"]')
    assert coordinates.is_visible()
    assert coordinates.evaluate("node => !node.inert")
    assert coordinates.locator("input").first.is_editable()
