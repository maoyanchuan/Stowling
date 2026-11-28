(() => {
    "use strict";

    const unit = 26;
    const names = {chest: "双箱", checker: "紫黑方块", glass: "玻璃", low: "低矮构件"};
    const directions = {ArrowLeft: [-1, 0], ArrowRight: [1, 0],
        ArrowUp: [0, -1], ArrowDown: [0, 1]};
    const hint = "每格 1 方块 · 拖到空格松手放置，也可聚焦构件后用方向键移动。";

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
        const axis = svgElement("g", {class: "classic-axis", "aria-hidden": "true"});
        svg.append(axis);
        const undo = figure.querySelector(".classic-undo");
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

        const pieces = [];
        let positions = {};
        let drag = null;
        let locked = true;

        /** @param {string} kind @param {number} x @param {number} y @returns {void} */
        function add(kind, x, y) {
            const width = kind === "chest" ? 2 : 1;
            const id = kind + "-" + pieces.length;
            const node = svgElement("g", {"data-piece": id, tabindex: 0, role: "button"});
            node.append(svgElement("use", {href: "#" + kind, "pointer-events": "none",
                x: kind === "chest" ? 2 : 0, y: kind === "low" ? 13 : 0}));
            node.append(svgElement("rect", {class: "piece-hit", width: width * unit,
                height: unit}));
            svg.append(node);
            pieces.push({id, kind, x, y, width, node});
        }
        const side = [["checker", 3, 0], ["chest", 4, 0], ["chest", 1, 1],
            ["checker", 2, 2], ["chest", 0, 3], ["checker", 1, 4], ["chest", 0, 5],
            ["checker", 0, 6], ["chest", 1, 7], ["checker", 3, 7], ["low", 4, 6]];
        for (const [kind, x, y] of side) {
            add(kind, x + 1, y + 1);
            add(kind, 14 - x - (kind === "chest" ? 2 : 1), y + 1);
        }
        for (let x = 6; x <= 8; x++) add("glass", x, 7);

        /** @param {object} piece @returns {object} */
        function position(piece) {
            return positions[piece.id] || piece;
        }

        /** @param {object} piece @param {number} x @param {number} y @returns {string} */
        function coordinates(piece, x, y) {
            const z = origin.z + x;
            const range = piece.width > 1 ? `${z}…${z + piece.width - 1}` : String(z);
            return `${names[piece.kind]} · X ${origin.x} / Y ${origin.y - y} / Z ${range}`;
        }

        /** @param {object} piece @param {number} x @param {number} y @returns {boolean} */
        function fits(piece, x, y) {
            if (x < 0 || x + piece.width > columns || y < 0 || y >= rows) return false;
            return pieces.every(other => {
                const p = position(other);
                return !other.visible || other === piece || p.y !== y || x >= p.x + other.width
                    || x + piece.width <= p.x;
            });
        }

        /** @param {object} piece @param {number} x @param {number} y @returns {void} */
        function place(piece, x, y) {
            piece.node.setAttribute("transform", `translate(${x * unit} ${y * unit})`);
        }

        /** @param {object} piece @param {number} x @param {number} y @returns {void} */
        function commit(piece, x, y) {
            if (!fits(piece, x, y)) {
                feedback.textContent = "请放到能容纳整个构件的空格。";
                return;
            }
            const current = position(piece);
            if (current.x === x && current.y === y) {
                feedback.textContent = coordinates(piece, x, y);
                return;
            }
            history.push(positions);
            const next = {...positions, [piece.id]: {x, y}};
            if (x === piece.x && y === piece.y) delete next[piece.id];
            changed(next);
            feedback.textContent = coordinates(piece, x, y);
        }

        /** @returns {void} */
        function cancel() {
            if (!drag) return;
            const {piece, pointerId} = drag;
            drag = null;
            if (svg.hasPointerCapture(pointerId)) svg.releasePointerCapture(pointerId);
            piece.node.classList.remove("moving");
            const p = position(piece);
            place(piece, p.x, p.y);
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

        for (const piece of pieces) {
            piece.node.addEventListener("pointerdown", event => {
                if (locked || drag || event.button !== 0) return;
                const p = position(piece);
                const point = local(event);
                drag = {piece, pointerId: event.pointerId,
                    dx: point.x - p.x * unit, dy: point.y - p.y * unit};
                piece.node.focus({preventScroll: true});
                svg.setPointerCapture(event.pointerId);
                piece.node.classList.add("moving");
                event.preventDefault();
            });
            piece.node.addEventListener("keydown", event => {
                if (event.key === "Escape") cancel();
                if (locked || drag || !Object.hasOwn(directions, event.key)) return;
                event.preventDefault();
                const [dx, dy] = directions[event.key];
                const p = position(piece);
                commit(piece, p.x + dx, p.y + dy);
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

        /** @param {string} value @param {number} x @param {number} y @returns {void} */
        function tick(value, x, y) {
            const label = svgElement("text", {x, y, "text-anchor": "middle"});
            label.textContent = value;
            axis.append(label);
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
                const digits = Math.max(String(origin.y).length, String(origin.y - rows + 1).length);
                const gutter = Math.max(26, digits * 6 + 6);
                const w = columns * unit;
                const h = rows * unit;
                svg.setAttribute("viewBox", `${-gutter} -22 ${gutter + w + 5} ${h + 27}`);
                svg.style.setProperty("--board-width", (gutter + w + 5) * 1.5 + "px");
                grid.setAttribute("width", w);
                grid.setAttribute("height", h);
                axis.replaceChildren();
                tick("Y/Z", -gutter / 2, -7);
                const stride = Math.ceil((String(origin.z + columns).length + 1) * 6 / unit);
                for (let x = 0; x < columns; x += Math.max(1, stride)) {
                    tick(String(origin.z + x), (x + .5) * unit, -7);
                }
                for (let y = 0; y < rows; y++) {
                    tick(String(origin.y - y), -gutter / 2, (y + .5) * unit + 3);
                }
            }
            return columns < width || rows < height;
        }

        /** @returns {void} */
        function reset() {
            cancel();
            history = [];
        }

        undo.addEventListener("click", () => {
            if (!locked && history.length) changed(history.pop());
        });

        /** @param {object|null} region @param {object} saved @param {boolean} stale
         * @param {boolean} preview @returns {void} */
        function render(region, saved, stale, preview) {
            cancel();
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
            for (const piece of pieces) {
                const p = position(piece);
                piece.visible = p.x >= 0 && p.y >= 0 && p.x + piece.width <= columns && p.y < rows;
                piece.node.style.display = piece.visible ? "" : "none";
                place(piece, p.x, p.y);
                piece.node.setAttribute("aria-disabled", String(locked));
                piece.node.setAttribute("tabindex", locked || !piece.visible ? "-1" : "0");
                piece.node.setAttribute("aria-label", coordinates(piece, p.x, p.y));
            }
            if (!stale && !pieces.some(piece => piece.visible)) {
                feedback.textContent = "此范围内没有完整的示例构件。";
            }
        }
        return {render, reset};
    }
    window.StowlingClassicSlice = {create};
})();
