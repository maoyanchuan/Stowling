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
    let newZoneEditor = null;

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
            zone.expansion ? "自动铺满" : "手动绑定",
            zone.expansion ? expansionDescription(zone.expansion) : "",
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
                "span",
                "resource-summary-meta",
                zone.expansion
                    ? `自动铺满 · ${zone.bindings.length} 片`
                    : `${zone.bindings.length} 组范围绑定`
            )
        );
        summary.append(identity);
        card.append(summary);

        const body = Stowling.element("div", "category-zone-editor");
        const name = document.createElement("input");
        name.value = zone.name;
        name.maxLength = 80;
        name.required = true;

        const zoneEditor = createBindingEditor(
            zone.bindings,
            zone.expansion
        );
        body.append(
            categoryNameField(name),
            zoneEditor.root
        );

        const actions = Stowling.element("div", "action-row");
        const save = Stowling.element("button", "primary compact", "保存");
        save.type = "button";
        save.addEventListener("click", () => {
            void saveZone(zone, {
                name: name.value,
                ...zoneEditor.read()
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
     * @param {Array<object>} initialBindings
     * @param {object | null} initialExpansion
     * @param {boolean} [expandBindings]
     * @returns {{root: HTMLElement, read: () => object}}
     */
    function createBindingEditor(
            initialBindings,
            initialExpansion,
            expandBindings = false
    ) {
        const root = Stowling.element("div", "category-binding-editor");
        let mode = initialExpansion ? "AUTO" : "MANUAL";
        let expansionValue = initialExpansion;
        let readers = [];
        let expansionReader = null;
        const expandedFromSaved = Boolean(initialExpansion)
            && initialBindings.length > 1;

        const modeControl = document.createElement("select");
        modeControl.append(
            new Option("手动绑定", "MANUAL"),
            new Option("自动铺满", "AUTO")
        );
        modeControl.value = mode;
        const modeHelp = Stowling.element("p", "muted compact-help", "");
        const modeField = field("范围模式", modeControl);
        modeField.classList.add("category-zone-mode-field");

        const expansionHost = Stowling.element(
            "div",
            "category-expansion-host"
        );
        const bindingsHost = Stowling.element(
            "div",
            "category-binding-list category-zone-binding-list"
        );
        const addBinding = Stowling.element(
            "button",
            "secondary compact",
            "添加绑定"
        );
        addBinding.type = "button";
        addBinding.addEventListener("click", () => {
            const card = addBinding.closest(".coordinate-point-editor");
            if (card instanceof HTMLElement) {
                card.dataset.dirty = "true";
            }
            readers.push(appendBinding(
                bindingsHost,
                Model.normalizeBinding({}),
                true
            ));
            refreshExpansionPreview();
        });
        const addBindingRow = Stowling.element(
            "div",
            "category-binding-actions"
        );
        addBindingRow.append(addBinding);

        /**
         * @returns {object}
         */
        function currentTemplate() {
            return readers[0]?.read() || Model.normalizeBinding({});
        }

        /**
         * @returns {void}
         */
        function refreshExpansionPreview() {
            expansionReader?.refresh();
        }

        /**
         * @param {Array<object>} bindings
         * @returns {void}
         */
        function renderBindings(bindings) {
            bindingsHost.replaceChildren();
            readers = bindings.map((binding) => appendBinding(
                bindingsHost,
                binding,
                mode === "AUTO" || expandBindings,
                mode === "AUTO" ? "单片母版" : ""
            ));
        }

        /**
         * @returns {void}
         */
        function renderExpansion() {
            expansionHost.replaceChildren();
            expansionReader = null;
            if (mode !== "AUTO") {
                expansionHost.hidden = true;
                addBinding.hidden = false;
                modeHelp.textContent = "每一片分别填写识别区和储存区。";
                return;
            }
            const template = currentTemplate();
            if (!expansionValue) {
                expansionValue = defaultExpansion(template);
            }
            expansionReader = appendExpansionEditor(
                expansionHost,
                expansionValue,
                currentTemplate
            );
            expansionHost.hidden = false;
            addBinding.hidden = true;
            modeHelp.textContent = "只填写一片母版，系统按方向和步长铺满总选区。";
        }

        /**
         * @param {string} nextMode
         * @returns {void}
         */
        function changeMode(nextMode) {
            if (nextMode === mode) {
                return;
            }
            if (nextMode === "AUTO" && readers.length > 1
                    && !window.confirm(
                        "自动铺满只保留第一组范围作为母版，"
                        + "其他手动绑定会从当前编辑中移除。继续？"
                    )) {
                modeControl.value = mode;
                return;
            }
            if (nextMode === "MANUAL" && mode === "AUTO"
                    && expandedFromSaved
                    && !window.confirm(
                        "转回手动模式只保留单片母版，"
                        + "自动生成的其他单片会被移除。继续？"
                    )) {
                modeControl.value = mode;
                return;
            }
            if (mode === "AUTO" && expansionReader) {
                expansionValue = expansionReader.read();
            }
            const template = currentTemplate();
            mode = nextMode;
            renderBindings([template]);
            renderExpansion();
        }

        modeControl.addEventListener("change", () => {
            changeMode(modeControl.value);
        });
        bindingsHost.addEventListener("input", refreshExpansionPreview);
        expansionHost.addEventListener("input", refreshExpansionPreview);

        renderBindings(mode === "AUTO"
            ? [initialBindings[0] || Model.normalizeBinding({})]
            : initialBindings);
        root.append(
            modeField,
            modeHelp,
            expansionHost,
            bindingHeading(),
            bindingsHost,
            addBindingRow
        );
        renderExpansion();
        return {
            root,
            read: () => {
                if (mode === "AUTO" && expansionReader) {
                    expansionValue = expansionReader.read();
                }
                return {
                    bindings: readers.map((reader) => reader.read()),
                    expansion: mode === "AUTO" ? expansionValue : null
                };
            }
        };
    }

    /**
     * @param {HTMLElement} host
     * @param {object} expansion
     * @param {() => object} templateReader
     * @returns {{root: HTMLElement, read: () => object, refresh: () => void}}
     */
    function appendExpansionEditor(host, expansion, templateReader) {
        const root = Stowling.element("article", "category-expansion-editor");
        const heading = Stowling.element("div", "resource-section-heading");
        heading.append(
            Stowling.element("h4", "", "自动铺满设置"),
            Stowling.element(
                "p",
                "muted compact-help",
                "母版为第 1 片；母版必须完整位于总选区内。"
            )
        );
        const area = cornerFields("总选区", "area", expansion);
        const direction = document.createElement("select");
        direction.append(
            new Option("X 正方向", "X_POSITIVE"),
            new Option("X 负方向", "X_NEGATIVE"),
            new Option("Z 正方向", "Z_POSITIVE"),
            new Option("Z 负方向", "Z_NEGATIVE")
        );
        direction.value = expansion.direction;
        const step = numberInput(expansion.step);
        step.min = "1";
        let stepTouched = false;
        const preview = Stowling.element("p", "category-expansion-preview", "");
        const controls = Stowling.element("div", "auto-area-fields");
        controls.append(
            field("拓展方向", direction),
            field("单片步长", step)
        );
        root.append(heading, area.root, controls, preview);
        host.append(root);

        /**
         * @returns {void}
         */
        function refresh() {
            const template = templateReader();
            if (!stepTouched) {
                step.value = String(axisSpan(template, direction.value));
            }
            const stats = expansionStats(template, {
                ...area.read(),
                direction: direction.value,
                step: Number(step.value)
            });
            preview.textContent = stats.message;
            preview.classList.toggle("error", !stats.valid);
        }

        direction.addEventListener("change", () => {
            if (!stepTouched) {
                step.value = String(
                    axisSpan(templateReader(), direction.value)
                );
            }
            refresh();
        });
        step.addEventListener("input", () => {
            stepTouched = true;
        });
        host.addEventListener("input", refresh);
        refresh();
        return {
            root,
            read: () => ({
                ...area.read(),
                direction: direction.value,
                step: Number(step.value)
            }),
            refresh
        };
    }

    /**
     * @param {object} template
     * @returns {object}
     */
    function defaultExpansion(template) {
        const values = [
            [template.markerFirstX, template.markerFirstY, template.markerFirstZ],
            [template.markerSecondX, template.markerSecondY, template.markerSecondZ],
            [template.storageFirstX, template.storageFirstY, template.storageFirstZ],
            [template.storageSecondX, template.storageSecondY, template.storageSecondZ]
        ];
        const xs = values.map((value) => Number(value[0]));
        const ys = values.map((value) => Number(value[1]));
        const zs = values.map((value) => Number(value[2]));
        return {
            areaFirstX: Math.min(...xs),
            areaFirstY: Math.min(...ys),
            areaFirstZ: Math.min(...zs),
            areaSecondX: Math.max(...xs),
            areaSecondY: Math.max(...ys),
            areaSecondZ: Math.max(...zs),
            direction: "X_POSITIVE",
            step: axisSpan(template, "X_POSITIVE")
        };
    }

    /**
     * @param {object} template
     * @param {string} direction
     * @returns {number}
     */
    function axisSpan(template, direction) {
        const xAxis = direction.startsWith("X_");
        const first = xAxis ? template.markerFirstX : template.markerFirstZ;
        const second = xAxis ? template.markerSecondX : template.markerSecondZ;
        return Math.max(1, Math.abs(Number(second) - Number(first)) + 1);
    }

    /**
     * @param {object} template
     * @param {object} expansion
     * @returns {{valid: boolean, message: string}}
     */
    function expansionStats(template, expansion) {
        const markerSpan = axisSpan(template, expansion.direction);
        const storageSpan = axisSpan(
            {
                markerFirstX: template.storageFirstX,
                markerSecondX: template.storageSecondX,
                markerFirstZ: template.storageFirstZ,
                markerSecondZ: template.storageSecondZ
            },
            expansion.direction
        );
        if (markerSpan !== storageSpan) {
            return {
                valid: false,
                message: "识别区和储存区在展开方向上的长度必须一致"
            };
        }
        if (!Number.isInteger(expansion.step)
                || expansion.step < markerSpan) {
            return {
                valid: false,
                message: "单片步长不能小于单片长度"
            };
        }
        if (!areaContainsBounds(expansion, template.markerFirstX,
                template.markerFirstY, template.markerFirstZ,
                template.markerSecondX, template.markerSecondY,
                template.markerSecondZ)
                || !areaContainsBounds(expansion, template.storageFirstX,
                    template.storageFirstY, template.storageFirstZ,
                    template.storageSecondX, template.storageSecondY,
                    template.storageSecondZ)) {
            return {
                valid: false,
                message: "单片母版必须完整位于总选区内"
            };
        }
        let count = 0;
        for (let index = 0; index <= 256; index++) {
            const delta = index * expansion.step
                * (expansion.direction.endsWith("NEGATIVE") ? -1 : 1);
            const xAxis = expansion.direction.startsWith("X_");
            const x = xAxis ? delta : 0;
            const z = xAxis ? 0 : delta;
            const markerInside = areaContainsBounds(
                expansion,
                template.markerFirstX + x,
                template.markerFirstY,
                template.markerFirstZ + z,
                template.markerSecondX + x,
                template.markerSecondY,
                template.markerSecondZ + z
            );
            const storageInside = areaContainsBounds(
                expansion,
                template.storageFirstX + x,
                template.storageFirstY,
                template.storageFirstZ + z,
                template.storageSecondX + x,
                template.storageSecondY,
                template.storageSecondZ + z
            );
            if (!markerInside || !storageInside) {
                break;
            }
            count++;
            if (count > 256) {
                return {
                    valid: false,
                    message: "自动铺满最多生成 256 个单片"
                };
            }
        }
        return {
            valid: true,
            message: `预计生成 ${count} 片，母版为第 1 片`
        };
    }

    /**
     * @param {object} area
     * @param {number} firstX
     * @param {number} firstY
     * @param {number} firstZ
     * @param {number} secondX
     * @param {number} secondY
     * @param {number} secondZ
     * @returns {boolean}
     */
    function areaContainsBounds(
            area,
            firstX,
            firstY,
            firstZ,
            secondX,
            secondY,
            secondZ
    ) {
        return Math.min(firstX, secondX) >= Math.min(
            area.areaFirstX,
            area.areaSecondX
        ) && Math.max(firstX, secondX) <= Math.max(
            area.areaFirstX,
            area.areaSecondX
        ) && Math.min(firstY, secondY) >= Math.min(
            area.areaFirstY,
            area.areaSecondY
        ) && Math.max(firstY, secondY) <= Math.max(
            area.areaFirstY,
            area.areaSecondY
        ) && Math.min(firstZ, secondZ) >= Math.min(
            area.areaFirstZ,
            area.areaSecondZ
        ) && Math.max(firstZ, secondZ) <= Math.max(
            area.areaFirstZ,
            area.areaSecondZ
        );
    }

    /**
     * @param {object} expansion
     * @returns {string}
     */
    function expansionDescription(expansion) {
        const direction = {
            X_POSITIVE: "X 正方向",
            X_NEGATIVE: "X 负方向",
            Z_POSITIVE: "Z 正方向",
            Z_NEGATIVE: "Z 负方向"
        }[expansion.direction] || expansion.direction;
        return `总选区 ${expansion.areaFirstX}, ${expansion.areaFirstY}, `
            + `${expansion.areaFirstZ} 至 ${expansion.areaSecondX}, `
            + `${expansion.areaSecondY}, ${expansion.areaSecondZ}`
            + ` · ${direction} · 步长 ${expansion.step}`;
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
     * @param {string} [title]
     * @returns {() => object}
     */
    function appendBinding(host, binding, expanded = false, title = "") {
        const card = Stowling.element("details", "category-binding-card");
        card.open = expanded;
        const summary = Stowling.element("summary", "category-binding-summary");
        const identity = Stowling.element("div", "resource-summary-text");
        const number = String(host.children.length + 1).padStart(2, "0");
        identity.append(
            Stowling.element(
                "strong",
                "",
                title || `范围 ${number} · 识别区 → 储存区`
            ),
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
            if (!newZoneEditor) {
                throw new Error("分类区编辑器尚未准备完成");
            }
            const response = await Stowling.request("/v1/category-zones", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(Model.payload({
                    name: elements.name.value,
                    ...newZoneEditor.read()
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
        newZoneEditor = createBindingEditor(
            [Model.normalizeBinding({})],
            Model.normalizeExpansion({}),
            true
        );
        elements.bindings.append(newZoneEditor.root);
        elements.addBinding.hidden = true;
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
