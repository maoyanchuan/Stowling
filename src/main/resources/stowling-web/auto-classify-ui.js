(() => {
    "use strict";

    const Stowling = window.Stowling;
    const Auto = window.StowlingAutoClassifyTaskModel;
    const cleanup = window.StowlingTaskCleanupSettings.create(
        document.querySelector("#auto-cleanup-settings"),
        {allowContinue: false, defaultLabel: "使用本任务缓存区域"});
    const state = {
        revision: "",
        items: [],
        allTasks: [],
        resources: {
            coordinates: [],
            regions: [],
            categoryZones: []
        },
        draft: Auto.newDraft([]),
        ready: false,
        loading: false,
        busy: false,
        dirty: false,
        active: false,
        expanded: false,
        warehouseSnapshot: null,
        phase: { collection: false, pickup: false, packing: false, classification: false }
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
        cacheMode: document.querySelector("#auto-cache-mode"),
        cacheThreshold: document.querySelector("#auto-cache-threshold"),
        cacheEnabled: document.querySelector("#auto-cache-packing-enabled"),
        pickupHeader: document.querySelector("#auto-pickup-toggle"),
        pickupBody: document.querySelector("#auto-pickup-body"),
        packingHeader: document.querySelector("#auto-packing-toggle"),
        packingBody: document.querySelector("#auto-packing-body"),
        areas: document.querySelector("#auto-classification-areas"),
        addArea: document.querySelector("#add-auto-classification-area"),
        collectHeader: document.querySelector("#auto-collect-toggle"),
        collectBody: document.querySelector("#auto-collect-body"),
        collectEnabled: document.querySelector("#auto-collect-enabled"),
        classifyHeader: document.querySelector("#auto-sort-toggle"),
        classifyBody: document.querySelector("#auto-sort-body"),
        classifyEnabled: document.querySelector("#auto-classify-enabled"),
        priority: document.querySelector("#auto-collect-priority"),
        message: document.querySelector("#auto-task-message"),
        resourceMessage: document.querySelector("#auto-resources-message"),
        newTask: document.querySelector("#new-auto-task"),
        refresh: document.querySelector("#refresh-move-tasks"),
        remove: document.querySelector("#delete-auto-task"),
        warehouseEnabled: document.querySelector("#robot-warehouse-enabled")
    };
    const rows = new Map();
    const phaseDisabledControls = new Map();
    let areaReaders = [];

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
            const [
                tasks,
                coordinates,
                regions,
                zones,
                allTasks,
                warehouse
            ] = await Promise.all([
                Stowling.request("/v1/auto-classify-tasks"),
                Stowling.request("/v1/coordinates"),
                Stowling.request("/v1/regions"),
                Stowling.request("/v1/category-zones"),
                Stowling.request("/v1/tasks"),
                Stowling.request("/v1/storage-settings")
            ]);
            state.revision = String(tasks.revision || "");
            state.items = Array.isArray(tasks.items) ? tasks.items : [];
            state.allTasks = Array.isArray(allTasks.tasks) ? allTasks.tasks : [];
            state.warehouseSnapshot = warehouse;
            state.resources = {
                coordinates: Array.isArray(coordinates.coordinates) ? coordinates.coordinates : [],
                regions: Array.isArray(regions.regions) ? regions.regions : [],
                categoryZones: Array.isArray(zones.categoryZones)
                    ? zones.categoryZones
                    : []
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
    function renderClassificationAreas() {
        for (const control of phaseDisabledControls.keys()) {
            if (elements.areas.contains(control)) phaseDisabledControls.delete(control);
        }
        elements.areas.replaceChildren();
        areaReaders = [];
        state.draft.classificationAreas.forEach((area, index) => {
            areaReaders.push(appendClassificationArea(area, index));
        });
    }

    /**
     * @param {object} area
     * @param {number} index
     * @returns {{read: () => object, collapse: () => void}}
     */
    function appendClassificationArea(area, index) {
        const range = document.createElement("select");
        range.required = true;
        const empty = new Option("请选择分类选区", "");
        range.append(empty);
        for (const zone of state.resources.categoryZones) {
            const option = new Option(
                zone.name || "未命名分类区",
                zone.id
            );
            range.append(option);
        }
        const selectedZone = state.resources.categoryZones.find(
            (zone) => zone.id === area.categoryZoneId
        );
        if (!selectedZone && area.categoryZoneId) {
            const missing = new Option("原分类选区已删除，请重新选择", "");
            missing.disabled = true;
            range.append(missing);
        }
        range.value = area.categoryZoneId;

        const policy = area.storagePolicy || selectedZone?.bindings?.[0]?.storagePolicy || {};
        const storage = window.StowlingStoragePolicyEditor.create(policy, state.resources);
        const fields = Stowling.element("div", "auto-area-fields");
        fields.append(
            labeledControl("分类范围", range),
            Stowling.element("p", "muted compact-help",
                "识别方式使用仓储布局中各分类物的设置。"),
            storage.root
        );
        const disclosure = window.StowlingSettings.disclosure(
            `分类选区 ${index + 1}`,
            selectedZone?.name || "请选择分类范围", fields);
        disclosure.root.classList.add("auto-classification-area");
        const remove = Stowling.element("button", "ghost compact danger-action", "移除");
        remove.type = "button";
        remove.addEventListener("click", event => {
            event.preventDefault();
            event.stopPropagation();
            removeClassificationArea(index);
        });
        disclosure.summary.insertBefore(remove, disclosure.summary.lastElementChild);
        range.addEventListener("input", () => {
            disclosure.description.textContent = range.selectedOptions[0]?.textContent || "";
        });
        elements.areas.append(disclosure.root);
        return {
            collapse: () => {
                disclosure.setExpanded(false);
                storage.collapse();
            },
            read: () => ({
                categoryZoneId: range.value,
                storagePolicy: storage.read()
            })
        };
    }

    /**
     * @param {string} label
     * @param {HTMLElement} control
     * @returns {HTMLElement}
     */
    function labeledControl(label, control) {
        const wrapper = Stowling.element("label", "move-field");
        wrapper.append(Stowling.element("span", "", label), control);
        return wrapper;
    }

    /** @returns {void} */
    function addClassificationArea() {
        if (unavailable()) {
            return;
        }
        readForm();
        state.draft.classificationAreas.push(Auto.newArea());
        state.dirty = true;
        showMessage("未保存修改");
        renderClassificationAreas();
        updateControls();
    }

    /**
     * @param {number} index
     * @returns {void}
     */
    function removeClassificationArea(index) {
        if (unavailable()) {
            return;
        }
        readForm();
        state.draft.classificationAreas.splice(index, 1);
        state.dirty = true;
        showMessage("未保存修改");
        renderClassificationAreas();
        updateControls();
    }

    /** @returns {void} */
    function syncForm() {
        const draft = state.draft;
        elements.name.value = draft.name;
        elements.radius.value = draft.pickupRadius;
        elements.priority.value = draft.collectionPriority;
        elements.cacheMode.value = draft.cachePolicy.mode;
        elements.cacheThreshold.value = draft.cachePolicy.thresholdPercent;
        elements.cacheEnabled.checked = draft.cachePolicy.enabled;
        populateSelect(elements.point, state.resources.coordinates, draft.coordinateId,
            "请选择坐标页面的检测点");
        populateSelect(elements.cache, state.resources.regions, draft.cacheRegionId,
            "请选择包含缓存箱子的区域");
        elements.collectEnabled.checked = draft.collectionEnabled;
        elements.classifyEnabled.checked = draft.classificationEnabled;
        elements.warehouseEnabled.checked = draft.overflowEnabled;
        cleanup.resources(state.resources.coordinates, state.resources.regions);
        cleanup.set({destinationRef: draft.cleanupDestinationRef, resumeMode: "RESTART"});
        renderClassificationAreas();
        updateControls();
    }

    /** @returns {void} */
    function readForm() {
        if (!elements.collectEnabled.checked) {
            elements.classifyEnabled.checked = false;
            elements.cacheEnabled.checked = false;
        }
        state.draft = {
            ...state.draft,
            name: elements.name.value,
            coordinateId: elements.point.value,
            pickupRadius: elements.radius.value,
            cacheRegionId: elements.cache.value,
            cachePolicy: {enabled: elements.cacheEnabled.checked, mode: elements.cacheMode.value,
                thresholdPercent: Number(elements.cacheThreshold.value)},
            classificationAreas: areaReaders.map((reader) => reader.read()),
            collectionPriority: elements.priority.value,
            collectionEnabled: elements.collectEnabled.checked,
            classificationEnabled: elements.classifyEnabled.checked,
            overflowEnabled: elements.warehouseEnabled.checked,
            overflowDestinationRef: "",
            cleanupDestinationRef: cleanup.read().destinationRef || ""
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
            const cache = state.resources.regions.find(
                (region) => region.id === draft.cacheRegionId
            );
            const zoneNames = [...new Set(
                draft.classificationAreas.map((area) => state.resources
                    .categoryZones.find((zone) => zone.id === area.categoryZoneId)
                    ?.name || "分类区已删除")
            )];
            overview.append(Stowling.element("span", "",
                `${cache?.name || "缓存区域已删除"} · `
                + `${draft.classificationAreas.length} 个分类选区`
                + (zoneNames.length > 0 ? `（${zoneNames.join("、")}）` : "")));
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
            const header = Stowling.element("div", "task-card-header");
            const listening = window.StowlingSettings.switchControl(
                document.createElement("input"), `监听任务：${item.name}`
            );
            listening.checked = item.enabled;
            listening.addEventListener("change", () => {
                void setListening(item, listening.checked);
            });
            header.append(button, listening);
            card.append(header, body);
            elements.list.append(card);
            rows.set(item.id, { button, body, status, listening });
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
        void window.StowlingSettings.reveal(elements.draftBody, isNew && state.expanded);
        elements.form.hidden = false;
        elements.draftToggle.setAttribute("aria-expanded", String(isNew && state.expanded));
        elements.remove.hidden = !state.draft.id;
        for (const [id, row] of rows) {
            const selected = state.draft.id === id;
            const expanded = selected && state.expanded;
            row.button.disabled = disabled;
            row.listening.disabled = disabled || (selected && state.dirty);
            row.listening.title = selected && state.dirty ? "先保存当前修改，再切换监听" : "启用监听";
            row.button.setAttribute("aria-expanded", String(expanded));
            void window.StowlingSettings.reveal(row.body, expanded);
            const item = state.items.find((value) => value.id === id);
            row.status.textContent = selected && state.dirty ? "未保存修改"
                : (item?.enabled ? "监听中" : "已保存");
        }
        const phaseStates = [
            ["collection", elements.collectHeader, elements.collectBody],
            ["pickup", elements.pickupHeader, elements.pickupBody],
            ["packing", elements.packingHeader, elements.packingBody],
            ["classification", elements.classifyHeader, elements.classifyBody]
        ];
        for (const [phase, header, body] of phaseStates) {
            if (!header || !body) {
                continue;
            }
            const enabled = phaseEnabled(phase);
            header.disabled = disabled || !enabled;
            header.title = enabled ? "展开或收起子任务设置" : "";
            header.setAttribute("aria-expanded", String(enabled && state.phase[phase]));
            void window.StowlingSettings.reveal(body, enabled && state.phase[phase]);
            header.closest(".auto-config-section").dataset.enabled = String(enabled);
            if (phase === "collection") continue;
            for (const control of body.querySelectorAll("input, select, textarea, button")) {
                if (!enabled && !phaseDisabledControls.has(control)) {
                    phaseDisabledControls.set(control, control.disabled);
                    control.disabled = true;
                } else if (enabled && phaseDisabledControls.has(control)) {
                    control.disabled = phaseDisabledControls.get(control);
                    phaseDisabledControls.delete(control);
                }
            }
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
        state.phase.collection = false;
        state.phase.pickup = false;
        state.phase.packing = false;
        state.phase.classification = false;
        syncForm();
        renderList();
        showMessage(item
            ? "已保存；可分别设置掉落物拾取和实验性打包缓存。"
            : "配置检测点、缓存区域与分类选区后保存任务。");
    }

    /**
     * @param {string} phase
     * @returns {boolean}
     */
    function phaseEnabled(phase) {
        if (phase === "pickup") return elements.collectEnabled.checked;
        if (phase === "packing") return elements.cacheEnabled.checked;
        if (phase === "classification") return elements.classifyEnabled.checked;
        return true;
    }

    /**
     * @param {string} phase
     * @returns {void}
     */
    function togglePhase(phase) {
        if (unavailable()) {
            return;
        }
        const enabled = phaseEnabled(phase);
        if (!enabled) return;
        if (phase === "classification" && !state.phase[phase]) {
            areaReaders.forEach(reader => reader.collapse());
        }
        state.phase[phase] = !state.phase[phase];
        updateControls();
    }

    /**
     * @param {object} item
     * @param {boolean} enabled
     * @returns {Promise<void>}
     */
    async function setListening(item, enabled) {
        if (unavailable()) {
            renderList();
            return;
        }
        state.busy = true;
        updateControls();
        try {
            const operation = enabled ? "start" : "stop";
            const response = await Stowling.request(
                `/v1/auto-classify-tasks/${encodeURIComponent(item.id)}/${operation}`, {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ revision: state.revision })
                }
            );
            if (!response.item?.id) {
                throw new Error("任务状态未返回，请刷新重试");
            }
            state.revision = String(response.revision || "");
            state.items = state.items.map((task) => task.id === item.id ? response.item : task);
            if (state.draft.id === item.id) {
                state.draft.enabled = response.item.enabled;
            }
            Stowling.showToast(enabled ? "已开始监听" : "已停止监听，已排队任务会继续执行");
        } catch (error) {
            Stowling.handleError(error, "切换任务监听失败");
        } finally {
            state.busy = false;
            renderList();
            document.dispatchEvent(new Event("stowling:auto-tasks-changed"));
        }
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

    /** @returns {Promise<void>} */
    async function saveTask() {
        if (unavailable() || !state.expanded) {
            return;
        }
        readForm();
        let payload;
        try {
            payload = Auto.payload(state.draft, state.resources, state.items, state.allTasks);
            if (state.draft.overflowEnabled
                    && !Auto.warehouseDraft(state.warehouseSnapshot?.settings).enabled) {
                throw new RangeError("请先在「坐标 → 公共设施 → 机器人仓库」配置存放位置");
            }
            delete payload.enabled;
        } catch (error) {
            showMessage(String(error.message || error), true);
            return;
        }
        state.busy = true;
        updateControls();
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
            }
            showMessage("自动分类已保存");
            Stowling.showToast("自动分类已保存");
        } catch (error) {
            showMessage(String(error.message || "保存失败，请刷新重试。"), true);
            Stowling.handleError(error, "保存自动分类失败");
        } finally {
            state.busy = false;
            updateControls();
            document.dispatchEvent(new Event("stowling:auto-tasks-changed"));
        }
    }

    /** @returns {Promise<void>} */
    async function deleteTask() {
        if (unavailable() || !state.draft.id) {
            return;
        }
        const prompt = "删除整个自动分类任务？若任务正在运行，请先取消并完成收尾。";
        const warning = state.dirty ? "未保存修改将被丢弃。" : "";
        if (!window.confirm(`${prompt}${warning}`)) {
            return;
        }
        state.busy = true;
        updateControls();
        try {
            const id = state.draft.id;
            const response = await Stowling.request(
                `/v1/auto-classify-tasks/${encodeURIComponent(id)}`, {
                    method: "DELETE",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ revision: state.revision })
                }
            );
            state.revision = String(response.revision || "");
            state.items = state.items.filter((item) => item.id !== id);
            state.draft = Auto.newDraft(state.items, state.allTasks);
            state.dirty = false;
            state.active = false;
            state.expanded = false;
            syncForm();
            renderList();
            Stowling.showToast("自动分类任务已删除");
        } catch (error) {
            showMessage(String(error.message || "操作失败，请刷新重试"), true);
            Stowling.handleError(error, "删除自动分类失败");
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
    elements.collectHeader.addEventListener("click", () => togglePhase("collection"));
    elements.pickupHeader.addEventListener("click", () => togglePhase("pickup"));
    elements.packingHeader.addEventListener("click", () => togglePhase("packing"));
    elements.classifyHeader.addEventListener("click", () => togglePhase("classification"));
    elements.collectBody.addEventListener("invalid", (event) => {
        state.phase.collection = true;
        if (elements.pickupBody.contains(event.target)) state.phase.pickup = true;
        if (elements.packingBody.contains(event.target)) state.phase.packing = true;
        updateControls();
    }, true);
    elements.classifyBody.addEventListener("invalid", () => {
        state.phase.classification = true;
        updateControls();
    }, true);
    elements.addArea.addEventListener("click", addClassificationArea);
    elements.refresh.addEventListener("click", () => void loadResources());
    elements.form.addEventListener("input", (event) => {
        if (event.target === elements.classifyEnabled) {
            state.phase.classification = elements.classifyEnabled.checked;
            if (elements.classifyEnabled.checked) {
                areaReaders.forEach(reader => reader.collapse());
                elements.collectEnabled.checked = true;
            }
        }
        if (event.target === elements.collectEnabled) {
            state.phase.pickup = elements.collectEnabled.checked;
            if (!elements.collectEnabled.checked) {
                elements.classifyEnabled.checked = false;
                state.phase.classification = false;
            }
        }
        if (event.target === elements.cacheEnabled) {
            state.phase.packing = elements.cacheEnabled.checked;
            if (elements.cacheEnabled.checked) elements.collectEnabled.checked = true;
        }
        readForm();
        state.dirty = true;
        showMessage("未保存修改");
        updateControls();
    });
    elements.form.addEventListener("submit", (event) => {
        event.preventDefault();
        void saveTask();
    });
    elements.remove.addEventListener("click", () => void deleteTask());
    document.addEventListener("stowling:tasks-changed", () => void loadResources());
    Stowling.onConnected(loadResources);
    updateControls();
    window.setInterval(updateControls, 3000);
})();
