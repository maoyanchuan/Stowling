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


@pytest.mark.parametrize("enabled,retain", [(False, True), (True, False), (True, True)])
def test_empty_box_warehouse_round_trip_preserves_switches_and_drafts(
    enabled: bool, retain: bool,
) -> None:
    value = {"enabled": enabled, "warehouseRef": "region:boxes",
             "retainInCache": retain, "cacheLimit": 12}
    result = _run("""(() => {
        const model = window.StowlingAutoClassifyTaskModel;
        const boxes = """ + json.dumps(value) + """;
        const draft = {...model.newDraft("CLASSIFICATION"), cacheLocationRef: "region:cache",
            classificationAreas: [{categoryZoneId: "zone"}],
            facilities: {emptyBoxWarehouse: boxes}};
        const saved = model.payload(draft, {regions: [{id: "cache"}, {id: "boxes"}],
            categoryZones: [{id: "zone"}]});
        return model.draftFromTask(saved).facilities.emptyBoxWarehouse;
    })()""")
    assert result == value


@pytest.mark.parametrize("ref,count", [("", 9), ("region:cache", 9),
                                      ("region:boxes", -1), ("region:boxes", 1.5)])
def test_empty_box_warehouse_rejects_missing_source_overlap_and_invalid_limit(
    ref: str, count: int | float,
) -> None:
    settings = json.dumps({"enabled": True, "warehouseRef": ref,
                           "retainInCache": True, "cacheLimit": count})
    errors = _run("""(() => {
        const model = window.StowlingAutoClassifyTaskModel;
        return model.validateDraft({...model.newDraft("CLASSIFICATION"),
            cacheLocationRef: "region:cache", classificationAreas: [{categoryZoneId: "zone"}],
            facilities: {emptyBoxWarehouse: """ + settings + """}},
            {regions: [{id: "cache"}, {id: "boxes"}], categoryZones: [{id: "zone"}]});
    })()""")
    assert len(errors) == 1
    assert "空盒" in errors[0]


@pytest.mark.parametrize("interval,delay", [(1, 30), (6, 45), (60, 0)])
def test_pickup_timing_settings_survive_save_and_reload(interval: int, delay: int) -> None:
    result = _run(f"""(() => {{
        const model = window.StowlingAutoClassifyTaskModel;
        const draft = {{...model.newDraft("PICKUP"), coordinateId: "point",
            followUpEnabled: true,
            cacheLocationRef: "coordinate:point", followUpTaskRef: "task:next",
            detectionIntervalSeconds: "{interval}", followUpDelaySeconds: "{delay}"}};
        const payload = model.payload(draft, {{coordinates: [{{id: "point"}}]}});
        return {{payload, loaded: model.draftFromTask(payload)}};
    }})()""")
    assert result["payload"]["detection"]["intervalSeconds"] == interval
    assert result["payload"]["followUpDelaySeconds"] == delay
    assert result["loaded"]["detectionIntervalSeconds"] == str(interval)
    assert result["loaded"]["followUpDelaySeconds"] == str(delay)
    assert result["loaded"]["followUpTaskRef"] == "task:next"


@pytest.mark.parametrize("field,value", [
    ("detectionIntervalSeconds", "0"), ("detectionIntervalSeconds", "61"),
    ("detectionIntervalSeconds", "1.5"), ("followUpDelaySeconds", "-1"),
    ("followUpDelaySeconds", "86401"), ("followUpDelaySeconds", ""),
])
def test_pickup_rejects_invalid_timing_settings(field: str, value: str) -> None:
    errors = _run(f"""(() => {{
        const model = window.StowlingAutoClassifyTaskModel;
        return model.validateDraft({{...model.newDraft("PICKUP"), coordinateId: "point",
            followUpEnabled: true,
            cacheLocationRef: "coordinate:point", {field}: {json.dumps(value)}}},
            {{coordinates: [{{id: "point"}}]}});
    }})()""")
    assert len(errors) == 1
    assert "间隔" in errors[0] or "倒计时" in errors[0]


