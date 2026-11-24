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
            noCacheLocation: policy.partialBoxDestinationRef ? "specified" : "inside-box",
            cacheDestination: policy.partialBoxDestinationRef || "",
            ignoreSupportBlock: Boolean(binding.ignoredMarkerBlockIds?.length),
            supportBlock: (binding.ignoredMarkerBlockIds || []).join(", "),
            expansionDirection: zone.expansion?.direction || "AUTO",
            expansionStep: zone.expansion?.step || 1
        };
    }

    /** @param {object} value @returns {object} */
    function optionsPayload(value) {
        return {scopeMode: value.scopeMode || "single",
            recognitionMode: value.recognitionMode || "marker",
            storageMethod: value.storageMethod || "bulk",
            noCacheLocation: value.noCacheLocation || "inside-box",
            cacheDestination: value.cacheDestination || "",
            ignoreSupportBlock: Boolean(value.ignoreSupportBlock),
            supportBlock: value.supportBlock || "",
            specifyStanding: value.specifyStanding ?? null,
            standingPosition: value.standingPosition ? copy(value.standingPosition) : null};
    }

    /** @param {object} binding @param {object} globalOptions @param {object} zone
     * @returns {object} */
    function bindingSettings(binding, globalOptions, zone) {
        if (binding.categoryOptions) {
            return {optionsEnabled: Boolean(binding.optionsEnabled),
                categoryOptions: copy(binding.categoryOptions)};
        }
        if (zone.categoryOptions) return {optionsEnabled: Boolean(binding.optionsEnabled)};
        const local = categoryOptions({bindings: [binding], expansion: zone.expansion});
        const different = JSON.stringify(optionsPayload(local))
            !== JSON.stringify(optionsPayload(globalOptions));
        return {optionsEnabled: different, categoryOptions: different ? optionsPayload(local) : null};
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
            const count = raw.expansion?.templateCount || raw.bindings.length;
            const options = {...categoryOptions(raw), ...raw.categoryOptions};
            values.push({id: raw.id, name: raw.name, kind: "category-zone",
                overallRegion: raw.overallArea ? region(raw.overallArea)
                    : raw.expansion ? region(raw.expansion.area)
                    : enclosingRegion(raw.bindings),
                bindings: raw.bindings.slice(0, count).map((binding, index) => ({
                    id: `${raw.id}-${index}`, name: String(binding.name || ""),
                    markerRegion: region(binding.markerArea),
                    storageRegion: region(binding.storageArea),
                    original: copy(normalized.bindings[index]),
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
        const globalOptions = value.categoryOptions;
        const bindings = value.bindings.map(binding => {
            const options = binding.optionsEnabled ? binding.categoryOptions || globalOptions
                : globalOptions;
            const original = binding.original || Zone.normalizeBinding({});
            const result = copy(original);
            result.name = String(binding.name ?? original.name ?? "").trim();
            result.optionsEnabled = Boolean(binding.optionsEnabled);
            result.categoryOptions = binding.categoryOptions ? optionsPayload(binding.categoryOptions)
                : null;
            for (const [kind, bounds] of [["marker", binding.markerRegion],
                ["storage", binding.storageRegion]]) {
                for (const [key, number] of Object.entries(area(bounds))) {
                    result[kind + key[0].toUpperCase() + key.slice(1)] = number;
                }
            }
            result.ruleId = options.recognitionMode === "container-item"
                ? "MARKER_CONTAINER_CONTENTS" : options.recognitionMode === "unstackable"
                    ? "UNSTACKABLE_ITEMS" : "DIRECT_BLOCK_ID";
            if (result.ruleId !== "MARKER_CONTAINER_CONTENTS") result.wholeStorage = false;
            result.storagePolicy.mode = {bulk: "NORMAL", mixed: "MIXED", box: "BOX_ONLY"}[
                options.storageMethod];
            if (options.noCacheLocation === "specified" && !options.cacheDestination) {
                throw new Error("请选择指定缓存位置。");
            }
            result.storagePolicy.partialBoxDestinationRef =
                options.noCacheLocation === "specified" ? options.cacheDestination : null;
            result.ignoredMarkerBlockIds = options.ignoreSupportBlock
                ? options.supportBlock.split(/[\s,，;；]+/).filter(Boolean) : [];
            if (options.ignoreSupportBlock && !result.ignoredMarkerBlockIds.length) {
                throw new Error("请填写要忽略的支撑方块 ID。");
            }
            if (options.specifyStanding != null) {
                result.standingArea = null;
                if (options.specifyStanding) {
                    const position = options.standingPosition;
                    if (!position || ["x", "y", "z"].some(axis =>
                        !Number.isInteger(position[axis]) || position[axis] < -2147483648
                            || position[axis] > 2147483647)) {
                        throw new Error("请填写站立位置的 X、Y、Z 整数坐标。");
                    }
                    result.standingArea = area({first: position, second: position});
                }
            }
            return result;
        });
        let expansion = null;
        const hasExpansion = value.bindings.some(binding => (binding.optionsEnabled
            ? binding.categoryOptions?.scopeMode || globalOptions.scopeMode
            : globalOptions.scopeMode) === "single");
        if (hasExpansion) {
            const bounds = area(value.overallRegion);
            expansion = {...value.originalExpansion,
                areaFirstX: bounds.firstX, areaFirstY: bounds.firstY, areaFirstZ: bounds.firstZ,
                areaSecondX: bounds.secondX, areaSecondY: bounds.secondY,
                areaSecondZ: bounds.secondZ,
                direction: globalOptions.expansionDirection || "AUTO",
                step: Number(globalOptions.expansionStep || 1)};
        }
        return Zone.payload({name, bindings, expansion, overallArea: area(value.overallRegion),
            categoryOptions: optionsPayload(globalOptions)});
    }

    window.StowlingCoordinateEditorModel = Object.freeze({resources, payload, ref, route, copy});
})();
