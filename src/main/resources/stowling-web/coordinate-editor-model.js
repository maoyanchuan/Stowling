(() => {
    "use strict";

    const Zone = window.StowlingCategoryZoneModel;
    const types = {point: "coordinate", region: "region",
        "category-zone": "category-zone"};
    const routes = {point: "coordinates", region: "regions",
        "category-zone": "category-zones"};

    /** @param {object} value @returns {object} */
    function copy(value) {
        return JSON.parse(JSON.stringify(value));
    }

    /** @param {object} area @returns {object} */
    function region(area) {
        return {
            first: {x: area.firstX, y: area.firstY, z: area.firstZ},
            second: {x: area.secondX, y: area.secondY, z: area.secondZ}
        };
    }

    /** @param {object} value @returns {object} */
    function area(value) {
        return {firstX: value.first.x, firstY: value.first.y, firstZ: value.first.z,
            secondX: value.second.x, secondY: value.second.y, secondZ: value.second.z};
    }

    /** @param {object[]} bindings @returns {object} */
    function enclosingRegion(bindings) {
        const areas = bindings.flatMap(binding => [binding.markerArea, binding.storageArea]);
        if (!areas.length) return {first: {x: 0, y: 64, z: 0}, second: {x: 0, y: 64, z: 0}};
        const first = {};
        const second = {};
        for (const axis of ["X", "Y", "Z"]) {
            const values = areas.flatMap(bounds => [
                bounds[`first${axis}`], bounds[`second${axis}`]
            ]);
            first[axis.toLowerCase()] = Math.min(...values);
            second[axis.toLowerCase()] = Math.max(...values);
        }
        return {first, second};
    }

    /** @param {object} zone @returns {object} */
    function categoryOptions(zone) {
        const binding = zone.bindings[0] || {};
        const policy = binding.storagePolicy || {};
        return {
            scopeMode: zone.expansion ? "single" : "selection",
            recognitionMode: binding.ruleId === "MARKER_CONTAINER_CONTENTS"
                ? "container-item" : binding.ruleId === "UNSTACKABLE_ITEMS"
                    ? "unstackable" : "marker",
            storageMethod: {NORMAL: "bulk", MIXED: "mixed", BOX_ONLY: "box"}[policy.mode] || "bulk",
            bulkStacks: policy.looseSlots ?? Math.floor(54 * (policy.loosePercent ?? 50) / 100),
            noCacheLocation: policy.partialBoxDestinationRef ? "specified" : "inside-box",
            cacheDestination: policy.partialBoxDestinationRef || "",
            ignoreSupportBlock: Boolean(binding.ignoredMarkerBlockIds?.length),
            supportBlock: (binding.ignoredMarkerBlockIds || []).join("\n"),
            ignoreMarkerPositions: false,
            ignoredMarkerPositions: [],
            ignoreRepeatedBlocks: false,
            multiItemBindingsEnabled: false,
            multiItemBindings: [],
            expansionDirection: zone.expansion?.direction || "AUTO",
            expansionStep: zone.expansion?.step || 1
        };
    }

    /** @param {object} value @returns {object} */
    function optionsPayload(value) {
        const bulkStacks = value.bulkStacks === undefined ? 27 : value.bulkStacks;
        if (!Number.isInteger(bulkStacks) || bulkStacks < 0 || bulkStacks > 54) {
            throw new Error("散货占比请填写 0 到 54 之间的整数组数。");
        }
        return {scopeMode: value.scopeMode || "single",
            recognitionMode: value.recognitionMode || "marker",
            unstackableOverflowRef: String(value.unstackableOverflowRef || ""),
            storageMethod: value.storageMethod || "bulk",
            bulkStacks,
            noCacheLocation: value.noCacheLocation || "inside-box",
            cacheDestination: value.cacheDestination || "",
            ignoreSupportBlock: Boolean(value.ignoreSupportBlock),
            supportBlock: value.supportBlock || "",
            ignoreMarkerPositions: Boolean(value.ignoreMarkerPositions),
            ignoredMarkerPositions: copy(value.ignoredMarkerPositions || []),
            ignoreRepeatedBlocks: Boolean(value.ignoreRepeatedBlocks),
            multiItemBindingsEnabled: Boolean(value.multiItemBindingsEnabled),
            multiItemBindings: copy(value.multiItemBindings || [])};
    }

    /** @param {object} options @returns {void} */
    function validateRules(options) {
        /** @param {object} position @returns {boolean} */
        const validPosition = position => position && ["x", "y", "z"].every(axis =>
            Number.isInteger(position[axis]) && position[axis] >= -2147483648
                && position[axis] <= 2147483647);
        const positions = options.ignoredMarkerPositions || [];
        if (options.ignoreMarkerPositions
                && (!positions.length || !positions.every(validPosition))) {
            throw new Error("请添加排除位置，并填写完整的 X、Y、Z 整数坐标。");
        }
        if (!options.multiItemBindingsEnabled) return;
        const rules = options.multiItemBindings || [];
        if (!rules.length) throw new Error("请至少添加一条物品绑定规则。");
        const idPattern = /^[a-z0-9_.-]+:[a-z0-9_./-]+$/;
        for (const rule of rules) {
            const ids = String(rule.itemIds || "").trim().split(/\s+/).filter(Boolean);
            if (!ids.length || !ids.every(id => idPattern.test(id))) {
                throw new Error("物品绑定请每行填写一个完整物品 ID。");
            }
            if (!["marker-id", "marker-position", "container"].includes(rule.targetType)) {
                throw new Error("物品绑定目标类型无效。");
            }
            if (rule.targetType === "marker-id") {
                if (!idPattern.test(String(rule.markerId || "").trim())) {
                    throw new Error("请填写完整的识别物 ID。");
                }
            } else if (!validPosition(rule.position)) {
                throw new Error("请填写绑定目标的 X、Y、Z 整数坐标。");
            }
            if (rule.targetType === "marker-position" && options.ignoreMarkerPositions
                    && positions.some(point => ["x", "y", "z"].every(axis =>
                        point[axis] === rule.position[axis]))) {
                throw new Error("物品绑定的目标标记已被坐标排除。");
            }
        }
    }

    /** @param {object} binding @param {object} globalOptions @param {object} zone
     * @returns {object} */
    function bindingSettings(binding, globalOptions, zone) {
        if (binding.categoryOptions) {
            return {optionsEnabled: Boolean(binding.optionsEnabled),
                categoryOptions: {...optionsPayload(binding.categoryOptions),
                    bulkStacks: binding.categoryOptions.bulkStacks
                        ?? categoryOptions({bindings: [binding]}).bulkStacks}};
        }
        if (zone.categoryOptions) return {optionsEnabled: Boolean(binding.optionsEnabled)};
        const local = categoryOptions({bindings: [binding], expansion: zone.expansion});
        const different = JSON.stringify(optionsPayload(local))
            !== JSON.stringify(optionsPayload(globalOptions));
        return {optionsEnabled: different, categoryOptions: different ? optionsPayload(local) : null};
    }

    /** @param {object} binding @returns {object} */
    function standingDraft(binding) {
        if (binding.standingPlan) return copy(binding.standingPlan);
        return {enabled: false, points: []};
    }

    /** @param {object} bounds @param {object} child @returns {boolean} */
    function contains(bounds, child) {
        return ["X", "Y", "Z"].every(axis => {
            const values = [child[`first${axis}`], child[`second${axis}`]];
            const parent = [bounds.first[axis.toLowerCase()], bounds.second[axis.toLowerCase()]];
            return values.every(Number.isInteger) && Math.min(...values) >= Math.min(...parent)
                && Math.max(...values) <= Math.max(...parent);
        });
    }

    /** @param {object} plan @param {object} markers @param {object} storage @returns {void} */
    function validateStanding(plan, markers, storage) {
        if (!plan.enabled) return;
        if (!plan.points.length) throw new Error("请至少添加一个站立位置。");
        const used = [];
        for (const point of plan.points) {
            if (!point.position || ["x", "y", "z"].some(axis =>
                !Number.isInteger(point.position[axis]) || point.position[axis] < -2147483648
                    || point.position[axis] > (axis === "y" ? 2147483646 : 2147483647))) {
                throw new Error("请填写站立脚下方块的 X、Y、Z 整数坐标。");
            }
            const mapped = point.subregionsEnabled ?? Boolean(point.markerArea || point.storageArea);
            if (mapped && (!point.markerArea || !point.storageArea
                    || !contains(markers, point.markerArea) || !contains(storage, point.storageArea))) {
                throw new Error("子识别区与子储存区必须填写完整，并位于本绑定组的对应范围内。");
            }
            const target = mapped ? point.storageArea : area(storage);
            if (used.some(previous => ["X", "Y", "Z"].every(axis =>
                Math.min(previous[`first${axis}`], previous[`second${axis}`])
                    <= Math.max(target[`first${axis}`], target[`second${axis}`])
                && Math.max(previous[`first${axis}`], previous[`second${axis}`])
                    >= Math.min(target[`first${axis}`], target[`second${axis}`])))) {
                throw new Error("多个站位的子储存区不能重叠，请划分各自负责范围。");
            }
            used.push(target);
        }
    }

    /** @param {object} directory @returns {object[]} */
    function resources(directory) {
        const values = [];
        for (const point of directory.coordinates || []) {
            values.push({...copy(point), kind: "point"});
        }
        for (const bounds of directory.regions || []) {
            values.push({...copy(bounds), kind: "region", region: region(bounds)});
        }
        for (const raw of directory.categoryZones || []) {
            const normalized = Zone.normalizeCategoryZones({categoryZones: [raw]})
                .categoryZones[0];
            const source = raw.scan?.templates || raw.bindings;
            const count = raw.scan ? source.length : raw.expansion?.templateCount || source.length;
            const options = {...categoryOptions(raw), ...raw.categoryOptions};
            options.bulkStacks ??= categoryOptions(raw).bulkStacks;
            values.push({id: raw.id, name: raw.name, kind: "category-zone",
                overallRegion: raw.overallArea ? region(raw.overallArea)
                    : raw.expansion ? region(raw.expansion.area)
                    : enclosingRegion(raw.bindings),
                setupMode: raw.scan ? "scan" : "manual",
                scan: raw.scan ? copy(raw.scan) : null,
                bindings: source.slice(0, count).map((binding, index) => ({
                    id: raw.scan ? binding.id : `${raw.id}-${index}`,
                    name: String(binding.name || ""),
                    markerRegion: region(binding.markerArea),
                    storageRegion: region(binding.storageArea),
                    original: raw.scan ? Zone.normalizeBinding(binding)
                        : copy(normalized.bindings[index]),
                    standingPlan: standingDraft(binding),
                    ...bindingSettings(binding, options, raw)
                })),
                categoryOptions: options, originalOptions: copy(options),
                originalExpansion: normalized.expansion});
        }
        const order = directory.catalog?.order || [];
        for (const value of values) {
            value.saved = true;
            value.folderId = directory.catalog?.placements?.[ref(value)] || "";
        }
        const rank = value => {
            const index = order.indexOf(ref(value));
            return index < 0 ? order.length : index;
        };
        return values.sort((left, right) => rank(left) - rank(right));
    }

    /** @param {object} value @returns {string} */
    function ref(value) {
        return `${types[value.kind]}:${value.id}`;
    }

    /** @param {object} value @returns {string} */
    function route(value) {
        return `/v1/${routes[value.kind]}`;
    }

    /** @param {object} value @returns {object} */
    function payload(value) {
        const name = value.name.trim();
        if (!name) throw new Error("请填写名称。");
        if (value.kind === "point") {
            return {name, x: value.x, y: value.y, z: value.z,
                dimension: value.dimension || null, radius: value.radius ?? null};
        }
        if (value.kind === "region") return {name, ...area(value.region)};
        const globalOptions = {...value.categoryOptions, ...optionsPayload(value.categoryOptions)};
        const bindings = value.bindings.map(binding => {
            const options = binding.optionsEnabled ? binding.categoryOptions || globalOptions
                : globalOptions;
            const unstackable = globalOptions.recognitionMode === "unstackable";
            if (!unstackable) validateRules(options);
            const original = binding.original || Zone.normalizeBinding({});
            const result = copy(original);
            result.name = String(binding.name ?? original.name ?? "").trim();
            result.optionsEnabled = Boolean(binding.optionsEnabled);
            result.standingPlan = copy(binding.standingPlan || standingDraft(original));
            validateStanding(result.standingPlan, binding.markerRegion, binding.storageRegion);
            result.categoryOptions = binding.categoryOptions ? optionsPayload(binding.categoryOptions)
                : null;
            for (const [kind, bounds] of [["marker", binding.markerRegion],
                ["storage", binding.storageRegion]]) {
                for (const [key, number] of Object.entries(area(bounds))) {
                    result[kind + key[0].toUpperCase() + key.slice(1)] = number;
                }
            }
            if (!unstackable && options.recognitionMode === "unstackable") {
                throw new Error("不可堆叠分类需要在整个分类区选择。");
            }
            result.ruleId = unstackable ? "UNSTACKABLE_ITEMS"
                : options.recognitionMode === "container-item"
                    ? "MARKER_CONTAINER_CONTENTS" : "DIRECT_BLOCK_ID";
            // Rotated marker chests cannot share a column with their wider storage slice.
            const separateBounds = ["X", "Z"].every(axis => {
                const marker = [result[`markerFirst${axis}`], result[`markerSecond${axis}`]];
                const storage = [result[`storageFirst${axis}`], result[`storageSecond${axis}`]];
                return Math.min(...marker) !== Math.min(...storage)
                    || Math.max(...marker) !== Math.max(...storage);
            });
            result.wholeStorage = result.ruleId === "MARKER_CONTAINER_CONTENTS"
                && (result.wholeStorage || (options.scopeMode === "single" && separateBounds));
            result.storagePolicy.mode = {bulk: "NORMAL", mixed: "MIXED", box: "BOX_ONLY"}[
                options.storageMethod];
            result.storagePolicy.looseSlots = optionsPayload(options).bulkStacks;
            if (options.noCacheLocation === "specified" && !options.cacheDestination) {
                throw new Error("请选择指定缓存位置。");
            }
            result.storagePolicy.partialBoxDestinationRef =
                options.noCacheLocation === "specified" ? options.cacheDestination : null;
            result.ignoredMarkerBlockIds = !unstackable && options.ignoreSupportBlock
                ? options.supportBlock.split(/[\s,，;；]+/).filter(Boolean) : [];
            if (!unstackable && options.ignoreSupportBlock
                    && !result.ignoredMarkerBlockIds.length) {
                throw new Error("请填写要忽略的支撑方块 ID。");
            }
            return result;
        });
        const scanning = value.setupMode === "scan" || (!value.setupMode && Boolean(value.scan));
        if (scanning && !value.scan) throw new Error("请先扫描并确认母版。");
        if (scanning && !["x", "y", "z"].every(axis => {
            const suffix = axis.toUpperCase();
            const a = [value.scan.area[`first${suffix}`], value.scan.area[`second${suffix}`]];
            const b = [value.overallRegion.first[axis], value.overallRegion.second[axis]];
            return Math.min(...a) === Math.min(...b) && Math.max(...a) === Math.max(...b);
        })) throw new Error("总选区已改变，请重新扫描。");
        let expansion = null;
        const hasExpansion = value.bindings.some(binding => (binding.optionsEnabled
            ? binding.categoryOptions?.scopeMode || globalOptions.scopeMode
            : globalOptions.scopeMode) === "single");
        if (hasExpansion && !scanning) {
            const bounds = area(value.overallRegion);
            expansion = {...value.originalExpansion,
                areaFirstX: bounds.firstX, areaFirstY: bounds.firstY, areaFirstZ: bounds.firstZ,
                areaSecondX: bounds.secondX, areaSecondY: bounds.secondY,
                areaSecondZ: bounds.secondZ,
                direction: globalOptions.expansionDirection || "AUTO",
                step: Number(globalOptions.expansionStep || 1)};
        }
        const result = Zone.payload({name, bindings, expansion, overallArea: area(value.overallRegion),
            categoryOptions: optionsPayload(globalOptions)});
        if (scanning) {
            result.scan = {...copy(value.scan), templates: result.bindings.map((binding, index) => {
                const draft = value.bindings[index];
                const original = value.scan.templates.find(template => template.id === draft.id);
                const id = /^[0-9a-f-]{36}$/i.test(draft.id) ? draft.id : crypto.randomUUID();
                return {id, name: binding.name,
                    markerArea: area(draft.markerRegion), storageArea: area(draft.storageRegion),
                    ruleId: binding.ruleId, ignoredMarkerBlockIds: binding.ignoredMarkerBlockIds,
                    storagePolicy: binding.storagePolicy, standingArea: binding.standingArea,
                    standingPlan: binding.standingPlan, wholeStorage: false,
                    optionsEnabled: binding.optionsEnabled,
                    categoryOptions: binding.categoryOptions || null,
                    containerOverrides: binding.containerOverrides.map((override, i) => ({
                        id: original?.containerOverrides?.[i]?.id || crypto.randomUUID(),
                        storagePosition: {x: override.storageX, y: override.storageY,
                            z: override.storageZ}, mode: override.mode, itemIds: override.itemIds
                    }))};
            })};
        }
        return result;
    }

    /** @param {object} scan @returns {object[]} */
    function scanBindings(scan) {
        return scan.templates.map(binding => ({id: binding.id, name: binding.name,
            markerRegion: region(binding.markerArea), storageRegion: region(binding.storageArea),
            original: Zone.normalizeBinding(binding), standingPlan: standingDraft(binding),
            optionsEnabled: Boolean(binding.optionsEnabled),
            categoryOptions: binding.categoryOptions ? copy(binding.categoryOptions) : null}));
    }

    window.StowlingCoordinateEditorModel = Object.freeze({resources, payload, ref, route, copy,
        scanBindings});
})();
