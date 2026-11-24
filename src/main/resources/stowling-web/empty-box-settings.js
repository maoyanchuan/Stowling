(() => {
    "use strict";

    /** @param {HTMLElement} host @returns {object} */
    function create(host) {
        const S = window.Stowling;
        const Settings = window.StowlingSettings;
        const enabled = document.createElement("input");
        enabled.id = "auto-empty-warehouse-enabled";
        const location = document.createElement("select");
        location.id = "auto-empty-warehouse-location";
        location.required = true;
        location.setAttribute("aria-label", "空盒仓库位置");
        location.dataset.optionDescription = "分类时取用空盒，并归还多余空盒";
        const locationRow = S.element("label", "move-field");
        locationRow.append(S.element("span", "", "空盒仓库位置"), location);
        const retain = document.createElement("input");
        retain.id = "auto-empty-cache-retain";
        const limit = document.createElement("input");
        limit.id = "auto-empty-cache-limit";
        limit.type = "number";
        limit.min = "0";
        limit.max = "3456";
        limit.step = "1";
        limit.required = true;
        const limitRow = S.element("label", "move-field");
        limitRow.append(S.element("span", "", "缓存保留数量（个）"), limit);
        const limitBody = S.element("div", "");
        limitBody.append(limitRow);
        const retained = Settings.optionalSection("缓存留存空盒",
            "物品来源内的空盒合计保留至上限，超出归还仓库；不足时不主动补足。", retain, limitBody);
        const body = S.element("div", "");
        body.append(locationRow, retained.root);
        const section = Settings.optionalSection("空盒仓库",
            "优先使用缓存空盒，不足从仓库取；关闭后只在缓存内取用和回收。", enabled, body);
        host.append(section.root);

        /** @returns {void} */
        function refresh() {
            section.refresh();
            retained.refresh();
            location.disabled = !enabled.checked;
            retain.disabled = !enabled.checked;
            limit.disabled = !enabled.checked || !retain.checked;
        }
        enabled.addEventListener("change", refresh);
        retain.addEventListener("change", refresh);

        /** @param {object} values @returns {void} */
        function set(values = {}) {
            enabled.checked = values?.enabled === true;
            retain.checked = values?.retainInCache !== false;
            limit.value = String(values?.cacheLimit ?? 9);
            const ref = values?.warehouseRef || "";
            if (ref && !Array.from(location.options).some(option => option.value === ref)) {
                const missing = new Option("原空盒仓库不存在，请重新选择", ref);
                missing.disabled = true;
                location.append(missing);
            }
            location.value = ref;
            refresh();
        }

        /** @param {Array<object>} points @param {Array<object>} regions @returns {void} */
        function resources(points, regions) {
            const previous = location.value;
            location.replaceChildren(new Option("请选择空盒仓库", ""));
            for (const point of points) {
                location.append(new Option(`[坐标点] ${point.name}`, `coordinate:${point.id}`));
            }
            for (const region of regions) {
                location.append(new Option(`[容器区域] ${region.name}`, `region:${region.id}`));
            }
            location.value = previous;
            window.StowlingResourcePicker?.group(location);
        }

        /** @returns {object} */
        function read() {
            return {enabled: enabled.checked, warehouseRef: location.value || null,
                retainInCache: retain.checked, cacheLimit: Number(limit.value)};
        }
        set();
        return {resources, set, read, refresh};
    }
    window.StowlingEmptyBoxSettings = Object.freeze({create});
})();
