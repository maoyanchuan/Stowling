import shutil
import subprocess
from pathlib import Path

import pytest


WEB = Path(__file__).resolve().parents[1] / "src" / "main" / "resources" / "stowling-web"


def test_refresh_task_has_range_switches_and_schedule_controls() -> None:
    html = (WEB / "tasks.html").read_text(encoding="utf-8")
    assert 'src="/refresh-cache-ui.js"' in html
    assert 'id="new-refresh-cache-task"' in html
    assert 'id="refresh-cache-target"' in html
    assert 'id="refresh-scan-markers"' in html
    assert 'id="refresh-include-markers"' in html
    assert 'value="MANUAL">仅手动' in html
    assert 'value="DAILY">每天固定时间' in html
    assert 'value="INTERVAL">每隔指定时长' in html


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
draft.scanMarkers = true;
draft.includeMarkers = true;
assert.deepEqual(model.settings(model.parameters(draft)), draft);
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
    createElement() { return new Element(); }
});
globalThis.Option = Element;
globalThis.CustomEvent = Event;

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
                requests.push(["PUT", body.revision, body.name]);
                task = {...task, name: body.name, steps: body.steps};
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
    const [summary, toggle] = taskHeader().children;
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
    assert.equal(taskHeader().children[1].checked, false);
    assert.equal(taskHeader().children[0].children[2].textContent, "已暂停");
    assert.equal(element("refresh-cache-name").value, "Renamed externally");
    element("refresh-cache-name").value = "Intermediate name";
    element("refresh-cache-form").dispatchEvent(new Event("input"));
    element("refresh-cache-name").value = "Final name";
    element("refresh-cache-form").dispatchEvent(new Event("input"));
    pendingAutosave();
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(requests.slice(4), [["PUT", "paused", "Final name"]]);
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
