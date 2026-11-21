(() => {
    "use strict";

    const S = window.Stowling;
    const Settings = window.StowlingSettings;
    const moduleId = "COLLECT_SCHEMATIC_MATERIALS";
    /** @param {string} id @returns {HTMLElement} */
    const byId = (id) => document.getElementById(id);
    const form = byId("material-task-form");
    const draft = byId("material-editor");
    const draftToggle = byId("toggle-material-draft");
    const draftBody = byId("material-draft-body");
    const name = byId("material-name");
    const file = byId("material-file");
    const destination = byId("material-destination");
    const boxedDelivery = Settings.switchControl(byId("material-boxed-delivery"));
    const boxedNote = byId("material-boxed-note");
    const status = byId("material-status");
    const previewStatus = byId("material-preview-status");
    const preview = byId("material-preview");
    const items = byId("material-preview-items");
    const list = byId("material-task-list");
    const add = byId("new-material-task");
    const taskRows = new Map();
    let rows = [];
    let locations = new Map();
    let revision = "";
    let current = null;
    let materials = {};
    let unsupported = {};
    let busy = false;

    /**
     * @param {HTMLElement} button
     * @param {HTMLElement} body
     * @param {boolean} expanded
     * @returns {void}
     */
    function disclose(button, body, expanded) {
        button.setAttribute("aria-expanded", String(expanded));
        body.inert = !expanded;
        void Settings.reveal(body, expanded);
    }

    /** @returns {void} */
    function controls() {
        const disabled = busy || !S.isConnected();
        add.disabled = disabled || !revision;
        form.querySelector("fieldset").disabled = disabled;
        byId("material-save").disabled = disabled || !Object.keys(materials).length;
        list.querySelectorAll("button").forEach((button) => {
            button.disabled = disabled;
        });
        const isDraft = current?.id === null;
        draft.hidden = !isDraft;
        draftToggle.disabled = disabled;
        disclose(draftToggle, draftBody, isDraft && current.expanded);
        for (const [id, {button, body}] of taskRows) {
            disclose(button, body, current?.id === id && current.expanded);
        }
    }

    /** @param {string} message @returns {void} */
    function showStatus(message) {
        status.textContent = message;
        status.hidden = !message;
    }

    /** @returns {void} */
    function notifyCount() {
        document.dispatchEvent(new CustomEvent("stowling:material-task-count", {
            detail: {count: rows.length, draftActive: current?.id === null}
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
    function fillForm(task) {
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
        preview.open = false;
        renderPreview();
    }

    /** @param {object|null} task @returns {void} */
    function selectTask(task) {
        const id = task?.id ?? null;
        const row = id ? taskRows.get(id) : {button: draftToggle, body: draftBody};
        if (busy || !S.isConnected() || !row) return;
        if (current?.id === id) {
            current.expanded = !current.expanded;
        } else {
            if (current?.dirty && !window.confirm("当前修改尚未保存。仍要切换任务吗？")) return;
            current = {id, revision, step: task?.steps[0].id, expanded: true, dirty: false};
            row.body.append(form);
            fillForm(task);
        }
        controls();
        notifyCount();
        row.button.focus();
    }

    /** @returns {void} */
    function closeEditor() {
        current = null;
        controls();
        notifyCount();
    }

    /** @returns {void} */
    function renderRows() {
        draftBody.append(form);
        list.replaceChildren();
        taskRows.clear();
        for (const task of rows) {
            const parameters = task.steps[0].parameters;
            const target = locations.get(parameters.targetRef) || "交付位置";
            const method = parameters.boxedDelivery?.toLowerCase() === "true"
                ? "盒装交付" : "散装交付";
            const card = S.element("article", "move-saved-task");
            const head = S.element("div", "task-card-header");
            const summary = S.element("button", "move-task-summary");
            summary.type = "button";
            const body = S.element("div", "move-task-body");
            body.id = `material-task-body-${task.id}`;
            body.hidden = true;
            summary.setAttribute("aria-controls", body.id);
            const overview = S.element("span", "move-task-overview");
            overview.append(S.element("strong", "", task.name),
                S.element("span", "", `${method} · ${target}`));
            const kind = S.element("span", "move-task-kind", "料");
            const chevron = S.element("span", "move-task-chevron", "⌄");
            kind.setAttribute("aria-hidden", "true");
            chevron.setAttribute("aria-hidden", "true");
            summary.append(kind, overview, chevron);
            summary.addEventListener("click", () => selectTask(task));
            const run = S.element("button", "secondary compact", "运行");
            run.type = "button";
            run.addEventListener("click", () => void runTask(task.id));
            const remove = S.element("button", "ghost compact danger-action", "删除");
            remove.type = "button";
            remove.addEventListener("click", () => void deleteTask(task));
            head.append(summary, run, remove);
            card.append(head, body);
            if (current?.id === task.id) {
                body.append(form);
            }
            list.append(card);
            taskRows.set(task.id, {button: summary, body});
        }
        controls();
        notifyCount();
    }

    /** @param {object} response @returns {void} */
    function applyTasks(response) {
        revision = response.revision;
        rows = response.tasks.filter((task) => task.steps.length === 1
            && task.steps[0].moduleId === moduleId);
        if (current?.id && !rows.some((task) => task.id === current.id)) {
            closeEditor();
        } else if (current && !current.dirty) {
            current.revision = revision;
            if (current.id) {
                const saved = rows.find((task) => task.id === current.id);
                current.step = saved.steps[0].id;
                fillForm(saved);
            }
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
        if (!selected || busy || !current) return;
        const retained = Object.keys(materials).length ? "。已保留原材料清单" : "";
        if (!selected.name.toLowerCase().endsWith(".litematic")
                || selected.size > 8 * 1024 * 1024) {
            previewStatus.textContent = "请选择 8 MiB 以内的 .litematic 文件" + retained;
            file.value = "";
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
            current.dirty = true;
            const warnings = Object.keys(unsupported).length;
            previewStatus.textContent = Object.keys(materials).length
                ? warnings ? `解析完成，${warnings} 种方块或实体需人工处理` : "解析完成"
                : "投影没有可收集的材料";
            preview.open = true;
        } catch (error) {
            previewStatus.textContent = error.message + retained;
            file.value = "";
        } finally {
            busy = false;
            renderPreview();
        }
    }

    /** @param {Event} event @returns {Promise<void>} */
    async function save(event) {
        event.preventDefault();
        if (busy || !current || !Object.keys(materials).length) return;
        const id = current.id;
        const targetRef = destination.value;
        const payload = {
            name: name.value.trim(),
            steps: [{
                ...(id ? {id: current.step} : {}),
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
            const response = await S.request(id ? `/v1/tasks/${id}` : "/v1/tasks", {
                method: id ? "PUT" : "POST",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify(id ? {...payload, revision: current.revision} : payload)
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

    add.addEventListener("click", () => {
        if (current?.id === null && current.expanded) {
            draftToggle.focus();
        } else {
            selectTask(null);
        }
    });
    draftToggle.addEventListener("click", () => selectTask(null));
    byId("material-cancel").addEventListener("click", () => {
        const button = taskRows.get(current?.id)?.button || add;
        closeEditor();
        button.focus();
    });
    byId("refresh-move-tasks").addEventListener("click", () => void load());
    file.addEventListener("change", () => void parseFile());
    boxedDelivery.addEventListener("change", () => {
        boxedNote.hidden = !boxedDelivery.checked;
    });
    form.addEventListener("input", (event) => {
        if (current && event.target !== file) current.dirty = true;
    });
    form.addEventListener("submit", (event) => void save(event));
    document.addEventListener("stowling:tasks-changed", () => void load());
    S.onConnected(load);
})();
