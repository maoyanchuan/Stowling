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
                    <section class="auto-config-section auto-cache-submenu">
                        <div class="auto-phase-body"><div class="task-settings-inner">
                            <label id="point-row" class="move-field" for="point">
                                <span>检测点</span>
                                <select id="point" required>
                                    <option value="">请选择检测点</option>
                                    <optgroup label="主仓库">
                                        <option value="a">东侧</option>
                                        <option value="b">西侧</option>
                                        <option value="missing" disabled>已删除</option>
                                    </optgroup>
                                </select>
                            </label>
                        </div></div>
                    </section>
                    <label class="move-field"><span>空盒供应箱</span>
                        <select id="boxes" multiple>
                            <option value="a" selected>箱子一</option>
                            <option value="b">箱子二</option>
                        </select>
                    </label>
                    <label id="priority-row" class="move-field move-priority">
                        <span>任务优先级</span>
                        <select id="priority">
                            <option value="NORMAL">普通</option>
                            <option value="URGENT">紧急</option>
                        </select>
                    </label>
                    <section class="auto-config-section">
                        <div id="follow-up-row" class="auto-phase-header">
                            <button class="auto-phase-toggle" type="button">
                                <span class="auto-phase-copy">
                                    <strong>任务结束后创建任务</strong><small>不创建任务</small>
                                </span>
                                <span class="move-task-chevron"></span>
                            </button>
                            <label class="auto-phase-switch">
                                <input type="checkbox"><span class="auto-switch-track"></span>
                            </label>
                        </div>
                    </section>
                    <details class="settings-disclosure task-cleanup-section">
                        <summary class="settings-row">
                            <span class="settings-copy"><strong>中断与收尾设置</strong>
                                <span class="settings-description">使用本任务缓存位置</span>
                            </span>
                            <span class="settings-chevron"></span>
                        </summary>
                    </details>
                    <label class="move-field"><span>后备打包位置</span>
                        <select id="fallback"><option value="">不指定</option>
                            <option value="a">打包点</option>
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
    trigger = page.locator("#point-row .task-select-trigger")
    trigger.focus()
    page.keyboard.press("ArrowDown")
    assert page.locator("#point").input_value() == ""
    page.keyboard.press("Enter")

    assert page.locator("#point").input_value() == "a"
    assert page.evaluate("document.activeElement.id") == trigger.get_attribute("id")
    assert page.evaluate("events") == [{"id": "point", "value": "a"}]
    assert page.get_by_role("listbox").count() == 0
    trigger.click()
    page.keyboard.press("Escape")
    assert trigger.get_attribute("aria-expanded") == "false"
    assert page.locator("#point").input_value() == "a"


def test_required_resource_hints_are_not_choices_and_do_not_select_a_resource(page: Any) -> None:
    page.get_by_role("button", name="检测点 请选择检测点", exact=True).click()

    assert page.locator(".task-select-menu:not([hidden]) .task-select-option").count() == 3
    assert all("请选择" not in text for text in page.get_by_role("option").all_text_contents())
    assert page.locator("#point").input_value() == ""
    assert page.evaluate("events") == []
    page.keyboard.press("Escape")
    page.get_by_role("button", name="后备打包位置 不指定", exact=True).click()
    assert page.get_by_role("option", name="不指定", exact=True).count() == 1


def test_empty_required_resource_list_has_no_selectable_placeholder(page: Any) -> None:
    page.evaluate("""() => {
        const select = document.querySelector('#point');
        select.replaceChildren(new Option('请选择检测点', ''));
        view.refresh(true);
    }""")
    page.get_by_role("button", name="检测点 请选择检测点", exact=True).click()

    assert page.get_by_role("option").count() == 0
    assert page.locator(".task-select-menu:not([hidden]) .task-select-empty").is_visible()
    assert page.locator("#point").input_value() == ""
    assert page.evaluate("events") == []


def test_multi_select_preserves_values_when_disabled_or_native_view_restored(page: Any) -> None:
    trigger = page.locator(".task-select-wrap:has(> #boxes) > .task-select-trigger")
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


@pytest.mark.parametrize("theme", ["green", "gray"])
@pytest.mark.parametrize("width", [360, 900])
def test_priority_and_cleanup_match_the_standard_rows(page: Any, theme: str, width: int) -> None:
    page.set_viewport_size({"width": width, "height": 1000})
    page.evaluate("theme => document.documentElement.dataset.theme = theme", theme)
    result = page.evaluate("""() => {
        const form = document.querySelector('#form');
        const priority = document.querySelector('#priority-row');
        const label = priority.firstElementChild;
        const trigger = priority.querySelector('.task-select-trigger');
        const cleanup = document.querySelector('.task-cleanup-section');
        const style = getComputedStyle(cleanup);
        return {
            formWidth:form.getBoundingClientRect().width,
            row:priority.getBoundingClientRect().toJSON(),
            label:label.getBoundingClientRect().toJSON(),
            trigger:trigger.getBoundingClientRect().toJSON(),
            labelColor:getComputedStyle(label).color,
            primaryColor:getComputedStyle(form).color,
            cleanupColor:getComputedStyle(cleanup.querySelector('strong')).color,
            cleanup:{background:style.backgroundColor, radius:style.borderRadius,
                border:style.borderTopWidth, padding:style.padding, margin:style.margin}
        };
    }""")

    assert result["row"]["width"] >= result["formWidth"] - 48
    assert result["labelColor"] == result["primaryColor"] == result["cleanupColor"]
    assert result["cleanup"] == {
        "background": "rgba(0, 0, 0, 0)",
        "radius": "0px",
        "border": "0px",
        "padding": "0px",
        "margin": "0px",
    }
    if width > 600:
        assert result["trigger"]["left"] >= result["label"]["right"] + 15
    else:
        assert result["trigger"]["top"] >= result["label"]["bottom"]
    assert result["trigger"]["right"] <= result["row"]["right"] + 1


