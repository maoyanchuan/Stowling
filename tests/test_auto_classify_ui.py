import shutil
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "src" / "main" / "resources" / "stowling-web"


def read(name: str) -> str:
    return (WEB / name).read_text(encoding="utf-8")


def read_java(relative_path: str) -> str:
    return (ROOT / relative_path).read_text(encoding="utf-8")


def between(source: str, start: str, end: str) -> str:
    start_index = source.index(start)
    content_start = start_index + len(start)
    return source[content_start:source.index(end, content_start)]


def test_task_page_places_auto_tasks_above_the_single_template() -> None:
    html = read("tasks.html")
    display = between(
        html,
        'id="task-display-area"',
        '<section class="panel task-selection-panel"',
    )

    assert display.index('id="auto-task-list"') < display.index(
        'id="auto-task-draft"'
    )
    for element_id in (
        "auto-task-list",
        "auto-task-draft",
        "toggle-auto-draft",
        "auto-draft-body",
        "auto-task-form",
        "auto-task-fields",
        "auto-detection-point",
        "auto-pickup-radius",
        "auto-cache-location",
        "auto-collect-priority",
        "auto-classification-targets",
        "auto-collect-toggle",
        "auto-collect-body",
        "auto-collect-enabled",
        "auto-sort-toggle",
        "auto-sort-body",
        "auto-classify-enabled",
        "auto-task-message",
        "delete-auto-task",
    ):
        assert f'id="{element_id}"' in display
    assert 'id="new-auto-task"' not in display
    assert 'id="new-auto-task"' in html
    assert "掉落物拾取" in display
    assert "任务名称" not in display
    radius = between(display, '<input id="auto-pickup-radius"', '>')
    assert 'type="number"' in radius
    assert 'min="1"' in radius
    assert 'max="64"' in radius


def test_auto_phases_are_collapsed_submenus_with_switches() -> None:
    html = read("tasks.html")
    javascript = read("auto-classify-ui.js")
    form = between(html, 'id="auto-task-form"', "</form>")

    assert "收集入缓存" not in html
    for phase, title in (("collect", "启用缓存"), ("sort", "后台分类")):
        toggle_id = f'id="auto-{phase}-toggle"'
        body_id = f'id="auto-{phase}-body"'
        assert toggle_id in form
        assert body_id in form
        assert title in form
    collect_header = between(
        form,
        'id="auto-collect-toggle"',
        'id="auto-collect-body"',
    )
    sort_header = between(
        form,
        'id="auto-sort-toggle"',
        'id="auto-sort-body"',
    )
    collect_body = between(
        form,
        'id="auto-collect-body"',
        'id="auto-collect-priority"',
    )
    sort_body = between(
        form,
        'id="auto-sort-body"',
        'id="combined-classification-help"',
    )

    assert 'aria-expanded="false"' in collect_header
    assert 'aria-controls="auto-collect-body"' in collect_header
    assert 'id="auto-collect-enabled"' not in collect_header
    assert 'id="auto-collect-enabled"' in collect_body
    assert "hidden" in collect_body
    assert 'aria-expanded="false"' in sort_header
    assert 'aria-controls="auto-sort-body"' in sort_header
    assert 'id="auto-classify-enabled"' in sort_header
    assert 'role="switch"' in sort_header
    assert "hidden" in sort_body

    for selector in (
        '#auto-collect-toggle',
        '#auto-collect-body',
        '#auto-collect-enabled',
        '#auto-sort-toggle',
        '#auto-sort-body',
        '#auto-classify-enabled',
    ):
        assert selector in javascript
    assert 'togglePhase("collection")' in javascript
    assert 'togglePhase("classification")' in javascript
    assert "reveal(body, expanded)" in javascript
    assert "header.disabled = disabled || !enabled" in javascript
    assert 'id="auto-classification-targets"' in sort_body


