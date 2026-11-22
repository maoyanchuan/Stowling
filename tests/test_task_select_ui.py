from collections.abc import Iterator
from pathlib import Path
from typing import Any

import pytest


WEB = Path(__file__).resolve().parents[1] / "src/main/resources/stowling-web"


@pytest.fixture
def page() -> Iterator[Any]:
    playwright = pytest.importorskip("playwright.sync_api")
    with playwright.sync_playwright() as runtime:
        try:
            browser = runtime.chromium.launch()
        except playwright.Error as error:
            pytest.skip(f"Playwright Chromium is required: {error}")
        page = browser.new_page(viewport={"width": 900, "height": 700})
        page.set_content("""
            <html data-theme="green"><body data-page="tasks">
            <article class="pickup-task-module" style="max-width:700px">
                <form id="form" class="auto-task-form" data-kind="PICKUP"><fieldset>
                    <label class="move-field" for="point"><span>检测点</span>
                        <select id="point" required>
                            <option value="">请选择检测点</option>
                            <optgroup label="主仓库">
                                <option value="a">东侧</option>
                                <option value="b">西侧</option>
                                <option value="missing" disabled>已删除</option>
                            </optgroup>
                        </select>
                    </label>
                    <label class="move-field"><span>空盒供应箱</span>
                        <select id="boxes" multiple>
                            <option value="a" selected>箱子一</option>
                            <option value="b">箱子二</option>
                        </select>
                    </label>
                </fieldset></form>
            </article></body></html>
        """)
        for name in ("styles.css", "workbench.css", "pickup-task-settings.css"):
            page.add_style_tag(content=(WEB / name).read_text(encoding="utf-8"))
        page.add_script_tag(content="""
            window.events = [];
            window.Stowling = {
                /** @param {string} tag @param {string} css @param {string} text
                 * @returns {HTMLElement} */
                element(tag, css = '', text = '') {
                    const node = document.createElement(tag);
                    node.className = css;
                    node.textContent = text;
                    return node;
                }
            };
            document.querySelector('#form').addEventListener('input', event => {
                events.push({id:event.target.id, value:event.target.value});
            });
        """)
        page.add_script_tag(content=(WEB / "task-select-ui.js").read_text(encoding="utf-8"))
        page.evaluate("""() => {
            window.view = StowlingTaskSelect.create(document.querySelector('#form'));
            view.refresh(true);
        }""")
        yield page
        browser.close()


def test_select_keyboard_commits_native_value_and_returns_focus(page: Any) -> None:
    trigger = page.get_by_role("button", name="检测点 请选择检测点", exact=True)
    trigger.focus()
    page.keyboard.press("ArrowDown")
    page.keyboard.press("ArrowDown")
    page.keyboard.press("Enter")

    assert page.locator("#point").input_value() == "a"
    assert page.evaluate("document.activeElement.id") == trigger.get_attribute("id")
    assert page.evaluate("events") == [{"id": "point", "value": "a"}]
    assert page.get_by_role("listbox").count() == 0
    trigger.click()
    page.keyboard.press("Escape")
    assert trigger.get_attribute("aria-expanded") == "false"
    assert page.locator("#point").input_value() == "a"


def test_multi_select_preserves_values_when_disabled_or_native_view_restored(page: Any) -> None:
    trigger = page.get_by_role("button", name="空盒供应箱 箱子一", exact=True)
    trigger.click()
    page.get_by_role("option", name="箱子二", exact=True).click()
    assert page.locator("#boxes").evaluate(
        "select => [...select.selectedOptions].map(option => option.value)"
    ) == ["a", "b"]
    page.evaluate("""() => {
        document.querySelector('fieldset').disabled = true;
        view.refresh(true);
    }""")
    assert trigger.is_disabled()
    assert page.get_by_role("listbox").count() == 0
    page.evaluate("""() => {
        document.querySelector('fieldset').disabled = false;
        view.refresh(false);
    }""")
    assert page.locator("#boxes").is_visible()
    assert page.locator("#boxes").evaluate("select => select.selectedOptions.length") == 2


def test_resource_refresh_and_collapsing_the_task_close_the_popup(page: Any) -> None:
    trigger = page.get_by_role("button", name="检测点 请选择检测点", exact=True)
    page.evaluate("""() => {
        const select = document.querySelector('#point');
        select.replaceChildren(new Option('新检测点', 'new'));
    }""")
    page.wait_for_function(
        "document.querySelector('.task-select-current').textContent === '新检测点'"
    )
    trigger = page.get_by_role("button", name="检测点 新检测点", exact=True)
    trigger.click()
    page.evaluate("""() => {
        document.querySelector('#form').inert = true;
        view.refresh(true);
    }""")
    assert page.get_by_role("listbox").count() == 0
    assert page.locator("#point").input_value() == "new"


@pytest.mark.parametrize("theme", ["green", "gray"])
def test_narrow_popup_fits_viewport_and_opens_above_when_needed(page: Any, theme: str) -> None:
    page.set_viewport_size({"width": 360, "height": 600})
    page.evaluate("""theme => {
        document.documentElement.dataset.theme = theme;
        document.querySelector('article').style.marginTop = '360px';
    }""", theme)
    page.get_by_role("button", name="检测点 请选择检测点", exact=True).click()
    menu = page.get_by_role("listbox")
    bounds = menu.bounding_box()
    assert bounds is not None
    assert bounds["x"] >= 0
    assert bounds["x"] + bounds["width"] <= 360
    assert bounds["y"] >= 0
    assert menu.get_attribute("data-placement") == "above"
    assert page.locator(".pickup-task-module").evaluate(
        "node => node.scrollWidth <= node.clientWidth"
    )
