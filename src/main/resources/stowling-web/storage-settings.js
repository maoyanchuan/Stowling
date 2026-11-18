(() => {
    "use strict";

    const Stowling = window.Stowling;
    const host = document.querySelector(".task-workbench") || document.querySelector("#workspace");
    if (!host) return;
    const root = document.createElement("details");
    root.className = "panel";
    const summary = Stowling.element("summary", "settings-row", "全局储存与中断收尾");
    const form = document.createElement("form");
    const enabled = document.createElement("input");
    enabled.type = "checkbox";
    enabled.className = "settings-switch";
    const temporary = document.createElement("textarea");
    const empty = document.createElement("textarea");
    const fallback = document.createElement("input");
    temporary.rows = empty.rows = 3;
    temporary.placeholder = empty.placeholder = "每行一个箱子坐标，例如：13 -40 32";
    fallback.placeholder = "可选：潜影盒放置坐标，例如：15 -40 35";
    let revision = "";
    let dirty = false;
    let busy = false;
    const message = Stowling.element("p", "field-message", "等待连接");
    const save = Stowling.element("button", "secondary", "保存全局配置");
    save.type = "submit";
    save.disabled = true;
    const refresh = Stowling.element("button", "ghost", "重新读取");
    refresh.type = "button";

    /** @param {string} label @param {HTMLElement} control @returns {HTMLElement} */
    function field(label, control) {
        const wrapper = document.createElement("label");
        wrapper.className = "move-field";
        wrapper.append(Stowling.element("span", "", label), control);
        return wrapper;
    }

    /** @param {string} value @returns {Array<object>} */
    function positions(value) {
        return value.trim() ? value.trim().split(/\n+/).map(line => {
            const values = line.trim().split(/[\s,，]+/).map(Number);
            if (values.length !== 3 || values.some(number => !Number.isInteger(number)
                    || number < -2147483648 || number > 2147483647)) {
                throw new Error("每行需要三个有效整数坐标");
            }
            return {x: values[0], y: values[1], z: values[2]};
        }) : [];
    }

    /** @param {Array<object>} values @returns {string} */
    function format(values) {
        return (values || []).map(value => `${value.x} ${value.y} ${value.z}`).join("\n");
    }

    /** @returns {Promise<void>} */
    async function load() {
        if (dirty || busy) return;
        try {
            const response = await Stowling.request("/v1/storage-settings");
            revision = response.revision;
            const value = response.settings;
            enabled.checked = value.cleanupEnabled;
            temporary.value = format(value.temporaryContainers);
            empty.value = format(value.emptyBoxContainers);
            fallback.value = format(value.fallbackPackingPosition ? [value.fallbackPackingPosition] : []);
            save.disabled = false;
            message.textContent = "配置由所有任务共用；暂存箱与空盒供应箱请分开设置。";
        } catch (error) {
            Stowling.handleError(error, "读取全局储存配置失败");
        }
    }

    form.append(field("统一回收任务物品与临时潜影盒", enabled),
        field("暂存箱（优先使用附近有空间的箱子）", temporary),
        field("空潜影盒供应箱", empty), field("后备打包位置", fallback), message);
    const actions = Stowling.element("div", "move-form-actions");
    actions.append(refresh, save);
    form.append(actions);
    root.append(summary, form);
    host.append(root);
    form.addEventListener("input", () => {dirty = true;});
    refresh.addEventListener("click", () => {
        if (dirty && !window.confirm("重新读取会丢弃未保存的全局配置，是否继续？")) return;
        dirty = false;
        void load();
    });
    form.addEventListener("submit", async event => {
        event.preventDefault();
        if (busy) return;
        busy = true;
        save.disabled = true;
        try {
            const points = positions(fallback.value);
            if (points.length > 1) throw new Error("后备打包位置只能填写一个坐标");
            const response = await Stowling.request("/v1/storage-settings", {
                method: "PUT", headers: {"Content-Type": "application/json"},
                body: JSON.stringify({revision, settings: {
                    cleanupEnabled: enabled.checked,
                    temporaryContainers: positions(temporary.value),
                    emptyBoxContainers: positions(empty.value),
                    fallbackPackingPosition: points[0] || null
                }})
            });
            revision = response.revision;
            dirty = false;
            message.textContent = "全局储存配置已保存";
            Stowling.showToast("全局储存配置已保存");
        } catch (error) {
            message.textContent = error.message || "保存失败";
            Stowling.handleError(error, "保存全局储存配置失败");
        } finally {
            busy = false;
            save.disabled = false;
        }
    });
    Stowling.onConnected(() => void load());
})();
