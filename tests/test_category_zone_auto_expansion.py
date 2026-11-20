import json
import shutil
import subprocess
from pathlib import Path
from typing import Any

import pytest


ROOT = Path(__file__).resolve().parents[1]
MODEL = ROOT / "src/main/resources/stowling-web/category-zone-model.js"
NODE = shutil.which("node")
pytestmark = pytest.mark.skipif(NODE is None, reason="Node.js is required")
RUNNER = r"""
const fs = require("node:fs");
const vm = require("node:vm");
globalThis.window = {};
vm.runInThisContext(fs.readFileSync(process.argv[1], "utf8"));
const model = window.StowlingCategoryZoneModel;
/**
 * @param {string} axis
 * @param {number} first
 * @param {number} second
 * @returns {object}
 */
function binding(axis, first, second) {
    const value = model.normalizeBinding({
        markerArea: {firstX: 0, secondX: 0, firstY: 64, secondY: 64,
            firstZ: 2, secondZ: 2},
        storageArea: {firstX: 0, secondX: 0, firstY: 64, secondY: 64,
            firstZ: 0, secondZ: 1}
    });
    if (axis === "Z") {
        for (const kind of ["marker", "storage"]) {
            for (const corner of ["First", "Second"]) {
                value[`${kind}${corner}X`] = value[`${kind}${corner}Z`];
            }
        }
    }
    for (const kind of ["marker", "storage"]) {
        value[`${kind}First${axis}`] = first;
        value[`${kind}Second${axis}`] = second;
    }
    return value;
}
/**
 * @param {string} axis
 * @param {number} first
 * @param {number} second
 * @returns {object}
 */
function area(axis, first, second) {
    const result = {areaFirstX: 0, areaSecondX: 2, areaFirstY: 64,
        areaSecondY: 70, areaFirstZ: 0, areaSecondZ: 2, direction: "AUTO", step: 80};
    result[`areaFirst${axis}`] = first;
    result[`areaSecond${axis}`] = second;
    return result;
}
process.stdout.write(JSON.stringify(eval(process.argv[2])));
"""


def run_model(expression: str) -> Any:
    if NODE is None:
        raise RuntimeError("Node.js is unavailable")
    result = subprocess.run(
        [NODE, "-e", RUNNER, str(MODEL), expression],
        check=True,
        capture_output=True,
        encoding="utf-8",
    )
    return json.loads(result.stdout)


def test_unstackable_frame_and_two_wide_storage_keep_separate_bounds() -> None:
    saved = run_model(
        """(() => {
            const sample = {...binding("X", 0, 0), ruleId: "UNSTACKABLE_ITEMS",
                storageSecondX: 1};
            return model.payload({name: "Frames", bindings: [sample],
                expansion: area("X", 0, 25)});
        })()"""
    )

    assert saved["expansion"]["step"] == 2
    assert saved["expansion"]["direction"] == "X_POSITIVE"
    template = saved["bindings"][0]
    assert (template["markerFirstZ"], template["markerSecondX"]) == (2, 0)
    assert (template["storageFirstZ"], template["storageSecondX"]) == (0, 1)


@pytest.mark.parametrize("axis", ["X", "Z"])
@pytest.mark.parametrize("reverse", [False, True])
def test_whole_storage_keeps_two_wide_slice_and_offsets_its_helpers(
    axis: str, reverse: bool
) -> None:
    result = run_model(
        f"""(() => {{
            const axis = {json.dumps(axis)};
            const start = {17 if reverse else 13};
            const template = {{...binding(axis, start, start),
                ruleId: 'MARKER_CONTAINER_CONTENTS', wholeStorage: true,
                standingArea: {{firstX: 20, secondX: 21, firstY: 65, secondY: 65,
                    firstZ: 20, secondZ: 21}},
                storagePolicy: {{packingArea: {{firstX: 22, secondX: 23,
                    firstY: 65, secondY: 65, firstZ: 22, secondZ: 23}}}},
                containerOverrides: [{{storageX: 20, storageY: 64, storageZ: 20,
                    mode: 'ADD', itemIds: ['minecraft:stone']}}]}};
            template[`storageSecond${{axis}}`] = start + 1;
            const other = axis === 'X' ? 'Z' : 'X';
            template[`markerFirst${{other}}`] = 0;
            template[`markerSecond${{other}}`] = 1;
            template[`storageFirst${{other}}`] = 0;
            template[`storageSecond${{other}}`] = 0;
            const before = JSON.stringify(template);
            const expansion = area(axis, {18 if reverse else 13}, {13 if reverse else 18});
            const result = model.inferExpansion([template], expansion);
            return {{...result, unchanged: before === JSON.stringify(template)}};
        }})()"""
    )

    assert result["count"] == 3
    assert result["collapsed"] is False
    assert result["unchanged"] is True
    assert result["expansion"]["step"] == 2
    assert result["expansion"]["direction"] == f"{axis}_{'NEGATIVE' if reverse else 'POSITIVE'}"
    saved = result["bindings"][0]
    assert saved["wholeStorage"] is True
    assert saved[f"storageSecond{axis}"] - saved[f"storageFirst{axis}"] == 1
    assert saved[f"markerFirst{axis}"] == (17 if reverse else 13)
    assert saved["standingArea"][f"first{axis}"] == 20
    assert saved["storagePolicy"]["packingArea"][f"first{axis}"] == 22


