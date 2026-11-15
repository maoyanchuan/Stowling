(() => {
    "use strict";

    /**
     * @param {unknown} value
     * @param {number} fallback
     * @returns {number}
     */
    function coordinateNumber(value, fallback = 0) {
        const number = Number(value);
        return Number.isInteger(number) ? number : fallback;
    }

    /**
     * @param {object} value
     * @returns {object}
     */
    function normalizeCoordinate(value) {
        const rawRadius = value?.radius;
        const radius = rawRadius === null
            || rawRadius === undefined
            || rawRadius === ""
            ? null
            : Number(rawRadius);
        return {
            id: String(value?.id || ""),
            name: String(value?.name || "").trim(),
            x: coordinateNumber(value?.x),
            y: coordinateNumber(value?.y, 64),
            z: coordinateNumber(value?.z),
            dimension: String(value?.dimension || "").trim(),
            radius: Number.isInteger(radius) ? radius : null
        };
    }

    /**
     * @param {object} response
     * @returns {{revision: string, coordinates: Array<object>}}
     */
    function normalizeCoordinates(response) {
        return {
            revision: String(response?.revision || ""),
            coordinates: Array.isArray(response?.coordinates)
                ? response.coordinates.map(normalizeCoordinate)
                : []
        };
    }

    /**
     * @param {object} value
     * @returns {string}
     */
    function coordinateLabel(value) {
        return String(value?.name || "");
    }

    window.StowlingCoordinateModel = Object.freeze({
        coordinateLabel,
        normalizeCoordinate,
        normalizeCoordinates
    });
})();
