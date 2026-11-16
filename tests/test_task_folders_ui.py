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


def test_task_list_has_persisted_folder_filters_and_folder_crud() -> None:
    html = read("tasks.html")
    javascript = read("tasks.js")

    for element_id in (
        "task-folder-list",
        "new-folder-button",
        "task-folder-form",
        "task-folder-name",
        "delete-folder-button",
    ):
        assert f'id="{element_id}"' in html
    assert 'id="task-folder-select"' not in html
    assert "全部任务" in javascript
    assert "未分组" in javascript
    assert "删除文件夹只会解散分组，不会删除任务" in html
    assert "function matchesTaskFolder(task)" in javascript
    assert "&& matchesTaskFilter(task)" in javascript
    assert "&& matchesTaskFolder(task)" in javascript
    assert 'Stowling.request("/v1/task-folders")' in javascript
    assert "/v1/task-folders/${encodeURIComponent(" in javascript
    assert "state.folderFilter = filter" in javascript
    assert "function renderTaskFolders()" in javascript
    assert "function renderTaskList()" in javascript
    apply_response = javascript.split("function applyFolderResponse", 1)[1]
    apply_response = apply_response.split("function folderValidationErrors", 1)[0]
    assert "state.folders = normalizeFolders(response?.folders)" in apply_response
    assert "renderTaskList()" in apply_response
    assert "renderTaskFolders()" not in apply_response
    assert "state.draft" not in javascript


def test_folder_deletion_only_ungroups_and_rereads_tasks() -> None:
    javascript = read("tasks.js")
    deletion = javascript.split("async function deleteFolder()", 1)[1]
    deletion = deletion.split("function isInterruptionTask", 1)[0]

    assert "state.tasks = state.tasks.map((task) => (" in deletion
    assert "task.folderId === folder.id" in deletion
    assert 'folderId: ""' in deletion
    assert 'tasksResponse = await Stowling.request("/v1/tasks")' in deletion
    assert "renderTaskList()" in deletion
    assert "文件夹已解散，但刷新任务失败" in deletion
    assert "renderTaskFolders()" not in deletion
    assert "state.draft" not in deletion
    assert "renderTaskFolderSelect" not in deletion
    assert "state.triggerDraft" not in deletion


def test_task_page_has_no_trigger_editor_or_task_delete_actions() -> None:
    html = read("tasks.html")
    javascript = read("tasks.js")

    assert 'id="task-trigger-settings"' not in html
    assert 'id="new-trigger-button"' not in html
    assert 'id="delete-task-button"' not in html
    assert "TriggerModel" not in javascript
    assert "currentTaskTriggers" not in javascript
    assert "renderTriggerEditor" not in javascript
    assert "deleteTrigger" not in javascript
    assert "/v1/task-triggers/${" not in javascript
    assert 'Stowling.request("/v1/task-triggers")' in javascript


def test_hidden_folder_form_overrides_grid_display() -> None:
    styles = read("styles.css")

    assert ".task-folder-form {" in styles
    assert "display: grid;" in styles.split(".task-folder-form {", 1)[1]
    assert ".task-folder-form[hidden]," in styles
    assert "display: none !important;" in styles.split(
        ".task-folder-form[hidden],", 1
    )[1]
