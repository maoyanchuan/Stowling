from collections.abc import Iterator
from pathlib import Path
from typing import Any

import pytest


UI = Path(__file__).resolve().parents[1] / "docs/ui"
TEMPLATE = UI / "settings-standard.html"


@pytest.fixture
def template_page() -> Iterator[Any]:
    playwright = pytest.importorskip("playwright.sync_api")
    with playwright.sync_playwright() as runtime:
        browser = runtime.chromium.launch()
        page = browser.new_page(viewport={"width": 1440, "height": 1100})
        page.emulate_media(reduced_motion="reduce")
        errors: list[str] = []
        page.on("pageerror", lambda error: errors.append(str(error)))
        page.goto(TEMPLATE.as_uri())
        yield page
        assert errors == []
        browser.close()


def test_there_is_one_html_template_with_two_real_tab_panels(template_page: Any) -> None:
    page = template_page
    assert list(UI.glob("*.html")) == [TEMPLATE]
    assert page.locator("iframe, frame").count() == 0
    assert page.get_by_role("tab").all_text_contents() == ["任务", "坐标"]
    assert page.get_by_role("tab", selected=True).inner_text() == "任务"
    assert page.get_by_role("tabpanel").count() == 1
    page.get_by_role("tab", name="坐标", exact=True).click()
    assert page.get_by_role("tabpanel").count() == 1
    assert page.locator("#coordinate-form").is_visible()
    assert page.locator("#stowling-settings-standard").is_hidden()


def test_switching_tabs_preserves_both_drafts_and_disclosure_state(template_page: Any) -> None:
    page = template_page
    page.locator("#sample-maximum").fill("180")
    page.locator("#sample-enabled").check()
    page.locator("#sample-wuling-expand").click()
    page.locator("#sample-wuling-reserve").fill("96")
    page.get_by_role("tab", name="坐标", exact=True).click()
    page.locator("#coordinate-name").fill("保留的分类区草稿")
    page.locator("#category-overall-first-x").fill("41")
    page.locator(".category-binding-toggle").click()
    page.get_by_role("tab", name="任务", exact=True).click()
    assert page.locator("#sample-maximum").input_value() == "180"
    assert page.locator("#sample-enabled").is_checked()
    assert page.locator("#sample-wuling-detail").is_visible()
    assert page.locator("#sample-wuling-reserve").input_value() == "96"
    page.get_by_role("tab", name="坐标", exact=True).click()
    assert page.locator("#coordinate-name").input_value() == "保留的分类区草稿"
    assert page.locator("#category-overall-first-x").input_value() == "41"
    assert page.locator(".category-binding-toggle").get_attribute("aria-expanded") == "true"


def test_tab_keyboard_navigation_keeps_one_focus_stop_and_hides_inactive_controls(
    template_page: Any,
) -> None:
    page = template_page
    task = page.get_by_role("tab", name="任务", exact=True)
    coordinates = page.get_by_role("tab", name="坐标", exact=True)
    task.focus()
    for key, selected in [("ArrowRight", coordinates), ("ArrowRight", task),
                          ("End", coordinates), ("Home", task)]:
        page.keyboard.press(key)
        assert selected.get_attribute("aria-selected") == "true"
        assert selected.evaluate("node => node === document.activeElement")
        assert page.locator('[role="tab"][tabindex="0"]').count() == 1
    assert page.locator("#template-coordinates-panel").evaluate("node => node.hidden && node.inert")
    assert not page.evaluate("""() => {
        const panel = document.querySelector('#template-coordinates-panel');
        panel.querySelector('input').focus();
        return panel.contains(document.activeElement);
    }""")


@pytest.mark.parametrize("theme", ["green", "gray"])
@pytest.mark.parametrize("width", [390, 1440])
def test_theme_and_layout_stay_shared_across_tabs(
    template_page: Any, theme: str, width: int,
) -> None:
    page = template_page
    page.set_viewport_size({"width": width, "height": 1100})
    page.locator(f'[data-theme-choice="{theme}"]').click()
    tab_bounds = page.get_by_role("tablist").bounding_box()
    for name in ["坐标", "任务", "坐标"]:
        page.get_by_role("tab", name=name, exact=True).click()
        assert page.get_by_role("tablist").bounding_box() == tab_bounds
        assert page.locator("html").get_attribute("data-theme") == theme
        assert page.locator(f'[data-theme-choice="{theme}"]').get_attribute("aria-pressed") == "true"
        assert page.evaluate("document.documentElement.scrollWidth <= innerWidth")


def test_switching_tabs_closes_open_popups_without_resetting_values(template_page: Any) -> None:
    page = template_page
    page.locator("#sample-strategy-trigger").click()
    assert page.locator("#sample-strategy-menu").is_visible()
    page.get_by_role("tab", name="坐标", exact=True).click()
    page.locator("#category-scope-mode-trigger").click()
    assert page.locator("#category-scope-mode-menu").is_visible()
    page.get_by_role("tab", name="任务", exact=True).click()
    assert page.locator("#sample-strategy-trigger").get_attribute("aria-expanded") == "false"
    page.get_by_role("tab", name="坐标", exact=True).click()
    assert page.locator("#category-scope-mode-trigger").get_attribute("aria-expanded") == "false"


def test_tabs_work_when_browser_storage_is_unavailable(template_page: Any) -> None:
    page = template_page
    page.add_init_script("""(() => {
        Storage.prototype.getItem = () => {throw new Error('storage unavailable');};
        Storage.prototype.setItem = () => {throw new Error('storage unavailable');};
    })();""")
    page.reload()
    page.get_by_role("tab", name="坐标", exact=True).click()
    assert page.locator("#coordinate-form").is_visible()
    page.locator('[data-theme-choice="gray"]').click()
    assert page.locator("html").get_attribute("data-theme") == "gray"
