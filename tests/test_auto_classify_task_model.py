import json
import shutil
import subprocess
from pathlib import Path
from typing import Any

import pytest


ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "src" / "main" / "resources" / "stowling-web"
MODEL = WEB / "auto-classify-task-model.js"
NODE = shutil.which("node")
pytestmark = pytest.mark.skipif(
    NODE is None,
    reason="Node.js is required to exercise the auto-classify task model",
)

NODE_RUNNER = r"""
const fs = require("node:fs");
const vm = require("node:vm");
const [modelPath, expression] = process.argv.slice(1);
globalThis.window = {};
vm.runInThisContext(fs.readFileSync(modelPath, "utf8"), { filename: modelPath });
const result = vm.runInThisContext(`(${expression})`);
process.stdout.write(JSON.stringify(result));
"""


def _run(expression: str) -> Any:
    if NODE is None:
        raise RuntimeError("Node.js is unavailable")
    completed = subprocess.run(
        [NODE, "-e", NODE_RUNNER, str(MODEL), expression],
        check=True,
        capture_output=True,
        encoding="utf-8",
        text=True,
    )
    return json.loads(completed.stdout)


def test_new_draft_has_contract_defaults_and_uses_first_free_name() -> None:
    result = _run(
        """
        (() => {
            const model = window.StowlingAutoClassifyTaskModel;
            return {
                empty: model.newDraft([]),
                gap: model.newDraft([
                    { id: "item-1", name: "  自动分类  " },
                    { id: "item-2", name: "自动分类 3" },
                ], {
                    tasks: [{ id: "task-1", name: "自动分类 4" }],
                }).name,
                continuous: model.newDraft([
                    { id: "item-1", name: "自动分类" },
                    { id: "item-2", name: "自动分类 2" },
                    { id: "item-3", name: "自动分类 3" },
                ]).name,
            };
        })()
        """
    )

    assert result["empty"] == {
        "id": "",
        "name": "自动分类",
        "coordinateId": "",
        "pickupRadius": "8",
        "cacheRegionId": "",
        "classificationAreas": [{
            "categoryZoneId": "",
            "ruleId": "DIRECT_BLOCK_ID",
            "markerSources": [
                "PLACED_ITEM",
                "ITEM_FRAME",
                "SHELF",
                "FLOWER_POT",
            ],
        }],
        "collectionPriority": "NORMAL",
        "enabled": False,
        "collectionEnabled": True,
        "classificationEnabled": True,
    }
    assert result["gap"] == "自动分类 2"
    assert result["continuous"] == "自动分类 4"


def test_draft_from_task_reads_only_flat_business_fields() -> None:
    result = _run(
        """
        (() => {
            const model = window.StowlingAutoClassifyTaskModel;
            return model.draftFromTask({
                id: "auto-1",
                name: "收集矿石",
                enabled: true,
                collectionEnabled: false,
                classificationEnabled: true,
                detection: {
                    coordinateRef: "coordinate:coordinate-1",
                    pickupRadius: 12,
                },
                cacheRegionRef: "region:cache-1",
                classificationAreas: [{
                    categoryZoneRef: "category-zone:ore-zone",
                    ruleId: "DIRECT_BLOCK_ID",
                    markerSources: ["SHELF", "PLACED_ITEM"],
                }, {
                    categoryZoneRef: "category-zone:storage-zone",
                    ruleId: "CONTAINER_CONTENTS",
                    markerSources: ["CONTAINER_CONTENTS"],
                }],
                collectionPriority: "URGENT",
                classificationPriority: "BACKGROUND",
                collectionTaskRef: "task:collect",
                classificationTaskRef: "task:classify",
                interruptionTaskRef: "task:interrupt",
                triggerIds: ["trigger:1"],
            });
        })()
        """
    )

    assert result == {
        "id": "auto-1",
        "name": "收集矿石",
        "coordinateId": "coordinate-1",
        "pickupRadius": "12",
        "cacheRegionId": "cache-1",
        "classificationAreas": [{
            "categoryZoneId": "ore-zone",
            "ruleId": "DIRECT_BLOCK_ID",
            "markerSources": ["SHELF", "PLACED_ITEM"],
        }, {
            "categoryZoneId": "storage-zone",
            "ruleId": "CONTAINER_CONTENTS",
            "markerSources": ["CONTAINER_CONTENTS"],
        }],
        "collectionPriority": "URGENT",
        "enabled": True,
        "collectionEnabled": False,
        "classificationEnabled": True,
    }


