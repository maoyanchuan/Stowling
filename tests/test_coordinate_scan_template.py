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
    figure = page.locator(".classic-board svg")
    assert figure.is_visible()
    assert figure.locator("[data-piece]").count() == 25
    assert page.get_by_role("button", name="展开坐标草稿").count() == 0
    chest = figure.locator('[data-piece^="chest"]').first
    chest.click()
    assert "X 0 / Y 72 / Z 5…6" in page.locator(".classic-hint").inner_text()
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
    assert page.locator("#category-configuration").evaluate("node => node.matches(':disabled')")
    assert page.locator(".category-binding-name").is_disabled()
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


def test_applied_result_keeps_binding_defaults_and_remains_visible(scan_page: Any) -> None:
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
    page.locator("#save-coordinate").click()
    assert page.locator("#category-scan-map").is_visible()
    page.locator("#category-overall-first-z").fill("31")
    assert page.locator("#category-scan-map").get_attribute("data-stage") == "stale"
    assert page.locator("#save-coordinate").is_disabled()


def test_result_follows_theme_without_resetting_edits(scan_page: Any) -> None:
    page = scan_page
    page.locator('[data-coordinate-id="scanned-zone-demo"]').click()
    board = page.locator(".classic-board svg")
    chest = board.locator('[data-piece^="chest"]').first
    initial = chest.get_attribute("transform")
    chest.press("ArrowDown")
    moved = chest.get_attribute("transform")
    assert moved != initial
    color = "svg => getComputedStyle(svg).backgroundColor"
    before = board.evaluate(color)
    page.locator("#template-theme-toggle").click()
    assert board.evaluate(color) != before
    assert chest.get_attribute("transform") == moved
    page.locator(".classic-undo").click()
    assert chest.get_attribute("transform") == initial


def test_result_limits_the_visible_area_to_the_snapshot(scan_page: Any) -> None:
    page = scan_page
    choose_mode(page, "scan")
    page.locator("#category-overall-second-y").fill("-44")
    page.locator("#category-overall-second-z").fill("30")
    page.locator("#category-scan-start").click()
    assert page.locator(".classic-board [data-piece]:visible").count() == 0
    assert page.locator(".classic-grid").get_attribute("width") == "26"
    page.locator("#category-overall-second-z").fill("10000")
    page.locator("#category-scan-start").click()
    assert "局部" in page.locator(".classic-stage").inner_text()


def test_classic_layout_drags_snaps_and_keeps_applied_snapshot(scan_page: Any) -> None:
    page = scan_page
    choose_mode(page, "scan")
    figure = page.locator(".classic-slice")
    assert figure.is_hidden()
    page.locator("#category-scan-start").click()
    chest = figure.locator('[data-piece^="chest"]').first
    original = chest.get_attribute("transform")
    chest.scroll_into_view_if_needed()
    hit = chest.locator("rect").bounding_box()
    assert hit is not None
    cell = hit["height"]
    x, y = hit["x"] + cell, hit["y"] + cell / 2
    page.mouse.move(x, y)
    page.mouse.down()
    page.mouse.move(x, y + cell * 1.2)
    page.mouse.up()
    moved = chest.get_attribute("transform")
    assert moved == "translate(130 52)"
    page.locator("#category-scan-apply").click()
    page.locator("#category-scan-start").click()
    assert chest.get_attribute("transform") == original
    page.locator("#category-scan-discard").click()
    assert chest.get_attribute("transform") == moved
    page.locator("#save-coordinate").click()
    page.locator("#duplicate-coordinate").click()
    chest.press("ArrowDown")
    assert chest.get_attribute("transform") != moved
    page.locator('[data-coordinate-id="custom-rules-demo"]').click()
    assert chest.get_attribute("transform") == moved
    page.locator("#category-overall-first-y").fill("-46")
    assert chest.get_attribute("aria-disabled") == "true"
    chest.dispatch_event("keydown", {"key": "ArrowDown"})
    assert chest.get_attribute("transform") == moved


def test_single_chests_merge_split_move_and_undo(scan_page: Any) -> None:
    page = scan_page
    page.locator('[data-coordinate-id="scanned-zone-demo"]').click()
    add = page.get_by_role("button", name="添加小箱子")
    singles = page.locator('.classic-board [data-kind="single"]')
    double = page.locator('.classic-board [data-kind="chest"][transform="translate(0 0)"]')
    add.click()
    assert singles.count() == 1
    add.click()
    assert double.count() == 1
    assert singles.count() == 0
    double.click(button="right")
    assert singles.count() == 2
    left = page.locator('[data-kind="single"][transform="translate(0 0)"]')
    left.press("ArrowDown")
    assert singles.count() == 2
    moved = page.locator('[data-kind="single"][transform="translate(0 26)"]')
    moved.press("ArrowUp")
    assert double.count() == 1
    page.locator(".classic-undo").click()
    assert singles.count() == 2
    assert moved.count() == 1


def test_split_builtin_double_survives_apply_save_and_reload_selection(scan_page: Any) -> None:
    page = scan_page
    choose_mode(page, "scan")
    page.locator("#category-scan-start").click()
    chest = page.locator('.classic-board [data-kind="chest"]:visible').first
    chest.click(button="right")
    singles = page.locator('.classic-board [data-kind="single"]:visible')
    assert singles.count() == 2
    page.locator("#category-scan-apply").click()
    page.locator("#save-coordinate").click()
    page.locator('[data-coordinate-id="home"]').click()
    page.locator('[data-coordinate-id="custom-rules-demo"]').click()
    assert singles.count() == 2
    page.locator("#category-scan-start").click()
    assert singles.count() == 0
    page.locator("#category-scan-discard").click()
    assert singles.count() == 2
    page.locator("#category-overall-first-y").fill("-46")
    assert page.get_by_role("button", name="添加小箱子").is_disabled()
    chest.dispatch_event("contextmenu")
    assert singles.count() == 2
