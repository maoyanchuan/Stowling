(() => {
    "use strict";
    const S = window.Stowling;
    const states = new WeakMap();
    let directory;

    /** @param {boolean} [refresh] @returns {Promise<object>} */
    function load(refresh = false) {
        if (refresh || !directory) {
            const request = S.request("/v1/coordinate-resources").catch(error => {
                if (directory === request) directory = undefined;
                throw error;
            });
            directory = request;
        }
        return directory;
    }

    /** @param {object} catalog @param {string} ref @returns {string} */
    function folderLabel(catalog, ref) {
        const folderId = catalog.placements?.[ref];
        return catalog.folders?.find(folder => folder.id === folderId)?.name || "未分组";
    }

    /**
     * @param {HTMLSelectElement} select
     * @param {string} [type]
     * @param {string} [panel]
     * @param {boolean} [showCreateLink]
     * @returns {void}
     */
    function group(select, type, panel = "coordinate-points", showCreateLink = true) {
        if (!select?.parentElement) return;
        let state = states.get(select);
        if (!state) {
            let controls = null;
            let link = null;
            if (showCreateLink) {
                controls = S.element("span", "resource-picker-controls");
                link = S.element("a", "text-link", "去坐标页添加");
                link.target = "_blank";
                link.rel = "noopener";
                controls.append(link);
                const anchor = select.closest("label") || select;
                anchor.after(controls);
            }
            state = {controls, link, options: [], catalog: {}};
            states.set(select, state);
        }
        state.options = Array.from(select.options, option => option.cloneNode(true));
        state.type = type;
        if (state.link) state.link.href = "/coordinates.html#" + panel;
        if (state.controls) {
            state.controls.hidden = state.options.some(option => option.value && !option.disabled);
        }
        const captured = state.options;
        load().then(response => {
            if (state.options !== captured || !select.isConnected) return;
            state.catalog = response.catalog || {};
            render(select, state);
        }).catch(() => {});
    }

    /** @param {HTMLSelectElement} select @param {object} state @returns {void} */
    function render(select, state) {
        const selection = select.multiple ? Array.from(select.selectedOptions, item => item.value)
            : [select.value];
        const groups = new Map();
        const options = [];
        for (const option of state.options) {
            const value = option.value;
            if (!value) {
                options.push(option.cloneNode(true));
                continue;
            }
            const ref = state.type ? state.type + ":" + value : value;
            const name = folderLabel(state.catalog, ref);
            if (!groups.has(name)) {
                const group = document.createElement("optgroup");
                group.label = name;
                groups.set(name, group);
            }
            groups.get(name).append(option.cloneNode(true));
        }
        select.replaceChildren(...options, ...groups.values());
        for (const option of select.options) option.selected = selection.includes(option.value);
    }

    document.querySelector("#refresh-move-tasks")?.addEventListener("click", () => {
        void load(true).catch(() => {});
    });
    window.StowlingResourcePicker = Object.freeze({load, group, folderLabel});
})();
