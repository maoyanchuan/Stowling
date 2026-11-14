(() => {
    "use strict";

    const Stowling = window.Stowling;
    const ITEM_KIND_LABELS = new Map([
        ["BULK", "散装"],
        ["SHULKER", "盒装"],
        ["PACKED", "盒装"],
        ["STALE", "过期"],
        ["UNKNOWN", "未知"]
    ]);
    const FRESHNESS = new Map([
        ["FRESH", { text: "有效", className: "badge" }],
        ["STALE", { text: "已过期", className: "badge warning" }],
        ["UNKNOWN", { text: "有效性未知", className: "badge danger" }]
    ]);

    const elements = {
        refresh: document.querySelector("#refresh-storage-button"),
        inventoryList: document.querySelector("#inventory-list"),
        inventoryCount: document.querySelector("#inventory-count"),
        observed: document.querySelector("#inventory-observed"),
        message: document.querySelector("#storage-message"),
        filterItem: document.querySelector("#inventory-filter-item"),
        filterContainer: document.querySelector("#inventory-filter-container")
    };

    const state = {
        inventory: [],
        lastReadAt: "",
        loading: false
    };

    /**
     * @returns {Promise<void>}
     */
    async function loadInventory() {
        if (state.loading) {
            return;
        }
        state.loading = true;
        elements.refresh.disabled = true;
        elements.message.textContent = "正在读取库存快照…";
        try {
            const payload = await Stowling.request("/v1/inventory");
            const inventory = Array.isArray(payload)
                ? payload
                : Array.isArray(payload?.inventory)
                    ? payload.inventory
                    : [];
            state.inventory = inventory;
            state.lastReadAt = new Date().toISOString();
            renderInventory();
            elements.message.textContent = "库存快照已刷新；本次操作未发起盘点";
        } catch (error) {
            elements.message.textContent = "库存读取失败";
            Stowling.handleError(error, "读取库存失败");
        } finally {
            state.loading = false;
            elements.refresh.disabled = false;
        }
    }

    /**
     * @returns {void}
     */
    function renderInventory() {
        elements.inventoryList.replaceChildren();
        const filtered = state.inventory
            .map(filterSnapshot)
            .filter(Boolean)
            .sort((left, right) => {
                return String(right.capturedAt || "").localeCompare(
                    String(left.capturedAt || "")
                );
            });
        const filtering = hasActiveFilter();
        elements.inventoryCount.textContent = filtering
            ? `${filtered.length} / ${state.inventory.length}`
            : String(filtered.length);
        elements.observed.textContent = state.lastReadAt
            ? Stowling.formatDate(state.lastReadAt)
            : "--";
        if (state.inventory.length === 0) {
            elements.inventoryList.append(
                Stowling.emptyState("已有库存尚未写入")
            );
            return;
        }
        if (filtered.length === 0) {
            elements.inventoryList.append(
                Stowling.emptyState("没有符合筛选条件的库存")
            );
            return;
        }
        for (const snapshot of filtered) {
            elements.inventoryList.append(createSnapshotCard(snapshot));
        }
    }

    /**
     * @param {object} snapshot
     * @returns {object | null}
     */
    function filterSnapshot(snapshot) {
        const itemQuery = elements.filterItem.value.trim().toLowerCase();
        const containerQuery = elements.filterContainer.value.trim().toLowerCase();
        const source = [
            snapshot.containerId,
            snapshot.sourceId,
            readableSource(snapshot.sourceId)
        ].join(" ").toLowerCase();
        if (containerQuery && !source.includes(containerQuery)) {
            return null;
        }
        const items = Array.isArray(snapshot.items) ? snapshot.items : [];
        const matchingItems = itemQuery
            ? items.filter((item) => {
                return String(item.itemId || "").toLowerCase().includes(itemQuery);
            })
            : items;
        if (itemQuery && matchingItems.length === 0) {
            return null;
        }
        return {
            ...snapshot,
            items: matchingItems
        };
    }

    /**
     * @returns {boolean}
     */
    function hasActiveFilter() {
        return Boolean(
            elements.filterItem.value.trim()
            || elements.filterContainer.value.trim()
        );
    }

    /**
     * @param {object} snapshot
     * @returns {HTMLElement}
     */
    function createSnapshotCard(snapshot) {
        const card = Stowling.element("article", "inventory-card");
        const heading = Stowling.element("div", "inventory-card-heading");
        const title = Stowling.element(
            "h3",
            "inventory-title",
            String(snapshot.containerId || "未知库存范围")
        );
        title.append(validityBadge(snapshot.freshness));
        const identity = Stowling.element(
            "span",
            "inventory-dimension",
            Stowling.dimensionLabel(snapshot.dimension)
        );
        heading.append(title, identity);

        const metadata = Stowling.element("div", "inventory-meta");
        metadata.append(
            metaText("来源", readableSource(snapshot.sourceId)),
            metaText("采集时间", Stowling.formatDate(snapshot.capturedAt)),
            metaText("有效性", validityText(snapshot.freshness))
        );
        card.append(heading, metadata);
        const items = Array.isArray(snapshot.items) ? snapshot.items : [];
        card.append(items.length === 0
            ? Stowling.emptyState("此库存快照没有物品")
            : createItemTable(items));
        return card;
    }

    /**
     * @param {Array<object>} items
     * @returns {HTMLTableElement}
     */
    function createItemTable(items) {
        const table = Stowling.element("table", "item-table");
        const header = document.createElement("thead");
        const headerRow = document.createElement("tr");
        for (const label of ["物品", "分类", "数量"]) {
            const cell = document.createElement("th");
            cell.scope = "col";
            cell.textContent = label;
            headerRow.append(cell);
        }
        header.append(headerRow);
        const body = document.createElement("tbody");
        for (const item of items) {
            const row = document.createElement("tr");
            const kind = String(item.kind || "UNKNOWN").toUpperCase();
            row.append(
                Stowling.element("td", "", String(item.itemId || "未知物品")),
                Stowling.element("td", "", ITEM_KIND_LABELS.get(kind) || "未知"),
                Stowling.element(
                    "td",
                    "",
                    Number(item.count || 0).toLocaleString("zh-CN")
                )
            );
            body.append(row);
        }
        table.append(header, body);
        return table;
    }

    /**
     * @param {unknown} value
     * @returns {HTMLSpanElement}
     */
    function validityBadge(value) {
        const state = freshness(value);
        return Stowling.element("span", state.className, state.text);
    }

    /**
     * @param {unknown} value
     * @returns {string}
     */
    function validityText(value) {
        return freshness(value).text;
    }

    /**
     * @param {unknown} value
     * @returns {object}
     */
    function freshness(value) {
        const key = String(value || "UNKNOWN").toUpperCase();
        return FRESHNESS.get(key) || FRESHNESS.get("UNKNOWN");
    }

    /**
     * @param {unknown} value
     * @returns {string}
     */
    function readableSource(value) {
        const source = String(value || "").trim();
        if (!source) {
            return "未知来源";
        }
        if (source === "aggregate") {
            return "已知容器汇总";
        }
        return source;
    }

    /**
     * @param {string} key
     * @param {string} value
     * @returns {HTMLSpanElement}
     */
    function metaText(key, value) {
        return Stowling.element("span", "", `${key}：${value}`);
    }

    elements.refresh.addEventListener("click", () => {
        void loadInventory();
    });
    elements.filterItem.addEventListener("input", () => {
        renderInventory();
        elements.message.textContent = "本地筛选已更新；未发起新请求";
    });
    elements.filterContainer.addEventListener("input", () => {
        renderInventory();
        elements.message.textContent = "本地筛选已更新；未发起新请求";
    });

    window.setInterval(() => {
        if (document.visibilityState === "visible" && Stowling.isConnected()) {
            void loadInventory();
        }
    }, 15000);

    Stowling.onConnected(loadInventory);
    Stowling.mountPage("storage");
})();
