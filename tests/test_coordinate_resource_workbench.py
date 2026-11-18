import json
import shutil
import subprocess
from pathlib import Path

import pytest


WEB = Path(__file__).resolve().parents[1] / "src/main/resources/stowling-web"
NODE = shutil.which("node")


@pytest.mark.skipif(NODE is None, reason="Node.js is required")
def test_task_policy_round_trip_preserves_per_task_storage_choice() -> None:
    script = """
        globalThis.window = {};
        require(process.argv[1]);
        const model = window.StowlingAutoClassifyTaskModel;
        const policy = {mode: 'BOX_ONLY', loosePercent: 25, allowPartialBoxes: true,
            refillLoose: true};
        const draft = model.draftFromTask({detection: {coordinateRef: 'coordinate:point'},
            cacheRegionRef: 'region:cache', classificationAreas: [{
                categoryZoneRef: 'category-zone:zone', ruleId: 'DIRECT_BLOCK_ID',
                markerSources: ['BLOCK'], storagePolicy: policy}]});
        process.stdout.write(JSON.stringify(draft.classificationAreas[0].storagePolicy));
    """
    result = subprocess.run(
        [str(NODE), "-e", script, str(WEB / "auto-classify-task-model.js")],
        check=True, capture_output=True, encoding="utf-8",
    )
    assert json.loads(result.stdout) == {
        "mode": "BOX_ONLY", "loosePercent": 25, "allowPartialBoxes": True,
        "refillLoose": True,
    }
