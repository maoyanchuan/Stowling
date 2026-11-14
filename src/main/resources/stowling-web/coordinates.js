(() => {
    "use strict";

    const Stowling = window.Stowling;
    const DIMENSIONS = Object.freeze([
        "minecraft:overworld",
        "minecraft:the_nether",
        "minecraft:the_end"
    ]);

    const elements = {
        form: document.querySelector("#coordinate-form"),
        list: document.querySelector("#coordinate-list"),
        add: document.querySelector("#add-coordinate-button"),
        discard: document.querySelector("#discard-coordinate-button"),
        save: document.querySelector("#save-coordinate-button"),
        errors: document.querySelector("#coordinate-errors"),
        status: document.querySelector("#coordinate-status"),
        revision: document.querySelector("#coordinate-revision"),
        usage: document.querySelector("#coordinate-usage"),
        referenceStatus: document.querySelector("#coordinate-reference-status"),
        reloadReferences: document.querySelector("#reload-coordinate-references-button")
    };

    const state = {
        snapshot: null,
        draft: null,
        dirty: false,
        writing: false,
        invalidFields: new Set(),
        references: new Map(),
        referenceSource: null,
        referencesReady: false,
        referencesLoading: false,
        cleanupIds: new Set()
    };

    /**
     * @param {object} snapshot
     * @returns {void}
     */
    function renderSnapshot(snapshot) {
        const points = Array.isArray(snapshot?.coordinatePoints)
            ? snapshot.coordinatePoints
            : [];
        state.snapshot = Stowling.clone({
            revision: String(snapshot?.revision || ""),
            coordinatePoints: points
        });
        state.draft = Stowling.clone(state.snapshot);
        for (const point of state.draft.coordinatePoints) {
            point._originalId = String(point.id || "");
        }
        state.dirty = false;
        state.cleanupIds.clear();
        elements.revision.textContent = Stowling.revisionLabel(state.snapshot.revision);
        elements.revision.title = state.snapshot.revision;
        elements.status.textContent = "命名坐标已与配置文件同步";
        showErrors([]);
        renderEditor();
        renderUsage();
    }

    /**
     * @returns {void}
     */
    function renderEditor() {
        elements.list.replaceChildren();
        if (!state.draft) {
            return;
        }
        state.draft.coordinatePoints.forEach((point, index) => {
            elements.list.append(createPointCard(point, index));
        });
        if (state.draft.coordinatePoints.length === 0) {
            elements.list.append(Stowling.emptyState("尚未创建命名坐标"));
        }
        updateSaveState();
    }

    /**
     * @param {object} point
     * @param {number} index
     * @returns {HTMLElement}
     */
    function createPointCard(point, index) {
        const card = Stowling.element("article", "editor-card coordinate-point-editor");
        card.dataset.index = String(index);

        const heading = Stowling.element("div", "editor-card-heading");
        const dragHandle = Stowling.element("button", "drag-handle", "⋮⋮");
        dragHandle.type = "button";
        dragHandle.draggable = true;
        dragHandle.title = "拖动排序";
        dragHandle.setAttribute("aria-label", "拖动排序");
        installDragEvents(card, dragHandle, index);
        const title = Stowling.element(
            "strong",
            "",
            String(point.name || point.id || "未命名坐标")
        );
        const titleRow = Stowling.element("div", "editor-card-title");
        titleRow.append(dragHandle, title);
        const actions = Stowling.element("div", "editor-card-actions");
        actions.append(
            moveButton("↑", index, -1),
            moveButton("↓", index, 1),
            removeButton(index)
        );
        heading.append(titleRow, actions);

        const fields = Stowling.element("div", "editor-fields");
        const idInput = textInput(
            `coordinate-id-${index}`,
            "坐标 ID",
            String(point.id || ""),
            (value) => {
                point.id = value;
                markDirty();
            }
        );
        idInput.pattern = "[a-z0-9][a-z0-9._-]{0,63}";
        const idField = field("坐标 ID", idInput);
        const nameInput = textInput(
            `coordinate-name-${index}`,
            "显示名称",
            String(point.name || ""),
            (value) => {
                point.name = value;
                title.textContent = value || point.id || "未命名坐标";
                markDirty();
            }
        );
        nameInput.maxLength = 80;
        fields.append(
            idField,
            field("显示名称", nameInput),
            field("维度", dimensionSelect(point, index))
        );

        const position = Stowling.element("fieldset", "position-editor");
        position.append(Stowling.element("legend", "", "方块坐标"));
        for (const axis of ["x", "y", "z"]) {
            const input = numberInput(
                `coordinate-${axis}-${index}`,
                axis.toUpperCase(),
                point.position?.[axis],
                (value) => {
                    point.position ||= { x: 0, y: 64, z: 0 };
                    point.position[axis] = value;
                    markDirty();
                }
            );
            input.dataset.validation = "position";
            position.append(field(axis.toUpperCase(), input));
        }

        card.append(heading, fields, position);
        return card;
    }

    /**
     * @param {string} label
     * @param {HTMLElement} input
     * @returns {HTMLElement}
     */
    function field(label, input) {
        const container = Stowling.element("div", "editor-field");
        const caption = Stowling.element("label", "", label);
        const inputId = input.querySelector?.("input, select")?.id || input.id;
        if (inputId) {
            caption.htmlFor = inputId;
        }
        container.append(caption, input);
        return container;
    }

    /**
     * @param {string} id
     * @param {string} label
     * @param {string} value
     * @param {(value: string) => void} onChange
     * @returns {HTMLInputElement}
     */
    function textInput(id, label, value, onChange) {
        const input = document.createElement("input");
        input.id = id;
        input.type = "text";
        input.value = value;
        input.autocomplete = "off";
        input.setAttribute("aria-label", label);
        input.addEventListener("input", () => onChange(input.value));
        return input;
    }

    /**
     * @param {string} id
     * @param {string} label
     * @param {unknown} value
     * @param {(value: number | string) => void} onChange
     * @returns {HTMLInputElement}
     */
    function numberInput(id, label, value, onChange) {
        const input = document.createElement("input");
        input.id = id;
        input.type = "number";
        input.step = "1";
        input.min = "-2147483648";
        input.max = "2147483647";
        input.value = String(value ?? 0);
        input.setAttribute("aria-label", label);
        input.addEventListener("input", () => {
            const parsed = parseInteger(input.value);
            onChange(parsed === null ? input.value : parsed);
        });
        return input;
    }

    /**
     * @param {object} point
     * @param {number} index
     * @returns {HTMLSelectElement}
     */
    function dimensionSelect(point, index) {
        const select = document.createElement("select");
        select.id = `coordinate-dimension-${index}`;
        select.setAttribute("aria-label", "维度");
        const values = [...DIMENSIONS];
        if (point.dimension && !values.includes(point.dimension)) {
            values.push(point.dimension);
        }
        for (const dimension of values) {
            const option = document.createElement("option");
            option.value = dimension;
            option.textContent = Stowling.dimensionLabel(dimension);
            option.title = dimension;
            select.append(option);
        }
        select.value = String(point.dimension || DIMENSIONS[0]);
        select.addEventListener("change", () => {
            point.dimension = select.value;
            markDirty();
        });
        return select;
    }

    /**
     * @param {string} text
     * @param {number} index
     * @param {number} offset
     * @returns {HTMLButtonElement}
     */
    function moveButton(text, index, offset) {
        const button = Stowling.element("button", "secondary compact", text);
        button.type = "button";
        button.title = offset < 0 ? "上移" : "下移";
        button.disabled = state.draft.coordinatePoints.length <= 1;
        button.addEventListener("click", () => movePoint(index, offset));
        return button;
    }

    /**
     * @param {number} index
     * @returns {HTMLButtonElement}
     */
    function removeButton(index) {
        const button = Stowling.element("button", "ghost compact danger-action", "删除");
        button.type = "button";
        button.addEventListener("click", () => { void removePoint(index); });
        return button;
    }

    /**
     * @param {HTMLElement} card
     * @param {HTMLButtonElement} dragHandle
     * @param {number} index
     * @returns {void}
     */
    function installDragEvents(card, dragHandle, index) {
        dragHandle.addEventListener("dragstart", (event) => {
            dragHandle.classList.add("dragging");
            event.dataTransfer?.setData("text/plain", String(index));
        });
        dragHandle.addEventListener("dragend", () => {
            dragHandle.classList.remove("dragging");
        });
        card.addEventListener("dragover", (event) => event.preventDefault());
        card.addEventListener("drop", (event) => {
            event.preventDefault();
            if (state.writing) {
                return;
            }
            const source = Number(event.dataTransfer?.getData("text/plain"));
            if (!Number.isInteger(source) || source === index) {
                return;
            }
            const points = state.draft.coordinatePoints;
            const [point] = points.splice(source, 1);
            points.splice(index, 0, point);
            markDirty(true);
        });
    }

    /**
     * @param {number} index
     * @param {number} offset
     * @returns {void}
     */
    function movePoint(index, offset) {
        if (state.writing) {
            return;
        }
        const points = state.draft.coordinatePoints;
        const target = index + offset;
        if (target < 0 || target >= points.length) {
            return;
        }
        const [point] = points.splice(index, 1);
        points.splice(target, 0, point);
        markDirty(true);
    }

    /**
     * @param {number} index
     * @returns {Promise<void>}
     */
    async function removePoint(index) {
        const point = state.draft?.coordinatePoints[index];
        if (!point || state.writing) {
            return;
        }
        const savedId = String(point._originalId || "");
        if (!savedId) {
            state.draft.coordinatePoints.splice(index, 1);
            markDirty(true);
            return;
        }
        if (!state.referencesReady) {
            showErrors(["引用读取未完成或失败，不能删除坐标。请重新读取后再操作。"]);
            return;
        }
        const plan = deletionPlan(new Set([savedId]));
        if (plan.blockers.length > 0) {
            showErrors([`无法删除“${point.name || savedId}”：${plan.blockers.join("；")}。`
                + "请先解除列出的引用并保存。"]);
            return;
        }
        const labels = newCleanupLabels(plan);
        let warning = `删除坐标“${point.name || savedId}”（${savedId}）？`;
        if (labels.length > 0) {
            warning += "\n同时删除以下实际关联项：\n"
                + labels.map((label) => `• ${label}`).join("\n");
        }
        warning += "\n确认后立即保存删除，刷新后不会恢复；取消不作任何更改。"
            + "\n其他未保存的坐标修改不会一并保存。";
        if (!window.confirm(warning)) {
            return;
        }
        const payload = {
            revision: state.snapshot.revision,
            coordinatePoints: state.snapshot.coordinatePoints
                .filter((item) => item.id !== savedId).map(toRequestPoint)
        };
        if (labels.length > 0) {
            payload.removeLegacyReferencesFor = [savedId];
        }
        state.writing = true;
        updateSaveState(false);
        let failure = "";
        try {
            const saved = await Stowling.request("/v1/config/coordinate-points", {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(payload)
            });
            const remainingDraft = state.draft.coordinatePoints
                .filter((item) => item !== point);
            renderSnapshot(saved);
            state.draft.coordinatePoints = remainingDraft;
            state.dirty = JSON.stringify(remainingDraft.map(toRequestPoint))
                !== JSON.stringify(state.snapshot.coordinatePoints.map(toRequestPoint));
            renderEditor();
            renderUsage();
            showErrors([]);
            await refreshSavedReferences();
            Stowling.showToast("坐标已删除并保存");
        } catch (error) {
            failure = "删除未保存，坐标仍保留";
            showErrors([error instanceof Error ? error.message : failure]);
            Stowling.handleError(error, "删除坐标失败");
        } finally {
            state.writing = false;
            updateSaveState(false);
            if (failure) {
                elements.status.textContent = failure;
            }
        }
    }

    /**
     * @returns {Promise<void>}
     */
    async function refreshSavedReferences() {
        const regionRefresh = window.StowlingCoordinateRegions?.refreshAfterCoordinates;
        const results = await Promise.allSettled([
            loadReferences(),
            typeof regionRefresh === "function" ? regionRefresh() : Promise.resolve()
        ]);
        if (results.some((result) => result.status === "rejected")) {
            Stowling.showToast("更改已保存，但关联信息读取失败；请重新读取后再删除", true);
        }
    }

    /**
     * @returns {void}
     */
    function addPoint() {
        if (state.writing) {
            return;
        }
        if (!state.draft) {
            Stowling.showToast("命名坐标尚未读取完成", true);
            return;
        }
        const id = nextPointId();
        state.draft.coordinatePoints.push({
            id,
            name: "新命名坐标",
            dimension: DIMENSIONS[0],
            position: { x: 0, y: 64, z: 0 },
            _originalId: ""
        });
        markDirty(true);
    }

    /**
     * @returns {string}
     */
    function nextPointId() {
        const used = new Set(
            state.draft.coordinatePoints.map((point) => String(point.id || ""))
        );
        let index = 1;
        while (used.has(`point-${index}`)) {
            index += 1;
        }
        return `point-${index}`;
    }

    /**
     * @param {boolean} rerender
     * @returns {void}
     */
    function markDirty(rerender = false) {
        state.dirty = true;
        if (rerender) {
            renderEditor();
            renderUsage();
            return;
        }
        updateSaveState();
    }

    /**
     * @returns {Array<string>}
     */
    function validateDraft() {
        if (!state.draft) {
            return ["尚未读取命名坐标"];
        }
        const errors = [];
        const ids = new Set();
        state.invalidFields.clear();
        state.draft.coordinatePoints.forEach((point, index) => {
            const id = String(point.id || "").trim();
            const ordinal = `第 ${index + 1} 个命名坐标`;
            const name = String(point.name || "").trim() || id || "未命名";
            if (!id) {
                addError(
                    errors,
                    `${ordinal}“${name}”的坐标 ID 不能为空；请输入 1-64 位小写字母、`
                        + "数字、点、下划线或连字符，且必须以字母或数字开头",
                    `coordinate-id-${index}`
                );
            } else if (!/^[a-z0-9][a-z0-9._-]{0,63}$/.test(id)) {
                addError(
                    errors,
                    `${ordinal}“${name}”的坐标 ID“${id}”格式无效；只能使用小写字母、`
                        + "数字、点、下划线和连字符，并以字母或数字开头",
                    `coordinate-id-${index}`
                );
            }
            if (id && ids.has(id)) {
                addError(
                    errors,
                    `${ordinal}“${name}”使用了重复 ID“${id}”；请改成未使用的 ID`,
                    `coordinate-id-${index}`
                );
            }
            if (id) {
                ids.add(id);
            }
            const originalId = String(point._originalId || "");
            if (originalId && id !== originalId) {
                const references = referencesFor(originalId);
                if (references.length > 0) {
                    addError(
                        errors,
                        `${ordinal}“${name}”的 ID 已从“${originalId}”改为`
                            + `“${id || "空"}”，但旧 ID 仍被 ${references.join("、")}`
                            + " 引用；请先解除这些引用，或恢复旧 ID",
                        `coordinate-id-${index}`
                    );
                }
            }
            if (!String(point.name || "").trim()) {
                addError(
                    errors,
                    `${ordinal}（ID：${id || "空"}）的显示名称不能为空；请输入便于识别的名称`,
                    `coordinate-name-${index}`
                );
            }
            if (!String(point.dimension || "").trim()) {
                addError(
                    errors,
                    `${ordinal}“${name}”必须选择维度；请选择主世界、下界或末地`,
                    `coordinate-dimension-${index}`
                );
            }
            for (const axis of ["x", "y", "z"]) {
                if (parseInteger(point.position?.[axis]) === null) {
                    addError(
                        errors,
                        `${ordinal}“${name}”的 ${axis.toUpperCase()} 坐标`
                            + "必须是 32 位整数；请填写有效方块坐标",
                        `coordinate-${axis}-${index}`
                    );
                }
            }
        });
        return [...new Set(errors)];
    }

    /**
     * @param {Array<string>} errors
     * @param {string} message
     * @param {string} fieldId
     * @returns {void}
     */
    function addError(errors, message, fieldId) {
        errors.push(message);
        state.invalidFields.add(fieldId);
    }

    /**
     * @param {boolean} showValidation
     * @returns {void}
     */
    function updateSaveState(showValidation = true) {
        const errors = [...validateDraft(), ...deletionGuardErrors()];
        const blocked = errors.length > 0;
        elements.save.disabled = state.writing || !state.dirty || blocked;
        elements.add.disabled = state.writing;
        elements.discard.disabled = state.writing;
        elements.reloadReferences.disabled = state.writing || state.referencesLoading;
        for (const control of elements.list.querySelectorAll("button, input, select")) {
            control.disabled = state.writing;
        }
        elements.status.classList.toggle("dirty", state.dirty);
        if (showValidation) {
            showErrors(state.dirty || blocked ? errors : []);
        }
        applyValidationStyles();
        if (state.writing) {
            elements.status.textContent = "正在保存，请稍候…";
        } else if (blocked) {
            elements.status.textContent =
                `发现 ${errors.length} 个命名坐标问题，保存已暂停`;
        } else if (state.dirty) {
            elements.status.textContent = "有未保存的命名坐标修改";
        } else {
            elements.status.textContent = "命名坐标已与配置文件同步";
        }
    }

    /**
     * @returns {void}
     */
    function applyValidationStyles() {
        for (const card of elements.list.querySelectorAll(".coordinate-point-editor")) {
            card.classList.remove("has-error");
            for (const control of card.querySelectorAll("input, select")) {
                control.setAttribute("aria-invalid", "false");
                control.closest(".editor-field, fieldset")?.classList.remove(
                    "has-error"
                );
            }
        }
        for (const fieldId of state.invalidFields) {
            const control = document.getElementById(fieldId);
            if (!control) {
                continue;
            }
            control.setAttribute("aria-invalid", "true");
            control.closest(".editor-field, fieldset")?.classList.add("has-error");
            control.closest(".coordinate-point-editor")?.classList.add("has-error");
        }
    }

    /**
     * @param {Array<string>} errors
     * @returns {void}
     */
    function showErrors(errors) {
        elements.errors.replaceChildren();
        if (errors.length === 0) {
            return;
        }
        elements.errors.append(Stowling.element("strong", "", "请修正以下问题："));
        const list = Stowling.element("ul");
        for (const error of errors) {
            list.append(Stowling.element("li", "", error));
        }
        elements.errors.append(list);
    }

    /**
     * @param {unknown} value
     * @returns {number | null}
     */
    function parseInteger(value) {
        const text = String(value ?? "").trim();
        if (!/^-?(0|[1-9][0-9]*)$/.test(text)) {
            return null;
        }
        const result = Number(text);
        if (!Number.isSafeInteger(result)
                || result < -2147483648
                || result > 2147483647) {
            return null;
        }
        return result;
    }

    /**
     * @returns {Promise<void>}
     */
    async function loadCoordinates() {
        elements.status.textContent = "正在读取命名坐标…";
        try {
            const snapshot = await Stowling.request("/v1/config/coordinate-points");
            renderSnapshot(snapshot);
        } catch (error) {
            elements.status.textContent = "命名坐标读取失败";
            throw error;
        }
    }

    /**
     * @returns {Promise<void>}
     */
    async function loadReferences() {
        state.referencesReady = false;
        state.referencesLoading = true;
        renderUsage();
        updateSaveState();
        try {
            const results = await Promise.allSettled([
                Stowling.request("/v1/config/task-resources"),
                Stowling.request("/v1/task-definitions")
            ]);
            const failure = results.find((result) => result.status === "rejected");
            if (failure) {
                throw failure.reason;
            }
            const resources = results[0].value;
            const definitions = results[1].value;
            if (!Array.isArray(resources?.pickupRegions)
                    || !Array.isArray(resources?.cacheContainers)
                    || !Array.isArray(resources?.pickupWorkflows)
                    || !Array.isArray(definitions?.tasks)) {
                throw new Error("引用响应不完整，请重新读取");
            }
            state.referenceSource = Stowling.clone({ resources, definitions });
            state.referencesReady = true;
        } catch (error) {
            state.referencesReady = false;
            throw error;
        } finally {
            state.referencesLoading = false;
            renderUsage();
            updateSaveState();
        }
    }

    /**
     * @returns {Set<string>}
     */
    function deletedCoordinateIds() {
        const retained = new Set(
            (state.draft?.coordinatePoints || []).map((point) => String(point.id || ""))
        );
        return new Set((state.snapshot?.coordinatePoints || [])
            .map((point) => String(point.id || ""))
            .filter((id) => id && !retained.has(id)));
    }

    /**
     * @param {unknown} value
     * @returns {Array<string>}
     */
    function referenceIds(value) {
        if (Array.isArray(value)) {
            return value.flatMap(referenceIds);
        }
        if (typeof value !== "string") {
            return [];
        }
        const text = value.trim();
        if (text.startsWith("[")) {
            try {
                return referenceIds(JSON.parse(text));
            } catch {
                return text.split(/[,;\s]+/).filter(Boolean);
            }
        }
        return text.split(/[,;\s]+/).filter(Boolean);
    }

    /**
     * @param {unknown} source
     * @returns {{coordinates: Set<string>, regions: Set<string>, caches: Set<string>,
     *     workflows: Set<string>}}
     */
    function collectReferences(source) {
        const result = {
            coordinates: new Set(),
            regions: new Set(),
            caches: new Set(),
            workflows: new Set()
        };
        visitReferences(source, result);
        return result;
    }

    /**
     * @param {unknown} source
     * @param {{coordinates: Set<string>, regions: Set<string>, caches: Set<string>,
     *     workflows: Set<string>}} result
     * @returns {void}
     */
    function visitReferences(source, result) {
        if (!source || typeof source !== "object") {
            return;
        }
        if (Array.isArray(source)) {
            source.forEach((entry) => visitReferences(entry, result));
            return;
        }
        for (const [key, value] of Object.entries(source)) {
            let target = null;
            if (key === "coordinateId" || key === "coordinateIds"
                    || key.endsWith("CoordinateId") || key.endsWith("CoordinateIds")) {
                target = result.coordinates;
            } else if (key === "regionId" || key === "regionIds") {
                target = result.regions;
            } else if (["cacheId", "cacheIds", "containerId", "sourceContainerId",
                "destinationContainerId", "destination", "fallbackContainerId",
                "outputContainerId", "boxContainerId"].includes(key)) {
                target = result.caches;
            } else if (["workflowId", "pickupWorkflowId"].includes(key)) {
                target = result.workflows;
            }
            if (target) {
                referenceIds(value).forEach((id) => target.add(id));
            }
            if (typeof value === "string" && /^[\[{]/.test(value.trim())) {
                try {
                    visitReferences(JSON.parse(value), result);
                } catch {
                    // Unparsed reference values remain subject to server-side protection.
                }
            } else {
                visitReferences(value, result);
            }
        }
    }

    /**
     * @param {Set<string>} values
     * @param {Set<string>} selected
     * @returns {boolean}
     */
    function intersects(values, selected) {
        return [...values].some((id) => selected.has(id));
    }

    /**
     * @returns {Array<object>}
     */
    function legacyPickupWorkflows() {
        const { resources, definitions } = state.referenceSource;
        const workflows = new Map();
        const entries = [
            ...resources.pickupWorkflows,
            ...definitions.tasks.filter((task) => task.kind === "PICKUP")
        ];
        for (const entry of entries) {
            const id = String(entry.id || entry.pickup?.id || "");
            const workflow = workflows.get(id) || {
                id,
                name: entry.name || entry.pickup?.name || id,
                sources: []
            };
            workflow.sources.push(entry);
            workflows.set(id, workflow);
        }
        return [...workflows.values()];
    }

    /**
     * @param {Set<string>} coordinateIds
     * @returns {object}
     */
    function deletionPlan(coordinateIds) {
        const plan = {
            coordinateIds,
            regions: [],
            caches: [],
            workflows: [],
            regionIds: new Set(),
            cacheIds: new Set(),
            workflowIds: new Set(),
            blockers: []
        };
        if (!state.referenceSource) {
            return plan;
        }
        const { resources, definitions } = state.referenceSource;
        plan.regions = resources.pickupRegions.filter((region) => {
            return intersects(collectReferences(region).coordinates, coordinateIds);
        });
        plan.caches = resources.cacheContainers.filter((cache) => {
            return intersects(collectReferences(cache).coordinates, coordinateIds);
        });
        plan.regionIds = new Set(plan.regions.map((region) => String(region.id)));
        plan.cacheIds = new Set(plan.caches.map((cache) => String(cache.id)));
        const legacy = legacyPickupWorkflows();
        plan.workflows = legacy.filter((workflow) => workflow.sources.some((source) => {
            const body = source.pickup || source;
            const regionIds = new Set((body.regions || [])
                .map((binding) => String(binding.regionId || "")));
            const cacheIds = new Set(referenceIds(body.cacheIds));
            return intersects(regionIds, plan.regionIds)
                || intersects(cacheIds, plan.cacheIds);
        }));
        plan.workflowIds = new Set(plan.workflows.map((workflow) => workflow.id));
        const tasks = [
            ...definitions.tasks,
            ...(resources.automationWorkflows || [])
        ];
        const labels = new Set();
        for (const task of tasks) {
            if (task.kind === "PICKUP") {
                continue;
            }
            taskReferenceLabels(task, plan).forEach((label) => labels.add(label));
        }
        for (const workflow of legacy) {
            if (plan.workflowIds.has(workflow.id)) {
                continue;
            }
            for (const source of workflow.sources) {
                taskReferenceLabels({
                    ...source,
                    kind: "PICKUP",
                    id: workflow.id,
                    name: workflow.name
                }, plan).forEach((label) => labels.add(label));
            }
        }
        plan.blockers = [...labels];
        return plan;
    }

    /**
     * @param {unknown} source
     * @param {object} plan
     * @returns {boolean}
     */
    function referencesDeletion(source, plan) {
        const refs = collectReferences(source);
        return intersects(refs.coordinates, plan.coordinateIds)
            || intersects(refs.regions, plan.regionIds)
            || intersects(refs.caches, plan.cacheIds)
            || intersects(refs.workflows, plan.workflowIds);
    }

    /**
     * @param {object} task
     * @param {object} plan
     * @returns {Array<string>}
     */
    function taskReferenceLabels(task, plan) {
        if (!referencesDeletion(task, plan)) {
            return [];
        }
        const kind = task.kind === "PICKUP" ? "旧 PICKUP 任务" : "任务";
        const label = `${kind}“${task.name || task.id}”（${task.id}）`;
        const labels = new Set();
        for (const step of task.steps || []) {
            if (referencesDeletion(step, plan)) {
                labels.add(`${label}步骤 ${step.id || "未命名"} · ${step.type || "模块"}`);
            }
        }
        for (const step of task.settings?.interruption?.steps || []) {
            if (referencesDeletion(step, plan)) {
                labels.add(`${label}插入步骤 ${step.id || "未命名"} · ${step.type || "模块"}`);
            }
        }
        if (referencesDeletion(task.settings?.trigger, plan)
                || referencesDeletion(task.trigger, plan)) {
            labels.add(`${label}触发器`);
        }
        if (labels.size === 0) {
            labels.add(`${label}内嵌或其他配置`);
        }
        return [...labels];
    }

    /**
     * @param {object} plan
     * @param {object | null} previous
     * @returns {Array<string>}
     */
    function newCleanupLabels(plan, previous = null) {
        const labels = new Set();
        for (const region of plan.regions) {
            if (!previous?.regionIds.has(String(region.id))) {
                labels.add(`关联区域“${region.name || region.id}”（${region.id}）`);
            }
        }
        for (const cache of plan.caches) {
            if (!previous?.cacheIds.has(String(cache.id))) {
                labels.add(`旧缓存“${cache.name || cache.id}”（${cache.id}）`);
            }
        }
        for (const workflow of plan.workflows) {
            if (!previous?.workflowIds.has(workflow.id)) {
                labels.add(`旧 PICKUP 任务“${workflow.name}”（${workflow.id}）`);
            }
        }
        return [...labels];
    }

    /**
     * @returns {Array<string>}
     */
    function deletionGuardErrors() {
        const ids = deletedCoordinateIds();
        if (ids.size === 0) {
            return [];
        }
        if (!state.referencesReady) {
            return ["引用未可靠读取，删除草稿暂不能保存。请重新读取（将放弃草稿）。"];
        }
        const plan = deletionPlan(ids);
        if (plan.blockers.length > 0) {
            return ["实际任务仍在引用，请先到任务页解除引用并保存："
                + plan.blockers.join("；")];
        }
        const confirmed = deletionPlan(new Set(cleanupCoordinateIds()));
        if (newCleanupLabels(plan, confirmed).length > 0) {
            return ["关联资源清理尚未全部确认，请放弃草稿并重新读取后再次删除。"];
        }
        return [];
    }

    /**
     * @returns {Array<string>}
     */
    function cleanupCoordinateIds() {
        const deleted = deletedCoordinateIds();
        return [...state.cleanupIds].filter((id) => deleted.has(id));
    }

    /**
     * @param {string} coordinateId
     * @returns {Array<string>}
     */
    function referencesFor(coordinateId) {
        return [...(state.references.get(String(coordinateId || "")) || [])];
    }

    /**
     * @returns {void}
     */
    function renderUsage() {
        elements.usage.replaceChildren();
        elements.referenceStatus.textContent = state.referencesReady
            ? "引用已读取；删除已保存的坐标会立即提交，并再次校验实际引用。"
            : state.referencesLoading
                ? "正在读取资源与任务引用，读取完成前不能删除坐标…"
                : "引用读取失败或未完成，不能删除坐标。请重新读取（将放弃草稿）。";
        elements.reloadReferences.hidden = state.referencesReady;
        elements.reloadReferences.disabled = state.referencesLoading;
        if (!state.referencesReady) {
            elements.usage.append(Stowling.emptyState("引用尚未可靠读取，删除已暂停"));
            return;
        }
        state.references = new Map();
        for (const point of state.snapshot?.coordinatePoints || []) {
            const plan = deletionPlan(new Set([String(point.id)]));
            state.references.set(String(point.id), [
                ...newCleanupLabels(plan),
                ...plan.blockers
            ]);
        }
        if (!state.draft || state.references.size === 0) {
            elements.usage.append(Stowling.emptyState("当前没有已保存的引用"));
            return;
        }
        for (const point of state.draft.coordinatePoints) {
            const references = [...new Set([
                ...referencesFor(point.id),
                ...referencesFor(point._originalId)
            ])];
            if (references.length === 0) {
                continue;
            }
            const card = Stowling.element("article", "usage-card");
            card.append(
                Stowling.element(
                    "strong",
                    "",
                    `${point.name || point.id} · ${point.id || "ID 为空"}`
                ),
                Stowling.element(
                    "span",
                    "",
                    [...new Set(references)].join("；")
                )
            );
            elements.usage.append(card);
        }
        if (!elements.usage.hasChildNodes()) {
            elements.usage.append(Stowling.emptyState("当前没有已保存的引用"));
        }
    }

    /**
     * @returns {Promise<void>}
     */
    async function saveCoordinates() {
        if (state.writing) {
            return;
        }
        const errors = [...validateDraft(), ...deletionGuardErrors()];
        if (errors.length > 0) {
            showErrors(errors);
            Stowling.showToast("命名坐标仍有错误，暂未保存", true);
            return;
        }
        if (!state.snapshot || !state.dirty) {
            return;
        }
        state.writing = true;
        updateSaveState(false);
        let failure = "";
        try {
            const payload = {
                revision: state.snapshot.revision,
                coordinatePoints: state.draft.coordinatePoints.map(toRequestPoint)
            };
            const cleanupIds = cleanupCoordinateIds();
            if (cleanupIds.length > 0) {
                payload.removeLegacyReferencesFor = cleanupIds;
            }
            const saved = await Stowling.request("/v1/config/coordinate-points", {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(payload)
            });
            renderSnapshot(saved);
            await refreshSavedReferences();
            Stowling.showToast("命名坐标已保存，旧配置已生成备份");
        } catch (error) {
            const conflict = error instanceof Stowling.ApiError && error.status === 409;
            const activity = error?.code === "active_task_conflict";
            let message = error instanceof Error ? error.message : "保存命名坐标失败";
            if (conflict) {
                message = activity
                    ? `活动或暂停任务存在：${message}`
                    : `配置已被外部修改：${message}`;
            }
            showErrors([message]);
            failure = conflict
                ? "配置版本冲突或任务正在运行，草稿已保留"
                : "命名坐标保存失败，草稿仍在";
            Stowling.handleError(error, "保存命名坐标失败");
        } finally {
            state.writing = false;
            updateSaveState(false);
            if (failure) {
                elements.status.textContent = failure;
            }
        }
    }

    /**
     * @param {object} point
     * @returns {object}
     */
    function toRequestPoint(point) {
        return {
            id: String(point.id || "").trim(),
            name: String(point.name || "").trim(),
            dimension: String(point.dimension || "").trim(),
            position: {
                x: Number(point.position?.x),
                y: Number(point.position?.y),
                z: Number(point.position?.z)
            }
        };
    }

    /**
     * @returns {Promise<void>}
     */
    async function discardChanges() {
        if (state.writing) {
            return;
        }
        elements.discard.disabled = true;
        state.cleanupIds.clear();
        if (state.snapshot) {
            renderSnapshot(state.snapshot);
        }
        try {
            await Promise.all([loadCoordinates(), loadReferences()]);
            Stowling.showToast("已放弃草稿并重新读取命名坐标");
        } catch (error) {
            Stowling.handleError(error, "重新读取命名坐标失败");
        } finally {
            elements.discard.disabled = false;
        }
    }

    elements.add.addEventListener("click", addPoint);
    elements.discard.addEventListener("click", () => {
        void discardChanges();
    });
    elements.reloadReferences.addEventListener("click", () => {
        void discardChanges();
    });
    elements.form.addEventListener("submit", (event) => {
        event.preventDefault();
        void saveCoordinates();
    });
    document.addEventListener("stowling:coordinate-regions-changed", () => {
        void loadReferences().catch((error) => {
            Stowling.handleError(error, "重新读取引用失败，删除已暂停");
        });
    });

    Stowling.onConnected(async () => {
        const results = await Promise.allSettled([loadCoordinates(), loadReferences()]);
        const failure = results.find((result) => result.status === "rejected");
        if (failure) {
            Stowling.handleError(failure.reason, "读取命名坐标页面失败");
        }
    });
    Stowling.mountPage("coordinates");
})();
