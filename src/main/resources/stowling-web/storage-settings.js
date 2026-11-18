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
    temporary.multiple = empty.multiple = true;
    temporary.size = empty.size = 6;
    const fallback = document.createElement("select");
    let revision = "";
    let dirty = false;
    let busy = false;
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
            const [response, points] = await Promise.all([
                S.request("/v1/storage-settings"), S.request("/v1/coordinates")]);
            revision = response.revision;
            const value = response.settings;
            enabled.checked = value.cleanupEnabled;
            for (const [select, ids] of [[temporary, value.temporaryCoordinateIds || []],
                    [empty, value.emptyBoxCoordinateIds || []],
                    [fallback, [value.fallbackCoordinateId]]]) {
                select.replaceChildren();
                if (select === fallback) select.append(new Option("不使用后备位置", ""));
                for (const point of points.coordinates) {
                    const option = new Option(`${point.name} · ${point.x}, ${point.y}, ${point.z}`,
                        point.id);
                    option.selected = ids.includes(point.id);
                    select.append(option);
                }
            }
            save.disabled = false;
            message.textContent = "设施引用基础点位；按 Ctrl 可多选。修改点位后引用自动更新。";
        } catch (error) {
            S.handleError(error, "读取公共设施失败");
        }
    }
    const heading = S.element("div", "panel-heading");
    const link = S.element("a", "text-link", "创建或编辑基础点位 →");
    link.href = "#coordinate-points";
    heading.append(S.element("h2", "", "公共设施"), link);
    const sections = [["统一中断收尾", enabled], ["暂存箱", temporary],
        ["空潜影盒供应箱", empty], ["后备打包位置", fallback]];
    const fields = sections.map(([name, control]) => field(name, control));
    const navigation = S.element("nav", "resource-browser-list");
    navigation.setAttribute("aria-label", "选择公共设施用途");
    const buttons = sections.map(([name], index) => {
        const button = S.element("button", "resource-browser-item", name);
        button.type = "button";
        button.addEventListener("click", () => {
            fields.forEach((row, number) => {row.hidden = number !== index;});
            buttons.forEach((item, number) => {
                item.setAttribute("aria-current", String(number === index));
            });
        });
        return button;
    });
    fields.forEach((row, index) => {row.hidden = index !== 1;});
    const navigation = S.element("nav", "resource-browser-list");
    buttons[1].setAttribute("aria-current", "true");
    navigation.append(...buttons);
    form.append(heading, ...fields, message);
    const actions = S.element("div", "move-form-actions");
    actions.append(refresh, save);
    form.append(actions);
    host.classList.add("resource-browser");
    host.append(navigation, form);
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
                    temporaryCoordinateIds: selected(temporary), emptyBoxCoordinateIds: selected(empty),
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
