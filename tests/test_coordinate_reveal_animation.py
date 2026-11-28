import re
from collections.abc import Iterator
from pathlib import Path
from typing import Any

import pytest


ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "src/main/resources/stowling-web"
TEMPLATE = ROOT / "docs/ui/settings-standard.html"


@pytest.fixture(params=["template", "editor"])
def animation_page(request: pytest.FixtureRequest) -> Iterator[Any]:
    playwright = pytest.importorskip("playwright.sync_api")
    with playwright.sync_playwright() as runtime:
        try:
            browser = runtime.chromium.launch()
        except playwright.Error as error:
            pytest.skip(f"Playwright Chromium is required: {error}")
        page = browser.new_page(viewport={"width": 1552, "height": 1253})
        page.emulate_media(reduced_motion="no-preference")
        source = TEMPLATE if request.param == "template" else WEB / "coordinates.html"
        html = source.read_text(encoding="utf-8")
        page.set_content(re.sub(
            r"<script\b[^>]*>[\s\S]*?</script>|<link\b[^>]*>", "", html,
        ))
        if request.param == "editor":
            for name in ("styles.css", "workbench.css", "coordinate-editor.css"):
                page.add_style_tag(content=(WEB / name).read_text(encoding="utf-8"))
            script = (WEB / "coordinate-editor.js").read_text(encoding="utf-8")
        else:
            script = html
            shared = ROOT / "docs/ui/shared"
            for name in ("settings-theme.css", "template-shell.css",
                         "coordinate-template.css", "settings-controls.css"):
                page.add_style_tag(content=(shared / name).read_text("utf-8"))
            page.add_script_tag(content=(shared / "settings-controls.js").read_text("utf-8"))
        # Exercise each real reveal helper and stylesheet without authentication or demo state.
        helper = re.search(r'    function reveal\([\s\S]*?(?=\n    /\*\*)', script)
        assert helper is not None
        state = "const reveals = new WeakMap();\n"
        if request.param == "template":
            state = "const animateReveal = StowlingSettingsControls.createReveal();\n"
        page.add_script_tag(content=(
            state + helper.group()
            + "\nwindow.revealForTest = reveal;"
        ))
        page.evaluate("""template => {
            const shell = document.querySelector('.coordinate-shell');
            const options = document.querySelector('.category-options');
            shell.replaceChildren(options);
            if (template) {
                const panel = document.createElement('section');
                panel.id = 'template-coordinates-panel';
                panel.dataset.settingsTemplate = 'coordinates';
                document.body.replaceChildren(panel);
                panel.append(shell);
            } else document.body.replaceChildren(shell);
            shell.style.display = 'block';
            shell.style.maxWidth = '700px';
            const recognition = options.querySelector('.category-recognition-panel');
            if (recognition) recognition.replaceWith(...recognition.children);
            const tail = document.createElement('div');
            tail.className = 'category-option-row';
            tail.textContent = '后续设置';
            options.append(tail);
            for (const row of options.querySelectorAll('.category-option-sub')) {
                window.revealForTest(row, true, false);
            }
        }""", request.param == "template")
        yield page
        browser.close()


@pytest.mark.parametrize("width", [1552, 390])
@pytest.mark.parametrize("theme", ["green", "gray"])
def test_submenus_shrink_continuously_without_squeezing_controls_or_a_final_jump(
    animation_page: Any, width: int, theme: str,
) -> None:
    page = animation_page
    page.set_viewport_size({"width": width, "height": 1253})
    page.evaluate("theme => document.documentElement.dataset.theme = theme", theme)
    for row in page.locator(".category-option-sub").all():
        result = row.evaluate("""async row => {
            const panel = row.parentElement;
            const next = panel.nextElementSibling;
            const naturalHeight = row.getBoundingClientRect().height;
            window.revealForTest(row, false, true);
            const animation = document.getAnimations().find(a => a.effect.target === panel);
            animation.pause();
            const frames = [0, 25, 75, 125, 149].map(time => {
                animation.currentTime = time;
                return {height: panel.getBoundingClientRect().height,
                    rowHeight: row.getBoundingClientRect().height};
            });
            const beforeHidden = next.getBoundingClientRect().top;
            animation.finish();
            await Promise.resolve();
            const result = {frames, naturalHeight, hidden: panel.hidden,
                finalJump: Math.abs(next.getBoundingClientRect().top - beforeHidden)};
            window.revealForTest(row, true, false);
            return result;
        }""")
        heights = [frame["height"] for frame in result["frames"]]
        assert heights[0] == pytest.approx(result["naturalHeight"], abs=0.1)
        assert heights == sorted(heights, reverse=True)
        assert heights[-1] < 1
        assert result["hidden"]
        assert result["finalJump"] < 1
        for frame in result["frames"]:
            assert frame["rowHeight"] == pytest.approx(result["naturalHeight"], abs=0.1)


