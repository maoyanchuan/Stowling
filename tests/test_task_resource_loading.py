import json
from pathlib import Path
import shutil
import subprocess

import pytest


ROOT = Path(__file__).resolve().parents[1]
RESOURCE_SCRIPT = (
    ROOT / "src" / "main" / "resources" / "stowling-web" / "task-resources.js"
)
TASKS_SCRIPT = ROOT / "src" / "main" / "resources" / "stowling-web" / "tasks.js"


def _node_executable() -> str:
    executable = shutil.which("node")
    if executable is not None:
        return executable

    windows_node = Path(r"C:\Program Files\nodejs\node.exe")
    if windows_node.is_file():
        return str(windows_node)

    pytest.skip("Node.js is unavailable")


def _execute_task_scripts(
    resource_script: Path,
    tasks_script: Path,
) -> dict[str, object]:
    harness = r"""
const fs = require("node:fs");
const vm = require("node:vm");

class FakeElement {
    constructor(tagName = "div") {
        this.tagName = tagName;
        this.children = [];
        this.dataset = {};
        this.classList = {
            add() {},
            remove() {},
            toggle() {}
        };
        this.textContent = "";
        this.value = "";
        this.disabled = false;
    }

    append(...children) {
        this.children.push(...children);
    }

    replaceChildren(...children) {
        this.children = [...children];
    }

    addEventListener() {}

    setAttribute(name, value) {
        this[name] = String(value);
    }
}

class FakeOption {
    constructor(text, value) {
        this.textContent = String(text);
        this.value = String(value);
    }
}

const elements = new Map();
const document = {
    visibilityState: "visible",
    createElement(tagName) {
        return new FakeElement(tagName);
    },
    querySelector(selector) {
        if (!elements.has(selector)) {
            elements.set(selector, new FakeElement());
        }
        return elements.get(selector);
    }
};

const responses = new Map([
    ["/v1/config/coordinate-points", {
        revision: "coordinate-revision-3",
        coordinatePoints: [{
            id: "cache-point",
            name: "缓存点",
            dimension: "minecraft:overworld",
            position: { x: 10, y: 64, z: -4 }
        }]
    }],
    ["/v1/config/task-resources", {
        revision: "resource-revision-7",
        pickupRegions: [],
        cacheContainers: [{
            id: "cache-main",
            name: "主缓存",
            coordinateId: "cache-point",
            dimension: "minecraft:overworld",
            type: "CHEST"
        }]
    }],
    ["/v1/config/coordinates", {
        revision: "base-revision-2",
        coordinates: []
    }]
]);

const Stowling = {
    connectedHandlers: [],
    mountedPages: [],
    clone(value) {
        return JSON.parse(JSON.stringify(value));
    },
    containerTypeLabel(value) {
        return String(value);
    },
    dimensionLabel(value) {
        return String(value);
    },
    element(tagName, className = "", text = "") {
        const node = new FakeElement(tagName);
        node.className = className;
        if (text) {
            node.textContent = text;
        }
        return node;
    },
    emptyState(message) {
        const node = new FakeElement();
        node.textContent = message;
        return node;
    },
    async request(path) {
        if (!responses.has(path)) {
            throw new Error(`Unexpected request: ${path}`);
        }
        return responses.get(path);
    },
    onConnected(handler) {
        this.connectedHandlers.push(handler);
    },
    mountPage(page) {
        this.mountedPages.push(page);
    },
    revisionLabel(value) {
        return String(value);
    }
};

const window = {
    Stowling,
    clearTimeout() {},
    confirm() {
        return true;
    },
    setInterval() {
        return 1;
    },
    setTimeout() {
        return 1;
    }
};

const context = vm.createContext({
    Option: FakeOption,
    console,
    document,
    window
});

vm.runInContext(
    fs.readFileSync(__RESOURCE_SCRIPT_PATH__, "utf8"),
    context,
    { filename: "task-resources.js" }
);

async function inspectTaskScripts() {
    const api = window.StowlingResources;
    if (!api) {
        throw new Error("window.StowlingResources was not registered");
    }
    const snapshot = await api.load();
    let resourceChangeRegistered = false;
    window.StowlingResources = {
        getSnapshot: api.getSnapshot,
        load: api.load,
        onChanged(handler) {
            resourceChangeRegistered = true;
            return api.onChanged(handler);
        }
    };

    vm.runInContext(
        fs.readFileSync(__TASKS_SCRIPT_PATH__, "utf8"),
        context,
        { filename: "tasks.js" }
    );

    console.log(JSON.stringify({
        api: Object.keys(api).sort(),
        snapshot,
        tasks: {
            connectedHandlerCount: Stowling.connectedHandlers.length,
            mountedPages: Stowling.mountedPages,
            resourceChangeRegistered
        }
    }));
}

inspectTaskScripts().catch((error) => {
    console.error(error.stack || error);
    process.exitCode = 1;
});
"""
    harness = harness.replace(
        "__RESOURCE_SCRIPT_PATH__",
        json.dumps(str(resource_script)),
    ).replace(
        "__TASKS_SCRIPT_PATH__",
        json.dumps(str(tasks_script)),
    )
    result = subprocess.run(
        [_node_executable(), "-e", harness],
        cwd=ROOT,
        check=False,
        text=True,
        capture_output=True,
        encoding="utf-8",
        errors="replace",
        timeout=20,
    )
    assert result.returncode == 0, "\n".join(
        part for part in (result.stdout, result.stderr) if part
    )
    return json.loads(result.stdout)


def test_task_scripts_load_resource_snapshot_and_mount_page() -> None:
    payload = _execute_task_scripts(RESOURCE_SCRIPT, TASKS_SCRIPT)
    snapshot = payload["snapshot"]
    tasks = payload["tasks"]

    assert payload["api"] == ["getSnapshot", "load", "onChanged"]
    assert isinstance(snapshot, dict)
    assert snapshot["pickupRevision"] == "resource-revision-7"
    assert snapshot["cacheContainers"] == [
        {
            "id": "cache-main",
            "name": "主缓存",
            "coordinateId": "cache-point",
            "dimension": "minecraft:overworld",
            "type": "CHEST",
        }
    ]
    assert isinstance(tasks, dict)
    assert tasks["resourceChangeRegistered"] is True
    assert tasks["connectedHandlerCount"] == 1
    assert tasks["mountedPages"] == ["tasks"]
