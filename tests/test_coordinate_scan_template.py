from collections.abc import Iterator
from pathlib import Path
from typing import Any

import pytest


TEMPLATE = Path(__file__).resolve().parents[1] / "docs/ui/settings-standard.html"


@pytest.fixture
def scan_page() -> Iterator[Any]:
    playwright = pytest.importorskip("playwright.sync_api")
    with playwright.sync_playwright() as runtime:
        browser = runtime.chromium.launch()
        page = browser.new_page(viewport={"width": 1440, "height": 1100})
        page.emulate_media(reduced_motion="reduce")
        errors: list[str] = []
        requests: list[str] = []
        page.on("pageerror", lambda error: errors.append(str(error)))
        page.on("request", lambda request: requests.append(request.url))
        page.goto(TEMPLATE.as_uri() + "#coordinates")
        page.locator('[data-coordinate-id="custom-rules-demo"]').click()
        yield page
        assert errors == []
        assert not any(url.startswith(("http://", "https://")) for url in requests)
        browser.close()


def choose_mode(page: Any, mode: str) -> None:
    page.locator("#category-setup-trigger").click()
    page.locator(f'#category-setup-menu [data-category-value="{mode}"]').click()


def test_pre_scanned_location_opens_with_a_complete_offline_snapshot(scan_page: Any) -> None:
    page = scan_page
    page.reload()
    assert page.locator("#coordinate-name").input_value() == "已扫描分类区（示例）"
    assert page.locator('[data-coordinate-id="scanned-zone-demo"]').get_attribute(
        "aria-current",
    ) == "true"
    assert page.locator("#category-setup-mode").input_value() == "scan"
    assert page.locator("#category-scan-map").is_visible()
    assert page.locator("#category-scan-map").get_attribute("data-stage") == "applied"
    assert page.locator("#category-scan-preview").is_hidden()
    figure = page.locator(".classic-slice img")
    assert figure.is_visible()
    assert figure.evaluate("img => img.complete && img.naturalWidth > 0")
    assert page.locator(".slice-tools").is_hidden()
    page.get_by_role("button", name="展开坐标草稿").click()
    assert page.locator(".slice-cell").count() == 60
    assert "X = 0" in page.locator(".slice-subtitle").inner_text()
    assert page.locator("#save-coordinate").is_enabled()
    page.locator('.slice-cell[data-roles="standing"]').click()
    assert "X 0 / Y 64 / Z 6" in page.locator(".slice-selection").inner_text()
    page.locator("#save-coordinate").click()
    page.locator('[data-coordinate-id="home"]').click()
    page.locator('[data-coordinate-id="scanned-zone-demo"]').click()
    assert page.locator("#category-scan-map").is_visible()
    page.reload()
    assert page.locator("#category-scan-map").get_attribute("data-stage") == "applied"


def apply_example(page: Any) -> None:
    choose_mode(page, "scan")
    page.locator("#category-scan-start").click()
    page.locator("#category-scan-apply").click()


def test_scan_only_requires_the_total_selection_and_preserves_manual_drafts(scan_page: Any) -> None:
    page = scan_page
    page.locator(".category-binding-name").fill("保留的手动组")
    choose_mode(page, "scan")
    assert page.locator("#category-overall-first-x").is_editable()
    assert page.locator("#category-details").is_hidden()
    assert page.locator("#category-configuration").is_disabled()
    assert page.locator("#save-coordinate").is_disabled()
    assert page.locator("#category-scan-panel").is_visible()
    assert "离线 UI 示例" in page.locator("#category-scan-panel").inner_text()
    assert not page.locator("#category-details").evaluate("""node => {
        node.querySelector('input').focus();
        return node.contains(document.activeElement);
    }""")
    choose_mode(page, "manual")
    assert page.locator("#category-scan-panel").is_hidden()
    assert page.locator(".category-binding-name").input_value() == "保留的手动组"
    assert page.locator("#save-coordinate").is_enabled()


