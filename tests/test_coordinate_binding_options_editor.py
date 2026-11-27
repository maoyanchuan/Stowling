from collections.abc import Iterator
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

import pytest


WEB = Path(__file__).resolve().parents[1] / "src/main/resources/stowling-web"
MOCK_COMMON = """
(() => {
    const handlers = [];
    let revision = 0;
    const state = {revision: 'r0', coordinates: [{id: 'cache', name: '缓存',
        x: 0, y: 64, z: 0, radius: null}], regions: [], categoryZones: [],
        catalog: {folders: [], warehouses: [], placements: {}, order: []}, references: {}};
    window.__editorState = state;
    const copy = value => structuredClone(value);
    window.confirm = () => true;
    window.Stowling = {
        isConnected: () => true,
        bindCoordinateInputs() {},
        onConnected(handler) { handlers.push(handler); },
        handleError(error) { window.__editorError = error.message; },
        mountPage() {
            document.querySelector('#workspace').setAttribute('aria-hidden', 'false');
            document.querySelector('#auth-shell').hidden = true;
            Promise.all(handlers.map(handler => handler())).then(() => {
                window.__editorConnected = true;
            });
        },
        async request(path, options = {}) {
            if (path === '/v1/coordinate-resources') return copy(state);
            const body = JSON.parse(options.body);
            const id = options.method === 'POST' ? 'zone-' + ++revision : path.split('/').pop();
            const area = (binding, prefix) => Object.fromEntries(['First', 'Second'].flatMap(corner =>
                ['X', 'Y', 'Z'].map(axis => [corner.toLowerCase() + axis,
                    binding[prefix + corner + axis]])));
            const zone = {id, name: body.name, categoryOptions: body.categoryOptions,
                overallArea: body.overallArea,
                bindings: body.bindings.map((binding, index) => ({...copy(binding),
                    id: id + '-' + index, markerArea: area(binding, 'marker'),
                    storageArea: area(binding, 'storage')})),
                expansion: body.expansion ? {area: copy(body.expansion),
                    direction: body.expansion.direction, step: body.expansion.step,
                    templateCount: body.bindings.length} : null};
            state.categoryZones = state.categoryZones.filter(value => value.id !== id);
            state.categoryZones.push(zone);
            state.revision = 'r' + ++revision;
            return copy(state);
        }
    };
})();
"""


@pytest.fixture
def editor_page() -> Iterator[Any]:
    playwright = pytest.importorskip("playwright.sync_api")
    with playwright.sync_playwright() as runtime:
        try:
            browser = runtime.chromium.launch()
        except playwright.Error as error:
            pytest.skip(f"Playwright Chromium is required: {error}")
        page = browser.new_page(viewport={"width": 1440, "height": 1000})
        page.emulate_media(reduced_motion="reduce")

        def serve(route: Any) -> None:
            name = urlparse(route.request.url).path.removeprefix("/")
            if name == "common.js":
                route.fulfill(body=MOCK_COMMON, content_type="text/javascript")
                return
            file = WEB / name
            if not file.is_file():
                route.fulfill(status=404)
                return
            content_type = {".html": "text/html", ".css": "text/css", ".js": "text/javascript"}
            route.fulfill(body=file.read_bytes(),
                          content_type=content_type.get(file.suffix, "application/octet-stream"))

        page.route("http://stowling.test/**", serve)
        page.goto("http://stowling.test/coordinates.html")
        page.wait_for_function("window.__editorConnected === true")
        page.locator("#add-coordinate").click()
        page.locator("#coordinate-kind-trigger").click()
        page.locator('[data-coordinate-kind="category-zone"]').click()
        page.locator("#category-overall-second-x").fill("3")
        page.locator(".category-binding-toggle").click()
        yield page
        browser.close()


def choose(root: Any, key: str, value: str) -> None:
    control = root.locator(f'[data-category-select="{key}"]')
    control.locator(".category-select-trigger").click()
    control.locator(f'[data-category-value="{value}"]').click()


def selected(root: Any, key: str) -> str:
    return root.locator(f'[data-category-select="{key}"] select').input_value()


