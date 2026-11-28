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
    assert "X 0 / Y 72 / Z 5…6" in chest.get_attribute("aria-label")
    assert " / Y " not in page.locator(".classic-hint").inner_text()
    assert figure.locator("text").count() == 0
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
    assert page.locator(".classic-columns").input_value() == "9971"
    assert page.locator(".classic-grid").get_attribute("width") == str(9971 * 26)


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
    add = page.get_by_role("button", name="箱子", exact=True)
    grid = page.locator(".classic-grid")
    singles = page.locator('.classic-board [data-kind="single"]')
    double = page.locator('.classic-board [data-kind="chest"][transform="translate(0 0)"]')
    add.click()
    grid.press("Enter")
    assert singles.count() == 1
    grid.press("ArrowRight")
    grid.press("Enter")
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
    assert page.get_by_role("button", name="箱子", exact=True).is_disabled()
    chest.dispatch_event("contextmenu")
    assert singles.count() == 2


@pytest.mark.parametrize(
    ("label", "kind"),
    [("木桶", "barrel"), ("半砖", "slab"), ("楼梯", "stairs"),
     ("玻璃", "glass"), ("黑紫块", "checker"), ("石英块", "quartz")],
)
def test_palette_places_selected_block_at_clicked_cell(
    scan_page: Any, label: str, kind: str,
) -> None:
    page = scan_page
    page.locator('[data-coordinate-id="scanned-zone-demo"]').click()
    blocks = page.locator(f'.classic-board [data-kind="{kind}"]')
    initial = blocks.count()
    page.get_by_role("button", name=label, exact=True).click()
    grid = page.locator(".classic-grid")
    grid.scroll_into_view_if_needed()
    bounds = grid.bounding_box()
    assert bounds is not None
    cell = bounds["width"] / 15
    page.mouse.click(bounds["x"] + 6.5 * cell, bounds["y"] + 4.5 * cell)
    placed = page.locator(f'[data-kind="{kind}"][transform="translate(156 104)"]')
    assert placed.count() == 1
    placed.click()
    assert blocks.count() == initial + 1
    page.locator(".classic-undo").click()
    assert placed.count() == 0


def test_palette_drag_rotates_stairs_and_preserves_orientation(scan_page: Any) -> None:
    page = scan_page
    choose_mode(page, "scan")
    page.locator("#category-scan-start").click()
    grid = page.locator(".classic-grid")
    grid.scroll_into_view_if_needed()
    bounds = grid.bounding_box()
    assert bounds is not None
    cell = bounds["width"] / int(grid.get_attribute("width")) * 26
    palette = page.locator('.classic-palette [data-block="stairs"]')
    palette.drag_to(grid, target_position={"x": cell / 2, "y": cell / 2})
    stairs = page.locator('.classic-board [data-kind="stairs"]:visible')
    assert stairs.count() == 1
    palette.drag_to(stairs)
    assert stairs.count() == 1
    art = stairs.locator("use")
    for angle in (90, 180, 270, 0, 90):
        stairs.click(button="right")
        assert art.get_attribute("transform") == f"rotate({angle} 13 13)"
    stairs.press("ArrowRight")
    assert art.get_attribute("transform") == "rotate(90 13 13)"
    page.locator("#category-scan-apply").click()
    page.locator("#save-coordinate").click()
    page.locator('[data-coordinate-id="home"]').click()
    page.locator('[data-coordinate-id="custom-rules-demo"]').click()
    assert art.get_attribute("transform") == "rotate(90 13 13)"
    stairs.press("Delete")
    assert stairs.count() == 0
    page.locator(".classic-undo").click()
    assert art.get_attribute("transform") == "rotate(90 13 13)"