def test_cache_submenus_have_named_switches_and_experimental_packing() -> None:
    html = read("tasks.html")
    javascript = read("auto-classify-ui.js")
    collect = between(html, 'id="auto-collect-body"', 'for="auto-collect-priority"')
    pickup = between(collect, 'id="auto-pickup-toggle"', 'id="auto-packing-toggle"')
    packing = between(collect, 'id="auto-packing-toggle"', '</section>')

    assert "掉落物拾取" in pickup
    assert 'id="auto-collect-enabled"' in pickup
    assert 'id="auto-cache-location"' in html
    assert 'id="auto-cache-location"' not in packing
    for field in ("auto-detection-point", "auto-pickup-radius"):
        assert f'id="{field}"' in pickup
        assert f'id="{field}"' not in packing
    assert "打包缓存" in packing
    assert "实验性" in packing
    assert "默认关闭" in packing
    assert 'id="auto-cache-packing-enabled"' in packing
    assert 'id="auto-cache-mode"' in packing
    assert 'id="auto-cache-threshold"' in packing
    assert 'role="switch"' in packing
    assert 'togglePhase("pickup")' in javascript
    assert 'togglePhase("packing")' in javascript
    assert 'kind !== "CLASSIFICATION" && elements.cacheEnabled.checked' in javascript


def test_pickup_settings_group_detection_and_allow_packing_details() -> None:
    html = read("tasks.html")
    javascript = read("auto-classify-ui.js")
    pickup = between(html, 'id="auto-pickup-body"', 'id="auto-packing-toggle"')
    follow_up = between(html, 'id="auto-follow-up-field"', 'id="auto-cleanup-settings"')

    for field in ("auto-detection-point", "auto-pickup-radius", "auto-detection-interval"):
        assert f'id="{field}"' in pickup
        assert f'id="{field}"' not in follow_up
    assert "掉落物拾取点" in html
    assert "倒计时（秒）" in follow_up
    assert 'value="30"' in follow_up
    assert 'id="auto-follow-up-task"' in follow_up
    assert 'aria-controls="auto-follow-up-body"' in follow_up
    assert 'elements.packingHeader.hidden = false' in javascript
    assert '!(pickup && phase === "packing")' not in javascript
    assert 'const alwaysOpen = pickup && phase === "collection"' in javascript
    assert 'phase === "collection" || phase === "pickup"' not in javascript
    assert html.index('id="auto-pickup-toggle"') < html.index('id="auto-packing-toggle"')
    assert html.index('id="auto-collect-priority"') < html.index('id="auto-follow-up-field"')


def test_robot_warehouse_has_a_switch_and_location_inside_the_classification_task() -> None:
    html = read("tasks.html")
    form = between(html, 'id="auto-task-form"', "</form>")
    warehouse = between(form, 'id="robot-warehouse-settings"', "</section>")
    assert 'id="robot-warehouse-enabled"' in warehouse
    assert 'role="switch"' in warehouse
    assert 'id="robot-warehouse-location"' in warehouse
    assert 'id="robot-warehouse-toggle"' in warehouse
    assert 'aria-controls="robot-warehouse-body"' in warehouse
    assert 'id="robot-warehouse-body"' in warehouse
    assert 'id="auto-overflow-destination"' not in html
    assert 'id="add-robot-warehouse-location"' not in html
    assert form.index('id="auto-sort-body"') < form.index('id="robot-warehouse-settings"')

    javascript = read("auto-classify-ui.js")
    assert 'overflowEnabled: kind !== "PICKUP" && elements.warehouseEnabled.checked' in javascript


def test_recognition_is_configured_only_in_the_storage_layout() -> None:
    javascript = read("auto-classify-ui.js")
    model = read("auto-classify-task-model.js")
    layout = read("category-zones.js")

    assert "分类物识别方式" not in javascript
    assert "沿用坐标页的分类规则和储存设置" in read("tasks.html")
    assert "ruleId" not in javascript
    assert "markerSources" not in javascript
    assert "ruleId" not in model
    assert "markerSources" not in model
    assert 'field("分类物识别方式", rule)' in layout
    assert "ruleId: rule.value" in layout
    assert "classificationAreas: []" in model
    assert "function renderClassificationTargets()" in javascript
    assert "function addClassificationArea()" not in javascript
    assert "function removeClassificationArea(index)" not in javascript
    assert "elements.addArea" not in javascript