@pytest.mark.parametrize("scope", ["global", "binding"])
@pytest.mark.parametrize("width", [1440, 390])
@pytest.mark.parametrize("theme", ["green", "gray"])
def test_support_blocks_accept_multiline_input_without_submitting(
    editor_page: Any, scope: str, width: int, theme: str,
) -> None:
    page = editor_page
    page.set_viewport_size({"width": width, "height": 1000})
    page.evaluate("theme => document.documentElement.dataset.theme = theme", theme)
    root = page.locator(".category-options").first
    if scope == "binding":
        root = page.locator(".category-binding-card").first
        root.locator(".category-binding-enabled").check()
    open_recognition(root)
    enabled = root.locator('[id$="category-ignore-support"]')
    expand = root.locator('[id$="category-support-expand"]')
    field = root.locator('[id$="category-support-block"]')
    draft = (
        "minecraft:stone\nminecraft:dirt\nminecraft:polished_deepslate\n"
        "minecraft:oak_planks\nminecraft:obsidian"
    )
    enabled.check()
    field.fill("minecraft:stone")
    field.press("End")
    field.press("Enter")
    page.keyboard.insert_text(draft.split("\n", 1)[1])
    assert field.input_value() == draft
    assert page.evaluate("window.__editorState.categoryZones.length") == 0
    assert field.evaluate("node => node.labels[0].textContent.includes('每行填写一个方块 ID')")
    bounds = field.bounding_box()
    assert bounds is not None and bounds["height"] >= 86
    assert bounds["x"] >= 0 and bounds["x"] + bounds["width"] <= width
    expand.click()
    assert field.is_hidden()
    assert enabled.is_checked()
    expand.click()
    assert field.input_value() == draft
    enabled.uncheck()
    assert field.is_disabled()
    enabled.check()
    assert field.input_value() == draft


@pytest.mark.parametrize("independent", [False, True])
@pytest.mark.parametrize("ignore_support", [False, True])
def test_support_block_lines_keep_inherited_saved_and_copied_drafts(
    editor_page: Any, independent: bool, ignore_support: bool,
) -> None:
    page = editor_page
    global_options = page.locator(".category-options").first
    global_draft = "minecraft:stone\nminecraft:dirt"
    local_draft = (
        "minecraft:glass\nminecraft:oak_planks\nminecraft:polished_deepslate\n"
        "minecraft:obsidian\nminecraft:granite"
    )
    open_recognition(global_options)
    global_options.locator('[id$="category-ignore-support"]').check()
    global_options.locator('[id$="category-support-block"]').fill(global_draft)
    card = page.locator(".category-binding-card").first
    override = card.locator(".category-binding-enabled")
    override.check()
    open_recognition(card)
    local_field = card.locator('[id$="category-support-block"]')
    assert local_field.input_value() == global_draft
    local_field.fill(local_draft)
    card.locator(".category-binding-toggle").click()
    card.locator(".category-binding-toggle").click()
    assert local_field.input_value() == local_draft
    card.locator('[id$="category-ignore-support"]').set_checked(ignore_support)
    override.uncheck()
    override.check()
    assert local_field.input_value() == local_draft
    override.set_checked(independent)
    global_options.locator('[id$="category-ignore-support"]').set_checked(ignore_support)
    page.locator('#coordinate-form button[type="submit"]').click()
    page.wait_for_function("window.__editorState.categoryZones.length === 1")
    saved = page.evaluate("window.__editorState.categoryZones[0]")
    assert saved["categoryOptions"]["supportBlock"] == global_draft
    binding = saved["bindings"][0]
    assert binding["categoryOptions"]["supportBlock"] == local_draft
    assert binding["optionsEnabled"] is independent
    effective_draft = local_draft if independent else global_draft
    assert binding["ignoredMarkerBlockIds"] == (
        effective_draft.splitlines() if ignore_support else []
    )
    page.locator("#duplicate-coordinate").click()
    open_recognition(global_options)
    global_options.locator('[id$="category-ignore-support"]').check()
    assert global_options.locator('[id$="category-support-block"]').input_value() == global_draft
    card = page.locator(".category-binding-card").first
    assert card.locator(".category-binding-enabled").is_checked() is independent
    card.locator(".category-binding-toggle").click()
    card.locator(".category-binding-enabled").check()
    open_recognition(card)
    card.locator('[id$="category-ignore-support"]').check()
    assert card.locator('[id$="category-support-block"]').input_value() == local_draft