def test_draft_from_task_rejects_untyped_or_wrongly_typed_references() -> None:
    result = _run(
        """
        (() => {
            const model = window.StowlingAutoClassifyTaskModel;
            return model.draftFromTask({
                id: "auto-2",
                name: "引用容错",
                enabled: false,
                collectionEnabled: true,
                classificationEnabled: false,
                detection: {
                    coordinateRef: "coordinate-1",
                    pickupRadius: 8,
                },
                cacheRegionRef: "coordinate:region-1",
                classificationAreas: [{
                    categoryZoneRef: "category-zone:",
                    ruleId: "DIRECT_BLOCK_ID",
                    markerSources: ["SHELF"],
                }],
                collectionPriority: "NORMAL",
            });
        })()
        """
    )

    assert result == {
        "id": "auto-2",
        "name": "引用容错",
        "coordinateId": "",
        "pickupRadius": "8",
        "cacheRegionId": "",
        "classificationAreas": [{
            "categoryZoneId": "",
            "ruleId": "DIRECT_BLOCK_ID",
            "markerSources": ["SHELF"],
        }],
        "collectionPriority": "NORMAL",
        "enabled": False,
        "collectionEnabled": True,
        "classificationEnabled": False,
    }


def test_payload_serializes_only_post_fields_and_round_trips() -> None:
    result = _run(
        """
        (() => {
            const model = window.StowlingAutoClassifyTaskModel;
            const resources = {
                coordinates: [{ id: "coordinate-1" }],
                regions: [{ id: "region-1" }],
                categoryZones: [{
                    id: "zone-1",
                    bindings: [{ id: "binding-1", ruleId: "DIRECT_BLOCK_ID" }],
                }, {
                    id: "zone-2",
                    bindings: [{ id: "binding-2", ruleId: "CONTAINER_CONTENTS" }],
                }],
                categoryRules: [
                    { id: "DIRECT_BLOCK_ID", name: "展示物精确匹配" },
                    { id: "CONTAINER_CONTENTS", name: "标记箱内容" },
                ],
            };
            const items = [{ id: "auto-1", name: "自动收集" }];
            const draft = {
                id: "auto-1",
                name: "自动收集",
                coordinateId: "coordinate-1",
                pickupRadius: " 8 ",
                cacheRegionId: "region-1",
                classificationAreas: [{
                    categoryZoneId: "zone-1",
                    ruleId: "DIRECT_BLOCK_ID",
                    markerSources: ["PLACED_ITEM", "ITEM_FRAME", "SHELF"],
                }, {
                    categoryZoneId: "zone-2",
                    ruleId: "CONTAINER_CONTENTS",
                    markerSources: ["CONTAINER_CONTENTS"],
                }],
                collectionPriority: "URGENT",
                enabled: false,
                collectionEnabled: false,
                classificationEnabled: true,
            };
            const body = model.payload(draft, resources, items);
            const enabledDraft = { ...draft, name: "自动收集 2", enabled: true };
            const enabledBody = model.payload(enabledDraft, resources, items);
            const item = {
                id: "auto-1",
                ...body,
                classificationPriority: "BACKGROUND",
                collectionTaskRef: "task:collect",
                classificationTaskRef: "task:classify",
                interruptionTaskRef: "task:interrupt",
                triggerIds: ["trigger:1"],
            };
            return {
                body,
                bodyKeys: Object.keys(body),
                detectionKeys: Object.keys(body.detection),
                enabled: enabledBody.enabled,
                roundTrip: model.draftFromTask(item),
            };
        })()
        """
    )

    body = result["body"]
    assert body == {
        "name": "自动收集",
        "enabled": False,
        "collectionEnabled": False,
        "classificationEnabled": True,
        "detection": {
            "coordinateRef": "coordinate:coordinate-1",
            "pickupRadius": 8,
        },
        "cacheRegionRef": "region:region-1",
        "classificationAreas": [{
            "categoryZoneRef": "category-zone:zone-1",
            "ruleId": "DIRECT_BLOCK_ID",
            "markerSources": ["PLACED_ITEM", "ITEM_FRAME", "SHELF"],
        }, {
            "categoryZoneRef": "category-zone:zone-2",
            "ruleId": "CONTAINER_CONTENTS",
            "markerSources": ["CONTAINER_CONTENTS"],
        }],
        "collectionPriority": "URGENT",
    }
    assert result["bodyKeys"] == [
        "name",
        "enabled",
        "collectionEnabled",
        "classificationEnabled",
        "detection",
        "cacheRegionRef",
        "classificationAreas",
        "collectionPriority",
    ]
    assert result["detectionKeys"] == ["coordinateRef", "pickupRadius"]
    assert result["enabled"] is True
    assert result["roundTrip"] == {
        "id": "auto-1",
        "name": "自动收集",
        "coordinateId": "coordinate-1",
        "pickupRadius": "8",
        "cacheRegionId": "region-1",
        "classificationAreas": [{
            "categoryZoneId": "zone-1",
            "ruleId": "DIRECT_BLOCK_ID",
            "markerSources": ["PLACED_ITEM", "ITEM_FRAME", "SHELF"],
        }, {
            "categoryZoneId": "zone-2",
            "ruleId": "CONTAINER_CONTENTS",
            "markerSources": ["CONTAINER_CONTENTS"],
        }],
        "collectionPriority": "URGENT",
        "enabled": False,
        "collectionEnabled": False,
        "classificationEnabled": True,
    }