def test_auto_ui_loads_independent_api_and_expected_array_properties() -> None:
    javascript = read("auto-classify-ui.js")
    loading = between(
        javascript,
        "async function loadResources()",
        "function populateSelect",
    )

    for route in (
        "Stowling.request(endpoint)",
        '"/v1/coordinates"',
        '"/v1/regions"',
        '"/v1/category-zones"',
    ):
        assert route in loading
    assert "Array.isArray(tasks.items) ? tasks.items : []" in loading
    assert "Array.isArray(coordinates.coordinates)" in loading
    assert "Array.isArray(regions.regions)" in loading
    assert "Array.isArray(zones.categoryZones)" in loading
    assert "/v1/category-rules" not in loading
    assert '"/v1/tasks"' not in loading
    assert "state.revision = String(tasks.revision || \"\");" in loading


def test_smart_recognition_reads_the_item_inside_a_flower_pot() -> None:
    resolver = read_java(
        "src/main/java/dev/stowling/client/automation/"
        "MinecraftCategoryZoneResolver.java"
    )

    assert "instanceof FlowerPotBlock flowerPot" in resolver
    assert "flowerPot.getPotted().asItem()" in resolver
    assert "PottedBlockItems.itemId(blockId)" in resolver
    assert "CategoryMarkerSource.PLACED_ITEM" in resolver
    assert "CategoryMarkerSource.FLOWER_POT" in resolver


def test_auto_ui_delegates_flat_business_rules_to_the_model() -> None:
    javascript = read("auto-classify-ui.js")

    assert "const Auto = window.StowlingAutoClassifyTaskModel;" in javascript
    for call in (
        "Auto.newDraft(",
        "Auto.draftFromTask(",
        "Auto.payload(",
    ):
        assert call in javascript
    assert "detection: {" not in javascript
    assert "coordinateRef:" not in javascript
    assert "cacheRegionRef:" not in javascript
    assert "categoryZoneRef:" not in javascript
    assert "classificationPriority" not in javascript

    form_reader = between(
        javascript,
        "function readForm()",
        "function renderList()",
    )
    assert "...state.draft" in form_reader
    assert "classificationAreas: Auto.classificationAreasFromSelection(" in form_reader
    assert "elements.targets.selectedOptions" in form_reader
    assert "collectionEnabled: elements.collectEnabled.checked" in form_reader
    assert "classificationEnabled: elements.classifyEnabled.checked" in form_reader
    assert "length ?" not in form_reader
    assert "\n            enabled:" not in form_reader


def test_auto_ui_saves_settings_without_changing_listening() -> None:
    javascript = read("auto-classify-ui.js")
    save = between(javascript, "async function saveTask()", "async function deleteTask()")

    assert "payload = Auto.payload(" in save
    assert "Auto.payload(state.draft, state.resources)" in save
    assert 'const endpoint = "/v1/auto-classify-tasks";' in javascript
    assert 'Autosave.write(`${endpoint}/${encodeURIComponent(id)}`, "PUT",' in save
    assert 'Autosave.write(endpoint, "POST", payload)' in save
    assert "...payload, revision: state.draftRevision" in save
    assert "delete payload.enabled;" in save
    assert "/start" not in save
    assert "/stop" not in save
    assert "const item = response.item;" in javascript
    assert "state.revision = String(response.revision || \"\");" in javascript