@pytest.mark.parametrize("width", [1440, 900, 761])
@pytest.mark.parametrize("theme", ["green", "gray"])
def test_directory_fills_the_left_column_without_stretching_rows(
    editor_page: Any, width: int, theme: str,
) -> None:
    page = editor_page
    page.set_viewport_size({"width": width, "height": 1000})
    page.evaluate("theme => document.documentElement.dataset.theme = theme", theme)
    bounds = page.locator("#coordinate-list").evaluate("""list => {
        const library = list.closest('.coordinate-library').getBoundingClientRect();
        const editor = document.querySelector('.coordinate-editor').getBoundingClientRect();
        const footer = document.querySelector('#library-footer').getBoundingClientRect();
        const rect = list.getBoundingClientRect();
        const root = list.querySelector('.coordinate-root-list').getBoundingClientRect();
        return {height: rect.height, footerGap: footer.top - rect.bottom,
            unusedSpace: rect.bottom - root.bottom,
            rowHeights: Array.from(list.querySelectorAll('.coordinate-item'),
                item => item.getBoundingClientRect().height),
            libraryBottom: library.bottom, editorBottom: editor.bottom};
    }""")
    assert bounds["height"] > 360
    assert bounds["footerGap"] == pytest.approx(16, abs=1)
    assert bounds["unusedSpace"] > 100
    assert bounds["rowHeights"] and all(height < 100 for height in bounds["rowHeights"])
    assert bounds["libraryBottom"] == pytest.approx(bounds["editorBottom"], abs=1)


def extend_directory(page: Any, count: int) -> None:
    page.locator("#coordinate-list").evaluate("""(list, count) => {
        const root = list.querySelector('.coordinate-root-list');
        const item = root.querySelector('.coordinate-item');
        for (let index = 0; index < count; index += 1) {
            const copy = item.cloneNode(true);
            copy.removeAttribute('id');
            copy.removeAttribute('aria-current');
            root.append(copy);
        }
    }""", count)


@pytest.mark.parametrize("theme", ["green", "gray"])
def test_long_directory_scrolls_inside_the_full_column(editor_page: Any, theme: str) -> None:
    page = editor_page
    page.evaluate("theme => document.documentElement.dataset.theme = theme", theme)
    library = page.locator(".coordinate-library")
    original_height = library.evaluate("node => node.getBoundingClientRect().height")
    listing = page.locator("#coordinate-list")
    extend_directory(page, 60)
    assert library.evaluate("node => node.getBoundingClientRect().height") == pytest.approx(
        original_height, abs=1,
    )
    assert listing.evaluate("node => node.scrollHeight > node.clientHeight")
    listing.locator(".coordinate-item").last.scroll_into_view_if_needed()
    assert listing.evaluate("""list => {
        const item = list.querySelector('.coordinate-root-list').lastElementChild;
        const rect = item.getBoundingClientRect();
        const viewport = list.getBoundingClientRect();
        return list.scrollTop > 0 && rect.top >= viewport.top - 1
            && rect.bottom <= viewport.bottom + 1;
    }""")


