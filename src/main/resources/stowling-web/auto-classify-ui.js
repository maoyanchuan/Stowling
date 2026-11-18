(() => {
    "use strict";

    const Stowling = window.Stowling;
    const Auto = window.StowlingAutoClassifyTaskModel;
    const state = {
        revision: "",
        items: [],
        allTasks: [],
        resources: {
            coordinates: [],
            regions: [],
            categoryZones: [],
            categoryRules: []
        },
        draft: Auto.newDraft([]),
        ready: false,
        loading: false,
        busy: false,
        dirty: false,
        active: false,
        expanded: false,
        phase: { collection: false, classification: false }
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
        overflow: document.querySelector("#auto-overflow-enabled"),
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
        save: document.querySelector("#save-auto-task"),
        start: document.querySelector("#start-auto-task"),
        stop: document.querySelector("#stop-auto-task"),
        remove: document.querySelector("#delete-auto-task")
    };
    const rows = new Map();
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
                rules,
                allTasks
            ] = await Promise.all([
                Stowling.request("/v1/auto-classify-tasks"),
                Stowling.request("/v1/coordinates"),
                Stowling.request("/v1/regions"),
                Stowling.request("/v1/category-zones"),
                Stowling.request("/v1/category-rules"),
                Stowling.request("/v1/tasks")
            ]);
            state.revision = String(tasks.revision || "");
            state.items = Array.isArray(tasks.items) ? tasks.items : [];
            state.allTasks = Array.isArray(allTasks.tasks) ? allTasks.tasks : [];
            state.resources = {
                coordinates: Array.isArray(coordinates.coordinates) ? coordinates.coordinates : [],
                regions: Array.isArray(regions.regions) ? regions.regions : [],
                categoryZones: Array.isArray(zones.categoryZones)
                    ? zones.categoryZones
                    : [],
                categoryRules: Array.isArray(rules.rules) ? rules.rules : []
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
        elements.areas.replaceChildren();
        areaReaders = [];
        if (state.draft.classificationAreas.length === 0) {
            state.draft.classificationAreas.push(Auto.newArea());
        }
        state.draft.classificationAreas.forEach((area, index) => {
            areaReaders.push(appendClassificationArea(area, index));
        });
    }

    /**
     * @param {object} area
     * @param {number} index
     * @returns {{read: () => object}}
     */
    function appendClassificationArea(area, index) {
        const card = Stowling.element("article", "auto-classification-area");
        const heading = Stowling.element(
            "div",
            "auto-classification-area-heading"
        );
        const title = Stowling.element(
            "div",
            "auto-classification-area-title"
        );
        title.append(
            Stowling.element("strong", "", `分类选区 ${index + 1}`),
            Stowling.element(
                "span",
                "",
                "大选区内的识别与储存范围在坐标管理中维护"
            )
        );
        const remove = Stowling.element(
            "button",
            "ghost compact danger-action",
            "移除"
        );
        remove.type = "button";
        remove.addEventListener("click", () => {
            removeClassificationArea(index);
        });
        heading.append(title, remove);

        const range = document.createElement("select");
        range.required = true;
        const empty = new Option("请选择分类选区", "");
        range.append(empty);
        for (const zone of state.resources.categoryZones) {
            const option = new Option(
                zone.name || "未命名分类区",
                zone.id
            );
            option.dataset.ruleId = zone.bindings?.[0]?.ruleId
                || "DIRECT_BLOCK_ID";
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
        const storageMode = document.createElement("select");
        for (const [value, name] of [["NORMAL", "普通散装"], ["MIXED", "散装与潜影盒混合"],
                ["BOX_ONLY", "纯潜影盒"]]) storageMode.append(new Option(name, value));
        storageMode.value = policy.mode || "NORMAL";
        const loose = document.createElement("input");
        loose.type = "number";
        loose.min = "0";
        loose.max = "100";
        loose.value = String(policy.loosePercent ?? 50);
        const partial = document.createElement("input");
        partial.type = "checkbox";
        partial.className = "settings-switch";
        partial.checked = Boolean(policy.allowPartialBoxes);
        const refill = document.createElement("input");
        refill.type = "checkbox";
        refill.className = "settings-switch";
        refill.checked = Boolean(policy.refillLoose);
        const layoutLink = Stowling.element("a", "text-link", "管理仓储布局与公共设施 →");
        layoutLink.href = "/coordinates.html#category-zones";
        const recognition = document.createElement("select");
        const smartRuleId = categoryRuleId(
            ["DIRECT_BLOCK_ID"],
            "DIRECT_BLOCK_ID"
        );
        const containerRuleId = categoryRuleId(
            ["MARKER_CONTAINER_CONTENTS", "CONTAINER_CONTENTS"],
            "MARKER_CONTAINER_CONTENTS"
        );
        recognition.append(
            new Option("智能识别", smartRuleId),
            new Option("标记箱/木桶", containerRuleId)
        );
        recognition.value = Auto.isContainerRuleId(area.ruleId)
                || area.markerSources.includes("CONTAINER_CONTENTS")
            ? containerRuleId
            : smartRuleId;

        /**
         * @param {Event} event
         * @returns {void}
         */
        function syncRange(event) {
            if (event.target !== range) {
                return;
            }
            const option = range.selectedOptions[0];
            if (option?.dataset.ruleId) {
                recognition.value = Auto.isContainerRuleId(
                    option.dataset.ruleId
                )
                    ? containerRuleId
                    : smartRuleId;
            }
        }

        range.addEventListener("input", syncRange);
        const fields = Stowling.element("div", "auto-area-fields");
        fields.append(
            labeledControl("分类范围", range),
            labeledControl("分类物识别方式", recognition),
            labeledControl("储存模式", storageMode),
            labeledControl("散装槽位比例（%）", loose),
            labeledControl("允许未满盒入库，下次优先补满", partial),
            labeledControl("混合模式：散货不足阈值时拆盒补足", refill), layoutLink
        );
        card.append(heading, fields);
        elements.areas.append(card);
        return {
            read: () => ({
                categoryZoneId: range.value,
                storagePolicy: {mode: storageMode.value, loosePercent: Number(loose.value),
                    allowPartialBoxes: partial.checked, refillLoose: refill.checked,
                    packingArea: null},
                ruleId: recognition.value,
                markerSources: Auto.defaultMarkerSources(recognition.value)
            })
        };
    }

    /**
     * @param {Array<string>} preferredIds
     * @param {string} fallback
     * @returns {string}
     */
    function categoryRuleId(preferredIds, fallback) {
        const rules = state.resources.categoryRules;
        for (const id of preferredIds) {
            if (rules.some((rule) => rule.id === id)) {
                return id;
            }
        }
        return fallback;
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
        if (state.draft.classificationAreas.length === 0) {
            state.draft.classificationAreas.push(Auto.newArea());
        }
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
        populateSelect(elements.point, state.resources.coordinates, draft.coordinateId,
            "请选择坐标页面的检测点");
        populateSelect(elements.cache, state.resources.regions, draft.cacheRegionId,
            "请选择包含缓存箱子的区域");
        elements.collectEnabled.checked = draft.collectionEnabled;
        elements.classifyEnabled.checked = draft.classificationEnabled;
        elements.overflow.checked = Boolean(draft.overflowEnabled);
        renderClassificationAreas();
        updateControls();
    }

    /** @returns {void} */
    function readForm() {
        if (!elements.collectEnabled.checked) {
            elements.classifyEnabled.checked = false;
        }
        state.draft = {
            ...state.draft,
            name: elements.name.value,
            coordinateId: elements.point.value,
            pickupRadius: elements.radius.value,
            cacheRegionId: elements.cache.value,
            classificationAreas: areaReaders.map((reader) => reader.read()),
            collectionPriority: elements.priority.value,
            collectionEnabled: elements.collectEnabled.checked,
            classificationEnabled: elements.classifyEnabled.checked,
            overflowEnabled: elements.overflow.checked
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
            row.listening.disabled = disabled || (selected && state.dirty);
            row.listening.title = selected && state.dirty ? "先保存当前修改，再切换监听" : "启用监听";
            row.button.setAttribute("aria-expanded", String(expanded));
            row.body.hidden = !expanded;
            const item = state.items.find((value) => value.id === id);
            row.status.textContent = selected && state.dirty ? "未保存修改"
                : (item?.enabled ? "监听中" : "已保存");
        }
        const phaseStates = [
            ["collection", elements.collectHeader, elements.collectBody],
            ["classification", elements.classifyHeader, elements.classifyBody]
        ];
        for (const [phase, header, body] of phaseStates) {
            if (!header || !body) {
                continue;
            }
            header.setAttribute("aria-expanded", String(state.phase[phase]));
            body.hidden = !state.phase[phase];
            header.closest(".auto-config-section").dataset.enabled = String(
                phase === "collection"
                    ? elements.collectEnabled.checked : elements.classifyEnabled.checked
            );
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
        state.phase.classification = false;
        syncForm();
        renderList();
        showMessage(item
            ? "已保存；拾取物存入缓存后，再创建后台分类任务。"
            : "配置检测点、缓存区域与分类选区后，保存或开始监听。");
    }

    /**
     * @param {"collection" | "classification"} phase
     * @returns {void}
     */
    function togglePhase(phase) {
        if (unavailable()) {
            return;
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
                ? "已开始监听；拾取物存入缓存后会创建后台分类任务。"
                : "自动分类已保存";
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
    elements.collectHeader.addEventListener("click", () => togglePhase("collection"));
    elements.classifyHeader.addEventListener("click", () => togglePhase("classification"));
    elements.addArea.addEventListener("click", addClassificationArea);
    elements.refresh.addEventListener("click", () => void loadResources());
    elements.form.addEventListener("input", (event) => {
        if (event.target === elements.classifyEnabled) {
            state.phase.classification = elements.classifyEnabled.checked;
            if (elements.classifyEnabled.checked) {
                elements.collectEnabled.checked = true;
            }
        }
        if (event.target === elements.collectEnabled) {
            state.phase.collection = elements.collectEnabled.checked;
            if (!elements.collectEnabled.checked) {
                elements.classifyEnabled.checked = false;
                state.phase.classification = false;
            }
        }
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