def test_preview_can_be_discarded_without_replacing_configuration(scan_page: Any) -> None:
    page = scan_page
    page.locator(".category-binding-name").fill("尚未替换")
    choose_mode(page, "scan")
    page.locator("#category-scan-start").click()
    assert page.locator("#category-scan-preview").is_visible()
    assert "尚未应用" in page.locator("#category-scan-status").inner_text()
    assert page.locator(".category-binding-name").input_value() == "尚未替换"
    page.locator("#category-scan-discard").click()
    assert page.locator("#category-scan-preview").is_hidden()
    assert page.locator("#category-scan-start").evaluate("node => node === document.activeElement")
    choose_mode(page, "manual")
    assert page.locator(".category-binding-name").input_value() == "尚未替换"


def test_empty_or_changed_selection_cannot_apply_a_stale_preview(scan_page: Any) -> None:
    page = scan_page
    choose_mode(page, "scan")
    page.locator("#category-overall-first-x").fill("")
    assert page.locator("#category-scan-start").is_disabled()
    page.locator("#category-overall-first-x").fill("40")
    page.locator("#category-scan-start").click()
    page.locator("#category-overall-first-x").fill("41")
    assert page.locator("#category-scan-apply").is_disabled()
    assert "预览已过期" in page.locator("#category-scan-status").inner_text()
    page.locator("#category-scan-start").click()
    assert page.locator("#category-scan-apply").is_enabled()
    assert "41, -44, 30" in page.locator("#category-scan-range").inner_text()


def test_applied_example_can_be_edited_saved_copied_and_reopened(scan_page: Any) -> None:
    page = scan_page
    apply_example(page)
    assert page.locator("#category-scan-preview").is_hidden()
    assert page.locator("#category-generated-toggle").is_visible()
    assert page.locator("#category-details").is_hidden()
    assert page.locator("#save-coordinate").is_enabled()
    page.locator("#category-generated-toggle").click()
    assert page.locator("#category-scope-mode").input_value() == "selection"
    assert page.locator("#category-standing-enabled").is_checked()
    page.locator(".category-binding-name").fill("扫描后微调")
    page.locator("#category-generated-toggle").click()
    page.locator("#save-coordinate").click()
    assert "已保存分类区" in page.locator("#editor-status").inner_text()
    page.locator("#duplicate-coordinate").click()
    assert page.locator("#category-setup-mode").input_value() == "scan"
    assert page.locator("#save-coordinate").is_enabled()
    page.locator("#category-generated-toggle").click()
    assert page.locator(".category-binding-name").input_value() == "扫描后微调"
    page.locator("#save-coordinate").click()
    copied_name = page.locator("#coordinate-name").input_value()
    page.locator('[data-coordinate-id="home"]').click()
    assert page.locator("#save-coordinate").is_enabled()
    page.locator(".coordinate-item").filter(has_text=copied_name).click()
    assert page.locator("#category-setup-mode").input_value() == "scan"
    assert page.locator(".category-binding-name").input_value() == "扫描后微调"


def test_applied_configuration_requires_rescan_after_bounds_change(scan_page: Any) -> None:
    page = scan_page
    apply_example(page)
    page.locator("#category-overall-first-x").fill("42")
    assert page.locator("#save-coordinate").is_disabled()
    assert "已有配置待更新" in page.locator("#category-scan-status").inner_text()
    page.locator("#category-scan-start").click()
    page.locator("#category-scan-apply").click()
    assert page.locator("#save-coordinate").is_enabled()
    page.locator("#category-overall-first-x").fill("43")
    choose_mode(page, "manual")
    assert page.locator("#save-coordinate").is_enabled()


def test_mode_uses_shared_keyboard_controls_and_preserves_state_across_tabs(scan_page: Any) -> None:
    page = scan_page
    trigger = page.locator("#category-setup-trigger")
    trigger.focus()
    page.keyboard.press("ArrowDown")
    page.keyboard.press("End")
    page.keyboard.press("Enter")
    assert page.locator("#category-setup-mode").input_value() == "scan"
    assert trigger.evaluate("node => node === document.activeElement")
    page.locator("#category-scan-start").click()
    page.get_by_role("tab", name="任务", exact=True).click()
    page.get_by_role("tab", name="坐标", exact=True).click()
    assert page.locator("#category-scan-preview").is_visible()
    assert page.locator("#save-coordinate").is_disabled()


