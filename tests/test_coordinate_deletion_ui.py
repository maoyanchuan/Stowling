"""Coordinate deletion UI regressions; execute JavaScript only when pytest is requested."""

import json
import os
from pathlib import Path
import subprocess
from typing import Any

import pytest


WEB_ROOT = Path(__file__).resolve().parents[1] / "src/main/resources/stowling-web"
COORDINATES_JS = WEB_ROOT / "coordinates.js"
COORDINATES_HTML = WEB_ROOT / "coordinates.html"

DRIVER = r"""
const fs = require("node:fs");
const vm = require("node:vm");
const input = JSON.parse(fs.readFileSync(0, "utf8"));
const ctx = {
    snapshot: input.snapshot,
    resources: input.resources,
    definitions: { tasks: input.tasks },
    failEndpoint: input.failEndpoint,
    confirm: input.confirm,
    writeFailure: false,
    confirmations: [],
    writes: [],
    handled: []
};

class Element {
    /** @returns {void} */
    constructor() {
        this.children = [];
        this.textContent = "";
        this.dataset = {};
        this.hidden = false;
        this.disabled = false;
        this.classList = {
            /** @returns {void} */
            add() {},
            /** @returns {void} */
            remove() {},
            /** @returns {void} */
            toggle() {}
        };
    }
    /** @param {...unknown} children @returns {void} */
    append(...children) { this.children.push(...children); }
    /** @param {...unknown} children @returns {void} */
    replaceChildren(...children) { this.children = children; }
    /** @returns {boolean} */
    hasChildNodes() { return this.children.length > 0; }
    /** @returns {void} */
    addEventListener() {}
    /** @returns {void} */
    setAttribute() {}
    /** @returns {Array<unknown>} */
    querySelectorAll() { return []; }
    /** @returns {null} */
    querySelector() { return null; }
    /** @returns {null} */
    closest() { return null; }
}

const nodes = new Map();
/** @param {string} selector @returns {Element} */
function node(selector) {
    if (!nodes.has(selector)) {
        nodes.set(selector, new Element());
    }
    return nodes.get(selector);
}
/** @param {unknown} value @returns {string} */
function text(value) {
    if (!value || typeof value !== "object") {
        return String(value || "");
    }
    return [value.textContent, ...value.children.map(text)].filter(Boolean).join("\n");
}
/** @param {unknown} value @returns {unknown} */
function clone(value) { return JSON.parse(JSON.stringify(value)); }

const Stowling = {
    clone,
    /** @param {string} tag @param {string} classes @param {string} label
     * @returns {Element} */
    element(tag, classes = "", label = "") {
        const result = new Element();
        result.textContent = label;
        return result;
    },
    /** @param {string} label @returns {Element} */
    emptyState(label) {
        const result = new Element();
        result.textContent = label;
        return result;
    },
    /** @param {string} revision @returns {string} */
    revisionLabel(revision) { return revision; },
    /** @returns {void} */
    showToast() {},
    /** @param {unknown} error @param {string} label @returns {void} */
    handleError(error, label) { ctx.handled.push(label); },
    /** @param {() => Promise<void>} callback @returns {void} */
    onConnected(callback) { ctx.connected = callback; },
    /** @returns {void} */
    mountPage() {},
    ApiError: Error,
    /** @param {string} url @param {object | null} options @returns {Promise<object>} */
    async request(url, options = null) {
        if (options?.method === "PUT") {
            const payload = JSON.parse(options.body);
            ctx.writes.push({ url, method: options.method, payload });
            if (ctx.writeFailure) {
                throw new Error("atomic save refused");
            }
            ctx.snapshot = {
                revision: "saved-revision",
                coordinatePoints: payload.coordinatePoints
            };
            return clone(ctx.snapshot);
        }
        if (ctx.failEndpoint === url) {
            throw new Error("reference read failed");
        }
        if (url === "/v1/config/coordinate-points") {
            return clone(ctx.snapshot);
        }
        if (url === "/v1/config/task-resources") {
            return clone(ctx.resources);
        }
        if (url === "/v1/task-definitions") {
            return clone(ctx.definitions);
        }
        throw new Error(`unexpected request: ${url}`);
    }
};
const window = {
    Stowling,
    /** @param {string} warning @returns {boolean} */
    confirm(warning) {
        ctx.confirmations.push(warning);
        return ctx.confirm;
    }
};
const document = {
    querySelector: node,
    /** @returns {Element} */
    createElement() { return new Element(); },
    /** @returns {null} */
    getElementById() { return null; },
    /** @returns {void} */
    addEventListener() {}
};
const context = vm.createContext({ window, document, console, ctx, Error });
const original = fs.readFileSync(input.sourcePath, "utf8");
const source = original.replace('    Stowling.mountPage("coordinates");', `
    window.ui = {
        state, renderSnapshot, renderUsage, removePoint, loadReferences, loadCoordinates,
        saveCoordinates, discardChanges, cleanupCoordinateIds, deletionPlan
    };
    Stowling.mountPage("coordinates");
`);
vm.runInContext(source, context);
context.ui = window.ui;
/** @returns {object} */
function inspect() {
    return clone({
        ids: window.ui.state.draft?.coordinatePoints.map((point) => point.id) || [],
        draft: window.ui.state.draft,
        cleanupIds: [...window.ui.state.cleanupIds],
        dirty: window.ui.state.dirty,
        ready: window.ui.state.referencesReady,
        errors: text(node("#coordinate-errors")),
        usage: text(node("#coordinate-usage")),
        referenceStatus: text(node("#coordinate-reference-status")),
        reloadHidden: node("#reload-coordinate-references-button").hidden,
        saveDisabled: node("#save-coordinate-button").disabled,
        confirmations: ctx.confirmations,
        writes: ctx.writes,
        handled: ctx.handled
    });
}
context.inspect = inspect;
/** @returns {Promise<void>} */
async function main() {
    window.ui.renderSnapshot(ctx.snapshot);
    try {
        await window.ui.loadReferences();
    } catch {
        // The scenarios verify that a failed read leaves deletion disabled.
    }
    const result = await vm.runInContext(`(async () => { ${input.scenario} })()`, context);
    process.stdout.write(JSON.stringify(result));
}
main().catch((error) => {
    console.error(error);
    process.exitCode = 1;
});
"""


