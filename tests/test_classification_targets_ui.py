import re
from collections.abc import Iterator
from pathlib import Path
from typing import Any

import pytest


WEB = Path(__file__).resolve().parents[1] / "src/main/resources/stowling-web"


@pytest.fixture
def target_page() -> Iterator[Any]:
    playwright = pytest.importorskip("playwright.sync_api")
    with playwright.sync_playwright() as runtime:
        try:
            browser = runtime.chromium.launch()
        except playwright.Error as error:
            pytest.skip(f"Playwright Chromium is required: {error}")
        page = browser.new_page(viewport={"width": 1100, "height": 1100})
        html = (WEB / "tasks.html").read_text(encoding="utf-8")
        draft = re.search(r'<article id="auto-task-draft"[\s\S]*?</article>', html)
        assert draft is not None
        page.set_content("""
            <html data-theme="green"><body data-page="tasks">
            <main style="max-width:900px;margin:24px">
                <button id="new-auto-task">拾取</button>
                <button id="new-classification-task">分类</button>
                <button id="refresh-move-tasks">刷新</button>
                <p id="auto-resources-message" hidden></p>
                <div id="auto-task-list"></div>
        """ + draft.group() + "</main></body></html>")
        for name in ("styles.css", "workbench.css", "pickup-task-settings.css"):
            page.add_style_tag(content=(WEB / name).read_text(encoding="utf-8"))
        page.add_script_tag(content="""
            window.taskResources = {
                coordinates: [], regions: [{id:'source', name:'物品来源容器'}],
                categoryZones: [{id:'a', name:'工具分类区'}, {id:'b', name:'建筑分类区'},
                    {id:'c', name:'材料分类区'}],
                catalog: {folders:[{id:'first', name:'主仓库'}, {id:'second', name:'副仓库'}],
                    placements:{'category-zone:a':'first', 'category-zone:b':'second',
                        'category-zone:c':'first'}}
            };
            window.taskItems = [{id:'sorter', name:'分类任务', kind:'CLASSIFICATION',
                enabled:false, collectionEnabled:false, classificationEnabled:true,
                cacheLocationRef:'region:source',
                classificationAreas:[{categoryZoneRef:'category-zone:b'},
                    {categoryZoneRef:'category-zone:a'}]}];
            window.Stowling = {
                /** @param {string} tag @param {string} css @param {string} text
                 * @returns {HTMLElement} */
                element(tag, css = '', text = '') {
                    const node = document.createElement(tag);
                    node.className = css;
                    node.textContent = text;
                    return node;
                },
                /** @returns {boolean} */
                isConnected() {return true;},
                /** @param {Function} callback @returns {void} */
                onConnected(callback) {window.connectTask = callback;},
                /** @param {string} path @returns {Promise<object>} */
                async request(path) {
                    if (path === '/v1/coordinate-resources') return taskResources;
                    if (path === '/v1/tasks') return {tasks:[]};
                    return {revision:'r1', items:taskItems};
                },
                /** @param {Error} error @returns {void} */
                handleError(error) {throw error;},
                /** @returns {void} */
                showToast() {}
            };
            window.StowlingTaskModel = {priorityLabel: () => '普通'};
            window.StowlingTaskCleanupSettings = {create: () => ({
                resources() {}, set() {}, read: () => ({})
            })};
            window.StowlingTaskAutosave = {
                /** @param {HTMLElement} form @param {Function} save @returns {object} */
                create(form, save) {
                    window.saveTaskDraft = save;
                    return {schedule() {}, cancel() {}};
                },
                /** @param {string} url @param {string} method @param {object} payload
                 * @returns {Promise<object>} */
                async write(url, method, payload) {
                    window.savedTask = structuredClone(payload);
                    const item = {...payload, id:'sorter', name:'分类任务'};
                    window.taskItems = [item];
                    return {revision:'r2', item};
                }
            };
        """)
        for name in (
            "settings-ui.js", "resource-picker.js", "task-select-ui.js",
            "empty-box-settings.js", "auto-classify-task-model.js", "auto-classify-ui.js",
        ):
            page.add_script_tag(content=(WEB / name).read_text(encoding="utf-8"))
        page.evaluate("connectTask()")
        page.locator("#auto-task-list .pickup-task-expand").click()
        yield page
        browser.close()