@pytest.mark.parametrize("theme", ["green", "gray"])
@pytest.mark.parametrize("width", [390, 1440])
def test_scan_preview_adapts_to_both_themes_and_narrow_screens(
    scan_page: Any, theme: str, width: int,
) -> None:
    page = scan_page
    page.set_viewport_size({"width": width, "height": 1100})
    page.evaluate("theme => document.documentElement.dataset.theme = theme", theme)
    choose_mode(page, "scan")
    page.locator("#category-scan-start").click()
    assert page.evaluate("document.documentElement.scrollWidth <= innerWidth")
    for selector in ["#category-scan-start", "#category-scan-range", "#category-scan-apply"]:
        bounds = page.locator(selector).bounding_box()
        assert bounds is not None
        assert bounds["x"] >= 0
        assert bounds["x"] + bounds["width"] <= width


def test_slice_plan_explains_each_role_and_supports_keyboard_selection(scan_page: Any) -> None:
    page = scan_page
    choose_mode(page, "scan")
    assert page.locator("#category-scan-map").is_hidden()
    page.locator("#category-scan-start").click()
    assert page.locator(".slice-cell").count() == 60
    for role in ["storage", "marker", "standing", "support"]:
        assert page.locator(f'.slice-cell[data-roles~="{role}"]').count() > 0
    standing = page.locator('.slice-cell[data-roles="standing"]')
    page.get_by_role("button", name="展开坐标草稿").click()
    standing.click()
    assert "X 40 / Y -44 / Z 36" in page.locator(".slice-selection").inner_text()
    page.keyboard.press("ArrowUp")
    assert "空位" in page.locator(".slice-selection").inner_text()
    assert "Y -43 / Z 36" in page.locator(".slice-selection").inner_text()
    assert page.locator('.slice-cell[tabindex="0"]').count() == 1
    assert page.locator('.slice-cell[aria-pressed="true"]').count() == 1


def test_applied_slice_matches_the_generated_binding_and_remains_visible(scan_page: Any) -> None:
    page = scan_page
    apply_example(page)
    assert page.locator("#category-scan-map").is_visible()
    assert page.locator("#category-scan-map").get_attribute("data-stage") == "applied"
    page.locator("#category-generated-toggle").click()
    page.locator(".category-binding-toggle").click()
    cases = [("storage", "-43", "-42", "33"), ("marker", "-41", "-40", "39")]
    for region, first_y, second_y, z in cases:
        for corner, y in [("first", first_y), ("second", second_y)]:
            prefix = f'[data-binding-region="{region}"][data-binding-corner="{corner}"]'
            assert page.locator(prefix + '[data-binding-axis="y"]').input_value() == y
            assert page.locator(prefix + '[data-binding-axis="z"]').input_value() == z
            assert page.locator(f'.slice-cell[data-roles~="{region}"]'
                                f'[data-y="{y}"][data-z="{z}"]').count() == 1
    page.locator("#save-coordinate").click()
    assert page.locator("#category-scan-map").is_visible()
    page.locator("#category-overall-first-z").fill("31")
    assert page.locator("#category-scan-map").get_attribute("data-stage") == "stale"
    assert page.locator("#save-coordinate").is_disabled()


def test_slice_explicitly_shows_overlap_and_limits_large_selections(scan_page: Any) -> None:
    page = scan_page
    choose_mode(page, "scan")
    page.locator("#category-overall-second-y").fill("-44")
    page.locator("#category-overall-second-z").fill("30")
    page.locator("#category-scan-start").click()
    assert page.locator(".slice-cell").count() == 1
    assert page.locator(".slice-cell").inner_text() == ""
    assert page.locator(".slice-cell .slice-tile").evaluate_all(
        "tiles => tiles.map(tile => tile.dataset.role)",
    ) == ["storage", "marker", "standing"]
    page.locator("#category-overall-second-y").fill("1000")
    page.locator("#category-overall-second-z").fill("10000")
    page.locator("#category-scan-start").click()
    assert page.locator(".slice-cell").count() == 96
    assert "仅展示单片局部" in page.locator(".slice-note").inner_text()


