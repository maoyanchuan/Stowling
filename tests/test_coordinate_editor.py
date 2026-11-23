import json
import shutil
import subprocess
from html.parser import HTMLParser
from pathlib import Path

import pytest


WEB = Path(__file__).resolve().parents[1] / "src/main/resources/stowling-web"
NODE = shutil.which("node")


def run_model(script: str) -> dict:
    completed = subprocess.run(
        [str(NODE), "-e", "globalThis.window = {}; require(process.argv[1]); "
         "require(process.argv[2]); const model = window.StowlingCoordinateEditorModel; "
         + script, str(WEB / "category-zone-model.js"),
         str(WEB / "coordinate-editor-model.js")],
        check=True, capture_output=True, encoding="utf-8",
    )
    return json.loads(completed.stdout)


@pytest.mark.skipif(NODE is None, reason="Node.js is required")
def test_directory_restores_order_placements_and_optional_point_values() -> None:
    result = run_model("""
        const directory = {coordinates: [{id: 'p', name: 'Point', x: 1, y: 2, z: 3,
            radius: null, dimension: 'minecraft:the_nether'}],
            regions: [{id: 'r', name: 'Region', firstX: 1, firstY: 2, firstZ: 3,
                secondX: 4, secondY: 5, secondZ: 6}],
            catalog: {order: ['region:r', 'coordinate:p'],
                placements: {'coordinate:p': 'folder'},
                warehouses: [{id: 'w', name: 'Warehouse', members: ['coordinate:p']}]}};
        const values = model.resources(directory);
        process.stdout.write(JSON.stringify({order: values.map(model.ref),
            folder: values[1].folderId, point: model.payload(values[1]),
            region: model.payload(values[0])}));
    """)
    assert result["order"] == ["region:r", "coordinate:p"]
    assert result["folder"] == "folder"
    assert result["point"] == {
        "name": "Point", "x": 1, "y": 2, "z": 3,
        "radius": None, "dimension": "minecraft:the_nether",
    }
    assert result["region"]["secondZ"] == 6


def editor_structure(source: str) -> list[tuple]:
    class EditorParser(HTMLParser):
        def __init__(self) -> None:
            super().__init__()
            self.depth = 0
            self.structure: list[tuple] = []

        def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
            if not self.depth and dict(attrs).get("class") != "coordinate-shell":
                return
            normalized = [(key, " ".join(value.split()) if value is not None else None)
                          for key, value in attrs]
            self.structure.append(("start", tag, sorted(normalized)))
            if tag not in {"input", "img", "br", "meta", "link"}:
                self.depth += 1

        def handle_startendtag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
            if self.depth:
                self.structure.append(("single", tag, sorted(attrs)))

        def handle_endtag(self, tag: str) -> None:
            if self.depth:
                self.structure.append(("end", tag))
                self.depth -= 1

        def handle_data(self, data: str) -> None:
            if self.depth and data.strip():
                self.structure.append(("text", " ".join(data.split())))

    parser = EditorParser()
    parser.feed(source)
    return parser.structure


def test_coordinate_editor_uses_the_complete_approved_template_structure() -> None:
    root = WEB.parents[3]
    template = (root / "docs/ui/coordinate-management-standard.html").read_text(encoding="utf-8")
    page = (WEB / "coordinates.html").read_text(encoding="utf-8")
    assert editor_structure(page) == editor_structure(template)


ZONE_SETUP = """
    const bounds = {firstX: 0, firstY: 64, firstZ: 0,
        secondX: 0, secondY: 65, secondZ: 2};
    const binding = {markerArea: bounds, storageArea: bounds,
        standingArea: bounds, ruleId: 'UNSTACKABLE_ITEMS', wholeStorage: false,
        storagePolicy: {mode: 'MIXED', loosePercent: 25, looseSlots: 4,
            allowPartialBoxes: true, refillLoose: true, packingArea: bounds,
            partialBoxDestinationRef: 'coordinate:cache'},
        ignoredMarkerBlockIds: ['minecraft:stone'],
        containerOverrides: [{storagePosition: {x: 0, y: 64, z: 0},
            mode: 'ADD', itemIds: ['minecraft:diamond_sword']}]};
    const raw = {id: 'z', name: 'Zone', bindings: [binding]};
    const directory = {categoryZones: [raw], catalog: {}};
"""


