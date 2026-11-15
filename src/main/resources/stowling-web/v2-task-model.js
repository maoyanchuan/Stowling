(() => {
    "use strict";

    const CACHE_TASK_MODULES = Object.freeze([
        "COLLECT_DROPPED_ITEMS_IN_REGION",
        "SCAN_CONTAINERS_IN_REGION",
        "DEPOSIT_ITEMS_TO_CONTAINERS"
    ]);
    const SORT_TASK_MODULES = Object.freeze([
        "SCAN_CONTAINERS_IN_REGION",
        "MATCH_CONTAINER_CATEGORIES",
        "SORT_CACHE_ITEMS"
    ]);

    /**
     * @param {object} value
     * @returns {object}
     */
    function normalizeStep(value) {
        return {
            moduleId: String(value?.moduleId || ""),
            parameters: window.Stowling.clone(value?.parameters || {})
        };
    }

    /**
     * @param {object} value
     * @returns {object}
     */
    function normalizeTask(value) {
        return {
            id: String(value?.id || ""),
            name: String(value?.name || "").trim(),
            steps: Array.isArray(value?.steps)
                ? value.steps.map(normalizeStep)
                : []
        };
    }

    /**
     * @param {Array<object>} modules
     * @returns {object}
     */
    function newTask(modules) {
        return {
            id: "",
            name: "新任务",
            steps: [newStep(modules[0])]
        };
    }

    /**
     * @param {Array<object>} modules
     * @param {string} name
     * @param {Array<string>} moduleIds
     * @returns {object | null}
     */
    function taskFromModules(modules, name, moduleIds) {
        const selected = moduleIds.map((moduleId) => (
            modules.find((module) => module.id === moduleId)
        ));
        if (selected.some((module) => !module)) {
            return null;
        }
        return {
            id: "",
            name,
            steps: selected.map(newStep)
        };
    }

    /**
     * @param {Array<object>} modules
     * @returns {object | null}
     */
    function cacheTask(modules) {
        return taskFromModules(
            modules,
            "掉落物入缓存",
            CACHE_TASK_MODULES
        );
    }

    /**
     * @param {Array<object>} modules
     * @returns {object | null}
     */
    function sortTask(modules) {
        return taskFromModules(
            modules,
            "缓存物品分类入库",
            SORT_TASK_MODULES
        );
    }

    /**
     * @param {object | undefined} module
     * @returns {object}
     */
    function newStep(module) {
        return {
            moduleId: String(module?.id || ""),
            parameters: {}
        };
    }

    /**
     * @param {object} step
     * @returns {object}
     */
    function payloadStep(step) {
        return {
            moduleId: step.moduleId,
            parameters: window.Stowling.clone(step.parameters)
        };
    }

    /**
     * @param {object} task
     * @returns {object}
     */
    function payloadTask(task) {
        return {
            name: task.name.trim(),
            steps: task.steps.map(payloadStep)
        };
    }

    window.StowlingTaskModel = Object.freeze({
        newStep,
        newTask,
        cacheTask,
        sortTask,
        taskFromModules,
        normalizeTask,
        payloadTask
    });
})();
