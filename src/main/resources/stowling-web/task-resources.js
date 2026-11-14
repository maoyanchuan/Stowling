(() => {
    "use strict";

    const Stowling = window.Stowling;
    const state = {
        snapshot: emptySnapshot(),
        changedHandlers: new Set()
    };

    /**
     * @returns {object}
     */
    function emptySnapshot() {
        return {
            revision: "",
            coordinatePoints: [],
            pickupRevision: "",
            pickupRegions: [],
            cacheContainers: [],
            pickupWorkflows: []
        };
    }

    /**
     * @param {object} coordinateSnapshot
     * @param {object} resourceSnapshot
     * @returns {object}
     */
    function normalizeSnapshot(coordinateSnapshot, resourceSnapshot) {
        return {
            revision: String(coordinateSnapshot?.revision || ""),
            coordinatePoints: Array.isArray(coordinateSnapshot?.coordinatePoints)
                ? Stowling.clone(coordinateSnapshot.coordinatePoints)
                : [],
            pickupRevision: String(resourceSnapshot?.revision || ""),
            pickupRegions: Array.isArray(resourceSnapshot?.pickupRegions)
                ? Stowling.clone(resourceSnapshot.pickupRegions)
                : [],
            cacheContainers: Array.isArray(resourceSnapshot?.cacheContainers)
                ? Stowling.clone(resourceSnapshot.cacheContainers)
                : [],
            pickupWorkflows: Array.isArray(resourceSnapshot?.pickupWorkflows)
                ? Stowling.clone(resourceSnapshot.pickupWorkflows)
                : []
        };
    }

    /**
     * @returns {Promise<object>}
     */
    async function load() {
        const [coordinateSnapshot, resourceSnapshot] = await Promise.all([
            Stowling.request("/v1/config/coordinate-points"),
            Stowling.request("/v1/config/task-resources")
        ]);
        state.snapshot = normalizeSnapshot(coordinateSnapshot, resourceSnapshot);
        await notifyChanged();
        return getSnapshot();
    }

    /**
     * @returns {object}
     */
    function getSnapshot() {
        return Stowling.clone(state.snapshot);
    }

    /**
     * @param {() => void | Promise<void>} handler
     * @returns {() => void}
     */
    function onChanged(handler) {
        state.changedHandlers.add(handler);
        return () => state.changedHandlers.delete(handler);
    }

    /**
     * @returns {Promise<void>}
     */
    async function notifyChanged() {
        await Promise.allSettled(
            [...state.changedHandlers].map((handler) => handler())
        );
    }

    window.StowlingResources = Object.freeze({
        getSnapshot,
        load,
        onChanged
    });
})();
