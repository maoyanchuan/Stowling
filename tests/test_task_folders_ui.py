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


def test_task_list_has_persisted_folder_filters_and_editor_assignment() -> None:
    html = read("tasks.html")
    javascript = read("tasks.js")

    for element_id in (
        "task-folder-list",
        "new-folder-button",
        "task-folder-form",
        "task-folder-name",
        "delete-folder-button",
        "task-folder-select",
    ):
        assert f'id="{element_id}"' in html
    assert "全部任务" in javascript
    assert "未分组" in javascript
    assert "删除文件夹只会解散分组，不会删除任务" in html
    assert "function matchesTaskFolder(task)" in javascript
    assert "&& matchesTaskFilter(task)" in javascript
    assert "&& matchesTaskFolder(task)" in javascript
    assert 'Stowling.request("/v1/task-folders")' in javascript
    assert "/v1/task-folders/${encodeURIComponent(" in javascript
    assert "state.folderFilter = filter" in javascript
    assert "draft.folderId = defaultFolderIdForNewTask()" in javascript
    assert "state.draft.folderId = defaultFolderIdForNewTask()" in javascript


def test_folder_deletion_reloads_tasks_without_replacing_dirty_drafts() -> None:
    javascript = read("tasks.js")
    deletion = javascript.split("async function deleteFolder()", 1)[1]
    deletion = deletion.split("function isInterruptionTask", 1)[0]

    reload_at = deletion.index(
        'tasksResponse = await Stowling.request("/v1/tasks")'
    )
    immediate_cleanup = deletion[:reload_at]
    reloaded_cleanup = deletion[reload_at:]

    assert "state.tasks = state.tasks.map((task) => (" in immediate_cleanup
    assert "task.folderId === folder.id" in immediate_cleanup
    assert 'folderId: ""' in immediate_cleanup
    assert "renderTaskList()" in immediate_cleanup
    assert "state.draft?.folderId === folder.id" in immediate_cleanup
    assert "state.draft?.folderId === folder.id" in reloaded_cleanup
    assert "state.tasks = state.tasks.map((task) => (" in reloaded_cleanup
    assert "state.draft = Model.normalizeTask" not in deletion
    assert "文件夹已解散，但刷新任务失败" in deletion
    assert "renderTaskFolderSelect()" in deletion
    assert "state.triggerDraft" not in deletion


def test_deleting_a_task_refreshes_triggers_and_runtime_status() -> None:
    javascript = read("tasks.js")
    deletion = javascript.split("async function deleteTask()", 1)[1]
    deletion = deletion.split("async function runTask()", 1)[0]

    assert "/v1/task-triggers" in deletion
    assert "state.triggers = Array.isArray(triggersResponse?.triggers)" in deletion
    assert "await loadRuns()" in deletion
    assert "任务已删除，但刷新触发器失败" in deletion


def test_trigger_editor_is_task_bound_and_guards_stale_responses() -> None:
    html = read("tasks.html")
    javascript = read("tasks.js")

    assert 'id="task-trigger-settings"' in html
    assert "open" not in html.split('id="task-trigger-settings"', 1)[1].split(
        ">", 1
    )[0]
    assert 'id="trigger-target-task"' not in html
    assert "新任务的检测器随任务保存" in html
    assert "function currentTaskTriggers()" in javascript
    assert "trigger.targetTaskId === state.selectedId" in javascript
    assert "TriggerModel.newTrigger(state.selectedId)" in javascript
    assert "targetTaskId: taskId" in javascript
    assert "state.triggerDraft === draftSnapshot" in javascript
    assert "state.triggerEditSerial === editSerial" in javascript
    assert "function confirmDiscardForTaskChange()" in javascript
    assert "当前任务有未保存修改" in javascript
    assert "当前任务的触发器有未保存修改" in javascript


def test_task_save_atomically_keeps_folder_and_trigger_draft() -> None:
    javascript = read("tasks.js")
    saving = javascript.split("async function saveTask()", 1)[1]
    saving = saving.split("async function deleteTask()", 1)[0]

    assert "const draftSnapshot = state.draft" in saving
    assert "const editSerial = state.taskEditSerial" in saving
    assert "const payload = Model.payloadTask(draftSnapshot)" in saving
    assert "state.draft === draftSnapshot" in saving
    assert "state.taskEditSerial === editSerial" in saving
    assert "else if (stillEditing)" in saving
    assert "if (!requestTaskId && saved)" in saving
    assert "state.selectedId = saved.id" in saving
    assert "renderEditor()" in saving
    assert "folderId" in read("v2-task-model.js")
    assert "renderTriggerEditor()" in saving
    assert "state.triggerDraft = null" not in saving
    assert "state.triggerDirty = false" not in saving

    unchanged = saving.split("if (unchanged)", 1)[1]
    unchanged = unchanged.split("} else if (stillEditing)", 1)[0]
    assert "state.draft = saved ? Model.normalizeTask(saved) : null" in unchanged
    assert "state.dirty = false" in unchanged

    edited = saving.split("} else if (stillEditing)", 1)[1]
    edited = edited.split("renderTaskList()", 1)[0]
    assert "state.selectedId = saved.id" in edited
    assert "state.draft = saved ? Model.normalizeTask(saved) : null" not in edited
    assert "state.dirty = false" not in edited

    dirty = javascript.split("function markDirty", 1)[1]
    dirty = dirty.split("function rerenderEditor", 1)[0]
    assert "state.taskEditSerial++" in dirty


def test_hidden_folder_and_trigger_forms_override_grid_display() -> None:
    styles = read("styles.css")

    assert ".task-folder-form {" in styles
    assert "display: grid;" in styles.split(".task-folder-form {", 1)[1]
    assert ".task-folder-form[hidden]," in styles
    assert ".task-trigger-settings[hidden]," in styles
    assert "display: none !important;" in styles.split(
        ".task-folder-form[hidden],", 1
    )[1]