def pickup_resources() -> dict[str, Any]:
    return {
        "pickupRegions": [{
            "id": "region-a",
            "name": "用户新建区域",
            "kind": "pickup-region",
            "standbyCoordinateId": "point-a",
            "shape": {
                "type": "CUBOID",
                "firstCornerCoordinateId": "point-a",
                "secondCornerCoordinateId": "point-b",
            },
        }],
        "cacheContainers": [{
            "id": "cache-a", "name": "缓存一", "coordinateId": "point-a",
        }],
        "pickupWorkflows": [{
            "id": "old-pickup",
            "name": "旧拾取",
            "regions": [{"regionId": "region-a"}],
            "cacheIds": ["cache-a"],
        }],
    }


def empty_resources() -> dict[str, Any]:
    return {"pickupRegions": [], "cacheContainers": [], "pickupWorkflows": []}


def saved_snapshot() -> dict[str, Any]:
    return {
        "revision": "original-revision",
        "coordinatePoints": [{
            "id": point_id,
            "name": point_id,
            "dimension": "minecraft:overworld",
            "position": {"x": index, "y": 64, "z": 0},
        } for index, point_id in enumerate(["point-a", "point-b"])],
    }


def run_ui(
    scenario: str,
    *,
    resources: dict[str, Any] | None = None,
    tasks: list[dict[str, Any]] | None = None,
    confirm: bool = True,
    fail_endpoint: str = "",
) -> dict[str, Any]:
    node_binary = os.environ.get(
        "STOWLING_NODE",
        r"C:\Program Files\nodejs\node.exe" if os.name == "nt" else "node",
    )
    input_data = {
        "sourcePath": str(COORDINATES_JS),
        "snapshot": saved_snapshot(),
        "resources": pickup_resources() if resources is None else resources,
        "tasks": tasks or [],
        "confirm": confirm,
        "failEndpoint": fail_endpoint,
        "scenario": scenario,
    }
    try:
        result = subprocess.run(
            [node_binary, "-e", DRIVER],
            input=json.dumps(input_data, ensure_ascii=False),
            text=True,
            encoding="utf-8",
            capture_output=True,
            check=True,
            timeout=15,
        )
    except FileNotFoundError:
        pytest.skip("Node is required for the coordinate UI regression tests")
    return json.loads(result.stdout)


