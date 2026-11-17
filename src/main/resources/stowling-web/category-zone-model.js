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
            ruleId: String(value?.ruleId || "DIRECT_BLOCK_ID"),
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
            direction: String(value.direction || "X_POSITIVE"),
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
                ruleId: String(binding.ruleId),
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

    window.StowlingCategoryZoneModel = Object.freeze({
        normalizeBinding,
        normalizeContainerOverride,
        normalizeExpansion,
        normalizeCategoryZones,
        payload
    });
})();