def test_delete_existing_glass_restores_on_undo_and_survives_save(scan_page: Any) -> None:
    page = scan_page
    page.locator('[data-coordinate-id="scanned-zone-demo"]').click()
    glass = page.locator('.classic-board [data-kind="glass"]')
    remove = page.get_by_role("button", name="删除方块")
    glass.first.click()
    remove.click()
    assert glass.count() == 2
    assert remove.is_disabled()
    page.locator(".classic-undo").click()
    assert glass.count() == 3
    glass.first.press("Delete")
    page.locator("#save-coordinate").click()
    page.locator('[data-coordinate-id="home"]').click()
    page.locator('[data-coordinate-id="scanned-zone-demo"]').click()
    assert glass.count() == 2


def test_default_quartz_stairs_face_the_glass(scan_page: Any) -> None:
    page = scan_page
    page.locator('[data-coordinate-id="scanned-zone-demo"]').click()
    stairs = page.locator('.classic-board [data-kind="stairs"]')
    assert stairs.count() == 2
    assert page.locator('.classic-board [data-kind="low"]').count() == 0
    for x, angle in [(5, 0), (9, 90)]:
        leg = page.locator(f'[data-kind="stairs"][transform="translate({x * 26} 182)"]')
        assert leg.locator("use").get_attribute("transform") == f"rotate({angle} 13 13)"
    for x in (6, 7, 8):
        glass = page.locator(f'[data-kind="glass"][transform="translate({x * 26} 182)"]')
        assert glass.count() == 1


def test_slab_toggles_in_place_and_keeps_upper_half_after_save(scan_page: Any) -> None:
    page = scan_page
    page.locator('[data-coordinate-id="scanned-zone-demo"]').click()
    page.get_by_role("button", name="半砖", exact=True).click()
    page.locator(".classic-grid").press("Enter")
    slab = page.locator('.classic-board [data-kind="slab"]')
    art = slab.locator("use")
    cell = slab.get_attribute("transform")
    assert art.get_attribute("y") == "0"
    slab.click(button="right")
    assert art.get_attribute("y") == "-13"
    assert slab.get_attribute("transform") == cell
    slab.click(button="right")
    assert art.get_attribute("y") == "0"
    page.locator(".classic-undo").click()
    assert "上半砖" in slab.get_attribute("aria-label")
    slab.press("ArrowRight")
    assert slab.get_attribute("transform") != cell
    page.locator("#save-coordinate").click()
    page.locator('[data-coordinate-id="home"]').click()
    page.locator('[data-coordinate-id="scanned-zone-demo"]').click()
    assert art.get_attribute("y") == "-13"


def test_resize_preserves_hidden_blocks_coordinates_and_undo(scan_page: Any) -> None:
    page = scan_page
    page.locator('[data-coordinate-id="scanned-zone-demo"]').click()
    columns = page.get_by_label("列数", exact=True)
    rows = page.get_by_label("行数", exact=True)
    resize = page.get_by_role("button", name="调整画布")
    glass = page.locator('.classic-board [data-kind="glass"]').first
    label = glass.get_attribute("aria-label")
    columns.fill("5")
    rows.fill("4")
    resize.click()
    assert glass.is_hidden()
    assert "画布外" in page.locator(".classic-hint").inner_text()
    page.locator(".classic-undo").click()
    assert columns.input_value() == "15"
    assert rows.input_value() == "10"
    assert glass.is_visible()
    columns.fill("24")
    rows.fill("16")
    rows.press("Enter")
    assert page.locator(".classic-grid").get_attribute("width") == str(24 * 26)
    assert page.locator(".classic-grid").get_attribute("height") == str(16 * 26)
    assert glass.get_attribute("aria-label") == label
    page.locator("#save-coordinate").click()
    page.locator('[data-coordinate-id="home"]').click()
    page.locator('[data-coordinate-id="scanned-zone-demo"]').click()
    assert columns.input_value() == "24"
    assert rows.input_value() == "16"
    page.locator("#category-scan-start").click()
    assert columns.input_value() == "15"
    page.locator("#category-scan-discard").click()
    assert columns.input_value() == "24"
    rows.fill("0")
    resize.click()
    assert page.locator(".classic-grid").get_attribute("height") == str(16 * 26)
    assert "正整数" in page.locator(".classic-hint").inner_text()


