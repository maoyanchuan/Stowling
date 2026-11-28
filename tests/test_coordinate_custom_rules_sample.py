from collections.abc import Iterator
from pathlib import Path
from typing import Any

import pytest


SAMPLE = Path(__file__).resolve().parents[1] / "docs/ui/settings-standard.html"


@pytest.fixture
def rules_page() -> Iterator[Any]:
    playwright = pytest.importorskip("playwright.sync_api")
    with playwright.sync_playwright() as runtime:
        try:
            browser = runtime.chromium.launch()
        except playwright.Error as error:
            pytest.skip(f"Playwright Chromium is required: {error}")
        page = browser.new_page(viewport={"width": 1552, "height": 1253})
        page.emulate_media(reduced_motion="reduce")
        page.goto(SAMPLE.as_uri() + "#coordinates")
        yield page
        browser.close()


def options_root(page: Any, scope: str = "global") -> Any:
    if scope == "global":
        root = page.locator(".category-options").first
    else:
        card = page.locator(".category-binding-card").first
        card.locator(".category-binding-toggle").click()
        card.locator(".category-binding-enabled").check()
        root = card.locator(".category-options")
    open_settings(root)
    return root


def field(root: Any, key: str) -> Any:
    return root.locator(f'[id$="category-{key}"]')


def open_settings(root: Any) -> None:
    if field(root, "recognition-expand").get_attribute("aria-expanded") == "false":
        field(root, "recognition-expand").click()
    if (field(root, "multi-bindings").is_checked()
            and field(root, "bindings-expand").get_attribute("aria-expanded") == "false"):
        field(root, "bindings-expand").click()


def open_rule(rule: Any) -> None:
    toggle = rule.locator(".category-rule-toggle")
    if toggle.get_attribute("aria-expanded") == "false":
        toggle.click()


def choose_target(rule: Any, value: str) -> None:
    open_rule(rule)
    control = rule.locator('[data-category-select="bindingTarget"]')
    control.locator(".category-select-trigger").click()
    control.locator(f'[data-category-value="{value}"]').click()


def save(page: Any) -> None:
    page.locator('button[type="submit"]').filter(has_text="保存坐标").click()


def test_demo_exposes_support_exclusion_and_both_routing_examples(rules_page: Any) -> None:
    page = rules_page
    assert page.locator("#coordinate-name").input_value() == "花簇与幽匿分类（示例）"
    root = options_root(page)
    assert field(root, "ignore-positions").is_checked()
    assert field(root, "positions-panel").is_visible()
    position = field(root, "positions-list").locator("input")
    assert [node.input_value() for node in position.all()] == ["44", "-41", "39"]
    rules = field(root, "rules-list").locator(".category-rule-form")
    assert rules.count() == 2
    assert rules.first.locator(".category-rule-count").inner_text() == "5 项物品"
    assert rules.first.locator(".category-rule-body").is_hidden()
    assert rules.last.locator(".category-rule-body").is_hidden()
    assert rules.first.locator('[data-rule-field="markerId"]').input_value() == "minecraft:sculk"
    assert "minecraft:sculk_shrieker" in rules.first.locator("textarea").input_value()
    assert rules.last.locator('[data-rule-field="targetType"]').input_value() == "container"
    assert [node.input_value() for node in rules.last.locator(
        '[data-position-axis]',
    ).all()] == ["56", "-44", "33"]


