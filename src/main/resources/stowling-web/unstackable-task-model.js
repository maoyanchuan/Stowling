(() => {
    "use strict";
    const MODULE_ID = "SORT_UNSTACKABLE_ITEMS";
    const Model = window.StowlingTaskModel;

    /** @param {object} task @returns {boolean} */
    function isUnstackableTask(task) {
        return !task?.defaultInterruptionFlow && task?.steps?.length === 1
            && task.steps[0].kind === "ACTION" && task.steps[0].moduleId === MODULE_ID;
    }

    /** @param {Array<object>} tasks @returns {object} */
    function newDraft(tasks = []) {
        const names = new Set(tasks.map(task => String(task.name).trim().toLowerCase()));
        let name = "不可堆叠物品分类";
        for (let suffix = 2; names.has(name.toLowerCase()); suffix += 1) {
            name = `不可堆叠物品分类 ${suffix}`;
        }
        return {id: "", name, regionRef: "", targetRef: "", priority: "BACKGROUND"};
    }

    /** @param {object} task @returns {object} */
    function draftFromTask(task) {
        return {id: task.id, name: task.name, priority: task.priority,
            regionRef: task.steps[0].parameters.regionRef || "",
            targetRef: task.steps[0].parameters.targetRef || ""};
    }

    /**
     * @param {object} draft
     * @param {Array<object>} regions
     * @param {Array<object>} tasks
     * @returns {object}
     */
    function createTask(draft, regions, tasks) {
        const name = String(draft.name || "").trim();
        if (!name || name.length > 80) throw new Error("任务名称需为 1～80 个字符");
        if (tasks.some(task => task.id !== draft.id
                && String(task.name).trim().toLowerCase() === name.toLowerCase())) {
            throw new Error("任务名称已存在，请使用其他名称");
        }
        const refs = new Set(regions.map(region => Model.regionRef(region.id)));
        if (!refs.has(draft.regionRef)) throw new Error("请选择有效的缓存区域");
        if (!refs.has(draft.targetRef)) throw new Error("请选择有效的不可堆叠储存区");
        if (draft.regionRef === draft.targetRef) {
            throw new Error("不可堆叠储存区需要使用独立区域");
        }
        if (!Model.PRIORITIES.includes(draft.priority)) throw new Error("请选择运行优先级");
        const original = draft.id ? tasks.find(task => task.id === draft.id) : null;
        if (draft.id && !isUnstackableTask(original)) {
            throw new Error("原任务已不存在或流程已改变，请刷新后重新选择");
        }
        return {...(original || {}), id: draft.id, name, priority: draft.priority,
            repeatWhenIdle: false, interruptionMode: original?.interruptionMode || "DEFAULT",
            cleanupPolicy: {destinationRef: draft.regionRef, resumeMode: "RESTART"},
            steps: [{...original?.steps[0], ...Model.actionStep(MODULE_ID,
                {regionRef: draft.regionRef, targetRef: draft.targetRef})}]};
    }

    window.StowlingUnstackableTaskModel = Object.freeze({
        MODULE_ID, isUnstackableTask, newDraft, draftFromTask, createTask
    });
})();
