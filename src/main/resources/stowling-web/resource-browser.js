(() => {
    "use strict";
    const S = window.Stowling;
    let references = new Map();
    let referencesStatus = "正在读取引用…";
    let loadId = 0;
    const idTypes = {
        coordinateId: "coordinate",
        regionId: "region",
        cacheRegionId: "region",
        categoryZoneId: "category-zone"
    };

    /**
     * @param {Map<string, Set<string>>} index
     * @param {unknown} value
     * @param {string} name
     * @param {string} [key]
     * @returns {void}
     */
    function collect(index, value, name, key = "") {
        if (typeof value === "string") {
            const type = idTypes[key];
            const text = type && !value.includes(":") ? type + ":" + value : value;
            for (const match of text.matchAll(/(?:coordinate|region|category-zone):[\w-]+/g)) {
                const names = index.get(match[0]) || new Set();
                names.add(name);
                index.set(match[0], names);
            }
        } else if (Array.isArray(value)) {
            value.forEach(item => collect(index, item, name, key));
        } else if (value && typeof value === "object") {
            for (const [field, item] of Object.entries(value)) {
                collect(index, item, name, field);
            }
        }
    }
    for (const [id, type] of [["coordinate-list", "coordinate"], ["region-list", "region"],
            ["category-zone-list", "category-zone"]]) {
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
                const names = references.get(type + ":" + key);
                hint.textContent = names?.size ? `正在使用：${Array.from(names).join("、")}`
                    : referencesStatus || "未发现引用此资源的任务或设施";
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
        const requestId = ++loadId;
        try {
            const [tasks, facilities, classifiers, triggers, layout] = await Promise.all([
                S.request("/v1/tasks"),
                S.request("/v1/storage-settings"),
                S.request("/v1/auto-classify-tasks"),
                S.request("/v1/task-triggers"),
                S.request("/v1/category-zones")
            ]);
            if (requestId !== loadId) return;
            const index = new Map();
            tasks.tasks.forEach(task => collect(index, [task.steps, task.cleanupPolicy], task.name));
            classifiers.items.forEach(task => collect(index, task, task.name));
            triggers.triggers.forEach(trigger => collect(index, trigger, trigger.name));
            layout.categoryZones.forEach(zone => collect(index, zone.bindings, zone.name));
            const settings = facilities.settings;
            const facilityRefs = [
                ["coordinate", settings.temporaryCoordinateIds, "公共设施·暂存箱"],
                ["region", settings.temporaryRegionIds, "公共设施·暂存区域"],
                ["coordinate", settings.emptyBoxCoordinateIds, "公共设施·空盒供应"],
                ["coordinate", [settings.fallbackCoordinateId], "公共设施·后备打包位置"],
                ["coordinate", settings.overflowCoordinateIds, "机器人仓库"],
                ["region", settings.overflowRegionIds, "机器人仓库"],
                ["region", [settings.unstackableOverflowRegionId], "公共设施·不可堆叠溢出区域"]
            ];
            for (const [type, ids, name] of facilityRefs) {
                (ids || []).filter(Boolean).forEach(id => collect(index, type + ":" + id, name));
            }
            references = index;
            referencesStatus = "";
        } catch (error) {
            if (requestId !== loadId) return;
            references = new Map();
            referencesStatus = "未能读取引用";
            S.handleError(error, "读取资源引用失败");
        }
        document.dispatchEvent(new Event("stowling:references-loaded"));
    }
    document.addEventListener("stowling:facilities-changed", () => void loadReferences());
    S.onConnected(() => void loadReferences());
})();
