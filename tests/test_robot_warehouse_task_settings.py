import json
import shutil
import subprocess
from pathlib import Path
from typing import Any

import pytest


WEB = Path(__file__).resolve().parents[1] / "src/main/resources/stowling-web"
NODE = shutil.which("node")
pytestmark = pytest.mark.skipif(NODE is None, reason="Node.js is required")
RUNNER = r"""
const fs = require("node:fs");
const vm = require("node:vm");
globalThis.window = {};
vm.runInThisContext(fs.readFileSync(process.argv[1] + "/auto-classify-task-model.js", "utf8"));
const Auto = window.StowlingAutoClassifyTaskModel;
const requests = [];
const messages = [];
let failure = false;
let connected = true;
const settings = {robotWarehouseEnabled: false, overflowCoordinateIds: ["overflow"],
    overflowRegionIds: [], robotWarehouseCoordinateIds: ["warehouse"],
    robotWarehouseRegionIds: [], emptyBoxCoordinateIds: ["supply"]};
const state = {
    revision: "task-revision", items: [], allTasks: [], ready: true, loading: false,
    busy: false, dirty: true, expanded: true, active: false,
    resources: {coordinates: [{id: "pickup"}], regions: [{id: "cache"}],
        categoryZones: [{id: "all-items-zone"}]},
    draft: {...Auto.newDraft(), id: "all-items", name: "全物品",
        overflowDestinationRef: "coordinate:legacy"},
    warehouseSnapshot: {revision: "warehouse-revision", settings}
};
const elements = {
    name: {value: "全物品"}, point: {value: "pickup"}, radius: {value: "8"},
    cache: {value: "cache"}, priority: {value: "NORMAL"},
    cacheMode: {value: "PACK_FIRST"}, cacheThreshold: {value: "80"},
    collectEnabled: {checked: true}, classifyEnabled: {checked: true},
    warehouseEnabled: {checked: true}
};
const cleanup = {
    /** @returns {object} */
    read() {return {destinationRef: ""};}
};
const areaReaders = [{
    /** @returns {object} */
    read() {return {categoryZoneId: "all-items-zone"};}
}];
const Stowling = {
    /** @returns {boolean} */
    isConnected() {return connected;},
    /** @param {string} url @param {object} options @returns {Promise<object>} */
    async request(url, options) {
        const body = JSON.parse(options.body);
        requests.push({url, method: options.method, body});
        if (failure) throw new Error("revision_conflict");
        return {revision: "next-task", item: {id: "all-items", enabled: true, ...body}};
    },
    /** @returns {void} */
    showToast() {},
    /** @returns {void} */
    handleError() {}
};
globalThis.document = {
    /** @returns {void} */
    dispatchEvent() {}
};
/** @returns {void} */
function updateControls() {}
/** @returns {void} */
function syncForm() {}
/** @returns {void} */
function renderList() {}
/** @param {string} text @param {boolean} [error] @returns {void} */
function showMessage(text, error = false) {messages.push({text, error});}
const source = fs.readFileSync(process.argv[1] + "/auto-classify-ui.js", "utf8");
for (const name of ["unavailable", "readForm", "applySave", "saveTask"]) {
    const marker = name === "saveTask" ? "async function " + name + "(" : "function " + name + "(";
    const start = source.indexOf(marker);
    const end = source.indexOf("\n    }", start) + 6;
    vm.runInThisContext(source.slice(start, end));
}
Promise.resolve(eval(process.argv[2])).then(result => process.stdout.write(JSON.stringify(result)));
"""


def run_settings(expression: str) -> Any:
    if NODE is None:
        raise RuntimeError("Node.js is unavailable")
    completed = subprocess.run(
        [NODE, "-e", RUNNER, str(WEB), expression],
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    return json.loads(completed.stdout)


@pytest.mark.parametrize("enabled", [True, False])
def test_task_switch_saves_only_the_task_and_uses_shared_warehouse_locations(enabled: bool) -> None:
    result = run_settings(
        """(async () => {
            elements.warehouseEnabled.checked = ENABLED;
            await saveTask();
            return {requests, state};
        })()""".replace("ENABLED", json.dumps(enabled))
    )

    assert len(result["requests"]) == 1
    task = result["requests"][0]
    assert task["url"] == "/v1/auto-classify-tasks/all-items"
    assert task["body"]["overflowEnabled"] is enabled
    assert task["body"]["overflowDestinationRef"] == ""
    assert "enabled" not in task["body"]
    assert result["state"]["warehouseSnapshot"]["settings"] == {
        "robotWarehouseEnabled": False,
        "overflowCoordinateIds": ["overflow"],
        "overflowRegionIds": [],
        "robotWarehouseCoordinateIds": ["warehouse"],
        "robotWarehouseRegionIds": [],
        "emptyBoxCoordinateIds": ["supply"],
    }
    assert result["state"]["dirty"] is False


def test_overflow_boxes_are_not_used_when_the_robot_warehouse_is_unconfigured() -> None:
    result = run_settings(
        """(async () => {
            settings.robotWarehouseCoordinateIds = [];
            await saveTask();
            return {requests, messages, dirty: state.dirty};
        })()"""
    )

    assert result["requests"] == []
    assert result["dirty"] is True
    assert result["messages"][-1]["error"] is True
    assert "坐标 → 公共设施 → 机器人仓库" in result["messages"][-1]["text"]


def test_disabling_a_task_does_not_require_a_configured_warehouse() -> None:
    result = run_settings(
        """(async () => {
            settings.robotWarehouseCoordinateIds = [];
            elements.warehouseEnabled.checked = false;
            await saveTask();
            return requests;
        })()"""
    )

    assert len(result) == 1
    assert result[0]["body"]["overflowEnabled"] is False


def test_a_save_conflict_keeps_the_switch_draft_and_retries_only_the_task() -> None:
    result = run_settings(
        """(async () => {
            failure = true;
            await saveTask();
            const afterFailure = {dirty: state.dirty, busy: state.busy};
            failure = false;
            await saveTask();
            return {requests, state, afterFailure};
        })()"""
    )

    assert result["afterFailure"] == {"dirty": True, "busy": False}
    assert [request["url"] for request in result["requests"]] == [
        "/v1/auto-classify-tasks/all-items",
        "/v1/auto-classify-tasks/all-items",
    ]
    assert result["state"]["dirty"] is False
