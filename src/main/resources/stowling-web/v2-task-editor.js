(() => {
    "use strict";

    const Stowling = window.Stowling;
    const Model = window.StowlingTaskModel;
    const OUTPUT_TYPES = Object.freeze(["output"]);

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
        renderStepList(context, host, task.steps || [], [], true);
    }

    /**
     * @param {object} context
     * @param {HTMLElement} host
     * @param {Array<object>} steps
     * @param {Array<number>} parentPath
     * @param {boolean} isRoot
     * @returns {void}
     */
    function renderStepList(context, host, steps, parentPath, isRoot) {
        steps.forEach((step, index) => {
            const path = [...parentPath, index];
            host.append(createStepCard(context, step, steps, index, path, isRoot));
        });
    }

    /**
     * @param {object} context
     * @param {object} step
     * @param {Array<object>} siblings
     * @param {number} index
     * @param {Array<number>} path
     * @param {boolean} isRoot
     * @returns {HTMLElement}
     */
    function createStepCard(context, step, siblings, index, path, isRoot) {
        const kind = step.kind || "ACTION";
        const module = context.modules.find(
            (candidate) => candidate.id === step.moduleId
        );
        const card = Stowling.element(
            "article",
            `step-card step-kind-${kind.toLocaleLowerCase()}`
        );
        const heading = Stowling.element("div", "step-card-heading");
        const identity = Stowling.element("div", "step-identity");
        identity.append(
            Stowling.element("strong", "", stepTitle(kind, module, step)),
            Stowling.element("span", "muted compact-help", stepHelp(kind, module))
        );
        const actions = Stowling.element("div", "move-buttons");
        actions.append(
            moveButton("↑", siblings, index, -1, context.changed),
            moveButton("↓", siblings, index, 1, context.changed),
            deleteButton(siblings, index, isRoot, context.changed)
        );
        heading.append(
            Stowling.element("span", "step-number", pathLabel(path)),
            identity,
            actions
        );

        const fields = Stowling.element("div", "editor-fields");
        if (kind === "ACTION") {
            fields.append(field(
                "基础动作",
                moduleSelect(step, context.modules, context.changed)
            ));
        } else if (kind === "CREATE_TASK") {
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
        } else {
            fields.append(field(
                "条件",
                conditionSelect(step, context.changed)
            ));
        }

        const parameters = Stowling.element("div", "step-fields");
        if (kind === "ACTION") {
            for (const parameter of module?.parameters || []) {
                parameters.append(field(
                    `${parameter.label}${parameter.required ? " *" : ""}`,
                    parameterControl(context, step, parameter)
                ));
            }
        } else if (kind === "IF" || kind === "REPEAT") {
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
        }

        card.append(heading, fields);
        if (parameters.children.length > 0) {
            card.append(parameters);
        }
        if (kind === "IF") {
            card.append(
                branchEditor(context, step, "steps", "满足时", path),
                branchEditor(context, step, "otherwise", "否则", path)
            );
        } else if (kind === "REPEAT") {
            card.append(branchEditor(context, step, "steps", "每轮执行", path));
        } else if (kind === "CREATE_TASK") {
            card.append(Stowling.element(
                "p",
                "muted compact-help step-kind-hint",
                "新任务会独立入队；未指定优先级时沿用目标任务配置。"
            ));
        }
        return card;
    }

    /**
     * @param {string} kind
     * @param {object | undefined} module
     * @param {object} step
     * @returns {string}
     */
    function stepTitle(kind, module, step) {
        if (kind === "ACTION") {
            return module?.name || step.moduleId || "请选择基础动作";
        }
        if (kind === "CREATE_TASK") {
            return "创建独立任务";
        }
        if (kind === "IF") {
            return `条件：${Model.conditionLabel(step.parameters.condition)}`;
        }
        return `循环：${Model.conditionLabel(step.parameters.condition)}`;
    }

    /**
     * @param {string} kind
     * @param {object | undefined} module
     * @returns {string}
     */
    function stepHelp(kind, module) {
        if (kind === "ACTION") {
            return module?.description || "模块只执行一个基础动作。";
        }
        if (kind === "CREATE_TASK") {
            return "完成后创建新任务，交给统一优先级队列。";
        }
        if (kind === "IF") {
            return "条件成立时执行子步骤，否则可执行另一组步骤。";
        }
        return "重复执行子步骤，直到条件不成立或达到次数上限。";
    }

    /**
     * @param {Array<number>} path
     * @returns {string}
     */
    function pathLabel(path) {
        return path.map((value) => value + 1).join(".");
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
     * @returns {HTMLSelectElement}
     */
    function referenceSelect(context, step, parameter, types) {
        const select = document.createElement("select");
        select.required = Boolean(parameter.required);
        select.append(new Option("请选择已保存资源或当前结果", ""));
        const current = normalizeLegacyReference(context, step, parameter, types);
        let found = false;
        for (const type of types) {
            const options = referenceOptions(context, step, type);
            if (options.length === 0) {
                continue;
            }
            const group = document.createElement("optgroup");
            group.label = referenceTypeLabel(type);
            for (const option of options) {
                group.append(new Option(option.label, option.value));
                found = found || option.value === current;
            }
            select.append(group);
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
        select.addEventListener("change", () => {
            setParameter(step, parameter.key, select.value, context.changed);
        });
        return select;
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
                label: "当前结果 · 容器",
                value: "output:container"
            },
            {
                type: "output-storage",
                label: "当前结果 · 存储位",
                value: "output:storage"
            },
            {
                type: "output-marker",
                label: "当前结果 · 标记容器",
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
            output: "当前结果",
            "output-container": "当前结果 · 容器",
            "output-storage": "当前结果 · 存储位",
            "output-marker": "当前结果 · 标记容器"
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
     * @returns {HTMLElement}
     */
    function branchEditor(context, step, branchKey, title, parentPath) {
        if (!Array.isArray(step[branchKey])) {
            step[branchKey] = [];
        }
        const branch = Stowling.element("section", "step-branch");
        const heading = Stowling.element("div", "step-branch-heading");
        heading.append(Stowling.element("strong", "", title));
        const addButtons = Stowling.element("div", "step-branch-actions");
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
        const list = Stowling.element("div", "step-list nested-step-list");
        renderStepList(context, list, step[branchKey], parentPath, false);
        if (step[branchKey].length === 0) {
            list.append(Stowling.element(
                "p",
                "muted compact-help empty-branch",
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
        button.addEventListener("click", () => {
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
        button.addEventListener("click", () => {
            const [step] = steps.splice(index, 1);
            steps.splice(index + offset, 0, step);
            changed(true);
        });
        return button;
    }

    /**
     * @param {Array<object>} steps
     * @param {number} index
     * @param {boolean} isRoot
     * @param {() => void} changed
     * @returns {HTMLButtonElement}
     */
    function deleteButton(steps, index, isRoot, changed) {
        const button = Stowling.element(
            "button",
            "ghost compact danger-action",
            "删除"
        );
        button.type = "button";
        button.disabled = isRoot && steps.length <= 1;
        button.addEventListener("click", () => {
            steps.splice(index, 1);
            changed(true);
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
