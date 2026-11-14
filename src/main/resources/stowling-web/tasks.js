(() => {
    "use strict";

    const Stowling = window.Stowling;
    const Resources = window.StowlingResources;
    const DIMENSIONS = Object.freeze([
        "minecraft:overworld",
        "minecraft:the_nether",
        "minecraft:the_end"
    ]);
    const TRIGGER_KINDS = Object.freeze([
        "MANUAL",
        "FIXED_INTERVAL",
        "DAILY",
        "AFTER_COMPLETION",
        "ITEMS_PRESENT"
    ]);
    const PRIORITIES = Object.freeze(["HIGH", "MEDIUM", "LOW"]);
    const INTERRUPTIONS = Object.freeze(["NONE", "INLINE", "TASK_REFERENCE"]);

    const elements = {
        refreshTasks: document.querySelector("#refresh-tasks-button"),
        addTask: document.querySelector("#add-task-button"),
        filterName: document.querySelector("#task-filter-name"),
        filterKind: document.querySelector("#task-filter-kind"),
        taskList: document.querySelector("#task-list"),
        taskListMessage: document.querySelector("#task-list-message"),
        taskForm: document.querySelector("#task-form"),
        taskEmpty: document.querySelector("#task-empty"),
        taskKind: document.querySelector("#task-kind"),
        taskId: document.querySelector("#task-id"),
        taskName: document.querySelector("#task-name"),
        taskPriority: document.querySelector("#task-priority"),
        triggerKind: document.querySelector("#trigger-kind"),
        triggerInterval: document.querySelector("#trigger-interval"),
        triggerIntervalField: document.querySelector("#trigger-interval-field"),
        triggerTimes: document.querySelector("#trigger-times"),
        triggerTimesField: document.querySelector("#trigger-times-field"),
        triggerZone: document.querySelector("#trigger-zone"),
        triggerZoneField: document.querySelector("#trigger-zone-field"),
        triggerRegions: document.querySelector("#trigger-regions"),
        triggerRegionsField: document.querySelector("#trigger-regions-field"),
        triggerRegionHints: document.querySelector("#trigger-region-hints"),
        autoStart: document.querySelector("#auto-start-on-restart"),
        interruptionKind: document.querySelector("#interruption-kind"),
        interruptionTask: document.querySelector("#interruption-task"),
        interruptionTaskField: document.querySelector("#interruption-task-field"),
        interruptionSteps: document.querySelector("#interruption-steps"),
        interruptionStepsSection: document.querySelector("#interruption-steps-section"),
        addInterruptionStep: document.querySelector("#add-interruption-step-button"),
        pickupEditor: document.querySelector("#pickup-task-editor"),
        pickupDimension: document.querySelector("#pickup-dimension"),
        pickupClassification: document.querySelector("#pickup-classification"),
        pickupCachePolicy: document.querySelector("#pickup-cache-policy"),
        pickupCompletionMode: document.querySelector("#pickup-completion-mode"),
        pickupCompletionIdle: document.querySelector("#pickup-completion-idle"),
        addPickupBinding: document.querySelector("#add-pickup-binding-button"),
        pickupBindingList: document.querySelector("#pickup-binding-list"),
        pickupCacheOptions: document.querySelector("#pickup-cache-options"),
        automationEditor: document.querySelector("#automation-task-editor"),
        taskSteps: document.querySelector("#task-steps"),
        addTaskStep: document.querySelector("#add-task-step-button"),
        errors: document.querySelector("#task-errors"),
        status: document.querySelector("#task-status"),
        revision: document.querySelector("#task-revision"),
        save: document.querySelector("#save-task-button"),
        discard: document.querySelector("#discard-task-button"),
        run: document.querySelector("#run-task-button"),
        enable: document.querySelector("#enable-task-button"),
        disable: document.querySelector("#disable-task-button"),
        remove: document.querySelector("#delete-task-button"),
        moduleCatalog: document.querySelector("#module-catalog"),
        moduleCount: document.querySelector("#module-count"),
        refreshRuntime: document.querySelector("#refresh-runtime-button"),
        runtimeList: document.querySelector("#runtime-task-list")
    };

    const state = {
        definitions: [],
        schedules: new Map(),
        modules: [],
        resources: {
            coordinatePoints: [],
            pickupRegions: [],
            cacheContainers: []
        },
        selectedKey: "",
        revision: "",
        draft: null,
        dirty: false,
        invalidFields: new Set(),
        loadingDetail: false
    };

    /**
     * @returns {object}
     */
    function defaultSettings() {
        return {
            priority: "MEDIUM",
            trigger: {
                kind: "MANUAL",
                intervalSeconds: 0,
                times: [],
                zoneId: "Asia/Shanghai",
                regionIds: []
            },
            autoStartOnRestart: false,
            interruption: {
                kind: "NONE",
                steps: [],
                taskId: null
            }
        };
    }

    /**
     * @param {string} kind
     * @returns {object}
     */
    function defaultDefinition(kind) {
        const settings = defaultSettings();
        const id = nextDefinitionId(kind);
        const name = kind === "PICKUP" ? "新拾取任务" : "新自定义任务";
        const definition = {
            kind,
            id,
            name,
            settings,
            pickup: null,
            steps: []
        };
        if (kind === "PICKUP") {
            definition.pickup = {
                id,
                name,
                dimension: DIMENSIONS[0],
                regions: [],
                cacheIds: [],
                cachePolicy: "NEAREST_AVAILABLE",
                classificationTiming: "AFTER_ROUND",
                completionMode: "MANUAL",
                completionIdleDelay: 0,
                settings
            };
        }
        return definition;
    }

    /**
     * @param {string} kind
     * @returns {string}
     */
    function nextDefinitionId(kind) {
        const prefix = kind === "PICKUP" ? "pickup" : "automation";
        const used = new Set(
            state.definitions
                .filter((definition) => definition.kind === kind)
                .map((definition) => String(definition.id || ""))
        );
        let index = 1;
        while (used.has(`${prefix}-${index}`)) {
            index += 1;
        }
        return `${prefix}-${index}`;
    }

    /**
     * @param {object} definition
     * @returns {object}
     */
    function normalizeDefinition(definition) {
        const kind = definition?.kind === "PICKUP" ? "PICKUP" : "AUTOMATION";
        const settings = normalizeSettings(definition?.settings);
        const result = {
            kind,
            id: String(definition?.id || ""),
            name: String(definition?.name || ""),
            settings,
            pickup: null,
            steps: (Array.isArray(definition?.steps) ? definition.steps : [])
                .map(normalizeStep)
        };
        if (kind === "PICKUP") {
            result.pickup = normalizePickup(definition?.pickup, result);
            result.steps = [];
        }
        return result;
    }

    /**
     * @param {object} settings
     * @returns {object}
     */
    function normalizeSettings(settings) {
        const trigger = settings?.trigger || {};
        const interruption = settings?.interruption || {};
        return {
            priority: PRIORITIES.includes(settings?.priority)
                ? settings.priority
                : "MEDIUM",
            trigger: {
                kind: TRIGGER_KINDS.includes(trigger.kind)
                    ? trigger.kind
                    : "MANUAL",
                intervalSeconds: Number(trigger.intervalSeconds || 0),
                times: Array.isArray(trigger.times)
                    ? trigger.times.map((value) => String(value))
                    : [],
                zoneId: String(trigger.zoneId || "Asia/Shanghai"),
                regionIds: Array.isArray(trigger.regionIds)
                    ? trigger.regionIds.map((value) => String(value))
                    : []
            },
            autoStartOnRestart: Boolean(settings?.autoStartOnRestart),
            interruption: {
                kind: INTERRUPTIONS.includes(interruption.kind)
                    ? interruption.kind
                    : "NONE",
                steps: (Array.isArray(interruption.steps) ? interruption.steps : [])
                    .map(normalizeStep),
                taskId: interruption.taskId ? String(interruption.taskId) : null
            }
        };
    }

    /**
     * @param {object} pickup
     * @param {object} definition
     * @returns {object}
     */
    function normalizePickup(pickup, definition) {
        return {
            id: String(pickup?.id || definition.id || ""),
            name: String(pickup?.name || definition.name || ""),
            dimension: String(pickup?.dimension || DIMENSIONS[0]),
            regions: (Array.isArray(pickup?.regions) ? pickup.regions : [])
                .map((binding) => ({
                    regionId: String(binding?.regionId || ""),
                    priority: Number(binding?.priority || 0),
                    idleDelay: Number(binding?.idleDelay || 0)
                })),
            cacheIds: Array.isArray(pickup?.cacheIds)
                ? pickup.cacheIds.map((value) => String(value))
                : [],
            cachePolicy: String(pickup?.cachePolicy || "NEAREST_AVAILABLE"),
            classificationTiming: String(
                pickup?.classificationTiming || "AFTER_ROUND"
            ),
            completionMode: String(pickup?.completionMode || "MANUAL"),
            completionIdleDelay: Number(pickup?.completionIdleDelay || 0),
            settings: definition.settings
        };
    }

    /**
     * @param {object} step
     * @returns {object}
     */
    function normalizeStep(step) {
        const parameters = {};
        for (const [key, value] of Object.entries(step?.parameters || {})) {
            if (value !== null && value !== undefined) {
                parameters[String(key)] = String(value);
            }
        }
        return {
            id: String(step?.id || ""),
            type: String(step?.type || ""),
            parameters
        };
    }

    /**
     * @returns {Promise<void>}
     */
    async function initialize() {
        const results = await Promise.allSettled([
            loadModules(),
            Resources.load(),
            loadDefinitions(),
            loadRuntimeTasks()
        ]);
        state.resources = Resources.getSnapshot();
        for (const result of results) {
            if (result.status === "rejected") {
                Stowling.handleError(result.reason, "读取任务页面失败");
            }
        }
        renderEditor();
    }

    /**
     * @returns {Promise<void>}
     */
    async function loadModules() {
        const payload = await Stowling.request("/v1/automation-modules");
        const modules = Array.isArray(payload) ? payload : payload?.modules;
        state.modules = (Array.isArray(modules) ? modules : [])
            .map(normalizeModule)
            .filter((module) => module.type);
        renderModuleCatalog();
        if (state.draft) {
            renderSteps();
        }
    }

    /**
     * @param {object} module
     * @returns {object}
     */
    function normalizeModule(module) {
        const type = String(module?.type || "").toUpperCase();
        return {
            moduleId: String(module?.moduleId || type),
            type,
            name: String(module?.name || module?.displayName || type || "模块"),
            description: String(module?.description || "后端提供的自动化模块"),
            parameters: (Array.isArray(module?.parameters) ? module.parameters : [])
                .map((parameter) => ({
                    key: String(parameter?.key || ""),
                    label: String(
                        parameter?.label || parameter?.name || parameter?.key || ""
                    ),
                    kind: String(parameter?.kind || "text"),
                    required: Boolean(parameter?.required),
                    aliases: Array.isArray(parameter?.aliases)
                        ? parameter.aliases.map((alias) => String(alias))
                        : []
                }))
                .filter((parameter) => parameter.key)
        };
    }

    /**
     * @param {boolean} background
     * @returns {Promise<void>}
     */
    async function loadDefinitions(background = false) {
        if (!background) {
            elements.taskListMessage.textContent = "正在读取任务列表…";
        }
        const payload = await Stowling.request("/v1/task-definitions");
        state.definitions = (Array.isArray(payload?.tasks) ? payload.tasks : [])
            .map((definition) => ({
                kind: definition.kind,
                id: String(definition.id || ""),
                name: String(definition.name || ""),
                definition
            }));
        state.schedules = scheduleMap(payload?.scheduling);
        renderTaskList();
        if (!background) {
            const count = state.definitions.length;
            elements.taskListMessage.textContent = count === 0
                ? "还没有任务定义，点击“新增任务”开始"
                : `已读取 ${count} 个任务定义`;
        }
    }

    /**
     * @param {Array<object>} scheduling
     * @returns {Map<string, object>}
     */
    function scheduleMap(scheduling) {
        const result = new Map();
        for (const value of Array.isArray(scheduling) ? scheduling : []) {
            const kind = String(value?.key?.kind || "");
            const id = String(value?.key?.id || "");
            if (kind && id) {
                result.set(keyOf(kind, id), normalizeSchedule(value));
            }
        }
        return result;
    }

    /**
     * @param {object} value
     * @returns {object}
     */
    function normalizeSchedule(value) {
        return {
            key: {
                kind: String(value?.key?.kind || ""),
                id: String(value?.key?.id || "")
            },
            enabled: Boolean(value?.enabled),
            state: String(value?.state || "UNKNOWN"),
            priority: String(value?.priority || "MEDIUM"),
            nextRunAt: value?.nextRunAt || null,
            runId: value?.runId || null,
            message: String(value?.message || "")
        };
    }

    /**
     * @param {string} kind
     * @param {string} id
     * @returns {string}
     */
    function keyOf(kind, id) {
        return `${String(kind || "")}/${String(id || "")}`;
    }

    /**
     * @returns {void}
     */
    function renderTaskList() {
        const query = elements.filterName.value.trim().toLowerCase();
        const kind = elements.filterKind.value;
        const visible = state.definitions.filter((entry) => {
            const matchesKind = !kind || entry.kind === kind;
            const searchable = `${entry.name} ${entry.id}`.toLowerCase();
            return matchesKind && (!query || searchable.includes(query));
        });
        elements.taskList.replaceChildren();
        if (visible.length === 0) {
            const message = state.definitions.length === 0
                ? "还没有任务定义"
                : "没有符合筛选条件的任务";
            elements.taskList.append(Stowling.emptyState(message));
            return;
        }
        for (const entry of visible) {
            elements.taskList.append(createDefinitionCard(entry));
        }
    }

    /**
     * @param {object} entry
     * @returns {HTMLElement}
     */
    function createDefinitionCard(entry) {
        const key = keyOf(entry.kind, entry.id);
        const card = Stowling.element("article", "task-definition-card");
        if (state.selectedKey === key) {
            card.classList.add("selected");
        }
        const main = Stowling.element("button", "task-definition-main");
        main.type = "button";
        main.addEventListener("click", () => {
            void selectDefinition(entry.kind, entry.id);
        });
        const title = Stowling.element("div", "task-definition-title");
        title.append(
            Stowling.element("strong", "", entry.name || entry.id),
            Stowling.element("span", "badge", Stowling.taskKindLabel(entry.kind)),
            scheduleStateBadge(state.schedules.get(key))
        );
        const schedule = state.schedules.get(key);
        const meta = Stowling.element("div", "task-definition-meta");
        meta.append(
            Stowling.element("span", "", `任务 ID：${entry.id}`),
            Stowling.element(
                "span",
                "",
                `调度：${schedule ? scheduleLabel(schedule) : "未返回状态"}`
            )
        );
        if (schedule) {
            meta.append(
                Stowling.element(
                    "span",
                    "",
                    `优先级：${Stowling.priorityLabel(schedule.priority)}`
                ),
                Stowling.element(
                    "span",
                    "",
                    `下次运行：${schedule.nextRunAt
                        ? Stowling.formatDate(schedule.nextRunAt)
                        : "未安排"}`
                ),
                Stowling.element(
                    "span",
                    "",
                    `倒计时：${scheduleCountdown(schedule.nextRunAt)}`
                )
            );
            if (schedule.runId) {
                meta.append(
                    Stowling.element("span", "", `运行 ID：${schedule.runId}`)
                );
            }
        }
        if (schedule?.message) {
            meta.append(Stowling.element(
                "span",
                "",
                Stowling.scheduleMessageLabel(schedule.message)
            ));
        }
        main.append(title, meta);
        const actions = Stowling.element("div", "task-definition-actions");
        actions.append(
            scheduleActionButton(entry, schedule, true),
            scheduleActionButton(entry, schedule, false)
        );
        card.append(main, actions);
        return card;
    }

    /**
     * @param {object} schedule
     * @returns {string}
     */
    function scheduleLabel(schedule) {
        const enabled = schedule.enabled ? "已启用" : "未启用";
        const stateLabel = Stowling.scheduleStateLabel(schedule.state);
        return `${enabled} · ${stateLabel}`;
    }

    /**
     * @param {object | undefined} schedule
     * @returns {HTMLSpanElement}
     */
    function scheduleStateBadge(schedule) {
        if (!schedule) {
            return Stowling.element("span", "badge warning", "未调度");
        }
        const state = String(schedule.state || "UNKNOWN").toUpperCase();
        const badge = Stowling.element(
            "span",
            "badge",
            Stowling.scheduleStateLabel(state)
        );
        if (["UNSAFE", "FAILED"].includes(state)) {
            badge.classList.add("danger");
        } else if (["WAITING", "UNKNOWN"].includes(state) || !schedule.enabled) {
            badge.classList.add("warning");
        }
        return badge;
    }

    /**
     * @param {unknown} value
     * @returns {string}
     */
    function scheduleCountdown(value) {
        if (!value) {
            return "未安排";
        }
        const target = new Date(value).getTime();
        if (Number.isNaN(target)) {
            return "时间未知";
        }
        const totalSeconds = Math.max(0, Math.ceil((target - Date.now()) / 1000));
        if (totalSeconds === 0) {
            return "已到时间";
        }
        const days = Math.floor(totalSeconds / 86400);
        const hours = Math.floor((totalSeconds % 86400) / 3600);
        const minutes = Math.floor((totalSeconds % 3600) / 60);
        const seconds = totalSeconds % 60;
        if (days > 0) {
            return `${days} 天 ${hours} 小时`;
        }
        if (hours > 0) {
            return `${hours} 小时 ${minutes} 分`;
        }
        if (minutes > 0) {
            return `${minutes} 分 ${seconds} 秒`;
        }
        return `${seconds} 秒`;
    }

    /**
     * @param {object} entry
     * @param {object | undefined} schedule
     * @param {boolean} enabled
     * @returns {HTMLButtonElement}
     */
    function scheduleActionButton(entry, schedule, enabled) {
        const active = Boolean(schedule?.enabled) === enabled;
        const button = Stowling.element(
            "button",
            "secondary compact",
            enabled ? "启用" : "停用"
        );
        button.type = "button";
        button.disabled = active;
        button.addEventListener("click", () => {
            void changeSchedule(entry.kind, entry.id, enabled);
        });
        return button;
    }

    /**
     * @param {string} kind
     * @param {string} id
     * @returns {Promise<void>}
     */
    async function selectDefinition(kind, id) {
        const key = keyOf(kind, id);
        if (state.dirty && state.selectedKey !== key
                && !window.confirm("当前任务有未保存修改，继续将放弃这些修改。")) {
            return;
        }
        if (state.loadingDetail) {
            return;
        }
        state.loadingDetail = true;
        elements.status.textContent = "正在读取任务详情…";
        try {
            const result = await requestDefinition(kind, id);
            state.selectedKey = key;
            state.revision = String(result?.revision || "");
            state.draft = normalizeDefinition(result?.definition);
            state.dirty = false;
            renderTaskList();
            renderEditor();
        } catch (error) {
            Stowling.handleError(error, "读取任务详情失败");
        } finally {
            state.loadingDetail = false;
        }
    }

    /**
     * @param {string} kind
     * @param {string} id
     * @returns {Promise<object>}
     */
    function requestDefinition(kind, id) {
        const path = `/v1/task-definitions/${encodeURIComponent(kind)}`
            + `/${encodeURIComponent(id)}`;
        return Stowling.request(path);
    }

    /**
     * @returns {void}
     */
    function startNewDefinition() {
        if (state.dirty && !window.confirm("当前任务有未保存修改，继续将放弃这些修改。")) {
            return;
        }
        state.selectedKey = "";
        state.revision = "";
        state.draft = defaultDefinition("AUTOMATION");
        state.dirty = true;
        renderTaskList();
        renderEditor();
        elements.taskName.focus();
        elements.taskName.select();
    }

    /**
     * @returns {void}
     */
    function renderEditor() {
        const hasDraft = Boolean(state.draft);
        elements.taskEmpty.hidden = hasDraft;
        elements.taskForm.hidden = !hasDraft;
        if (!hasDraft) {
            elements.status.textContent = "选择任务或新增任务";
            elements.revision.textContent = "任务版本：--";
            updateSaveState();
            return;
        }
        const draft = state.draft;
        elements.taskKind.value = draft.kind;
        elements.taskKind.disabled = Boolean(state.revision);
        elements.taskId.value = draft.id;
        elements.taskId.disabled = Boolean(state.revision);
        elements.taskName.value = draft.name;
        elements.taskPriority.value = draft.settings.priority;
        elements.triggerKind.value = draft.settings.trigger.kind;
        elements.autoStart.checked = draft.settings.autoStartOnRestart;
        elements.interruptionKind.value = draft.settings.interruption.kind;
        elements.revision.textContent = state.revision
            ? `任务版本：${state.revision.slice(0, 12)}`
            : "新任务尚未保存";
        elements.revision.title = state.revision;
        renderTriggerFields();
        renderInterruption();
        renderPickupEditor();
        renderSteps();
        updateSaveState();
    }

    /**
     * @returns {void}
     */
    function renderTriggerFields() {
        const trigger = state.draft?.settings.trigger;
        if (!trigger) {
            return;
        }
        const interval = trigger.kind === "FIXED_INTERVAL"
            || trigger.kind === "AFTER_COMPLETION";
        const daily = trigger.kind === "DAILY";
        const items = trigger.kind === "ITEMS_PRESENT";
        elements.triggerIntervalField.hidden = !interval;
        elements.triggerTimesField.hidden = !daily;
        elements.triggerZoneField.hidden = !daily;
        elements.triggerRegionsField.hidden = !items;
        elements.triggerInterval.value = String(trigger.intervalSeconds || 0);
        elements.triggerTimes.value = (trigger.times || []).join("\n");
        elements.triggerZone.value = trigger.zoneId || "Asia/Shanghai";
        elements.triggerRegions.value = (trigger.regionIds || []).join("\n");
        renderTriggerRegionHints();
    }

    /**
     * @returns {void}
     */
    function renderTriggerRegionHints() {
        elements.triggerRegionHints.replaceChildren();
        elements.triggerRegionHints.append(Stowling.element(
            "span",
            "muted compact-help",
            state.draft?.kind === "PICKUP"
                ? "留空时使用自身全部区域；填写区域 ID 表示明确选择自定义区域。"
                : "自定义任务需要明确填写区域 ID。"
        ));
        if (state.draft?.kind !== "PICKUP") {
            return;
        }
        const regionIds = (state.draft.pickup?.regions || [])
            .map((binding) => binding.regionId)
            .filter(Boolean);
        if (regionIds.length === 0) {
            elements.triggerRegionHints.append(Stowling.element(
                "span",
                "muted compact-help",
                "尚未配置自身区域；请先在下方添加拾取区域。"
            ));
            return;
        }
        for (const regionId of regionIds) {
            const region = resourceRegion(regionId);
            elements.triggerRegionHints.append(Stowling.element(
                "span",
                "loopback-tag",
                `${region?.name || regionId} · 自身区域`
            ));
        }
    }

    /**
     * @returns {void}
     */
    function renderInterruption() {
        const interruption = state.draft?.settings.interruption;
        if (!interruption) {
            return;
        }
        const inline = interruption.kind === "INLINE";
        const reference = interruption.kind === "TASK_REFERENCE";
        elements.interruptionTaskField.hidden = !reference;
        elements.interruptionStepsSection.hidden = !inline;
        renderInterruptionTaskOptions();
        if (inline) {
            renderStepTarget(
                elements.interruptionSteps,
                interruption.steps,
                "interruption"
            );
        } else {
            elements.interruptionSteps.replaceChildren();
        }
    }

    /**
     * @returns {void}
     */
    function renderInterruptionTaskOptions() {
        const interruption = state.draft?.settings.interruption;
        const current = interruption?.taskId || "";
        elements.interruptionTask.replaceChildren();
        const candidates = state.definitions.filter((entry) => {
            return entry.kind === "AUTOMATION" && entry.id !== state.draft.id;
        });
        if (candidates.length === 0) {
            elements.interruptionTask.append(new Option("没有可引用的自定义任务", ""));
            elements.interruptionTask.disabled = true;
            return;
        }
        elements.interruptionTask.append(new Option("请选择自定义任务", ""));
        for (const entry of candidates) {
            elements.interruptionTask.append(
                new Option(`${entry.name} · ${entry.id}`, entry.id)
            );
        }
        elements.interruptionTask.disabled = false;
        elements.interruptionTask.value = candidates.some((entry) => {
            return entry.id === current;
        }) ? current : "";
    }

    /**
     * @returns {void}
     */
    function renderPickupEditor() {
        const pickup = state.draft?.pickup;
        const show = state.draft?.kind === "PICKUP" && Boolean(pickup);
        elements.pickupEditor.hidden = !show;
        elements.automationEditor.hidden = state.draft?.kind !== "AUTOMATION";
        if (!show || !pickup) {
            elements.pickupBindingList.replaceChildren();
            elements.pickupCacheOptions.replaceChildren();
            return;
        }
        elements.pickupDimension.replaceChildren();
        for (const dimension of DIMENSIONS) {
            elements.pickupDimension.append(new Option(
                Stowling.dimensionLabel(dimension),
                dimension
            ));
        }
        elements.pickupDimension.value = pickup.dimension;
        elements.pickupClassification.value = pickup.classificationTiming;
        elements.pickupCachePolicy.replaceChildren(new Option(
            Stowling.cachePolicyLabel("NEAREST_AVAILABLE"),
            "NEAREST_AVAILABLE"
        ));
        elements.pickupCachePolicy.value = pickup.cachePolicy;
        elements.pickupCompletionMode.value = pickup.completionMode;
        elements.pickupCompletionIdle.value = String(
            pickup.completionIdleDelay || 0
        );
        renderPickupBindings();
        renderPickupCacheOptions();
    }

    /**
     * @returns {void}
     */
    function renderPickupBindings() {
        elements.pickupBindingList.replaceChildren();
        const pickup = state.draft?.pickup;
        if (!pickup) {
            return;
        }
        pickup.regions.forEach((binding, index) => {
            elements.pickupBindingList.append(
                createPickupBindingCard(binding, index)
            );
        });
        if (pickup.regions.length === 0) {
            elements.pickupBindingList.append(
                Stowling.emptyState("还没有添加拾取区域")
            );
        }
    }

    /**
     * @param {object} binding
     * @param {number} index
     * @returns {HTMLElement}
     */
    function createPickupBindingCard(binding, index) {
        const card = Stowling.element("article", "editor-card");
        const heading = Stowling.element("div", "editor-card-heading");
        heading.append(
            Stowling.element(
                "strong",
                "",
                resourceRegion(binding.regionId)?.name || binding.regionId || "区域"
            )
        );
        const remove = Stowling.element(
            "button",
            "ghost compact danger-action",
            "移除"
        );
        remove.type = "button";
        remove.addEventListener("click", () => {
            state.draft.pickup.regions.splice(index, 1);
            markDirty(true);
        });
        heading.append(remove);
        const fields = Stowling.element("div", "editor-fields");
        fields.append(
            field("拾取区域", pickupRegionSelect(binding, index)),
            field(
                "优先级",
                numberInput(
                    `pickup-binding-priority-${index}`,
                    "优先级",
                    binding.priority,
                    (value) => {
                        binding.priority = Number(value);
                        markDirty();
                    }
                )
            ),
            field(
                "空闲秒数",
                numberInput(
                    `pickup-binding-idle-${index}`,
                    "空闲秒数",
                    binding.idleDelay,
                    (value) => {
                        binding.idleDelay = Number(value);
                        markDirty();
                    }
                )
            )
        );
        card.append(heading, fields);
        return card;
    }

    /**
     * @param {object} binding
     * @param {number} index
     * @returns {HTMLSelectElement}
     */
    function pickupRegionSelect(binding, index) {
        const select = document.createElement("select");
        select.id = `pickup-binding-region-${index}`;
        select.setAttribute("aria-label", "拾取区域");
        const regions = state.resources.pickupRegions || [];
        if (binding.regionId && !regions.some((region) => {
            return String(region.id || "") === binding.regionId;
        })) {
            select.append(new Option(`缺失区域 · ${binding.regionId}`, binding.regionId));
        }
        for (const region of regions) {
            select.append(new Option(
                `${region.name || region.id} · ${region.id}`,
                String(region.id)
            ));
        }
        select.value = binding.regionId;
        select.addEventListener("change", () => {
            binding.regionId = select.value;
            markDirty();
            renderTriggerRegionHints();
        });
        return select;
    }

    /**
     * @returns {void}
     */
    function renderPickupCacheOptions() {
        elements.pickupCacheOptions.replaceChildren();
        const pickup = state.draft?.pickup;
        if (!pickup) {
            return;
        }
        const caches = state.resources.cacheContainers || [];
        for (const cache of caches) {
            const id = String(cache.id || "");
            const label = Stowling.element("label", "check-option");
            const checkbox = document.createElement("input");
            checkbox.type = "checkbox";
            checkbox.value = id;
            checkbox.checked = pickup.cacheIds.includes(id);
            checkbox.addEventListener("change", () => {
                if (checkbox.checked && !pickup.cacheIds.includes(id)) {
                    pickup.cacheIds.push(id);
                } else if (!checkbox.checked) {
                    pickup.cacheIds = pickup.cacheIds.filter((value) => value !== id);
                }
                markDirty();
            });
            label.append(
                checkbox,
                Stowling.element("span", "", cache.name || id)
            );
            elements.pickupCacheOptions.append(label);
        }
        if (caches.length === 0) {
            elements.pickupCacheOptions.append(
                Stowling.emptyState("共享资源中没有缓存容器")
            );
        }
    }

    /**
     * @returns {void}
     */
    function renderSteps() {
        renderStepTarget(elements.taskSteps, state.draft?.steps || [], "task");
    }

    /**
     * @param {HTMLElement} target
     * @param {Array<object>} steps
     * @param {string} scope
     * @returns {void}
     */
    function renderStepTarget(target, steps, scope) {
        target.replaceChildren();
        steps.forEach((step, index) => {
            target.append(createStepCard(steps, step, index, scope));
        });
        if (steps.length === 0) {
            target.append(Stowling.emptyState("还没有步骤"));
        }
    }

    /**
     * @param {Array<object>} steps
     * @param {object} step
     * @param {number} index
     * @param {string} scope
     * @returns {HTMLElement}
     */
    function createStepCard(steps, step, index, scope) {
        const card = Stowling.element("article", "step-card");
        const heading = Stowling.element("div", "step-card-heading");
        const number = Stowling.element("span", "step-number", String(index + 1));
        const identity = Stowling.element("div", "step-identity");
        identity.append(
            Stowling.element("strong", "", moduleFor(step.type)?.name || "未知模块"),
            Stowling.element("span", "muted compact-help", step.id || "尚未分配步骤 ID")
        );
        const actions = Stowling.element("div", "move-buttons");
        actions.append(
            stepMoveButton("↑", steps, index, -1, scope),
            stepMoveButton("↓", steps, index, 1, scope),
            stepDeleteButton(steps, index, scope)
        );
        heading.append(number, identity, actions);
        const fields = Stowling.element("div", "editor-fields");
        fields.append(field("模块", moduleSelect(step, steps, scope)));
        const parameters = Stowling.element("div", "step-fields");
        const definitions = moduleFor(step.type)?.parameters || [];
        definitions.forEach((definition) => {
            const editor = field(
                `${definition.label}${definition.required ? " *" : ""}`,
                parameterControl(steps, step, definition, index, scope)
            );
            appendParameterAliases(editor, definition.aliases);
            parameters.append(editor);
        });
        if (definitions.length === 0) {
            parameters.append(Stowling.element(
                "p",
                "muted compact-help",
                "该模块没有公开可编辑参数。"
            ));
        }
        card.append(heading, fields, parameters);
        return card;
    }

    /**
     * @param {object} step
     * @param {Array<object>} steps
     * @param {string} scope
     * @returns {HTMLSelectElement}
     */
    function moduleSelect(step, steps, scope) {
        const select = document.createElement("select");
        select.setAttribute("aria-label", "步骤模块");
        if (step.type && !moduleFor(step.type)) {
            select.append(new Option(`未知模块 · ${step.type}`, step.type));
        }
        for (const module of state.modules) {
            const option = new Option(module.name, module.moduleId);
            option.title = module.type;
            select.append(option);
        }
        select.value = moduleFor(step.type)?.moduleId || step.type;
        select.addEventListener("change", () => {
            step.type = moduleById(select.value)?.type || select.value;
            step.parameters = {};
            markDirty(true);
        });
        return select;
    }

    /**
     * @param {Array<object>} steps
     * @param {object} step
     * @param {object} definition
     * @param {number} index
     * @param {string} scope
     * @returns {HTMLElement}
     */
    function parameterControl(steps, step, definition, index, scope) {
        const value = parameterValue(step, definition);
        if (definition.kind === "coordinate") {
            return referenceSelect(
                coordinateOptions(),
                value,
                definition,
                (next) => setStepParameter(step, definition, next)
            );
        }
        if (definition.kind === "pickup") {
            const options = state.definitions
                .filter((entry) => entry.kind === "PICKUP")
                .map((entry) => [entry.id, `${entry.name} · ${entry.id}`]);
            return referenceSelect(
                options,
                value,
                definition,
                (next) => setStepParameter(step, definition, next)
            );
        }
        if (definition.kind === "pickup-region") {
            const options = (state.resources.pickupRegions || []).map((region) => [
                String(region.id),
                `${region.name || region.id} · ${region.id}`
            ]);
            return referenceSelect(
                options,
                value,
                definition,
                (next) => setStepParameter(step, definition, next)
            );
        }
        if (definition.kind === "container") {
            const options = (state.resources.cacheContainers || []).map((cache) => [
                String(cache.id),
                `${cache.name || cache.id} · ${cache.id}`
            ]);
            return referenceSelect(
                options,
                value,
                definition,
                (next) => setStepParameter(step, definition, next)
            );
        }
        if (definition.kind === "boolean") {
            const checkbox = document.createElement("input");
            checkbox.type = "checkbox";
            checkbox.checked = value === "true";
            checkbox.setAttribute("aria-label", definition.label);
            checkbox.addEventListener("change", () => {
                setStepParameter(
                    step,
                    definition,
                    checkbox.checked ? "true" : "false"
                );
            });
            return checkbox;
        }
        const input = document.createElement("input");
        input.type = ["positive-integer", "integer", "number"].includes(
            definition.kind
        ) ? "number" : "text";
        if (input.type === "number") {
            input.step = definition.kind === "number" ? "any" : "1";
            if (definition.kind === "positive-integer") {
                input.min = "1";
            }
        }
        input.id = `${scope}-step-parameter-${index}-${definition.key}`;
        input.value = value;
        input.required = definition.required;
        input.setAttribute("aria-label", definition.label);
        input.addEventListener("input", () => {
            setStepParameter(step, definition, input.value);
        });
        return input;
    }

    /**
     * @param {Array<Array<string>>} options
     * @param {string} value
     * @param {object} definition
     * @param {(value: string) => void} onChange
     * @returns {HTMLSelectElement}
     */
    function referenceSelect(options, value, definition, onChange) {
        const select = document.createElement("select");
        select.required = definition.required;
        select.setAttribute("aria-label", definition.label);
        if (!options.some(([optionValue]) => optionValue === value)) {
            if (value) {
                select.append(new Option(`保留原值 · ${value}`, value));
            } else {
                select.append(new Option("请选择", ""));
            }
        }
        for (const [optionValue, optionLabel] of options) {
            select.append(new Option(optionLabel, optionValue));
        }
        select.value = value;
        select.addEventListener("change", () => onChange(select.value));
        return select;
    }

    /**
     * @returns {Array<Array<string>>}
     */
    function coordinateOptions() {
        return (state.resources.coordinatePoints || []).map((point) => [
            String(point.id),
            `${point.name || point.id} · ${Stowling.dimensionLabel(point.dimension)}`
        ]);
    }

    /**
     * @param {object} step
     * @param {object} definition
     * @returns {string}
     */
    function parameterValue(step, definition) {
        for (const name of [definition.key, ...(definition.aliases || [])]) {
            const value = step.parameters[name];
            if (value !== undefined && value !== null) {
                return String(value);
            }
        }
        return "";
    }

    /**
     * @param {HTMLElement} editor
     * @param {Array<string>} aliases
     * @returns {void}
     */
    function appendParameterAliases(editor, aliases) {
        if (!Array.isArray(aliases) || aliases.length === 0) {
            return;
        }
        editor.append(Stowling.element(
            "p",
            "compact-help parameter-aliases",
            `兼容参数名：${aliases.join("、")}`
        ));
    }

    /**
     * @param {object} step
     * @param {object} definition
     * @param {string} value
     * @returns {void}
     */
    function setStepParameter(step, definition, value) {
        for (const name of [definition.key, ...(definition.aliases || [])]) {
            delete step.parameters[name];
        }
        if (value) {
            step.parameters[definition.key] = value;
        }
        markDirty();
    }

    /**
     * @param {string} text
     * @param {Array<object>} steps
     * @param {number} index
     * @param {number} offset
     * @param {string} scope
     * @returns {HTMLButtonElement}
     */
    function stepMoveButton(text, steps, index, offset, scope) {
        const button = Stowling.element("button", "secondary compact", text);
        button.type = "button";
        button.title = offset < 0 ? "上移步骤" : "下移步骤";
        button.disabled = index + offset < 0 || index + offset >= steps.length;
        button.addEventListener("click", () => {
            const [step] = steps.splice(index, 1);
            steps.splice(index + offset, 0, step);
            markDirty(true);
        });
        return button;
    }

    /**
     * @param {Array<object>} steps
     * @param {number} index
     * @param {string} scope
     * @returns {HTMLButtonElement}
     */
    function stepDeleteButton(steps, index, scope) {
        const button = Stowling.element("button", "ghost compact danger-action", "删除");
        button.type = "button";
        button.addEventListener("click", () => {
            steps.splice(index, 1);
            markDirty(true);
        });
        return button;
    }

    /**
     * @param {string} scope
     * @returns {void}
     */
    function addStep(scope) {
        const steps = scope === "interruption"
            ? state.draft.settings.interruption.steps
            : state.draft.steps;
        const firstModule = state.modules[0];
        steps.push({
            id: nextStepId(steps),
            type: firstModule?.type || "",
            parameters: {}
        });
        markDirty(true);
    }

    /**
     * @param {Array<object>} steps
     * @returns {string}
     */
    function nextStepId(steps) {
        const used = new Set(steps.map((step) => String(step.id || "")));
        let index = 1;
        while (used.has(`step-${index}`)) {
            index += 1;
        }
        return `step-${index}`;
    }

    /**
     * @returns {void}
     */
    function addPickupBinding() {
        const pickup = state.draft?.pickup;
        if (!pickup) {
            return;
        }
        const used = new Set(pickup.regions.map((binding) => binding.regionId));
        const region = (state.resources.pickupRegions || []).find((candidate) => {
            return !used.has(String(candidate.id || ""));
        });
        if (!region) {
            Stowling.showToast("没有可添加的拾取区域，请先在资源区创建", true);
            return;
        }
        pickup.regions.push({
            regionId: String(region.id),
            priority: 100,
            idleDelay: 60
        });
        markDirty(true);
    }

    /**
     * @param {string} id
     * @returns {object | null}
     */
    function resourceRegion(id) {
        return (state.resources.pickupRegions || []).find((region) => {
            return String(region.id || "") === String(id || "");
        }) || null;
    }

    /**
     * @param {string} type
     * @returns {object | null}
     */
    function moduleFor(type) {
        return state.modules.find((module) => module.type === type) || null;
    }

    /**
     * @param {string} moduleId
     * @returns {object | null}
     */
    function moduleById(moduleId) {
        return state.modules.find((module) => module.moduleId === moduleId) || null;
    }

    /**
     * @param {boolean} rerender
     * @returns {void}
     */
    function markDirty(rerender = false) {
        state.dirty = true;
        if (rerender) {
            renderPickupEditor();
            renderInterruption();
            renderSteps();
            renderTriggerRegionHints();
            updateSaveState();
            return;
        }
        updateSaveState();
    }

    /**
     * @returns {Array<string>}
     */
    function validateDraft() {
        const errors = [];
        state.invalidFields.clear();
        if (!state.draft) {
            return ["尚未选择任务"];
        }
        const draft = state.draft;
        const id = String(draft.id || "").trim();
        const name = String(draft.name || "").trim();
        if (!id) {
            addError(errors, "任务 ID 不能为空", "task-id");
        } else if (!/^[a-z0-9][a-z0-9._-]{0,63}$/.test(id)) {
            addError(
                errors,
                "任务 ID 必须使用小写字母、数字、点、下划线或连字符",
                "task-id"
            );
        }
        if (!name) {
            addError(errors, "显示名称不能为空", "task-name");
        }
        const duplicate = state.definitions.some((entry) => {
            return entry.kind === draft.kind
                && entry.id === id
                && keyOf(entry.kind, entry.id) !== state.selectedKey;
        });
        if (duplicate) {
            addError(errors, "重复任务 ID；同类型任务 ID 必须唯一", "task-id");
        }
        validateTrigger(errors);
        validateInterruption(errors);
        validateSteps(errors, draft.steps, "任务");
        if (draft.kind === "PICKUP") {
            validatePickupTask(errors);
        } else if (draft.steps.length === 0) {
            addError(errors, "自定义任务至少需要一个步骤", "task-steps");
        }
        return [...new Set(errors)];
    }

    /**
     * @param {Array<string>} errors
     * @returns {void}
     */
    function validateTrigger(errors) {
        const trigger = state.draft.settings.trigger;
        if (trigger.kind === "FIXED_INTERVAL"
                || trigger.kind === "AFTER_COMPLETION") {
            if (!Number.isInteger(trigger.intervalSeconds)
                    || trigger.intervalSeconds < 1) {
                addError(errors, "该触发器必须填写大于等于 1 的间隔秒数");
            }
        }
        if (trigger.kind === "DAILY") {
            if (trigger.times.length === 0) {
                addError(errors, "每日定时至少需要一个 HH:mm 时间");
            }
            for (const time of trigger.times) {
                if (!/^(?:[01][0-9]|2[0-3]):[0-5][0-9]$/.test(time)) {
                    addError(errors, `每日时间“${time}”格式无效；请使用 HH:mm`);
                }
            }
            if (!trigger.zoneId.trim()) {
                addError(errors, "每日定时必须填写时区");
            }
        }
        if (trigger.kind === "ITEMS_PRESENT"
                && state.draft.kind === "AUTOMATION"
                && trigger.regionIds.length === 0) {
            addError(errors, "区域有物品触发器至少需要一个区域 ID");
        }
    }

    /**
     * @param {Array<string>} errors
     * @returns {void}
     */
    function validateInterruption(errors) {
        const interruption = state.draft.settings.interruption;
        if (interruption.kind === "INLINE") {
            if (interruption.steps.length === 0) {
                addError(errors, "内联中断至少需要一个步骤");
            }
            validateSteps(errors, interruption.steps, "中断步骤");
        }
        if (interruption.kind === "TASK_REFERENCE") {
            const exists = state.definitions.some((entry) => {
                return entry.kind === "AUTOMATION"
                    && entry.id === interruption.taskId
                    && entry.id !== state.draft.id;
            });
            if (!exists) {
                addError(errors, "引用中断必须选择一个已有自定义任务");
            }
        }
    }

    /**
     * @param {Array<string>} errors
     * @param {Array<object>} steps
     * @param {string} scope
     * @returns {void}
     */
    function validateSteps(errors, steps, scope) {
        const ids = new Set();
        steps.forEach((step, index) => {
            const label = `${scope} ${index + 1}`;
            if (!step.id) {
                addError(errors, `${label}缺少步骤 ID`);
            } else if (ids.has(step.id)) {
                addError(errors, `${label}使用了重复步骤 ID“${step.id}”`);
            }
            ids.add(step.id);
            const module = moduleFor(step.type);
            if (!module) {
                addError(errors, `${label}使用了不可用的机器人模块`);
                return;
            }
            for (const parameter of module.parameters) {
                if (parameter.required
                        && !parameterValue(step, parameter).trim()) {
                    addError(
                        errors,
                        `${label}的“${parameter.label}”不能为空`
                    );
                }
            }
        });
    }

    /**
     * @param {Array<string>} errors
     * @returns {void}
     */
    function validatePickupTask(errors) {
        const pickup = state.draft.pickup;
        if (!pickup) {
            addError(errors, "拾取任务缺少拾取配置");
            return;
        }
        if (pickup.regions.length === 0) {
            addError(errors, "拾取任务至少选择一个区域", "pickup-binding-list");
        }
        if (pickup.cacheIds.length === 0) {
            addError(errors, "拾取任务至少选择一个缓存", "pickup-cache-options");
        }
        pickup.cacheIds.forEach((cacheId) => {
            const exists = (state.resources.cacheContainers || []).some((cache) => {
                return String(cache.id || "") === cacheId;
            });
            if (!exists) {
                addError(errors, `缓存“${cacheId}”已不存在；请重新选择`);
            }
        });
        pickup.regions.forEach((binding, index) => {
            if (!resourceRegion(binding.regionId)) {
                addError(
                    errors,
                    `第 ${index + 1} 个区域引用不存在；请重新选择`,
                    `pickup-binding-region-${index}`
                );
            }
            if (!Number.isInteger(binding.priority)) {
                addError(errors, `第 ${index + 1} 个区域优先级必须是整数`);
            }
            if (!Number.isInteger(binding.idleDelay) || binding.idleDelay < 0) {
                addError(errors, `第 ${index + 1} 个区域空闲秒数必须是非负整数`);
            }
        });
        if (!["AFTER_REGION", "AFTER_ROUND"].includes(
            pickup.classificationTiming
        )) {
            addError(errors, "拾取分类时机无效");
        }
        if (pickup.cachePolicy !== "NEAREST_AVAILABLE") {
            addError(errors, "缓存选择策略无效");
        }
        if (!["MANUAL", "ALL_IDLE"].includes(pickup.completionMode)) {
            addError(errors, "拾取结束方式无效");
        }
        if (!Number.isInteger(pickup.completionIdleDelay)
                || pickup.completionIdleDelay < 0) {
            addError(errors, "流程空闲秒数必须是非负整数");
        }
    }

    /**
     * @param {Array<string>} errors
     * @param {string} message
     * @param {string} fieldId
     * @returns {void}
     */
    function addError(errors, message, fieldId) {
        errors.push(message);
        if (fieldId) {
            state.invalidFields.add(fieldId);
        }
    }

    /**
     * @returns {void}
     */
    function updateSaveState() {
        if (!state.draft) {
            elements.save.disabled = true;
            elements.discard.disabled = true;
            elements.run.disabled = true;
            elements.enable.disabled = true;
            elements.disable.disabled = true;
            elements.remove.disabled = true;
            showErrors([]);
            return;
        }
        const errors = validateDraft();
        const saved = Boolean(state.revision);
        elements.save.disabled = !state.dirty || errors.length > 0;
        elements.discard.disabled = !state.dirty && !saved;
        elements.run.disabled = !saved || state.dirty;
        elements.enable.disabled = !saved || state.dirty;
        elements.disable.disabled = !saved || state.dirty;
        elements.remove.disabled = !saved;
        showErrors(state.dirty || errors.length > 0 ? errors : []);
        applyValidationStyles();
        if (state.dirty && errors.length > 0) {
            elements.status.textContent =
                `发现 ${errors.length} 个任务问题，保存已暂停`;
        } else if (state.dirty) {
            elements.status.textContent = "有未保存的任务定义修改";
        } else if (saved) {
            elements.status.textContent = "任务定义已与配置同步；保存不会自动启用";
        } else {
            elements.status.textContent = "新任务尚未保存";
        }
    }

    /**
     * @returns {void}
     */
    function applyValidationStyles() {
        for (const control of elements.taskForm.querySelectorAll(
            "input, select, textarea"
        )) {
            control.setAttribute("aria-invalid", "false");
            control.closest(".editor-field")?.classList.remove("has-error");
        }
        for (const fieldId of state.invalidFields) {
            const control = document.getElementById(fieldId);
            if (!control) {
                continue;
            }
            control.setAttribute("aria-invalid", "true");
            control.closest(".editor-field")?.classList.add("has-error");
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
        const list = Stowling.element("ul");
        for (const error of errors) {
            list.append(Stowling.element("li", "", error));
        }
        elements.errors.append(
            Stowling.element("strong", "", "请修正以下问题："),
            list
        );
    }

    /**
     * @returns {Promise<void>}
     */
    async function saveTask() {
        const errors = validateDraft();
        if (errors.length > 0) {
            state.dirty = true;
            updateSaveState();
            Stowling.showToast("任务定义仍有错误，暂未保存", true);
            return;
        }
        if (!state.draft || !state.dirty) {
            return;
        }
        elements.save.disabled = true;
        elements.discard.disabled = true;
        elements.status.textContent = "正在保存任务定义…";
        const definition = definitionPayload();
        try {
            const path = state.revision
                ? `/v1/task-definitions/${encodeURIComponent(definition.kind)}`
                    + `/${encodeURIComponent(definition.id)}`
                : "/v1/task-definitions";
            const body = state.revision
                ? { revision: state.revision, definition }
                : definition;
            const saved = await Stowling.request(path, {
                method: state.revision ? "PUT" : "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(body)
            });
            state.revision = String(saved?.revision || "");
            state.draft = normalizeDefinition(saved?.definition || definition);
            state.selectedKey = keyOf(state.draft.kind, state.draft.id);
            state.dirty = false;
            await loadDefinitions();
            renderEditor();
            Stowling.showToast("任务已保存；调度保持当前启用状态");
        } catch (error) {
            const conflict = error instanceof Stowling.ApiError && error.status === 409;
            showErrors([
                conflict
                    ? "任务版本冲突；请刷新任务详情后重试"
                    : error instanceof Error
                        ? error.message
                        : "保存任务失败"
            ]);
            elements.status.textContent = conflict
                ? "任务版本冲突，草稿已保留"
                : "任务保存失败，草稿仍在";
            Stowling.handleError(error, "保存任务失败");
        } finally {
            elements.discard.disabled = false;
            updateSaveState();
        }
    }

    /**
     * @returns {object}
     */
    function definitionPayload() {
        const draft = state.draft;
        const settings = {
            priority: draft.settings.priority,
            trigger: {
                kind: draft.settings.trigger.kind,
                intervalSeconds: Number(
                    draft.settings.trigger.intervalSeconds || 0
                ),
                times: [...draft.settings.trigger.times],
                zoneId: draft.settings.trigger.zoneId,
                regionIds: [...draft.settings.trigger.regionIds]
            },
            autoStartOnRestart: draft.settings.autoStartOnRestart,
            interruption: {
                kind: draft.settings.interruption.kind,
                steps: draft.settings.interruption.kind === "INLINE"
                    ? draft.settings.interruption.steps.map(stepPayload)
                    : [],
                taskId: draft.settings.interruption.kind === "TASK_REFERENCE"
                    ? draft.settings.interruption.taskId
                    : null
            }
        };
        const result = {
            kind: draft.kind,
            id: String(draft.id || "").trim(),
            name: String(draft.name || "").trim(),
            settings,
            pickup: null,
            steps: draft.kind === "AUTOMATION"
                ? draft.steps.map(stepPayload)
                : []
        };
        if (draft.kind === "PICKUP") {
            result.pickup = {
                id: result.id,
                name: result.name,
                dimension: draft.pickup.dimension,
                regions: draft.pickup.regions.map((binding) => ({
                    regionId: binding.regionId,
                    priority: Number(binding.priority),
                    idleDelay: Number(binding.idleDelay)
                })),
                cacheIds: [...draft.pickup.cacheIds],
                cachePolicy: draft.pickup.cachePolicy,
                classificationTiming: draft.pickup.classificationTiming,
                completionMode: draft.pickup.completionMode,
                completionIdleDelay: Number(draft.pickup.completionIdleDelay),
                settings
            };
        }
        return result;
    }

    /**
     * @param {object} step
     * @returns {object}
     */
    function stepPayload(step) {
        return {
            id: String(step.id || "").trim(),
            type: String(step.type || "").trim(),
            parameters: Stowling.clone(step.parameters || {})
        };
    }

    /**
     * @returns {Promise<void>}
     */
    async function discardTask() {
        if (!state.selectedKey) {
            state.draft = null;
            state.dirty = false;
            renderEditor();
            return;
        }
        const [kind, id] = state.selectedKey.split("/");
        await selectDefinition(kind, id);
    }

    /**
     * @param {string} kind
     * @param {string} id
     * @param {boolean} enabled
     * @returns {Promise<void>}
     */
    async function changeSchedule(kind, id, enabled) {
        const suffix = enabled ? "enable" : "disable";
        const path = `/v1/task-definitions/${encodeURIComponent(kind)}`
            + `/${encodeURIComponent(id)}/${suffix}`;
        try {
            const result = await Stowling.request(path, { method: "POST" });
            state.schedules = scheduleMap(result?.scheduling);
            renderTaskList();
            if (state.selectedKey === keyOf(kind, id)) {
                renderEditor();
            }
            Stowling.showToast(enabled ? "任务调度已启用" : "任务调度已停用");
        } catch (error) {
            Stowling.handleError(error, enabled ? "启用任务失败" : "停用任务失败");
        }
    }

    /**
     * @returns {Promise<void>}
     */
    async function runSelectedTask() {
        if (!state.draft || !state.revision || state.dirty) {
            Stowling.showToast("请先保存当前任务", true);
            return;
        }
        const draft = state.draft;
        const path = `/v1/task-definitions/${encodeURIComponent(draft.kind)}`
            + `/${encodeURIComponent(draft.id)}/run`;
        elements.run.disabled = true;
        try {
            const task = await Stowling.request(path, { method: "POST" });
            const taskId = String(task?.id || task?.runId || task?.taskId || "");
            Stowling.showToast(taskId ? `任务已运行：${taskId}` : "任务已运行");
            await loadRuntimeTasks();
        } catch (error) {
            Stowling.handleError(error, "运行任务失败");
        } finally {
            updateSaveState();
        }
    }

    /**
     * @returns {Promise<void>}
     */
    async function deleteSelectedTask() {
        if (!state.draft || !state.revision) {
            return;
        }
        const name = state.draft.name || state.draft.id;
        if (!window.confirm(`删除任务“${name}”？此操作会移除任务定义。`)) {
            return;
        }
        const draft = state.draft;
        const path = `/v1/task-definitions/${encodeURIComponent(draft.kind)}`
            + `/${encodeURIComponent(draft.id)}`;
        elements.remove.disabled = true;
        try {
            await Stowling.request(path, {
                method: "DELETE",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ revision: state.revision })
            });
            state.selectedKey = "";
            state.revision = "";
            state.draft = null;
            state.dirty = false;
            await loadDefinitions();
            renderEditor();
            Stowling.showToast("任务已删除");
        } catch (error) {
            Stowling.handleError(error, "删除任务失败");
        } finally {
            updateSaveState();
        }
    }

    /**
     * @returns {Promise<void>}
     */
    async function loadRuntimeTasks() {
        const payload = await Stowling.request("/v1/tasks");
        renderRuntimeTasks(Array.isArray(payload) ? payload : []);
    }

    /**
     * @param {boolean} reportErrors
     * @returns {Promise<void>}
     */
    async function refreshLiveState(reportErrors = false) {
        const results = await Promise.allSettled([
            loadDefinitions(true),
            loadRuntimeTasks()
        ]);
        if (!reportErrors) {
            return;
        }
        for (const result of results) {
            if (result.status === "rejected") {
                Stowling.handleError(result.reason, "刷新任务状态失败");
            }
        }
    }

    /**
     * @param {Array<object>} tasks
     * @returns {void}
     */
    function renderRuntimeTasks(tasks) {
        elements.runtimeList.replaceChildren();
        if (tasks.length === 0) {
            elements.runtimeList.append(Stowling.emptyState("当前没有运行任务"));
            return;
        }
        const sorted = [...tasks].sort((left, right) => {
            return String(right.updatedAt || "").localeCompare(
                String(left.updatedAt || "")
            );
        });
        for (const task of sorted) {
            elements.runtimeList.append(createRuntimeCard(task));
        }
    }

    /**
     * @param {object} task
     * @returns {HTMLElement}
     */
    function createRuntimeCard(task) {
        const card = Stowling.element("article", "task-card");
        const content = Stowling.element("div");
        const title = Stowling.element(
            "div",
            "task-title",
            String(task.definitionId || task.workflowId || task.id || "任务")
        );
        title.append(statusBadge(task.status));
        const meta = Stowling.element("div", "task-meta");
        meta.append(
            metaText("任务 ID", String(task.id || "未知")),
            metaText("类型", Stowling.taskKindLabel(task.kind)),
            metaText(
                "阶段",
                Stowling.taskStageLabel(task.stage || task.automationState)
            ),
            metaText("更新", Stowling.formatDate(task.updatedAt))
        );
        const failure = Stowling.conciseFailureReason(task.failureReason);
        if (failure) {
            meta.append(metaText("原因", failure));
        }
        content.append(title, meta);
        card.append(content);
        if (Stowling.ACTIVE_TASK_STATES.has(task.status)) {
            const cancel = Stowling.element(
                "button",
                "secondary compact",
                "取消任务"
            );
            cancel.type = "button";
            cancel.addEventListener("click", () => {
                void cancelRuntimeTask(task.id, cancel);
            });
            card.append(cancel);
        }
        return card;
    }

    /**
     * @param {unknown} value
     * @returns {HTMLSpanElement}
     */
    function statusBadge(value) {
        const status = String(value || "UNKNOWN");
        const badge = Stowling.element(
            "span",
            "badge",
            Stowling.taskStatusLabel(status)
        );
        if (["FAILED", "CANCELLED"].includes(status)) {
            badge.classList.add("danger");
        } else if (["WAITING", "UNKNOWN"].includes(status)) {
            badge.classList.add("warning");
        }
        return badge;
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
     * @param {string} taskId
     * @param {HTMLButtonElement} button
     * @returns {Promise<void>}
     */
    async function cancelRuntimeTask(taskId, button) {
        button.disabled = true;
        try {
            await Stowling.request(
                `/v1/tasks/${encodeURIComponent(taskId)}/cancel`,
                { method: "POST" }
            );
            Stowling.showToast("任务已取消");
            await loadRuntimeTasks();
        } catch (error) {
            Stowling.handleError(error, "取消任务失败");
        } finally {
            button.disabled = false;
        }
    }

    /**
     * @returns {void}
     */
    function renderModuleCatalog() {
        elements.moduleCatalog.replaceChildren();
        elements.moduleCount.textContent = `${state.modules.length} 个模块`;
        if (state.modules.length === 0) {
            elements.moduleCatalog.append(
                Stowling.emptyState("后端未返回自动化模块描述")
            );
            return;
        }
        for (const module of state.modules) {
            const card = Stowling.element("article", "module-card");
            card.title = module.type;
            card.dataset.moduleType = module.type;
            card.dataset.moduleId = module.moduleId;
            card.append(
                Stowling.element("strong", "", module.name),
                Stowling.element("p", "", module.description)
            );
            elements.moduleCatalog.append(card);
        }
    }

    /**
     * @param {string} label
     * @param {HTMLElement} control
     * @returns {HTMLElement}
     */
    function field(label, control) {
        const wrapper = Stowling.element("div", "editor-field");
        const caption = Stowling.element("label", "", label);
        if (control.id) {
            caption.htmlFor = control.id;
        }
        wrapper.append(caption, control);
        return wrapper;
    }

    /**
     * @param {string} id
     * @param {string} label
     * @param {unknown} value
     * @param {(value: number | string) => void} onChange
     * @returns {HTMLInputElement}
     */
    function numberInput(id, label, value, onChange) {
        const input = document.createElement("input");
        input.id = id;
        input.type = "number";
        input.step = "1";
        input.value = String(value ?? 0);
        input.setAttribute("aria-label", label);
        input.addEventListener("input", () => onChange(input.value));
        return input;
    }

    /**
     * @returns {Array<string>}
     */
    function parseLines(value) {
        return [...new Set(
            String(value || "")
                .split(/[\n,，]+/)
                .map((item) => item.trim())
                .filter(Boolean)
        )];
    }

    elements.refreshTasks.addEventListener("click", () => {
        loadDefinitions().catch((error) => {
            Stowling.handleError(error, "刷新任务列表失败");
        });
    });
    elements.addTask.addEventListener("click", startNewDefinition);
    elements.filterName.addEventListener("input", renderTaskList);
    elements.filterKind.addEventListener("change", renderTaskList);
    elements.taskKind.addEventListener("change", () => {
        if (state.revision) {
            return;
        }
        const previous = state.draft;
        const next = defaultDefinition(elements.taskKind.value);
        next.id = previous.id;
        next.name = previous.name;
        next.settings = previous.settings;
        state.draft = next;
        markDirty(true);
    });
    elements.taskId.addEventListener("input", () => {
        state.draft.id = elements.taskId.value;
        if (state.draft.pickup) {
            state.draft.pickup.id = elements.taskId.value;
        }
        markDirty();
    });
    elements.taskName.addEventListener("input", () => {
        state.draft.name = elements.taskName.value;
        if (state.draft.pickup) {
            state.draft.pickup.name = elements.taskName.value;
        }
        markDirty();
    });
    elements.taskPriority.addEventListener("change", () => {
        state.draft.settings.priority = elements.taskPriority.value;
        markDirty();
    });
    elements.triggerKind.addEventListener("change", () => {
        const trigger = state.draft.settings.trigger;
        trigger.kind = elements.triggerKind.value;
        if (
            ["FIXED_INTERVAL", "AFTER_COMPLETION"].includes(trigger.kind)
            && trigger.intervalSeconds < 1
        ) {
            trigger.intervalSeconds = 300;
        }
        markDirty(true);
    });
    elements.triggerInterval.addEventListener("input", () => {
        state.draft.settings.trigger.intervalSeconds = Number(
            elements.triggerInterval.value
        );
        markDirty();
    });
    elements.triggerTimes.addEventListener("input", () => {
        state.draft.settings.trigger.times = parseLines(elements.triggerTimes.value);
        markDirty();
    });
    elements.triggerZone.addEventListener("input", () => {
        state.draft.settings.trigger.zoneId = elements.triggerZone.value;
        markDirty();
    });
    elements.triggerRegions.addEventListener("input", () => {
        state.draft.settings.trigger.regionIds = parseLines(
            elements.triggerRegions.value
        );
        markDirty();
    });
    elements.autoStart.addEventListener("change", () => {
        state.draft.settings.autoStartOnRestart = elements.autoStart.checked;
        markDirty();
    });
    elements.interruptionKind.addEventListener("change", () => {
        const interruption = state.draft.settings.interruption;
        interruption.kind = elements.interruptionKind.value;
        if (interruption.kind !== "INLINE") {
            interruption.steps = [];
        }
        if (interruption.kind !== "TASK_REFERENCE") {
            interruption.taskId = null;
        }
        markDirty(true);
    });
    elements.interruptionTask.addEventListener("change", () => {
        state.draft.settings.interruption.taskId =
            elements.interruptionTask.value || null;
        markDirty();
    });
    elements.addInterruptionStep.addEventListener("click", () => {
        addStep("interruption");
    });
    elements.addTaskStep.addEventListener("click", () => addStep("task"));
    elements.addPickupBinding.addEventListener("click", addPickupBinding);
    elements.pickupDimension.addEventListener("change", () => {
        state.draft.pickup.dimension = elements.pickupDimension.value;
        markDirty();
    });
    elements.pickupClassification.addEventListener("change", () => {
        state.draft.pickup.classificationTiming = elements.pickupClassification.value;
        markDirty();
    });
    elements.pickupCachePolicy.addEventListener("change", () => {
        state.draft.pickup.cachePolicy = elements.pickupCachePolicy.value;
        markDirty();
    });
    elements.pickupCompletionMode.addEventListener("change", () => {
        state.draft.pickup.completionMode = elements.pickupCompletionMode.value;
        markDirty();
    });
    elements.pickupCompletionIdle.addEventListener("input", () => {
        state.draft.pickup.completionIdleDelay = Number(
            elements.pickupCompletionIdle.value
        );
        markDirty();
    });
    elements.taskForm.addEventListener("submit", (event) => {
        event.preventDefault();
        void saveTask();
    });
    elements.discard.addEventListener("click", () => {
        void discardTask();
    });
    elements.run.addEventListener("click", () => {
        void runSelectedTask();
    });
    elements.enable.addEventListener("click", () => {
        if (state.draft) {
            void changeSchedule(state.draft.kind, state.draft.id, true);
        }
    });
    elements.disable.addEventListener("click", () => {
        if (state.draft) {
            void changeSchedule(state.draft.kind, state.draft.id, false);
        }
    });
    elements.remove.addEventListener("click", () => {
        void deleteSelectedTask();
    });
    elements.refreshRuntime.addEventListener("click", () => {
        void refreshLiveState(true);
    });

    window.setInterval(() => {
        if (document.visibilityState === "visible" && Stowling.isConnected()) {
            void refreshLiveState(false);
        }
    }, 5000);

    Resources.onChanged(async () => {
        state.resources = Resources.getSnapshot();
        if (state.draft) {
            renderPickupEditor();
            renderTriggerRegionHints();
        }
    });
    Stowling.onConnected(initialize);
    Stowling.mountPage("tasks");
})();
