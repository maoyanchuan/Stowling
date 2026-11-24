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


def editor_structure(source: str, omitted_ids: frozenset[str] = frozenset()) -> list[tuple]:
    class EditorParser(HTMLParser):
        def __init__(self) -> None:
            super().__init__()
            self.depth = 0
            self.skipped_depth = 0
            self.structure: list[tuple] = []

        def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
            if self.skipped_depth or dict(attrs).get("id") in omitted_ids:
                if tag not in {"input", "img", "br", "meta", "link"}:
                    self.skipped_depth += 1
                return
            if not self.depth and dict(attrs).get("class") != "coordinate-shell":
                return
            normalized = [(key, " ".join(value.split()) if value is not None else None)
                          for key, value in attrs]
            self.structure.append(("start", tag, sorted(normalized)))
            if tag not in {"input", "img", "br", "meta", "link"}:
                self.depth += 1

        def handle_startendtag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
            if self.depth and not self.skipped_depth:
                self.structure.append(("single", tag, sorted(attrs)))

        def handle_endtag(self, tag: str) -> None:
            if self.skipped_depth:
                self.skipped_depth -= 1
                return
            if self.depth:
                self.structure.append(("end", tag))
                self.depth -= 1

        def handle_data(self, data: str) -> None:
            if self.depth and not self.skipped_depth and data.strip():
                self.structure.append(("text", " ".join(data.split())))

    parser = EditorParser()
    parser.feed(source)
    return parser.structure


def test_coordinate_editor_uses_the_approved_template_core_structure() -> None:
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
@pytest.mark.parametrize("stacks", [0, 9, 27, 54])
def test_bulk_stacks_apply_effective_profile_and_survive_copying(stacks: int) -> None:
    result = run_model(ZONE_SETUP + """
        const value = model.resources(directory)[0];
        value.categoryOptions.bulkStacks = 13;
        const group = value.bindings[0];
        group.optionsEnabled = true;
        group.categoryOptions = {...value.categoryOptions, bulkStacks: """ + str(stacks) + """};
        const independent = model.payload(value);
        group.optionsEnabled = false;
        const inherited = model.payload(value);
        const copied = model.copy(value);
        copied.bindings[0].optionsEnabled = true;
        copied.categoryOptions.storageMethod = 'bulk';
        const restored = model.payload(copied);
        process.stdout.write(JSON.stringify({independent, inherited, restored}));
    """)
    assert result["independent"]["bindings"][0]["storagePolicy"]["looseSlots"] == stacks
    assert result["inherited"]["bindings"][0]["storagePolicy"]["looseSlots"] == 13
    assert result["inherited"]["bindings"][0]["categoryOptions"]["bulkStacks"] == stacks
    assert result["restored"]["bindings"][0]["storagePolicy"]["looseSlots"] == stacks
    assert result["restored"]["categoryOptions"]["bulkStacks"] == 13


@pytest.mark.skipif(NODE is None, reason="Node.js is required")
@pytest.mark.parametrize("stacks", [None, -1, 55, 1.5, "12"])
def test_bulk_stacks_reject_invalid_values(stacks: int | float | str | None) -> None:
    result = run_model(ZONE_SETUP + """
        const value = model.resources(directory)[0];
        value.categoryOptions.bulkStacks = """ + json.dumps(stacks) + """;
        try { model.payload(value); process.stdout.write('{}'); }
        catch (error) { process.stdout.write(JSON.stringify({error: error.message})); }
    """)
    assert "散货占比" in result["error"]


@pytest.mark.skipif(NODE is None, reason="Node.js is required")
def test_legacy_bulk_stacks_prefer_slots_then_percent_then_default() -> None:
    result = run_model(ZONE_SETUP + """
        const explicit = model.resources(directory)[0];
        delete binding.storagePolicy.looseSlots;
        const percent = model.resources(directory)[0];
        delete binding.storagePolicy.loosePercent;
        const fallback = model.resources(directory)[0];
        raw.categoryOptions = {storageMethod: 'mixed'};
        binding.categoryOptions = {storageMethod: 'mixed'};
        binding.storagePolicy.looseSlots = 7;
        const oldProfiles = model.resources(directory)[0];
        process.stdout.write(JSON.stringify({explicit: explicit.categoryOptions.bulkStacks,
            percent: percent.categoryOptions.bulkStacks,
            fallback: fallback.categoryOptions.bulkStacks,
            global: oldProfiles.categoryOptions.bulkStacks,
            local: oldProfiles.bindings[0].categoryOptions.bulkStacks}));
    """)
    assert result == {"explicit": 4, "percent": 13, "fallback": 27, "global": 7, "local": 7}


