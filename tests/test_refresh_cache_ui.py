import re
import shutil
import subprocess
from collections.abc import Iterator
from pathlib import Path
from typing import Any

import pytest


WEB = Path(__file__).resolve().parents[1] / "src" / "main" / "resources" / "stowling-web"


def test_refresh_task_has_range_switches_and_schedule_controls() -> None:
    html = (WEB / "tasks.html").read_text(encoding="utf-8")
    assert 'src="/refresh-cache-ui.js"' in html
    assert 'id="new-refresh-cache-task"' in html
    assert 'id="refresh-cache-target"' in html
    assert 'id="refresh-category-storage-only"' in html
    assert 'id="refresh-scan-markers"' not in html
    assert 'id="refresh-include-markers"' not in html
    for value in ("MANUAL", "DAILY", "INTERVAL"):
        assert f'value="{value}"' in html


def test_refresh_options_and_schedules_round_trip() -> None:
    node = shutil.which("node")
    if node is None:
        pytest.skip("Node.js is needed to exercise the console model")
    script = '''
const assert = require("node:assert/strict");
require(process.argv[1]);
const model = globalThis.StowlingRefreshCacheModel;
const draft = model.settings();
assert.deepEqual(draft.kinds, {chests: true, barrels: true, shulkerBoxes: true,
    otherContainers: false});
draft.targetRef = "category-zone:layout";
assert.equal(draft.categoryStorageOnlyEnabled, false);
draft.categoryStorageOnlyEnabled = true;
assert.deepEqual(model.settings(model.parameters(draft)), draft);
for (const key of ['reuseSafeHandEnabled', 'nearbyRouteEnabled']) {
    for (const value of ['true', 'false', 'invalid', '']) {
        const normalized = model.settings({...model.parameters(draft), [key]: value});
        assert.equal(Object.hasOwn(normalized, key), false);
        assert.equal(Object.hasOwn(model.parameters({...normalized, [key]: true}), key), false);
    }
}
assert.equal(model.parameters(draft).scanMarkers, "false");
assert.equal(model.parameters(draft).includeMarkersInInventory, "false");
assert.equal(model.settings({categoryStorageOnlyEnabled: "false"})
    .categoryStorageOnlyEnabled, false);
draft.targetRef = "region:region";
assert.equal(model.parameters(draft).includeMarkersInInventory, "false");
for (const key of Object.keys(draft.kinds)) draft.kinds[key] = false;
assert.throws(() => model.parameters(draft));
assert.deepEqual(model.schedulePayload({mode: "DAILY", time: "04:00", zoneId: "Asia/Shanghai"}),
    {mode: "DAILY", time: "04:00", zoneId: "Asia/Shanghai"});
assert.deepEqual(model.schedulePayload({mode: "INTERVAL", intervalMinutes: 60}),
    {mode: "INTERVAL", intervalMinutes: 60});
assert.throws(() => model.schedulePayload({mode: "INTERVAL", intervalMinutes: 0}));
assert.deepEqual(model.schedulePayload({mode: "MANUAL"}), {mode: "MANUAL"});
assert.deepEqual(model.taskSwitch(false, false, "QUEUED"),
    {checked: true, stopping: false, label: "排队中"});
assert.equal(model.taskSwitch(false, false, "SUCCEEDED").checked, false);
assert.equal(model.taskSwitch(false, false, "INTERRUPTING").checked, false);
assert.equal(model.taskSwitch(true, false, null).checked, false);
assert.equal(model.taskSwitch(true, true, null).checked, true);
'''
    subprocess.run([node, "-e", script, str(WEB / "refresh-cache-ui.js")], check=True)