def test_auto_ui_switch_delete_and_saved_task_count_contracts() -> None:
    javascript = read("auto-classify-ui.js")
    tasks = read("tasks.js")
    switch = between(javascript, "async function setListening(", "function applySave(")
    delete = between(javascript, "async function deleteTask()", "elements.newTask")

    assert 'const operation = enabled ? "start" : "stop";' in switch
    assert 'method: "POST"' in switch
    assert 'method: "DELETE"' in delete
    assert "body: JSON.stringify({ revision: previousRevision })" in switch
    assert "body: JSON.stringify({ revision: state.revision })" in delete
    assert "const item = response.item;" in javascript
    assert "state.revision = String(response.revision || \"\");" in javascript
    assert 'new CustomEvent("stowling:auto-task-count"' in javascript
    count_event = between(
        javascript,
        'new CustomEvent("stowling:auto-task-count", {',
        "}));",
    )
    assert "detail:" in count_event
    assert "count: state.items.length" in count_event
    assert "draftActive: state.active && !state.draft.id" in count_event
    assert 'document.addEventListener("stowling:auto-task-count"' in tasks
    assert "state.autoTaskCount = Number(event.detail?.count || 0);" in tasks
    assert "state.moveTasks.length + state.autoTaskCount" in tasks


def test_auto_draft_hides_the_empty_task_placeholder() -> None:
    tasks = read("tasks.js")
    listener = between(
        tasks,
        'document.addEventListener("stowling:auto-task-count", (event) => {',
        "});",
    )
    disclosure = between(
        tasks,
        "function syncMoveDisclosure()",
        "function selectMoveTask(",
    )
    placeholder = between(disclosure, "elements.movePlaceholder.hidden =", ";")

    assert "autoDraftActive: false" in tasks
    assert "state.autoDraftActive = event.detail?.draftActive === true;" in listener
    assert "syncMoveDisclosure();" in listener
    assert "savedCount > 0 || newDraft || state.autoDraftActive" in placeholder
    assert "robotWarehouseDraftActive" not in tasks
    assert "robotWarehouseCount" not in tasks


def test_dirty_auto_task_switch_is_disabled_and_deletion_warns() -> None:
    javascript = read("auto-classify-ui.js")
    delete = between(javascript, "async function deleteTask()", "elements.newTask")
    warning = 'const warning = state.dirty ? "未保存修改将被丢弃。" : "";'
    confirmation = 'window.confirm(`${prompt}${warning}`)'

    assert "row.listening.disabled = disabled || (selected && state.dirty);" in javascript
    assert delete.index(warning) < delete.index(confirmation) < delete.index("Stowling.request(")