def test_marker_sources_reject_empty_or_unknown_legacy_values() -> None:
    result = _run(
        """
        (() => {
            const model = window.StowlingAutoClassifyTaskModel;
            const resources = {
                coordinates: [{ id: "coordinate-1" }],
                regions: [{ id: "region-1" }],
                categoryZones: [{
                    id: "zone-1",
                    bindings: [{
                        id: "binding-1",
                        ruleId: "DIRECT_BLOCK_ID",
                    }],
                }],
                categoryRules: [
                    { id: "DIRECT_BLOCK_ID", name: "展示物精确匹配" },
                    { id: "CONTAINER_CONTENTS", name: "标记箱内容" },
                ],
            };
            const area = {
                categoryZoneId: "zone-1",
                ruleId: "DIRECT_BLOCK_ID",
                markerSources: ["PLACED_ITEM", "ITEM_FRAME", "SHELF"],
            };
            const base = {
                id: "",
                name: "自动分类",
                coordinateId: "coordinate-1",
                pickupRadius: "8",
                cacheRegionId: "region-1",
                classificationAreas: [area],
                collectionPriority: "NORMAL",
                enabled: false,
                collectionEnabled: true,
                classificationEnabled: true,
            };
            const payloadError = (markerSources) => {
                try {
                    model.payload({
                        ...base,
                        classificationAreas: [{ ...area, markerSources }],
                    }, resources, []);
                    return "";
                } catch (error) {
                    return `${error.name}:${error.message}`;
                }
            };
            return {
                all: model.validateDraft(base, resources, []),
                one: model.validateDraft({
                    ...base,
                    classificationAreas: [{ ...area, markerSources: ["SHELF"] }],
                }, resources, []),
                empty: model.validateDraft({
                    ...base,
                    classificationAreas: [{ ...area, markerSources: [] }],
                }, resources, []),
                unknown: model.validateDraft({
                    ...base,
                    classificationAreas: [{
                        ...area,
                        markerSources: ["ITEM_FRAME", "UNKNOWN_SOURCE"],
                    }],
                }, resources, []),
                emptyPayload: payloadError([]),
                unknownPayload: payloadError(["UNKNOWN_SOURCE"]),
            };
        })()
        """
    )

    assert result["all"] == []
    assert result["one"] == []
    assert result["empty"] == ["每个分类选区都需至少选择一种识别方式"]
    assert result["unknown"] == ["分类物识别方式包含未知选项"]
    assert "每个分类选区都需至少选择一种识别方式" in result["emptyPayload"]
    assert "分类物识别方式包含未知选项" in result["unknownPayload"]
    assert "RangeError" in result["unknownPayload"]


