import json
import shutil
import subprocess
from pathlib import Path

import pytest


WEB = Path(__file__).resolve().parents[1] / "src/main/resources/stowling-web"
NODE = shutil.which("node")


@pytest.mark.skipif(NODE is None, reason="Node.js is required")
def test_task_save_discards_legacy_storage_choices_and_keeps_coordinate_settings() -> None:
    script = """
        globalThis.window = {};
        require(process.argv[1]);
        const model = window.StowlingAutoClassifyTaskModel;
        const policy = {mode: 'BOX_ONLY', loosePercent: 25, allowPartialBoxes: true,
            refillLoose: true};
        const resources = {coordinates: [{id: 'point'}], regions: [{id: 'cache'}],
            categoryZones: [{id: 'zone', bindings: [
                {storagePolicy: {mode: 'NORMAL'}}, {storagePolicy: {mode: 'MIXED'}}]}]};
        const originalResources = structuredClone(resources);
        const draft = model.draftFromTask({kind: 'CLASSIFICATION',
            collectionEnabled: false, classificationEnabled: true, enabled: false,
            collectionPriority: 'NORMAL',
            cacheLocationRef: 'region:cache', classificationAreas: [{
                categoryZoneRef: 'category-zone:zone', ruleId: 'DIRECT_BLOCK_ID',
                markerSources: ['BLOCK'], storagePolicy: policy}]});
        const payload = model.payload(draft, resources);
        const withLegacyDraft = model.payload({...draft, classificationAreas: [
            {...draft.classificationAreas[0], storagePolicy: policy}]}, resources);
        process.stdout.write(JSON.stringify({areas: draft.classificationAreas,
            saved: payload.classificationAreas, savedLegacy: withLegacyDraft.classificationAreas,
            resourcesUnchanged: JSON.stringify(resources) === JSON.stringify(originalResources)}));
    """
    result = subprocess.run(
        [str(NODE), "-e", script, str(WEB / "auto-classify-task-model.js")],
        check=True, capture_output=True, encoding="utf-8",
    )
    assert json.loads(result.stdout) == {
        "areas": [{"categoryZoneId": "zone"}],
        "saved": [{"categoryZoneRef": "category-zone:zone"}],
        "savedLegacy": [{"categoryZoneRef": "category-zone:zone"}],
        "resourcesUnchanged": True,
    }
