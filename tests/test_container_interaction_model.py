import json
import shutil
import subprocess
from pathlib import Path
from typing import Any

import pytest


ROOT = Path(__file__).resolve().parents[1]
MODEL = ROOT / "src" / "main" / "resources" / "stowling-web" / "v2-task-model.js"
NODE = shutil.which("node")
DESCRIPTOR_KEYS = {
    "key",
    "label",
    "kind",
    "required",
    "options",
    "min",
    "max",
    "step",
    "defaultValue",
}
pytestmark = pytest.mark.skipif(
    NODE is None,
    reason="Node.js is required to exercise the JavaScript task model",
)

NODE_RUNNER = """
const fs = require("node:fs");
const vm = require("node:vm");
const source = fs.readFileSync(process.argv[1], "utf8");
globalThis.window = {
    Stowling: {
        clone(value) {
            return JSON.parse(JSON.stringify(value));
        },
    },
};
vm.runInThisContext(source, { filename: process.argv[1] });
const result = vm.runInThisContext(`(${process.argv[2]})`);
process.stdout.write(JSON.stringify(result));
"""


def _run_model(expression: str) -> Any:
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


def _by_key(items: list[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    return {item["key"]: item for item in items}


def test_interaction_parameters_are_operation_specific() -> None:
    result = _run_model(
        """
        (() => {
            const model = window.StowlingTaskModel;
            return {
                operations: model.INTERACTION_OPERATIONS,
                parameters: Object.fromEntries(
                    model.INTERACTION_OPERATIONS.map((operation) => [
                        operation,
                        model.interactionParameters(operation),
                    ])
                ),
                unknown: model.interactionParameters("UNKNOWN"),
            };
        })()
        """
    )

    assert result["operations"] == [
        "COLLECT",
        "FIND",
        "READ",
        "WITHDRAW",
        "DEPOSIT",
    ]
    assert result["unknown"] == []

    params = result["parameters"]
    for operation in result["operations"]:
        descriptors = params[operation]
        assert "operation" not in {item["key"] for item in descriptors}
        assert all(set(item) == DESCRIPTOR_KEYS for item in descriptors)

    collect = _by_key(params["COLLECT"])
    assert list(collect) == ["areaRef", "itemFilter", "timeoutSeconds"]
    assert collect["areaRef"]["kind"] == "area-reference"
    assert collect["areaRef"]["required"] is True
    assert collect["areaRef"]["defaultValue"] == "detector"
    assert collect["itemFilter"]["required"] is False
    assert collect["itemFilter"]["defaultValue"] is None
    assert collect["timeoutSeconds"]["min"] == 0
    assert collect["timeoutSeconds"]["max"] == 86_400
    assert collect["timeoutSeconds"]["defaultValue"] == "120"

    find = _by_key(params["FIND"])
    assert list(find) == ["targetRef", "containerTypes", "maxVolume"]
    assert find["targetRef"]["kind"] == "region-category-zone-reference"
    assert find["maxVolume"]["min"] == 1
    assert find["maxVolume"]["max"] == 1_048_576

    read = _by_key(params["READ"])
    assert list(read) == ["targetRef"]
    assert read["targetRef"]["kind"] == "coordinate-output-reference"
    assert read["targetRef"]["required"] is True

    withdraw = _by_key(params["WITHDRAW"])
    assert list(withdraw) == ["targetRef", "itemSource"]
    assert withdraw["itemSource"]["options"] == ["classified"]
    assert withdraw["itemSource"]["defaultValue"] == "classified"
    assert "itemFilter" not in withdraw

    deposit = _by_key(params["DEPOSIT"])
    assert list(deposit) == ["targetRef", "itemSource"]
    assert deposit["targetRef"]["kind"] == "deposit-target-reference"
    assert deposit["itemSource"]["options"] == [
        "collected",
        "carried",
        "managed",
    ]
    assert deposit["itemSource"]["defaultValue"] == "collected"


def test_interaction_step_round_trip_and_legacy_operations() -> None:
    result = _run_model(
        """
        (() => {
            const model = window.StowlingTaskModel;
            const step = model.interactionStep("DEPOSIT", {
                targetRef: "region:cache",
                itemSource: "collected",
            });
            const legacyModules = [
                "COLLECT_DROPPED_ITEMS",
                "SCAN_CONTAINERS_IN_REGION",
                "READ_CONTAINER",
                "WITHDRAW_ITEMS",
                "DEPOSIT_ITEMS_TO_CONTAINERS",
            ];
            let invalidOperation = "";
            try {
                model.interactionStep("UNKNOWN");
            } catch (error) {
                invalidOperation = error.name;
            }
            return {
                step,
                operation: model.interactionOperation(step),
                isDeposit: model.isInteractionStep(step, "DEPOSIT"),
                isRead: model.isInteractionStep(step, "READ"),
                label: model.interactionOperationLabel("DEPOSIT"),
                legacy: legacyModules.map((moduleId) => ({
                    moduleId,
                    operation: model.interactionOperation(
                        model.actionStep(moduleId, {})
                    ),
                    isInteraction: model.isInteractionStep(
                        model.actionStep(moduleId, {})
                    ),
                })),
                invalidOperation,
                moduleIds: model.ACTION_MODULE_IDS,
            };
        })()
        """
    )

    assert result["step"] == {
        "kind": "ACTION",
        "moduleId": "CONTAINER_INTERACTION",
        "parameters": {
            "targetRef": "region:cache",
            "itemSource": "collected",
            "operation": "DEPOSIT",
        },
        "steps": [],
        "otherwise": [],
    }
    assert result["operation"] == "DEPOSIT"
    assert result["isDeposit"] is True
    assert result["isRead"] is False
    assert result["label"] == "存入物品"
    assert result["invalidOperation"] == "RangeError"
    assert result["moduleIds"][0] == "CONTAINER_INTERACTION"
    assert [item["operation"] for item in result["legacy"]] == [
        "COLLECT",
        "FIND",
        "READ",
        "WITHDRAW",
        "DEPOSIT",
    ]
    assert all(item["isInteraction"] is False for item in result["legacy"])


def test_interaction_validation_is_operation_specific_and_resource_free() -> None:
    result = _run_model(
        """
        (() => {
            const model = window.StowlingTaskModel;
            const valid = [
                model.interactionStep("COLLECT", {
                    areaRef: "detector",
                    itemFilter: "minecraft:dirt",
                    timeoutSeconds: "120",
                }),
                model.interactionStep("COLLECT", {
                    areaRef: "coordinate:missing-coordinate",
                }),
                model.interactionStep("FIND", {
                    targetRef: "region:missing-region",
                }),
                model.interactionStep("FIND", {
                    targetRef: "category-zone:missing-zone",
                }),
                model.interactionStep("READ", {
                    targetRef: "coordinate:missing-reader",
                }),
                model.interactionStep("READ", {
                    targetRef: "output:marker",
                }),
                model.interactionStep("WITHDRAW", {
                    targetRef: "output:container",
                    itemSource: "classified",
                }),
                model.interactionStep("DEPOSIT", {
                    targetRef: "category-zone:missing-zone",
                    itemSource: "managed",
                }),
            ];
            const invalid = {
                emptyArea: model.validateInteractionStep(
                    model.interactionStep("COLLECT", { areaRef: "" })
                ),
                collectTarget: model.validateInteractionStep(
                    model.interactionStep("COLLECT", {
                        areaRef: "detector",
                        targetRef: "coordinate:unrelated",
                    })
                ),
                findCoordinate: model.validateInteractionStep(
                    model.interactionStep("FIND", {
                        targetRef: "coordinate:not-allowed",
                    })
                ),
                readRegion: model.validateInteractionStep(
                    model.interactionStep("READ", {
                        targetRef: "region:not-allowed",
                    })
                ),
                emptyItemSource: model.validateInteractionStep(
                    model.interactionStep("DEPOSIT", {
                        targetRef: "region:cache",
                        itemSource: "",
                    })
                ),
                wrongItemSource: model.validateInteractionStep(
                    model.interactionStep("DEPOSIT", {
                        targetRef: "region:cache",
                        itemSource: "classified",
                    })
                ),
                timeout: model.validateInteractionStep(
                    model.interactionStep("COLLECT", {
                        areaRef: "detector",
                        timeoutSeconds: "86401",
                    })
                ),
                nested: model.validateInteractionStep({
                    ...model.interactionStep("READ", {
                        targetRef: "output:marker",
                    }),
                    steps: [model.interactionStep("READ", {
                        targetRef: "output:marker",
                    })],
                }),
            };
            return {
                valid: valid.map((step) => model.validateInteractionStep(step)),
                invalid,
            };
        })()
        """
    )

    assert result["valid"] == [[] for _ in range(8)]
    for errors in result["invalid"].values():
        assert errors
    assert "必须填写" in result["invalid"]["emptyArea"][0]
    assert "不支持参数" in result["invalid"]["collectTarget"][0]
    assert "必须是" in result["invalid"]["findCoordinate"][0]
    assert "必须是" in result["invalid"]["readRegion"][0]
    assert "不能为空" in result["invalid"]["emptyItemSource"][0]
    assert "不是有效选项" in result["invalid"]["wrongItemSource"][0]
    assert "超出允许范围" in result["invalid"]["timeout"][0]
    assert "不能包含子步骤" in result["invalid"]["nested"][0]


@pytest.mark.parametrize(
    ("operation", "key", "parameters"),
    [
        ("COLLECT", "timeoutSeconds", {"areaRef": "detector"}),
        ("FIND", "maxVolume", {"targetRef": "region:missing-region"}),
    ],
)
@pytest.mark.parametrize("value", ["1e2", "0x10", "1.0"])
def test_interaction_integer_fields_reject_non_decimal_syntax(
    operation: str,
    key: str,
    parameters: dict[str, str],
    value: str,
) -> None:
    step_parameters = {**parameters, key: value}
    result = _run_model(
        f"""
        window.StowlingTaskModel.validateInteractionStep(
            window.StowlingTaskModel.interactionStep(
                {json.dumps(operation)},
                {json.dumps(step_parameters)}
            )
        )
        """
    )

    assert len(result) == 1
    assert "必须是整数" in result[0]


@pytest.mark.parametrize(
    ("operation", "key", "parameters"),
    [
        ("COLLECT", "timeoutSeconds", {"areaRef": "detector"}),
        ("FIND", "maxVolume", {"targetRef": "region:missing-region"}),
    ],
)
@pytest.mark.parametrize("value", ["001", "+100", " 100 "])
def test_interaction_integer_fields_accept_decimal_syntax(
    operation: str,
    key: str,
    parameters: dict[str, str],
    value: str,
) -> None:
    step_parameters = {**parameters, key: value}
    result = _run_model(
        f"""
        window.StowlingTaskModel.validateInteractionStep(
            window.StowlingTaskModel.interactionStep(
                {json.dumps(operation)},
                {json.dumps(step_parameters)}
            )
        )
        """
    )

    assert result == []


def test_interaction_validation_requires_uppercase_raw_operations() -> None:
    result = _run_model(
        """
        (() => {
            const model = window.StowlingTaskModel;
            return model.INTERACTION_OPERATIONS.map((operation) => {
                const parameters = operation === "COLLECT"
                    ? { areaRef: "detector" }
                    : { targetRef: operation === "FIND"
                        ? "region:missing-region"
                        : "output:container" };
                const constructed = model.interactionStep(
                    operation.toLowerCase(),
                    parameters
                );
                return {
                    operation,
                    constructedOperation: constructed.parameters.operation,
                    constructedErrors: model.validateInteractionStep(constructed),
                    uppercaseErrors: model.validateInteractionStep(
                        model.actionStep("CONTAINER_INTERACTION", {
                            ...parameters,
                            operation,
                        })
                    ),
                    lowercaseErrors: model.validateInteractionStep(
                        model.actionStep("CONTAINER_INTERACTION", {
                            ...parameters,
                            operation: operation.toLowerCase(),
                        })
                    ),
                    mixedCaseErrors: model.validateInteractionStep(
                        model.actionStep("CONTAINER_INTERACTION", {
                            ...parameters,
                            operation: operation[0] + operation.slice(1).toLowerCase(),
                        })
                    ),
                };
            });
        })()
        """
    )

    for entry in result:
        assert entry["constructedOperation"] == entry["operation"]
        assert entry["constructedErrors"] == []
        assert entry["uppercaseErrors"] == []
        assert entry["lowercaseErrors"] == ["容器交互操作无效"]
        assert entry["mixedCaseErrors"] == ["容器交互操作无效"]


def test_collect_missing_area_defaults_to_detector_without_mutating_step() -> None:
    result = _run_model(
        """
        (() => {
            const model = window.StowlingTaskModel;
            const omitted = model.interactionStep("COLLECT");
            return {
                omittedErrors: model.validateInteractionStep(omitted),
                parameters: omitted.parameters,
                payloadParameters: model.payloadTask({ steps: [omitted] })
                    .steps[0].parameters,
                emptyErrors: model.validateInteractionStep(
                    model.interactionStep("COLLECT", { areaRef: "" })
                ),
                blankErrors: model.validateInteractionStep(
                    model.interactionStep("COLLECT", { areaRef: "   " })
                ),
            };
        })()
        """
    )

    assert result["omittedErrors"] == []
    assert result["parameters"] == {"operation": "COLLECT"}
    assert result["payloadParameters"] == {"operation": "COLLECT"}
    assert "必须填写" in result["emptyErrors"][0]
    assert "必须填写" in result["blankErrors"][0]


@pytest.mark.parametrize("container_types", ["minecraft:chest", "", "   "])
def test_find_category_zone_rejects_container_types_even_when_empty(
    container_types: str,
) -> None:
    result = _run_model(
        """
        (() => {
            const model = window.StowlingTaskModel;
            const containerTypes = TEST_CONTAINER_TYPES;
            return {
                zoneErrors: model.validateInteractionStep(
                    model.interactionStep("FIND", {
                        targetRef: "category-zone:missing-zone",
                        containerTypes,
                    })
                ),
                regionErrors: model.validateInteractionStep(
                    model.interactionStep("FIND", {
                        targetRef: "region:missing-region",
                        containerTypes,
                    })
                ),
                omittedErrors: model.validateInteractionStep(
                    model.interactionStep("FIND", {
                        targetRef: "category-zone:missing-zone",
                        maxVolume: "+100",
                    })
                ),
            };
        })()
        """.replace("TEST_CONTAINER_TYPES", json.dumps(container_types))
    )

    assert len(result["zoneErrors"]) == 1
    assert "category-zone" in result["zoneErrors"][0]
    assert "containerTypes" in result["zoneErrors"][0]
    assert result["regionErrors"] == []
    assert result["omittedErrors"] == []


@pytest.mark.parametrize("operation", ["READ", "WITHDRAW"])
def test_container_read_operations_restrict_outputs_but_deposit_keeps_all(
    operation: str,
) -> None:
    result = _run_model(
        """
        (() => {
            const model = window.StowlingTaskModel;
            const operation = TEST_OPERATION;
            return model.OUTPUT_REFERENCES.map((targetRef) => ({
                targetRef,
                errors: model.validateInteractionStep(
                    model.interactionStep(operation, { targetRef })
                ),
                depositErrors: model.validateInteractionStep(
                    model.interactionStep("DEPOSIT", { targetRef })
                ),
            }));
        })()
        """.replace("TEST_OPERATION", json.dumps(operation))
    )

    assert {entry["targetRef"] for entry in result} == {
        "output:container",
        "output:marker",
        "output:storage",
    }
    for entry in result:
        assert entry["depositErrors"] == []
        if entry["targetRef"] == "output:storage":
            assert len(entry["errors"]) == 1
            assert "output:container 或 output:marker" in entry["errors"][0]
        else:
            assert entry["errors"] == []


def test_compact_templates_and_legacy_deposit_helper_are_compatible() -> None:
    result = _run_model(
        """
        (() => {
            const model = window.StowlingTaskModel;
            const interactionModule = {
                id: "CONTAINER_INTERACTION",
                kind: "ACTION",
                level: "BASIC",
            };
            const oldBasicModule = {
                id: "MOVE_TO_COORDINATE",
                kind: "ACTION",
                level: "BASIC",
            };
            const collect = model.collectTask([]);
            const interruption = model.interruptionTask([]);
            const oldHelper = model.depositToRegionSteps(
                "region:cache",
                "managed"
            );
            let invalidSource = "";
            try {
                model.depositToRegionSteps("region:cache", "unknown");
            } catch (error) {
                invalidSource = error.name;
            }
            return {
                collect,
                collectPayload: model.payloadTask(collect),
                interruption,
                defaultModuleId: model.defaultModule([
                    oldBasicModule,
                    interactionModule,
                ]).id,
                newStep: model.newStep(interactionModule),
                oldHelper,
                invalidSource,
            };
        })()
        """
    )

    collect = result["collect"]
    assert collect["name"] == "拾取并存入"
    assert len(collect["steps"]) == 3
    assert collect["steps"][0]["parameters"] == {
        "areaRef": "detector",
        "operation": "COLLECT",
    }
    assert collect["steps"][1]["parameters"] == {
        "targetRef": "",
        "itemSource": "collected",
        "operation": "DEPOSIT",
    }
    assert collect["steps"][2]["moduleId"] == (
        "ASSERT_PLAYER_INVENTORY_RESTORED"
    )
    assert len(result["collectPayload"]["steps"]) == 3

    interruption = result["interruption"]
    assert len(interruption["steps"]) == 1
    assert interruption["steps"][0]["parameters"] == {
        "targetRef": "",
        "itemSource": "managed",
        "operation": "DEPOSIT",
    }

    assert result["defaultModuleId"] == "CONTAINER_INTERACTION"
    assert result["newStep"]["parameters"] == {
        "areaRef": "detector",
        "operation": "COLLECT",
    }
    assert len(result["oldHelper"]) == 3
    assert result["oldHelper"][0]["moduleId"] == "SCAN_CONTAINERS_IN_REGION"
    assert result["oldHelper"][1]["kind"] == "REPEAT"
    assert result["oldHelper"][2]["moduleId"] == "ASSERT_NO_MANAGED_ITEMS"
    assert result["invalidSource"] == "RangeError"
