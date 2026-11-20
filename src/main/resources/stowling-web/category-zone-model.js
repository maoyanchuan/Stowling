(() => {
    "use strict";

    /**
     * @param {object} value
     * @returns {object}
     */
    function normalizeBinding(value) {
        const marker = value?.markerArea || {};
        const storage = value?.storageArea || {};
        return {
            markerFirstX: Number(marker.firstX ?? 0),
            markerFirstY: Number(marker.firstY ?? 64),
            markerFirstZ: Number(marker.firstZ ?? 0),
            markerSecondX: Number(marker.secondX ?? 0),
            markerSecondY: Number(marker.secondY ?? 64),
            markerSecondZ: Number(marker.secondZ ?? 0),
            storageFirstX: Number(storage.firstX ?? 0),
            storageFirstY: Number(storage.firstY ?? 64),
            storageFirstZ: Number(storage.firstZ ?? 0),
            storageSecondX: Number(storage.secondX ?? 0),
            storageSecondY: Number(storage.secondY ?? 64),
            storageSecondZ: Number(storage.secondZ ?? 0),
            standingArea: value?.standingArea ? normalizeArea(value.standingArea) : null,
            storagePolicy: normalizeStoragePolicy(value?.storagePolicy),
            ruleId: String(value?.ruleId || "DIRECT_BLOCK_ID"),
            wholeStorage: Boolean(value?.wholeStorage),
            ignoredMarkerBlockIds: Array.isArray(value?.ignoredMarkerBlockIds)
                ? value.ignoredMarkerBlockIds
                    .map((blockId) => String(blockId).trim())
                    .filter(Boolean)
                : [],
            containerOverrides: Array.isArray(value?.containerOverrides)
                ? value.containerOverrides.map(normalizeContainerOverride)
                : []
        };
    }

    /**
     * @param {object} value
     * @returns {object}
     */
    function normalizeStoragePolicy(value) {
        return {
            mode: String(value?.mode || "NORMAL"),
            loosePercent: Number(value?.loosePercent ?? 50),
            allowPartialBoxes: Boolean(value?.allowPartialBoxes),
            refillLoose: Boolean(value?.refillLoose),
            ...(value?.looseSlots != null ? {looseSlots: Number(value.looseSlots)} : {}),
            ...(value?.partialBoxDestinationRef
                ? {partialBoxDestinationRef: String(value.partialBoxDestinationRef)} : {}),
            packingArea: value?.packingArea ? normalizeArea(value.packingArea) : null
        };
    }

    /** @param {object} value @returns {object} */
    function normalizeArea(value) {
        const result = {};
        for (const corner of ["first", "second"]) {
            for (const axis of ["X", "Y", "Z"]) {
                result[`${corner}${axis}`] = Number(value[`${corner}${axis}`]);
            }
        }
        return result;
    }

    /**
     * @param {object} value
     * @returns {object}
     */
    function normalizeContainerOverride(value) {
        const position = value?.storagePosition || {};
        return {
            storageX: Number(position.x ?? value?.storageX ?? 0),
            storageY: Number(position.y ?? value?.storageY ?? 64),
            storageZ: Number(position.z ?? value?.storageZ ?? 0),
            mode: String(value?.mode || "ADD"),
            itemIds: Array.isArray(value?.itemIds)
                ? value.itemIds
                    .map((itemId) => String(itemId).trim())
                    .filter(Boolean)
                : []
        };
    }

    /**
     * @param {object} value
     * @returns {object | null}
     */
    function normalizeExpansion(value) {
        if (!value) {
            return null;
        }
        const area = value.area || {};
        return {
            areaFirstX: Number(area.firstX ?? 0),
            areaFirstY: Number(area.firstY ?? 64),
            areaFirstZ: Number(area.firstZ ?? 0),
            areaSecondX: Number(area.secondX ?? 0),
            areaSecondY: Number(area.secondY ?? 64),
            areaSecondZ: Number(area.secondZ ?? 0),
            direction: String(value.direction || "AUTO"),
            step: Number(value.step ?? 1),
            templateCount: Number(value.templateCount ?? 1)
        };
    }

    /**
     * @param {object} response
     * @returns {object}
     */
    function normalizeCategoryZones(response) {
        const values = Array.isArray(response?.categoryZones)
            ? response.categoryZones
            : [];
        return {
            revision: String(response?.revision || ""),
            categoryZones: values.map((value) => ({
                id: String(value?.id || ""),
                name: String(value?.name || ""),
                bindings: Array.isArray(value?.bindings)
                    ? value.bindings.map(normalizeBinding)
                    : [],
                expansion: normalizeExpansion(value?.expansion)
            }))
        };
    }

    /**
     * @param {object} zone
     * @returns {object}
     */
    function payload(zone) {
        if (zone.expansion?.direction === "AUTO") {
            zone = { ...zone, ...inferExpansion(zone.bindings, zone.expansion) };
        }
        const result = {
            name: String(zone.name || "").trim(),
            bindings: zone.bindings.map((binding) => ({
                markerFirstX: Number(binding.markerFirstX),
                markerFirstY: Number(binding.markerFirstY),
                markerFirstZ: Number(binding.markerFirstZ),
                markerSecondX: Number(binding.markerSecondX),
                markerSecondY: Number(binding.markerSecondY),
                markerSecondZ: Number(binding.markerSecondZ),
                storageFirstX: Number(binding.storageFirstX),
                storageFirstY: Number(binding.storageFirstY),
                storageFirstZ: Number(binding.storageFirstZ),
                storageSecondX: Number(binding.storageSecondX),
                storageSecondY: Number(binding.storageSecondY),
                storageSecondZ: Number(binding.storageSecondZ),
                standingArea: binding.standingArea ? normalizeArea(binding.standingArea) : null,
                storagePolicy: normalizeStoragePolicy(binding.storagePolicy),
                ruleId: String(binding.ruleId),
                wholeStorage: Boolean(binding.wholeStorage),
                ignoredMarkerBlockIds: binding.ignoredMarkerBlockIds,
                containerOverrides: binding.containerOverrides.map(
                    (override) => ({
                        storageX: Number(override.storageX),
                        storageY: Number(override.storageY),
                        storageZ: Number(override.storageZ),
                        mode: String(override.mode),
                        itemIds: override.itemIds
                    })
                )
            }))
        };
        if (zone.expansion) {
            result.expansion = {
                firstX: Number(zone.expansion.areaFirstX),
                firstY: Number(zone.expansion.areaFirstY),
                firstZ: Number(zone.expansion.areaFirstZ),
                secondX: Number(zone.expansion.areaSecondX),
                secondY: Number(zone.expansion.areaSecondY),
                secondZ: Number(zone.expansion.areaSecondZ),
                direction: String(zone.expansion.direction),
                step: Number(zone.expansion.step)
            };
        }
        return result;
    }

    /**
     * @param {Array<object>} bindings
     * @param {object} expansion
     * @returns {{bindings: Array<object>, expansion: object, count: number, collapsed: boolean}}
     */
    function inferExpansion(bindings, expansion) {
        if (bindings.length === 0) {
            throw new RangeError("请先填写单片母版");
        }
        const limits = {};
        for (const axis of ["X", "Y", "Z"]) {
            const first = Number(expansion[`areaFirst${axis}`]);
            const second = Number(expansion[`areaSecond${axis}`]);
            requireCoordinate(first);
            requireCoordinate(second);
            limits[axis] = [Math.min(first, second), Math.max(first, second)];
            for (const binding of bindings) {
                for (const kind of ["marker", "storage"]) {
                    const bounds = bindingBounds(binding, kind, axis);
                    if (bounds[0] < limits[axis][0] || bounds[1] > limits[axis][1]) {
                        throw new RangeError("单片母版必须完整位于总选区内");
                    }
                }
            }
        }
        if (bindings.some((binding) => binding.wholeStorage
                || binding.ruleId === "UNSTACKABLE_ITEMS")) {
            return inferWholeStorageExpansion(bindings, expansion, limits);
        }
        const candidates = ["X", "Z"].filter((axis) => {
            const expected = bindingBounds(bindings[0], "marker", axis);
            return bindings.every((binding) => ["marker", "storage"].every((kind) => {
                const bounds = bindingBounds(binding, kind, axis);
                return bounds[0] === expected[0] && bounds[1] === expected[1];
            }));
        }).sort((left, right) => axisLength(limits[right]) - axisLength(limits[left]));
        if (candidates.length === 0 || (candidates.length > 1
                && axisLength(limits[candidates[0]]) === axisLength(limits[candidates[1]]))) {
            throw new RangeError("无法唯一识别拓展方向，请手动选择方向和步长");
        }
        const axis = candidates[0];
        const count = axisLength(limits[axis]);
        if (count > 256) {
            throw new RangeError("自动铺满最多生成 256 个单片");
        }
        const [minimum, maximum] = bindingBounds(bindings[0], "marker", axis);
        const [lower, upper] = limits[axis];
        const positive = minimum === lower && maximum < upper
            ? true
            : maximum === upper && minimum > lower
                ? false
                : Number(expansion[`areaFirst${axis}`]) <= Number(
                    expansion[`areaSecond${axis}`]
                );
        const anchor = positive ? lower : upper;
        const collapsed = maximum > minimum;
        const templates = bindings.map((binding) => {
            if (collapsed && binding.containerOverrides.length > 0) {
                throw new RangeError("整排母版包含容器例外，请保留手动模式或先填写一片母版");
            }
            const result = { ...binding };
            if (binding.standingArea) {
                const standing = normalizeArea(binding.standingArea);
                for (const value of Object.values(standing)) {
                    requireCoordinate(value);
                }
                const first = standing[`first${axis}`];
                const second = standing[`second${axis}`];
                if (collapsed && Math.abs(second - first) === maximum - minimum) {
                    standing[`first${axis}`] = anchor
                        + first - (first <= second ? minimum : maximum);
                    standing[`second${axis}`] = anchor
                        + second - (first <= second ? maximum : minimum);
                } else {
                    standing[`first${axis}`] += anchor - minimum;
                    standing[`second${axis}`] += anchor - minimum;
                }
                result.standingArea = standing;
            }
            if (binding.storagePolicy?.packingArea) {
                const area = normalizeArea(binding.storagePolicy.packingArea);
                for (const corner of ["first", "second"]) {
                    area[`${corner}${axis}`] += anchor - minimum;
                }
                result.storagePolicy = {...binding.storagePolicy, packingArea: area};
            }
            for (const kind of ["marker", "storage"]) {
                result[`${kind}First${axis}`] = anchor;
                result[`${kind}Second${axis}`] = anchor;
            }
            result.containerOverrides = binding.containerOverrides.map((override) => ({
                ...override,
                [`storage${axis}`]: Number(override[`storage${axis}`]) + anchor - minimum
            }));
            return result;
        });
        return {
            bindings: templates,
            expansion: {
                ...expansion,
                direction: `${axis}_${positive ? "POSITIVE" : "NEGATIVE"}`,
                step: 1,
                templateCount: templates.length
            },
            count,
            collapsed
        };
    }

    /**
     * @param {Array<object>} bindings
     * @param {object} expansion
     * @param {object} limits
     * @returns {object}
     */
    function inferWholeStorageExpansion(bindings, expansion, limits) {
        const footprints = {};
        for (const axis of ["X", "Z"]) {
            const bounds = bindings.flatMap((binding) => ["marker", "storage"]
                .flatMap((kind) => bindingBounds(binding, kind, axis)));
            footprints[axis] = [Math.min(...bounds), Math.max(...bounds)];
        }
        const candidates = ["X", "Z"].map((axis) => ({
            axis,
            step: axisLength(footprints[axis]),
            count: Math.floor(axisLength(limits[axis]) / axisLength(footprints[axis]))
        })).sort((left, right) => right.count - left.count);
        if (candidates[0].count === candidates[1].count) {
            throw new RangeError("无法唯一识别拓展方向，请手动选择方向和步长");
        }
        const {axis, step, count} = candidates[0];
        if (count > 256) {
            throw new RangeError("自动铺满最多生成 256 个单片");
        }
        const [minimum, maximum] = footprints[axis];
        const [lower, upper] = limits[axis];
        const positive = minimum === lower && maximum < upper ? true
            : maximum === upper && minimum > lower ? false
                : Number(expansion[`areaFirst${axis}`]) <= Number(
                    expansion[`areaSecond${axis}`]);
        const delta = positive ? lower - minimum : upper - maximum;
        const templates = bindings.map((binding) => {
            const result = {...binding};
            for (const kind of ["marker", "storage"]) {
                for (const corner of ["First", "Second"]) {
                    result[`${kind}${corner}${axis}`] = Number(
                        binding[`${kind}${corner}${axis}`]) + delta;
                }
            }
            for (const kind of ["standingArea", "packingArea"]) {
                const source = kind === "standingArea" ? binding.standingArea
                    : binding.storagePolicy?.packingArea;
                if (!source) continue;
                const area = normalizeArea(source);
                for (const corner of ["first", "second"]) {
                    area[`${corner}${axis}`] += delta;
                }
                if (kind === "standingArea") result.standingArea = area;
                else result.storagePolicy = {...binding.storagePolicy, packingArea: area};
            }
            result.containerOverrides = binding.containerOverrides.map((override) => ({
                ...override,
                [`storage${axis}`]: Number(override[`storage${axis}`]) + delta
            }));
            return result;
        });
        return {bindings: templates, expansion: {...expansion,
            direction: `${axis}_${positive ? "POSITIVE" : "NEGATIVE"}`,
            step, templateCount: templates.length}, count, collapsed: false};
    }

    /**
     * @param {object} binding
     * @param {string} kind
     * @param {string} axis
     * @returns {Array<number>}
     */
    function bindingBounds(binding, kind, axis) {
        const first = Number(binding[`${kind}First${axis}`]);
        const second = Number(binding[`${kind}Second${axis}`]);
        requireCoordinate(first);
        requireCoordinate(second);
        return [Math.min(first, second), Math.max(first, second)];
    }

    /**
     * @param {Array<number>} bounds
     * @returns {number}
     */
    function axisLength(bounds) {
        return bounds[1] - bounds[0] + 1;
    }

    /**
     * @param {number} value
     * @returns {void}
     */
    function requireCoordinate(value) {
        if (!Number.isInteger(value) || value < -2147483648 || value > 2147483647) {
            throw new RangeError("请填写有效的整数坐标");
        }
    }

    window.StowlingCategoryZoneModel = Object.freeze({
        normalizeBinding,
        normalizeContainerOverride,
        normalizeExpansion,
        normalizeCategoryZones,
        payload,
        inferExpansion
    });
})();