@pytest.mark.skipif(NODE is None, reason="Node.js is required")
def test_renaming_keeps_unshown_category_settings_and_per_binding_policies() -> None:
    result = run_model(ZONE_SETUP + """
        raw.bindings.push({...binding, ruleId: 'MARKER_CONTAINER_CONTENTS', wholeStorage: true,
            storagePolicy: {...binding.storagePolicy, mode: 'BOX_ONLY', loosePercent: 70}});
        const value = model.resources(directory)[0];
        value.name = 'Renamed';
        const saved = model.payload(value);
        process.stdout.write(JSON.stringify({saved, original: raw}));
    """)
    saved = result["saved"]
    assert saved["name"] == "Renamed"
    first, second = saved["bindings"]
    assert first["ruleId"] == "UNSTACKABLE_ITEMS"
    assert first["standingArea"]["secondZ"] == 2
    assert first["storagePolicy"] == result["original"]["bindings"][0]["storagePolicy"]
    assert first["containerOverrides"][0]["itemIds"] == ["minecraft:diamond_sword"]
    assert first["ignoredMarkerBlockIds"] == ["minecraft:stone"]
    assert second["ruleId"] == "MARKER_CONTAINER_CONTENTS"
    assert second["wholeStorage"] is True
    assert second["storagePolicy"]["mode"] == "BOX_ONLY"
    assert second["storagePolicy"]["loosePercent"] == 70
    assert "expansion" not in saved


@pytest.mark.skipif(NODE is None, reason="Node.js is required")
def test_expanded_zone_edits_only_the_original_templates() -> None:
    result = run_model(ZONE_SETUP + """
        raw.expansion = {area: {...bounds, secondX: 5},
            direction: 'X_POSITIVE', step: 1, templateCount: 1};
        raw.bindings = Array.from({length: 6}, (_, index) => ({...binding,
            markerArea: {...bounds, firstX: index, secondX: index},
            storageArea: {...bounds, firstX: index, secondX: index}}));
        const value = model.resources(directory)[0];
        value.overallRegion.second.x = 9;
        value.categoryOptions.expansionStep = 2;
        process.stdout.write(JSON.stringify({count: value.bindings.length,
            saved: model.payload(value)}));
    """)
    assert result["count"] == 1
    assert len(result["saved"]["bindings"]) == 1
    assert result["saved"]["expansion"] == {
        "firstX": 0, "firstY": 64, "firstZ": 0,
        "secondX": 9, "secondY": 65, "secondZ": 2,
        "direction": "X_POSITIVE", "step": 2,
    }


@pytest.mark.skipif(NODE is None, reason="Node.js is required")
def test_category_controls_update_real_rules_storage_and_cache_references() -> None:
    result = run_model(ZONE_SETUP + """
        const value = model.resources(directory)[0];
        Object.assign(value.categoryOptions, {recognitionMode: 'container-item',
            storageMethod: 'box', cacheDestination: 'region:new-cache',
            supportBlock: 'minecraft:dirt，minecraft:stone'});
        const saved = model.payload(value);
        value.categoryOptions.noCacheLocation = 'inside-box';
        value.categoryOptions.ignoreSupportBlock = false;
        const disabled = model.payload(value);
        process.stdout.write(JSON.stringify({saved, disabled}));
    """)
    binding = result["saved"]["bindings"][0]
    assert binding["ruleId"] == "MARKER_CONTAINER_CONTENTS"
    assert binding["storagePolicy"]["mode"] == "BOX_ONLY"
    assert binding["storagePolicy"]["partialBoxDestinationRef"] == "region:new-cache"
    assert binding["ignoredMarkerBlockIds"] == ["minecraft:dirt", "minecraft:stone"]
    disabled = result["disabled"]["bindings"][0]
    assert "partialBoxDestinationRef" not in disabled["storagePolicy"]
    assert disabled["ignoredMarkerBlockIds"] == []
    assert disabled["storagePolicy"]["packingArea"] == binding["storagePolicy"]["packingArea"]


