(() => {
    "use strict";

    const Stowling = window.Stowling;
    const Model = window.StowlingCoordinateModel;
    const RegionModel = window.StowlingRegionModel;
    const TAB_EVENT = "stowling:resource-tab-change";
    const TAB_IDS = new Set(["coordinate-points", "regions", "category-zones", "facilities"]);
    const state = {
        revision: "",
        coordinates: [],
        regions: [],
        query: "",
        regionQuery: ""
    };
    let coordinateRequest = 0;
    let regionRequest = 0;
    let coordinateReturnPanel = null;

    const elements = {
        tabs: Array.from(document.querySelectorAll(".page-subnav-tab")),
        panels: Array.from(document.querySelectorAll(".resource-tab-panel")),
        add: document.querySelector("#add-coordinate-button"),
        addRegion: document.querySelector("#add-region-button"),
        cancel: document.querySelector("#cancel-coordinate-button"),
        cancelRegion: document.querySelector("#cancel-region-button"),
        form: document.querySelector("#new-coordinate-form"),
        regionForm: document.querySelector("#new-region-form"),
        name: document.querySelector("#new-coordinate-name"),
        x: document.querySelector("#new-coordinate-x"),
        y: document.querySelector("#new-coordinate-y"),
        z: document.querySelector("#new-coordinate-z"),
        useDimension: document.querySelector("#new-coordinate-use-dimension"),
        dimension: document.querySelector("#new-coordinate-dimension"),
        useRadius: document.querySelector("#new-coordinate-use-radius"),
        radius: document.querySelector("#new-coordinate-radius"),
        regionName: document.querySelector("#new-region-name"),
        regionFirstX: document.querySelector("#new-region-first-x"),
        regionFirstY: document.querySelector("#new-region-first-y"),
        regionFirstZ: document.querySelector("#new-region-first-z"),
        regionSecondX: document.querySelector("#new-region-second-x"),
        regionSecondY: document.querySelector("#new-region-second-y"),
        regionSecondZ: document.querySelector("#new-region-second-z"),
        search: document.querySelector("#resource-search"),
        regionSearch: document.querySelector("#region-resource-search"),
        coordinateCount: document.querySelector("#coordinate-list-count"),
        regionCount: document.querySelector("#region-list-count"),
        list: document.querySelector("#coordinate-list"),
        regionList: document.querySelector("#region-list"),
        errors: document.querySelector("#coordinate-errors"),
        regionErrors: document.querySelector("#region-errors"),
        status: document.querySelector("#coordinate-status"),
        regionStatus: document.querySelector("#region-status"),
        revision: document.querySelector("#coordinate-revision"),
        regionRevision: document.querySelector("#region-revision")
    };

    /**
     * @param {string} revision
     * @returns {void}
     */
    function updateRevision(revision) {
        if (revision) {
            state.revision = revision;
        }
        const label = Stowling.revisionLabel(state.revision);
        elements.revision.textContent = label;
        elements.regionRevision.textContent = label;
    }

    /**
     * @param {HTMLElement} host
     * @returns {boolean}
     */
    function hasDirtyEditor(host) {
        return Boolean(host.querySelector('[data-dirty="true"]'));
    }

    /**
     * @returns {Promise<void>}
     */
    async function loadResources() {
        await Promise.all([
            loadCoordinatePoints(false),
            loadRegions(false)
        ]);
    }

    /**
     * @param {boolean} [preserveDirty]
     * @returns {Promise<void>}
     */
    async function loadCoordinatePoints(preserveDirty = false) {
        const requestId = ++coordinateRequest;
        elements.status.textContent = "正在同步坐标点…";
        try {
            const response = await Stowling.request("/v1/coordinates");
            if (requestId !== coordinateRequest) {
                return;
            }
            applyCoordinateResponse(
                Model.normalizeCoordinates(response),
                preserveDirty
            );
        } catch (error) {
            if (requestId !== coordinateRequest) {
                return;
            }
            elements.status.textContent = "读取坐标点失败";
            showError(error, "读取坐标点失败", elements.errors);
        }
    }

    /**
     * @param {boolean} [preserveDirty]
     * @returns {Promise<void>}
     */
    async function loadRegions(preserveDirty = false) {
        const requestId = ++regionRequest;
        elements.regionStatus.textContent = "正在同步区域…";
        try {
            const response = await Stowling.request("/v1/regions");
            if (requestId !== regionRequest) {
                return;
            }
            applyRegionResponse(response, preserveDirty);
        } catch (error) {
            if (requestId !== regionRequest) {
                return;
            }
            elements.regionStatus.textContent = "读取区域失败";
            showError(error, "读取区域失败", elements.regionErrors);
        }
    }

    /**
     * @param {{revision: string, coordinates: Array<object>}} normalized
     * @param {boolean} [preserveDirty]
     * @returns {void}
     */
    function applyCoordinateResponse(normalized, preserveDirty = false) {
        state.coordinates = normalized.coordinates;
        updateRevision(normalized.revision);
        if (preserveDirty && hasDirtyEditor(elements.list)) {
            elements.status.textContent = "有未保存修改，已同步版本";
            filterResourceList(elements.list);
            return;
        }
        renderCoordinates();
        elements.status.textContent = state.coordinates.length === 0
            ? "尚未添加坐标"
            : "坐标点已同步";
    }

    /**
     * @param {object} response
     * @param {boolean} [preserveDirty]
     * @returns {void}
     */
    function applyRegionResponse(response, preserveDirty = false) {
        const normalized = RegionModel.normalizeRegions(response);
        state.regions = normalized.regions;
        updateRevision(normalized.revision);
        if (preserveDirty && hasDirtyEditor(elements.regionList)) {
            elements.regionStatus.textContent = "有未保存修改，已同步版本";
            filterResourceList(elements.regionList);
            return;
        }
        renderRegions();
        elements.regionStatus.textContent = state.regions.length === 0
            ? "尚未添加区域"
            : "区域已同步";
    }

    /**
     * @param {object} response
     * @returns {void}
     */
    function applyResponse(response) {
        ++coordinateRequest;
        applyCoordinateResponse(Model.normalizeCoordinates(response));
    }

    /**
     * @returns {void}
     */
    function renderCoordinates() {
        elements.list.replaceChildren();
        elements.coordinateCount.textContent = String(state.coordinates.length);
        if (state.coordinates.length === 0) {
            elements.list.append(Stowling.emptyState("尚未添加坐标"));
            return;
        }
        for (const coordinate of state.coordinates) {
            elements.list.append(createCard(coordinate));
        }
        const empty = Stowling.emptyState("没有匹配的坐标");
        empty.classList.add("search-empty");
        elements.list.append(empty);
        filterResourceList(elements.list);
    }


    /**
     * @returns {void}
     */
    function renderRegions() {
        elements.regionList.replaceChildren();
        elements.regionCount.textContent = String(state.regions.length);
        if (state.regions.length === 0) {
            elements.regionList.append(Stowling.emptyState("尚未添加区域"));
            return;
        }
        for (const region of state.regions) {
            elements.regionList.append(createRegionCard(region));
        }
        const empty = Stowling.emptyState("没有匹配的区域");
        empty.classList.add("search-empty");
        elements.regionList.append(empty);
        filterResourceList(elements.regionList);
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
        card.dataset.dirty = "false";
        card.dataset.resourceId = coordinate.id;
        const heading = Stowling.element(
            "summary",
            "editor-card-heading coordinate-point-summary"
        );
        heading.append(resourceIdentity(
            coordinate.name,
            positionLabel(coordinate.x, coordinate.y, coordinate.z)
        ));
        card.dataset.searchText = `${coordinate.name} ${coordinate.x} `
            + `${coordinate.y} ${coordinate.z}`;
        card.append(heading);

        const fields = Stowling.element("div", "resource-editor-body");
        const name = input("text", coordinate.name, 80);
        const x = input("number", coordinate.x);
        const y = input("number", coordinate.y);
        const z = input("number", coordinate.z);
        Stowling.bindCoordinateInputs(x, y, z);
        const useDimension = checkbox(Boolean(coordinate.dimension));
        const dimension = input(
            "text",
            coordinate.dimension || "minecraft:overworld",
            256
        );
        const useRadius = checkbox(coordinate.radius !== null);
        const radius = input("number", coordinate.radius ?? 3);
        radius.min = "0";
        radius.max = "128";
        const options = Stowling.element("details", "resource-options");
        options.open = useDimension.checked || useRadius.checked;
        const optionFields = Stowling.element("div", "resource-option-fields");
        optionFields.append(
            optionalField("指定维度", useDimension, dimension),
            optionalField("指定半径", useRadius, radius)
        );
        options.append(
            Stowling.element("summary", "", "更多选项 · 维度与到达半径"),
            optionFields
        );
        fields.append(field("名称", name), axisGroup("位置", x, y, z), options);

        const actions = Stowling.element("div", "action-row");
        const save = Stowling.element("button", "primary compact", "保存");
        save.type = "button";
        save.addEventListener("click", () => {
            void saveCoordinate(coordinate, {
                name: name.value,
                x,
                y,
                z,
                useDimension,
                dimension,
                useRadius,
                radius
            });
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
        fields.append(actions);
        card.append(fields);
        return card;
    }

    /**
     * @param {object} region
     * @returns {HTMLElement}
     */
    function createRegionCard(region) {
        const card = Stowling.element(
            "details",
            "editor-card coordinate-point-editor"
        );
        card.dataset.dirty = "false";
        card.dataset.resourceId = region.id;
        const heading = Stowling.element(
            "summary",
            "editor-card-heading coordinate-point-summary"
        );
        heading.append(resourceIdentity(
            region.name,
            `${positionLabel(region.firstX, region.firstY, region.firstZ)} → `
                + positionLabel(region.secondX, region.secondY, region.secondZ)
        ));
        card.dataset.searchText = `${region.name} ${region.firstX} ${region.firstY} `
            + `${region.firstZ} ${region.secondX} ${region.secondY} ${region.secondZ}`;
        card.append(heading);

        const fields = Stowling.element("div", "resource-editor-body");
        const name = input("text", region.name, 80);
        const firstX = input("number", region.firstX);
        const firstY = input("number", region.firstY);
        const firstZ = input("number", region.firstZ);
        const secondX = input("number", region.secondX);
        const secondY = input("number", region.secondY);
        const secondZ = input("number", region.secondZ);
        Stowling.bindCoordinateInputs(firstX, firstY, firstZ);
        Stowling.bindCoordinateInputs(secondX, secondY, secondZ);
        const corners = Stowling.element("div", "resource-corners");
        corners.append(
            axisGroup("角点 1", firstX, firstY, firstZ),
            axisGroup("角点 2", secondX, secondY, secondZ)
        );
        fields.append(field("区域名称", name), corners);

        const actions = Stowling.element("div", "action-row");
        const save = Stowling.element("button", "primary compact", "保存");
        save.type = "button";
        save.addEventListener("click", () => {
            void saveRegion(region, {
                name,
                firstX,
                firstY,
                firstZ,
                secondX,
                secondY,
                secondZ
            });
        });
        const remove = Stowling.element(
            "button",
            "ghost compact danger-action",
            "删除"
        );
        remove.type = "button";
        remove.addEventListener("click", () => {
            void deleteRegion(region);
        });
        actions.append(save, remove);
        fields.append(actions);
        card.append(fields);
        return card;
    }

    /**
     * @param {HTMLElement} host
     * @returns {void}
     */
    function filterResourceList(host) {
        const query = host === elements.regionList
            ? state.regionQuery
            : state.query;
        let visible = 0;
        for (const card of host.querySelectorAll(".coordinate-point-editor")) {
            card.hidden = !card.dataset.searchText.toLocaleLowerCase().includes(query);
            visible += card.hidden ? 0 : 1;
        }
        const empty = host.querySelector(".search-empty");
        if (empty) {
            empty.hidden = visible > 0;
        }
    }

    /**
     * @param {number} x
     * @param {number} y
     * @param {number} z
     * @returns {string}
     */
    function positionLabel(x, y, z) {
        return `${x}, ${y}, ${z}`;
    }

    /**
     * @param {string} name
     * @param {string} description
     * @returns {HTMLElement}
     */
    function resourceIdentity(name, description) {
        const identity = Stowling.element("div", "resource-summary-text");
        identity.append(
            Stowling.element("strong", "", name),
            Stowling.element("span", "resource-summary-meta", description)
        );
        return identity;
    }

    /**
     * @param {string} label
     * @param {HTMLInputElement} x
     * @param {HTMLInputElement} y
     * @param {HTMLInputElement} z
     * @returns {HTMLElement}
     */
    function axisGroup(label, x, y, z) {
        const group = Stowling.element("fieldset", "resource-axis-group");
        const row = Stowling.element("div", "coordinate-axis-row");
        row.append(field("X", x), field("Y", y), field("Z", z));
        group.append(Stowling.element("legend", "", label), row);
        return group;
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
                        z: Number(values.z.value),
                        dimension: values.useDimension.checked
                            ? values.dimension.value.trim()
                            : null,
                        radius: values.useRadius.checked
                            ? Number(values.radius.value)
                            : null
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
     * @param {object} region
     * @param {object} values
     * @returns {Promise<void>}
     */
    async function saveRegion(region, values) {
        try {
            const response = await Stowling.request(
                `/v1/regions/${encodeURIComponent(region.id)}`,
                {
                    method: "PUT",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({
                        revision: state.revision,
                        name: values.name.value.trim(),
                        firstX: Number(values.firstX.value),
                        firstY: Number(values.firstY.value),
                        firstZ: Number(values.firstZ.value),
                        secondX: Number(values.secondX.value),
                        secondY: Number(values.secondY.value),
                        secondZ: Number(values.secondZ.value)
                    })
                }
            );
            ++regionRequest;
            applyRegionResponse(response);
            clearErrors(elements.regionErrors);
            Stowling.showToast("区域已保存");
        } catch (error) {
            showError(error, "保存区域失败", elements.regionErrors);
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
     * @param {object} region
     * @returns {Promise<void>}
     */
    async function deleteRegion(region) {
        if (!window.confirm(`删除区域“${region.name}”？`)) {
            return;
        }
        try {
            const response = await Stowling.request(
                `/v1/regions/${encodeURIComponent(region.id)}`,
                {
                    method: "DELETE",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ revision: state.revision })
                }
            );
            ++regionRequest;
            applyRegionResponse(response);
            clearErrors(elements.regionErrors);
            Stowling.showToast("区域已删除");
        } catch (error) {
            showError(error, "删除区域失败", elements.regionErrors);
        }
    }

    /**
     * @returns {Promise<void>}
     */
    async function createCoordinate() {
        try {
            const knownIds = new Set(state.coordinates.map(point => point.id));
            const requestedName = elements.name.value.trim();
            const response = await Stowling.request("/v1/coordinates", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    name: elements.name.value.trim(),
                    x: Number(elements.x.value),
                    y: Number(elements.y.value),
                    z: Number(elements.z.value),
                    dimension: elements.useDimension.checked
                        ? elements.dimension.value.trim()
                        : null,
                    radius: elements.useRadius.checked
                        ? Number(elements.radius.value)
                        : null
                })
            });
            applyResponse(response);
            elements.form.reset();
            elements.x.value = "0";
            elements.y.value = "64";
            elements.z.value = "0";
            elements.dimension.value = "minecraft:overworld";
            elements.radius.value = "3";
            syncOptionalField(elements.useDimension, elements.dimension);
            syncOptionalField(elements.useRadius, elements.radius);
            elements.form.hidden = true;
            elements.add.focus();
            clearErrors();
            Stowling.showToast("坐标已添加");
            const created = response.coordinates.find(point => !knownIds.has(point.id)
                && point.name === requestedName);
            if (coordinateReturnPanel && created) {
                const panel = coordinateReturnPanel;
                coordinateReturnPanel = null;
                document.dispatchEvent(new CustomEvent("stowling:coordinate-created", {
                    detail: {coordinate: created}
                }));
                window.location.hash = panel;
                activateTab(panel);
            }
        } catch (error) {
            showError(error, "添加坐标失败");
        }
    }

    /**
     * @returns {Promise<void>}
     */
    async function createRegion() {
        try {
            const response = await Stowling.request("/v1/regions", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    name: elements.regionName.value.trim(),
                    firstX: Number(elements.regionFirstX.value),
                    firstY: Number(elements.regionFirstY.value),
                    firstZ: Number(elements.regionFirstZ.value),
                    secondX: Number(elements.regionSecondX.value),
                    secondY: Number(elements.regionSecondY.value),
                    secondZ: Number(elements.regionSecondZ.value)
                })
            });
            applyRegionResponse(response);
            elements.regionForm.reset();
            elements.regionFirstX.value = "0";
            elements.regionFirstY.value = "64";
            elements.regionFirstZ.value = "0";
            elements.regionSecondX.value = "0";
            elements.regionSecondY.value = "64";
            elements.regionSecondZ.value = "0";
            elements.regionForm.hidden = true;
            elements.addRegion.focus();
            clearErrors(elements.regionErrors);
            Stowling.showToast("区域已添加");
        } catch (error) {
            showError(error, "添加区域失败", elements.regionErrors);
        }
    }

    /**
     * @returns {void}
     */
    function showCoordinateForm() {
        elements.form.hidden = false;
        elements.regionForm.hidden = true;
        clearErrors();
        elements.name.focus();
    }

    /**
     * @returns {void}
     */
    function showRegionForm() {
        elements.form.hidden = true;
        elements.regionForm.hidden = false;
        clearErrors(elements.regionErrors);
        elements.regionName.focus();
    }

    /**
     * @returns {void}
     */
    function hideCreateForms() {
        elements.form.hidden = true;
        elements.regionForm.hidden = true;
        clearErrors(elements.errors);
        clearErrors(elements.regionErrors);
        if (coordinateReturnPanel) {
            const panel = coordinateReturnPanel;
            coordinateReturnPanel = null;
            document.dispatchEvent(new Event("stowling:coordinate-create-cancelled"));
            window.location.hash = panel;
            activateTab(panel);
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
     * @param {boolean} checked
     * @returns {HTMLInputElement}
     */
    function checkbox(checked) {
        const control = document.createElement("input");
        control.type = "checkbox";
        control.checked = checked;
        return control;
    }

    /**
     * @param {string} label
     * @param {HTMLInputElement} toggle
     * @param {HTMLInputElement} control
     * @returns {HTMLElement}
     */
    function optionalField(label, toggle, control) {
        window.StowlingSettings.switchControl(toggle, label);
        const wrapper = Stowling.element(
            "div",
            "editor-field coordinate-optional-field"
        );
        const switchLabel = Stowling.element(
            "label",
            "coordinate-switch"
        );
        switchLabel.append(toggle, Stowling.element("span", "", label));
        wrapper.append(switchLabel, control);
        syncOptionalField(toggle, control);
        toggle.addEventListener("change", () => {
            syncOptionalField(toggle, control);
        });
        return wrapper;
    }

    /**
     * @param {HTMLInputElement} toggle
     * @param {HTMLInputElement} control
     * @returns {void}
     */
    function syncOptionalField(toggle, control) {
        control.disabled = !toggle.checked;
        control.hidden = !toggle.checked;
        control.required = toggle.checked;
    }

    /**
     * @param {string} label
     * @param {HTMLElement} control
     * @returns {HTMLElement}
     */
    function field(label, control) {
        const wrapper = Stowling.element("div", "editor-field");
        control.setAttribute("aria-label", label);
        wrapper.append(Stowling.element("label", "", label), control);
        return wrapper;
    }

    /**
     * @param {unknown} error
     * @param {string} context
     * @param {HTMLElement} [host]
     * @returns {void}
     */
    function showError(error, context, host = elements.errors) {
        const taskNames = Array.isArray(error?.details?.taskNames)
            ? error.details.taskNames.filter(Boolean)
            : [];
        const message = taskNames.length > 0
            ? `该坐标仍被任务“${taskNames.join("、")}”引用，请先修改或删除这些任务。`
            : error instanceof Error
                ? error.message
                : context;
        host.replaceChildren(Stowling.element("p", "", message));
        Stowling.handleError(error, context);
    }

    /**
     * @param {HTMLElement} [host]
     * @returns {void}
     */
    function clearErrors(host = elements.errors) {
        host.replaceChildren();
    }

    /**
     * @param {string} value
     * @returns {string}
     */
    function normalizeTabId(value) {
        const candidate = String(value || "").replace(/^#/, "");
        return TAB_IDS.has(candidate) ? candidate : "category-zones";
    }

    /**
     * @param {string} panelId
     * @param {boolean} [refresh]
     * @returns {void}
     */
    function activateTab(panelId, refresh = true) {
        const activeId = normalizeTabId(panelId);
        document.body.dataset.coordinateTab = activeId;
        for (const tab of elements.tabs) {
            const selected = tab.dataset.panel === activeId;
            tab.setAttribute("aria-selected", String(selected));
            tab.tabIndex = selected ? 0 : -1;
        }
        for (const panel of elements.panels) {
            const selected = panel.id === activeId;
            panel.hidden = !selected;
            panel.setAttribute("aria-hidden", String(!selected));
        }
        if (refresh && Stowling.isConnected()) {
            document.dispatchEvent(new CustomEvent(TAB_EVENT, {
                detail: { panelId: activeId }
            }));
        }
    }

    /**
     * @returns {void}
     */
    function activateHashTab() {
        activateTab(normalizeTabId(window.location.hash));
    }

    /**
     * @param {KeyboardEvent} event
     * @returns {void}
     */
    function handleTabKeydown(event) {
        if (!(event.target instanceof Element)) {
            return;
        }
        const tab = event.target.closest(".page-subnav-tab");
        if (!tab) {
            return;
        }
        const current = elements.tabs.indexOf(tab);
        if (current < 0) {
            return;
        }
        let next = current;
        if (event.key === "ArrowRight") {
            next = (current + 1) % elements.tabs.length;
        } else if (event.key === "ArrowLeft") {
            next = (current - 1 + elements.tabs.length) % elements.tabs.length;
        } else if (event.key === "Home") {
            next = 0;
        } else if (event.key === "End") {
            next = elements.tabs.length - 1;
        } else {
            return;
        }
        event.preventDefault();
        const target = elements.tabs[next];
        window.location.hash = String(target.dataset.panel);
        target.focus();
    }

    /**
     * @param {Event} event
     * @returns {void}
     */
    function markDirtyEditor(event) {
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
    function refreshResourcePanel(event) {
        const detail = event instanceof CustomEvent ? event.detail : null;
        const panelId = String(detail?.panelId || "");
        if (panelId === "coordinate-points") {
            void loadCoordinatePoints(true);
        } else if (panelId === "regions") {
            void loadRegions(true);
        }
    }

    elements.search.addEventListener("input", () => {
        state.query = elements.search.value.trim().toLocaleLowerCase();
        filterResourceList(elements.list);
    });
    elements.regionSearch.addEventListener("input", () => {
        state.regionQuery = elements.regionSearch.value.trim().toLocaleLowerCase();
        filterResourceList(elements.regionList);
    });
    elements.list.addEventListener("input", markDirtyEditor);
    elements.list.addEventListener("change", markDirtyEditor);
    elements.regionList.addEventListener("input", markDirtyEditor);
    elements.regionList.addEventListener("change", markDirtyEditor);
    elements.tabs.forEach((tab) => {
        tab.addEventListener("keydown", handleTabKeydown);
    });
    document.addEventListener(TAB_EVENT, refreshResourcePanel);
    window.addEventListener("hashchange", activateHashTab);
    activateHashTab();
    document.addEventListener("stowling:request-coordinate-create", event => {
        coordinateReturnPanel = event.detail.returnPanel;
        window.location.hash = "coordinate-points";
        activateTab("coordinate-points");
        showCoordinateForm();
        const base = event.detail.name || "坐标点";
        const names = new Set(state.coordinates.map(point => point.name));
        let candidate = base;
        for (let suffix = 2; names.has(candidate); suffix++) {
            candidate = `${base} ${suffix}`;
        }
        elements.name.value = candidate;
    });
    elements.add.addEventListener("click", showCoordinateForm);
    elements.addRegion.addEventListener("click", showRegionForm);
    elements.cancel.addEventListener("click", hideCreateForms);
    elements.cancelRegion.addEventListener("click", hideCreateForms);
    elements.useDimension.addEventListener("change", () => {
        syncOptionalField(elements.useDimension, elements.dimension);
    });
    elements.useRadius.addEventListener("change", () => {
        syncOptionalField(elements.useRadius, elements.radius);
    });
    elements.form.addEventListener("submit", (event) => {
        event.preventDefault();
        void createCoordinate();
    });
    elements.regionForm.addEventListener("submit", (event) => {
        event.preventDefault();
        void createRegion();
    });
    Stowling.bindCoordinateInputs(
        elements.x,
        elements.y,
        elements.z
    );
    Stowling.bindCoordinateInputs(
        elements.regionFirstX,
        elements.regionFirstY,
        elements.regionFirstZ
    );
    Stowling.bindCoordinateInputs(
        elements.regionSecondX,
        elements.regionSecondY,
        elements.regionSecondZ
    );
    syncOptionalField(elements.useDimension, elements.dimension);
    syncOptionalField(elements.useRadius, elements.radius);
    Stowling.onConnected(() => loadResources());
    Stowling.mountPage("coordinates");
})();
