(() => {
    "use strict";

    const Stowling = window.Stowling;
    const Auto = window.StowlingAutoClassifyTaskModel;
    const state = {
        revision: "",
        items: [],
        allTasks: [],
        resources: { coordinates: [], regions: [], categoryZones: [] },
        draft: Auto.newDraft([]),
        ready: false,
        loading: false,
        busy: false,
        dirty: false,
        active: false,
        expanded: false
    };
    const elements = {
        list: document.querySelector("#auto-task-list"),
        draft: document.querySelector("#auto-task-draft"),
        draftToggle: document.querySelector("#toggle-auto-draft"),
        draftBody: document.querySelector("#auto-draft-body"),
        form: document.querySelector("#auto-task-form"),
        fields: document.querySelector("#auto-task-fields"),
        name: document.querySelector("#auto-task-name"),
        point: document.querySelector("#auto-detection-point"),
        radius: document.querySelector("#auto-pickup-radius"),
        cache: document.querySelector("#auto-cache-region"),
        zone: document.querySelector("#auto-category-zone"),
        sources: [...document.querySelectorAll('input[name="markerSources"]')],
        priority: document.querySelector("#auto-collect-priority"),
        message: document.querySelector("#auto-task-message"),
        resourceMessage: document.querySelector("#auto-resources-message"),
        newTask: document.querySelector("#new-auto-task"),
        refresh: document.querySelector("#refresh-move-tasks"),
        save: document.querySelector("#save-auto-task"),
        start: document.querySelector("#start-auto-task"),
        stop: document.querySelector("#stop-auto-task"),
        remove: document.querySelector("#delete-auto-task")
    };
    const rows = new Map();

    /** @returns {boolean} */
    function unavailable() {
        return state.loading || state.busy || !state.ready || !Stowling.isConnected();
    }

    /**
     * @param {string} text
     * @param {boolean} [error]
     * @returns {void}
     */
    function showMessage(text, error = false) {
        elements.message.textContent = text;
        elements.message.classList.toggle("error", error);
    }

    /** @returns {Promise<void>} */
    async function loadResources() {
        if (state.loading || state.busy || !Stowling.isConnected()) {
            return;
        }
        state.loading = true;
        updateControls();
        try {
            const [tasks, coordinates, regions, zones, allTasks] = await Promise.all([
                Stowling.request("/v1/auto-classify-tasks"),
                Stowling.request("/v1/coordinates"),
                Stowling.request("/v1/regions"),
                Stowling.request("/v1/category-zones"),
                Stowling.request("/v1/tasks")
            ]);
            state.revision = String(tasks.revision || "");
            state.items = Array.isArray(tasks.items) ? tasks.items : [];
            state.allTasks = Array.isArray(allTasks.tasks) ? allTasks.tasks : [];
            state.resources = {
                coordinates: Array.isArray(coordinates.coordinates) ? coordinates.coordinates : [],
                regions: Array.isArray(regions.regions) ? regions.regions : [],
                categoryZones: Array.isArray(zones.categoryZones) ? zones.categoryZones : []
            };
            state.ready = true;
            if (state.draft.id && !state.dirty) {
                const item = state.items.find((task) => task.id === state.draft.id);
                if (item) {
                    state.draft = Auto.draftFromTask(item);
                } else {
                    state.draft = Auto.newDraft(state.items, state.allTasks);
                    state.active = false;
                    state.expanded = false;
                }
            }
            elements.resourceMessage.hidden = true;
            syncForm();
            renderList();
        } catch (error) {
            state.ready = false;
            elements.resourceMessage.hidden = false;
            elements.resourceMessage.textContent = "自动分类配置读取失败，请刷新重试。";
            elements.resourceMessage.classList.add("error");
            Stowling.handleError(error, "读取自动分类任务失败");
        } finally {
            state.loading = false;
            updateControls();
        }
    }

    /**
     * @param {HTMLSelectElement} select
     * @param {Array<object>} values
     * @param {string} selected
     * @param {string} placeholder
     * @returns {void}
     */
    function populateSelect(select, values, selected, placeholder) {
        const empty = Stowling.element("option", "", placeholder);
        empty.value = "";
        select.replaceChildren(empty);
        for (const value of values) {
            const option = Stowling.element("option", "", value.name || value.id);
            option.value = value.id;
            select.append(option);
        }
        if (selected && !values.some((value) => value.id === selected)) {
            const missing = Stowling.element("option", "", "原位置已删除，请重新选择");
            missing.value = selected;
            missing.disabled = true;
            select.append(missing);
        }
        select.value = selected;
    }

    /** @returns {void} */
    function syncForm() {
        const draft = state.draft;
        elements.name.value = draft.name;
        elements.radius.value = draft.pickupRadius;
        elements.priority.value = draft.collectionPriority;
        populateSelect(elements.point, state.resources.coordinates, draft.coordinateId,
            "请选择坐标页面的检测点");
        populateSelect(elements.cache, state.resources.regions, draft.cacheRegionId,
            "请选择包含缓存箱子的区域");
        populateSelect(elements.zone, state.resources.categoryZones, draft.categoryZoneId,
            "请选择分类区（识别区 + 储存区）");
        for (const input of elements.sources) {
            input.checked = draft.markerSources.includes(input.value);
        }
        updateControls();
    }

    /** @returns {void} */
    function readForm() {
        state.draft = {
            ...state.draft,
            name: elements.name.value,
            coordinateId: elements.point.value,
            pickupRadius: elements.radius.value,
            cacheRegionId: elements.cache.value,
            categoryZoneId: elements.zone.value,
            markerSources: elements.sources.filter((input) => input.checked)
                .map((input) => input.value),
            collectionPriority: elements.priority.value
        };
    }

    /** @returns {void} */
    function renderList() {
        elements.draftBody.append(elements.form);
        elements.list.replaceChildren();
        rows.clear();
        for (const item of state.items) {
            const draft = Auto.draftFromTask(item);
            const card = Stowling.element("article", "move-saved-task auto-saved-task");
            const button = Stowling.element("button", "move-task-summary");
            button.type = "button";
            const body = Stowling.element("div", "move-task-body");
            body.id = `auto-task-body-${item.id}`;
            button.setAttribute("aria-controls", body.id);
            const icon = Stowling.element("span", "move-task-kind", "⇄");
            icon.setAttribute("aria-hidden", "true");
            const overview = Stowling.element("span", "move-task-overview");
            overview.append(Stowling.element("strong", "", item.name));
            const cache = state.resources.regions.find((region) => region.id === draft.cacheRegionId);
            const zone = state.resources.categoryZones.find(
                (value) => value.id === draft.categoryZoneId
            );
            overview.append(Stowling.element("span", "",
                `${cache?.name || "缓存区域已删除"} → ${zone?.name || "分类区已删除"}`));
            const priority = window.StowlingTaskModel.priorityLabel(item.collectionPriority);
            const status = Stowling.element("span", "move-task-state",
                item.enabled ? "监听中" : "已保存");
            const chevron = Stowling.element("span", "move-task-chevron", "⌄");
            chevron.setAttribute("aria-hidden", "true");
            button.append(icon, overview, Stowling.element("span", "move-task-priority",
                `${priority} / 后台`), status, chevron);
            button.addEventListener("click", () => selectTask(item));
            if (state.draft.id === item.id) {
                body.append(elements.form);
            }
            card.append(button, body);
            elements.list.append(card);
            rows.set(item.id, { button, body, status });
        }
        document.dispatchEvent(new CustomEvent("stowling:auto-task-count", {
            detail: {
                count: state.items.length,
                draftActive: state.active && !state.draft.id
            }
        }));
        updateControls();
    }

    /** @returns {void} */
    function updateControls() {
        const disabled = unavailable();
        elements.fields.disabled = disabled;
        elements.newTask.disabled = disabled;
        elements.draftToggle.disabled = disabled;
        const isNew = state.active && !state.draft.id;
        elements.draft.hidden = !isNew;
        elements.draftBody.hidden = !isNew || !state.expanded;
        elements.form.hidden = !state.expanded;
        elements.draftToggle.setAttribute("aria-expanded", String(isNew && state.expanded));
        elements.start.textContent = state.draft.enabled
            ? "保存并继续监听" : "保存并开始监听";
        elements.stop.hidden = !state.draft.id || !state.draft.enabled;
        elements.remove.hidden = !state.draft.id;
        for (const [id, row] of rows) {
            const selected = state.draft.id === id;
            const expanded = selected && state.expanded;
            row.button.disabled = disabled;
            row.button.setAttribute("aria-expanded", String(expanded));
            row.body.hidden = !expanded;
            const item = state.items.find((value) => value.id === id);
            row.status.textContent = selected && state.dirty ? "未保存修改"
                : (item?.enabled ? "监听中" : "已保存");
        }
    }

    /**
     * @param {object | null} [item]
     * @returns {void}
     */
    function selectTask(item = null) {
        if (unavailable()) {
            return;
        }
        if (item && state.draft.id === item.id) {
            state.expanded = !state.expanded;
            updateControls();
            return;
        }
        if (state.dirty && !window.confirm("当前修改尚未保存，切换任务会丢弃修改。继续？")) {
            return;
        }
        state.draft = item ? Auto.draftFromTask(item)
            : Auto.newDraft(state.items, state.allTasks);
        state.active = !item;
        state.expanded = true;
        state.dirty = false;
        syncForm();
        renderList();
        showMessage(item ? "已保存；监听时自动收集，分类以后台优先级排队。"
            : "配置检测点、缓存区域与分类区后，保存或开始监听。");
    }

    /**
     * @param {object} response
     * @returns {void}
     */
    function applySave(response) {
        const item = response.item;
        if (!item?.id) {
            throw new Error("保存响应缺少任务内容，请刷新后重试");
        }
        state.revision = String(response.revision || "");
        const index = state.items.findIndex((value) => value.id === item.id);
        if (index < 0) {
            state.items.push(item);
        } else {
            state.items[index] = item;
        }
        state.draft = Auto.draftFromTask(item);
        state.dirty = false;
        state.active = false;
        syncForm();
        renderList();
    }

    /**
     * @param {boolean} [start]
     * @returns {Promise<void>}
     */
    async function saveTask(start = false) {
        if (unavailable() || !state.expanded) {
            return;
        }
        readForm();
        let payload;
        try {
            payload = Auto.payload(state.draft, state.resources, state.items, state.allTasks);
        } catch (error) {
            showMessage(String(error.message || error), true);
            return;
        }
        state.busy = true;
        updateControls();
        let saved = false;
        try {
            if (!state.draft.id || state.dirty) {
                const id = state.draft.id;
                const response = await Stowling.request(id
                    ? `/v1/auto-classify-tasks/${encodeURIComponent(id)}`
                    : "/v1/auto-classify-tasks", {
                    method: id ? "PUT" : "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify(id ? { ...payload, revision: state.revision } : payload)
                });
                applySave(response);
                saved = true;
            }
            if (start) {
                const response = await Stowling.request(
                    `/v1/auto-classify-tasks/${encodeURIComponent(state.draft.id)}/start`, {
                        method: "POST",
                        headers: { "Content-Type": "application/json" },
                        body: JSON.stringify({ revision: state.revision })
                    }
                );
                applySave(response);
            }
            const message = start
                ? "已开始监听；已有缓存会进入后台分类队列。" : "自动分类已保存";
            showMessage(message);
            Stowling.showToast(message);
        } catch (error) {
            showMessage(saved ? "任务已保存，但启动失败，请重试或刷新。"
                : String(error.message || "保存失败，请刷新重试。"), true);
            Stowling.handleError(error, "保存或启动自动分类失败");
        } finally {
            state.busy = false;
            updateControls();
            document.dispatchEvent(new Event("stowling:auto-tasks-changed"));
        }
    }

    /**
     * @param {boolean} [remove]
     * @returns {Promise<void>}
     */
    async function stopTask(remove = false) {
        if (unavailable() || !state.draft.id) {
            return;
        }
        const prompt = remove
            ? "删除整个自动分类任务？若任务正在运行，请先取消并完成收尾。"
            : "停止掉落物监听？已经排队或正在执行的流程仍会继续。";
        const warning = state.dirty ? "未保存修改将被丢弃。" : "";
        if (!window.confirm(`${prompt}${warning}`)) {
            return;
        }
        state.busy = true;
        updateControls();
        try {
            const id = state.draft.id;
            const response = await Stowling.request(
                `/v1/auto-classify-tasks/${encodeURIComponent(id)}${remove ? "" : "/stop"}`, {
                    method: remove ? "DELETE" : "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ revision: state.revision })
                }
            );
            if (remove) {
                state.revision = String(response.revision || "");
                state.items = state.items.filter((item) => item.id !== id);
                state.draft = Auto.newDraft(state.items, state.allTasks);
                state.dirty = false;
                state.active = false;
                state.expanded = false;
                syncForm();
                renderList();
            } else {
                applySave(response);
            }
            const message = remove ? "自动分类任务已删除" : "已停止监听，排队中的流程不受影响";
            showMessage(message);
            Stowling.showToast(message);
        } catch (error) {
            showMessage(String(error.message || "操作失败，请刷新重试"), true);
            Stowling.handleError(error, remove ? "删除自动分类失败" : "停止监听失败");
        } finally {
            state.busy = false;
            updateControls();
            document.dispatchEvent(new Event("stowling:auto-tasks-changed"));
        }
    }

    elements.newTask.addEventListener("click", () => selectTask());
    elements.draftToggle.addEventListener("click", () => {
        if (!unavailable()) {
            state.expanded = !state.expanded;
            updateControls();
        }
    });
    elements.refresh.addEventListener("click", () => void loadResources());
    elements.form.addEventListener("input", () => {
        readForm();
        state.dirty = true;
        showMessage("未保存修改");
        updateControls();
    });
    elements.save.addEventListener("click", () => void saveTask());
    elements.form.addEventListener("submit", (event) => {
        event.preventDefault();
        void saveTask(true);
    });
    elements.stop.addEventListener("click", () => void stopTask());
    elements.remove.addEventListener("click", () => void stopTask(true));
    document.addEventListener("stowling:tasks-changed", () => void loadResources());
    Stowling.onConnected(loadResources);
    updateControls();
    window.setInterval(updateControls, 3000);
})();
