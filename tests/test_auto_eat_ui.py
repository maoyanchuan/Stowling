import re
from collections.abc import Iterator
from pathlib import Path
from typing import Any

import pytest


WEB = Path(__file__).resolve().parents[1] / "src/main/resources/stowling-web"


@pytest.fixture
def eat_page() -> Iterator[Any]:
    playwright = pytest.importorskip("playwright.sync_api")
    with playwright.sync_playwright() as runtime:
        try:
            browser = runtime.chromium.launch()
        except playwright.Error as error:
            pytest.skip(f"Playwright Chromium is required: {error}")
        page = browser.new_page(viewport={"width": 1000, "height": 1000})
        html = (WEB / "tasks.html").read_text(encoding="utf-8")
        html = re.sub(r"<script\b[^>]*>[\s\S]*?</script>", "", html)
        page.set_content(re.sub(r"<link\b[^>]*>", "", html))
        page.evaluate("""() => {
            const ids = ['auto-eat-draft', 'auto-eat-list', 'auto-eat-status',
                'new-auto-eat', 'refresh-move-tasks'];
            document.body.replaceChildren(...ids.map(id => document.getElementById(id)));
            document.body.style.padding = '16px';
        }""")
        for name in ("styles.css", "workbench.css", "pickup-task-settings.css"):
            page.add_style_tag(content=(WEB / name).read_text(encoding="utf-8"))
        page.add_script_tag(content=r"""
            window.backend = {
                revision: '1', requests: [], errors: [], handlers: [], failSwitch: false,
                tasks: [{id: 'eat/1', settings: {name: '自动进食', enabled: false,
                    foodItemId: 'minecraft:bread', coordinateId: null,
                    foodLevelThreshold: 18, intervalSeconds: 1}}]
            };
            window.Stowling = {
                /** @param {string} tag @param {string} css @param {string} text
                 * @returns {HTMLElement} */
                element(tag, css = '', text = '') {
                    const element = document.createElement(tag);
                    element.className = css;
                    element.textContent = text;
                    return element;
                },
                /** @returns {boolean} */
                isConnected() {return true;},
                /** @returns {void} */
                showToast() {},
                /** @param {Error} error @returns {void} */
                handleError(error) {backend.errors.push(error.message);},
                /** @param {Function} handler @returns {void} */
                onConnected(handler) {backend.handlers.push(handler);},
                /** @param {string} path @param {object} options @returns {Promise<object>} */
                async request(path, options = {}) {
                    const method = options.method || 'GET';
                    const body = options.body ? JSON.parse(options.body) : null;
                    backend.requests.push({path, method, body});
                    if (path === '/v1/coordinates') return {coordinates: [
                        {id: 'food', name: '主仓食物箱', x: 10, y: 64, z: -20},
                        {id: 'reserve', name: '备用食物箱', x: 90, y: 70, z: 30}
                    ]};
                    let taskId;
                    if (method === 'POST' && /\/(start|stop)$/.test(path)) {
                        if (backend.failSwitch) throw new Error('监听切换失败');
                        backend.tasks[0].settings.enabled = path.endsWith('/start');
                    } else if (method === 'PUT') {
                        Object.assign(backend.tasks[0].settings, {
                            foodItemId: body.foodItemId, coordinateId: body.coordinateId,
                            foodLevelThreshold: body.foodLevelThreshold,
                            intervalSeconds: body.intervalSeconds
                        });
                        taskId = backend.tasks[0].id;
                    } else if (method === 'POST') {
                        taskId = 'eat/new';
                        const {revision, ...settings} = body;
                        backend.tasks.push({id: taskId, settings: {
                            ...settings, name: '自动进食 2', enabled: false
                        }});
                    }
                    if (method !== 'GET') {
                        backend.revision = String(Number(backend.revision) + 1);
                    }
                    return structuredClone({revision: backend.revision,
                        items: backend.tasks, taskId});
                }
            };
        """)
        for name in ("settings-ui.js", "task-select-ui.js", "task-autosave.js", "auto-eat-ui.js"):
            page.add_script_tag(content=(WEB / name).read_text(encoding="utf-8"))
        page.evaluate("async () => {for (const handler of backend.handlers) await handler();}")
        yield page
        browser.close()


def settle(page: Any) -> None:
    page.wait_for_function("document.getAnimations().length === 0")


def open_settings(page: Any) -> None:
    page.locator("#auto-eat-list .pickup-task-expand").click()
    settle(page)