def test_disabling_refresh_reloads_a_stale_revision_and_retries() -> None:
    node = shutil.which("node")
    if node is None:
        pytest.skip("Node.js is needed to exercise the console")
    script = '''
const assert = require("node:assert/strict");

class Element extends EventTarget {
    children = [];
    value = "";
    classList = {/** @returns {void} */ toggle() {}};
    /** @param {...Element} nodes @returns {void} */
    append(...nodes) { this.children.push(...nodes); }
    /** @param {...Element} nodes @returns {void} */
    replaceChildren(...nodes) { this.children = nodes; }
    /** @returns {Element} */
    querySelector() { return new Element(); }
    /** @returns {Element[]} */
    querySelectorAll() {
        return [Object.assign(new Element(), {
            dataset: {refreshKind: "chests"}, labels: [{textContent: "箱子"}]
        })];
    }
    /** @returns {void} */
    setAttribute() {}
    /** @returns {void} */
    focus() {}
}

const elements = new Map();
/** @param {string} id @returns {Element} */
function element(id) {
    if (!elements.has(id)) elements.set(id, new Element());
    return elements.get(id);
}
globalThis.document = Object.assign(new EventTarget(), {
    getElementById: element,
    /** @returns {Element} */
    createElementNS() { return new Element(); },
    /** @returns {Element} */
    createElement() { return new Element(); }
});
globalThis.Option = Element;
globalThis.CustomEvent = class extends Event {
    /** @param {string} type @param {object} options */
    constructor(type, options) {
        super(type, options);
        this.detail = options.detail;
    }
};

let task = {id: "refresh", name: "Refresh", steps: [{id: "step",
    moduleId: "REFRESH_CONTAINER_CACHE", parameters: {targetRef: "region:stock"}}]};
const schedules = {refresh: {mode: "DAILY", time: "04:00", zoneId: "Asia/Shanghai"}};
const requests = [];
const errors = [];
let connect;
let revision = "old";
let pendingAutosave;
globalThis.window = {
    /** @returns {void} */
    addEventListener() {},
    /** @param {Function} callback @returns {number} */
    setTimeout(callback) { pendingAutosave = callback; return 1; },
    /** @returns {void} */
    clearTimeout() { pendingAutosave = undefined; },
    StowlingResourcePicker: {
        load: async () => ({regions: [{id: "stock", name: "Stock"}]}),
        group: () => {}
    },
    StowlingTaskSelect: {
        /** @returns {object} */
        create() { return {/** @returns {void} */ refresh() {}}; }
    },
    StowlingSettings: {
        /** @param {Element} input @returns {Element} */
        switchControl(input) { return input; },
        /** @returns {object} */
        bindDisclosure() { return {/** @returns {void} */ setExpanded() {}}; },
        /** @returns {void} */
        reveal() {}
    },
    Stowling: {
        /** @returns {boolean} */
        isConnected() { return true; },
        /** @param {Function} callback @returns {void} */
        onConnected(callback) { connect = callback; },
        /** @param {string} tag @param {string} css @param {string} value @returns {Element} */
        element(tag, css, value) { return Object.assign(new Element(), {textContent: value}); },
        /** @param {Error} error @returns {void} */
        handleError(error) { errors.push(error); },
        /** @returns {void} */
        showToast() {},
        /** @param {string} path @param {object} options @returns {Promise<object>} */
        async request(path, options) {
            if (path === "/v1/regions") return {regions: [{id: "stock", name: "Stock"}]};
            if (path === "/v1/category-zones") return {categoryZones: []};
            if (path === "/v1/tasks") {
                requests.push(["GET", revision]);
                return {revision, tasks: [task], schedules, enabledSchedules: ["refresh"]};
            }
            if (options.method === "PUT") {
                assert.equal(path, "/v1/tasks/refresh");
                const body = JSON.parse(options.body);
                assert.equal("name" in body, false);
                requests.push(["PUT", body.revision, body.schedule.time]);
                task = {...task, steps: body.steps};
                return {revision: "edited", tasks: [task], schedules};
            }
            assert.equal(path, "/v1/tasks/refresh/stop");
            assert.equal(options.method, "POST");
            const submitted = JSON.parse(options.body).revision;
            requests.push(["STOP", submitted]);
            if (submitted !== revision) {
                throw Object.assign(new Error("stale"), {status: 409, code: "revision_conflict"});
            }
            return {revision: "paused", scheduleEnabled: false};
        }
    }
};

/** @returns {Element} */
function taskHeader() {
    return element("refresh-cache-list").children[0].children[0];
}

(async () => {
    require(process.argv[2]);
    require(process.argv[1]);
    await connect();
    const [, summary, toggle] = taskHeader().children;
    summary.dispatchEvent(new Event("click"));
    assert.equal(toggle.checked, true);
    revision = "latest";
    task = {...task, name: "Renamed externally"};
    toggle.checked = false;
    toggle.dispatchEvent(new Event("change"));
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(requests, [
        ["GET", "old"], ["STOP", "old"], ["GET", "latest"], ["STOP", "latest"]
    ]);
    assert.deepEqual(errors, []);
    assert.equal(taskHeader().children[2].checked, false);
    assert.equal(element("refresh-cache-list").children[0].children[2].textContent, "已暂停");
    assert.equal(taskHeader().children[0].children[1].textContent,
        "Renamed externally");
    element("refresh-daily-time").value = "05:00";
    element("refresh-cache-form").dispatchEvent(new Event("input"));
    element("refresh-daily-time").value = "06:00";
    element("refresh-cache-form").dispatchEvent(new Event("input"));
    pendingAutosave();
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(requests.slice(4), [["PUT", "paused", "06:00"]]);
    assert.equal(element("refresh-cache-message").textContent, "已自动保存");
})().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
'''
    subprocess.run(
        [node, "-e", script, str(WEB / "refresh-cache-ui.js"), str(WEB / "task-autosave.js")],
        check=True,
    )