def test_pickup_follow_up_defaults_off_and_keeps_legacy_successors_enabled() -> None:
    result = _run("""(() => {
        const model = window.StowlingAutoClassifyTaskModel;
        return {
            fresh: model.newDraft("PICKUP").followUpEnabled,
            empty: model.draftFromTask({kind: "PICKUP"}).followUpEnabled,
            legacy: model.draftFromTask({kind: "PICKUP",
                followUpTaskRef: "task:next"}).followUpEnabled,
            disabled: model.draftFromTask({kind: "PICKUP", followUpEnabled: false,
                followUpTaskRef: "task:next"}).followUpEnabled
        };
    })()""")
    assert result == {"fresh": False, "empty": False, "legacy": True, "disabled": False}


@pytest.mark.parametrize("enabled", [False, True])
@pytest.mark.parametrize("successor", ["", "task:next"])
def test_pickup_follow_up_switch_and_settings_survive_save_and_reload(
    enabled: bool, successor: str,
) -> None:
    result = _run(f"""(() => {{
        const model = window.StowlingAutoClassifyTaskModel;
        const draft = {{...model.newDraft("PICKUP"), coordinateId: "point",
            cacheLocationRef: "coordinate:point", followUpEnabled: {json.dumps(enabled)},
            followUpTaskRef: {json.dumps(successor)}, followUpDelaySeconds: "45"}};
        return model.draftFromTask(model.payload(draft, {{coordinates: [{{id: "point"}}]}}));
    }})()""")
    assert result["followUpEnabled"] is enabled
    assert result["followUpTaskRef"] == successor
    assert result["followUpDelaySeconds"] == "45"


def test_disabling_pickup_follow_up_allows_saving_with_an_unfinished_countdown() -> None:
    result = _run("""(() => {
        const model = window.StowlingAutoClassifyTaskModel;
        const draft = {...model.newDraft("PICKUP"), coordinateId: "point",
            cacheLocationRef: "coordinate:point", followUpTaskRef: "task:next",
            followUpDelaySeconds: ""};
        return model.payload(draft, {coordinates: [{id: "point"}]});
    })()""")
    assert result["followUpEnabled"] is False
    assert result["followUpTaskRef"] == "task:next"
    assert result["followUpDelaySeconds"] == 30


def test_task_configures_unstackable_sorting_through_a_layout() -> None:
    result = _run(
        """
        (() => {
            const model = window.StowlingAutoClassifyTaskModel;
            const draft = {
                ...model.newDraft(),
                coordinateId: "pickup",
                cacheLocationRef: "region:cache",
                classificationAreas: [{ categoryZoneId: "tools" }],
            };
            const resources = {
                coordinates: [{ id: "pickup" }],
                regions: [{ id: "cache" }],
                categoryZones: [{ id: "tools" }],
            };
            const payload = model.payload({ ...draft, unstackableRegionId: "old" }, resources, []);
            return {
                areas: payload.classificationAreas,
                hasLegacyField: Object.hasOwn(payload, "unstackableRegionRef"),
                missingLayout: model.validateDraft({
                    ...draft, classificationAreas: [], unstackableRegionId: "old",
                }, resources, []),
            };
        })()
        """
    )

    assert result == {
        "areas": [{"categoryZoneRef": "category-zone:tools"}],
        "hasLegacyField": False,
        "missingLayout": ["请至少添加一个目标分类区"],
    }


