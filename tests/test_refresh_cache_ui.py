import shutil
import subprocess
from pathlib import Path

import pytest


WEB = Path(__file__).resolve().parents[1] / "src" / "main" / "resources" / "stowling-web"


def test_refresh_task_has_range_switches_and_schedule_controls() -> None:
    html = (WEB / "tasks.html").read_text(encoding="utf-8")
    assert 'src="/refresh-cache-ui.js"' in html
    assert 'id="new-refresh-cache-task"' in html
    assert 'id="refresh-cache-target"' in html
    assert 'id="refresh-scan-markers"' in html
    assert 'id="refresh-include-markers"' in html
    assert 'value="MANUAL">仅手动' in html
    assert 'value="DAILY">每天固定时间' in html
    assert 'value="INTERVAL">每隔指定时长' in html


def test_refresh_options_and_schedules_round_trip() -> None:
    node = shutil.which("node")
    if node is None:
        pytest.skip("Node.js is needed to exercise the console model")
    script = '''
const assert = require("node:assert/strict");
require(process.argv[1]);
const model = globalThis.StowlingRefreshCacheModel;
const draft = model.settings();
assert.deepEqual(draft.kinds, {chests: true, barrels: true, shulkerBoxes: true,
    otherContainers: false});
draft.targetRef = "category-zone:layout";
draft.scanMarkers = true;
draft.includeMarkers = true;
assert.deepEqual(model.settings(model.parameters(draft)), draft);
draft.targetRef = "region:region";
assert.equal(model.parameters(draft).includeMarkersInInventory, "false");
for (const key of Object.keys(draft.kinds)) draft.kinds[key] = false;
assert.throws(() => model.parameters(draft));
assert.deepEqual(model.schedulePayload({mode: "DAILY", time: "04:00", zoneId: "Asia/Shanghai"}),
    {mode: "DAILY", time: "04:00", zoneId: "Asia/Shanghai"});
assert.deepEqual(model.schedulePayload({mode: "INTERVAL", intervalMinutes: 60}),
    {mode: "INTERVAL", intervalMinutes: 60});
assert.throws(() => model.schedulePayload({mode: "INTERVAL", intervalMinutes: 0}));
assert.deepEqual(model.schedulePayload({mode: "MANUAL"}), {mode: "MANUAL"});
assert.deepEqual(model.taskSwitch(false, false, "QUEUED"),
    {checked: true, stopping: false, label: "排队中"});
assert.equal(model.taskSwitch(false, false, "SUCCEEDED").checked, false);
assert.equal(model.taskSwitch(false, false, "INTERRUPTING").checked, false);
assert.equal(model.taskSwitch(true, false, null).checked, false);
assert.equal(model.taskSwitch(true, true, null).checked, true);
'''
    subprocess.run([node, "-e", script, str(WEB / "refresh-cache-ui.js")], check=True)