@pytest.mark.skipif(NODE is None, reason="Node.js is required")
def test_binding_names_survive_loading_renaming_copying_and_auto_expansion() -> None:
    result = run_model(ZONE_SETUP + """
        raw.bindings[0].name = '石材';
        const value = model.resources(directory)[0];
        const loaded = value.bindings[0].name;
        value.bindings[0].name = '木材';
        const renamed = model.payload(value);
        const copied = model.payload(model.copy(value));
        value.categoryOptions.scopeMode = 'single';
        value.categoryOptions.expansionDirection = 'AUTO';
        value.overallRegion.second.x = 3;
        const expanded = model.payload(value);
        process.stdout.write(JSON.stringify({loaded, renamed, copied, expanded}));
    """)
    assert result["loaded"] == "石材"
    for key in ("renamed", "copied", "expanded"):
        assert result[key]["bindings"][0]["name"] == "木材"
    assert result["expanded"]["expansion"]["direction"] == "X_POSITIVE"


@pytest.mark.skipif(NODE is None, reason="Node.js is required")
def test_independent_settings_preserve_global_values_and_disabled_drafts() -> None:
    result = run_model(ZONE_SETUP + """
        const value = model.resources(directory)[0];
        const global = {...value.categoryOptions, storageMethod: 'bulk',
            recognitionMode: 'marker', noCacheLocation: 'inside-box', cacheDestination: '',
            ignoreSupportBlock: false, supportBlock: ''};
        value.categoryOptions = global;
        value.overallRegion.second.x = 7;
        value.bindings[0].optionsEnabled = true;
        value.bindings[0].categoryOptions = {...global, storageMethod: 'box',
            recognitionMode: 'container-item', ignoreSupportBlock: true,
            supportBlock: 'minecraft:stone'};
        const enabled = model.payload(value);
        value.bindings[0].optionsEnabled = false;
        const disabled = model.payload(value);
        const server = {...raw, categoryOptions: disabled.categoryOptions,
            overallArea: disabled.overallArea,
            bindings: [{...raw.bindings[0], optionsEnabled: false,
                categoryOptions: disabled.bindings[0].categoryOptions,
                ruleId: disabled.bindings[0].ruleId,
                storagePolicy: disabled.bindings[0].storagePolicy}]};
        const restored = model.resources({categoryZones: [server], catalog: {}})[0];
        const copy = model.copy(restored);
        copy.bindings[0].optionsEnabled = true;
        const reenabled = model.payload(copy);
        process.stdout.write(JSON.stringify({enabled, disabled, restored, reenabled}));
    """)
    assert result["enabled"]["categoryOptions"]["storageMethod"] == "bulk"
    assert result["enabled"]["bindings"][0]["storagePolicy"]["mode"] == "BOX_ONLY"
    assert result["enabled"]["bindings"][0]["ruleId"] == "MARKER_CONTAINER_CONTENTS"
    disabled = result["disabled"]["bindings"][0]
    assert disabled["optionsEnabled"] is False
    assert disabled["storagePolicy"]["mode"] == "NORMAL"
    assert disabled["categoryOptions"]["storageMethod"] == "box"
    assert result["restored"]["bindings"][0]["optionsEnabled"] is False
    assert result["restored"]["overallRegion"]["second"]["x"] == 7
    assert result["reenabled"]["bindings"][0]["storagePolicy"]["mode"] == "BOX_ONLY"
    assert result["reenabled"]["categoryOptions"]["storageMethod"] == "bulk"


@pytest.mark.skipif(NODE is None, reason="Node.js is required")
def test_selection_group_does_not_interfere_with_an_independent_single_slice() -> None:
    result = run_model(ZONE_SETUP + """
        const value = model.resources(directory)[0];
        value.categoryOptions = {...value.categoryOptions, scopeMode: 'selection',
            recognitionMode: 'marker', noCacheLocation: 'inside-box', cacheDestination: ''};
        const second = model.copy(value.bindings[0]);
        second.id = 'independent';
        second.optionsEnabled = true;
        second.categoryOptions = {...value.categoryOptions, scopeMode: 'single'};
        value.bindings.push(second);
        value.overallRegion.second.x = 3;
        const saved = model.payload(value);
        process.stdout.write(JSON.stringify(saved));
    """)
    assert len(result["bindings"]) == 2
    assert result["categoryOptions"]["scopeMode"] == "selection"
    assert result["bindings"][1]["categoryOptions"]["scopeMode"] == "single"
    assert result["expansion"]["direction"] == "X_POSITIVE"


