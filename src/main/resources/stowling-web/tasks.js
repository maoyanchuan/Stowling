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
        selectedId: "",
        runTaskId: "",
        draft: null,
        dirty: false,
        runs: []
    };

    const elements = {
        newTask: document.querySelector("#new-task-button"),
        newCacheTask: document.querySelector("#new-cache-task-button"),
        newSortTask: document.querySelector("#new-sort-task-button"),
        list: document.querySelector("#task-list"),
        listMessage: document.querySelector("#task-list-message"),
        empty: document.querySelector("#task-empty"),
        form: document.querySelector("#task-form"),
        name: document.querySelector("#task-name"),
        steps: document.querySelector("#task-steps"),
        addStep: document.querySelector("#add-step-button"),
        moduleCatalog: document.querySelector("#task-module-catalog"),
        moduleCount: document.querySelector("#task-module-count"),
        errors: document.querySelector("#task-errors"),
        revision: document.querySelector("#task-revision"),
        save: document.querySelector("#save-task-button"),
        discard: document.querySelector("#discard-task-button"),
        runSelect: document.querySelector("#run-task-select"),
        run: document.querySelector("#run-task-button"),
        remove: document.querySelector("#delete-task-button"),
        runtime: document.querySelector("#runtime-list"),
        refreshRuns: document.querySelector("#refresh-runs-button")
    };

    /**
     * @returns {Promise<void>}
     */
    async function loadAll() {
        try {
            const [tasks, modules, coordinates, regions, runs] = await Promise.all([
                Stowling.request("/v1/tasks"),
                Stowling.request("/v1/task-modules"),
                Stowling.request("/v1/coordinates"),
                Stowling.request("/v1/regions"),
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
            state.runs = Array.isArray(runs?.runs) ? runs.runs : [];
            renderTaskList();
            renderModuleCatalog();
            renderRunControls();
            renderRuntime();
        } catch (error) {
            Stowling.handleError(error, "读取任务失败");
        }
    }

    /**
     * @returns {void}
     */
    function renderTaskList() {
        elements.list.replaceChildren();
        if (state.tasks.length === 0) {
            elements.list.append(Stowling.emptyState("尚未创建任务"));
            return;
        }
        for (const task of state.tasks) {
            const button = Stowling.element("button", "task-card task-select", task.name);
            button.type = "button";
            button.classList.toggle("active", task.id === state.selectedId);
            button.addEventListener("click", () => selectTask(task));
            elements.list.append(button);
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
        for (const module of state.modules) {
            const card = Stowling.element("article", "module-card");
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
                Stowling.element("code", "", module.id),
                Stowling.element("strong", "", module.name),
                Stowling.element("p", "", module.description || ""),
                Stowling.element(
                    "p",
                    "compact-help",
                    `参数：${parameterText}`
                )
            );
            elements.moduleCatalog.append(card);
        }
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
        state.draft = Model.normalizeTask(task);
        state.dirty = false;
        renderEditor();
        renderTaskList();
    }

    /**
     * @returns {void}
     */
    function renderRunControls() {
        const current = state.runTaskId;
        elements.runSelect.replaceChildren(new Option("请选择任务", ""));
        for (const task of state.tasks) {
            elements.runSelect.append(new Option(task.name, task.id));
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
        const busy = state.runs.some(isActiveRun);
        elements.runSelect.disabled = state.tasks.length === 0 || busy;
        elements.run.disabled = !state.runTaskId || busy;
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
        elements.revision.textContent = state.selectedId
            ? Stowling.revisionLabel(state.revision)
            : "新任务";
        Editor.render(
            elements.steps,
            state.draft,
            state.modules,
            state.coordinates,
            state.regions,
            rerenderEditor
        );
        updateActions();
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
            await Stowling.request(
                `/v1/tasks/${encodeURIComponent(state.runTaskId)}/runs`,
                { method: "POST" }
            );
            await loadRuns();
            Stowling.showToast("任务已开始运行");
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
            renderRuntime();
            updateRunActions();
        } catch (error) {
            Stowling.handleError(error, "读取运行状态失败");
        }
    }

    /**
     * @returns {void}
     */
    function renderRuntime() {
        elements.runtime.replaceChildren();
        if (state.runs.length === 0) {
            elements.runtime.append(Stowling.emptyState("当前没有运行记录"));
            return;
        }
        for (const run of state.runs) {
            const card = Stowling.element("article", "task-card");
            const content = Stowling.element("div");
            content.append(
                Stowling.element("strong", "", run.taskName),
                Stowling.element(
                    "p",
                    "muted compact-help",
                    `${run.state} · ${run.completedSteps}/${run.totalSteps} · ${run.message}`
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

    elements.newTask.addEventListener("click", startNewTask);
    elements.newCacheTask.addEventListener("click", startCacheTask);
    elements.newSortTask.addEventListener("click", startSortTask);
    elements.name.addEventListener("input", () => {
        state.draft.name = elements.name.value;
        markDirty();
    });
    elements.addStep.addEventListener("click", () => {
        state.draft.steps.push(Model.newStep(state.modules[0]));
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
