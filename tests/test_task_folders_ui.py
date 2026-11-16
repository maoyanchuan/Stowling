from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "src" / "main" / "resources" / "stowling-web"


def read(name: str) -> str:
    return (WEB / name).read_text(encoding="utf-8")


def test_task_model_preserves_folder_ids_and_sends_null_when_ungrouped() -> None:
    model = read("v2-task-model.js")

    assert 'folderId: String(value?.folderId || "")' in model
    assert "folderId: task.folderId ? String(task.folderId) : null" in model
    assert 'folderId: "",' in model


def test_task_page_does_not_expose_folder_management() -> None:
    html = read("tasks.html")
    javascript = read("tasks.js")

    for element_id in (
        "task-folder-list",
        "new-folder-button",
        "task-folder-form",
        "task-folder-name",
        "delete-folder-button",
    ):
        assert f'id="{element_id}"' not in html
    for removed in (
        "renderTaskFolders",
        "saveFolder",
        "deleteFolder",
        "folderFilter",
        "matchesTaskFolder",
        '"/v1/task-folders"',
    ):
        assert removed not in javascript


def test_task_page_has_no_trigger_editor_or_config_reads() -> None:
    html = read("tasks.html")
    javascript = read("tasks.js")
    trigger_model = read("v2-trigger-model.js")

    assert 'id="task-trigger-settings"' not in html
    assert 'id="new-trigger-button"' not in html
    assert 'id="delete-task-button"' not in html
    assert "TriggerModel" not in javascript
    assert "currentTaskTriggers" not in javascript
    assert "renderTriggerEditor" not in javascript
    assert "deleteTrigger" not in javascript
    assert '"/v1/task-triggers"' not in javascript
    assert "state.triggerStatuses" in javascript
    assert "DROPPED_ITEMS" in trigger_model
    assert "FOOD_LEVEL_AT_OR_BELOW" in trigger_model
    assert "CACHE_UPDATED" in trigger_model

def test_fixed_move_editor_preserves_folder_metadata_without_folder_api() -> None:
    model = read("move-task-model.js")
    tasks = read("tasks.js")

    assert "folderId: normalized.folderId" in model
    assert "interruptionMode: normalized.interruptionMode === \"NONE\"" in model
    assert "state.moveTasks = tasks;" in tasks
    assert '"/v1/task-folders"' not in tasks
