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
        const overflow = destinationSetting("overflow-storage", "溢出存放",
            "所有对应分类目标均已满时，送往所选位置；放不下的物品保留在来源。");
        const body = S.element("div");
        body.append(overflow.root, robot);
        const section = Settings.disclosure("溢出",
            "分类区放满后的存放位置与机器人仓库", body);
        section.root.id = "auto-overflow-settings";
        section.summary.id = "auto-overflow-toggle";
        host.append(unclassifiable.root, section.root);

        /** @returns {void} */
        function refresh() {
            unclassifiable.refresh();
            overflow.refresh();
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

        return {resources, set, read, refresh};
    }

    window.StowlingClassificationRemainders = Object.freeze({create});
})();
