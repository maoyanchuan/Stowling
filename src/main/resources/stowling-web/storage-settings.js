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
    temporary.multiple = empty.multiple = overflow.multiple = true;
    temporary.size = empty.size = overflow.size = 6;
    const fallback = document.createElement("select");
    let revision = "";
    let dirty = false;
    let busy = false;
    let activeSection = 1;
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
            enabled.checked = value.cleanupEnabled;
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
            save.disabled = false;
            message.textContent = "任务的中断存放位置请在任务内选择。这里的暂存仅兼容旧任务；溢出支持点位或区域。";
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
        pendingSection = activeSection === 0 ? 1 : activeSection;
        document.dispatchEvent(new CustomEvent("stowling:request-coordinate-create", {
            detail: {returnPanel: "facilities", name: ["", "暂存箱", "空盒供应箱",
                "后备打包位置", "溢出箱"][pendingSection]}
        }));
    });
    heading.append(S.element("h2", "", "公共设施"), link, regionLink, add);
    const sections = [["旧任务收尾（兼容）", enabled], ["旧任务暂存（兼容）", temporary],
        ["空潜影盒供应箱", empty], ["后备打包位置", fallback], ["溢出箱", overflow]];
    const fields = sections.map(([name, control]) => field(name, control));
    const navigation = S.element("nav", "resource-browser-list");
    navigation.setAttribute("aria-label", "选择公共设施用途");
    const buttons = sections.map(([name], index) => {
        const button = S.element("button", "resource-browser-item", name);
        button.type = "button";
        button.addEventListener("click", () => {
            activeSection = index;
            fields.forEach((row, number) => {row.hidden = number !== index;});
            buttons.forEach((item, number) => {
                item.setAttribute("aria-current", String(number === index));
            });
        });
        return button;
    });
    fields.forEach((row, index) => {row.hidden = index !== 1;});
    buttons[1].setAttribute("aria-current", "true");
    navigation.append(...buttons);
    form.append(heading, ...fields, message);
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
        const select = [null, temporary, empty, fallback, overflow][pendingSection];
        pendingSection = null;
        select.focus();
        message.textContent = "坐标点已创建并选中，请保存公共设施以应用引用。";
    });
    document.addEventListener("stowling:coordinate-create-cancelled", () => {pendingSection = null;});
    form.addEventListener("input", () => {dirty = true;});
    refresh.addEventListener("click", () => {
        if (dirty && !window.confirm("重新读取会丢弃未保存的公共设施配置，是否继续？")) return;
        dirty = false;
        void load();
    });
    form.addEventListener("submit", async event => {
        event.preventDefault();
        if (busy) return;
        busy = true;
        save.disabled = true;
        try {
            const response = await S.request("/v1/storage-settings", {
                method: "PUT", headers: {"Content-Type": "application/json"},
                body: JSON.stringify({revision, settings: {
                    cleanupEnabled: enabled.checked, temporaryContainers: [], emptyBoxContainers: [],
                    temporaryCoordinateIds: selected(temporary)
                        .filter(value => value.startsWith("coordinate:"))
                        .map(value => value.slice("coordinate:".length)),
                    temporaryRegionIds: selected(temporary)
                        .filter(value => value.startsWith("region:"))
                        .map(value => value.slice("region:".length)),
                    emptyBoxCoordinateIds: selected(empty),
                    overflowCoordinateIds: selected(overflow)
                        .filter(value => value.startsWith("coordinate:"))
                        .map(value => value.slice("coordinate:".length)),
                    overflowRegionIds: selected(overflow)
                        .filter(value => value.startsWith("region:"))
                        .map(value => value.slice("region:".length)),
                    fallbackCoordinateId: fallback.value || null
                }})});
            revision = response.revision;
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
        if (event.detail.panelId === "facilities") void load();
    });
    S.onConnected(() => void load());
})();
