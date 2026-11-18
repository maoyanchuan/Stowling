(() => {
    "use strict";
    const S = window.Stowling;
    const references = new Map();
    /** @param {unknown} value @param {string} name @returns {void} */
    function collect(value, name) {
        if (typeof value === "string") {
            for (const match of value.matchAll(/(?:coordinate|region|category-zone):([\w-]+)/g)) {
                const values = references.get(match[1]) || new Set();
                values.add(name);
                references.set(match[1], values);
            }
        } else if (Array.isArray(value)) {
            value.forEach(item => collect(item, name));
        } else if (value && typeof value === "object") {
            Object.values(value).forEach(item => collect(item, name));
        }
    }
    for (const id of ["coordinate-list", "region-list", "category-zone-list"]) {
        const list = document.getElementById(id);
        if (!list) continue;
        const browser = S.element("div", "resource-browser");
        const navigation = S.element("nav", "resource-browser-list");
        navigation.setAttribute("aria-label", "选择要编辑的资源");
        list.before(browser);
        browser.append(navigation, list);
        let selected = "";
        /** @param {HTMLElement} card @returns {string} */
        function identity(card) {
            return card.dataset.resourceId || card.dataset.zoneId
                || card.querySelector("summary strong")?.textContent || "";
        }
        /** @returns {void} */
        function render() {
            const cards = Array.from(list.children).filter(card => card.tagName === "DETAILS");
            navigation.replaceChildren();
            if (!cards.some(card => identity(card) === selected && !card.hidden)) {
                selected = identity(cards.find(card => !card.hidden) || document.createElement("div"));
            }
            for (const card of cards) {
                const key = identity(card);
                const button = S.element("button", "resource-browser-item",
                    card.querySelector("summary strong")?.textContent || "资源");
                button.type = "button";
                button.hidden = card.hidden;
                button.setAttribute("aria-current", String(key === selected));
                const meta = card.querySelector("summary .resource-summary-meta");
                if (meta) button.append(S.element("small", "", meta.textContent));
                button.addEventListener("click", () => {selected = key; render();});
                navigation.append(button);
                card.classList.toggle("resource-not-selected", key !== selected);
                if (key === selected) card.open = true;
                let hint = card.querySelector(":scope > .resource-reference-hint");
                if (!hint) {
                    hint = S.element("p", "muted compact-help resource-reference-hint");
                    card.append(hint);
                }
                const names = references.get(key);
                hint.textContent = names?.size ? `正在使用：${Array.from(names).join("、")}`
                    : "当前没有已保存任务或公共设施引用此资源";
            }
        }
        new MutationObserver(render).observe(list, {childList: true});
        document.addEventListener("input", event => {
            if (event.target.matches("input[type=search]")) queueMicrotask(render);
        });
        document.addEventListener("stowling:references-loaded", render);
        render();
    }
    /** @returns {Promise<void>} */
    async function loadReferences() {
        try {
            references.clear();
            const [tasks, facilities] = await Promise.all([
                S.request("/v1/tasks"), S.request("/v1/storage-settings")]);
            tasks.tasks.forEach(task => collect(task.steps, task.name));
            const settings = facilities.settings;
            (settings.temporaryRegionIds || []).forEach(id =>
                collect(`region:${id}`, "公共设施·暂存区域"));
            for (const [values, name] of [[settings.temporaryCoordinateIds, "公共设施·暂存箱"],
                    [settings.emptyBoxCoordinateIds, "公共设施·空盒供应"],
                    [[settings.fallbackCoordinateId], "公共设施·后备打包位置"]]) {
                (values || []).filter(Boolean).forEach(id => collect(`coordinate:${id}`, name));
            }
            document.dispatchEvent(new Event("stowling:references-loaded"));
        } catch (error) {
            S.handleError(error, "读取资源引用失败");
        }
    }
    document.addEventListener("stowling:facilities-changed", () => void loadReferences());
    S.onConnected(() => void loadReferences());
})();
