import json
import shutil
import subprocess
from pathlib import Path
from typing import Any

import pytest


WEB = Path(__file__).resolve().parents[1] / "src/main/resources/stowling-web"
NODE = shutil.which("node")


def run_model(expression: str) -> Any:
    if NODE is None:
        pytest.skip("Node.js is required")
    script = r"""
const fs = require('node:fs');
const vm = require('node:vm');
globalThis.window = {};
vm.runInThisContext(fs.readFileSync(process.argv[1], 'utf8'));
const model = window.StowlingBackpackModel;
const item = {key: 'minecraft:diamond_pickaxe#exact', count: 5, taskCount: 2};
const snapshot = {revision: '42', available: true, busy: false};
process.stdout.write(JSON.stringify(eval(process.argv[2])));
"""
    result = subprocess.run(
        [NODE, "-e", script, str(WEB / "backpack-model.js"), expression],
        check=True,
        capture_output=True,
        encoding="utf-8",
    )
    return json.loads(result.stdout)


def test_allocating_personal_quantity_preserves_the_exact_identity_and_revision() -> None:
    result = run_model("model.allocation(snapshot, item, 1)")
    assert result == {
        "revision": "42",
        "key": "minecraft:diamond_pickaxe#exact",
        "keepCount": 1,
    }
    assert run_model("model.freeCount(item)") == 3


def test_slot_order_places_quickbar_below_the_three_inventory_rows() -> None:
    assert run_model("model.slotOrder()") == list(range(9, 36)) + list(range(9))


def test_tooltip_keeps_item_details_and_ownership_summary() -> None:
    result = run_model(
        "model.slotDetails("
        "{count: 1, maxCount: 1, tooltip: ['经验修补'], "
        "maxDurability: 100, remainingDurability: 70}, "
        "{count: 67, protectedCount: 17, taskCount: 30, classificationCount: 20})"
    )
    assert result == [
        "经验修补",
        "耐久 70 / 100",
        "保留自用 17 · 任务 30 · 待分类 20",
    ]


@pytest.mark.parametrize(
    "expression",
    [
        "model.allocation({...snapshot, busy: true}, item, 0)",
        "model.allocation({...snapshot, available: false}, item, 0)",
        "model.allocation(snapshot, item, 4)",
        "model.allocation(snapshot, item, -1)",
        "model.allocation(snapshot, item, 0.5)",
    ],
)
def test_invalid_allocations_do_not_take_task_owned_items(expression: str) -> None:
    result = run_model(
        f"(() => {{try {{ {expression}; return false; }} catch (error) {{return true;}}}})()"
    )
    assert result is True


def test_backpack_controls_are_available_from_the_main_navigation() -> None:
    navigation = (WEB / "common.js").read_text(encoding="utf-8")
    page = (WEB / "backpack.html").read_text(encoding="utf-8")
    assert 'href: "/backpack.html", label: "背包管理"' in navigation
    assert 'id="backpack-keep"' in page
    assert 'id="backpack-classify-all"' in page
    assert 'id="backpack-keep-all"' in page