def mark_cell(page: Any, col: float, row: float, end: tuple[float, float] | None = None) -> None:
    grid = page.locator(".classic-grid")
    grid.scroll_into_view_if_needed()
    bounds = grid.bounding_box()
    assert bounds is not None
    size = bounds["width"] * 26 / float(grid.get_attribute("width"))
    x, y = bounds["x"], bounds["y"]
    page.mouse.move(x + col * size, y + row * size)
    page.mouse.down()
    if end:
        page.mouse.move(x + end[0] * size, y + end[1] * size, steps=3)
    page.mouse.up()


def test_annotation_form_round_trip_and_shared_station(scan_page: Any) -> None:
    page = scan_page
    page.locator('[data-coordinate-id="scanned-zone-demo"]').click()
    page.get_by_role("button", name="区域与站位", exact=True).click()
    sidebar = page.locator(".area-sidebar")
    sidebar.get_by_role("button", name="＋ 绑定组").click()
    sidebar.get_by_label("当前绑定组名称").fill("上层双箱")
    mark_cell(page, 4.5, 1.5)
    sidebar.locator('[data-area-tool="storage"]').click()
    mark_cell(page, 5.5, 1.5)
    card = page.locator(".category-binding-card").last
    fields = card.locator('[data-binding-region="storage"][data-binding-axis="z"]')
    assert [field.input_value() for field in fields.all()] == ["5", "6"]
    marker = card.locator('[data-binding-region="marker"][data-binding-axis="x"]')
    assert [field.input_value() for field in marker.all()] == ["0", "0"]
    sidebar.get_by_role("button", name="＋ 点选站位").click()
    mark_cell(page, 7.5, 7.5)
    sidebar.get_by_role("checkbox").first.check()
    assert sidebar.get_by_role("checkbox").last.is_checked()
    page.locator("#category-generated-toggle").click()
    card.locator(".category-binding-toggle").click()
    assert card.locator(".category-binding-name").input_value() == "上层双箱"
    fields.last.fill("8")
    group_id = card.get_attribute("data-binding-id")
    rect = page.locator(f'[data-area="storage"][data-group="{group_id}"] > rect').first
    assert rect.get_attribute("width") == str(4 * 26 - 2)
    page.locator("#save-coordinate").click()
    page.locator('[data-coordinate-id="home"]').click()
    page.locator('[data-coordinate-id="scanned-zone-demo"]').click()
    page.get_by_role("button", name="区域与站位", exact=True).click()
    assert rect.get_attribute("width") == str(4 * 26 - 2)
    sidebar.get_by_role("button", name="站位 2", exact=True).click()
    assert all(check.is_checked() for check in sidebar.get_by_role("checkbox").all())


def test_pending_annotations_are_isolated_and_stale_regions_are_read_only(scan_page: Any) -> None:
    page = scan_page
    page.locator('[data-coordinate-id="scanned-zone-demo"]').click()
    page.locator("#category-scan-start").click()
    page.get_by_role("button", name="区域与站位", exact=True).click()
    sidebar = page.locator(".area-sidebar")
    sidebar.get_by_role("button", name="＋ 绑定组").click()
    assert page.locator("#category-scan-apply").is_disabled()
    mark_cell(page, 2.5, 2.5, (3.5, 3.5))
    sidebar.locator('[data-area-tool="storage"]').click()
    mark_cell(page, 5.5, 1.5)
    assert page.locator("#category-scan-apply").is_enabled()
    assert page.locator(".category-binding-card").count() == 1
    page.locator("#category-scan-discard").click()
    assert sidebar.locator("[data-group-choice]").count() == 1
    page.locator("#category-scan-start").click()
    sidebar.get_by_label("当前绑定组名称").fill("预览组")
    page.locator("#category-scan-apply").click()
    assert page.locator(".category-binding-name").input_value() == "预览组"
    page.locator("#category-overall-first-z").fill("1")
    assert sidebar.get_by_role("button", name="＋ 绑定组").is_disabled()
