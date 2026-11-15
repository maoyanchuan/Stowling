(() => {
    "use strict";

    /**
     * @param {unknown} value
     * @returns {number}
     */
    function regionNumber(value) {
        const number = Number(value);
        return Number.isInteger(number) ? number : 0;
    }

    /**
     * @param {object} value
     * @returns {object}
     */
    function normalizeRegion(value) {
        return {
            id: String(value?.id || ""),
            name: String(value?.name || "").trim(),
            firstX: regionNumber(value?.firstX),
            firstY: regionNumber(value?.firstY),
            firstZ: regionNumber(value?.firstZ),
            secondX: regionNumber(value?.secondX),
            secondY: regionNumber(value?.secondY),
            secondZ: regionNumber(value?.secondZ)
        };
    }

    /**
     * @param {object} response
     * @returns {{revision: string, regions: Array<object>}}
     */
    function normalizeRegions(response) {
        return {
            revision: String(response?.revision || ""),
            regions: Array.isArray(response?.regions)
                ? response.regions.map(normalizeRegion)
                : []
        };
    }

    window.StowlingRegionModel = Object.freeze({
        normalizeRegion,
        normalizeRegions
    });
})();
