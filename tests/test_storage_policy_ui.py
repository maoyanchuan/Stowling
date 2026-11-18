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
