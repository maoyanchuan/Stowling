(() => {
    "use strict";

    const DEFAULT_CACHE_POLICY = Object.freeze({
        enabled: false, mode: "PACK_FIRST", thresholdPercent: 80
    });
    const COLLECTION_PRIORITIES = Object.freeze([
        "NORMAL",
        "URGENT"
    ]);
    const INTEGER_PATTERN = /^\d+$/;
    const DEFAULT_REMAINDER_POLICY = Object.freeze({
        unclassifiableEnabled: false, unclassifiableDestinationRef: null,
        overflowEnabled: false, overflowDestinationRef: null
    });

    /** @param {object} [value] @returns {object} */
    function remainderPolicy(value = {}) {
        const policy = {...DEFAULT_REMAINDER_POLICY, ...value};
        return {
            unclassifiableEnabled: policy.unclassifiableEnabled,
            unclassifiableDestinationRef: policy.unclassifiableDestinationRef,
            overflowEnabled: policy.overflowEnabled,
            overflowDestinationRef: policy.overflowDestinationRef
        };
    }

    /**
     * @param {string} type
     * @param {unknown} id
     * @returns {string}
     */
    function reference(type, id) {
        const normalizedId = String(id ?? "").trim();
        return normalizedId === "" ? "" : `${type}:${normalizedId}`;
    }

    /**
     * @param {unknown} value
     * @param {string} expectedType
     * @returns {string}
     */
    function referencedId(value, expectedType) {
        const text = String(value ?? "").trim();
        const separator = text.indexOf(":");
        if (separator <= 0 || separator === text.length - 1) {
            return "";
        }
        const type = text.slice(0, separator);
        return type === expectedType ? text.slice(separator + 1).trim() : "";
    }

    /** @param {string} [kind] @returns {object} */
    function newDraft(kind = "COMBINED") {
        return {
            id: "",
            coordinateId: "",
            pickupRadius: "",
            detectionIntervalSeconds: "1",
            cacheLocationRef: "",
            cachePolicy: {...DEFAULT_CACHE_POLICY},
            classificationAreas: [],
            collectionPriority: "NORMAL",
            enabled: false,
            overflowEnabled: false,
            overflowDestinationRef: "",
            robotWarehouseMixedBoxes: true,
            unstackableZoneRoutingEnabled: false,
            foodPauseResumeEnabled: false,
            remainderPolicy: {...DEFAULT_REMAINDER_POLICY},
            cleanupDestinationRef: "",
            facilities: {},
            collectionEnabled: kind !== "CLASSIFICATION",
            classificationEnabled: kind !== "PICKUP",
            ...(kind !== "COMBINED" ? {kind} : {}),
            ...(kind === "PICKUP" ? {followUpEnabled: false,
                followUpTaskRef: "", followUpDelaySeconds: "30"} : {})
        };
    }

    /**
     * @param {object} item
     * @returns {object}
     */
    function draftFromTask(item) {
        const detection = item?.detection && typeof item.detection === "object"
            ? item.detection
            : {};
        return {
            id: String(item?.id ?? ""),
            coordinateId: referencedId(detection.coordinateRef, "coordinate"),
            detectionIntervalSeconds: String(detection.intervalSeconds ?? 1),
            pickupRadius: detection.pickupRadius === undefined
                || detection.pickupRadius === null
                ? ""
                : String(detection.pickupRadius),
            cacheLocationRef: String(item?.cacheLocationRef || ""),
            cachePolicy: {...DEFAULT_CACHE_POLICY, ...item?.cachePolicy},
            classificationAreas: Array.isArray(item?.classificationAreas)
                ? item.classificationAreas.map((area) => ({
                    categoryZoneId: referencedId(
                        area?.categoryZoneRef,
                        "category-zone"
                    )
                }))
                : [],
            collectionPriority: String(item?.collectionPriority ?? ""),
            enabled: item?.enabled === true,
            overflowEnabled: item?.overflowEnabled === true,
            overflowDestinationRef: String(item?.overflowDestinationRef || ""),
            robotWarehouseMixedBoxes: item?.robotWarehouseMixedBoxes !== false,
            unstackableZoneRoutingEnabled: item?.unstackableZoneRoutingEnabled === true,
            foodPauseResumeEnabled: item?.foodPauseResumeEnabled === true,
            remainderPolicy: remainderPolicy(item?.remainderPolicy),
            cleanupDestinationRef: item?.cleanupPolicy?.destinationRef === item?.cacheLocationRef
                ? "" : String(item?.cleanupPolicy?.destinationRef || ""),
            facilities: item?.cleanupPolicy || {},
            collectionEnabled: item?.collectionEnabled === true,
            classificationEnabled: item?.classificationEnabled === true,
            ...(item?.kind && item.kind !== "COMBINED" ? {kind: item.kind} : {}),
            ...(item?.kind === "PICKUP" ? {
                followUpEnabled: item.followUpEnabled ?? Boolean(item.followUpTaskRef),
                followUpTaskRef: item.followUpTaskRef || "",
                followUpDelaySeconds: String(item.followUpDelaySeconds ?? 30)} : {})
        };
    }

    /**
     * @param {unknown} resources
     * @param {string} key
     * @returns {Array<object>}
     */
    function resourceList(resources, key) {
        const value = resources?.[key];
        return Array.isArray(value) ? value : [];
    }

    /**
     * @param {unknown} resources
     * @param {string} key
     * @param {string} id
     * @returns {boolean}
     */
    function resourceExists(resources, key, id) {
        return resourceList(resources, key).some(
            (resource) => String(resource?.id ?? "") === id
        );
    }

    /**
     * @param {unknown} value
     * @returns {number | null}
     */
    function parsedRadius(value) {
        const text = String(value ?? "").trim();
        if (!INTEGER_PATTERN.test(text)) {
            return null;
        }
        const radius = Number(text);
        return Number.isInteger(radius) && radius >= 1 && radius <= 64
            ? radius
            : null;
    }

    /** @param {unknown} value @param {number} min @param {number} max @returns {boolean} */
    function validSeconds(value, min, max) {
        const text = String(value ?? "").trim();
        return INTEGER_PATTERN.test(text) && Number(text) >= min && Number(text) <= max;
    }

    /**
     * @param {object} draft
     * @param {unknown} resources
     * @returns {Array<string>}
     */
    function validateDraft(draft, resources) {
        const errors = [];
        const policy = {...DEFAULT_CACHE_POLICY, ...draft?.cachePolicy};
        if (typeof policy.enabled !== "boolean") {
            errors.push("打包缓存开关需为布尔值");
        }
        if (!["PACK_FIRST", "CLASSIFY_FIRST"].includes(policy.mode)) {
            errors.push("请选择缓存打包优先或分类优先");
        }
        if (!Number.isInteger(policy.thresholdPercent)
            || policy.thresholdPercent < 1 || policy.thresholdPercent > 100) {
            errors.push("缓存容量阈值必须为 1 到 100 的整数百分比");
        }
        if (draft?.kind !== "CLASSIFICATION") {
            if (!validSeconds(draft?.detectionIntervalSeconds ?? 1, 1, 60)) {
                errors.push("掉落物检测间隔需为 1 至 60 秒");
            }
            const coordinateId = String(draft?.coordinateId ?? "").trim();
            if (coordinateId === "") {
                errors.push("请选择掉落物检测点");
            } else if (!resourceExists(resources, "coordinates", coordinateId)) {
                errors.push("所选掉落物检测点不存在，请刷新后重试");
            }

            const radius = String(draft?.pickupRadius ?? "").trim();
            if (radius && parsedRadius(radius) === null) {
                errors.push("任务覆盖半径需为 1 到 64 格");
            }
        }

        if (draft?.kind === "PICKUP" && typeof draft.followUpEnabled !== "boolean") {
            errors.push("任务结束后创建任务开关需为布尔值");
        }
        if (draft?.kind === "PICKUP" && draft.followUpEnabled
            && !validSeconds(draft?.followUpDelaySeconds ?? 30, 0, 86400)) {
            errors.push("创建任务倒计时需为 0 至 86400 秒");
        }

        const cache = String(draft?.cacheLocationRef || "");
        const sourceLabel = draft?.kind === "CLASSIFICATION" ? "物品来源" : "缓存位置";
        validateRemainders(draft, resources, errors);
        const boxes = draft?.facilities?.emptyBoxWarehouse;
        if (draft?.kind === "CLASSIFICATION" && boxes?.enabled) {
            const places = [...(resources?.coordinates || []).map(item => "coordinate:" + item.id),
                ...(resources?.regions || []).map(item => "region:" + item.id)];
            if (!places.includes(boxes.warehouseRef)) errors.push("请选择有效的空盒仓库");
            if (boxes.warehouseRef === cache) errors.push("空盒仓库和物品来源必须使用不同的位置");
            if (!Number.isInteger(boxes.cacheLimit) || boxes.cacheLimit < 0
                || boxes.cacheLimit > 3456) errors.push("缓存空盒保留数量必须为 0 到 3456 的整数");
        }
        if (!cache) {
            errors.push(`请选择${sourceLabel}`);
        } else if (!cacheLocations(resources).some(item => item.id === cache)) {
            errors.push(`所选${sourceLabel}不存在，请重新选择`);
        }
        if (typeof draft?.robotWarehouseMixedBoxes !== "boolean"
            && draft?.robotWarehouseMixedBoxes !== undefined) {
            errors.push("机器人仓库储存方式需为布尔值");
        }
        if (draft?.overflowEnabled && !robotWarehouseLocations(resources)
                .some(item => item.id === draft.overflowDestinationRef)) {
            errors.push("请选择有效的机器人仓库区域");
        }
        if (draft?.overflowEnabled && draft.overflowDestinationRef === cache) {
            errors.push("机器人仓库与物品来源必须使用不同的位置");
        }
        validateAreas(draft, resources, errors);

        if (!COLLECTION_PRIORITIES.includes(draft?.collectionPriority)) {
            errors.push("收集优先级只能为普通或紧急");
        }
        if (typeof draft?.enabled !== "boolean") {
            errors.push("启用状态需为布尔值");
        }
        if (typeof draft?.collectionEnabled !== "boolean") {
            errors.push("掉落物拾取需为布尔值");
        }
        if (typeof draft?.classificationEnabled !== "boolean") {
            errors.push("后台分类需为布尔值");
        }
        return errors;
    }

    /**
     * @param {object} draft
     * @param {unknown} resources
     * @returns {object}
     */
    function payload(draft, resources) {
        const errors = validateDraft(draft, resources);
        if (errors.length > 0) {
            throw new RangeError(errors.join("；"));
        }
        return {
            enabled: draft.enabled,
            overflowEnabled: draft.overflowEnabled === true,
            overflowDestinationRef: draft.overflowDestinationRef || "",
            robotWarehouseMixedBoxes: draft.robotWarehouseMixedBoxes !== false,
            unstackableZoneRoutingEnabled: draft.kind === "CLASSIFICATION"
                && draft.unstackableZoneRoutingEnabled === true,
            foodPauseResumeEnabled: draft.kind === "CLASSIFICATION"
                && draft.foodPauseResumeEnabled === true,
            remainderPolicy: remainderPolicy(draft.remainderPolicy),
            cleanupPolicy: {...draft.facilities, destinationRef: draft.cleanupDestinationRef || null,
                resumeMode: "RESTART"},
            collectionEnabled: draft.collectionEnabled,
            classificationEnabled: draft.classificationEnabled,
            ...(draft.kind !== "CLASSIFICATION" ? {detection: {
                coordinateRef: reference("coordinate", draft.coordinateId),
                pickupRadius: parsedRadius(draft.pickupRadius),
                intervalSeconds: Number(draft.detectionIntervalSeconds ?? 1)
            }} : {}),
            cacheLocationRef: draft.cacheLocationRef,
            cachePolicy: {...DEFAULT_CACHE_POLICY, ...draft.cachePolicy},
            classificationAreas: draft.classificationAreas.filter(area => area.categoryZoneId)
                .map((area) => ({
                categoryZoneRef: reference(
                    "category-zone",
                    area.categoryZoneId
                )
            })),
            collectionPriority: draft.collectionPriority,
            ...(draft.kind ? {kind: draft.kind} : {}),
            ...(draft.kind === "PICKUP" ? {followUpEnabled: draft.followUpEnabled === true,
                followUpTaskRef: draft.followUpTaskRef || "",
                followUpDelaySeconds: validSeconds(draft.followUpDelaySeconds ?? 30, 0, 86400)
                    ? Number(draft.followUpDelaySeconds ?? 30) : 30} : {})
        };
    }

    /** @param {object} resources @returns {Array<{id: string, name: string}>} */
    function cacheLocations(resources) {
        return [
            ["coordinates", "coordinate", "点位"],
            ["regions", "region", "区域"],
            ["warehouses", "warehouse", "储存区"]
        ].flatMap(([key, type, name]) => resourceList(resources, key).map(item => ({
            id: reference(type, item.id), name: name + " · " + item.name
        })));
    }

    /** @param {unknown} resources @returns {Array<{id: string, name: string}>} */
    function robotWarehouseLocations(resources) {
        return [
            ...resourceList(resources, "regions").map(item => ({
                id: reference("region", item.id), name: "容器区域 · " + item.name
            })),
            ...resourceList(resources, "warehouses").map(item => ({
                id: reference("warehouse", item.id), name: "已有仓库 · " + item.name
            }))
        ];
    }

    /** @param {object} draft @param {object} resources @param {Array<string>} errors
     * @returns {void} */
    function validateRemainders(draft, resources, errors) {
        const policy = {...DEFAULT_REMAINDER_POLICY, ...draft.remainderPolicy};
        const places = cacheLocations(resources);
        for (const [key, label] of [["unclassifiable", "不可分类"], ["overflow", "总溢出"]]) {
            if (typeof policy[`${key}Enabled`] !== "boolean") {
                errors.push(label + "开关需为布尔值");
            }
            if (!policy[`${key}Enabled`]) continue;
            const ref = policy[`${key}DestinationRef`];
            if (!places.some(item => item.id === ref)) {
                errors.push(`请选择有效的${label}存放位置`);
            } else if (ref === draft.cacheLocationRef) {
                errors.push(`${label}存放位置必须与物品来源分开`);
            }
        }
    }

    /**
     * @param {Array<{categoryZoneId: string}>} areas
     * @param {Array<string>} selectedIds
     * @returns {Array<{categoryZoneId: string}>}
     */
    function classificationAreasFromSelection(areas, selectedIds) {
        const remaining = new Set(selectedIds.filter(Boolean));
        const result = [];
        for (const area of areas) {
            if (!remaining.delete(area.categoryZoneId)) continue;
            result.push({categoryZoneId: area.categoryZoneId});
        }
        for (const id of remaining) result.push({categoryZoneId: id});
        return result;
    }

    /**
     * @param {object} draft
     * @param {unknown} resources
     * @param {Array<string>} errors
     * @returns {void}
     */
    function validateAreas(draft, resources, errors) {
        if (!draft?.classificationEnabled) return;
        const areas = draft?.classificationAreas;
        if (!Array.isArray(areas) || areas.length === 0) {
            errors.push("请至少选择一个目标分类区");
            return;
        }
        const zoneIds = new Set();
        for (const area of areas) {
            const zoneId = String(area?.categoryZoneId ?? "").trim();
            const zone = resourceList(resources, "categoryZones")
                .find((value) => String(value?.id ?? "") === zoneId);
            if (!zone) {
                errors.push("所选目标分类区不存在，请重新选择");
                continue;
            }
            if (zoneIds.has(zoneId)) {
                errors.push("同一个目标分类区不能重复选择");
            }
            zoneIds.add(zoneId);
        }
    }

    window.StowlingAutoClassifyTaskModel = Object.freeze({
        draftFromTask,
        newDraft,
        payload,
        validateDraft,
        cacheLocations,
        robotWarehouseLocations,
        classificationAreasFromSelection
    });
})();

