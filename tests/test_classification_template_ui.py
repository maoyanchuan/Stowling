import re
from collections.abc import Iterator
from pathlib import Path
from typing import Any

import pytest


WEB = Path(__file__).resolve().parents[1] / "src/main/resources/stowling-web"


@pytest.fixture
def classification_page() -> Iterator[Any]:
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
            const ids = ['auto-task-list', 'auto-task-draft', 'auto-resources-message',
                'new-auto-task', 'new-classification-task', 'refresh-move-tasks'];
            document.body.replaceChildren(...ids.map(id => document.getElementById(id)));
            document.body.style.padding = '16px';
        }""")
        for name in ("styles.css", "workbench.css", "pickup-task-settings.css"):
            page.add_style_tag(content=(WEB / name).read_text(encoding="utf-8"))
        page.add_script_tag(content=r"""
            window.backend = {revision: '1', requests: [], errors: [], handlers: [],
                failSwitch: false, item: {
                    id: 'classification/1', kind: 'CLASSIFICATION', name: '分类 · 主仓库',
                    enabled: false, collectionEnabled: false, classificationEnabled: true,
                    cacheLocationRef: 'region:cache', collectionPriority: 'NORMAL',
                    classificationAreas: [{categoryZoneRef: 'category-zone:zone',
                        storagePolicy: {mode: 'BOX_ONLY', loosePercent: 25}}],
                    overflowEnabled: false, overflowDestinationRef: 'warehouse:warehouse'
                }};
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
                    if (path === '/v1/tasks') return {tasks: []};
                    if (method === 'GET') {
                        return structuredClone({revision: backend.revision, items: [backend.item]});
                    }
                    if (backend.failSwitch && method === 'POST') throw new Error('switch failed');
                    if (method === 'PUT') {
                        Object.assign(backend.item, body);
                        backend.revision = String(Number(backend.revision) + 1);
                    }
                    return structuredClone({revision: backend.revision, item: backend.item});
                }
            };
            window.StowlingResourcePicker = {
                /** @returns {Promise<object>} */
                async load() {
                    return {coordinates: [{id: 'box', name: '供应箱'}],
                        regions: [{id: 'cache', name: '主仓缓存'}, {id: 'reserve', name: '备用缓存'}],
                        categoryZones: [{id: 'zone', name: '主仓分类区', bindings: [
                            {storagePolicy: {mode: 'NORMAL'}},
                            {storagePolicy: {mode: 'MIXED', looseSlots: 19}}]}],
                        catalog: {warehouses: [{id: 'warehouse', name: '溢出仓库'}]}};
                },
                /** @returns {void} */
                group() {}
            };
        """)
        for name in (
            "settings-ui.js", "task-select-ui.js", "v2-task-model.js",
            "auto-classify-task-model.js",
            "task-cleanup-settings.js", "empty-box-settings.js", "task-autosave.js",
            "classification-remainder-settings.js",
            "auto-classify-ui.js",
        ):
            page.add_script_tag(content=(WEB / name).read_text(encoding="utf-8"))
        page.evaluate("async () => {for (const handler of backend.handlers) await handler();}")
        yield page
        browser.close()


def settle(page: Any) -> None:
    page.wait_for_function("document.getAnimations().length === 0")


def open_settings(page: Any) -> None:
    page.locator("#auto-task-list .pickup-task-expand").click()
    settle(page)


def test_classification_header_separates_real_execution_and_configuration(
    classification_page: Any,
) -> None:
    page = classification_page
    card = page.locator("#auto-task-list article")
    header = card.locator(".pickup-task-header")
    assert header.evaluate("node => [...node.children].map(child => child.tagName)") == [
        "H3", "BUTTON", "INPUT",
    ]
    assert header.locator("h3 svg").count() == 1
    assert card.locator(".pickup-task-body").is_hidden()
    toggle = header.get_by_role("switch")
    bounds = toggle.bounding_box()
    assert bounds is not None and (bounds["width"], bounds["height"]) == (56, 32)
    open_settings(page)
    toggle.check()
    assert toggle.is_checked()
    assert card.locator(".pickup-task-expand").get_attribute("aria-expanded") == "true"
    assert page.locator("#auto-cache-location").is_enabled()
    card.locator(".pickup-task-expand").press("Space")
    settle(page)
    assert card.locator(".pickup-task-body").is_hidden()
    assert toggle.is_checked()
    toggle.uncheck()
    assert not toggle.is_checked()
    assert card.locator(".pickup-task-body").is_hidden()
    assert page.evaluate("backend.requests.filter(item => item.method === 'POST')") == [
        {"path": "/v1/auto-classify-tasks/classification%2F1/start", "method": "POST",
         "body": {"revision": "1"}},
        {"path": "/v1/auto-classify-tasks/classification%2F1/stop", "method": "POST",
         "body": {"revision": "1"}},
    ]


@pytest.mark.parametrize("run_state", ["QUEUED", "RUNNING", "INTERRUPTING"])
def test_monitor_updates_switch_without_closing_settings(
    classification_page: Any, run_state: str,
) -> None:
    page = classification_page
    open_settings(page)
    page.evaluate("""state => document.dispatchEvent(new CustomEvent(
        'stowling:refresh-task-status', {detail: {pending: [
            {taskId: 'classification/1', state}
        ]}}))""", run_state)
    toggle = page.locator("#auto-task-list .pickup-task-header").get_by_role("switch")
    assert toggle.is_checked()
    page.evaluate("""() => document.dispatchEvent(new CustomEvent(
        'stowling:refresh-task-status', {detail: {pending: []}}))""")
    assert not toggle.is_checked()
    assert page.locator("#auto-cache-location").is_enabled()
    assert page.locator("#auto-cache-location").input_value() == "region:cache"
    assert page.locator("#auto-task-list .pickup-task-expand").get_attribute(
        "aria-expanded"
    ) == "true"


def test_failed_run_request_restores_switch_and_preserves_fields(classification_page: Any) -> None:
    page = classification_page
    open_settings(page)
    page.evaluate("backend.failSwitch = true")
    toggle = page.locator("#auto-task-list .pickup-task-header").get_by_role("switch")
    toggle.click()
    page.wait_for_function("backend.errors.length === 1")
    assert not toggle.is_checked()
    assert page.locator("#auto-cache-location").input_value() == "region:cache"
    assert page.locator("#auto-task-form").is_visible()


def test_paused_classification_autosaves_without_starting(classification_page: Any) -> None:
    page = classification_page
    open_settings(page)
    page.locator(".move-field:has(#auto-cache-location) .task-select-trigger").click()
    page.get_by_role("option", name="备用缓存").click()
    page.wait_for_function("backend.item.cacheLocationRef === 'region:reserve'")
    toggle = page.locator("#auto-task-list .pickup-task-header").get_by_role("switch")
    assert not toggle.is_checked()
    assert page.locator("#auto-cache-location").is_enabled()
    assert page.evaluate("backend.requests.filter(item => item.method === 'POST')") == []
    writes = page.evaluate("backend.requests.filter(item => item.method === 'PUT')")
    assert writes and all("enabled" not in item["body"] for item in writes)


def test_classification_uses_coordinate_storage_settings_without_a_task_editor(
    classification_page: Any,
) -> None:
    page = classification_page
    open_settings(page)
    area = page.locator("#auto-classification-areas .auto-classification-area")
    area.locator(":scope > summary").click()
    settle(page)
    assert area.get_by_text(
        "储存模式和识别方式使用坐标页面中所选分类区及绑定组的设置。"
    ).is_visible()
    assert area.locator("select").count() == 1
    assert area.locator("select").input_value() == "zone"
    assert area.locator(".storage-policy-editor").count() == 0
    assert area.locator("details").count() == 0
    assert page.evaluate("typeof window.StowlingStoragePolicyEditor") == "undefined"
    page.locator(".move-field:has(#auto-cache-location) .task-select-trigger").click()
    page.get_by_role("option", name="备用缓存").click()
    page.wait_for_function("backend.item.cacheLocationRef === 'region:reserve'")
    writes = page.evaluate("backend.requests.filter(item => item.method === 'PUT')")
    assert writes and all(item["body"]["classificationAreas"] == [
        {"categoryZoneRef": "category-zone:zone"},
    ] for item in writes)


def test_warehouse_switch_and_disclosure_keep_the_selected_resource(classification_page: Any) -> None:
    page = classification_page
    open_settings(page)
    page.locator("#auto-overflow-toggle").click()
    settle(page)
    page.locator("#robot-warehouse-enabled").check()
    assert page.locator("#robot-warehouse-body").is_hidden()
    page.locator("#robot-warehouse-toggle").click()
    settle(page)
    assert page.locator("#robot-warehouse-location").input_value() == "warehouse:warehouse"
    page.locator("#robot-warehouse-toggle").click()
    settle(page)
    assert page.locator("#robot-warehouse-enabled").is_checked()
    page.locator("#robot-warehouse-toggle").click()
    settle(page)
    assert page.locator("#robot-warehouse-location").input_value() == "warehouse:warehouse"


def test_remainder_destinations_save_and_keep_drafts_when_disabled(classification_page: Any) -> None:
    page = classification_page
    open_settings(page)
    enabled = page.locator("#auto-unclassifiable-enabled")
    enabled.check()
    settle(page)
    location = page.locator(".move-field:has(#auto-unclassifiable-location)")
    location.locator(".task-select-trigger").click()
    page.get_by_role("option", name="备用缓存").click()
    page.wait_for_function(
        "backend.item.remainderPolicy?.unclassifiableDestinationRef === 'region:reserve'"
    )
    enabled.uncheck()
    page.wait_for_function("backend.item.remainderPolicy.unclassifiableEnabled === false")
    assert page.evaluate("backend.item.remainderPolicy.unclassifiableDestinationRef") == (
        "region:reserve"
    )
    enabled.check()
    settle(page)
    assert page.locator("#auto-unclassifiable-location").input_value() == "region:reserve"
    page.locator("#auto-unclassifiable-toggle").click()
    settle(page)
    assert enabled.is_checked()
    assert page.locator("#auto-unclassifiable-location").is_hidden()
    assert page.evaluate("backend.requests.filter(item => item.method === 'POST')") == []


@pytest.mark.parametrize("theme,width", [("green", 1000), ("gray", 1000), ("green", 390)])
def test_overflow_contains_robot_and_keeps_independent_switches_and_keyboard_selection(
    classification_page: Any, theme: str, width: int,
) -> None:
    page = classification_page
    page.set_viewport_size({"width": width, "height": 1000})
    page.evaluate("theme => document.documentElement.dataset.theme = theme", theme)
    open_settings(page)
    section = page.locator("#auto-overflow-settings")
    assert section.locator("#robot-warehouse-settings").count() == 1
    assert page.locator("#robot-warehouse-enabled").is_hidden()
    page.locator("#auto-overflow-toggle").click()
    settle(page)
    page.locator("#auto-overflow-storage-enabled").check()
    settle(page)
    trigger = page.locator(".move-field:has(#auto-overflow-storage-location) .task-select-trigger")
    trigger.press("ArrowDown")
    menu = page.locator(".task-select-menu:not([hidden])")
    menu.get_by_role("option", name="备用缓存").press("Enter")
    assert trigger.evaluate("node => node === document.activeElement")
    page.wait_for_function(
        "backend.item.remainderPolicy?.overflowDestinationRef === 'region:reserve'"
    )
    assert not page.locator("#robot-warehouse-enabled").is_checked()
    page.locator("#robot-warehouse-enabled").check()
    page.wait_for_function("backend.item.overflowEnabled === true")
    page.locator("#auto-overflow-toggle").click()
    settle(page)
    assert page.locator("#robot-warehouse-enabled").is_checked()
    assert page.locator("#auto-overflow-storage-enabled").is_checked()
    assert page.locator("#robot-warehouse-enabled").is_hidden()
    page.locator("#auto-overflow-toggle").click()
    settle(page)
    assert page.locator("#auto-overflow-storage-location").input_value() == "region:reserve"
    assert page.locator("#robot-warehouse-location").input_value() == "warehouse:warehouse"
    assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth")


@pytest.mark.parametrize("theme,width", [("green", 1000), ("gray", 1000), ("green", 390)])
def test_template_dropdown_and_rows_fit_both_themes_and_small_windows(
    classification_page: Any, theme: str, width: int,
) -> None:
    page = classification_page
    page.set_viewport_size({"width": width, "height": 1000})
    page.evaluate("theme => document.documentElement.dataset.theme = theme", theme)
    open_settings(page)
    row = page.locator(".move-field:has(#auto-cache-location)")
    assert row.evaluate("node => node.getBoundingClientRect().height") >= 82
    trigger = row.locator(".task-select-trigger")
    trigger.press("ArrowDown")
    menu = page.locator(".task-select-menu:not([hidden])")
    assert menu.get_by_role("option").count() == 4
    assert menu.get_by_role("option", selected=True).inner_text() == "区域 · 主仓缓存"
    bounds = menu.bounding_box()
    assert bounds is not None and bounds["x"] >= 0
    assert bounds["x"] + bounds["width"] <= width
    menu.get_by_role("option", name="主仓缓存").press("Escape")
    assert trigger.evaluate("node => node === document.activeElement")
    assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth")


def test_area_removal_is_inside_expanded_settings(classification_page: Any) -> None:
    page = classification_page
    open_settings(page)
    area = page.locator(".auto-classification-area")
    assert area.evaluate("node => getComputedStyle(node).borderTopWidth") == "0px"
    assert area.locator("summary button").count() == 0
    assert area.get_by_role("button", name="移除").is_hidden()
    area.locator("summary").click()
    settle(page)
    assert area.get_by_role("button", name="移除").is_visible()
    assert area.locator("select").first.input_value() == "zone"


def test_empty_box_warehouse_retains_drafts_when_each_switch_is_disabled(
    classification_page: Any,
) -> None:
    page = classification_page
    open_settings(page)
    host = page.locator("#auto-empty-box-settings")
    enabled = host.locator("#auto-empty-warehouse-enabled")
    enabled.check()
    settle(page)
    row = host.locator(".move-field:has(#auto-empty-warehouse-location)")
    row.locator(".task-select-trigger").click()
    page.get_by_role("option", name="备用缓存").click()
    retain = host.locator("#auto-empty-cache-retain")
    retain.uncheck()
    retain.check()
    settle(page)
    limit = host.locator("#auto-empty-cache-limit")
    limit.fill("12")
    limit.blur()
    page.wait_for_function("backend.item.cleanupPolicy?.emptyBoxWarehouse?.cacheLimit === 12")
    retain.uncheck()
    page.wait_for_function("backend.item.cleanupPolicy.emptyBoxWarehouse.retainInCache === false")
    enabled.uncheck()
    page.wait_for_function("backend.item.cleanupPolicy.emptyBoxWarehouse.enabled === false")
    assert page.evaluate("backend.item.cleanupPolicy.emptyBoxWarehouse.warehouseRef") == (
        "region:reserve"
    )
    enabled.check()
    settle(page)
    retain.check()
    settle(page)
    assert limit.input_value() == "12"
    assert host.locator("#auto-empty-warehouse-location").input_value() == "region:reserve"
    assert page.locator("#auto-task-list .pickup-task-header [role=switch]").is_checked() is False


@pytest.mark.parametrize("theme,width", [("green", 1000), ("gray", 1000), ("green", 390)])
def test_empty_box_warehouse_dropdown_supports_keyboard_and_fits_the_window(
    classification_page: Any, theme: str, width: int,
) -> None:
    page = classification_page
    page.set_viewport_size({"width": width, "height": 1000})
    page.evaluate("theme => document.documentElement.dataset.theme = theme", theme)
    open_settings(page)
    page.locator("#auto-empty-warehouse-enabled").check()
    settle(page)
    sections = page.locator("#auto-empty-box-settings .settings-option")
    assert sections.count() == 2
    for section in sections.all():
        assert section.evaluate("node => getComputedStyle(node).borderBottomWidth") == "0px"
    row = page.locator(".move-field:has(#auto-empty-warehouse-location)")
    trigger = row.locator(".task-select-trigger")
    trigger.press("ArrowDown")
    menu = page.locator(".task-select-menu:not([hidden])")
    assert menu.get_by_role("option").count() == 3
    menu.get_by_role("option", name="备用缓存").press("Enter")
    assert page.locator("#auto-empty-warehouse-location").input_value() == "region:reserve"
    assert trigger.evaluate("node => node === document.activeElement")
    assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth")