@pytest.mark.parametrize("theme", ["green", "gray"])
@pytest.mark.parametrize("selector", ["#point-row", "#follow-up-row", ".settings-row"])
def test_hover_has_one_rounded_layer_without_clipping(page: Any, theme: str, selector: str) -> None:
    page.evaluate("theme => document.documentElement.dataset.theme = theme", theme)
    row = page.locator(selector)
    row.hover()
    page.wait_for_function("""selector =>
        Number(getComputedStyle(document.querySelector(selector), '::before').opacity) > 0.99
    """, arg=selector)
    result = row.evaluate("""row => {
        const highlight = getComputedStyle(row, '::before');
        const clipping = [];
        for (let node = row.parentElement; node; node = node.parentElement) {
            if (['hidden', 'clip', 'scroll', 'auto'].includes(getComputedStyle(node).overflowX)) {
                clipping.push(node.className);
            }
        }
        const button = row.querySelector('.auto-phase-toggle');
        return {radius:highlight.borderRadius, left:highlight.left, right:highlight.right,
            rowBackground:getComputedStyle(row).backgroundColor, clipping,
            buttonBackground:button ? getComputedStyle(button).backgroundColor : null};
    }""")

    assert result["radius"] == "10px"
    assert result["left"] == result["right"] == "-12px"
    assert result["rowBackground"] == "rgba(0, 0, 0, 0)"
    assert result["clipping"] == []
    if selector == "#follow-up-row":
        assert result["buttonBackground"] == "rgba(0, 0, 0, 0)"


@pytest.mark.parametrize("theme", ["green", "gray"])
@pytest.mark.parametrize("width", [360, 900, 1800])
def test_cleanup_controls_stay_inside_their_own_hover_rows(
    page: Any, theme: str, width: int
) -> None:
    page.set_viewport_size({"width": width, "height": 1200})
    for name in ("settings-ui.js", "task-cleanup-settings.js"):
        page.add_script_tag(content=(WEB / name).read_text(encoding="utf-8"))
    page.evaluate("""theme => {
        document.documentElement.dataset.theme = theme;
        document.querySelector('article').style.maxWidth = 'none';
        const host = document.createElement('div');
        host.id = 'auto-cleanup-settings';
        document.querySelector('.task-cleanup-section').replaceWith(host);
        window.cleanup = StowlingTaskCleanupSettings.create(host, {
            allowContinue:false, facilities:true, defaultLabel:'使用本任务缓存位置'
        });
        cleanup.resources([{id:'pickup', name:'掉落物拾取点'}], []);
        cleanup.set({emptyBoxRefs:['coordinate:pickup']});
        const content = host.querySelector('.settings-detail');
        const inner = document.createElement('div');
        inner.className = 'task-settings-inner';
        inner.append(...content.childNodes);
        content.append(inner);
        view.refresh(true);
    }""", theme)
    page.locator("#auto-cleanup-settings summary").click()
    page.wait_for_function("""() => {
        const content = document.querySelector('#auto-cleanup-settings .settings-detail');
        return !content.hidden && content.style.overflow !== 'hidden';
    }""")

    rows = page.locator("#auto-cleanup-settings .move-field")
    measurements = []
    for index in (0, 1):
        row = rows.nth(index)
        row.hover()
        measurements.append(row.evaluate("""row => {
            const bounds = row.getBoundingClientRect();
            const highlight = getComputedStyle(row, '::before');
            return {
                row:bounds.toJSON(),
                highlight:{top:bounds.top + parseFloat(highlight.top),
                    bottom:bounds.bottom - parseFloat(highlight.bottom)},
                label:row.firstElementChild.getBoundingClientRect().toJSON(),
                control:row.querySelector('.task-select-trigger')
                    .getBoundingClientRect().toJSON(),
                note:row.querySelector('small')?.getBoundingClientRect().toJSON()
            };
        }"""))

    for measurement in measurements:
        highlight = measurement["highlight"]
        control = measurement["control"]
        assert control["top"] >= highlight["top"] + 8
        assert control["bottom"] <= highlight["bottom"] - 8
        assert measurement["label"]["top"] >= highlight["top"]
        if note := measurement.get("note"):
            assert note["bottom"] <= highlight["bottom"] - 8
        if width > 600:
            assert abs(control["top"] + control["bottom"]
                       - highlight["top"] - highlight["bottom"]) <= 2

    assert measurements[0]["highlight"]["bottom"] <= measurements[1]["highlight"]["top"]
    if width > 600:
        assert abs(measurements[0]["row"]["height"] - measurements[1]["row"]["height"]) <= 1
        assert abs(measurements[0]["control"]["right"]
                   - measurements[1]["control"]["right"]) <= 1
    assert page.evaluate("cleanup.read().emptyBoxRefs") == ["coordinate:pickup"]
