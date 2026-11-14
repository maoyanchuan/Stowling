(() => {
    "use strict";

    const Stowling = window.Stowling;
    const DIMENSIONS = Object.freeze([
        "minecraft:overworld",
        "minecraft:the_nether",
        "minecraft:the_end"
    ]);
    const CONTAINER_TYPES = Object.freeze([
        "CHEST",
        "TRAPPED_CHEST",
        "BARREL",
        "SHULKER_BOX",
        "HOPPER",
        "OTHER"
    ]);
    const ITEM_KIND_LABELS = new Map([
        ["BULK", "散装"],
        ["SHULKER", "盒装"],
        ["PACKED", "盒装"],
        ["STALE", "过期"],
        ["UNKNOWN", "未知"]
    ]);

    const elements = {
        containerList: document.querySelector("#container-list"),
        inventoryList: document.querySelector("#inventory-list"),
        refresh: document.querySelector("#refresh-storage-button"),
        audit: document.querySelector("#audit-button"),
        storageMessage: document.querySelector("#storage-message"),
        baseForm: document.querySelector("#base-coordinate-form"),
        baseList: document.querySelector("#base-coordinate-list"),
        baseSave: document.querySelector("#save-base-coordinate-button"),
        baseDiscard: document.querySelector("#discard-base-coordinate-button"),
        baseStatus: document.querySelector("#base-coordinate-status"),
        baseRevision: document.querySelector("#base-coordinate-revision"),
        pickupForm: document.querySelector("#pickup-form"),
        regionList: document.querySelector("#region-list"),
        cacheList: document.querySelector("#cache-list"),
        pickupWorkflowList: document.querySelector("#pickup-workflow-list"),
        addRegion: document.querySelector("#add-region-button"),
        addCache: document.querySelector("#add-cache-button"),
        addPickupWorkflow: document.querySelector("#add-pickup-workflow-button"),
        pickupSave: document.querySelector("#save-pickup-button"),
        pickupDiscard: document.querySelector("#discard-pickup-button"),
        pickupErrors: document.querySelector("#pickup-errors"),
        pickupStatus: document.querySelector("#pickup-status"),
        pickupRevision: document.querySelector("#pickup-revision")
    };

    const state = {
        containers: [],
        baseSnapshot: null,
        baseDirty: false,
        coordinates: [],
        pickupSnapshot: null,
        pickupDraft: null,
        pickupDirty: false,
        pickupEditableIds: new Set()
    };

    /**
     * @returns {Promise<void>}
     */
    async function loadStorage() {
        const results = await Promise.allSettled([
            Stowling.request("/v1/containers"),
            Stowling.request("/v1/inventory")
        ]);
        if (results[0].status === "fulfilled") {
            state.containers = Array.isArray(results[0].value) ? results[0].value : [];
            renderContainers();
        }
        if (results[1].status === "fulfilled") {
            renderInventory(Array.isArray(results[1].value) ? results[1].value : []);
        }
        const failure = results.find((result) => result.status === "rejected");
        if (failure) {
            throw failure.reason;
        }
        elements.storageMessage.textContent = "容器与库存已刷新";
    }

    /**
     * @returns {void}
     */
    function renderContainers() {
        const checked = new Set(
            [...elements.containerList.querySelectorAll("input:checked")]
                .map((input) => input.value)
        );
        elements.containerList.replaceChildren();
        if (state.containers.length === 0) {
            elements.containerList.append(Stowling.emptyState("尚未发现可盘点容器"));
            return;
        }
        for (const container of state.containers) {
            const option = Stowling.element("label", "container-option");
            const checkbox = document.createElement("input");
            checkbox.type = "checkbox";
            checkbox.value = String(container.id || "");
            checkbox.checked = checked.has(checkbox.value);
            const title = Stowling.element(
                "strong",
                "",
                String(container.name || container.id || "未命名容器")
            );
            const position = container.position || {};
            const detail = Stowling.element("span", "", [
                container.type || "未知类型",
                container.dimension || "未知维度",
                `${position.x ?? "?"}, ${position.y ?? "?"}, ${position.z ?? "?"}`
            ].join(" · "));
            option.append(checkbox, title, detail);
            elements.containerList.append(option);
        }
    }

    /**
     * @param {Array<object>} inventory
     * @returns {void}
     */
    function renderInventory(inventory) {
        elements.inventoryList.replaceChildren();
        if (inventory.length === 0) {
            elements.inventoryList.append(Stowling.emptyState("还没有库存快照"));
            return;
        }
        for (const snapshot of inventory) {
            const card = Stowling.element("article", "inventory-card");
            const title = Stowling.element(
                "h3",
                "inventory-title",
                String(snapshot.containerId || "未知容器")
            );
            title.append(Stowling.element(
                "span",
                freshnessClass(snapshot.freshness),
                freshnessLabel(snapshot.freshness)
            ));
            const meta = Stowling.element("div", "inventory-meta");
            meta.append(
                metaText("来源", String(snapshot.sourceId || "未知")),
                metaText("维度", String(snapshot.dimension || "未知")),
                metaText("采集", Stowling.formatDate(snapshot.capturedAt))
            );
            card.append(title, meta);
            const items = Array.isArray(snapshot.items) ? snapshot.items : [];
            card.append(items.length === 0
                ? Stowling.emptyState("此快照没有物品")
                : createItemTable(items));
            elements.inventoryList.append(card);
        }
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
     * @param {string} value
     * @returns {string}
     */
    function freshnessLabel(value) {
        const labels = new Map([
            ["FRESH", "新鲜"],
            ["STALE", "过期"],
            ["UNKNOWN", "未知"]
        ]);
        return labels.get(String(value || "UNKNOWN")) || String(value || "未知");
    }

    /**
     * @param {string} value
     * @returns {string}
     */
    function freshnessClass(value) {
        const normalized = String(value || "UNKNOWN");
        if (normalized === "STALE") {
            return "badge danger";
        }
        if (normalized === "UNKNOWN") {
            return "badge warning";
        }
        return "badge";
    }

    /**
     * @param {string} key
     * @param {string} value
     * @returns {HTMLSpanElement}
     */
    function metaText(key, value) {
        return Stowling.element("span", "", `${key}：${value}`);
    }

    /**
     * @returns {Promise<void>}
     */
    async function requestAudit() {
        const selected = [...elements.containerList.querySelectorAll("input:checked")]
            .map((input) => input.value)
            .filter(Boolean);
        if (selected.length === 0) {
            Stowling.showToast("请至少选择一个容器", true);
            return;
        }
        elements.audit.disabled = true;
        elements.storageMessage.textContent = "正在创建盘点任务…";
        try {
            await Stowling.request("/v1/audits", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ containerIds: selected })
            });
            elements.storageMessage.textContent = "盘点任务已创建";
            Stowling.showToast("盘点任务已创建");
        } catch (error) {
            elements.storageMessage.textContent = "发起盘点失败";
            Stowling.handleError(error, "发起盘点失败");
        } finally {
            elements.audit.disabled = false;
        }
    }

    /**
     * @returns {Promise<void>}
     */
    async function loadBaseCoordinates() {
        elements.baseStatus.textContent = "正在读取仓库坐标…";
        const snapshot = await Stowling.request("/v1/config/coordinates");
        state.baseSnapshot = Stowling.clone({
            revision: String(snapshot?.revision || ""),
            coordinates: Array.isArray(snapshot?.coordinates) ? snapshot.coordinates : []
        });
        state.baseDirty = false;
        renderBaseCoordinates();
        elements.baseStatus.textContent = "仓库坐标已与配置文件同步";
        elements.baseRevision.textContent =
            `revision: ${state.baseSnapshot.revision.slice(0, 12) || "--"}`;
        elements.baseRevision.title = state.baseSnapshot.revision;
    }

    /**
     * @returns {void}
     */
    function renderBaseCoordinates() {
        elements.baseList.replaceChildren();
        if (!state.baseSnapshot) {
            return;
        }
        const groups = [
            ["CONTAINER", "仓库与溢出容器"],
            ["PACKING_STATION_SHULKER_BOX", "装盒工位"]
        ];
        for (const [kind, label] of groups) {
            const coordinates = state.baseSnapshot.coordinates.filter((item) => {
                return item.kind === kind;
            });
            if (coordinates.length > 0) {
                elements.baseList.append(createBaseCoordinateGroup(label, coordinates));
            }
        }
        if (state.baseSnapshot.coordinates.length === 0) {
            elements.baseList.append(Stowling.emptyState("配置中没有可编辑坐标"));
        }
        elements.baseSave.disabled = true;
    }

    /**
     * @param {string} title
     * @param {Array<object>} coordinates
     * @returns {HTMLElement}
     */
    function createBaseCoordinateGroup(title, coordinates) {
        const group = Stowling.element("section", "coordinate-group");
        group.append(Stowling.element("h3", "", title));
        const list = Stowling.element("div", "coordinate-list");
        coordinates.forEach((coordinate, index) => {
            list.append(createBaseCoordinateRow(coordinate, `${coordinate.kind}-${index}`));
        });
        group.append(list);
        return group;
    }

    /**
     * @param {object} coordinate
     * @param {string} prefix
     * @returns {HTMLElement}
     */
    function createBaseCoordinateRow(coordinate, prefix) {
        const row = Stowling.element("div", "coordinate-row");
        row.dataset.key = String(coordinate.key || "");
        const identity = Stowling.element("div", "coordinate-identity");
        identity.append(
            Stowling.element("strong", "", String(coordinate.ownerId || "未命名")),
            Stowling.element("span", "", `${coordinate.dimension} · ${coordinate.key}`)
        );
        row.append(identity);
        for (const axis of ["x", "y", "z"]) {
            const input = document.createElement("input");
            input.id = `${prefix}-${axis}`;
            input.type = "number";
            input.step = "1";
            input.value = String(coordinate.position?.[axis] ?? 0);
            input.dataset.axis = axis;
            input.setAttribute("aria-label", axis.toUpperCase());
            input.addEventListener("input", updateBaseDirtyState);
            row.append(field(axis.toUpperCase(), input));
        }
        return row;
    }

    /**
     * @param {string} label
     * @param {HTMLElement} control
     * @returns {HTMLElement}
     */
    function field(label, control) {
        const wrapper = Stowling.element("div", "editor-field");
        const caption = Stowling.element("label", "", label);
        if (control.id) {
            caption.htmlFor = control.id;
        }
        wrapper.append(caption, control);
        return wrapper;
    }

    /**
     * @returns {void}
     */
    function updateBaseDirtyState() {
        let changed = false;
        let invalid = false;
        const original = new Map(
            state.baseSnapshot.coordinates.map((item) => [item.key, item])
        );
        for (const row of elements.baseList.querySelectorAll(".coordinate-row")) {
            const coordinate = original.get(row.dataset.key);
            for (const input of row.querySelectorAll("input[data-axis]")) {
                const value = parseInteger(input.value);
                invalid ||= value === null;
                changed ||= value !== coordinate?.position?.[input.dataset.axis];
            }
        }
        state.baseDirty = changed;
        elements.baseSave.disabled = !changed || invalid;
        elements.baseStatus.classList.toggle("dirty", changed);
        elements.baseStatus.textContent = invalid
            ? "坐标必须是 32 位整数"
            : changed
                ? "有未保存的仓库坐标修改"
                : "仓库坐标已与配置文件同步";
    }

    /**
     * @param {string} value
     * @returns {number | null}
     */
    function parseInteger(value) {
        const text = String(value ?? "").trim();
        if (!/^-?(0|[1-9][0-9]*)$/.test(text)) {
            return null;
        }
        const number = Number(text);
        if (!Number.isSafeInteger(number)
                || number < -2147483648
                || number > 2147483647) {
            return null;
        }
        return number;
    }

    /**
     * @returns {Array<object>}
     */
    function collectBaseCoordinates() {
        const updates = [];
        for (const row of elements.baseList.querySelectorAll(".coordinate-row")) {
            const value = { key: row.dataset.key };
            for (const input of row.querySelectorAll("input[data-axis]")) {
                const parsed = parseInteger(input.value);
                if (parsed === null) {
                    throw new Error("坐标必须是 32 位整数");
                }
                value[input.dataset.axis] = parsed;
            }
            updates.push(value);
        }
        return updates;
    }

    /**
     * @returns {Promise<void>}
     */
    async function saveBaseCoordinates() {
        if (!state.baseDirty) {
            return;
        }
        let coordinates;
        try {
            coordinates = collectBaseCoordinates();
        } catch (error) {
            Stowling.handleError(error, "仓库坐标格式错误");
            return;
        }
        elements.baseSave.disabled = true;
        elements.baseDiscard.disabled = true;
        elements.baseStatus.textContent = "正在保存仓库坐标…";
        try {
            const saved = await Stowling.request("/v1/config/coordinates", {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    revision: state.baseSnapshot.revision,
                    coordinates
                })
            });
            state.baseSnapshot = {
                revision: String(saved?.revision || state.baseSnapshot.revision),
                coordinates: Array.isArray(saved?.coordinates)
                    ? saved.coordinates
                    : state.baseSnapshot.coordinates
            };
            state.baseDirty = false;
            renderBaseCoordinates();
            elements.baseStatus.textContent = "仓库坐标已保存";
            elements.baseRevision.textContent =
                `revision: ${state.baseSnapshot.revision.slice(0, 12)}`;
            Stowling.showToast("仓库坐标已保存，旧配置已生成备份");
        } catch (error) {
            updateBaseDirtyState();
            Stowling.handleError(error, "保存仓库坐标失败");
        } finally {
            elements.baseDiscard.disabled = false;
        }
    }

    /**
     * @returns {Promise<void>}
     */
    async function loadPackages() {
        const results = await Promise.all([
            Stowling.request("/v1/config/coordinate-points"),
            Stowling.request("/v1/config/pickup")
        ]);
        state.coordinates = results[0]?.coordinatePoints || [];
        renderPickupSnapshot(results[1]);
    }

    /**
     * @param {object} snapshot
     * @returns {void}
     */
    function renderPickupSnapshot(snapshot) {
        state.pickupSnapshot = Stowling.clone({
            revision: String(snapshot?.revision || ""),
            pickupRegions: Array.isArray(snapshot?.pickupRegions)
                ? snapshot.pickupRegions
                : [],
            cacheContainers: Array.isArray(snapshot?.cacheContainers)
                ? snapshot.cacheContainers
                : [],
            pickupWorkflows: Array.isArray(snapshot?.pickupWorkflows)
                ? snapshot.pickupWorkflows
                : []
        });
        state.pickupDraft = Stowling.clone(state.pickupSnapshot);
        state.pickupDirty = false;
        state.pickupEditableIds = new Set();
        renderPickupEditor();
        elements.pickupStatus.textContent = "拾取配置已与配置文件同步";
        elements.pickupRevision.textContent =
            `revision: ${state.pickupSnapshot.revision.slice(0, 12) || "--"}`;
        elements.pickupRevision.title = state.pickupSnapshot.revision;
        showPickupErrors([]);
    }

    /**
     * @returns {void}
     */
    function renderPickupEditor() {
        elements.regionList.replaceChildren();
        elements.cacheList.replaceChildren();
        elements.pickupWorkflowList.replaceChildren();
        if (!state.pickupDraft) {
            return;
        }
        state.pickupDraft.pickupRegions.forEach((region, index) => {
            elements.regionList.append(createRegionCard(region, index));
        });
        state.pickupDraft.cacheContainers.forEach((cache, index) => {
            elements.cacheList.append(createCacheCard(cache, index));
        });
        state.pickupDraft.pickupWorkflows.forEach((workflow, index) => {
            elements.pickupWorkflowList.append(createPickupWorkflowCard(workflow, index));
        });
        if (!elements.regionList.hasChildNodes()) {
            elements.regionList.append(Stowling.emptyState("尚未创建拾取区域"));
        }
        if (!elements.cacheList.hasChildNodes()) {
            elements.cacheList.append(Stowling.emptyState("尚未创建缓存容器"));
        }
        if (!elements.pickupWorkflowList.hasChildNodes()) {
            elements.pickupWorkflowList.append(Stowling.emptyState("尚未创建拾取流程"));
        }
        updatePickupState();
    }

    /**
     * @param {object} region
     * @param {number} index
     * @returns {HTMLElement}
     */
    function createRegionCard(region, index) {
        const card = Stowling.element("article", "editor-card");
        card.append(editorHeading(
            region.name || region.id,
            () => removePickupResource("region", index),
            (offset) => movePickupResource("region", index, offset)
        ));
        const fields = Stowling.element("div", "editor-fields");
        fields.append(
            editableIdField(
                `region-id-${index}`,
                region,
                state.pickupEditableIds.has(region.id)
            ),
            textField(`region-name-${index}`, "显示名称", region.name, (value) => {
                region.name = value;
                markPickupDirty(true);
            }),
            dimensionField(`region-dimension-${index}`, region.dimension, (value) => {
                region.dimension = value;
                markPickupDirty();
            }),
            coordinateField(
                `region-standby-${index}`,
                "待机点",
                region.standbyCoordinateId,
                (value) => {
                    region.standbyCoordinateId = value;
                    markPickupDirty();
                }
            )
        );
        fields.append(
            selectField(`region-shape-${index}`, "形状", [
                ["SPHERE", "球形"],
                ["CUBOID", "长方体"]
            ], region.shape?.type || "SPHERE", (value) => {
                region.shape = value === "CUBOID"
                    ? {
                        type: "CUBOID",
                        firstCornerCoordinateId: region.standbyCoordinateId || "",
                        secondCornerCoordinateId: region.standbyCoordinateId || ""
                    }
                    : {
                        type: "SPHERE",
                        centerCoordinateId: region.standbyCoordinateId || "",
                        radius: 32
                    };
                markPickupDirty(true);
            })
        );
        if (region.shape?.type === "CUBOID") {
            fields.append(
                coordinateField(
                    `region-first-${index}`,
                    "第一角点",
                    region.shape.firstCornerCoordinateId,
                    (value) => {
                        region.shape.firstCornerCoordinateId = value;
                        markPickupDirty();
                    }
                ),
                coordinateField(
                    `region-second-${index}`,
                    "第二角点",
                    region.shape.secondCornerCoordinateId,
                    (value) => {
                        region.shape.secondCornerCoordinateId = value;
                        markPickupDirty();
                    }
                )
            );
        } else {
            fields.append(
                coordinateField(
                    `region-center-${index}`,
                    "球心",
                    region.shape?.centerCoordinateId,
                    (value) => {
                        region.shape.centerCoordinateId = value;
                        markPickupDirty();
                    }
                ),
                numberField(
                    `region-radius-${index}`,
                    "半径",
                    region.shape?.radius,
                    (value) => {
                        region.shape.radius = value;
                        markPickupDirty();
                    }
                )
            );
        }
        card.append(fields);
        return card;
    }

    /**
     * @param {object} cache
     * @param {number} index
     * @returns {HTMLElement}
     */
    function createCacheCard(cache, index) {
        const card = Stowling.element("article", "editor-card");
        card.append(editorHeading(
            cache.name || cache.id,
            () => removePickupResource("cache", index),
            (offset) => movePickupResource("cache", index, offset)
        ));
        const fields = Stowling.element("div", "editor-fields");
        fields.append(
            editableIdField(
                `cache-id-${index}`,
                cache,
                state.pickupEditableIds.has(cache.id)
            ),
            textField(`cache-name-${index}`, "显示名称", cache.name, (value) => {
                cache.name = value;
                markPickupDirty();
            }),
            coordinateField(
                `cache-coordinate-${index}`,
                "容器坐标",
                cache.coordinateId,
                (value) => {
                    cache.coordinateId = value;
                    const point = coordinateFor(value);
                    cache.dimension = point?.dimension || cache.dimension;
                    markPickupDirty();
                }
            ),
            selectField(
                `cache-type-${index}`,
                "容器类型",
                CONTAINER_TYPES.map((type) => [type, containerTypeLabel(type)]),
                cache.type || "CHEST",
                (value) => {
                    cache.type = value;
                    markPickupDirty();
                }
            )
        );
        card.append(fields);
        const hint = Stowling.element(
            "p",
            "coordinate-select-hint",
            `维度由坐标决定：${cache.dimension || "未选择"}`
        );
        card.append(hint);
        return card;
    }

    /**
     * @param {string} type
     * @returns {string}
     */
    function containerTypeLabel(type) {
        const labels = new Map([
            ["CHEST", "箱子"],
            ["TRAPPED_CHEST", "陷阱箱"],
            ["BARREL", "木桶"],
            ["SHULKER_BOX", "潜影盒"],
            ["HOPPER", "漏斗"],
            ["OTHER", "其他"]
        ]);
        return labels.get(type) || type;
    }

    /**
     * @param {object} workflow
     * @param {number} index
     * @returns {HTMLElement}
     */
    function createPickupWorkflowCard(workflow, index) {
        const card = Stowling.element("article", "editor-card");
        card.append(editorHeading(
            workflow.name || workflow.id,
            () => removePickupResource("workflow", index),
            (offset) => movePickupResource("workflow", index, offset)
        ));
        const fields = Stowling.element("div", "editor-fields");
        fields.append(
            editableIdField(
                `pickup-workflow-id-${index}`,
                workflow,
                state.pickupEditableIds.has(workflow.id)
            ),
            textField(
                `pickup-workflow-name-${index}`,
                "显示名称",
                workflow.name,
                (value) => {
                    workflow.name = value;
                    markPickupDirty();
                }
            ),
            dimensionField(
                `pickup-workflow-dimension-${index}`,
                workflow.dimension,
                (value) => {
                    workflow.dimension = value;
                    markPickupDirty();
                }
            ),
            selectField(
                `pickup-workflow-timing-${index}`,
                "分类时机",
                [["AFTER_REGION", "每个区域后"], ["AFTER_ROUND", "整轮结束后"]],
                workflow.classificationTiming || "AFTER_ROUND",
                (value) => {
                    workflow.classificationTiming = value;
                    markPickupDirty();
                }
            ),
            selectField(
                `pickup-workflow-mode-${index}`,
                "结束方式",
                [["MANUAL", "手动停止"], ["ALL_IDLE", "全部空闲后"]],
                workflow.completionMode || "MANUAL",
                (value) => {
                    workflow.completionMode = value;
                    markPickupDirty();
                }
            ),
            numberField(
                `pickup-workflow-idle-${index}`,
                "流程空闲秒数",
                workflow.completionIdleDelay,
                (value) => {
                    workflow.completionIdleDelay = value;
                    markPickupDirty();
                }
            )
        );
        const regions = Stowling.element("div", "binding-section");
        regions.append(Stowling.element("h4", "", "区域优先级"));
        workflow.regions ||= [];
        workflow.regions.forEach((binding, bindingIndex) => {
            regions.append(createRegionBinding(workflow, binding, index, bindingIndex));
        });
        const addBindingRow = Stowling.element("div", "binding-add-row");
        const available = state.pickupDraft.pickupRegions.filter((region) => {
            return !workflow.regions.some((binding) => binding.regionId === region.id);
        });
        const select = document.createElement("select");
        select.setAttribute("aria-label", "添加拾取区域");
        for (const region of available) {
            const option = document.createElement("option");
            option.value = region.id;
            option.textContent = region.name || region.id;
            select.append(option);
        }
        const add = Stowling.element("button", "secondary compact", "添加区域");
        add.type = "button";
        add.disabled = available.length === 0;
        add.addEventListener("click", () => {
            if (select.value) {
                workflow.regions.push({
                    regionId: select.value,
                    priority: 100,
                    idleDelay: 60
                });
                markPickupDirty(true);
            }
        });
        addBindingRow.append(select, add);
        regions.append(addBindingRow);

        const caches = Stowling.element("div", "binding-section");
        caches.append(Stowling.element("h4", "", "可用缓存"));
        const checks = Stowling.element("div", "check-grid");
        workflow.cacheIds ||= [];
        for (const cache of state.pickupDraft.cacheContainers) {
            const label = Stowling.element("label", "check-option");
            const checkbox = document.createElement("input");
            checkbox.type = "checkbox";
            checkbox.value = cache.id;
            checkbox.checked = workflow.cacheIds.includes(cache.id);
            checkbox.addEventListener("change", () => {
                if (checkbox.checked && !workflow.cacheIds.includes(cache.id)) {
                    workflow.cacheIds.push(cache.id);
                } else if (!checkbox.checked) {
                    workflow.cacheIds = workflow.cacheIds.filter((id) => id !== cache.id);
                }
                markPickupDirty();
            });
            label.append(checkbox, Stowling.element("span", "", cache.name || cache.id));
            checks.append(label);
        }
        caches.append(checks);
        card.append(fields, regions, caches);
        return card;
    }

    /**
     * @param {object} workflow
     * @param {object} binding
     * @param {number} workflowIndex
     * @param {number} bindingIndex
     * @returns {HTMLElement}
     */
    function createRegionBinding(workflow, binding, workflowIndex, bindingIndex) {
        const row = Stowling.element("div", "binding-row");
        const region = state.pickupDraft.pickupRegions.find((candidate) => {
            return candidate.id === binding.regionId;
        });
        row.append(Stowling.element("strong", "", region?.name || binding.regionId));
        row.append(
            numberField(
                `binding-priority-${workflowIndex}-${bindingIndex}`,
                "优先级",
                binding.priority,
                (value) => {
                    binding.priority = value;
                    markPickupDirty();
                }
            ),
            numberField(
                `binding-idle-${workflowIndex}-${bindingIndex}`,
                "空闲秒数",
                binding.idleDelay,
                (value) => {
                    binding.idleDelay = value;
                    markPickupDirty();
                }
            )
        );
        const remove = Stowling.element("button", "ghost compact danger-action", "移除");
        remove.type = "button";
        remove.addEventListener("click", () => {
            workflow.regions.splice(bindingIndex, 1);
            markPickupDirty(true);
        });
        row.append(remove);
        return row;
    }

    /**
     * @param {string} title
     * @param {() => void} onRemove
     * @param {(offset: number) => void} onMove
     * @returns {HTMLElement}
     */
    function editorHeading(title, onRemove, onMove) {
        const heading = Stowling.element("div", "editor-card-heading");
        heading.append(Stowling.element("strong", "", String(title || "未命名资源")));
        const actions = Stowling.element("div", "editor-card-actions");
        const moves = Stowling.element("div", "move-buttons");
        const up = Stowling.element("button", "secondary compact", "↑");
        up.type = "button";
        up.title = "上移";
        up.addEventListener("click", () => onMove(-1));
        const down = Stowling.element("button", "secondary compact", "↓");
        down.type = "button";
        down.title = "下移";
        down.addEventListener("click", () => onMove(1));
        moves.append(up, down);
        const remove = Stowling.element("button", "ghost compact danger-action", "删除");
        remove.type = "button";
        remove.addEventListener("click", onRemove);
        actions.append(moves, remove);
        heading.append(actions);
        return heading;
    }

    /**
     * @param {string} id
     * @param {object} resource
     * @param {boolean} editable
     * @returns {HTMLElement}
     */
    function editableIdField(id, resource, editable) {
        const input = document.createElement("input");
        input.id = id;
        input.value = String(resource.id || "");
        input.disabled = !editable;
        input.pattern = "[a-z0-9][a-z0-9._-]{0,63}";
        input.addEventListener("input", () => {
            resource.id = input.value;
            markPickupDirty();
        });
        return field("资源 ID", input);
    }

    /**
     * @param {string} id
     * @param {string} label
     * @param {unknown} value
     * @param {(value: string) => void} onChange
     * @returns {HTMLElement}
     */
    function textField(id, label, value, onChange) {
        const input = document.createElement("input");
        input.id = id;
        input.value = String(value || "");
        input.addEventListener("input", () => onChange(input.value));
        return field(label, input);
    }

    /**
     * @param {string} id
     * @param {string} label
     * @param {unknown} value
     * @param {(value: number) => void} onChange
     * @returns {HTMLElement}
     */
    function numberField(id, label, value, onChange) {
        const input = document.createElement("input");
        input.id = id;
        input.type = "number";
        input.step = "1";
        input.value = String(value ?? 0);
        input.addEventListener("input", () => {
            const parsed = Number(input.value);
            if (Number.isInteger(parsed)) {
                onChange(parsed);
            }
        });
        return field(label, input);
    }

    /**
     * @param {string} id
     * @param {string} label
     * @param {Array<Array<string>>} options
     * @param {string} value
     * @param {(value: string) => void} onChange
     * @returns {HTMLElement}
     */
    function selectField(id, label, options, value, onChange) {
        const select = document.createElement("select");
        select.id = id;
        for (const [optionValue, optionLabel] of options) {
            const option = document.createElement("option");
            option.value = optionValue;
            option.textContent = optionLabel;
            select.append(option);
        }
        select.value = value;
        select.addEventListener("change", () => onChange(select.value));
        return field(label, select);
    }

    /**
     * @param {string} id
     * @param {unknown} value
     * @param {(value: string) => void} onChange
     * @returns {HTMLElement}
     */
    function dimensionField(id, value, onChange) {
        return selectField(
            id,
            "维度",
            DIMENSIONS.map((dimension) => [dimension, dimension]),
            String(value || DIMENSIONS[0]),
            onChange
        );
    }

    /**
     * @param {string} id
     * @param {string} label
     * @param {unknown} value
     * @param {(value: string) => void} onChange
     * @returns {HTMLElement}
     */
    function coordinateField(id, label, value, onChange) {
        const select = document.createElement("select");
        select.id = id;
        const placeholder = document.createElement("option");
        placeholder.value = "";
        placeholder.textContent = "请选择命名坐标";
        select.append(placeholder);
        for (const point of state.coordinates) {
            const option = document.createElement("option");
            option.value = point.id;
            option.textContent = `${point.name || point.id} · ${point.dimension}`;
            select.append(option);
        }
        if (value && !state.coordinates.some((point) => point.id === value)) {
            const option = document.createElement("option");
            option.value = String(value);
            option.textContent = `${value} · 坐标缺失`;
            select.append(option);
        }
        select.value = String(value || "");
        select.addEventListener("change", () => onChange(select.value));
        return field(label, select);
    }

    /**
     * @param {string} coordinateId
     * @returns {object | undefined}
     */
    function coordinateFor(coordinateId) {
        return state.coordinates.find((point) => point.id === coordinateId);
    }

    /**
     * @param {string} kind
     * @param {number} index
     * @returns {void}
     */
    function removePickupResource(kind, index) {
        const collections = {
            region: [state.pickupDraft.pickupRegions, "区域"],
            cache: [state.pickupDraft.cacheContainers, "缓存"],
            workflow: [state.pickupDraft.pickupWorkflows, "流程"]
        };
        const [collection, label] = collections[kind];
        const resource = collection[index];
        const references = pickupReferences(kind, resource.id);
        if (references.length > 0) {
            showPickupErrors([
                `无法删除${label}“${resource.name || resource.id}”：仍被`
                    + ` ${references.join("、")} 引用。`
            ]);
            return;
        }
        collection.splice(index, 1);
        state.pickupEditableIds.delete(resource.id);
        markPickupDirty(true);
    }

    /**
     * @param {string} kind
     * @param {number} index
     * @param {number} offset
     * @returns {void}
     */
    function movePickupResource(kind, index, offset) {
        const collections = {
            region: state.pickupDraft.pickupRegions,
            cache: state.pickupDraft.cacheContainers,
            workflow: state.pickupDraft.pickupWorkflows
        };
        const collection = collections[kind];
        const target = index + offset;
        if (!collection || target < 0 || target >= collection.length) {
            return;
        }
        const [resource] = collection.splice(index, 1);
        collection.splice(target, 0, resource);
        markPickupDirty(true);
    }

    /**
     * @param {string} kind
     * @param {string} id
     * @returns {Array<string>}
     */
    function pickupReferences(kind, id) {
        const references = [];
        for (const workflow of state.pickupDraft.pickupWorkflows) {
            if (kind === "cache" && workflow.cacheIds?.includes(id)) {
                references.push(`拾取流程“${workflow.name || workflow.id}”`);
            }
            if (kind === "region"
                    && workflow.regions?.some((item) => item.regionId === id)) {
                references.push(`拾取流程“${workflow.name || workflow.id}”`);
            }
        }
        return references;
    }

    /**
     * @returns {void}
     */
    function addRegion() {
        const id = nextPickupId("region");
        state.pickupDraft.pickupRegions.push({
            id,
            name: "新拾取区域",
            dimension: DIMENSIONS[0],
            standbyCoordinateId: state.coordinates[0]?.id || "",
            shape: {
                type: "SPHERE",
                centerCoordinateId: state.coordinates[0]?.id || "",
                radius: 32
            }
        });
        state.pickupEditableIds.add(id);
        markPickupDirty(true);
    }

    /**
     * @returns {void}
     */
    function addCache() {
        const id = nextPickupId("cache");
        state.pickupDraft.cacheContainers.push({
            id,
            name: "新缓存箱",
            coordinateId: state.coordinates[0]?.id || "",
            dimension: state.coordinates[0]?.dimension || DIMENSIONS[0],
            type: "CHEST"
        });
        state.pickupEditableIds.add(id);
        markPickupDirty(true);
    }

    /**
     * @returns {void}
     */
    function addPickupWorkflow() {
        const id = nextPickupId("workflow");
        state.pickupDraft.pickupWorkflows.push({
            id,
            name: "新拾取流程",
            dimension: DIMENSIONS[0],
            regions: [],
            cacheIds: [],
            cachePolicy: "NEAREST_AVAILABLE",
            classificationTiming: "AFTER_ROUND",
            completionMode: "MANUAL",
            completionIdleDelay: 300
        });
        state.pickupEditableIds.add(id);
        markPickupDirty(true);
    }

    /**
     * @param {string} prefix
     * @returns {string}
     */
    function nextPickupId(prefix) {
        const used = new Set([
            ...state.pickupDraft.pickupRegions.map((item) => item.id),
            ...state.pickupDraft.cacheContainers.map((item) => item.id),
            ...state.pickupDraft.pickupWorkflows.map((item) => item.id)
        ]);
        let index = 1;
        while (used.has(`${prefix}-${index}`)) {
            index += 1;
        }
        return `${prefix}-${index}`;
    }

    /**
     * @param {boolean} rerender
     * @returns {void}
     */
    function markPickupDirty(rerender = false) {
        state.pickupDirty = true;
        showPickupErrors([]);
        if (rerender) {
            renderPickupEditor();
            return;
        }
        updatePickupState();
    }

    /**
     * @returns {Array<string>}
     */
    function validatePickupDraft() {
        if (!state.pickupDraft) {
            return ["尚未读取拾取配置"];
        }
        const errors = [];
        const ids = new Set();
        const allResources = [
            ...state.pickupDraft.pickupRegions,
            ...state.pickupDraft.cacheContainers,
            ...state.pickupDraft.pickupWorkflows
        ];
        for (const resource of allResources) {
            const id = String(resource.id || "");
            if (!/^[a-z0-9][a-z0-9._-]{0,63}$/.test(id)) {
                errors.push(`资源 ID“${id || "空"}”格式无效`);
            }
            if (ids.has(id)) {
                errors.push(`重复 ID“${id}”`);
            }
            ids.add(id);
            if (!String(resource.name || "").trim()) {
                errors.push(`资源“${id || "未命名"}”的显示名称不能为空`);
            }
        }
        validateRegions(errors);
        validateCaches(errors);
        validatePickupWorkflows(errors);
        return [...new Set(errors)];
    }

    /**
     * @param {Array<string>} errors
     * @returns {void}
     */
    function validateRegions(errors) {
        for (const region of state.pickupDraft.pickupRegions) {
            const label = `拾取区域“${region.name || region.id}”`;
            const standby = coordinateFor(region.standbyCoordinateId);
            if (!standby) {
                errors.push(`${label}必须选择一个已保存的命名坐标作为待机点`);
                continue;
            }
            if (standby.dimension !== region.dimension) {
                errors.push(`${label}的待机点与区域跨维度`);
            }
            if (region.shape?.type === "CUBOID") {
                const first = coordinateFor(region.shape.firstCornerCoordinateId);
                const second = coordinateFor(region.shape.secondCornerCoordinateId);
                if (!first || !second) {
                    errors.push(`${label}的长方体角点必须引用命名坐标`);
                    continue;
                }
                if (first.dimension !== region.dimension
                        || second.dimension !== region.dimension) {
                    errors.push(`${label}的角点与区域跨维度`);
                }
                if (comparePosition(first.position, second.position) > 0) {
                    errors.push(`${label}的第一角点不能大于第二角点`);
                }
            } else {
                const center = coordinateFor(region.shape?.centerCoordinateId);
                if (!center) {
                    errors.push(`${label}的球心必须引用命名坐标`);
                } else if (center.dimension !== region.dimension) {
                    errors.push(`${label}的球心与区域跨维度`);
                }
                if (!Number.isInteger(region.shape?.radius)
                        || region.shape.radius < 1
                        || region.shape.radius > 128) {
                    errors.push(`${label}的半径必须为 1-128`);
                }
            }
        }
    }

    /**
     * @param {Array<string>} errors
     * @returns {void}
     */
    function validateCaches(errors) {
        for (const cache of state.pickupDraft.cacheContainers) {
            const label = `缓存容器“${cache.name || cache.id}”`;
            const coordinate = coordinateFor(cache.coordinateId);
            if (!coordinate) {
                errors.push(`${label}必须选择一个已保存的命名坐标`);
            } else if (coordinate.dimension !== cache.dimension) {
                errors.push(`${label}与所选坐标跨维度`);
            }
        }
    }

    /**
     * @param {Array<string>} errors
     * @returns {void}
     */
    function validatePickupWorkflows(errors) {
        for (const workflow of state.pickupDraft.pickupWorkflows) {
            const label = `拾取流程“${workflow.name || workflow.id}”`;
            if (!workflow.regions || workflow.regions.length === 0) {
                errors.push(`${label}至少选择一个区域`);
            }
            if (!workflow.cacheIds || workflow.cacheIds.length === 0) {
                errors.push(`${label}至少选择一个缓存`);
            }
            for (const id of workflow.cacheIds || []) {
                if (!state.pickupDraft.cacheContainers.some((cache) => cache.id === id)) {
                    errors.push(`${label}引用了不存在的缓存“${id}”`);
                }
            }
            for (const binding of workflow.regions || []) {
                const region = state.pickupDraft.pickupRegions.find((item) => {
                    return item.id === binding.regionId;
                });
                if (!region) {
                    errors.push(`${label}引用了不存在的区域“${binding.regionId}”`);
                    continue;
                }
                if (region.dimension !== workflow.dimension) {
                    errors.push(`${label}与区域“${region.name || region.id}”跨维度`);
                }
                if (!Number.isInteger(binding.idleDelay) || binding.idleDelay < 0) {
                    errors.push(`${label}的区域空闲时间必须是正整数`);
                }
                if (!Number.isInteger(binding.priority)) {
                    errors.push(`${label}的区域优先级必须是整数`);
                }
            }
            const caches = (workflow.cacheIds || [])
                .map((id) => state.pickupDraft.cacheContainers.find((item) => item.id === id))
                .filter(Boolean);
            if (caches.some((cache) => cache.dimension !== workflow.dimension)) {
                errors.push(`${label}与缓存跨维度`);
            }
            if (!Number.isInteger(workflow.completionIdleDelay)
                    || workflow.completionIdleDelay < 0) {
                errors.push(`${label}的完成空闲时间必须是正整数`);
            }
        }
    }

    /**
     * @param {object} first
     * @param {object} second
     * @returns {number}
     */
    function comparePosition(first, second) {
        if (!first || !second) {
            return 0;
        }
        for (const axis of ["x", "y", "z"]) {
            const difference = Number(first[axis]) - Number(second[axis]);
            if (difference !== 0) {
                return difference;
            }
        }
        return 0;
    }

    /**
     * @returns {void}
     */
    function updatePickupState() {
        const errors = validatePickupDraft();
        elements.pickupSave.disabled = !state.pickupDirty || errors.length > 0;
        elements.pickupStatus.classList.toggle("dirty", state.pickupDirty);
        if (state.pickupDirty && errors.length > 0) {
            elements.pickupStatus.textContent = "草稿中有错误，修正后才能保存";
        } else if (state.pickupDirty) {
            elements.pickupStatus.textContent = "有未保存的拾取配置修改";
        } else {
            elements.pickupStatus.textContent = "拾取配置已与配置文件同步";
        }
    }

    /**
     * @param {Array<string>} errors
     * @returns {void}
     */
    function showPickupErrors(errors) {
        elements.pickupErrors.replaceChildren();
        if (errors.length === 0) {
            return;
        }
        elements.pickupErrors.append(Stowling.element("strong", "", "请修正以下问题："));
        const list = Stowling.element("ul");
        for (const error of errors) {
            list.append(Stowling.element("li", "", error));
        }
        elements.pickupErrors.append(list);
    }

    /**
     * @returns {object}
     */
    function hydratePickupRequest() {
        const points = new Map(state.coordinates.map((point) => [point.id, point]));
        const requirePoint = (id, label) => {
            const point = points.get(id);
            if (!point) {
                throw new Error(`${label}引用的命名坐标不存在或无效`);
            }
            return Stowling.clone(point.position);
        };
        const regions = state.pickupDraft.pickupRegions.map((region) => {
            const standby = requirePoint(region.standbyCoordinateId, `区域 ${region.id} 待机点`);
            let shape;
            if (region.shape?.type === "CUBOID") {
                shape = {
                    type: "CUBOID",
                    firstCornerCoordinateId: region.shape.firstCornerCoordinateId,
                    secondCornerCoordinateId: region.shape.secondCornerCoordinateId,
                    firstCorner: requirePoint(
                        region.shape.firstCornerCoordinateId,
                        `区域 ${region.id} 第一角点`
                    ),
                    secondCorner: requirePoint(
                        region.shape.secondCornerCoordinateId,
                        `区域 ${region.id} 第二角点`
                    )
                };
            } else {
                shape = {
                    type: "SPHERE",
                    centerCoordinateId: region.shape?.centerCoordinateId,
                    center: requirePoint(
                        region.shape?.centerCoordinateId,
                        `区域 ${region.id} 球心`
                    ),
                    radius: region.shape?.radius
                };
            }
            return {
                id: region.id,
                name: region.name,
                dimension: region.dimension,
                standbyCoordinateId: region.standbyCoordinateId,
                standbyPosition: standby,
                shape
            };
        });
        const caches = state.pickupDraft.cacheContainers.map((cache) => ({
            id: cache.id,
            name: cache.name,
            coordinateId: cache.coordinateId,
            dimension: cache.dimension,
            position: requirePoint(cache.coordinateId, `缓存 ${cache.id}`),
            type: cache.type
        }));
        return {
            revision: state.pickupSnapshot.revision,
            pickupRegions: regions,
            cacheContainers: caches,
            pickupWorkflows: Stowling.clone(state.pickupDraft.pickupWorkflows)
        };
    }

    /**
     * @returns {Promise<void>}
     */
    async function savePickupConfig() {
        const errors = validatePickupDraft();
        if (errors.length > 0) {
            showPickupErrors(errors);
            Stowling.showToast("拾取配置仍有错误，暂未保存", true);
            return;
        }
        let body;
        try {
            body = hydratePickupRequest();
        } catch (error) {
            showPickupErrors([error.message]);
            return;
        }
        elements.pickupSave.disabled = true;
        elements.pickupDiscard.disabled = true;
        elements.pickupStatus.textContent = "正在保存拾取配置…";
        try {
            const saved = await Stowling.request("/v1/config/pickup", {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(body)
            });
            renderPickupSnapshot(saved);
            Stowling.showToast("拾取配置已保存，旧配置已生成备份");
        } catch (error) {
            const conflict = error instanceof Stowling.ApiError && error.status === 409;
            let message = error instanceof Error ? error.message : "保存拾取配置失败";
            if (conflict) {
                message = error?.code === "active_task_conflict"
                    ? `活动或暂停任务存在：${message}`
                    : `配置已被外部修改：${message}`;
            }
            showPickupErrors([message]);
            elements.pickupStatus.textContent = conflict
                ? "配置版本冲突或任务正在运行，草稿已保留"
                : "拾取配置保存失败，草稿仍在";
            Stowling.handleError(error, "保存拾取配置失败");
        } finally {
            elements.pickupDiscard.disabled = false;
            updatePickupState();
        }
    }

    /**
     * @returns {Promise<void>}
     */
    async function discardAllChanges() {
        elements.baseDiscard.disabled = true;
        elements.pickupDiscard.disabled = true;
        const results = await Promise.allSettled([
            loadBaseCoordinates(),
            loadPackages()
        ]);
        const failure = results.find((result) => result.status === "rejected");
        if (failure) {
            Stowling.handleError(failure.reason, "重新读取配置失败");
        } else {
            Stowling.showToast("已放弃修改并重新读取配置");
        }
        elements.baseDiscard.disabled = false;
        elements.pickupDiscard.disabled = false;
    }

    elements.refresh.addEventListener("click", () => {
        loadStorage().catch((error) => Stowling.handleError(error, "刷新仓库失败"));
    });
    elements.audit.addEventListener("click", () => {
        void requestAudit();
    });
    elements.baseForm.addEventListener("submit", (event) => {
        event.preventDefault();
        void saveBaseCoordinates();
    });
    elements.baseDiscard.addEventListener("click", () => {
        loadBaseCoordinates()
            .then(() => Stowling.showToast("已重新读取仓库坐标"))
            .catch((error) => Stowling.handleError(error, "读取仓库坐标失败"));
    });
    elements.addRegion.addEventListener("click", addRegion);
    elements.addCache.addEventListener("click", addCache);
    elements.addPickupWorkflow.addEventListener("click", addPickupWorkflow);
    elements.pickupForm.addEventListener("submit", (event) => {
        event.preventDefault();
        void savePickupConfig();
    });
    elements.pickupDiscard.addEventListener("click", () => {
        void discardAllChanges();
    });

    Stowling.onConnected(async () => {
        const results = await Promise.allSettled([
            loadStorage(),
            loadBaseCoordinates(),
            loadPackages()
        ]);
        for (const result of results) {
            if (result.status === "rejected") {
                Stowling.handleError(result.reason, "读取仓库页面失败");
            }
        }
    });
    Stowling.mountPage("storage");
})();
