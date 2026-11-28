(() => {
    "use strict";

    const titles = {marker: "识别区", storage: "储存区", station: "站位"};
    const unit = 26;

    /** @param {string} tag @param {object} attrs @returns {SVGElement} */
    function svgNode(tag, attrs) {
        const node = document.createElementNS("http://www.w3.org/2000/svg", tag);
        for (const [key, value] of Object.entries(attrs)) node.setAttribute(key, value);
        return node;
    }

    /** @param {string} tag @param {string} text @returns {HTMLElement} */
    function html(tag, text = "") {
        const node = document.createElement(tag);
        node.textContent = text;
        if (tag === "button") node.type = "button";
        return node;
    }

    /** @param {object[]} groups @returns {Map<string, object>} */
    function stations(groups) {
        const result = new Map();
        for (const group of groups) {
            for (const point of group.standingPlan?.points || []) {
                const key = JSON.stringify([point.standingId, point.position]);
                if (!result.has(key)) result.set(key, {point, ids: new Set()});
                result.get(key).ids.add(group.id);
            }
        }
        return result;
    }

    /** @param {HTMLElement} root @param {Function} read @param {Function} write
     * @param {Function} validStations @returns {object} */
    function create(root, read, write, validStations) {
        const figure = root.querySelector(".classic-slice");
        const svg = figure.querySelector(".classic-board svg");
        const palette = figure.querySelector(".classic-palette");
        const side = figure.querySelector(".area-sidebar");
        const footer = figure.querySelector(".classic-footer");
        const modes = figure.querySelectorAll("[data-canvas-mode]");
        const layer = svgNode("g", {class: "area-layer"});
        const surface = svgNode("rect", {fill: "transparent", width: "100%", height: "100%"});
        layer.append(surface);
        const shapes = svgNode("g", {});
        layer.append(shapes);
        const status = html("p");
        status.className = "area-help";
        status.setAttribute("role", "status");
        figure.append(status);
        let enabled = false;
        let blockGridTab = "-1";
        let locked = true;
        let region = null;
        let groupId = "";
        let stationKey = "";
        let tool = "marker";
        let gesture = null;
        let typingName = false;

        /** @param {string} label @param {Function} action @param {boolean} active
         * @returns {HTMLButtonElement} */
        function button(label, action, active = false) {
            const node = html("button", label);
            node.setAttribute("aria-pressed", String(active));
            node.disabled = locked;
            node.addEventListener("click", action);
            return node;
        }

        /** @returns {object} */
        function origin() {
            return {x: Math.min(region.first.x, region.second.x),
                y: Math.max(region.first.y, region.second.y),
                z: Math.min(region.first.z, region.second.z)};
        }

        /** @param {PointerEvent} event @returns {object} */
        function location(event) {
            const pt = new DOMPoint(event.clientX, event.clientY)
                .matrixTransform(svg.getScreenCTM().inverse());
            const base = origin();
            return {x: base.x, y: base.y - Math.floor(pt.y / unit),
                z: base.z + Math.floor(pt.x / unit)};
        }

        /** @param {object} a @param {object} b @param {object|null} old @returns {object} */
        function box(a, b, old) {
            return {first: {x: old?.first.x ?? a.x, y: Math.min(a.y, b.y), z: Math.min(a.z, b.z)},
                second: {x: old?.second.x ?? b.x, y: Math.max(a.y, b.y), z: Math.max(a.z, b.z)}};
        }

        /** @param {object} group @param {string} kind @param {object} area
         * @param {boolean} highlighted @returns {void} */
        function drawBox(group, kind, area, highlighted) {
            if (!area) return;
            const base = origin();
            if (base.x < Math.min(area.first.x, area.second.x)
                    || base.x > Math.max(area.first.x, area.second.x)) return;
            const x = (Math.min(area.first.z, area.second.z) - base.z) * unit;
            const y = (base.y - Math.max(area.first.y, area.second.y)) * unit;
            const w = (Math.abs(area.second.z - area.first.z) + 1) * unit;
            const h = (Math.abs(area.second.y - area.first.y) + 1) * unit;
            const current = groupId === group.id && kind === tool;
            const g = svgNode("g", {"data-area": kind, "data-group": group.id,
                class: highlighted ? "active" : "muted"});
            const rect = svgNode("rect", {x: x + 1, y: y + 1, width: w - 2, height: h - 2});
            g.append(rect);
            if (current) {
                rect.dataset.action = "move";
                const label = svgNode("text", {x: x + 4, y: y + 11, "pointer-events": "none"});
                label.textContent = group.name;
                g.append(label);
                for (const [corner, cx, cy] of [["nw", x, y], ["ne", x + w, y],
                    ["sw", x, y + h], ["se", x + w, y + h]]) {
                    g.append(svgNode("rect", {class: "area-handle", "data-corner": corner,
                        x: cx - 3, y: cy - 3, width: 6, height: 6}));
                }
                shapes.append(g);
            } else {
                shapes.prepend(g);
            }
        }

        /** @param {object[]} groups @returns {void} */
        function draw(groups) {
            shapes.replaceChildren();
            const points = stations(groups);
            const selected = tool === "station" ? points.get(stationKey) : null;
            for (const group of groups) {
                for (const kind of ["marker", "storage"]) {
                    const area = gesture && group.id === groupId && kind === tool
                        ? gesture.result : group[kind + "Region"];
                    drawBox(group, kind, area, selected ? selected.ids.has(group.id) : group.id === groupId);
                }
            }
            const base = origin();
            for (const [key, station] of points) {
                const p = station.point.position;
                if (p.x !== base.x || !Number.isInteger(p.y) || !Number.isInteger(p.z)) continue;
                const x = (p.z - base.z) * unit + 13;
                const y = (base.y - p.y) * unit + 3;
                const picked = tool === "station" && stationKey === key;
                const g = svgNode("g", {"data-station": key,
                    class: picked || station.ids.has(groupId) ? "active" : "muted",
                    transform: `translate(${x} ${y})`});
                g.append(svgNode("rect", {x: -9, y: -7, width: 18, height: 12, rx: 4}));
                g.append(svgNode("path", {d: "M-5 -4h3v5h-3zM-5 2h3v2h-3z"
                    + "M1 -5h3v5H1zM1 1h3v2H1z", fill: "white", "pointer-events": "none"}));
                shapes.append(g);
            }
        }

        /** @returns {void} */
        function refresh() {
            if (!enabled || !region) return;
            const groups = read();
            const points = stations(groups);
            const group = groups.find(g => g.id === groupId) || groups[0];
            groupId = group?.id || "";
            if (!points.has(stationKey)) stationKey = "";
            if (typingName && group) {
                const choice = side.querySelector(`[data-group-choice="${CSS.escape(group.id)}"]`);
                if (choice) choice.textContent = group.name || "未命名绑定组";
                draw(groups);
                return;
            }
            side.replaceChildren();
            const heading = html("strong", "绑定组");
            const add = button("＋ 绑定组", () => {
                const list = read();
                groupId = crypto.randomUUID();
                tool = "marker";
                list.push({id: groupId, name: "绑定组 " + (list.length + 1),
                    markerRegion: null, storageRegion: null});
                write(list, "add", groupId);
                const name = side.querySelector(".area-group-name");
                name?.select();
            });
            side.append(heading);
            for (const item of groups) {
                const entry = button(item.name || "未命名绑定组", () => {
                    groupId = item.id;
                    if (tool === "station") tool = "marker";
                    refresh();
                }, groupId === item.id);
                entry.dataset.groupChoice = item.id;
                side.append(entry);
            }
            side.append(add);
            if (group) {
                const name = html("input");
                name.className = "area-group-name";
                name.setAttribute("aria-label", "当前绑定组名称");
                name.maxLength = 40;
                name.disabled = locked;
                name.value = group.name;
                name.addEventListener("input", () => {
                    const list = read();
                    list.find(g => g.id === groupId).name = name.value;
                    typingName = true;
                    try { write(list, "name", groupId); } finally { typingName = false; }
                });
                name.addEventListener("keydown", e => {
                    if (e.key === "Enter") { e.preventDefault(); name.blur(); }
                });
                side.append(name);
                const tools = html("div");
                tools.className = "area-tools";
                for (const kind of ["marker", "storage"]) {
                    const b = button(titles[kind], () => { tool = kind; refresh(); }, tool === kind);
                    b.dataset.areaTool = kind;
                    tools.append(b);
                }
                side.append(tools);
                const remove = button("删除此组", () => {
                    const list = read().filter(g => g.id !== groupId);
                    write(list, "remove", groupId);
                });
                remove.disabled = locked || groups.length < 2
                    || Array.from(points.values()).some(s => s.ids.has(groupId));
                if (remove.disabled) remove.title = "保留至少一组，并先解除站位关联。";
                side.append(remove);
            }
            side.append(html("strong", "站位"));
            let index = 0;
            for (const [key] of points) {
                const b = button("站位 " + (++index), () => {
                    tool = "station"; stationKey = key; refresh();
                }, tool === "station" && key === stationKey);
                side.append(b);
            }
            side.append(button("＋ 点选站位", () => {
                tool = "station"; stationKey = ""; refresh();
            }, tool === "station" && !stationKey));
            const chosen = points.get(stationKey);
            if (tool === "station" && chosen) {
                side.append(html("strong", "关联绑定组"));
                for (const item of groups) {
                    const label = html("label");
                    const check = html("input");
                    check.type = "checkbox";
                    check.checked = chosen.ids.has(item.id);
                    check.disabled = locked || (check.checked && chosen.ids.size === 1);
                    check.addEventListener("change", () => {
                        const list = read();
                        const g = list.find(value => value.id === item.id);
                        if (!validStations()) { refresh(); return; }
                        if (check.checked) {
                            g.standingPlan ??= {points: []};
                            g.standingPlan.enabled = true;
                            g.standingPlan.points.push({standingId: chosen.point.standingId,
                                position: {...chosen.point.position}, subregionsEnabled: false});
                        } else {
                            g.standingPlan.points = g.standingPlan.points.filter(p =>
                                JSON.stringify([p.standingId, p.position]) !== stationKey);
                        }
                        write(list, "stations", item.id);
                    });
                    label.append(check, document.createTextNode(item.name));
                    side.append(label);
                }
                side.append(button("删除此站位", () => {
                    if (!validStations()) return;
                    const list = read();
                    for (const g of list) {
                        if (g.standingPlan) g.standingPlan.points = g.standingPlan.points.filter(p =>
                            JSON.stringify([p.standingId, p.position]) !== stationKey);
                    }
                    stationKey = "";
                    write(list, "stations", groupId);
                }));
            }
            if (locked) status.textContent = "选区已变更，重新预览后可标注。";
            else if (tool === "station") status.textContent = !validStations()
                ? "请先在下方设置中补全已有站位的关联组。"
                : stationKey ? "点空格可移动所选站位；在侧栏勾选它负责的绑定组。"
                    : "点击脚下方块设置站位，点击脚印编辑关联组。";
            else status.textContent = `拖框绘制${titles[tool]}；拖动框内平移，拖动角点调整范围。`;
            draw(groups);
        }

        /** @param {object} pos @returns {void} */
        function markStation(pos) {
            if (!validStations()) return;
            const groups = read();
            const g = groups.find(item => item.id === groupId);
            if (!g) return;
            const points = stations(groups);
            const same = Array.from(points).find(([, s]) => {
                const p = s.point.position;
                return p.x === pos.x && p.y === pos.y && p.z === pos.z;
            });
            if (same) { stationKey = same[0]; refresh(); return; }
            if (stationKey && points.has(stationKey)) {
                const identity = points.get(stationKey).point.standingId;
                for (const group of groups) {
                    for (const p of group.standingPlan?.points || []) {
                        if (JSON.stringify([p.standingId, p.position]) === stationKey) {
                            p.position = {...pos};
                        }
                    }
                }
                stationKey = JSON.stringify([identity, pos]);
            } else {
                const id = crypto.randomUUID();
                g.standingPlan ??= {points: []};
                g.standingPlan.enabled = true;
                g.standingPlan.points.push({standingId: id, position: pos, subregionsEnabled: false});
                stationKey = JSON.stringify([id, pos]);
            }
            write(groups, "stations", groupId);
        }

        /** @param {object} end @returns {object} */
        function proposed(end) {
            const {start, old, action} = gesture;
            if (action === "move") {
                const shift = p => ({x: p.x, y: p.y + end.y - start.y, z: p.z + end.z - start.z});
                return {first: shift(old.first), second: shift(old.second)};
            }
            if (action && old) {
                const normal = box(old.first, old.second, old);
                const anchor = {z: action.endsWith("w") ? normal.second.z : normal.first.z,
                    y: action.startsWith("n") ? normal.first.y : normal.second.y};
                return box(anchor, end, old);
            }
            return box(start, end, old);
        }

        /** @returns {void} */
        function cancel() {
            if (!gesture) return;
            const id = gesture.id;
            gesture = null;
            if (layer.hasPointerCapture(id)) layer.releasePointerCapture(id);
        }

        layer.addEventListener("pointerdown", event => {
            if (locked || gesture || event.button !== 0) return;
            event.preventDefault();
            const pos = location(event);
            if (tool === "station") {
                const key = event.target.closest("[data-station]")?.dataset.station;
                if (key) { stationKey = key; refresh(); }
                else markStation(pos);
                return;
            }
            const group = read().find(g => g.id === groupId);
            if (!group) return;
            const old = group[tool + "Region"];
            const action = event.target.dataset.corner || event.target.dataset.action;
            gesture = {id: event.pointerId, start: pos, action, old};
            gesture.result = action ? old : box(pos, pos, old);
            layer.setPointerCapture(event.pointerId);
            draw(read());
        });
        layer.addEventListener("pointermove", event => {
            if (gesture?.id !== event.pointerId) return;
            gesture.result = proposed(location(event));
            draw(read());
        });
        layer.addEventListener("pointerup", event => {
            if (gesture?.id !== event.pointerId) return;
            const end = location(event);
            const {start, action} = gesture;
            if (action && start.y === end.y && start.z === end.z) {
                cancel();
                refresh();
                return;
            }
            const bounds = svg.viewBox.baseVal;
            const base = origin();
            const area = proposed(end);
            if (!action && tool === "storage" && end.y === start.y && end.z === start.z) {
                for (const node of svg.querySelectorAll('[data-kind="chest"]')) {
                    if (node.style.display === "none") continue;
                    const t = node.transform.baseVal.getItem(0).matrix;
                    const z = base.z + t.e / unit;
                    if (end.y === base.y - t.f / unit && [z, z + 1].includes(end.z)) {
                        area.first.z = z;
                        area.second.z = z + 1;
                        break;
                    }
                }
            }
            cancel();
            const outside = p => p.z < base.z || p.z >= base.z + bounds.width / unit
                || p.y > base.y || p.y <= base.y - bounds.height / unit;
            if (outside(area.first) || outside(area.second)) { refresh(); return; }
            const groups = read();
            groups.find(g => g.id === groupId)[tool + "Region"] = area;
            write(groups, tool, groupId);
        });
        root.addEventListener("keydown", event => {
            if (event.key === "Escape" && gesture) { cancel(); refresh(); }
        });
        for (const type of ["pointercancel", "lostpointercapture"]) {
            layer.addEventListener(type, event => {
                if (gesture?.id === event.pointerId) { cancel(); refresh(); }
            });
        }

        /** @returns {void} */
        function syncMode() {
            figure.dataset.canvasMode = enabled ? "areas" : "blocks";
            side.hidden = status.hidden = !enabled;
            footer.hidden = palette.hidden = enabled;
            for (const b of modes) {
                const current = b.dataset.canvasMode === figure.dataset.canvasMode;
                b.setAttribute("aria-pressed", String(current));
            }
            if (enabled) svg.append(layer);
            else layer.remove();
            for (const n of svg.querySelectorAll("[data-piece], .classic-grid")) {
                if (enabled) n.setAttribute("tabindex", "-1");
                else if (n.dataset.piece && n.style.display !== "none" && !locked) {
                    n.setAttribute("tabindex", "0");
                }
            }
            refresh();
        }
        for (const b of modes) {
            b.addEventListener("click", () => {
                cancel();
                if (!enabled) blockGridTab = svg.querySelector(".classic-grid").getAttribute("tabindex");
                enabled = b.dataset.canvasMode === "areas";
                syncMode();
                if (!enabled && !locked) svg.querySelector(".classic-grid")
                    .setAttribute("tabindex", blockGridTab);
            });
        }

        /** @param {object|null} value @param {boolean} stale @returns {void} */
        function render(value, stale) {
            cancel();
            region = value;
            locked = !value || stale;
            syncMode();
        }

        /** @returns {void} */
        function reset() {
            cancel();
            enabled = false;
            groupId = stationKey = "";
            tool = "marker";
        }
        return {render, refresh, reset};
    }
    window.StowlingScanAnnotations = {create};
})();
