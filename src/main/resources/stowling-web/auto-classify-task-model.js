(() => {
    "use strict";

    const DEFAULT_NAME = "自动分类";
    const DEFAULT_PICKUP_RADIUS = "8";
    const MARKER_SOURCES = Object.freeze([
        "PLACED_ITEM",
        "ITEM_FRAME",
        "SHELF",
        "CONTAINER_CONTENTS"
    ]);
    const COLLECTION_PRIORITIES = Object.freeze([
        "NORMAL",
        "URGENT"
    ]);
    const INTEGER_PATTERN = /^\d+$/;

    /**
     * @param {unknown} value
     * @param {string} key
     * @returns {Array<object>}
     */
    function listValue(value, key) {
        if (Array.isArray(value)) {
            return value;
        }
        return Array.isArray(value?.[key]) ? value[key] : [];
    }

    /**
     * @param {unknown} value
     * @returns {string}
     */
    function normalizedName(value) {
        return String(value ?? "").trim().toLowerCase();
    }

    /**
     * @param {unknown} items
     * @param {unknown} [allTasks]
     * @returns {string}
     */
    function defaultDraftName(items, allTasks = []) {
        const names = new Set([
            ...listValue(items, "items"),
            ...listValue(allTasks, "tasks")
        ].map((item) => normalizedName(item?.name)));
        let candidate = DEFAULT_NAME;
        let suffix = 2;
        while (names.has(normalizedName(candidate))) {
            candidate = `${DEFAULT_NAME} ${suffix}`;
            suffix += 1;
        }
        return candidate;
    }

    /**
     * @param {unknown} name
     * @param {unknown} items
     * @param {unknown} allTasks
     * @param {unknown} [excludedId]
     * @returns {boolean}
     */
    function hasNameConflict(name, items, allTasks, excludedId = "") {
        const normalized = normalizedName(name);
        if (normalized === "") {
            return false;
        }
        const excluded = String(excludedId ?? "").trim();
        return [...listValue(items, "items"), ...listValue(allTasks, "tasks")]
            .some((item) => {
                if (excluded !== "" && String(item?.id ?? "") === excluded) {
                    return false;
                }
                return normalizedName(item?.name) === normalized;
            });
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
     * @param {unknown} value
     * @returns {Array<string>}
     */
    function markerSourceText(value) {
        return Array.isArray(value) ? value.map((source) => String(source)) : [];
    }

    /**
     * @param {unknown} [items]
     * @param {unknown} [allTasks]
     * @returns {object}
     */
    function newDraft(items = [], allTasks = []) {
        return {
            id: "",
            name: defaultDraftName(items, allTasks),
            coordinateId: "",
            pickupRadius: DEFAULT_PICKUP_RADIUS,
            cacheRegionId: "",
            categoryZoneId: "",
            markerSources: [...MARKER_SOURCES],
            collectionPriority: "NORMAL",
            enabled: false
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
            name: String(item?.name ?? ""),
            coordinateId: referencedId(detection.coordinateRef, "coordinate"),
            pickupRadius: detection.pickupRadius === undefined
                || detection.pickupRadius === null
                ? ""
                : String(detection.pickupRadius),
            cacheRegionId: referencedId(item?.cacheRegionRef, "region"),
            categoryZoneId: referencedId(
                item?.categoryZoneRef,
                "category-zone"
            ),
            markerSources: markerSourceText(item?.markerSources),
            collectionPriority: String(item?.collectionPriority ?? ""),
            enabled: item?.enabled === true
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
     * @param {unknown} items
     * @param {unknown} [allTasks]
     * @returns {Array<string>}
     */
    function validateDraft(draft, resources, items, allTasks = []) {
        const errors = [];
        const name = String(draft?.name ?? "").trim();
        if (name.length < 1 || name.length > 80) {
            errors.push("任务名称需为1～80个字符");
        }
        if (hasNameConflict(name, items, allTasks, draft?.id)) {
            errors.push("任务名称已存在，请使用其他名称");
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

        const categoryZoneId = String(draft?.categoryZoneId ?? "").trim();
        if (categoryZoneId === "") {
            errors.push("请选择分类区");
        } else if (!resourceExists(
            resources,
            "categoryZones",
            categoryZoneId
        )) {
            errors.push("所选分类区不存在，请刷新后重试");
        }

        const sources = draft?.markerSources;
        if (!Array.isArray(sources) || sources.length === 0) {
            errors.push("请至少选择一种分类物识别方式");
        } else if (sources.some((source) => !MARKER_SOURCES.includes(source))) {
            errors.push("分类物识别方式包含未知选项");
        }

        if (!COLLECTION_PRIORITIES.includes(draft?.collectionPriority)) {
            errors.push("收集优先级只能为普通或紧急");
        }
        if (typeof draft?.enabled !== "boolean") {
            errors.push("启用状态需为布尔值");
        }
        return errors;
    }

    /**
     * @param {object} draft
     * @param {unknown} resources
     * @param {unknown} items
     * @param {unknown} [allTasks]
     * @returns {object}
     */
    function payload(draft, resources, items, allTasks = []) {
        const errors = validateDraft(draft, resources, items, allTasks);
        if (errors.length > 0) {
            throw new RangeError(errors.join("；"));
        }
        return {
            name: String(draft.name).trim(),
            enabled: draft.enabled,
            detection: {
                coordinateRef: reference(
                    "coordinate",
                    draft.coordinateId
                ),
                pickupRadius: parsedRadius(draft.pickupRadius)
            },
            cacheRegionRef: reference("region", draft.cacheRegionId),
            categoryZoneRef: reference(
                "category-zone",
                draft.categoryZoneId
            ),
            markerSources: [...draft.markerSources],
            collectionPriority: draft.collectionPriority
        };
    }

    window.StowlingAutoClassifyTaskModel = Object.freeze({
        draftFromTask,
        newDraft,
        payload,
        validateDraft
    });
})();

