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
require(process.argv[1]);
const model = globalThis.StowlingWarehouseModel;
const first = {id: 'chest', dimension: 'minecraft:overworld',
    positions: [{x: 10,y: 64,z: 10}, {x: 11,y: 64,z: 10}],
    items: [{key: 'stone', itemId: 'minecraft:stone', name: '石头', loose: 64, boxed: 1728},
        {key: 'stone#named', itemId: 'minecraft:stone', name: '自定义石头', loose: 1, boxed: 0}]};
const second = {id: 'barrel', dimension: 'minecraft:overworld',
    positions: [{x: 30,y: 64,z: 10}],
    items: [{key: 'stone', itemId: 'minecraft:stone', name: '石头', loose: 32, boxed: 0}]};
const nether = {...first, id: 'nether', dimension: 'minecraft:the_nether'};
const snapshot = {containers: [first, first, second, nether], areas: [{id: 'region',
    dimension: 'minecraft:overworld', bounds: [{firstX: 12, firstY: 65, firstZ: 11,
        secondX: 11, secondY: 63, secondZ: 9}]}]};
process.stdout.write(JSON.stringify(eval(process.argv[2])));
"""


def evaluate(expression: str) -> Any:
    result = subprocess.run(
        [str(NODE), "-e", RUNNER, str(WEB / "warehouse-model.js"), expression],
        check=True, capture_output=True, encoding="utf-8",
    )
    return json.loads(result.stdout)


def test_default_is_entire_bank_and_container_duplicates_are_counted_once() -> None:
    assert evaluate("model.containers(snapshot).map(value => value.id)") == [
        "chest", "barrel", "nether",
    ]


def test_region_uses_linked_chest_half_inclusive_bounds_and_current_dimension() -> None:
    assert evaluate("model.containers(snapshot, {areaId: 'region'}).map(value => value.id)") == [
        "chest",
    ]


def test_counts_combine_loose_and_box_contents_without_merging_different_components() -> None:
    rows = evaluate("model.aggregate(model.containers(snapshot, {dimension: 'minecraft:overworld'}))")
    assert len(rows) == 2
    assert rows[0]["loose"] == 96
    assert rows[0]["boxed"] == 1728
    assert rows[0]["total"] == 1824
    assert len(rows[0]["locations"]) == 2
    assert rows[1]["total"] == 1


def test_item_search_supports_localized_name_and_registry_identifier() -> None:
    assert len(evaluate("model.aggregate([first], '自定义')")) == 1
    assert len(evaluate("model.aggregate([first], 'minecraft:stone')")) == 2