def test_whole_storage_payload_and_legacy_default_round_trip() -> None:
    result = run_model(
        """(() => {
            const legacy = model.normalizeBinding({});
            const template = {...binding('X', 20, 20), wholeStorage: true,
                ruleId: 'MARKER_CONTAINER_CONTENTS', storageSecondX: 21,
                standingArea: {firstX: 19, secondX: 20, firstY: 65, secondY: 65,
                    firstZ: -1, secondZ: -1},
                storagePolicy: {packingArea: {firstX: 22, secondX: 22,
                    firstY: 65, secondY: 65, firstZ: -1, secondZ: -1}},
                containerOverrides: [{storageX: 21, storageY: 64, storageZ: 0,
                    mode: 'ADD', itemIds: ['minecraft:stone']}]};
            return {legacy: legacy.wholeStorage,
                saved: model.payload({name: 'Wide', bindings: [template],
                    expansion: area('X', 13, 92)})};
        })()"""
    )

    assert result["legacy"] is False
    saved = result["saved"]["bindings"][0]
    assert saved["wholeStorage"] is True
    assert saved["storageSecondX"] == 14
    assert saved["standingArea"]["firstX"] == 12
    assert saved["storagePolicy"]["packingArea"]["firstX"] == 15
    assert saved["containerOverrides"][0]["storageX"] == 14


@pytest.mark.parametrize("axis", ["X", "Z"])
@pytest.mark.parametrize("reverse", [False, True])
def test_whole_row_becomes_eighty_single_slices(axis: str, reverse: bool) -> None:
    first, second = (92, 13) if reverse else (13, 92)
    result = run_model(
        f"""(() => {{
            const templates = Array.from({{length: 5}}, (_, index) => ({{
                ...binding({json.dumps(axis)}, 13, 92),
                markerFirstY: 64 + index,
                markerSecondY: 64 + index
            }}));
            const before = JSON.stringify(templates);
            const inferred = model.inferExpansion(
                templates, area({json.dumps(axis)}, {first}, {second})
            );
            return {{...inferred, unchanged: JSON.stringify(templates) === before}};
        }})()"""
    )

    assert result["count"] == 80
    assert result["collapsed"] is True
    assert result["unchanged"] is True
    direction = f"{axis}_{'NEGATIVE' if reverse else 'POSITIVE'}"
    assert result["expansion"]["direction"] == direction
    assert result["expansion"]["step"] == 1
    assert result["expansion"]["templateCount"] == 5
    for index, template in enumerate(result["bindings"]):
        assert template[f"markerFirst{axis}"] == first
        assert template[f"markerSecond{axis}"] == first
        assert template[f"storageFirst{axis}"] == first
        assert template["markerFirstY"] == 64 + index


@pytest.mark.parametrize("coordinate,direction", [(13, "X_POSITIVE"), (92, "X_NEGATIVE")])
def test_thin_template_expands_inward_regardless_of_corner_order(
    coordinate: int, direction: str
) -> None:
    result = run_model(
        f"model.inferExpansion([binding('X', {coordinate}, {coordinate})], area('X', 92, 13))"
    )

    assert result["count"] == 80
    assert result["collapsed"] is False
    assert result["expansion"]["direction"] == direction


def test_auto_payload_sends_a_concrete_direction_and_compressed_template() -> None:
    result = run_model(
        """model.payload({name: ' Wall ', bindings: [binding('X', 13, 92)],
            expansion: area('X', 13, 92)})"""
    )

    assert result["name"] == "Wall"
    assert result["expansion"]["direction"] == "X_POSITIVE"
    assert result["expansion"]["step"] == 1
    assert result["bindings"][0]["markerSecondX"] == 13
    assert "AUTO" not in json.dumps(result)


