from html.parser import HTMLParser
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "src/main/resources/stowling-web"


class SettingsMarkup(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.stack: list[tuple[str, str | None]] = []
        self.parents: dict[str, tuple[str, ...]] = {}
        self.attrs: dict[str, dict[str, str | None]] = {}
        self.order: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = dict(attrs)
        identity = values.get("id")
        if identity:
            self.parents[identity] = tuple(key for _, key in self.stack if key)
            self.attrs[identity] = values
            self.order.append(identity)
        if tag not in {"area", "base", "br", "col", "embed", "hr", "img", "input", "link",
                       "meta", "param", "source", "track", "wbr"}:
            self.stack.append((tag, identity))

    def handle_endtag(self, tag: str) -> None:
        for index in range(len(self.stack) - 1, -1, -1):
            if self.stack[index][0] == tag:
                del self.stack[index:]
                return


def markup(path: Path) -> SettingsMarkup:
    parsed = SettingsMarkup()
    parsed.feed(path.read_text(encoding="utf-8"))
    return parsed


def test_task_experiments_are_after_cleanup_and_before_delete_actions() -> None:
    page = markup(WEB / "tasks.html")
    menu = "auto-experiments-settings"
    assert "auto-task-fields" in page.parents[menu]
    assert page.order.index("auto-cleanup-settings") < page.order.index(menu)
    assert page.order.index(menu) < page.order.index("delete-auto-task")
    assert page.attrs["auto-experiments-toggle"]["aria-expanded"] == "false"
    assert page.attrs["auto-experiments-toggle"]["aria-controls"] == "auto-experiments-body"
    assert "hidden" in page.attrs["auto-experiments-body"]
    assert "type" not in page.attrs[menu]


def test_all_existing_experimental_controls_share_the_footer_submenu() -> None:
    page = markup(WEB / "tasks.html")
    controls = ["auto-classification-baseline", "auto-full-handoff", "auto-partial-demand",
                "auto-empty-settlement", "auto-same-category-batching",
                "auto-cache-packing-enabled", "auto-cache-mode", "auto-cache-threshold"]
    for control in controls:
        assert page.order.count(control) == 1
        assert "auto-experiments-body" in page.parents[control]
        assert "auto-collect-body" not in page.parents[control]
        assert "auto-sort-body" not in page.parents[control]
    for control in ("auto-detection-point", "auto-cache-location",
                    "robot-warehouse-enabled", "auto-follow-up-enabled"):
        assert "auto-experiments-body" not in page.parents[control]


def test_runnable_template_places_experiments_last_with_an_independent_disclosure() -> None:
    page = markup(ROOT / "docs/ui/task-settings-standard.html")
    assert page.order.index("sample-maximum") < page.order.index("sample-experiments")
    assert "sample-experiments-detail" in page.parents["sample-batching"]
    assert page.attrs["sample-experiments-expand"]["aria-expanded"] == "false"
    assert page.attrs["sample-experiments-expand"]["aria-controls"] == "sample-experiments-detail"
    assert "hidden" in page.attrs["sample-experiments-detail"]
    assert "sample-experiments" not in page.parents["sample-enabled"]
