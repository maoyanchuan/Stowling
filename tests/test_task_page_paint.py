from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]
STYLES_PATH = (
    ROOT / "src" / "main" / "resources" / "stowling-web" / "styles.css"
)


def _rule_body(selector: str, stylesheet: str) -> str:
    pattern = re.compile(
        rf"(?m)^{re.escape(selector)}\s*\{{(?P<body>[^}}]*)\}}"
    )
    match = pattern.search(stylesheet)
    assert match is not None, f"Missing CSS rule: {selector}"
    return match.group("body")


def test_tasks_panels_avoid_large_backdrop_filter_repaints() -> None:
    styles = STYLES_PATH.read_text(encoding="utf-8")

    task_panel = _rule_body('body[data-page="tasks"] .panel', styles)
    assert "background: var(--panel-solid);" in task_panel
    assert "backdrop-filter: none;" in task_panel

    shared_panel = _rule_body(".panel", styles)
    assert "background: var(--panel);" in shared_panel
    assert "backdrop-filter: blur(18px);" in shared_panel

    workspace_nav = _rule_body(".workspace-nav", styles)
    assert "backdrop-filter: blur(14px);" in workspace_nav

    connection_state = _rule_body(".connection-state", styles)
    assert "backdrop-filter: blur(12px);" in connection_state

    assert styles.count("--panel-solid:") >= 2
    assert styles.count("--panel:") >= 2