@pytest.mark.skipif(NODE is None, reason="Node.js is required")
def test_binding_name_accepts_focus_and_edits_without_toggling_the_card() -> None:
    script = r"""
        const fs = require('node:fs');
        const vm = require('node:vm');
        const source = fs.readFileSync(process.argv[1], 'utf8');
        const elements = [];
        const document = {createElement: tag => {
            const element = {tag, dataset: {}, children: [], handlers: {}, readOnly: false,
                append(...children) { this.children.push(...children); },
                setAttribute() {},
                addEventListener(name, handler) { this.handlers[name] = handler; },
                querySelector(selector) {
                    return elements.find(child => child.className === selector.slice(1));
                }};
            elements.push(element);
            return element;
        }};
        let toggles = 0;
        const context = {document, dirty: false, appendBindingRegion() {},
            bindingOptions: new WeakMap(), categoryMenuOpen: null,
            updateCategoryOptionState() {},
            createBindingOptions: () => ({root: {}}), reveal() {},
            setCategoryBindingOpen() { toggles++; }};
        vm.createContext(context);
        for (const name of ['createCategoryBindingCard', 'readCategoryBindings']) {
            const start = source.indexOf('    function ' + name + '(');
            const end = source.indexOf('\n    }', start) + 6;
            vm.runInContext(source.slice(start, end), context);
        }
        const region = {first: {x: 0, y: 64, z: 0}, second: {x: 0, y: 64, z: 0}};
        const card = context.createCategoryBindingCard({id: 'binding', name: '石材',
            markerRegion: region, storageRegion: region}, 0);
        const input = elements.find(element => element.className === 'category-binding-name');
        const loaded = input.value;
        let prevented = false;
        let stopped = false;
        input.handlers.click({preventDefault() { prevented = true; },
            stopPropagation() { stopped = true; }});
        input.value = '木材';
        context.nodes = {categoryBindings: {children: [card]}};
        context.readBindingRegion = () => region;
        const result = context.readCategoryBindings();
        process.stdout.write(JSON.stringify({loaded, prevented, stopped,
            readOnly: input.readOnly, saved: result[0].name, toggles}));
    """
    completed = subprocess.run(
        [str(NODE), "-e", script, str(WEB / "coordinate-editor.js")],
        check=True, capture_output=True, encoding="utf-8",
    )
    result = json.loads(completed.stdout)
    assert result == {
        "loaded": "石材", "prevented": False, "stopped": True,
        "readOnly": False, "saved": "木材", "toggles": 1,
    }


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
def test_standing_position_uses_effective_options_and_keeps_disabled_group_drafts() -> None:
    result = run_model(ZONE_SETUP + """
        const value = model.resources(directory)[0];
        value.categoryOptions = {...value.categoryOptions, specifyStanding: true,
            standingPosition: {x: -3, y: 65, z: 7}};
        const group = value.bindings[0];
        group.optionsEnabled = true;
        group.categoryOptions = {...value.categoryOptions,
            standingPosition: {x: 8, y: 66, z: -9}};
        const local = model.payload(value);
        group.optionsEnabled = false;
        const inherited = model.payload(value);
        group.optionsEnabled = true;
        group.categoryOptions.specifyStanding = false;
        const disabled = model.payload(value);
        const clone = model.copy(value);
        clone.bindings[0].categoryOptions.standingPosition.x = 99;
        process.stdout.write(JSON.stringify({local, inherited, disabled,
            originalX: group.categoryOptions.standingPosition.x}));
    """)
    assert result["local"]["bindings"][0]["standingArea"] == {
        "firstX": 8, "firstY": 67, "firstZ": -9,
        "secondX": 8, "secondY": 67, "secondZ": -9,
    }
    assert result["local"]["bindings"][0]["categoryOptions"]["standingPosition"]["y"] == 66
    inherited = result["inherited"]["bindings"][0]
    assert inherited["standingArea"]["firstX"] == -3
    assert inherited["standingArea"]["firstY"] == 66
    assert result["inherited"]["categoryOptions"]["standingPosition"]["y"] == 65
    assert inherited["categoryOptions"]["standingPosition"]["x"] == 8
    disabled = result["disabled"]["bindings"][0]
    assert disabled["standingArea"] is None
    assert disabled["categoryOptions"]["standingPosition"]["x"] == 8
    assert result["originalX"] == 8


@pytest.mark.skipif(NODE is None, reason="Node.js is required")
@pytest.mark.parametrize("position", [None, {"x": None, "y": 64, "z": 0},
                                    {"x": 0.5, "y": 64, "z": 0},
                                    {"x": 2147483648, "y": 64, "z": 0}])
def test_standing_position_rejects_missing_fractional_and_overflow_coordinates(
    position: dict[str, int | float | None] | None,
) -> None:
    result = run_model(ZONE_SETUP + """
        const value = model.resources(directory)[0];
        value.categoryOptions.specifyStanding = true;
        value.categoryOptions.standingPosition = """ + json.dumps(position) + """;
        try { model.payload(value); process.stdout.write('{}'); }
        catch (error) { process.stdout.write(JSON.stringify({error: error.message})); }
    """)
    assert "整数坐标" in result["error"]


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
def test_legacy_support_block_ids_display_on_separate_lines_and_round_trip() -> None:
    result = run_model(ZONE_SETUP + """
        binding.ignoredMarkerBlockIds = ['minecraft:stone', 'minecraft:dirt',
            'minecraft:polished_deepslate', 'minecraft:oak_planks', 'minecraft:obsidian'];
        const value = model.resources(directory)[0];
        process.stdout.write(JSON.stringify({draft: value.categoryOptions.supportBlock,
            saved: model.payload(value)}));
    """)
    ids = [
        "minecraft:stone", "minecraft:dirt", "minecraft:polished_deepslate",
        "minecraft:oak_planks", "minecraft:obsidian",
    ]
    draft = "\n".join(ids)
    assert result["draft"] == draft
    assert result["saved"]["categoryOptions"]["supportBlock"] == draft
    assert result["saved"]["bindings"][0]["ignoredMarkerBlockIds"] == ids


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
