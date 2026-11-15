(() => {
    "use strict";

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
            name: "新移动任务",
            steps: [newStep(modules[0])]
        };
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
        normalizeTask,
        payloadTask
    });
})();
