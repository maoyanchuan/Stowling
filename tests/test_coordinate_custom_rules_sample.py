from collections.abc import Iterator
from pathlib import Path
from typing import Any

import pytest


SAMPLE = Path(__file__).resolve().parents[1] / "docs/ui/coordinate-management-standard.html"


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
        page.goto(SAMPLE.as_uri())
        yield page
        browser.close()


def options_root(page: Any, scope: str = "global") -> Any:
    if scope == "global":
        return page.locator(".category-options").first
    card = page.locator(".category-binding-card").first
    card.locator(".category-binding-toggle").click()
    card.locator(".category-binding-enabled").check()
    return card.locator(".category-options")


def field(root: Any, key: str) -> Any:
    return root.locator(f'[id$="category-{key}"]')


def choose_target(rule: Any, value: str) -> None:
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
    global_items = field(global_options, "rules-list").locator("textarea").first
    local_items = field(local_options, "rules-list").locator("textarea").first
    assert local_items.input_value() == global_items.input_value()
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
    field(root, "rules-list").locator("textarea").first.fill("minecraft:diamond")
    save(page)
    card = page.locator(".category-binding-card").first
    assert card.locator(".category-binding-enabled").is_checked() is enabled
    card.locator(".category-binding-toggle").click()
    card.locator(".category-binding-enabled").check()
    field(card, "multi-bindings").check()
    assert field(card, "rules-list").locator("textarea").first.input_value() == (
        "minecraft:stone\nminecraft:dirt"
    )
    field(card, "ignore-positions").check()
    assert field(card, "positions-list").locator(
        '[data-position-axis="x"]',
    ).input_value() == "123"
    page.locator('[data-coordinate-id="custom-rules-demo"]').click()
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
    field(root, "rules-list").locator("textarea").first.fill("不是物品 ID")
    save(page)
    assert "请每行填写一个完整物品 ID" in page.locator("#editor-status").inner_text()
    field(root, "multi-bindings").uncheck()
    save(page)
    assert page.locator("#editor-status").inner_text() == "已保存分类区。"
    root = options_root(page)
    field(root, "multi-bindings").check()
    assert field(root, "rules-list").locator("textarea").first.input_value() == "不是物品 ID"
