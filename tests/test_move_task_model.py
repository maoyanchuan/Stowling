import json
import shutil
import subprocess
from pathlib import Path
from typing import Any

import pytest


ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "src" / "main" / "resources" / "stowling-web"
TASK_MODEL = WEB / "v2-task-model.js"
MOVE_MODEL = WEB / "move-task-model.js"
NODE = shutil.which("node")
pytestmark = pytest.mark.skipif(
    NODE is None,
    reason="Node.js is required to exercise the JavaScript move task model",
)

NODE_RUNNER = r"""
const fs = require("node:fs");
const vm = require("node:vm");
const [taskModelPath, moveModelPath, expression] = process.argv.slice(1);
globalThis.window = {
    Stowling: {
        clone(value) {
            return JSON.parse(JSON.stringify(value));
        },
    },
};
for (const path of [taskModelPath, moveModelPath]) {
    vm.runInThisContext(fs.readFileSync(path, "utf8"), { filename: path });
}
const result = vm.runInThisContext(`(${expression})`);
process.stdout.write(JSON.stringify(result));
"""


def _run(expression: str) -> Any:
    if NODE is None:
        raise RuntimeError("Node.js is unavailable")
    completed = subprocess.run(
        [NODE, "-e", NODE_RUNNER, str(TASK_MODEL), str(MOVE_MODEL), expression],
        check=True,
        capture_output=True,
        encoding="utf-8",
        text=True,
    )
    return json.loads(completed.stdout)


def test_new_draft_returns_fixed_shape_and_first_free_default_name() -> None:
    result = _run(
        """
        (() => {
            const model = window.StowlingMoveTaskModel;
            /**
             * @param {string} id
             * @param {string} name
             * @returns {object}
             */
            const complexTask = (id, name) => ({
                id,
                name,
                priority: "NORMAL",
                steps: [{
                    kind: "ACTION",
                    moduleId: "ASSERT_NO_MANAGED_ITEMS",
                    parameters: {},
                    steps: [],
                    otherwise: [],
                }],
            });
            return {
                empty: model.newDraft([]),
                gap: model.newDraft([
                    complexTask("complex-1", "移动到坐标"),
                    complexTask("complex-2", "  移动到坐标 2  "),
                    complexTask("complex-3", "移动到坐标 4"),
                ]).name,
                continuous: model.newDraft([
                    complexTask("complex-1", "移动到坐标"),
                    complexTask("complex-2", "移动到坐标 2"),
                    complexTask("complex-3", "移动到坐标 3"),
                ]).name,
            };
        })()
        """
    )

    assert result["empty"] == {
        "id": "",
        "name": "移动到坐标",
        "priority": "NORMAL",
        "source": "manual",
        "x": "",
        "y": "",
        "z": "",
        "coordinateId": "",
    }
    assert result["gap"] == "移动到坐标 3"
    assert result["continuous"] == "移动到坐标 4"


def test_move_task_detection_accepts_only_supported_basic_shapes() -> None:
    result = _run(
        """
        (() => {
            const model = window.StowlingMoveTaskModel;
            const base = {
                id: "task-1",
                name: "移动到坐标",
                folderId: "folder-1",
                priority: "NORMAL",
                interruptionMode: "DEFAULT",
                steps: [{
                    kind: "ACTION",
                    moduleId: "MOVE_TO_COORDINATE",
                    parameters: { x: "1", y: "2", z: "3" },
                    steps: [],
                    otherwise: [],
                }],
            };
            const manual = structuredClone(base);
            const saved = structuredClone(base);
            saved.interruptionMode = "NONE";
            saved.steps[0].parameters = {
                targetRef: "coordinate:00000000-0000-0000-0000-000000000001",
            };
            const legacy = structuredClone(base);
            legacy.steps[0].parameters = { coordinateId: "legacy-coordinate" };
            const output = structuredClone(saved);
            output.steps[0].parameters = { targetRef: "output:container" };
            const mixed = structuredClone(saved);
            mixed.steps[0].parameters.x = "1";
            const nested = structuredClone(manual);
            nested.steps[0].steps = [structuredClone(nested.steps[0])];
            const otherwise = structuredClone(manual);
            otherwise.steps[0].otherwise = [structuredClone(otherwise.steps[0])];
            const repeated = structuredClone(manual);
            repeated.repeatWhenIdle = true;
            const defaultFlow = structuredClone(manual);
            defaultFlow.defaultInterruptionFlow = true;
            const requeued = structuredClone(manual);
            requeued.requeueAfterInterruption = true;
            const custom = structuredClone(manual);
            custom.interruptionMode = "CUSTOM";
            custom.interruptionTaskRef = "task:cleanup";
            const extraParameter = structuredClone(manual);
            extraParameter.steps[0].parameters.speed = "1";
            return {
                manual: model.isMoveTask(manual),
                saved: model.isMoveTask(saved),
                legacy: model.isMoveTask(legacy),
                output: model.isMoveTask(output),
                mixed: model.isMoveTask(mixed),
                nested: model.isMoveTask(nested),
                otherwise: model.isMoveTask(otherwise),
                repeated: model.isMoveTask(repeated),
                defaultFlow: model.isMoveTask(defaultFlow),
                requeued: model.isMoveTask(requeued),
                custom: model.isMoveTask(custom),
                extraParameter: model.isMoveTask(extraParameter),
                twoRoots: model.isMoveTask({
                    ...manual,
                    steps: [manual.steps[0], manual.steps[0]],
                }),
            };
        })()
        """
    )

    assert result["manual"] is True
    assert result["saved"] is True
    assert result["legacy"] is True
    for rejected in (
        "output",
        "mixed",
        "nested",
        "otherwise",
        "repeated",
        "defaultFlow",
        "requeued",
        "custom",
        "extraParameter",
        "twoRoots",
    ):
        assert result[rejected] is False