@pytest.mark.parametrize("width", [760, 390])
@pytest.mark.parametrize("theme", ["green", "gray"])
def test_narrow_directory_expands_with_its_content_above_the_editor(
    editor_page: Any, width: int, theme: str,
) -> None:
    page = editor_page
    page.set_viewport_size({"width": width, "height": 844})
    page.evaluate("theme => document.documentElement.dataset.theme = theme", theme)
    extend_directory(page, 15)
    bounds = page.locator("#coordinate-list").evaluate("""list => {
        const library = list.closest('.coordinate-library').getBoundingClientRect();
        const editor = document.querySelector('.coordinate-editor').getBoundingClientRect();
        return {height: list.clientHeight, contentHeight: list.scrollHeight,
            libraryBottom: library.bottom, editorTop: editor.top,
            pageWidth: document.documentElement.scrollWidth, viewportWidth: innerWidth};
    }""")
    assert bounds["height"] > 250
    assert bounds["height"] == pytest.approx(bounds["contentHeight"], abs=1)
    assert bounds["editorTop"] >= bounds["libraryBottom"]
    assert bounds["pageWidth"] <= bounds["viewportWidth"]


@pytest.mark.parametrize("independent", [False, True])
def test_bulk_ratio_inherits_and_keeps_saved_and_copied_drafts(
    editor_page: Any, independent: bool,
) -> None:
    page = editor_page
    global_options = page.locator(".category-options").first
    global_input = page.locator("#category-bulk-stacks")
    assert global_input.input_value() == "27"
    assert global_input.is_hidden()
    assert global_input.is_disabled()
    choose(global_options, "storageMethod", "mixed")
    assert global_input.is_visible()
    global_input.fill("13")
    card = page.locator(".category-binding-card").first
    card.locator(".category-binding-enabled").check()
    local_input = card.locator('[id$="category-bulk-stacks"]')
    assert local_input.input_value() == "13"
    local_input.fill("9")
    for method in ("box", "bulk", "mixed"):
        choose(card, "storageMethod", method)
        assert local_input.input_value() == "9"
        assert local_input.is_visible() is (method == "mixed")
        assert local_input.is_disabled() is (method != "mixed")
    card.locator(".category-binding-enabled").set_checked(independent)
    page.locator('#coordinate-form button[type="submit"]').click()
    page.wait_for_function("window.__editorState.categoryZones.length === 1")
    saved = page.evaluate("window.__editorState.categoryZones[0]")
    assert saved["categoryOptions"]["bulkStacks"] == 13
    assert saved["bindings"][0]["categoryOptions"]["bulkStacks"] == 9
    assert saved["bindings"][0]["storagePolicy"]["looseSlots"] == (9 if independent else 13)
    page.locator("#duplicate-coordinate").click()
    card = page.locator(".category-binding-card").first
    card.locator(".category-binding-toggle").click()
    card.locator(".category-binding-enabled").check()
    assert card.locator('[id$="category-bulk-stacks"]').input_value() == "9"
    assert page.locator("#category-bulk-stacks").input_value() == "13"


@pytest.mark.parametrize("stacks", ["0", "54"])
def test_bulk_ratio_accepts_boundary_values(editor_page: Any, stacks: str) -> None:
    page = editor_page
    choose(page.locator(".category-options").first, "storageMethod", "mixed")
    page.locator("#category-bulk-stacks").fill(stacks)
    page.locator('#coordinate-form button[type="submit"]').click()
    page.wait_for_function("window.__editorState.categoryZones.length === 1")
    assert page.evaluate(
        "window.__editorState.categoryZones[0].bindings[0].storagePolicy.looseSlots"
    ) == int(stacks)


@pytest.mark.parametrize("stacks", ["", "-1", "55", "1.5"])
def test_bulk_ratio_blocks_invalid_input_without_saving(editor_page: Any, stacks: str) -> None:
    page = editor_page
    choose(page.locator(".category-options").first, "storageMethod", "mixed")
    page.locator("#category-bulk-stacks").fill(stacks)
    page.locator('#coordinate-form button[type="submit"]').click()
    assert page.evaluate("window.__editorState.categoryZones.length") == 0
    if not stacks:
        assert "散货占比" in page.locator("#editor-status").inner_text()
    else:
        assert not page.locator("#category-bulk-stacks").evaluate("node => node.validity.valid")