def test_confirmation_names_resources_once_and_only_changes_draft() -> None:
    resources = pickup_resources()
    resources["cacheContainers"].append(dict(resources["cacheContainers"][0]))
    old_task = {
        "kind": "PICKUP",
        "id": "old-pickup",
        "name": "旧拾取",
        "pickup": resources["pickupWorkflows"][0],
    }
    result = run_ui(
        "ui.removePoint(0); return inspect();", resources=resources, tasks=[old_task],
    )
    assert result["ids"] == ["point-b"]
    assert result["cleanupIds"] == ["point-a"]
    assert result["dirty"] is True
    assert result["writes"] == []
    assert len(result["confirmations"]) == 1
    warning = result["confirmations"][0]
    assert "删除坐标“point-a”（point-a）" in warning
    assert warning.count("关联区域“用户新建区域”（region-a）") == 1
    assert warning.count("旧缓存“缓存一”（cache-a）") == 1
    assert warning.count("旧 PICKUP 任务“旧拾取”（old-pickup）") == 1
    assert "旧区域" not in warning
    assert "完整移除" in warning
    assert "不会立即保存" in warning
    assert "取消不作任何更改" in warning


def test_cancel_keeps_all_original_values_and_no_cleanup_authorization() -> None:
    result = run_ui(
        "const before = inspect(); ui.removePoint(0); return { before, after: inspect() };",
        confirm=False,
    )
    before, after = result["before"], result["after"]
    assert after["draft"] == before["draft"]
    assert after["cleanupIds"] == []
    assert after["dirty"] is False
    assert after["writes"] == []


def test_save_submits_original_revision_and_confirmed_cleanup_in_one_put() -> None:
    result = run_ui("ui.removePoint(0); await ui.saveCoordinates(); return inspect();")
    assert len(result["writes"]) == 1
    write = result["writes"][0]
    assert write["url"] == "/v1/config/coordinate-points"
    assert write["method"] == "PUT"
    assert write["payload"]["revision"] == "original-revision"
    assert write["payload"]["removeLegacyReferencesFor"] == ["point-a"]
    assert [point["id"] for point in write["payload"]["coordinatePoints"]] == ["point-b"]
    assert "_originalId" not in write["payload"]["coordinatePoints"][0]
    assert result["cleanupIds"] == []
    assert result["dirty"] is False


def test_no_resource_cleanup_omits_optional_request_field() -> None:
    result = run_ui(
        "ui.removePoint(0); await ui.saveCoordinates(); return inspect();",
        resources=empty_resources(),
    )
    assert result["confirmations"] == []
    assert "removeLegacyReferencesFor" not in result["writes"][0]["payload"]


@pytest.mark.parametrize("endpoint", ["/v1/config/task-resources", "/v1/task-definitions"])
def test_reference_read_failure_blocks_deletion_and_initialization_handles_it(
    endpoint: str,
) -> None:
    result = run_ui(
        "await ctx.connected(); ui.removePoint(0); return inspect();",
        fail_endpoint=endpoint,
    )
    assert result["ready"] is False
    assert result["ids"] == ["point-a", "point-b"]
    assert result["confirmations"] == []
    assert result["cleanupIds"] == []
    assert result["reloadHidden"] is False
    assert "不能删除" in result["errors"]
    assert "重新读取" in result["referenceStatus"]
    assert "读取命名坐标页面失败" in result["handled"]


def test_incomplete_reference_response_does_not_become_empty_safe_references() -> None:
    result = run_ui("ui.removePoint(0); return inspect();", resources={})
    assert result["ready"] is False
    assert result["ids"] == ["point-a", "point-b"]
    assert result["confirmations"] == []


def test_usage_deduplicates_coordinate_id_and_original_id() -> None:
    result = run_ui("return inspect();")
    assert result["usage"].count("旧缓存“缓存一”（cache-a）") == 1
    assert result["usage"].count("旧 PICKUP 任务“旧拾取”（old-pickup）") == 2
    assert result["usage"].count("关联区域“用户新建区域”（region-a）") == 2