def test_color_editor_paints_clears_and_undoes_without_cell_text(scan_page: Any) -> None:
    page = scan_page
    page.locator('[data-coordinate-id="scanned-zone-demo"]').click()
    page.get_by_role("button", name="展开坐标草稿").click()
    target = page.locator('.slice-cell[data-y="69"][data-z="1"]')
    assert page.locator(".slice-cell").all_text_contents() == [""] * 60
    page.locator('[data-tool="storage"]').click()
    target.click()
    assert target.get_attribute("data-roles") == "storage"
    assert "储存区" in target.get_attribute("aria-label")
    assert "已手动调整" in page.locator(".slice-stage").inner_text()
    page.locator('[data-tool="erase"]').click()
    target.click()
    assert target.get_attribute("data-roles") == ""
    page.locator(".slice-undo").click()
    assert target.get_attribute("data-roles") == "storage"
    page.locator(".slice-undo").click()
    assert target.get_attribute("data-roles") == ""
    assert page.locator(".slice-undo").is_disabled()


def test_color_editor_saves_and_copies_independent_structure_drafts(scan_page: Any) -> None:
    page = scan_page
    page.locator('[data-coordinate-id="scanned-zone-demo"]').click()
    page.get_by_role("button", name="展开坐标草稿").click()
    target = page.locator('.slice-cell[data-y="69"][data-z="1"]')
    page.locator('[data-tool="storage"]').click()
    target.click()
    page.locator("#save-coordinate").click()
    assert target.get_attribute("data-roles") == "storage"
    page.locator("#duplicate-coordinate").click()
    assert target.get_attribute("data-roles") == "storage"
    page.locator('[data-tool="marker"]').click()
    target.click()
    page.locator("#save-coordinate").click()
    page.locator('[data-coordinate-id="scanned-zone-demo"]').click()
    assert target.get_attribute("data-roles") == "storage"
    page.locator(".coordinate-item").filter(has_text="已扫描分类区（示例） 副本").click()
    assert target.get_attribute("data-roles") == "marker"
    assert page.locator("#category-setup-mode").input_value() == "scan"


def test_preview_edit_discard_restores_applied_structure_and_rescan_starts_fresh(
    scan_page: Any,
) -> None:
    page = scan_page
    page.locator('[data-coordinate-id="scanned-zone-demo"]').click()
    page.get_by_role("button", name="展开坐标草稿").click()
    target = page.locator('.slice-cell[data-y="69"][data-z="1"]')
    page.locator('[data-tool="storage"]').click()
    target.click()
    page.locator("#category-scan-start").click()
    assert target.get_attribute("data-roles") == ""
    page.locator('[data-tool="marker"]').click()
    target.click()
    page.locator("#category-scan-discard").click()
    assert target.get_attribute("data-roles") == "storage"
    page.locator("#category-scan-start").click()
    page.locator('[data-tool="support"]').click()
    target.click()
    page.locator("#category-scan-apply").click()
    assert target.get_attribute("data-roles") == "support"
    page.locator("#category-overall-second-z").fill("10")
    assert page.locator('[data-tool="storage"]').is_disabled()
    assert page.locator(".slice-undo").is_disabled()
    assert page.locator("#save-coordinate").is_disabled()


def test_editor_keyboard_activation_paints_but_arrow_keys_only_select(scan_page: Any) -> None:
    page = scan_page
    page.locator('[data-coordinate-id="scanned-zone-demo"]').click()
    page.get_by_role("button", name="展开坐标草稿").click()
    target = page.locator('.slice-cell[data-y="69"][data-z="1"]')
    page.locator('[data-tool="standing"]').click()
    target.focus()
    page.keyboard.press("Space")
    assert target.get_attribute("data-roles") == "standing"
    page.keyboard.press("ArrowRight")
    neighbor = page.locator('.slice-cell[data-y="69"][data-z="2"]')
    assert neighbor.get_attribute("data-roles") == ""
    page.keyboard.press("Enter")
    assert neighbor.get_attribute("data-roles") == "standing"
    page.locator('[data-tool="view"]').click()
    target.click()
    assert target.get_attribute("data-roles") == "standing"