def test_deleted_resource_references_are_reported_per_resource() -> None:
    result = _run(
        """
        (() => {
            const model = window.StowlingAutoClassifyTaskModel;
            const resources = {
                coordinates: [{ id: "coordinate-1" }],
                regions: [{ id: "region-1" }],
                categoryZones: [{
                    id: "zone-1",
                    bindings: [{
                        id: "binding-1",
                        ruleId: "DIRECT_BLOCK_ID",
                    }],
                }],
                categoryRules: [
                    { id: "DIRECT_BLOCK_ID", name: "展示物精确匹配" },
                    { id: "CONTAINER_CONTENTS", name: "标记箱内容" },
                ],
            };
            const area = {
                categoryZoneId: "zone-1",
                ruleId: "DIRECT_BLOCK_ID",
                markerSources: ["PLACED_ITEM"],
            };
            const base = {
                id: "",
                name: "自动分类",
                coordinateId: "coordinate-1",
                pickupRadius: "8",
                cacheRegionId: "region-1",
                classificationAreas: [area],
                collectionPriority: "NORMAL",
                enabled: false,
                collectionEnabled: true,
                classificationEnabled: true,
            };
            return model.validateDraft({
                ...base,
                coordinateId: "deleted-coordinate",
                cacheRegionId: "deleted-region",
                classificationAreas: [{
                    ...area,
                    categoryZoneId: "deleted-zone",
                }],
            }, resources, []);
        })()
        """
    )

    assert result == [
        "所选掉落物检测点不存在，请刷新后重试",
        "所选缓存区域不存在，请刷新后重试",
        "分类选区引用的分类区不存在，请刷新后重试",
    ]


@pytest.mark.parametrize(
    ("radius", "valid"),
    [
        ("1", True),
        ("64", True),
        (" 64 ", True),
        ("0", False),
        ("65", False),
        ("-1", False),
        ("1.5", False),
        ("1e1", False),
        ("", False),
    ],
)
def test_pickup_radius_accepts_only_integer_range(
    radius: str,
    valid: bool,
) -> None:
    result = _run(
        f"""
        (() => {{
            const model = window.StowlingAutoClassifyTaskModel;
            const resources = {{
                coordinates: [{{ id: "coordinate-1" }}],
                regions: [{{ id: "region-1" }}],
                categoryZones: [{{
                    id: "zone-1",
                    bindings: [{{
                        id: "binding-1",
                        ruleId: "DIRECT_BLOCK_ID",
                    }}],
                }}],
                categoryRules: [
                    {{ id: "DIRECT_BLOCK_ID", name: "展示物精确匹配" }},
                ],
            }};
            const draft = {{
                id: "",
                name: "自动分类",
                coordinateId: "coordinate-1",
                pickupRadius: {json.dumps(radius)},
                cacheRegionId: "region-1",
                classificationAreas: [{{
                    categoryZoneId: "zone-1",
                    ruleId: "DIRECT_BLOCK_ID",
                    markerSources: ["PLACED_ITEM"],
                }}],
                collectionPriority: "NORMAL",
                enabled: false,
                collectionEnabled: true,
                classificationEnabled: true,
            }};
            return model.validateDraft(draft, resources, []);
        }})()
        """
    )

    if valid:
        assert result == []
    else:
        assert result == ["拾取半径需为1～64的整数"]