@pytest.mark.parametrize("theme", ["green", "gray"])
@pytest.mark.parametrize("width", [1100, 420])
def test_target_menu_uses_standard_controls_and_keeps_execution_order(
    target_page: Any, theme: str, width: int,
) -> None:
    page = target_page
    page.set_viewport_size({"width": width, "height": 1100})
    page.evaluate("theme => document.documentElement.dataset.theme = theme", theme)
    trigger = page.locator(".task-select-wrap:has(#auto-classification-targets) > button")
    assert trigger.inner_text() == "建筑分类区、工具分类区"
    assert page.locator("#auto-classification-order").inner_text() == (
        "执行顺序：建筑分类区 → 工具分类区"
    )
    assert page.locator("#add-auto-classification-area, .auto-classification-area").count() == 0
    assert trigger.get_attribute("aria-describedby") == "auto-target-help"
    source = page.locator(".task-select-wrap:has(#auto-cache-location) > button")
    assert abs(trigger.bounding_box()["width"] - source.bounding_box()["width"]) <= 1
    assert trigger.bounding_box()["width"] <= width - 48

    trigger.focus()
    page.keyboard.press("ArrowDown")
    page.keyboard.press("Space")
    assert trigger.inner_text() == "建筑分类区"
    assert page.get_by_role("listbox").is_visible()
    page.keyboard.press("Space")
    assert trigger.inner_text() == "建筑分类区、工具分类区"
    page.keyboard.press("Escape")
    trigger.click()
    assert page.get_by_role("listbox").get_attribute("aria-multiselectable") == "true"
    page.get_by_role("option", name="材料分类区").click()
    assert trigger.inner_text() == "已选 3 个分类区"
    assert page.get_by_role("listbox").is_visible()
    assert page.get_by_role("option", name="材料分类区").get_attribute("aria-selected") == "true"
    page.keyboard.press("Escape")
    assert trigger.evaluate("node => node === document.activeElement")
    page.evaluate("saveTaskDraft()")
    assert page.evaluate("savedTask.classificationAreas.map(area => area.categoryZoneRef)") == [
        "category-zone:b", "category-zone:a", "category-zone:c",
    ]
    page.evaluate("connectTask()")
    assert page.locator("#auto-classification-order").inner_text() == (
        "执行顺序：建筑分类区 → 工具分类区 → 材料分类区"
    )


def test_deleted_target_can_be_unchecked_without_losing_other_targets(target_page: Any) -> None:
    page = target_page
    page.evaluate("""async () => {
        taskItems[0].classificationAreas.splice(1, 0, {categoryZoneRef:'category-zone:deleted'});
        await connectTask();
    }""")
    trigger = page.locator(".task-select-wrap:has(#auto-classification-targets) > button")
    trigger.click()
    missing = page.get_by_role("option", name="已删除的分类区")
    assert missing.is_enabled()
    assert missing.get_attribute("aria-selected") == "true"
    missing.click()
    page.keyboard.press("Escape")
    page.evaluate("saveTaskDraft()")
    assert page.evaluate("savedTask.classificationAreas.map(area => area.categoryZoneRef)") == [
        "category-zone:b", "category-zone:a",
    ]


def test_empty_target_menu_has_guidance_and_does_not_choose_a_resource(target_page: Any) -> None:
    page = target_page
    page.evaluate("""async () => {
        taskItems[0].classificationAreas = [];
        taskResources.categoryZones = [];
        await connectTask();
    }""")
    trigger = page.locator(".task-select-wrap:has(#auto-classification-targets) > button")
    assert trigger.inner_text() == "请选择目标分类区"
    trigger.focus()
    page.keyboard.press("ArrowDown")
    assert page.get_by_role("option").count() == 0
    assert page.locator(".task-select-menu:not([hidden]) .task-select-empty").inner_text() == (
        "暂无分类区，请先到坐标页配置。"
    )
    assert page.locator("#auto-classification-targets").evaluate(
        "select => select.selectedOptions.length"
    ) == 0
