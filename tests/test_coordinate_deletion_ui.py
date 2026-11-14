"""Coordinate deletion UI regressions; execute JavaScript only when pytest is requested."""

import json
import os
from pathlib import Path
import subprocess
from typing import Any

import pytest


WEB_ROOT = Path(__file__).resolve().parents[1] / "src/main/resources/stowling-web"
COORDINATES_JS = WEB_ROOT / "coordinates.js"

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
    writeFailure: null,
    holdWrite: false,
    failAfterWriteEndpoint: "",
    successfulWrites: 0,
    confirmations: [],
    requests: [],
    writes: [],
    toasts: [],
    handled: []
};

class ApiError extends Error {
    /** @param {object} failure @returns {void} */
    constructor(failure) {
        super(failure.message);
        this.status = failure.status;
        this.code = failure.code;
    }
}

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

/** @param {unknown} source @param {Set<string>} ids @returns {boolean} */
function usesCoordinate(source, ids) {
    if (!source || typeof source !== "object") {
        return false;
    }
    return Object.entries(source).some(([key, value]) => {
        return ((key === "coordinateId" || key.endsWith("CoordinateId")) && ids.has(value))
            || usesCoordinate(value, ids);
    });
}
/** @param {object} body @param {Set<string>} regions @param {Set<string>} caches
 * @returns {boolean} */