@pytest.mark.parametrize("scope", ["global", "binding"])
@pytest.mark.parametrize("width", [1552, 390])
@pytest.mark.parametrize("theme", ["green", "gray"])
def test_controls_keep_drafts_and_fit_both_themes_and_widths(
    rules_page: Any, scope: str, width: int, theme: str,
) -> None:
    page = rules_page
    page.set_viewport_size({"width": width, "height": 1253})
    page.locator(f'[data-theme-choice="{theme}"]').click()
    root = options_root(page, scope)
    panel = field(root, "bindings-panel")
    toggle = field(root, "multi-bindings")
    expand = field(root, "bindings-expand")
    open_rule(panel.locator(".category-rule-form").first)
    draft = "minecraft:sculk_sensor\nminecraft:sculk_catalyst\nminecraft:sculk_shrieker"
    items = panel.locator("textarea").first
    items.fill("minecraft:sculk_sensor")
    items.press("End")
    items.press("Enter")
    page.keyboard.insert_text(draft.split("\n", 1)[1])
    assert items.input_value() == draft
    assert page.locator("#editor-status").inner_text() != "已保存分类区。"
    expand.click()
    assert toggle.is_checked()
    assert panel.is_hidden()
    expand.click()
    toggle.uncheck()
    assert items.is_disabled()
    toggle.check()
    assert items.input_value() == draft
    for control in panel.locator("input:visible, textarea:visible, button:visible").all():
        bounds = control.bounding_box()
        assert bounds is not None
        assert bounds["x"] >= 0 and bounds["x"] + bounds["width"] <= width
    rule = panel.locator(".category-rule-form").first
    trigger = rule.locator(".category-select-trigger")
    trigger.click()
    menu = rule.locator(".category-select-menu")
    assert menu.locator('[aria-selected="true"]').inner_text().startswith("识别物 ID")
    assert menu.locator('[aria-selected="true"] svg').evaluate(
        "node => getComputedStyle(node).opacity",
    ) == "1"
    trigger.press("Escape")
    assert menu.is_hidden()
    assert toggle.is_checked()


@pytest.mark.parametrize("scope", ["global", "binding"])
def test_target_keyboard_and_hidden_target_drafts(rules_page: Any, scope: str) -> None:
    root = options_root(rules_page, scope)
    rule = field(root, "rules-list").locator(".category-rule-form").first
    open_rule(rule)
    trigger = rule.locator(".category-select-trigger")
    marker = rule.locator('[data-rule-field="markerId"]')
    trigger.focus()
    trigger.press("ArrowDown")
    rules_page.keyboard.press("End")
    rules_page.keyboard.press("Enter")
    assert rule.locator('[data-rule-field="targetType"]').input_value() == "container"
    assert marker.is_hidden() and marker.is_disabled()
    rule.locator('[data-position-axis="y"]').fill("-42")
    choose_target(rule, "marker-position")
    assert "标记坐标" in rule.inner_text()
    assert rule.locator('[data-position-axis="y"]').input_value() == "-42"
    choose_target(rule, "marker-id")
    assert marker.input_value() == "minecraft:sculk"
    assert rule.locator('[data-position-axis="y"]').is_disabled()
    choose_target(rule, "container")
    assert rule.locator('[data-position-axis="y"]').input_value() == "-42"


def test_position_and_rule_lists_allow_add_and_remove(rules_page: Any) -> None:
    root = options_root(rules_page)
    positions = field(root, "positions-list")
    field(root, "add-position").click()
    assert positions.locator(".category-position-entry").count() == 2
    positions.locator(".category-position-entry").first.get_by_role(
        "button", name="删除排除坐标",
    ).click()
    assert positions.locator(".category-position-entry").count() == 1
    rules = field(root, "rules-list")
    field(root, "add-rule").click()
    assert rules.locator(".category-rule-form").count() == 3
    rules.locator(".category-rule-form").last.get_by_role("button", name="删除规则").click()
    assert rules.locator(".category-rule-form").count() == 2