def test_new_draft_has_contract_defaults_without_a_name() -> None:
    result = _run("window.StowlingAutoClassifyTaskModel.newDraft()")

    assert result == {
        "id": "",
        "coordinateId": "",
        "pickupRadius": "",
        "detectionIntervalSeconds": "1",
        "cacheLocationRef": "",
        "cachePolicy": {"enabled": False, "mode": "PACK_FIRST", "thresholdPercent": 80},
        "classificationAreas": [{
            "categoryZoneId": "",
        }],
        "collectionPriority": "NORMAL",
        "enabled": False,
        "collectionEnabled": True,
        "classificationEnabled": True,
        "overflowEnabled": False,
        "overflowDestinationRef": "",
        "cleanupDestinationRef": "",
        "facilities": {},
    }


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
                cacheLocationRef: "region:cache-1",
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
        "coordinateId": "coordinate-1",
        "pickupRadius": "12",
        "detectionIntervalSeconds": "1",
        "cacheLocationRef": "region:cache-1",
        "cachePolicy": {"enabled": False, "mode": "PACK_FIRST", "thresholdPercent": 80},
        "classificationAreas": [{
            "categoryZoneId": "ore-zone",
        }, {
            "categoryZoneId": "storage-zone",
        }],
        "collectionPriority": "URGENT",
        "enabled": True,
        "collectionEnabled": False,
        "classificationEnabled": True,
        "overflowEnabled": False,
        "overflowDestinationRef": "",
        "cleanupDestinationRef": "",
        "facilities": {},
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
                cacheLocationRef: "",
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
        "coordinateId": "",
        "pickupRadius": "8",
        "cacheLocationRef": "",
        "cachePolicy": {"enabled": False, "mode": "PACK_FIRST", "thresholdPercent": 80},
        "classificationAreas": [{
            "categoryZoneId": "",
        }],
        "collectionPriority": "NORMAL",
        "enabled": False,
        "collectionEnabled": True,
        "classificationEnabled": False,
        "overflowEnabled": False,
        "overflowDestinationRef": "",
        "cleanupDestinationRef": "",
        "facilities": {},
    }



def test_payload_serializes_only_post_fields_and_round_trips() -> None:
    result = _run(
        """
        (() => {
            const model = window.StowlingAutoClassifyTaskModel;
            const resources = {
                coordinates: [{ id: "coordinate-1" }],
                regions: [{ id: "region-1" }],
                warehouses: [{id: "overflow"}],
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
                cacheLocationRef: "region:region-1",
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
            const enabledDraft = { ...draft, name: "自动收集 2", enabled: true,
                overflowEnabled: true, overflowDestinationRef: "warehouse:overflow",
                cleanupDestinationRef: "coordinate:coordinate-1" };
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
                overflow: model.draftFromTask({id: "auto-2", ...enabledBody}).overflowEnabled,
                destination: model.draftFromTask(enabledBody).overflowDestinationRef,
                cleanup: model.draftFromTask({id: "auto-2", ...enabledBody}).cleanupDestinationRef,
                roundTrip: model.draftFromTask(item),
            };
        })()
        """
    )

    body = result["body"]
    assert body == {
        "enabled": False,
        "collectionEnabled": False,
        "classificationEnabled": True,
        "overflowEnabled": False,
        "overflowDestinationRef": "",
        "cleanupPolicy": {"destinationRef": None, "resumeMode": "RESTART"},
        "detection": {
            "coordinateRef": "coordinate:coordinate-1",
            "pickupRadius": 8,
            "intervalSeconds": 1,
        },
        "cacheLocationRef": "region:region-1",
        "cachePolicy": {"enabled": False, "mode": "PACK_FIRST", "thresholdPercent": 80},
        "classificationAreas": [{
            "categoryZoneRef": "category-zone:zone-1",
        }, {
            "categoryZoneRef": "category-zone:zone-2",
        }],
        "collectionPriority": "URGENT",
    }
    assert result["bodyKeys"] == [
        "enabled",
        "overflowEnabled",
        "overflowDestinationRef",
        "cleanupPolicy",
        "collectionEnabled",
        "classificationEnabled",
        "detection",
        "cacheLocationRef",
        "cachePolicy",
        "classificationAreas",
        "collectionPriority",
    ]
    assert result["detectionKeys"] == ["coordinateRef", "pickupRadius", "intervalSeconds"]
    assert result["enabled"] is True
    assert result["overflow"] is True
    assert result["destination"] == "warehouse:overflow"
    assert result["cleanup"] == "coordinate:coordinate-1"
    assert result["roundTrip"] == {
        "id": "auto-1",
        "coordinateId": "coordinate-1",
        "pickupRadius": "8",
        "detectionIntervalSeconds": "1",
        "cacheLocationRef": "region:region-1",
        "cachePolicy": {"enabled": False, "mode": "PACK_FIRST", "thresholdPercent": 80},
        "classificationAreas": [{
            "categoryZoneId": "zone-1",
        }, {
            "categoryZoneId": "zone-2",
        }],
        "collectionPriority": "URGENT",
        "enabled": False,
        "collectionEnabled": False,
        "classificationEnabled": True,
        "overflowEnabled": False,
        "overflowDestinationRef": "",
        "cleanupDestinationRef": "",
        "facilities": {"destinationRef": None, "resumeMode": "RESTART"},
    }



