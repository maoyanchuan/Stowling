(() => {
    "use strict";

    const Stowling = window.Stowling;
    const Auto = window.StowlingAutoClassifyTaskModel;
    const Autosave = window.StowlingTaskAutosave;
    const Picker = window.StowlingResourcePicker;
    const endpoint = "/v1/auto-classify-tasks";
    const cleanup = window.StowlingTaskCleanupSettings.create(
        document.querySelector("#auto-cleanup-settings"),
        {allowContinue: false, facilities: true, defaultLabel: "使用本任务缓存位置"});
    const boxSettings = window.StowlingEmptyBoxSettings?.create(
        document.querySelector("#auto-empty-box-settings"));
    const remainderSettings = window.StowlingClassificationRemainders?.create(
        document.querySelector("#auto-remainder-settings"),
        document.querySelector("#robot-warehouse-settings"));
    const state = {
        revision: "",
        draftRevision: "",
        original: null,
        items: [],
        regularTasks: [],
        resources: {
            coordinates: [],
            regions: [],
            categoryZones: [],
            warehouses: []
        },
        draft: Auto.newDraft(),
        ready: false,
        loading: false,
        busy: false,
        pendingSwitch: null,
        classificationRuns: [],
        dirty: false,
        active: false,
        expanded: false,
        phase: {collection: false, pickup: false, packing: false,
            classification: false, followUp: false, warehouse: false}
    };
    const elements = {
        list: document.querySelector("#auto-task-list"),
        draft: document.querySelector("#auto-task-draft"),
        draftToggle: document.querySelector("#toggle-auto-draft"),
        draftBody: document.querySelector("#auto-draft-body"),
        form: document.querySelector("#auto-task-form"),
        fields: document.querySelector("#auto-task-fields"),
        point: document.querySelector("#auto-detection-point"),
        radius: document.querySelector("#auto-pickup-radius"),
        detectionInterval: document.querySelector("#auto-detection-interval"),
        cache: document.querySelector("#auto-cache-location"),
        cacheMode: document.querySelector("#auto-cache-mode"),
        cacheThreshold: document.querySelector("#auto-cache-threshold"),
        cacheEnabled: document.querySelector("#auto-cache-packing-enabled"),
        pickupHeader: document.querySelector("#auto-pickup-toggle"),
        pickupBody: document.querySelector("#auto-pickup-body"),
        packingHeader: document.querySelector("#auto-packing-toggle"),
        packingBody: document.querySelector("#auto-packing-body"),
        targets: document.querySelector("#auto-classification-targets"),
        targetOrder: document.querySelector("#auto-classification-order"),
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
        newClassification: document.querySelector("#new-classification-task"),
        draftTitle: document.querySelector("#auto-draft-title"),
        draftIcon: document.querySelector("#auto-draft-icon"),
        draftRun: document.querySelector("#auto-draft-run"),
        combinedPreview: document.querySelector("#combined-task-preview"),
        cacheLabel: document.querySelector("#auto-cache-label"),
        followUp: document.querySelector("#auto-follow-up-task"),
        followUpEnabled: document.querySelector("#auto-follow-up-enabled"),
        followUpDelay: document.querySelector("#auto-follow-up-delay"),
        followUpHeader: document.querySelector("#auto-follow-up-toggle"),
        followUpBody: document.querySelector("#auto-follow-up-body"),
        followUpDescription: document.querySelector("#auto-follow-up-description"),
        followUpField: document.querySelector("#auto-follow-up-field"),
        refresh: document.querySelector("#refresh-move-tasks"),
        remove: document.querySelector("#delete-auto-task"),
        warehouseEnabled: document.querySelector("#robot-warehouse-enabled"),
        warehouse: document.querySelector("#robot-warehouse-location"),
        warehouseMixed: document.querySelector("#robot-warehouse-mixed-boxes"),
        batching: document.querySelector("#auto-same-category-batching"),
        batchingRow: document.querySelector("#auto-same-category-batching-row"),
        baseline: document.querySelector("#auto-classification-baseline"),
        baselineRow: document.querySelector("#auto-classification-baseline-row"),
        baselineStatus: document.querySelector("#auto-classification-baseline-status"),
        warehouseHeader: document.querySelector("#robot-warehouse-toggle"),
        warehouseBody: document.querySelector("#robot-warehouse-body")
    };
    const rows = new Map();
    for (const content of elements.form.querySelectorAll(
        ".auto-phase-body:not(#auto-collect-body), .settings-detail")) {
        wrapSettingsContent(content);
    }
    const selectView = window.StowlingTaskSelect?.create(elements.form);
    const phaseDisabledControls = new Map();
    const autosave = Autosave.create(elements.form, saveTask, () => state.busy || state.loading);

    /** @param {HTMLElement} content @returns {void} */
    function wrapSettingsContent(content) {
        const inner = Stowling.element("div", "task-settings-inner");
        inner.append(...content.childNodes);
        content.append(inner);
    }

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
            const [tasks, resources, taskDirectory] = await Promise.all([
                Stowling.request(endpoint), Picker.load(), Stowling.request("/v1/tasks")
            ]);
            state.regularTasks = taskDirectory.tasks || [];
            state.revision = String(tasks.revision || "");
            state.items = Array.isArray(tasks.items) ? tasks.items : [];
            state.resources = {...resources, warehouses: resources.catalog?.warehouses || []};
            state.ready = true;
            if (state.draft.id && !state.dirty) {
                const item = state.items.find((task) => task.id === state.draft.id);
                if (item) {
                    state.draft = Auto.draftFromTask(item);
                    state.original = item;
                    state.draftRevision = state.revision;
                } else {
                    state.draft = Auto.newDraft();
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
    function renderClassificationTargets() {
        const selected = new Set(state.draft.classificationAreas.map(area => area.categoryZoneId));
        elements.targets.replaceChildren();
        for (const zone of state.resources.categoryZones) {
            const option = new Option(zone.name || "未命名分类区", zone.id);
            option.selected = selected.has(zone.id);
            elements.targets.append(option);
            selected.delete(zone.id);
        }
        for (const id of selected) {
            if (!id) continue;
            const missing = new Option("已删除的分类区", id);
            missing.selected = true;
            missing.dataset.description = "请取消选择，再选择已有的分类区";
            elements.targets.append(missing);
        }
        renderClassificationOrder();
        Picker.group(elements.targets, "category-zone", "category-zones", false);
    }

    /** @returns {void} */
    function renderClassificationOrder() {
        const ids = state.draft.classificationAreas.map(area => area.categoryZoneId).filter(Boolean);
        const names = [];
        for (const option of elements.targets.options) {
            const index = ids.indexOf(option.value);
            option.dataset.selectionOrder = String(index < 0 ? ids.length : index);
        }
        for (const id of ids) {
            const option = Array.from(elements.targets.options).find(item => item.value === id);
            names.push(option?.textContent || "已删除的分类区");
        }
        elements.targetOrder.textContent = names.length > 1
            ? "执行顺序：" + names.join(" → ") : "";
        elements.targetOrder.hidden = names.length < 2;
    }

    /** @returns {void} */
    function syncForm() {
        const draft = state.draft;
        elements.radius.value = draft.pickupRadius;
        elements.detectionInterval.value = draft.detectionIntervalSeconds;
        elements.followUpDelay.value = draft.followUpDelaySeconds ?? "30";
        elements.followUpEnabled.checked = draft.followUpEnabled === true;
        elements.priority.value = draft.collectionPriority;
        elements.cacheMode.value = draft.cachePolicy.mode;
        elements.cacheThreshold.value = draft.cachePolicy.thresholdPercent;
        elements.cacheEnabled.checked = draft.cachePolicy.enabled;
        populateSelect(elements.point, state.resources.coordinates, draft.coordinateId,
            "请选择坐标页面的检测点");
        Picker.group(elements.point, "coordinate");
        populateSelect(elements.cache, Auto.cacheLocations(state.resources),
            draft.cacheLocationRef,
            draft.kind === "CLASSIFICATION" ? "请选择物品来源" : "请选择缓存位置");
        Picker.group(elements.cache);
        populateSelect(elements.warehouse, Auto.robotWarehouseLocations(state.resources),
            draft.overflowDestinationRef, "请选择机器人仓库区域");
        Picker.group(elements.warehouse, undefined, "regions", false);
        elements.warehouseMixed.checked = draft.robotWarehouseMixedBoxes;
        elements.batching.checked = draft.sameCategoryBoxBatching;
        elements.baseline.checked = draft.classificationBaselineEnabled;
        const available = [...state.regularTasks, ...state.items]
            .filter(task => task.id !== draft.id)
            .map(task => ({id: "task:" + task.id, name: task.name}));
        populateSelect(elements.followUp, available, draft.followUpTaskRef || "", "不创建任务");
        syncRadiusHint();
        elements.collectEnabled.checked = draft.collectionEnabled;
        elements.classifyEnabled.checked = draft.classificationEnabled;
        elements.warehouseEnabled.checked = draft.overflowEnabled;
        cleanup.resources(state.resources.coordinates, state.resources.regions);
        cleanup.set({...draft.facilities, destinationRef: draft.cleanupDestinationRef,
            resumeMode: "RESTART"});
        boxSettings?.resources(state.resources.coordinates, state.resources.regions);
        boxSettings?.set(draft.facilities.emptyBoxWarehouse);
        remainderSettings?.resources(Auto.cacheLocations(state.resources));
        remainderSettings?.set(draft.remainderPolicy);
        renderClassificationTargets();
        updateControls();
    }

    /** @returns {void} */
    function syncRadiusHint() {
        const point = state.resources.coordinates.find(item => item.id === elements.point.value);
        elements.radius.placeholder = "跟随点位（" + (point?.radius ?? 8) + " 格）";
    }

    /** @returns {void} */
    function readForm() {
        const kind = state.draft.kind;
        if (!kind && !elements.collectEnabled.checked) {
            elements.classifyEnabled.checked = false;
            elements.cacheEnabled.checked = false;
        }
        state.draft = {
            ...state.draft,
            coordinateId: elements.point.value,
            pickupRadius: elements.radius.value,
            detectionIntervalSeconds: elements.detectionInterval.value,
            cacheLocationRef: elements.cache.value,
            cachePolicy: {
                enabled: kind !== "CLASSIFICATION" && elements.cacheEnabled.checked,
                mode: kind === "PICKUP" ? "PACK_FIRST" : elements.cacheMode.value,
                thresholdPercent: Number(elements.cacheThreshold.value)
            },
            classificationAreas: Auto.classificationAreasFromSelection(
                state.draft.classificationAreas,
                Array.from(elements.targets.selectedOptions, option => option.value)),
            collectionPriority: elements.priority.value,
            collectionEnabled: kind ? kind === "PICKUP" : elements.collectEnabled.checked,
            classificationEnabled: kind ? kind === "CLASSIFICATION"
                : elements.classifyEnabled.checked,
            ...(kind === "PICKUP" ? {followUpTaskRef: elements.followUp.value,
                followUpEnabled: elements.followUpEnabled.checked,
                followUpDelaySeconds: elements.followUpDelay.value} : {}),
            overflowEnabled: kind !== "PICKUP" && elements.warehouseEnabled.checked,
            overflowDestinationRef: elements.warehouse.value,
            robotWarehouseMixedBoxes: elements.warehouseMixed.checked,
            sameCategoryBoxBatching: elements.batching.checked,
            classificationBaselineEnabled: elements.baseline.checked,
            remainderPolicy: remainderSettings?.read() || state.draft.remainderPolicy,
            facilities: {...cleanup.read(), ...(kind === "CLASSIFICATION" && boxSettings
                ? {emptyBoxWarehouse: boxSettings.read()} : {})},
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
            const pickup = item.kind === "PICKUP";
            const classification = item.kind === "CLASSIFICATION";
            const standard = pickup || classification;
            const card = Stowling.element("article", standard
                ? "pickup-task-module" : "move-saved-task auto-saved-task");
            card.dataset.taskId = item.id;
            const button = Stowling.element("button", "move-task-summary");
            button.type = "button";
            const body = Stowling.element("div", "move-task-body");
            body.id = `auto-task-body-${item.id}`;
            button.setAttribute("aria-controls", body.id);
            const icon = Stowling.element("span", "move-task-kind", "⇄");
            icon.setAttribute("aria-hidden", "true");
            const overview = Stowling.element("span", "move-task-overview");
            overview.append(Stowling.element("strong", "", item.name));
            const cache = Auto.cacheLocations(state.resources)
                .find(item => item.id === draft.cacheLocationRef);
            const zoneNames = [...new Set(
                draft.classificationAreas.map((area) => state.resources
                    .categoryZones.find((zone) => zone.id === area.categoryZoneId)
                    ?.name || "分类区已删除")
            )];
            const source = cache?.name || (item.kind === "CLASSIFICATION"
                ? "物品来源已删除" : "缓存位置已删除");
            let description = source;
            if (item.kind === "PICKUP") {
                description = "缓存 · " + source;
            } else if (zoneNames.length > 0) {
                description = source + " → " + zoneNames.join("、");
            }
            overview.append(Stowling.element("span", "", description));
            const priority = window.StowlingTaskModel.priorityLabel(item.collectionPriority);
            const status = Stowling.element("span", "move-task-state",
                item.enabled ? "监听中" : "已保存");
            const chevron = Stowling.element("span", "move-task-chevron", "⌄");
            chevron.setAttribute("aria-hidden", "true");
            button.append(icon, overview, Stowling.element("span", "move-task-priority",
                item.kind === "CLASSIFICATION" ? "后台"
                    : item.kind === "PICKUP" ? priority : priority + " / 后台"), status, chevron);
            button.addEventListener("click", () => {
                selectTask(state.items.find((task) => task.id === item.id));
            });
            if (state.draft.id === item.id) {
                body.append(elements.form);
            }
            const header = Stowling.element("div", "task-card-header");
            const row = {button, body, status};
            if (standard) {
                header.className = "pickup-task-header";
                const title = Stowling.element("h3", "pickup-task-title", item.name);
                if (classification && item.classificationBaselineEnabled === true) {
                    const badge = Stowling.element("span", "task-debug-badge", "调试改动已开启");
                    badge.title = "classification-multi-source-fixed-order";
                    title.append(badge);
                }
                title.prepend(pickupIcon());
                if (classification) {
                    title.firstElementChild.firstElementChild.setAttribute("d",
                        "M4 4h6v6H4ZM14 4h6v6h-6ZM4 14h6v6H4ZM14 14h6v6h-6Z");
                }
                button.className = "pickup-task-expand";
                button.replaceChildren(disclosureIcon());
                status.className = "visually-hidden";
                status.id = `auto-task-status-${item.id}`;
                status.setAttribute("role", "status");
                status.setAttribute("aria-live", "polite");
                body.className = "pickup-task-body";
                header.append(title, button);
            } else {
                header.append(button);
            }
            row.listening = window.StowlingSettings.switchControl(
                document.createElement("input"), "开始任务：" + item.name
            );
            if (standard) row.listening.setAttribute("aria-describedby", status.id);
            row.listening.addEventListener("change", () => {
                void setListening(item.id, row.listening.checked);
            });
            header.append(row.listening);
            card.append(header, body);
            if (standard) card.append(status);
            elements.list.append(card);
            rows.set(item.id, row);
        }
        document.dispatchEvent(new CustomEvent("stowling:auto-task-count", {
            detail: {
                count: state.items.length,
                draftActive: state.active && !state.draft.id
            }
        }));
        updateControls();
    }

    /** @returns {SVGElement} */
    function pickupIcon() {
        const icon = document.createElementNS("http://www.w3.org/2000/svg", "svg");
        icon.setAttribute("viewBox", "0 0 24 24");
        icon.setAttribute("width", "20");
        icon.setAttribute("height", "20");
        icon.setAttribute("fill", "none");
        icon.setAttribute("stroke", "currentColor");
        icon.setAttribute("stroke-width", "1.8");
        icon.setAttribute("stroke-linejoin", "round");
        icon.setAttribute("aria-hidden", "true");
        const path = document.createElementNS("http://www.w3.org/2000/svg", "path");
        path.setAttribute("d", "M12 3 3 7.5v9L12 21l9-4.5v-9L12 3ZM3 7.5l9 4.5 9-4.5M12 12v9");
        icon.append(path);
        return icon;
    }

    /** @returns {SVGElement} */
    function disclosureIcon() {
        const icon = pickupIcon();
        icon.firstElementChild.setAttribute("d", "m9 5 7 7-7 7");
        return icon;
    }

    /** @returns {void} */
    function updateControls() {
        const disabled = unavailable();
        elements.fields.disabled = disabled;
        elements.newTask.disabled = disabled;
        elements.newClassification.disabled = disabled;
        const kind = state.draft.kind;
        const pickup = kind === "PICKUP";
        const classification = kind === "CLASSIFICATION";
        const boxHost = document.querySelector("#auto-empty-box-settings");
        if (boxHost) boxHost.hidden = !classification;
        cleanup.setSupplyVisible?.(!classification);
        boxSettings?.refresh();
        const remainderHost = document.querySelector("#auto-remainder-settings");
        if (remainderHost) remainderHost.hidden = pickup;
        remainderSettings?.refresh({robotEnabled: elements.warehouseEnabled.checked});
        elements.form.dataset.kind = kind || "COMBINED";
        elements.draft.classList.toggle("pickup-task-module", pickup || classification);
        elements.draftBody.classList.toggle("pickup-task-body", pickup || classification);
        elements.draftTitle.textContent = classification ? "新建 · 分类" : "新建 · 掉落物拾取";
        elements.draftIcon.setAttribute("d", classification
            ? "M4 4h6v6H4ZM14 4h6v6h-6ZM4 14h6v6H4ZM14 14h6v6h-6Z"
            : "M12 3 3 7.5v9L12 21l9-4.5v-9L12 3ZM3 7.5l9 4.5 9-4.5M12 12v9");
        elements.draftRun.hidden = !classification;
        elements.combinedPreview.hidden = Boolean(kind);
        elements.cacheLabel.textContent = classification ? "物品来源" : "缓存位置";
        elements.followUpField.hidden = !pickup;
        elements.followUpDescription.textContent = !elements.followUpEnabled.checked
            ? "不创建任务" : elements.followUp.value
                ? `任务完成 ${elements.followUpDelay.value} 秒后创建` : "请选择后续任务";
        elements.collectHeader.closest(".auto-config-section").hidden = classification;
        elements.classifyHeader.closest(".auto-config-section").hidden = pickup;
        elements.warehouseEnabled.closest(".auto-config-section").hidden = pickup;
        elements.cacheMode.closest(".move-field").hidden = pickup;
        for (const header of [elements.collectHeader, elements.classifyHeader]) {
            header.closest(".auto-phase-header").hidden = Boolean(kind);
        }
        elements.pickupHeader.closest(".auto-phase-header").hidden = classification;
        elements.collectEnabled.closest("label").hidden = Boolean(kind);
        elements.packingHeader.hidden = false;
        for (const id of ["combined-packing-help", "combined-packing-mode-help",
            "combined-classification-help"]) {
            document.querySelector("#" + id).hidden = Boolean(kind);
        }
        document.querySelector("#pickup-packing-help").hidden = !pickup;
        elements.draftToggle.disabled = disabled;
        const isNew = state.active && !state.draft.id;
        elements.draft.hidden = !isNew;
        void window.StowlingSettings.reveal(elements.draftBody, isNew && state.expanded);
        elements.form.hidden = false;
        elements.draftToggle.setAttribute("aria-expanded", String(isNew && state.expanded));
        elements.draftToggle.setAttribute("aria-label",
            `${state.expanded ? "收起" : "展开"}${elements.draftTitle.textContent}设置`);
        elements.remove.hidden = !state.draft.id;
        for (const [id, row] of rows) {
            const selected = state.draft.id === id;
            const expanded = selected && state.expanded;
            const item = state.items.find((value) => value.id === id);
            const pending = state.pendingSwitch?.id === id;
            const classification = item.kind === "CLASSIFICATION";
            const active = classification ? state.classificationRuns.some(run => run.taskId === id)
                : Boolean(item.enabled);
            row.button.disabled = disabled;
            row.listening.checked = pending ? state.pendingSwitch.enabled : active;
            row.listening.disabled = disabled || (!active && selected && state.dirty);
            row.button.setAttribute("aria-expanded", String(expanded));
            row.button.setAttribute("aria-label", `${expanded ? "收起" : "展开"}${item.name}设置`);
            if (row.listening) {
                const action = row.listening.checked ? "关闭" : "开始";
                row.listening.setAttribute("aria-label", `${action}任务：${item.name}`);
                row.listening.title = !active && selected && state.dirty
                    ? "修改保存后可开始任务" : `${action}任务`;
            }
            void window.StowlingSettings.reveal(row.body, expanded);
            row.status.textContent = pending ? "正在切换任务状态"
                : active ? classification ? "排队或运行中" : "监听中"
                    : selected && state.dirty ? "未保存修改" : "已保存";
        }
        const phaseStates = [
            ["collection", elements.collectHeader, elements.collectBody],
            ["pickup", elements.pickupHeader, elements.pickupBody],
            ["packing", elements.packingHeader, elements.packingBody],
            ["classification", elements.classifyHeader, elements.classifyBody],
            ["followUp", elements.followUpHeader, elements.followUpBody],
            ["warehouse", elements.warehouseHeader, elements.warehouseBody]
        ];
        for (const [phase, header, body] of phaseStates) {
            if (!header || !body) {
                continue;
            }
            const enabled = phaseEnabled(phase);
            header.disabled = disabled || !enabled;
            header.title = enabled ? "展开或收起子任务设置" : "";
            const alwaysOpen = pickup && phase === "collection"
                || classification && phase === "classification";
            const expanded = enabled && (alwaysOpen || state.phase[phase]);
            header.setAttribute("aria-expanded", String(expanded));
            const labels = {collection: "缓存", pickup: "掉落物拾取点", packing: "打包缓存",
                classification: "后台分类", followUp: "任务结束后创建任务", warehouse: "机器人仓库"};
            header.setAttribute("aria-label",
                `${expanded ? "收起" : "展开"}${labels[phase]}设置`);
            void window.StowlingSettings.reveal(body, expanded);
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
        elements.followUpDelay.disabled = !elements.followUpEnabled.checked
            || !elements.followUp.value;
        const showBatching = !pickup && window.StowlingTaskDebugMode?.enabled() === true;
        elements.batchingRow.hidden = !showBatching;
        elements.batchingRow.inert = !showBatching;
        elements.batching.disabled = disabled || !showBatching
            || !elements.classifyEnabled.checked || elements.baseline.checked;
        const baselineEnabled = state.draft.kind === "CLASSIFICATION"
            && elements.baseline.checked;
        const showBaseline = state.draft.kind === "CLASSIFICATION"
            && window.StowlingTaskDebugMode?.enabled() === true;
        elements.baselineRow.hidden = !showBaseline;
        elements.baselineRow.inert = !showBaseline;
        elements.baseline.disabled = disabled || !showBaseline;
        elements.baselineStatus.hidden = !baselineEnabled;
        elements.baselineStatus.textContent = baselineEnabled
            ? "已开启 classification-multi-source-fixed-order；同类多盒合批的保存值保留，"
                + "本轮使用多物品共享批次。" : "";
        selectView?.refresh(true);
    }

    /**
     * @param {object | null} [item]
     * @param {string} [kind]
     * @returns {void}
     */
    function selectTask(item = null, kind = "PICKUP") {
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
        autosave.cancel();
        state.original = item;
        state.draftRevision = state.revision;
        state.draft = item ? Auto.draftFromTask(item)
            : Auto.newDraft(kind);
        state.active = !item;
        state.expanded = true;
        state.dirty = false;
        state.phase.collection = false;
        state.phase.pickup = false;
        state.phase.packing = false;
        state.phase.classification = false;
        state.phase.followUp = false;
        state.phase.warehouse = false;
        syncForm();
        renderList();
        showMessage(item ? "已保存" : "配置完整后自动保存");
    }

    /**
     * @param {string} phase
     * @returns {boolean}
     */
    function phaseEnabled(phase) {
        if (phase === "pickup") return elements.collectEnabled.checked;
        if (phase === "packing") return elements.cacheEnabled.checked;
        if (phase === "classification") return elements.classifyEnabled.checked;
        if (phase === "followUp") return elements.followUpEnabled.checked;
        if (phase === "warehouse") return elements.warehouseEnabled.checked;
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
        state.phase[phase] = !state.phase[phase];
        updateControls();
    }

    /**
     * @param {string} id
     * @param {boolean} enabled
     * @returns {Promise<void>}
     */
    async function setListening(id, enabled) {
        const row = rows.get(id);
        if (!row || row.listening.disabled || unavailable()) {
            updateControls();
            return;
        }
        const previousRevision = state.revision;
        const classification = state.items.find(item => item.id === id)?.kind === "CLASSIFICATION";
        const retainFocus = document.activeElement === row.listening;
        state.pendingSwitch = {id, enabled};
        state.busy = true;
        row.listening.setAttribute("aria-busy", "true");
        updateControls();
        try {
            const operation = enabled ? "start" : "stop";
            const response = await Stowling.request(
                `${endpoint}/${encodeURIComponent(id)}/${operation}`, {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ revision: previousRevision })
                }
            );
            if (response.item?.id !== id) {
                throw new Error("任务状态未返回，请刷新重试");
            }
            state.revision = String(response.revision || "");
            state.items = state.items.map((task) => task.id === id ? response.item : task);
            if (state.draft.id === id) {
                state.draft.enabled = response.item.enabled;
                state.original = response.item;
            }
            if (state.draftRevision === previousRevision) {
                state.draftRevision = state.revision;
            }
            document.dispatchEvent(new CustomEvent("stowling:task-switch-changed", {
                detail: {previousRevision, revision: state.revision}
            }));
            if (classification) {
                state.classificationRuns = state.classificationRuns.filter(run => run.taskId !== id);
                if (enabled) state.classificationRuns.push({taskId: id, state: "QUEUED"});
            }
            Stowling.showToast(classification
                ? enabled ? "分类任务已提交" : "已请求关闭分类任务"
                : response.item.enabled ? "已开始监听" : "已停止监听，已排队任务会继续执行");
        } catch (error) {
            Stowling.handleError(error, "切换任务运行状态失败");
        } finally {
            state.busy = false;
            state.pendingSwitch = null;
            row.listening.removeAttribute("aria-busy");
            updateControls();
            document.dispatchEvent(new Event("stowling:monitor-refresh"));
            if (retainFocus && document.activeElement === document.body) {
                row.listening.focus({preventScroll: true});
            }
        }
    }

    /** @param {Array<object>} pending @returns {void} */
    function syncClassificationRuns(pending) {
        state.classificationRuns = pending.filter(run => ["QUEUED", "RUNNING", "WAITING",
            "INTERRUPTING", "INTERRUPTION_FAILED"].includes(run.state || "QUEUED"));
        updateControls();
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
        state.draftRevision = state.revision;
        state.original = item;
        const index = state.items.findIndex((value) => value.id === item.id);
        if (index < 0) {
            state.items.push(item);
        } else {
            state.items[index] = item;
        }
        state.draft = Auto.draftFromTask(item);
        state.dirty = false;
        state.active = false;
        renderList();
    }

    /** @returns {Promise<void>} */
    async function saveTask() {
        if (unavailable() || !state.dirty) {
            return;
        }
        readForm();
        let payload;
        try {
            payload = Auto.payload(state.draft, state.resources);
            delete payload.enabled;
        } catch (error) {
            showMessage(String(error.message || error), true);
            return;
        }
        state.busy = true;
        updateControls();
        try {
            showMessage("正在自动保存…");
            const id = state.draft.id;
            const response = id
                ? await Autosave.write(`${endpoint}/${encodeURIComponent(id)}`, "PUT",
                    {...payload, revision: state.draftRevision},
                    () => Autosave.latestRevision(endpoint, state.original,
                        (current) => current.items.find((item) => item.id === id)))
                : await Autosave.write(endpoint, "POST", payload);
            applySave(response);
            showMessage("已自动保存");
            document.dispatchEvent(new Event("stowling:auto-tasks-changed"));
            document.dispatchEvent(new Event("stowling:tasks-changed"));
        } catch (error) {
            showMessage(String(error.message || "保存失败，请刷新重试。"), true);
            Stowling.handleError(error, "保存自动分类失败");
        } finally {
            state.busy = false;
            updateControls();
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
            state.draft = Auto.newDraft();
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

    elements.newTask.addEventListener("click", () => selectTask(null, "PICKUP"));
    elements.newClassification.addEventListener("click", () => selectTask(null, "CLASSIFICATION"));
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
    elements.followUpHeader.addEventListener("click", () => togglePhase("followUp"));
    elements.warehouseHeader.addEventListener("click", () => togglePhase("warehouse"));
    elements.followUpBody.addEventListener("invalid", () => {
        state.phase.followUp = true;
        updateControls();
    }, true);
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
    elements.refresh.addEventListener("click", () => void loadResources());
    elements.form.addEventListener("input", (event) => {
        if (event.target === elements.point) syncRadiusHint();
        if (!state.draft.kind && event.target === elements.classifyEnabled) {
            state.phase.classification = elements.classifyEnabled.checked;
            if (elements.classifyEnabled.checked) {
                elements.collectEnabled.checked = true;
            }
        }
        if (!state.draft.kind && event.target === elements.collectEnabled) {
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
        if (event.target === elements.followUpEnabled) {
            state.phase.followUp = false;
        }
        if (event.target === elements.warehouseEnabled) {
            state.phase.warehouse = false;
        }
        readForm();
        if (event.target === elements.targets) renderClassificationOrder();
        state.dirty = true;
        showMessage("未保存修改");
        updateControls();
        autosave.schedule();
    });
    elements.form.addEventListener("submit", (event) => {
        event.preventDefault();
        void autosave.flush();
    });
    elements.remove.addEventListener("click", () => void deleteTask());
    document.addEventListener("stowling:task-switch-changed", ({detail}) => {
        if (state.revision === detail.previousRevision) state.revision = detail.revision;
        if (state.draftRevision === detail.previousRevision) {
            state.draftRevision = detail.revision;
        }
    });
    document.addEventListener("stowling:tasks-changed", () => void loadResources());
    document.addEventListener("stowling:refresh-task-status", ({detail}) => {
        syncClassificationRuns(detail.pending || []);
    });
    document.addEventListener("stowling:task-debug-mode-changed", updateControls);
    Stowling.onConnected(loadResources);
    updateControls();
    window.setInterval(updateControls, 3000);
})();