@pytest.mark.parametrize("enabled", [False, True])
def test_inheritance_save_copy_and_resource_selection_keep_independent_drafts(
    rules_page: Any, enabled: bool,
) -> None:
    page = rules_page
    global_options = options_root(page)
    local_options = options_root(page, "binding")
    open_rule(field(local_options, "rules-list").locator(".category-rule-form").first)
    global_items = field(global_options, "rules-list").locator("textarea").first
    local_items = field(local_options, "rules-list").locator("textarea").first
    assert local_items.input_value() == global_items.input_value()
    assert field(local_options, "ignore-repeated").is_checked()
    field(local_options, "ignore-repeated").uncheck()
    local_items.fill("minecraft:stone\nminecraft:dirt")
    field(local_options, "positions-list").locator('[data-position-axis="x"]').fill("123")
    field(local_options, "multi-bindings").set_checked(enabled)
    field(local_options, "ignore-positions").set_checked(enabled)
    page.locator(".category-binding-enabled").set_checked(enabled)
    save(page)
    assert page.locator("#editor-status").inner_text() == "已保存分类区。"
    page.locator("#duplicate-coordinate").click()
    assert page.locator("#coordinate-name").input_value().endswith("副本")
    root = options_root(page)
    original = field(root, "rules-list").locator("textarea").first.input_value()
    open_rule(field(root, "rules-list").locator(".category-rule-form").first)
    field(root, "rules-list").locator("textarea").first.fill("minecraft:diamond")
    save(page)
    card = page.locator(".category-binding-card").first
    assert card.locator(".category-binding-enabled").is_checked() is enabled
    card.locator(".category-binding-toggle").click()
    card.locator(".category-binding-enabled").check()
    open_settings(card)
    assert not field(card, "ignore-repeated").is_checked()
    field(card, "multi-bindings").check()
    assert field(card, "rules-list").locator("textarea").first.input_value() == (
        "minecraft:stone\nminecraft:dirt"
    )
    field(card, "ignore-positions").check()
    assert field(card, "positions-list").locator(
        '[data-position-axis="x"]',
    ).input_value() == "123"
    page.locator('[data-coordinate-id="custom-rules-demo"]').click()
    assert field(options_root(page), "ignore-repeated").is_checked()
    assert field(options_root(page), "rules-list").locator(
        "textarea",
    ).first.input_value() == original


def test_excluded_marker_conflict_does_not_forbid_a_container_at_that_position(
    rules_page: Any,
) -> None:
    page = rules_page
    root = options_root(page)
    rule = field(root, "rules-list").locator(".category-rule-form").first
    choose_target(rule, "marker-position")
    for axis, value in (("x", "44"), ("y", "-41"), ("z", "39")):
        rule.locator(f'[data-position-axis="{axis}"]').fill(value)
    save(page)
    assert "目标标记已被排除" in page.locator("#editor-status").inner_text()
    choose_target(rule, "container")
    save(page)
    assert page.locator("#editor-status").inner_text() == "已保存分类区。"


def test_incomplete_active_rules_prevent_save_but_disabled_drafts_are_kept(
    rules_page: Any,
) -> None:
    page = rules_page
    root = options_root(page)
    field(root, "positions-list").locator('[data-position-axis="y"]').fill("")
    save(page)
    assert "请添加排除位置" in page.locator("#editor-status").inner_text()
    field(root, "ignore-positions").uncheck()
    open_rule(field(root, "rules-list").locator(".category-rule-form").first)
    field(root, "rules-list").locator("textarea").first.fill("不是物品 ID")
    save(page)
    assert "请每行填写一个完整物品 ID" in page.locator("#editor-status").inner_text()
    field(root, "multi-bindings").uncheck()
    save(page)
    assert page.locator("#editor-status").inner_text() == "已保存分类区。"
    root = options_root(page)
    field(root, "multi-bindings").check()
    assert field(root, "rules-list").locator("textarea").first.input_value() == "不是物品 ID"


