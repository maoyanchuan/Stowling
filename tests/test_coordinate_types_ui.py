"""Coordinate type and range editor tests; execute JavaScript only under pytest."""

import json
import os
from pathlib import Path
import subprocess
from typing import Any

import pytest


ROOT = Path(__file__).resolve().parents[1]
WEB_ROOT = ROOT / "src" / "main" / "resources" / "stowling-web"
COORDINATES_JS = WEB_ROOT / "coordinates.js"
COORDINATES_HTML = WEB_ROOT / "coordinates.html"

DRIVER = r"""
const fs = require("node:fs");
const vm = require("node:vm");
const input = JSON.parse(fs.readFileSync(0, "utf8"));
/** @param {unknown} value @returns {unknown} */
function clone(value) { return JSON.parse(JSON.stringify(value)); }

const ctx = {
    requests: [],
    writes: [],
    toasts: [],
    handled: [],
    failWrite: input.failWrite,
    server: {
        snapshot: clone(input.snapshot),
        resources: {
            pickupRegions: [],
            cacheContainers: [],
            pickupWorkflows: []
        },
        definitions: { tasks: [] },
        writes: 0
    }
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
    showToast(message, error = false) {
        ctx.toasts.push({ message, error });
    },
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
            if (ctx.failWrite) {
                throw new ApiError(ctx.failWrite);
            }
            if (payload.revision !== ctx.server.snapshot.revision) {
                throw new ApiError({
                    status: 409,
                    code: "config_revision_conflict",
                    message: "coordinate revision changed"
                });
            }
            ctx.server.writes += 1;
            ctx.server.snapshot = {
                revision: `saved-${ctx.server.writes}`,
                coordinatePoints: clone(payload.coordinatePoints)
            };
            return clone(ctx.server.snapshot);
        }
        if (url === "/v1/config/coordinate-points") {
            return clone(ctx.server.snapshot);
        }
        if (url === "/v1/config/task-resources") {
            return clone(ctx.server.resources);
        }
        if (url === "/v1/task-definitions") {
            return clone(ctx.server.definitions);
        }
        throw new Error(`unexpected request: ${url}`);
    }
};
const window = { Stowling };
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
const context = vm.createContext({ window, document, console, Error, JSON });
const original = fs.readFileSync(input.sourcePath, "utf8");
const source = original.replace(
    '    Stowling.mountPage("coordinates");',
    `
    window.ui = {
        state, renderSnapshot, changePointType, changeRadiusShape, validateDraft,
        toRequestPoint, saveCoordinates
    };
    Stowling.mountPage("coordinates");
`
);
vm.runInContext(source, context);
context.ui = window.ui;

/** @returns {object} */
function inspect() {
    return clone({
        snapshot: window.ui.state.snapshot,
        draft: window.ui.state.draft,
        dirty: window.ui.state.dirty,
        requests: ctx.requests,
        writes: ctx.writes,
        toasts: ctx.toasts,
        handled: ctx.handled,
        errors: text(node("#coordinate-errors")),
        status: text(node("#coordinate-status"))
    });
}
context.inspect = inspect;

/** @returns {Promise<void>} */
async function main() {
    window.ui.renderSnapshot(ctx.server.snapshot);
    const result = await vm.runInContext(
        `(async () => { ${input.scenario} })()`,
        context
    );
    process.stdout.write(JSON.stringify(result));
}
main().catch((error) => {
    console.error(error);
    process.exitCode = 1;
});
"""


def legacy_snapshot() -> dict[str, Any]:
    return {
        "revision": "legacy-revision",
        "coordinatePoints": [{
            "id": "point-a",
            "name": "旧坐标",
            "dimension": "minecraft:overworld",
            "position": {"x": 4, "y": 65, "z": -2},
        }],
    }


