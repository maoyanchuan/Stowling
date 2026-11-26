(() => {
    "use strict";

    /** @param {HTMLElement} host @param {HTMLElement} robot @returns {object} */
    function create(host, robot) {
        const S = window.Stowling;
        const Settings = window.StowlingSettings;

        /** @param {string} key @param {string} title @param {string} note @returns {object} */
        function destinationSetting(key, title, note) {
            const enabled = document.createElement("input");
            enabled.id = `auto-${key}-enabled`;
            const location = document.createElement("select");
            location.id = `auto-${key}-location`;
            const row = S.element("label", "move-field");
            row.htmlFor = location.id;
            row.append(S.element("span", "", "存放位置"), location);
            const body = S.element("div");
            body.append(row);
            const section = Settings.optionalSection(title, note, enabled, body);
            section.root.id = `auto-${key}-settings`;
            section.root.querySelector("button").id = `auto-${key}-toggle`;
            return {enabled, location, ...section};
        }

        const unclassifiable = destinationSetting("unclassifiable", "不可分类",
            "未匹配任何目标分类区的物品，送往所选位置。");
        const overflow = destinationSetting("overflow-storage", "总溢出", "");
        const body = S.element("div");
        body.append(unclassifiable.root, overflow.root, robot);
        const section = Settings.disclosure("溢出处理", "", body);
        section.root.id = "auto-overflow-settings";
        section.summary.id = "auto-overflow-toggle";
        host.append(section.root);
        let context = {robotEnabled: false};

        /** @param {object} [options] @returns {void} */
        function refresh(options = {}) {
            context = {...context, ...options};
            unclassifiable.refresh();
            overflow.refresh();
            const total = overflow.enabled.checked;
            const robot = context.robotEnabled;
            section.description.textContent = total && robot
                ? "执行顺序：机器人仓库 → 总溢出"
                : total ? "仅总溢出；未接收余量保留在来源。"
                    : robot ? "仅机器人仓库；按纯盒／杂盒方式入库。"
                        : "未启用；溢出物品保留在来源。";
            if (unclassifiable.enabled.checked) {
                const following = [robot ? "机器人仓库" : "", total ? "总溢出" : ""]
                    .filter(Boolean);
                section.description.textContent = following.length
                    ? "执行顺序：不可分类 → " + following.join(" → ")
                    : "仅不可分类；其他余物保留在来源。";
            }
            overflow.root.querySelector(".settings-description").textContent = robot
                ? "接收机器人仓库未能存入的余量；放不下的物品保留在来源。"
                : "所有对应分类目标均已满时直接转存；未接收余量保留在来源。";
            if (total && context.packingEnabled) {
                overflow.root.querySelector(".settings-description").textContent =
                    "送入此位置的任务物品先续装已有未满盒，再使用空盒；"
                    + "不可分类共用此位置时同样打包。";
            }
        }

        /** @param {object} values @returns {void} */
        function set(values = {}) {
            const settings = [["unclassifiable", unclassifiable], ["overflow", overflow]];
            for (const [key, setting] of settings) {
                setting.enabled.checked = values?.[`${key}Enabled`] === true;
                const ref = values?.[`${key}DestinationRef`] || "";
                if (ref && !Array.from(setting.location.options).some(item => item.value === ref)) {
                    const missing = new Option("原存放位置已删除，请重新选择", ref);
                    missing.disabled = true;
                    setting.location.append(missing);
                }
                setting.location.value = ref;
            }
            refresh();
        }

        /** @param {Array<object>} locations @returns {void} */
        function resources(locations) {
            for (const setting of [unclassifiable, overflow]) {
                const previous = setting.location.value;
                setting.location.replaceChildren(new Option("请选择存放位置", ""));
                for (const location of locations) {
                    setting.location.append(new Option(location.name, location.id));
                }
                setting.location.value = previous;
                window.StowlingResourcePicker?.group(setting.location, undefined,
                    "coordinate-points", false);
            }
        }

        /** @returns {object} */
        function read() {
            return {
                unclassifiableEnabled: unclassifiable.enabled.checked,
                unclassifiableDestinationRef: unclassifiable.location.value || null,
                overflowEnabled: overflow.enabled.checked,
                overflowDestinationRef: overflow.location.value || null
            };
        }

        refresh();
        return {resources, set, read, refresh};
    }

    window.StowlingClassificationRemainders = Object.freeze({create});
})();
