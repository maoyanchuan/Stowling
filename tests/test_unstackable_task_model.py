import json
import shutil
import subprocess
from pathlib import Path
from typing import Any

import pytest


ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "src" / "main" / "resources" / "stowling-web"
NODE = shutil.which("node")
NODE_RUNNER = r"""
const fs = require("node:fs");
const vm = require("node:vm");
const [base, model, expression] = process.argv.slice(1);
globalThis.window = {Stowling: {clone: value => JSON.parse(JSON.stringify(value))}};
for (const file of [base, model]) vm.runInThisContext(fs.readFileSync(file, "utf8"));
const result = vm.runInThisContext(`(${expression})`);
process.stdout.write(JSON.stringify(result));
"""


def _run(expression: str) -> Any:
    if NODE is None:
        pytest.skip("Node.js is required to exercise the task model")
    result = subprocess.run(
        [NODE, "-e", NODE_RUNNER, str(WEB / "v2-task-model.js"),
         str(WEB / "unstackable-task-model.js"), expression],
        check=True,
        capture_output=True,
        encoding="utf-8",
        text=True,
    )
    return json.loads(result.stdout)


def test_task_saves_as_a_standalone_module_with_cache_recovery() -> None:
    result = _run("""
        (() => {
            const model = window.StowlingUnstackableTaskModel;
            const draft = {...model.newDraft(), regionRef: "region:cache",
                targetRef: "region:storage"};
            const task = model.createTask(draft, [{id: "cache"}, {id: "storage"}], []);
            return {payload: window.StowlingTaskModel.payloadTask(task),
                recognized: model.isUnstackableTask(task),
                restored: model.draftFromTask(task)};
        })()
    """)

    payload = result["payload"]
    assert result["recognized"]
    assert payload["priority"] == "BACKGROUND"
    assert not payload["repeatWhenIdle"]
    assert payload["cleanupPolicy"] == {
        "destinationRef": "region:cache", "resumeMode": "RESTART"
    }
    assert len(payload["steps"]) == 1
    assert payload["steps"][0]["moduleId"] == "SORT_UNSTACKABLE_ITEMS"
    assert payload["steps"][0]["parameters"] == {
        "regionRef": "region:cache", "targetRef": "region:storage"
    }
    assert result["restored"]["targetRef"] == "region:storage"


def test_invalid_regions_duplicate_names_and_changed_tasks_are_rejected() -> None:
    result = _run("""
        (() => {
            const model = window.StowlingUnstackableTaskModel;
            const draft = {...model.newDraft(), regionRef: "region:cache",
                targetRef: "region:storage"};
            const regions = [{id: "cache"}, {id: "storage"}];
            return [
                [{...draft, targetRef: draft.regionRef}, []],
                [{...draft, targetRef: "region:missing"}, []],
                [{...draft, regionRef: "coordinate:cache"}, []],
                [{...draft, name: "used"}, [{id: "other", name: "USED"}]],
                [{...draft, id: "deleted"}, []]
            ].map(([value, tasks]) => {
                try {model.createTask(value, regions, tasks); return "";}
                catch (error) {return error.message;}
            });
        })()
    """)

    assert all(result)
    assert "独立区域" in result[0]
    assert "已存在" in result[3]


def test_editing_preserves_task_and_step_ids() -> None:
    result = _run("""
        (() => {
            const model = window.StowlingUnstackableTaskModel;
            const regions = [{id: "cache"}, {id: "storage"}];
            const task = model.createTask({...model.newDraft(), regionRef: "region:cache",
                targetRef: "region:storage"}, regions, []);
            task.id = "saved";
            task.steps[0].id = "step";
            task.folderId = "folder";
            const changed = model.createTask({...model.draftFromTask(task), name: "renamed"},
                regions, [task]);
            return {id: changed.id, stepId: changed.steps[0].id, folderId: changed.folderId,
                recognized: model.isUnstackableTask({...task, steps: []})};
        })()
    """)

    assert result == {"id": "saved", "stepId": "step", "folderId": "folder", "recognized": False}


def test_task_page_exposes_the_dedicated_editor_and_queue_action() -> None:
    html = (WEB / "tasks.html").read_text(encoding="utf-8")
    ui = (WEB / "unstackable-task-ui.js").read_text(encoding="utf-8")

    for element_id in ("new", "list", "draft", "form", "cache", "storage", "save", "remove"):
        assert f'id="unstackable-{element_id}"' in html
    assert "排除潜影盒" in html
    assert '<script src="/unstackable-task-model.js" defer></script>' in html
    assert '<script src="/unstackable-task-ui.js" defer></script>' in html
    assert "/runs`" in ui
    assert "revision: state.revision" in ui
    assert 'method: "DELETE"' in ui
    assert "stowling:unstackable-task-count" in ui
