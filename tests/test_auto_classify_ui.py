from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "src" / "main" / "resources" / "stowling-web"


def read(name: str) -> str:
    return (WEB / name).read_text(encoding="utf-8")


def read_java(relative_path: str) -> str:
    return (ROOT / relative_path).read_text(encoding="utf-8")


def between(source: str, start: str, end: str) -> str:
    start_index = source.index(start)
    content_start = start_index + len(start)
    return source[content_start:source.index(end, content_start)]


def test_task_page_places_auto_tasks_above_the_single_template() -> None:
    html = read("tasks.html")
    display = between(
        html,
        'id="task-display-area"',
        '<section class="panel task-selection-panel"',
    )

    assert display.index('id="auto-task-list"') < display.index(
        'id="auto-task-draft"'
    )
    for element_id in (
        "auto-task-list",
        "auto-task-draft",
        "toggle-auto-draft",
        "auto-draft-body",
        "auto-task-form",
        "auto-task-fields",
        "auto-task-name",
        "auto-detection-point",
        "auto-pickup-radius",
        "auto-cache-region",
        "auto-overflow-enabled",
        "auto-collect-priority",
        "auto-classification-areas",
        "add-auto-classification-area",
        "auto-collect-toggle",
        "auto-collect-body",
        "auto-collect-enabled",
        "auto-sort-toggle",
        "auto-sort-body",
        "auto-classify-enabled",
        "auto-task-message",
        "save-auto-task",
        "start-auto-task",
        "stop-auto-task",
        "delete-auto-task",
    ):
        assert f'id="{element_id}"' in display
    assert 'id="new-auto-task"' not in display
    assert 'id="new-auto-task"' in html
    assert "自动分类" in display
    assert '<input id="auto-task-name" maxlength="80"' in display
    assert 'id="auto-pickup-radius" type="number"' in display
    assert 'min="1" max="64" step="1" value="8"' in display


def test_auto_phases_are_collapsed_submenus_with_switches() -> None:
    html = read("tasks.html")
    javascript = read("auto-classify-ui.js")
    form = between(html, 'id="auto-task-form"', "</form>")

    assert "收集入缓存" not in html
    for phase, title in (("collect", "启用缓存"), ("sort", "后台分类")):
        toggle_id = f'id="auto-{phase}-toggle"'
        body_id = f'id="auto-{phase}-body"'
        assert toggle_id in form
        assert body_id in form
        assert title in form
    collect_header = between(
        form,
        'id="auto-collect-toggle"',
        'id="auto-collect-body"',
    )
    sort_header = between(
        form,
        'id="auto-sort-toggle"',
        'id="auto-sort-body"',
    )
    collect_body = between(
        form,
        'id="auto-collect-body"',
        'id="auto-collect-priority"',
    )
    sort_body = between(
        form,
        'id="auto-sort-body"',
        'id="add-auto-classification-area"',
    )

    assert 'aria-expanded="false"' in collect_header
    assert 'aria-controls="auto-collect-body"' in collect_header
    assert 'id="auto-collect-enabled"' in collect_header
    assert 'role="switch"' in collect_header
    assert "hidden" in collect_body
    assert 'aria-expanded="false"' in sort_header
    assert 'aria-controls="auto-sort-body"' in sort_header
    assert 'id="auto-classify-enabled"' in sort_header
    assert 'role="switch"' in sort_header
    assert "hidden" in sort_body

    for selector in (
        '#auto-collect-toggle',
        '#auto-collect-body',
        '#auto-collect-enabled',
        '#auto-sort-toggle',
        '#auto-sort-body',
        '#auto-classify-enabled',
    ):
        assert selector in javascript
    assert 'togglePhase("collection")' in javascript
    assert 'togglePhase("classification")' in javascript
    assert "reveal(body, enabled && state.phase[phase])" in javascript
    assert "header.disabled = disabled || !enabled" in javascript
    assert "areaReaders.forEach(reader => reader.collapse())" in javascript


