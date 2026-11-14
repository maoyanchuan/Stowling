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
        pickupInvalidFields: new Set(),
        automationReferences: new Map()
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
                Stowling.containerTypeLabel(container.type || "OTHER"),
                Stowling.dimensionLabel(container.dimension),
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
                metaText("维度", Stowling.dimensionLabel(snapshot.dimension)),
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
        elements.baseRevision.textContent = Stowling.revisionLabel(
            state.baseSnapshot.revision
        );
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
            Stowling.element(
                "span",
                "",
                `${Stowling.dimensionLabel(coordinate.dimension)} · ${coordinate.key}`
            )
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
            elements.baseRevision.textContent = Stowling.revisionLabel(
                state.baseSnapshot.revision
            );
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
            Stowling.request("/v1/config/pickup"),
            Stowling.request("/v1/config/automation-workflows")
        ]);
        state.coordinates = results[0]?.coordinatePoints || [];
        state.automationReferences = collectAutomationReferences(results[2]);
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
        for (const resource of [
            ...state.pickupDraft.pickupRegions,
            ...state.pickupDraft.cacheContainers,
            ...state.pickupDraft.pickupWorkflows
        ]) {
            resource._originalId = String(resource.id || "");
        }
        state.pickupDirty = false;
        elements.pickupStatus.textContent = "拾取配置已与配置文件同步";
        elements.pickupRevision.textContent = Stowling.revisionLabel(
            state.pickupSnapshot.revision
        );
        elements.pickupRevision.title = state.pickupSnapshot.revision;
        renderPickupEditor();
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
                "region"
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
                "cache"
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
            `维度由坐标决定：${Stowling.dimensionLabel(cache.dimension)}`
        );
        card.append(hint);
        return card;
    }

    /**
     * @param {string} type
     * @returns {string}
     */
    function containerTypeLabel(type) {
        return Stowling.containerTypeLabel(type);
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
                "workflow"
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
                `pickup-workflow-cache-policy-${index}`,
                "缓存选择策略",
                [["NEAREST_AVAILABLE", Stowling.cachePolicyLabel("NEAREST_AVAILABLE")]],
                workflow.cachePolicy || "NEAREST_AVAILABLE",
                (value) => {
                    workflow.cachePolicy = value;
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
        regions.id = `pickup-workflow-regions-${index}`;
        regions.dataset.validationContainer = "true";
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
        checks.id = `pickup-workflow-caches-${index}`;
        checks.dataset.validationContainer = "true";
        workflow.cacheIds ||= [];
        state.pickupDraft.cacheContainers.forEach((cache, cacheIndex) => {
            const label = Stowling.element("label", "check-option");
            const checkbox = document.createElement("input");
            checkbox.type = "checkbox";
            checkbox.id = `pickup-workflow-cache-${index}-${cacheIndex}`;
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
        });
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
     * @param {string} kind
     * @returns {HTMLElement}
     */
    function editableIdField(id, resource, kind) {
        const input = document.createElement("input");
        input.id = id;
        input.value = String(resource.id || "");
        input.dataset.lastValidId = String(resource.id || "");
        input.pattern = "[a-z0-9][a-z0-9._-]{0,63}";
        input.addEventListener("input", () => {
            updateResourceId(resource, kind, input);
            markPickupDirty();
        });
        return field("资源 ID", input);
    }

    /**
     * @param {object} resource
     * @param {string} kind
     * @param {HTMLInputElement} input
     * @returns {void}
     */
    function updateResourceId(resource, kind, input) {
        const candidate = input.value.trim();
        const previousValid = String(input.dataset.lastValidId || "");
        resource.id = candidate;
        if (!validResourceId(candidate)
                || pickupResources().some((other) => {
                    return other !== resource && String(other.id || "") === candidate;
                })) {
            return;
        }
        if (kind !== "workflow" && previousValid && previousValid !== candidate) {
            replacePickupReferences(kind, previousValid, candidate);
        }
        input.dataset.lastValidId = candidate;
    }

    /**
     * @param {string} value
     * @returns {boolean}
     */
    function validResourceId(value) {
        return /^[a-z0-9][a-z0-9._-]{0,63}$/.test(String(value || ""));
    }

    /**
     * @param {string} kind
     * @param {string} oldId
     * @param {string} newId
     * @returns {void}
     */
    function replacePickupReferences(kind, oldId, newId) {
        if (kind === "region") {
            for (const workflow of state.pickupDraft.pickupWorkflows) {
                for (const binding of workflow.regions || []) {
                    if (binding.regionId === oldId) {
                        binding.regionId = newId;
                    }
                }
            }
        }
        if (kind === "cache") {
            for (const workflow of state.pickupDraft.pickupWorkflows) {
                workflow.cacheIds = (workflow.cacheIds || []).map((id) => {
                    return id === oldId ? newId : id;
                });
            }
        }
    }

    /**
     * @param {object|null} source
     * @returns {Map<string, Array<string>>}
     */
    function collectAutomationReferences(source) {
        const references = new Map();
        for (const workflow of source?.automationWorkflows || []) {
            for (const step of workflow.steps || []) {
                const pickupId = String(step.parameters?.workflowId || "");
                if (!pickupId) {
                    continue;
                }
                const label = `任务“${workflow.name || workflow.id}”`
                    + `的第 ${step.id || "未知"} 步`;
                const values = references.get(pickupId) || [];
                if (!values.includes(label)) {
                    values.push(label);
                    references.set(pickupId, values);
                }
            }
        }
        return references;
    }

    /**
     * @returns {Array<object>}
     */
    function pickupResources() {
        return [
            ...state.pickupDraft.pickupRegions,
            ...state.pickupDraft.cacheContainers,
            ...state.pickupDraft.pickupWorkflows
        ];
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
            DIMENSIONS.map((dimension) => [
                dimension,
                Stowling.dimensionLabel(dimension)
            ]),
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
            option.textContent = `${point.name || point.id} · `
                + Stowling.dimensionLabel(point.dimension);
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
        const references = [
            ...pickupReferences(kind, resource.id),
            ...pickupReferences(kind, resource._originalId),
            ...(kind === "workflow"
                ? automationReferencesFor(resource.id, resource._originalId)
                : [])
        ];
        if (references.length > 0) {
            showPickupErrors([
                `无法删除${label}“${resource.name || resource.id}”：仍被`
                    + ` ${[...new Set(references)].join("、")} 引用。`
                    + "请先解除引用并保存。"
            ]);
            return;
        }
        collection.splice(index, 1);
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
            },
            _originalId: ""
        });
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
            type: "CHEST",
            _originalId: ""
        });
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
            completionIdleDelay: 300,
            _originalId: ""
        });
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
        state.pickupInvalidFields.clear();
        const groups = [
            ["region", "拾取区域", state.pickupDraft.pickupRegions],
            ["cache", "缓存容器", state.pickupDraft.cacheContainers],
            ["workflow", "拾取流程", state.pickupDraft.pickupWorkflows]
        ];
        for (const [kind, typeLabel, resources] of groups) {
            resources.forEach((resource, index) => {
                const id = String(resource.id || "").trim();
                const name = String(resource.name || "").trim() || id || "未命名";
                const label = `${typeLabel} ${index + 1}“${name}”`;
                const idField = resourceFieldId(kind, index, "id");
                const nameField = resourceFieldId(kind, index, "name");
                if (!id) {
                    addPickupError(
                        errors,
                        `${label}的资源 ID 不能为空；请输入 1-64 位小写字母、数字、`
                            + "点、下划线或连字符，且必须以字母或数字开头",
                        idField
                    );
                } else if (!validResourceId(id)) {
                    addPickupError(
                        errors,
                        `${label}的资源 ID“${id}”格式无效；只能使用小写字母、数字、`
                            + "点、下划线和连字符",
                        idField
                    );
                } else if (ids.has(id)) {
                    addPickupError(
                        errors,
                        `${label}使用了重复 ID“${id}”；请改成未使用的 ID`,
                        idField
                    );
                }
                if (id) {
                    ids.add(id);
                }
                if (!String(resource.name || "").trim()) {
                    addPickupError(
                        errors,
                        `${label}的显示名称不能为空；请输入便于识别的名称`,
                        nameField
                    );
                }
                validateRenamedResource(
                    kind,
                    resource,
                    id,
                    label,
                    idField,
                    errors
                );
            });
        }
        validateRegions(errors);
        validateCaches(errors);
        validatePickupWorkflows(errors);
        return [...new Set(errors)];
    }

    /**
     * @param {string} kind
     * @param {number} index
     * @param {string} field
     * @returns {string}
     */
    function resourceFieldId(kind, index, field) {
        if (kind === "workflow") {
            return `pickup-workflow-${field}-${index}`;
        }
        return `${kind}-${field}-${index}`;
    }

    /**
     * @param {string} kind
     * @param {object} resource
     * @param {string} id
     * @param {string} label
     * @param {string} idField
     * @param {Array<string>} errors
     * @returns {void}
     */
    function validateRenamedResource(kind, resource, id, label, idField, errors) {
        const originalId = String(resource._originalId || "");
        if (!originalId || id === originalId) {
            return;
        }
        if (kind === "workflow") {
            const references = automationReferencesFor(originalId);
            if (references.length > 0) {
                addPickupError(
                    errors,
                    `${label}的 ID 已从“${originalId}”改为“${id || "空"}”，但旧 ID `
                        + `仍被 ${references.join("、")} 引用；请先修改这些任务，或恢复旧 ID`,
                    idField
                );
            }
            return;
        }
        const references = pickupReferences(kind, originalId);
        if (references.length > 0) {
            addPickupError(
                errors,
                `${label}的 ID 正在从“${originalId}”改为“${id || "空"}”，但草稿引用`
                    + `尚未同步；请输入格式正确且不重复的新 ID`,
                idField
            );
        }
    }

    /**
     * @param {...unknown} resourceIds
     * @returns {Array<string>}
     */
    function automationReferencesFor(...resourceIds) {
        const references = [];
        const seenIds = new Set();
        for (const value of resourceIds) {
            const id = String(value || "").trim();
            if (!id || seenIds.has(id)) {
                continue;
            }
            seenIds.add(id);
            references.push(...(state.automationReferences.get(id) || []));
        }
        return [...new Set(references)];
    }

    /**
     * @param {Array<string>} errors
     * @param {string} message
     * @param {...string} fieldIds
     * @returns {void}
     */
    function addPickupError(errors, message, ...fieldIds) {
        errors.push(message);
        for (const fieldId of fieldIds.filter(Boolean)) {
            state.pickupInvalidFields.add(fieldId);
        }
    }

    /**
     * @param {Array<string>} errors
     * @returns {void}
     */
    function validateRegions(errors) {
        state.pickupDraft.pickupRegions.forEach((region, index) => {
            const label = `拾取区域 ${index + 1}“${region.name || region.id || "未命名"}”`;
            const standby = coordinateFor(region.standbyCoordinateId);
            if (!standby) {
                addPickupError(
                    errors,
                    `${label}必须选择一个已保存的命名坐标作为待机点；请从下拉框选择`,
                    `region-standby-${index}`
                );
            } else if (standby.dimension !== region.dimension) {
                addPickupError(
                    errors,
                    `${label}的待机点位于${Stowling.dimensionLabel(standby.dimension)}，`
                        + `与区域维度${Stowling.dimensionLabel(region.dimension)}不一致`,
                    `region-standby-${index}`,
                    `region-dimension-${index}`
                );
            }
            if (region.shape?.type === "CUBOID") {
                const first = coordinateFor(region.shape.firstCornerCoordinateId);
                const second = coordinateFor(region.shape.secondCornerCoordinateId);
                if (!first) {
                    addPickupError(
                        errors,
                        `${label}的第一角点必须选择一个命名坐标`,
                        `region-first-${index}`
                    );
                }
                if (!second) {
                    addPickupError(
                        errors,
                        `${label}的第二角点必须选择一个命名坐标`,
                        `region-second-${index}`
                    );
                }
                if (first && second && (
                    first.dimension !== region.dimension
                        || second.dimension !== region.dimension)) {
                    addPickupError(
                        errors,
                        `${label}的角点维度必须与区域维度一致`,
                        `region-first-${index}`,
                        `region-second-${index}`
                    );
                }
                if (first && second
                        && comparePosition(first.position, second.position) > 0) {
                    addPickupError(
                        errors,
                        `${label}的第一角点必须位于第二角点的负方向；请交换两个角点`,
                        `region-first-${index}`,
                        `region-second-${index}`
                    );
                }
            } else {
                const center = coordinateFor(region.shape?.centerCoordinateId);
                if (!center) {
                    addPickupError(
                        errors,
                        `${label}的球心必须选择一个命名坐标`,
                        `region-center-${index}`
                    );
                } else if (center.dimension !== region.dimension) {
                    addPickupError(
                        errors,
                        `${label}的球心维度必须与区域维度一致`,
                        `region-center-${index}`,
                        `region-dimension-${index}`
                    );
                }
                if (!Number.isInteger(region.shape?.radius)
                        || region.shape.radius < 1
                        || region.shape.radius > 128) {
                    addPickupError(
                        errors,
                        `${label}的半径必须为 1-128；请填写整数`,
                        `region-radius-${index}`
                    );
                }
            }
        });
    }

    /**
     * @param {Array<string>} errors
     * @returns {void}
     */
    function validateCaches(errors) {
        state.pickupDraft.cacheContainers.forEach((cache, index) => {
            const label = `缓存容器 ${index + 1}`
                + `“${cache.name || cache.id || "未命名"}”`;
            const coordinate = coordinateFor(cache.coordinateId);
            if (!coordinate) {
                addPickupError(
                    errors,
                    `${label}必须选择一个已保存的命名坐标`,
                    `cache-coordinate-${index}`
                );
            } else if (coordinate.dimension !== cache.dimension) {
                addPickupError(
                    errors,
                    `${label}的维度与命名坐标不一致；请重新选择坐标`,
                    `cache-coordinate-${index}`
                );
            }
        });
    }

    /**
     * @param {Array<string>} errors
     * @returns {void}
     */
    function validatePickupWorkflows(errors) {
        state.pickupDraft.pickupWorkflows.forEach((workflow, workflowIndex) => {
            const label = `拾取流程 ${workflowIndex + 1}`
                + `“${workflow.name || workflow.id || "未命名"}”`;
            const cacheListField = `pickup-workflow-caches-${workflowIndex}`;
            if (!workflow.regions || workflow.regions.length === 0) {
                addPickupError(
                    errors,
                    `${label}至少选择一个区域；请从区域下拉框添加`,
                    `pickup-workflow-regions-${workflowIndex}`
                );
            }
            if (!workflow.cacheIds || workflow.cacheIds.length === 0) {
                addPickupError(
                    errors,
                    `${label}至少选择一个缓存；请勾选可用缓存`,
                    cacheListField
                );
            }
            for (const id of workflow.cacheIds || []) {
                if (!state.pickupDraft.cacheContainers.some((cache) => cache.id === id)) {
                    addPickupError(
                        errors,
                        `${label}引用了不存在的缓存“${id}”；请取消勾选或重新创建缓存`,
                        cacheListField
                    );
                }
            }
            (workflow.regions || []).forEach((binding, bindingIndex) => {
                const region = state.pickupDraft.pickupRegions.find((item) => {
                    return item.id === binding.regionId;
                });
                if (!region) {
                    addPickupError(
                        errors,
                        `${label}的第 ${bindingIndex + 1} 个区域引用不存在；请移除后`
                            + "重新添加区域",
                        `binding-priority-${workflowIndex}-${bindingIndex}`
                    );
                } else if (region.dimension !== workflow.dimension) {
                    addPickupError(
                        errors,
                        `${label}与区域“${region.name || region.id}”维度不一致；`
                            + "请统一流程和区域维度",
                        `pickup-workflow-dimension-${workflowIndex}`,
                        `region-dimension-${state.pickupDraft.pickupRegions.indexOf(region)}`
                    );
                }
                if (!Number.isInteger(binding.idleDelay) || binding.idleDelay < 0) {
                    addPickupError(
                        errors,
                        `${label}的第 ${bindingIndex + 1} 个区域空闲秒数必须`
                            + "是非负整数",
                        `binding-idle-${workflowIndex}-${bindingIndex}`
                    );
                }
                if (!Number.isInteger(binding.priority)) {
                    addPickupError(
                        errors,
                        `${label}的第 ${bindingIndex + 1} 个区域优先级必须是整数`,
                        `binding-priority-${workflowIndex}-${bindingIndex}`
                    );
                }
            });
            const caches = (workflow.cacheIds || [])
                .map((id) => state.pickupDraft.cacheContainers.find((item) => item.id === id))
                .filter(Boolean);
            if (caches.some((cache) => cache.dimension !== workflow.dimension)) {
                addPickupError(
                    errors,
                    `${label}与所选缓存维度不一致；请调整流程或缓存坐标`,
                    `pickup-workflow-dimension-${workflowIndex}`,
                    cacheListField
                );
            }
            if (!Number.isInteger(workflow.completionIdleDelay)
                    || workflow.completionIdleDelay < 0) {
                addPickupError(
                    errors,
                    `${label}的流程空闲秒数必须是非负整数`,
                    `pickup-workflow-idle-${workflowIndex}`
                );
            }
            if (workflow.cachePolicy !== "NEAREST_AVAILABLE") {
                addPickupError(
                    errors,
                    `${label}的缓存选择策略无效；请选择“最近可用缓存”`,
                    `pickup-workflow-cache-policy-${workflowIndex}`
                );
            }
            if (!["AFTER_REGION", "AFTER_ROUND"].includes(
                workflow.classificationTiming
            )) {
                addPickupError(
                    errors,
                    `${label}的分类时机无效；请重新选择`,
                    `pickup-workflow-timing-${workflowIndex}`
                );
            }
            if (!["MANUAL", "ALL_IDLE"].includes(workflow.completionMode)) {
                addPickupError(
                    errors,
                    `${label}的结束方式无效；请重新选择`,
                    `pickup-workflow-mode-${workflowIndex}`
                );
            }
        });
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
     * @param {boolean} showValidation
     * @returns {void}
     */
    function updatePickupState(showValidation = true) {
        const errors = validatePickupDraft();
        elements.pickupSave.disabled = !state.pickupDirty || errors.length > 0;
        elements.pickupStatus.classList.toggle("dirty", state.pickupDirty);
        if (showValidation) {
            showPickupErrors(state.pickupDirty || errors.length > 0 ? errors : []);
        }
        applyPickupValidationStyles();
        if (state.pickupDirty && errors.length > 0) {
            elements.pickupStatus.textContent =
                `发现 ${errors.length} 个拾取配置问题，保存已暂停`;
        } else if (state.pickupDirty) {
            elements.pickupStatus.textContent = "有未保存的拾取配置修改";
        } else {
            elements.pickupStatus.textContent = "拾取配置已与配置文件同步";
        }
    }

    /**
     * @returns {void}
     */
    function applyPickupValidationStyles() {
        for (const card of elements.pickupForm.querySelectorAll(".editor-card")) {
            card.classList.remove("has-error");
        }
        for (const control of elements.pickupForm.querySelectorAll(
            "input, select, textarea, [data-validation-container]"
        )) {
            control.setAttribute("aria-invalid", "false");
            control.closest(".editor-field")?.classList.remove("has-error");
        }
        for (const fieldId of state.pickupInvalidFields) {
            const control = document.getElementById(fieldId);
            if (!control) {
                continue;
            }
            control.setAttribute("aria-invalid", "true");
            control.closest(".editor-field")?.classList.add("has-error");
            control.closest(".editor-card")?.classList.add("has-error");
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
            pickupWorkflows: state.pickupDraft.pickupWorkflows.map((workflow) => ({
                id: String(workflow.id || "").trim(),
                name: String(workflow.name || "").trim(),
                dimension: workflow.dimension,
                regions: Stowling.clone(workflow.regions || []),
                cacheIds: Stowling.clone(workflow.cacheIds || []),
                cachePolicy: workflow.cachePolicy,
                classificationTiming: workflow.classificationTiming,
                completionMode: workflow.completionMode,
                completionIdleDelay: workflow.completionIdleDelay
            }))
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
            updatePickupState(false);
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
