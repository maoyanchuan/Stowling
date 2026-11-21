(() => {
    "use strict";
    const S = window.Stowling;
    const Model = globalThis.StowlingWarehouseModel;
    const PAGE_SIZE = 50;
    const area = document.querySelector("#inventory-area");
    const query = document.querySelector("#inventory-query");
    const list = document.querySelector("#inventory-list");
    const metrics = document.querySelector("#inventory-metrics");
    const message = document.querySelector("#storage-message");
    const refresh = document.querySelector("#refresh-storage-button");
    const previous = document.querySelector("#inventory-previous");
    const next = document.querySelector("#inventory-next");
    const pageLabel = document.querySelector("#inventory-page");
    let snapshot = {containers: [], areas: []};
    let page = 0;
    let busy = false;

    /** @returns {Promise<void>} */
    async function load() {
        if (busy || !S.isConnected()) return;
        busy = true;
        refresh.disabled = true;
        message.textContent = "正在读取 Chest Tracker 记录…";
        try {
            const response = await S.request("/v1/warehouse-inventory");
            snapshot = response;
            const selectedArea = area.value;
            area.replaceChildren(new Option("整个仓库", ""));
            snapshot.areas.forEach(value => area.append(new Option(value.name, value.id)));
            area.value = snapshot.areas.some(value => value.id === selectedArea) ? selectedArea : "";
            message.classList.remove("error");
            message.textContent =
                snapshot.message + " · 页面读取：" + S.formatDate(snapshot.readAt);
            render();
        } catch (error) {
            message.classList.add("error");
            message.textContent = "库存读取失败；当前列表保留上次成功读取的数据。";
            S.handleError(error, "读取库存失败");
        } finally {
            busy = false;
            refresh.disabled = false;
        }
    }

    /** @returns {void} */
    function render() {
        const containers = Model.containers(snapshot, {areaId: area.value});
        const rows = Model.aggregate(containers, query.value);
        metrics.replaceChildren();
        const full = containers.reduce((sum, value) => sum + value.fullBoxes, 0);
        const partial = containers.reduce((sum, value) => sum + value.partialBoxes, 0);
        const empty = containers.reduce((sum, value) => sum + value.emptyBoxes, 0);
        for (const [label, value] of [["物品条目", rows.length], ["已记录容器", containers.length],
                ["满盒", full], ["未满盒", partial], ["空盒", empty]]) {
            const metric = S.element("div", "metric");
            metric.append(S.element("span", "muted compact-help", label),
                S.element("strong", "", String(value)));
            metrics.append(metric);
        }
        const pages = Math.max(1, Math.ceil(rows.length / PAGE_SIZE));
        page = Math.min(page, pages - 1);
        previous.disabled = page === 0;
        next.disabled = page === pages - 1;
        pageLabel.textContent = `第 ${page + 1} / ${pages} 页 · ${rows.length} 条`;
        list.replaceChildren();
        if (rows.length === 0) {
            list.append(S.emptyState(snapshot.state !== "READY" ? snapshot.message
                : snapshot.containers.length === 0 ? "尚无容器记录，请先在游戏中打开仓库箱子。"
                    : "当前范围或搜索条件下没有物品。"));
            return;
        }
        for (const item of rows.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE)) {
            const card = document.createElement("details");
            card.className = "warehouse-item";
            const summary = document.createElement("summary");
            const heading = S.element("div", "warehouse-item-heading");
            const title = S.element("div", "warehouse-item-name");
            title.append(S.element("strong", "", item.name),
                S.element("small", "muted", item.itemId));
            heading.append(itemIcon(item), title);
            summary.append(heading, numberCell("总数量", item.total), numberCell("散装", item.loose),
                numberCell("盒内", item.boxed), numberCell("容器", item.locations.length));
            const locations = S.element("div", "warehouse-locations");
            for (const location of item.locations) {
                const container = location.container;
                const position = container.positions[0];
                const row = S.element("div", "warehouse-location");
                row.append(S.element("strong", "", container.name),
                    S.element("span", "", `${position.x}, ${position.y}, ${position.z}`),
                    S.element("span", "", `散装 ${format(location.loose)} · `
                        + `盒内 ${format(location.boxed)}`),
                    S.element("span", "muted compact-help", "记录时间："
                        + (container.recordedAt ? S.formatDate(container.recordedAt) : "未知")));
                locations.append(row);
            }
            card.append(summary, locations);
            list.append(card);
        }
    }

    /** @param {{name: string, itemId: string, icon?: string}} item @returns {HTMLElement} */
    function itemIcon(item) {
        const icon = S.element("span", "warehouse-item-icon");
        const image = S.element("img");
        const initials = Array.from(item.name).slice(0, 2).join("");
        const fallback = S.element("span", "", initials);
        icon.setAttribute("aria-hidden", "true");
        image.alt = "";
        image.hidden = true;
        icon.append(image, fallback);
        void S.itemIcon(item.icon || item.itemId).then(source => {
            if (!source) return;
            image.src = source;
            image.hidden = false;
            fallback.hidden = true;
        });
        return icon;
    }

    /** @param {string} label @param {number} value @returns {HTMLElement} */
    function numberCell(label, value) {
        const cell = S.element("div", "warehouse-number");
        cell.append(S.element("small", "muted compact-help", label),
            S.element("strong", "", format(value)));
        return cell;
    }

    /** @param {number} value @returns {string} */
    function format(value) {
        return Number(value).toLocaleString("zh-CN");
    }

    area.addEventListener("change", () => {page = 0; render();});
    query.addEventListener("input", () => {page = 0; render();});
    previous.addEventListener("click", () => {page--; render();});
    next.addEventListener("click", () => {page++; render();});
    refresh.addEventListener("click", () => void load());
    window.setInterval(() => {
        if (document.visibilityState === "visible") void load();
    }, 15000);
    S.onConnected(() => void load());
    S.mountPage("storage");
})();
