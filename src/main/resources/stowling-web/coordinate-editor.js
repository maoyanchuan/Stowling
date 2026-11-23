(() => {
    "use strict";

    /** @typedef {{x: number, y: number, z: number}} CoordinateCorner */

    /** @typedef {{first: CoordinateCorner, second: CoordinateCorner}} CoordinateRegion */

    /** @typedef {{id: string, name?: string, markerRegion: CoordinateRegion,
     * storageRegion: CoordinateRegion, original?: object}} CategoryBinding */

    /** @typedef {{scopeMode: string, recognitionMode: string,
     * storageMethod: string, noCacheLocation: string, ignoreSupportBlock: boolean,
     * supportBlock: string, cacheDestination: string, expansionDirection: string,
     * expansionStep: number}} CategoryOptions */

    /** @typedef {{key: string, select: HTMLSelectElement, wrap: HTMLElement,
     * trigger: HTMLButtonElement, current: HTMLElement, menu: HTMLElement,
     * options: HTMLButtonElement[]}} CategorySelect */

    /** @typedef {{id: string, name: string, kind: string, folderId: string,
     * x?: number, y?: number, z?: number, radius?: number|null, dimension?: string,
     * saved?: boolean, members?: string[], originalOptions?: CategoryOptions,
     * originalExpansion?: object,
     * region?: CoordinateRegion, overallRegion?: CoordinateRegion,
     * bindings?: CategoryBinding[], categoryOptions?: CategoryOptions}} Coordinate */

    const S = window.Stowling;
    const Model = window.StowlingCoordinateEditorModel;
    const folders = [];
    const coordinates = [];
    let directory = null;
    let busy = false;
    let editingFolder = "";
    let dirty = false;
    const state = {
        selectedId: coordinates[0]?.id || "",
        editorKind: coordinates[0]?.kind || "point",
        showKindPicker: false
    };
    let draggedId = "";
    let draggedItem = null;
    let dropPlaceholder = null;
    let placeholderLocation = "";
    let kindMenuOpen = false;
    let categoryMenuOpen = null;
    const reveals = new WeakMap();
    state.categorySupportExpanded = false;
    const nodes = {
        list: document.querySelector("#coordinate-list"),
        add: document.querySelector("#add-coordinate"),
        kind: document.querySelector("#coordinate-kind"),
        kindWrap: document.querySelector(".coordinate-kind-select"),
        kindPicker: document.querySelector("#coordinate-kind-picker"),
        kindTrigger: document.querySelector("#coordinate-kind-trigger"),
        kindCurrent: document.querySelector("#coordinate-kind-current"),
        kindMenu: document.querySelector("#coordinate-kind-menu"),
        kindOptions: Array.from(document.querySelectorAll("[data-coordinate-kind]")),
        addFolder: document.querySelector("#add-folder"),
        folderForm: document.querySelector("#folder-form"),
        folderName: document.querySelector("#folder-name"),
        cancelFolder: document.querySelector("#cancel-folder"),
        footer: document.querySelector("#library-footer"),
        empty: document.querySelector("#editor-empty"),
        content: document.querySelector("#editor-content"),
        title: document.querySelector("#editor-title"),
        subtitle: document.querySelector("#editor-subtitle"),
        pointFields: Array.from(document.querySelectorAll("[data-point-field]")),
        pointOptions: document.querySelector("#point-options"),
        regionFields: document.querySelector("#region-fields"),
        categoryFields: document.querySelector("#category-zone-fields"),
        categoryBindings: document.querySelector("#category-bindings"),
        addCategoryBinding: document.querySelector("#add-category-binding"),
        categoryScopeMode: document.querySelector("#category-scope-mode"),
        categoryRecognitionMode: document.querySelector("#category-recognition-mode"),
        categoryStorageMethod: document.querySelector("#category-storage-method"),
        categoryNoCacheRow: document.querySelector("#category-no-cache-row"),
        categoryNoCacheLocation: document.querySelector("#category-no-cache-location"),
        categoryIgnoreSupport: document.querySelector("#category-ignore-support"),
        categorySupportExpand: document.querySelector("#category-support-expand"),
        categorySupportBlockRow: document.querySelector("#category-support-block-row"),
        categorySupportBlock: document.querySelector("#category-support-block"),
        form: document.querySelector("#coordinate-form"),
        name: document.querySelector("#coordinate-name"),
        x: document.querySelector("#coordinate-x"),
        y: document.querySelector("#coordinate-y"),
        z: document.querySelector("#coordinate-z"),
        radius: document.querySelector("#coordinate-radius"),
        dimension: document.querySelector("#coordinate-dimension"),
        warehouseMembers: document.querySelector("#warehouse-members"),
        cacheDestination: document.querySelector("#category-cache-destination"),
        cacheRow: document.querySelector("#category-cache-row"),
        expansionDirection: document.querySelector("#category-expansion-direction"),
        expansionStep: document.querySelector("#category-expansion-step"),
        categoryOverall: {
            first: {
                x: document.querySelector("#category-overall-first-x"),
                y: document.querySelector("#category-overall-first-y"),
                z: document.querySelector("#category-overall-first-z")
            },
            second: {
                x: document.querySelector("#category-overall-second-x"),
                y: document.querySelector("#category-overall-second-y"),
                z: document.querySelector("#category-overall-second-z")
            }
        },
        region: {
            first: {
                x: document.querySelector("#region-first-x"),
                y: document.querySelector("#region-first-y"),
                z: document.querySelector("#region-first-z")
            },
            second: {
                x: document.querySelector("#region-second-x"),
                y: document.querySelector("#region-second-y"),
                z: document.querySelector("#region-second-z")
            }
        },
        duplicate: document.querySelector("#duplicate-coordinate"),
        remove: document.querySelector("#delete-coordinate"),
        status: document.querySelector("#editor-status")
    };

    /** @type {CategorySelect[]} */
    const categorySelects = [
        ["scopeMode", nodes.categoryScopeMode],
        ["recognitionMode", nodes.categoryRecognitionMode],
        ["storageMethod", nodes.categoryStorageMethod],
        ["noCacheLocation", nodes.categoryNoCacheLocation],
        ["cacheDestination", nodes.cacheDestination],
        ["expansionDirection", nodes.expansionDirection]
    ].map(([key, select]) => {
        const wrap = document.querySelector(`[data-category-select="${key}"]`);
        return {
            key,
            select,
            wrap,
            trigger: wrap.querySelector(".category-select-trigger"),
            current: wrap.querySelector(".category-select-current"),
            menu: wrap.querySelector(".category-select-menu"),
            options: Array.from(wrap.querySelectorAll(".category-select-option"))
        };
    });

    /** @param {HTMLElement} content @param {boolean} expanded
     * @param {boolean} animate @param {string} [kind] @returns {void} */
    function reveal(content, expanded, animate, kind = "panel") {
        const previous = reveals.get(content);
        if (previous?.expanded === expanded) return;
        const height = content.hidden ? 0 : content.getBoundingClientRect().height;
        const computed = previous?.animation ? window.getComputedStyle(content) : null;
        const opacity = computed?.opacity ?? (expanded ? 0 : 1);
        const offset = content.dataset.placement === "above"
            ? "translateY(4px)" : "translateY(-4px)";
        const transform = computed?.transform ?? (expanded ? offset : "none");
        previous?.animation?.cancel();
        const current = {expanded, animation: null};
        reveals.set(content, current);
        content.dataset.collapsed = String(!expanded);
        content.inert = !expanded;
        content.hidden = false;
        const reduced = window.matchMedia?.("(prefers-reduced-motion: reduce)")?.matches;
        if (!previous || !animate || reduced || !content.animate) {
            content.hidden = !expanded;
            content.style.overflow = "";
            content.style.minHeight = "";
            return;
        }
        let expandedHeight = 0;
        if (kind === "panel" && expanded) {
            content.style.minHeight = "";
            expandedHeight = content.scrollHeight;
        }
        if (kind === "panel") {
            content.style.minHeight = "0";
        }
        const frames = kind === "menu" ? [
            {opacity, transform},
            {opacity: expanded ? 1 : 0, transform: expanded ? "none" : offset}
        ] : [
            {height: `${height}px`, opacity},
            {height: `${expanded ? expandedHeight : 0}px`, opacity: expanded ? 1 : 0}
        ];
        if (kind === "panel") content.style.overflow = "hidden";
        const animation = content.animate(frames, {
            duration: expanded ? 200 : 150,
            easing: "cubic-bezier(0.16, 1, 0.3, 1)"
        });
        current.animation = animation;
        animation.finished.then(() => {
            if (reveals.get(content) !== current) return;
            content.hidden = !expanded;
            content.style.overflow = "";
            content.style.minHeight = "";
            current.animation = null;
        }).catch(() => {});
    }

    /** @param {CategorySelect} control @returns {void} */
    function syncCategorySelect(control) {
        const selected = control.select.options[control.select.selectedIndex];
        control.current.textContent = selected?.textContent || "";
        control.trigger.disabled = control.select.disabled;
        control.options.forEach(option => {
            const active = option.dataset.categoryValue === control.select.value;
            option.setAttribute("aria-selected", String(active));
            option.tabIndex = active ? 0 : -1;
        });
    }

    /** @param {CategorySelect} control @param {boolean} open
     * @param {boolean} [focus] @param {boolean} [animate] @returns {void} */
    function setCategoryMenuOpen(control, open, focus = false, animate = true) {
        if (open && categoryMenuOpen && categoryMenuOpen !== control) {
            setCategoryMenuOpen(categoryMenuOpen, false, false, false);
        }
        categoryMenuOpen = open ? control : categoryMenuOpen;
        const optionRow = control.wrap.closest(".category-option-row, .category-option-sub");
        if (open) optionRow?.setAttribute("data-menu-open", "true");
        else optionRow?.removeAttribute("data-menu-open");
        if (open) {
            control.menu.hidden = false;
            const bounds = control.trigger.getBoundingClientRect();
            const height = control.menu.scrollHeight;
            const below = window.innerHeight - bounds.bottom - 12;
            control.menu.dataset.placement = below < height && bounds.top > height + 12
                ? "above" : "below";
        }
        control.trigger.setAttribute("aria-expanded", String(open));
        reveal(control.menu, open, animate, "menu");
        if (!open && categoryMenuOpen === control) categoryMenuOpen = null;
        if (!focus) return;
        if (open) {
            control.options.find(option => option.dataset.categoryValue === control.select.value)
                ?.focus();
        } else {
            control.trigger.focus();
        }
    }

    /** @param {CategorySelect} control @param {string} value @returns {void} */
    function selectCategoryOption(control, value) {
        if (!control.options.some(option => option.dataset.categoryValue === value)) return;
        control.select.value = value;
        syncCategorySelect(control);
        control.select.dispatchEvent(new Event("change", {bubbles: true}));
        setCategoryMenuOpen(control, false, true);
    }

    /** @returns {void} */
    function clearDropTargets() {
        nodes.list.querySelectorAll(".folder-drop-target").forEach(target => {
            target.classList.remove("folder-drop-target");
        });
        nodes.list.querySelectorAll(".drop-before, .drop-after").forEach(target => {
            target.classList.remove("drop-before", "drop-after");
        });
        nodes.list.querySelectorAll(".root-drop-target").forEach(target => {
            target.classList.remove("root-drop-target");
        });
    }

    /** @returns {Map<Element, number>} */
    function captureCoordinatePositions() {
        const positions = new Map();
        nodes.list.querySelectorAll(".coordinate-item:not(.dragging)").forEach(item => {
            positions.set(item, item.getBoundingClientRect().top);
        });
        return positions;
    }

    /** @param {Map<Element, number>} previous @returns {void} */
    function animateCoordinateShift(previous) {
        const reduced = window.matchMedia?.("(prefers-reduced-motion: reduce)")?.matches;
        if (reduced || !Element.prototype.animate) return;
        nodes.list.querySelectorAll(".coordinate-item:not(.dragging)").forEach(item => {
            const before = previous.get(item);
            if (before === undefined) return;
            const delta = before - item.getBoundingClientRect().top;
            if (Math.abs(delta) < 1) return;
            item.animate(
                [{transform: `translateY(${delta}px)`}, {transform: "translateY(0)"}],
                {duration: 220, easing: "cubic-bezier(0.16, 1, 0.3, 1)"}
            );
        });
    }

    /** @param {HTMLElement} host @param {HTMLElement|null} before
     * @param {string} text @param {string} location @returns {void} */
    function movePlaceholder(host, before, text, location) {
        if (!dropPlaceholder) return;
        if (placeholderLocation === location) return;
        const previous = captureCoordinatePositions();
        placeholderLocation = location;
        dropPlaceholder.textContent = text;
        if (before) before.before(dropPlaceholder);
        else host.append(dropPlaceholder);
        animateCoordinateShift(previous);
    }

    /** @param {HTMLElement} host @param {number} clientY
     * @returns {HTMLElement|null} */
    function coordinateDropTarget(host, clientY) {
        return Array.from(host.querySelectorAll(
            ":scope > .coordinate-item:not(.dragging)"
        )).find(item => clientY < item.getBoundingClientRect().top
            + item.getBoundingClientRect().height / 2) || null;
    }

    /** @param {string} folderId @param {string} [beforeId] @returns {boolean} */
    function placeDraggedCoordinate(folderId, beforeId = "") {
        const sourceIndex = coordinates.findIndex(value => value.id === draggedId);
        if (sourceIndex < 0) return false;
        const [moved] = coordinates.splice(sourceIndex, 1);
        moved.folderId = folderId;
        const targetIndex = beforeId
            ? coordinates.findIndex(value => value.id === beforeId)
            : -1;
        coordinates.splice(targetIndex < 0 ? coordinates.length : targetIndex, 0, moved);
        return true;
    }

    /** @param {string} kind @returns {string} */
    function kindLabel(kind) {
        return {
            point: "坐标点",
            region: "区域",
            "category-zone": "分类区",
            warehouse: "储存区"
        }[kind] || "坐标点";
    }

    /** @returns {CoordinateCorner} */
    function emptyCorner() {
        return {x: 0, y: 64, z: 0};
    }

    /** @returns {CoordinateRegion} */
    function emptyRegion() {
        return {first: emptyCorner(), second: emptyCorner()};
    }

    /** @param {CoordinateRegion} region @returns {CoordinateRegion} */
    function cloneRegion(region) {
        return {first: {...region.first}, second: {...region.second}};
    }

    /** @returns {CategoryBinding} */
    function emptyBinding() {
        return {
            id: nextId(),
            name: "",
            markerRegion: emptyRegion(),
            storageRegion: emptyRegion()
        };
    }

    /** @returns {CategoryOptions} */
    function defaultCategoryOptions() {
        return {
            scopeMode: "single",
            recognitionMode: "marker",
            storageMethod: "bulk",
            noCacheLocation: "inside-box",
            ignoreSupportBlock: false,
            supportBlock: "",
            cacheDestination: "",
            expansionDirection: "AUTO",
            expansionStep: 1
        };
    }

    /** @param {HTMLElement} card @param {boolean} open @returns {void} */
    function setCategoryBindingOpen(card, open) {
        card.dataset.collapsed = String(!open);
        const toggle = card.querySelector(".category-binding-toggle");
        const body = card.querySelector(".category-binding-card-body");
        toggle.setAttribute("aria-expanded", String(open));
        toggle.setAttribute("aria-label", `${open ? "收起" : "展开"}绑定组`);
        reveal(body, open, true);
    }

    /** @param {CategoryOptions} options @returns {void} */
    function writeCategoryOptions(options) {
        const values = {...defaultCategoryOptions(), ...options};
        populateCacheOptions();
        nodes.cacheDestination.value = values.cacheDestination;
        nodes.expansionDirection.value = values.expansionDirection;
        nodes.expansionStep.value = String(values.expansionStep);
        nodes.categoryScopeMode.value = values.scopeMode;
        nodes.categoryRecognitionMode.value = values.recognitionMode;
        nodes.categoryStorageMethod.value = values.storageMethod;
        nodes.categoryNoCacheLocation.value = values.noCacheLocation;
        nodes.categoryIgnoreSupport.checked = values.ignoreSupportBlock;
        nodes.categorySupportBlock.value = values.supportBlock;
        state.categorySupportExpanded = values.ignoreSupportBlock;
        categorySelects.forEach(syncCategorySelect);
        updateCategoryOptionState();
    }

    /** @returns {CategoryOptions} */
    function readCategoryOptions() {
        return {
            scopeMode: nodes.categoryScopeMode.value,
            recognitionMode: nodes.categoryRecognitionMode.value,
            storageMethod: nodes.categoryStorageMethod.value,
            noCacheLocation: nodes.categoryNoCacheLocation.value,
            ignoreSupportBlock: nodes.categoryIgnoreSupport.checked,
            supportBlock: nodes.categorySupportBlock.value.trim(),
            cacheDestination: nodes.cacheDestination.value,
            expansionDirection: nodes.expansionDirection.value,
            expansionStep: Number(nodes.expansionStep.value)
        };
    }

    /** @returns {void} */
    function updateCategoryOptionState() {
        const hasStorageLocation = ["mixed", "box"]
            .includes(nodes.categoryStorageMethod.value);
        nodes.categoryNoCacheLocation.disabled = !hasStorageLocation;
        reveal(nodes.categoryNoCacheRow, hasStorageLocation, true);
        const needsCache = hasStorageLocation
            && nodes.categoryNoCacheLocation.value === "specified";
        nodes.cacheDestination.disabled = !needsCache;
        reveal(nodes.cacheRow, needsCache, true);
        const single = nodes.categoryScopeMode.value === "single";
        nodes.expansionDirection.disabled = nodes.expansionStep.disabled = !single;
        reveal(document.querySelector("#category-expansion-row"), single, true);
        reveal(document.querySelector("#category-step-row"), single, true);
        categorySelects.forEach(syncCategorySelect);
        const ignoreSupport = nodes.categoryIgnoreSupport.checked;
        nodes.categorySupportExpand.hidden = !ignoreSupport;
        nodes.categorySupportExpand.disabled = !ignoreSupport;
        const supportExpanded = ignoreSupport && state.categorySupportExpanded;
        nodes.categorySupportExpand.setAttribute("aria-expanded", String(supportExpanded));
        nodes.categorySupportExpand.setAttribute(
            "aria-label",
            `${supportExpanded ? "收起" : "展开"}支撑方块设置`
        );
        reveal(nodes.categorySupportBlockRow, supportExpanded, true);
        nodes.categorySupportBlock.disabled = !ignoreSupport;
    }

    /** @param {HTMLElement} host @param {string} title @param {string} regionKind
     * @param {string} bindingId @param {CoordinateRegion} region @returns {void} */
    function appendBindingRegion(host, title, regionKind, bindingId, region) {
        const fieldset = document.createElement("fieldset");
        fieldset.className = "coordinate-region-card";
        const legend = document.createElement("legend");
        legend.textContent = title;
        const fields = document.createElement("div");
        fields.className = "coordinate-corner-fields";
        for (const [cornerKey, cornerLabel] of [["first", "1"], ["second", "2"]]) {
            for (const axis of ["x", "y", "z"]) {
                const label = document.createElement("label");
                label.className = "field-label";
                const input = document.createElement("input");
                const inputId = `binding-${bindingId}-${regionKind}-${cornerKey}-${axis}`;
                input.id = inputId;
                input.type = "number";
                input.step = "1";
                input.required = true;
                input.value = String(region[cornerKey][axis]);
                input.dataset.bindingRegion = regionKind;
                input.dataset.bindingCorner = cornerKey;
                input.dataset.bindingAxis = axis;
                label.htmlFor = inputId;
                label.append(`${axis.toUpperCase()}${cornerLabel}`, input);
                fields.append(label);
            }
        }
        fieldset.append(legend, fields);
        for (const corner of ["first", "second"]) {
            S.bindCoordinateInputs(...fields.querySelectorAll(
                `[data-binding-corner="${corner}"]`));
        }
        host.append(fieldset);
    }

    /** @param {CategoryBinding} binding @param {number} index @param {boolean} [open]
     * @returns {HTMLElement} */
    function createCategoryBindingCard(binding, index, open = false) {
        const card = document.createElement("article");
        card.className = "category-binding-card";
        card.dataset.collapsed = String(!open);
        card.dataset.bindingId = binding.id;
        card.originalBinding = binding.original;
        const summary = document.createElement("div");
        summary.className = "category-binding-summary";
        const toggle = document.createElement("button");
        toggle.className = "category-binding-toggle";
        toggle.type = "button";
        toggle.innerHTML = "<svg aria-hidden=\"true\" width=\"16\" height=\"16\" "
            + "viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" "
            + "stroke-width=\"1.8\"><path d=\"m9 6 6 6-6 6\"/></svg>";
        toggle.addEventListener("click", () => {
            setCategoryBindingOpen(card, card.dataset.collapsed === "true");
        });
        summary.addEventListener("click", event => {
            if (event.target.closest("input, button")) return;
            setCategoryBindingOpen(card, card.dataset.collapsed === "true");
        });
        const name = document.createElement("input");
        name.className = "category-binding-name";
        name.type = "text";
        name.maxLength = 40;
        name.value = `绑定组 ${index + 1}`;
        name.readOnly = true;
        name.setAttribute("aria-label", `绑定组 ${index + 1}名称`);
        name.addEventListener("click", event => {
            event.preventDefault();
            event.stopPropagation();
        });
        name.addEventListener("keydown", event => event.stopPropagation());
        const remove = document.createElement("button");
        remove.type = "button";
        remove.className = "ghost danger category-binding-remove";
        remove.textContent = "删除此组";
        remove.addEventListener("click", event => {
            event.preventDefault();
            event.stopPropagation();
            card.remove();
            dirty = true;
            updateCategoryBindingCards();
        });
        summary.append(toggle, name, remove);
        const grid = document.createElement("div");
        const body = document.createElement("div");
        body.className = "category-binding-card-body";
        grid.className = "coordinate-region-grid";
        appendBindingRegion(
            grid,
            "标识物识别区",
            "marker",
            binding.id,
            binding.markerRegion || emptyRegion()
        );
        appendBindingRegion(
            grid,
            "储存区",
            "storage",
            binding.id,
            binding.storageRegion || emptyRegion()
        );
        body.append(grid);
        card.append(summary, body);
        setCategoryBindingOpen(card, open);
        return card;
    }

    /** @returns {void} */
    function updateCategoryBindingCards() {
        const cards = Array.from(nodes.categoryBindings.children);
        cards.forEach((card, index) => {
            card.querySelector(".category-binding-name").placeholder = `绑定组 ${index + 1}`;
            card.querySelector(".category-binding-remove").disabled = cards.length <= 1;
        });
    }

    /** @param {CategoryBinding[]} bindings @returns {void} */
    function renderCategoryBindings(bindings) {
        const values = bindings.length ? bindings : [emptyBinding()];
        nodes.categoryBindings.replaceChildren();
        values.forEach((binding, index) => {
            nodes.categoryBindings.append(createCategoryBindingCard(binding, index));
        });
        updateCategoryBindingCards();
    }

    /** @returns {void} */
    function addCategoryBinding() {
        dirty = true;
        const index = nodes.categoryBindings.children.length;
        nodes.categoryBindings.append(createCategoryBindingCard(emptyBinding(), index, true));
        updateCategoryBindingCards();
    }

    /** @param {HTMLElement} card @param {string} regionKind
     * @returns {CoordinateRegion|null} */
    function readBindingRegion(card, regionKind) {
        const fields = {first: {}, second: {}};
        for (const corner of ["first", "second"]) {
            for (const axis of ["x", "y", "z"]) {
                fields[corner][axis] = card.querySelector(
                    `[data-binding-region="${regionKind}"]`
                        + `[data-binding-corner="${corner}"][data-binding-axis="${axis}"]`
                );
            }
        }
        return readRegionFields(fields);
    }

    /** @returns {CategoryBinding[]|null} */
    function readCategoryBindings() {
        const cards = Array.from(nodes.categoryBindings.children);
        if (!cards.length) return null;
        const bindings = cards.map((card, index) => {
            const markerRegion = readBindingRegion(card, "marker");
            const storageRegion = readBindingRegion(card, "storage");
            if (!markerRegion || !storageRegion) return null;
            return {
                id: card.dataset.bindingId || nextId(),
                name: card.querySelector(".category-binding-name").value.trim()
                    || `绑定组 ${index + 1}`,
                original: card.originalBinding,
                markerRegion,
                storageRegion
            };
        });
        return bindings.some(binding => !binding) ? null : bindings;
    }

    /** @param {{x: HTMLInputElement, y: HTMLInputElement, z: HTMLInputElement}}
     * fields @param {CoordinateCorner} corner @returns {void} */
    function writeCornerFields(fields, corner) {
        fields.x.value = String(corner.x);
        fields.y.value = String(corner.y);
        fields.z.value = String(corner.z);
    }

    /** @param {{x: HTMLInputElement, y: HTMLInputElement, z: HTMLInputElement}}
     * fields @returns {CoordinateCorner|null} */
    function readCornerFields(fields) {
        const values = [fields.x.value, fields.y.value, fields.z.value].map(integerValue);
        if (values.some(value => value === null)) return null;
        return {x: values[0], y: values[1], z: values[2]};
    }

    /** @param {{first: object, second: object}} fields
     * @param {CoordinateRegion} region @returns {void} */
    function writeRegionFields(fields, region) {
        writeCornerFields(fields.first, region.first);
        writeCornerFields(fields.second, region.second);
    }

    /** @param {{first: object, second: object}} fields
     * @returns {CoordinateRegion|null} */
    function readRegionFields(fields) {
        const first = readCornerFields(fields.first);
        const second = readCornerFields(fields.second);
        if (!first || !second) return null;
        return {first, second};
    }

    /** @param {HTMLElement} group @param {boolean} enabled @returns {void} */
    function setFieldGroupEnabled(group, enabled) {
        group.hidden = !enabled;
        group.querySelectorAll("input, select, button").forEach(control => {
            control.disabled = !enabled;
        });
    }

    /** @param {string} kind @returns {boolean} */
    function isCoordinateKind(kind) {
        return ["point", "region", "category-zone", "warehouse"].includes(kind);
    }

    /** @param {string} kind @returns {void} */
    function syncKindMenu(kind) {
        const selected = isCoordinateKind(kind) ? kind : "point";
        nodes.kind.value = selected;
        nodes.kindCurrent.textContent = kindLabel(selected);
        nodes.kindOptions.forEach(option => {
            const active = option.dataset.coordinateKind === selected;
            option.setAttribute("aria-selected", String(active));
            option.tabIndex = active ? 0 : -1;
        });
    }

    /** @param {boolean} open @param {boolean} [focus] @returns {void} */
    function setKindMenuOpen(open, focus = false) {
        kindMenuOpen = open;
        if (open) {
            nodes.kindMenu.hidden = false;
            const bounds = nodes.kindTrigger.getBoundingClientRect();
            const height = nodes.kindMenu.scrollHeight;
            const below = window.innerHeight - bounds.bottom - 12;
            nodes.kindMenu.dataset.placement = below < height && bounds.top > height + 12
                ? "above" : "below";
        }
        nodes.kindTrigger.setAttribute("aria-expanded", String(open));
        reveal(nodes.kindMenu, open, true, "menu");
        if (!focus) return;
        if (open) {
            nodes.kindOptions.find(option => option.dataset.coordinateKind === nodes.kind.value)
                ?.focus();
        } else {
            nodes.kindTrigger.focus();
        }
    }

    /** @param {string} kind @returns {void} */
    function selectCoordinateKind(kind) {
        if (!isCoordinateKind(kind)) return;
        if (busy) return;
        const coordinate = selectedCoordinate();
        if (coordinate) {
            coordinate.name = nodes.name.value;
            if (state.editorKind === "point") {
                for (const axis of ["x", "y", "z"]) {
                    coordinate[axis] = integerValue(nodes[axis].value) ?? coordinate[axis];
                }
                coordinate.radius = integerValue(nodes.radius.value);
                coordinate.dimension = nodes.dimension.value;
            } else if (state.editorKind === "region") {
                coordinate.region = readRegionFields(nodes.region) || coordinate.region;
            } else if (state.editorKind === "category-zone") {
                coordinate.overallRegion = readRegionFields(nodes.categoryOverall)
                    || coordinate.overallRegion;
                coordinate.bindings = readCategoryBindings() || coordinate.bindings;
                coordinate.categoryOptions = readCategoryOptions();
            } else if (state.editorKind === "warehouse") {
                coordinate.members = Array.from(nodes.warehouseMembers.querySelectorAll(
                    "input:checked"), input => input.value);
            }
        }
        dirty = false;
        state.editorKind = kind;
        syncKindMenu(kind);
        if (coordinate) populateEditor(coordinate);
        setKindMenuOpen(false, true);
    }

    /** @param {Coordinate} coordinate @returns {void} */
    function populateEditor(coordinate) {
        const kind = state.editorKind || coordinate.kind || "point";
        nodes.kindPicker.hidden = !state.showKindPicker;
        setFieldGroupEnabled(nodes.warehouseMembers, kind === "warehouse");
        if (kind === "warehouse") populateWarehouseMembers(coordinate);
        const references = directory?.references?.[Model.ref(coordinate)] || [];
        document.querySelector("#resource-references").textContent = references.length
            ? `被引用：${references.join("、")}` : "";
        nodes.title.textContent = `${kindLabel(kind)}编辑`;
        syncKindMenu(kind);
        nodes.subtitle.textContent = kind === coordinate.kind
            ? coordinateSummary(coordinate)
            : `正在填写新的${kindLabel(kind)}信息`;
        nodes.name.value = coordinate.name;
        nodes.pointFields.forEach(field => {
            field.hidden = kind !== "point";
            field.querySelector("input").disabled = kind !== "point";
        });
        if (kind === "category-zone") {
            writeRegionFields(nodes.categoryOverall, coordinate.overallRegion || emptyRegion());
            writeCategoryOptions(coordinate.categoryOptions || defaultCategoryOptions());
            renderCategoryBindings(coordinate.bindings || [emptyBinding()]);
        }
        setFieldGroupEnabled(nodes.pointOptions, kind === "point");
        setFieldGroupEnabled(nodes.regionFields, kind === "region");
        setFieldGroupEnabled(nodes.categoryFields, kind === "category-zone");
        if (kind === "category-zone") updateCategoryOptionState();
        if (kind === "point") {
            nodes.x.value = String(coordinate.x ?? 0);
            nodes.y.value = String(coordinate.y ?? 64);
            nodes.z.value = String(coordinate.z ?? 0);
            nodes.radius.value = coordinate.radius == null ? "" : String(coordinate.radius);
            nodes.dimension.value = coordinate.dimension || "";
        } else if (kind === "region") {
            writeRegionFields(nodes.region, coordinate.region || emptyRegion());
        }
        nodes.form.querySelector("button[type=submit]").textContent = `保存${kindLabel(kind)}`;
        nodes.duplicate.textContent = `复制${kindLabel(kind)}`;
        nodes.remove.textContent = `删除${kindLabel(kind)}`;
    }

    /** @param {Coordinate} coordinate @returns {string} */
    function coordinateSummary(coordinate) {
        if (coordinate.kind === "warehouse") return `${coordinate.members?.length || 0} 个成员`;
        const format = corner => `${corner.x}, ${corner.y}, ${corner.z}`;
        if (coordinate.kind === "region" && coordinate.region) {
            return `${format(coordinate.region.first)} → ${format(coordinate.region.second)}`;
        }
        if (coordinate.kind === "category-zone" && coordinate.overallRegion
                && coordinate.bindings?.length) {
            return `${coordinate.bindings.length} 组绑定 · 总选区 `
                + `${format(coordinate.overallRegion.first)} → `
                + `${format(coordinate.overallRegion.second)}`;
        }
        return `${coordinate.x ?? 0}, ${coordinate.y ?? 64}, ${coordinate.z ?? 0}`;
    }

    /** @param {string} kind @returns {Coordinate} */
    function newCoordinate(kind) {
        const coordinate = {
            id: nextId(),
            name: `新${kindLabel(kind)}`,
            kind,
            folderId: ""
        };
        if (kind === "point") {
            return {...coordinate, x: 0, y: 64, z: 0, radius: 3};
        }
        if (kind === "region") return {...coordinate, region: emptyRegion()};
        if (kind === "warehouse") return {...coordinate, members: []};
        return {
            ...coordinate,
            overallRegion: emptyRegion(),
            bindings: [emptyBinding()],
            categoryOptions: defaultCategoryOptions()
        };
    }

    /** @param {Coordinate} source @returns {Coordinate} */
    function cloneCoordinate(source) {
        return {...Model.copy(source), id: nextId(), saved: false, name: `${source.name} 副本`};
    }

    /** @param {Coordinate} coordinate @param {HTMLElement} host @returns {void} */
    function renderItem(coordinate, host) {
        const item = document.createElement("button");
        item.type = "button";
        item.className = "coordinate-item";
        item.draggable = Boolean(coordinate.saved);
        item.dataset.coordinateId = coordinate.id;
        item.setAttribute("role", "option");
        item.setAttribute("aria-selected", String(coordinate.id === state.selectedId));
        item.setAttribute("aria-current", String(coordinate.id === state.selectedId));
        item.innerHTML = "<strong></strong><span class=\"coordinate-item-meta\"></span>";
        item.querySelector("strong").textContent = coordinate.name;
        item.querySelector(".coordinate-item-meta").textContent = kindLabel(coordinate.kind);
        item.addEventListener("click", () => {
            if (coordinate.id === state.selectedId || !leaveEditor()) return;
            state.selectedId = coordinate.id;
            state.editorKind = coordinate.kind || "point";
            state.showKindPicker = false;
            setStatus("");
            render();
        });
        item.addEventListener("dragstart", event => {
            if (busy || !coordinate.saved) { event.preventDefault(); return; }
            draggedId = coordinate.id;
            draggedItem = item;
            item.classList.add("dragging");
            nodes.list.classList.add("is-dragging");
            dropPlaceholder = document.createElement("div");
            dropPlaceholder.className = "coordinate-drop-placeholder";
            dropPlaceholder.textContent = "放到这里";
            item.before(dropPlaceholder);
            if (event.dataTransfer) {
                event.dataTransfer.effectAllowed = "move";
                event.dataTransfer.setData("text/plain", coordinate.id);
            }
        });
        item.addEventListener("dragend", clearDragState);
        item.addEventListener("dragover", event => {
            if (!draggedId || draggedId === coordinate.id) return;
            event.preventDefault();
            if (event.dataTransfer) event.dataTransfer.dropEffect = "move";
            clearDropTargets();
            const before = event.clientY < item.getBoundingClientRect().top
                + item.getBoundingClientRect().height / 2;
            movePlaceholder(
                host,
                before ? item : item.nextElementSibling,
                "放到这里",
                `coordinate:${coordinate.id}:${before ? "before" : "after"}`
            );
        });
        item.addEventListener("drop", event => {
            event.preventDefault();
            const sourceIndex = coordinates.findIndex(value => value.id === draggedId);
            const targetIndex = coordinates.findIndex(value => value.id === coordinate.id);
            if (sourceIndex < 0 || targetIndex < 0 || sourceIndex === targetIndex) return;
            const before = event.clientY < item.getBoundingClientRect().top
                + item.getBoundingClientRect().height / 2;
            const [moved] = coordinates.splice(sourceIndex, 1);
            let insertIndex = coordinates.findIndex(value => value.id === coordinate.id);
            if (!before) insertIndex += 1;
            moved.folderId = coordinate.folderId;
            coordinates.splice(insertIndex, 0, moved);
            clearDragState();
            void persistPlacement(moved);
        });
        host.append(item);
    }

    /** @param {object} folder @returns {void} */
    function renderFolder(folder) {
        const section = document.createElement("section");
        section.className = "coordinate-folder";
        section.dataset.collapsed = String(folder.collapsed);
        const header = document.createElement("button");
        header.type = "button";
        header.className = "coordinate-folder-header";
        header.setAttribute("aria-expanded", String(!folder.collapsed));
        const title = document.createElement("strong");
        title.textContent = folder.name;
        const count = document.createElement("span");
        count.className = "coordinate-folder-count";
        count.textContent = `${coordinates.filter(value => value.folderId === folder.id).length}`;
        header.append(title, count);
        header.title = "双击或按 F2 管理文件夹";
        header.addEventListener("dblclick", () => showFolderForm(folder));
        header.addEventListener("keydown", event => {
            if (event.key !== "F2") return;
            event.preventDefault();
            showFolderForm(folder);
        });
        header.addEventListener("click", () => {
            folder.collapsed = !folder.collapsed;
            section.dataset.collapsed = String(folder.collapsed);
            header.setAttribute("aria-expanded", String(!folder.collapsed));
            children.dataset.collapsed = String(folder.collapsed);
            children.inert = folder.collapsed;
        });
        header.addEventListener("dragover", event => {
            if (!draggedId) return;
            event.preventDefault();
            if (event.dataTransfer) event.dataTransfer.dropEffect = "move";
            clearDropTargets();
            header.classList.add("folder-drop-target");
            if (!folder.collapsed) {
                movePlaceholder(children, null, "放到这里", `folder:${folder.id}`);
            } else {
                dropPlaceholder?.remove();
                placeholderLocation = "";
            }
        });
        header.addEventListener("drop", event => {
            if (!draggedId) return;
            event.preventDefault();
            const index = coordinates.findIndex(value => value.id === draggedId);
            if (index < 0) return;
            const [moved] = coordinates.splice(index, 1);
            moved.folderId = folder.id;
            coordinates.push(moved);
            folder.collapsed = false;
            clearDragState();
            void persistPlacement(moved);
        });

        const children = document.createElement("div");
        children.className = "coordinate-folder-children";
        children.dataset.collapsed = String(folder.collapsed);
        children.inert = folder.collapsed;
        section.addEventListener("dragover", event => {
            if (!draggedId || event.target.closest(".coordinate-folder-header")
                    || event.target.closest(".coordinate-item")) return;
            event.preventDefault();
            if (event.dataTransfer) event.dataTransfer.dropEffect = "move";
            clearDropTargets();
            header.classList.add("folder-drop-target");
            const target = coordinateDropTarget(children, event.clientY);
            const location = target
                ? `folder:${folder.id}:before:${target.dataset.coordinateId}`
                : `folder:${folder.id}:end`;
            movePlaceholder(children, target, "放到这里", location);
        });
        section.addEventListener("drop", event => {
            if (!draggedId || event.target.closest(".coordinate-folder-header")
                    || event.target.closest(".coordinate-item")) return;
            event.preventDefault();
            const target = coordinateDropTarget(children, event.clientY);
            const beforeId = target?.dataset.coordinateId || "";
            const moved = coordinates.find(value => value.id === draggedId);
            if (!placeDraggedCoordinate(folder.id, beforeId)) return;
            folder.collapsed = false;
            clearDragState();
            void persistPlacement(moved);
        });
        coordinates.filter(value => value.folderId === folder.id)
            .forEach(coordinate => renderItem(coordinate, children));
        section.append(header, children);
        nodes.list.append(section);
    }

    /** @returns {Coordinate | undefined} */
    function selectedCoordinate() {
        return coordinates.find(coordinate => coordinate.id === state.selectedId);
    }

    /** @param {HTMLElement} root @returns {void} */
    function moveCoordinateToRoot(root) {
        const index = coordinates.findIndex(value => value.id === draggedId);
        if (index < 0) return;
        const [moved] = coordinates.splice(index, 1);
        moved.folderId = "";
        let beforeId = "";
        let next = dropPlaceholder?.nextElementSibling;
        while (next) {
            if (next.classList.contains("coordinate-item")
                    && next.dataset.coordinateId !== moved.id) {
                beforeId = next.dataset.coordinateId;
                break;
            }
            next = next.nextElementSibling;
        }
        const insertIndex = beforeId
            ? coordinates.findIndex(value => value.id === beforeId)
            : coordinates.length;
        coordinates.splice(insertIndex < 0 ? coordinates.length : insertIndex, 0, moved);
        clearDragState();
        void persistPlacement(moved);
    }

    /** @param {string} message @param {string} [tone] @returns {void} */
    function setStatus(message, tone = "") {
        nodes.status.textContent = message;
        if (tone) nodes.status.dataset.tone = tone;
        else delete nodes.status.dataset.tone;
    }

    /** @returns {void} */
    function clearDragState() {
        draggedItem?.classList.remove("dragging");
        dropPlaceholder?.remove();
        draggedItem = null;
        dropPlaceholder = null;
        draggedId = "";
        placeholderLocation = "";
        nodes.list.classList.remove("is-dragging");
        clearDropTargets();
    }

    /** @param {HTMLElement} root @returns {void} */
    function bindRootDrop(root) {
        root.addEventListener("dragover", event => {
            if (!draggedId || event.target.closest(".coordinate-item")
                    || event.target.closest(".coordinate-folder")) return;
            event.preventDefault();
            if (event.dataTransfer) event.dataTransfer.dropEffect = "move";
            clearDropTargets();
            root.classList.add("root-drop-target");
            const target = Array.from(root.querySelectorAll(":scope > .coordinate-item"))
                .find(item => event.clientY < item.getBoundingClientRect().top
                    + item.getBoundingClientRect().height / 2);
            const location = target
                ? `root-before:${target.dataset.coordinateId}` : "root-end";
            movePlaceholder(root, target, "放到这里", location);
        });
        root.addEventListener("drop", event => {
            if (!draggedId || event.target.closest(".coordinate-item")
                    || event.target.closest(".coordinate-folder")) return;
            event.preventDefault();
            moveCoordinateToRoot(root);
        });
    }

    /** @returns {void} */
    function renderLibrary() {
        nodes.list.replaceChildren();
        folders.forEach(renderFolder);
        const root = document.createElement("div");
        root.className = "coordinate-root-list";
        bindRootDrop(root);
        coordinates.filter(coordinate => !coordinate.folderId)
            .forEach(coordinate => renderItem(coordinate, root));
        if (!root.children.length) {
            root.append(document.createElement("div"));
            root.firstElementChild.className = "coordinate-root-empty";
            root.firstElementChild.textContent = "根目录暂无坐标";
        }
        nodes.list.append(root);
        nodes.footer.textContent = `${coordinates.length} 个资源 · ${folders.length} 个文件夹`;

        filterLibrary();
    }

    /** @returns {void} */
    function render() {
        renderLibrary();
        const coordinate = selectedCoordinate();
        const hasCoordinate = Boolean(coordinate);
        nodes.empty.hidden = hasCoordinate;
        nodes.content.hidden = !hasCoordinate;
        if (!coordinate) return;
        if (!dirty) populateEditor(coordinate);
    }

    /** @returns {string} */
    function nextId() {
        return `coordinate-${Date.now()}-${Math.random().toString(16).slice(2)}`;
    }

    /** @returns {void} */
    function addCoordinate() {
        if (!leaveEditor()) return;
        const kind = nodes.kind.value;
        state.editorKind = kind;
        state.showKindPicker = true;
        const coordinate = newCoordinate(kind);
        dirty = false;
        coordinates.unshift(coordinate);
        state.selectedId = coordinate.id;
        setStatus(`已创建${kindLabel(coordinate.kind)}草稿，请保存${kindLabel(coordinate.kind)}。`,
            "success");
        render();
        nodes.name.focus();
        nodes.name.select();
    }

    /** @param {object|null} [folder] @returns {void} */
    function showFolderForm(folder = null) {
        if (busy) return;
        editingFolder = folder?.id || "";
        nodes.folderForm.hidden = false;
        nodes.folderName.value = folder?.name || "";
        nodes.folderForm.querySelector("button[type=submit]").textContent = folder
            ? "保存文件夹" : "创建文件夹";
        document.querySelector("#delete-folder").hidden = !editingFolder;
        nodes.folderName.focus();
    }

    /** @returns {void} */
    function hideFolderForm() {
        nodes.folderForm.hidden = true;
        nodes.folderName.value = "";
    }

    /** @returns {Promise<void>} */
    async function createFolder() {
        const name = nodes.folderName.value.trim();
        if (!name) return;
        await writeAction(async () => {
            const path = "/v1/coordinate-folders" + (editingFolder ? "/" + editingFolder : "");
            await send(path, editingFolder ? "PUT" : "POST", {name}, true);
            await loadDirectory(false);
            hideFolderForm();
        }, "文件夹已保存。");
    }

    /** @param {string} value @returns {number | null} */
    function integerValue(value) {
        if (!String(value).trim()) return null;
        const parsed = Number(value);
        return Number.isInteger(parsed) && parsed >= -2147483648 && parsed <= 2147483647
            ? parsed : null;
    }

    /** @returns {Promise<void>} */
    async function saveCoordinate() {
        const source = selectedCoordinate();
        if (!source || busy) return;
        const coordinate = {...source, name: nodes.name.value.trim()};
        coordinate.kind = state.editorKind || source.kind;
        if (coordinate.kind === "point") {
            const values = [nodes.x.value, nodes.y.value, nodes.z.value].map(integerValue);
            if (values.some(value => value === null)) {
                setStatus("请填写有效的整数坐标。", "error");
                return;
            }
            [coordinate.x, coordinate.y, coordinate.z] = values;
            coordinate.dimension = nodes.dimension.value.trim();
            coordinate.radius = nodes.radius.value.trim() ? integerValue(nodes.radius.value) : null;
            if (coordinate.radius !== null && (coordinate.radius < 0 || coordinate.radius > 128)) {
                setStatus("半径必须在 0 到 128 之间。", "error");
                return;
            }
        } else if (coordinate.kind === "region") {
            coordinate.region = readRegionFields(nodes.region);
            if (!coordinate.region) return;
        } else if (coordinate.kind === "warehouse") {
            coordinate.members = Array.from(nodes.warehouseMembers.querySelectorAll(
                "input:checked"), input => input.value);
        } else {
            coordinate.overallRegion = readRegionFields(nodes.categoryOverall);
            coordinate.bindings = readCategoryBindings();
            coordinate.categoryOptions = readCategoryOptions();
            if (!coordinate.overallRegion || !coordinate.bindings) {
                setStatus("请填写总选区、识别区和储存区的整数坐标。", "error");
                return;
            }
        }
        await writeAction(async () => {
            const body = Model.payload(coordinate);
            const previous = new Set(coordinates.filter(item => item.saved).map(item => item.id));
            const path = Model.route(coordinate) + (source.saved ? "/" + source.id : "");
            const response = await send(path, source.saved ? "PUT" : "POST", body,
                Boolean(source.saved) || coordinate.kind === "warehouse");
            const collection = coordinate.kind === "point" ? response.coordinates
                : coordinate.kind === "region" ? response.regions
                    : coordinate.kind === "category-zone" ? response.categoryZones
                        : response.catalog.warehouses;
            const id = source.saved ? source.id : collection.find(item => !previous.has(item.id))?.id;
            if (!id) throw new Error("资源已保存，请重新读取目录。");
            state.selectedId = id;
            state.showKindPicker = false;
            dirty = false;
            coordinates.splice(coordinates.indexOf(source), 1);
            if (!source.saved && source.folderId) {
                await send("/v1/coordinate-resources", "PUT", {
                    resourceRef: Model.ref({...coordinate, id}), folderId: source.folderId,
                    beforeRef: null
                }, true);
            }
            await loadDirectory();
        }, `${kindLabel(coordinate.kind)}已保存。`);
    }

    /** @returns {void} */
    function duplicateCoordinate() {
        if (!leaveEditor()) return;
        const source = selectedCoordinate();
        if (!source) return;
        const copy = cloneCoordinate(source);
        dirty = false;
        coordinates.splice(coordinates.indexOf(source) + 1, 0, copy);
        state.selectedId = copy.id;
        state.editorKind = copy.kind;
        state.showKindPicker = false;
        setStatus(`已复制${kindLabel(copy.kind)}，请按需修改后保存。`, "success");
        render();
        nodes.name.focus();
        nodes.name.select();
    }

    /** @returns {Promise<void>} */
    async function deleteCoordinate() {
        const coordinate = selectedCoordinate();
        if (!coordinate || busy || !window.confirm(`删除“${coordinate.name}”？`)) return;
        if (!coordinate.saved) {
            coordinates.splice(coordinates.indexOf(coordinate), 1);
            dirty = false;
            state.selectedId = coordinates[0]?.id || "";
            state.editorKind = coordinates[0]?.kind || "point";
            render();
            return;
        }
        await writeAction(async () => {
            await send(Model.route(coordinate) + "/" + coordinate.id, "DELETE", {}, true);
            dirty = false;
            state.selectedId = "";
            await loadDirectory();
        }, `${kindLabel(coordinate.kind)}已删除。`);
    }

    /** @param {string} theme @param {boolean} [persist] @returns {void} */
    function setTheme(theme, persist = false) {
        const selected = theme === "gray" ? "gray" : "green";
        document.documentElement.dataset.theme = selected;
        document.querySelectorAll("[data-theme-choice]").forEach(button => {
            button.setAttribute("aria-pressed", String(button.dataset.themeChoice === selected));
        });
        if (!persist) return;
        try {
            window.localStorage.setItem("stowling.theme", selected);
        } catch {
            // Theme switching remains available when storage is unavailable.
        }
    }

    /** @returns {boolean} */
    function leaveEditor() {
        if (busy) return false;
        const draft = selectedCoordinate();
        if ((dirty || draft && !draft.saved)
                && !window.confirm("当前有未保存的修改，放弃修改并继续？")) return false;
        dirty = false;
        return true;
    }

    /** @returns {void} */
    function filterLibrary() {
        const query = document.querySelector("#coordinate-search").value.trim().toLowerCase();
        for (const item of nodes.list.querySelectorAll(".coordinate-item")) {
            const resource = coordinates.find(value => value.id === item.dataset.coordinateId);
            item.hidden = !(resource.name + " " + coordinateSummary(resource))
                .toLowerCase().includes(query);
        }
        for (const group of nodes.list.querySelectorAll(".coordinate-folder")) {
            const header = group.querySelector(".coordinate-folder-header");
            const matches = header.textContent.toLowerCase().includes(query);
            if (matches && query) {
                group.querySelectorAll(".coordinate-item").forEach(item => { item.hidden = false; });
            }
            group.hidden = Boolean(query) && !matches
                && !group.querySelector(".coordinate-item:not([hidden])");
            if (query && !group.hidden) {
                group.dataset.collapsed = "false";
                const children = group.querySelector(".coordinate-folder-children");
                children.dataset.collapsed = "false";
                children.inert = false;
                header.setAttribute("aria-expanded", "true");
            }
        }
    }

    /** @param {Coordinate} coordinate @returns {void} */
    function populateWarehouseMembers(coordinate) {
        const legend = document.createElement("legend");
        legend.textContent = "储存区成员";
        nodes.warehouseMembers.replaceChildren(legend);
        for (const member of coordinates.filter(item => item.saved
                && ["point", "region"].includes(item.kind))) {
            const label = document.createElement("label");
            label.className = "warehouse-member";
            const input = document.createElement("input");
            input.type = "checkbox";
            input.value = Model.ref(member);
            input.checked = (coordinate.members || []).includes(input.value);
            label.append(input, `${kindLabel(member.kind)} · ${member.name}`);
            nodes.warehouseMembers.append(label);
        }
        if (nodes.warehouseMembers.children.length === 1) {
            const message = document.createElement("p");
            message.textContent = "请先添加坐标点或区域，再组合为储存区。";
            nodes.warehouseMembers.append(message);
        }
    }

    /** @returns {void} */
    function populateCacheOptions() {
        const control = categorySelects.find(item => item.key === "cacheDestination");
        control.select.replaceChildren(new Option("请选择缓存位置", ""));
        control.menu.replaceChildren();
        for (const resource of coordinates.filter(item => item.saved
                && ["point", "region"].includes(item.kind))) {
            const ref = Model.ref(resource);
            const label = `${kindLabel(resource.kind)} · ${resource.name}`;
            control.select.append(new Option(label, ref));
            const option = document.createElement("button");
            option.type = "button";
            option.className = "category-select-option";
            option.dataset.categoryValue = ref;
            option.setAttribute("role", "option");
            option.setAttribute("aria-selected", "false");
            option.tabIndex = -1;
            const text = document.createElement("span");
            text.textContent = label;
            const check = nodes.categoryScopeMode.closest(".category-select-wrap")
                .querySelector(".category-select-option svg").cloneNode(true);
            option.append(text, check);
            control.menu.append(option);
        }
        control.options = Array.from(control.menu.children);
    }

    /** @param {boolean} [refreshEditor] @returns {Promise<void>} */
    async function loadDirectory(refreshEditor = true) {
        const response = await S.request("/v1/coordinate-resources");
        const drafts = coordinates.filter(item => !item.saved);
        const collapsed = new Map(folders.map(folder => [folder.id, folder.collapsed]));
        directory = response;
        const order = response.catalog.order || [];
        const folderValues = response.catalog.folders.map(folder => ({...folder,
            collapsed: collapsed.get(folder.id) || false}));
        const rank = folder => {
            const index = order.indexOf("folder:" + folder.id);
            return index < 0 ? order.length : index;
        };
        folderValues.sort((left, right) => rank(left) - rank(right));
        folders.splice(0, folders.length, ...folderValues);
        coordinates.splice(0, coordinates.length, ...drafts, ...Model.resources(response));
        if (!selectedCoordinate()) {
            const kind = {"#coordinate-points": "point", "#regions": "region",
                "#category-zones": "category-zone", "#warehouses": "warehouse"}[
                    window.location.hash];
            const selected = coordinates.find(item => item.kind === kind) || coordinates[0];
            state.selectedId = selected?.id || "";
            state.editorKind = selected?.kind || kind || "point";
            syncKindMenu(state.editorKind);
        }
        if (refreshEditor) render();
        else renderLibrary();
    }

    /** @param {string} path @param {string} method @param {object} body
     * @param {boolean} [revision] @returns {Promise<object>} */
    async function send(path, method, body, revision = false) {
        const response = await S.request(path, {
            method, headers: {"Content-Type": "application/json"},
            body: JSON.stringify({...body, ...(revision ? {revision: directory.revision} : {})})
        });
        directory = {...directory, revision: response.revision};
        document.dispatchEvent(new CustomEvent("stowling:coordinate-directory-saved", {
            detail: {revision: response.revision}
        }));
        return response;
    }

    /** @param {() => Promise<void>} action @param {string} message
     * @returns {Promise<boolean>} */
    async function writeAction(action, message) {
        if (busy || !S.isConnected()) return false;
        busy = true;
        nodes.form.inert = true;
        nodes.list.inert = true;
        nodes.kindPicker.inert = true;
        nodes.folderForm.inert = true;
        nodes.add.disabled = nodes.addFolder.disabled = true;
        document.querySelector(".coordinate-editor").setAttribute("aria-busy", "true");
        setStatus("正在同步…");
        try {
            await action();
            setStatus(message, "success");
            nodes.footer.textContent = `${coordinates.length} 个资源 · ${folders.length} 个文件夹`;
            return true;
        } catch (error) {
            const message = error.code === "revision_conflict"
                ? "配置已被外部修改，当前草稿已保留。请重新读取后再保存。"
                : error.message || "操作失败，请重试。";
            setStatus(message, "error");
            nodes.footer.textContent = message;
            S.handleError(error, "坐标资源操作失败");
            return false;
        } finally {
            busy = false;
            nodes.form.inert = nodes.list.inert = nodes.folderForm.inert = false;
            nodes.kindPicker.inert = false;
            nodes.add.disabled = nodes.addFolder.disabled = false;
            document.querySelector(".coordinate-editor").setAttribute("aria-busy", "false");
        }
    }

    /** @param {Coordinate} moved @returns {Promise<void>} */
    async function persistPlacement(moved) {
        const siblings = coordinates.filter(item => item.saved && item.folderId === moved.folderId);
        const next = siblings[siblings.indexOf(moved) + 1];
        const success = await writeAction(async () => {
            await send("/v1/coordinate-resources", "PUT", {
                resourceRef: Model.ref(moved), folderId: moved.folderId || null,
                beforeRef: next ? Model.ref(next) : null
            }, true);
            await loadDirectory(false);
        }, "坐标目录已保存。");
        if (!success) {
            const drafts = coordinates.filter(item => !item.saved);
            coordinates.splice(0, coordinates.length, ...drafts, ...Model.resources(directory));
            renderLibrary();
        }
    }

    nodes.form.addEventListener("input", () => { dirty = true; });
    nodes.form.addEventListener("change", () => { dirty = true; });
    window.addEventListener("beforeunload", event => {
        if (!dirty && !coordinates.some(item => !item.saved)) return;
        event.preventDefault();
        event.returnValue = "";
    });
    document.querySelector("#coordinate-search").addEventListener("input", filterLibrary);
    document.querySelector("#refresh-coordinates").addEventListener("click", () => {
        if (!leaveEditor()) return;
        const saved = coordinates.filter(item => item.saved);
        coordinates.splice(0, coordinates.length, ...saved);
        void writeAction(loadDirectory, "坐标资源已同步。");
    });
    document.querySelector("#delete-folder").addEventListener("click", () => {
        if (!editingFolder || busy || !window.confirm("删除文件夹？其中的资源将保留在根目录。")) return;
        void writeAction(async () => {
            await send("/v1/coordinate-folders/" + editingFolder, "DELETE", {}, true);
            hideFolderForm();
            await loadDirectory(false);
        }, "文件夹已删除，资源已保留。");
    });
    window.addEventListener("hashchange", () => {
        const kind = {"#coordinate-points": "point", "#regions": "region",
            "#category-zones": "category-zone", "#warehouses": "warehouse"}[window.location.hash];
        const selected = coordinates.find(item => item.kind === kind);
        if (!kind || !leaveEditor()) return;
        state.selectedId = selected?.id || "";
        state.editorKind = kind;
        syncKindMenu(kind);
        render();
    });

    nodes.kindTrigger.addEventListener("click", () => setKindMenuOpen(!kindMenuOpen));
    nodes.kindTrigger.addEventListener("keydown", event => {
        if (!["ArrowDown", "ArrowUp", "Enter", " "].includes(event.key)) return;
        event.preventDefault();
        setKindMenuOpen(true, true);
    });
    for (const option of nodes.kindOptions) {
        option.addEventListener("click", () => selectCoordinateKind(option.dataset.coordinateKind));
    }
    nodes.kindMenu.addEventListener("keydown", event => {
        if (event.key === "Escape") {
            event.preventDefault();
            setKindMenuOpen(false, true);
            return;
        }
        if (event.key === "Tab") {
            setKindMenuOpen(false);
            return;
        }
        if (!["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) return;
        event.preventDefault();
        let index = nodes.kindOptions.indexOf(document.activeElement);
        if (event.key === "Home") index = 0;
        if (event.key === "End") index = nodes.kindOptions.length - 1;
        if (event.key === "ArrowDown") index = (index + 1) % nodes.kindOptions.length;
        if (event.key === "ArrowUp") {
            index = (index - 1 + nodes.kindOptions.length) % nodes.kindOptions.length;
        }
        nodes.kindOptions[index].focus();
    });
    for (const control of categorySelects) {
        control.trigger.addEventListener("click", () => {
            const open = control.trigger.getAttribute("aria-expanded") === "true";
            setCategoryMenuOpen(control, !open);
        });
        control.trigger.addEventListener("keydown", event => {
            if (!["ArrowDown", "ArrowUp", "Enter", " "].includes(event.key)) return;
            event.preventDefault();
            setCategoryMenuOpen(control, true, true);
        });
        control.menu.addEventListener("click", event => {
            const option = event.target.closest("[data-category-value]");
            if (option) selectCategoryOption(control, option.dataset.categoryValue);
        });
        control.menu.addEventListener("keydown", event => {
            if (event.key === "Escape") {
                event.preventDefault();
                setCategoryMenuOpen(control, false, true);
                return;
            }
            if (event.key === "Tab") {
                setCategoryMenuOpen(control, false);
                return;
            }
            if (event.key === "Enter" || event.key === " ") {
                event.preventDefault();
                const option = /** @type {HTMLButtonElement} */ (document.activeElement);
                selectCategoryOption(control, option.dataset.categoryValue);
                return;
            }
            if (!["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) return;
            event.preventDefault();
            let index = control.options.indexOf(document.activeElement);
            if (event.key === "Home") index = 0;
            if (event.key === "End") index = control.options.length - 1;
            if (event.key === "ArrowDown") index = (index + 1) % control.options.length;
            if (event.key === "ArrowUp") {
                index = (index - 1 + control.options.length) % control.options.length;
            }
            control.options[index]?.focus();
        });
        control.select.addEventListener("change", () => {
            syncCategorySelect(control);
            if ([nodes.categoryStorageMethod, nodes.categoryNoCacheLocation, nodes.categoryScopeMode]
                .includes(control.select)) {
                updateCategoryOptionState();
            }
        });
    }
    nodes.categorySupportExpand.addEventListener("click", () => {
        if (!nodes.categoryIgnoreSupport.checked) return;
        state.categorySupportExpanded = !state.categorySupportExpanded;
        updateCategoryOptionState();
    });
    document.addEventListener("pointerdown", event => {
        if (kindMenuOpen && !nodes.kindWrap.contains(event.target)) setKindMenuOpen(false);
        if (categoryMenuOpen && !categoryMenuOpen.wrap.contains(event.target)) {
            setCategoryMenuOpen(categoryMenuOpen, false);
        }
    });
    document.addEventListener("keydown", event => {
        if (categoryMenuOpen && event.key === "Escape") {
            event.preventDefault();
            setCategoryMenuOpen(categoryMenuOpen, false, true);
        }
    });
    nodes.add.addEventListener("click", addCoordinate);
    nodes.addCategoryBinding.addEventListener("click", addCategoryBinding);
    nodes.categoryIgnoreSupport.addEventListener("change", () => {
        state.categorySupportExpanded = nodes.categoryIgnoreSupport.checked;
        updateCategoryOptionState();
    });
    nodes.addFolder.addEventListener("click", () => showFolderForm());
    nodes.cancelFolder.addEventListener("click", hideFolderForm);
    nodes.folderForm.addEventListener("submit", event => {
        event.preventDefault();
        createFolder();
    });
    nodes.form.addEventListener("submit", event => {
        event.preventDefault();
        saveCoordinate();
    });
    nodes.duplicate.addEventListener("click", duplicateCoordinate);
    nodes.remove.addEventListener("click", deleteCoordinate);
    document.querySelectorAll("[data-theme-choice]").forEach(button => {
        button.addEventListener("click", () => setTheme(button.dataset.themeChoice, true));
    });

    let savedTheme = document.documentElement.dataset.theme;
    try {
        savedTheme = window.localStorage.getItem("stowling.theme") || savedTheme;
    } catch {
        // The standalone preview works without local storage.
    }
    setTheme(savedTheme);
    S.bindCoordinateInputs(nodes.x, nodes.y, nodes.z);
    for (const fields of [nodes.region, nodes.categoryOverall]) {
        for (const corner of [fields.first, fields.second]) {
            S.bindCoordinateInputs(corner.x, corner.y, corner.z);
        }
    }
    render();
    S.onConnected(async () => {
        await writeAction(loadDirectory, "坐标资源已同步。");
    });
    S.mountPage("coordinates");
})();
