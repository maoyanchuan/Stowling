(() => {
    "use strict";

    const Stowling = window.Stowling;
    const Model = window.StowlingCoordinateModel;
    const state = {
        revision: "",
        coordinates: []
    };

    const elements = {
        add: document.querySelector("#add-coordinate-button"),
        form: document.querySelector("#new-coordinate-form"),
        name: document.querySelector("#new-coordinate-name"),
        x: document.querySelector("#new-coordinate-x"),
        y: document.querySelector("#new-coordinate-y"),
        z: document.querySelector("#new-coordinate-z"),
        list: document.querySelector("#coordinate-list"),
        errors: document.querySelector("#coordinate-errors"),
        status: document.querySelector("#coordinate-status"),
        revision: document.querySelector("#coordinate-revision")
    };

    /**
     * @returns {Promise<void>}
     */
    async function loadCoordinates() {
        try {
            const response = await Stowling.request("/v1/coordinates");
            applyResponse(response);
            elements.status.textContent = "坐标已同步";
        } catch (error) {
            showError(error, "读取坐标失败");
        }
    }

    /**
     * @param {object} response
     * @returns {void}
     */
    function applyResponse(response) {
        const normalized = Model.normalizeCoordinates(response);
        state.revision = normalized.revision;
        state.coordinates = normalized.coordinates;
        elements.revision.textContent = Stowling.revisionLabel(state.revision);
        render();
    }

    /**
     * @returns {void}
     */
    function render() {
        elements.list.replaceChildren();
        if (state.coordinates.length === 0) {
            elements.list.append(Stowling.emptyState("尚未添加坐标"));
            return;
        }
        for (const coordinate of state.coordinates) {
            elements.list.append(createCard(coordinate));
        }
    }

    /**
     * @param {object} coordinate
     * @returns {HTMLElement}
     */
    function createCard(coordinate) {
        const card = Stowling.element(
            "details",
            "editor-card coordinate-point-editor"
        );
        const heading = Stowling.element(
            "summary",
            "editor-card-heading coordinate-point-summary"
        );
        heading.append(Stowling.element("strong", "", coordinate.name));
        card.append(heading);

        const fields = Stowling.element("div", "editor-fields");
        const name = input("text", coordinate.name, 80);
        const x = input("number", coordinate.x);
        const y = input("number", coordinate.y);
        const z = input("number", coordinate.z);
        fields.append(
            field("名称", name),
            field("X", x),
            field("Y", y),
            field("Z", z)
        );

        const actions = Stowling.element("div", "action-row");
        const save = Stowling.element("button", "primary compact", "保存");
        save.type = "button";
        save.addEventListener("click", () => {
            void saveCoordinate(coordinate, { name: name.value, x, y, z });
        });
        const remove = Stowling.element(
            "button",
            "ghost compact danger-action",
            "删除"
        );
        remove.type = "button";
        remove.addEventListener("click", () => {
            void deleteCoordinate(coordinate);
        });
        actions.append(save, remove);
        card.append(fields, actions);
        return card;
    }

    /**
     * @param {object} coordinate
     * @param {object} values
     * @returns {Promise<void>}
     */
    async function saveCoordinate(coordinate, values) {
        try {
            const response = await Stowling.request(
                `/v1/coordinates/${encodeURIComponent(coordinate.id)}`,
                {
                    method: "PUT",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({
                        revision: state.revision,
                        name: values.name.trim(),
                        x: Number(values.x.value),
                        y: Number(values.y.value),
                        z: Number(values.z.value)
                    })
                }
            );
            applyResponse(response);
            clearErrors();
            Stowling.showToast("坐标已保存");
        } catch (error) {
            showError(error, "保存坐标失败");
        }
    }

    /**
     * @param {object} coordinate
     * @returns {Promise<void>}
     */
    async function deleteCoordinate(coordinate) {
        if (!window.confirm(`删除坐标“${coordinate.name}”？`)) {
            return;
        }
        try {
            const response = await Stowling.request(
                `/v1/coordinates/${encodeURIComponent(coordinate.id)}`,
                {
                    method: "DELETE",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ revision: state.revision })
                }
            );
            applyResponse(response);
            clearErrors();
            Stowling.showToast("坐标已删除");
        } catch (error) {
            showError(error, "删除坐标失败");
        }
    }

    /**
     * @returns {Promise<void>}
     */
    async function createCoordinate() {
        try {
            const response = await Stowling.request("/v1/coordinates", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    name: elements.name.value.trim(),
                    x: Number(elements.x.value),
                    y: Number(elements.y.value),
                    z: Number(elements.z.value)
                })
            });
            applyResponse(response);
            elements.form.reset();
            elements.x.value = "0";
            elements.y.value = "64";
            elements.z.value = "0";
            elements.name.focus();
            clearErrors();
            Stowling.showToast("坐标已添加");
        } catch (error) {
            showError(error, "添加坐标失败");
        }
    }

    /**
     * @param {string} type
     * @param {unknown} value
     * @param {number} [maxLength]
     * @returns {HTMLInputElement}
     */
    function input(type, value, maxLength) {
        const control = document.createElement("input");
        control.type = type;
        control.value = String(value ?? "");
        control.step = type === "number" ? "1" : "";
        control.required = true;
        if (maxLength) {
            control.maxLength = maxLength;
        }
        return control;
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

    /**
     * @param {unknown} error
     * @param {string} context
     * @returns {void}
     */
    function showError(error, context) {
        const taskNames = Array.isArray(error?.details?.taskNames)
            ? error.details.taskNames.filter(Boolean)
            : [];
        const message = taskNames.length > 0
            ? `该坐标仍被任务“${taskNames.join("、")}”引用，请先修改或删除这些任务。`
            : error instanceof Error
                ? error.message
                : context;
        elements.errors.replaceChildren(Stowling.element("p", "", message));
        Stowling.handleError(error, context);
    }

    /**
     * @returns {void}
     */
    function clearErrors() {
        elements.errors.replaceChildren();
    }

    elements.add.addEventListener("click", () => elements.name.focus());
    elements.form.addEventListener("submit", (event) => {
        event.preventDefault();
        void createCoordinate();
    });
    Stowling.onConnected(() => loadCoordinates());
    Stowling.mountPage("coordinates");
})();