@pytest.mark.skipif(NODE is None, reason="Node.js is required")
def test_new_single_slice_uses_existing_auto_expansion_and_rejects_missing_cache() -> None:
    result = run_model(ZONE_SETUP + """
        const value = model.resources(directory)[0];
        value.originalOptions = {};
        value.categoryOptions.scopeMode = 'single';
        value.categoryOptions.expansionDirection = 'AUTO';
        value.categoryOptions.recognitionMode = 'marker';
        value.overallRegion.second.x = 3;
        const saved = model.payload(value);
        value.categoryOptions.cacheDestination = '';
        let error;
        try { model.payload(value); } catch (failure) { error = failure.message; }
        process.stdout.write(JSON.stringify({saved, error}));
    """)
    assert result["saved"]["expansion"]["direction"] == "X_POSITIVE"
    assert result["saved"]["expansion"]["step"] == 1
    assert result["error"] == "请选择指定缓存位置。"


@pytest.mark.skipif(NODE is None, reason="Node.js is required")
def test_failed_save_retains_form_values_and_restores_busy_controls() -> None:
    script = r"""
        const fs = require('node:fs');
        const vm = require('node:vm');
        const source = fs.readFileSync(process.argv[1], 'utf8');
        const start = source.indexOf('    async function writeAction(');
        const end = source.indexOf('\n    }', start) + 6;
        const controls = () => ({inert: false, disabled: false});
        const nodes = {form: {...controls(), value: 'unsaved XYZ'}, list: controls(),
            folderForm: controls(), kindPicker: controls(), add: controls(),
            addFolder: controls(), footer: {}};
        const statuses = [];
        const context = {nodes, busy: false, coordinates: [], folders: [],
            S: {isConnected: () => true, handleError: () => {}},
            setStatus: (...status) => statuses.push(status),
            document: {querySelector: () => ({setAttribute: () => {}})}};
        vm.createContext(context);
        vm.runInContext(source.slice(start, end), context);
        (async () => {
            const result = await context.writeAction(async () => {
                throw {code: 'revision_conflict', message: 'Conflict'};
            }, 'Saved');
            process.stdout.write(JSON.stringify({result, nodes, statuses, busy: context.busy}));
        })();
    """
    completed = subprocess.run(
        [str(NODE), "-e", script, str(WEB / "coordinate-editor.js")],
        check=True, capture_output=True, encoding="utf-8",
    )
    result = json.loads(completed.stdout)
    assert result["result"] is False
    assert result["nodes"]["form"]["value"] == "unsaved XYZ"
    assert result["nodes"]["form"]["inert"] is False
    assert result["nodes"]["add"]["disabled"] is False
    assert result["busy"] is False
    assert result["statuses"][-1][1] == "error"
    assert "草稿已保留" in result["statuses"][-1][0]


@pytest.mark.skipif(NODE is None, reason="Node.js is required")
def test_editor_sends_revision_through_authenticated_request_and_advances_it() -> None:
    script = r"""
        const fs = require('node:fs');
        const vm = require('node:vm');
        const source = fs.readFileSync(process.argv[1], 'utf8');
        const start = source.indexOf('    async function send(');
        const end = source.indexOf('\n    }', start) + 6;
        const requests = [];
        const context = {directory: {revision: 'old'},
            CustomEvent: class { constructor(name, detail) { this.detail = detail; } },
            document: {dispatchEvent: () => {}},
            S: {request: async (path, options) => {
                requests.push({path, method: options.method, body: JSON.parse(options.body)});
                return {revision: 'next'};
            }}};
        vm.createContext(context);
        vm.runInContext(source.slice(start, end), context);
        (async () => {
            await context.send('/v1/coordinate-resources', 'PUT', {
                resourceRef: 'coordinate:p', folderId: 'f', beforeRef: null}, true);
            await context.send('/v1/coordinates', 'POST', {name: 'New', x: 0, y: 64, z: 0});
            process.stdout.write(JSON.stringify({requests, revision: context.directory.revision}));
        })();
    """
    completed = subprocess.run(
        [str(NODE), "-e", script, str(WEB / "coordinate-editor.js")],
        check=True, capture_output=True, encoding="utf-8",
    )
    result = json.loads(completed.stdout)
    assert result["requests"][0]["body"]["revision"] == "old"
    assert result["requests"][0]["body"]["resourceRef"] == "coordinate:p"
    assert result["requests"][0]["body"]["folderId"] == "f"
    assert "revision" not in result["requests"][1]["body"]
    assert result["revision"] == "next"