@pytest.mark.parametrize("parameters", [
    {"coordinateId": "point-a"},
    {"sourceCoordinateId": "point-a"},
    {"fallbackCoordinateId": "point-a"},
    {"regionId": "region-a"},
    {"workflowId": "old-pickup"},
    {"cacheIds": '["cache-a"]'},
    {"containerId": "cache-a"},
    {"sourceContainerId": "cache-a"},
    {"destination": "cache-a"},
])
def test_custom_steps_protect_direct_and_region_workflow_cache_references(
    parameters: dict[str, str],
) -> None:
    task = {
        "kind": "AUTOMATION", "id": "custom-task", "name": "真实组合任务",
        "steps": [{"id": "keep-step", "type": "RUN_PICKUP", "parameters": parameters}],
    }
    result = run_ui("ui.removePoint(0); return inspect();", tasks=[task])
    assert result["ids"] == ["point-a", "point-b"]
    assert result["cleanupIds"] == []
    assert result["confirmations"] == []
    assert result["writes"] == []
    assert "真实组合任务" in result["errors"]
    assert "custom-task" in result["errors"]
    assert "keep-step" in result["errors"]
    assert "任务页" in result["errors"]


@pytest.mark.parametrize("settings,expected", [
    ({"trigger": {"kind": "ITEMS_PRESENT", "regionIds": ["region-a"]}}, "触发器"),
    ({"interruption": {"kind": "INLINE", "steps": [{
        "id": "inline-pickup", "type": "RUN_PICKUP", "parameters": {"regionId": "region-a"},
    }]}}, "插入步骤 inline-pickup"),
    ({"inline": {"steps": [{"parameters": {"workflowId": "old-pickup"}}]}}, "内嵌"),
])
def test_automation_trigger_and_inline_references_prevent_cleanup(
    settings: dict[str, Any], expected: str,
) -> None:
    task = {"kind": "AUTOMATION", "id": "auto", "name": "区域自动化", "settings": settings}
    result = run_ui("ui.removePoint(0); return inspect();", tasks=[task])
    assert result["ids"] == ["point-a", "point-b"]
    assert result["confirmations"] == []
    assert "区域自动化" in result["errors"]
    assert expected in result["errors"]


def test_saved_automation_workflows_are_protected_and_never_listed_for_cleanup() -> None:
    resources = pickup_resources()
    resources["automationWorkflows"] = [{
        "id": "new-auto", "name": "纯区域拾取",
        "steps": [{
            "id": "pure", "type": "RUN_PICKUP",
            "parameters": {"regionId": "region-a"},
        }],
    }]
    result = run_ui("ui.removePoint(0); return inspect();", resources=resources)
    assert result["ids"] == ["point-a", "point-b"]
    assert "纯区域拾取" in result["errors"]
    assert result["confirmations"] == []


@pytest.mark.parametrize("settings", [
    {"interruption": {"kind": "INLINE", "steps": [{
        "id": "legacy-interrupt", "type": "TAKE", "parameters": {"containerId": "cache-a"},
    }]}},
    {"trigger": {"regionIds": ["region-a"]}},
])
def test_legacy_settings_only_reference_blocks_instead_of_deleting_whole_workflow(
    settings: dict[str, Any],
) -> None:
    resources = pickup_resources()
    survivor = {
        "id": "unrelated-old", "name": "仅设置引用的旧任务",
        "regions": [{"regionId": "other-region"}], "cacheIds": ["other-cache"],
        "settings": settings,
    }
    resources["pickupWorkflows"].append(survivor)
    task = {
        "kind": "PICKUP", "id": survivor["id"], "name": survivor["name"],
        "pickup": survivor, "settings": settings,
    }
    result = run_ui(
        "const plan = ui.deletionPlan(new Set(['point-a'])); ui.removePoint(0);"
        "return { workflowIds: [...plan.workflowIds], result: inspect() };",
        resources=resources,
        tasks=[task],
    )
    assert result["workflowIds"] == ["old-pickup"]
    assert result["result"]["confirmations"] == []
    assert result["result"]["ids"] == ["point-a", "point-b"]
    assert result["result"]["errors"].count("仅设置引用的旧任务") == 1


def test_multiple_deletions_use_saved_union_without_reconfirming_shared_resources() -> None:
    resources = pickup_resources()
    resources["cacheContainers"].append({
        "id": "cache-b", "name": "缓存二", "coordinateId": "point-b",
    })
    result = run_ui(
        "ui.removePoint(0); ui.removePoint(0); await ui.saveCoordinates(); return inspect();",
        resources=resources,
    )
    assert result["ids"] == []
    assert len(result["confirmations"]) == 2
    second = result["confirmations"][1]
    assert "旧缓存“缓存二”（cache-b）" in second
    assert "关联区域“用户新建区域”（region-a）" not in second
    assert "旧 PICKUP 任务“旧拾取”（old-pickup）" not in second
    assert result["writes"][0]["payload"]["removeLegacyReferencesFor"] == ["point-a", "point-b"]