def test_overflow_switch_is_visible_outside_both_collapsed_phase_sections() -> None:
    html = read("tasks.html")
    form = between(html, 'id="auto-task-form"', "</form>")
    for title in ("auto-collect-title", "auto-sort-title"):
        section = between(form, f'aria-labelledby="{title}"', "</section>")
        assert 'id="auto-overflow-enabled"' not in section
    overflow = between(form, 'id="auto-overflow-section"', "</section>")
    assert 'id="auto-overflow-enabled"' in overflow
    assert 'role="switch"' in overflow
    assert 'class="auto-switch-track"' in overflow
    assert " hidden" not in overflow
    assert form.index('id="auto-sort-body"') < form.index('id="auto-overflow-section"')
    assert form.index('id="auto-overflow-section"') < form.index('id="auto-cleanup-settings"')


def test_recognition_is_configured_only_in_the_storage_layout() -> None:
    javascript = read("auto-classify-ui.js")
    model = read("auto-classify-task-model.js")
    layout = read("category-zones.js")

    assert "分类物识别方式" not in javascript
    assert "识别方式使用仓储布局中各分类物的设置。" in javascript
    assert "ruleId" not in javascript
    assert "markerSources" not in javascript
    assert "ruleId" not in model
    assert "markerSources" not in model
    assert 'field("分类物识别方式", rule)' in layout
    assert "ruleId: rule.value" in layout
    assert "classificationAreas: [newArea()]" in model
    assert "function addClassificationArea()" in javascript
    assert "function removeClassificationArea(index)" in javascript
    assert 'labeledControl("分类范围", range)' in javascript
    assert "elements.addArea.addEventListener" in javascript


def test_auto_ui_loads_independent_api_and_expected_array_properties() -> None:
    javascript = read("auto-classify-ui.js")
    loading = between(
        javascript,
        "async function loadResources()",
        "function populateSelect",
    )

    for route in (
        '"/v1/auto-classify-tasks"',
        '"/v1/coordinates"',
        '"/v1/regions"',
        '"/v1/category-zones"',
        '"/v1/tasks"',
    ):
        assert route in loading
    assert "Array.isArray(tasks.items) ? tasks.items : []" in loading
    assert "Array.isArray(coordinates.coordinates)" in loading
    assert "Array.isArray(regions.regions)" in loading
    assert "Array.isArray(zones.categoryZones)" in loading
    assert "/v1/category-rules" not in loading
    assert "Array.isArray(allTasks.tasks) ? allTasks.tasks : []" in loading
    assert "state.revision = String(tasks.revision || \"\");" in loading


def test_smart_recognition_reads_the_item_inside_a_flower_pot() -> None:
    resolver = read_java(
        "src/main/java/dev/stowling/client/automation/"
        "MinecraftCategoryZoneResolver.java"
    )

    assert "instanceof FlowerPotBlock flowerPot" in resolver
    assert "flowerPot.getPotted().asItem()" in resolver
    assert "PottedBlockItems.itemId(blockId)" in resolver
    assert "CategoryMarkerSource.PLACED_ITEM" in resolver
    assert "CategoryMarkerSource.FLOWER_POT" in resolver


def test_auto_ui_delegates_flat_business_rules_to_the_model() -> None:
    javascript = read("auto-classify-ui.js")

    assert "const Auto = window.StowlingAutoClassifyTaskModel;" in javascript
    for call in (
        "Auto.newDraft(",
        "Auto.draftFromTask(",
        "Auto.payload(",
    ):
        assert call in javascript
    assert "detection: {" not in javascript
    assert "coordinateRef:" not in javascript
    assert "cacheRegionRef:" not in javascript
    assert "categoryZoneRef:" not in javascript
    assert "classificationPriority" not in javascript

    form_reader = between(
        javascript,
        "function readForm()",
        "function renderList()",
    )
    assert "...state.draft" in form_reader
    assert "classificationAreas: areaReaders.map((reader) => reader.read())" in (
        form_reader
    )
    assert "collectionEnabled: elements.collectEnabled.checked" in form_reader
    assert "classificationEnabled: elements.classifyEnabled.checked" in form_reader
    assert "length ?" not in form_reader
    assert "enabled:" not in form_reader