@pytest.mark.parametrize("kind", ["COMBINED", "PICKUP"])
def test_switching_auto_task_keeps_the_open_card_and_rolls_back_on_error(kind: str) -> None:
    node = shutil.which("node")
    if node is None:
        pytest.skip("Node.js is needed to exercise the task card")
    script = """
const assert = require("node:assert/strict");

class Element extends EventTarget {
    children = [];
    attributes = new Map();
    dataset = {};
    classList = {
        /** @returns {void} */ add() {},
        /** @returns {void} */ toggle() {}
    };
    /** @param {...Element} nodes @returns {void} */
    append(...nodes) { this.children.push(...nodes); }
    /** @param {...Element} nodes @returns {void} */
    prepend(...nodes) { this.children.unshift(...nodes); }
    /** @returns {Element} */
    get firstElementChild() { return this.children[0]; }
    /** @returns {Element[]} */
    get options() { return this.children; }
    /** @returns {Element[]} */
    get selectedOptions() { return this.options.filter(option => option.selected); }
    /** @param {...Element} nodes @returns {void} */
    replaceChildren(...nodes) { this.children = nodes; }
    /** @param {string} key @param {string} value @returns {void} */
    setAttribute(key, value) { this.attributes.set(key, value); }
    /** @param {string} key @returns {void} */
    removeAttribute(key) { this.attributes.delete(key); }
    /** @returns {Element} */
    closest() { return this; }
    /** @returns {Element[]} */
    querySelectorAll() { return []; }
    /** @returns {void} */
    insertBefore() {}
}

const elements = new Map();
/** @param {string} selector @returns {Element} */
function element(selector) {
    if (!elements.has(selector)) elements.set(selector, new Element());
    return elements.get(selector);
}
globalThis.document = Object.assign(new EventTarget(), {
    querySelector: element,
    /** @returns {Element} */
    createElement() { return new Element(); },
    /** @returns {Element} */
    createElementNS() { return new Element(); }
});
globalThis.Option = Element;
globalThis.CustomEvent = class extends Event {
    /** @param {string} type @param {object} options */
    constructor(type, options) {
        super(type, options);
        this.detail = options.detail;
    }
};

const kind = process.argv[3];
const item = {id: "sorter", name: "自动分类", ...(kind === "PICKUP" ? {kind} : {}), enabled: false,
    collectionEnabled: true, classificationAreas: []};
const requests = [];
const errors = [];
const changes = [];
let connect;
let finish;
let fail;
globalThis.window = {
    Stowling: {
        isConnected: () => true,
        onConnected: (callback) => { connect = callback; },
        element: (tag, css, textContent) => Object.assign(new Element(), {textContent}),
        handleError: (error) => { errors.push(error.message); },
        showToast: () => {},
        /** @param {string} path @param {object} [options] @returns {object|Promise<object>} */
        request(path, options) {
            requests.push(path);
            if (options) {
                requests.push(JSON.parse(options.body).revision);
                return new Promise((resolve, reject) => {
                    finish = resolve;
                    fail = reject;
                });
            }
            return {revision: "r1", items: [item]};
        }
    },
    StowlingSettings: {
        reveal: (body, expanded) => { body.hidden = !expanded; },
        switchControl: (control) => control,
        disclosure: () => ({root: new Element(), summary: new Element()})
    },
    StowlingResourcePicker: {
        load: async () => ({coordinates: [], regions: [], categoryZones: [], catalog: {}}),
        group: () => {}
    },
    StowlingTaskModel: {priorityLabel: () => "普通"},
    StowlingTaskCleanupSettings: {create: () => ({resources() {}, set() {}})},
    StowlingStoragePolicyEditor: {create: () => ({root: new Element()})},
    StowlingTaskAutosave: {create: () => ({cancel() {}})},
    setInterval: () => 0
};
document.addEventListener("stowling:task-switch-changed", ({detail}) => changes.push(detail));
document.addEventListener("stowling:auto-tasks-changed", () => changes.push("reload"));

(async () => {
    require(process.argv[1]);
    require(process.argv[2]);
    await connect();
    const list = element("#auto-task-list");
    const initialHeader = list.children[0].children[0];
    initialHeader.children[kind === "PICKUP" ? 1 : 0].dispatchEvent(new Event("click"));
    const card = list.children[0];
    const [header, body] = card.children;
    const summary = header.children[kind === "PICKUP" ? 1 : 0];
    const toggle = header.children[kind === "PICKUP" ? 2 : 1];
    const status = kind === "PICKUP" ? card.children[2] : summary.children[3];
    const form = element("#auto-task-form");
    const radius = element("#auto-pickup-radius");
    radius.value = "24";
    assert.equal(body.hidden, false);
    assert.ok(body.children.includes(form));
    requests.length = 0;

    toggle.checked = true;
    toggle.dispatchEvent(new Event("change"));
    assert.equal(toggle.checked, true);
    assert.equal(toggle.disabled, true);
    finish({revision: "r2", item: {...item, enabled: true}});
    await new Promise(setImmediate);
    assert.equal(list.children[0], card);
    assert.equal(body.hidden, false);
    assert.ok(body.children.includes(form));
    assert.equal(status.textContent, "监听中");
    assert.equal(toggle.disabled, false);
    assert.equal(radius.value, "24");
    assert.equal(element("#auto-task-fields").disabled, false);

    toggle.checked = false;
    toggle.dispatchEvent(new Event("change"));
    fail(new Error("request failed"));
    await new Promise(setImmediate);
    assert.equal(list.children[0], card);
    assert.equal(toggle.checked, true);
    assert.equal(toggle.disabled, false);
    assert.equal(status.textContent, "监听中");
    assert.deepEqual(errors, ["request failed"]);
    assert.deepEqual(requests, [
        "/v1/auto-classify-tasks/sorter/start", "r1",
        "/v1/auto-classify-tasks/sorter/stop", "r2"
    ]);
    assert.deepEqual(changes, [{previousRevision: "r1", revision: "r2"}]);
})().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
"""
    subprocess.run(
        [node, "-e", script, str(WEB / "auto-classify-task-model.js"),
         str(WEB / "auto-classify-ui.js"), kind],
        check=True,
    )


