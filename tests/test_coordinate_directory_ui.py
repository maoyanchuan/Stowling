import json
import shutil
import subprocess
from pathlib import Path

import pytest


WEB = Path(__file__).resolve().parents[1] / "src/main/resources/stowling-web"
NODE = shutil.which("node")


def test_folder_and_resource_actions_share_the_coordinate_page() -> None:
    html = (WEB / "coordinates.html").read_text(encoding="utf-8")
    for control in (
        "coordinate-list", "add-folder", "folder-form", "coordinate-kind",
    ):
        assert f'id="{control}"' in html
    assert 'data-coordinate-kind="category-zone"' in html
    assert 'data-coordinate-kind="warehouse"' not in html
    assert "储存区成员" not in html
    assert 'id="facilities"' not in html


def test_cache_resource_types_keep_their_folder_and_radius_setting() -> None:
    script = """
        globalThis.document = {querySelector: () => null};
        globalThis.window = {Stowling: {}};
        require(process.argv[1]);
        require(process.argv[2]);
        const model = window.StowlingAutoClassifyTaskModel;
        const picker = window.StowlingResourcePicker;
        const resources = {
            coordinates: [{id: "drop", name: "Drop", radius: 90}],
            regions: [{id: "row", name: "Row"}],
            warehouses: [{id: "cache", name: "Buffer"}],
            categoryZones: [{id: "sorting", name: "Sorting"}]
        };
        const locations = model.cacheLocations(resources).map(item => item.id);
        const draft = {...model.newDraft(), classificationEnabled: false,
            coordinateId: "drop", cacheLocationRef: "warehouse:cache"};
        const saved = model.payload(draft, resources);
        const override = model.payload({...draft, pickupRadius: "5"}, resources);
        const catalog = {folders: [{id: "folder", name: "Collection"}],
            placements: {"warehouse:cache": "folder", "coordinate:drop": "folder"}};
        const groups = locations.map(ref => picker.folderLabel(catalog, ref));
        const invalid = model.validateDraft({...draft, cacheLocationRef: "category-zone:sorting"},
            resources);
        process.stdout.write(JSON.stringify({locations, saved, override, groups, invalid}));
    """
    completed = subprocess.run(
        [str(NODE), "-e", script, str(WEB / "auto-classify-task-model.js"),
         str(WEB / "resource-picker.js")],
        check=True, capture_output=True, encoding="utf-8",
    )
    state = json.loads(completed.stdout)
    assert state["locations"] == ["coordinate:drop", "region:row", "warehouse:cache"]
    assert state["groups"] == ["Collection", "未分组", "Collection"]
    assert state["saved"]["cacheLocationRef"] == "warehouse:cache"
    assert state["saved"]["detection"]["pickupRadius"] is None
    assert state["override"]["detection"]["pickupRadius"] == 5
    assert "所选缓存位置不存在，请重新选择" in state["invalid"]


@pytest.mark.skipif(NODE is None, reason="Node.js is required")
def test_drag_uses_the_middle_of_a_folder_to_move_and_edges_to_sort() -> None:
    script = r"""
        const fs = require("node:fs");
        const vm = require("node:vm");
        const source = fs.readFileSync(process.argv[1], "utf8");
        const start = source.indexOf("function dropLocation(event)");
        const end = source.indexOf("\n    }", start) + 6;
        const context = {dragged: "coordinate:dragged",
            siblings: () => [{ref: "folder:group"}, {ref: "coordinate:next"}]};
        vm.createContext(context);
        vm.runInContext(source.slice(start, end), context);
        const row = {dataset: {ref: "folder:group", folderId: ""},
            getBoundingClientRect: () => ({top: 0, height: 40})};
        const target = {closest: () => row};
        const positions = [4, 20, 36].map(clientY => context.dropLocation({target, clientY}));
        context.dragged = "folder:dragged";
        positions.push(context.dropLocation({target, clientY: 20}));
        process.stdout.write(JSON.stringify(positions.map(({position, folderId, beforeRef}) =>
            ({position, folderId, beforeRef}))));
    """
    completed = subprocess.run(
        [str(NODE), "-e", script, str(WEB / "resource-browser.js")],
        check=True, capture_output=True, encoding="utf-8",
    )
    assert json.loads(completed.stdout) == [
        {"position": "before", "folderId": "", "beforeRef": "folder:group"},
        {"position": "inside", "folderId": "group", "beforeRef": None},
        {"position": "after", "folderId": "", "beforeRef": "coordinate:next"},
        {"position": "after", "folderId": "", "beforeRef": "coordinate:next"},
    ]
