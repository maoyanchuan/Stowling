(() => {
    "use strict";

    const S = window.Stowling;
    const moduleId = "COLLECT_SCHEMATIC_MATERIALS";
    /** @param {string} id @returns {HTMLElement} */
    const byId = (id) => document.getElementById(id);
    const form = byId("material-task-form");
    const editor = byId("material-editor");
    const name = byId("material-name");
    const file = byId("material-file");
    const destination = byId("material-destination");
    const boxedDelivery = byId("material-boxed-delivery");
    const boxedNote = byId("material-boxed-note");
    const status = byId("material-status");
    const previewStatus = byId("material-preview-status");
    const preview = byId("material-preview");
    const items = byId("material-preview-items");
    const list = byId("material-task-list");
    const add = byId("new-material-task");
    let rows = [];
    let locations = new Map();
    let revision = "";
    let current = null;
    let materials = {};
    let unsupported = {};
    let busy = false;

    /** @returns {void} */
    function controls() {
        const disabled = busy || !S.isConnected();
        add.disabled = disabled || !revision;
        form.querySelector("fieldset").disabled = disabled;
        byId("material-save").disabled = disabled || !Object.keys(materials).length;
        list.querySelectorAll("button").forEach((button) => {
            button.disabled = disabled;
        });
    }

    /** @param {string} message @returns {void} */
    function showStatus(message) {
        status.textContent = message;
        status.hidden = !message;
    }

    /** @returns {void} */
    function notifyCount() {
        document.dispatchEvent(new CustomEvent("stowling:material-task-count", {
            detail: {count: rows.length, draftActive: !editor.hidden && !current}
        }));
    }

    /** @param {string} selected @returns {void} */
    function renderDestinations(selected) {
        destination.replaceChildren(new Option("选择交付位置", ""));
        for (const [value, label] of locations) {
            destination.add(new Option(label, value));
        }
        destination.value = selected;
    }

    /** @returns {void} */
    function renderPreview() {
        const counts = Object.entries(materials);
        preview.hidden = counts.length === 0 && !Object.keys(unsupported).length;
        byId("material-preview-title").textContent = `材料清单 · ${counts.length} 种`;
        items.replaceChildren();
        for (const [id, count] of counts) {
            items.append(S.element("li", "", `${id} × ${count}`));
        }
        for (const [id, count] of Object.entries(unsupported)) {
            items.append(S.element("li", "muted", `需人工处理 · ${id} × ${count}`));
        }
        controls();
    }

    /** @param {object|null} task @returns {void} */
    function openEditor(task = null) {
        current = task ? {id: task.id, revision: revision, step: task.steps[0].id} : null;
        const params = task?.steps[0].parameters;
        materials = params ? JSON.parse(params.materials) : {};
        unsupported = {};
        name.value = task?.name || "自动收集材料";
        file.value = "";
        renderDestinations(params?.targetRef || "");
        boxedDelivery.checked = params?.boxedDelivery?.toLowerCase() === "true";
        boxedNote.hidden = !boxedDelivery.checked;
        previewStatus.textContent = task ? "可上传新投影替换材料清单"
            : "选择投影文件后自动解析材料";
        byId("material-editor-title").textContent = task ? "编辑 · 自动收集材料"
            : "新建 · 自动收集材料";
        editor.hidden = false;
        renderPreview();
        notifyCount();
        name.focus();
    }

    /** @returns {void} */
    function closeEditor() {
        editor.hidden = true;
        current = null;
        notifyCount();
    }

    /** @returns {void} */
    function renderRows() {
        list.replaceChildren();
        for (const task of rows) {
            const parameters = task.steps[0].parameters;
            const target = locations.get(parameters.targetRef) || "交付位置";
            const method = parameters.boxedDelivery?.toLowerCase() === "true"
                ? "盒装交付" : "散装交付";
            const card = S.element("article", "move-saved-task");
            const head = S.element("div", "task-card-header");
            const summary = S.element("button", "move-task-summary");
            summary.type = "button";
            const overview = S.element("span", "move-task-overview");
            overview.append(S.element("strong", "", task.name),
                S.element("span", "", `${method} · ${target}`));
            summary.append(S.element("span", "move-task-kind", "料"), overview);
            summary.addEventListener("click", () => openEditor(task));
            const run = S.element("button", "secondary compact", "运行");
            run.type = "button";
            run.addEventListener("click", () => void runTask(task.id));
            const remove = S.element("button", "ghost compact danger-action", "删除");
            remove.type = "button";
            remove.addEventListener("click", () => void deleteTask(task));
            head.append(summary, run, remove);
            card.append(head);
            list.append(card);
        }
        controls();
        notifyCount();
    }

    /** @param {object} response @returns {void} */
    function applyTasks(response) {
        revision = response.revision;
        rows = response.tasks.filter((task) => task.steps.length === 1
            && task.steps[0].moduleId === moduleId);
        if (current && !rows.some((task) => task.id === current.id)) {
            closeEditor();
        }
        renderRows();
    }

    /** @returns {Promise<void>} */
    async function load() {
        if (busy || !S.isConnected()) return;
        busy = true;
        controls();
        try {
            const [tasks, coordinates, regions] = await Promise.all([
                S.request("/v1/tasks"), S.request("/v1/coordinates"), S.request("/v1/regions")
            ]);
            locations = new Map();
            for (const point of coordinates.coordinates) {
                locations.set(`coordinate:${point.id}`, `容器坐标 · ${point.name}`);
            }
            for (const region of regions.regions) {
                locations.set(`region:${region.id}`, `区域 · ${region.name}`);
            }
            renderDestinations(destination.value);
            applyTasks(tasks);
            showStatus("");
        } catch (error) {
            showStatus(`读取材料任务失败：${error.message}`);
        } finally {
            busy = false;
            controls();
        }
    }

    /** @returns {Promise<void>} */
    async function parseFile() {
        const selected = file.files[0];
        if (!selected || busy) return;
        materials = {};
        unsupported = {};
        if (!selected.name.toLowerCase().endsWith(".litematic")
                || selected.size > 8 * 1024 * 1024) {
            previewStatus.textContent = "请选择 8 MiB 以内的 .litematic 文件";
            renderPreview();
            return;
        }
        busy = true;
        renderPreview();
        previewStatus.textContent = "正在解析投影…";
        try {
            const result = await S.request("/v1/schematic-materials", {
                method: "POST",
                headers: {"Content-Type": "application/octet-stream"},
                body: selected
            });
            materials = result.items;
            unsupported = result.unsupported;
            const warnings = Object.keys(unsupported).length;
            previewStatus.textContent = Object.keys(materials).length
                ? warnings ? `解析完成，${warnings} 种方块或实体需人工处理` : "解析完成"
                : "投影没有可收集的材料";
            preview.open = true;
        } catch (error) {
            previewStatus.textContent = error.message;
        } finally {
            busy = false;
            renderPreview();
        }
    }

    /** @param {Event} event @returns {Promise<void>} */
    async function save(event) {
        event.preventDefault();
        if (busy || !Object.keys(materials).length) return;
        const targetRef = destination.value;
        const payload = {
            name: name.value.trim(),
            steps: [{
                ...(current ? {id: current.step} : {}),
                moduleId: moduleId,
                parameters: {
                    targetRef,
                    materials: JSON.stringify(materials),
                    boxedDelivery: String(boxedDelivery.checked)
                }
            }],
            cleanupPolicy: {destinationRef: targetRef, resumeMode: "CONTINUE"}
        };
        busy = true;
        controls();
        try {
            const response = await S.request(current ? `/v1/tasks/${current.id}` : "/v1/tasks", {
                method: current ? "PUT" : "POST",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify(current ? {...payload, revision: current.revision} : payload)
            });
            closeEditor();
            applyTasks(response);
            changed();
            showStatus("材料收集任务已保存");
        } catch (error) {
            previewStatus.textContent = error.message;
        } finally {
            busy = false;
            controls();
        }
    }

    /** @param {string} id @returns {Promise<void>} */
    async function runTask(id) {
        if (busy) return;
        busy = true;
        controls();
        try {
            const run = await S.request(`/v1/tasks/${id}/runs`, {method: "POST"});
            S.showToast(run.state === "QUEUED" ? "材料任务已加入队列" : "材料任务已开始运行");
            document.dispatchEvent(new Event("stowling:monitor-refresh"));
        } catch (error) {
            S.handleError(error, "运行材料任务失败");
        } finally {
            busy = false;
            controls();
        }
    }

    /** @param {object} task @returns {Promise<void>} */
    async function deleteTask(task) {
        if (busy || !window.confirm(`删除材料收集任务「${task.name}」？`)) return;
        busy = true;
        controls();
        try {
            const response = await S.request(`/v1/tasks/${task.id}`, {
                method: "DELETE",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({revision})
            });
            applyTasks(response);
            changed();
            showStatus("材料收集任务已删除");
        } catch (error) {
            S.handleError(error, "删除材料任务失败");
        } finally {
            busy = false;
            controls();
        }
    }

    /** @returns {void} */
    function changed() {
        document.dispatchEvent(new Event("stowling:material-tasks-changed"));
        document.dispatchEvent(new Event("stowling:tasks-changed"));
    }

    add.addEventListener("click", () => openEditor());
    byId("material-cancel").addEventListener("click", closeEditor);
    byId("refresh-move-tasks").addEventListener("click", () => void load());
    file.addEventListener("change", () => void parseFile());
    boxedDelivery.addEventListener("change", () => {
        boxedNote.hidden = !boxedDelivery.checked;
    });
    form.addEventListener("submit", (event) => void save(event));
    document.addEventListener("stowling:tasks-changed", () => void load());
    S.onConnected(load);
})();