def test_first_enable_uses_global_values_and_groups_stay_independent(editor_page: Any) -> None:
    page = editor_page
    global_options = page.locator(".category-options").first
    first = page.locator(".category-binding-card").first
    assert not first.locator(".category-binding-enabled").is_checked()
    assert first.locator(".category-binding-options").is_hidden()
    choose(global_options, "storageMethod", "mixed")
    first.locator(".category-binding-enabled").check()
    assert selected(first, "storageMethod") == "mixed"
    choose(first, "storageMethod", "box")
    page.locator("#add-category-binding").click()
    second = page.locator(".category-binding-card").nth(1)
    second.locator(".category-binding-enabled").check()
    assert selected(second, "storageMethod") == "mixed"
    assert selected(first, "storageMethod") == "box"
    assert selected(global_options, "storageMethod") == "mixed"
    assert page.evaluate("""() => {
        const ids = Array.from(document.querySelectorAll('[id]'), node => node.id);
        return ids.length === new Set(ids).size;
    }""")


def test_disabling_retains_values_and_does_not_collapse_or_change_coordinates(
    editor_page: Any,
) -> None:
    page = editor_page
    card = page.locator(".category-binding-card").first
    enabled = card.locator(".category-binding-enabled")
    coordinate = card.locator('[data-binding-axis="x"]').first
    coordinate.fill("1")
    enabled.check()
    choose(card, "storageMethod", "box")
    choose(card, "noCacheLocation", "coordinate:cache")
    enabled.uncheck()
    assert card.locator(".category-binding-options").is_hidden()
    assert card.get_attribute("data-collapsed") == "false"
    choose(page.locator(".category-options").first, "storageMethod", "mixed")
    enabled.check()
    assert selected(card, "storageMethod") == "box"
    assert selected(card, "noCacheLocation") == "coordinate:cache"
    assert coordinate.input_value() == "1"
    assert card.locator(".category-binding-override-status").inner_text() == "独立设置"


def test_group_menus_support_keyboard_and_fast_override_toggles(editor_page: Any) -> None:
    page = editor_page
    card = page.locator(".category-binding-card").first
    card.locator(".category-binding-enabled").check()
    control = card.locator('[data-category-select="storageMethod"]')
    control.locator(".category-select-trigger").focus()
    page.keyboard.press("ArrowDown")
    page.keyboard.press("ArrowDown")
    page.keyboard.press("Enter")
    assert selected(card, "storageMethod") == "mixed"
    assert control.locator(".category-select-trigger").evaluate(
        "node => node === document.activeElement"
    )
    page.emulate_media(reduced_motion="no-preference")
    card.evaluate("""async card => {
        const enabled = card.querySelector('.category-binding-enabled');
        for (const checked of [false, true, false, true]) {
            enabled.checked = checked;
            enabled.dispatchEvent(new Event('change', {bubbles: true}));
        }
        for (const animation of document.getAnimations()) animation.finish();
        await Promise.resolve();
    }""")
    assert card.locator(".category-binding-options").is_visible()
    assert card.get_attribute("data-collapsed") == "false"
    assert selected(card, "storageMethod") == "mixed"


@pytest.mark.parametrize("enabled", [False, True])
def test_saved_and_copied_bindings_retain_profiles(editor_page: Any, enabled: bool) -> None:
    page = editor_page
    card = page.locator(".category-binding-card").first
    card.locator(".category-binding-enabled").check()
    choose(card, "scopeMode", "selection")
    choose(card, "storageMethod", "box")
    card.locator(".category-binding-enabled").set_checked(enabled)
    page.locator('#coordinate-form button[type="submit"]').click()
    page.wait_for_function("window.__editorState.categoryZones.length === 1")
    page.locator("#duplicate-coordinate").click()
    card = page.locator(".category-binding-card").first
    assert card.locator(".category-binding-enabled").is_checked() is enabled
    card.locator(".category-binding-toggle").click()
    card.locator(".category-binding-enabled").check()
    assert selected(card, "storageMethod") == "box"
    assert selected(card, "scopeMode") == "selection"
    assert page.locator("#category-storage-method").input_value() == "bulk"


def open_recognition(root: Any) -> None:
    toggle = root.locator('[id$="category-recognition-expand"]')
    if toggle.get_attribute("aria-expanded") == "false":
        toggle.click()