def test_legacy_recognition_is_discarded_without_requiring_category_rules() -> None:
    result = _run(
        """
        (() => {
            const model = window.StowlingAutoClassifyTaskModel;
            const resources = {
                coordinates: [{ id: "coordinate-1" }],
                regions: [{ id: "region-1" }],
                categoryZones: [{ id: "zone-1" }],
            };
            const draft = {
                ...model.newDraft([]),
                coordinateId: "coordinate-1",
                cacheLocationRef: "region:region-1",
                classificationAreas: [{
                    categoryZoneId: "zone-1",
                    ruleId: "UNKNOWN_LEGACY_RULE",
                    markerSources: ["UNKNOWN_SOURCE"],
                }],
            };
            const body = model.payload(draft, resources, []);
            return {
                errors: model.validateDraft(draft, resources, []),
                areas: body.classificationAreas,
                restored: model.draftFromTask({
                    ...body,
                    classificationAreas: [{
                        ...body.classificationAreas[0],
                        ruleId: "MARKER_CONTAINER_CONTENTS",
                        markerSources: ["CONTAINER_CONTENTS"],
                    }],
                }).classificationAreas,
            };
        })()
        """
    )

    assert result == {
        "errors": [],
        "areas": [{"categoryZoneRef": "category-zone:zone-1"}],
        "restored": [{"categoryZoneId": "zone-1"}],
    }


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
                cacheLocationRef: "region:region-1",
                classificationAreas: [area],
                collectionPriority: "NORMAL",
                enabled: false,
                collectionEnabled: true,
                classificationEnabled: true,
            };
            return model.validateDraft({
                ...base,
                coordinateId: "deleted-coordinate",
                cacheLocationRef: "region:deleted-region",
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
        "所选缓存位置不存在，请重新选择",
        "所选目标分类区不存在，请重新选择",
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
        ("", True),
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
                cacheLocationRef: "region:region-1",
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
        assert result == ["任务覆盖半径需为 1 到 64 格"]


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
                cacheLocationRef: "region:region-1",
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
                cacheLocationRef: "region:region-1",
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
        "掉落物拾取需为布尔值",
        "后台分类需为布尔值",
    ]
    assert result["roundTrip"]["collectionEnabled"] is False
    assert result["roundTrip"]["classificationEnabled"] is True


def test_classification_areas_reject_duplicate_zones() -> None:
    result = _run(
        """
        (() => {
            const model = window.StowlingAutoClassifyTaskModel;
            const resources = {
                coordinates: [{ id: "coordinate-1" }],
                regions: [{ id: "region-1" }],
                categoryZones: [{ id: "zone-1" }],
            };
            return model.validateDraft({
                ...model.newDraft([]),
                coordinateId: "coordinate-1",
                cacheLocationRef: "region:region-1",
                classificationAreas: [
                    { categoryZoneId: "zone-1" },
                    { categoryZoneId: "zone-1" },
                ],
            }, resources, []);
        })()
        """
    )

    assert result == ["同一个目标分类区不能重复添加"]


def test_cache_policy_defaults_and_selected_settings_survive_payload_round_trip() -> None:
    result = _run(
        """
        (() => {
            const model = window.StowlingAutoClassifyTaskModel;
            const draft = {...model.newDraft(), coordinateId: "pickup", cacheLocationRef: "region:cache",
                classificationAreas: [{categoryZoneId: "zone"}],
                cachePolicy: {enabled: true, mode: "CLASSIFY_FIRST", thresholdPercent: 65}};
            const resources = {coordinates: [{id: "pickup"}], regions: [{id: "cache"}],
                categoryZones: [{id: "zone"}]};
            const payload = model.payload(draft, resources, []);
            return {defaults: model.newDraft().cachePolicy,
                legacy: model.draftFromTask({}).cachePolicy,
                saved: payload.cachePolicy,
                reopened: model.draftFromTask(payload).cachePolicy};
        })()
        """
    )
    default = {"enabled": False, "mode": "PACK_FIRST", "thresholdPercent": 80}
    selected = {"enabled": True, "mode": "CLASSIFY_FIRST", "thresholdPercent": 65}
    assert result == {"defaults": default, "legacy": default,
                      "saved": selected, "reopened": selected}


@pytest.mark.parametrize("threshold", [0, 101, 80.5])
def test_cache_policy_rejects_invalid_percentages(threshold: float) -> None:
    result = _run(
        f"""
        (() => {{
            const model = window.StowlingAutoClassifyTaskModel;
            const draft = {{...model.newDraft(),
                cachePolicy: {{mode: "PACK_FIRST", thresholdPercent: {threshold}}}}};
            return model.validateDraft(draft, {{}}, []);
        }})()
        """
    )
    assert "缓存容量阈值必须为 1 到 100 的整数百分比" in result


@pytest.mark.parametrize("enabled", [False, True])
def test_packing_switch_survives_save_without_losing_its_settings(enabled: bool) -> None:
    result = _run(
        f"""
        (() => {{
            const model = window.StowlingAutoClassifyTaskModel;
            const draft = {{...model.newDraft(), coordinateId: "pickup", cacheLocationRef: "region:cache",
                classificationAreas: [{{categoryZoneId: "zone"}}],
                cachePolicy: {{enabled: {json.dumps(enabled)},
                    mode: "CLASSIFY_FIRST", thresholdPercent: 65}}}};
            const resources = {{coordinates: [{{id: "pickup"}}], regions: [{{id: "cache"}}],
                categoryZones: [{{id: "zone"}}]}};
            return model.draftFromTask(model.payload(draft, resources, [])).cachePolicy;
        }})()
        """
    )
    assert result == {"enabled": enabled, "mode": "CLASSIFY_FIRST", "thresholdPercent": 65}


def test_legacy_packing_policy_is_disabled_but_keeps_its_selected_values() -> None:
    result = _run(
        "window.StowlingAutoClassifyTaskModel.draftFromTask({cachePolicy: "
        "{mode: 'CLASSIFY_FIRST', thresholdPercent: 65}}).cachePolicy"
    )
    assert result == {"enabled": False, "mode": "CLASSIFY_FIRST", "thresholdPercent": 65}


@pytest.mark.parametrize("enabled", [None, 0, "true"])
def test_packing_switch_rejects_non_boolean_values(enabled: Any) -> None:
    result = _run(
        f"""
        window.StowlingAutoClassifyTaskModel.validateDraft({{
            ...window.StowlingAutoClassifyTaskModel.newDraft(),
            cachePolicy: {{enabled: {json.dumps(enabled)}, mode: "PACK_FIRST", thresholdPercent: 80}}
        }}, {{}}, [])
        """
    )
    assert "打包缓存开关需为布尔值" in result


def test_independent_tasks_use_separate_settings_and_keep_the_selected_follow_up() -> None:
    output = _run("""
        (() => {
            const model = window.StowlingAutoClassifyTaskModel;
            const resources = {
                coordinates: [{id: "pickup", name: "拾取点"}],
                regions: [{id: "cache", name: "缓存"}],
                categoryZones: [{id: "sorting", name: "分类区"}],
            };
            const pickup = model.payload({
                ...model.newDraft("PICKUP"), coordinateId: "pickup",
                cacheLocationRef: "region:cache", followUpTaskRef: "task:chosen"
            }, resources);
            const sort = model.payload({
                ...model.newDraft("CLASSIFICATION"), cacheLocationRef: "region:cache",
                classificationAreas: [{categoryZoneId: "sorting"}]
            }, resources);
            return {pickup, sort, loaded: model.draftFromTask(pickup)};
        })()
    """)

    assert output["pickup"]["classificationAreas"] == []
    assert output["pickup"]["detection"]["coordinateRef"] == "coordinate:pickup"
    assert output["pickup"]["followUpTaskRef"] == "task:chosen"
    assert output["loaded"]["followUpTaskRef"] == "task:chosen"
    assert output["sort"]["kind"] == "CLASSIFICATION"
    assert "detection" not in output["sort"]
    assert "followUpTaskRef" not in output["sort"]
    assert output["sort"]["classificationAreas"][0]["categoryZoneRef"] == "category-zone:sorting"
