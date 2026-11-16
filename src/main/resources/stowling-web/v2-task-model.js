(() => {
    "use strict";

    const CACHE_TASK_MODULES = Object.freeze([
        "COLLECT_DROPPED_ITEMS_IN_REGION",
        "SCAN_CONTAINERS_IN_REGION",
        "DEPOSIT_ITEMS_TO_CONTAINERS"
    ]);
    const SORT_TASK_MODULES = Object.freeze([
        "SORT_CACHE_ITEMS_BY_CATEGORY_ZONE"
    ]);
    const PRIORITIES = Object.freeze([
        "BACKGROUND",
        "NORMAL",
        "URGENT"
    ]);
    const MODULE_KINDS = Object.freeze([
        "ACTION",
        "LISTENER",
        "PRESET",
        "INTERNAL"
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
            priority: PRIORITIES.includes(value?.priority)
                ? value.priority
                : "NORMAL",
            repeatWhenIdle: Boolean(value?.repeatWhenIdle),
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
            priority: "NORMAL",
            repeatWhenIdle: false,
            steps: [newStep(defaultModule(modules))]
        };
    }

    /**
     * @param {Array<object>} modules
     * @returns {Array<object>}
     */
    function visibleModules(modules) {
        return modules.filter((module) => (
            !module?.deprecated
                && moduleKind(module) !== "INTERNAL"
                && moduleKind(module) !== "PRESET"
        ));
    }

    /**
     * @param {object} module
     * @returns {string}
     */
    function moduleKind(module) {
        return MODULE_KINDS.includes(module?.kind)
            ? module.kind
            : "ACTION";
    }

    /**
     * @param {Array<object>} modules
     * @returns {Array<object>}
     */
    function actionModules(modules) {
        return visibleModules(modules).filter(
            (module) => moduleKind(module) === "ACTION"
        );
    }

    /**
     * @param {Array<object>} modules
     * @returns {object | undefined}
     */
    function defaultModule(modules) {
        const actions = actionModules(modules);
        return actions.find((module) => module?.level === "BASIC")
            || actions[0];
    }

    /**
     * @param {object} task
     * @param {Array<object>} modules
     * @returns {string}
     */
    function taskLevel(task, modules) {
        if (!Array.isArray(task?.steps) || task.steps.length !== 1) {
            return "COMPOSITE";
        }
        const module = modules.find(
            (candidate) => candidate.id === task.steps[0].moduleId
        );
        if (moduleKind(module) === "PRESET") {
            return "PRESET";
        }
        return module?.level === "BASIC" ? "BASIC" : "COMPOSITE";
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
            priority: "NORMAL",
            repeatWhenIdle: false,
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
        const task = taskFromModules(
            modules,
            "缓存物品分类入库",
            SORT_TASK_MODULES
        );
        if (task) {
            task.priority = "NORMAL";
            task.repeatWhenIdle = false;
        }
        return task;
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
            priority: task.priority,
            repeatWhenIdle: Boolean(
                task.priority === "BACKGROUND"
                && task.repeatWhenIdle
            ),
            steps: task.steps.map(payloadStep)
        };
    }

    /**
     * @param {string} priority
     * @returns {string}
     */
    function priorityLabel(priority) {
        if (priority === "BACKGROUND") {
            return "后台";
        }
        if (priority === "URGENT") {
            return "紧急";
        }
        return "普通";
    }

    window.StowlingTaskModel = Object.freeze({
        newStep,
        visibleModules,
        actionModules,
        moduleKind,
        defaultModule,
        newTask,
        taskLevel,
        cacheTask,
        sortTask,
        taskFromModules,
        normalizeTask,
        payloadTask,
        priorityLabel,
        PRIORITIES,
        MODULE_KINDS
    });
})();
