(() => {
    "use strict";

    const Stowling = window.Stowling;
    const Model = window.StowlingCategoryZoneModel;
    const state = {
        revision: "",
        rules: [],
        categoryZones: []
    };

    const elements = {
        add: document.querySelector("#add-category-zone-button"),
        cancel: document.querySelector("#cancel-category-zone-button"),
        form: document.querySelector("#new-category-zone-form"),
        name: document.querySelector("#new-category-zone-name"),
        bindings: document.querySelector("#new-category-bindings"),
        addBinding: document.querySelector("#add-new-binding-button"),
        list: document.querySelector("#category-zone-list"),
        errors: document.querySelector("#category-zone-errors"),
        status: document.querySelector("#category-zone-status"),
        revision: document.querySelector("#category-zone-revision")
    };
    const newBindingReaders = [];

    /**
     * @returns {Promise<void>}
     */
    async function loadAll() {
        try {
            const [zones, rules] = await Promise.all([
                Stowling.request("/v1/category-zones"),
                Stowling.request("/v1/category-rules")
            ]);
            state.rules = Array.isArray(rules?.rules) ? rules.rules : [];
            applyResponse(zones);
            elements.status.textContent = "分类区已同步";
        } catch (error) {
            showError(error, "读取分类区失败");
        }
    }

    /**
     * @param {object} response
     * @returns {void}
     */
    function applyResponse(response) {
        const normalized = Model.normalizeCategoryZones(response);
        state.revision = normalized.revision;
        state.categoryZones = normalized.categoryZones;
        elements.revision.textContent = Stowling.revisionLabel(state.revision);
        renderCategoryZones();
    }

    /**
     * @returns {void}
     */
    function renderCategoryZones() {
        elements.list.replaceChildren();
        if (state.categoryZones.length === 0) {
            elements.list.append(Stowling.emptyState("尚未添加分类区"));
            return;
        }
        for (const zone of state.categoryZones) {
            elements.list.append(createZoneCard(zone));
        }
    }

    /**
     * @param {object} zone
     * @returns {HTMLElement}
     */
    function createZoneCard(zone) {
        const card = Stowling.element(
            "details",
            "editor-card coordinate-point-editor"
        );
        const summary = Stowling.element(
            "summary",
            "editor-card-heading coordinate-point-summary"
        );
        summary.append(Stowling.element("strong", "", zone.name));
        card.append(summary);

        const body = Stowling.element("div", "category-zone-editor");
        const name = document.createElement("input");
        name.value = zone.name;
        name.maxLength = 80;
        name.required = true;

        const bindingsHost = Stowling.element(
            "div",
            "category-binding-list category-zone-binding-list"
        );
        const readers = zone.bindings.map((binding) => (
            appendBinding(bindingsHost, binding)
        ));
        const addBinding = Stowling.element(
            "button",
            "secondary compact",
            "添加绑定"
        );
        addBinding.type = "button";
        addBinding.addEventListener("click", () => {
            readers.push(appendBinding(
                bindingsHost,
                Model.normalizeBinding({})
            ));
        });
        const addBindingRow = Stowling.element(
            "div",
            "category-binding-actions"
        );
        addBindingRow.append(addBinding);
        body.append(
            categoryNameField(name),
            bindingHeading(),
            bindingsHost,
            addBindingRow
        );

        const actions = Stowling.element("div", "action-row");
        const save = Stowling.element("button", "primary compact", "保存");
        save.type = "button";
        save.addEventListener("click", () => {
            void saveZone(zone, {
                name: name.value,
                bindings: readers.map((read) => read())
            });
        });
        const remove = Stowling.element(
            "button",
            "ghost compact danger-action",
            "删除"
        );
        remove.type = "button";
        remove.addEventListener("click", () => {
            void deleteZone(zone);
        });
        actions.append(save, remove);
        card.append(body, actions);
        return card;
    }

    /**
     * @returns {HTMLElement}
     */
    function bindingHeading() {
        const wrapper = Stowling.element("div", "resource-section-heading");
        const text = Stowling.element("div", "");
        text.append(
            Stowling.element("h3", "", "识别区 → 储存区"),
            Stowling.element(
                "p",
                "muted compact-help",
                "同一列的分类方块决定该列箱子的物品类型。"
            )
        );
        wrapper.append(text);
        return wrapper;
    }

    /**
     * @param {HTMLInputElement} input
     * @returns {HTMLElement}
     */
    function categoryNameField(input) {
        const wrapper = field("分类区名称", input);
        wrapper.classList.add("category-zone-name-field");
        return wrapper;
    }

    /**
     * @param {HTMLElement} host
     * @param {object} binding
     * @returns {() => object}
     */
    function appendBinding(host, binding) {
        const card = Stowling.element("article", "category-binding-card");
        const marker = cornerFields("分类物识别区", "marker", binding);
        const storage = cornerFields("储存区", "storage", binding);
        const rule = document.createElement("select");
        for (const definition of state.rules) {
            rule.append(new Option(definition.name, definition.id));
        }
        rule.value = binding.ruleId;
        const ruleField = field("识别规则", rule);
        card.append(marker.root, storage.root, ruleField);
        host.append(card);
        return () => ({
            ...marker.read(),
            ...storage.read(),
            ruleId: rule.value
        });
    }

    /**
     * @param {string} label
     * @param {string} prefix
     * @param {object} binding
     * @returns {object}
     */
    function cornerFields(label, prefix, binding) {
        const root = Stowling.element("div", "category-binding-section");
        const first = axisFields(
            "角点 1",
            `${prefix}First`,
            binding
        );
        const second = axisFields(
            "角点 2",
            `${prefix}Second`,
            binding
        );
        root.append(
            Stowling.element("h4", "", label),
            first.root,
            second.root
        );
        return {
            root,
            read: () => ({
                ...first.read(),
                ...second.read()
            })
        };
    }

    /**
     * @param {string} label
     * @param {string} prefix
     * @param {object} binding
     * @returns {object}
     */
    function axisFields(label, prefix, binding) {
        const root = Stowling.element("fieldset", "region-corner-group");
        root.append(Stowling.element("legend", "", label));
        const row = Stowling.element("div", "coordinate-axis-row");
        const x = numberInput(binding[`${prefix}X`]);
        const y = numberInput(binding[`${prefix}Y`]);
        const z = numberInput(binding[`${prefix}Z`]);
        row.append(field("X", x), field("Y", y), field("Z", z));
        root.append(row);
        return {
            root,
            read: () => ({
                [`${prefix}X`]: Number(x.value),
                [`${prefix}Y`]: Number(y.value),
                [`${prefix}Z`]: Number(z.value)
            })
        };
    }

    /**
     * @param {unknown} value
     * @returns {HTMLInputElement}
     */
    function numberInput(value) {
        const input = document.createElement("input");
        input.type = "number";
        input.step = "1";
        input.required = true;
        input.value = String(value ?? 0);
        return input;
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
     * @param {object} zone
     * @param {object} values
     * @returns {Promise<void>}
     */
    async function saveZone(zone, values) {
        try {
            const response = await Stowling.request(
                `/v1/category-zones/${encodeURIComponent(zone.id)}`,
                {
                    method: "PUT",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({
                        revision: state.revision,
                        ...Model.payload(values)
                    })
                }
            );
            applyResponse(response);
            clearErrors();
            Stowling.showToast("分类区已保存");
        } catch (error) {
            showError(error, "保存分类区失败");
        }
    }

    /**
     * @param {object} zone
     * @returns {Promise<void>}
     */
    async function deleteZone(zone) {
        if (!window.confirm(`删除分类区“${zone.name}”？`)) {
            return;
        }
        try {
            const response = await Stowling.request(
                `/v1/category-zones/${encodeURIComponent(zone.id)}`,
                {
                    method: "DELETE",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ revision: state.revision })
                }
            );
            applyResponse(response);
            clearErrors();
            Stowling.showToast("分类区已删除");
        } catch (error) {
            showError(error, "删除分类区失败");
        }
    }

    /**
     * @returns {Promise<void>}
     */
    async function createZone() {
        try {
            const response = await Stowling.request("/v1/category-zones", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(Model.payload({
                    name: elements.name.value,
                    bindings: newBindingReaders.map((read) => read())
                }))
            });
            applyResponse(response);
            elements.form.reset();
            elements.form.hidden = true;
            clearErrors();
            Stowling.showToast("分类区已添加");
        } catch (error) {
            showError(error, "添加分类区失败");
        }
    }

    /**
     * @returns {void}
     */
    function showCreateForm() {
        elements.form.hidden = false;
        elements.bindings.replaceChildren();
        newBindingReaders.length = 0;
        newBindingReaders.push(appendBinding(
            elements.bindings,
            Model.normalizeBinding({})
        ));
        clearErrors();
        elements.name.focus();
    }

    /**
     * @param {unknown} error
     * @param {string} context
     * @returns {void}
     */
    function showError(error, context) {
        elements.errors.replaceChildren(Stowling.element(
            "p",
            "",
            error instanceof Error ? error.message : context
        ));
        Stowling.handleError(error, context);
    }

    /**
     * @returns {void}
     */
    function clearErrors() {
        elements.errors.replaceChildren();
    }

    elements.add.addEventListener("click", showCreateForm);
    elements.cancel.addEventListener("click", () => {
        elements.form.hidden = true;
        clearErrors();
    });
    elements.addBinding.addEventListener("click", () => {
        newBindingReaders.push(appendBinding(
            elements.bindings,
            Model.normalizeBinding({})
        ));
    });
    elements.form.addEventListener("submit", (event) => {
        event.preventDefault();
        void createZone();
    });
    Stowling.onConnected(() => loadAll());
    Stowling.mountPage("category-zones");
})();