@pytest.mark.parametrize("scope", ["global", "binding"])
def test_rule_submenus_keep_their_drafts_and_do_not_toggle_siblings(
    rules_page: Any, scope: str,
) -> None:
    page = rules_page
    root = options_root(page, scope)
    rules = field(root, "rules-list").locator(".category-rule-form")
    first = rules.first
    second = rules.last
    toggle = first.locator(".category-rule-toggle")
    assert toggle.get_attribute("aria-expanded") == "false"
    assert first.get_by_role("button", name="删除规则").is_hidden()
    first.locator('[data-rule-field="name"]').fill("幽匿收藏")
    assert toggle.get_attribute("aria-expanded") == "false"
    toggle.focus()
    toggle.press("Space")
    assert first.locator(".category-rule-body").is_visible()
    assert toggle.get_attribute("aria-label") == "收起物品绑定：幽匿收藏"
    assert second.locator(".category-rule-body").is_hidden()
    items = first.locator("textarea")
    items.fill("minecraft:sculk\nminecraft:sculk_sensor\nminecraft:sculk")
    assert first.locator(".category-rule-count").inner_text() == "2 项物品"
    first.locator(".category-select-trigger").click()
    toggle.click()
    assert first.locator(".category-select-menu").is_hidden()
    assert first.locator(".category-rule-body").is_hidden()
    assert field(root, "multi-bindings").is_checked()
    assert second.locator(".category-rule-body").is_hidden()
    toggle.press("Enter")
    assert items.input_value() == "minecraft:sculk\nminecraft:sculk_sensor\nminecraft:sculk"
    assert first.get_by_role("button", name="删除规则").is_visible()


@pytest.mark.parametrize("scope", ["global", "binding"])
def test_repeated_block_switch_and_explanation_have_independent_states(
    rules_page: Any, scope: str,
) -> None:
    root = options_root(rules_page, scope)
    toggle = field(root, "ignore-repeated")
    expand = field(root, "repeats-expand")
    panel = field(root, "repeats-panel")
    assert toggle.is_checked()
    assert panel.is_hidden()
    expand.click()
    assert panel.is_visible()
    assert "珊瑚扇＋平滑石英台阶 → 仅识别珊瑚扇" in panel.inner_text()
    assert "继续提示冲突" in panel.inner_text()
    assert "没有独立参照时不自动排除" in panel.inner_text()
    expand.click()
    assert panel.is_hidden()
    assert toggle.is_checked()
    toggle.focus()
    toggle.press("Space")
    assert not toggle.is_checked()
    assert expand.is_hidden()
    assert field(root, "ignore-positions").is_checked()
    assert field(root, "multi-bindings").is_checked()
    toggle.press("Space")
    assert panel.is_visible()
    assert toggle.get_attribute("aria-expanded") == "true"


def test_new_categories_leave_repeated_block_handling_off_and_new_rules_open(
    rules_page: Any,
) -> None:
    page = rules_page
    page.locator("#add-coordinate").click()
    page.locator("#coordinate-kind-trigger").click()
    page.locator('[data-coordinate-kind="category-zone"]').click()
    root = options_root(page)
    assert not field(root, "ignore-repeated").is_checked()
    assert field(root, "repeats-panel").is_hidden()
    field(root, "multi-bindings").check()
    rule = field(root, "rules-list").locator(".category-rule-form")
    assert rule.count() == 1
    assert rule.locator(".category-rule-body").is_visible()
    assert rule.locator(".category-rule-count").inner_text() == "0 项物品"


def test_invalid_collapsed_rule_is_opened_when_saving(rules_page: Any) -> None:
    page = rules_page
    root = options_root(page)
    rule = field(root, "rules-list").locator(".category-rule-form").first
    open_rule(rule)
    rule.locator("textarea").fill("")
    rule.locator(".category-rule-toggle").click()
    assert rule.locator(".category-rule-body").is_hidden()
    save(page)
    assert "请每行填写一个完整物品 ID" in page.locator("#editor-status").inner_text()
    assert rule.locator(".category-rule-body").is_visible()


