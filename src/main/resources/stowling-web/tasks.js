(() => {
    "use strict";

    const Stowling = window.Stowling;
    const Model = window.StowlingTaskModel;
    const TriggerModel = window.StowlingTriggerModel;
    const Editor = window.StowlingTaskEditor;
    const state = {
        revision: "",
        tasks: [],
        triggers: [],
        triggerStatuses: [],
        modules: [],
        coordinates: [],
        regions: [],
        categoryZones: [],
        selectedId: "",
        selectedTriggerId: "",
        runTaskId: "",
        draft: null,
        triggerDraft: null,
        dirty: false,
        triggerDirty: false,
        runs: [],
        queue: [],
        query: ""
    };

    const elements = {
        newTask: document.querySelector("#new-task-button"),
        newCacheTask: document.querySelector("#new-cache-task-button"),
        newSortTask: document.querySelector("#new-sort-task-button"),
        newAutoEatTask: document.querySelector("#new-auto-eat-task-button"),
        autoEatForm: document.querySelector("#auto-eat-preset-form"),
        autoEatCoordinate: document.querySelector("#auto-eat-coordinate"),
        autoEatFood: document.querySelector("#auto-eat-food"),
        autoEatThreshold: document.querySelector("#auto-eat-threshold"),
        autoEatErrors: document.querySelector("#auto-eat-preset-errors"),
        createAutoEatPreset: document.querySelector("#create-auto-eat-preset"),
        cancelAutoEatPreset: document.querySelector("#cancel-auto-eat-preset"),
        search: document.querySelector("#task-search"),
        taskCount: document.querySelector("#task-list-count"),
        settingsLabel: document.querySelector("#task-settings-label"),
        list: document.querySelector("#task-list"),
        listMessage: document.querySelector("#task-list-message"),
        empty: document.querySelector("#task-empty"),
        form: document.querySelector("#task-form"),
        name: document.querySelector("#task-name"),
        priority: document.querySelector("#task-priority"),
        repeat: document.querySelector("#task-repeat"),
        steps: document.querySelector("#task-steps"),
        addStep: document.querySelector("#add-step-button"),
        moduleCatalog: document.querySelector("#task-module-catalog"),
        moduleCount: document.querySelector("#task-module-count"),
        errors: document.querySelector("#task-errors"),
        revision: document.querySelector("#task-revision"),
        save: document.querySelector("#save-task-button"),
        discard: document.querySelector("#discard-task-button"),
        runSelect: document.querySelector("#run-task-select"),
        runPriority: document.querySelector("#run-priority-select"),
        run: document.querySelector("#run-task-button"),
        remove: document.querySelector("#delete-task-button"),
        newTrigger: document.querySelector("#new-trigger-button"),
        triggerList: document.querySelector("#trigger-list"),
        triggerForm: document.querySelector("#trigger-form"),
        triggerName: document.querySelector("#trigger-name"),
        triggerType: document.querySelector("#trigger-type"),
        triggerTargetTask: document.querySelector("#trigger-target-task"),
        triggerPriority: document.querySelector("#trigger-priority"),
        triggerInterval: document.querySelector("#trigger-interval"),
        triggerIntervalLabel: document.querySelector("#trigger-interval-label"),
        triggerCooldown: document.querySelector("#trigger-cooldown"),
        triggerCoordinate: document.querySelector("#trigger-coordinate"),
        triggerItemFilter: document.querySelector("#trigger-item-filter"),
        triggerDropFields: document.querySelector("#trigger-drop-fields"),
        triggerFoodFields: document.querySelector("#trigger-food-fields"),
        triggerFoodThreshold: document.querySelector("#trigger-food-threshold"),
        triggerCacheFields: document.querySelector("#trigger-cache-fields"),
        triggerCacheRegion: document.querySelector("#trigger-cache-region"),
        triggerErrors: document.querySelector("#trigger-errors"),
        saveTrigger: document.querySelector("#save-trigger-button"),
        discardTrigger: document.querySelector("#discard-trigger-button"),
        deleteTrigger: document.querySelector("#delete-trigger-button"),
        triggerStatusList: document.querySelector("#trigger-status-list"),
        runtime: document.querySelector("#runtime-list"),
        queueList: document.querySelector("#task-queue-list"),
        refreshRuns: document.querySelector("#refresh-runs-button")
    };

    /**
     * @returns {Promise<void>}
     */
    async function loadAll() {
        try {
            const [
                tasks,
                triggers,
                modules,
                coordinates,
                regions,
                categoryZones,
                runs
            ] = await Promise.all([
                Stowling.request("/v1/tasks"),
                Stowling.request("/v1/task-triggers"),
                Stowling.request("/v1/task-modules"),
                Stowling.request("/v1/coordinates"),
                Stowling.request("/v1/regions"),
                Stowling.request("/v1/category-zones"),
                Stowling.request("/v1/task-runs")
            ]);
            state.revision = String(tasks?.revision || "");
            state.tasks = Array.isArray(tasks?.tasks)
                ? tasks.tasks.map(Model.normalizeTask)
                : [];
            state.triggers = Array.isArray(triggers?.triggers)
                ? triggers.triggers.map(TriggerModel.normalizeTrigger)
                : [];
            state.modules = Array.isArray(modules?.modules) ? modules.modules : [];
            state.coordinates = Array.isArray(coordinates?.coordinates)
                ? coordinates.coordinates
                : [];
            state.regions = Array.isArray(regions?.regions)
                ? regions.regions
                : [];
            state.categoryZones = Array.isArray(categoryZones?.categoryZones)
                ? categoryZones.categoryZones
                : [];
            state.runs = Array.isArray(runs?.runs) ? runs.runs : [];
            state.queue = Array.isArray(runs?.queue) ? runs.queue : [];
            state.triggerStatuses = Array.isArray(runs?.triggers)
                ? runs.triggers
                : [];
            renderTaskList();
            renderTriggerList();
            renderTriggerEditor();
            renderModuleCatalog();
            renderRunControls();
            if (!state.draft && state.tasks.length > 0) {
                selectTask(state.tasks[0]);
            }
            renderTriggerStatuses();
            renderRuntime();
            renderQueue();
        } catch (error) {
            Stowling.handleError(error, "读取任务失败");
        }
    }

    /**
     * @returns {void}
     */
    function renderTaskList() {
        elements.list.replaceChildren();
        elements.taskCount.textContent = String(state.tasks.length);
        const matches = state.tasks.filter(
            (task) => task.name.toLocaleLowerCase().includes(state.query)
        );
        if (matches.length === 0 && state.tasks.length > 0) {
            elements.list.append(Stowling.emptyState("没有匹配的任务"));
            return;
        }
        if (state.tasks.length === 0) {
            elements.list.append(Stowling.emptyState("尚未创建任务"));
            return;
        }
        const groups = [
            ["BASIC", "基础任务"],
            ["PRESET", "任务预设"],
            ["COMPOSITE", "组合任务"]
        ];
        for (const [level, label] of groups) {
            const tasks = matches.filter(
                (task) => Model.taskLevel(task, state.modules) === level
            );
            if (tasks.length === 0) {
                continue;
            }
            const group = Stowling.element("section", "task-group");
            const heading = Stowling.element("div", "task-group-heading");
            heading.append(
                Stowling.element("strong", "", label),
                Stowling.element(
                    "span",
                    "muted compact-help",
                    `${tasks.length} 个`
                )
            );
            group.append(heading);
            for (const task of tasks) {
                const button = Stowling.element(
                    "button",
                    "task-card task-select"
                );
                button.type = "button";
                const identity = Stowling.element("div", "task-select-identity");
                identity.append(
                    Stowling.element("strong", "", task.name),
                    Stowling.element("span", "compact-help", `${task.steps.length} 个步骤`)
                );
                button.setAttribute("aria-pressed", String(task.id === state.selectedId));
                button.append(
                    identity,
                    Stowling.element(
                        "span",
                        "module-level-tag",
                        Model.priorityLabel(task.priority)
                    )
                );
                button.classList.toggle(
                    "active",
                    task.id === state.selectedId
                );
                button.addEventListener("click", () => selectTask(task));
                group.append(button);
            }
            elements.list.append(group);
        }
    }

    /**
     * @returns {void}
     */
    function renderTriggerList() {
        elements.triggerList.replaceChildren();
        if (state.triggers.length === 0) {
            elements.triggerList.append(
                Stowling.emptyState("尚未创建任务触发器")
            );
            return;
        }
        for (const trigger of state.triggers) {
            const target = state.tasks.find(
                (task) => task.id === trigger.targetTaskId
            );
            const button = Stowling.element(
                "button",
                "task-card task-select trigger-select"
            );
            button.type = "button";
            button.classList.toggle(
                "active",
                trigger.id === state.selectedTriggerId
            );
            const identity = Stowling.element("div", "task-select-identity");
            identity.append(
                Stowling.element("strong", "", trigger.name),
                Stowling.element(
                    "span",
                    "compact-help",
                    TriggerModel.conditionLabel(trigger)
                ),
                Stowling.element(
                    "span",
                    "muted compact-help",
                    `→ ${target?.name || "目标任务不存在"} · `
                        + Model.priorityLabel(trigger.priority)
                )
            );
            button.append(identity);
            button.addEventListener("click", () => selectTrigger(trigger));
            elements.triggerList.append(button);
        }
    }

    /**
     * @param {object} trigger
     * @returns {void}
     */
    function selectTrigger(trigger) {
        if (state.triggerDirty
                && !window.confirm("当前触发器有未保存修改，继续将放弃这些修改。")) {
            return;
        }
        state.selectedTriggerId = trigger.id;
        state.triggerDraft = TriggerModel.normalizeTrigger(trigger);
        state.triggerDirty = false;
        renderTriggerList();
        renderTriggerEditor();
    }

    /**
     * @returns {void}
     */
    function startNewTrigger() {
        if (state.triggerDirty
                && !window.confirm("当前触发器有未保存修改，继续将放弃这些修改。")) {
            return;
        }
        state.selectedTriggerId = "";
        state.triggerDraft = TriggerModel.newTrigger(
            state.selectedId || state.tasks[0]?.id
        );
        state.triggerDirty = true;
        renderTriggerList();
        renderTriggerEditor();
        elements.triggerName.focus();
        elements.triggerName.select();
    }

    /**
     * @returns {void}
     */
    function renderTriggerEditor() {
        const draft = state.triggerDraft;
        elements.triggerForm.hidden = !draft;
        if (!draft) {
            return;
        }
        const triggerModules = Model.visibleModules(state.modules).filter(
            (module) => Model.moduleKind(module) === "LISTENER"
        );
        if (triggerModules.length > 0) {
            elements.triggerType.replaceChildren();
            for (const module of triggerModules) {
                elements.triggerType.append(
                    new Option(module.name, module.id)
                );
            }
        }
        elements.triggerTargetTask.replaceChildren(
            new Option("请选择目标任务", "")
        );
        for (const task of state.tasks) {
            elements.triggerTargetTask.append(
                new Option(task.name, task.id)
            );
        }
        elements.triggerCoordinate.replaceChildren(
            new Option("请选择检测坐标", "")
        );
        for (const coordinate of state.coordinates) {
            elements.triggerCoordinate.append(
                new Option(coordinate.name, coordinate.id)
            );
        }
        elements.triggerCacheRegion.replaceChildren(
            new Option("请选择缓存区域", "")
        );
        for (const region of state.regions) {
            elements.triggerCacheRegion.append(
                new Option(region.name, region.id)
            );
        }
        elements.triggerName.value = draft.name;
        elements.triggerType.value = draft.type;
        elements.triggerTargetTask.value = draft.targetTaskId;
        elements.triggerPriority.value = draft.priority;
        elements.triggerInterval.value = String(
            Math.max(0.05, draft.intervalTicks / 20)
        );
        elements.triggerCooldown.value = String(
            Math.max(0, draft.cooldownTicks / 20)
        );
        elements.triggerCoordinate.value = draft.coordinateId;
        elements.triggerItemFilter.value = draft.itemFilter;
        elements.triggerCacheRegion.value = draft.cacheRegionId;
        elements.triggerFoodThreshold.value = String(
            draft.foodLevelThreshold
        );
        elements.triggerDropFields.hidden =
            draft.type !== "DROPPED_ITEMS";
        elements.triggerFoodFields.hidden =
            draft.type !== "FOOD_LEVEL_AT_OR_BELOW";
        elements.triggerCacheFields.hidden =
            draft.type !== "CACHE_UPDATED";
        elements.triggerIntervalLabel.textContent =
            draft.type === "CACHE_UPDATED"
                ? "触发防抖（秒）"
                : "检测间隔（秒）";
        updateTriggerActions();
    }

    /**
     * @returns {void}
     */
    function markTriggerDirty() {
        state.triggerDirty = true;
        updateTriggerActions();
    }

    /**
     * @returns {Array<string>}
     */
    function triggerValidationErrors() {
        const trigger = state.triggerDraft;
        if (!trigger) {
            return ["尚未选择触发器"];
        }
        const errors = [];
        if (!String(trigger.name || "").trim()) {
            errors.push("触发器名称不能为空");
        }
        if (!TriggerModel.TYPES.includes(trigger.type)) {
            errors.push("触发条件无效");
        }
        if (!state.tasks.some((task) => task.id === trigger.targetTaskId)) {
            errors.push("必须选择一个存在的目标任务");
        }
        if (!Number.isInteger(trigger.intervalTicks)
                || trigger.intervalTicks < 1
                || trigger.intervalTicks > 1200) {
            errors.push("检测间隔必须在 0.05 到 60 秒之间");
        }
        if (!Number.isInteger(trigger.cooldownTicks)
                || trigger.cooldownTicks < 0
                || trigger.cooldownTicks > 72000) {
            errors.push("完成冷却必须在 0 到 3600 秒之间");
        }
        if (trigger.type === "DROPPED_ITEMS"
                && !state.coordinates.some(
                    (point) => point.id === trigger.coordinateId
                )) {
            errors.push("掉落物触发必须选择一个检测坐标");
        }
        if (trigger.type === "FOOD_LEVEL_AT_OR_BELOW"
                && (!Number.isInteger(trigger.foodLevelThreshold)
                    || trigger.foodLevelThreshold < 0
                    || trigger.foodLevelThreshold > 20)) {
            errors.push("饱食度阈值必须是 0 到 20 的整数");
        }
        if (trigger.type === "CACHE_UPDATED"
                && !state.regions.some(
                    (region) => region.id === trigger.cacheRegionId
                )) {
            errors.push("缓存触发必须选择一个缓存区域");
        }
        return errors;
    }

    /**
     * @returns {void}
     */
    function updateTriggerActions() {
        const errors = triggerValidationErrors();
        elements.triggerErrors.replaceChildren();
        if (state.triggerDirty && errors.length > 0) {
            elements.triggerErrors.append(
                Stowling.element("strong", "", "请修正以下问题：")
            );
            for (const error of errors) {
                elements.triggerErrors.append(
                    Stowling.element("p", "", error)
                );
            }
        }
        elements.saveTrigger.disabled =
            !state.triggerDirty || errors.length > 0;
        elements.discardTrigger.disabled = !state.triggerDirty;
        elements.deleteTrigger.disabled = !state.selectedTriggerId;
    }

    /**
     * @returns {Promise<void>}
     */
    async function saveTrigger() {
        const errors = triggerValidationErrors();
        if (errors.length > 0) {
            updateTriggerActions();
            return;
        }
        const payload = TriggerModel.payloadTrigger(state.triggerDraft);
        try {
            let response;
            if (state.selectedTriggerId) {
                response = await Stowling.request(
                    `/v1/task-triggers/${encodeURIComponent(
                        state.selectedTriggerId
                    )}`,
                    {
                        method: "PUT",
                        headers: { "Content-Type": "application/json" },
                        body: JSON.stringify({
                            revision: state.revision,
                            ...payload
                        })
                    }
                );
            } else {
                response = await Stowling.request("/v1/task-triggers", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify(payload)
                });
            }
            state.revision = String(response?.revision || state.revision);
            state.triggers = response.triggers.map(
                TriggerModel.normalizeTrigger
            );
            const saved = state.triggers.find(
                (trigger) => trigger.name === payload.name
            );
            state.selectedTriggerId = saved?.id || "";
            state.triggerDraft = saved
                ? TriggerModel.normalizeTrigger(saved)
                : null;
            state.triggerDirty = false;
            renderTriggerList();
            renderTriggerEditor();
            await loadRuns();
            Stowling.showToast("任务触发器已保存");
        } catch (error) {
            Stowling.handleError(error, "保存任务触发器失败");
        }
    }

    /**
     * @returns {Promise<void>}
     */
    async function deleteTrigger() {
        if (!state.selectedTriggerId) {
            return;
        }
        if (!window.confirm(`删除触发器“${state.triggerDraft.name}”？`)) {
            return;
        }
        try {
            const response = await Stowling.request(
                `/v1/task-triggers/${encodeURIComponent(
                    state.selectedTriggerId
                )}`,
                {
                    method: "DELETE",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ revision: state.revision })
                }
            );
            state.revision = String(response?.revision || state.revision);
            state.triggers = response.triggers.map(
                TriggerModel.normalizeTrigger
            );
            state.selectedTriggerId = "";
            state.triggerDraft = null;
            state.triggerDirty = false;
            renderTriggerList();
            renderTriggerEditor();
            await loadRuns();
            Stowling.showToast("任务触发器已删除");
        } catch (error) {
            Stowling.handleError(error, "删除任务触发器失败");
        }
    }

    /**
     * @param {string} triggerState
     * @returns {string}
     */
    function triggerStateLabel(triggerState) {
        const labels = {
            IDLE: "空闲",
            RUNNING: "已入队",
            COOLDOWN: "冷却中",
            ERROR: "异常"
        };
        return labels[triggerState] || "未加载";
    }

    /**
     * @returns {void}
     */
    function renderModuleCatalog() {
        elements.moduleCatalog.replaceChildren();
        const visibleModules = Model.visibleModules(state.modules);
        elements.moduleCount.textContent = `${visibleModules.length} 个`;
        if (visibleModules.length === 0) {
            elements.moduleCatalog.append(
                Stowling.emptyState("当前没有可用模块")
            );
            return;
        }
        const groups = [
            ["LISTENER", "监听器"],
            ["BASIC", "基础模块"]
        ];
        for (const [groupKey, label] of groups) {
            const modules = visibleModules.filter((module) => (
                groupKey === "BASIC"
                    ? Model.moduleKind(module) === "ACTION"
                        && module.level === groupKey
                    : Model.moduleKind(module) === groupKey
            ));
            if (modules.length === 0) {
                continue;
            }
            const group = Stowling.element("section", "module-group");
            const heading = Stowling.element("div", "task-group-heading");
            heading.append(
                Stowling.element("strong", "", label),
                Stowling.element(
                    "span",
                    "muted compact-help",
                    `${modules.length} 个`
                )
            );
            group.append(heading);
            for (const module of modules) {
                group.append(createModuleCard(module));
            }
            elements.moduleCatalog.append(group);
        }
    }

    /**
     * @param {object} module
     * @returns {string}
     */
    function moduleTag(module) {
        const kind = Model.moduleKind(module);
        if (kind === "LISTENER") {
            return "监听";
        }
        if (kind === "PRESET") {
            return "预设";
        }
        return module.level === "BASIC" ? "基础" : "复合";
    }

    /**
     * @param {object} module
     * @returns {HTMLElement}
     */
    function createModuleCard(module) {
        const card = Stowling.element("details", "module-card");
        const summary = Stowling.element("summary", "");
        summary.append(
            Stowling.element("strong", "", module.name),
            Stowling.element("span", "module-level-tag", moduleTag(module))
        );
        const parameters = Array.isArray(module.parameters)
            ? module.parameters
            : [];
        const parameterText = parameters.length === 0
            ? "无需参数"
            : parameters.map((parameter) => {
                const required = parameter.required ? "（必填）" : "";
                return `${parameter.label}${required}`;
            }).join("、");
        card.append(
            summary,
            Stowling.element("p", "", module.description || "")
        );
        const composedOf = Array.isArray(module.composedOf)
            ? module.composedOf
            : [];
        if (composedOf.length > 0) {
            const names = composedOf.map((moduleId) => {
                const called = state.modules.find(
                    (candidate) => candidate.id === moduleId
                );
                return called?.name || moduleId;
            });
            card.append(Stowling.element(
                "p",
                "compact-help",
                `调用：${names.join(" → ")}`
            ));
        }
        card.append(Stowling.element(
            "p",
            "compact-help",
            `参数：${parameterText}`
        ));
        return card;
    }

    /**
     * @param {object} task
     * @returns {void}
     */
    function selectTask(task) {
        if (state.dirty
                && !window.confirm("当前任务有未保存修改，继续将放弃这些修改。")) {
            return;
        }
        state.selectedId = task.id;
        state.runTaskId = task.id;
        state.draft = Model.normalizeTask(task);
        state.dirty = false;
        renderEditor();
        renderTaskList();
        renderRunControls();
        animateEditor();
    }

    /**
     * @returns {void}
     */
    function renderRunControls() {
        const current = state.runTaskId;
        elements.runSelect.replaceChildren(new Option("请选择任务", ""));
        const groups = [
            ["BASIC", "基础任务"],
            ["PRESET", "任务预设"],
            ["COMPOSITE", "组合任务"]
        ];
        for (const [level, label] of groups) {
            const group = document.createElement("optgroup");
            group.label = label;
            for (const task of state.tasks.filter(
                (candidate) => Model.taskLevel(candidate, state.modules)
                    === level
            )) {
                group.append(new Option(task.name, task.id));
            }
            if (group.children.length > 0) {
                elements.runSelect.append(group);
            }
        }
        const preserved = state.tasks.some((task) => task.id === current)
            ? current
            : state.tasks[0]?.id || "";
        state.runTaskId = preserved;
        elements.runSelect.value = preserved;
        updateRunActions();
    }

    /**
     * @returns {void}
     */
    function updateRunActions() {
        elements.runSelect.disabled = state.tasks.length === 0;
        elements.runPriority.disabled = state.tasks.length === 0;
        elements.run.disabled = !state.runTaskId;
    }

    /**
     * @param {string} base
     * @param {Array<string>} existing
     * @returns {string}
     */
    function uniqueName(base, existing) {
        const used = new Set(existing.map((name) => name.toLocaleLowerCase()));
        if (!used.has(base.toLocaleLowerCase())) {
            return base;
        }
        let suffix = 2;
        while (used.has(`${base} ${suffix}`.toLocaleLowerCase())) {
            suffix++;
        }
        return `${base} ${suffix}`;
    }

    /**
     * @returns {void}
     */
    function showAutoEatPresetForm() {
        elements.autoEatCoordinate.replaceChildren(
            new Option("请选择食物箱坐标", "")
        );
        for (const coordinate of state.coordinates) {
            elements.autoEatCoordinate.append(
                new Option(coordinate.name, coordinate.id)
            );
        }
        elements.autoEatFood.value = "minecraft:bread";
        elements.autoEatThreshold.value = "18";
        elements.autoEatErrors.replaceChildren();
        elements.autoEatForm.hidden = false;
        elements.autoEatCoordinate.focus();
    }

    /**
     * @returns {void}
     */
    function hideAutoEatPresetForm() {
        elements.autoEatForm.hidden = true;
        elements.autoEatErrors.replaceChildren();
    }

    /**
     * @returns {Promise<void>}
     */
    async function createAutoEatPreset() {
        const coordinateId = elements.autoEatCoordinate.value;
        const foodItemId = elements.autoEatFood.value.trim();
        const threshold = Number(elements.autoEatThreshold.value);
        const errors = [];
        if (!state.coordinates.some((coordinate) => (
            coordinate.id === coordinateId
        ))) {
            errors.push("请选择食物箱坐标");
        }
        if (!/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(foodItemId)) {
            errors.push("食物 ID 必须是 minecraft:bread 这样的资源 ID");
        }
        if (!Number.isInteger(threshold) || threshold < 1 || threshold > 20) {
            errors.push("饱食度阈值必须是 1 到 20 的整数");
        }
        elements.autoEatErrors.replaceChildren();
        if (errors.length > 0) {
            for (const error of errors) {
                elements.autoEatErrors.append(
                    Stowling.element("p", "", error)
                );
            }
            return;
        }
        const taskName = uniqueName(
            "自动进食",
            state.tasks.map((task) => task.name)
        );
        const triggerName = uniqueName(
            `${taskName} 监听器`,
            state.triggers.map((trigger) => trigger.name)
        );
        const steps = [
            {
                moduleId: "MOVE_TO_COORDINATE",
                parameters: { coordinateId }
            },
            {
                moduleId: "WITHDRAW_FOOD",
                parameters: { coordinateId, foodItemId }
            },
            {
                moduleId: "EAT_FOOD",
                parameters: { foodItemId }
            }
        ];
        elements.createAutoEatPreset.disabled = true;
        try {
            const taskResponse = await Stowling.request("/v1/tasks", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    name: taskName,
                    priority: "NORMAL",
                    repeatWhenIdle: false,
                    steps
                })
            });
            state.revision = String(taskResponse?.revision || state.revision);
            state.tasks = taskResponse.tasks.map(Model.normalizeTask);
            const createdTask = state.tasks.find(
                (task) => task.name === taskName
            );
            if (!createdTask) {
                throw new Error("任务创建成功，但没有返回任务 ID");
            }
            const triggerPayload = {
                name: triggerName,
                type: "FOOD_LEVEL_AT_OR_BELOW",
                targetTaskId: createdTask.id,
                priority: "NORMAL",
                intervalTicks: 20,
                cooldownTicks: 100,
                coordinateId: null,
                itemFilter: null,
                foodLevelThreshold: threshold,
                cacheRegionId: null
            };
            const triggerResponse = await Stowling.request(
                "/v1/task-triggers",
                {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify(triggerPayload)
                }
            );
            state.revision = String(
                triggerResponse?.revision || state.revision
            );
            state.triggers = triggerResponse.triggers.map(
                TriggerModel.normalizeTrigger
            );
            hideAutoEatPresetForm();
            selectTask(createdTask);
            renderTriggerList();
            renderTriggerEditor();
            renderRunControls();
            Stowling.showToast("自动进食任务与饱食度监听器已创建");
        } catch (error) {
            Stowling.handleError(error, "创建自动进食预设失败");
        } finally {
            elements.createAutoEatPreset.disabled = false;
        }
    }

    /**
     * @returns {void}
     */
    function startNewTask() {
        startTaskTemplate("new");
    }

    /**
     * @returns {void}
     */
    function startCacheTask() {
        startTaskTemplate("cache");
    }

    /**
     * @returns {void}
     */
    function startSortTask() {
        startTaskTemplate("sort");
    }

    /**
     * @param {string} template
     * @returns {void}
     */
    function startTaskTemplate(template) {
        if (state.dirty
                && !window.confirm("当前任务有未保存修改，继续将放弃这些修改。")) {
            return;
        }
        if (state.modules.length === 0) {
            Stowling.showToast("任务模块尚未加载", true);
            return;
        }
        const draft = switchTaskTemplate(template);
        if (!draft) {
            Stowling.showToast("任务模板所需模块尚未加载", true);
            return;
        }
        state.selectedId = "";
        state.draft = draft;
        state.dirty = true;
        renderEditor();
        renderTaskList();
        elements.name.focus();
        elements.name.select();
        animateEditor();
    }

    /**
     * @param {string} template
     * @returns {object | null}
     */
    function switchTaskTemplate(template) {
        if (template === "cache") {
            return Model.cacheTask(state.modules);
        }
        if (template === "sort") {
            return Model.sortTask(state.modules);
        }
        return Model.newTask(state.modules);
    }

    /**
     * @returns {void}
     */
    function renderEditor() {
        const hasDraft = Boolean(state.draft);
        elements.empty.hidden = hasDraft;
        elements.form.hidden = !hasDraft;
        if (!hasDraft) {
            return;
        }
        elements.name.value = state.draft.name;
        elements.priority.value = state.draft.priority;
        updateSettingsLabel();
        elements.repeat.checked = state.draft.repeatWhenIdle;
        elements.repeat.disabled = state.draft.priority !== "BACKGROUND";
        elements.revision.textContent = state.selectedId
            ? Stowling.revisionLabel(state.revision)
            : "新任务";
        Editor.render(
            elements.steps,
            state.draft,
            state.modules,
            state.coordinates,
            state.regions,
            state.categoryZones,
            rerenderEditor
        );
        updateActions();
    }

    /**
     * @returns {void}
     */
    function updateSettingsLabel() {
        const behavior = state.draft.repeatWhenIdle
            ? " · 自动重排"
            : " · 执行一次";
        elements.settingsLabel.textContent =
            Model.priorityLabel(state.draft.priority) + behavior;
    }

    /**
     * @returns {void}
     */
    function animateEditor() {
        if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
            return;
        }
        elements.form.animate(
            [{ opacity: 0.5, transform: "translateY(6px)" }, { opacity: 1, transform: "none" }],
            { duration: 180, easing: "ease-out" }
        );
    }

    /**
     * @param {boolean} rerender
     * @returns {void}
     */
    function markDirty(rerender = false) {
        state.dirty = true;
        if (rerender) {
            renderEditor();
        } else {
            updateActions();
        }
    }

    /**
     * @param {boolean} rerender
     * @returns {void}
     */
    function rerenderEditor(rerender = false) {
        markDirty(Boolean(rerender));
    }

    /**
     * @returns {Array<string>}
     */
    function validationErrors() {
        const errors = [];
        const draft = state.draft;
        if (!draft) {
            return ["尚未选择任务"];
        }
        if (!String(draft.name || "").trim()) {
            errors.push("任务名称不能为空");
        }
        if (!Model.PRIORITIES.includes(draft.priority)) {
            errors.push("任务优先级无效");
        }
        if (draft.repeatWhenIdle && draft.priority !== "BACKGROUND") {
            errors.push("只有后台任务可以自动重新排队");
        }
        if (draft.steps.length === 0) {
            errors.push("任务至少需要一个步骤");
        }
        for (const [index, step] of draft.steps.entries()) {
            const module = state.modules.find(
                (candidate) => candidate.id === step.moduleId
            );
            if (!module) {
                errors.push(`步骤 ${index + 1} 使用了未知模块`);
                continue;
            }
            for (const parameter of module.parameters || []) {
                const value = String(
                    step.parameters[parameter.key] ?? ""
                ).trim();
                if (parameter.required && !value) {
                    errors.push(
                        `步骤 ${index + 1} 必须填写“${parameter.label}”`
                    );
                    continue;
                }
                if (!value) {
                    continue;
                }
                const exists = validateReference(parameter.kind, value);
                if (exists === false) {
                    errors.push(
                        `步骤 ${index + 1} 的“${parameter.label}”不存在`
                    );
                }
                if (parameter.kind === "integer") {
                    const integerError = validateInteger(parameter, value);
                    if (integerError) {
                        errors.push(
                            `步骤 ${index + 1} 的“${parameter.label}”`
                                + integerError
                        );
                    }
                }
                if (
                    parameter.kind === "select"
                    && !selectOptions(parameter).includes(value)
                ) {
                    errors.push(
                        `步骤 ${index + 1} 的“${parameter.label}”不是有效选项`
                    );
                }
            }
        }
        return errors;
    }

    /**
     * @param {string} kind
     * @param {string} value
     * @returns {boolean | null}
     */
    function validateReference(kind, value) {
        if (kind === "coordinate-point") {
            return state.coordinates.some((point) => point.id === value);
        }
        if (kind === "region-point") {
            return state.regions.some((region) => region.id === value);
        }
        if (kind === "category-zone-point") {
            return state.categoryZones.some((zone) => zone.id === value);
        }
        if (kind === "area-reference") {
            const separator = value.indexOf(":");
            if (separator <= 0) {
                return false;
            }
            const type = value.slice(0, separator);
            const id = value.slice(separator + 1);
            if (type === "coordinate") {
                return state.coordinates.some((point) => point.id === id);
            }
            if (type === "region") {
                return state.regions.some((region) => region.id === id);
            }
            return false;
        }
        return null;
    }

    /**
     * @param {object} parameter
     * @param {string} value
     * @returns {string}
     */
    function validateInteger(parameter, value) {
        const number = Number(value);
        if (!Number.isInteger(number)) {
            return "必须是整数";
        }
        const minimum = Number(parameter.min ?? parameter.minimum);
        if (Number.isFinite(minimum) && number < minimum) {
            return `不能小于 ${minimum}`;
        }
        const maximum = Number(parameter.max ?? parameter.maximum);
        if (Number.isFinite(maximum) && number > maximum) {
            return `不能大于 ${maximum}`;
        }
        return "";
    }

    /**
     * @param {object} parameter
     * @returns {Array<string>}
     */
    function selectOptions(parameter) {
        return Array.isArray(parameter.options)
            ? parameter.options.map((option) => String(option))
            : [];
    }

    /**
     * @param {object} run
     * @returns {boolean}
     */
    function isActiveRun(run) {
        return run.state === "RUNNING" || run.state === "WAITING";
    }

    /**
     * @param {string} state
     * @returns {string}
     */
    function runStateLabel(state) {
        const labels = {
            RUNNING: "运行中",
            WAITING: "等待中",
            SUCCEEDED: "已完成",
            FAILED: "失败",
            PREEMPTED: "已中断",
            CANCELLED: "已取消"
        };
        return labels[state] || String(state || "");
    }

    /**
     * @returns {void}
     */
    function updateActions() {
        const errors = validationErrors();
        elements.errors.replaceChildren();
        if (state.dirty && errors.length > 0) {
            elements.errors.append(
                Stowling.element("strong", "", "请修正以下问题：")
            );
            for (const error of errors) {
                elements.errors.append(Stowling.element("p", "", error));
            }
        }
        elements.save.disabled = !state.dirty || errors.length > 0;
        elements.discard.disabled = !state.dirty;
        elements.remove.disabled = !state.selectedId;
    }

    /**
     * @returns {Promise<void>}
     */
    async function saveTask() {
        const errors = validationErrors();
        if (errors.length > 0) {
            updateActions();
            return;
        }
        const payload = Model.payloadTask(state.draft);
        try {
            let response;
            if (state.selectedId) {
                response = await Stowling.request(
                    `/v1/tasks/${encodeURIComponent(state.selectedId)}`,
                    {
                        method: "PUT",
                        headers: { "Content-Type": "application/json" },
                        body: JSON.stringify({
                            revision: state.revision,
                            ...payload
                        })
                    }
                );
            } else {
                response = await Stowling.request("/v1/tasks", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify(payload)
                });
            }
            state.revision = String(response?.revision || state.revision);
            state.tasks = response.tasks.map(Model.normalizeTask);
            const saved = state.tasks.find((task) => task.name === payload.name);
            state.selectedId = saved?.id || "";
            state.draft = saved ? Model.normalizeTask(saved) : null;
            state.dirty = false;
            if (!state.tasks.some((task) => task.id === state.runTaskId)) {
                state.runTaskId = saved?.id || "";
            }
            renderEditor();
            renderTaskList();
            renderTriggerList();
            renderTriggerEditor();
            renderRunControls();
            Stowling.showToast("任务已保存");
        } catch (error) {
            Stowling.handleError(error, "保存任务失败");
        }
    }

    /**
     * @returns {Promise<void>}
     */
    async function deleteTask() {
        if (!state.selectedId) {
            return;
        }
        if (!window.confirm(`删除任务“${state.draft.name}”？`)) {
            return;
        }
        try {
            const response = await Stowling.request(
                `/v1/tasks/${encodeURIComponent(state.selectedId)}`,
                {
                    method: "DELETE",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ revision: state.revision })
                }
            );
            state.revision = String(response.revision || "");
            state.tasks = response.tasks.map(Model.normalizeTask);
            state.selectedId = "";
            state.draft = null;
            state.dirty = false;
            if (!state.tasks.some((task) => task.id === state.runTaskId)) {
                state.runTaskId = state.tasks[0]?.id || "";
            }
            renderTaskList();
            renderTriggerList();
            renderTriggerEditor();
            renderEditor();
            renderRunControls();
            Stowling.showToast("任务已删除");
        } catch (error) {
            Stowling.handleError(error, "删除任务失败");
        }
    }

    /**
     * @returns {Promise<void>}
     */
    async function runTask() {
        if (!state.runTaskId) {
            return;
        }
        try {
            const response = await Stowling.request(
                `/v1/tasks/${encodeURIComponent(state.runTaskId)}/runs`,
                {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify(elements.runPriority.value
                        ? { priority: elements.runPriority.value }
                        : {})
                }
            );
            await loadRuns();
            Stowling.showToast(
                response?.state === "QUEUED"
                    ? "任务已加入调度队列"
                    : "任务已开始运行"
            );
        } catch (error) {
            Stowling.handleError(error, "运行任务失败");
        }
    }

    /**
     * @returns {Promise<void>}
     */
    async function loadRuns() {
        try {
            const response = await Stowling.request("/v1/task-runs");
            state.runs = Array.isArray(response?.runs) ? response.runs : [];
            state.queue = Array.isArray(response?.queue)
                ? response.queue
                : [];
            state.triggerStatuses = Array.isArray(response?.triggers)
                ? response.triggers
                : [];
            renderTriggerList();
            renderTriggerStatuses();
            renderRuntime();
            renderQueue();
            updateRunActions();
        } catch (error) {
            Stowling.handleError(error, "读取运行状态失败");
        }
    }

    /**
     * @returns {void}
     */
    function renderTriggerStatuses() {
        elements.triggerStatusList.replaceChildren();
        if (state.triggerStatuses.length === 0) {
            elements.triggerStatusList.append(
                Stowling.emptyState("当前没有后台触发器")
            );
            return;
        }
        for (const status of state.triggerStatuses) {
            const card = Stowling.element("article", "task-card");
            const content = Stowling.element("div");
            content.append(
                Stowling.element("strong", "", status.triggerName),
                Stowling.element(
                    "span",
                    "module-level-tag trigger-state-tag",
                    triggerStateLabel(status.state)
                ),
                Stowling.element(
                    "p",
                    "muted compact-help",
                    `目标：${status.targetTaskName} · `
                        + `${Model.priorityLabel(status.priority)}队列`
                ),
                Stowling.element(
                    "span",
                    "muted compact-help",
                    status.message || ""
                )
            );
            card.append(content);
            elements.triggerStatusList.append(card);
        }
    }

    /**
     * @returns {void}
     */
    function renderRuntime() {
        elements.runtime.replaceChildren();
        const runs = state.runs.filter((run) => run.state !== "QUEUED");
        if (runs.length === 0) {
            elements.runtime.append(Stowling.emptyState("当前没有运行记录"));
            return;
        }
        for (const run of runs) {
            const card = Stowling.element("article", "task-card");
            const content = Stowling.element("div");
            content.append(
                Stowling.element(
                    "strong",
                    "",
                    `${run.taskName} · ${Model.priorityLabel(run.priority)}`
                ),
                Stowling.element(
                    "p",
                    "muted compact-help",
                    `${runStateLabel(run.state)} · `
                        + `${run.completedSteps}/${run.totalSteps} · `
                        + run.message
                ),
                Stowling.element(
                    "span",
                    "muted compact-help",
                    Stowling.formatDate(run.updatedAt)
                )
            );
            card.append(content);
            if (isActiveRun(run)) {
                const cancel = Stowling.element(
                    "button",
                    "secondary compact",
                    "取消任务"
                );
                cancel.type = "button";
                cancel.addEventListener("click", () => {
                    void cancelRun(run.runId);
                });
                card.append(cancel);
            }
            elements.runtime.append(card);
        }
    }

    /**
     * @returns {void}
     */
    function renderQueue() {
        elements.queueList.replaceChildren();
        if (state.queue.length === 0) {
            elements.queueList.append(
                Stowling.emptyState("当前没有等待任务")
            );
            return;
        }
        state.queue.forEach((run, index) => {
            const card = Stowling.element("article", "task-card");
            const content = Stowling.element("div");
            content.append(
                Stowling.element(
                    "strong",
                    "",
                    `${index + 1}. ${run.taskName}`
                ),
                Stowling.element(
                    "span",
                    "module-level-tag",
                    Model.priorityLabel(run.priority)
                ),
                Stowling.element(
                    "span",
                    "muted compact-help",
                    run.message || "排队等待"
                )
            );
            const cancel = Stowling.element(
                "button",
                "secondary compact",
                "取消排队"
            );
            cancel.type = "button";
            cancel.addEventListener("click", () => {
                void cancelRun(run.runId);
            });
            card.append(content, cancel);
            elements.queueList.append(card);
        });
    }

    /**
     * @param {string} runId
     * @returns {Promise<void>}
     */
    async function cancelRun(runId) {
        try {
            await Stowling.request(
                `/v1/task-runs/${encodeURIComponent(runId)}/cancel`,
                { method: "POST" }
            );
            await loadRuns();
        } catch (error) {
            Stowling.handleError(error, "取消任务失败");
        }
    }

    elements.search.addEventListener("input", () => {
        state.query = elements.search.value.trim().toLocaleLowerCase();
        renderTaskList();
    });
    elements.newTask.addEventListener("click", startNewTask);
    elements.newCacheTask.addEventListener("click", startCacheTask);
    elements.newSortTask.addEventListener("click", startSortTask);
    elements.newAutoEatTask.addEventListener("click", showAutoEatPresetForm);
    elements.cancelAutoEatPreset.addEventListener("click", hideAutoEatPresetForm);
    elements.autoEatForm.addEventListener("submit", (event) => {
        event.preventDefault();
        void createAutoEatPreset();
    });
    elements.name.addEventListener("input", () => {
        state.draft.name = elements.name.value;
        markDirty();
    });
    elements.priority.addEventListener("change", () => {
        state.draft.priority = elements.priority.value;
        if (state.draft.priority !== "BACKGROUND") {
            state.draft.repeatWhenIdle = false;
        }
        markDirty(true);
    });
    elements.repeat.addEventListener("change", () => {
        state.draft.repeatWhenIdle = elements.repeat.checked;
        updateSettingsLabel();
        markDirty();
    });
    elements.newTrigger.addEventListener("click", startNewTrigger);
    elements.triggerName.addEventListener("input", () => {
        state.triggerDraft.name = elements.triggerName.value;
        markTriggerDirty();
    });
    elements.triggerType.addEventListener("change", () => {
        state.triggerDraft.type = elements.triggerType.value;
        renderTriggerEditor();
        markTriggerDirty();
    });
    elements.triggerTargetTask.addEventListener("change", () => {
        state.triggerDraft.targetTaskId = elements.triggerTargetTask.value;
        markTriggerDirty();
    });
    elements.triggerPriority.addEventListener("change", () => {
        state.triggerDraft.priority = elements.triggerPriority.value;
        markTriggerDirty();
    });
    elements.triggerInterval.addEventListener("input", () => {
        state.triggerDraft.intervalTicks = Math.round(
            Number(elements.triggerInterval.value) * 20
        );
        markTriggerDirty();
    });
    elements.triggerCooldown.addEventListener("input", () => {
        state.triggerDraft.cooldownTicks = Math.round(
            Number(elements.triggerCooldown.value) * 20
        );
        markTriggerDirty();
    });
    elements.triggerCoordinate.addEventListener("change", () => {
        state.triggerDraft.coordinateId = elements.triggerCoordinate.value;
        markTriggerDirty();
    });
    elements.triggerCacheRegion.addEventListener("change", () => {
        state.triggerDraft.cacheRegionId =
            elements.triggerCacheRegion.value;
        markTriggerDirty();
    });
    elements.triggerItemFilter.addEventListener("input", () => {
        state.triggerDraft.itemFilter = elements.triggerItemFilter.value;
        markTriggerDirty();
    });
    elements.triggerFoodThreshold.addEventListener("input", () => {
        state.triggerDraft.foodLevelThreshold = Number(
            elements.triggerFoodThreshold.value
        );
        markTriggerDirty();
    });
    elements.addStep.addEventListener("click", () => {
        state.draft.steps.push(Model.newStep(
            Model.defaultModule(state.modules)
        ));
        markDirty(true);
    });
    elements.form.addEventListener("submit", (event) => {
        event.preventDefault();
        void saveTask();
    });
    elements.triggerForm.addEventListener("submit", (event) => {
        event.preventDefault();
        void saveTrigger();
    });
    elements.runSelect.addEventListener("change", () => {
        state.runTaskId = elements.runSelect.value;
        updateRunActions();
    });
    elements.discard.addEventListener("click", () => {
        const task = state.tasks.find((entry) => entry.id === state.selectedId);
        if (task) {
            selectTask(task);
        } else {
            state.draft = null;
            state.dirty = false;
            renderEditor();
            renderTaskList();
        }
    });
    elements.run.addEventListener("click", () => void runTask());
    elements.remove.addEventListener("click", () => void deleteTask());
    elements.discardTrigger.addEventListener("click", () => {
        const trigger = state.triggers.find(
            (entry) => entry.id === state.selectedTriggerId
        );
        if (trigger) {
            selectTrigger(trigger);
        } else {
            state.triggerDraft = null;
            state.triggerDirty = false;
            renderTriggerList();
            renderTriggerEditor();
        }
    });
    elements.deleteTrigger.addEventListener("click", () => {
        void deleteTrigger();
    });
    elements.refreshRuns.addEventListener("click", () => void loadRuns());
    Stowling.onConnected(() => loadAll());
    window.setInterval(() => {
        if (Stowling.isConnected()) {
            void loadRuns();
        }
    }, 3000);
    Stowling.mountPage("tasks");
})();
