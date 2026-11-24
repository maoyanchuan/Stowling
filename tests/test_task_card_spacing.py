import re
from io import BytesIO
from collections.abc import Iterator
from pathlib import Path
from typing import Any

import pytest


ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "src/main/resources/stowling-web"


@pytest.fixture
def task_page() -> Iterator[Any]:
    playwright = pytest.importorskip("playwright.sync_api")
    with playwright.sync_playwright() as runtime:
        try:
            browser = runtime.chromium.launch()
        except playwright.Error as error:
            pytest.skip(f"Playwright Chromium is required: {error}")
        page = browser.new_page()
        html = (WEB / "tasks.html").read_text("utf-8")
        html = re.sub(r"<script\b[^>]*>[\s\S]*?</script>", "", html)
        html = re.sub(r"<link\b[^>]*>", "", html)
        page.set_content(html)
        for name in ("styles.css", "workbench.css", "pickup-task-settings.css"):
            page.add_style_tag(content=(WEB / name).read_text("utf-8"))
        page.evaluate("""() => {
            const display = document.querySelector('#task-display-area');
            document.body.replaceChildren(display);
            display.querySelectorAll(':scope > .field-message, :scope > .move-placeholder')
                .forEach(node => { node.hidden = true; });
        }""")
        yield page
        browser.close()


@pytest.mark.parametrize("width", [1280, 390])
@pytest.mark.parametrize("theme", ["green", "gray"])
@pytest.mark.parametrize("saved_list, template", [
    ("move-task-list", "move-task-draft"),
    ("auto-task-list", "auto-task-draft"),
    ("auto-eat-list", "auto-eat-draft"),
    ("material-task-list", "material-editor"),
    ("refresh-cache-list", "refresh-cache-editor"),
])
def test_new_move_card_keeps_gap_after_any_saved_task(
    task_page: Any, width: int, theme: str, saved_list: str, template: str,
) -> None:
    page = task_page
    page.set_viewport_size({"width": width, "height": 1200})
    page.evaluate("""({theme, savedList, template}) => {
        document.documentElement.dataset.theme = theme;
        const saved = document.getElementById(template).cloneNode(true);
        saved.removeAttribute('id');
        saved.querySelectorAll('[id]').forEach(node => node.removeAttribute('id'));
        saved.classList.remove('is-draft');
        saved.hidden = false;
        document.getElementById(savedList).append(saved);
    }""", {"theme": theme, "savedList": saved_list, "template": template})
    saved = page.locator(f"#{saved_list} > article").bounding_box()
    assert saved is not None
    draft = page.locator("#move-task-draft")

    for expanded in (False, True, False):
        # Reopening a draft and changing its disclosure must retain the same card gap.
        draft.evaluate("card => { card.hidden = true; }")
        draft.evaluate("""(card, expanded) => {
            card.hidden = false;
            card.querySelector('#move-draft-body').hidden = !expanded;
            card.querySelector('form').hidden = false;
            card.querySelector('button').setAttribute('aria-expanded', String(expanded));
        }""", expanded)
        bounds = draft.bounding_box()
        assert bounds is not None
        assert bounds["y"] - saved["y"] - saved["height"] == pytest.approx(12, abs=0.5)
        assert bounds["x"] == pytest.approx(saved["x"])
        assert bounds["width"] == pytest.approx(saved["width"])


@pytest.mark.parametrize("width", [1280, 390])
@pytest.mark.parametrize("theme", ["green", "gray"])
@pytest.mark.parametrize("saved", [False, True])
def test_expanded_pickup_keeps_bottom_corners_without_clipping_menus(
    task_page: Any, width: int, theme: str, saved: bool,
) -> None:
    image = pytest.importorskip("PIL.Image")
    page = task_page
    page.set_viewport_size({"width": width, "height": 1400})
    page.evaluate("""({theme, saved}) => {
        document.documentElement.dataset.theme = theme;
        const display = document.querySelector('#task-display-area');
        const card = document.querySelector('#auto-task-draft');
        display.replaceChildren(card);
        display.style.background = 'rgb(245, 113, 80)';
        card.className = saved ? 'pickup-task-module'
            : 'move-saved-task is-draft pickup-task-module';
        card.hidden = false;
        const body = card.querySelector('#auto-draft-body');
        body.classList.add('pickup-task-body');
        if (saved) body.classList.remove('move-task-body');
        body.hidden = false;
        const form = body.querySelector('form');
        form.hidden = false;
        form.dataset.kind = 'PICKUP';
        card.querySelector('#toggle-auto-draft').setAttribute('aria-expanded', 'true');
    }""", {"theme": theme, "saved": saved})
    card = page.locator("#auto-task-draft")
    body = page.locator("#auto-draft-body")
    styles = body.evaluate("""node => {
        const style = getComputedStyle(node);
        return {left: parseFloat(style.borderBottomLeftRadius),
            right: parseFloat(style.borderBottomRightRadius),
            background: style.backgroundColor,
            overflow: getComputedStyle(node.parentElement).overflow};
    }""")
    expected_radius = 17 if theme == "green" else 13
    assert styles == {
        "left": expected_radius,
        "right": expected_radius,
        "background": "rgba(0, 0, 0, 0)",
        "overflow": "visible",
    }
    pixels = image.open(BytesIO(card.screenshot())).convert("RGB")
    assert pixels.getpixel((pixels.width - 3, pixels.height - 3)) == (245, 113, 80)

    # A menu extending past the card must remain visible in its bottom-right corner.
    body.evaluate("""node => {
        const menu = document.createElement('div');
        menu.id = 'corner-menu';
        menu.style.cssText = 'position:absolute;right:0;bottom:-24px;width:48px;height:48px;'
            + 'z-index:10;background:rgb(20,80,160)';
        node.append(menu);
    }""")
    assert page.locator("#corner-menu").evaluate("""node => {
        const bounds = node.getBoundingClientRect();
        return document.elementFromPoint(bounds.right - 4, bounds.bottom - 4) === node;
    }""")
