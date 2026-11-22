import shutil
import subprocess
from pathlib import Path

import pytest


WEB = Path(__file__).resolve().parents[1] / "src" / "main" / "resources" / "stowling-web"


def test_all_task_editors_have_no_manual_save_button() -> None:
    html = (WEB / "tasks.html").read_text(encoding="utf-8")
    script_tag = '<script src="/task-autosave.js" defer></script>'
    for script in ("tasks", "auto-classify-ui", "auto-eat-ui", "material-collection-ui",
                   "refresh-cache-ui"):
        assert html.index(script_tag) < html.index(f'<script src="/{script}.js"')
    for form in ("move-task", "auto-task", "auto-eat", "material-task", "refresh-cache"):
        editor = html.split(f'id="{form}-form"', 1)[1].split("</form>", 1)[0]
        assert 'type="submit"' not in editor
        assert 'role="status"' in editor


def test_autosave_debounces_and_only_retries_unchanged_tasks() -> None:
    node = shutil.which("node")
    if node is None:
        pytest.skip("Node.js is needed to exercise autosave")
    script = """
const assert = require("node:assert/strict");
const original = {id: "example", name: "Original"};
let latest = original;
let blocked = false;
let saves = 0;
let next;
let calls = [];
globalThis.document = {activeElement: null};
globalThis.window = {
    /** @returns {void} */
    addEventListener() {},
    /** @param {Function} callback @returns {number} */
    setTimeout(callback) { next = callback; return 1; },
    /** @returns {void} */
    clearTimeout() { next = undefined; },
    Stowling: {
        /** @param {string} path @param {object} options @returns {Promise<object>} */
        async request(path, options) {
            const revision = options && JSON.parse(options.body).revision;
            calls.push([path, revision]);
            if (!options) return {revision: "latest", tasks: [latest]};
            if (revision === "old") {
                throw Object.assign(new Error("stale"), {status: 409, code: "revision_conflict"});
            }
            return {revision: "saved"};
        }
    }
};
require(process.argv[1]);
const {create, write, latestRevision} = window.StowlingTaskAutosave;
/** @returns {Promise<string>} */
const rebase = () => latestRevision("/v1/tasks", original, (response) => response.tasks[0]);

(async () => {
    const autosave = create({}, async () => { saves++; }, () => blocked);
    autosave.schedule();
    autosave.schedule();
    assert.equal(saves, 0);
    blocked = true;
    next();
    assert.equal(saves, 0);
    blocked = false;
    next();
    assert.equal(saves, 1);
    autosave.schedule();
    autosave.cancel();
    assert.equal(next, undefined);

    const path = "/v1/tasks/example";
    const payload = {revision: "old", name: "New name"};
    assert.deepEqual(await write(path, "PUT", payload, rebase), {revision: "saved"});
    assert.deepEqual(calls, [[path, "old"], ["/v1/tasks", undefined], [path, "latest"]]);

    latest = {...original, name: "External change"};
    calls = [];
    await assert.rejects(write(path, "PUT", payload, rebase), /其他位置修改/);
    assert.deepEqual(calls, [[path, "old"], ["/v1/tasks", undefined]]);
})().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
"""
    subprocess.run([node, "-e", script, str(WEB / "task-autosave.js")], check=True)
