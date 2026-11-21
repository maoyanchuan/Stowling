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
const events = [];
let failureUrl = "";
let connected = true;
const settings = {robotWarehouseEnabled: false, overflowCoordinateIds: [], overflowRegionIds: [],
    emptyBoxCoordinateIds: ["supply"], temporaryRegionIds: ["recovery"]};
const state = {
    revision: "task-revision", items: [], allTasks: [], ready: true, loading: false,
    busy: false, dirty: true, expanded: true, active: false,
    resources: {coordinates: [{id: "pickup"}, {id: "overflow"}],
        regions: [{id: "cache"}], categoryZones: [{id: "all-items-zone"}]},
    draft: {...Auto.newDraft(), id: "all-items", name: "全物品"},
    warehouseSnapshot: {revision: "warehouse-revision", settings},
    warehouseDraft: {enabled: false, locations: ["coordinate:overflow"]},
    warehouseDraftRevision: "warehouse-revision"
};
const elements = {
    name: {value: "全物品"}, point: {value: "pickup"}, radius: {value: "8"},
    cache: {value: "cache"}, priority: {value: "NORMAL"},
    collectEnabled: {checked: true}, classifyEnabled: {checked: true},
    warehouseEnabled: {checked: true}, overflowDestination: {value: "coordinate:overflow"}
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
        if (url === failureUrl) throw new Error("revision_conflict");
        if (url === "/v1/storage-settings") {
            return {revision: "next-warehouse", settings: body.settings};
        }
        return {revision: "next-task", item: {id: "all-items", enabled: true, ...body}};
    },
    /** @returns {void} */
    showToast() {},
    /** @returns {void} */
    handleError() {}
};
globalThis.document = {
    /** @param {Event} event @returns {void} */
    dispatchEvent(event) {events.push(event.type);}
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
for (const name of ["unavailable", "readForm", "resetWarehouseDraft", "saveWarehouse",
        "applySave", "saveTask"]) {
    const marker = ["saveWarehouse", "saveTask"].includes(name)
        ? "async function " + name + "(" : "function " + name + "(";
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


def test_one_task_save_configures_the_warehouse_before_enabling_overflow() -> None:
    result = run_settings(
        """(async () => {
            await saveTask();
            return {requests, state};
        })()"""
    )

    warehouse, task = result["requests"]
    assert warehouse["url"] == "/v1/storage-settings"
    assert warehouse["body"] == {
        "revision": "warehouse-revision",
        "settings": {
            "robotWarehouseEnabled": True,
            "overflowCoordinateIds": ["overflow"],
            "overflowRegionIds": [],
            "emptyBoxCoordinateIds": ["supply"],
            "temporaryRegionIds": ["recovery"],
        },
    }
    assert task["url"] == "/v1/auto-classify-tasks/all-items"
    assert task["body"]["overflowEnabled"] is True
    assert task["body"]["overflowDestinationRef"] == "coordinate:overflow"
    assert "enabled" not in task["body"]
    assert result["state"]["warehouseDraftRevision"] == "next-warehouse"
    assert result["state"]["dirty"] is False


def test_disabling_one_tasks_switch_preserves_the_shared_warehouse() -> None:
    result = run_settings(
        """(async () => {
            settings.robotWarehouseEnabled = true;
            settings.overflowCoordinateIds = ["overflow"];
            resetWarehouseDraft();
            elements.warehouseEnabled.checked = false;
            await saveTask();
            return {requests, state};
        })()"""
    )

    assert len(result["requests"]) == 1
    task = result["requests"][0]
    assert task["url"] == "/v1/auto-classify-tasks/all-items"
    assert task["body"]["overflowEnabled"] is False
    assert task["body"]["overflowDestinationRef"] == ""
    assert result["state"]["warehouseSnapshot"]["settings"]["robotWarehouseEnabled"] is True


@pytest.mark.parametrize("destination", ["auto", "coordinate:overflow"])
def test_existing_locations_can_be_used_without_rewriting_shared_settings(
    destination: str,
) -> None:
    result = run_settings(
        """(async () => {
            settings.robotWarehouseEnabled = true;
            settings.overflowCoordinateIds = ["overflow"];
            resetWarehouseDraft();
            elements.overflowDestination.value = DESTINATION;
            await saveTask();
            return requests;
        })()""".replace("DESTINATION", json.dumps(destination))
    )

    assert len(result) == 1
    assert result[0]["body"]["overflowEnabled"] is True
    expected = "" if destination == "auto" else destination
    assert result[0]["body"]["overflowDestinationRef"] == expected


def test_enabling_without_a_location_keeps_the_draft_and_makes_no_requests() -> None:
    result = run_settings(
        """(async () => {
            state.warehouseDraft.locations = [];
            await saveTask();
            return {requests, messages, dirty: state.dirty};
        })()"""
    )

    assert result["requests"] == []
    assert result["dirty"] is True
    assert result["messages"][-1]["error"] is True
    assert "至少一个存放位置" in result["messages"][-1]["text"]


def test_a_warehouse_save_conflict_does_not_save_or_discard_the_task_draft() -> None:
    result = run_settings(
        """(async () => {
            failureUrl = "/v1/storage-settings";
            await saveTask();
            return {requests, state, messages};
        })()"""
    )

    assert [request["url"] for request in result["requests"]] == ["/v1/storage-settings"]
    assert result["state"]["dirty"] is True
    assert result["state"]["busy"] is False
    assert result["state"]["warehouseDraft"]["locations"] == ["coordinate:overflow"]
    assert result["messages"][-1] == {"text": "revision_conflict", "error": True}


def test_retry_after_a_task_save_failure_uses_the_new_warehouse_revision() -> None:
    result = run_settings(
        """(async () => {
            failureUrl = "/v1/auto-classify-tasks/all-items";
            await saveTask();
            const dirtyAfterFailure = state.dirty;
            failureUrl = "";
            await saveTask();
            return {requests, state, dirtyAfterFailure};
        })()"""
    )

    assert [request["url"] for request in result["requests"]] == [
        "/v1/storage-settings",
        "/v1/auto-classify-tasks/all-items",
        "/v1/auto-classify-tasks/all-items",
    ]
    assert result["dirtyAfterFailure"] is True
    assert result["state"]["warehouseDraftRevision"] == "next-warehouse"
    assert result["state"]["dirty"] is False
