(() => {
    "use strict";
    const S = window.Stowling;
    const host = document.querySelector("#facility-editor");
    if (!host) return;
    const form = document.createElement("form");
    const enabled = document.createElement("input");
    enabled.type = "checkbox";
    enabled.className = "settings-switch";
    const temporary = document.createElement("select");
    const empty = document.createElement("select");
    const overflow = document.createElement("select");
    const warehouseEnabled = document.createElement("input");
    warehouseEnabled.type = "checkbox";
    warehouseEnabled.className = "settings-switch";
    const unstackableOverflow = document.createElement("select");
    temporary.multiple = empty.multiple = overflow.multiple = true;
    temporary.size = empty.size = overflow.size = 6;
    const fallback = document.createElement("select");
    let revision = "";
    let storedSettings = {};
    let overflowOrder = [];
    let robotWarehouseEnabled;
    let dirty = false;
    let busy = false;
    let activeSection = 4;
    let pendingSection = null;
    const message = S.element("p", "field-message", "等待连接");
    const save = S.element("button", "secondary", "保存公共设施");
    save.type = "submit";
    save.disabled = true;
    const refresh = S.element("button", "ghost", "重新读取");
    refresh.type = "button";
    /** @param {string} label @param {HTMLElement} control @returns {HTMLElement} */
    function field(label, control) {
        const wrapper = document.createElement("label");
        wrapper.className = "move-field";
        wrapper.append(S.element("span", "", label), control);
        return wrapper;
    }
    /** @param {HTMLSelectElement} select @returns {Array<string>} */
    function selected(select) {
        return Array.from(select.selectedOptions, option => option.value);
    }
    /** @returns {Promise<void>} */
    async function load() {
        if (dirty || busy) return;
        try {
            const [response, points, regions] = await Promise.all([
                S.request("/v1/storage-settings"), S.request("/v1/coordinates"),
                S.request("/v1/regions")]);
            if (dirty || busy) return;
            revision = response.revision;
            const value = response.settings;
            storedSettings = value;
            overflowOrder = [
                ...(value.overflowCoordinateIds || []).map(id => "coordinate:" + id),
                ...(value.overflowRegionIds || []).map(id => "region:" + id)
            ];
            robotWarehouseEnabled = value.robotWarehouseEnabled;
            enabled.checked = value.cleanupEnabled;
            unstackableOverflow.replaceChildren(new Option("未配置", ""));
            for (const [select, ids] of [[temporary, value.temporaryCoordinateIds || []],
                    [overflow, value.overflowCoordinateIds || []],
                    [empty, value.emptyBoxCoordinateIds || []],
                    [fallback, [value.fallbackCoordinateId]]]) {
                select.replaceChildren();
                if (select === fallback) select.append(new Option("不使用后备位置", ""));
                for (const point of points.coordinates) {
                    const regionCapable = select === temporary || select === overflow;
                    const option = new Option(`${regionCapable ? "[点位] " : ""}`
                        + `${point.name} · ${point.x}, ${point.y}, ${point.z}`,
                        regionCapable ? `coordinate:${point.id}` : point.id);
                    option.selected = ids.includes(point.id);
                    select.append(option);
                }
            }
            for (const region of regions.regions) {
                unstackableOverflow.append(new Option(region.name, region.id));
                for (const [select, ids] of [[temporary, value.temporaryRegionIds || []],
                        [overflow, value.overflowRegionIds || []]]) {
                    const option = new Option(`[区域] ${region.name} · `
                        + `${region.firstX}, ${region.firstY}, ${region.firstZ} → `
                        + `${region.secondX}, ${region.secondY}, ${region.secondZ}`,
                        `region:${region.id}`);
                    option.selected = ids.includes(region.id);
                    select.append(option);
                }
            }
            unstackableOverflow.value = value.unstackableOverflowRegionId || "";
            warehouseEnabled.checked = robotWarehouseEnabled !== false
                && selected(overflow).length > 0;
            save.disabled = false;
            message.textContent = "";
        } catch (error) {
            message.textContent = "读取公共设施失败，请点击重新读取。";
            S.handleError(error, "读取公共设施失败");
        }
    }
    const heading = S.element("div", "panel-heading");
    const link = S.element("a", "text-link", "创建或编辑基础点位 →");
    link.href = "#coordinate-points";
    const regionLink = S.element("a", "text-link", "创建或编辑基础区域 →");
    regionLink.href = "#regions";
    const add = S.element("button", "secondary", "＋ 添加坐标点");
    add.type = "button";
    add.id = "add-facility-coordinate";
    add.addEventListener("click", () => {
        pendingSection = activeSection;
        document.dispatchEvent(new CustomEvent("stowling:request-coordinate-create", {
            detail: {
                returnPanel: pendingSection === 4 ? "robot-warehouse" : "facilities",
                name: ["", "暂存箱", "空盒供应箱", "后备打包位置", "溢出箱"][pendingSection]
            }
        }));
    });
    heading.append(S.element("h2", "", "公共设施"), link, regionLink, add);
    const fields = [
        field("旧任务收尾", enabled),
        field("旧任务暂存", temporary),
        field("空潜影盒供应箱", empty),
        field("后备打包位置", fallback),
        field("可用的存放点位和区域", overflow),
        field("不可堆叠溢出区域", unstackableOverflow)
    ];
    const warehouse = S.element("div", "facility-warehouse");
    warehouse.append(field("启用机器人仓库", warehouseEnabled), fields[4],
        S.element("p", "muted compact-help", "自动分类任务可从这里已配置的位置中选择。"));
    const legacy = S.element("details", "facility-legacy");
    legacy.append(S.element("summary", "", "兼容设置"), fields[0], fields[1], fields[5]);
    const sections = [
        {index: 4, name: "机器人仓库", content: warehouse},
        {index: 2, name: "空潜影盒供应箱", content: fields[2]},
        {index: 3, name: "后备打包位置", content: fields[3]},
        {index: 1, name: "兼容设置", content: legacy}
    ];
    const navigation = S.element("nav", "resource-browser-list");
    navigation.setAttribute("aria-label", "选择公共设施用途");
    const buttons = sections.map(({index, name}) => {
        const button = S.element("button", "resource-browser-item", name);
        button.type = "button";
        if (index === 4) button.id = "robot-warehouse";
        button.addEventListener("click", () => selectSection(index));
        return button;
    });

    /** @param {number} index @returns {void} */
    function selectSection(index) {
        activeSection = index;
        if (index === 1) legacy.open = true;
        sections.forEach((section, position) => {
            section.content.hidden = section.index !== index;
            buttons[position].setAttribute("aria-current", String(section.index === index));
        });
    }

    selectSection(activeSection);
    navigation.append(...buttons);
    form.append(heading, ...sections.map(({content}) => content), message);
    const actions = S.element("div", "move-form-actions");
    actions.append(refresh, save);
    form.append(actions);
    host.classList.add("resource-browser");
    host.append(navigation, form);
    document.addEventListener("stowling:coordinate-created", event => {
        if (pendingSection === null) return;
        const point = event.detail.coordinate;
        for (const [index, select] of [[1, temporary], [2, empty], [3, fallback], [4, overflow]]) {
            const regionCapable = index === 1 || index === 4;
            const option = new Option(`${regionCapable ? "[点位] " : ""}${point.name} · `
                + `${point.x}, ${point.y}, ${point.z}`,
                regionCapable ? `coordinate:${point.id}` : point.id);
            option.selected = index === pendingSection;
            select.append(option);
        }
        dirty = true;
        if (robotWarehouseEnabled == null) warehouseEnabled.checked = selected(overflow).length > 0;
        const select = [null, temporary, empty, fallback, overflow][pendingSection];
        pendingSection = null;
        select.focus();
        message.textContent = "坐标点已创建并选中，请保存公共设施以应用引用。";
    });
    document.addEventListener("stowling:coordinate-create-cancelled", () => {pendingSection = null;});
    warehouseEnabled.addEventListener("input", () => {
        robotWarehouseEnabled = warehouseEnabled.checked;
    });
    overflow.addEventListener("input", () => {
        if (robotWarehouseEnabled == null) warehouseEnabled.checked = selected(overflow).length > 0;
    });
    form.addEventListener("input", () => {dirty = true;});
    refresh.addEventListener("click", () => {
        if (dirty && !window.confirm("重新读取会丢弃未保存的公共设施配置，是否继续？")) return;
        dirty = false;
        void load();
    });
    form.addEventListener("submit", async event => {
        event.preventDefault();
        if (busy) return;
        const chosen = new Set(selected(overflow));
        const overflowLocations = Array.from(new Set([...overflowOrder, ...chosen]))
            .filter(ref => chosen.has(ref));
        if (robotWarehouseEnabled === true && overflowLocations.length === 0) {
            message.textContent = "启用机器人仓库前请添加至少一个存放位置。";
            selectSection(4);
            overflow.focus();
            return;
        }
        busy = true;
        save.disabled = true;
        try {
            const response = await S.request("/v1/storage-settings", {
                method: "PUT", headers: {"Content-Type": "application/json"},
                body: JSON.stringify({revision, settings: {
                    ...storedSettings,
                    cleanupEnabled: enabled.checked,
                    temporaryCoordinateIds: selected(temporary)
                        .filter(value => value.startsWith("coordinate:"))
                        .map(value => value.slice("coordinate:".length)),
                    temporaryRegionIds: selected(temporary)
                        .filter(value => value.startsWith("region:"))
                        .map(value => value.slice("region:".length)),
                    emptyBoxCoordinateIds: selected(empty),
                    overflowCoordinateIds: overflowLocations
                        .filter(value => value.startsWith("coordinate:"))
                        .map(value => value.slice("coordinate:".length)),
                    overflowRegionIds: overflowLocations
                        .filter(value => value.startsWith("region:"))
                        .map(value => value.slice("region:".length)),
                    robotWarehouseEnabled,
                    unstackableOverflowRegionId: unstackableOverflow.value || null,
                    fallbackCoordinateId: fallback.value || null
                }})});
            revision = response.revision;
            overflowOrder = overflowLocations;
            dirty = false;
            message.textContent = "公共设施已保存";
            S.showToast(message.textContent);
            document.dispatchEvent(new Event("stowling:facilities-changed"));
        } catch (error) {
            message.textContent = error.message || "保存失败";
            S.handleError(error, "保存公共设施失败");
        } finally {
            busy = false;
            save.disabled = false;
        }
    });
    document.addEventListener("stowling:resource-tab-change", event => {
        if (event.detail.panelId !== "facilities") return;
        if (window.location.hash === "#robot-warehouse") selectSection(4);
        void load();
    });
    S.onConnected(() => void load());
})();
