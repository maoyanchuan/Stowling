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


@pytest.mark.parametrize("enabled", [False, True])
def test_standing_switch_and_arrow_preserve_saved_and_copied_drafts(
    editor_page: Any, enabled: bool,
) -> None:
    page = editor_page
    global_options = page.locator(".category-options").first
    toggle = global_options.locator('[id$="category-specify-standing"]')
    assert not toggle.is_checked()
    toggle.check()
    for axis, value in {"x": "-3", "y": "65", "z": "7"}.items():
        global_options.locator(f'[id$="category-standing-{axis}"]').fill(value)
    arrow = global_options.locator('[id$="category-standing-expand"]')
    arrow.click()
    assert toggle.is_checked()
    assert global_options.locator('[id$="category-standing-x"]').is_hidden()
    arrow.click()
    assert global_options.locator('[id$="category-standing-x"]').input_value() == "-3"

    card = page.locator(".category-binding-card").first
    card.locator(".category-binding-enabled").check()
    local_x = card.locator('[id$="category-standing-x"]')
    assert local_x.input_value() == "-3"
    local_x.fill("8")
    card.locator('[id$="category-specify-standing"]').set_checked(enabled)
    card.locator(".category-binding-enabled").uncheck()
    card.locator(".category-binding-enabled").check()
    assert card.locator('[id$="category-specify-standing"]').is_checked() is enabled
    if enabled:
        card.locator('[id$="category-standing-expand"]').click()
    page.locator('#coordinate-form button[type="submit"]').click()
    page.wait_for_function("window.__editorState.categoryZones.length === 1")
    saved = page.evaluate("window.__editorState.categoryZones[0]")
    binding = saved["bindings"][0]
    assert binding["categoryOptions"]["standingPosition"]["x"] == 8
    assert saved["categoryOptions"]["standingPosition"]["x"] == -3
    assert binding["standingArea"] == ({
        "firstX": 8, "firstY": 66, "firstZ": 7,
        "secondX": 8, "secondY": 66, "secondZ": 7,
    } if enabled else None)
    page.locator("#duplicate-coordinate").click()
    card = page.locator(".category-binding-card").first
    card.locator(".category-binding-toggle").click()
    assert card.locator('[id$="category-specify-standing"]').is_checked() is enabled
    assert card.locator('[id$="category-standing-x"]').input_value() == "8"


def test_standing_submenu_reverses_without_losing_values_or_wrapping_twice(
    editor_page: Any,
) -> None:
    page = editor_page
    page.locator("#category-specify-standing").check()
    page.locator("#category-standing-x").fill("23")
    page.emulate_media(reduced_motion="no-preference")
    result = page.locator("#category-standing-row").evaluate("""async row => {
        const arrow = document.querySelector('#category-standing-expand');
        const panel = row.parentElement;
        arrow.click();
        const close = document.getAnimations().find(a => a.effect.target === panel);
        close.pause();
        close.currentTime = 40;
        const before = panel.getBoundingClientRect().height;
        arrow.click();
        const open = document.getAnimations().find(a => a.effect.target === panel);
        open.pause();
        open.currentTime = 0;
        const after = panel.getBoundingClientRect().height;
        open.finish();
        await Promise.resolve();
        return {before, after, hidden: panel.hidden, inert: panel.inert,
            nested: row.closest('.category-options')
                .querySelectorAll('.category-option-reveal .category-option-reveal').length};
    }""")
    assert result["after"] == pytest.approx(result["before"], abs=0.1)
    assert not result["hidden"]
    assert not result["inert"]
    assert result["nested"] == 0
    assert page.locator("#category-standing-x").input_value() == "23"
