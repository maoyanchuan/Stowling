import re
from html.parser import HTMLParser
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
SAMPLE = ROOT / "docs" / "ui" / "settings-standard.html"
WORKBENCH = ROOT / "src" / "main" / "resources" / "stowling-web" / "workbench.css"


class SampleMarkup(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.ids: list[str] = []
        self.references: list[str] = []
        self.external_sources: list[str] = []
        self.themes: set[str] = set()
        self.in_module_header = False
        self.module_header_controls: list[tuple[str, dict[str, str | None]]] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = dict(attrs)
        if tag == "header" and "sample-header" in (values.get("class") or "").split():
            self.in_module_header = True
        if self.in_module_header and tag in {"input", "button"}:
            self.module_header_controls.append((tag, values))
        if identity := values.get("id"):
            self.ids.append(identity)
        for key in ("for", "aria-controls", "aria-labelledby", "aria-describedby"):
            if reference := values.get(key):
                self.references.extend(reference.split())
        if source := values.get("src"):
            if not source.startswith("data:"):
                self.external_sources.append(source)
        if tag == "link" and values.get("href"):
            self.external_sources.append(values["href"] or "")
        if theme := values.get("data-theme-choice"):
            self.themes.add(theme)

    def handle_endtag(self, tag: str) -> None:
        if tag == "header":
            self.in_module_header = False


def read_sample() -> str:
    return SAMPLE.read_text(encoding="utf-8")


def theme_tokens(source: str, selector: str) -> dict[str, str]:
    pattern = re.escape(selector) + r"\s*\{([^}]+)\}"
    match = re.search(pattern, source)
    assert match is not None, f"Missing palette: {selector}"
    return dict(re.findall(r"(--[\w-]+):\s*([^;]+);", match.group(1)))


@pytest.mark.parametrize("selector", [":root", ':root[data-theme="green"]'])
def test_sample_palette_matches_the_project(selector: str) -> None:
    project = WORKBENCH.read_text(encoding="utf-8")
    parser = SampleMarkup()
    parser.feed(read_sample())
    theme = next(source for source in parser.external_sources
                 if source.endswith("settings-theme.css"))
    assert theme_tokens((SAMPLE.parent / theme).read_text("utf-8"), selector) == theme_tokens(
        project, selector,
    )


def test_standalone_page_has_no_missing_labels_or_external_assets() -> None:
    parser = SampleMarkup()
    parser.feed(read_sample())
    assert len(parser.ids) == len(set(parser.ids))
    assert set(parser.references).issubset(set(parser.ids))
    assert parser.external_sources
    assert all(not source.startswith(("http:", "https:", "//"))
               and (SAMPLE.parent / source).is_file() for source in parser.external_sources)
    assert parser.themes == {"green", "gray"}


def test_sample_is_independent_of_host_controls_and_game_services() -> None:
    source = read_sample()
    assert source.lstrip().lower().startswith("<!doctype html>")
    assert "window.openai" not in source
    assert "Tweak" not in source
    assert "fetch(" not in source
    assert "XMLHttpRequest" not in source
    assert "WebSocket" not in source


def test_module_header_keeps_task_switch_after_independent_disclosure() -> None:
    parser = SampleMarkup()
    parser.feed(read_sample())
    controls = parser.module_header_controls
    assert [(tag, attrs.get("id")) for tag, attrs in controls] == [
        ("button", "sample-collapse"),
        ("input", "sample-enabled"),
    ]
    assert controls[0][1]["aria-controls"] == "sample-module-body"
    assert controls[1][1]["role"] == "switch"
    assert controls[1][1]["aria-label"] == "运行售卖弹性物资任务"
    assert "sample-grip" not in read_sample()
