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
                : []
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
                    : []
            }))
        };
    }

    /**
     * @param {object} zone
     * @returns {object}
     */
    function payload(zone) {
        return {
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
                ignoredMarkerBlockIds: binding.ignoredMarkerBlockIds
            }))
        };
    }

    window.StowlingCategoryZoneModel = Object.freeze({
        normalizeBinding,
        normalizeCategoryZones,
        payload
    });
})();
