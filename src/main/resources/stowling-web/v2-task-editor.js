(() => {
    "use strict";

    const Stowling = window.Stowling;

    /**
     * @param {HTMLElement} host
     * @param {object} task
     * @param {Array<object>} modules
     * @param {Array<object>} coordinates
     * @param {() => void} changed
     * @returns {void}
     */
    function render(host, task, modules, coordinates, changed) {
        host.replaceChildren();
        task.steps.forEach((step, index) => {
            host.append(createStepCard(
                task,
                step,
                index,
                modules,
                coordinates,
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
     * @param {() => void} changed
     * @returns {HTMLElement}
     */
    function createStepCard(task, step, index, modules, coordinates, changed) {
        const card = Stowling.element("article", "step-card");
        const heading = Stowling.element("div", "step-card-heading");
        const identity = Stowling.element("div", "step-identity");
        const module = modules.find((candidate) => candidate.id === step.moduleId);
        identity.append(
            Stowling.element("strong", "", module?.name || step.moduleId),
            Stowling.element("span", "muted compact-help", `步骤 ${index + 1}`)
        );
        const actions = Stowling.element("div", "move-buttons");
        actions.append(
            moveButton("↑", task, index, -1, changed),
            moveButton("↓", task, index, 1, changed),
            deleteButton(task, index, changed)
        );
        heading.append(identity, actions);

        const fields = Stowling.element("div", "editor-fields");
        fields.append(field("模块", moduleSelect(step, modules, changed)));
        const parameters = Stowling.element("div", "step-fields");
        for (const parameter of module?.parameters || []) {
            parameters.append(field(
                `${parameter.label}${parameter.required ? " *" : ""}`,
                parameterControl(step, parameter, coordinates, changed)
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
        for (const module of modules) {
            select.append(new Option(module.name, module.id));
        }
        select.value = step.moduleId;
        select.addEventListener("change", () => {
            step.moduleId = select.value;
            step.parameters = {};
            changed(true);
        });
        return select;
    }

    /**
     * @param {object} step
     * @param {object} parameter
     * @param {Array<object>} coordinates
     * @param {() => void} changed
     * @returns {HTMLElement}
     */
    function parameterControl(step, parameter, coordinates, changed) {
        if (parameter.kind === "coordinate-point") {
            const select = document.createElement("select");
            select.required = parameter.required;
            select.append(new Option("请选择坐标", ""));
            for (const coordinate of coordinates) {
                select.append(new Option(
                    window.StowlingCoordinateModel.coordinateLabel(coordinate),
                    coordinate.id
                ));
            }
            select.value = String(step.parameters[parameter.key] || "");
            select.addEventListener("change", () => {
                setParameter(step, parameter.key, select.value, changed);
            });
            return select;
        }
        const input = document.createElement("input");
        input.value = String(step.parameters[parameter.key] || "");
        input.required = parameter.required;
        input.addEventListener("input", () => {
            setParameter(step, parameter.key, input.value, changed);
        });
        return input;
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
