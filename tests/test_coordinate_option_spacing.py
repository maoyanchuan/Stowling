import re
from collections.abc import Iterator
from pathlib import Path
from typing import Any

import pytest


ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "src/main/resources/stowling-web"


@pytest.fixture(params=["template", "editor"])
def category_page(request: pytest.FixtureRequest) -> Iterator[Any]:
    playwright = pytest.importorskip("playwright.sync_api")
    with playwright.sync_playwright() as runtime:
        try:
            browser = runtime.chromium.launch()
        except playwright.Error as error:
            pytest.skip(f"Playwright Chromium is required: {error}")
        page = browser.new_page()
        source = ROOT / "docs/ui/settings-standard.html"
        if request.param == "editor":
            source = WEB / "coordinates.html"
        html = re.sub(r"<script\b[^>]*>[\s\S]*?</script>", "", source.read_text("utf-8"))
        html = re.sub(r"<link\b[^>]*>", "", html)
        page.set_content(html)
        if request.param == "editor":
            for name in ("styles.css", "workbench.css", "coordinate-editor.css"):
                page.add_style_tag(content=(WEB / name).read_text("utf-8"))
        else:
            for name in ("settings-theme.css", "template-shell.css",
                         "coordinate-template.css", "settings-controls.css"):
                page.add_style_tag(content=(ROOT / "docs/ui/shared" / name).read_text("utf-8"))
        # Isolate the real options markup without authentication or example-data scripts.
        page.evaluate("""template => {
            const options = document.querySelector('.category-options');
            if (template) {
                const panel = document.createElement('section');
                panel.id = 'template-coordinates-panel';
                panel.dataset.settingsTemplate = 'coordinates';
                document.body.replaceChildren(panel);
                panel.append(options);
            } else document.body.replaceChildren(options);
            const recognition = options.querySelector('.category-recognition-panel');
            if (recognition) recognition.hidden = false;
            options.querySelectorAll('.category-option-sub').forEach(row => {
                row.hidden = false;
                row.dataset.collapsed = 'false';
            });
        }""", request.param == "template")
        page.add_style_tag(content="""
            body {min-height: 0; padding: 16px;}
            .category-options {max-width: 700px; margin: 0 auto;}
        """)
        yield page
        browser.close()


@pytest.mark.parametrize("width", [900, 390])
@pytest.mark.parametrize("theme", ["green", "gray"])
def test_category_highlights_leave_space_around_labels_and_controls(
    category_page: Any, width: int, theme: str,
) -> None:
    page = category_page
    page.set_viewport_size({"width": width, "height": 1000})
    page.evaluate("theme => document.documentElement.dataset.theme = theme", theme)
    rows = page.locator(
        "div.category-option-row, .category-option-sub:not(.category-rule-panel)",
    )
    assert rows.count() > 0
    for row in rows.all():
        row.scroll_into_view_if_needed()
        bounds = row.bounding_box()
        label = row.locator(".category-option-label").bounding_box()
        control = row.locator(
            ":scope > .category-select-wrap, :scope > .category-option-actions, "
            ":scope > .field-label, :scope > .category-bulk-ratio, "
            ":scope > .coordinate-corner-fields"
        ).bounding_box()
        assert bounds is not None and label is not None and control is not None
        inset = row.evaluate("""row => {
            const style = getComputedStyle(row, '::before');
            return {top: parseFloat(style.top), bottom: parseFloat(style.bottom)};
        }""")
        sub = "category-option-sub" in (row.get_attribute("class") or "").split()
        expected_left = 30 if sub and width > 480 else 12
        assert label["x"] - bounds["x"] == pytest.approx(expected_left)
        assert bounds["x"] + bounds["width"] - control["x"] - control["width"] == (
            pytest.approx(12)
        )
        content_top = min(label["y"], control["y"])
        content_bottom = max(label["y"] + label["height"], control["y"] + control["height"])
        assert content_top - bounds["y"] - inset["top"] >= 6
        assert bounds["y"] + bounds["height"] - inset["bottom"] - content_bottom >= 6
        row.hover()
        assert row.bounding_box() == bounds
        assert row.locator(".category-option-label").bounding_box() == label
        assert page.locator("body").evaluate(
            "body => body.scrollWidth <= window.innerWidth"
        )
