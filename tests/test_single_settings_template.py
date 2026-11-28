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
    if page.locator("html").get_attribute("data-theme") != theme:
        page.locator("#template-theme-toggle").click()
    tab_bounds = page.get_by_role("tablist").bounding_box()
    main_bounds = page.locator(".page-main").bounding_box()
    panel_bounds = page.get_by_role("tabpanel").bounding_box()
    header_bounds = page.locator(".page-header").bounding_box()
    assert tab_bounds is not None and main_bounds is not None and panel_bounds is not None
    assert tab_bounds["x"] == pytest.approx(main_bounds["x"])
    assert tab_bounds["width"] == pytest.approx(main_bounds["width"])
    assert tab_bounds["y"] + tab_bounds["height"] <= panel_bounds["y"]
    assert header_bounds is not None
    assert tab_bounds["y"] - header_bounds["y"] - header_bounds["height"] == pytest.approx(14)
    assert panel_bounds["y"] - tab_bounds["y"] - tab_bounds["height"] == pytest.approx(32)
    if width <= 540:
        task_bounds = page.get_by_role("tab", name="任务", exact=True).bounding_box()
        coordinate_bounds = page.get_by_role("tab", name="坐标", exact=True).bounding_box()
        assert task_bounds is not None and coordinate_bounds is not None
        assert task_bounds["width"] == pytest.approx(coordinate_bounds["width"])
    for name in ["坐标", "任务", "坐标"]:
        page.get_by_role("tab", name=name, exact=True).click()
        assert page.get_by_role("tablist").bounding_box() == tab_bounds
        assert page.locator("html").get_attribute("data-theme") == theme
        assert page.locator("#template-theme-toggle").get_attribute("aria-pressed") == str(
            theme == "green"
        ).lower()
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
    page.locator("#template-theme-toggle").click()
    assert page.locator("html").get_attribute("data-theme") == "gray"


def test_header_theme_toggle_and_subtitle_follow_both_panels(template_page: Any) -> None:
    page = template_page
    toggle = page.get_by_role("button", name="浅绿色主题", exact=True)
    assert toggle.count() == 1
    assert toggle.inner_text() == "主题：浅绿"
    assert page.get_by_role("heading", level=1).inner_text() == "Stowling 控制台"
    assert page.locator(".template-connection").inner_text() == "离线预览"
    toggle.click()
    assert toggle.inner_text() == "主题：深灰"
    assert toggle.get_attribute("title") == "切换为浅绿主题"
    assert toggle.get_attribute("aria-pressed") == "false"
    page.get_by_role("tab", name="坐标", exact=True).click()
    assert page.locator("#template-page-subtitle").inner_text() == "坐标管理 · 坐标资源与分类区"
    page.reload()
    assert page.get_by_role("tab", selected=True).inner_text() == "坐标"
    assert toggle.inner_text() == "主题：深灰"
    page.get_by_role("tab", name="任务", exact=True).click()
    assert page.locator("#template-page-subtitle").inner_text() == "任务管理 · 已保存任务与运行队列"
    toggle.click()
    assert toggle.inner_text() == "主题：浅绿"


@pytest.mark.parametrize("theme", ["green", "gray"])
@pytest.mark.parametrize("width", [320, 780, 1440])
def test_console_modules_follow_desktop_and_narrow_layout(
    template_page: Any, theme: str, width: int,
) -> None:
    page = template_page
    page.set_viewport_size({"width": width, "height": 1100})
    if page.locator("html").get_attribute("data-theme") != theme:
        page.locator("#template-theme-toggle").click()
    rail = page.locator(".template-monitor-rail").bounding_box()
    workbench = page.locator(".template-task-workbench").bounding_box()
    assert rail is not None and workbench is not None
    if width > 780:
        assert rail["x"] + rail["width"] < workbench["x"]
        assert rail["y"] == pytest.approx(workbench["y"])
    else:
        assert workbench["y"] + workbench["height"] <= rail["y"]
    assert page.locator("#template-saved-count").inner_text() == "1"
    assert page.locator("#template-queue-count").inner_text() == "0"
    assert page.locator("#template-log-count").inner_text() == "1"
    assert page.locator(".template-task-options button").count() == 1
    assert page.locator("#template-saved-list > .sample-module").count() == 1
    assert page.locator("#template-saved-list #sample-maximum").is_visible()
    assert page.locator("#template-saved-list .sample-title").inner_text() == "售卖弹性物资"
    assert page.locator(".template-task-options strong").inner_text() == "＋ 售卖弹性物资"
    assert page.locator(".template-task-card, .template-component-example").count() == 0
    assert page.evaluate("document.documentElement.scrollWidth <= innerWidth")


