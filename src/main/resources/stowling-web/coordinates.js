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
        usage: document.querySelector("#coordinate-usage")
    };

    const state = {
        snapshot: null,
        draft: null,
        dirty: false,
        invalidFields: new Set(),
        references: new Map()
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
        button.addEventListener("click", () => removePoint(index));
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
     * @returns {void}
     */
    function removePoint(index) {
        const point = state.draft.coordinatePoints[index];
        const references = [
            ...referencesFor(point.id),
            ...referencesFor(point._originalId)
        ];
        if (references.length > 0) {
            const message = `无法删除“${point.name || point.id}”：仍被 ${references.join("、")}`
                + " 引用。请先在对应页面解除引用并保存。";
            showErrors([message]);
            Stowling.showToast("命名坐标仍被引用，暂未删除", true);
            return;
        }
        state.draft.coordinatePoints.splice(index, 1);
        markDirty(true);
    }

    /**
     * @returns {void}
     */
    function addPoint() {
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
        const errors = validateDraft();
        const blocked = errors.length > 0;
        elements.save.disabled = !state.dirty || blocked;
        elements.status.classList.toggle("dirty", state.dirty);
        if (showValidation) {
            showErrors(state.dirty || blocked ? errors : []);
        }
        applyValidationStyles();
        if (blocked) {
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
        const results = await Promise.allSettled([
            Stowling.request("/v1/config/pickup"),
            Stowling.request("/v1/config/automation-workflows")
        ]);
        state.references = new Map();
        if (results[0].status === "fulfilled") {
            addPickupReferences(results[0].value);
        }
        if (results[1].status === "fulfilled") {
            addAutomationReferences(results[1].value);
        }
        renderUsage();
    }

    /**
     * @param {object} source
     * @returns {void}
     */
    function addPickupReferences(source) {
        for (const region of source?.pickupRegions || []) {
            addReference(region.standbyCoordinateId, `拾取区域“${region.name || region.id}”待机点`);
            addReference(
                region.shape?.centerCoordinateId,
                `拾取区域“${region.name || region.id}”球心`
            );
            addReference(
                region.shape?.firstCornerCoordinateId,
                `拾取区域“${region.name || region.id}”第一角点`
            );
            addReference(
                region.shape?.secondCornerCoordinateId,
                `拾取区域“${region.name || region.id}”第二角点`
            );
        }
        for (const cache of source?.cacheContainers || []) {
            addReference(cache.coordinateId, `缓存容器“${cache.name || cache.id}”`);
        }
    }

    /**
     * @param {object} source
     * @returns {void}
     */
    function addAutomationReferences(source) {
        for (const workflow of source?.automationWorkflows || []) {
            for (const step of workflow.steps || []) {
                for (const [key, value] of Object.entries(step.parameters || {})) {
                    if (key.endsWith("CoordinateId") && typeof value === "string") {
                        addReference(
                            value,
                            `任务“${workflow.name || workflow.id}”步骤 ${step.id}`
                        );
                    }
                }
            }
        }
    }

    /**
     * @param {unknown} coordinateId
     * @param {string} label
     * @returns {void}
     */
    function addReference(coordinateId, label) {
        const id = String(coordinateId || "");
        if (!id) {
            return;
        }
        const values = state.references.get(id) || [];
        if (!values.includes(label)) {
            values.push(label);
            state.references.set(id, values);
        }
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
        if (!state.draft || state.references.size === 0) {
            elements.usage.append(Stowling.emptyState("当前没有已保存的引用"));
            return;
        }
        for (const point of state.draft.coordinatePoints) {
            const references = [
                ...referencesFor(point.id),
                ...referencesFor(point._originalId)
            ];
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
        const errors = validateDraft();
        if (errors.length > 0) {
            showErrors(errors);
            Stowling.showToast("命名坐标仍有错误，暂未保存", true);
            return;
        }
        if (!state.snapshot || !state.dirty) {
            return;
        }
        elements.save.disabled = true;
        elements.discard.disabled = true;
        elements.status.textContent = "正在保存命名坐标…";
        try {
            const saved = await Stowling.request("/v1/config/coordinate-points", {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    revision: state.snapshot.revision,
                    coordinatePoints: state.draft.coordinatePoints.map(toRequestPoint)
                })
            });
            renderSnapshot(saved);
            await Promise.allSettled([loadCoordinates(), loadReferences()]);
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
            elements.status.textContent = conflict
                ? "配置版本冲突或任务正在运行，草稿已保留"
                : "命名坐标保存失败，草稿仍在";
            Stowling.handleError(error, "保存命名坐标失败");
        } finally {
            elements.discard.disabled = false;
            updateSaveState(false);
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
        elements.discard.disabled = true;
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
    elements.form.addEventListener("submit", (event) => {
        event.preventDefault();
        void saveCoordinates();
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