@pytest.fixture
def refresh_page() -> Iterator[Any]:
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
            const ids = ['refresh-cache-editor', 'refresh-cache-list',
                'new-refresh-cache-task', 'refresh-move-tasks', 'task-debug-mode'];
            document.body.replaceChildren(...ids.map(id => document.getElementById(id)));
            document.body.style.padding = '16px';
        }""")
        for name in ("styles.css", "workbench.css", "pickup-task-settings.css"):
            page.add_style_tag(content=(WEB / name).read_text(encoding="utf-8"))
        page.add_script_tag(content=r"""
            window.backend = {
                revision: '1', requests: [], errors: [], handlers: [], schedules: {},
                enabledSchedules: [], runState: null, failSwitch: false,
                tasks: [{id: 'refresh/1', name: '刷新缓存', priority: 'NORMAL',
                    steps: [{id: 'step', moduleId: 'REFRESH_CONTAINER_CACHE',
                        parameters: {targetRef: 'region:stock', chests: 'true',
                            barrels: 'true', shulkerBoxes: 'true', otherContainers: 'false'}}]}]
            };
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
                    if (path === '/v1/coordinate-resources') return {regions: [
                        {id: 'stock', name: '主仓选区'}, {id: 'reserve', name: '备用仓选区'}
                    ], categoryZones: [{id: 'category', name: '分类仓库'}], catalog: {
                        folders: [{id: 'warehouse-folder', name: '仓库资源'}],
                        placements: {'category-zone:category': 'warehouse-folder'}
                    }};
                    const scheduled = backend.schedules[backend.tasks[0].id];
                    let taskId;
                    if (method === 'POST' && /\/(start|stop)$/.test(path)) {
                        if (backend.failSwitch) throw new Error('切换失败');
                        const enabled = path.endsWith('/start');
                        backend.enabledSchedules = scheduled && enabled ? ['refresh/1'] : [];
                        backend.runState = !scheduled && enabled ? 'QUEUED' : null;
                    } else if (method === 'PUT') {
                        backend.tasks[0].steps = body.steps;
                        taskId = backend.tasks[0].id;
                        if (body.schedule.mode === 'MANUAL') delete backend.schedules[taskId];
                        else backend.schedules[taskId] = body.schedule;
                    } else if (method === 'POST') {
                        taskId = 'refresh/new';
                        backend.tasks.push({id: taskId, name: '刷新缓存 2', steps: body.steps});
                    }
                    if (method !== 'GET') {
                        backend.revision = String(Number(backend.revision) + 1);
                    }
                    return structuredClone({revision: backend.revision, tasks: backend.tasks,
                        schedules: backend.schedules, enabledSchedules: backend.enabledSchedules,
                        scheduleEnabled: backend.enabledSchedules.length > 0,
                        runState: backend.runState, taskId});
                }
            };
        """)
        for name in ("resource-picker.js", "settings-ui.js", "task-select-ui.js",
                     "task-debug-mode.js", "task-autosave.js", "refresh-cache-ui.js"):
            page.add_script_tag(content=(WEB / name).read_text(encoding="utf-8"))
        page.evaluate("async () => {for (const handler of backend.handlers) await handler();}")
        yield page
        browser.close()


def settle_refresh(page: Any) -> None:
    page.evaluate("() => new Promise(resolve => requestAnimationFrame("
                  "() => requestAnimationFrame(resolve)))")
    page.wait_for_function("document.getAnimations().length === 0")


def open_refresh(page: Any) -> None:
    page.locator("#refresh-cache-list .pickup-task-expand").click()
    settle_refresh(page)


def select_refresh_mode(page: Any, name: str) -> None:
    page.locator("#refresh-schedule-mode + .task-select-trigger").click()
    page.locator(".task-select-menu:not([hidden])").get_by_role(
        "option", name=name
    ).click()
    settle_refresh(page)
    page.wait_for_function("document.querySelector('#refresh-cache-message').textContent"
                           " === '已自动保存'")
    settle_refresh(page)


@pytest.mark.parametrize("theme,width", [
    ("green", 1000), ("gray", 1000), ("green", 390), ("gray", 390)
])
def test_refresh_template_layout_and_independent_run_switch(
    refresh_page: Any, theme: str, width: int
) -> None:
    page = refresh_page
    page.set_viewport_size({"width": width, "height": 1000})
    page.evaluate("theme => document.documentElement.dataset.theme = theme", theme)
    card = page.locator("#refresh-cache-list article")
    header = card.locator(".pickup-task-header")
    assert header.evaluate("node => [...node.children].map(child => child.tagName)") == [
        "H3", "BUTTON", "INPUT"
    ]
    assert header.locator("h3").inner_text() == "刷新缓存"
    assert header.locator("h3 svg").count() == 1
    bounds = header.bounding_box()
    switch = header.get_by_role("switch").bounding_box()
    assert bounds is not None and bounds["height"] >= 70
    assert switch is not None and (switch["width"], switch["height"]) == (56, 32)
    assert card.locator(".pickup-task-body").evaluate("node => node.hidden && node.inert")

    open_refresh(page)
    assert page.locator("#refresh-cache-target").is_enabled()
    assert page.locator("#refresh-cache-form").get_by_role("button", name="关闭").count() == 0
    target = page.locator("#refresh-cache-target + .task-select-trigger")
    row = target.locator("xpath=../..")
    row_bounds = row.bounding_box()
    target_bounds = target.bounding_box()
    assert row_bounds is not None and row_bounds["height"] >= 82
    assert target_bounds is not None and target_bounds["height"] == 42
    assert page.evaluate("document.documentElement.scrollWidth <= innerWidth")
    before = row.bounding_box()
    row.hover()
    assert row.bounding_box() == before

    assert page.evaluate("backend.requests.filter(item => item.method !== 'GET')") == []
    header.get_by_role("switch").check()
    page.wait_for_function("backend.runState === 'QUEUED'")
    assert page.locator("#refresh-cache-target").is_enabled()
    assert header.locator("button").get_attribute("aria-expanded") == "true"
    header.locator("button").click()
    settle_refresh(page)
    assert header.get_by_role("switch").is_checked()
    assert card.locator(".pickup-task-body").is_hidden()
    header.get_by_role("switch").uncheck()
    page.wait_for_function("backend.runState === null")
    assert header.locator("button").get_attribute("aria-expanded") == "false"
    assert page.evaluate("backend.requests.filter(item => item.method === 'POST')") == [
        {"path": "/v1/tasks/refresh%2F1/start", "method": "POST", "body": {"revision": "1"}},
        {"path": "/v1/tasks/refresh%2F1/stop", "method": "POST", "body": {"revision": "2"}},
    ]


def test_refresh_container_disclosure_preserves_values_and_animates(refresh_page: Any) -> None:
    page = refresh_page
    open_refresh(page)
    summary = page.locator("#refresh-kind-summary")
    arrow = summary.locator(".settings-chevron")
    assert arrow.count() == 1
    assert arrow.locator("svg").count() == 0
    assert arrow.evaluate("node => getComputedStyle(node).borderRightWidth") == "2px"
    assert arrow.evaluate("node => getComputedStyle(node).borderBottomWidth") == "2px"
    summary.focus()
    summary.press("Enter")
    settle_refresh(page)
    page.locator('[data-refresh-kind="barrels"]').uncheck()
    page.wait_for_function("backend.tasks[0].steps[0].parameters.barrels === 'false'")
    assert page.locator("#refresh-kind-selection").inner_text() == "箱子、潜影盒"
    summary.press("Space")
    settle_refresh(page)
    assert page.locator("#refresh-kind-options").evaluate("node => node.hidden && node.inert")
    summary.press("Enter")
    settle_refresh(page)
    assert not page.locator('[data-refresh-kind="barrels"]').is_checked()
    page.evaluate("""() => {
        const button = document.querySelector('#refresh-cache-list .pickup-task-expand');
        button.click(); button.click(); button.click(); button.click();
    }""")
    settle_refresh(page)
    body = page.locator("#refresh-cache-list .pickup-task-body")
    assert body.is_visible()
    assert body.evaluate("node => !node.inert && node.style.overflow === ''")
    assert not page.locator('[data-refresh-kind="barrels"]').is_checked()
    assert page.evaluate("backend.requests.every(item => item.method !== 'POST')")


def test_refresh_schedule_drafts_survive_strategy_changes(refresh_page: Any) -> None:
    page = refresh_page
    open_refresh(page)
    select_refresh_mode(page, "每天固定时间")
    page.locator("#refresh-daily-time").fill("06:15")
    page.wait_for_function("backend.schedules['refresh/1'].time === '06:15'")
    settle_refresh(page)
    select_refresh_mode(page, "每隔指定时长")
    assert page.locator("#refresh-daily-field").evaluate("node => node.hidden && node.inert")
    page.locator("#refresh-interval").fill("3")
    page.wait_for_function("backend.schedules['refresh/1'].intervalMinutes === 180")
    settle_refresh(page)
    select_refresh_mode(page, "仅手动")
    assert page.locator("#refresh-interval-field").evaluate("node => node.hidden && node.inert")
    assert page.locator("#refresh-interval").is_disabled()
    select_refresh_mode(page, "每天固定时间")
    assert page.locator("#refresh-daily-time").input_value() == "06:15"
    select_refresh_mode(page, "每隔指定时长")
    assert page.locator("#refresh-interval").input_value() == "3"
    assert page.locator("#refresh-interval-unit").input_value() == "60"
    assert not page.locator("#refresh-cache-list .pickup-task-header input").is_checked()
    assert page.evaluate("backend.requests.every(item => item.method !== 'POST')")


def test_refresh_dropdown_keyboard_and_upward_placement(refresh_page: Any) -> None:
    page = refresh_page
    open_refresh(page)
    target = page.locator("#refresh-cache-target + .task-select-trigger")
    target.press("ArrowDown")
    menu = page.locator(".task-select-menu:not([hidden])")
    assert menu.get_by_role("option").count() == 3
    assert "选择普通区域" not in menu.inner_text()
    assert menu.evaluate("node => getComputedStyle(node).rowGap") == "6px"
    menu.get_by_role("option", name="区域 · 备用仓选区").press("Enter")
    assert target.evaluate("node => node === document.activeElement")
    page.wait_for_function("backend.tasks[0].steps[0].parameters.targetRef === 'region:reserve'")
    settle_refresh(page)
    page.set_viewport_size({"width": 800, "height": 420})
    mode = page.locator("#refresh-schedule-mode + .task-select-trigger")
    mode.evaluate("node => node.scrollIntoView({block: 'end'})")
    settle_refresh(page)
    mode.press("ArrowDown")
    assert menu.get_attribute("data-placement") == "above"
    assert menu.get_by_role("option", selected=True).count() == 1
    menu.get_by_role("option").first.press("Escape")
    assert menu.count() == 0
    assert mode.evaluate("node => node === document.activeElement")


def test_refresh_range_only_shows_dropdown_even_without_regions(refresh_page: Any) -> None:
    page = refresh_page
    open_refresh(page)
    row = page.locator("#refresh-cache-target").locator("xpath=../..")
    assert row.locator(".resource-picker-controls, a").count() == 0
    assert row.locator(".task-select-trigger").count() == 1
    page.evaluate("""async () => {
        const select = document.getElementById('refresh-cache-target');
        select.replaceChildren(new Option('选择普通区域', ''));
        StowlingResourcePicker.group(select, undefined, 'regions', false);
        await StowlingResourcePicker.load();
    }""")
    assert row.locator(".resource-picker-controls, a").count() == 0
    trigger = row.locator(".task-select-trigger")
    trigger.click()
    assert page.locator(".task-select-menu:not([hidden])").get_by_role("option").count() == 0
    page.keyboard.press("Escape")
    assert trigger.evaluate("node => node === document.activeElement")


def test_refresh_run_failure_restores_switch_and_draft_stays_editable(refresh_page: Any) -> None:
    page = refresh_page
    open_refresh(page)
    page.evaluate("backend.failSwitch = true")
    page.locator("#refresh-cache-list .pickup-task-header input").click()
    page.wait_for_function("backend.errors.length === 1")
    assert not page.locator("#refresh-cache-list .pickup-task-header input").is_checked()
    assert page.locator("#refresh-cache-target").is_enabled()
    page.locator("#new-refresh-cache-task").click()
    settle_refresh(page)
    draft = page.locator("#refresh-cache-editor")
    assert draft.is_visible()
    assert draft.locator(".pickup-task-header input").is_disabled()
    assert page.locator("#refresh-cache-target").is_enabled()
    assert page.locator("#refresh-cache-target").input_value() == ""


@pytest.mark.parametrize("theme,width", [
    ("green", 1000), ("gray", 1000), ("green", 390), ("gray", 390)
])
def test_refresh_category_storage_is_a_normal_setting_and_retains_its_value(
    refresh_page: Any, theme: str, width: int
) -> None:
    page = refresh_page
    page.set_viewport_size({"width": width, "height": 1200})
    page.evaluate("theme => document.documentElement.dataset.theme = theme", theme)
    open_refresh(page)
    switch = page.locator("#refresh-category-storage-only")
    run = page.locator("#refresh-cache-list .pickup-task-header input")
    assert switch.is_visible()
    assert not switch.is_checked()
    assert page.locator("#refresh-experiments-settings").count() == 0
    assert page.locator("#refresh-reuse-safe-hand, #refresh-nearby-route").count() == 0
    assert switch.evaluate("node => !node.closest('[hidden], [inert]')")
    assert switch.evaluate("node => Boolean(node.closest('#refresh-cache-form'))")

    page.locator("#refresh-cache-target + .task-select-trigger").click()
    menu = page.locator(".task-select-menu:not([hidden])")
    assert "仓库资源" in menu.inner_text()
    menu.get_by_role("option", name="分类区 · 分类仓库").click()
    page.wait_for_function(
        "backend.tasks[0].steps[0].parameters.targetRef === 'category-zone:category'"
    )
    settle_refresh(page)
    assert run.is_disabled()
    assert "请启用下方" in page.locator("#refresh-cache-target-note").inner_text()
    switch.focus()
    switch.press("Space")
    page.wait_for_function(
        "backend.tasks[0].steps[0].parameters.categoryStorageOnlyEnabled === 'true'"
    )
    settle_refresh(page)
    saved = page.evaluate("backend.tasks[0].steps[0].parameters")
    assert saved["scanMarkers"] == saved["includeMarkersInInventory"] == "false"
    assert "reuseSafeHandEnabled" not in saved and "nearbyRouteEnabled" not in saved
    assert run.is_enabled() and not run.is_checked()
    assert "不打开识别区箱子" in page.locator("#refresh-cache-target-note").inner_text()
    assert page.evaluate("document.documentElement.scrollWidth <= innerWidth")
    switch_bounds = switch.bounding_box()
    row_bounds = switch.locator("xpath=..").bounding_box()
    assert switch_bounds is not None and row_bounds is not None
    assert (switch_bounds["width"], switch_bounds["height"]) == (56, 32)
    assert switch_bounds["x"] + switch_bounds["width"] == row_bounds["x"] + row_bounds["width"]
    screenshot = WEB.parents[3] / "build" / "reports" / "refresh-cache-ui"
    screenshot.mkdir(parents=True, exist_ok=True)
    page.screenshot(path=str(screenshot / f"mainline-{theme}-{width}.png"), full_page=True)

    writes = page.evaluate("backend.requests.filter(item => item.method !== 'GET').length")
    page.locator("#task-debug-mode").check()
    settle_refresh(page)
    assert switch.is_visible() and switch.is_checked()
    assert page.locator("#refresh-experiments-settings").count() == 0
    page.locator("#task-debug-mode").uncheck()
    page.locator("#refresh-cache-list .pickup-task-expand").click()
    settle_refresh(page)
    assert page.locator("#refresh-cache-list > article > p").count() == 0
    open_refresh(page)
    assert page.locator("#refresh-cache-target").input_value() == "category-zone:category"
    assert switch.is_checked()
    assert page.evaluate("backend.requests.filter(item => item.method !== 'GET').length") == writes
    switch.uncheck()
    page.wait_for_function(
        "backend.tasks[0].steps[0].parameters.categoryStorageOnlyEnabled === 'false'"
    )
    settle_refresh(page)
    assert page.locator("#refresh-cache-target").input_value() == "category-zone:category"
    assert run.is_disabled()
    assert page.evaluate("backend.requests.every(item => !/\\/(start|stop)$/.test(item.path))")


@pytest.mark.parametrize("legacy", ["true", "false", "invalid"])
def test_refresh_saving_old_tasks_drops_retired_execution_flags(
    refresh_page: Any, legacy: str
) -> None:
    page = refresh_page
    page.evaluate("""legacy => {
        const parameters = backend.tasks[0].steps[0].parameters;
        parameters.reuseSafeHandEnabled = legacy;
        parameters.nearbyRouteEnabled = legacy;
        document.dispatchEvent(new CustomEvent('stowling:tasks-changed'));
    }""", legacy)
    settle_refresh(page)
    open_refresh(page)
    switch = page.locator("#refresh-category-storage-only")
    switch.check()
    page.wait_for_function(
        "backend.tasks[0].steps[0].parameters.categoryStorageOnlyEnabled === 'true'"
    )
    saved = page.evaluate("backend.tasks[0].steps[0].parameters")
    assert "reuseSafeHandEnabled" not in saved and "nearbyRouteEnabled" not in saved
    assert saved["targetRef"] == "region:stock"
    assert saved["chests"] == saved["barrels"] == saved["shulkerBoxes"] == "true"
    assert page.locator("#refresh-experiments-settings").count() == 0