def test_preview_running_and_disclosure_states_remain_independent(template_page: Any) -> None:
    page = template_page
    card = page.locator("#template-saved-list > .sample-module")
    expand = page.locator("#sample-collapse")
    toggle = page.locator("#sample-enabled")
    page.locator("#sample-maximum").fill("180")
    toggle.check()
    assert expand.get_attribute("aria-expanded") == "true"
    assert page.locator("#template-queue-count").inner_text() == "1"
    expand.click()
    toggle.uncheck()
    assert expand.get_attribute("aria-expanded") == "false"
    assert page.locator("#template-queue-count").inner_text() == "0"
    page.locator("#template-select-selling-task").click()
    assert card.locator("#sample-module-body").is_visible()
    assert page.locator("#sample-maximum").input_value() == "180"
    toggle.check()
    page.locator("#template-refresh-tasks").click()
    page.get_by_role("tab", name="坐标", exact=True).click()
    page.locator("#template-theme-toggle").click()
    page.get_by_role("tab", name="任务", exact=True).click()
    assert toggle.is_checked()
    assert expand.get_attribute("aria-expanded") == "true"
    assert card.locator("#sample-module-body").is_visible()
    assert page.locator("#template-queue-count").inner_text() == "1"
    assert page.locator("#template-log-count").inner_text() == "4"


def test_task_selection_opens_existing_selling_task_without_losing_drafts(
    template_page: Any,
) -> None:
    page = template_page
    page.locator("#sample-maximum").fill("180")
    page.locator("#sample-collapse").click()
    assert page.locator("#sample-module-body").is_hidden()
    for _ in range(2):
        page.locator("#template-select-selling-task").click()
        assert page.locator("#sample-module-body").is_visible()
        assert page.locator("#sample-collapse").evaluate("node => node === document.activeElement")
    assert page.locator("#template-saved-count").inner_text() == "1"
    assert page.locator("#template-saved-list > .sample-module").count() == 1
    assert page.locator("#template-queue-count").inner_text() == "0"
    assert page.locator("#sample-maximum").input_value() == "180"


def test_preview_details_and_log_actions_handle_empty_states(template_page: Any) -> None:
    page = template_page
    page.locator("#sample-enabled").check()
    page.get_by_role("button", name="运行详情", exact=True).click()
    dialog = page.get_by_role("dialog")
    assert dialog.is_visible()
    assert dialog.locator(".template-queue-entry").count() == 1
    assert dialog.locator(".template-queue-entry strong").inner_text() == "售卖弹性物资"
    page.keyboard.press("Escape")
    assert dialog.is_hidden()
    with page.expect_download() as download:
        page.get_by_role("button", name="导出示例日志", exact=True).click()
    assert download.value.suggested_filename == "stowling-template-example-logs.json"
    page.get_by_role("button", name="清空示例日志", exact=True).click()
    assert page.locator("#template-log-count").inner_text() == "0"
    assert page.get_by_text("暂无任务进度", exact=True).is_visible()
    assert page.get_by_role("button", name="导出示例日志", exact=True).is_disabled()
    page.locator("#sample-enabled").uncheck()
    assert page.locator("#template-queue-count").inner_text() == "0"
    assert page.locator("#template-queue-list").inner_text() == "暂无运行任务"


def test_selling_experiment_requires_debug_to_edit_but_allows_reading(template_page: Any) -> None:
    page = template_page
    menu = page.locator("#sample-experiments")
    expand = page.locator("#sample-experiments-expand")
    option = page.locator("#sample-experiment-option")
    debug = page.locator("#sample-debug-mode")
    assert menu.is_visible()
    assert expand.get_attribute("aria-expanded") == "false"
    expand.click()
    assert page.locator("#sample-experiment-option-row").is_visible()
    assert option.is_disabled()
    page.locator('label[for="sample-experiment-option"]').click()
    assert not option.is_checked()
    debug.check()
    assert option.is_enabled()
    option.check()
    debug.uncheck()
    assert option.is_disabled()
    assert option.is_checked()
    assert expand.get_attribute("aria-expanded") == "true"
    page.locator("#sample-collapse").click()
    active = page.locator("#sample-experiment-active")
    assert active.is_visible()
    assert "experimental-example" in active.inner_text()
    page.reload()
    assert not debug.is_checked()
    assert option.is_disabled()
    assert option.is_checked()
    assert active.is_visible()
