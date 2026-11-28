(() => {
    "use strict";

    const roles = {
        storage: {name: "储存区"},
        marker: {name: "标识物识别区"},
        standing: {name: "站立位置（脚下方块）"},
        support: {name: "支撑方块"}
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

    /** @param {HTMLElement} host @param {(cells: object) => void} changed @returns {object} */
    function create(host, changed) {
        host.classList.add("slice-plan");
        const heading = element("div", "slice-heading");
        const titles = element("div", "");
        const title = element("h3", "", "坐标草稿");
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
        const toolbar = element("div", "slice-tools");
        toolbar.setAttribute("role", "group");
        toolbar.setAttribute("aria-label", "单片结构编辑工具");
        const tools = [];
        const choices = {view: "查看", storage: "储存区", marker: "识别区",
            standing: "站位", support: "支撑", erase: "清空"};
        for (const [key, name] of Object.entries(choices)) {
            const tool = element("button", "slice-tool");
            tool.type = "button";
            tool.dataset.tool = key;
            tool.setAttribute("aria-pressed", String(key === "view"));
            if (key !== "view") {
                const swatch = element("span", "slice-swatch");
                swatch.dataset.role = key;
                swatch.setAttribute("aria-hidden", "true");
                tool.append(swatch);
            }
            tool.append(element("span", "", name));
            tool.addEventListener("click", () => {
                brush = key;
                updateTools();
            });
            tools.push(tool);
            toolbar.append(tool);
        }
        const actions = element("div", "slice-edit-actions");
        const undo = element("button", "slice-undo", "撤销");
        undo.type = "button";
        undo.disabled = true;
        const editStatus = element("span", "slice-edit-status");
        editStatus.setAttribute("role", "status");
        editStatus.setAttribute("aria-live", "polite");
        actions.append(undo, editStatus);
        const selection = element("p", "slice-selection");
        selection.setAttribute("role", "status");
        selection.setAttribute("aria-live", "polite");
        const note = element("p", "slice-note");
        const panHint = element("p", "slice-pan-hint", "左右滑动查看完整单片");
        const expand = element("button", "slice-draft-toggle", "展开坐标草稿");
        expand.type = "button";
        const editor = element("div", "slice-draft-content");
        editor.id = host.id + "-editor";
        expand.setAttribute("aria-controls", editor.id);
        expand.setAttribute("aria-expanded", "false");
        const reveal = window.StowlingSettingsControls.createReveal();
        reveal(editor, false, false);
        expand.addEventListener("click", () => {
            const open = expand.getAttribute("aria-expanded") === "false";
            expand.setAttribute("aria-expanded", String(open));
            expand.textContent = open ? "收起坐标草稿" : "展开坐标草稿";
            reveal(editor, open);
        });
        editor.append(toolbar, viewport, panHint, selection, actions, note);
        host.append(heading, expand, editor);
        let previous = "";
        let buttons = [];
        let selected = null;
        let columns = 0;
        let model = null;
        let cells = {};
        let history = [];
        let brush = "view";
        let stale = false;

        /** @returns {string} */
        function renderKey() {
            return JSON.stringify([model.first, model.second, cells]);
        }

        /** @returns {void} */
        function updateTools() {
            for (const tool of tools) {
                tool.disabled = stale && tool.dataset.tool !== "view";
                tool.setAttribute("aria-pressed", String(tool.dataset.tool === brush));
            }
            undo.disabled = stale || !history.length;
            const count = Object.keys(cells).length;
            const instruction = brush === "view" ? "点击查看坐标" : `正在设置：${choices[brush]}`;
            editStatus.textContent = stale ? "选区已变更，请重新预览后编辑。"
                : instruction
                    + (count ? ` · 已调整 ${count} 格，保存坐标可保留草稿` : "");
            host.dataset.editing = String(brush !== "view" && !stale);
        }

        /** @param {HTMLButtonElement} button @returns {void} */
        function updateCell(button) {
            const point = {x: model.first.x,
                y: Number(button.dataset.y), z: Number(button.dataset.z)};
            const key = `${point.y}:${point.z}`;
            const found = Object.hasOwn(cells, key) ? cells[key] : cellRoles(point, model);
            button.dataset.roles = found.join(" ");
            const names = found.map(role => roles[role].name).join(" / ") || "空位";
            const label = `${names} · X ${point.x} / Y ${point.y} / Z ${point.z}`;
            button.setAttribute("aria-label", label);
            button.title = label;
            button.replaceChildren();
            for (const role of found) {
                const tile = element("span", "slice-tile");
                tile.dataset.role = role;
                tile.setAttribute("aria-hidden", "true");
                button.append(tile);
            }
        }

        /** @param {HTMLButtonElement} button @returns {void} */
        function activate(button) {
            if (brush !== "view" && !stale) {
                const found = brush === "erase" ? [] : [brush];
                const point = {x: model.first.x,
                    y: Number(button.dataset.y), z: Number(button.dataset.z)};
                const key = `${point.y}:${point.z}`;
                const current = button.dataset.roles.split(" ").filter(Boolean);
                if (JSON.stringify(current) !== JSON.stringify(found)) {
                    history.push(structuredClone(cells));
                    if (JSON.stringify(found) === JSON.stringify(cellRoles(point, model))) {
                        delete cells[key];
                    } else {
                        cells[key] = found;
                    }
                    updateCell(button);
                    previous = renderKey();
                    changed(structuredClone(cells));
                }
            }
            select(button);
            updateTools();
        }

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

        /** @param {object} region @param {string} stage @param {object} overrides
         * @returns {void} */
        function render(region, stage, overrides) {
            model = example(region);
            cells = structuredClone(overrides || {});
            stale = stage === "stale";
            if (stale) brush = "view";
            const edited = Object.keys(cells).length > 0;
            stageLabel.textContent = {pending: "待应用 · 示例",
                applied: edited ? "已手动调整 · 结构草稿" : "已应用 · 示例结构",
                stale: "选区已变更 · 旧结构"}[stage];
            host.dataset.stage = stage;
            updateTools();
            const key = renderKey();
            if (key === previous) return;
            history = [];
            undo.disabled = true;
            previous = key;
            columns = model.zMax - model.first.z + 1;
            grid.style.setProperty("--slice-columns", String(columns));
            subtitle.textContent = `侧视图 · 固定 X = ${model.first.x} · 每格 1 个方块`;
            note.textContent = "横轴 Z（前后），纵轴 Y（高度）；站位表示脚下方块。"
                + "选择用途后点击格子修改，调整后点击“保存坐标”。"
                + "当前编辑保存结构图草稿，尚未转换为下方区域参数；刷新恢复内置示例。";
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
                    const button = element("button", "slice-cell");
                    button.type = "button";
                    button.tabIndex = -1;
                    button.dataset.y = String(y);
                    button.dataset.z = String(z);
                    button.setAttribute("aria-pressed", "false");
                    updateCell(button);
                    button.addEventListener("click", () => activate(button));
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
        undo.addEventListener("click", () => {
            if (stale || !history.length) return;
            cells = history.pop();
            buttons.forEach(updateCell);
            previous = renderKey();
            changed(structuredClone(cells));
            if (selected) select(selected);
            updateTools();
        });
        /** @returns {void} */
        function reset() {
            previous = "";
            history = [];
            brush = "view";
        }

        return {render, reset};
    }

    window.StowlingCoordinateSlicePlan = {example, create};
})();
