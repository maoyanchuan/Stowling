(() => {
    "use strict";

    const DEFAULT_PICKUP_RADIUS = "8";
    const DEFAULT_CACHE_POLICY = Object.freeze({
        enabled: false, mode: "PACK_FIRST", thresholdPercent: 80
    });
    const COLLECTION_PRIORITIES = Object.freeze([
        "NORMAL",
        "URGENT"
    ]);
    const INTEGER_PATTERN = /^\d+$/;

    /**
     * @returns {object}
     */
    function newArea() {
        return {
            categoryZoneId: ""
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

    /**
     * @returns {object}
     */
    function newDraft() {
        return {
            id: "",
            coordinateId: "",
            pickupRadius: DEFAULT_PICKUP_RADIUS,
            cacheRegionId: "",
            cachePolicy: {...DEFAULT_CACHE_POLICY},
            classificationAreas: [newArea()],
            collectionPriority: "NORMAL",
            enabled: false,
            overflowEnabled: false,
            overflowDestinationRef: "",
            cleanupDestinationRef: "",
            collectionEnabled: true,
            classificationEnabled: true
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
            pickupRadius: detection.pickupRadius === undefined
                || detection.pickupRadius === null
                ? ""
                : String(detection.pickupRadius),
            cacheRegionId: referencedId(item?.cacheRegionRef, "region"),
            cachePolicy: {...DEFAULT_CACHE_POLICY, ...item?.cachePolicy},
            classificationAreas: Array.isArray(item?.classificationAreas)
                ? item.classificationAreas.map((area) => ({
                    categoryZoneId: referencedId(
                        area?.categoryZoneRef,
                        "category-zone"
                    ),
                    storagePolicy: area?.storagePolicy || null
                }))
                : [],
            collectionPriority: String(item?.collectionPriority ?? ""),
            enabled: item?.enabled === true,
            overflowEnabled: item?.overflowEnabled === true,
            overflowDestinationRef: String(item?.overflowDestinationRef || ""),
            cleanupDestinationRef: item?.cleanupPolicy?.destinationRef === item?.cacheRegionRef
                ? "" : String(item?.cleanupPolicy?.destinationRef || ""),
            collectionEnabled: item?.collectionEnabled === true,
            classificationEnabled: item?.classificationEnabled === true
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
        const coordinateId = String(draft?.coordinateId ?? "").trim();
        if (coordinateId === "") {
            errors.push("请选择掉落物检测点");
        } else if (!resourceExists(resources, "coordinates", coordinateId)) {
            errors.push("所选掉落物检测点不存在，请刷新后重试");
        }

        if (parsedRadius(draft?.pickupRadius) === null) {
            errors.push("拾取半径需为1～64的整数");
        }

        const cacheRegionId = String(draft?.cacheRegionId ?? "").trim();
        if (cacheRegionId === "") {
            errors.push("请选择缓存区域");
        } else if (!resourceExists(resources, "regions", cacheRegionId)) {
            errors.push("所选缓存区域不存在，请刷新后重试");
        }

        const cleanup = String(draft?.cleanupDestinationRef || "");
        if (cleanup && !(cleanup.startsWith("coordinate:")
                && resourceExists(resources, "coordinates", cleanup.slice(11)))
                && !(cleanup.startsWith("region:")
                    && resourceExists(resources, "regions", cleanup.slice(7)))) {
            errors.push("中断存放位置不存在，请重新选择");
        }
        if (draft?.overflowEnabled && draft.overflowDestinationRef === `region:${cacheRegionId}`) {
            errors.push("溢出存入位置需要与缓存区域分开");
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
            overflowDestinationRef: draft.overflowEnabled ? draft.overflowDestinationRef || "" : "",
            cleanupPolicy: {destinationRef: draft.cleanupDestinationRef
                || reference("region", draft.cacheRegionId), resumeMode: "RESTART"},
            collectionEnabled: draft.collectionEnabled,
            classificationEnabled: draft.classificationEnabled,
            detection: {
                coordinateRef: reference(
                    "coordinate",
                    draft.coordinateId
                ),
                pickupRadius: parsedRadius(draft.pickupRadius)
            },
            cacheRegionRef: reference("region", draft.cacheRegionId),
            cachePolicy: {...DEFAULT_CACHE_POLICY, ...draft.cachePolicy},
            classificationAreas: draft.classificationAreas.map((area) => ({
                categoryZoneRef: reference(
                    "category-zone",
                    area.categoryZoneId
                ),
                storagePolicy: area.storagePolicy || null
            })),
            collectionPriority: draft.collectionPriority
        };
    }

    /**
     * @param {object} settings
     * @returns {{enabled: boolean, locations: Array<string>}}
     */
    function warehouseDraft(settings) {
        const locations = [
            ...(settings?.robotWarehouseCoordinateIds || []).map((id) => `coordinate:${id}`),
            ...(settings?.robotWarehouseRegionIds || []).map((id) => `region:${id}`)
        ];
        return {enabled: locations.length > 0, locations};
    }

    /**
     * @param {object} draft
     * @param {unknown} resources
     * @param {Array<string>} errors
     * @returns {void}
     */
    function validateAreas(draft, resources, errors) {
        const areas = draft?.classificationAreas;
        if (!Array.isArray(areas) || areas.length === 0) {
            errors.push("请至少添加一个分类选区");
            return;
        }
        const zoneIds = new Set();
        for (const area of areas) {
            const zoneId = String(area?.categoryZoneId ?? "").trim();
            const zone = resourceList(resources, "categoryZones")
                .find((value) => String(value?.id ?? "") === zoneId);
            if (!zone) {
                errors.push("分类选区引用的分类区不存在，请刷新后重试");
                continue;
            }
            if (zoneIds.has(zoneId)) {
                errors.push("同一个分类选区不能重复添加");
            }
            zoneIds.add(zoneId);
        }
    }

    window.StowlingAutoClassifyTaskModel = Object.freeze({
        draftFromTask,
        newArea,
        newDraft,
        payload,
        validateDraft,
        warehouseDraft
    });
})();