def test_manual_payload_preserves_custom_step_and_wide_template() -> None:
    result = run_model(
        """model.payload({name: 'Wall', bindings: [binding('X', 13, 92)],
            expansion: {...area('X', 13, 92), direction: 'X_NEGATIVE', step: 80}})"""
    )

    assert result["expansion"]["step"] == 80
    assert result["expansion"]["direction"] == "X_NEGATIVE"
    assert result["bindings"][0]["markerSecondX"] == 92


@pytest.mark.parametrize("reverse", [False, True])
def test_whole_row_standing_strip_becomes_a_relative_single_slice(reverse: bool) -> None:
    first, second = (92, 13) if reverse else (13, 92)
    result = run_model(
        f"""model.payload({{name: 'Steps', bindings: [{{...binding('X', 13, 92),
            standingArea: {{firstX: 13, secondX: 92, firstY: 65, secondY: 65,
                firstZ: -1, secondZ: -1}}
        }}], expansion: area('X', {first}, {second})}})"""
    )

    standing = result["bindings"][0]["standingArea"]
    assert standing["firstX"] == first
    assert standing["secondX"] == first
    assert standing["firstY"] == 65
    assert standing["firstZ"] == -1


def test_single_slice_standing_area_moves_with_the_selected_mother_slice() -> None:
    result = run_model(
        """model.inferExpansion([{...binding('X', 20, 20), standingArea: {
            firstX: 20, secondX: 21, firstY: 65, secondY: 65, firstZ: -1, secondZ: 0
        }}], area('X', 13, 92))"""
    )

    assert result["bindings"][0]["standingArea"] == {
        "firstX": 13, "secondX": 14,
        "firstY": 65, "secondY": 65,
        "firstZ": -1, "secondZ": 0,
    }


def test_manual_standing_area_round_trip_and_disable() -> None:
    result = run_model(
        """(() => {
            const bounds = {firstX: 1, secondX: 2, firstY: 65, secondY: 65,
                firstZ: -1, secondZ: -1};
            const template = {...binding('X', 0, 0), standingArea: bounds};
            const saved = model.payload({name: 'Steps', bindings: [template],
                expansion: {...area('X', 0, 10), direction: 'X_POSITIVE', step: 2}});
            const restored = model.normalizeBinding({standingArea: bounds});
            const disabled = model.payload({name: 'Steps', bindings: [{...template,
                standingArea: null}]});
            return {saved: saved.bindings[0].standingArea, restored: restored.standingArea,
                disabled: disabled.bindings[0].standingArea};
        })()"""
    )

    assert result["saved"] == result["restored"]
    assert result["disabled"] is None


def test_center_template_and_override_move_together_to_the_starting_edge() -> None:
    result = run_model(
        """model.inferExpansion([{...binding('X', 20, 20), containerOverrides: [{
            storageX: 20, storageY: 64, storageZ: 0, mode: 'ADD',
            itemIds: ['minecraft:stone']
        }]}], area('X', 13, 92))"""
    )

    assert result["bindings"][0]["markerFirstX"] == 13
    assert result["bindings"][0]["containerOverrides"][0]["storageX"] == 13
    assert result["bindings"][0]["containerOverrides"][0]["itemIds"] == ["minecraft:stone"]


@pytest.mark.parametrize(
    "expression,message",
    [
        ("model.inferExpansion([], area('X', 0, 10))", "母版"),
        ("model.inferExpansion([binding('X', 0, 0)], area('X', 0, 256))", "256"),
        ("model.inferExpansion([binding('X', 12, 92)], area('X', 13, 92))", "总选区"),
        (
            "model.inferExpansion([binding('X', 0, 0)], {...area('X', 0, 10),"
            " areaSecondX: 1.5})",
            "整数坐标",
        ),
        (
            "model.inferExpansion([{...binding('X', 0, 2), storageSecondX: 1}],"
            " area('X', 0, 10))",
            "手动选择",
        ),
        (
            "model.inferExpansion([{...binding('X', 0, 2), containerOverrides: [{}]}],"
            " area('X', 0, 10))",
            "容器例外",
        ),
        (
            "model.inferExpansion([{...binding('X', 0, 0), markerFirstZ: 0,"
            " markerSecondZ: 0, storageFirstZ: 0, storageSecondZ: 0}],"
            " {...area('X', 0, 10), areaSecondZ: 10})",
            "手动选择",
        ),
    ],
)
def test_invalid_auto_expansion_is_rejected(expression: str, message: str) -> None:
    result = run_model(
        f"""(() => {{try {{ {expression}; return ''; }}
            catch (error) {{return error.message;}}}})()"""
    )

    assert message in result
