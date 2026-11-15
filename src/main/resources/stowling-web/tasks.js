(() => {
    "use strict";

    const Stowling = window.Stowling;
    const Model = window.StowlingTaskModel;
    const Editor = window.StowlingTaskEditor;
    const state = {
        revision: "",
        tasks: [],
        modules: [],
        coordinates: [],
        regions: [],
        categoryZones: [],
        selectedId: "",
        runTaskId: "",
        draft: null,
        dirty: false,
        runs: [],
        queue: [],
        watches: [],
        query: ""
    };

    const elements = {
        newTask: document.querySelector("#new-task-button"),
        newCacheTask: document.querySelector("#new-cache-task-button"),
        newSortTask: document.querySelector("#new-sort-task-button"),
        newWatchTask: document.querySelector("#new-watch-task-button"),
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
        watchEnabled: document.querySelector("#task-watch-enabled"),
        watchFields: document.querySelector("#task-watch-fields"),
        watchCoordinate: document.querySelector("#task-watch-coordinate"),
        watchFilter: document.querySelector("#task-watch-filter"),
        watchInterval: document.querySelector("#task-watch-interval"),
        watchCooldown: document.querySelector("#task-watch-cooldown"),
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
        runtime: document.querySelector("#runtime-list"),
        watchList: document.querySelector("#task-watch-list"),
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
                modules,
                coordinates,
                regions,
                categoryZones,
                runs
            ] = await Promise.all([
                Stowling.request("/v1/tasks"),
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
            state.watches = Array.isArray(runs?.watches)
                ? runs.watches
                : [];
            renderTaskList();
            renderModuleCatalog();
            renderRunControls();
            if (!state.draft && state.tasks.length > 0) {
                selectTask(state.tasks[0]);
            }
            renderRuntime();
            renderWatches();
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
            ["COMPOSITE", "复合任务"]
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
                if (task.trigger) {
                    button.append(Stowling.element(
                        "span",
                        "module-level-tag",
                        "监听"
                    ));
                }
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
    function renderModuleCatalog() {
        elements.moduleCatalog.replaceChildren();
        elements.moduleCount.textContent = `${state.modules.length} 个`;
        if (state.modules.length === 0) {
            elements.moduleCatalog.append(
                Stowling.emptyState("当前没有可用模块")
            );
            return;
        }
        const groups = [
            ["BASIC", "基础模块"],
            ["COMPOSITE", "复合模块"]
        ];
        for (const [level, label] of groups) {
            const modules = state.modules.filter(
                (module) => module.level === level
            );
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
     * @returns {HTMLElement}
     */
    function createModuleCard(module) {
        const card = Stowling.element("details", "module-card");
        const summary = Stowling.element("summary", "");
        summary.append(
            Stowling.element("strong", "", module.name),
            Stowling.element(
                "span", "module-level-tag", module.level === "BASIC" ? "基础" : "复合"
            )
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
            Stowling.element("p", "", module.description || ""),
            Stowling.element(
                "p",
                "compact-help",
                `参数：${parameterText}`
            )
        );
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
            ["COMPOSITE", "复合任务"]
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
     * @returns {void}
     */
    function startWatchTask() {
        startTaskTemplate("watch");
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
        if (template === "watch") {
            return Model.watchTask(state.modules);
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
        elements.repeat.disabled = state.draft.priority !== "BACKGROUND"
            || Boolean(state.draft.trigger);
        renderWatchControls();
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
        const behavior = state.draft.trigger
            ? " · 掉落物监听"
            : state.draft.repeatWhenIdle
                ? " · 自动重排"
                : " · 执行一次";
        elements.settingsLabel.textContent =
            Model.priorityLabel(state.draft.priority) + behavior;
    }

    /**
     * @returns {void}
     */
    function renderWatchControls() {
        const trigger = state.draft.trigger;
        elements.watchEnabled.checked = Boolean(trigger);
        elements.watchFields.hidden = !trigger;
        elements.watchCoordinate.replaceChildren(
            new Option("请选择坐标", "")
        );
        for (const coordinate of state.coordinates) {
            elements.watchCoordinate.append(
                new Option(coordinate.name, coordinate.id)
            );
        }
        if (!trigger) {
            return;
        }
        elements.watchCoordinate.value = trigger.coordinateId;
        elements.watchFilter.value = trigger.itemFilter;
        elements.watchInterval.value = String(
            Math.max(0.05, trigger.intervalTicks / 20)
        );
        elements.watchCooldown.value = String(
            Math.max(0, trigger.cooldownTicks / 20)
        );
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
        if (draft.trigger) {
            if (draft.repeatWhenIdle) {
                errors.push("掉落物监听任务不能同时启用自动重排");
            }
            if (!draft.trigger.coordinateId) {
                errors.push("掉落物监听必须选择监测坐标");
            } else if (!state.coordinates.some(
                (point) => point.id === draft.trigger.coordinateId
            )) {
                errors.push("掉落物监听的监测坐标不存在");
            }
            if (!Number.isInteger(draft.trigger.intervalTicks)
                    || draft.trigger.intervalTicks < 1
                    || draft.trigger.intervalTicks > 1200) {
                errors.push("检测间隔必须在 0.05 到 60 秒之间");
            }
            if (!Number.isInteger(draft.trigger.cooldownTicks)
                    || draft.trigger.cooldownTicks < 0
                    || draft.trigger.cooldownTicks > 72000) {
                errors.push("监听冷却必须在 0 到 3600 秒之间");
            }
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
            state.watches = Array.isArray(response?.watches)
                ? response.watches
                : [];
            renderWatches();
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
    function renderWatches() {
        elements.watchList.replaceChildren();
        if (state.watches.length === 0) {
            elements.watchList.append(
                Stowling.emptyState("当前没有掉落物监听任务")
            );
            return;
        }
        for (const watch of state.watches) {
            const card = Stowling.element("article", "task-card");
            const content = Stowling.element("div");
            const filter = watch.itemFilter
                ? ` · ${watch.itemFilter}`
                : " · 全部物品";
            content.append(
                Stowling.element(
                    "strong",
                    "",
                    watch.taskName
                ),
                Stowling.element(
                    "span",
                    "module-level-tag",
                    watchStateLabel(watch.state)
                ),
                Stowling.element(
                    "p",
                    "muted compact-help",
                    `${watch.coordinateName}${filter} · ${watch.message}`
                )
            );
            card.append(content);
            elements.watchList.append(card);
        }
    }

    /**
     * @param {string} watchState
     * @returns {string}
     */
    function watchStateLabel(watchState) {
        const labels = {
            IDLE: "空闲监听",
            RUNNING: "触发执行中",
            COOLDOWN: "冷却中",
            ERROR: "监听异常"
        };
        return labels[watchState] || String(watchState || "");
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
    elements.newWatchTask.addEventListener("click", startWatchTask);
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
    elements.watchEnabled.addEventListener("change", () => {
        if (elements.watchEnabled.checked) {
            const collectStep = state.draft.steps.find(
                (step) => step.moduleId === "COLLECT_DROPPED_ITEMS"
            );
            state.draft.trigger = {
                type: "DROPPED_ITEMS",
                coordinateId: String(
                    collectStep?.parameters?.coordinateId || ""
                ),
                itemFilter: "",
                intervalTicks: 20,
                cooldownTicks: 100
            };
            state.draft.priority = "BACKGROUND";
            state.draft.repeatWhenIdle = false;
        } else {
            state.draft.trigger = null;
        }
        markDirty(true);
    });
    elements.watchCoordinate.addEventListener("change", () => {
        if (!state.draft.trigger) {
            return;
        }
        state.draft.trigger.coordinateId = elements.watchCoordinate.value;
        for (const step of state.draft.steps) {
            if (step.moduleId === "COLLECT_DROPPED_ITEMS"
                    && !step.parameters.coordinateId) {
                step.parameters.coordinateId = elements.watchCoordinate.value;
            }
        }
        markDirty(true);
    });
    elements.watchFilter.addEventListener("input", () => {
        if (state.draft.trigger) {
            state.draft.trigger.itemFilter = elements.watchFilter.value;
            markDirty();
        }
    });
    elements.watchInterval.addEventListener("input", () => {
        if (state.draft.trigger) {
            state.draft.trigger.intervalTicks = Math.round(
                Number(elements.watchInterval.value) * 20
            );
            markDirty();
        }
    });
    elements.watchCooldown.addEventListener("input", () => {
        if (state.draft.trigger) {
            state.draft.trigger.cooldownTicks = Math.round(
                Number(elements.watchCooldown.value) * 20
            );
            markDirty();
        }
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
    elements.refreshRuns.addEventListener("click", () => void loadRuns());
    Stowling.onConnected(() => loadAll());
    window.setInterval(() => {
        if (Stowling.isConnected()) {
            void loadRuns();
        }
    }, 3000);
    Stowling.mountPage("tasks");
})();
