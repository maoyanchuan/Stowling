import json
import shutil
import subprocess
from pathlib import Path

import pytest


WEB = Path(__file__).resolve().parents[1] / "src/main/resources/stowling-web"
NODE = shutil.which("node")


@pytest.mark.skipif(NODE is None, reason="Node.js is required")
def test_storage_policy_round_trip_keeps_tail_switch_and_external_packing_area() -> None:
    policy = {"mode": "MIXED", "loosePercent": 50, "allowPartialBoxes": True,
              "refillLoose": True, "looseSlots": 19,
              "partialBoxDestinationRef": "region:7c69978e-13f9-4be2-8ce0-4c0ea3b8d321",
              "packingArea": {"firstX": 1, "firstY": 64, "firstZ": 2,
                              "secondX": 1, "secondY": 64, "secondZ": 3}}
    script = """
        globalThis.window = {};
        require(process.argv[1]);
        const model = window.StowlingCategoryZoneModel;
        const normalized = model.normalizeCategoryZones({categoryZones: [{
            bindings: [{storagePolicy: JSON.parse(process.argv[2])}]}]});
        const value = model.payload(normalized.categoryZones[0]);
        process.stdout.write(JSON.stringify(value.bindings[0].storagePolicy));
    """
    result = subprocess.run(
        [str(NODE), "-e", script, str(WEB / "category-zone-model.js"), json.dumps(policy)],
        check=True, capture_output=True, encoding="utf-8",
    )
    assert json.loads(result.stdout) == policy


def test_task_editor_loads_and_saves_the_optional_refill_switch() -> None:
    source = (WEB / "storage-policy-editor.js").read_text(encoding="utf-8")
    assert "refill.checked = Boolean(policy.refillLoose)" in source
    assert 'refillLoose: mode.value === "MIXED" && refill.checked' in source
    assert "散货不足设定槽位时拆盒补足" in source
    task = (WEB / "auto-classify-ui.js").read_text(encoding="utf-8")
    assert "StowlingStoragePolicyEditor.create(policy, state.resources)" in task
