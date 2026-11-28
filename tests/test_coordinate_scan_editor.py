from typing import Any

import pytest

from test_coordinate_binding_options_editor import editor_page
from test_coordinate_editor import NODE, run_model


SCAN_SETUP = """
    const templateId = '11111111-1111-4111-8111-111111111111';
    const total = {firstX: 0, firstY: 64, firstZ: 0,
        secondX: 9, secondY: 66, secondZ: 5};
    const template = {id: templateId, name: '多数母版', ruleId: 'DIRECT_BLOCK_ID',
        markerArea: {firstX: 1, firstY: 64, firstZ: 3, secondX: 1, secondY: 65, secondZ: 3},
        storageArea: {firstX: 1, firstY: 64, firstZ: 1, secondX: 1, secondY: 64, secondZ: 2},
        ignoredMarkerBlockIds: [], containerOverrides: [],
        optionsEnabled: false, categoryOptions: null,
        standingPlan: {enabled: false, points: []}};
    const scan = {area: total, dimension: 'minecraft:overworld', axis: 'X', step: 1,
        templateIndex: 1, sliceCount: 10, templates: [template],
        blocks: [{position: {x: 1, y: 64, z: 1}, partner: {x: 1, y: 64, z: 2},
            blockId: 'minecraft:chest', kind: 'chest', facing: 'east', turns: 0, upper: false}],
        canvasSize: {columns: 6, rows: 3},
        slices: Array.from({length: 10}, (_, index) => ({index,
            origin: {x: index, y: 64, z: 0}, reason: [0, 2].includes(index) ? '箱体不同' : ''}))};
    const options = {scopeMode: 'single', recognitionMode: 'marker', storageMethod: 'bulk',
        bulkStacks: 27, noCacheLocation: 'inside-box', cacheDestination: '',
        ignoreSupportBlock: false, supportBlock: ''};
    const effective = scan.slices.filter(slice => !slice.reason).map(slice => ({...template,
        id: 'effective-' + slice.index,
        markerArea: {...template.markerArea, firstX: slice.index, secondX: slice.index},
        storageArea: {...template.storageArea, firstX: slice.index, secondX: slice.index}}));
    const raw = {id: 'zone', name: '真实扫描', overallArea: total,
        categoryOptions: options, bindings: effective, expansion: null, scan};
    const directory = {categoryZones: [raw], catalog: {}};
"""


@pytest.mark.skipif(NODE is None, reason="Node.js is required")
def test_reopen_save_and_copy_use_the_separate_mother_and_preserve_skipped_slices() -> None:
    result = run_model(SCAN_SETUP + """
        const value = model.resources(directory)[0];
        const saved = model.payload(value);
        const duplicate = model.copy(value);
        duplicate.bindings[0].name = '副本母版';
        const copied = model.payload(duplicate);
        const reloaded = model.resources({categoryZones: [{...raw, scan: saved.scan}]})[0];
        process.stdout.write(JSON.stringify({value, saved, copied,
            reloaded: model.payload(reloaded), source: model.payload(value)}));
    """)
    assert result["value"]["setupMode"] == "scan"
    assert len(result["value"]["bindings"]) == 1
    assert result["value"]["bindings"][0]["markerRegion"]["first"]["x"] == 1
    assert "expansion" not in result["saved"]
    assert result["saved"]["scan"]["templateIndex"] == 1
    assert result["saved"]["scan"]["slices"][2]["reason"] == "箱体不同"
    assert result["saved"] == result["reloaded"] == result["source"]
    assert result["copied"]["scan"]["templates"][0]["name"] == "副本母版"
    assert result["saved"]["scan"]["templates"][0]["name"] == "多数母版"
    assert result["copied"]["scan"]["canvasSize"] == {"columns": 6, "rows": 3}


@pytest.mark.skipif(NODE is None, reason="Node.js is required")
def test_selection_change_rejects_old_scan_but_reversed_corners_remain_equivalent() -> None:
    result = run_model(SCAN_SETUP + """
        const value = model.resources(directory)[0];
        [value.overallRegion.first, value.overallRegion.second] =
            [value.overallRegion.second, value.overallRegion.first];
        const reversed = model.payload(value);
        value.overallRegion.first.x = 10;
        let error = '';
        try { model.payload(value); } catch (failure) { error = failure.message; }
        process.stdout.write(JSON.stringify({reversed, error}));
    """)
    assert result["reversed"]["scan"]["templateIndex"] == 1
    assert "重新扫描" in result["error"]


@pytest.mark.skipif(NODE is None, reason="Node.js is required")
def test_corrected_ranges_are_written_into_the_mother_without_reusing_effective_bindings() -> None:
    result = run_model(SCAN_SETUP + """
        const value = model.resources(directory)[0];
        value.bindings[0].markerRegion.first.z = 4;
        value.bindings[0].markerRegion.second.z = 4;
        process.stdout.write(JSON.stringify(model.payload(value)));
    """)
    assert result["scan"]["templates"][0]["markerArea"]["firstZ"] == 4
    assert result["bindings"][0]["markerFirstZ"] == 4
    assert result["scan"]["slices"][2]["reason"]


