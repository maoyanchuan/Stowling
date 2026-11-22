(() => {
    "use strict";

    const S = window.Stowling;
    const Settings = window.StowlingSettings;
    const Autosave = window.StowlingTaskAutosave;
    const endpoint = "/v1/tasks";
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
    const mixedBoxes = byId("material-mixed-boxes");
    const pureBoxes = byId("material-pure-boxes");
    const boxedOptions = byId("material-boxed-options");
    const boxedSettings = Settings.optionalSection("按潜影盒交付",
        "选择混杂盒交付或纯净盒交付", boxedDelivery, boxedOptions);
    byId("material-boxed-settings").replaceChildren(boxedSettings.root);
    const iceInsteadOfWater = Settings.switchControl(byId("material-ice-instead-of-water"));
    const skipWater = Settings.switchControl(byId("material-skip-water"));
    const skipLava = Settings.switchControl(byId("material-skip-lava"));
    const skipShulkerBoxes = Settings.switchControl(byId("material-skip-shulker-boxes"));
    const skipMinecarts = Settings.switchControl(byId("material-skip-minecarts"));
    const sourceSettings = Settings.bindDisclosure(byId("material-source-settings"),
        byId("material-source-summary"), byId("material-source-options"));
    const deliverySettings = Settings.bindDisclosure(byId("material-delivery-settings"),
        byId("material-delivery-summary"), byId("material-delivery-options"));
    const unstackableSettings = Settings.bindDisclosure(byId("material-unstackable-settings"),
        byId("material-unstackable-summary"), byId("material-unstackable-options"));
    const previewStatus = byId("material-preview-status");
    const saveStatus = byId("material-save-status");
    const preview = byId("material-preview");
    const items = byId("material-preview-items");
    const list = byId("material-task-list");
    const add = byId("new-material-task");
    const remove = byId("delete-material-task");
    const taskRows = new Map();
    let rows = [];
    let locations = new Map();
    let revision = "";
    let current = null;
    let materials = {};
    let unsupported = {};
    let busy = false;
    const autosave = Autosave.create(form, save, () => busy);

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
        remove.hidden = !current?.id;
        const isDraft = current?.id === null;
        draft.hidden = !isDraft;
        draftToggle.disabled = disabled;
        disclose(draftToggle, draftBody, isDraft && current.expanded);
        for (const [id, {button, body, state, run}] of taskRows) {
            const selected = current?.id === id;
            button.disabled = disabled;
            run.disabled = disabled || (selected && current.dirty);
            state.textContent = selected && current.dirty ? "未保存修改" : "已保存";
            disclose(button, body, selected && current.expanded);
        }
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

    /** @returns {Record<string, number>} */
    function requestedMaterials() {
        const requested = {...materials};
        if (iceInsteadOfWater.checked || skipWater.checked) {
            const waterBuckets = requested["minecraft:water_bucket"];
            if (iceInsteadOfWater.checked && waterBuckets) {
                requested["minecraft:ice"] = (requested["minecraft:ice"] || 0) + waterBuckets;
            }
            delete requested["minecraft:water_bucket"];
        }
        if (skipLava.checked) {
            delete requested["minecraft:lava_bucket"];
        }
        for (const id of Object.keys(requested)) {
            const shulkerBox = id === "minecraft:shulker_box"
                || id.startsWith("minecraft:") && id.endsWith("_shulker_box");
            const minecart = id === "minecraft:minecart"
                || id.startsWith("minecraft:") && id.endsWith("_minecart");
            if ((skipShulkerBoxes.checked && shulkerBox) || (skipMinecarts.checked && minecart)) {
                delete requested[id];
            }
        }
        return requested;
    }

    /** @param {boolean} boxed @param {boolean} single @returns {string} */
    function deliveryMethod(boxed, single) {
        return boxed ? (single ? "纯净盒交付" : "混杂盒交付") : "散装交付";
    }

    /** @returns {void} */
    function renderSummaries() {
        const count = Object.keys(requestedMaterials()).length;
        const warnings = Object.keys(unsupported).length;
        byId("material-source-description").textContent = Object.keys(materials).length
            ? `${count} 种材料${warnings ? ` · ${warnings} 种需人工处理` : ""}`
            : warnings ? `${warnings} 种需人工处理` : "未选择投影";
        const target = locations.get(destination.value) || "选择交付位置";
        const method = deliveryMethod(boxedDelivery.checked, pureBoxes.checked);
        byId("material-delivery-description").textContent = `${target} · ${method}`;
        const rules = [];
        if (iceInsteadOfWater.checked) rules.push("普通冰替代水桶");
        if (skipWater.checked) rules.push("不收集水桶");
        if (skipLava.checked) rules.push("不收集岩浆桶");
        if (skipShulkerBoxes.checked) rules.push("不收集潜影盒");
        if (skipMinecarts.checked) rules.push("不收集矿车");
        byId("material-unstackable-description").textContent = rules.join(" · ") || "全部收集";
    }

    /** @returns {void} */
    function renderPreview() {
        const counts = Object.entries(requestedMaterials());
        const hasMaterials = Object.keys(materials).length > 0;
        preview.hidden = !hasMaterials && !Object.keys(unsupported).length;
        byId("material-preview-title").textContent = `材料清单 · ${counts.length} 种`;
        items.replaceChildren();
        if (hasMaterials && counts.length === 0) {
            items.append(S.element("li", "muted", "按当前设置无需收集材料"));
        }
        for (const [id, count] of counts) {
            items.append(S.element("li", "", `${id} × ${count}`));
        }
        for (const [id, count] of Object.entries(unsupported)) {
            items.append(S.element("li", "muted", `需人工处理 · ${id} × ${count}`));
        }
        renderSummaries();
        controls();
    }

    /** @param {boolean} pure @returns {void} */
    function selectBoxedMode(pure) {
        pureBoxes.checked = pure;
        mixedBoxes.checked = !pure;
        renderSummaries();
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
        pureBoxes.checked = params?.singleMaterialBoxes?.toLowerCase() === "true";
        mixedBoxes.checked = !pureBoxes.checked;
        iceInsteadOfWater.checked = params?.iceInsteadOfWater?.toLowerCase() === "true";
        skipWater.checked = params?.skipWaterBuckets?.toLowerCase() === "true";
        skipLava.checked = params?.skipLava === undefined
            ? iceInsteadOfWater.checked : params.skipLava.toLowerCase() === "true";
        skipShulkerBoxes.checked = params?.collectShulkerBoxes?.toLowerCase() === "false";
        skipMinecarts.checked = params?.collectMinecarts?.toLowerCase() === "false";
        boxedSettings.refresh();
        previewStatus.textContent = task ? "可上传新投影替换材料清单"
            : "选择投影文件后自动解析材料";
        showStatus(task ? "" : "选择投影和交付位置后自动保存");
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
            autosave.cancel();
            current = {id, revision, task, expanded: true, dirty: false};
            row.body.append(form);
            sourceSettings.setExpanded(false);
            deliverySettings.setExpanded(false);
            unstackableSettings.setExpanded(false);
            fillForm(task);
        }
        controls();
        notifyCount();
        row.button.focus();
    }

    /** @returns {void} */
    function closeEditor() {
        autosave.cancel();
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
            const method = deliveryMethod(parameters.boxedDelivery?.toLowerCase() === "true",
                parameters.singleMaterialBoxes?.toLowerCase() === "true");
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
            const state = S.element("span", "move-task-state", "已保存");
            const chevron = S.element("span", "move-task-chevron", "⌄");
            kind.setAttribute("aria-hidden", "true");
            chevron.setAttribute("aria-hidden", "true");
            summary.append(kind, overview, state, chevron);
            summary.addEventListener("click", () => selectTask(task));
            const run = S.element("button", "secondary move-task-run material-task-run", "运行");
            run.type = "button";
            run.title = "加入队列";
            run.setAttribute("aria-label", `运行：${task.name}`);
            run.addEventListener("click", () => void runTask(task.id));
            head.append(summary, run);
            card.append(head, body);
            if (current?.id === task.id) {
                body.append(form);
            }
            list.append(card);
            taskRows.set(task.id, {button: summary, body, state, run});
        }
        controls();
        notifyCount();
    }

    /** @param {object} response @param {boolean} [refill] @returns {void} */
    function applyTasks(response, refill = true) {
        revision = response.revision;
        rows = response.tasks.filter((task) => task.steps.length === 1
            && task.steps[0].moduleId === moduleId);
        if (current?.id && !rows.some((task) => task.id === current.id)) {
            closeEditor();
        } else if (current && !current.dirty) {
            current.revision = revision;
            if (current.id) {
                current.task = rows.find((task) => task.id === current.id);
                if (refill) fillForm(current.task);
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
                S.request(endpoint), S.request("/v1/coordinates"), S.request("/v1/regions")
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
            renderSummaries();
        } catch (error) {
            S.handleError(error, "读取材料任务失败");
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
            markDirty();
            const warnings = Object.keys(unsupported).length;
            previewStatus.textContent = Object.keys(materials).length
                ? warnings ? `解析完成，${warnings} 种方块或实体需人工处理` : "解析完成"
                : "投影没有可收集的材料";
            sourceSettings.setExpanded(true);
        } catch (error) {
            previewStatus.textContent = error.message + retained;
            file.value = "";
        } finally {
            busy = false;
            renderPreview();
        }
    }

    /** @param {string} text @param {boolean} [error] @returns {void} */
    function showStatus(text, error = false) {
        saveStatus.textContent = text;
        saveStatus.classList.toggle("error", error);
    }

    /** @returns {void} */
    function markDirty() {
        current.dirty = true;
        showStatus("未保存修改");
        controls();
        autosave.schedule();
    }

    /** @returns {Promise<void>} */
    async function save() {
        if (busy || !current?.dirty || !S.isConnected()) return;
        if (!name.value.trim() || !destination.value || !Object.keys(materials).length) {
            showStatus("请填写任务名称、上传投影并选择交付位置", true);
            return;
        }
        const id = current.id;
        const targetRef = destination.value;
        const payload = {
            name: name.value.trim(),
            steps: [{
                ...(id ? {id: current.task.steps[0].id} : {}),
                moduleId: moduleId,
                parameters: {
                    targetRef,
                    materials: JSON.stringify(materials),
                    boxedDelivery: String(boxedDelivery.checked),
                    singleMaterialBoxes: String(pureBoxes.checked),
                    iceInsteadOfWater: String(iceInsteadOfWater.checked),
                    skipWaterBuckets: String(skipWater.checked),
                    skipLava: String(skipLava.checked),
                    collectShulkerBoxes: String(!skipShulkerBoxes.checked),
                    collectMinecarts: String(!skipMinecarts.checked)
                }
            }],
            cleanupPolicy: {destinationRef: targetRef, resumeMode: "CONTINUE"}
        };
        busy = true;
        controls();
        showStatus("正在自动保存…");
        try {
            const response = id
                ? await Autosave.write(`${endpoint}/${id}`, "PUT",
                    {...payload, revision: current.revision},
                    () => Autosave.latestRevision(endpoint, current.task,
                        (latest) => latest.tasks.find((task) => task.id === id)))
                : await Autosave.write(endpoint, "POST", payload);
            current.id = response.taskId || id;
            current.dirty = false;
            applyTasks(response, false);
            changed();
            showStatus("已自动保存");
        } catch (error) {
            showStatus(error.message, true);
        } finally {
            busy = false;
            controls();
        }
    }

    /** @param {string} id @returns {Promise<void>} */
    async function runTask(id) {
        if (busy || taskRows.get(id)?.run.disabled) return;
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

    /** @returns {Promise<void>} */
    async function deleteTask() {
        const task = rows.find((item) => item.id === current?.id);
        if (busy || !task || !window.confirm(`删除材料收集任务「${task.name}」？`)) return;
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
            S.showToast("材料收集任务已删除");
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
    remove.addEventListener("click", () => void deleteTask());
    byId("material-cancel").addEventListener("click", async () => {
        if (current?.dirty) await autosave.flush();
        if (current?.dirty && !window.confirm("关闭并放弃未保存的修改？")) return;
        const button = taskRows.get(current?.id)?.button || add;
        closeEditor();
        button.focus();
    });
    byId("refresh-move-tasks").addEventListener("click", () => void load());
    file.addEventListener("change", () => void parseFile());
    boxedDelivery.addEventListener("change", renderSummaries);
    mixedBoxes.addEventListener("change", () => selectBoxedMode(false));
    pureBoxes.addEventListener("change", () => selectBoxedMode(true));
    iceInsteadOfWater.addEventListener("change", () => {
        if (iceInsteadOfWater.checked) skipWater.checked = false;
        renderPreview();
    });
    skipWater.addEventListener("change", () => {
        if (skipWater.checked) iceInsteadOfWater.checked = false;
        renderPreview();
    });
    skipLava.addEventListener("change", renderPreview);
    skipShulkerBoxes.addEventListener("change", renderPreview);
    skipMinecarts.addEventListener("change", renderPreview);
    form.addEventListener("input", (event) => {
        renderSummaries();
        if (current && event.target !== file) markDirty();
    });
    form.addEventListener("submit", (event) => {
        event.preventDefault();
        void autosave.flush();
    });
    document.addEventListener("stowling:tasks-changed", () => void load());
    S.onConnected(load);
})();
