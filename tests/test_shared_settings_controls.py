from collections.abc import Iterator
from pathlib import Path
from typing import Any

import pytest


ROOT = Path(__file__).resolve().parents[1]
SAMPLES = {
    "task": {
        "file": "settings-standard.html",
        "row": ".sample-strategy-row",
        "label": ".sample-row-label",
        "trigger": "#sample-strategy-trigger",
        "menu": "#sample-strategy-menu",
        "value": "#sample-strategy",
        "switch": "#sample-enabled",
        "height": 82,
        "font": "16px",
    },
    "coordinate": {
        "file": "settings-standard.html",
        "row": ".category-option-row",
        "label": ".category-option-label",
        "trigger": "#category-scope-mode-trigger",
        "menu": "#category-scope-mode-menu",
        "value": "#category-scope-mode",
        "switch": "#category-multi-bindings",
        "height": 62,
        "font": "13px",
    },
}


@pytest.fixture(params=list(SAMPLES))
def sample(request: pytest.FixtureRequest) -> Iterator[tuple[Any, dict[str, Any]]]:
    playwright = pytest.importorskip("playwright.sync_api")
    with playwright.sync_playwright() as runtime:
        browser = runtime.chromium.launch()
        page = browser.new_page(viewport={"width": 1440, "height": 1100})
        page.emulate_media(reduced_motion="reduce")
        errors: list[str] = []
        page.on("pageerror", lambda error: errors.append(str(error)))
        config = SAMPLES[request.param]
        page.goto((ROOT / "docs/ui" / config["file"]).as_uri()
                  + ("#coordinates" if request.param == "coordinate" else "#task"))
        assert errors == []
        yield page, config
        browser.close()


@pytest.mark.parametrize("theme", ["green", "gray"])
def test_shared_controls_keep_page_density_and_offline_dependencies(
    sample: tuple[Any, dict[str, Any]], theme: str,
) -> None:
    page, config = sample
    page.evaluate("theme => document.documentElement.dataset.theme = theme", theme)
    assert page.evaluate("typeof StowlingSettingsControls.createReveal") == "function"
    styles = page.evaluate("""() => [...document.styleSheets]
        .map(sheet => sheet.href).filter(Boolean)""")
    assert any(source.endswith("/settings-theme.css") for source in styles)
    assert any(source.endswith("/settings-controls.css") for source in styles)
    assert all(source.startswith("file:") for source in styles)
    trigger = page.locator(config["trigger"])
    assert trigger.evaluate("node => node.getBoundingClientRect().height") == 42
    assert page.locator(config["switch"]).evaluate("""node => ({
        width:node.getBoundingClientRect().width, height:node.getBoundingClientRect().height
    })""") == {"width": 56, "height": 32}
    row = page.locator(config["row"]).first
    assert row.evaluate("node => node.getBoundingClientRect().height") == config["height"]
    assert row.locator(config["label"]).first.evaluate(
        "node => getComputedStyle(node).fontSize"
    ) == config["font"]


def test_single_select_uses_the_same_keyboard_and_focus_contract(
    sample: tuple[Any, dict[str, Any]],
) -> None:
    page, config = sample
    trigger = page.locator(config["trigger"])
    before = page.locator(config["value"]).input_value()
    trigger.focus()
    page.keyboard.press("ArrowDown")
    page.keyboard.press("ArrowDown")
    page.keyboard.press("Enter")
    assert page.locator(config["value"]).input_value() != before
    assert trigger.get_attribute("aria-expanded") == "false"
    assert trigger.evaluate("node => node === document.activeElement")
    trigger.click()
    page.keyboard.press("Escape")
    assert trigger.evaluate("node => node === document.activeElement")
    assert page.locator(config["menu"]).is_hidden()


def test_tab_leaves_the_menu_without_forcing_focus_back_to_the_trigger(
    sample: tuple[Any, dict[str, Any]],
) -> None:
    page, config = sample
    trigger = page.locator(config["trigger"])
    trigger.focus()
    page.keyboard.press("ArrowDown")
    page.keyboard.press("Tab")
    assert trigger.get_attribute("aria-expanded") == "false"
    assert not trigger.evaluate("node => node === document.activeElement")


@pytest.mark.parametrize("width", [390, 1440])
def test_open_menu_stays_above_later_settings_rows(
    sample: tuple[Any, dict[str, Any]], width: int,
) -> None:
    page, config = sample
    page.set_viewport_size({"width": width, "height": 1100})
    page.locator(config["trigger"]).click()
    menu = page.locator(config["menu"])
    assert menu.evaluate("""menu => {
        const option = menu.querySelector('[role="option"]');
        const rect = option.getBoundingClientRect();
        const hit = document.elementFromPoint(rect.x + 12, rect.y + rect.height / 2);
        return option.contains(hit);
    }""")


def test_shared_reveal_cancels_old_callbacks_and_keeps_owners_independent(
    sample: tuple[Any, dict[str, Any]],
) -> None:
    page, _ = sample
    page.emulate_media(reduced_motion="no-preference")
    result = page.evaluate("""async () => {
        const first = document.createElement('div');
        const second = document.createElement('div');
        first.textContent = 'first draft';
        second.textContent = 'second draft';
        document.body.append(first, second);
        const reveal = StowlingSettingsControls.createReveal();
        const other = StowlingSettingsControls.createReveal();
        await reveal(first, false);
        await other(second, true);
        const opening = reveal(first, true);
        const duration = first.getAnimations()[0].effect.getTiming().duration;
        const repeated = opening === reveal(first, true);
        const closing = reveal(first, false);
        const closeDuration = first.getAnimations()[0].effect.getTiming().duration;
        await reveal(first, true);
        await Promise.all([opening, closing]);
        return {duration, closeDuration, repeated, hidden:first.hidden,
            inert:first.inert, otherHidden:second.hidden, otherInert:second.inert,
            overflow:first.style.overflow, text:first.textContent};
    }""")
    assert result == {
        "duration": 200, "closeDuration": 150, "repeated": True,
        "hidden": False, "inert": False, "otherHidden": False, "otherInert": False,
        "overflow": "", "text": "first draft",
    }