def test_template_header_separates_disclosure_and_listening(eat_page: Any) -> None:
    page = eat_page
    card = page.locator("#auto-eat-list article")
    assert card.locator(".pickup-task-header").evaluate(
        "node => [...node.children].map(child => child.tagName)"
    ) == ["H3", "BUTTON", "INPUT"]
    assert card.locator("h3").inner_text() == "自动进食"
    assert card.locator("h3 svg").count() == 1
    assert card.locator(".pickup-task-body").is_hidden()
    assert card.locator(".pickup-task-body").evaluate("node => node.inert")
    header = card.locator(".pickup-task-header").bounding_box()
    switch = card.get_by_role("switch").bounding_box()
    assert header is not None and header["height"] >= 70
    assert switch is not None and (switch["width"], switch["height"]) == (56, 32)

    toggle = card.locator(".pickup-task-expand")
    toggle.focus()
    toggle.press("Enter")
    settle(page)
    assert page.locator("#auto-eat-threshold").is_enabled()
    assert not card.get_by_role("switch").is_checked()
    assert page.evaluate("backend.requests.filter(item => item.method !== 'GET')") == []

    card.get_by_role("switch").check()
    page.wait_for_function("backend.tasks[0].settings.enabled")
    assert toggle.get_attribute("aria-expanded") == "true"
    toggle.press("Space")
    settle(page)
    assert card.get_by_role("switch").is_checked()
    assert card.locator(".pickup-task-body").is_hidden()
    card.get_by_role("switch").uncheck()
    page.wait_for_function("!backend.tasks[0].settings.enabled")
    assert toggle.get_attribute("aria-expanded") == "false"
    assert page.evaluate("backend.requests.filter(item => item.method === 'POST')") == [
        {"path": "/v1/auto-eat-tasks/eat%2F1/start", "method": "POST", "body": {"revision": "1"}},
        {"path": "/v1/auto-eat-tasks/eat%2F1/stop", "method": "POST", "body": {"revision": "2"}},
    ]


def test_paused_task_remains_editable_and_autosaves_without_starting(eat_page: Any) -> None:
    page = eat_page
    open_settings(page)
    page.locator("#auto-eat-threshold").fill("12")
    page.locator("#auto-eat-interval").fill("3")
    page.locator("#auto-eat-food").fill("minecraft:baked_potato")
    page.wait_for_function("backend.tasks[0].settings.foodItemId === 'minecraft:baked_potato'")
    assert page.locator("#auto-eat-threshold").input_value() == "12"
    assert page.locator("#auto-eat-interval").input_value() == "3"
    assert page.locator("#auto-eat-threshold").is_enabled()
    assert not page.get_by_role("switch").is_checked()
    writes = page.evaluate("backend.requests.filter(item => item.method !== 'GET')")
    assert writes and all(item["method"] == "PUT" for item in writes)
    assert all("enabled" not in item["body"] for item in writes)
    assert writes[-1]["body"]["coordinateId"] is None


def test_source_and_coordinate_use_keyboard_menus_and_preserve_selection(eat_page: Any) -> None:
    page = eat_page
    open_settings(page)
    trigger = page.locator("#auto-eat-source + .task-select-trigger")
    trigger.press("ArrowDown")
    menu = page.locator(".task-select-menu:not([hidden])")
    assert menu.get_by_role("option").count() == 2
    assert menu.get_by_role("option").first.get_attribute("aria-selected") == "true"
    assert menu.locator("small").count() == 2
    menu.get_by_role("option").first.press("End")
    menu.get_by_role("option").last.press("Enter")
    assert trigger.evaluate("node => node === document.activeElement")
    settle(page)
    assert page.locator("#auto-eat-chest-settings").is_visible()
    assert page.locator("#auto-eat-coordinate").evaluate("node => node.required && !node.disabled")

    coordinate = page.locator("#auto-eat-coordinate + .task-select-trigger")
    coordinate.press("ArrowDown")
    assert menu.get_by_role("option").count() == 2
    assert "请选择" not in menu.inner_text()
    menu.get_by_role("option").first.press("Enter")
    assert page.locator("#auto-eat-coordinate").input_value() == "food"
    states = page.evaluate("""() => {
        const source = document.getElementById('auto-eat-source');
        const coordinate = document.getElementById('auto-eat-coordinate');
        const states = [];
        for (const value of ['inventory', 'chest', 'inventory', 'chest']) {
            source.value = value;
            source.dispatchEvent(new Event('input', {bubbles: true}));
            source.dispatchEvent(new Event('change', {bubbles: true}));
            states.push([coordinate.value, coordinate.required, coordinate.disabled]);
        }
        return states;
    }""")
    assert states == [["food", False, True], ["food", True, False]] * 2
    settle(page)
    assert page.locator("#auto-eat-coordinate").input_value() == "food"
    assert not page.locator("#auto-eat-chest-settings").evaluate("node => node.inert")
    page.wait_for_function("backend.tasks[0].settings.coordinateId === 'food'")


