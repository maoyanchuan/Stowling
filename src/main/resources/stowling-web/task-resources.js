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

    const elements = {
        regionList: document.querySelector("#pickup-region-list"),
        cacheList: document.querySelector("#cache-list"),
        addRegion: document.querySelector("#add-pickup-region-button"),
        addCache: document.querySelector("#add-cache-button"),
        pickupErrors: document.querySelector("#pickup-resource-errors"),
        pickupSave: document.querySelector("#save-pickup-resources-button"),
        pickupDiscard: document.querySelector("#discard-pickup-resources-button"),
        pickupStatus: document.querySelector("#pickup-resource-status"),
        pickupRevision: document.querySelector("#pickup-resource-revision"),
        baseList: document.querySelector("#base-coordinate-list"),
        baseErrors: document.querySelector("#base-coordinate-errors"),
        baseSave: document.querySelector("#save-base-coordinates-button"),
        baseDiscard: document.querySelector("#discard-base-coordinates-button"),
        baseStatus: document.querySelector("#base-coordinate-status"),
        baseRevision: document.querySelector("#base-coordinate-revision")
    };

    const state = {
        coordinateSnapshot: null,
        pickupSnapshot: null,
        pickupDraft: null,
        baseSnapshot: null,
        baseDraft: [],
        pickupDirty: false,
        baseDirty: false,
        pickupErrors: [],
        baseErrors: [],
        changedHandlers: new Set()
    };

    /**
     * @returns {Promise<object>}
     */
    async function load() {
        elements.pickupStatus.textContent = "正在读取拾取资源…";
        elements.baseStatus.textContent = "正在读取仓库基础坐标…";
        const results = await Promise.all([
            Stowling.request("/v1/config/coordinate-points"),
            Stowling.request("/v1/config/task-resources"),
            Stowling.request("/v1/config/coordinates")
        ]);
        applyCoordinateSnapshot(results[0]);
        applyPickupSnapshot(results[1]);
        applyBaseSnapshot(results[2]);
        return getSnapshot();
    }

    /**
     * @param {object} snapshot
     * @returns {void}
     */
    function applyCoordinateSnapshot(snapshot) {
        state.coordinateSnapshot = Stowling.clone({
            revision: String(snapshot?.revision || ""),
            coordinatePoints: Array.isArray(snapshot?.coordinatePoints)
                ? snapshot.coordinatePoints
                : []
        });
    }

    /**
     * @param {object} snapshot
     * @returns {void}
     */
    function applyPickupSnapshot(snapshot) {
        state.pickupSnapshot = Stowling.clone({
            revision: String(snapshot?.revision || ""),
            pickupRegions: Array.isArray(snapshot?.pickupRegions)
                ? snapshot.pickupRegions
                : [],
            cacheContainers: Array.isArray(snapshot?.cacheContainers)
                : []
        });
        state.pickupDraft = Stowling.clone(state.pickupSnapshot);
        for (const region of state.pickupDraft.pickupRegions) {
            region._originalId = String(region.id || "");
        }
        for (const cache of state.pickupDraft.cacheContainers) {
            cache._originalId = String(cache.id || "");
        }
        state.pickupDirty = false;
        state.pickupErrors = [];
        renderPickupResources();
        elements.pickupRevision.textContent = Stowling.revisionLabel(
            state.pickupSnapshot.revision
        );
        elements.pickupRevision.title = state.pickupSnapshot.revision;
        elements.pickupStatus.textContent = "拾取资源已与配置文件同步";
    }

    /**
     * @param {object} snapshot
     * @returns {void}
     */
    function applyBaseSnapshot(snapshot) {
        state.baseSnapshot = Stowling.clone({
            revision: String(snapshot?.revision || ""),
            coordinates: Array.isArray(snapshot?.coordinates)
                ? snapshot.coordinates
                : []
        });
        state.baseDraft = Stowling.clone(state.baseSnapshot.coordinates);
        state.baseDirty = false;
        state.baseErrors = [];
        renderBaseCoordinates();
        elements.baseRevision.textContent = Stowling.revisionLabel(
            state.baseSnapshot.revision
        );
        elements.baseRevision.title = state.baseSnapshot.revision;
        elements.baseStatus.textContent = "仓库基础坐标已与配置文件同步";
    }

    /**
     * @returns {object}
     */
    function getSnapshot() {
        return Stowling.clone({
            revision: state.coordinateSnapshot?.revision || "",
            coordinatePoints: state.coordinateSnapshot?.coordinatePoints || [],
            pickupRevision: state.pickupSnapshot?.revision || "",
            pickupRegions: state.pickupSnapshot?.pickupRegions || [],
            cacheContainers: state.pickupSnapshot?.cacheContainers || [],
            baseCoordinates: state.baseSnapshot?.coordinates || []
        });
    }

    /**
     * @returns {void}
     */
    function renderPickupResources() {
        elements.regionList.replaceChildren();
        elements.cacheList.replaceChildren();
        if (!state.pickupDraft) {
            return;
        }
        state.pickupDraft.pickupRegions.forEach((region, index) => {
            elements.regionList.append(createRegionCard(region, index));
        });
        state.pickupDraft.cacheContainers.forEach((cache, index) => {
            elements.cacheList.append(createCacheCard(cache, index));
        });
        if (state.pickupDraft.pickupRegions.length === 0) {
            elements.regionList.append(Stowling.emptyState("尚未创建拾取区域"));
        }
        if (state.pickupDraft.cacheContainers.length === 0) {
            elements.cacheList.append(Stowling.emptyState("尚未创建缓存容器"));
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
        const heading = Stowling.element("div", "editor-card-heading");
        heading.append(
            Stowling.element(
                "strong",
                "",
                String(region.name || region.id || "未命名区域")
            ),
            deleteResourceButton("删除区域", () => removeRegion(index))
        );
        const fields = Stowling.element("div", "editor-fields");
        fields.append(
            field(
                "区域 ID",
                textInput(
                    `pickup-region-id-${index}`,
                    "区域 ID",
                    region.id,
                    Boolean(region._originalId),
                    (value) => {
                        region.id = value;
                        markPickupDirty();
                    }
                )
            ),
            field(
                "显示名称",
                textInput(
                    `pickup-region-name-${index}`,
                    "显示名称",
                    region.name,
                    false,
                    (value) => {
                        region.name = value;
                        markPickupDirty();
                    }
                )
            ),
            field(
                "维度",
                dimensionSelect(
                    `pickup-region-dimension-${index}`,
                    region.dimension,
                    (value) => {
                        region.dimension = value;
                        markPickupDirty();
                    }
                )
            ),
            field(
                "待机命名坐标",
                coordinateSelect(
                    `pickup-region-standby-${index}`,
                    region.standbyCoordinateId,
                    (value) => {
                        region.standbyCoordinateId = value;
                        const point = coordinateFor(value);
                        if (point) {
                            region.dimension = point.dimension;
                        }
                        markPickupDirty(true);
                    }
                )
            ),
            field(
                "范围形状",
                shapeSelect(region, index)
            )
        );
        if (region.shape?.type === "CUBOID") {
            fields.append(
                field(
                    "第一角点",
                    coordinateSelect(
                        `pickup-region-first-${index}`,
                        region.shape.firstCornerCoordinateId,
                        (value) => {
                            region.shape.firstCornerCoordinateId = value;
                            markPickupDirty();
                        }
                    )
                ),
                field(
                    "第二角点",
                    coordinateSelect(
                        `pickup-region-second-${index}`,
                        region.shape.secondCornerCoordinateId,
                        (value) => {
                            region.shape.secondCornerCoordinateId = value;
                            markPickupDirty();
                        }
                    )
                )
            );
        } else {
            fields.append(
                field(
                    "球心坐标",
                    coordinateSelect(
                        `pickup-region-center-${index}`,
                        region.shape?.centerCoordinateId,
                        (value) => {
                            region.shape.centerCoordinateId = value;
                            markPickupDirty();
                        }
                    )
                ),
                field(
                    "半径",
                    numberInput(
                        `pickup-region-radius-${index}`,
                        "半径",
                        region.shape?.radius,
                        (value) => {
                            region.shape.radius = value;
                            markPickupDirty();
                        }
                    )
                )
            );
        }
        card.append(heading, fields);
        return card;
    }

    /**
     * @param {object} region
     * @param {number} index
     * @returns {HTMLSelectElement}
     */
    function shapeSelect(region, index) {
        const select = selectInput(
            `pickup-region-shape-${index}`,
            "范围形状",
            [
                ["SPHERE", "球形"],
                ["CUBOID", "长方体"]
            ],
            region.shape?.type || "SPHERE",
            (value) => {
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
            }
        );
        return select;
    }

    /**
     * @param {object} cache
     * @param {number} index
     * @returns {HTMLElement}
     */
    function createCacheCard(cache, index) {
        const card = Stowling.element("article", "editor-card");
        const heading = Stowling.element("div", "editor-card-heading");
        heading.append(
            Stowling.element(
                "strong",
                "",
                String(cache.name || cache.id || "未命名缓存")
            ),
            deleteResourceButton("删除缓存", () => removeCache(index))
        );
        const fields = Stowling.element("div", "editor-fields");
        fields.append(
            field(
                "缓存 ID",
                textInput(
                    `cache-id-${index}`,
                    "缓存 ID",
                    cache.id,
                    Boolean(cache._originalId),
                    (value) => {
                        cache.id = value;
                        markPickupDirty();
                    }
                )
            ),
            field(
                "显示名称",
                textInput(
                    `cache-name-${index}`,
                    "显示名称",
                    cache.name,
                    false,
                    (value) => {
                        cache.name = value;
                        markPickupDirty();
                    }
                )
            ),
            field(
                "容器命名坐标",
                coordinateSelect(
                    `cache-coordinate-${index}`,
                    cache.coordinateId,
                    (value) => {
                        cache.coordinateId = value;
                        const point = coordinateFor(value);
                        cache.dimension = point?.dimension || cache.dimension;
                        markPickupDirty();
                    }
                )
            ),
            field(
                "容器类型",
                selectInput(
                    `cache-type-${index}`,
                    "容器类型",
                    CONTAINER_TYPES.map((type) => [
                        type,
                        Stowling.containerTypeLabel(type)
                    ]),
                    cache.type || "CHEST",
                    (value) => {
                        cache.type = value;
                        markPickupDirty();
                    }
                )
            )
        );
        card.append(heading, fields);
        return card;
    }

    /**
     * @param {string} label
     * @param {() => void} action
     * @returns {HTMLButtonElement}
     */
    function deleteResourceButton(label, action) {
        const button = Stowling.element("button", "ghost compact danger-action", label);
        button.type = "button";
        button.addEventListener("click", action);
        return button;
    }

    /**
     * @param {string} value
     * @returns {object | null}
     */
    function coordinateFor(value) {
        const id = String(value || "");
        return (state.coordinateSnapshot?.coordinatePoints || []).find((point) => {
            return String(point.id || "") === id;
        }) || null;
    }

    /**
     * @returns {void}
     */
    function addRegion() {
        if (!state.pickupDraft) {
            return;
        }
        const id = nextResourceId("region", state.pickupDraft.pickupRegions);
        const firstPoint = state.coordinateSnapshot?.coordinatePoints?.[0];
        state.pickupDraft.pickupRegions.push({
            id,
            name: "新拾取区域",
            dimension: firstPoint?.dimension || DIMENSIONS[0],
            standbyCoordinateId: firstPoint?.id || "",
            shape: {
                type: "SPHERE",
                centerCoordinateId: firstPoint?.id || "",
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
        if (!state.pickupDraft) {
            return;
        }
        const id = nextResourceId("cache", state.pickupDraft.cacheContainers);
        const firstPoint = state.coordinateSnapshot?.coordinatePoints?.[0];
        state.pickupDraft.cacheContainers.push({
            id,
            name: "新缓存容器",
            coordinateId: firstPoint?.id || "",
            dimension: firstPoint?.dimension || DIMENSIONS[0],
            type: "CHEST",
            _originalId: ""
        });
        markPickupDirty(true);
    }

    /**
     * @param {string} prefix
     * @param {Array<object>} resources
     * @returns {string}
     */
    function nextResourceId(prefix, resources) {
        const used = new Set(resources.map((resource) => String(resource.id || "")));
        let index = 1;
        while (used.has(`${prefix}-${index}`)) {
            index += 1;
        }
        return `${prefix}-${index}`;
    }

    /**
     * @param {number} index
     * @returns {void}
     */
    function removeRegion(index) {
        const region = state.pickupDraft.pickupRegions[index];
        if (!window.confirm(`删除拾取区域“${region.name || region.id}”？`)) {
            return;
        }
        state.pickupDraft.pickupRegions.splice(index, 1);
        markPickupDirty(true);
    }

    /**
     * @param {number} index
     * @returns {void}
     */
    function removeCache(index) {
        const cache = state.pickupDraft.cacheContainers[index];
        if (!window.confirm(`删除缓存容器“${cache.name || cache.id}”？`)) {
            return;
        }
        state.pickupDraft.cacheContainers.splice(index, 1);
        markPickupDirty(true);
    }

    /**
     * @param {boolean} rerender
     * @returns {void}
     */
    function markPickupDirty(rerender = false) {
        state.pickupDirty = true;
        if (rerender) {
            renderPickupResources();
            return;
        }
        updatePickupState();
    }

    /**
     * @returns {void}
     */
    function updatePickupState() {
        const errors = validatePickupResources();
        state.pickupErrors = errors;
        elements.pickupSave.disabled = !state.pickupDirty || errors.length > 0;
        elements.pickupDiscard.disabled = !state.pickupDirty;
        elements.pickupStatus.classList.toggle("dirty", state.pickupDirty);
        if (state.pickupDirty && errors.length > 0) {
            elements.pickupStatus.textContent =
                `发现 ${errors.length} 个拾取资源问题，保存已暂停`;
        } else if (state.pickupDirty) {
            elements.pickupStatus.textContent = "有未保存的拾取资源修改";
        } else {
            elements.pickupStatus.textContent = "拾取资源已与配置文件同步";
        }
        showPickupErrors(state.pickupDirty ? state.pickupErrors : []);
    }

    /**
     * @returns {Array<string>}
     */
    function validatePickupResources() {
        const errors = [];
        if (!state.pickupDraft) {
            return ["尚未读取拾取资源"];
        }
        const ids = new Set();
        state.pickupDraft.pickupRegions.forEach((region, index) => {
            const label = `拾取区域 ${index + 1}“${region.name || region.id || "未命名"}”`;
            validateResourceIdentity(errors, ids, region, label);
            const standby = coordinateFor(region.standbyCoordinateId);
            if (!standby) {
                errors.push(`${label}必须选择一个已保存的待机命名坐标；请重新选择`);
            } else if (standby.dimension !== region.dimension) {
                errors.push(`${label}的维度必须与待机坐标一致；请重新选择`);
            }
            if (region.shape?.type === "CUBOID") {
                validateCuboid(region, label, errors);
            } else {
                const center = coordinateFor(region.shape?.centerCoordinateId);
                if (!center) {
                    errors.push(`${label}必须选择一个球心命名坐标；请重新选择`);
                } else if (center.dimension !== region.dimension) {
                    errors.push(`${label}的球心维度必须与区域维度一致`);
                }
                const radius = parseInteger(region.shape?.radius);
                if (radius === null || radius < 1 || radius > 128) {
                    errors.push(`${label}的半径必须为 1-128 的整数`);
                }
            }
        });
        const cacheIds = new Set();
        state.pickupDraft.cacheContainers.forEach((cache, index) => {
            const label = `缓存容器 ${index + 1}“${cache.name || cache.id || "未命名"}”`;
            validateResourceIdentity(errors, cacheIds, cache, label);
            const point = coordinateFor(cache.coordinateId);
            if (!point) {
                errors.push(`${label}必须选择一个已保存的容器命名坐标；请重新选择`);
            } else if (point.dimension !== cache.dimension) {
                errors.push(`${label}的维度必须与容器命名坐标一致`);
            }
        });
        return [...new Set(errors)];
    }

    /**
     * @param {Array<string>} errors
     * @param {Set<string>} ids
     * @param {object} resource
     * @param {string} label
     * @returns {void}
     */
    function validateResourceIdentity(errors, ids, resource, label) {
        const id = String(resource.id || "").trim();
        if (!id) {
            errors.push(`${label}的资源 ID 不能为空`);
        } else if (!/^[a-z0-9][a-z0-9._-]{0,63}$/.test(id)) {
            errors.push(`${label}的资源 ID 格式无效；请使用小写字母、数字、点、`
                + "下划线或连字符");
        } else if (ids.has(id)) {
            errors.push(`${label}使用了重复资源 ID“${id}”`);
        }
        if (id) {
            ids.add(id);
        }
        if (!String(resource.name || "").trim()) {
            errors.push(`${label}的显示名称不能为空`);
        }
    }

    /**
     * @param {object} region
     * @param {string} label
     * @param {Array<string>} errors
     * @returns {void}
     */
    function validateCuboid(region, label, errors) {
        const first = coordinateFor(region.shape?.firstCornerCoordinateId);
        const second = coordinateFor(region.shape?.secondCornerCoordinateId);
        if (!first || !second) {
            errors.push(`${label}必须选择两个已保存的角点命名坐标`);
            return;
        }
        if (first.dimension !== region.dimension
                || second.dimension !== region.dimension) {
            errors.push(`${label}的角点维度必须与区域维度一致`);
            return;
        }
        for (const axis of ["x", "y", "z"]) {
            if (Number(first.position?.[axis]) > Number(second.position?.[axis])) {
                errors.push(`${label}的第一角点必须位于第二角点的负方向`);
                return;
            }
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
        const list = Stowling.element("ul");
        for (const error of errors) {
            list.append(Stowling.element("li", "", error));
        }
        elements.pickupErrors.append(
            Stowling.element("strong", "", "请修正以下问题："),
            list
        );
    }

    /**
     * @returns {Promise<void>}
     */
    async function savePickupResources() {
        const errors = validatePickupResources();
        if (errors.length > 0) {
            state.pickupDirty = true;
            updatePickupState();
            Stowling.showToast("拾取资源仍有错误，暂未保存", true);
            return;
        }
        let body;
        try {
            body = pickupRequest();
        } catch (error) {
            showPickupErrors([error instanceof Error ? error.message : "资源无法保存"]);
            return;
        }
        elements.pickupSave.disabled = true;
        elements.pickupDiscard.disabled = true;
        elements.pickupStatus.textContent = "正在保存拾取资源…";
        try {
            const saved = await Stowling.request("/v1/config/task-resources", {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(body)
            });
            applyPickupSnapshot(saved);
            await notifyChanged();
            Stowling.showToast("拾取资源已保存，旧配置已生成备份");
        } catch (error) {
            const conflict = error instanceof Stowling.ApiError && error.status === 409;
            showPickupErrors([
                conflict
                    ? "配置版本冲突或仍有任务引用；请刷新后重试"
                    : error instanceof Error
                        ? error.message
                        : "保存拾取资源失败"
            ]);
            elements.pickupStatus.textContent = conflict
                ? "拾取资源配置已变化，草稿已保留"
                : "拾取资源保存失败，草稿仍在";
            Stowling.handleError(error, "保存拾取资源失败");
        } finally {
            elements.pickupDiscard.disabled = false;
            updatePickupState();
        }
    }

    /**
     * @returns {object}
     */
    function pickupRequest() {
        const points = new Map(
            (state.coordinateSnapshot?.coordinatePoints || []).map((point) => {
                return [String(point.id || ""), point];
            })
        );
        const requirePoint = (value, label) => {
            const point = points.get(String(value || ""));
            if (!point) {
                throw new Error(`${label}引用的命名坐标不存在`);
            }
            return Stowling.clone(point.position);
        };
        const pickupRegions = state.pickupDraft.pickupRegions.map((region) => {
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
                    radius: Number(region.shape?.radius)
                };
            }
            return {
                id: String(region.id || "").trim(),
                name: String(region.name || "").trim(),
                dimension: region.dimension,
                standbyCoordinateId: region.standbyCoordinateId,
                standbyPosition: requirePoint(
                    region.standbyCoordinateId,
                    `区域 ${region.id} 待机点`
                ),
                shape
            };
        });
        const cacheContainers = state.pickupDraft.cacheContainers.map((cache) => ({
            id: String(cache.id || "").trim(),
            name: String(cache.name || "").trim(),
            coordinateId: cache.coordinateId,
            dimension: cache.dimension,
            position: requirePoint(cache.coordinateId, `缓存 ${cache.id}`),
            type: cache.type
        }));
        return {
            revision: state.pickupSnapshot.revision,
            pickupRegions,
            cacheContainers
        };
    }

    /**
     * @returns {void}
     */
    function renderBaseCoordinates() {
        elements.baseList.replaceChildren();
        if (state.baseDraft.length === 0) {
            elements.baseList.append(Stowling.emptyState("配置中没有仓库基础坐标"));
        }
        const groups = new Map();
        state.baseDraft.forEach((coordinate, index) => {
            const kind = String(coordinate.kind || "OTHER");
            const values = groups.get(kind) || [];
            values.push({ coordinate, index });
            groups.set(kind, values);
        });
        for (const [kind, values] of groups) {
            const group = Stowling.element("section", "coordinate-group");
            group.append(Stowling.element("h3", "", baseCoordinateGroupLabel(kind)));
            const list = Stowling.element("div", "coordinate-list");
            for (const value of values) {
                list.append(createBaseCoordinateRow(value.coordinate, value.index));
            }
            group.append(list);
            elements.baseList.append(group);
        }
        updateBaseState();
    }

    /**
     * @param {string} kind
     * @returns {string}
     */
    function baseCoordinateGroupLabel(kind) {
        const labels = new Map([
            ["CONTAINER", "仓库与溢出容器"],
            ["PACKING_STATION_SHULKER_BOX", "装盒工位"]
        ]);
        return labels.get(kind) || kind;
    }

    /**
     * @param {object} coordinate
     * @param {number} index
     * @returns {HTMLElement}
     */
    function createBaseCoordinateRow(coordinate, index) {
        const row = Stowling.element("div", "coordinate-row");
        row.dataset.index = String(index);
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
            row.append(field(
                axis.toUpperCase(),
                numberInput(
                    `base-coordinate-${axis}-${index}`,
                    axis.toUpperCase(),
                    coordinate.position?.[axis],
                    (value) => {
                        coordinate.position ||= { x: 0, y: 64, z: 0 };
                        coordinate.position[axis] = value;
                        markBaseDirty();
                    }
                )
            ));
        }
        return row;
    }

    /**
     * @returns {void}
     */
    function markBaseDirty() {
        state.baseDirty = true;
        updateBaseState();
    }

    /**
     * @returns {void}
     */
    function updateBaseState() {
        const errors = validateBaseCoordinates();
        state.baseErrors = errors;
        elements.baseSave.disabled = !state.baseDirty || errors.length > 0;
        elements.baseDiscard.disabled = !state.baseDirty;
        elements.baseStatus.classList.toggle("dirty", state.baseDirty);
        showBaseErrors(state.baseDirty ? errors : []);
        if (state.baseDirty && errors.length > 0) {
            elements.baseStatus.textContent =
                `发现 ${errors.length} 个基础坐标问题，保存已暂停`;
        } else if (state.baseDirty) {
            elements.baseStatus.textContent = "有未保存的仓库基础坐标修改";
        } else {
            elements.baseStatus.textContent = "仓库基础坐标已与配置文件同步";
        }
    }

    /**
     * @returns {Array<string>}
     */
    function validateBaseCoordinates() {
        const errors = [];
        for (const coordinate of state.baseDraft) {
            for (const axis of ["x", "y", "z"]) {
                if (parseInteger(coordinate.position?.[axis]) === null) {
                    errors.push(
                        `${coordinate.ownerId || coordinate.key} 的 ${axis.toUpperCase()}`
                            + "坐标必须是 32 位整数"
                    );
                }
            }
        }
        return errors;
    }

    /**
     * @param {Array<string>} errors
     * @returns {void}
     */
    function showBaseErrors(errors) {
        elements.baseErrors.replaceChildren();
        if (errors.length === 0) {
            return;
        }
        const list = Stowling.element("ul");
        for (const error of errors) {
            list.append(Stowling.element("li", "", error));
        }
        elements.baseErrors.append(
            Stowling.element("strong", "", "请修正以下问题："),
            list
        );
    }

    /**
     * @returns {Promise<void>}
     */
    async function saveBaseCoordinates() {
        const errors = validateBaseCoordinates();
        if (errors.length > 0) {
            state.baseDirty = true;
            updateBaseState();
            return;
        }
        elements.baseSave.disabled = true;
        elements.baseDiscard.disabled = true;
        elements.baseStatus.textContent = "正在保存仓库基础坐标…";
        try {
            const saved = await Stowling.request("/v1/config/coordinates", {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    revision: state.baseSnapshot.revision,
                    coordinates: state.baseDraft.map((coordinate) => ({
                        key: coordinate.key,
                        x: Number(coordinate.position?.x),
                        y: Number(coordinate.position?.y),
                        z: Number(coordinate.position?.z)
                    }))
                })
            });
            applyBaseSnapshot(saved);
            await notifyChanged();
            Stowling.showToast("仓库基础坐标已保存，旧配置已生成备份");
        } catch (error) {
            showBaseErrors([error instanceof Error ? error.message : "保存坐标失败"]);
            elements.baseStatus.textContent = "仓库基础坐标保存失败，草稿仍在";
            Stowling.handleError(error, "保存仓库基础坐标失败");
        } finally {
            elements.baseDiscard.disabled = false;
            updateBaseState();
        }
    }

    /**
     * @returns {Promise<void>}
     */
    async function discardPickupResources() {
        elements.pickupDiscard.disabled = true;
        try {
            const snapshot = await Stowling.request("/v1/config/task-resources");
            applyPickupSnapshot(snapshot);
            Stowling.showToast("已重新读取拾取资源");
        } catch (error) {
            Stowling.handleError(error, "读取拾取资源失败");
        } finally {
            elements.pickupDiscard.disabled = !state.pickupDirty;
        }
    }

    /**
     * @returns {Promise<void>}
     */
    async function discardBaseCoordinates() {
        elements.baseDiscard.disabled = true;
        try {
            const snapshot = await Stowling.request("/v1/config/coordinates");
            applyBaseSnapshot(snapshot);
            Stowling.showToast("已重新读取仓库基础坐标");
        } catch (error) {
            Stowling.handleError(error, "读取仓库基础坐标失败");
        } finally {
            elements.baseDiscard.disabled = !state.baseDirty;
        }
    }

    /**
     * @returns {Promise<void>}
     */
    async function notifyChanged() {
        for (const handler of state.changedHandlers) {
            await handler();
        }
    }

    /**
     * @param {() => void | Promise<void>} handler
     * @returns {void}
     */
    function onChanged(handler) {
        state.changedHandlers.add(handler);
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
     * @param {string} id
     * @param {string} label
     * @param {unknown} value
     * @param {boolean} disabled
     * @param {(value: string) => void} onChange
     * @returns {HTMLInputElement}
     */
    function textInput(id, label, value, disabled, onChange) {
        const input = document.createElement("input");
        input.id = id;
        input.type = "text";
        input.value = String(value ?? "");
        input.autocomplete = "off";
        input.disabled = disabled;
        input.setAttribute("aria-label", label);
        input.addEventListener("input", () => onChange(input.value));
        return input;
    }

    /**
     * @param {string} id
     * @param {string} label
     * @param {unknown} value
     * @param {(value: number | string) => void} onChange
     * @returns {HTMLInputElement}
     */
    function numberInput(id, label, value, onChange) {
        const input = document.createElement("input");
        input.id = id;
        input.type = "number";
        input.step = "1";
        input.value = String(value ?? 0);
        input.setAttribute("aria-label", label);
        input.addEventListener("input", () => {
            const parsed = parseInteger(input.value);
            onChange(parsed === null ? input.value : parsed);
        });
        return input;
    }

    /**
     * @param {string} id
     * @param {string} label
     * @param {Array<Array<string>>} options
     * @param {unknown} value
     * @param {(value: string) => void} onChange
     * @returns {HTMLSelectElement}
     */
    function selectInput(id, label, options, value, onChange) {
        const select = document.createElement("select");
        select.id = id;
        select.setAttribute("aria-label", label);
        for (const [optionValue, optionLabel] of options) {
            select.append(new Option(optionLabel, optionValue));
        }
        select.value = String(value ?? "");
        select.addEventListener("change", () => onChange(select.value));
        return select;
    }

    /**
     * @param {string} id
     * @param {unknown} value
     * @param {(value: string) => void} onChange
     * @returns {HTMLSelectElement}
     */
    function dimensionSelect(id, value, onChange) {
        const values = [...DIMENSIONS];
        const current = String(value || DIMENSIONS[0]);
        if (!values.includes(current)) {
            values.push(current);
        }
        return selectInput(
            id,
            "维度",
            values.map((dimension) => [
                dimension,
                Stowling.dimensionLabel(dimension)
            ]),
            current,
            onChange
        );
    }

    /**
     * @param {string} id
     * @param {unknown} value
     * @param {(value: string) => void} onChange
     * @returns {HTMLSelectElement}
     */
    function coordinateSelect(id, value, onChange) {
        const options = [["", "请选择命名坐标"]];
        for (const point of state.coordinateSnapshot?.coordinatePoints || []) {
            options.push([
                String(point.id || ""),
                `${point.name || point.id} · ${Stowling.dimensionLabel(point.dimension)}`
            ]);
        }
        return selectInput(id, "命名坐标", options, value, onChange);
    }

    /**
     * @param {unknown} value
     * @returns {number | null}
     */
    function parseInteger(value) {
        const text = String(value ?? "").trim();
        if (!/^-?(0|[1-9][0-9]*)$/.test(text)) {
            return null;
        }
        const result = Number(text);
        if (!Number.isSafeInteger(result)
                || result < -2147483648
                || result > 2147483647) {
            return null;
        }
        return result;
    }

    elements.addRegion.addEventListener("click", addRegion);
    elements.addCache.addEventListener("click", addCache);
    elements.pickupSave.addEventListener("click", () => {
        void savePickupResources();
    });
    elements.pickupDiscard.addEventListener("click", () => {
        void discardPickupResources();
    });
    elements.baseSave.addEventListener("click", () => {
        void saveBaseCoordinates();
    });
    elements.baseDiscard.addEventListener("click", () => {
        void discardBaseCoordinates();
    });

    window.StowlingResources = Object.freeze({
        getSnapshot,
        load,
        onChanged
    });
})();
