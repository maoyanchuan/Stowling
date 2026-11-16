from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "src" / "main" / "resources" / "stowling-web"
EDITOR_PATH = WEB / "v2-task-editor.js"
STYLES_PATH = WEB / "styles.css"


def read_editor() -> str:
    return EDITOR_PATH.read_text(encoding="utf-8")


def read_styles() -> str:
    return STYLES_PATH.read_text(encoding="utf-8")


def test_task_tree_summary_has_no_interactive_controls() -> None:
    editor = read_editor()
    summary_block = editor.split(
        'const summary = Stowling.element("summary", "workflow-node-summary");',
        1,
    )[1].split(
        'const body = Stowling.element("div", "workflow-node-body");',
        1,
    )[0]

    assert "button" not in summary_block
    assert "input" not in summary_block
    assert "select" not in summary_block
    assert "details.append(summary, body);" in editor
    assert "node.append(details, tools);" in editor


def test_action_parameters_collapse_and_control_flow_opens_by_default() -> None:
    editor = read_editor()

    details_markup = (
        'Stowling.element("details", "workflow-node-details")'
    )
    assert details_markup in editor
    assert 'details.open = expansionValue(' in editor
    assert 'kind === "IF" || kind === "REPEAT"' in editor
    assert "expansionValue(editorState.configOpen, key, false)" in editor
    assert 'Stowling.element("details", "workflow-node-config")' in editor


def test_expansion_state_is_keyed_by_step_id_and_removed_on_delete() -> None:
    editor = read_editor()

    assert "nodeOpen: new Map()" in editor
    assert "configOpen: new Map()" in editor
    assert "return `step:${String(step.id)}`;" in editor
    toggle_markup = 'details.addEventListener("toggle", () => {'
    assert toggle_markup in editor
    assert "values.set(key, details.open);" in editor
    assert "editorState.nodeOpen.delete(key);" in editor
    assert "editorState.configOpen.delete(key);" in editor
    assert "forgetStepState(step);" in editor
    assert "draft:${editorState.nextDraftKey}" in editor


def test_step_summary_uses_local_formatter_and_resource_display_names() -> None:
    editor = read_editor()
    headline = editor.split("function stepHeadline(", 1)[1].split(
        "function stepSummary(",
        1,
    )[0]

    assert "function stepSummary(" in editor
    assert "Model.stepSummary" not in editor
    assert "Model.summary" not in editor
    assert "referenceDisplayName(context, String(value))" in editor
    assert "resourceDisplayName(" in editor
    assert "OUTPUT_REFERENCE_LABELS[text]" in editor
    assert "function humanizeParameterValue(" in editor
    assert "动作不可用" in headline
    assert "未选择动作" in headline
    assert "return module?.name || step.moduleId" not in headline


def test_input_updates_summary_without_rebuilding_the_tree() -> None:
    editor = read_editor()

    assert 'body.addEventListener("input", () => {' in editor
    assert 'body.addEventListener("change", () => {' in editor
    assert "function refreshNodeSummary(" in editor
    assert "meta.textContent = stepSummary(context, step, module);" in editor
    assert "configMeta.textContent = configurationSummary(step, kind);" in editor
    assert "renderEditor" not in editor


def test_branches_and_depth_labels_are_explicit_and_compact() -> None:
    editor = read_editor()

    assert '"满足条件"' in editor
    assert '"否则"' in editor
    assert '"重复执行"' in editor
    assert "const cappedDepth = Math.min(depth, 4);" in editor
    assert "`workflow-depth-${cappedDepth}`" in editor
    assert "`workflow-branch-${role}`" in editor
    assert '"workflow-node-position"' in editor
    assert 'path.map((index) => index + 1).join(".")' in editor


def test_tree_actions_keep_existing_model_callbacks_and_stop_bubbling() -> None:
    editor = read_editor()

    assert "function renderStepList(" in editor
    assert "function branchEditor(" in editor
    assert "step[branchKey] = []" in editor
    assert "Model.newStep(" in editor
    assert "steps.splice(index, 1);" in editor
    assert "steps.splice(index + offset, 0, step);" in editor
    assert editor.count("event.stopPropagation();") >= 3


def test_workflow_tree_css_contract_is_present() -> None:
    styles = read_styles()

    for selector in (
        ".workflow-tree",
        ".workflow-node",
        ".workflow-node-summary",
        ".workflow-node-tools",
        ".workflow-node-body",
        ".workflow-node-config",
        ".workflow-branch",
        ".workflow-step-list",
        ".workflow-depth-4",
    ):
        assert selector in styles
    assert "summary:focus-visible" in styles