@pytest.mark.skipif(NODE is None, reason="Node.js is required")
def test_logical_display_slot_round_trips_until_its_physical_region_is_redrawn() -> None:
    result = run_model(SCAN_SETUP + """
        scan.markerAnchors = {[templateId]: [{x: 1, y: 64, z: 3}]};
        const value = model.resources(directory)[0];
        const saved = model.payload(value);
        const duplicate = model.payload(model.copy(value));
        const restored = model.resources({categoryZones: [{...raw, scan: saved.scan}]})[0];
        const reloaded = model.payload(restored);
        restored.bindings[0].markerRegion.first.z = 4;
        restored.bindings[0].markerRegion.second.z = 4;
        const redrawn = model.payload(restored);
        process.stdout.write(JSON.stringify({saved, duplicate, reloaded, redrawn}));
    """)
    assert result["saved"] == result["duplicate"] == result["reloaded"]
    assert result["saved"]["scan"]["markerAnchors"] == {
        "11111111-1111-4111-8111-111111111111": [{"x": 1, "y": 64, "z": 3}],
    }
    assert result["redrawn"]["scan"]["markerAnchors"] == {}
    assert result["redrawn"]["scan"]["templates"][0]["markerArea"]["firstZ"] == 4


def install_scan_service(page: Any) -> None:
    page.evaluate("() => {" + SCAN_SETUP + """
        const original = window.Stowling.request.bind(window.Stowling);
        window.__scanRequests = [];
        window.__scanResult = {scan, bindings: effective};
        window.Stowling.request = async (path, request = {}) => {
            if (path === '/v1/category-zones/scan') {
                const body = JSON.parse(request.body);
                window.__scanRequests.push(body);
                if (window.__holdScan) {
                    await new Promise(resolve => { window.__releaseScan = resolve; });
                }
                return structuredClone({scan: {...(body.scan || scan), slices: scan.slices},
                    bindings: effective});
            }
            if (path.startsWith('/v1/category-zones') && request.body) {
                const body = JSON.parse(request.body);
                window.__savedScan = structuredClone(body);
                const response = await original(path, request);
                const saved = window.__editorState.categoryZones.at(-1);
                saved.scan = body.scan;
                return structuredClone(window.__editorState);
            }
            return original(path, request);
        };
    }
    """)
    for corner, coordinates in (("first", (0, 64, 0)), ("second", (9, 66, 5))):
        for axis, number in zip(("x", "y", "z"), coordinates):
            page.locator(f"#category-overall-{corner}-{axis}").fill(str(number))


def scan_mode(page: Any) -> None:
    page.locator("#category-setup-trigger").click()
    page.locator('#category-setup-menu [data-category-value="scan"]').click()


@pytest.mark.parametrize("width,theme", [(1440, "green"), (390, "gray")])
def test_live_scan_is_corrected_confirmed_saved_and_reopened_with_one_mother(
    editor_page: Any, width: int, theme: str,
) -> None:
    page = editor_page
    page.set_viewport_size({"width": width, "height": 1100})
    page.evaluate("theme => document.documentElement.dataset.theme = theme", theme)
    install_scan_service(page)
    scan_mode(page)
    save = page.locator('#coordinate-form button[type="submit"]')
    assert save.is_disabled()
    assert page.locator("#category-details").is_hidden()
    page.locator("#category-scan-start").click()
    assert page.locator("#category-scan-map").is_visible()
    assert page.locator(".classic-board [data-piece]").count() == 1
    assert page.locator("#category-scan-results li").count() == 2
    assert save.is_disabled()
    page.locator("#category-generated-toggle").click()
    page.locator(".category-binding-toggle").click()
    for field in page.locator('[data-binding-region="marker"][data-binding-axis="z"]').all():
        field.fill("4")
    page.locator("#category-scan-apply").click()
    assert save.is_enabled()
    request = page.evaluate("window.__scanRequests.at(-1)")
    assert request["scan"]["templates"][0]["markerArea"]["firstZ"] == 4
    page.locator("#category-generated-toggle").click()
    page.locator("#coordinate-name").fill("扫描分类区")
    save.click()
    page.wait_for_function("window.__savedScan !== undefined")
    assert page.locator("#category-setup-mode").input_value() == "scan"
    assert page.locator("#category-scan-results li").count() == 2
    assert page.evaluate("window.__savedScan.expansion === undefined")
    page.locator("#duplicate-coordinate").click()
    assert page.locator("#category-scan-map").is_visible()
    assert page.locator("#category-scan-results li").count() == 2


def test_changed_selection_and_late_response_cannot_apply_to_another_range(editor_page: Any) -> None:
    page = editor_page
    install_scan_service(page)
    scan_mode(page)
    page.evaluate("window.__holdScan = true")
    page.locator("#category-scan-start").click()
    page.wait_for_function("Boolean(window.__releaseScan)")
    page.locator("#category-overall-second-x").fill("10")
    page.evaluate("window.__releaseScan()")
    assert page.locator("#category-scan-map").is_hidden()
    assert page.locator('#coordinate-form button[type="submit"]').is_disabled()


def test_discard_restores_manual_draft_and_hidden_details_do_not_trap_native_validation(
    editor_page: Any,
) -> None:
    page = editor_page
    install_scan_service(page)
    page.locator(".category-binding-name").fill("保留手动组")
    scan_mode(page)
    page.locator("#category-scan-start").click()
    page.locator("#category-scan-discard").click()
    page.locator("#category-setup-trigger").click()
    page.locator('#category-setup-menu [data-category-value="manual"]').click()
    assert page.locator(".category-binding-name").input_value() == "保留手动组"
    assert page.locator("#category-scan-panel").is_hidden()