def test_rapid_disclosure_changes_keep_final_state_and_keyboard_focus(eat_page: Any) -> None:
    page = eat_page
    open_settings(page)
    page.evaluate("""() => {
        const button = document.querySelector('#auto-eat-list .pickup-task-expand');
        for (let index = 0; index < 4; index++) button.click();
    }""")
    settle(page)
    body = page.locator("#auto-eat-list .pickup-task-body")
    assert body.is_visible() and not body.evaluate("node => node.inert")
    trigger = page.locator("#auto-eat-source + .task-select-trigger")
    trigger.press("ArrowDown")
    page.locator(".task-select-menu:not([hidden]) [role=option]").first.press("Escape")
    assert trigger.evaluate("node => node === document.activeElement")
    assert page.locator(".task-select-menu:not([hidden])").count() == 0
    page.locator("#auto-eat-list .pickup-task-expand").click()
    settle(page)
    assert body.is_hidden() and body.evaluate("node => node.inert")
    assert page.evaluate("backend.requests.filter(item => item.method !== 'GET')") == []


def test_failed_listening_switch_preserves_settings_and_expansion(eat_page: Any) -> None:
    page = eat_page
    open_settings(page)
    page.evaluate("backend.failSwitch = true")
    page.get_by_role("switch").click()
    page.wait_for_function("backend.errors.length === 1")
    assert not page.get_by_role("switch").is_checked()
    assert page.locator("#auto-eat-list .pickup-task-expand").get_attribute(
        "aria-expanded"
    ) == "true"
    assert page.locator("#auto-eat-food").input_value() == "minecraft:bread"
    assert page.locator("#auto-eat-threshold").is_enabled()


def test_dropdown_opens_upwards_when_space_below_is_limited(eat_page: Any) -> None:
    page = eat_page
    page.set_viewport_size({"width": 700, "height": 360})
    page.emulate_media(reduced_motion="reduce")
    open_settings(page)
    trigger = page.locator("#auto-eat-source + .task-select-trigger")
    trigger.evaluate("node => node.scrollIntoView({block: 'end'})")
    trigger.click()
    menu = page.locator(".task-select-menu:not([hidden])")
    assert menu.get_attribute("data-placement") == "above"
    bounds = menu.bounding_box()
    trigger_bounds = trigger.bounding_box()
    assert bounds is not None and trigger_bounds is not None
    assert bounds["y"] >= 0
    assert bounds["y"] + bounds["height"] <= trigger_bounds["y"]


def test_new_draft_keeps_template_and_becomes_a_paused_saved_task(eat_page: Any) -> None:
    page = eat_page
    page.locator("#new-auto-eat").click()
    assert page.locator("#auto-eat-draft").is_visible()
    assert page.locator("#auto-eat-draft .pickup-task-header h3").count() == 1
    page.wait_for_function("backend.tasks.length === 2")
    assert page.locator("#auto-eat-draft").is_hidden()
    card = page.locator('#auto-eat-list article[data-task-id="eat/new"]')
    assert card.locator("h3").inner_text() == "自动进食 2"
    assert not card.get_by_role("switch").is_checked()
    assert card.locator("#auto-eat-form").is_visible()


@pytest.mark.parametrize("theme", ["green", "gray"])
@pytest.mark.parametrize("width", [360, 960])
def test_template_layout_and_dropdown_fit_both_themes(
    eat_page: Any, theme: str, width: int
) -> None:
    page = eat_page
    page.set_viewport_size({"width": width, "height": 900})
    page.evaluate("theme => document.documentElement.dataset.theme = theme", theme)
    page.emulate_media(reduced_motion="reduce")
    open_settings(page)
    rows = page.locator("#auto-eat-form > fieldset > .move-field")
    assert rows.count() == 4
    for row in rows.all():
        bounds = row.bounding_box()
        assert bounds is not None and bounds["height"] >= 82
    controls = page.locator(
        '#auto-eat-form input:visible, #auto-eat-form .task-select-trigger:visible'
    )
    for control in controls.all():
        bounds = control.bounding_box()
        assert bounds is not None and bounds["height"] == 42
        assert bounds["x"] >= 0 and bounds["x"] + bounds["width"] <= width
    if width > 600:
        right_edges = [control.bounding_box()["x"] + control.bounding_box()["width"]
                       for control in controls.all()]
        assert max(right_edges) - min(right_edges) < 1
    assert page.evaluate("document.documentElement.scrollWidth <= innerWidth")
    trigger = page.locator("#auto-eat-source + .task-select-trigger")
    trigger.click()
    menu = page.locator(".task-select-menu:not([hidden])")
    bounds = menu.bounding_box()
    assert bounds is not None and bounds["x"] >= 0
    assert bounds["x"] + bounds["width"] <= width
    assert menu.get_by_role("option", selected=True).count() == 1
    assert menu.evaluate("node => getComputedStyle(node).gap") == "6px"