def test_draft_from_task_restores_manual_saved_and_legacy_values() -> None:
    result = _run(
        """
        (() => {
            const model = window.StowlingMoveTaskModel;
            const manual = {
                id: "manual-id",
                name: "手动移动",
                priority: "URGENT",
                steps: [{
                    kind: "ACTION",
                    moduleId: "MOVE_TO_COORDINATE",
                    parameters: { x: "-1", y: "+64", z: "80" },
                    steps: [],
                    otherwise: [],
                }],
            };
            const saved = {
                id: "saved-id",
                name: "保存坐标移动",
                priority: "BACKGROUND",
                steps: [{
                    kind: "ACTION",
                    moduleId: "MOVE_TO_COORDINATE",
                    parameters: { targetRef: "coordinate:saved-coordinate" },
                    steps: [],
                    otherwise: [],
                }],
            };
            const legacy = {
                id: "legacy-id",
                name: "旧坐标移动",
                priority: "NORMAL",
                steps: [{
                    kind: "ACTION",
                    moduleId: "MOVE_TO_COORDINATE",
                    parameters: { coordinateId: "legacy-coordinate" },
                    steps: [],
                    otherwise: [],
                }],
            };
            return {
                manual: model.draftFromTask(manual),
                saved: model.draftFromTask(saved),
                legacy: model.draftFromTask(legacy),
            };
        })()
        """
    )

    assert result["manual"]["source"] == "manual"
    assert (result["manual"]["x"], result["manual"]["y"], result["manual"]["z"]) == (
        "-1",
        "+64",
        "80",
    )
    assert result["saved"]["source"] == "saved"
    assert result["saved"]["coordinateId"] == "saved-coordinate"
    assert result["legacy"]["source"] == "saved"
    assert result["legacy"]["coordinateId"] == "legacy-coordinate"


def test_validation_only_checks_the_active_source_and_uses_chinese_messages() -> None:
    result = _run(
        """
        (() => {
            const model = window.StowlingMoveTaskModel;
            const base = model.newDraft();
            const coordinates = [{ id: "coordinate-1" }];
            return {
                manualWithSavedValue: model.validateDraft({
                    ...base,
                    x: "+1",
                    y: "-2",
                    z: "003",
                    coordinateId: "coordinate-1",
                }, coordinates),
                savedWithManualValues: model.validateDraft({
                    ...base,
                    source: "saved",
                    coordinateId: "coordinate-1",
                    x: "not-an-integer",
                    y: "",
                    z: "",
                }, coordinates),
                lowerBound: model.validateDraft({
                    ...base,
                    x: "-2147483648",
                    y: "2147483647",
                    z: "+0",
                }, coordinates),
                outOfRange: model.validateDraft({
                    ...base,
                    x: "2147483648",
                    y: "-2147483649",
                    z: "1.5",
                }, coordinates),
                invalid: model.validateDraft({
                    ...base,
                    name: " ",
                    priority: "INVALID",
                    source: "invalid",
                }, coordinates),
                missingSaved: model.validateDraft({
                    ...base,
                    source: "saved",
                    coordinateId: "missing",
                }, coordinates),
            };
        })()
        """
    )

    assert result["manualWithSavedValue"] == []
    assert result["savedWithManualValues"] == []
    assert result["lowerBound"] == []
    assert "X 坐标超出允许范围" in result["outOfRange"]
    assert "Y 坐标超出允许范围" in result["outOfRange"]
    assert "Z 坐标需为整数" in result["outOfRange"]
    assert "任务名称需为1～80个字符" in result["invalid"]
    assert "请选择运行优先级" in result["invalid"]
    assert "请选择坐标输入方式" in result["invalid"]
    assert result["missingSaved"] == ["所选坐标不存在，请刷新后重试"]
    assert not any("int32" in error for error in result["outOfRange"])


