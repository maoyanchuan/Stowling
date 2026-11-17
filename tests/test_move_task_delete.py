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
let confirmResult = true;
globalThis.window = {
    Stowling: {
        /** @param {unknown} value @returns {unknown} */
        clone(value) {return JSON.parse(JSON.stringify(value));}
    },
    /** @returns {boolean} */
    confirm() {return confirmResult;}
};
vm.runInThisContext(fs.readFileSync(process.argv[1] + "/v2-task-model.js", "utf8"));
vm.runInThisContext(fs.readFileSync(process.argv[1] + "/move-task-model.js", "utf8"));
const Model = window.StowlingTaskModel;
const Move = window.StowlingMoveTaskModel;
const events = [];
const requests = [];
const errors = [];
let failure = false;
let response;
let focused = false;
let renders = 0;
let connected = true;
const elements = {newMove: {
    /** @returns {void} */
    focus() {focused = true;}
}};
globalThis.document = {
    /** @param {Event} event @returns {void} */
    dispatchEvent(event) {events.push(event.type);}
};
const Stowling = {
    /** @returns {boolean} */
    isConnected() {return connected;},
    /** @param {string} url @param {object} options @returns {Promise<object>} */
    async request(url, options) {
        requests.push({url, ...options});
        if (failure) throw new Error("task_in_use");
        return response;
    },
    /** @returns {void} */
    showToast() {},
    /** @param {Error} error @returns {void} */
    handleError(error) {errors.push(error.message);}
};
/** @param {string} id @returns {object} */
function move(id) {
    return {id, name: id, priority: "NORMAL", steps: [{id: "step-" + id,
        kind: "ACTION", moduleId: "MOVE_TO_COORDINATE",
        parameters: {x: "1", y: "64", z: "1"}, steps: [], otherwise: []}]};
}
const original = move("move/1");
let state = {moveBusy: false, moveLoading: false, moveResourcesReady: true,
    moveFormOpen: true, moveDraftActive: false, moveDirty: true, moveRevision: "old",
    moveTasks: [original, move("move-2")], moveAllTasks: [original, move("move-2")],
    moveDraft: {...Move.draftFromTask(original), name: "Unsaved edit"}};
/** @returns {void} */
function updateMoveControls() {}
/** @returns {void} */
function syncMoveForm() {}
/** @returns {void} */
function renderMoveTaskList() {renders++;}
/** @param {string} text @returns {void} */
function showMoveMessage(text) {errors.push(text);}
const source = fs.readFileSync(process.argv[1] + "/tasks.js", "utf8");
const start = source.indexOf("async function deleteMoveTask(");
const end = source.indexOf("\n    }", start) + 6;
const deleteMoveTask = eval("(" + source.slice(start, end) + ")");
Promise.resolve(eval(process.argv[2])).then(result => process.stdout.write(JSON.stringify(result)));
"""


def run_delete(expression: str) -> Any:
    if NODE is None:
        raise RuntimeError("Node.js is unavailable")
    completed = subprocess.run(
        [NODE, "-e", RUNNER, str(WEB), expression],
        check=True,
        capture_output=True,
        encoding="utf-8",
    )
    return json.loads(completed.stdout)


def test_delete_updates_all_tasks_revision_and_closes_the_shared_editor() -> None:
    result = run_delete(
        """(async () => {
            const complex = {id: 'complex', name: 'Workflow', steps: []};
            response = {revision: 'next', tasks: [move('move-2'), complex]};
            await deleteMoveTask();
            return {state, requests, events, focused, renders};
        })()"""
    )

    assert result["requests"][0]["url"] == "/v1/tasks/move%2F1"
    assert result["requests"][0]["method"] == "DELETE"
    assert json.loads(result["requests"][0]["body"]) == {"revision": "old"}
    assert [task["id"] for task in result["state"]["moveAllTasks"]] == ["move-2", "complex"]
    assert [task["id"] for task in result["state"]["moveTasks"]] == ["move-2"]
    assert result["state"]["moveRevision"] == "next"
    assert result["state"]["moveDraft"]["id"] == ""
    assert result["state"]["moveFormOpen"] is False
    assert result["state"]["moveDirty"] is False
    assert result["state"]["moveBusy"] is False
    assert result["focused"] is True
    assert result["renders"] == 1
    assert result["events"] == ["stowling:tasks-changed"]


@pytest.mark.parametrize("change", ["confirmResult = false", "state.moveDraft.id = ''",
                                     "state.moveBusy = true", "connected = false"])
def test_cancel_unsaved_busy_and_disconnected_states_do_not_delete(change: str) -> None:
    result = run_delete(
        f"""(async () => {{{change}; await deleteMoveTask();
            return {{requests, events, renders}};}})()"""
    )

    assert result == {"requests": [], "events": [], "renders": 0}


@pytest.mark.parametrize("failure_mode", ["failure = true",
                                         "response = {revision: 'next', tasks: [original]}"])
def test_failed_delete_keeps_unsaved_input_and_saved_list(failure_mode: str) -> None:
    result = run_delete(
        f"""(async () => {{{failure_mode}; await deleteMoveTask();
            return {{state, events, focused, renders, errors}};}})()"""
    )

    assert result["state"]["moveDraft"]["name"] == "Unsaved edit"
    assert result["state"]["moveTasks"][0]["id"] == "move/1"
    assert result["state"]["moveRevision"] == "old"
    assert result["state"]["moveFormOpen"] is True
    assert result["state"]["moveBusy"] is False
    assert result["events"] == []
    assert result["focused"] is False
    assert result["renders"] == 0
    assert result["errors"]
