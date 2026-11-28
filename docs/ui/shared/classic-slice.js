(() => {
    "use strict";

    const unit = 26;
    const names = {chest: "双箱", single: "小箱子",
        barrel: "木桶", slab: "下半砖", stairs: "楼梯", checker: "黑紫块",
        glass: "玻璃", quartz: "石英块", low: "低矮构件"};
    const directions = {ArrowLeft: [-1, 0], ArrowRight: [1, 0],
        ArrowUp: [0, -1], ArrowDown: [0, 1]};
    const hint = "从侧栏拖到空格放置，点选方块可删除。右键楼梯旋转，右键双箱拆分。";

    /** @param {string} tag @param {object} attrs @returns {SVGElement} */
    function svgElement(tag, attrs) {
        const el = document.createElementNS("http://www.w3.org/2000/svg", tag);
        for (const [key, value] of Object.entries(attrs)) el.setAttribute(key, value);
        return el;
    }

    /** @param {HTMLElement} root @param {(positions: object) => void} changed @returns {object} */
    function create(root, changed) {
        const figure = root.querySelector(".classic-slice");
        const feedback = figure.querySelector(".classic-hint");
        const svg = document.querySelector("#classic-slice-art").content.firstElementChild
            .cloneNode(true);
        const grid = svg.querySelector(".classic-grid");
        const cursor = svgElement("rect", {class: "placement-cursor", width: unit, height: unit,
            "pointer-events": "none", fill: "none", stroke: "var(--accent)", "stroke-width": 2});
        svg.append(cursor);
        grid.setAttribute("role", "button");
        grid.setAttribute("aria-label", "放置方块，方向键选格，回车放置");
        const undo = figure.querySelector(".classic-undo");
        const remove = figure.querySelector(".classic-delete");
        let picked = null;
        let paletteDrag = null;
        let history = [];
        let columns = 0;
        let rows = 0;
        let origin = {};
        let extent = "";
        svg.setAttribute("role", "group");
        svg.setAttribute("aria-label", "经典单片方格编辑区");
        figure.querySelector(".classic-board").append(svg);
        const target = svgElement("rect", {height: unit, "pointer-events": "none"});
        target.style.display = "none";
        svg.append(target);

        const ghost = svgElement("use", {"pointer-events": "none", opacity: .8});
        ghost.style.display = "none";
        const palette = figure.querySelector(".classic-palette");
        let selected = "";
        let cellX = 0;
        let cellY = 0;
        const defaults = new Map();
        const pieces = new Map();
        let positions = {};
        let drag = null;
        let locked = true;

        /** @param {string} kind @param {number} x @param {number} y @returns {void} */
        function add(kind, x, y) {
            defaults.set(kind + "-" + defaults.size, {kind, x, y});
        }

        /** @param {string} id @returns {object} */
        function makePiece(id) {
            const node = svgElement("g", {"data-piece": id, tabindex: 0, role: "button"});
            node.append(svgElement("use", {"pointer-events": "none"}));
            node.append(svgElement("rect", {class: "piece-hit", height: unit}));
            svg.append(node);
            const piece = {id, node};
            bindPiece(piece);
            return piece;
        }
        const side = [["checker", 3, 0], ["chest", 4, 0], ["chest", 1, 1],
            ["checker", 2, 2], ["chest", 0, 3], ["checker", 1, 4], ["chest", 0, 5],
            ["checker", 0, 6], ["chest", 1, 7], ["checker", 3, 7], ["low", 4, 6]];
        for (const [kind, x, y] of side) {
            add(kind, x + 1, y + 1);
            add(kind, 14 - x - (kind === "chest" ? 2 : 1), y + 1);
        }
        for (let x = 6; x <= 8; x++) add("glass", x, 7);

        /** @param {object} piece @param {number} x @param {number} y @returns {string} */
        function coordinates(piece, x, y) {
            const z = origin.z + x;
            const range = piece.width > 1 ? `${z}…${z + piece.width - 1}` : String(z);
            return `${names[piece.kind]} · X ${origin.x} / Y ${origin.y - y} / Z ${range}`;
        }

        /** @param {object} piece @param {number} x @param {number} y @returns {boolean} */
        function fits(piece, x, y) {
            if (x < 0 || x + piece.width > columns || y < 0 || y >= rows) return false;
            for (const other of pieces.values()) {
                if (other.visible && other !== piece && other.y === y
                        && x < other.x + other.width && x + piece.width > other.x) return false;
            }
            return true;
        }

        /** @param {object} piece @param {number} x @param {number} y @returns {void} */
        function place(piece, x, y) {
            piece.node.setAttribute("transform", `translate(${x * unit} ${y * unit})`);
        }

        /** @param {object} next @param {string} id @param {object} value @returns {void} */
        function put(next, id, value) {
            const base = defaults.get(id);
            if (base && base.kind === value.kind && base.x === value.x && base.y === value.y) {
                delete next[id];
            } else {
                next[id] = value;
            }
        }

        /** @param {string|null} id @returns {void} */
        function selectPiece(id) {
            pieces.get(picked)?.node.removeAttribute("data-selected");
            picked = id;
            pieces.get(id)?.node.setAttribute("data-selected", "");
            remove.disabled = locked || !pieces.get(id)?.visible;
        }

        /** @param {object} next @param {string} id @returns {void} */
        function omit(next, id) {
            if (defaults.has(id)) next[id] = null;
            else delete next[id];
        }

        /** @param {object} next @param {string|null} focusId @returns {void} */
        function apply(next, focusId) {
            history.push(positions);
            changed(next);
            selectPiece(focusId);
            const focus = pieces.get(focusId)?.node || undo;
            focus.focus({preventScroll: true});

        }

        /** @param {string} id @param {string} kind @param {number} x @param {number} y
         * @returns {void} */
        function drop(id, kind, x, y) {
            const next = {...positions};
            if (kind === "single") {
                const neighbors = Array.from(pieces.values()).filter(other => other.id !== id
                    && other.visible && other.kind === "single" && other.y === y
                    && Math.abs(other.x - x) === 1);
                const mate = neighbors.sort((a, b) => a.x - b.x)[0];
                if (mate) {
                    omit(next, mate.id);
                    kind = "chest";
                    x = Math.min(x, mate.x);
                }
            }
            const value = {kind, x, y};
            if (kind === "stairs") value.turns = pieces.get(id)?.turns || 0;
            put(next, id, value);
            apply(next, id);
        }

        /** @param {object} piece @returns {void} */
        function split(piece) {
            if (locked || drag || piece.kind !== "chest") return;
            const next = {...positions};
            put(next, piece.id, {kind: "single", x: piece.x, y: piece.y});
            next[crypto.randomUUID()] = {kind: "single", x: piece.x + 1, y: piece.y};
            apply(next, piece.id);
            feedback.textContent = "已原地拆为两个小箱子。";
        }

        /** @param {object} piece @returns {void} */
        function removePiece(piece) {
            if (!piece?.visible || locked || drag || paletteDrag) return;
            const next = {...positions};
            omit(next, piece.id);
            apply(next, null);
            feedback.textContent = `已删除${names[piece.kind]}。`;
        }

        remove.addEventListener("click", () => removePiece(pieces.get(picked)));

        /** @param {object} piece @param {Event} event @returns {void} */
        function secondary(piece, event) {
            if (locked || drag || paletteDrag || !["chest", "stairs"].includes(piece.kind)) return;
            event.preventDefault();
            if (piece.kind === "chest") {
                split(piece);
                return;
            }
            const {id, kind, x, y} = piece;
            const turns = (piece.turns + 1) % 4;
            apply({...positions, [id]: {kind, x, y, turns}}, id);
            feedback.textContent = `楼梯朝向已旋转至 ${turns * 90}°。`;
        }

        /** @returns {void} */
        function updatePalette() {
            for (const button of palette.querySelectorAll("button")) {
                button.disabled = locked;
                button.setAttribute("aria-pressed", String(button.dataset.block === selected));
            }
            grid.setAttribute("tabindex", selected && !locked ? "0" : "-1");
            grid.style.cursor = selected && !locked ? "crosshair" : "";
            cursor.style.display = document.activeElement === grid && selected && !locked
                ? "" : "none";
            cursor.setAttribute("x", cellX * unit);
            cursor.setAttribute("y", cellY * unit);
        }

        for (const kind of ["single", "barrel", "slab", "stairs", "glass", "checker", "quartz"]) {
            const label = kind === "single" ? "箱子" : names[kind];
            const button = document.createElement("button");
            button.type = "button";
            button.dataset.block = kind;
            const icon = svgElement("svg", {viewBox: "0 0 26 26", "aria-hidden": "true"});
            icon.append(svgElement("use", {href: "#" + kind}));
            button.append(icon, document.createTextNode(label));
            palette.append(button);
            let suppressClick = false;
            button.addEventListener("pointerdown", event => {
                suppressClick = false;
                if (locked || drag || paletteDrag || event.button !== 0) return;
                paletteDrag = {kind, button, id: event.pointerId, active: false,
                    x: event.clientX, y: event.clientY};
                button.setPointerCapture(event.pointerId);
            });
            button.addEventListener("pointermove", event => {
                if (paletteDrag?.id !== event.pointerId) return;
                const state = paletteDrag;
                if (!state.active && Math.hypot(event.clientX - state.x,
                        event.clientY - state.y) < 4) return;
                state.active = true;
                suppressClick = true;
                selected = kind;
                updatePalette();
                const point = local(event);
                const x = Math.floor(point.x / unit);
                const y = Math.floor(point.y / unit);
                const inside = x >= 0 && y >= 0 && x < columns && y < rows;
                ghost.style.display = inside ? "" : "none";
                ghost.setAttribute("href", "#" + kind);
                ghost.setAttribute("transform", `translate(${x * unit} ${y * unit})`);
                ghost.setAttribute("opacity", fits({width: 1}, x, y) ? ".8" : ".28");
                svg.append(ghost);
            });
            button.addEventListener("pointerup", event => {
                if (paletteDrag?.id !== event.pointerId) return;
                const active = paletteDrag.active;
                cancelPaletteDrag();
                if (!active) return;
                const point = local(event);
                insert(Math.floor(point.x / unit), Math.floor(point.y / unit));
            });
            for (const type of ["pointercancel", "lostpointercapture"]) {
                button.addEventListener(type, event => {
                    if (paletteDrag?.id === event.pointerId) cancelPaletteDrag();
                });
            }
            button.addEventListener("click", event => {
                if (event.detail && suppressClick) return;
                selectPiece(null);
                selected = kind;
                updatePalette();
                grid.focus({preventScroll: true});
                feedback.textContent = `点击空格放置${label}，或用方向键选格后按回车。`;
            });
        }

        /** @returns {void} */
        function cancelPaletteDrag() {
            if (!paletteDrag) return;
            const {button, id} = paletteDrag;
            paletteDrag = null;
            ghost.style.display = "none";
            if (button.hasPointerCapture(id)) button.releasePointerCapture(id);
        }

        figure.addEventListener("keydown", event => {
            if (event.key === "Escape") cancelPaletteDrag();
        });

        /** @param {number} x @param {number} y @returns {void} */
        function insert(x, y) {
            if (!selected || locked || drag) return;
            if (!fits({width: 1}, x, y)) {
                feedback.textContent = "请选择空格。";
                return;
            }
            drop(crypto.randomUUID(), selected, x, y);
        }

        grid.addEventListener("click", event => {
            const p = local(event);
            insert(Math.floor(p.x / unit), Math.floor(p.y / unit));
        });
        grid.addEventListener("focus", updatePalette);
        grid.addEventListener("blur", () => { cursor.style.display = "none"; });
        grid.addEventListener("keydown", event => {
            if (locked || !selected) return;
            if (event.key === "Enter" || event.key === " ") {
                event.preventDefault();
                insert(cellX, cellY);
                grid.focus({preventScroll: true});
            } else if (Object.hasOwn(directions, event.key)) {
                event.preventDefault();
                const [dx, dy] = directions[event.key];
                cellX = Math.max(0, Math.min(columns - 1, cellX + dx));
                cellY = Math.max(0, Math.min(rows - 1, cellY + dy));
                updatePalette();
            }
        });

        /** @param {object} piece @param {number} x @param {number} y @returns {void} */
        function commit(piece, x, y) {
            if (!fits(piece, x, y)) {
                feedback.textContent = "请放到能容纳整个构件的空格。";
                return;
            }
            if (piece.x === x && piece.y === y) return;
            drop(piece.id, piece.kind, x, y);
        }

        /** @returns {void} */
        function cancel() {
            if (!drag) return;
            const {piece, pointerId} = drag;
            drag = null;
            if (svg.hasPointerCapture(pointerId)) svg.releasePointerCapture(pointerId);
            piece.node.classList.remove("moving");
            place(piece, piece.x, piece.y);
            target.style.display = "none";
        }

        /** @param {PointerEvent} event @returns {DOMPoint} */
        function local(event) {
            return new DOMPoint(event.clientX, event.clientY)
                .matrixTransform(svg.getScreenCTM().inverse());
        }

        /** @param {PointerEvent} event @returns {object} */
        function destination(event) {
            const point = local(event);
            return {x: (point.x - drag.dx) / unit, y: (point.y - drag.dy) / unit};
        }

        /** @param {object} piece @returns {void} */
        function bindPiece(piece) {
            piece.node.addEventListener("contextmenu", event => secondary(piece, event));
            piece.node.addEventListener("focus", () => selectPiece(piece.id));
            piece.node.addEventListener("pointerdown", event => {
                if (locked || drag || paletteDrag || event.button !== 0) return;
                selectPiece(piece.id);
                const point = local(event);
                drag = {piece, pointerId: event.pointerId,
                    dx: point.x - piece.x * unit, dy: point.y - piece.y * unit};
                piece.node.focus({preventScroll: true});
                svg.setPointerCapture(event.pointerId);
                piece.node.classList.add("moving");
                event.preventDefault();
            });
            piece.node.addEventListener("keydown", event => {
                if (event.key === "Escape") cancel();
                if (event.shiftKey && event.key === "F10") {
                    secondary(piece, event);
                    return;
                }
                if (event.key === "Delete" || event.key === "Backspace") {
                    event.preventDefault();
                    removePiece(piece);
                    return;
                }
                if (locked || drag || !Object.hasOwn(directions, event.key)) return;
                event.preventDefault();
                const [dx, dy] = directions[event.key];
                commit(piece, piece.x + dx, piece.y + dy);
            });
        }
        svg.addEventListener("pointermove", event => {
            if (drag?.pointerId !== event.pointerId) return;
            const {x, y} = destination(event);
            const col = Math.round(x);
            const row = Math.round(y);
            target.style.display = "";
            target.setAttribute("x", col * unit);
            target.setAttribute("y", row * unit);
            target.setAttribute("width", drag.piece.width * unit);
            const valid = fits(drag.piece, col, row);
            target.setAttribute("fill", valid ? "var(--accent-soft)" : "var(--danger)");
            target.setAttribute("stroke", valid ? "var(--accent)" : "var(--danger)");
            place(drag.piece, x, y);
        });
        svg.addEventListener("pointerup", event => {
            if (drag?.pointerId !== event.pointerId) return;
            const {piece} = drag;
            const {x, y} = destination(event);
            cancel();
            commit(piece, Math.round(x), Math.round(y));
        });
        for (const type of ["pointercancel", "lostpointercapture"]) {
            svg.addEventListener(type, event => {
                if (drag?.pointerId === event.pointerId) cancel();
            });
        }

        /** @param {object} region @returns {boolean} */
        function setBounds(region) {
            const width = Math.abs(region.second.z - region.first.z) + 1;
            const height = Math.abs(region.second.y - region.first.y) + 1;
            columns = Math.min(width, 15);
            rows = Math.min(height, 10);
            origin = {x: Math.min(region.first.x, region.second.x),
                y: Math.min(region.first.y, region.second.y) + rows - 1,
                z: Math.min(region.first.z, region.second.z)};
            const key = JSON.stringify([origin, rows, columns]);
            if (key !== extent) {
                extent = key;
                const w = columns * unit;
                const h = rows * unit;
                svg.setAttribute("viewBox", `0 0 ${w} ${h}`);
                svg.style.setProperty("--board-width", w * 1.5 + "px");
                grid.setAttribute("width", w);
                grid.setAttribute("height", h);
                cellX = Math.min(cellX, columns - 1);
                cellY = Math.min(cellY, rows - 1);
            }
            return columns < width || rows < height;
        }

        /** @returns {void} */
        function reset() {
            cancel();
            selectPiece(null);
            history = [];
            selected = "";
            cellX = 0;
            cellY = 0;
        }

        undo.addEventListener("click", () => {
            if (!locked && history.length) changed(history.pop());
        });

        /** @param {object|null} region @param {object} saved @param {boolean} stale
         * @param {boolean} preview @returns {void} */
        function render(region, saved, stale, preview) {
            cancel();
            cancelPaletteDrag();
            figure.hidden = !region;
            locked = stale || !region;
            if (!region) return;
            const partial = setBounds(region);
            positions = saved || {};
            figure.dataset.stage = stale ? "stale" : preview ? "pending" : "applied";
            const stage = stale ? "选区已变更" : preview ? "待应用" : "已应用";
            figure.querySelector(".classic-stage").textContent =
                `${stage} · X = ${origin.x}${partial ? " · 局部" : ""}`;
            feedback.textContent = stale ? "选区已变更，重新预览后可移动构件。" : hint;
            undo.disabled = locked || !history.length;
            updatePalette();
            const ids = new Set([...defaults.keys(), ...Object.keys(positions)]);
            for (const [id, piece] of pieces) {
                if (!ids.has(id) || positions[id] === null) {
                    piece.node.remove();
                    pieces.delete(id);
                }
            }
            for (const id of ids) {
                if (positions[id] === null) continue;
                const value = {...defaults.get(id), ...positions[id]};
                const piece = pieces.get(id) || makePiece(id);
                pieces.set(id, piece);
                Object.assign(piece, value);
                piece.turns = value.turns || 0;
                piece.width = piece.kind === "chest" ? 2 : 1;
                const {x, y, width, kind, node} = piece;
                const art = node.firstElementChild;
                if (kind === "stairs") {
                    art.setAttribute("transform", `rotate(${piece.turns * 90} 13 13)`);
                } else {
                    art.removeAttribute("transform");
                }
                art.setAttribute("href", "#" + kind);
                art.setAttribute("x", kind === "chest" ? 2 : 0);
                art.setAttribute("y", kind === "low" ? 13 : 0);
                node.lastElementChild.setAttribute("width", width * unit);
                node.dataset.kind = kind;
                piece.visible = x >= 0 && y >= 0 && x + width <= columns && y < rows;
                node.style.display = piece.visible ? "" : "none";
                place(piece, x, y);
                node.setAttribute("aria-disabled", String(locked));
                node.setAttribute("tabindex", locked || !piece.visible ? "-1" : "0");
                node.setAttribute("aria-label", coordinates(piece, x, y));
            }
            selectPiece(pieces.get(picked)?.visible ? picked : null);

        }
        return {render, reset};
    }
    window.StowlingClassicSlice = {create};
})();
