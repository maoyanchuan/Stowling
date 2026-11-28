(() => {
    "use strict";

    const roles = {
        storage: {name: "储存区", mark: "箱"},
        marker: {name: "标识物识别区", mark: "标"},
        standing: {name: "站立位置（脚下方块）", mark: "站"},
        support: {name: "支撑方块", mark: "撑"}
    };

    /** @param {object} region @returns {object} */
    function example(region) {
        const first = {};
        const second = {};
        for (const axis of ["x", "y", "z"]) {
            first[axis] = Math.min(region.first[axis], region.second[axis]);
            second[axis] = Math.max(region.first[axis], region.second[axis]);
        }
        // Synthetic structure only. These offsets do not infer anything about a game world.
        /** @param {number} y @param {number} z @returns {object} */
        const at = (y, z) => ({
            x: first.x, y: Math.min(first.y + y, second.y),
            z: Math.min(first.z + z, second.z)
        });
        /** @param {object} lower @param {object} upper @returns {object} */
        const span = (lower, upper) => ({first: lower, second: {...upper, x: second.x}});
        return {
            first, second,
            markerRegion: span(at(3, 9), at(4, 9)),
            storageRegion: span(at(1, 3), at(2, 3)),
            standing: at(0, 6),
            yMax: Math.min(first.y + 7, second.y),
            zMax: Math.min(first.z + 11, second.z)
        };
    }

    /** @param {string} tag @param {string} className @param {string} [text]
     * @returns {HTMLElement} */
    function element(tag, className, text = "") {
        const node = document.createElement(tag);
        node.className = className;
        node.textContent = text;
        return node;
    }

    /** @param {object} point @param {object} region @returns {boolean} */
    function inside(point, region) {
        return ["x", "y", "z"].every(axis => point[axis] >= region.first[axis]
            && point[axis] <= region.second[axis]);
    }

    /** @param {object} point @param {object} model @returns {string[]} */
    function cellRoles(point, model) {
        const found = [];
        if (inside(point, model.storageRegion)) found.push("storage");
        if (inside(point, model.markerRegion)) found.push("marker");
        if (point.y === model.standing.y && point.z === model.standing.z) {
            found.push("standing");
        }
        if (!found.length && (point.y === model.first.y
                || (point.z === model.markerRegion.first.z
                    && point.y < model.markerRegion.first.y))) found.push("support");
        return found;
    }

    /** @param {HTMLElement} host @returns {{render: (region: object, stage: string) => void}} */
    function create(host) {
        host.classList.add("slice-plan");
        const heading = element("div", "slice-heading");
        const titles = element("div", "");
        const title = element("h3", "", "单片结构平面图");
        title.id = `${host.id}-title`;
        host.setAttribute("aria-labelledby", title.id);
        const subtitle = element("p", "slice-subtitle");
        const stageLabel = element("span", "slice-stage");
        titles.append(title, subtitle);
        heading.append(titles, stageLabel);
        const viewport = element("div", "slice-viewport");
        const grid = element("div", "slice-grid");
        grid.setAttribute("role", "group");
        grid.setAttribute("aria-label", "单片方块图，方向键移动，Enter 或空格选择");
        viewport.append(grid);
        const legend = element("div", "slice-legend");
        for (const [key, role] of Object.entries(roles)) {
            const item = element("span", "slice-legend-item");
            const swatch = element("span", "slice-swatch", role.mark);
            swatch.dataset.role = key;
            swatch.setAttribute("aria-hidden", "true");
            item.append(swatch, element("span", "", role.name));
            legend.append(item);
        }
        const selection = element("p", "slice-selection");
        selection.setAttribute("role", "status");
        selection.setAttribute("aria-live", "polite");
        const note = element("p", "slice-note");
        const panHint = element("p", "slice-pan-hint", "左右滑动查看完整单片");
        host.append(heading, viewport, panHint, legend, selection, note);
        let previous = "";
        let buttons = [];
        let selected = null;
        let columns = 0;

        /** @param {HTMLButtonElement} button @param {boolean} [focus] @returns {void} */
        function select(button, focus = false) {
            if (selected) {
                selected.tabIndex = -1;
                selected.setAttribute("aria-pressed", "false");
            }
            selected = button;
            button.tabIndex = 0;
            button.setAttribute("aria-pressed", "true");
            selection.textContent = button.getAttribute("aria-label");
            if (focus) button.focus();
        }

        /** @param {object} region @param {string} stage @returns {void} */
        function render(region, stage) {
            const model = example(region);
            stageLabel.textContent = {pending: "待应用 · 示例", applied: "已应用 · 扫描快照",
                stale: "选区已变更 · 旧快照"}[stage];
            host.dataset.stage = stage;
            const key = JSON.stringify([model.first, model.second]);
            if (key === previous) return;
            previous = key;
            columns = model.zMax - model.first.z + 1;
            grid.style.setProperty("--slice-columns", String(columns));
            subtitle.textContent = `侧视图 · 固定 X = ${model.first.x} · 每格 1 个方块`;
            note.textContent = "横轴 Z（前后），纵轴 Y（高度）。点击方块查看坐标；"
                + "站位标注脚下方块。此图为扫描示例快照，手动调整以表单为准。";
            if (model.yMax < model.second.y || model.zMax < model.second.z) {
                note.textContent += " 当前仅展示单片局部，其余范围未绘制。";
            }
            grid.replaceChildren();
            buttons = [];
            selected = null;
            const axis = element("div", "slice-grid-row slice-axis");
            axis.append(element("span", "slice-axis-label", "Y / Z"));
            for (let column = 0; column < columns; column += 1) {
                const z = model.first.z + column;
                axis.append(element("span", "slice-axis-label", String(z)));
            }
            grid.append(axis);
            const rows = model.yMax - model.first.y + 1;
            for (let rowIndex = 0; rowIndex < rows; rowIndex += 1) {
                const y = model.yMax - rowIndex;
                const row = element("div", "slice-grid-row");
                row.append(element("span", "slice-axis-label", String(y)));
                for (let column = 0; column < columns; column += 1) {
                    const z = model.first.z + column;
                    const found = cellRoles({x: model.first.x, y, z}, model);
                    const button = element("button", "slice-cell");
                    button.type = "button";
                    button.tabIndex = -1;
                    button.dataset.roles = found.join(" ");
                    button.dataset.y = String(y);
                    button.dataset.z = String(z);
                    button.setAttribute("aria-pressed", "false");
                    const names = found.map(role => roles[role].name).join(" / ") || "空位";
                    const label = `${names} · X ${model.first.x} / Y ${y} / Z ${z}`;
                    button.setAttribute("aria-label", label);
                    button.title = label;
                    for (const role of found) {
                        const tile = element("span", "slice-tile", roles[role].mark);
                        tile.dataset.role = role;
                        tile.setAttribute("aria-hidden", "true");
                        button.append(tile);
                    }
                    button.addEventListener("click", () => select(button));
                    buttons.push(button);
                    row.append(button);
                }
                grid.append(row);
            }
            select(buttons.find(button => button.dataset.roles.includes("storage")) || buttons[0]);
        }

        grid.addEventListener("keydown", event => {
            const offsets = {ArrowLeft: -1, ArrowRight: 1,
                ArrowUp: -columns, ArrowDown: columns};
            const index = buttons.indexOf(document.activeElement);
            if (!(event.key in offsets) || index < 0) return;
            event.preventDefault();
            const next = index + offsets[event.key];
            const sideways = ["ArrowLeft", "ArrowRight"].includes(event.key);
            if (next < 0 || next >= buttons.length
                    || (sideways && Math.floor(next / columns) !== Math.floor(index / columns))) {
                return;
            }
            select(buttons[next], true);
        });
        return {render};
    }

    window.StowlingCoordinateSlicePlan = {example, create};
})();
