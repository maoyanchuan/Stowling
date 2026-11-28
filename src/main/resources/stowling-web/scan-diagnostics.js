(() => {
    "use strict";

    const PATH = "/v1/category-zones/scan-diagnostics";
    const OPERATIONS = {PREVIEW: "预览扫描", CONFIRM: "确认母版", SAVE: "保存核对"};
    const STATES = {SUCCEEDED: "成功", PARTIAL: "部分跳过", FAILED: "失败"};

    /** @param {object} scan @returns {string} */
    function label(scan) {
        return `${OPERATIONS[scan.operation] || "扫描"} · ${STATES[scan.state] || scan.state}`;
    }

    /** @param {object} point @returns {string} */
    function position(point) {
        return point ? `(${point.x}, ${point.y}, ${point.z})` : "无";
    }

    /** @param {object} area @returns {string} */
    function bounds(area) {
        return area ? `(${area.firstX}, ${area.firstY}, ${area.firstZ}) → `
            + `(${area.secondX}, ${area.secondY}, ${area.secondZ})` : "未采集";
    }

    /** @returns {Promise<object>} */
    async function history() {
        return window.Stowling.request(PATH);
    }

    /** @param {string} id @returns {Promise<object>} */
    async function detail(id) {
        return window.Stowling.request(`${PATH}/${encodeURIComponent(id)}`);
    }

    /** @param {object} entry @returns {{text: string, filename: string}} */
    function output(entry) {
        const version = String(entry.versions?.stowling || "unknown")
            .replace(/[^a-zA-Z0-9._-]/g, "_");
        const date = String(entry.summary.finishedAt).replace(/[^a-zA-Z0-9-]/g, "-");
        return {text: JSON.stringify(entry, null, 4),
            filename: `stowling-scan-log-${entry.summary.id}-${version}-${date}.json`};
    }

    /** @param {string} id @returns {Promise<void>} */
    async function download(id) {
        const file = output(await detail(id));
        const url = URL.createObjectURL(new Blob([file.text], {type: "application/json"}));
        const link = document.createElement("a");
        link.href = url;
        link.download = file.filename;
        document.body.append(link);
        link.click();
        link.remove();
        window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    }

    /** @param {object} entry @returns {Array<{title: string, text: string}>} */
    function stages(entry) {
        return (entry.stages || []).map(stage => {
            const data = stage.data;
            switch (stage.stage) {
                case "WORLD":
                    return {title: "世界采集", text: `${data.blocks} 个方块／实体，`
                        + `${data.markers} 个展示标记，${data.attachments} 个支撑关系`};
                case "LAYOUT_CANDIDATES":
                    return {title: "布局候选（按评分排序，最多 16 个）", text: data.map(value =>
                        `${value.axis} 轴 · 步长 ${value.step} · ${value.count} 片 · `
                        + `母版第 ${value.mother + 1} 片 · 命中 ${value.hits} · 评分 ${value.score}`
                    ).join("\n") || "无候选布局"};
                case "MARKER_CANDIDATES":
                    return {title: `第 ${data.group} 组候选${data.ambiguous ? " · 归属有歧义" : ""}`,
                        text: `储存区 ${bounds(data.storageArea)}\n已分配：`
                            + (data.selected || []).map(choice => position(choice.anchor))
                                .join("、") + "\n"
                            + (data.choices || []).map(choice => `${position(choice.anchor)}`
                                + ` · 评分 ${choice.score} · 覆盖第 `
                                + (choice.slices || []).map(index => index + 1).join(", ")
                                + " 片").join("\n")};
                case "FINAL_TEMPLATE":
                    return {title: "最终母版", text: `${data.axis} 轴 · 步长 ${data.step} · `
                        + `${data.templates.length} 组 · 逻辑候选位置见完整导出`};
                case "SLICE_RESULTS":
                    return {title: "逐片核对", text: data.map(slice =>
                        `第 ${slice.index + 1} 片 ${position(slice.origin)} · `
                        + (slice.reason || "通过")).join("\n")};
                case "SLICE_MARKERS":
                    return {title: `第 ${data.slice + 1} 片实际识别标记`, text: data.groups.map(
                        (group, index) => `第 ${index + 1} 组 · 识别区 ${bounds(group.markerArea)}`
                            + ` · 储存区 ${bounds(group.storageArea)}\n`
                            + (group.markers.map(marker => `  ${position(marker.position)} `
                                + `${marker.itemId} · ${marker.source}`).join("\n") || "  无有效标记")
                    ).join("\n")};
                case "SLICE_CATEGORIES":
                    return {title: `第 ${data.slice + 1} 片物品与储存绑定`,
                        text: data.message + "\n" + Object.entries(data.categories).map(
                            ([id, targets]) => `${id} → `
                                + targets.map(target => position(target.position)).join("、")
                        ).join("\n")};
                default:
                    return {title: stage.stage === "ERROR" ? "失败原因" : "扫描结果",
                        text: data.message || JSON.stringify(data)};
            }
        });
    }

    /** @param {object} entry @param {string} query @returns {string[]} */
    function worldRows(entry, query) {
        const world = entry.world;
        if (!world) return [];
        const rows = (world.blocks || []).map(block =>
            `${position(block.position)} ${block.blockId} · 朝向 ${block.facing}`
            + (block.partner ? ` · 箱体另一半 ${position(block.partner)}` : ""));
        for (const marker of world.markers || []) {
            rows.push(`${position(marker.position)} 展示物 ${marker.itemId} · ${marker.source}`);
        }
        for (const value of world.attachments || []) {
            rows.push(`${position(value.position)} 展示物 → 支撑 ${position(value.anchor)}`);
        }
        const tokens = query.toLowerCase().split(/[\s,，()]+/).filter(Boolean);
        return rows.filter(row => tokens.every(token => row.toLowerCase().includes(token)));
    }

    /** @param {HTMLElement} root @param {Function} reveal @returns {object} */
    function mount(root, reveal) {
        const UI = window.Stowling;
        const toggle = root.querySelector("#scan-log-toggle");
        const panel = root.querySelector("#scan-log-content");
        const list = root.querySelector("#scan-log-history");
        const message = root.querySelector("#scan-log-message");
        const body = root.querySelector("#scan-log-detail");
        const refreshButton = root.querySelector("#scan-log-refresh");
        const exportButton = root.querySelector("#scan-log-export");
        let scans = [];
        let selected = "";
        let expanded = false;
        let revision = 0;
        let selectionRevision = 0;

        /** @param {object} entry @returns {void} */
        function render(entry) {
            body.replaceChildren();
            body.append(UI.element("p", "scan-log-summary", `${label(entry.summary)} · `
                + `${entry.summary.message} · ${entry.summary.elapsedMillis} ms`),
            UI.element("p", "scan-log-meta", `选区 ${bounds(entry.summary.area)}`));
            for (const stage of stages(entry)) {
                const item = UI.element("details", "scan-log-stage");
                item.open = ["逐片核对", "失败原因"].includes(stage.title);
                item.append(UI.element("summary", "", stage.title),
                    UI.element("pre", "", stage.text));
                body.append(item);
            }
            if (entry.droppedStages) body.append(UI.element("p", "scan-log-meta",
                `阶段记录达到上限，省略 ${entry.droppedStages} 条；世界快照与最终结果仍完整保留。`));
            if (!entry.world) return;
            const section = UI.element("section", "scan-log-world");
            const filter = UI.element("input");
            filter.type = "search";
            filter.id = "scan-log-filter";
            filter.placeholder = "输入坐标或方块 ID，例如 222 -23 -74";
            const caption = UI.element("label", "field-label", "现场方块与展示物");
            caption.htmlFor = filter.id;
            const count = UI.element("p", "scan-log-meta");
            const rows = UI.element("pre", "scan-log-world-rows");
            /** @returns {void} */
            function filterRows() {
                const matches = worldRows(entry, filter.value);
                count.textContent = `${matches.length} 条匹配，最多显示 80 条；完整数据请导出。`;
                rows.textContent = matches.slice(0, 80).join("\n") || "没有匹配记录";
            }
            filter.addEventListener("input", filterRows);
            filterRows();
            section.append(caption, filter, count, rows);
            body.append(section);
        }

        /** @param {string} id @returns {Promise<void>} */
        async function select(id) {
            selected = id;
            const current = ++selectionRevision;
            for (const button of list.querySelectorAll("button")) {
                button.setAttribute("aria-pressed", String(button.dataset.scanId === id));
            }
            exportButton.disabled = !id;
            body.replaceChildren();
            if (!id) return;
            message.textContent = "正在读取扫描日志…";
            try {
                const entry = await detail(id);
                if (current !== selectionRevision) return;
                render(entry);
                message.textContent = "";
            } catch (error) {
                if (current !== selectionRevision) return;
                message.textContent = error.message || "读取日志失败，请刷新后重试。";
            }
        }

        /** @param {string} [preferred] @returns {Promise<void>} */
        async function refresh(preferred = "") {
            const current = ++revision;
            refreshButton.disabled = true;
            try {
                const result = await history();
                if (current !== revision) return;
                scans = result.scans || [];
                list.replaceChildren();
                for (const scan of scans) {
                    const button = UI.element("button", "scan-log-choice",
                        `${new Date(scan.finishedAt).toLocaleTimeString("zh-CN", {hour12: false})}`
                            + ` · ${label(scan)} · ${scan.message}`);
                    button.type = "button";
                    button.dataset.scanId = scan.id;
                    button.addEventListener("click", () => void select(scan.id));
                    list.append(button);
                }
                const id = [preferred, selected].find(value => scans.some(scan => scan.id === value))
                    || scans[0]?.id || "";
                selected = id;
                exportButton.disabled = !id;
                if (expanded) await select(id);
                if (!scans.length) message.textContent = "暂无扫描日志，请先开始扫描。";
            } catch (error) {
                if (current === revision) message.textContent = error.message || "读取日志失败。";
            } finally {
                if (current === revision) refreshButton.disabled = false;
            }
        }

        toggle.addEventListener("click", () => {
            expanded = !expanded;
            toggle.setAttribute("aria-expanded", String(expanded));
            reveal(panel, expanded, true);
            if (expanded) void refresh();
        });
        refreshButton.addEventListener("click", () => void refresh());
        exportButton.addEventListener("click", async () => {
            if (!selected) return;
            exportButton.disabled = true;
            try { await download(selected); }
            catch (error) { message.textContent = error.message || "导出失败，请重试。"; }
            finally { exportButton.disabled = !selected; }
        });
        reveal(panel, false, false);
        return {refresh};
    }

    window.StowlingScanDiagnostics = Object.freeze({history, detail, download, output, label,
        stages, worldRows, mount});
})();
