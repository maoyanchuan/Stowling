(() => {
    "use strict";

    const Zone = window.StowlingCategoryZoneModel;
    const types = {point: "coordinate", region: "region",
        "category-zone": "category-zone", warehouse: "warehouse"};
    const routes = {point: "coordinates", region: "regions",
        "category-zone": "category-zones", warehouse: "warehouses"};

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
            const options = categoryOptions(raw);
            values.push({id: raw.id, name: raw.name, kind: "category-zone",
                overallRegion: raw.expansion ? region(raw.expansion.area)
                    : enclosingRegion(raw.bindings),
                bindings: raw.bindings.slice(0, count).map((binding, index) => ({
                    id: `${raw.id}-${index}`, markerRegion: region(binding.markerArea),
                    storageRegion: region(binding.storageArea),
                    original: copy(normalized.bindings[index])
                })),
                categoryOptions: options, originalOptions: copy(options),
                originalExpansion: normalized.expansion});
        }
        for (const warehouse of directory.catalog?.warehouses || []) {
            values.push({...copy(warehouse), kind: "warehouse"});
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
        if (value.kind === "warehouse") return {name, members: value.members};
        const options = value.categoryOptions;
        const baseline = value.originalOptions || {};
        const changed = key => !(key in baseline) || options[key] !== baseline[key];
        const bindings = value.bindings.map(binding => {
            const original = binding.original || Zone.normalizeBinding({});
            const result = copy(original);
            for (const [kind, bounds] of [["marker", binding.markerRegion],
                ["storage", binding.storageRegion]]) {
                for (const [key, number] of Object.entries(area(bounds))) {
                    result[kind + key[0].toUpperCase() + key.slice(1)] = number;
                }
            }
            if (changed("recognitionMode")) {
                result.ruleId = options.recognitionMode === "container-item"
                    ? "MARKER_CONTAINER_CONTENTS" : options.recognitionMode === "unstackable"
                        ? "UNSTACKABLE_ITEMS" : "DIRECT_BLOCK_ID";
                if (result.ruleId !== "MARKER_CONTAINER_CONTENTS") result.wholeStorage = false;
            }
            if (changed("storageMethod")) {
                result.storagePolicy.mode = {bulk: "NORMAL", mixed: "MIXED", box: "BOX_ONLY"}[
                    options.storageMethod];
            }
            if (changed("noCacheLocation") || changed("cacheDestination")) {
                if (options.noCacheLocation === "specified" && !options.cacheDestination) {
                    throw new Error("请选择指定缓存位置。");
                }
                result.storagePolicy.partialBoxDestinationRef =
                    options.noCacheLocation === "specified" ? options.cacheDestination : null;
            }
            if (changed("ignoreSupportBlock") || changed("supportBlock")) {
                result.ignoredMarkerBlockIds = options.ignoreSupportBlock
                    ? options.supportBlock.split(/[\s,，;；]+/).filter(Boolean) : [];
                if (options.ignoreSupportBlock && !result.ignoredMarkerBlockIds.length) {
                    throw new Error("请填写要忽略的支撑方块 ID。");
                }
            }
            return result;
        });
        let expansion = null;
        if (options.scopeMode === "single") {
            const bounds = area(value.overallRegion);
            expansion = {...value.originalExpansion,
                areaFirstX: bounds.firstX, areaFirstY: bounds.firstY, areaFirstZ: bounds.firstZ,
                areaSecondX: bounds.secondX, areaSecondY: bounds.secondY,
                areaSecondZ: bounds.secondZ,
                direction: options.expansionDirection || "AUTO",
                step: Number(options.expansionStep || 1)};
        }
        return Zone.payload({name, bindings, expansion});
    }

    window.StowlingCoordinateEditorModel = Object.freeze({resources, payload, ref, route, copy});
})();
