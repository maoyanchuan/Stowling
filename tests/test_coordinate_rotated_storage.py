import json

import pytest

from test_coordinate_editor import NODE, run_model


pytestmark = pytest.mark.skipif(NODE is None, reason="Node.js is required")


@pytest.mark.parametrize("axis", ["X", "Z"])
@pytest.mark.parametrize("reverse", [False, True])
@pytest.mark.parametrize("independent", [False, True])
def test_rotated_marker_chest_keeps_two_wide_storage_through_save_and_copy(
    axis: str, reverse: bool, independent: bool
) -> None:
    result = run_model("""
        const axis = """ + json.dumps(axis) + """;
        const reverse = """ + json.dumps(reverse) + """;
        const independent = """ + json.dumps(independent) + """;
        const marker = {firstX: 11, firstY: -40, firstZ: 46,
            secondX: 10, secondY: -40, secondZ: 46};
        const storage = {firstX: 10, firstY: -38, firstZ: 46,
            secondX: 10, secondY: -36, secondZ: 47};
        const total = {firstX: 10, firstY: -40, firstZ: 57,
            secondX: 11, secondY: -36, secondZ: 46};
        if (reverse) {
            marker.firstZ = marker.secondZ = 57;
            storage.firstZ = 57;
            storage.secondZ = 56;
        }
        if (axis === 'X') {
            for (const bounds of [marker, storage, total]) {
                for (const corner of ['first', 'second']) {
                    [bounds[corner + 'X'], bounds[corner + 'Z']] =
                        [bounds[corner + 'Z'], bounds[corner + 'X']];
                }
            }
        }
        const global = {scopeMode: 'single', recognitionMode: 'container-item',
            storageMethod: 'bulk', expansionDirection: 'AUTO'};
        const binding = {name: '尾流MIS', markerArea: marker, storageArea: storage,
            ruleId: 'MARKER_CONTAINER_CONTENTS', wholeStorage: false,
            optionsEnabled: independent, categoryOptions: independent ? global : null};
        const raw = {id: 'rotated', name: '尾流MIS', overallArea: total,
            categoryOptions: independent ? {...global, recognitionMode: 'marker'} : global,
            bindings: [binding]};
        const value = model.resources({categoryZones: [raw]})[0];
        const before = JSON.stringify(value);
        const saved = model.payload(value);
        const copied = model.payload(model.copy(value));
        const row = saved.bindings[0];
        /** @param {string} kind @returns {object} */
        const bounds = kind => Object.fromEntries(['first', 'second'].flatMap(corner =>
            ['X', 'Y', 'Z'].map(axis => [corner + axis,
                row[kind + corner[0].toUpperCase() + corner.slice(1) + axis]])));
        const restored = model.resources({categoryZones: [{...raw,
            categoryOptions: saved.categoryOptions,
            expansion: {area: saved.overallArea, direction: saved.expansion.direction,
                step: saved.expansion.step, templateCount: 1},
            bindings: [{...row, markerArea: bounds('marker'),
                storageArea: bounds('storage')}]}]})[0];
        const reloaded = model.payload(restored);
        const fresh = model.copy(value);
        delete fresh.bindings[0].original;
        process.stdout.write(JSON.stringify({saved, copied, reloaded,
            fresh: model.payload(fresh), unchanged: before === JSON.stringify(value)}));
    """)

    assert result["unchanged"] is True
    expected_marker = 57 if reverse else 46
    expected_storage = 56 if reverse else 47
    for key in ("saved", "copied", "reloaded", "fresh"):
        saved = result[key]
        assert saved["expansion"]["direction"] == (
            f"{axis}_{'NEGATIVE' if reverse else 'POSITIVE'}"
        )
        assert saved["expansion"]["step"] == 2
        assert saved["overallArea"][f"first{axis}"] == 57
        assert saved["overallArea"][f"second{axis}"] == 46
        row = saved["bindings"][0]
        assert row["wholeStorage"] is True
        assert row["optionsEnabled"] is independent
        assert row["name"] == "尾流MIS"
        assert row[f"markerFirst{axis}"] == expected_marker
        assert row[f"markerSecond{axis}"] == expected_marker
        assert row[f"storageFirst{axis}"] == expected_marker
        assert row[f"storageSecond{axis}"] == expected_storage
        assert row["markerFirstY"] == -40
        assert row["storageFirstY"] == -38
        assert row["storageSecondY"] == -36
        other = "Z" if axis == "X" else "X"
        assert (row[f"markerFirst{other}"], row[f"markerSecond{other}"]) == (11, 10)
        assert (row[f"storageFirst{other}"], row[f"storageSecond{other}"]) == (10, 10)


def test_container_row_keeps_column_pairing_and_single_block_expansion() -> None:
    result = run_model("""
        const marker = {firstX: 13, firstY: 64, firstZ: 2,
            secondX: 92, secondY: 64, secondZ: 2};
        const storage = {...marker, firstZ: 0, secondZ: 1};
        const raw = {id: 'row', name: 'Row',
            overallArea: {...marker, firstZ: 0, secondZ: 2},
            categoryOptions: {scopeMode: 'single', recognitionMode: 'container-item',
                storageMethod: 'bulk', expansionDirection: 'AUTO'},
            bindings: [{markerArea: marker, storageArea: storage,
                ruleId: 'MARKER_CONTAINER_CONTENTS', wholeStorage: false}]};
        process.stdout.write(JSON.stringify(model.payload(
            model.resources({categoryZones: [raw]})[0])));
    """)

    assert result["expansion"]["direction"] == "X_POSITIVE"
    assert result["expansion"]["step"] == 1
    assert result["bindings"][0]["wholeStorage"] is False
    assert result["bindings"][0]["markerSecondX"] == 13
    assert result["bindings"][0]["storageSecondX"] == 13


def test_selection_keeps_existing_column_pairing_for_rotated_bounds() -> None:
    result = run_model("""
        const raw = {id: 'selection', name: 'Selection',
            categoryOptions: {scopeMode: 'selection', recognitionMode: 'container-item',
                storageMethod: 'bulk'},
            bindings: [{ruleId: 'MARKER_CONTAINER_CONTENTS', wholeStorage: false,
                markerArea: {firstX: 11, firstY: -40, firstZ: 46,
                    secondX: 10, secondY: -40, secondZ: 46},
                storageArea: {firstX: 10, firstY: -38, firstZ: 46,
                    secondX: 10, secondY: -36, secondZ: 47}}]};
        process.stdout.write(JSON.stringify(model.payload(
            model.resources({categoryZones: [raw]})[0])));
    """)

    assert "expansion" not in result
    assert result["bindings"][0]["wholeStorage"] is False
    assert result["bindings"][0]["storageSecondZ"] == 47
