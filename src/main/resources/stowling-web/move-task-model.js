(() => {
    "use strict";

    const Model = window.StowlingTaskModel;
    const MOVE_MODULE_ID = "MOVE_TO_COORDINATE";
    const INT32_MIN = -2147483648;
    const INT32_MAX = 2147483647;
    const INTEGER_PATTERN = /^[+-]?\d+$/;
    const XYZ_KEYS = Object.freeze(["x", "y", "z"]);
    const MOVE_STEP_FIELDS = Object.freeze([
        "id",
        "kind",
        "moduleId",
        "parameters",
        "steps",
        "otherwise"
    ]);

    /**
     * @param {unknown} value
     * @returns {boolean}
     */
    function isDisabledFlag(value) {
        return value === undefined || value === null || value === false;
    }

    /**
     * @param {unknown} value
     * @returns {boolean}
     */
    function isEmptyBranch(value) {
        return value === undefined
            || (Array.isArray(value) && value.length === 0);
    }

    /**
     * @param {object} parameters
     * @param {Array<string>} keys
     * @returns {boolean}
     */
    function hasExactKeys(parameters, keys) {
        const actual = Object.keys(parameters).sort();
        const expected = [...keys].sort();
        return actual.length === expected.length
            && actual.every((key, index) => key === expected[index]);
    }

    /**
     * @param {unknown} value
     * @returns {boolean}
     */
    function isNonEmptyString(value) {
        return typeof value === "string" && value.trim() !== "";
    }

    /**
     * @param {object} parameters
     * @returns {boolean}
     */
    function hasCoordinateReference(parameters) {
        if (!hasExactKeys(parameters, ["targetRef"])) {
            return false;
        }
        const reference = Model.parseReference(parameters.targetRef);
        return reference?.type === "coordinate"
            && String(reference.id || "").trim() !== "";
    }

    /**
     * @param {object} parameters
     * @returns {boolean}
     */
    function hasLegacyCoordinateId(parameters) {
        if (!hasExactKeys(parameters, ["coordinateId"])) {
            return false;
        }
        const coordinateId = String(parameters.coordinateId || "").trim();
        return coordinateId !== "" && !coordinateId.includes(":");
    }

    /**
     * @param {object} parameters
     * @returns {boolean}
     */
    function hasXYZCoordinates(parameters) {
        return hasExactKeys(parameters, XYZ_KEYS)
            && XYZ_KEYS.every((key) => isNonEmptyString(parameters[key]));
    }

    /**
     * @param {unknown} value
     * @returns {boolean}
     */
    function isMoveTask(value) {
        if (!value || typeof value !== "object" || Array.isArray(value)) {
            return false;
        }
        if (!isDisabledFlag(value.repeatWhenIdle)
                || !isDisabledFlag(value.defaultInterruptionFlow)
                || !isDisabledFlag(value.requeueAfterInterruption)
                    && value?.cleanupPolicy?.resumeMode !== "CONTINUE") {
            return false;
        }
        const mode = value.interruptionMode;
        if (mode !== undefined && mode !== null
                && mode !== "DEFAULT" && mode !== "NONE") {
            return false;
        }
        if (String(value.interruptionTaskRef || "").trim() !== "") {
            return false;
        }
        if (!Array.isArray(value.steps) || value.steps.length !== 1) {
            return false;
        }
        const step = value.steps[0];
        if (!step || typeof step !== "object" || Array.isArray(step)) {
            return false;
        }
        if (step.kind !== "ACTION" || step.moduleId !== MOVE_MODULE_ID) {
            return false;
        }
        if (!isEmptyBranch(step.steps) || !isEmptyBranch(step.otherwise)) {
            return false;
        }
        if (Object.keys(step).some((key) => !MOVE_STEP_FIELDS.includes(key))) {
            return false;
        }
        const parameters = step.parameters;
        if (!parameters || typeof parameters !== "object"
                || Array.isArray(parameters)) {
            return false;
        }
        const styles = [
            hasCoordinateReference(parameters),
            hasLegacyCoordinateId(parameters),
            hasXYZCoordinates(parameters)
        ];
        return styles.filter(Boolean).length === 1;
    }

    /**
     * @returns {object}
     */
    function newDraft() {
        return {
            id: "",
            priority: "NORMAL",
            source: "manual",
            x: "",
            y: "",
            z: "",
            coordinateId: ""
        };
    }

    /**
     * @param {object} task
     * @returns {object}
     */
    function draftFromTask(task) {
        const normalized = Model.normalizeTask(task);
        const parameters = normalized.steps[0].parameters;
        const parsed = Model.parseReference(parameters.targetRef);
        const coordinateId = parsed?.type === "coordinate"
            ? String(parsed.id || "").trim()
            : String(parameters.coordinateId || "").trim();
        const saved = coordinateId !== "";
        return {
            id: normalized.id,
            priority: normalized.priority,
            source: saved ? "saved" : "manual",
            x: saved ? "" : String(parameters.x || ""),
            y: saved ? "" : String(parameters.y || ""),
            z: saved ? "" : String(parameters.z || ""),
            coordinateId: saved ? coordinateId : ""
        };
    }

    /**
     * @param {unknown} value
     * @returns {Array<object>}
     */
    function coordinateList(value) {
        if (Array.isArray(value)) {
            return value;
        }
        return Array.isArray(value?.coordinates) ? value.coordinates : [];
    }

    /**
     * @param {string} axis
     * @param {unknown} value
     * @returns {string | null}
     */
    function manualCoordinateError(axis, value) {
        const text = String(value ?? "").trim();
        const upper = axis.toUpperCase();
        if (text === "") {
            return `请填写 ${upper} 坐标`;
        }
        if (!INTEGER_PATTERN.test(text)) {
            return `${upper} 坐标需为整数`;
        }
        if (BigInt(text) < BigInt(INT32_MIN)
                || BigInt(text) > BigInt(INT32_MAX)) {
            return `${upper} 坐标超出允许范围`;
        }
        return null;
    }


    /**
     * @param {object} draft
     * @param {unknown} coordinates
     * @returns {Array<string>}
     */
    function validateDraft(draft, coordinates) {
        const errors = [];
        if (!Model.PRIORITIES.includes(draft?.priority)) {
            errors.push("请选择运行优先级");
        }
        if (draft?.source !== "manual" && draft?.source !== "saved") {
            errors.push("请选择坐标输入方式");
        }

        if (draft?.source === "manual") {
            for (const key of XYZ_KEYS) {
                const error = manualCoordinateError(key, draft?.[key]);
                if (error) {
                    errors.push(error);
                }
            }
        } else if (draft?.source === "saved") {
            const coordinateId = String(draft?.coordinateId ?? "").trim();
            if (coordinateId === "") {
                errors.push("请选择已保存坐标");
            } else {
                const exists = coordinateList(coordinates).some(
                    (coordinate) => String(coordinate?.id || "") === coordinateId
                );
                if (!exists) {
                    errors.push("所选坐标不存在，请刷新后重试");
                }
            }
        }
        return errors;
    }

    /**
     * @param {object} draft
     * @returns {object}
     */
    function coordinateParameters(draft) {
        if (draft.source === "saved") {
            return {
                targetRef: Model.coordinateRef(String(draft.coordinateId).trim())
            };
        }
        return {
            x: String(draft.x).trim(),
            y: String(draft.y).trim(),
            z: String(draft.z).trim()
        };
    }

    /**
     * @param {object | null} originalTask
     * @returns {{folderId: string, interruptionMode: string, stepId: string}}
     */
    function preservedMetadata(originalTask) {
        if (!originalTask) {
            return {
                folderId: "",
                interruptionMode: "DEFAULT",
                stepId: ""
            };
        }
        const normalized = Model.normalizeTask(originalTask);
        return {
            folderId: normalized.folderId,
            interruptionMode: normalized.interruptionMode === "NONE"
                ? "NONE"
                : "DEFAULT",
            stepId: String(normalized.steps[0]?.id || "")
        };
    }

    /**
     * @param {object} draft
     * @param {unknown} coordinates
     * @param {object | null} originalTask
     * @returns {object}
     */
    function createTask(draft, coordinates, originalTask = null) {
        const errors = validateDraft(draft, coordinates);
        if (errors.length > 0) {
            throw new RangeError(errors.join("；"));
        }
        const metadata = preservedMetadata(originalTask);
        const step = Model.actionStep(
            MOVE_MODULE_ID,
            coordinateParameters(draft)
        );
        if (metadata.stepId) {
            step.id = metadata.stepId;
        }
        const candidate = Model.normalizeTask({
            id: String(draft.id || ""),
            folderId: metadata.folderId,
            priority: draft.priority,
            repeatWhenIdle: false,
            interruptionMode: metadata.interruptionMode,
            interruptionTaskRef: "",
            defaultInterruptionFlow: false,
            requeueAfterInterruption: false,
            // Updates retain omitted policies on the server; reset legacy movement settings.
            ...(originalTask?.cleanupPolicy ? {cleanupPolicy: {
                destinationRef: null, resumeMode: "RESTART"
            }} : {}),
            steps: [step]
        });
        const task = Model.normalizeTask({...Model.payloadTask(candidate), id: candidate.id});
        delete task.name;
        return task;
    }

    window.StowlingMoveTaskModel = Object.freeze({
        createTask,
        draftFromTask,
        isMoveTask,
        newDraft,
        validateDraft
    });
})();