def test_second_coordinate_with_only_already_confirmed_resources_needs_no_new_cleanup_id() -> None:
    result = run_ui(
        "ui.removePoint(0); ui.removePoint(0); await ui.saveCoordinates(); return inspect();",
    )
    assert result["ids"] == []
    assert len(result["confirmations"]) == 1
    assert result["writes"][0]["payload"]["removeLegacyReferencesFor"] == ["point-a"]


def test_cancelling_second_confirmation_preserves_first_confirmed_deletion() -> None:
    resources = pickup_resources()
    resources["cacheContainers"].append({
        "id": "cache-b", "name": "缓存二", "coordinateId": "point-b",
    })
    result = run_ui(
        "ui.removePoint(0); const before = inspect(); ctx.confirm = false;"
        "ui.removePoint(0); return { before, after: inspect() };",
        resources=resources,
    )
    assert result["after"]["draft"] == result["before"]["draft"]
    assert result["after"]["cleanupIds"] == ["point-a"]
    assert result["after"]["writes"] == []


def test_cleanup_payload_filters_to_saved_coordinates_still_absent_from_draft() -> None:
    result = run_ui(
        "ui.removePoint(0); ui.state.cleanupIds.add('never-saved');"
        "ui.state.draft.coordinatePoints.push({ ...ctx.snapshot.coordinatePoints[0] });"
        "return { cleanup: ui.cleanupCoordinateIds() };",
    )
    assert result["cleanup"] == []


def test_discard_and_reload_clear_cleanup_and_restore_saved_points() -> None:
    result = run_ui(
        "ui.removePoint(0); await ui.discardChanges(); return inspect();",
    )
    assert result["ids"] == ["point-a", "point-b"]
    assert result["cleanupIds"] == []
    assert result["dirty"] is False
    assert result["writes"] == []


def test_discard_clears_authorization_even_when_reread_fails() -> None:
    result = run_ui(
        "ui.removePoint(0); ctx.failEndpoint = '/v1/config/task-resources';"
        "await ui.discardChanges(); return inspect();",
    )
    assert result["cleanupIds"] == []
    assert result["ids"] == ["point-a", "point-b"]
    assert result["ready"] is False
    assert result["writes"] == []


def test_failed_atomic_save_preserves_draft_revision_and_cleanup_for_retry() -> None:
    result = run_ui(
        "ui.removePoint(0); const before = inspect(); ctx.writeFailure = true;"
        "await ui.saveCoordinates(); return { before, after: inspect() };",
    )
    assert result["after"]["draft"] == result["before"]["draft"]
    assert result["after"]["cleanupIds"] == ["point-a"]
    assert result["after"]["dirty"] is True
    assert result["after"]["draft"]["revision"] == "original-revision"
    assert "atomic save refused" in result["after"]["errors"]


def test_reference_failure_after_confirmation_prevents_saving_deletion_draft() -> None:
    result = run_ui(
        "ui.removePoint(0); ctx.failEndpoint = '/v1/task-definitions';"
        "try { await ui.loadReferences(); } catch {}"
        "await ui.saveCoordinates(); return inspect();",
    )
    assert result["ids"] == ["point-b"]
    assert result["cleanupIds"] == ["point-a"]
    assert result["writes"] == []
    assert result["saveDisabled"] is True


def test_references_finishing_before_coordinates_still_render_usage_after_snapshot() -> None:
    result = run_ui(
        "ui.state.snapshot = null; ui.state.draft = null; await ui.loadReferences();"
        "ui.renderSnapshot(ctx.snapshot); return inspect();",
    )
    assert result["ready"] is True
    assert "关联区域“用户新建区域”（region-a）" in result["usage"]
    assert result["usage"].count("旧缓存“缓存一”（cache-a）") == 1
    assert "当前没有" not in result["usage"]


def test_html_explains_explicit_cleanup_and_reread_discards_draft() -> None:
    html = COORDINATES_HTML.read_text(encoding="utf-8")
    source = COORDINATES_JS.read_text(encoding="utf-8")
    assert 'id="coordinate-reference-status"' in html
    assert 'id="reload-coordinate-references-button"' in html
    assert "重新读取（放弃草稿）" in html
    assert "关联区域、旧缓存与旧 PICKUP 任务" in html
    assert "取消确认不作更改" in html
    assert 'elements.reloadReferences.addEventListener("click"' in source
    assert "state.cleanupIds.clear();" in source