def run_ui(
    scenario: str,
    *,
    snapshot: dict[str, Any] | None = None,
    fail_write: dict[str, Any] | None = None,
) -> dict[str, Any]:
    node_binary = os.environ.get(
        "STOWLING_NODE",
        r"C:\Program Files\nodejs\node.exe" if os.name == "nt" else "node",
    )
    input_data = {
        "sourcePath": str(COORDINATES_JS),
        "snapshot": legacy_snapshot() if snapshot is None else snapshot,
        "failWrite": fail_write,
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
        pytest.skip("Node is required for coordinate type UI tests")
    return json.loads(result.stdout)


def test_coordinate_page_configures_ranges_without_legacy_region_module() -> None:
    html = COORDINATES_HTML.read_text(encoding="utf-8")
    script = COORDINATES_JS.read_text(encoding="utf-8")
    assert "坐标与范围" in html
    assert "coordinate-regions.js" not in html
    assert "coordinate-region-form" not in html
    assert "coordinate-type-" in script
    assert "coordinate-radius-shape-" in script
    assert "coordinate-radius-" in script
    assert "coordinate-second-" in script
    assert '["POINT", "坐标点"]' in script
    assert '["RADIUS", "半径（立方体/球）"]' in script
    assert '["AREA", "区域"]' in script
    assert '["CUBE", "立方体"]' in script
    assert '["SPHERE", "球"]' in script
    assert "idInput.readOnly = true;" in script
    assert "point.radius = 8;" in script
    assert "立方体范围（XYZ 中心 ± 半径）" in script
    assert "球范围（距离 ≤ 半径）" in script
    assert "window.StowlingCoordinateRegions" not in script


def test_snapshot_defaults_missing_type_and_preserves_range_fields() -> None:
    snapshot = {
        "revision": "types-revision",
        "coordinatePoints": [
            legacy_snapshot()["coordinatePoints"][0],
            {
                "id": "sphere-a",
                "name": "球形范围",
                "dimension": "minecraft:overworld",
                "position": {"x": 10, "y": 70, "z": 12},
                "type": "CUBE",
                "radius": "11",
            },
            {
                "id": "area-a",
                "name": "反向区域",
                "dimension": "minecraft:the_nether",
                "position": {"x": 8, "y": 90, "z": 2},
                "type": "AREA",
                "secondCorner": {"x": -3, "y": 40, "z": -7},
            },
        ],
    }
    result = run_ui("return inspect();", snapshot=snapshot)
    points = result["draft"]["coordinatePoints"]
    assert points[0]["type"] == "POINT"
    assert "radius" not in points[0]
    assert "secondCorner" not in points[0]
    assert points[1]["type"] == "CUBE"
    assert points[1]["radius"] == 11
    assert points[2]["type"] == "AREA"
    assert points[2]["secondCorner"] == {"x": -3, "y": 40, "z": -7}


def test_type_switches_keep_center_and_clear_inapplicable_fields() -> None:
    result = run_ui(
        """
        const point = ui.state.draft.coordinatePoints[0];
        const originalPosition = JSON.parse(JSON.stringify(point.position));
        ui.changePointType(point, "RADIUS");
        const cube = JSON.parse(JSON.stringify(point));
        point.radius = 17;
        ui.changeRadiusShape(point, "SPHERE");
        const sphere = JSON.parse(JSON.stringify(point));
        ui.changePointType(point, "AREA");
        const area = JSON.parse(JSON.stringify(point));
        point.secondCorner = { x: -5, y: 20, z: 9 };
        ui.changePointType(point, "POINT");
        return {
            originalPosition,
            cube,
            sphere,
            area,
            point: JSON.parse(JSON.stringify(point))
        };
        """
    )
    assert result["cube"]["type"] == "CUBE"
    assert result["cube"]["radius"] == 8
    assert result["cube"]["position"] == result["originalPosition"]
    assert "secondCorner" not in result["cube"]
    assert result["sphere"]["type"] == "SPHERE"
    assert result["sphere"]["radius"] == 17
    assert result["sphere"]["position"] == result["originalPosition"]
    assert result["area"]["type"] == "AREA"
    assert result["area"]["secondCorner"] == result["originalPosition"]
    assert "radius" not in result["area"]
    assert result["point"]["type"] == "POINT"
    assert result["point"]["position"] == result["originalPosition"]
    assert "radius" not in result["point"]
    assert "secondCorner" not in result["point"]


def test_request_omits_wrong_fields_and_serializes_radius_as_number() -> None:
    result = run_ui(
        """
        return {
            sphere: ui.toRequestPoint({
                id: "sphere", name: "球", dimension: "minecraft:overworld",
                position: { x: 1, y: 2, z: 3 }, type: "SPHERE", radius: "9",
                secondCorner: { x: 99, y: 99, z: 99 }
            }),
            area: ui.toRequestPoint({
                id: "area", name: "区域", dimension: "minecraft:the_end",
                position: { x: 5, y: 8, z: 1 }, type: "AREA", radius: 20,
                secondCorner: { x: -2, y: 40, z: 3 }
            }),
            point: ui.toRequestPoint({
                id: "point", name: "点", dimension: "minecraft:overworld",
                position: { x: 0, y: 64, z: 0 }, radius: 8,
                secondCorner: { x: 1, y: 1, z: 1 }
            })
        };
        """
    )
    assert result["sphere"]["type"] == "SPHERE"
    assert result["sphere"]["radius"] == 9
    assert isinstance(result["sphere"]["radius"], int)
    assert "secondCorner" not in result["sphere"]
    assert result["area"]["type"] == "AREA"
    assert result["area"]["secondCorner"] == {"x": -2, "y": 40, "z": 3}
    assert "radius" not in result["area"]
    assert result["point"]["type"] == "POINT"
    assert "radius" not in result["point"]
    assert "secondCorner" not in result["point"]


def test_validation_rejects_bad_radius_and_second_corner() -> None:
    result = run_ui(
        """
        const point = ui.state.draft.coordinatePoints[0];
        ui.changePointType(point, "AREA");
        point.secondCorner.x = "bad";
        const areaErrors = ui.validateDraft();
        ui.changePointType(point, "RADIUS");
        point.radius = 0;
        const radiusErrors = ui.validateDraft();
        point.radius = 129;
        const highRadiusErrors = ui.validateDraft();
        return { areaErrors, radiusErrors, highRadiusErrors };
        """
    )
    assert any("第二角点 X" in error for error in result["areaErrors"])
    assert any("半径必须是 1-128" in error for error in result["radiusErrors"])
    assert any("半径必须是 1-128" in error for error in result["highRadiusErrors"])


def test_save_refresh_keeps_area_fields_and_ordered_corners() -> None:
    snapshot = {
        "revision": "area-revision",
        "coordinatePoints": [{
            "id": "area-a",
            "name": "区域",
            "dimension": "minecraft:overworld",
            "position": {"x": 12, "y": 80, "z": 4},
            "type": "AREA",
            "secondCorner": {"x": -1, "y": 30, "z": -6},
        }],
    }
    result = run_ui(
        """
        ui.state.draft.coordinatePoints[0].name = "改名区域";
        ui.state.dirty = true;
        await ui.saveCoordinates();
        return inspect();
        """,
        snapshot=snapshot,
    )
    written = result["writes"][0]["payload"]["coordinatePoints"][0]
    refreshed = result["draft"]["coordinatePoints"][0]
    refreshed_fields = {
        key: value for key, value in refreshed.items() if key != "_originalId"
    }
    assert written["type"] == "AREA"
    assert written["secondCorner"] == {"x": -1, "y": 30, "z": -6}
    assert "radius" not in written
    assert refreshed_fields == written
    assert result["snapshot"]["coordinatePoints"][0] == written
    assert result["dirty"] is False


def test_revision_conflict_keeps_range_draft() -> None:
    snapshot = {
        "revision": "sphere-revision",
        "coordinatePoints": [{
            "id": "sphere-a",
            "name": "球",
            "dimension": "minecraft:overworld",
            "position": {"x": 3, "y": 70, "z": 9},
            "type": "SPHERE",
            "radius": 14,
        }],
    }
    result = run_ui(
        """
        const point = ui.state.draft.coordinatePoints[0];
        point.name = "未保存球";
        point.radius = 16;
        ui.state.dirty = true;
        await ui.saveCoordinates();
        return inspect();
        """,
        snapshot=snapshot,
        fail_write={
            "status": 409,
            "code": "config_revision_conflict",
            "message": "configuration changed",
        },
    )
    assert result["draft"]["coordinatePoints"][0]["name"] == "未保存球"
    assert result["draft"]["coordinatePoints"][0]["type"] == "SPHERE"
    assert result["draft"]["coordinatePoints"][0]["radius"] == 16
    assert result["snapshot"]["coordinatePoints"][0]["name"] == "球"
    assert result["dirty"] is True
    assert "草稿已保留" in result["status"]
