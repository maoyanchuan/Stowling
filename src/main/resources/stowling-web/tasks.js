(() => {
    "use strict";

    const Stowling = window.Stowling;
    const STATUS_LABELS = new Map([
        ["PENDING", "等待中"],
        ["RUNNING", "运行中"],
        ["WAITING", "等待条件"],
        ["PAUSED", "已暂停"],
        ["SUCCEEDED", "已完成"],
        ["FAILED", "失败"],
        ["CANCELLED", "已取消"]
    ]);
    const FIELD_DEFINITIONS = Object.freeze({
        NAVIGATE: [
            field("coordinateId", "目标命名坐标", "coordinate", true)
        ],
        WAIT: [
            field("durationSeconds", "等待秒数", "positive-integer", true)
        ],
        RUN_PICKUP: [
            field("workflowId", "拾取流程", "pickup", true),
            field("regionId", "只运行区域", "pickup-region", false)
        ],
        TAKE_ITEMS: [
            field("sourceContainerId", "来源容器", "container", true),
            field("itemId", "物品 ID", "item", true),
            field("count", "数量", "positive-integer", true)
        ],
        PUT_ITEMS: [
            field("destinationContainerId", "目标容器", "container", true),
            field("itemId", "物品 ID", "item", true),
            field("count", "数量", "positive-integer", true)
        ],
        TRANSFER_ITEMS: [
            field("sourceContainerId", "来源容器", "container", true),
            field("destinationContainerId", "目标容器", "container", true),
            field("itemId", "物品 ID", "item", true),
            field("count", "数量", "positive-integer", true)
        ],
        CHECK_INVENTORY: [
            field("itemId", "物品 ID", "item", true),
            field("count", "期望数量", "positive-integer", true)
        ],
        ACQUIRE_MATERIALS: [
            field("itemId", "目标物品 ID", "item", true),
            field("count", "缺少数量", "positive-integer", true)
        ],
        CRAFT_ITEM: [
            field("itemId", "制作物品 ID", "item", true),
            field("count", "制作数量", "positive-integer", true)
        ]
    });
    const FALLBACK_MODULES = Object.freeze([
        descriptor("NAVIGATE", "前往命名坐标", "使用 Baritone 导航到命名坐标。"),
        descriptor("WAIT", "等待", "暂停指定秒数，并继续响应暂停与取消。"),
        descriptor("RUN_PICKUP", "运行拾取", "运行完整拾取流程或指定一个区域。"),
        descriptor("TAKE_ITEMS", "从容器取物", "从指定容器取物到机器人背包。"),
        descriptor("PUT_ITEMS", "向容器放物", "把指定物品放入目标容器。"),
        descriptor("TRANSFER_ITEMS", "容器搬运", "在两个容器之间搬运指定物品。"),
        descriptor("CHECK_INVENTORY", "检查库存", "检查玩家或容器内物品数量。"),
        descriptor("ACQUIRE_MATERIALS", "获取缺少原料", "计算并补齐制作所需原料。"),
        descriptor("CRAFT_ITEM", "制作物品", "使用当前配方制作指定物品。")
    ]);

    const elements = {
        form: document.querySelector("#workflow-form"),
        workflowList: document.querySelector("#workflow-list"),
        workflowEditor: document.querySelector("#workflow-editor"),
        workflowEmpty: document.querySelector("#workflow-empty"),
        workflowId: document.querySelector("#workflow-id"),
        workflowName: document.querySelector("#workflow-name"),
        stepList: document.querySelector("#step-list"),
        addWorkflow: document.querySelector("#add-workflow-button"),
        addStep: document.querySelector("#add-step-button"),
        discard: document.querySelector("#discard-workflow-button"),
        save: document.querySelector("#save-workflow-button"),
        errors: document.querySelector("#workflow-errors"),
        status: document.querySelector("#workflow-status"),
        revision: document.querySelector("#workflow-revision"),
        launchSelect: document.querySelector("#launch-workflow"),
        start: document.querySelector("#start-task-button"),
        launchMessage: document.querySelector("#launch-message"),
        pause: document.querySelector("#pause-task-button"),
        resume: document.querySelector("#resume-task-button"),
        refresh: document.querySelector("#refresh-task-button"),
        taskList: document.querySelector("#task-list"),
        moduleCatalog: document.querySelector("#module-catalog"),
        moduleCount: document.querySelector("#module-count")
    };

    const state = {
        snapshot: null,
        draft: null,
        selectedIndex: -1,
        dirty: false,
        editableWorkflowIds: new Set(),
        modules: [...FALLBACK_MODULES],
        coordinates: [],
        pickups: [],
        containers: []
    };

    /**
     * @param {string} key
     * @param {string} label
     * @param {string} kind
     * @param {boolean} required
     * @returns {object}
     */
    function field(key, label, kind, required) {
        return { key, label, kind, required };
    }

    /**
     * @param {string} type
     * @param {string} name
     * @param {string} description
     * @returns {object}
     */
    function descriptor(type, name, description) {
        return { type, name, description, parameters: FIELD_DEFINITIONS[type] || [] };
    }

    /**
     * @returns {Promise<void>}
     */
    async function loadWorkflows() {
        elements.status.textContent = "正在读取任务配置…";
        const snapshot = await Stowling.request("/v1/config/automation-workflows");
        const workflows = Array.isArray(snapshot?.automationWorkflows)
            ? snapshot.automationWorkflows.map(toDraftWorkflow)
            : [];
        state.snapshot = Stowling.clone({
            revision: String(snapshot?.revision || ""),
            automationWorkflows: workflows
        });
        state.draft = Stowling.clone(state.snapshot);
        state.editableWorkflowIds = new Set();
        state.dirty = false;
        state.selectedIndex = state.draft.automationWorkflows.length > 0 ? 0 : -1;
        elements.revision.textContent =
            `revision: ${state.snapshot.revision.slice(0, 12) || "--"}`;
        elements.revision.title = state.snapshot.revision;
        elements.status.textContent = "任务配置已与配置文件同步";
        showErrors([]);
        renderWorkflowList();
        renderWorkflowEditor();
        renderLaunchOptions();
    }

    /**
     * @returns {Promise<void>}
     */
    async function loadModules() {
        try {
            const value = await Stowling.request("/v1/automation-modules");
            const remote = Array.isArray(value) ? value : value?.modules;
            if (Array.isArray(remote) && remote.length > 0) {
                state.modules = mergeModules(remote);
            }
        } catch (error) {
            if (!(error instanceof Stowling.ApiError) || error.status !== 404) {
                throw error;
            }
        }
        renderModuleCatalog();
    }

    /**
     * @param {Array<object>} remote
     * @returns {Array<object>}
     */
    function mergeModules(remote) {
        const result = new Map();
        for (const module of FALLBACK_MODULES) {
            result.set(module.type, module);
        }
        for (const value of remote) {
            const type = String(value.type || value.id || "").toUpperCase();
            if (!type) {
                continue;
            }
            const fallback = result.get(type);
            result.set(type, {
                type,
                name: String(
                    value.displayName
                        || value.name
                        || value.label
                        || fallback?.name
                        || type
                ),
                description: String(
                    value.description || fallback?.description || "后端提供的机器人模块"
                ),
                parameters: Array.isArray(value.parameters)
                    ? normalizeParameters(value.parameters, type)
                    : FIELD_DEFINITIONS[type] || []
            });
        }
        return [...result.values()];
    }

    /**
     * @param {Array<unknown>} parameters
     * @param {string} type
     * @returns {Array<object>}
     */
    function normalizeParameters(parameters, type) {
        const known = new Map((FIELD_DEFINITIONS[type] || []).map((item) => [item.key, item]));
        return parameters.map((value) => {
            if (typeof value === "string") {
                return known.get(value) || field(value, value, "text", true);
            }
            const key = String(value.key || value.id || value.name || "");
            const fallback = known.get(key);
            return {
                key,
                label: String(value.label || value.name || fallback?.label || key),
                kind: String(value.kind || fallback?.kind || "text"),
                required: Boolean(value.required ?? fallback?.required ?? true)
            };
        }).filter((value) => value.key);
    }

    /**
     * @returns {Promise<void>}
     */
    async function loadReferences() {
        const results = await Promise.allSettled([
            Stowling.request("/v1/config/coordinate-points"),
            Stowling.request("/v1/pickup-workflows"),
            Stowling.request("/v1/containers"),
            Stowling.request("/v1/config/pickup")
        ]);
        state.coordinates = results[0].status === "fulfilled"
            ? results[0].value?.coordinatePoints || []
            : state.coordinates;
        state.pickups = results[1].status === "fulfilled"
            ? results[1].value || []
            : state.pickups;
        const runtimeContainers = results[2].status === "fulfilled"
            ? results[2].value || []
            : [];
        const caches = results[3].status === "fulfilled"
            ? results[3].value?.cacheContainers || []
            : [];
        const byId = new Map();
        for (const container of [...runtimeContainers, ...caches]) {
            if (container?.id) {
                byId.set(String(container.id), container);
            }
        }
        state.containers = [...byId.values()];
        renderWorkflowEditor();
    }

    /**
     * @returns {void}
     */
    function renderWorkflowList() {
        elements.workflowList.replaceChildren();
        if (!state.draft) {
            return;
        }
        const workflows = state.draft.automationWorkflows;
        workflows.forEach((workflow, index) => {
            const card = Stowling.element("article", "workflow-list-card");
            if (index === state.selectedIndex) {
                card.classList.add("selected");
            }
            const select = Stowling.element(
                "button",
                "workflow-select-button",
                workflow.name || workflow.id || "未命名任务"
            );
            select.type = "button";
            select.addEventListener("click", () => selectWorkflow(index));
            const meta = Stowling.element(
                "span",
                "workflow-list-meta",
                `${workflow.id || "无 ID"} · ${workflow.steps?.length || 0} 步`
            );
            const actions = Stowling.element("div", "move-buttons");
            actions.append(
                listMoveButton("↑", index, -1),
                listMoveButton("↓", index, 1),
                workflowDeleteButton(index)
            );
            const body = Stowling.element("div", "workflow-list-body");
            body.append(select, meta);
            card.append(body, actions);
            elements.workflowList.append(card);
        });
        if (workflows.length === 0) {
            elements.workflowList.append(
                Stowling.emptyState("还没有自定义任务，点击“新增任务”开始")
            );
        }
    }

    /**
     * @param {string} text
     * @param {number} index
     * @param {number} offset
     * @returns {HTMLButtonElement}
     */
    function listMoveButton(text, index, offset) {
        const button = Stowling.element("button", "secondary compact", text);
        button.type = "button";
        button.title = offset < 0 ? "任务上移" : "任务下移";
        button.addEventListener("click", () => moveWorkflow(index, offset));
        return button;
    }

    /**
     * @param {number} index
     * @returns {HTMLButtonElement}
     */
    function workflowDeleteButton(index) {
        const button = Stowling.element("button", "ghost compact danger-action", "删除");
        button.type = "button";
        button.addEventListener("click", () => removeWorkflow(index));
        return button;
    }

    /**
     * @param {number} index
     * @returns {void}
     */
    function selectWorkflow(index) {
        state.selectedIndex = index;
        renderWorkflowList();
        renderWorkflowEditor();
    }

    /**
     * @param {number} index
     * @param {number} offset
     * @returns {void}
     */
    function moveWorkflow(index, offset) {
        const workflows = state.draft.automationWorkflows;
        const target = index + offset;
        if (target < 0 || target >= workflows.length) {
            return;
        }
        const [workflow] = workflows.splice(index, 1);
        workflows.splice(target, 0, workflow);
        if (state.selectedIndex === index) {
            state.selectedIndex = target;
        } else if (state.selectedIndex === target) {
            state.selectedIndex = index;
        }
        markDirty();
        renderWorkflowList();
    }

    /**
     * @param {number} index
     * @returns {void}
     */
    function removeWorkflow(index) {
        const workflow = state.draft.automationWorkflows[index];
        if (!window.confirm(`删除任务“${workflow.name || workflow.id}”？`)) {
            return;
        }
        state.draft.automationWorkflows.splice(index, 1);
        state.editableWorkflowIds.delete(workflow.id);
        if (state.selectedIndex >= state.draft.automationWorkflows.length) {
            state.selectedIndex = state.draft.automationWorkflows.length - 1;
        }
        markDirty();
        renderWorkflowList();
        renderWorkflowEditor();
    }

    /**
     * @returns {void}
     */
    function addWorkflow() {
        if (!state.draft) {
            Stowling.showToast("任务配置尚未读取完成", true);
            return;
        }
        const workflow = {
            id: nextWorkflowId(),
            name: "新自动化任务",
            steps: []
        };
        state.draft.automationWorkflows.push(workflow);
        state.editableWorkflowIds.add(workflow.id);
        state.selectedIndex = state.draft.automationWorkflows.length - 1;
        markDirty();
        renderWorkflowList();
        renderWorkflowEditor();
        elements.workflowName.focus();
        elements.workflowName.select();
    }

    /**
     * @returns {string}
     */
    function nextWorkflowId() {
        const used = new Set(
            state.draft.automationWorkflows.map((workflow) => String(workflow.id || ""))
        );
        let index = 1;
        while (used.has(`automation-${index}`)) {
            index += 1;
        }
        return `automation-${index}`;
    }

    /**
     * @returns {void}
     */
    function renderWorkflowEditor() {
        const workflow = selectedWorkflow();
        elements.workflowEmpty.hidden = Boolean(workflow);
        elements.workflowEditor.hidden = !workflow;
        if (!workflow) {
            elements.stepList.replaceChildren();
            return;
        }
        elements.workflowId.value = String(workflow.id || "");
        elements.workflowId.disabled = !state.editableWorkflowIds.has(workflow.id);
        elements.workflowName.value = String(workflow.name || "");
        renderSteps();
    }

    /**
     * @returns {object | null}
     */
    function selectedWorkflow() {
        if (!state.draft || state.selectedIndex < 0) {
            return null;
        }
        return state.draft.automationWorkflows[state.selectedIndex] || null;
    }

    /**
     * @returns {void}
     */
    function renderSteps() {
        elements.stepList.replaceChildren();
        const workflow = selectedWorkflow();
        if (!workflow) {
            return;
        }
        workflow.steps ||= [];
        workflow.steps.forEach((step, index) => {
            elements.stepList.append(createStepCard(workflow, step, index));
        });
        if (workflow.steps.length === 0) {
            elements.stepList.append(Stowling.emptyState("还没有步骤"));
        }
        updateSaveState();
    }

    /**
     * @param {object} workflow
     * @param {object} step
     * @param {number} index
     * @returns {HTMLElement}
     */
    function createStepCard(workflow, step, index) {
        const card = Stowling.element("article", "step-card");
        const heading = Stowling.element("div", "step-card-heading");
        const number = Stowling.element("span", "step-number", String(index + 1));
        const identity = Stowling.element("div", "step-identity");
        identity.append(
            Stowling.element("strong", "", moduleFor(step.type)?.name || step.type || "模块"),
            Stowling.element("code", "", String(step.id || "无步骤 ID"))
        );
        const actions = Stowling.element("div", "move-buttons");
        actions.append(
            stepMoveButton("↑", index, -1, workflow),
            stepMoveButton("↓", index, 1, workflow),
            stepDeleteButton(index, workflow)
        );
        heading.append(number, identity, actions);

        const topFields = Stowling.element("div", "editor-fields");
        topFields.append(fieldWrap("模块类型", moduleSelect(step, index, workflow)));
        const fields = Stowling.element("div", "step-fields");
        const definitions = parametersFor(step.type);
        for (const definition of definitions) {
            fields.append(fieldWrap(
                definition.label,
                parameterControl(step, definition, index)
            ));
        }
        if (definitions.length === 0) {
            fields.append(Stowling.element(
                "p",
                "muted compact-help",
                "此后端模块没有公开可编辑参数。"
            ));
        }
        card.append(heading, topFields, fields);
        return card;
    }

    /**
     * @param {string} label
     * @param {HTMLElement} control
     * @returns {HTMLElement}
     */
    function fieldWrap(label, control) {
        const wrapper = Stowling.element("div", "editor-field");
        wrapper.append(Stowling.element("label", "", label), control);
        return wrapper;
    }

    /**
     * @param {object} step
     * @param {number} index
     * @param {object} workflow
     * @returns {HTMLSelectElement}
     */
    function moduleSelect(step, index, workflow) {
        const select = document.createElement("select");
        select.id = `step-module-${index}`;
        select.setAttribute("aria-label", `第 ${index + 1} 步模块`);
        for (const module of state.modules) {
            const option = document.createElement("option");
            option.value = module.type;
            option.textContent = `${module.name} · ${module.type}`;
            select.append(option);
        }
        select.value = String(step.type || "");
        select.addEventListener("change", () => {
            step.type = select.value;
            step.parameters = defaultParameters(step.type);
            markDirty(true);
            renderWorkflowList();
            void workflow;
        });
        return select;
    }

    /**
     * @param {string} type
     * @returns {object}
     */
    function defaultParameters(type) {
        const result = {};
        for (const definition of parametersFor(type)) {
            result[definition.key] = definition.kind.includes("integer") ? 1 : "";
        }
        return result;
    }

    /**
     * @param {string} type
     * @returns {Array<object>}
     */
    function parametersFor(type) {
        return moduleFor(type)?.parameters || FIELD_DEFINITIONS[type] || [];
    }

    /**
     * @param {string} type
     * @returns {object | undefined}
     */
    function moduleFor(type) {
        return state.modules.find((module) => module.type === type);
    }

    /**
     * @param {object} step
     * @param {object} definition
     * @param {number} stepIndex
     * @returns {HTMLElement}
     */
    function parameterControl(step, definition, stepIndex) {
        step.parameters ||= {};
        const value = step.parameters[definition.key] ?? "";
        let control;
        if (["coordinate", "pickup", "pickup-region", "container"].includes(definition.kind)) {
            control = referenceSelect(definition, step, stepIndex, value);
        } else {
            control = document.createElement("input");
            control.type = ["positive-integer", "non-negative-integer"].includes(definition.kind)
                ? "number"
                : "text";
            if (control.type === "number") {
                control.min = definition.kind === "positive-integer" ? "1" : "0";
                control.step = "1";
            } else {
                control.placeholder = definition.kind === "item"
                    ? "minecraft:iron_ingot"
                    : "";
            }
            control.value = String(value);
            control.addEventListener("input", () => {
                step.parameters[definition.key] = control.type === "number"
                    ? (control.value === "" ? "" : Number(control.value))
                    : control.value;
                markDirty();
                updateSaveState();
            });
        }
        control.id = `step-${stepIndex}-${definition.key}`;
        return control;
    }

    /**
     * @param {object} definition
     * @param {object} step
     * @param {number} stepIndex
     * @param {unknown} value
     * @returns {HTMLSelectElement}
     */
    function referenceSelect(definition, step, stepIndex, value) {
        const select = document.createElement("select");
        const placeholder = document.createElement("option");
        placeholder.value = "";
        placeholder.textContent = definition.required ? "请选择" : "不指定";
        select.append(placeholder);
        const entries = optionsFor(definition.kind, step);
        for (const entry of entries) {
            const option = document.createElement("option");
            option.value = String(entry.id);
            option.textContent = String(entry.label);
            select.append(option);
        }
        if (value && !entries.some((entry) => entry.id === value)) {
            const existing = document.createElement("option");
            existing.value = String(value);
            existing.textContent = String(value);
            select.append(existing);
        }
        select.value = String(value || "");
        select.addEventListener("change", () => {
            step.parameters[definition.key] = select.value;
            if (definition.kind === "pickup") {
                step.parameters.regionId = "";
            }
            markDirty(true);
        });
        select.id = `step-${stepIndex}-${definition.key}`;
        return select;
    }

    /**
     * @param {string} kind
     * @param {object} step
     * @returns {Array<object>}
     */
    function optionsFor(kind, step) {
        if (kind === "coordinate") {
            return state.coordinates.map((point) => ({
                id: point.id,
                label: `${point.name || point.id} · ${point.dimension}`
            }));
        }
        if (kind === "pickup") {
            return state.pickups.map((workflow) => ({
                id: workflow.id,
                label: `${workflow.name || workflow.id} · ${workflow.id}`
            }));
        }
        if (kind === "pickup-region") {
            const pickup = state.pickups.find((candidate) => {
                return candidate.id === step.parameters?.workflowId;
            });
            return (pickup?.regions || []).map((region) => ({
                id: region.regionId,
                label: `${region.name || region.regionId} · 优先级 ${region.priority ?? "-"}`
            }));
        }
        return state.containers.map((container) => ({
            id: container.id,
            label: `${container.name || container.id} · ${container.type || "容器"}`
        }));
    }

    /**
     * @param {string} text
     * @param {number} index
     * @param {number} offset
     * @param {object} workflow
     * @returns {HTMLButtonElement}
     */
    function stepMoveButton(text, index, offset, workflow) {
        const button = Stowling.element("button", "secondary compact", text);
        button.type = "button";
        button.title = offset < 0 ? "步骤上移" : "步骤下移";
        button.addEventListener("click", () => moveStep(workflow, index, offset));
        return button;
    }

    /**
     * @param {number} index
     * @param {object} workflow
     * @returns {HTMLButtonElement}
     */
    function stepDeleteButton(index, workflow) {
        const button = Stowling.element("button", "ghost compact danger-action", "删除");
        button.type = "button";
        button.addEventListener("click", () => {
            workflow.steps.splice(index, 1);
            markDirty();
            renderWorkflowList();
            renderSteps();
        });
        return button;
    }

    /**
     * @param {object} workflow
     * @param {number} index
     * @param {number} offset
     * @returns {void}
     */
    function moveStep(workflow, index, offset) {
        const target = index + offset;
        if (target < 0 || target >= workflow.steps.length) {
            return;
        }
        const [step] = workflow.steps.splice(index, 1);
        workflow.steps.splice(target, 0, step);
        markDirty();
        renderSteps();
    }

    /**
     * @returns {void}
     */
    function addStep() {
        const workflow = selectedWorkflow();
        if (!workflow) {
            return;
        }
        workflow.steps ||= [];
        const type = state.modules[0]?.type || "NAVIGATE";
        workflow.steps.push({
            id: nextStepId(workflow),
            type,
            parameters: defaultParameters(type)
        });
        markDirty();
        renderWorkflowList();
        renderSteps();
    }

    /**
     * @param {object} workflow
     * @returns {string}
     */
    function nextStepId(workflow) {
        const used = new Set((workflow.steps || []).map((step) => String(step.id || "")));
        let index = 1;
        while (used.has(`step-${index}`)) {
            index += 1;
        }
        return `step-${index}`;
    }

    /**
     * @returns {void}
     */
    function markDirty(rerenderEditor = false) {
        state.dirty = true;
        showErrors([]);
        updateSaveState();
        if (rerenderEditor) {
            renderSteps();
        }
    }

    /**
     * @returns {Array<string>}
     */
    function validateDraft() {
        if (!state.draft) {
            return ["尚未读取任务配置"];
        }
        const errors = [];
        const workflowIds = new Set();
        for (const workflow of state.draft.automationWorkflows) {
            const id = String(workflow.id || "").trim();
            if (!/^[a-z0-9][a-z0-9._-]{0,63}$/.test(id)) {
                errors.push(`任务 ID“${id || "空"}”格式无效`);
            }
            if (workflowIds.has(id)) {
                errors.push(`重复任务 ID“${id}”`);
            }
            workflowIds.add(id);
            if (!String(workflow.name || "").trim()) {
                errors.push(`任务“${id || "未命名"}”的显示名称不能为空`);
            }
            const steps = Array.isArray(workflow.steps) ? workflow.steps : [];
            if (steps.length === 0) {
                errors.push(`任务“${workflow.name || id}”至少需要一个步骤`);
            }
            const stepIds = new Set();
            for (const step of steps) {
                const stepId = String(step.id || "");
                if (!stepId) {
                    errors.push(`任务“${workflow.name || id}”存在无 ID 步骤`);
                } else if (stepIds.has(stepId)) {
                    errors.push(`任务“${workflow.name || id}”存在重复步骤 ID“${stepId}”`);
                }
                stepIds.add(stepId);
                validateStep(workflow, step, errors);
            }
        }
        return [...new Set(errors)];
    }

    /**
     * @param {object} workflow
     * @param {object} step
     * @param {Array<string>} errors
     * @returns {void}
     */
    function validateStep(workflow, step, errors) {
        const module = moduleFor(step.type);
        const label = `任务“${workflow.name || workflow.id}”步骤 ${step.id}`;
        if (!module) {
            errors.push(`${label} 使用了不可用模块“${step.type || "空"}”`);
            return;
        }
        for (const definition of module.parameters || []) {
            const value = step.parameters?.[definition.key];
            if (definition.required && String(value ?? "").trim() === "") {
                errors.push(`${label} 的“${definition.label}”不能为空`);
                continue;
            }
            if (definition.kind.includes("integer") && value !== "") {
                const minimum = definition.kind === "positive-integer" ? 1 : 0;
                if (!Number.isInteger(value) || value < minimum) {
                    errors.push(`${label} 的“${definition.label}”必须是大于等于 `
                        + `${minimum} 的整数`);
                }
            }
        }
    }

    /**
     * @returns {void}
     */
    function updateSaveState() {
        const errors = validateDraft();
        elements.save.disabled = !state.dirty || errors.length > 0;
        elements.status.classList.toggle("dirty", state.dirty);
        if (state.dirty && errors.length > 0) {
            elements.status.textContent = "草稿中有错误，修正后才能保存";
        } else if (state.dirty) {
            elements.status.textContent = "有未保存的任务配置修改";
        } else {
            elements.status.textContent = "任务配置已与配置文件同步";
        }
    }

    /**
     * @param {Array<string>} errors
     * @returns {void}
     */
    function showErrors(errors) {
        elements.errors.replaceChildren();
        if (errors.length === 0) {
            return;
        }
        elements.errors.append(Stowling.element("strong", "", "请修正以下问题："));
        const list = Stowling.element("ul");
        for (const error of errors) {
            list.append(Stowling.element("li", "", error));
        }
        elements.errors.append(list);
    }

    /**
     * @returns {Promise<void>}
     */
    async function saveWorkflows() {
        const errors = validateDraft();
        if (errors.length > 0) {
            showErrors(errors);
            Stowling.showToast("任务配置仍有错误，暂未保存", true);
            return;
        }
        if (!state.snapshot || !state.dirty) {
            return;
        }
        elements.save.disabled = true;
        elements.discard.disabled = true;
        elements.status.textContent = "正在保存任务配置…";
        try {
            const body = {
                revision: state.snapshot.revision,
                automationWorkflows: state.draft.automationWorkflows.map(toRequestWorkflow)
            };
            const saved = await Stowling.request("/v1/config/automation-workflows", {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(body)
            });
            await loadWorkflows();
            await Promise.allSettled([loadReferences(), loadTasks()]);
            Stowling.showToast("任务配置已保存，旧配置已生成备份");
            if (saved?.revision) {
                elements.revision.textContent = `revision: ${saved.revision.slice(0, 12)}`;
            }
        } catch (error) {
            const conflict = error instanceof Stowling.ApiError && error.status === 409;
            const activity = error?.code === "active_task_conflict";
            let message = error instanceof Error ? error.message : "保存任务配置失败";
            if (conflict) {
                message = activity
                    ? `活动或暂停任务存在：${message}`
                    : `配置已被外部修改：${message}`;
            }
            showErrors([message]);
            elements.status.textContent = conflict
                ? "配置版本冲突或任务正在运行，草稿已保留"
                : "任务配置保存失败，草稿仍在";
            Stowling.handleError(error, "保存任务配置失败");
        } finally {
            elements.discard.disabled = false;
            updateSaveState();
        }
    }

    /**
     * @param {object} workflow
     * @returns {object}
     */
    function toDraftWorkflow(workflow) {
        return {
            id: String(workflow?.id || ""),
            name: String(workflow?.name || ""),
            steps: (workflow?.steps || []).map((step) => ({
                id: String(step.id || ""),
                type: String(step.type || ""),
                parameters: parametersToDraft(step.type, step.parameters || {})
            }))
        };
    }

    /**
     * @param {string} type
     * @param {object} parameters
     * @returns {object}
     */
    function parametersToDraft(type, parameters) {
        const result = Stowling.clone(parameters);
        if (type === "WAIT" && result.seconds != null) {
            result.durationSeconds = Number(result.seconds);
            delete result.seconds;
        }
        if ((type === "TAKE_ITEMS" || type === "PUT_ITEMS")
                && result.containerId != null) {
            const key = type === "TAKE_ITEMS"
                ? "sourceContainerId"
                : "destinationContainerId";
            result[key] = result.containerId;
            delete result.containerId;
        }
        if (type === "TRANSFER_ITEMS" && result.destination != null) {
            result.destinationContainerId = result.destination;
            delete result.destination;
        }
        for (const key of ["count", "durationSeconds"]) {
            if (result[key] !== undefined && result[key] !== "") {
                result[key] = Number(result[key]);
            }
        }
        return result;
    }

    /**
     * @param {object} workflow
     * @returns {object}
     */
    function toRequestWorkflow(workflow) {
        return {
            id: String(workflow.id || "").trim(),
            name: String(workflow.name || "").trim(),
            steps: (workflow.steps || []).map((step) => ({
                id: String(step.id || "").trim(),
                type: String(step.type || "").trim(),
                parameters: parametersToRequest(step.type, step.parameters || {})
            }))
        };
    }

    /**
     * @param {string} type
     * @param {object} parameters
     * @returns {object}
     */
    function parametersToRequest(type, parameters) {
        const result = {};
        if (type === "WAIT") {
            result.seconds = String(parameters.durationSeconds ?? "");
        } else if (type === "TAKE_ITEMS") {
            result.containerId = String(parameters.sourceContainerId || "");
            result.itemId = String(parameters.itemId || "");
            result.count = String(parameters.count ?? "");
        } else if (type === "PUT_ITEMS") {
            result.containerId = String(parameters.destinationContainerId || "");
            result.itemId = String(parameters.itemId || "");
            result.count = String(parameters.count ?? "");
        } else if (type === "TRANSFER_ITEMS") {
            result.sourceContainerId = String(parameters.sourceContainerId || "");
            result.destination = String(parameters.destinationContainerId || "");
            result.itemId = String(parameters.itemId || "");
            result.count = String(parameters.count ?? "");
        } else {
            for (const [key, value] of Object.entries(parameters)) {
                if (value !== "" && value != null) {
                    result[key] = String(value);
                }
            }
        }
        for (const key of Object.keys(result)) {
            if (result[key] === "") {
                delete result[key];
            }
        }
        return result;
    }

    /**
     * @returns {Promise<void>}
     */
    async function discardChanges() {
        elements.discard.disabled = true;
        try {
            await loadWorkflows();
            await loadReferences();
            Stowling.showToast("已放弃草稿并重新读取任务配置");
        } catch (error) {
            Stowling.handleError(error, "重新读取任务配置失败");
        } finally {
            elements.discard.disabled = false;
        }
    }

    /**
     * @returns {void}
     */
    function renderLaunchOptions() {
        const selected = elements.launchSelect.value;
        elements.launchSelect.replaceChildren();
        const workflows = state.snapshot?.automationWorkflows || [];
        if (workflows.length === 0) {
            const option = document.createElement("option");
            option.value = "";
            option.textContent = "没有已保存的自定义任务";
            elements.launchSelect.append(option);
            elements.launchSelect.disabled = true;
            elements.start.disabled = true;
            return;
        }
        for (const workflow of workflows) {
            const option = document.createElement("option");
            option.value = workflow.id;
            const stepCount = workflow.steps?.length || 0;
            option.textContent = `${workflow.name || workflow.id} · ${stepCount} 步`;
            elements.launchSelect.append(option);
        }
        if (workflows.some((workflow) => workflow.id === selected)) {
            elements.launchSelect.value = selected;
        }
        elements.launchSelect.disabled = false;
        elements.start.disabled = false;
        elements.launchMessage.textContent = "任务将在客户端线程中按步骤执行";
    }

    /**
     * @returns {Promise<void>}
     */
    async function startWorkflow() {
        const workflowId = elements.launchSelect.value;
        if (!workflowId) {
            Stowling.showToast("请选择一个自定义任务", true);
            return;
        }
        elements.start.disabled = true;
        elements.launchMessage.textContent = "正在创建任务…";
        try {
            await Stowling.request("/v1/tasks/automation", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ workflowId })
            });
            elements.launchMessage.textContent = "自定义任务已创建";
            Stowling.showToast("自定义任务已开始");
            await loadTasks();
        } catch (error) {
            elements.launchMessage.textContent = "启动自定义任务失败";
            Stowling.handleError(error, "启动自定义任务失败");
        } finally {
            elements.start.disabled = false;
        }
    }

    /**
     * @returns {Promise<void>}
     */
    async function loadTasks() {
        const tasks = await Stowling.request("/v1/tasks");
        renderTasks(Array.isArray(tasks) ? tasks : []);
    }

    /**
     * @param {Array<object>} tasks
     * @returns {void}
     */
    function renderTasks(tasks) {
        elements.taskList.replaceChildren();
        if (tasks.length === 0) {
            elements.taskList.append(Stowling.emptyState("当前没有任务"));
            return;
        }
        const sorted = [...tasks].sort((left, right) => {
            return String(right.updatedAt || "").localeCompare(String(left.updatedAt || ""));
        });
        for (const task of sorted) {
            const card = Stowling.element("article", "task-card");
            const content = Stowling.element("div");
            const title = Stowling.element(
                "div",
                "task-title",
                String(task.workflowId || task.kind || "任务")
            );
            title.append(statusBadge(task.status));
            const id = Stowling.element("div", "task-id", String(task.id || "未知 ID"));
            const meta = Stowling.element("div", "task-meta");
            meta.append(
                metaText("阶段", String(task.stage || task.automationState || "未知")),
                metaText("当前步骤", String(task.currentStepId || task.stage || "-")),
                metaText("模块状态", String(task.moduleState || task.automationState || "-")),
                metaText("更新", Stowling.formatDate(task.updatedAt))
            );
            if (task.failureReason) {
                meta.append(metaText("原因", String(task.failureReason)));
            }
            content.append(title, id, meta);
            card.append(content);
            if (Stowling.ACTIVE_TASK_STATES.has(task.status)) {
                const cancel = Stowling.element("button", "secondary compact", "取消任务");
                cancel.type = "button";
                cancel.addEventListener("click", () => cancelTask(task.id, cancel));
                card.append(cancel);
            }
            elements.taskList.append(card);
        }
    }

    /**
     * @param {string} key
     * @param {string} value
     * @returns {HTMLSpanElement}
     */
    function metaText(key, value) {
        return Stowling.element("span", "", `${key}：${value}`);
    }

    /**
     * @param {string} value
     * @returns {HTMLSpanElement}
     */
    function statusBadge(value) {
        const status = String(value || "UNKNOWN");
        const badge = Stowling.element("span", "badge", STATUS_LABELS.get(status) || status);
        if (["FAILED", "CANCELLED"].includes(status)) {
            badge.classList.add("danger");
        } else if (["PAUSED", "WAITING", "UNKNOWN"].includes(status)) {
            badge.classList.add("warning");
        }
        return badge;
    }

    /**
     * @param {string} taskId
     * @param {HTMLButtonElement} button
     * @returns {Promise<void>}
     */
    async function cancelTask(taskId, button) {
        button.disabled = true;
        try {
            await Stowling.request(`/v1/tasks/${encodeURIComponent(taskId)}/cancel`, {
                method: "POST"
            });
            Stowling.showToast("任务已取消");
            await loadTasks();
        } catch (error) {
            Stowling.handleError(error, "取消任务失败");
        } finally {
            button.disabled = false;
        }
    }

    /**
     * @param {string} path
     * @param {string} label
     * @returns {Promise<void>}
     */
    async function controlAutomation(path, label) {
        try {
            await Stowling.request(path, { method: "POST" });
            Stowling.showToast(`${label}成功`);
            await loadTasks();
        } catch (error) {
            Stowling.handleError(error, `${label}失败`);
        }
    }

    /**
     * @returns {void}
     */
    function renderModuleCatalog() {
        elements.moduleCatalog.replaceChildren();
        elements.moduleCount.textContent = `${state.modules.length} 个模块`;
        for (const module of state.modules) {
            const card = Stowling.element("article", "module-card");
            card.append(
                Stowling.element("code", "", module.type),
                Stowling.element("strong", "", module.name),
                Stowling.element("p", "", module.description)
            );
            elements.moduleCatalog.append(card);
        }
    }

    elements.addWorkflow.addEventListener("click", addWorkflow);
    elements.addStep.addEventListener("click", addStep);
    elements.discard.addEventListener("click", () => {
        void discardChanges();
    });
    elements.form.addEventListener("submit", (event) => {
        event.preventDefault();
        void saveWorkflows();
    });
    elements.workflowId.addEventListener("input", () => {
        const workflow = selectedWorkflow();
        if (!workflow) {
            return;
        }
        workflow.id = elements.workflowId.value;
        markDirty();
        renderWorkflowList();
    });
    elements.workflowName.addEventListener("input", () => {
        const workflow = selectedWorkflow();
        if (!workflow) {
            return;
        }
        workflow.name = elements.workflowName.value;
        markDirty();
        renderWorkflowList();
    });
    elements.start.addEventListener("click", () => {
        void startWorkflow();
    });
    elements.pause.addEventListener("click", () => {
        void controlAutomation("/v1/pause", "暂停机器人");
    });
    elements.resume.addEventListener("click", () => {
        void controlAutomation("/v1/resume", "恢复机器人");
    });
    elements.refresh.addEventListener("click", () => {
        loadTasks().catch((error) => Stowling.handleError(error, "刷新任务状态失败"));
    });

    window.setInterval(() => {
        if (document.visibilityState === "visible" && Stowling.isConnected()) {
            loadTasks().catch(() => {});
        }
    }, 5000);

    Stowling.onConnected(async () => {
        const results = await Promise.allSettled([
            loadWorkflows(),
            loadModules(),
            loadReferences(),
            loadTasks()
        ]);
        const failures = results.filter((result) => result.status === "rejected");
        for (const failure of failures) {
            Stowling.handleError(failure.reason, "读取任务页面失败");
        }
        renderModuleCatalog();
    });
    Stowling.mountPage("tasks");
})();