def test_pickup_follow_up_submenu_requires_the_switch_and_closes_when_disabled() -> None:
    node = shutil.which("node")
    if node is None:
        pytest.skip("Node.js is needed to exercise the follow-up submenu")
    script = r"""
const assert = require("node:assert/strict");

class Element extends EventTarget {
    children = [];
    attributes = new Map();
    dataset = {};
    checked = false;
    disabled = false;
    value = "";
    classList = {
        /** @returns {void} */ add() {},
        /** @returns {void} */ toggle() {}
    };
    /** @param {...Element} nodes @returns {void} */
    append(...nodes) { this.children.push(...nodes); }
    /** @param {...Element} nodes @returns {void} */
    replaceChildren(...nodes) { this.children = nodes; }
    /** @param {string} key @param {string} value @returns {void} */
    setAttribute(key, value) { this.attributes.set(key, value); }
    /** @returns {Element} */
    closest() { return this; }
    /** @returns {Element[]} */
    get options() { return this.children; }
    /** @returns {Element[]} */
    get selectedOptions() { return this.options.filter(option => option.selected); }
    /** @param {Element} node @returns {boolean} */
    contains(node) { return this.children.includes(node); }
    /** @returns {Element[]} */
    querySelectorAll() { return this.controls || []; }
    /** @returns {void} */
    insertBefore() {}
}
const elements = new Map();
/** @param {string} selector @returns {Element} */
function element(selector) {
    if (!elements.has(selector)) elements.set(selector, new Element());
    return elements.get(selector);
}
globalThis.document = Object.assign(new EventTarget(), {
    querySelector: element,
    /** @returns {Element} */
    createElement() { return new Element(); }
});
globalThis.Option = Element;
globalThis.CustomEvent = class extends Event {
    /** @param {string} type @param {object} options */
    constructor(type, options) { super(type); this.detail = options.detail; }
};
let connect;
let scheduled = 0;
const enabled = element("#auto-follow-up-enabled");
const header = element("#auto-follow-up-toggle");
const body = element("#auto-follow-up-body");
const successor = element("#auto-follow-up-task");
const delay = element("#auto-follow-up-delay");
body.controls = [successor, delay];
globalThis.window = {
    Stowling: {
        isConnected: () => true,
        onConnected: (callback) => { connect = callback; },
        element: () => new Element(),
        request: async () => ({revision: "r1", items: []}),
        handleError: (error) => { throw error; }
    },
    StowlingSettings: {
        reveal: (body, expanded) => { body.hidden = !expanded; },
        disclosure: () => ({root: new Element(), summary: new Element()})
    },
    StowlingResourcePicker: {
        load: async () => ({coordinates: [], regions: [], categoryZones: [], catalog: {}}),
        group: () => {}
    },
    StowlingTaskCleanupSettings: {
        create: () => ({resources() {}, set() {}, read: () => ({})})
    },
    StowlingStoragePolicyEditor: {create: () => ({root: new Element()})},
    StowlingTaskAutosave: {
        create: () => ({cancel() {}, schedule: () => { scheduled++; }})
    },
    setInterval: () => 0
};
/** @param {Element} target @returns {void} */
function input(target) {
    const event = new Event("input");
    Object.defineProperty(event, "target", {value: target});
    element("#auto-task-form").dispatchEvent(event);
}
(async () => {
    require(process.argv[1]);
    require(process.argv[2]);
    await connect();
    element("#new-auto-task").dispatchEvent(new Event("click"));
    assert.equal(enabled.checked, false);
    assert.equal(header.disabled, true);
    assert.equal(body.hidden, true);
    assert.equal(successor.disabled, true);
    header.dispatchEvent(new Event("click"));
    assert.equal(body.hidden, true);

    enabled.checked = true;
    input(enabled);
    assert.equal(header.disabled, false);
    assert.equal(body.hidden, true);
    header.dispatchEvent(new Event("click"));
    assert.equal(body.hidden, false);
    assert.equal(successor.disabled, false);
    assert.equal(delay.disabled, true);
    successor.value = "task:next";
    delay.value = "45";
    input(successor);
    assert.equal(delay.disabled, false);

    enabled.checked = false;
    input(enabled);
    assert.equal(header.disabled, true);
    assert.equal(header.attributes.get("aria-expanded"), "false");
    assert.equal(body.hidden, true);
    assert.equal(successor.disabled, true);
    assert.equal(delay.disabled, true);
    header.dispatchEvent(new Event("click"));
    assert.equal(body.hidden, true);

    enabled.checked = true;
    input(enabled);
    assert.equal(body.hidden, true);
    header.dispatchEvent(new Event("click"));
    assert.equal(body.hidden, false);
    assert.equal(successor.value, "task:next");
    assert.equal(delay.value, "45");
    assert.equal(delay.disabled, false);
    assert.equal(scheduled, 4);

    const packing = element("#auto-cache-packing-enabled");
    const packingHeader = element("#auto-packing-toggle");
    const packingBody = element("#auto-packing-body");
    const threshold = element("#auto-cache-threshold");
    packingBody.controls = [threshold];
    threshold.value = "65";
    packing.checked = true;
    input(packing);
    assert.equal(packingHeader.hidden, false);
    assert.equal(packingBody.hidden, false);
    assert.equal(threshold.disabled, false);
    packingHeader.dispatchEvent(new Event("click"));
    assert.equal(packingBody.hidden, true);
    assert.equal(threshold.value, "65");
    packing.checked = false;
    input(packing);
    assert.equal(packingBody.hidden, true);
    assert.equal(threshold.value, "65");
    packing.checked = true;
    input(packing);
    assert.equal(packingBody.hidden, false);
    assert.equal(threshold.value, "65");
})().catch(error => { console.error(error); process.exitCode = 1; });
"""
    subprocess.run(
        [node, "-e", script, str(WEB / "auto-classify-task-model.js"),
         str(WEB / "auto-classify-ui.js")],
        check=True,
    )


def test_task_page_loads_model_before_ui_and_ui_after_shared_styles() -> None:
    html = read("tasks.html")
    model = '<script src="/auto-classify-task-model.js" defer></script>'
    ui = '<script src="/auto-classify-ui.js" defer></script>'
    tasks = '<script src="/tasks.js" defer></script>'

    assert html.index(model) < html.index(ui)
    assert html.index(tasks) < html.index(ui)
    assert '<link rel="stylesheet" href="/styles.css">' in html
    assert ".auto-task-form" in read("styles.css")
    assert "auto-task-form[hidden]" in read("styles.css")


def test_unstackable_rule_is_configured_in_the_warehouse_layout() -> None:
    html = read("tasks.html")
    assert "坐标 → 区域 → 分类区" in html
    assert 'id="auto-unstackable-region"' not in html
    assert "关闭不可堆叠分类" not in read("auto-classify-ui.js")
    editor = read("category-zones.js")
    assert "marker.root.hidden" not in editor
    assert "...marker.read()" in editor
    assert "...storage.read()" in editor

