(() => {
    "use strict";

    const TOKEN_KEY = "stowling.control.token";
    const ACTIVE_TASK_STATES = new Set(["PENDING", "RUNNING", "WAITING", "PAUSED"]);
    const TASK_STATUS_LABELS = Object.freeze({
        PENDING: "等待中",
        RUNNING: "运行中",
        WAITING: "等待条件",
        PAUSED: "已暂停",
        SUCCEEDED: "已完成",
        FAILED: "失败",
        CANCELLED: "已取消"
    });
    const AUTOMATION_LABELS = Object.freeze({
        PICKING_UP: "拾取中",
        CACHING: "缓存中",
        IDLE_WAITING: "空闲等待",
        SORTING: "分类中",
        PACKING: "装盒中",
        COMPLETED: "已完成",
        FAILED: "失败"
    });

    const elements = {
        authForm: document.querySelector("#auth-form"),
        tokenInput: document.querySelector("#token-input"),
        connectButton: document.querySelector("#connect-button"),
        clearTokenButton: document.querySelector("#clear-token-button"),
        connectionDot: document.querySelector("#connection-dot"),
        connectionLabel: document.querySelector("#connection-label"),
        workspace: document.querySelector("#workspace"),
        refreshButton: document.querySelector("#refresh-button"),
        pauseButton: document.querySelector("#pause-button"),
        resumeButton: document.querySelector("#resume-button"),
        reloadButton: document.querySelector("#reload-button"),
        pickupForm: document.querySelector("#pickup-form"),
        siteSelect: document.querySelector("#site-select"),
        pickupSubmit: document.querySelector("#pickup-submit"),
        pickupSitesMessage: document.querySelector("#pickup-sites-message"),
        coordinatesForm: document.querySelector("#coordinates-form"),
        coordinatesList: document.querySelector("#coordinates-list"),
        coordinatesSave: document.querySelector("#coordinates-save"),
        coordinatesDiscard: document.querySelector("#coordinates-discard"),
        coordinatesStatus: document.querySelector("#coordinates-status"),
        coordinatesRevision: document.querySelector("#coordinates-revision"),
        tasksList: document.querySelector("#tasks-list"),
        refreshTasksButton: document.querySelector("#refresh-tasks-button"),
        containersList: document.querySelector("#containers-list"),
        refreshContainersButton: document.querySelector("#refresh-containers-button"),
        auditForm: document.querySelector("#audit-form"),
        inventoryList: document.querySelector("#inventory-list"),
        refreshInventoryButton: document.querySelector("#refresh-inventory-button"),
        statusState: document.querySelector("#status-state"),
        statusPaused: document.querySelector("#status-paused"),
        statusObserved: document.querySelector("#status-observed"),
        toastRegion: document.querySelector("#toast-region")
    };

    let authToken = "";
    let refreshInFlight = false;
    let coordinateSnapshot = null;
    let coordinatesDirty = false;

    /**
     * @param {string} path
     * @param {RequestInit} options
     * @param {string} token
     * @returns {Promise<unknown>}
     */
    async function requestJson(path, options = {}, token = authToken) {
        if (!token) {
            throw new ApiRequestError("请先验证访问令牌", 401);
        }
        const controller = new AbortController();
        const timeout = window.setTimeout(() => controller.abort(), 10000);
        const headers = new Headers(options.headers || {});
        headers.set("Accept", "application/json");
        headers.set("Authorization", `Bearer ${token}`);
        try {
            const response = await fetch(path, {
                ...options,
                headers,
                signal: controller.signal,
                cache: "no-store"
            });
            const text = await response.text();
            let payload = null;
            if (text) {
                try {
                    payload = JSON.parse(text);
                } catch (error) {
                    if (response.ok) {
                        throw new ApiRequestError("服务器返回了无法读取的数据", response.status);
                    }
                }
            }
            if (!response.ok) {
                const serverCode = payload?.error?.code || "";
                const serverMessage = payload?.error?.message;
                throw new ApiRequestError(
                    friendlyApiMessage(
                        serverCode,
                        serverMessage || statusMessage(response.status)
                    ),
                    response.status,
                    serverCode
                );
            }
            return payload;
        } catch (error) {
            if (error instanceof ApiRequestError) {
                throw error;
            }
            if (error?.name === "AbortError") {
                throw new ApiRequestError("请求超时，请确认游戏仍在运行", 0);
            }
            throw new ApiRequestError("无法连接 Stowling，请确认本地服务正在运行", 0);
        } finally {
            window.clearTimeout(timeout);
        }
    }

    /**
     * @param {number} status
     * @returns {string}
     */
    function statusMessage(status) {
        if (status === 401) {
            return "令牌无效或已失效，请重新输入";
        }
        if (status === 403) {
            return "当前请求没有访问权限";
        }
        if (status === 404) {
            return "请求的资源不存在";
        }
        if (status === 405) {
            return "服务器不接受此操作";
        }
        if (status >= 500) {
            return "Stowling 暂时无法完成该操作";
        }
        return "操作失败，请稍后重试";
    }

    /**
     * @param {string} code
     * @param {string} fallback
     * @returns {string}
     */
    function friendlyApiMessage(code, fallback) {
        const messages = {
            pickup_site_not_found: "配置中找不到这个拾取点，请刷新或重载配置后重新选择",
            task_not_found: "该任务已不存在，请刷新任务列表",
            container_not_found: "配置中找不到所选容器，请刷新或重载配置后重新选择",
            config_revision_conflict: "配置已被外部修改，请重新读取坐标后再保存",
            active_task_conflict: "拾取任务正在运行，请先取消任务或等待任务完成",
            invalid_config_coordinates: "坐标数据不完整，请重新读取后再修改"
        };
        return messages[code] || fallback;
    }

    class ApiRequestError extends Error {
        /**
         * @param {string} message
         * @param {number} status
         * @param {string} code
         */
        constructor(message, status, code = "") {
            super(message);
            this.name = "ApiRequestError";
            this.status = status;
            this.code = code;
        }
    }

    /**
     * @param {string} candidate
     * @param {boolean} restored
     * @returns {Promise<void>}
     */
    async function connectToken(candidate, restored = false) {
        const token = candidate.trim();
        if (!token) {
            showToast("请输入访问令牌", true);
            elements.tokenInput.focus();
            return;
        }
        setConnectBusy(true);
        try {
            await requestJson("/v1/status", { method: "GET" }, token);
            authToken = token;
            sessionStorage.setItem(TOKEN_KEY, token);
            elements.tokenInput.value = "";
            setConnected(true);
            await refreshAll();
            if (!restored) {
                showToast("连接成功");
            }
        } catch (error) {
            setConnected(false);
            handleError(error, "验证失败");
            elements.tokenInput.focus();
        } finally {
            setConnectBusy(false);
        }
    }

    /**
     * @param {boolean} busy
     * @returns {void}
     */
    function setConnectBusy(busy) {
        elements.connectButton.disabled = busy;
        elements.connectButton.textContent = busy ? "正在验证…" : "验证并连接";
    }

    /**
     * @param {boolean} connected
     * @returns {void}
     */
    function setConnected(connected) {
        elements.connectionLabel.textContent = connected ? "已连接" : "未连接";
        elements.connectionDot.classList.toggle("online", connected);
        elements.connectionDot.classList.toggle("offline", !connected);
        elements.workspace.setAttribute("aria-hidden", connected ? "false" : "true");
    }

    /**
     * @param {boolean} clearInput
     * @returns {void}
     */
    function clearAuthentication(clearInput = true) {
        authToken = "";
        sessionStorage.removeItem(TOKEN_KEY);
        if (clearInput) {
            elements.tokenInput.value = "";
        }
        setConnected(false);
    }

    /**
     * @param {unknown} error
     * @param {string} context
     * @returns {void}
     */
    function handleError(error, context) {
        const message = error instanceof Error ? error.message : "操作失败";
        if (error instanceof ApiRequestError && error.status === 401) {
            clearAuthentication(false);
            showToast("登录已失效，请重新输入访问令牌", true);
            elements.tokenInput.focus();
            return;
        }
        showToast(`${context}：${message}`, true);
    }

    /**
     * @param {string} text
     * @param {boolean} error
     * @returns {void}
     */
    function showToast(text, error = false) {
        const toast = document.createElement("div");
        toast.className = error ? "toast error" : "toast";
        toast.textContent = text;
        elements.toastRegion.append(toast);
        window.setTimeout(() => toast.remove(), 5000);
    }

    /**
     * @returns {Promise<void>}
     */
    async function refreshAll() {
        if (!authToken || refreshInFlight) {
            return;
        }
        refreshInFlight = true;
        try {
            const results = await Promise.allSettled([
                refreshStatus(),
                refreshTasks(),
                refreshPickupSites(),
                refreshCoordinates(false),
                refreshContainers(),
                refreshInventory()
            ]);
            const failed = results.find((result) => result.status === "rejected");
            if (failed) {
                handleError(failed.reason, "自动刷新失败");
            }
        } finally {
            refreshInFlight = false;
        }
    }

    /**
     * @returns {Promise<void>}
     */
    async function refreshStatus() {
        const status = await requestJson("/v1/status");
        elements.statusState.textContent = status?.state || "未知";
        elements.statusPaused.textContent = status?.paused ? "是" : "否";
        elements.statusObserved.textContent = formatDate(status?.observedAt);
    }

    /**
     * @returns {Promise<void>}
     */
    async function refreshTasks() {
        const tasks = await requestJson("/v1/tasks");
        renderTasks(Array.isArray(tasks) ? tasks : []);
    }

    /**
     * @returns {Promise<void>}
     */
    async function refreshPickupSites() {
        elements.siteSelect.disabled = true;
        elements.pickupSubmit.disabled = true;
        elements.pickupSitesMessage.classList.remove("error");
        elements.pickupSitesMessage.textContent = "正在读取配置中的拾取点…";
        try {
            const pickupSites = await requestJson("/v1/pickup-sites");
            renderPickupSites(Array.isArray(pickupSites) ? pickupSites : []);
        } catch (error) {
            renderPickupSites([], "无法读取拾取点，请稍后重试");
            throw error;
        }
    }

    /**
     * @param {Array<object>} pickupSites
     * @param {string} errorMessage
     * @returns {void}
     */
    function renderPickupSites(pickupSites, errorMessage = "") {
        const selectedId = elements.siteSelect.value;
        elements.siteSelect.replaceChildren();
        if (pickupSites.length === 0) {
            const option = document.createElement("option");
            option.value = "";
            option.textContent = "没有可用拾取点";
            elements.siteSelect.append(option);
            elements.siteSelect.disabled = true;
            elements.pickupSubmit.disabled = true;
            elements.pickupSitesMessage.classList.toggle("error", Boolean(errorMessage));
            elements.pickupSitesMessage.textContent = errorMessage
                || "配置中没有 pickupSites，请先添加拾取点并重载配置";
            return;
        }
        for (const site of pickupSites) {
            const center = site.center || {};
            const option = document.createElement("option");
            option.value = String(site.id || "");
            option.textContent = `${site.id} · ${center.x}, ${center.y}, ${center.z}`;
            elements.siteSelect.append(option);
        }
        if (pickupSites.some((site) => site.id === selectedId)) {
            elements.siteSelect.value = selectedId;
        }
        elements.siteSelect.disabled = false;
        elements.pickupSubmit.disabled = false;
        elements.pickupSitesMessage.classList.remove("error");
        elements.pickupSitesMessage.textContent = "仅可选择当前配置中的拾取点 ID";
    }

    /**
     * @param {boolean} discardChanges
     * @returns {Promise<void>}
     */
    async function refreshCoordinates(discardChanges) {
        if (coordinatesDirty && !discardChanges) {
            return;
        }
        elements.coordinatesStatus.textContent = "正在读取坐标配置…";
        try {
            const snapshot = await requestJson("/v1/config/coordinates");
            renderCoordinates(snapshot);
        } catch (error) {
            elements.coordinatesStatus.textContent = "坐标配置读取失败";
            throw error;
        }
    }

    /**
     * @param {object} snapshot
     * @returns {void}
     */
    function renderCoordinates(snapshot) {
        const coordinates = Array.isArray(snapshot?.coordinates)
            ? snapshot.coordinates
            : [];
        coordinateSnapshot = {
            revision: String(snapshot?.revision || ""),
            coordinates
        };
        coordinatesDirty = false;
        elements.coordinatesList.replaceChildren();
        const groups = [
            ["PICKUP_SITE_CENTER", "拾取中心"],
            ["CONTAINER", "缓存与仓库容器"],
            ["PACKING_STATION_SHULKER_BOX", "装盒工位"]
        ];
        for (const [kind, title] of groups) {
            const entries = coordinates.filter((coordinate) => coordinate.kind === kind);
            if (entries.length > 0) {
                elements.coordinatesList.append(createCoordinateGroup(title, entries));
            }
        }
        if (coordinates.length === 0) {
            elements.coordinatesList.append(emptyState("配置中没有可编辑坐标"));
        }
        elements.coordinatesSave.disabled = true;
        elements.coordinatesStatus.classList.remove("dirty");
        elements.coordinatesStatus.textContent = "坐标已与配置文件同步";
        const revision = coordinateSnapshot.revision;
        elements.coordinatesRevision.textContent = `revision: ${revision.slice(0, 12) || "--"}`;
        elements.coordinatesRevision.title = revision;
    }

    /**
     * @param {string} title
     * @param {Array<object>} coordinates
     * @returns {HTMLElement}
     */
    function createCoordinateGroup(title, coordinates) {
        const group = document.createElement("section");
        group.className = "coordinate-group";
        const heading = document.createElement("h3");
        heading.textContent = title;
        group.append(heading);
        const list = document.createElement("div");
        list.className = "coordinate-list";
        coordinates.forEach((coordinate, index) => {
            list.append(createCoordinateRow(coordinate, `${coordinate.kind}-${index}`));
        });
        group.append(list);
        return group;
    }

    /**
     * @param {object} coordinate
     * @param {string} inputPrefix
     * @returns {HTMLElement}
     */
    function createCoordinateRow(coordinate, inputPrefix) {
        const row = document.createElement("div");
        row.className = "coordinate-row";
        row.dataset.key = String(coordinate.key || "");
        const identity = document.createElement("div");
        identity.className = "coordinate-identity";
        const name = document.createElement("strong");
        name.textContent = `${coordinateKindLabel(coordinate.kind)} · ${coordinate.ownerId}`;
        const details = document.createElement("span");
        details.textContent = `${coordinate.dimension} · ${coordinate.key}`;
        identity.append(name, details);
        row.append(identity);
        for (const axis of ["x", "y", "z"]) {
            const field = document.createElement("div");
            field.className = "coordinate-axis";
            const label = document.createElement("label");
            const input = document.createElement("input");
            input.id = `${inputPrefix}-${axis}`;
            input.type = "number";
            input.step = "1";
            input.required = true;
            input.dataset.axis = axis;
            input.value = String(coordinate.position?.[axis] ?? 0);
            input.addEventListener("input", updateCoordinatesDirtyState);
            label.htmlFor = input.id;
            label.textContent = axis.toUpperCase();
            field.append(label, input);
            row.append(field);
        }
        return row;
    }

    /**
     * @param {string} kind
     * @returns {string}
     */
    function coordinateKindLabel(kind) {
        if (kind === "PICKUP_SITE_CENTER") {
            return "拾取中心";
        }
        if (kind === "PACKING_STATION_SHULKER_BOX") {
            return "潜影盒位置";
        }
        return "容器";
    }

    /**
     * @returns {void}
     */
    function updateCoordinatesDirtyState() {
        if (!coordinateSnapshot) {
            return;
        }
        let invalid = false;
        let changed = false;
        const original = new Map(
            coordinateSnapshot.coordinates.map((coordinate) => [coordinate.key, coordinate])
        );
        for (const row of elements.coordinatesList.querySelectorAll(".coordinate-row")) {
            const coordinate = original.get(row.dataset.key);
            for (const input of row.querySelectorAll("input[data-axis]")) {
                const value = parseCoordinateInput(input.value);
                invalid ||= value === null;
                changed ||= value !== coordinate?.position?.[input.dataset.axis];
            }
        }
        coordinatesDirty = changed;
        elements.coordinatesSave.disabled = !changed || invalid;
        elements.coordinatesStatus.classList.toggle("dirty", changed);
        if (invalid) {
            elements.coordinatesStatus.textContent = "坐标必须是 32 位整数";
        } else if (changed) {
            elements.coordinatesStatus.textContent = "有未保存的坐标修改";
        } else {
            elements.coordinatesStatus.textContent = "坐标已与配置文件同步";
        }
    }

    /**
     * @param {string} value
     * @returns {number|null}
     */
    function parseCoordinateInput(value) {
        if (!/^-?(0|[1-9][0-9]*)$/.test(value.trim())) {
            return null;
        }
        const number = Number(value);
        if (!Number.isInteger(number) || number < -2147483648 || number > 2147483647) {
            return null;
        }
        return number;
    }

    /**
     * @returns {Array<object>}
     */
    function collectCoordinateUpdates() {
        const updates = [];
        for (const row of elements.coordinatesList.querySelectorAll(".coordinate-row")) {
            const values = {};
            for (const input of row.querySelectorAll("input[data-axis]")) {
                const value = parseCoordinateInput(input.value);
                if (value === null) {
                    throw new Error("坐标必须是 32 位整数");
                }
                values[input.dataset.axis] = value;
            }
            updates.push({ key: row.dataset.key, x: values.x, y: values.y, z: values.z });
        }
        return updates;
    }

    /**
     * @param {Array<object>} tasks
     * @returns {void}
     */
    function renderTasks(tasks) {
        elements.tasksList.replaceChildren();
        if (tasks.length === 0) {
            elements.tasksList.append(emptyState("当前没有任务"));
            return;
        }
        const sortedTasks = [...tasks].sort((left, right) => {
            return String(right.updatedAt).localeCompare(String(left.updatedAt));
        });
        for (const task of sortedTasks) {
            const card = document.createElement("article");
            card.className = "task-card";

            const content = document.createElement("div");
            const title = document.createElement("div");
            title.className = "task-title";
            title.append(document.createTextNode(`${task.kind || "任务"} · `));
            title.append(statusBadge(task.status));
            content.append(title);

            const id = document.createElement("div");
            id.className = "task-id";
            id.textContent = String(task.id || "未知 ID");
            content.append(id);

            const meta = document.createElement("div");
            meta.className = "task-meta";
            meta.append(metaText("阶段", automationLabel(task.automationState)));
            meta.append(metaText("更新", formatDate(task.updatedAt)));
            if (task.failureReason) {
                meta.append(metaText("原因", task.failureReason));
            }
            content.append(meta);
            card.append(content);

            if (ACTIVE_TASK_STATES.has(task.status)) {
                const cancelButton = document.createElement("button");
                cancelButton.className = "secondary compact";
                cancelButton.type = "button";
                cancelButton.textContent = "取消任务";
                cancelButton.addEventListener("click", () => cancelTask(task.id, cancelButton));
                card.append(cancelButton);
            }
            elements.tasksList.append(card);
        }
    }

    /**
     * @param {string} taskId
     * @param {HTMLButtonElement} button
     * @returns {Promise<void>}
     */
    async function cancelTask(taskId, button) {
        button.disabled = true;
        try {
            await requestJson(`/v1/tasks/${encodeURIComponent(taskId)}/cancel`, {
                method: "POST"
            });
            showToast("任务已取消");
            await refreshTasks();
        } catch (error) {
            handleError(error, "取消任务失败");
        } finally {
            button.disabled = false;
        }
    }

    /**
     * @returns {Promise<void>}
     */
    async function refreshContainers() {
        const containers = await requestJson("/v1/containers");
        renderContainers(Array.isArray(containers) ? containers : []);
    }

    /**
     * @param {Array<object>} containers
     * @returns {void}
     */
    function renderContainers(containers) {
        const checkedIds = new Set(
            [...elements.containersList.querySelectorAll("input:checked")]
                .map((input) => input.value)
        );
        elements.containersList.replaceChildren();
        if (containers.length === 0) {
            elements.containersList.append(emptyState("尚未发现可盘点容器"));
            return;
        }
        for (const container of containers) {
            const option = document.createElement("label");
            option.className = "container-option";
            const checkbox = document.createElement("input");
            checkbox.type = "checkbox";
            checkbox.value = String(container.id || "");
            checkbox.checked = checkedIds.has(checkbox.value);
            option.append(checkbox);

            const id = document.createElement("strong");
            id.textContent = container.id || "未命名容器";
            option.append(id);

            const position = container.position || {};
            const detail = document.createElement("span");
            detail.textContent = [
                container.type || "未知类型",
                container.dimension || "未知维度",
                `${position.x ?? "?"}, ${position.y ?? "?"}, ${position.z ?? "?"}`
            ].join(" · ");
            option.append(detail);
            elements.containersList.append(option);
        }
    }

    /**
     * @returns {Promise<void>}
     */
    async function refreshInventory() {
        const inventory = await requestJson("/v1/inventory");
        renderInventory(Array.isArray(inventory) ? inventory : []);
    }

    /**
     * @param {Array<object>} inventory
     * @returns {void}
     */
    function renderInventory(inventory) {
        elements.inventoryList.replaceChildren();
        if (inventory.length === 0) {
            elements.inventoryList.append(emptyState("还没有库存快照"));
            return;
        }
        for (const snapshot of inventory) {
            const card = document.createElement("article");
            card.className = "inventory-card";

            const title = document.createElement("h3");
            title.className = "inventory-title";
            title.textContent = snapshot.containerId || "未知容器";
            title.append(statusBadge(snapshot.freshness));
            card.append(title);

            const meta = document.createElement("div");
            meta.className = "inventory-meta";
            meta.append(metaText("来源", snapshot.sourceId || "未知"));
            meta.append(metaText("维度", snapshot.dimension || "未知"));
            meta.append(metaText("采集", formatDate(snapshot.capturedAt)));
            card.append(meta);

            const items = Array.isArray(snapshot.items) ? snapshot.items : [];
            if (items.length === 0) {
                card.append(emptyState("此快照没有物品"));
            } else {
                card.append(createItemTable(items));
            }
            elements.inventoryList.append(card);
        }
    }

    /**
     * @param {Array<object>} items
     * @returns {HTMLTableElement}
     */
    function createItemTable(items) {
        const table = document.createElement("table");
        table.className = "item-table";
        const header = document.createElement("thead");
        const headerRow = document.createElement("tr");
        for (const label of ["物品", "分类", "数量"]) {
            const cell = document.createElement("th");
            cell.scope = "col";
            cell.textContent = label;
            headerRow.append(cell);
        }
        header.append(headerRow);
        table.append(header);

        const body = document.createElement("tbody");
        for (const item of items) {
            const row = document.createElement("tr");
            const name = document.createElement("td");
            name.textContent = item.itemId || "未知物品";
            const kind = document.createElement("td");
            kind.textContent = item.kind || "未知";
            const count = document.createElement("td");
            count.textContent = Number(item.count || 0).toLocaleString("zh-CN");
            row.append(name, kind, count);
            body.append(row);
        }
        table.append(body);
        return table;
    }

    /**
     * @param {string} key
     * @param {string} value
     * @returns {HTMLSpanElement}
     */
    function metaText(key, value) {
        const span = document.createElement("span");
        span.textContent = `${key}：${value}`;
        return span;
    }

    /**
     * @param {string} value
     * @returns {HTMLSpanElement}
     */
    function statusBadge(value) {
        const badge = document.createElement("span");
        const status = String(value || "UNKNOWN");
        badge.className = "badge";
        if (status === "FAILED" || status === "CANCELLED" || status === "STALE") {
            badge.classList.add("danger");
        } else if (status === "PAUSED" || status === "UNKNOWN") {
            badge.classList.add("warning");
        }
        badge.textContent = TASK_STATUS_LABELS[status] || status;
        return badge;
    }

    /**
     * @param {string} value
     * @returns {string}
     */
    function automationLabel(value) {
        return AUTOMATION_LABELS[value] || value || "未知";
    }

    /**
     * @param {string} value
     * @returns {string}
     */
    function formatDate(value) {
        if (!value) {
            return "未知";
        }
        const date = new Date(value);
        if (Number.isNaN(date.getTime())) {
            return String(value);
        }
        return new Intl.DateTimeFormat("zh-CN", {
            month: "2-digit",
            day: "2-digit",
            hour: "2-digit",
            minute: "2-digit",
            second: "2-digit"
        }).format(date);
    }

    /**
     * @param {string} message
     * @returns {HTMLDivElement}
     */
    function emptyState(message) {
        const state = document.createElement("div");
        state.className = "empty-state";
        state.textContent = message;
        return state;
    }

    elements.authForm.addEventListener("submit", (event) => {
        event.preventDefault();
        void connectToken(elements.tokenInput.value);
    });

    elements.clearTokenButton.addEventListener("click", () => {
        clearAuthentication();
        showToast("访问令牌已清除");
        elements.tokenInput.focus();
    });

    elements.refreshButton.addEventListener("click", () => {
        void refreshAll();
    });

    elements.pauseButton.addEventListener("click", async () => {
        try {
            await requestJson("/v1/pause", { method: "POST" });
            showToast("自动化已暂停");
            await refreshStatus();
        } catch (error) {
            handleError(error, "暂停失败");
        }
    });

    elements.resumeButton.addEventListener("click", async () => {
        try {
            await requestJson("/v1/resume", { method: "POST" });
            showToast("自动化已恢复");
            await refreshStatus();
        } catch (error) {
            handleError(error, "恢复失败");
        }
    });

    elements.reloadButton.addEventListener("click", async () => {
        try {
            await requestJson("/v1/config/reload", { method: "POST" });
            showToast("配置已重载");
        } catch (error) {
            handleError(error, "重载配置失败");
            return;
        }
        const refreshes = await Promise.allSettled([
            refreshPickupSites(),
            refreshContainers(),
            refreshCoordinates(false)
        ]);
        const failed = refreshes.find((result) => result.status === "rejected");
        if (failed) {
            handleError(failed.reason, "更新配置视图失败");
        }
    });

    elements.coordinatesForm.addEventListener("submit", async (event) => {
        event.preventDefault();
        if (!coordinateSnapshot || !coordinatesDirty) {
            return;
        }
        let coordinates;
        try {
            coordinates = collectCoordinateUpdates();
        } catch (error) {
            handleError(error, "坐标格式错误");
            return;
        }
        elements.coordinatesSave.disabled = true;
        elements.coordinatesDiscard.disabled = true;
        elements.coordinatesStatus.textContent = "正在保存坐标配置…";
        try {
            const saved = await requestJson("/v1/config/coordinates", {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    revision: coordinateSnapshot.revision,
                    coordinates
                })
            });
            renderCoordinates(saved);
            await Promise.all([refreshPickupSites(), refreshContainers()]);
            showToast("坐标已保存，旧配置已备份为 stowling.json.bak");
        } catch (error) {
            updateCoordinatesDirtyState();
            handleError(error, "保存坐标失败");
        } finally {
            elements.coordinatesDiscard.disabled = false;
        }
    });

    elements.coordinatesDiscard.addEventListener("click", () => {
        elements.coordinatesDiscard.disabled = true;
        refreshCoordinates(true)
            .then(() => showToast("已重新读取坐标配置"))
            .catch((error) => handleError(error, "重新读取坐标失败"))
            .finally(() => {
                elements.coordinatesDiscard.disabled = false;
            });
    });

    elements.pickupForm.addEventListener("submit", async (event) => {
        event.preventDefault();
        const siteId = elements.siteSelect.value;
        if (!siteId) {
            showToast("请选择一个配置中的拾取点", true);
            return;
        }
        try {
            await requestJson("/v1/tasks/pickup", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ siteId })
            });
            showToast("拾取任务已创建");
            await refreshTasks();
        } catch (error) {
            handleError(error, "启动拾取失败");
        }
    });

    elements.auditForm.addEventListener("submit", async (event) => {
        event.preventDefault();
        const selected = [...elements.containersList.querySelectorAll("input:checked")]
            .map((input) => input.value)
            .filter(Boolean);
        if (selected.length === 0) {
            showToast("请至少选择一个容器", true);
            return;
        }
        try {
            await requestJson("/v1/audits", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ containerIds: selected })
            });
            showToast("盘点任务已创建");
            await refreshTasks();
        } catch (error) {
            handleError(error, "发起盘点失败");
        }
    });

    elements.refreshTasksButton.addEventListener("click", () => {
        refreshTasks().catch((error) => handleError(error, "更新任务失败"));
    });
    elements.refreshContainersButton.addEventListener("click", () => {
        refreshContainers().catch((error) => handleError(error, "更新容器失败"));
    });
    elements.refreshInventoryButton.addEventListener("click", () => {
        refreshInventory().catch((error) => handleError(error, "更新库存失败"));
    });

    window.setInterval(() => {
        if (authToken && document.visibilityState === "visible") {
            void refreshAll();
        }
    }, 10000);

    setConnected(false);
    const restoredToken = sessionStorage.getItem(TOKEN_KEY);
    if (restoredToken) {
        void connectToken(restoredToken, true);
    } else {
        elements.tokenInput.focus();
    }
})();
