(() => {
    "use strict";

    const Model = window.StowlingCoordinateEditorModel;
    const copy = Model.copy;

    /** @param {object|null} region @returns {string} */
    function regionKey(region) {
        if (!region) return "";
        return JSON.stringify(["x", "y", "z"].map(axis => [
            Math.min(region.first[axis], region.second[axis]),
            Math.max(region.first[axis], region.second[axis])
        ]));
    }

    /** @param {object} area @returns {object} */
    function region(area) {
        return {first: {x: area.firstX, y: area.firstY, z: area.firstZ},
            second: {x: area.secondX, y: area.secondY, z: area.secondZ}};
    }

    /** @param {HTMLElement} root @param {object} helpers @returns {object} */
    function create(root, helpers) {
        const modeRow = root.querySelector("#category-setup-row");
        const mode = root.querySelector("#category-setup-mode");
        const panel = root.querySelector("#category-scan-panel");
        const status = root.querySelector("#category-scan-status");
        const start = root.querySelector("#category-scan-start");
        const apply = root.querySelector("#category-scan-apply");
        const discard = root.querySelector("#category-scan-discard");
        const toggle = root.querySelector("#category-generated-toggle");
        const details = root.querySelector("#category-details");
        const configuration = root.querySelector("#category-configuration");
        const layer = root.querySelector("#category-scan-layer");
        const results = root.querySelector("#category-scan-results");
        const diagnostics = window.StowlingScanDiagnostics.mount(root, helpers.reveal);
        root.querySelector("#category-configuration-content").append(
            root.querySelector(".category-options"), root.querySelector(".category-binding-section"));
        let draft = null;
        let groups = [];
        let baseline = null;
        let manual = null;
        let expanded = false;
        let applied = false;
        let pending = false;
        let generation = 0;
        let selectedId = "";
        let active = false;
        let plane = 0;
        let size = null;
        let blocks = new Map();
        let visible = new Set();
        let synchronizing = false;
        let scanOptions = null;
        let previousMode = "manual";
        let appliedSnapshot = null;
        const canvas = window.StowlingScanCanvas.create(root, changeBlocks);
        const annotations = window.StowlingScanAnnotations.create(root, () => {
            syncGroups();
            return groups.map(viewGroup);
        }, values => {
            groups = values.map(viewGroup);
            writeGroups();
            changed();
        }, () => helpers.validStations());
        const syncSelect = helpers.bindMode(modeRow, () => {
            generation++;
            pending = false;
            if (previousMode === "manual") {
                manual = {bindings: helpers.readBindings() || manual.bindings,
                    options: helpers.readOptions()};
            }
            if (mode.value === "manual") {
                syncGroups();
                helpers.writeBindings(copy(manual.bindings));
                helpers.writeOptions(copy(manual.options));
            } else if (draft) {
                writeGroups();
                helpers.writeOptions(copy(scanOptions || manual.options));
            }
            helpers.changed();
            previousMode = mode.value;
            update();
        });

        /** @returns {object|null} */
        function currentRegion() {
            const fields = [...root.querySelectorAll('[id^="category-overall-"]')];
            if (fields.some(field => !field.value.trim() || !field.validity.valid)) return null;
            return helpers.readRegion();
        }

        /** @param {object} point @returns {object} */
        function viewPoint(point) {
            return draft?.axis === "Z" ? {...point, x: point.z, z: point.x} : {...point};
        }

        /** @param {object|null} value @returns {object|null} */
        function viewRegion(value) {
            return value ? {first: viewPoint(value.first), second: viewPoint(value.second)} : null;
        }

        /** @param {object} value @returns {object} */
        function viewGroup(value) {
            const result = copy(value);
            result.markerRegion = viewRegion(value.markerRegion);
            result.storageRegion = viewRegion(value.storageRegion);
            for (const point of result.standingPlan?.points || []) {
                point.position = viewPoint(point.position);
                if (draft.axis === "Z") {
                    for (const key of ["markerArea", "storageArea"]) {
                        if (!point[key]) continue;
                        for (const corner of ["first", "second"]) {
                            const x = point[key][corner + "X"];
                            point[key][corner + "X"] = point[key][corner + "Z"];
                            point[key][corner + "Z"] = x;
                        }
                    }
                }
            }
            return result;
        }

        /** @returns {object|null} */
        function mother() {
            if (!draft) return null;
            const value = region(draft.area);
            for (const axis of ["x", "y", "z"]) {
                const values = [value.first[axis], value.second[axis]];
                value.first[axis] = Math.min(...values);
                value.second[axis] = Math.max(...values);
            }
            const axis = draft.axis.toLowerCase();
            value.first[axis] += draft.templateIndex * draft.step;
            value.second[axis] = Math.min(value.second[axis], value.first[axis] + draft.step - 1);
            return value;
        }

        /** @returns {void} */
        function syncGroups() {
            if (!draft || mode.value !== "scan" || synchronizing) return;
            const fields = [...configuration.querySelectorAll('input[type="number"][required]')];
            if (fields.some(input => !input.value.trim() || !input.validity.valid)) return;
            groups = helpers.readBindings() || groups;
        }

        /** @returns {void} */
        function writeGroups() {
            synchronizing = true;
            helpers.writeBindings(groups.map(group => ({...group,
                markerRegion: group.markerRegion || helpers.emptyRegion(),
                storageRegion: group.storageRegion || helpers.emptyRegion()})));
            synchronizing = false;
        }

        /** @returns {object} */
        function scanPayload() {
            syncGroups();
            if (!draft || groups.some(group => !group.markerRegion || !group.storageRegion)) {
                throw new Error("请为每个母版组完整绘制识别区和储存区。");
            }
            const value = {kind: "category-zone", name: "扫描母版", setupMode: "scan",
                scan: {...copy(draft), blocks: [...blocks.values()], canvasSize: size},
                overallRegion: currentRegion(), bindings: groups,
                categoryOptions: helpers.readOptions()};
            const payload = Model.payload(value);
            return {area: payload.overallArea, scan: payload.scan,
                categoryOptions: payload.categoryOptions};
        }

        /** @returns {boolean} */
        function fresh() {
            return Boolean(draft && regionKey(region(draft.area)) === regionKey(currentRegion()));
        }

        /** @returns {boolean} */
        function canSave() {
            return !active || mode.value !== "scan" || Boolean(applied && fresh() && !pending);
        }

        /** @param {boolean} [animate] @returns {void} */
        function update(animate = true) {
            const scanning = active && mode.value === "scan";
            const showDetails = active && (!scanning || Boolean(draft && expanded));
            helpers.reveal(panel, scanning, animate);
            helpers.reveal(details, showDetails, animate);
            configuration.disabled = !showDetails;
            toggle.hidden = !scanning || !draft;
            toggle.setAttribute("aria-expanded", String(showDetails));
            const valid = fresh();
            start.disabled = pending || !currentRegion();
            start.textContent = pending ? "正在读取游戏选区…" : draft ? "重新扫描" : "开始扫描";
            apply.disabled = pending || !valid || groups.some(group =>
                !group.markerRegion || !group.storageRegion);
            apply.textContent = applied ? "已应用当前母版" : "确认母版并应用";
            if (applied) apply.disabled = true;
            root.querySelector(".category-scan-actions").hidden = !scanning || !draft;
            discard.disabled = pending;
            panel.setAttribute("aria-busy", String(pending));
            const bounds = mother();
            const viewed = bounds ? viewRegion(bounds) : null;
            root.dataset.scanAxis = draft?.axis || "X";
            if (viewed) {
                layer.min = String(viewed.first.x);
                layer.max = String(viewed.second.x);
                plane = Math.max(viewed.first.x, Math.min(viewed.second.x, plane));
                layer.value = String(plane);
                viewed.first.x = viewed.second.x = plane;
            }
            layer.closest("label").hidden = !scanning || !viewed
                || mother()?.first[draft.axis.toLowerCase()]
                    === mother()?.second[draft.axis.toLowerCase()];
            layer.disabled = pending || !valid;
            const positions = {};
            visible = new Set();
            if (viewed) {
                for (const [id, block] of blocks) {
                    let p = viewPoint(block.position);
                    const partner = block.partner ? viewPoint(block.partner) : null;
                    const fromPartner = p.x !== plane && partner?.x === plane;
                    if (fromPartner) p = partner;
                    if (p.x !== plane) continue;
                    visible.add(id);
                    positions[id] = {kind: block.kind === "chest" && partner?.x !== p.x
                        ? "single" : block.kind,
                    x: p.z - viewed.first.z, y: viewed.second.y - p.y, turns: block.turns,
                    upper: block.upper, source: copy(block), fromPartner};
                }
            }
            canvas.render(scanning ? viewed : null, positions, pending || !valid, !applied, size);
            annotations.render(scanning ? viewed : null, pending || !valid);
            results.replaceChildren();
            for (const slice of draft?.slices || []) {
                if (!slice.reason) continue;
                const row = document.createElement("li");
                const p = slice.origin;
                row.textContent = `第 ${slice.index + 1} 片 (${p.x}, ${p.y}, ${p.z})：${slice.reason}`;
                results.append(row);
            }
            if (!pending) {
                const good = draft?.slices?.filter(slice => !slice.reason).length || 0;
                status.textContent = !currentRegion() ? "请填写总选区的 6 个整数坐标。"
                    : draft && !valid ? "总选区已改变，旧扫描已过期，请重新扫描。"
                        : !draft ? "填写总选区后开始扫描。需保持游戏世界与完整选区已加载。"
                            : applied ? `已应用：${good} / ${draft.sliceCount} 片有效。异常片留空，可保存。`
                                : "母版待确认。摆放方块校正几何，区域与站位调整展示位和绑定；"
                                    + "确认时重新读取每片现场标记。";
            }
            helpers.setSaveEnabled(canSave());
        }

        /** @returns {void} */
        function changed() {
            if (pending) { generation++; pending = false; }
            applied = false;
            if (draft) draft.slices = [];
            helpers.changed();
            update(false);
        }

        /** @param {object} positions @param {object|null} nextSize @returns {void} */
        function changeBlocks(positions, nextSize) {
            if (!draft) return;
            const view = viewRegion(mother());
            const next = new Map(blocks);
            for (const id of visible) if (!positions[id]) next.delete(id);
            for (const [id, piece] of Object.entries(positions)) {
                if (!piece) { next.delete(id); continue; }
                const old = piece.source || blocks.get(id);
                let p = viewPoint({x: plane, y: view.second.y - piece.y,
                    z: view.first.z + piece.x});
                const depthPair = old?.partner && viewPoint(old.partner).x
                    !== viewPoint(old.position).x;
                let partner = null;
                if (depthPair) {
                    const anchor = piece.fromPartner ? old.partner : old.position;
                    const delta = {x: p.x - anchor.x, y: p.y - anchor.y, z: p.z - anchor.z};
                    p = {x: old.position.x + delta.x, y: old.position.y + delta.y,
                        z: old.position.z + delta.z};
                    partner = {x: old.partner.x + delta.x, y: old.partner.y + delta.y,
                        z: old.partner.z + delta.z};
                } else if (piece.kind === "chest") {
                    partner = viewPoint({x: plane, y: view.second.y - piece.y,
                        z: view.first.z + piece.x + 1});
                }
                const ids = {single: "chest", chest: "chest", barrel: "barrel",
                    slab: "quartz_slab", stairs: "quartz_stairs", glass: "glass",
                    checker: "black_concrete", quartz: "quartz_block"};
                next.set(id, {position: p, partner,
                    blockId: old?.blockId || "minecraft:" + ids[piece.kind],
                    kind: depthPair ? "chest" : piece.kind,
                    facing: piece.kind === "stairs"
                        ? ["north", "east", "south", "west"][piece.turns || 0]
                        : old?.facing || (draft.axis === "X" ? "east" : "north"),
                    turns: piece.turns || 0, upper: Boolean(piece.upper)});
            }
            const bounds = mother();
            const occupied = new Set();
            for (const block of next.values()) {
                for (const point of [block.position, block.partner].filter(Boolean)) {
                    const key = JSON.stringify(point);
                    if (occupied.has(key) || ["x", "y", "z"].some(axis =>
                        point[axis] < bounds.first[axis] || point[axis] > bounds.second[axis])) {
                        update(false);
                        status.textContent = "方块越出母版范围或与其他方块重叠，已恢复原位置。";
                        return;
                    }
                    occupied.add(key);
                }
            }
            blocks = next;
            size = nextSize;
            changed();
        }

        /** @param {object} scan @returns {void} */
        function acceptScan(scan) {
            draft = copy(scan);
            groups = Model.scanBindings(scan);
            blocks = new Map(scan.blocks.map((block, index) => ["block-" + index, copy(block)]));
            plane = viewRegion(mother()).first.x;
            size = scan.canvasSize || null;
            canvas.reset();
            annotations.reset();
            writeGroups();
        }

        /** @param {boolean} confirming @returns {Promise<void>} */
        async function requestScan(confirming) {
            if (pending || !currentRegion()) return;
            let request;
            try {
                if (confirming) request = scanPayload();
                else {
                    const value = currentRegion();
                    request = {area: {firstX: value.first.x, firstY: value.first.y,
                        firstZ: value.first.z, secondX: value.second.x,
                        secondY: value.second.y, secondZ: value.second.z},
                    categoryOptions: {...helpers.readOptions(), recognitionMode: "marker"}};
                    delete request.categoryOptions.expansionDirection;
                    delete request.categoryOptions.expansionStep;
                }
            } catch (error) { status.textContent = error.message; return; }
            const key = regionKey(currentRegion());
            const requestId = ++generation;
            let failure = "";
            let diagnosticId = "";
            pending = true;
            status.textContent = confirming ? "正在按最终母版逐片核对…" : "正在读取游戏选区…";
            update(false);
            try {
                const result = await window.Stowling.request("/v1/category-zones/scan", {
                    method: "POST", headers: {"Content-Type": "application/json"},
                    body: JSON.stringify(request)
                });
                diagnosticId = result.diagnosticId || "";
                if (requestId !== generation || mode.value !== "scan"
                        || key !== regionKey(currentRegion())) return;
                acceptScan(result.scan);
                scanOptions = copy(request.categoryOptions);
                helpers.writeOptions(request.categoryOptions);
                applied = confirming && result.bindings.length > 0;
                if (applied) appliedSnapshot = {scan: copy(draft),
                    bindings: copy(groups), options: copy(request.categoryOptions)};
                helpers.changed();
                if (confirming && !result.bindings.length) {
                    failure = "所有片均未通过核对，请根据下方差异校正母版。";
                }
            } catch (error) {
                if (requestId === generation) failure = error.message || "扫描失败，请重试。";
            } finally {
                void diagnostics.refresh(diagnosticId);
                if (requestId === generation) {
                    pending = false;
                    update(false);
                    if (failure) status.textContent = failure;
                }
            }
        }

        /** @param {object} value @param {string} kind @returns {void} */
        function load(value, kind) {
            if (kind === "category-zone") void diagnostics.refresh();
            generation++;
            pending = false;
            selectedId = value.id;
            active = kind === "category-zone";
            baseline = copy(value);
            manual = {bindings: copy(value.bindings || []), options: helpers.readOptions()};
            draft = null;
            expanded = false;
            applied = false;
            scanOptions = helpers.readOptions();
            appliedSnapshot = null;
            if (active && value.scan) {
                acceptScan(value.scan);
                applied = value.scanApplied ?? Boolean(value.scan.slices?.length);
                if (applied) appliedSnapshot = {scan: copy(draft), bindings: copy(groups),
                    options: copy(scanOptions)};
            }
            mode.value = value.setupMode || (value.scan ? "scan" : "manual");
            previousMode = mode.value;
            syncSelect();
            update(false);
        }

        /** @returns {object} */
        function snapshot() {
            if (mode.value !== "scan") return {setupMode: "manual", scan: null};
            let scan = draft ? copy(draft) : null;
            if (scan) {
                scan = scanPayload().scan;
            }
            return {setupMode: "scan", scan, scanApplied: canSave()};
        }

        start.addEventListener("click", () => requestScan(false));
        apply.addEventListener("click", () => requestScan(true));
        discard.addEventListener("click", () => {
            const restore = {...copy(baseline), ...(appliedSnapshot ? {
                scan: copy(appliedSnapshot.scan), bindings: copy(appliedSnapshot.bindings),
                categoryOptions: copy(appliedSnapshot.options), setupMode: "scan", scanApplied: true
            } : {scan: null, bindings: copy(manual.bindings),
                categoryOptions: copy(manual.options)})};
            helpers.writeBindings(restore.bindings || []);
            helpers.writeOptions(restore.categoryOptions || manual.options);
            load(restore, "category-zone");
            mode.value = "scan";
            syncSelect();
            update(false);
            start.focus();
            helpers.changed();
        });
        toggle.addEventListener("click", () => { expanded = !expanded; update(); });
        layer.addEventListener("change", () => {
            if (!layer.value || !layer.validity.valid) return;
            plane = Number(layer.value);
            canvas.reset();
            update(false);
        });
        for (const eventName of ["input", "change"]) {
            root.addEventListener(eventName, event => {
                if (synchronizing || !active || mode.value !== "scan"
                        || event.target === layer || !event.target.closest("#category-details")) return;
                syncGroups();
                if (draft) scanOptions = helpers.readOptions();
                changed();
            });
        }
        root.querySelectorAll('[id^="category-overall-"]').forEach(input => {
            input.addEventListener("input", () => { generation++; pending = false; update(false); });
        });
        // Library selection and new-resource creation invalidate outstanding reads before load().
        document.addEventListener("stowling:scan-invalidate", () => {
            generation++;
            pending = false;
        });
        root.addEventListener("click", event => {
            if (!active || !draft || mode.value !== "scan") return;
            if (event.target.closest("#add-category-binding, .category-binding-remove, "
                    + "[id$='add-rule'], [id$='add-position'], .category-rule-remove")) {
                syncGroups();
                changed();
            }
        });
        return {load, snapshot, canSave, refresh: () => update(false),
            markChanged: () => {
                if (active && draft && mode.value === "scan" && !synchronizing) {
                    syncGroups();
                    changed();
                }
            },
            selectedId: () => selectedId};
    }

    window.StowlingCoordinateScan = {create, regionKey};
})();