def test_name_validation_trims_case_folds_and_excludes_current_id() -> None:
    result = _run(
        """
        (() => {
            const model = window.StowlingAutoClassifyTaskModel;
            const resources = {
                coordinates: [{ id: "coordinate-1" }],
                regions: [{ id: "region-1" }],
                categoryZones: [{
                    id: "zone-1",
                    bindings: [{
                        id: "binding-1",
                        ruleId: "DIRECT_BLOCK_ID",
                    }],
                }],
                categoryRules: [
                    { id: "DIRECT_BLOCK_ID", name: "展示物精确匹配" },
                    { id: "CONTAINER_CONTENTS", name: "标记箱内容" },
                ],
            };
            const area = {
                categoryZoneId: "zone-1",
                ruleId: "DIRECT_BLOCK_ID",
                markerSources: ["PLACED_ITEM"],
            };
            const base = {
                id: "current-id",
                name: "  Auto Sort  ",
                coordinateId: "coordinate-1",
                pickupRadius: "8",
                cacheRegionId: "region-1",
                classificationAreas: [area],
                collectionPriority: "NORMAL",
                enabled: false,
                collectionEnabled: true,
                classificationEnabled: true,
            };
            return {
                self: model.validateDraft(base, resources, [
                    { id: "current-id", name: "auto sort" },
                ]),
                itemConflict: model.validateDraft(base, resources, [
                    { id: "current-id", name: "auto sort" },
                    { id: "other-id", name: "AUTO SORT" },
                ]),
                taskConflict: model.validateDraft(base, resources, [], {
                    tasks: [{ id: "task-1", name: " auto sort " }],
                }),
                maxLength: model.validateDraft({
                    ...base,
                    name: "a".repeat(80),
                }, resources, []),
                tooLong: model.validateDraft({
                    ...base,
                    name: "a".repeat(81),
                }, resources, []),
            };
        })()
        """
    )

    assert result["self"] == []
    assert result["itemConflict"] == ["任务名称已存在，请使用其他名称"]
    assert result["taskConflict"] == ["任务名称已存在，请使用其他名称"]
    assert result["maxLength"] == []
    assert result["tooLong"] == ["任务名称需为1～80个字符"]


def test_collection_priority_and_enabled_are_strictly_validated() -> None:
    result = _run(
        """
        (() => {
            const model = window.StowlingAutoClassifyTaskModel;
            const resources = {
                coordinates: [{ id: "coordinate-1" }],
                regions: [{ id: "region-1" }],
                categoryZones: [{
                    id: "zone-1",
                    bindings: [{
                        id: "binding-1",
                        ruleId: "DIRECT_BLOCK_ID",
                    }],
                }],
                categoryRules: [
                    { id: "DIRECT_BLOCK_ID", name: "展示物精确匹配" },
                    { id: "CONTAINER_CONTENTS", name: "标记箱内容" },
                ],
            };
            const draft = {
                id: "",
                name: "自动分类",
                coordinateId: "coordinate-1",
                pickupRadius: "8",
                cacheRegionId: "region-1",
                classificationAreas: [{
                    categoryZoneId: "zone-1",
                    ruleId: "DIRECT_BLOCK_ID",
                    markerSources: ["PLACED_ITEM"],
                }],
                collectionPriority: "BACKGROUND",
                enabled: "false",
                collectionEnabled: true,
                classificationEnabled: true,
            };
            return model.validateDraft(draft, resources, []);
        })()
        """
    )

    assert result == [
        "收集优先级只能为普通或紧急",
        "启用状态需为布尔值",
    ]


