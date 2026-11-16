(() => {
    "use strict";

    const Stowling = window.Stowling;
    const Model = window.StowlingCategoryZoneModel;
    const TAB_EVENT = "stowling:resource-tab-change";
    const state = {
        revision: "",
        rules: [],
        categoryZones: [],
        query: ""
    };
    let loadRequest = 0;

    const elements = {
        add: document.querySelector("#add-category-zone-button"),
        cancel: document.querySelector("#cancel-category-zone-button"),
        form: document.querySelector("#new-category-zone-form"),
        name: document.querySelector("#new-category-zone-name"),
        bindings: document.querySelector("#new-category-bindings"),
        addBinding: document.querySelector("#add-new-binding-button"),
        search: document.querySelector("#category-zone-search"),
        count: document.querySelector("#category-zone-list-count"),
        list: document.querySelector("#category-zone-list"),
        errors: document.querySelector("#category-zone-errors"),
        status: document.querySelector("#category-zone-status"),
        revision: document.querySelector("#category-zone-revision")
    };
    const newBindingReaders = [];

    /**
     * @param {HTMLElement} host
     * @returns {boolean}
     */
    function hasDirtyZone(host) {
        return Boolean(host.querySelector('[data-dirty="true"]'));
    }

    /**
     * @param {boolean} [preserveDirty]
     * @returns {Promise<void>}
     */
    async function loadAll(preserveDirty = false) {
        const requestId = ++loadRequest;
        elements.status.textContent = "正在同步分类区…";
        try {
            const [zones, rules] = await Promise.all([
                Stowling.request("/v1/category-zones"),
                Stowling.request("/v1/category-rules")
            ]);
            if (requestId !== loadRequest) {
                return;
            }
            state.rules = Array.isArray(rules?.rules) ? rules.rules : [];
            applyResponse(zones, preserveDirty);
        } catch (error) {
            if (requestId !== loadRequest) {
                return;
            }
            elements.status.textContent = "读取分类区失败";
            showError(error, "读取分类区失败");
        }
    }

    /**
     * @param {object} response
     * @param {boolean} [preserveDirty]
     * @returns {void}
     */
    function applyResponse(response, preserveDirty = false) {
        const normalized = Model.normalizeCategoryZones(response);
        state.revision = normalized.revision || state.revision;
        state.categoryZones = normalized.categoryZones;
        elements.revision.textContent = Stowling.revisionLabel(state.revision);
        if (preserveDirty && hasDirtyZone(elements.list)) {
            elements.status.textContent = "有未保存修改，已同步版本";
            filterCategoryZones();
            return;
        }
        renderCategoryZones();
        elements.status.textContent = state.categoryZones.length === 0
            ? "尚未添加分类区"
            : "分类区已同步";
    }

    /**
     * @returns {void}
     */
    function renderCategoryZones() {
        elements.list.replaceChildren();
        elements.count.textContent = String(state.categoryZones.length);
        if (state.categoryZones.length === 0) {
            elements.list.append(Stowling.emptyState("尚未添加分类区"));
            return;
        }
        for (const zone of state.categoryZones) {
            elements.list.append(createZoneCard(zone));
        }
        const empty = Stowling.emptyState("没有匹配的分类区");
        empty.classList.add("search-empty");
        elements.list.append(empty);
        filterCategoryZones();
    }

    /**
     * @returns {void}
     */
    function filterCategoryZones() {
        let visible = 0;
        for (const card of elements.list.querySelectorAll(".coordinate-point-editor")) {
            const searchText = String(card.dataset.searchText || "");
            card.hidden = !searchText.toLocaleLowerCase().includes(state.query);
            visible += card.hidden ? 0 : 1;
        }
        const empty = elements.list.querySelector(".search-empty");
        if (empty) {
            empty.hidden = visible > 0;
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
        card.dataset.dirty = "false";
        card.dataset.resourceId = zone.id;
        card.dataset.searchText = [
            zone.name,
            ...zone.bindings.map((binding) => bindingDescription(binding))
        ].join(" ");
        const summary = Stowling.element(
            "summary",
            "editor-card-heading coordinate-point-summary"
        );
        const identity = Stowling.element("div", "resource-summary-text");
        identity.append(
            Stowling.element("strong", "", zone.name),
            Stowling.element(
                "span", "resource-summary-meta", `${zone.bindings.length} 组范围绑定`
            )
        );
        summary.append(identity);
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
            card.dataset.dirty = "true";
            readers.push(appendBinding(
                bindingsHost,
                Model.normalizeBinding({}),
                true
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
            }, card);
        });
        const remove = Stowling.element(
            "button",
            "ghost compact danger-action",
            "删除"
        );
        remove.type = "button";
        remove.addEventListener("click", () => {
            void deleteZone(zone, card);
        });
        actions.append(save, remove);
        body.append(actions);
        card.append(body);
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
                "自动读取展示框、展示架和摆放方块，每列只识别一个物品。"
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
     * @param {boolean} [expanded]
     * @returns {() => object}
     */
    function appendBinding(host, binding, expanded = false) {
        const card = Stowling.element("details", "category-binding-card");
        card.open = expanded;
        const summary = Stowling.element("summary", "category-binding-summary");
        const identity = Stowling.element("div", "resource-summary-text");
        const number = String(host.children.length + 1).padStart(2, "0");
        identity.append(
            Stowling.element("strong", "", `范围 ${number} · 识别区 → 储存区`),
            Stowling.element("span", "resource-summary-meta", bindingDescription(binding))
        );
        summary.append(identity);
        const body = Stowling.element("div", "category-binding-body");
        const marker = cornerFields("分类物识别区", "marker", binding);
        const storage = cornerFields("储存区", "storage", binding);
        const rule = document.createElement("select");
        for (const definition of state.rules) {
            rule.append(new Option(definition.name, definition.id));
        }
        rule.value = binding.ruleId;
        const ignored = document.createElement("textarea");
        ignored.rows = 2;
        ignored.placeholder = "minecraft:stone_bricks";
        ignored.value = binding.ignoredMarkerBlockIds.join("\n");
        const recognition = Stowling.element(
            "div",
            "category-recognition-fields"
        );
        recognition.append(
            field("自动识别规则", rule),
            field("忽略支撑方块（每行一个）", ignored)
        );
        const overrideHeading = Stowling.element(
            "div",
            "resource-section-heading"
        );
        overrideHeading.append(Stowling.element(
            "div",
            "",
            "容器例外规则"
        ));
        const overrideHelp = Stowling.element(
            "p",
            "muted compact-help",
            "追加物品会保留自动分类；替换物品会忽略该箱子的自动分类。"
        );
        overrideHeading.append(overrideHelp);
        const overrides = Stowling.element(
            "div",
            "category-override-list"
        );
        const overrideReaders = [];

        /**
         * @param {object} value
         * @returns {void}
         */
        function addOverrideReader(value) {
            let reader;
            reader = appendContainerOverride(overrides, value, () => {
                const index = overrideReaders.indexOf(reader);
                if (index >= 0) {
                    overrideReaders.splice(index, 1);
                }
            });
            overrideReaders.push(reader);
        }
        const addOverride = Stowling.element(
            "button",
            "secondary compact",
            "添加例外箱子"
        );
        addOverride.type = "button";
        addOverride.addEventListener("click", () => {
            markContainingZoneDirty(addOverride);
            addOverrideReader(Model.normalizeContainerOverride({
                storageX: binding.storageFirstX,
                storageY: binding.storageFirstY,
                storageZ: binding.storageFirstZ
            }));
        });
        for (const override of binding.containerOverrides) {
            addOverrideReader(override);
        }
        const overrideActions = Stowling.element(
            "div",
            "category-binding-actions"
        );
        overrideActions.append(addOverride);
        const ranges = Stowling.element("div", "category-range-pair");
        ranges.append(marker.root, storage.root);
        const settings = Stowling.element("details", "category-binding-settings");
        const settingsBody = Stowling.element("div", "category-settings-body");
        settingsBody.append(recognition, overrideHeading, overrides, overrideActions);
        settings.append(
            Stowling.element("summary", "", "识别规则与容器例外"),
            settingsBody
        );
        body.append(ranges, settings);
        card.append(summary, body);
        host.append(card);
        return () => ({
            ...marker.read(),
            ...storage.read(),
            ruleId: rule.value,
            ignoredMarkerBlockIds: parseBlockIds(ignored.value),
            containerOverrides: overrideReaders.map((reader) => reader.read())
        });
    }

    /**
     * @param {Element} control
     * @returns {void}
     */
    function markContainingZoneDirty(control) {
        const card = control.closest(".coordinate-point-editor");
        if (card instanceof HTMLElement) {
            card.dataset.dirty = "true";
        }
    }

    /**
     * @param {object} binding
     * @returns {string}
     */
    function bindingDescription(binding) {
        const marker = `${binding.markerFirstX}, ${binding.markerFirstY}, `
            + `${binding.markerFirstZ}`;
        const storage = `${binding.storageFirstX}, ${binding.storageFirstY}, `
            + `${binding.storageFirstZ}`;
        const exceptions = binding.containerOverrides.length;
        return `识别起点 ${marker} · 储存起点 ${storage} · ${exceptions} 个例外`;
    }

    /**
     * @param {HTMLElement} host
     * @param {object} override
     * @param {() => void} onRemove
     * @returns {object}
     */
    function appendContainerOverride(host, override, onRemove) {
        const card = Stowling.element("article", "category-override-card");
        const mode = document.createElement("select");
        mode.append(
            new Option("追加允许物品", "ADD"),
            new Option("替换自动分类", "REPLACE")
        );
        mode.value = override.mode;
        const itemIds = document.createElement("textarea");
        itemIds.rows = 2;
        itemIds.required = true;
        itemIds.placeholder = "minecraft:oak_planks";
        itemIds.value = override.itemIds.join("\n");
        const x = numberInput(override.storageX);
        const y = numberInput(override.storageY);
        const z = numberInput(override.storageZ);
        Stowling.bindCoordinateInputs(x, y, z);
        const position = Stowling.element("div", "coordinate-axis-row");
        position.append(field("X", x), field("Y", y), field("Z", z));
        const remove = Stowling.element(
            "button",
            "ghost compact danger-action",
            "移除例外"
        );
        remove.type = "button";
        remove.addEventListener("click", () => {
            markContainingZoneDirty(remove);
            card.remove();
            onRemove();
        });
        const footer = Stowling.element("div", "category-binding-actions");
        footer.append(remove);
        card.append(
            field("例外箱子坐标", position),
            field("处理方式", mode),
            field("物品 ID（每行一个）", itemIds),
            footer
        );
        host.append(card);
        return {
            root: card,
            read: () => ({
                storageX: Number(x.value),
                storageY: Number(y.value),
                storageZ: Number(z.value),
                mode: mode.value,
                itemIds: parseBlockIds(itemIds.value)
            })
        };
    }

    /**
     * @param {string} value
     * @returns {string[]}
     */
    function parseBlockIds(value) {
        return [...new Set(
            value
                .split(/[\s,]+/)
                .map((blockId) => blockId.trim())
                .filter(Boolean)
        )];
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
        Stowling.bindCoordinateInputs(x, y, z);
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
        if (control.matches("input, select, textarea")) {
            control.setAttribute("aria-label", label);
        }
        wrapper.append(Stowling.element("label", "", label), control);
        return wrapper;
    }

    /**
     * @param {object} zone
     * @param {object} values
     * @param {HTMLElement} card
     * @returns {Promise<void>}
     */
    async function saveZone(zone, values, card) {
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
            ++loadRequest;
            card.dataset.dirty = "false";
            applyResponse(response, true);
            clearErrors();
            Stowling.showToast("分类区已保存");
        } catch (error) {
            showError(error, "保存分类区失败");
        }
    }

    /**
     * @param {object} zone
     * @param {HTMLElement} card
     * @returns {Promise<void>}
     */
    async function deleteZone(zone, card) {
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
            ++loadRequest;
            card.remove();
            applyResponse(response, true);
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
            ++loadRequest;
            applyResponse(response, true);
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
            Model.normalizeBinding({}),
            true
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

    /**
     * @param {Event} event
     * @returns {void}
     */
    function markDirtyZoneEditor(event) {
        if (!(event.target instanceof Element)) {
            return;
        }
        const card = event.target.closest(".coordinate-point-editor");
        const host = event.currentTarget;
        if (!card || !(host instanceof HTMLElement) || !host.contains(card)) {
            return;
        }
        card.dataset.dirty = "true";
    }

    /**
     * @param {Event} event
     * @returns {void}
     */
    function refreshCategoryZones(event) {
        const detail = event instanceof CustomEvent ? event.detail : null;
        if (detail?.panelId === "category-zones" && Stowling.isConnected()) {
            void loadAll(true);
        }
    }

    elements.search.addEventListener("input", () => {
        state.query = elements.search.value.trim().toLocaleLowerCase();
        filterCategoryZones();
    });
    elements.list.addEventListener("input", markDirtyZoneEditor);
    elements.list.addEventListener("change", markDirtyZoneEditor);
    document.addEventListener(TAB_EVENT, refreshCategoryZones);
    elements.add.addEventListener("click", showCreateForm);
    elements.cancel.addEventListener("click", () => {
        elements.form.hidden = true;
        clearErrors();
    });
    elements.addBinding.addEventListener("click", () => {
        newBindingReaders.push(appendBinding(
            elements.bindings,
            Model.normalizeBinding({}),
            true
        ));
    });
    elements.form.addEventListener("submit", (event) => {
        event.preventDefault();
        void createZone();
    });
    const embedded = document.body.dataset.page === "coordinates";
    Stowling.onConnected(() => loadAll());
    if (!embedded) {
        Stowling.mountPage("category-zones");
    }
})();
