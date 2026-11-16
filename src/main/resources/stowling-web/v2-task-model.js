(() => {
    "use strict";

    const PRIORITIES = Object.freeze([
        "BACKGROUND",
        "NORMAL",
        "URGENT"
    ]);
    const PRIORITY_RANK = Object.freeze({
        URGENT: 0,
        NORMAL: 1,
        BACKGROUND: 2
    });
    const MODULE_KINDS = Object.freeze([
        "ACTION",
        "LISTENER"
    ]);
    const STEP_KINDS = Object.freeze([
        "ACTION",
        "IF",
        "REPEAT",
        "CREATE_TASK"
    ]);
    const INTERRUPTION_MODES = Object.freeze([
        "DEFAULT",
        "CUSTOM",
        "NONE"
    ]);
    const CONDITIONS = Object.freeze([
        "ALWAYS",
        "FOOD_BELOW_THRESHOLD",
        "HAS_CONTAINERS",
        "HAS_CARRIED_ITEMS",
        "HAS_MANAGED_ITEMS",
        "CACHE_HAS_MORE",
        "HAS_MARKER_CONTAINERS"
    ]);
    const ACTION_MODULE_IDS = Object.freeze([
        "MOVE_TO_COORDINATE",
        "COLLECT_DROPPED_ITEMS",
        "SCAN_CONTAINERS_IN_REGION",
        "RESOLVE_CONTAINER_CATEGORIES",
        "SELECT_CONTAINER",
        "WITHDRAW_ITEMS",
        "SELECT_STORAGE_TARGET",
        "DEPOSIT_ITEMS_TO_CONTAINERS",
        "WITHDRAW_FOOD",
        "EAT_FOOD",
        "SCAN_CATEGORY_MARKERS",
        "SELECT_MARKER_CONTAINER",
        "READ_CONTAINER",
        "SAVE_CATEGORY_MARKER_CACHE",
        "ASSERT_NO_MANAGED_ITEMS"
    ]);
    const MAX_REPEAT_ITERATIONS = 1_000_000;
    const MAX_ROOT_STEPS = 128;
    const MAX_STEP_DEPTH = 16;
    const MAX_TOTAL_STEPS = 512;
    const DEFAULT_REPEAT_ITERATIONS = 1024;
    const OUTPUT_REFERENCES = Object.freeze([
        "output:container",
        "output:storage",
        "output:marker"
    ]);

    /**
     * @param {unknown} value
     * @returns {object}
     */
    function cloneObject(value) {
        return value && typeof value === "object"
            ? window.Stowling.clone(value)
            : {};
    }

    /**
     * @param {unknown} value
     * @returns {object}
     */
    function normalizeParameters(value) {
        const source = cloneObject(value);
        const parameters = {};
        for (const [key, entry] of Object.entries(source)) {
            if (entry !== null && entry !== undefined) {
                parameters[key] = String(entry);
            }
        }
        return parameters;
    }

    /**
     * @param {unknown} value
     * @returns {Array<object>}
     */
    function normalizeStepList(value) {
        return Array.isArray(value) ? value.map(normalizeStep) : [];
    }

    /**
     * @param {object} value
     * @returns {object}
     */
    function normalizeStep(value) {
        const kind = STEP_KINDS.includes(value?.kind)
            ? value.kind
            : "ACTION";
        const step = {
            kind,
            moduleId: String(value?.moduleId || ""),
            parameters: normalizeParameters(value?.parameters),
            steps: normalizeStepList(value?.steps),
            otherwise: normalizeStepList(value?.otherwise)
        };
        if (value?.id) {
            step.id = String(value.id);
        }
        return step;
    }

    /**
     * @param {object} value
     * @returns {object}
     */
    function normalizeTask(value) {
        const mode = INTERRUPTION_MODES.includes(value?.interruptionMode)
            ? value.interruptionMode
            : "DEFAULT";
        const isDefault = Boolean(value?.defaultInterruptionFlow);
        return {
            id: String(value?.id || ""),
            name: String(value?.name || "").trim(),
            folderId: String(value?.folderId || ""),
            priority: PRIORITIES.includes(value?.priority)
                ? value.priority
                : "NORMAL",
            repeatWhenIdle: Boolean(value?.repeatWhenIdle),
            interruptionMode: isDefault ? "NONE" : mode,
            interruptionTaskRef: String(value?.interruptionTaskRef || ""),
            defaultInterruptionFlow: isDefault,
            requeueAfterInterruption: Boolean(value?.requeueAfterInterruption),
            steps: normalizeStepList(value?.steps)
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
            folderId: "",
            priority: "NORMAL",
            repeatWhenIdle: false,
            interruptionMode: "DEFAULT",
            interruptionTaskRef: "",
            defaultInterruptionFlow: false,
            requeueAfterInterruption: false,
            steps: [newStep(defaultModule(modules), "ACTION")]
        };
    }

    /**
     * @param {Array<object>} modules
     * @returns {Array<object>}
     */
    function visibleModules(modules) {
        if (!Array.isArray(modules)) {
            return [];
        }
        return modules.filter((module) => {
            const kind = moduleKind(module);
            const basicAction = kind === "ACTION"
                && (module?.level === undefined || module.level === "BASIC");
            return !module?.deprecated
                && (basicAction || kind === "LISTENER");
        });
    }

    /**
     * @param {object} module
     * @returns {string}
     */
    function moduleKind(module) {
        const kind = String(module?.kind || "").toUpperCase();
        return kind || "ACTION";
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
     * @returns {string}
     */
    function taskLevel(task) {
        const steps = Array.isArray(task?.steps) ? task.steps : [];
        if (steps.length === 1 && (steps[0]?.kind || "ACTION") === "ACTION") {
            return "BASIC";
        }
        return "COMPOSITE";
    }

    /**
     * @param {string} priority
     * @returns {number}
     */
    function priorityRank(priority) {
        return PRIORITY_RANK[priority] ?? PRIORITY_RANK.NORMAL;
    }

    /**
     * @param {string} type
     * @param {string} id
     * @returns {string}
     */
    function reference(type, id) {
        const normalizedType = String(type || "").trim();
        const normalizedId = String(id || "").trim();
        if (!normalizedType || !normalizedId) {
            return "";
        }
        return normalizedId.includes(":")
            ? normalizedId
            : `${normalizedType}:${normalizedId}`;
    }

    /**
     * @param {string} id
     * @returns {string}
     */
    function coordinateRef(id) {
        return reference("coordinate", id);
    }

    /**
     * @param {string} id
     * @returns {string}
     */
    function regionRef(id) {
        return reference("region", id);
    }

    /**
     * @param {string} id
     * @returns {string}
     */
    function categoryZoneRef(id) {
        return reference("category-zone", id);
    }

    /**
     * @param {string} id
     * @returns {string}
     */
    function taskRef(id) {
        return reference("task", id);
    }

    /**
     * @param {string} value
     * @returns {{type: string, id: string} | null}
     */
    function parseReference(value) {
        const text = String(value || "").trim();
        const separator = text.indexOf(":");
        if (separator <= 0 || separator === text.length - 1) {
            return null;
        }
        return {
            type: text.slice(0, separator),
            id: text.slice(separator + 1)
        };
    }

    /**
     * @param {string} value
     * @returns {string}
     */
    function referenceType(value) {
        return parseReference(value)?.type || "";
    }

    /**
     * @param {string} value
     * @returns {string}
     */
    function referenceId(value) {
        return parseReference(value)?.id || "";
    }

    /**
     * @param {string} value
     * @returns {boolean}
     */
    function isOutputReference(value) {
        return OUTPUT_REFERENCES.includes(String(value || ""));
    }

    /**
     * @param {string} condition
     * @returns {string}
     */
    function conditionLabel(condition) {
        const labels = {
            ALWAYS: "总是",
            FOOD_BELOW_THRESHOLD: "饱食度低于阈值",
            HAS_CONTAINERS: "还有待处理容器",
            HAS_CARRIED_ITEMS: "身上还有携带物品",
            HAS_MANAGED_ITEMS: "还有任务管理物品",
            CACHE_HAS_MORE: "缓存还有可分类物品",
            HAS_MARKER_CONTAINERS: "还有待读取标记容器"
        };
        return labels[condition] || "未知条件";
    }

    /**
     * @param {string} condition
     * @returns {boolean}
     */
    function conditionNeedsThreshold(condition) {
        return condition === "FOOD_BELOW_THRESHOLD";
    }

    /**
     * @param {object | undefined} module
     * @param {string} kind
     * @returns {object}
     */
    function newStep(module, kind = "ACTION") {
        if (kind === "IF" || kind === "REPEAT") {
            const parameters = { condition: "ALWAYS" };
            if (kind === "REPEAT") {
                parameters.maxIterations = String(DEFAULT_REPEAT_ITERATIONS);
            }
            return {
                kind,
                moduleId: "",
                parameters,
                steps: [],
                otherwise: kind === "IF" ? [] : []
            };
        }
        if (kind === "CREATE_TASK") {
            return {
                kind,
                moduleId: "",
                parameters: {
                    taskRef: ""
                },
                steps: [],
                otherwise: []
            };
        }
        return actionStep(String(module?.id || ""), {});
    }

    /**
     * @param {string} moduleId
     * @param {object} parameters
     * @returns {object}
     */
    function actionStep(moduleId, parameters) {
        return {
            kind: "ACTION",
            moduleId,
            parameters: normalizeParameters(parameters),
            steps: [],
            otherwise: []
        };
    }

    /**
     * @param {string} condition
     * @param {Array<object>} steps
     * @param {object} extras
     * @returns {object}
     */
    function repeatStep(condition, steps, extras = {}) {
        return {
            kind: "REPEAT",
            moduleId: "",
            parameters: {
                condition,
                maxIterations: String(DEFAULT_REPEAT_ITERATIONS),
                ...normalizeParameters(extras)
            },
            steps: cloneStepList(steps),
            otherwise: []
        };
    }

    /**
     * @param {string} condition
     * @param {Array<object>} steps
     * @param {Array<object>} otherwise
     * @param {object} extras
     * @returns {object}
     */
    function ifStep(condition, steps, otherwise = [], extras = {}) {
        return {
            kind: "IF",
            moduleId: "",
            parameters: {
                condition,
                ...normalizeParameters(extras)
            },
            steps: cloneStepList(steps),
            otherwise: cloneStepList(otherwise)
        };
    }

    /**
     * @param {Array<object>} steps
     * @returns {Array<object>}
     */
    function cloneStepList(steps) {
        return Array.isArray(steps) ? steps.map(normalizeStep) : [];
    }

    /**
     * @param {string} name
     * @param {string} priority
     * @param {Array<object>} steps
     * @returns {object}
     */
    function taskTemplate(name, priority, steps) {
        return {
            id: "",
            name,
            priority,
            repeatWhenIdle: false,
            interruptionMode: "DEFAULT",
            interruptionTaskRef: "",
            defaultInterruptionFlow: false,
            requeueAfterInterruption: false,
            steps: cloneStepList(steps)
        };
    }

    /**
     * @param {Array<object>} modules
     * @returns {object}
     */
    function collectTask(modules) {
        const steps = [
            actionStep("COLLECT_DROPPED_ITEMS", { areaRef: "" }),
            actionStep("SCAN_CONTAINERS_IN_REGION", { regionRef: "" }),
            repeatStep("HAS_CONTAINERS", [
                actionStep("SELECT_CONTAINER", {
                    resetCacheHasMore: "true"
                }),
                actionStep("MOVE_TO_COORDINATE", {
                    targetRef: "output:container"
                }),
                actionStep("DEPOSIT_ITEMS_TO_CONTAINERS", {
                    targetRef: "output:container",
                    itemSource: "collected"
                })
            ]),
            actionStep("ASSERT_NO_MANAGED_ITEMS", {})
        ];
        return taskTemplate("收集入缓存", "NORMAL", steps);
    }

    /**
     * @param {Array<object>} modules
     * @returns {object}
     */
    function cacheTask(modules) {
        return collectTask(modules);
    }

    /**
     * @param {Array<object>} modules
     * @returns {object}
     */
    function sortTask(modules) {
        const withdraw = actionStep("WITHDRAW_ITEMS", {
            targetRef: "output:container",
            itemSource: "classified"
        });
        const deposit = repeatStep("HAS_CARRIED_ITEMS", [
            actionStep("SELECT_STORAGE_TARGET", {}),
            actionStep("MOVE_TO_COORDINATE", {
                targetRef: "output:storage"
            }),
            actionStep("DEPOSIT_ITEMS_TO_CONTAINERS", {
                targetRef: "output:storage",
                itemSource: "carried"
            })
        ]);
        const sortContainers = repeatStep("HAS_CONTAINERS", [
            actionStep("SELECT_CONTAINER", {
                resetCacheHasMore: "true"
            }),
            repeatStep("CACHE_HAS_MORE", [
                actionStep("MOVE_TO_COORDINATE", {
                    targetRef: "output:container"
                }),
                withdraw,
                deposit
            ])
        ]);
        return taskTemplate("缓存物品分类入库", "BACKGROUND", [
            actionStep("SCAN_CONTAINERS_IN_REGION", { regionRef: "" }),
            actionStep("RESOLVE_CONTAINER_CATEGORIES", {
                categoryZoneRef: ""
            }),
            sortContainers,
            actionStep("ASSERT_NO_MANAGED_ITEMS", {})
        ]);
    }

    /**
     * @param {Array<object>} modules
     * @param {string} targetRef
     * @param {string} foodItemId
     * @param {number} threshold
     * @returns {object}
     */
    function eatTask(
        modules,
        targetRef = "",
        foodItemId = "minecraft:bread",
        threshold = 18
    ) {
        const eatLoop = repeatStep(
            "FOOD_BELOW_THRESHOLD",
            [
                actionStep("WITHDRAW_FOOD", {
                    targetRef,
                    foodItemId
                }),
                actionStep("EAT_FOOD", { foodItemId })
            ],
            { foodLevelThreshold: String(threshold) }
        );
        const gate = ifStep(
            "FOOD_BELOW_THRESHOLD",
            [
                actionStep("MOVE_TO_COORDINATE", { targetRef }),
                eatLoop
            ],
            [],
            { foodLevelThreshold: String(threshold) }
        );
        return taskTemplate("自动进食", "NORMAL", [gate]);
    }

    /**
     * @param {Array<object>} modules
     * @returns {object}
     */
    function markerTask(modules) {
        return taskTemplate("刷新分类标记", "BACKGROUND", [
            actionStep("SCAN_CATEGORY_MARKERS", { categoryZoneRef: "" }),
            repeatStep("HAS_MARKER_CONTAINERS", [
                actionStep("SELECT_MARKER_CONTAINER", {}),
                actionStep("MOVE_TO_COORDINATE", {
                    targetRef: "output:marker"
                }),
                actionStep("READ_CONTAINER", {
                    targetRef: "output:marker"
                })
            ]),
            actionStep("SAVE_CATEGORY_MARKER_CACHE", {
                categoryZoneRef: ""
            })
        ]);
    }

    /**
     * @param {Array<object>} modules
     * @returns {object}
     */
    function interruptionTask(modules) {
        return taskTemplate("中断收尾", "BACKGROUND", [
            actionStep("SCAN_CONTAINERS_IN_REGION", { regionRef: "" }),
            repeatStep("HAS_CONTAINERS", [
                actionStep("SELECT_CONTAINER", {
                    resetCacheHasMore: "true"
                }),
                actionStep("MOVE_TO_COORDINATE", {
                    targetRef: "output:container"
                }),
                actionStep("DEPOSIT_ITEMS_TO_CONTAINERS", {
                    targetRef: "output:container",
                    itemSource: "managed"
                })
            ]),
            actionStep("ASSERT_NO_MANAGED_ITEMS", {})
        ]);
    }

    /**
     * @param {object} step
     * @returns {object}
     */
    function payloadStep(step) {
        const parameters = normalizeParameters(step?.parameters);
        const payload = {
            kind: STEP_KINDS.includes(step?.kind) ? step.kind : "ACTION",
            moduleId: String(step?.moduleId || ""),
            parameters,
            steps: Array.isArray(step?.steps)
                ? step.steps.map(payloadStep)
                : [],
            otherwise: Array.isArray(step?.otherwise)
                ? step.otherwise.map(payloadStep)
                : []
        };
        if (
            payload.kind === "CREATE_TASK"
            && !String(payload.parameters.priority || "").trim()
        ) {
            delete payload.parameters.priority;
        }
        if (step?.id) {
            payload.id = String(step.id);
        }
        return payload;
    }

    /**
     * @param {object} task
     * @returns {object}
     */
    function payloadTask(task) {
        const isDefault = Boolean(task.defaultInterruptionFlow);
        const requestedMode = INTERRUPTION_MODES.includes(
            task.interruptionMode
        )
            ? task.interruptionMode
            : "DEFAULT";
        const mode = isDefault ? "NONE" : requestedMode;
        return {
            name: String(task.name || "").trim(),
            folderId: task.folderId ? String(task.folderId) : null,
            priority: task.priority,
            repeatWhenIdle: Boolean(
                task.priority === "BACKGROUND"
                && task.repeatWhenIdle
            ),
            interruptionMode: mode,
            interruptionTaskRef: mode === "CUSTOM"
                ? String(task.interruptionTaskRef || "")
                : null,
            defaultInterruptionFlow: isDefault,
            requeueAfterInterruption: Boolean(task.requeueAfterInterruption),
            steps: Array.isArray(task.steps)
                ? task.steps.map(payloadStep)
                : []
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

    /**
     * @param {string} mode
     * @returns {string}
     */
    function interruptionModeLabel(mode) {
        const labels = {
            DEFAULT: "引用全局默认收尾",
            CUSTOM: "引用指定收尾任务",
            NONE: "不执行中断收尾"
        };
        return labels[mode] || labels.DEFAULT;
    }

    /**
     * @param {Array<object>} modules
     * @param {Array<string>} moduleIds
     * @returns {Array<string>}
     */
    function missingModules(modules, moduleIds) {
        const available = new Set(
            actionModules(modules).map((module) => module.id)
        );
        return moduleIds.filter((moduleId) => !available.has(moduleId));
    }

    /**
     * @param {object} task
     * @returns {Array<string>}
     */
    function taskModuleIds(task) {
        const result = [];
        const visit = (step) => {
            if ((step?.kind || "ACTION") === "ACTION" && step?.moduleId) {
                result.push(step.moduleId);
            }
            for (const child of step?.steps || []) {
                visit(child);
            }
            for (const child of step?.otherwise || []) {
                visit(child);
            }
        };
        for (const step of task?.steps || []) {
            visit(step);
        }
        return [...new Set(result)];
    }

    window.StowlingTaskModel = Object.freeze({
        ACTION_MODULE_IDS,
        CONDITIONS,
        DEFAULT_REPEAT_ITERATIONS,
        INTERRUPTION_MODES,
        MAX_REPEAT_ITERATIONS,
        MAX_ROOT_STEPS,
        MAX_STEP_DEPTH,
        MAX_TOTAL_STEPS,
        MODULE_KINDS,
        OUTPUT_REFERENCES,
        PRIORITIES,
        STEP_KINDS,
        actionModules,
        actionStep,
        cacheTask,
        categoryZoneRef,
        cloneStepList,
        collectTask,
        normalizeParameters,
        conditionLabel,
        conditionNeedsThreshold,
        coordinateRef,
        defaultModule,
        eatTask,
        ifStep,
        interruptionModeLabel,
        interruptionTask,
        isOutputReference,
        markerTask,
        missingModules,
        moduleKind,
        newStep,
        newTask,
        normalizeTask,
        parseReference,
        payloadTask,
        priorityLabel,
        priorityRank,
        reference,
        referenceId,
        referenceType,
        regionRef,
        repeatStep,
        sortTask,
        taskLevel,
        taskModuleIds,
        taskRef,
        taskTemplate,
        visibleModules
    });
})();