@pytest.mark.parametrize("width", [1552, 390])
@pytest.mark.parametrize("theme", ["green", "gray"])
def test_main_settings_stay_compact_and_aligned_in_both_themes(
    rules_page: Any, width: int, theme: str,
) -> None:
    page = rules_page
    page.set_viewport_size({"width": width, "height": 1253})
    page.locator(f'[data-theme-choice="{theme}"]').click()
    root = page.locator(".category-options").first
    assert field(root, "recognition-panel").is_hidden()
    assert field(root, "bindings-panel").is_hidden()
    assert root.locator(".category-select-trigger:visible").count() == 3
    assert field(root, "recognition-summary").inner_text() == "已启用 2 项"
    assert field(root, "bindings-summary").inner_text() == "2 条规则"
    group = field(root, "recognition-expand")
    group.scroll_into_view_if_needed()
    bounds = group.bounding_box()
    assert bounds is not None and bounds["x"] >= 0
    assert bounds["x"] + bounds["width"] <= width
    actions = group.locator(".category-option-actions").bounding_box()
    standing = root.locator(".category-standing-plan > .category-option-row").locator(
        ".category-option-actions",
    ).bounding_box()
    assert actions is not None and standing is not None
    assert actions["x"] + actions["width"] == pytest.approx(
        standing["x"] + standing["width"], abs=1,
    )
    group.hover()
    assert group.bounding_box() == bounds


@pytest.mark.parametrize("scope", ["global", "binding"])
def test_recognition_disclosure_changes_visibility_without_disabling_rules(
    rules_page: Any, scope: str,
) -> None:
    page = rules_page
    if scope == "global":
        root = page.locator(".category-options").first
    else:
        card = page.locator(".category-binding-card").first
        card.locator(".category-binding-toggle").click()
        card.locator(".category-binding-enabled").check()
        root = card.locator(".category-options")
    panel = field(root, "recognition-panel")
    toggle = field(root, "recognition-expand")
    assert panel.is_hidden()
    assert panel.get_by_role("switch", include_hidden=True).count() == 3
    toggle.focus()
    toggle.press("Space")
    assert panel.is_visible()
    assert toggle.get_attribute("aria-expanded") == "true"
    field(root, "ignore-repeated").uncheck()
    assert field(root, "recognition-summary").inner_text() == "已启用 1 项"
    field(root, "ignore-support").check()
    field(root, "support-block").fill("minecraft:stone")
    assert field(root, "recognition-summary").inner_text() == "已启用 2 项"
    toggle.click()
    assert panel.is_hidden()
    assert field(root, "ignore-support").is_checked()
    assert field(root, "ignore-positions").is_checked()
    assert not field(root, "ignore-repeated").is_checked()
    assert field(root, "multi-bindings").is_checked()
    toggle.press("Enter")
    assert field(root, "support-block").input_value() == "minecraft:stone"
    assert field(root, "positions-list").locator(
        '[data-position-axis="x"]',
    ).input_value() == "44"


def test_binding_summary_tracks_enabled_and_saved_draft_counts(rules_page: Any) -> None:
    page = rules_page
    root = options_root(page)
    summary = field(root, "bindings-summary")
    field(root, "add-rule").click()
    assert summary.inner_text() == "3 条规则"
    field(root, "rules-list").locator(".category-rule-form").last.get_by_role(
        "button", name="删除规则",
    ).click()
    assert summary.inner_text() == "2 条规则"
    field(root, "multi-bindings").uncheck()
    assert summary.inner_text() == "已关闭 · 2 条规则"
    save(page)
    page.locator("#duplicate-coordinate").click()
    root = page.locator(".category-options").first
    assert field(root, "bindings-summary").inner_text() == "已关闭 · 2 条规则"
    assert field(root, "recognition-summary").inner_text() == "已启用 2 项"
    assert field(root, "recognition-panel").is_hidden()
    assert field(root, "bindings-panel").is_hidden()
    field(root, "multi-bindings").check()
    assert field(root, "bindings-summary").inner_text() == "2 条规则"


def test_invalid_hidden_coordinates_open_the_recognition_group(rules_page: Any) -> None:
    page = rules_page
    root = options_root(page)
    field(root, "positions-list").locator('[data-position-axis="y"]').fill("")
    field(root, "recognition-expand").click()
    assert field(root, "recognition-panel").is_hidden()
    save(page)
    assert "请添加排除位置" in page.locator("#editor-status").inner_text()
    assert field(root, "recognition-panel").is_visible()
    assert field(root, "positions-panel").is_visible()
