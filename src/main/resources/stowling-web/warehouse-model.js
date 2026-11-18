(() => {
    "use strict";
    /** @param {object} point @param {object} bounds @returns {boolean} */
    function contains(point, bounds) {
        return ["X", "Y", "Z"].every(axis => point[axis.toLowerCase()]
            >= Math.min(bounds[`first${axis}`], bounds[`second${axis}`])
            && point[axis.toLowerCase()] <= Math.max(bounds[`first${axis}`], bounds[`second${axis}`]));
    }
    /** @param {object} snapshot @param {object} filters @returns {Array<object>} */
    function containers(snapshot, filters = {}) {
        const area = (snapshot.areas || []).find(value => value.id === filters.areaId);
        const unique = new Map();
        for (const container of snapshot.containers || []) {
            if (filters.dimension && container.dimension !== filters.dimension) continue;
            if (area && (container.dimension !== area.dimension
                    || !container.positions.some(point => area.bounds.some(bound =>
                        contains(point, bound))))) continue;
            unique.set(container.id, container);
        }
        return Array.from(unique.values());
    }
    /** @param {Array<object>} values @param {string} query @returns {Array<object>} */
    function aggregate(values, query = "") {
        const items = new Map();
        const search = query.trim().toLowerCase();
        for (const container of values) {
            for (const entry of container.items) {
                const previous = items.get(entry.key) || {...entry, loose: 0, boxed: 0, locations: []};
                previous.loose += entry.loose;
                previous.boxed += entry.boxed;
                previous.total = previous.loose + previous.boxed;
                previous.locations.push({container, loose: entry.loose, boxed: entry.boxed});
                items.set(entry.key, previous);
            }
        }
        return Array.from(items.values()).filter(item => !search
            || `${item.name} ${item.itemId}`.toLowerCase().includes(search))
            .sort((first, second) => second.total - first.total
                || first.name.localeCompare(second.name));
    }
    globalThis.StowlingWarehouseModel = Object.freeze({contains, containers, aggregate});
})();