def test_phase_enable_flags_are_strict_and_round_trip() -> None:
    result = _run(
        """
        (() => {
            const model = window.StowlingAutoClassifyTaskModel;
            const resources = {
                coordinates: [{ id: "coordinate-1" }],
                regions: [{ id: "region-1" }],
                categoryZones: [{
                    id: "zone-1",
                    bindings: [{
                        id: "binding-1",
                        ruleId: "DIRECT_BLOCK_ID",
                    }],
                }],
                categoryRules: [
                    { id: "DIRECT_BLOCK_ID", name: "展示物精确匹配" },
                    { id: "CONTAINER_CONTENTS", name: "标记箱内容" },
                ],
            };
            const draft = {
                id: "",
                name: "按阶段启用",
                coordinateId: "coordinate-1",
                pickupRadius: "8",
                cacheRegionId: "region-1",
                classificationAreas: [{
                    categoryZoneId: "zone-1",
                    ruleId: "DIRECT_BLOCK_ID",
                    markerSources: ["PLACED_ITEM"],
                }],
                collectionPriority: "NORMAL",
                enabled: false,
                collectionEnabled: false,
                classificationEnabled: true,
            };
            return {
                body: model.payload(draft, resources, []),
                invalid: model.validateDraft({
                    ...draft,
                    collectionEnabled: "false",
                    classificationEnabled: 1,
                }, resources, []),
                roundTrip: model.draftFromTask({
                    id: "auto-1",
                    ...model.payload(draft, resources, []),
                }),
            };
        })()
        """
    )

    assert result["body"]["collectionEnabled"] is False
    assert result["body"]["classificationEnabled"] is True
    assert result["invalid"] == [
        "启用缓存需为布尔值",
        "后台分类需为布尔值",
    ]
    assert result["roundTrip"]["collectionEnabled"] is False
    assert result["roundTrip"]["classificationEnabled"] is True


def test_classification_areas_require_unique_bindings_and_compatible_rules() -> None:
    result = _run(
        """
        (() => {
            const model = window.StowlingAutoClassifyTaskModel;
            const resources = {
                coordinates: [{ id: "coordinate-1" }],
                regions: [{ id: "region-1" }],
                categoryZones: [{
                    id: "zone-1",
                    bindings: [
                        { id: "binding-1", ruleId: "DIRECT_BLOCK_ID" },
                        { id: "binding-2", ruleId: "CONTAINER_CONTENTS" },
                    ],
                }],
                categoryRules: [
                    { id: "DIRECT_BLOCK_ID" },
                    { id: "CONTAINER_CONTENTS" },
                ],
            };
            const base = {
                id: "",
                name: "多选区",
                coordinateId: "coordinate-1",
                pickupRadius: "8",
                cacheRegionId: "region-1",
                classificationAreas: [{
                    categoryZoneId: "zone-1",
                    ruleId: "DIRECT_BLOCK_ID",
                    markerSources: ["PLACED_ITEM"],
                }],
                collectionPriority: "NORMAL",
                enabled: false,
                collectionEnabled: true,
                classificationEnabled: true,
            };
            return {
                duplicate: model.validateDraft({
                    ...base,
                    classificationAreas: [
                        ...base.classificationAreas,
                        { ...base.classificationAreas[0] },
                    ],
                }, resources, []),
                incompatible: model.validateDraft({
                    ...base,
                    classificationAreas: [{
                        categoryZoneId: "zone-1",
                        ruleId: "CONTAINER_CONTENTS",
                        markerSources: ["PLACED_ITEM"],
                    }],
                }, resources, []),
                defaults: {
                    direct: model.defaultMarkerSources("DIRECT_BLOCK_ID"),
                    container: model.defaultMarkerSources("CONTAINER_CONTENTS"),
                },
            };
        })()
        """
    )

    assert result["duplicate"] == ["同一个分类选区不能重复添加"]
    assert result["incompatible"] == ["分类物识别方式配置无效"]
    assert result["defaults"]["direct"] == [
        "PLACED_ITEM",
        "ITEM_FRAME",
        "SHELF",
        "FLOWER_POT",
    ]
    assert result["defaults"]["container"] == ["CONTAINER_CONTENTS"]


