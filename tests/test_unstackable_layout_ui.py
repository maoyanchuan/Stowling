from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "src" / "main" / "resources" / "stowling-web"


def test_unstackable_configuration_belongs_to_the_storage_layout() -> None:
    layout = (WEB / "category-zones.js").read_text(encoding="utf-8")
    tasks = (WEB / "tasks.html").read_text(encoding="utf-8")
    task_ui = (WEB / "auto-classify-ui.js").read_text(encoding="utf-8")

    assert 'field("分类方式", rule)' in layout
    assert 'rule.value === "UNSTACKABLE_ITEMS"' in layout
    assert "marker.root.hidden = unstackable" in layout
    assert "排除所有潜影盒" in layout
    assert 'id="auto-unstackable-region"' not in tasks
    assert 'id="unstackable-new"' not in tasks
    assert "unstackable-task-ui.js" not in tasks
    assert "elements.unstackable" not in task_ui
