from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / "src" / "main" / "resources" / "stowling-web"


def read(name: str) -> str:
    return (WEB / name).read_text(encoding="utf-8")


def function_source(source: str, name: str) -> str:
    body = source.split(f"function {name}(", 1)[1]
    return body.split("\n    function ", 1)[0]


def test_deposit_target_matches_editor_and_save_validation() -> None:
    editor = read("v2-task-editor.js")
    tasks = read("tasks.js")
    editor_target = function_source(editor, "targetReferenceTypesFor")
    tasks_target = function_source(tasks, "targetReferenceTypesFor")

    assert 'moduleId === "DEPOSIT_ITEMS_TO_CONTAINERS"' in editor_target
    assert (
        'return ["coordinate", "region", ...outputTypesFor(moduleId)];'
        in editor_target
    )
    assert 'moduleId === "DEPOSIT_ITEMS_TO_CONTAINERS"' in tasks_target
    assert 'return ["coordinate", "output"];' in tasks_target
    output_modules = tasks_target.split("const outputModules", 1)[1]
    assert "DEPOSIT_ITEMS_TO_CONTAINERS" not in output_modules

    outputs = function_source(editor, "outputTypesFor")
    deposit = outputs.split(
        "DEPOSIT_ITEMS_TO_CONTAINERS: [",
        1,
    )[1].split("],", 1)[0]
    for output_type in ("output-container", "output-storage", "output-marker"):
        assert output_type in deposit

    valid_outputs = function_source(tasks, "validOutputReference")
    assert "DEPOSIT_ITEMS_TO_CONTAINERS: Model.OUTPUT_REFERENCES" in valid_outputs


def test_withdraw_and_read_do_not_open_coordinate_targets() -> None:
    editor = read("v2-task-editor.js")
    tasks = read("tasks.js")
    target = function_source(editor, "targetReferenceTypesFor")
    outputs = function_source(editor, "outputTypesFor")
    tasks_target = function_source(tasks, "targetReferenceTypesFor")

    withdraw = outputs.split('WITHDRAW_ITEMS: ["', 1)[1].split('"]', 1)[0]
    read_output = outputs.split('READ_CONTAINER: ["', 1)[1].split('"]', 1)[0]
    assert "coordinate" not in withdraw
    assert "coordinate" not in read_output
    assert 'return outputs.length > 0 ? outputs : ["coordinate"];' in target

    read_target = target.split('moduleId === "READ_CONTAINER"', 1)[1]
    read_target = read_target.split("}", 1)[0]
    assert 'return ["output-marker"];' in read_target
    output_only = tasks_target.split("const outputModules", 1)[1]
    assert '"WITHDRAW_ITEMS"' in output_only
    assert '"READ_CONTAINER"' in output_only
    assert 'return outputModules.has(moduleId) ? ["output"] : ["coordinate"];' in (
        tasks_target
    )


def test_reference_select_groups_output_types_and_keeps_state_handling() -> None:
    editor = read("v2-task-editor.js")
    select = function_source(editor, "referenceSelect")

    assert "const groups = new Map();" in select
    assert 'type.startsWith("output") ? "output" : type' in select
    assert "const options = referenceOptions(context, step, type);" in select
    assert 'group.label = groupType === "coordinate"' in select
    assert '"已保存坐标"' in select
    assert "referenceTypeLabel(groupType)" in select
    assert "found = found || option.value === current;" in select
    assert "invalid.label" in select and '"已失效引用"' in select
    assert "Model.referenceType(current)" in select
    assert "select.value = current;" in select
    assert "setParameter(step, parameter.key, select.value, context.changed)" in select


def test_coordinate_options_reuse_model_and_do_not_auto_select() -> None:
    editor = read("v2-task-editor.js")
    options = function_source(editor, "referenceOptions")
    select = function_source(editor, "referenceSelect")
    coordinate = options.split(
        'if (type === "coordinate") {',
        1,
    )[1].split('if (type === "region")', 1)[0]

    assert "coordinateLabel(point)" in coordinate
    assert "Model.coordinateRef(point.id)" in coordinate
    assert 'new Option("请选择已保存资源或前序选择结果", "")' in select
    assert "select.value = current;" in select


def test_output_dropdown_labels_summary_hints_and_deposit_guidance() -> None:
    editor = read("v2-task-editor.js")
    select = function_source(editor, "referenceSelect")
    summary = function_source(editor, "referenceDisplayName")
    hint = select.split("const updateHint = () => {", 1)[1].split("};", 1)[0]

    assert 'option.label.replace(/^前序选择 · /, "")' in select
    assert '"output:container": "前序选择 · 容器"' in editor
    assert "return OUTPUT_REFERENCE_LABELS[text];" in summary
    assert 'hint.textContent = OUTPUT_REFERENCE_HINTS[select.value] || "";' in hint
    assert 'step.moduleId === "DEPOSIT_ITEMS_TO_CONTAINERS"' in hint
    assert 'Model.referenceType(select.value) === "coordinate"' in hint
    assert "context.coordinates.some(" in hint
    assert "Model.coordinateRef(point.id) === select.value" in hint
    assert "直接存入该坐标处的容器；请确认坐标指向箱子等可打开的容器。" in hint
    assert "hint.hidden = !hint.textContent;" in hint
    assert "updateHint();" in select


def test_reference_control_uses_compact_adaptive_width() -> None:
    styles = read("styles.css")
    control = styles.split(".task-reference-control {", 1)[1].split("}", 1)[0]
    select = styles.split(".task-reference-control select {", 1)[1].split("}", 1)[0]

    assert "width: min(100%, 30rem);" in control
    assert "min-width: 0;" in control
    assert "width: 100%;" in select
    assert "min-width: 0;" in select
