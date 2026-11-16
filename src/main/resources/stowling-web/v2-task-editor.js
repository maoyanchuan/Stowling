(() => {
    "use strict";

    const Stowling = window.Stowling;
    const Model = window.StowlingTaskModel;

    /**
     * @param {HTMLElement} host
     * @param {object} task
     * @param {Array<object>} modules
     * @param {Array<object>} coordinates
     * @param {Array<object>} regions
     * @param {Array<object>} categoryZones
     * @param {() => void} changed
     * @returns {void}
     */
    function render(
        host,
        task,
        modules,
        coordinates,
        regions,
        categoryZones,
        changed
    ) {
        host.replaceChildren();
        task.steps.forEach((step, index) => {
            host.append(createStepCard(
                task,
                step,
                index,
                modules,
                coordinates,
                regions,
                categoryZones,
                changed
            ));
        });
    }

    /**
     * @param {object} task
     * @param {object} step
     * @param {number} index
     * @param {Array<object>} modules
     * @param {Array<object>} coordinates
     * @param {Array<object>} regions
     * @param {Array<object>} categoryZones
     * @param {() => void} changed
     * @returns {HTMLElement}
     */
    function createStepCard(
        task,
        step,
        index,
        modules,
        coordinates,
        regions,
        categoryZones,
        changed
    ) {
        const card = Stowling.element("article", "step-card");
        const heading = Stowling.element("div", "step-card-heading");
        const identity = Stowling.element("div", "step-identity");
        const module = modules.find((candidate) => candidate.id === step.moduleId);
        identity.append(
            Stowling.element("strong", "", module?.name || step.moduleId),
            Stowling.element(
                "span",
                "muted compact-help",
                module?.deprecated
                    ? "旧任务兼容模块，建议改用“收集掉落物”"
                    : "选择模块并填写所需参数"
            )
        );
        const actions = Stowling.element("div", "move-buttons");
        actions.append(
            moveButton("↑", task, index, -1, changed),
            moveButton("↓", task, index, 1, changed),
            deleteButton(task, index, changed)
        );
        heading.append(
            Stowling.element("span", "step-number", String(index + 1).padStart(2, "0")),
            identity,
            actions
        );

        const fields = Stowling.element("div", "editor-fields");
        fields.append(field("模块", moduleSelect(step, modules, changed)));
        const parameters = Stowling.element("div", "step-fields");
        for (const parameter of module?.parameters || []) {
            parameters.append(field(
                `${parameter.label}${parameter.required ? " *" : ""}`,
                parameterControl(
                    step,
                    parameter,
                    coordinates,
                    regions,
                    categoryZones,
                    changed
                )
            ));
        }
        card.append(heading, fields, parameters);
        return card;
    }

    /**
     * @param {object} step
     * @param {Array<object>} modules
     * @param {() => void} changed
     * @returns {HTMLSelectElement}
     */
    function moduleSelect(step, modules, changed) {
        const select = document.createElement("select");
        const selectable = modules.filter((module) => (
            Model.moduleKind(module) === "ACTION"
                || module.id === step.moduleId
        ) && (!module.deprecated || module.id === step.moduleId));
        const groups = [
            ["BASIC", "基础模块"],
            ["PRESET", "任务预设"]
        ];
        for (const [level, label] of groups) {
            const group = document.createElement("optgroup");
            group.label = label;
            for (const module of selectable.filter((candidate) => (
                level === "PRESET"
                    ? Model.moduleKind(candidate) === "PRESET"
                        && candidate.id === step.moduleId
                    : Model.moduleKind(candidate) === "ACTION"
                        && candidate.level === level
            ) && (!candidate.deprecated || candidate.id === step.moduleId))) {
                group.append(new Option(module.name, module.id));
            }
            if (group.children.length > 0) {
                select.append(group);
            }
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
     * @param {object} parameter
     * @param {Array<object>} coordinates
     * @param {Array<object>} regions
     * @param {Array<object>} categoryZones
     * @param {() => void} changed
     * @returns {HTMLElement}
     */
    function parameterControl(
        step,
        parameter,
        coordinates,
        regions,
        categoryZones,
        changed
    ) {
        if (parameter.kind === "coordinate-point") {
            return referenceSelect(
                step,
                parameter,
                "请选择坐标",
                coordinates,
                "coordinate",
                changed
            );
        }
        if (parameter.kind === "region-point") {
            return referenceSelect(
                step,
                parameter,
                "请选择区域",
                regions,
                "region",
                changed
            );
        }
        if (parameter.kind === "category-zone-point") {
            return referenceSelect(
                step,
                parameter,
                "请选择分类区",
                categoryZones,
                "category-zone",
                changed
            );
        }
        if (parameter.kind === "area-reference") {
            return areaReferenceSelect(
                step,
                parameter,
                coordinates,
                regions,
                changed
            );
        }
        if (parameter.kind === "select") {
            return optionSelect(step, parameter, changed);
        }
        if (parameter.kind === "integer") {
            return integerInput(step, parameter, changed);
        }
        const input = document.createElement("input");
        input.type = "text";
        input.value = String(step.parameters[parameter.key] || "");
        input.required = parameter.required;
        input.addEventListener("input", () => {
            setParameter(step, parameter.key, input.value, changed);
        });
        return input;
    }

    /**
     * @param {object} step
     * @param {object} parameter
     * @param {Array<object>} coordinates
     * @param {Array<object>} regions
     * @param {() => void} changed
     * @returns {HTMLSelectElement}
     */
    function areaReferenceSelect(
        step,
        parameter,
        coordinates,
        regions,
        changed
    ) {
        const select = document.createElement("select");
        select.required = parameter.required;
        select.append(new Option("请选择坐标或区域", ""));
        for (const coordinate of coordinates) {
            select.append(new Option(
                `坐标 · ${window.StowlingCoordinateModel.coordinateLabel(coordinate)}`,
                `coordinate:${coordinate.id}`
            ));
        }
        for (const region of regions) {
            select.append(new Option(
                `区域 · ${String(region.name || "")}`,
                `region:${region.id}`
            ));
        }
        let value = String(step.parameters[parameter.key] || "");
        if (!value && step.parameters.coordinateId) {
            value = `coordinate:${step.parameters.coordinateId}`;
            delete step.parameters.coordinateId;
            step.parameters[parameter.key] = value;
        }
        select.value = value;
        select.addEventListener("change", () => {
            setParameter(step, parameter.key, select.value, changed);
        });
        return select;
    }

    /**
     * @param {object} step
     * @param {object} parameter
     * @param {string} prompt
     * @param {Array<object>} resources
     * @param {string} type
     * @param {() => void} changed
     * @returns {HTMLSelectElement}
     */
    function referenceSelect(
        step,
        parameter,
        prompt,
        resources,
        type,
        changed
    ) {
        const select = document.createElement("select");
        select.required = parameter.required;
        select.append(new Option(prompt, ""));
        for (const resource of resources) {
            const label = type === "coordinate"
                ? window.StowlingCoordinateModel.coordinateLabel(resource)
                : String(resource.name || "");
            select.append(new Option(label, resource.id));
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
        select.required = parameter.required;
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
        if (select.value && step.parameters[parameter.key] === undefined) {
            step.parameters[parameter.key] = select.value;
        }
        select.addEventListener("change", () => {
            setParameter(step, parameter.key, select.value, changed);
        });
        return select;
    }

    /**
     * @param {object} step
     * @param {object} parameter
     * @param {() => void} changed
     * @returns {HTMLInputElement}
     */
    function integerInput(step, parameter, changed) {
        const input = document.createElement("input");
        input.type = "number";
        input.step = String(parameter.step ?? 1);
        input.required = parameter.required;
        if ((parameter.min ?? parameter.minimum) !== undefined) {
            input.min = String(parameter.min ?? parameter.minimum);
        }
        if ((parameter.max ?? parameter.maximum) !== undefined) {
            input.max = String(parameter.max ?? parameter.maximum);
        }
        input.value = String(
            step.parameters[parameter.key]
                ?? defaultValue(parameter)
                ?? ""
        );
        input.addEventListener("input", () => {
            setParameter(step, parameter.key, input.value, changed);
        });
        return input;
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
                parameters[parameter.key] = String(value);
            }
        }
        return parameters;
    }

    /**
     * @param {object} parameter
     * @returns {unknown}
     */
    function defaultValue(parameter) {
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
        if (value) {
            step.parameters[key] = value;
        } else {
            delete step.parameters[key];
        }
        changed();
    }

    /**
     * @param {string} text
     * @param {object} task
     * @param {number} index
     * @param {number} offset
     * @param {() => void} changed
     * @returns {HTMLButtonElement}
     */
    function moveButton(text, task, index, offset, changed) {
        const button = Stowling.element("button", "secondary compact", text);
        button.type = "button";
        button.disabled = index + offset < 0 || index + offset >= task.steps.length;
        button.addEventListener("click", () => {
            const [step] = task.steps.splice(index, 1);
            task.steps.splice(index + offset, 0, step);
            changed(true);
        });
        return button;
    }

    /**
     * @param {object} task
     * @param {number} index
     * @param {() => void} changed
     * @returns {HTMLButtonElement}
     */
    function deleteButton(task, index, changed) {
        const button = Stowling.element("button", "ghost compact danger-action", "删除");
        button.type = "button";
        button.disabled = task.steps.length <= 1;
        button.addEventListener("click", () => {
            task.steps.splice(index, 1);
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