def test_binding_override_spacing_collapses_with_its_content(animation_page: Any) -> None:
    result = animation_page.evaluate("""async () => {
        const options = document.querySelector('.category-options');
        const content = document.createElement('div');
        content.className = 'category-binding-content';
        const detail = document.createElement('div');
        detail.className = 'category-binding-options';
        const regions = document.createElement('div');
        regions.className = 'coordinate-region-grid';
        regions.textContent = '识别区与储存区';
        options.before(content);
        detail.append(options);
        content.append(detail, regions);
        window.revealForTest(detail, true, false);
        window.revealForTest(detail, false, true);
        const animation = document.getAnimations().find(a => a.effect.target === detail);
        animation.pause();
        animation.currentTime = 149;
        const beforeHidden = regions.getBoundingClientRect().top;
        animation.finish();
        await Promise.resolve();
        return {hidden: detail.hidden,
            finalJump: Math.abs(regions.getBoundingClientRect().top - beforeHidden)};
    }""")
    assert result["hidden"]
    assert result["finalJump"] < 1


def test_reversing_a_close_resumes_at_the_current_height_and_reuses_the_wrapper(
    animation_page: Any,
) -> None:
    result = animation_page.locator(".category-option-sub").first.evaluate("""async row => {
        const panel = row.parentElement;
        window.revealForTest(row, false, true);
        const closing = document.getAnimations().find(a => a.effect.target === panel);
        closing.pause();
        closing.currentTime = 40;
        const before = panel.getBoundingClientRect().height;
        window.revealForTest(row, true, true);
        const opening = document.getAnimations().find(a => a.effect.target === panel);
        opening.pause();
        opening.currentTime = 0;
        const after = panel.getBoundingClientRect().height;
        opening.finish();
        await Promise.resolve();
        return {before, after, hidden: panel.hidden, inert: panel.inert,
            overflow: getComputedStyle(panel).overflow,
            pointerEvents: getComputedStyle(row).pointerEvents,
            wrappers: row.closest('.category-options')
                .querySelectorAll('.category-option-reveal .category-option-reveal').length};
    }""")
    assert result["after"] == pytest.approx(result["before"], abs=0.1)
    assert not result["hidden"]
    assert not result["inert"]
    assert result["overflow"] == "visible"
    assert result["pointerEvents"] != "none"
    assert result["wrappers"] == 0


def test_reduced_motion_hides_the_whole_row_and_reopens_editable_content(
    animation_page: Any,
) -> None:
    page = animation_page
    page.emulate_media(reduced_motion="reduce")
    result = page.locator(".category-option-sub").first.evaluate("""row => {
        const panel = row.parentElement;
        window.revealForTest(row, false, true);
        const closed = panel.hidden && panel.inert;
        window.revealForTest(row, true, true);
        return {closed, hidden: panel.hidden, inert: panel.inert,
            pointerEvents: getComputedStyle(row).pointerEvents,
            animations: document.getAnimations().length};
    }""")
    assert result["closed"]
    assert not result["hidden"]
    assert not result["inert"]
    assert result["pointerEvents"] != "none"
    assert result["animations"] == 0