def test_auto_ui_posts_new_disabled_drafts_and_puts_with_revision() -> None:
    javascript = read("auto-classify-ui.js")
    save = between(javascript, "async function saveTask(", "async function stopTask(")

    assert "payload = Auto.payload(" in save
    assert "state.draft, state.resources, state.items, state.allTasks" in save
    assert "? `/v1/auto-classify-tasks/${encodeURIComponent(id)}`" in save
    assert ': "/v1/auto-classify-tasks"' in save
    assert 'method: id ? "PUT" : "POST"' in save
    assert "...payload, revision: state.revision" in save
    assert "? { ...payload, revision: state.revision } : payload" in save
    assert "/start" in save
    assert "const item = response.item;" in javascript
    assert "state.revision = String(response.revision || \"\");" in javascript


def test_auto_ui_stop_delete_and_saved_task_count_contracts() -> None:
    javascript = read("auto-classify-ui.js")
    tasks = read("tasks.js")
    stop = between(javascript, "async function stopTask(", "elements.newTask")

    assert "/stop" in stop
    assert 'method: remove ? "DELETE" : "POST"' in stop
    assert "body: JSON.stringify({ revision: state.revision })" in stop
    assert "const item = response.item;" in javascript
    assert "state.revision = String(response.revision || \"\");" in javascript
    assert 'new CustomEvent("stowling:auto-task-count"' in javascript
    count_event = between(
        javascript,
        'new CustomEvent("stowling:auto-task-count", {',
        "}));",
    )
    assert "detail:" in count_event
    assert "count: state.items.length" in count_event
    assert "draftActive: state.active && !state.draft.id" in count_event
    assert 'document.addEventListener("stowling:auto-task-count"' in tasks
    assert "state.autoTaskCount = Number(event.detail?.count || 0);" in tasks
    assert "state.moveTasks.length + state.autoTaskCount" in tasks


def test_auto_draft_hides_the_empty_task_placeholder() -> None:
    tasks = read("tasks.js")
    listener = between(
        tasks,
        'document.addEventListener("stowling:auto-task-count", (event) => {',
        "});",
    )
    disclosure = between(
        tasks,
        "function syncMoveDisclosure()",
        "function selectMoveTask(",
    )
    placeholder = between(disclosure, "elements.movePlaceholder.hidden =", ";")

    assert "autoDraftActive: false" in tasks
    assert "state.autoDraftActive = event.detail?.draftActive === true;" in listener
    assert "syncMoveDisclosure();" in listener
    assert " ".join(placeholder.split()) == (
        "state.moveTasks.length + state.autoTaskCount > 0"
        " || newDraft || state.autoDraftActive"
    )


def test_stopping_a_dirty_auto_task_warns_before_discarding_edits() -> None:
    javascript = read("auto-classify-ui.js")
    stop = between(javascript, "async function stopTask(", "elements.newTask")
    warning = 'const warning = state.dirty ? "未保存修改将被丢弃。" : "";'
    confirmation = 'window.confirm(`${prompt}${warning}`)'

    assert "停止掉落物监听？" in stop
    assert warning in stop
    assert confirmation in stop
    assert stop.index(warning) < stop.index(confirmation) < stop.index("Stowling.request(")
    assert "if (!window.confirm(`${prompt}${warning}`)) {\n            return;" in stop


def test_task_page_loads_model_before_ui_and_ui_after_shared_styles() -> None:
    html = read("tasks.html")
    model = '<script src="/auto-classify-task-model.js" defer></script>'
    ui = '<script src="/auto-classify-ui.js" defer></script>'
    tasks = '<script src="/tasks.js" defer></script>'

    assert html.index(model) < html.index(ui)
    assert html.index(tasks) < html.index(ui)
    assert '<link rel="stylesheet" href="/styles.css">' in html
    assert ".auto-task-form" in read("styles.css")
    assert "auto-task-form[hidden]" in read("styles.css")


def test_unstackable_rule_is_configured_in_the_warehouse_layout() -> None:
    html = read("tasks.html")
    assert "坐标 → 仓储布局" in html
    assert 'id="auto-unstackable-region"' not in html
    assert "关闭不可堆叠分类" not in read("auto-classify-ui.js")
    editor = read("category-zones.js")
    assert "marker.root.hidden" not in editor
    assert "...marker.read()" in editor
    assert "...storage.read()" in editor

