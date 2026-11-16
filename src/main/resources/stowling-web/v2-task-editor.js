(() => {
    "use strict";

    const Stowling = window.Stowling;
    const Model = window.StowlingTaskModel;
    const OUTPUT_TYPES = Object.freeze(["output"]);
    const STEP_KIND_LABELS = Object.freeze({
        ACTION: "动作",
        IF: "条件",
        REPEAT: "循环",
        CREATE_TASK: "创建任务"
    });
    const STEP_KIND_ICONS = Object.freeze({
        ACTION: "◆",
        IF: "◇",
        REPEAT: "↻",
        CREATE_TASK: "＋"
    });
    const OUTPUT_REFERENCE_LABELS = Object.freeze({
        "output:container": "前序选择 · 容器",
        "output:storage": "前序选择 · 存储位",
        "output:marker": "前序选择 · 标记容器"
    });
    const OUTPUT_REFERENCE_HINTS = Object.freeze({
        "output:container": "需要先扫描区域并执行“选择容器”；不会自动选择缓存箱。",
        "output:storage": "需要先执行“选择存储位”；不会自动选择分类目标。",
        "output:marker": "需要先扫描分类标记并执行“选择标记容器”；"
            + "收集掉落物不会产生此结果。若要存入固定缓存箱，请选择它的已保存坐标。"
    });
    const ITEM_SOURCE_LABELS = Object.freeze({
        collected: "已收集",
        classified: "已分类",
        carried: "当前携带",
        managed: "任务管理物品"
    });
    const editorState = {
        nodeOpen: new Map(),
        configOpen: new Map(),
        draftKeys: new WeakMap(),
        nextDraftKey: 1
    };

    /**
     * @param {HTMLElement} host
     * @param {object} task
     * @param {Array<object>} modules
     * @param {Array<object>} coordinates
     * @param {Array<object>} regions
     * @param {Array<object>} categoryZones
     * @param {() => void} changed
     * @param {Array<object>} tasks
     * @returns {void}
     */
    function render(
        host,
        task,
        modules,
        coordinates,
        regions,
        categoryZones,
        changed,
        tasks = []
    ) {
        const context = {
            task,
            modules,
            coordinates,
            regions,
            categoryZones,
            tasks,
            changed
        };
        host.replaceChildren();
        const tree = Stowling.element("div", "workflow-tree");
        renderStepList(context, tree, task.steps || [], [], true, 0);
        host.append(tree);
    }

    /**
     * @param {object} context
     * @param {HTMLElement} host
     * @param {Array<object>} steps
     * @param {Array<number>} parentPath
     * @param {boolean} isRoot
     * @param {number} depth
     * @returns {void}
     */
    function renderStepList(context, host, steps, parentPath, isRoot, depth) {
        steps.forEach((step, index) => {
            const path = [...parentPath, index];
            host.append(createStepNode(
                context,
                step,
                steps,
                index,
                path,
                isRoot,
                depth
            ));
        });
    }

    /**
     * @param {object} context
     * @param {object} step
     * @param {Array<object>} siblings
     * @param {number} index
     * @param {Array<number>} path
     * @param {boolean} isRoot
     * @param {number} depth
     * @returns {HTMLElement}
     */
    function createStepNode(context, step, siblings, index, path, isRoot, depth) {
        const kind = step.kind || "ACTION";
        const module = context.modules.find(
            (candidate) => candidate.id === step.moduleId
        );
        const invalid = kind === "ACTION" && !module;
        const node = Stowling.element(
            "article",
            [
                "workflow-node",
                `workflow-node-kind-${kind.toLocaleLowerCase()}`,
                invalid ? "workflow-node-invalid" : "",
                invalid && step.moduleId ? "workflow-node-unsupported" : ""
            ].filter(Boolean).join(" ")
        );
        node.dataset.stepKind = kind;
        node.dataset.depth = String(Math.min(depth, 4));

        const stateKey = stepStateKey(step);
        const details = Stowling.element("details", "workflow-node-details");
        details.dataset.stateKey = stateKey;
        const summary = Stowling.element("summary", "workflow-node-summary");
        const icon = Stowling.element(
            "span",
            "workflow-node-icon",
            STEP_KIND_ICONS[kind] || "◆"
        );
        icon.setAttribute("aria-hidden", "true");
        const copy = Stowling.element("span", "workflow-node-copy");
        const title = Stowling.element(
            "strong",
            "workflow-node-title",
            stepHeadline(kind, module, step)
        );
        const meta = Stowling.element(
            "span",
            "workflow-node-meta",
            stepSummary(context, step, module)
        );
        copy.append(
            title,
            meta
        );
        const position = Stowling.element(
            "span", "workflow-node-position", path.map((index) => index + 1).join(".")
        );
        position.setAttribute("aria-label", `步骤 ${position.textContent}`);
        summary.append(
            position,
            icon,
            copy,
            Stowling.element(
                "span",
                "workflow-node-kind-badge",
                STEP_KIND_LABELS[kind] || "步骤"
            )
        );

        const body = Stowling.element("div", "workflow-node-body");
        if (kind === "ACTION") {
            body.append(actionEditor(context, step, module));
        } else if (kind === "CREATE_TASK") {
            body.append(createTaskEditor(context, step));
        } else {
            body.append(configurationEditor(context, step, kind));
            body.append(branchEditor(
                context,
                step,
                "steps",
                kind === "IF" ? "满足条件" : "重复执行",
                path,
                depth + 1,
                kind === "IF" ? "then" : "repeat"
            ));
            if (kind === "IF") {
                body.append(branchEditor(
                    context,
                    step,
                    "otherwise",
                    "否则",
                    path,
                    depth + 1,
                    "otherwise"
                ));
            }
        }
        body.addEventListener("input", () => {
            refreshNodeSummary(
                body,
                title,
                meta,
                context,
                step,
                module,
                kind
            );
        });
        body.addEventListener("change", () => {
            refreshNodeSummary(
                body,
                title,
                meta,
                context,
                step,
                module,
                kind
            );
        });
        details.append(summary, body);
        details.open = expansionValue(
            editorState.nodeOpen,
            stateKey,
            kind === "IF" || kind === "REPEAT"
        );
        bindExpansion(details, editorState.nodeOpen, stateKey);

        const tools = Stowling.element("div", "workflow-node-tools");
        tools.append(
            moveButton("↑", siblings, index, -1, context.changed),
            moveButton("↓", siblings, index, 1, context.changed),
            deleteButton(context, step, siblings, index, isRoot)
        );
        node.append(details, tools);
        return node;
    }

    /**
     * @param {object} context
     * @param {object} step
     * @param {object | undefined} module
     * @returns {HTMLElement}
     */
    function actionEditor(context, step, module) {
        const fields = Stowling.element("div", "editor-fields");
        fields.append(field(
            "基础动作",
            moduleSelect(step, context.modules, context.changed)
        ));
        const parameters = Stowling.element("div", "step-fields");
        for (const parameter of module?.parameters || []) {
            parameters.append(field(
                `${parameter.label}${parameter.required ? " *" : ""}`,
                parameterControl(context, step, parameter)
            ));
        }
        const editor = Stowling.element("div", "workflow-action-editor");
        editor.append(fields);
        if (parameters.children.length > 0) {
            editor.append(parameters);
        }
        return editor;
    }

    /**
     * @param {object} context
     * @param {object} step
     * @returns {HTMLElement}
     */
    function createTaskEditor(context, step) {
        const editor = Stowling.element("div", "workflow-action-editor");
        const fields = Stowling.element("div", "editor-fields");
        fields.append(
            field(
                "目标任务",
                taskReferenceSelect(
                    step,
                    "taskRef",
                    "请选择要创建的任务",
                    context,
                    true
                )
            ),
            field(
                "新任务优先级",
                createTaskPrioritySelect(step, context.changed)
            )
        );
        editor.append(
            fields,
            Stowling.element(
                "p",
                "muted compact-help workflow-node-help",
                "未指定优先级时沿用目标任务配置。"
            )
        );
        return editor;
    }

    /**
     * @param {object} context
     * @param {object} step
     * @param {string} kind
     * @returns {HTMLElement}
     */
    function configurationEditor(context, step, kind) {
        const key = stepStateKey(step);
        const details = Stowling.element("details", "workflow-node-config");
        const summary = Stowling.element("summary", "workflow-config-summary");
        summary.append(
            Stowling.element("span", "", kind === "IF" ? "条件配置" : "循环配置"),
            Stowling.element(
                "span",
                "workflow-config-meta",
                configurationSummary(step, kind)
            )
        );

        const body = Stowling.element("div", "workflow-config-body");
        const fields = Stowling.element("div", "editor-fields");
        fields.append(field(
            "条件",
            conditionSelect(step, context.changed)
        ));
        const parameters = Stowling.element("div", "step-fields");
        if (Model.conditionNeedsThreshold(step.parameters.condition)) {
            parameters.append(field(
                "饱食度阈值 *",
                integerControl(
                    step,
                    "foodLevelThreshold",
                    context.changed,
                    0,
                    20,
                    18
                )
            ));
        }
        parameters.append(field(
            "条件取反",
            negateControl(step, context.changed)
        ));
        if (kind === "REPEAT") {
            parameters.append(field(
                "最大循环次数",
                integerControl(
                    step,
                    "maxIterations",
                    context.changed,
                    1,
                    Model.MAX_REPEAT_ITERATIONS,
                    Model.DEFAULT_REPEAT_ITERATIONS
                )
            ));
        }
        body.append(fields, parameters);
        details.append(summary, body);
        details.open = expansionValue(editorState.configOpen, key, false);
        bindExpansion(details, editorState.configOpen, key);
        return details;
    }

    /**
     * @param {string} kind
     * @param {object | undefined} module
     * @param {object} step
     * @returns {string}
     */
    function stepHeadline(kind, module, step) {
        if (kind === "ACTION") {
            if (module?.name) {
                return module.name;
            }
            return step.moduleId ? "动作不可用" : "未选择动作";
        }
        if (kind === "CREATE_TASK") {
            return "创建独立任务";
        }
        if (kind === "IF") {
            const condition = step.parameters?.condition || "ALWAYS";
            return condition === "ALWAYS"
                ? "条件分支"
                : `如果${conditionPhrase(step)}`;
        }
        const condition = step.parameters?.condition || "ALWAYS";
        return condition === "ALWAYS"
            ? "重复执行"
            : `当${conditionPhrase(step)}时重复`;
    }

    /**
     * @param {object} context
     * @param {object} step
     * @param {object | undefined} module
     * @returns {string}
     */
    function stepSummary(context, step, module) {
        const kind = step.kind || "ACTION";
        if (kind === "ACTION") {
            return actionParameterSummary(context, step, module);
        }
        if (kind === "IF") {
            return [
                `条件：${conditionPhrase(step)}`,
                `满足时 ${(step.steps || []).length} 步`,
                `否则 ${(step.otherwise || []).length} 步`
            ].join(" · ");
        }
        if (kind === "REPEAT") {
            return [
                `条件：${conditionPhrase(step)}`,
                `最多 ${repeatCount(step)} 次`,
                `每轮 ${(step.steps || []).length} 步`
            ].join(" · ");
        }
        return createTaskSummary(context, step);
    }

    /**
     * @param {HTMLElement} body
     * @param {HTMLElement} title
     * @param {HTMLElement} meta
     * @param {object} context
     * @param {object} step
     * @param {object | undefined} module
     * @param {string} kind
     * @returns {void}
     */
    function refreshNodeSummary(body, title, meta, context, step, module, kind) {
        title.textContent = stepHeadline(kind, module, step);
        meta.textContent = stepSummary(context, step, module);
        const configMeta = body.querySelector(".workflow-config-meta");
        if (configMeta) {
            configMeta.textContent = configurationSummary(step, kind);
        }
    }

    /**
     * @param {object} step
     * @returns {string}
     */
    function conditionPhrase(step) {
        const parameters = step.parameters || {};
        const condition = parameters.condition || "ALWAYS";
        const label = condition === "FOOD_BELOW_THRESHOLD"
            ? `饱食度低于 ${parameters.foodLevelThreshold || 18}`
            : Model.conditionLabel(condition);
        return parameters.negate === "true" ? `不满足“${label}”` : label;
    }

    /**
     * @param {object} step
     * @returns {string}
     */
    function repeatCount(step) {
        return String(
            step.parameters?.maxIterations || Model.DEFAULT_REPEAT_ITERATIONS
        );
    }

    /**
     * @param {object} step
     * @param {string} kind
     * @returns {string}
     */
    function configurationSummary(step, kind) {
        const parts = [conditionPhrase(step)];
        if (kind === "REPEAT") {
            parts.push(`最多 ${repeatCount(step)} 次`);
        }
        return parts.join(" · ");
    }

    /**
     * @param {object} context
     * @param {object} step
     * @param {object | undefined} module
     * @returns {string}
     */
    function actionParameterSummary(context, step, module) {
        if (!module) {
            return step.moduleId ? "请选择可用动作" : "尚未选择动作";
        }
        const parts = [];
        for (const parameter of module.parameters || []) {
            const value = parameterSummaryValue(context, step, parameter);
            if (value) {
                parts.push(`${parameter.label}：${value}`);
            }
        }
        if (parts.length === 0) {
            return module.description || "无需额外参数";
        }
        if (parts.length > 4) {
            return [
                parts.slice(0, 4).join(" · "),
                `另有 ${parts.length - 4} 项`
            ].join(" · ");
        }
        return parts.join(" · ");
    }

    /**
     * @param {object} context
     * @param {object} step
     * @param {object} parameter
     * @returns {string}
     */
    function parameterSummaryValue(context, step, parameter) {
        const value = step.parameters?.[parameter.key]
            ?? defaultValue(parameter);
        if (value === null || value === undefined || value === "") {
            return parameter.required ? "未设置" : "";
        }
        const referenceTypes = referenceTypesFor(step, parameter);
        if (referenceTypes.length > 0) {
            return referenceDisplayName(context, String(value));
        }
        if (parameter.kind === "boolean") {
            return String(value) === "true" ? "是" : "否";
        }
        if (parameter.key === "priority") {
            return Model.priorityLabel(String(value));
        }
        if (parameter.kind === "select") {
            return optionDisplayLabel(parameter, String(value));
        }
        return humanizeParameterValue(String(value));
    }

    /**
     * @param {object} parameter
     * @param {string} value
     * @returns {string}
     */
    function optionDisplayLabel(parameter, value) {
        if (parameter.key === "itemSource" && ITEM_SOURCE_LABELS[value]) {
            return ITEM_SOURCE_LABELS[value];
        }
        for (const option of parameter.options || []) {
            const optionValue = String(option?.value ?? option);
            if (optionValue !== value) {
                continue;
            }
            const label = option?.label ?? option?.name ?? option?.text;
            return label ? String(label) : humanizeParameterValue(value);
        }
        return humanizeParameterValue(value);
    }

    /**
     * @param {string} value
     * @returns {string}
     */
    function humanizeParameterValue(value) {
        return value.split(",").map((part) => {
            const trimmed = part.trim();
            if (!trimmed) {
                return "";
            }
            const separator = trimmed.lastIndexOf(":");
            const name = separator >= 0
                ? trimmed.slice(separator + 1)
                : trimmed;
            return name.replaceAll("_", " ");
        }).filter(Boolean).join("、");
    }

    /**
     * @param {object} context
     * @param {object} step
     * @returns {string}
     */
    function createTaskSummary(context, step) {
        const parameters = step.parameters || {};
        const task = parameters.taskRef
            ? referenceDisplayName(context, parameters.taskRef)
            : "尚未选择目标任务";
        const priority = parameters.priority
            ? `优先级：${Model.priorityLabel(parameters.priority)}`
            : "沿用目标任务优先级";
        return `${task} · ${priority}`;
    }

    /**
     * @param {object} context
     * @param {string} value
     * @returns {string}
     */
    function referenceDisplayName(context, value) {
        const text = String(value || "");
        if (!text) {
            return "未设置";
        }
        if (OUTPUT_REFERENCE_LABELS[text]) {
            return OUTPUT_REFERENCE_LABELS[text];
        }
        const parsed = Model.parseReference(text);
        if (parsed) {
            const resource = findResource(context, parsed.type, parsed.id);
            if (resource) {
                return resourceDisplayName(
                    resource,
                    referenceTypeLabel(parsed.type)
                );
            }
            return `已失效的${referenceTypeLabel(parsed.type)}`;
        }
        for (const [type, resources] of referenceCollections(context)) {
            const resource = resources.find((candidate) => candidate.id === text);
            if (resource) {
                return resourceDisplayName(resource, referenceTypeLabel(type));
            }
        }
        return "引用不可用";
    }

    /**
     * @param {object} context
     * @param {string} type
     * @param {string} id
     * @returns {object | undefined}
     */
    function findResource(context, type, id) {
        for (const [candidateType, resources] of referenceCollections(context)) {
            if (candidateType === type) {
                return resources.find((resource) => resource.id === id);
            }
        }
        return undefined;
    }

    /**
     * @param {object} context
     * @returns {Array<[string, Array<object>]>}
     */
    function referenceCollections(context) {
        return [
            ["coordinate", context.coordinates || []],
            ["region", context.regions || []],
            ["category-zone", context.categoryZones || []],
            ["task", context.tasks || []]
        ];
    }

    /**
     * @param {object} resource
     * @param {string} fallback
     * @returns {string}
     */
    function resourceDisplayName(resource, fallback) {
        const name = String(
            resource?.displayName
                ?? resource?.name
                ?? resource?.label
                ?? ""
        ).trim();
        return name || fallback;
    }

    /**
     * @param {object} step
     * @returns {string}
     */
    function stepStateKey(step) {
        if (step?.id) {
            return `step:${String(step.id)}`;
        }
        let key = editorState.draftKeys.get(step);
        if (!key) {
            key = `draft:${editorState.nextDraftKey}`;
            editorState.nextDraftKey += 1;
            editorState.draftKeys.set(step, key);
        }
        return key;
    }

    /**
     * @param {Map<string, boolean>} values
     * @param {string} key
     * @param {boolean} fallback
     * @returns {boolean}
     */
    function expansionValue(values, key, fallback) {
        return values.has(key) ? values.get(key) : fallback;
    }

    /**
     * @param {HTMLDetailsElement} details
     * @param {Map<string, boolean>} values
     * @param {string} key
     * @returns {void}
     */
    function bindExpansion(details, values, key) {
        details.addEventListener("toggle", () => {
            values.set(key, details.open);
        });
    }

    /**
     * @param {object} step
     * @returns {void}
     */
    function forgetStepState(step) {
        if (!step) {
            return;
        }
        const key = stepStateKey(step);
        editorState.nodeOpen.delete(key);
        editorState.configOpen.delete(key);
        editorState.draftKeys.delete(step);
        for (const child of step.steps || []) {
            forgetStepState(child);
        }
        for (const child of step.otherwise || []) {
            forgetStepState(child);
        }
    }

    /**
     * @param {object} step
     * @param {Array<object>} modules
     * @param {() => void} changed
     * @returns {HTMLSelectElement}
     */
    function moduleSelect(step, modules, changed) {
        const select = document.createElement("select");
        select.required = true;
        const actions = Model.actionModules(modules);
        const known = actions.some((module) => module.id === step.moduleId);
        if (!known && step.moduleId) {
            select.append(new Option("不可用动作，请重新选择", step.moduleId));
        } else {
            select.append(new Option("请选择基础动作", ""));
        }
        const group = document.createElement("optgroup");
        group.label = "基础动作";
        for (const module of actions) {
            group.append(new Option(module.name, module.id));
        }
        if (group.children.length > 0) {
            select.append(group);
        }
        select.value = step.moduleId;
        select.addEventListener("change", () => {
            const selected = modules.find((module) => module.id === select.value);
            step.moduleId = select.value;
            step.parameters = defaultParameters(selected);
            changed(true);
        });
        return select;
    }

    /**
     * @param {object} step
     * @param {() => void} changed
     * @returns {HTMLSelectElement}
     */
    function conditionSelect(step, changed) {
        const select = document.createElement("select");
        for (const condition of Model.CONDITIONS) {
            select.append(new Option(
                Model.conditionLabel(condition),
                condition
            ));
        }
        select.value = step.parameters.condition || "ALWAYS";
        select.addEventListener("change", () => {
            step.parameters.condition = select.value;
            if (Model.conditionNeedsThreshold(select.value)) {
                if (step.parameters.foodLevelThreshold === undefined) {
                    step.parameters.foodLevelThreshold = "18";
                }
            } else {
                delete step.parameters.foodLevelThreshold;
            }
            changed(true);
        });
        return select;
    }

    /**
     * @param {object} step
     * @param {() => void} changed
     * @returns {HTMLElement}
     */
    function negateControl(step, changed) {
        const label = Stowling.element("label", "checkbox-field compact-check");
        const input = document.createElement("input");
        input.type = "checkbox";
        input.checked = step.parameters.negate === "true";
        input.addEventListener("change", () => {
            if (input.checked) {
                step.parameters.negate = "true";
            } else {
                delete step.parameters.negate;
            }
            changed();
        });
        label.append(input, Stowling.element("span", "", "反向判断"));
        return label;
    }

    /**
     * @param {object} step
     * @param {() => void} changed
     * @returns {HTMLSelectElement}
     */
    function createTaskPrioritySelect(step, changed) {
        const select = document.createElement("select");
        select.append(new Option("沿用目标任务优先级", ""));
        for (const priority of Model.PRIORITIES) {
            select.append(new Option(
                Model.priorityLabel(priority),
                priority
            ));
        }
        select.value = step.parameters.priority || "";
        select.addEventListener("change", () => {
            if (select.value) {
                step.parameters.priority = select.value;
            } else {
                delete step.parameters.priority;
            }
            changed();
        });
        return select;
    }

    /**
     * @param {object} context
     * @param {object} step
     * @param {object} parameter
     * @returns {HTMLElement}
     */
    function parameterControl(context, step, parameter) {
        const referenceTypes = referenceTypesFor(step, parameter);
        if (referenceTypes.length > 0) {
            return referenceSelect(
                context,
                step,
                parameter,
                referenceTypes
            );
        }
        if (parameter.key === "itemSource") {
            return itemSourceSelect(step, parameter, context.changed);
        }
        if (parameter.kind === "select") {
            return optionSelect(step, parameter, context.changed);
        }
        if (parameter.kind === "integer") {
            return contractIntegerInput(context, step, parameter);
        }
        if (parameter.kind === "boolean") {
            return booleanControl(step, parameter, context.changed);
        }
        return textControl(step, parameter, context.changed);
    }

    /**
     * @param {object} step
     * @param {object} parameter
     * @returns {Array<string>}
     */
    function referenceTypesFor(step, parameter) {
        const key = String(parameter.key || "");
        if (key === "taskRef" || parameter.kind === "task-reference") {
            return ["task"];
        }
        if (key === "regionRef") {
            return ["region"];
        }
        if (key === "categoryZoneRef") {
            return ["category-zone"];
        }
        if (key === "areaRef" || parameter.kind === "area-reference") {
            return ["coordinate", "region"];
        }
        if (key === "targetRef") {
            return targetReferenceTypesFor(step.moduleId);
        }
        if (
            parameter.kind === "coordinate-point"
            || parameter.kind === "coordinate-reference"
        ) {
            return ["coordinate"];
        }
        if (
            parameter.kind === "region-point"
            || parameter.kind === "region-reference"
        ) {
            return ["region"];
        }
        if (
            parameter.kind === "category-zone-point"
            || parameter.kind === "category-zone-reference"
        ) {
            return ["category-zone"];
        }
        if (parameter.kind === "container-reference") {
            return ["output"];
        }
        if (parameter.kind === "resource-reference") {
            return resourceTypesFromParameter(parameter);
        }
        return [];
    }

    /**
     * @param {string} moduleId
     * @returns {Array<string>}
     */
    function targetReferenceTypesFor(moduleId) {
        if (moduleId === "MOVE_TO_COORDINATE") {
            return ["coordinate", ...OUTPUT_TYPES];
        }
        if (moduleId === "WITHDRAW_FOOD") {
            return ["coordinate"];
        }
        if (moduleId === "READ_CONTAINER") {
            return ["output-marker"];
        }
        if (moduleId === "DEPOSIT_ITEMS_TO_CONTAINERS") {
            return ["coordinate", ...outputTypesFor(moduleId)];
        }
        const outputs = outputTypesFor(moduleId);
        return outputs.length > 0 ? outputs : ["coordinate"];
    }

    /**
     * @param {string} moduleId
     * @returns {Array<string>}
     */
    function outputTypesFor(moduleId) {
        const outputs = {
            MOVE_TO_COORDINATE: OUTPUT_TYPES,
            WITHDRAW_ITEMS: ["output-container"],
            DEPOSIT_ITEMS_TO_CONTAINERS: [
                "output-container",
                "output-storage",
                "output-marker"
            ],
            READ_CONTAINER: ["output-marker"]
        };
        return outputs[moduleId] || [];
    }

    /**
     * @param {object} parameter
     * @returns {Array<string>}
     */
    function resourceTypesFromParameter(parameter) {
        const value = String(
            parameter.resourceType
                || parameter.referenceType
                || parameter.valueType
                || parameter.kind
                || ""
        ).toLocaleLowerCase();
        const types = [];
        if (value.includes("coordinate")) {
            types.push("coordinate");
        }
        if (value.includes("region")) {
            types.push("region");
        }
        if (value.includes("category") || value.includes("zone")) {
            types.push("category-zone");
        }
        if (value.includes("task")) {
            types.push("task");
        }
        return types.length > 0 ? types : ["coordinate", "region"];
    }

    /**
     * @param {object} context
     * @param {object} step
     * @param {object} parameter
     * @param {Array<string>} types
     * @returns {HTMLElement}
     */
    function referenceSelect(context, step, parameter, types) {
        const select = document.createElement("select");
        select.required = Boolean(parameter.required);
        select.setAttribute("aria-label", parameter.label || "资源引用");
        select.append(new Option("请选择已保存资源或前序选择结果", ""));
        const current = normalizeLegacyReference(context, step, parameter, types);
        let found = false;
        const groups = new Map();
        for (const type of types) {
            const options = referenceOptions(context, step, type);
            if (options.length === 0) {
                continue;
            }
            const groupType = type.startsWith("output") ? "output" : type;
            let group = groups.get(groupType);
            if (!group) {
                group = document.createElement("optgroup");
                group.label = groupType === "coordinate"
                    ? "已保存坐标"
                    : referenceTypeLabel(groupType);
                groups.set(groupType, group);
                select.append(group);
            }
            for (const option of options) {
                const label = groupType === "output"
                    ? option.label.replace(/^前序选择 · /, "")
                    : option.label;
                group.append(new Option(label, option.value));
                found = found || option.value === current;
            }
        }
        if (current && !found) {
            const invalid = document.createElement("optgroup");
            invalid.label = "已失效引用";
            invalid.append(new Option(
                `已删除或不可用的${referenceTypeLabel(
                    Model.referenceType(current)
                )}`,
                current
            ));
            select.append(invalid);
        }
        select.value = current;
        const wrapper = Stowling.element("div", "task-reference-control");
        const hint = Stowling.element("p", "task-reference-hint");
        hint.setAttribute("aria-live", "polite");
        /** @returns {void} */
        const updateHint = () => {
            hint.textContent = OUTPUT_REFERENCE_HINTS[select.value] || "";
            if (
                step.moduleId === "DEPOSIT_ITEMS_TO_CONTAINERS"
                && Model.referenceType(select.value) === "coordinate"
                && context.coordinates.some(
                    (point) => Model.coordinateRef(point.id) === select.value
                )
            ) {
                hint.textContent = "直接存入该坐标处的容器；请确认坐标指向箱子等可打开的容器。";
            }
            hint.hidden = !hint.textContent;
        };
        updateHint();
        select.addEventListener("change", () => {
            updateHint();
            setParameter(step, parameter.key, select.value, context.changed);
        });
        wrapper.append(select, hint);
        return wrapper;
    }

    /**
     * @param {object} context
     * @param {object} step
     * @param {object} parameter
     * @param {Array<string>} types
     * @returns {string}
     */
    function normalizeLegacyReference(context, step, parameter, types) {
        let value = String(step.parameters[parameter.key] || "");
        if (!value || Model.parseReference(value)) {
            return value;
        }
        const candidates = [
            ["coordinate", context.coordinates],
            ["region", context.regions],
            ["category-zone", context.categoryZones],
            ["task", context.tasks]
        ];
        for (const [type, resources] of candidates) {
            if (!types.includes(type)) {
                continue;
            }
            const match = resources.some((resource) => resource.id === value);
            if (match) {
                value = Model.reference(type, value);
                step.parameters[parameter.key] = value;
                return value;
            }
        }
        return "";
    }

    /**
     * @param {object} context
     * @param {object} step
     * @param {string} type
     * @returns {Array<{label: string, value: string}>}
     */
    function referenceOptions(context, step, type) {
        if (type === "coordinate") {
            return context.coordinates.map((point) => ({
                label: coordinateLabel(point),
                value: Model.coordinateRef(point.id)
            }));
        }
        if (type === "region") {
            return context.regions.map((region) => ({
                label: String(region.name || "未命名区域"),
                value: Model.regionRef(region.id)
            }));
        }
        if (type === "category-zone") {
            return context.categoryZones.map((zone) => ({
                label: String(zone.name || "未命名分类区"),
                value: Model.categoryZoneRef(zone.id)
            }));
        }
        if (type === "task") {
            return context.tasks
                .filter((task) => task.id && task.id !== context.task.id)
                .map((task) => ({
                    label: String(task.name || "未命名任务"),
                    value: Model.taskRef(task.id)
                }));
        }
        return outputOptions(type, step.moduleId);
    }

    /**
     * @param {object} point
     * @returns {string}
     */
    function coordinateLabel(point) {
        return window.StowlingCoordinateModel?.coordinateLabel(point)
            || String(point?.name || "未命名坐标");
    }

    /**
     * @param {string} type
     * @param {string} moduleId
     * @returns {Array<{label: string, value: string}>}
     */
    function outputOptions(type, moduleId) {
        const all = [
            {
                type: "output-container",
                label: OUTPUT_REFERENCE_LABELS["output:container"],
                value: "output:container"
            },
            {
                type: "output-storage",
                label: OUTPUT_REFERENCE_LABELS["output:storage"],
                value: "output:storage"
            },
            {
                type: "output-marker",
                label: OUTPUT_REFERENCE_LABELS["output:marker"],
                value: "output:marker"
            }
        ];
        return all.filter((option) => {
            if (type === "output") {
                return moduleId === "MOVE_TO_COORDINATE";
            }
            return option.type === type;
        });
    }

    /**
     * @param {string} type
     * @returns {string}
     */
    function referenceTypeLabel(type) {
        const labels = {
            coordinate: "坐标",
            region: "区域",
            "category-zone": "分类区",
            task: "任务",
            output: "前序步骤结果",
            "output-container": OUTPUT_REFERENCE_LABELS["output:container"],
            "output-storage": OUTPUT_REFERENCE_LABELS["output:storage"],
            "output-marker": OUTPUT_REFERENCE_LABELS["output:marker"]
        };
        return labels[type] || "引用";
    }

    /**
     * @param {object} step
     * @param {object} parameter
     * @param {() => void} changed
     * @returns {HTMLSelectElement}
     */
    function itemSourceSelect(step, parameter, changed) {
        const select = document.createElement("select");
        select.required = Boolean(parameter.required);
        select.append(new Option("请选择物品来源", ""));
        const choices = parameter.options?.length > 0
            ? parameter.options
            : ["collected", "classified", "carried", "managed"];
        const labels = {
            collected: "已收集",
            classified: "已分类",
            carried: "当前携带",
            managed: "任务管理物品"
        };
        for (const option of choices) {
            select.append(new Option(labels[option] || String(option), option));
        }
        select.value = String(step.parameters[parameter.key] || "");
        select.addEventListener("change", () => {
            setParameter(step, parameter.key, select.value, changed);
        });
        return select;
    }

    /**
     * @param {object} step
     * @param {object} parameter
     * @param {() => void} changed
     * @returns {HTMLSelectElement}
     */
    function optionSelect(step, parameter, changed) {
        const select = document.createElement("select");
        select.required = Boolean(parameter.required);
        if (!parameter.required) {
            select.append(new Option("请选择", ""));
        }
        for (const option of parameter.options || []) {
            select.append(new Option(String(option), String(option)));
        }
        select.value = String(
            step.parameters[parameter.key]
                ?? defaultValue(parameter)
                ?? ""
        );
        select.addEventListener("change", () => {
            setParameter(step, parameter.key, select.value, changed);
        });
        return select;
    }

    /**
     * @param {object} context
     * @param {object} step
     * @param {object} parameter
     * @returns {HTMLInputElement}
     */
    function contractIntegerInput(context, step, parameter) {
        return integerControl(
            step,
            parameter.key,
            context.changed,
            parameter.min ?? parameter.minimum,
            parameter.max ?? parameter.maximum,
            defaultValue(parameter),
            Boolean(parameter.required),
            parameter.step
        );
    }

    /**
     * @param {object} step
     * @param {string} key
     * @param {() => void} changed
     * @param {number} minimum
     * @param {number} maximum
     * @param {number} fallback
     * @param {boolean} required
     * @param {number} stepSize
     * @returns {HTMLInputElement}
     */
    function integerControl(
        step,
        key,
        changed,
        minimum,
        maximum,
        fallback,
        required = false,
        stepSize = 1
    ) {
        const input = document.createElement("input");
        input.type = "number";
        input.step = String(stepSize ?? 1);
        input.required = required;
        if (minimum !== undefined && minimum !== null) {
            input.min = String(minimum);
        }
        if (maximum !== undefined && maximum !== null) {
            input.max = String(maximum);
        }
        const current = step.parameters[key];
        input.value = String(current ?? fallback ?? "");
        input.addEventListener("input", () => {
            setNumericParameter(step, key, input.value, changed);
        });
        return input;
    }

    /**
     * @param {object} step
     * @param {object} parameter
     * @param {() => void} changed
     * @returns {HTMLElement}
     */
    function booleanControl(step, parameter, changed) {
        const label = Stowling.element("label", "checkbox-field compact-check");
        const input = document.createElement("input");
        input.type = "checkbox";
        input.checked = step.parameters[parameter.key] === "true";
        input.addEventListener("change", () => {
            setParameter(
                step,
                parameter.key,
                input.checked ? "true" : "",
                changed
            );
        });
        label.append(input, Stowling.element("span", "", "启用"));
        return label;
    }

    /**
     * @param {object} step
     * @param {object} parameter
     * @param {() => void} changed
     * @returns {HTMLInputElement}
     */
    function textControl(step, parameter, changed) {
        const input = document.createElement("input");
        input.type = "text";
        input.value = String(
            step.parameters[parameter.key]
                ?? defaultValue(parameter)
                ?? ""
        );
        input.required = Boolean(parameter.required);
        input.addEventListener("input", () => {
            setParameter(step, parameter.key, input.value, changed);
        });
        return input;
    }

    /**
     * @param {object} context
     * @param {object} step
     * @param {string} branchKey
     * @param {string} title
     * @param {Array<number>} parentPath
     * @param {number} depth
     * @param {string} role
     * @returns {HTMLElement}
     */
    function branchEditor(context, step, branchKey, title, parentPath, depth, role) {
        if (!Array.isArray(step[branchKey])) {
            step[branchKey] = [];
        }
        const cappedDepth = Math.min(depth, 4);
        const branch = Stowling.element(
            "section",
            [
                "workflow-branch",
                `workflow-branch-${role}`,
                `workflow-depth-${cappedDepth}`
            ].join(" ")
        );
        branch.dataset.depth = String(cappedDepth);
        const heading = Stowling.element("div", "workflow-branch-heading");
        heading.append(Stowling.element(
            "span",
            "workflow-branch-title",
            title
        ));
        const addButtons = Stowling.element("div", "workflow-branch-actions");
        for (const [kind, label] of [
            ["ACTION", "动作"],
            ["IF", "条件"],
            ["REPEAT", "循环"],
            ["CREATE_TASK", "创建任务"]
        ]) {
            addButtons.append(createAddButton(
                context,
                step[branchKey],
                kind,
                label
            ));
        }
        heading.append(addButtons);
        branch.append(heading);
        const list = Stowling.element("div", "workflow-step-list");
        renderStepList(
            context,
            list,
            step[branchKey],
            parentPath,
            false,
            depth
        );
        if (step[branchKey].length === 0) {
            list.append(Stowling.element(
                "p",
                "muted compact-help workflow-branch-empty",
                "尚未添加步骤"
            ));
        }
        branch.append(list);
        return branch;
    }

    /**
     * @param {object} context
     * @param {Array<object>} steps
     * @param {string} kind
     * @param {string} label
     * @returns {HTMLButtonElement}
     */
    function createAddButton(context, steps, kind, label) {
        const button = Stowling.element(
            "button",
            "secondary compact step-add-button",
            `＋${label}`
        );
        button.type = "button";
        button.addEventListener("click", (event) => {
            event.stopPropagation();
            steps.push(Model.newStep(
                Model.defaultModule(context.modules),
                kind
            ));
            context.changed(true);
        });
        return button;
    }

    /**
     * @param {object | undefined} module
     * @returns {object}
     */
    function defaultParameters(module) {
        const parameters = {};
        for (const parameter of module?.parameters || []) {
            const value = defaultValue(parameter);
            if (value !== null && value !== undefined && value !== "") {
                parameters[parameter.key] = value;
            }
        }
        return parameters;
    }

    /**
     * @param {object | undefined} parameter
     * @returns {unknown}
     */
    function defaultValue(parameter) {
        if (!parameter) {
            return null;
        }
        if (parameter.defaultValue !== undefined) {
            return parameter.defaultValue;
        }
        if (parameter.default !== undefined) {
            return parameter.default;
        }
        if (
            parameter.kind === "select"
            && Array.isArray(parameter.options)
            && parameter.options.length === 1
        ) {
            return parameter.options[0];
        }
        return null;
    }

    /**
     * @param {object} step
     * @param {string} key
     * @param {string} value
     * @param {() => void} changed
     * @returns {void}
     */
    function setParameter(step, key, value, changed) {
        if (value !== "" && value !== null && value !== undefined) {
            step.parameters[key] = value;
        } else {
            delete step.parameters[key];
        }
        changed();
    }

    /**
     * @param {object} step
     * @param {string} key
     * @param {string} value
     * @param {() => void} changed
     * @returns {void}
     */
    function setNumericParameter(step, key, value, changed) {
        if (value === "") {
            delete step.parameters[key];
        } else {
            step.parameters[key] = String(value);
        }
        changed();
    }

    /**
     * @param {string} text
     * @param {Array<object>} steps
     * @param {number} index
     * @param {number} offset
     * @param {() => void} changed
     * @returns {HTMLButtonElement}
     */
    function moveButton(text, steps, index, offset, changed) {
        const button = Stowling.element("button", "secondary compact", text);
        button.type = "button";
        button.disabled = index + offset < 0 || index + offset >= steps.length;
        button.addEventListener("click", (event) => {
            event.stopPropagation();
            const [step] = steps.splice(index, 1);
            steps.splice(index + offset, 0, step);
            changed(true);
        });
        return button;
    }

    /**
     * @param {object} context
     * @param {object} step
     * @param {Array<object>} steps
     * @param {number} index
     * @param {boolean} isRoot
     * @returns {HTMLButtonElement}
     */
    function deleteButton(context, step, steps, index, isRoot) {
        const button = Stowling.element(
            "button",
            "ghost compact danger-action",
            "删除"
        );
        button.type = "button";
        button.disabled = isRoot && steps.length <= 1;
        button.addEventListener("click", (event) => {
            event.stopPropagation();
            forgetStepState(step);
            steps.splice(index, 1);
            context.changed(true);
        });
        return button;
    }

    /**
     * @param {string} label
     * @param {HTMLElement} control
     * @returns {HTMLElement}
     */
    function field(label, control) {
        const wrapper = Stowling.element("div", "editor-field");
        wrapper.append(Stowling.element("label", "", label), control);
        return wrapper;
    }

    window.StowlingTaskEditor = Object.freeze({ render });
})();