function usesLegacyResources(body, regions, caches) {
    return (body.regions || []).some((binding) => regions.has(binding.regionId))
        || (body.cacheIds || []).some((id) => caches.has(id));
}
/** @param {object} payload @returns {void} */
function applyCoordinateWrite(payload) {
    const retainedIds = new Set(payload.coordinatePoints.map((point) => point.id));
    const savedIds = new Set(ctx.snapshot.coordinatePoints.map((point) => point.id));
    const removedIds = new Set((payload.removeLegacyReferencesFor || [])
        .filter((id) => savedIds.has(id) && !retainedIds.has(id)));
    const regions = new Set(ctx.resources.pickupRegions
        .filter((region) => usesCoordinate(region, removedIds)).map((region) => region.id));
    const caches = new Set(ctx.resources.cacheContainers
        .filter((cache) => removedIds.has(cache.coordinateId)).map((cache) => cache.id));
    const workflows = new Set([
        ...ctx.resources.pickupWorkflows,
        ...ctx.definitions.tasks.filter((task) => task.kind === "PICKUP")
    ].filter((entry) => usesLegacyResources(entry.pickup || entry, regions, caches))
        .map((entry) => entry.id));
    ctx.resources.pickupRegions = ctx.resources.pickupRegions
        .filter((region) => !regions.has(region.id));
    ctx.resources.cacheContainers = ctx.resources.cacheContainers
        .filter((cache) => !caches.has(cache.id));
    ctx.resources.pickupWorkflows = ctx.resources.pickupWorkflows
        .filter((workflow) => !workflows.has(workflow.id));
    ctx.definitions.tasks = ctx.definitions.tasks
        .filter((task) => task.kind !== "PICKUP" || !workflows.has(task.id));
    ctx.successfulWrites += 1;
    ctx.snapshot = {
        revision: `saved-revision-${ctx.successfulWrites}`,
        coordinatePoints: clone(payload.coordinatePoints)
    };
}

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
    /** @param {string} dimension @returns {string} */
    dimensionLabel(dimension) { return dimension; },
    /** @param {string} message @param {boolean} error @returns {void} */
    showToast(message, error = false) { ctx.toasts.push({ message, error }); },
    /** @param {unknown} error @param {string} label @returns {void} */
    handleError(error, label) { ctx.handled.push(label); },
    /** @param {() => Promise<void>} callback @returns {void} */
    onConnected(callback) { ctx.connected = callback; },
    /** @returns {void} */
    mountPage() {},
    ApiError,
    /** @param {string} url @param {object | null} options @returns {Promise<object>} */
    async request(url, options = null) {
        ctx.requests.push({ url, method: options?.method || "GET" });
        if (options?.method === "PUT") {
            if (url !== "/v1/config/coordinate-points") {
                throw new Error(`unexpected write: ${url}`);
            }
            const payload = JSON.parse(options.body);
            ctx.writes.push({ url, method: options.method, payload });
            if (ctx.holdWrite) {
                await new Promise((resolve) => { ctx.releaseWrite = resolve; });
            }
            if (ctx.writeFailure) {
                throw new ApiError(ctx.writeFailure);
            }
            if (payload.revision !== ctx.snapshot.revision) {
                throw new Error("stale coordinate revision");
            }
            applyCoordinateWrite(payload);
            if (ctx.failAfterWriteEndpoint) {
                ctx.failEndpoint = ctx.failAfterWriteEndpoint;
            }
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
    /** @param {string} id @returns {Element | null} */
    getElementById(id) { return nodes.get(`#${id}`) || null; },
    /** @returns {Array<Element>} */
    querySelectorAll() { return []; },
    /** @returns {void} */
    addEventListener() {}
};
const context = vm.createContext({ window, document, console, ctx, Error });
const original = fs.readFileSync(input.sourcePath, "utf8");
const source = original.replace('    Stowling.mountPage("coordinates");', `
    window.ui = {
        state, renderSnapshot, renderUsage, removePoint, loadReferences, loadCoordinates,
        saveCoordinates, discardChanges
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
        snapshot: window.ui.state.snapshot,
        serverSnapshot: ctx.snapshot,
        resources: ctx.resources,
        tasks: ctx.definitions.tasks,
        cleanupIds: [...(window.ui.state.cleanupIds || [])],
        writing: Boolean(window.ui.state.writing),
        dirty: window.ui.state.dirty,
        ready: window.ui.state.referencesReady,
        errors: text(node("#coordinate-errors")),
        usage: text(node("#coordinate-usage")),
        referenceStatus: text(node("#coordinate-reference-status")),
        reloadHidden: node("#reload-coordinate-references-button").hidden,
        saveDisabled: node("#save-coordinate-button").disabled,
        addDisabled: node("#add-coordinate-button").disabled,
        discardDisabled: node("#discard-coordinate-button").disabled,
        status: text(node("#coordinate-status")),
        confirmations: ctx.confirmations,
        requests: ctx.requests,
        writes: ctx.writes,
        toasts: ctx.toasts,
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
            "type": "POINT",
        } for index, point_id in enumerate(["point-a", "point-b"])],
    }


def run_ui(
    scenario: str,
    *,
    resources: dict[str, Any] | None = None,
    tasks: list[dict[str, Any]] | None = None,
    snapshot: dict[str, Any] | None = None,
    confirm: bool = True,
    fail_endpoint: str = "",
) -> dict[str, Any]:
    node_binary = os.environ.get(
        "STOWLING_NODE",
        r"C:\Program Files\nodejs\node.exe" if os.name == "nt" else "node",
    )
    input_data = {
        "sourcePath": str(COORDINATES_JS),
        "snapshot": saved_snapshot() if snapshot is None else snapshot,
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


def test_confirmed_deletion_is_persisted_once_and_does_not_return_after_refresh() -> None:
    resources = pickup_resources()
    resources["cacheContainers"].append(dict(resources["cacheContainers"][0]))
    old_task = {
        "kind": "PICKUP", "id": "old-pickup", "name": "旧拾取",
        "pickup": resources["pickupWorkflows"][0],
    }
    result = run_ui(
        "await ui.removePoint(0); const deleted = inspect();"
        "await ui.loadCoordinates(); return { deleted, refreshed: inspect() };",
        resources=resources,
        tasks=[old_task],
    )
    deleted, refreshed = result["deleted"], result["refreshed"]
    assert deleted["ids"] == refreshed["ids"] == ["point-b"]
    assert deleted["snapshot"] == deleted["serverSnapshot"]
    assert deleted["dirty"] is False
    assert deleted["cleanupIds"] == []
    assert len(deleted["writes"]) == len(refreshed["writes"]) == 1
    write = deleted["writes"][0]
    assert write["url"] == "/v1/config/coordinate-points"
    assert write["method"] == "PUT"
    assert write["payload"]["revision"] == "original-revision"
    assert write["payload"]["removeLegacyReferencesFor"] == ["point-a"]
    assert write["payload"]["coordinatePoints"] == saved_snapshot()["coordinatePoints"][1:]
    warning = deleted["confirmations"][0]
    assert warning.count("关联区域“用户新建区域”（region-a）") == 1
    assert warning.count("旧缓存“缓存一”（cache-a）") == 1
    assert warning.count("旧 PICKUP 任务“旧拾取”（old-pickup）") == 1
    assert "仅修改草稿" not in warning
    assert deleted["resources"] == empty_resources()
    assert deleted["tasks"] == []


@pytest.mark.parametrize("association", ["none", "region", "cache"])
def test_no_task_association_never_mentions_tasks_in_delete_confirmation(
    association: str,
) -> None:
    resources = empty_resources()
    if association == "region":
        resources["pickupRegions"] = pickup_resources()["pickupRegions"]
    elif association == "cache":
        resources["cacheContainers"] = pickup_resources()["cacheContainers"]
    result = run_ui(
        "ui.state.draft.coordinatePoints[0].name = '删除前未保存改名';"
        "ui.state.dirty = true; await ui.removePoint(0); return inspect();",
        resources=resources,
    )
    assert len(result["confirmations"]) == 1
    warning = result["confirmations"][0]
    assert "point-a" in warning
    assert "任务" not in warning
    assert "PICKUP" not in warning
    assert "仅修改草稿" not in warning
    assert result["ids"] == ["point-b"]
    assert result["dirty"] is False
    assert len(result["writes"]) == 1
    payload = result["writes"][0]["payload"]
    if association == "none":
        assert "removeLegacyReferencesFor" not in payload
        assert "关联区域" not in warning
        assert "旧缓存" not in warning
    else:
        assert payload["removeLegacyReferencesFor"] == ["point-a"]
        assert ("关联区域" in warning) is (association == "region")
        assert ("旧缓存" in warning) is (association == "cache")


def test_cancel_preserves_complete_draft_and_snapshot_without_any_request() -> None:
    result = run_ui(
        "ui.state.draft.coordinatePoints[1].position.x = 42; ui.state.dirty = true;"
        "ctx.requests.length = 0; const before = inspect(); await ui.removePoint(0);"
        "return { before, after: inspect() };",
        confirm=False,
    )
    before, after = result["before"], result["after"]
    assert after["draft"] == before["draft"]
    assert after["snapshot"] == before["snapshot"] == after["serverSnapshot"]
    assert after["dirty"] is True
    assert after["cleanupIds"] == []
    assert after["requests"] == []
    assert len(after["confirmations"]) == 1


def test_unsaved_coordinate_is_removed_only_from_draft_even_if_references_failed() -> None:
    result = run_ui(
        "ui.state.draft.coordinatePoints[1].name = '其他未保存改名';"
        "ui.state.draft.coordinatePoints.push({"
        "...ctx.snapshot.coordinatePoints[0], id: 'unsaved', name: '未保存', _originalId: ''"
        "}); ui.state.dirty = true; ctx.requests.length = 0; const before = inspect();"
        "await ui.removePoint(2); return { before, after: inspect() };",
        fail_endpoint="/v1/config/task-resources",
    )
    before, after = result["before"], result["after"]
    assert after["ids"] == ["point-a", "point-b"]
    assert after["draft"]["coordinatePoints"] == before["draft"]["coordinatePoints"][:2]
    assert after["snapshot"] == before["snapshot"] == after["serverSnapshot"]
    assert after["dirty"] is True
    assert after["confirmations"] == after["requests"] == []


@pytest.mark.parametrize("endpoint", ["/v1/config/task-resources", "/v1/task-definitions"])
def test_failed_reference_read_blocks_saved_deletion_without_any_request(endpoint: str) -> None:
    result = run_ui(
        "ctx.requests.length = 0; const before = inspect(); await ui.removePoint(0);"
        "return { before, after: inspect() };",
        fail_endpoint=endpoint,
    )
    before, after = result["before"], result["after"]
    assert after["ready"] is False
    assert after["draft"] == before["draft"]
    assert after["snapshot"] == before["snapshot"]
    assert after["confirmations"] == after["requests"] == []
    assert "重新读取" in after["referenceStatus"]
    assert after["reloadHidden"] is False


@pytest.mark.parametrize("parameters", [
    {"coordinateId": "point-a"},
    {"regionId": "region-a"},
    {"workflowId": "old-pickup"},
    {"cacheIds": '["cache-a"]'},
])
def test_real_custom_task_references_block_deletion_and_leave_everything_unchanged(
    parameters: dict[str, str],
) -> None:
    task = {
        "kind": "AUTOMATION", "id": "custom-task", "name": "真实组合任务",
        "steps": [{"id": "keep-step", "type": "RUN_PICKUP", "parameters": parameters}],
    }
    result = run_ui(
        "ctx.requests.length = 0; const before = inspect(); await ui.removePoint(0);"
        "return { before, after: inspect() };",
        tasks=[task],
    )
    before, after = result["before"], result["after"]
    assert after["draft"] == before["draft"]
    assert after["snapshot"] == before["snapshot"]
    assert after["resources"] == before["resources"]
    assert after["tasks"] == before["tasks"]
    assert after["requests"] == after["confirmations"] == []
    assert "custom-task" in after["errors"]
    assert "keep-step" in after["errors"]


@pytest.mark.parametrize("status", [409, 500])
def test_failed_delete_preserves_draft_and_snapshot_and_displays_api_message(status: int) -> None:
    result = run_ui(
        "ui.state.draft.coordinatePoints[1].position.x = 42; ui.state.dirty = true;"
        f"ctx.writeFailure = {{ status: {status}, code: 'invalid_coordinate_points',"
        "message: 'cacheContainers[0].coordinateId: point-a 仍被容器引用' };"
        "const before = inspect(); await ui.removePoint(0);"
        "return { before, after: inspect() };",
    )
    before, after = result["before"], result["after"]
    assert after["draft"] == before["draft"]
    assert after["snapshot"] == before["snapshot"] == after["serverSnapshot"]
    assert after["resources"] == before["resources"]
    assert after["ids"] == ["point-a", "point-b"]
    assert after["dirty"] is True
    assert after["writing"] is False
    assert len(after["writes"]) == 1
    visible = after["errors"] + after["status"] + " ".join(
        toast["message"] for toast in after["toasts"]
    )
    assert "cacheContainers[0].coordinateId: point-a 仍被容器引用" in visible
    assert not any(not toast["error"] for toast in after["toasts"])


def test_delete_saves_only_snapshot_and_preserves_other_draft_edits_additions_and_order() -> None:
    snapshot = saved_snapshot()
    snapshot["coordinatePoints"].append({
        "id": "point-c", "name": "point-c", "dimension": "minecraft:overworld",
        "position": {"x": 2, "y": 64, "z": 0}, "type": "POINT",
    })
    result = run_ui(
        "const [a, b, c] = ui.state.draft.coordinatePoints;"
        "b.name = '未保存改名'; b.position.x = 99;"
        "const added = { ...a, id: 'draft-only', name: '未保存新增', _originalId: '' };"
        "ui.state.draft.coordinatePoints = [c, added, a, b]; ui.state.dirty = true;"
        "const before = inspect(); await ui.removePoint(2); const deleted = inspect();"
        "await ui.saveCoordinates(); return { before, deleted, saved: inspect() };",
        resources=empty_resources(),
        snapshot=snapshot,
    )
    before, deleted, saved = result["before"], result["deleted"], result["saved"]
    first_payload = deleted["writes"][0]["payload"]
    assert first_payload["coordinatePoints"] == snapshot["coordinatePoints"][1:]
    assert first_payload["revision"] == snapshot["revision"]
    assert "removeLegacyReferencesFor" not in first_payload
    assert deleted["ids"] == ["point-c", "draft-only", "point-b"]
    assert deleted["draft"]["coordinatePoints"] == [
        point for point in before["draft"]["coordinatePoints"] if point["id"] != "point-a"
    ]
    assert deleted["dirty"] is True
    assert deleted["snapshot"] == deleted["serverSnapshot"]
    assert len(saved["writes"]) == 2
    second_payload = saved["writes"][1]["payload"]
    assert second_payload["revision"] == deleted["snapshot"]["revision"]
    assert [point["id"] for point in second_payload["coordinatePoints"]] == deleted["ids"]
    assert second_payload["coordinatePoints"][-1]["name"] == "未保存改名"
    assert second_payload["coordinatePoints"][-1]["position"]["x"] == 99
    assert all("_originalId" not in point for point in second_payload["coordinatePoints"])
    assert "removeLegacyReferencesFor" not in second_payload
    assert saved["dirty"] is False


def test_two_independent_deletions_use_latest_revision_and_refreshed_resource_cleanup() -> None:
    result = run_ui(
        "await ui.removePoint(0); const first = inspect(); await ui.removePoint(0);"
        "return { first, second: inspect() };",
    )
    first, second = result["first"], result["second"]
    assert len(first["writes"]) == 1
    assert len(second["writes"]) == 2
    assert second["ids"] == []
    assert first["writes"][0]["payload"]["removeLegacyReferencesFor"] == ["point-a"]
    assert first["snapshot"]["revision"] == "saved-revision-1"
    second_payload = second["writes"][1]["payload"]
    assert second_payload["revision"] == first["snapshot"]["revision"]
    assert second_payload["coordinatePoints"] == []
    assert "removeLegacyReferencesFor" not in second_payload
    assert len(second["confirmations"]) == 2
    assert "任务" not in second["confirmations"][1]
    assert "关联区域" not in second["confirmations"][1]
    assert "旧缓存" not in second["confirmations"][1]
    assert second["cleanupIds"] == []
    assert second["resources"] == empty_resources()


def test_writing_lock_keeps_coordinate_until_success_and_ignores_other_delete_or_save() -> None:
    result = run_ui(
        "ui.state.draft.coordinatePoints[1].name = '其他未保存'; ui.state.dirty = true;"
        "ctx.requests.length = 0; ctx.holdWrite = true; const pending = ui.removePoint(0);"
        "const during = inspect(); await ui.removePoint(1); await ui.saveCoordinates();"
        "const blocked = inspect(); ctx.holdWrite = false; ctx.releaseWrite(); await pending;"
        "return { during, blocked, after: inspect() };",
        resources=empty_resources(),
    )
    during, blocked, after = result["during"], result["blocked"], result["after"]
    assert during["writing"] is True
    assert during["ids"] == ["point-a", "point-b"]
    assert during["snapshot"]["revision"] == "original-revision"
    assert during["addDisabled"] and during["discardDisabled"] and during["saveDisabled"]
    assert blocked["draft"] == during["draft"]
    assert len(blocked["writes"]) == len(blocked["requests"]) == 1
    assert len(blocked["confirmations"]) == 1
    assert after["ids"] == ["point-b"]
    assert after["writing"] is False
    assert len(after["writes"]) == 1
    assert after["draft"]["coordinatePoints"][0]["name"] == "其他未保存"
    assert after["dirty"] is True


def test_successful_delete_with_failed_reference_refresh_is_not_a_delete_failure() -> None:
    result = run_ui(
        "ctx.failAfterWriteEndpoint = '/v1/config/task-resources';"
        "await ui.removePoint(0); return inspect();",
    )
    assert result["ids"] == ["point-b"]
    assert result["snapshot"] == result["serverSnapshot"]
    assert result["ready"] is False
    assert result["writing"] is False
    assert result["reloadHidden"] is False
    assert len(result["writes"]) == 1
    visible = result["errors"] + result["status"] + result["referenceStatus"] + " ".join(
        toast["message"] for toast in result["toasts"]
    )
    assert "引用" in visible
    assert "删除失败" not in visible
    assert any("删除" in toast["message"] and not toast["error"] for toast in result["toasts"])


def test_immediate_delete_preserves_remaining_radius_and_area_fields() -> None:
    snapshot = {
        "revision": "range-revision",
        "coordinatePoints": [
            {
                "id": "remove-me",
                "name": "待删除",
                "dimension": "minecraft:overworld",
                "position": {"x": 0, "y": 64, "z": 0},
                "type": "POINT",
            },
            {
                "id": "sphere-center",
                "name": "球心",
                "dimension": "minecraft:overworld",
                "position": {"x": 10, "y": 70, "z": -4},
                "type": "SPHERE",
                "radius": 12,
            },
            {
                "id": "area-bounds",
                "name": "反向区域",
                "dimension": "minecraft:the_nether",
                "position": {"x": 8, "y": 90, "z": 2},
                "type": "AREA",
                "secondCorner": {"x": -3, "y": 40, "z": -7},
            },
        ],
    }
    result = run_ui(
        "await ui.removePoint(0); return inspect();",
        resources=empty_resources(),
        snapshot=snapshot,
    )
    remaining = snapshot["coordinatePoints"][1:]
    assert result["writes"][0]["payload"]["coordinatePoints"] == remaining
    draft_remaining = [
        {key: value for key, value in point.items() if key != "_originalId"}
        for point in result["draft"]["coordinatePoints"]
    ]
    assert draft_remaining == remaining
    assert result["snapshot"]["coordinatePoints"] == remaining
    assert result["draft"]["coordinatePoints"][0]["radius"] == 12
    assert result["draft"]["coordinatePoints"][1]["secondCorner"] == {
        "x": -3, "y": 40, "z": -7,
    }
    assert result["dirty"] is False
