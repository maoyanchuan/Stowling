(() => {
    "use strict";

    const Stowling = window.Stowling;
    const elements = {
        form: document.querySelector("#coordinate-region-form"),
        list: document.querySelector("#coordinate-region-list"),
        add: document.querySelector("#add-coordinate-region-button"),
        discard: document.querySelector("#discard-coordinate-region-button"),
        save: document.querySelector("#save-coordinate-region-button"),
        errors: document.querySelector("#coordinate-region-errors"),
        status: document.querySelector("#coordinate-region-status"),
        revision: document.querySelector("#coordinate-region-revision")
    };
    const state = {
        coordinateSnapshot: {
            revision: "",
            coordinatePoints: []
        },
        resourceSnapshot: {
            revision: "",
            pickupRegions: [],
            cacheContainers: [],
            pickupWorkflows: []
        },
        draft: [],
        dirty: false,
        invalidFields: new Set(),
        taskReferences: new Map(),
        legacyReferences: new Map()
    };

    /**
     * @param {object} snapshot
     * @returns {object}
     */
    function normalizeCoordinateSnapshot(snapshot) {
        return {
            revision: String(snapshot?.revision || ""),
            coordinatePoints: Array.isArray(snapshot?.coordinatePoints)
                ? Stowling.clone(snapshot.coordinatePoints)
                : []
        };
    }

    /**
     * @param {object} snapshot
     * @returns {object}
     */
    function normalizeResourceSnapshot(snapshot) {
        return {
            revision: String(snapshot?.revision || ""),
            pickupRegions: Array.isArray(snapshot?.pickupRegions)
                ? Stowling.clone(snapshot.pickupRegions)
                : [],
            cacheContainers: Array.isArray(snapshot?.cacheContainers)
                ? Stowling.clone(snapshot.cacheContainers)
                : [],
            pickupWorkflows: Array.isArray(snapshot?.pickupWorkflows)
                ? Stowling.clone(snapshot.pickupWorkflows)
                : []
        };
    }

    /**
     * @param {object} snapshot
     * @returns {void}
     */
    function applyCoordinateSnapshot(snapshot) {
        state.coordinateSnapshot = normalizeCoordinateSnapshot(snapshot);
    }

    /**
     * @param {object} snapshot
     * @param {boolean} preserveDraft
     * @returns {void}
     */
    function applyResourceSnapshot(snapshot, preserveDraft = false) {
        state.resourceSnapshot = normalizeResourceSnapshot(snapshot);
        rebuildLegacyReferences();
        if (!preserveDraft) {
            state.draft = Stowling.clone(state.resourceSnapshot.pickupRegions);
            for (const region of state.draft) {
                region._originalId = String(region.id || "");
            }
            state.dirty = false;
        }
    }

    /**
     * @returns {Promise<void>}
     */
    async function load() {
        elements.status.textContent = "正在读取命名坐标与拾取区域…";
        const [coordinateSnapshot, resourceSnapshot] = await Promise.all([
            Stowling.request("/v1/config/coordinate-points"),
            Stowling.request("/v1/config/task-resources")
        ]);
        applyCoordinateSnapshot(coordinateSnapshot);
        applyResourceSnapshot(resourceSnapshot);
        await refreshTaskReferences();
        elements.revision.textContent = Stowling.revisionLabel(
            state.resourceSnapshot.revision
        );
        elements.revision.title = state.resourceSnapshot.revision;
        elements.status.textContent = "拾取区域已与配置文件同步";
        showErrors([]);
        renderEditor();
    }

    /**
     * @returns {void}
     */
    function renderEditor() {
        elements.list.replaceChildren();
        state.draft.forEach((region, index) => {
            elements.list.append(createRegionCard(region, index));
        });
        if (state.draft.length === 0) {
            elements.list.append(Stowling.emptyState("尚未创建拾取区域"));
        }
        updateState();
    }

    /**
     * @param {object} region
     * @param {number} index
     * @returns {HTMLElement}
     */
    function createRegionCard(region, index) {
        const card = Stowling.element("article", "editor-card coordinate-region-editor");
        card.dataset.index = String(index);
        const heading = Stowling.element("div", "editor-card-heading");
        const titleRow = Stowling.element("div", "editor-card-title");
        titleRow.append(Stowling.element(
            "strong",
            "",
            String(region.name || region.id || "未命名区域")
        ));
        const remove = Stowling.element(
            "button",
            "ghost compact danger-action",
            "删除区域"
        );
        remove.type = "button";
        remove.addEventListener("click", () => removeRegion(index));
        heading.append(titleRow, remove);

        const fields = Stowling.element("div", "editor-fields");
        fields.append(
            field(
                "区域 ID",
                textInput(
                    `coordinate-region-id-${index}`,
                    "区域 ID",
                    region.id,
                    Boolean(region._originalId),
                    (value) => {
                        region.id = value;
                        markDirty();
                    }
                )
            ),
            field(
                "显示名称",
                textInput(
                    `coordinate-region-name-${index}`,
                    "显示名称",
                    region.name,
                    false,
                    (value) => {
                        region.name = value;
                        titleRow.querySelector("strong").textContent =
                            value || region.id || "未命名区域";
                        markDirty();
                    }
                )
            ),
            field("范围形状", shapeSelect(region, index)),
            field("区域维度", readOnlyInput(
                `coordinate-region-dimension-${index}`,
                "区域维度",
                Stowling.dimensionLabel(regionDimension(region))
            ))
        );
        appendShapeFields(fields, region, index);

        const summary = Stowling.element(
            "p",
            "muted section-help coordinate-region-summary",
            regionSummary(region)
        );
        const compatibility = Stowling.element(
            "p",
            "muted coordinate-region-compatibility",
            "任务按区域 ID 引用；球心/第一角点仅作为旧配置兼容待机点保存。"
        );
        card.append(heading, fields, summary, compatibility);
        return card;
    }

    /**
     * @param {HTMLElement} fields
     * @param {object} region
     * @param {number} index
     * @returns {void}
     */
    function appendShapeFields(fields, region, index) {
        if (region.shape?.type === "CUBOID") {
            fields.append(
                field(
                    "第一角点",
                    pointSelect(
                        `coordinate-region-first-${index}`,
                        region.shape.firstCornerCoordinateId,
                        (value) => {
                            region.shape.firstCornerCoordinateId = value;
                            markDirty(true);
                        }
                    )
                ),
                field(
                    "第二角点",
                    pointSelect(
                        `coordinate-region-second-${index}`,
                        region.shape.secondCornerCoordinateId,
                        (value) => {
                            region.shape.secondCornerCoordinateId = value;
                            markDirty(true);
                        }
                    )
                )
            );
            return;
        }
        fields.append(
            field(
                "球心命名点",
                pointSelect(
                    `coordinate-region-center-${index}`,
                    region.shape?.centerCoordinateId,
                    (value) => {
                        region.shape.centerCoordinateId = value;
                        markDirty(true);
                    }
                )
            ),
            field(
                "半径",
                numberInput(
                    `coordinate-region-radius-${index}`,
                    "半径",
                    region.shape?.radius,
                    1,
                    128,
                    (value) => {
                        region.shape.radius = value;
                        markDirty();
                    }
                )
            )
        );
    }

    /**
     * @param {object} region
     * @param {number} index
     * @returns {HTMLSelectElement}
     */
    function shapeSelect(region, index) {
        const select = selectInput(
            `coordinate-region-shape-${index}`,
            "范围形状",
            [
                ["SPHERE", "球形"],
                ["CUBOID", "长方体"]
            ],
            region.shape?.type || "SPHERE",
            (value) => {
                const anchorId = shapeAnchorId(region);
                region.shape = value === "CUBOID"
                    ? {
                        type: "CUBOID",
                        firstCornerCoordinateId: anchorId,
                        secondCornerCoordinateId: anchorId
                    }
                    : {
                        type: "SPHERE",
                        centerCoordinateId: anchorId,
                        radius: 32
                    };
                markDirty(true);
            }
        );
        return select;
    }

    /**
     * @param {object} region
     * @returns {string}
     */
    function shapeAnchorId(region) {
        if (region.shape?.type === "CUBOID") {
            return String(region.shape.firstCornerCoordinateId || "");
        }
        return String(
            region.shape?.centerCoordinateId
                || region.standbyCoordinateId
                || ""
        );
    }

    /**
     * @param {string} label
     * @param {HTMLElement} control
     * @returns {HTMLElement}
     */
    function field(label, control) {
        const wrapper = Stowling.element("div", "editor-field");
        const caption = Stowling.element("label", "", label);
        if (control.id) {
            caption.htmlFor = control.id;
        }
        wrapper.append(caption, control);
        return wrapper;
    }

    /**
     * @param {string} id
     * @param {string} label
     * @param {unknown} value
     * @param {boolean} disabled
     * @param {(value: string) => void} onChange
     * @returns {HTMLInputElement}
     */
    function textInput(id, label, value, disabled, onChange) {
        const input = document.createElement("input");
        input.id = id;
        input.type = "text";
        input.value = String(value ?? "");
        input.autocomplete = "off";
        input.disabled = disabled;
        input.setAttribute("aria-label", label);
        input.addEventListener("input", () => onChange(input.value));
        return input;
    }

    /**
     * @param {string} id
     * @param {string} label
     * @param {unknown} value
     * @returns {HTMLInputElement}
     */
    function readOnlyInput(id, label, value) {
        const input = document.createElement("input");
        input.id = id;
        input.type = "text";
        input.value = String(value ?? "");
        input.readOnly = true;
        input.setAttribute("aria-label", label);
        return input;
    }

    /**
     * @param {string} id
     * @param {string} label
     * @param {unknown} value
     * @param {number} min
     * @param {number} max
     * @param {(value: number | string) => void} onChange
     * @returns {HTMLInputElement}
     */
    function numberInput(id, label, value, min, max, onChange) {
        const input = document.createElement("input");
        input.id = id;
        input.type = "number";
        input.step = "1";
        input.min = String(min);
        input.max = String(max);
        input.value = String(value ?? min);
        input.setAttribute("aria-label", label);
        input.addEventListener("input", () => {
            const parsed = parseInteger(input.value);
            onChange(parsed === null ? input.value : parsed);
        });
        return input;
    }

    /**
     * @param {string} id
     * @param {string} label
     * @param {Array<Array<string>>} options
     * @param {unknown} value
     * @param {(value: string) => void} onChange
     * @returns {HTMLSelectElement}
     */
    function selectInput(id, label, options, value, onChange) {
        const select = document.createElement("select");
        select.id = id;
        select.setAttribute("aria-label", label);
        for (const [optionValue, optionLabel] of options) {
            select.append(new Option(optionLabel, optionValue));
        }
        select.value = String(value ?? "");
        select.addEventListener("change", () => onChange(select.value));
        return select;
    }

    /**
     * @param {string} id
     * @param {unknown} value
     * @param {(value: string) => void} onChange
     * @returns {HTMLSelectElement}
     */
    function pointSelect(id, value, onChange) {
        const options = [["", "请选择命名点"]];
        for (const point of state.coordinateSnapshot.coordinatePoints) {
            options.push([
                String(point.id || ""),
                pointOptionLabel(point)
            ]);
        }
        return selectInput(id, "命名点", options, value, onChange);
    }

    /**
     * @param {object} point
     * @returns {string}
     */
    function pointOptionLabel(point) {
        return `${point.name || point.id} · ${point.id} · `
            + `${Stowling.dimensionLabel(point.dimension)} · `
            + `${point.position?.x}, ${point.position?.y}, ${point.position?.z}`;
    }

    /**
     * @param {string} value
     * @returns {object | null}
     */
    function pointFor(value) {
        const id = String(value || "");
        return state.coordinateSnapshot.coordinatePoints.find((point) => {
            return String(point.id || "") === id;
        }) || null;
    }

    /**
     * @param {object} region
     * @returns {string}
     */
    function regionDimension(region) {
        if (region.shape?.type === "CUBOID") {
            return String(
                pointFor(region.shape.firstCornerCoordinateId)?.dimension
                    || pointFor(region.shape.secondCornerCoordinateId)?.dimension
                    || ""
            );
        }
        return String(
            pointFor(region.shape?.centerCoordinateId)?.dimension || ""
        );
    }

    /**
     * @param {object} region
     * @returns {string}
     */
    function regionSummary(region) {
        if (region.shape?.type === "CUBOID") {
            const first = pointFor(region.shape.firstCornerCoordinateId);
            const second = pointFor(region.shape.secondCornerCoordinateId);
            if (!first || !second) {
                return "选择两个命名点作为长方体角点；顺序不限。";
            }
            const bounds = boundsFor(first, second);
            return `角点：${first.name || first.id} ↔ ${second.name || second.id}。`
                + `范围 X ${bounds.min.x}–${bounds.max.x}、`
                + `Y ${bounds.min.y}–${bounds.max.y}、`
                + `Z ${bounds.min.z}–${bounds.max.z}。`;
        }
        const center = pointFor(region.shape?.centerCoordinateId);
        if (!center) {
            return "选择一个命名点作为球心。";
        }
        return `球心：${center.name || center.id} · `
            + `X ${center.position?.x}、Y ${center.position?.y}、`
            + `Z ${center.position?.z} · 半径 ${region.shape?.radius ?? "--"}。`;
    }

    /**
     * @param {object} first
     * @param {object} second
     * @returns {{min: object, max: object}}
     */
    function boundsFor(first, second) {
        const min = {};
        const max = {};
        for (const axis of ["x", "y", "z"]) {
            const firstValue = Number(first.position?.[axis]);
            const secondValue = Number(second.position?.[axis]);
            min[axis] = Math.min(firstValue, secondValue);
            max[axis] = Math.max(firstValue, secondValue);
        }
        return { min, max };
    }

    /**
     * @returns {void}
     */
    function addRegion() {
        const firstPoint = state.coordinateSnapshot.coordinatePoints[0];
        const pointId = String(firstPoint?.id || "");
        state.draft.push({
            id: nextRegionId(),
            name: "新拾取区域",
            dimension: String(firstPoint?.dimension || ""),
            shape: {
                type: "SPHERE",
                centerCoordinateId: pointId,
                radius: 32
            },
            _originalId: ""
        });
        markDirty(true);
    }

    /**
     * @returns {string}
     */
    function nextRegionId() {
        const used = new Set(state.draft.map((region) => String(region.id || "")));
        let index = 1;
        while (used.has(`region-${index}`)) {
            index += 1;
        }
        return `region-${index}`;
    }

    /**
     * @param {number} index
     * @returns {void}
     */
    function removeRegion(index) {
        const region = state.draft[index];
        const references = referencesFor(region._originalId || region.id);
        if (references.length > 0) {
            showErrors([
                `无法删除“${region.name || region.id}”：仍被 ${references.join("、")}`
                    + " 引用。请先在任务或旧拾取流程中解除引用并保存。"
            ]);
            Stowling.showToast("拾取区域仍被引用，暂未删除", true);
            return;
        }
        if (!window.confirm(`删除拾取区域“${region.name || region.id}”？`)) {
            return;
        }
        state.draft.splice(index, 1);
        markDirty(true);
    }

    /**
     * @param {boolean} rerender
     * @returns {void}
     */
    function markDirty(rerender = false) {
        state.dirty = true;
        if (rerender) {
            renderEditor();
            return;
        }
        updateState();
    }

    /**
     * @returns {Array<string>}
     */
    function validateDraft() {
        const errors = [];
        const ids = new Set();
        state.invalidFields.clear();
        state.draft.forEach((region, index) => {
            const label = `区域 ${index + 1}“${region.name || region.id || "未命名"}”`;
            validateIdentity(errors, ids, region, label, index);
            if (region.shape?.type === "CUBOID") {
                validateCuboid(region, label, index, errors);
            } else {
                validateSphere(region, label, index, errors);
            }
        });
        for (const original of state.resourceSnapshot.pickupRegions) {
            const originalId = String(original.id || "");
            const stillPresent = state.draft.some((region) => {
                return String(region._originalId || region.id || "") === originalId;
            });
            if (stillPresent) {
                continue;
            }
            const references = referencesFor(originalId);
            if (references.length > 0) {
                errors.push(
                    `区域“${original.name || originalId}”仍被 ${references.join("、")}`
                        + " 引用，不能删除；请先解除引用"
                );
            }
        }
        return [...new Set(errors)];
    }

    /**
     * @param {Array<string>} errors
     * @param {Set<string>} ids
     * @param {object} region
     * @param {string} label
     * @param {number} index
     * @returns {void}
     */
    function validateIdentity(errors, ids, region, label, index) {
        const id = String(region.id || "").trim();
        if (!id) {
            addError(errors, `${label}的区域 ID 不能为空`, `coordinate-region-id-${index}`);
        } else if (!/^[a-z0-9][a-z0-9._-]{0,63}$/.test(id)) {
            addError(
                errors,
                `${label}的区域 ID 格式无效；请使用小写字母、数字、点、下划线或连字符`,
                `coordinate-region-id-${index}`
            );
        } else if (ids.has(id)) {
            addError(
                errors,
                `${label}使用了重复区域 ID“${id}”`,
                `coordinate-region-id-${index}`
            );
        }
        if (id) {
            ids.add(id);
        }
        if (!String(region.name || "").trim()) {
            addError(
                errors,
                `${label}的显示名称不能为空`,
                `coordinate-region-name-${index}`
            );
        }
    }

    /**
     * @param {object} region
     * @param {string} label
     * @param {number} index
     * @param {Array<string>} errors
     * @returns {void}
     */
    function validateSphere(region, label, index, errors) {
        const center = pointFor(region.shape?.centerCoordinateId);
        if (!center) {
            addError(
                errors,
                `${label}必须选择一个已保存的球心命名点`,
                `coordinate-region-center-${index}`
            );
        }
        const radius = parseInteger(region.shape?.radius);
        if (radius === null || radius < 1 || radius > 128) {
            addError(
                errors,
                `${label}的半径必须是 1-128 的整数`,
                `coordinate-region-radius-${index}`
            );
        }
    }

    /**
     * @param {object} region
     * @param {string} label
     * @param {number} index
     * @param {Array<string>} errors
     * @returns {void}
     */
    function validateCuboid(region, label, index, errors) {
        const first = pointFor(region.shape?.firstCornerCoordinateId);
        const second = pointFor(region.shape?.secondCornerCoordinateId);
        if (!first) {
            addError(
                errors,
                `${label}必须选择一个已保存的第一角点`,
                `coordinate-region-first-${index}`
            );
        }
        if (!second) {
            addError(
                errors,
                `${label}必须选择一个已保存的第二角点`,
                `coordinate-region-second-${index}`
            );
        }
        if (first && second && first.dimension !== second.dimension) {
            addError(
                errors,
                `${label}的两个角点必须位于同一维度`,
                `coordinate-region-second-${index}`
            );
        }
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
     * @returns {void}
     */
    function updateState() {
        const errors = validateDraft();
        elements.save.disabled = !state.dirty || errors.length > 0;
        elements.discard.disabled = !state.dirty;
        elements.status.classList.toggle("dirty", state.dirty);
        if (errors.length > 0 && state.dirty) {
            elements.status.textContent =
                `发现 ${errors.length} 个拾取区域问题，保存已暂停`;
        } else if (state.dirty) {
            elements.status.textContent = "有未保存的拾取区域修改";
        } else {
            elements.status.textContent = "拾取区域已与配置文件同步";
        }
        showErrors(state.dirty ? errors : []);
        applyValidationStyles();
    }

    /**
     * @returns {void}
     */
    function applyValidationStyles() {
        for (const card of elements.list.querySelectorAll(".coordinate-region-editor")) {
            card.classList.remove("has-error");
            for (const control of card.querySelectorAll("input, select")) {
                control.setAttribute("aria-invalid", "false");
                control.closest(".editor-field")?.classList.remove("has-error");
            }
        }
        for (const fieldId of state.invalidFields) {
            const control = document.getElementById(fieldId);
            if (!control) {
                continue;
            }
            control.setAttribute("aria-invalid", "true");
            control.closest(".editor-field")?.classList.add("has-error");
            control.closest(".coordinate-region-editor")?.classList.add("has-error");
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
        const list = Stowling.element("ul");
        for (const error of errors) {
            list.append(Stowling.element("li", "", error));
        }
        elements.errors.append(
            Stowling.element("strong", "", "请修正以下问题："),
            list
        );
    }

    /**
     * @returns {Promise<void>}
     */
    async function saveRegions() {
        const errors = validateDraft();
        if (errors.length > 0) {
            showErrors(errors);
            Stowling.showToast("拾取区域仍有错误，暂未保存", true);
            return;
        }
        let body;
        try {
            body = regionRequest();
        } catch (error) {
            showErrors([error instanceof Error ? error.message : "区域无法保存"]);
            return;
        }
        elements.save.disabled = true;
        elements.discard.disabled = true;
        elements.status.textContent = "正在保存拾取区域…";
        try {
            const latest = await Stowling.request("/v1/config/task-resources");
            if (String(latest.revision || "") !== body.revision) {
                throw Object.assign(new Error("区域配置已改变，请刷新后再保存；草稿仍在"), {
                    status: 409,
                    code: "config_revision_conflict"
                });
            }
            body.cacheContainers = Stowling.clone(latest.cacheContainers || []);
            const saved = await Stowling.request("/v1/config/task-resources", {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(body)
            });
            applyResourceSnapshot(saved);
            elements.revision.textContent = Stowling.revisionLabel(
                state.resourceSnapshot.revision
            );
            elements.revision.title = state.resourceSnapshot.revision;
            renderEditor();
            document.dispatchEvent(new CustomEvent(
                "stowling:coordinate-regions-changed"
            ));
            Stowling.showToast("拾取区域已保存，旧缓存配置保持不变");
        } catch (error) {
            showErrors([saveErrorMessage(error)]);
            elements.status.textContent = isConflict(error)
                ? "配置版本冲突或任务正在运行，区域草稿已保留"
                : "拾取区域保存失败，草稿仍在";
            Stowling.handleError(error, "保存拾取区域失败");
        } finally {
            updateState();
        }
    }

    /**
     * @returns {object}
     */
    function regionRequest() {
        const pickupRegions = state.draft.map(toRequestRegion);
        return {
            revision: state.resourceSnapshot.revision,
            pickupRegions,
            cacheContainers: Stowling.clone(state.resourceSnapshot.cacheContainers)
        };
    }

    /**
     * @param {object} region
     * @returns {object}
     */
    function toRequestRegion(region) {
        const id = String(region.id || "").trim();
        let standbyPoint;
        let shape;
        if (region.shape?.type === "CUBOID") {
            const first = requirePoint(
                region.shape.firstCornerCoordinateId,
                `区域 ${id} 第一角点`
            );
            const second = requirePoint(
                region.shape.secondCornerCoordinateId,
                `区域 ${id} 第二角点`
            );
            const bounds = boundsFor(first, second);
            standbyPoint = first;
            shape = {
                type: "CUBOID",
                firstCornerCoordinateId: first.id,
                secondCornerCoordinateId: second.id,
                firstCorner: clonePosition(bounds.min),
                secondCorner: clonePosition(bounds.max)
            };
        } else {
            const center = requirePoint(
                region.shape?.centerCoordinateId,
                `区域 ${id} 球心`
            );
            standbyPoint = center;
            shape = {
                type: "SPHERE",
                centerCoordinateId: center.id,
                center: clonePosition(center.position),
                radius: Number(region.shape?.radius)
            };
        }
        const original = state.resourceSnapshot.pickupRegions.find((entry) => {
            return entry.id === (region._originalId || region.id);
        });
        if (original && original.dimension === standbyPoint.dimension) {
            standbyPoint = requirePoint(
                original.standbyCoordinateId,
                `区域 ${id} 旧兼容锚点`
            );
        }
        return {
            id,
            name: String(region.name || "").trim(),
            dimension: String(standbyPoint.dimension || ""),
            standbyCoordinateId: standbyPoint.id,
            standbyPosition: clonePosition(standbyPoint.position),
            shape
        };
    }

    /**
     * @param {unknown} value
     * @param {string} label
     * @returns {object}
     */
    function requirePoint(value, label) {
        const point = pointFor(value);
        if (!point) {
            throw new Error(`${label}引用的命名点不存在；请先保存命名坐标`);
        }
        return point;
    }

    /**
     * @param {object} position
     * @returns {object}
     */
    function clonePosition(position) {
        return {
            x: Number(position?.x),
            y: Number(position?.y),
            z: Number(position?.z)
        };
    }

    /**
     * @param {unknown} error
     * @returns {boolean}
     */
    function isConflict(error) {
        return error?.status === 409;
    }

    /**
     * @param {unknown} error
     * @returns {string}
     */
    function saveErrorMessage(error) {
        if (isConflict(error) && error.code === "active_task_conflict") {
            return "活动或暂停任务存在，区域草稿已保留；请先停止任务再保存";
        }
        if (isConflict(error)) {
            return "配置已被外部修改，区域草稿已保留；请放弃修改后重新读取";
        }
        const message = error instanceof Error ? error.message : "保存拾取区域失败";
        if (error?.code === "invalid_pickup_config") {
            return `${message}；请确认角点仍存在、维度一致，且区域未被任务引用`;
        }
        return message;
    }

    /**
     * @returns {Promise<void>}
     */
    async function discardChanges() {
        elements.discard.disabled = true;
        try {
            await load();
            Stowling.showToast("已放弃区域草稿并重新读取命名点");
        } catch (error) {
            Stowling.handleError(error, "重新读取拾取区域失败");
        }
    }

    /**
     * @returns {Promise<void>}
     */
    async function refreshAfterCoordinates() {
        const [coordinateSnapshot, resourceSnapshot] = await Promise.all([
            Stowling.request("/v1/config/coordinate-points"),
            Stowling.request("/v1/config/task-resources")
        ]);
        const preserveDraft = state.dirty;
        applyCoordinateSnapshot(coordinateSnapshot);
        applyResourceSnapshot(resourceSnapshot, preserveDraft);
        await refreshTaskReferences();
        elements.revision.textContent = Stowling.revisionLabel(
            state.resourceSnapshot.revision
        );
        elements.revision.title = state.resourceSnapshot.revision;
        renderEditor();
    }

    /**
     * @returns {Promise<void>}
     */
    async function refreshTaskReferences() {
        state.taskReferences = new Map();
        try {
            const source = await Stowling.request("/v1/task-definitions");
            addTaskReferences(source);
        } catch (error) {
            state.taskReferences = new Map();
        }
    }

    /**
     * @param {object} source
     * @returns {void}
     */
    function addTaskReferences(source) {
        const tasks = Array.isArray(source?.tasks) ? source.tasks : [];
        for (const task of tasks) {
            const taskLabel = `任务“${task.name || task.id}”`;
            const steps = [
                ...(task.steps || []),
                ...(task.settings?.interruption?.steps || [])
            ];
            for (const step of steps) {
                addRegionReference(
                    step.parameters?.regionId,
                    `${taskLabel}步骤 ${step.id || step.type}`
                );
            }
            for (const binding of task.settings?.pickup?.regions || []) {
                addRegionReference(binding.regionId, `${taskLabel}旧区域顺序`);
            }
            for (const regionId of task.settings?.trigger?.regionIds || []) {
                addRegionReference(regionId, `${taskLabel}触发区域`);
            }
        }
    }

    /**
     * @returns {void}
     */
    function rebuildLegacyReferences() {
        state.legacyReferences = new Map();
        for (const workflow of state.resourceSnapshot.pickupWorkflows) {
            for (const binding of workflow.regions || []) {
                addReference(
                    state.legacyReferences,
                    binding.regionId,
                    `旧拾取流程“${workflow.name || workflow.id}”`
                );
            }
        }
    }

    /**
     * @param {unknown} regionId
     * @param {string} label
     * @returns {void}
     */
    function addRegionReference(regionId, label) {
        addReference(state.taskReferences, regionId, label);
    }

    /**
     * @param {Map<string, Array<string>>} target
     * @param {unknown} regionId
     * @param {string} label
     * @returns {void}
     */
    function addReference(target, regionId, label) {
        const id = String(regionId || "");
        if (!id) {
            return;
        }
        const values = target.get(id) || [];
        if (!values.includes(label)) {
            values.push(label);
            target.set(id, values);
        }
    }

    /**
     * @param {unknown} regionId
     * @returns {Array<string>}
     */
    function referencesFor(regionId) {
        const id = String(regionId || "");
        return [...new Set([
            ...(state.legacyReferences.get(id) || []),
            ...(state.taskReferences.get(id) || [])
        ])];
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
        return Number.isSafeInteger(result) ? result : null;
    }

    elements.add.addEventListener("click", addRegion);
    elements.discard.addEventListener("click", () => {
        void discardChanges();
    });
    elements.form.addEventListener("submit", (event) => {
        event.preventDefault();
        void saveRegions();
    });

    window.StowlingCoordinateRegions = Object.freeze({
        load,
        refreshAfterCoordinates
    });

    Stowling.onConnected(async () => {
        try {
            await load();
        } catch (error) {
            elements.status.textContent = "拾取区域读取失败";
            Stowling.handleError(error, "读取拾取区域页面失败");
        }
    });
})();
