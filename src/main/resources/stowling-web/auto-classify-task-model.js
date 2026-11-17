(() => {
    "use strict";

    const DEFAULT_NAME = "自动分类";
    const DEFAULT_PICKUP_RADIUS = "8";
    const DIRECT_RULE_ID = "DIRECT_BLOCK_ID";
    const CONTAINER_RULE_ID = "CONTAINER_CONTENTS";
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
     * @param {string} ruleId
     * @returns {Array<string>}
     */
    function defaultMarkerSources(ruleId) {
        return ruleId === CONTAINER_RULE_ID
            ? ["CONTAINER_CONTENTS"]
            : ["PLACED_ITEM", "ITEM_FRAME", "SHELF"];
    }

    /**
     * @param {string} ruleId
     * @param {Array<string>} sources
     * @returns {boolean}
     */
    function ruleSupportsSources(ruleId, sources) {
        if (ruleId === CONTAINER_RULE_ID) {
            return sources.length === 1
                && sources[0] === "CONTAINER_CONTENTS";
        }
        if (ruleId === DIRECT_RULE_ID) {
            return sources.length > 0
                && sources.every((source) => source !== "CONTAINER_CONTENTS");
        }
        return false;
    }

    /**
     * @returns {object}
     */
    function newArea() {
        return {
            categoryZoneId: "",
            ruleId: DIRECT_RULE_ID,
            markerSources: defaultMarkerSources(DIRECT_RULE_ID)
        };
    }

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
            classificationAreas: [newArea()],
            collectionPriority: "NORMAL",
            enabled: false,
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
            name: String(item?.name ?? ""),
            coordinateId: referencedId(detection.coordinateRef, "coordinate"),
            pickupRadius: detection.pickupRadius === undefined
                || detection.pickupRadius === null
                ? ""
                : String(detection.pickupRadius),
            cacheRegionId: referencedId(item?.cacheRegionRef, "region"),
            classificationAreas: Array.isArray(item?.classificationAreas)
                ? item.classificationAreas.map((area) => ({
                    categoryZoneId: referencedId(
                        area?.categoryZoneRef,
                        "category-zone"
                    ),
                    ruleId: String(area?.ruleId ?? DIRECT_RULE_ID),
                    markerSources: markerSourceText(area?.markerSources)
                }))
                : [],
            collectionPriority: String(item?.collectionPriority ?? ""),
            enabled: item?.enabled === true,
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

        validateAreas(draft, resources, errors);

        if (!COLLECTION_PRIORITIES.includes(draft?.collectionPriority)) {
            errors.push("收集优先级只能为普通或紧急");
        }
        if (typeof draft?.enabled !== "boolean") {
            errors.push("启用状态需为布尔值");
        }
        if (typeof draft?.collectionEnabled !== "boolean") {
            errors.push("启用缓存需为布尔值");
        }
        if (typeof draft?.classificationEnabled !== "boolean") {
            errors.push("后台分类需为布尔值");
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
            classificationAreas: draft.classificationAreas.map((area) => ({
                categoryZoneRef: reference(
                    "category-zone",
                    area.categoryZoneId
                ),
                ruleId: String(area.ruleId),
                markerSources: [...area.markerSources]
            })),
            collectionPriority: draft.collectionPriority
        };
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
        const ruleIds = resourceList(resources, "categoryRules")
            .map((rule) => String(rule?.id ?? ""));
        for (const area of areas) {
            const zoneId = String(area?.categoryZoneId ?? "").trim();
            const zone = resourceList(resources, "categoryZones")
                .find((value) => String(value?.id ?? "") === zoneId);
            if (!zone) {
                errors.push("分类选区引用的分类区不存在，请刷新后重试");
                continue;
            }
            if (!zoneIds.add(zoneId)) {
                errors.push("同一个分类选区不能重复添加");
            }
            const ruleId = String(area?.ruleId ?? "").trim();
            if (!ruleIds.includes(ruleId)) {
                errors.push("所选分类方法不存在，请刷新后重试");
            }
            const sources = area?.markerSources;
            if (!Array.isArray(sources) || sources.length === 0) {
                errors.push("每个分类选区都需至少选择一种识别方式");
            } else if (sources.some(
                (source) => !MARKER_SOURCES.includes(source)
            )) {
                errors.push("分类物识别方式包含未知选项");
            } else if (ruleIds.includes(ruleId)
                    && !ruleSupportsSources(ruleId, sources)) {
                errors.push("分类方法与所选识别方式不兼容");
            }
        }
    }

    window.StowlingAutoClassifyTaskModel = Object.freeze({
        defaultMarkerSources,
        draftFromTask,
        newArea,
        newDraft,
        payload,
        ruleSupportsSources,
        validateDraft
    });
})();