def test_name_validation_normalizes_and_excludes_the_edited_task() -> None:
    result = _run(
        """
        (() => {
            const model = window.StowlingMoveTaskModel;
            const base = {
                ...model.newDraft(),
                name: "本地名称",
                x: "1",
                y: "2",
                z: "3",
            };
            const tasks = [
                { id: "complex-1", name: "  MiXeD Task  " },
                { id: "self-id", name: "当前移动任务" },
            ];
            const duplicate = model.validateDraft(
                { ...base, name: " mixed TASK " },
                [],
                tasks
            );
            const self = model.validateDraft(
                { ...base, id: "self-id", name: " 当前移动任务 " },
                [],
                tasks
            );
            const created = model.createTask(
                { ...base, id: "self-id", name: " 当前移动任务 " },
                [],
                null,
                tasks
            );
            let duplicateCreate = null;
            try {
                model.createTask(
                    { ...base, name: " mixed TASK " },
                    [],
                    null,
                    tasks
                );
            } catch (error) {
                duplicateCreate = {
                    name: error.name,
                    message: error.message,
                };
            }
            return { duplicate, self, created, duplicateCreate };
        })()
        """
    )

    assert result["duplicate"] == ["任务名称已存在，请使用其他名称"]
    assert result["self"] == []
    assert result["created"]["name"] == "当前移动任务"
    assert result["duplicateCreate"]["name"] == "RangeError"
    assert "任务名称已存在，请使用其他名称" in result["duplicateCreate"]["message"]


def test_create_task_serializes_only_the_active_source() -> None:
    result = _run(
        """
        (() => {
            const model = window.StowlingMoveTaskModel;
            const coordinates = [{ id: "coordinate-1" }];
            const shared = {
                ...model.newDraft(),
                id: "task-1",
                name: "共享输入",
                x: "+10",
                y: "-64",
                z: "20",
                coordinateId: "coordinate-1",
            };
            const saved = model.createTask({ ...shared, source: "saved" }, coordinates);
            const manual = model.createTask(
                { ...shared, source: "manual" },
                coordinates
            );
            return {
                savedParameters: saved.steps[0].parameters,
                manualParameters: manual.steps[0].parameters,
                savedStep: saved.steps[0],
                manualStep: manual.steps[0],
            };
        })()
        """
    )

    assert result["savedParameters"] == {"targetRef": "coordinate:coordinate-1"}
    assert result["manualParameters"] == {"x": "+10", "y": "-64", "z": "20"}
    assert result["savedStep"]["kind"] == "ACTION"
    assert result["savedStep"]["moduleId"] == "MOVE_TO_COORDINATE"
    assert result["manualStep"]["kind"] == "ACTION"
    assert result["manualStep"]["moduleId"] == "MOVE_TO_COORDINATE"


def test_create_task_defaults_and_preserves_supported_metadata() -> None:
    result = _run(
        """
        (() => {
            const model = window.StowlingMoveTaskModel;
            const coordinates = [{ id: "coordinate-1" }];
            const original = {
                id: "task-1",
                name: "原任务",
                folderId: "folder-1",
                priority: "URGENT",
                interruptionMode: "NONE",
                defaultInterruptionFlow: false,
                repeatWhenIdle: false,
                requeueAfterInterruption: false,
                interruptionTaskRef: "",
                steps: [{
                    id: "step-1",
                    kind: "ACTION",
                    moduleId: "MOVE_TO_COORDINATE",
                    parameters: { x: "1", y: "2", z: "3" },
                    steps: [],
                    otherwise: [],
                }],
            };
            const created = model.createTask({
                ...model.newDraft(),
                id: "task-1",
                name: "更新后的任务",
                source: "saved",
                coordinateId: "coordinate-1",
            }, coordinates, original);
            const fresh = model.createTask({
                ...model.newDraft(),
                name: "新任务",
                priority: "NORMAL",
                x: "1",
                y: "2",
                z: "3",
            }, coordinates);
            return { created, fresh };
        })()
        """
    )

    assert result["created"]["id"] == "task-1"
    assert result["created"]["folderId"] == "folder-1"
    assert result["created"]["interruptionMode"] == "NONE"
    assert result["created"]["steps"][0]["id"] == "step-1"
    assert result["created"]["repeatWhenIdle"] is False
    assert result["created"]["defaultInterruptionFlow"] is False
    assert result["created"]["requeueAfterInterruption"] is False
    assert result["fresh"]["id"] == ""
    assert result["fresh"]["folderId"] == ""
    assert result["fresh"]["priority"] == "NORMAL"
    assert result["fresh"]["interruptionMode"] == "DEFAULT"
    assert result["fresh"]["defaultInterruptionFlow"] is False


def test_create_task_throws_range_error_before_building_a_task() -> None:
    result = _run(
        """
        (() => {
            const model = window.StowlingMoveTaskModel;
            try {
                model.createTask({
                    ...model.newDraft(),
                    name: "",
                    x: "x",
                    y: "",
                    z: "9999999999",
                }, []);
                return { threw: false };
            } catch (error) {
                return {
                    threw: true,
                    name: error.name,
                    message: error.message,
                };
            }
        })()
        """
    )

    assert result["threw"] is True
    assert result["name"] == "RangeError"
    assert "任务名称需为1～80个字符" in result["message"]
    assert "X 坐标需为整数" in result["message"]
    assert "请填写 Y 坐标" in result["message"]
    assert "Z 坐标超出允许范围" in result["message"]
    assert "；" in result["message"]
