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
        workflowSelect: document.querySelector("#workflow-select"),
        pickupRegionSelect: document.querySelector("#pickup-region-select"),
        pickupSubmit: document.querySelector("#pickup-submit"),
        pickupWorkflowsMessage: document.querySelector("#pickup-workflows-message"),
        pickupConfigForm: document.querySelector("#pickup-config-form"),
        pickupRegionsList: document.querySelector("#pickup-regions-list"),
        pickupCachesList: document.querySelector("#pickup-caches-list"),
        pickupWorkflowsList: document.querySelector("#pickup-workflows-list"),
        addRegionButton: document.querySelector("#add-region-button"),
        addCacheButton: document.querySelector("#add-cache-button"),
        addWorkflowButton: document.querySelector("#add-workflow-button"),
        pickupConfigSave: document.querySelector("#pickup-config-save"),
        pickupConfigDiscard: document.querySelector("#pickup-config-discard"),
        pickupConfigErrors: document.querySelector("#pickup-config-errors"),
        pickupConfigStatus: document.querySelector("#pickup-config-status"),
        pickupConfigRevision: document.querySelector("#pickup-config-revision"),
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
    let pickupWorkflowOptions = [];
    let pickupConfigSnapshot = null;
    let pickupConfigDraft = null;
    let pickupConfigDirty = false;
    let editablePickupIds = new WeakSet();

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
            pickup_workflow_not_found: "配置中找不到这个拾取流程，请重新读取配置",
            invalid_pickup_request: "所选区域不属于这个流程，请重新选择",
            task_not_found: "该任务已不存在，请刷新任务列表",
            container_not_found: "配置中找不到所选容器，请刷新或重载配置后重新选择",
            config_revision_conflict: "配置已被外部修改，请放弃草稿并重新读取后再保存",
            active_task_conflict: "活动或暂停任务存在，请先取消任务或等待任务完成",
            invalid_pickup_config: "拾取配置未通过校验，请检查页面中的中文错误提示",
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
                refreshPickupWorkflows(),
                refreshPickupConfig(false),
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
    async function refreshPickupWorkflows() {
        elements.workflowSelect.disabled = true;
        elements.pickupRegionSelect.disabled = true;
        elements.pickupSubmit.disabled = true;
        elements.pickupWorkflowsMessage.classList.remove("error");
        elements.pickupWorkflowsMessage.textContent = "正在读取可启动流程…";
        try {
            const workflows = await requestJson("/v1/pickup-workflows");
            renderPickupWorkflows(Array.isArray(workflows) ? workflows : []);
        } catch (error) {
            renderPickupWorkflows([], "无法读取拾取流程，请稍后重试");
            throw error;
        }
    }

    /**
     * @param {Array<object>} workflows
     * @param {string} errorMessage
     * @returns {void}
     */
    function renderPickupWorkflows(workflows, errorMessage = "") {
        const selectedId = elements.workflowSelect.value;
        pickupWorkflowOptions = workflows;
        elements.workflowSelect.replaceChildren();
        if (workflows.length === 0) {
            const option = document.createElement("option");
            option.value = "";
            option.textContent = "没有可用拾取流程";
            elements.workflowSelect.append(option);
            elements.workflowSelect.disabled = true;
            elements.pickupRegionSelect.disabled = true;
            elements.pickupSubmit.disabled = true;
            elements.pickupWorkflowsMessage.classList.toggle(
                "error",
                Boolean(errorMessage)
            );
            elements.pickupWorkflowsMessage.textContent = errorMessage
                || "配置中没有可用流程，请先在拾取配置中创建并保存";
            renderPickupLaunchRegions();
            return;
        }
        for (const workflow of workflows) {
            const option = document.createElement("option");
            option.value = String(workflow.id || "");
            option.textContent = `${workflow.name || workflow.id} · ${workflow.id}`;
            elements.workflowSelect.append(option);
        }
        if (workflows.some((workflow) => workflow.id === selectedId)) {
            elements.workflowSelect.value = selectedId;
        }
        elements.workflowSelect.disabled = false;
        elements.pickupSubmit.disabled = false;
        elements.pickupWorkflowsMessage.classList.remove("error");
        elements.pickupWorkflowsMessage.textContent = "可运行整个流程或只运行其中一个区域";
        renderPickupLaunchRegions();
    }

    /**
     * @returns {void}
     */
    function renderPickupLaunchRegions() {
        const selectedRegion = elements.pickupRegionSelect.value;
        const workflow = pickupWorkflowOptions.find((candidate) => {
            return candidate.id === elements.workflowSelect.value;
        });
        elements.pickupRegionSelect.replaceChildren();
        const allOption = document.createElement("option");
        allOption.value = "";
        allOption.textContent = "运行整个流程";
        elements.pickupRegionSelect.append(allOption);
        for (const region of workflow?.regions || []) {
            const option = document.createElement("option");
            option.value = String(region.regionId || "");
            option.textContent = `${region.name || region.regionId} · 优先级 ${region.priority}`;
            elements.pickupRegionSelect.append(option);
        }
        if ((workflow?.regions || []).some((region) => {
            return region.regionId === selectedRegion;
        })) {
            elements.pickupRegionSelect.value = selectedRegion;
        }
        elements.pickupRegionSelect.disabled = !workflow;
    }

    /**
     * @param {boolean} discardChanges
     * @returns {Promise<void>}
     */
    async function refreshPickupConfig(discardChanges) {
        if (pickupConfigDirty && !discardChanges) {
            return;
        }
        elements.pickupConfigStatus.textContent = "正在读取拾取配置…";
        try {
            const snapshot = await requestJson("/v1/config/pickup");
            renderPickupConfig(snapshot);
        } catch (error) {
            elements.pickupConfigStatus.textContent = "拾取配置读取失败";
            throw error;
        }
    }

    /**
     * @param {object} snapshot
     * @returns {void}
     */
    function renderPickupConfig(snapshot) {
        pickupConfigSnapshot = cloneJson({
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
        pickupConfigDraft = cloneJson(pickupConfigSnapshot);
        editablePickupIds = new WeakSet();
        pickupConfigDirty = false;
        renderPickupConfigEditor();
        elements.pickupConfigStatus.textContent = "拾取配置已与配置文件同步";
        const revision = pickupConfigSnapshot.revision;
        elements.pickupConfigRevision.textContent =
            `revision: ${revision.slice(0, 12) || "--"}`;
        elements.pickupConfigRevision.title = revision;
    }

    /**
     * @template T
     * @param {T} value
     * @returns {T}
     */
    function cloneJson(value) {
        return JSON.parse(JSON.stringify(value));
    }

    /**
     * @returns {void}
     */
    function renderPickupConfigEditor() {
        elements.pickupRegionsList.replaceChildren();
        elements.pickupCachesList.replaceChildren();
        elements.pickupWorkflowsList.replaceChildren();
        if (!pickupConfigDraft) {
            return;
        }
        pickupConfigDraft.pickupRegions.forEach((region, index) => {
            elements.pickupRegionsList.append(createRegionEditor(region, index));
        });
        pickupConfigDraft.cacheContainers.forEach((cache, index) => {
            elements.pickupCachesList.append(createCacheEditor(cache, index));
        });
        pickupConfigDraft.pickupWorkflows.forEach((workflow, index) => {
            elements.pickupWorkflowsList.append(createWorkflowEditor(workflow, index));
        });
        appendEditorEmptyStates();
        updatePickupConfigState();
    }

    /**
     * @returns {void}
     */
    function appendEditorEmptyStates() {
        const targets = [
            [elements.pickupRegionsList, "尚未创建拾取区域"],
            [elements.pickupCachesList, "尚未创建缓存容器"],
            [elements.pickupWorkflowsList, "尚未创建拾取流程"]
        ];
        for (const [target, message] of targets) {
            if (!target.hasChildNodes()) {
                target.append(emptyState(message));
            }
        }
    }

    /**
     * @param {object} region
     * @param {number} index
     * @returns {HTMLElement}
     */
    function createRegionEditor(region, index) {
        const card = createEditorCard("region", region, index, () => {
            removePickupResource("region", index);
        });
        const fields = document.createElement("div");
        fields.className = "editor-fields";
        fields.append(
            createIdentityField("region", region, "区域 ID"),
            createTextField("显示名称", region.name, (value) => {
                region.name = value;
                markPickupConfigDirty();
            }),
            createTextField("维度", region.dimension, (value) => {
                region.dimension = value;
                markPickupConfigDirty();
            })
        );
        const shapeType = region.shape?.type === "CUBOID" ? "CUBOID" : "SPHERE";
        fields.append(createSelectField(
            "区域形状",
            shapeType,
            [["SPHERE", "球形"], ["CUBOID", "长方体"]],
            (value) => changeRegionShape(region, value)
        ));
        card.append(fields);
        card.append(createPositionEditor("待机点", region.standbyPosition, (value) => {
            region.standbyPosition = value;
            markPickupConfigDirty();
        }));
        if (shapeType === "SPHERE") {
            card.append(createPositionEditor("球心", region.shape.center, (value) => {
                region.shape.center = value;
                markPickupConfigDirty();
            }));
            const radius = createNumberField(
                "半径（1-128）",
                region.shape.radius,
                1,
                (value) => {
                    region.shape.radius = value;
                    markPickupConfigDirty();
                }
            );
            card.append(radius);
        } else {
            card.append(createPositionEditor("第一角点", region.shape.firstCorner, (value) => {
                region.shape.firstCorner = value;
                markPickupConfigDirty();
            }));
            card.append(createPositionEditor("第二角点", region.shape.secondCorner, (value) => {
                region.shape.secondCorner = value;
                markPickupConfigDirty();
            }));
        }
        return card;
    }

    /**
     * @param {object} region
     * @param {string} type
     * @returns {void}
     */
    function changeRegionShape(region, type) {
        const origin = cloneJson(region.standbyPosition || { x: 0, y: 64, z: 0 });
        if (type === "CUBOID") {
            region.shape = {
                type: "CUBOID",
                firstCorner: cloneJson(origin),
                secondCorner: cloneJson(origin)
            };
        } else {
            region.shape = { type: "SPHERE", center: cloneJson(origin), radius: 16 };
        }
        markPickupConfigDirty(true);
    }

    /**
     * @param {object} cache
     * @param {number} index
     * @returns {HTMLElement}
     */
    function createCacheEditor(cache, index) {
        const card = createEditorCard("cache", cache, index, () => {
            removePickupResource("cache", index);
        });
        const fields = document.createElement("div");
        fields.className = "editor-fields";
        fields.append(
            createIdentityField("cache", cache, "缓存 ID"),
            createTextField("显示名称", cache.name, (value) => {
                cache.name = value;
                markPickupConfigDirty();
            }),
            createTextField("维度", cache.dimension, (value) => {
                cache.dimension = value;
                markPickupConfigDirty();
            }),
            createSelectField(
                "容器类型",
                cache.type,
                containerTypeOptions(),
                (value) => {
                    cache.type = value;
                    markPickupConfigDirty();
                }
            )
        );
        card.append(fields);
        card.append(createPositionEditor("容器坐标", cache.position, (value) => {
            cache.position = value;
            markPickupConfigDirty();
        }));
        return card;
    }

    /**
     * @returns {Array<Array<string>>}
     */
    function containerTypeOptions() {
        return [
            ["CHEST", "箱子"],
            ["TRAPPED_CHEST", "陷阱箱"],
            ["BARREL", "木桶"],
            ["SHULKER_BOX", "潜影盒"],
            ["HOPPER", "漏斗"],
            ["OTHER", "其他"]
        ];
    }

    /**
     * @param {object} workflow
     * @param {number} index
     * @returns {HTMLElement}
     */
    function createWorkflowEditor(workflow, index) {
        const card = createEditorCard("workflow", workflow, index, () => {
            removePickupResource("workflow", index);
        });
        card.classList.add("workflow-editor");
        const fields = document.createElement("div");
        fields.className = "editor-fields";
        fields.append(
            createIdentityField("workflow", workflow, "流程 ID"),
            createTextField("显示名称", workflow.name, (value) => {
                workflow.name = value;
                markPickupConfigDirty();
            }),
            createTextField("维度", workflow.dimension, (value) => {
                workflow.dimension = value;
                markPickupConfigDirty();
            }),
            createSelectField(
                "缓存策略",
                workflow.cachePolicy,
                [["NEAREST_AVAILABLE", "最近可用缓存"]],
                (value) => {
                    workflow.cachePolicy = value;
                    markPickupConfigDirty();
                }
            ),
            createSelectField(
                "分类时机",
                workflow.classificationTiming,
                [["AFTER_REGION", "每个区域后"], ["AFTER_ROUND", "每轮后"]],
                (value) => {
                    workflow.classificationTiming = value;
                    markPickupConfigDirty();
                }
            ),
            createSelectField(
                "结束方式",
                workflow.completionMode,
                [["MANUAL", "手动停止"], ["ALL_IDLE", "全部空闲后结束"]],
                (value) => {
                    workflow.completionMode = value;
                    markPickupConfigDirty();
                }
            ),
            createNumberField(
                "全部空闲等待（秒）",
                workflow.completionIdleDelay,
                0,
                (value) => {
                    workflow.completionIdleDelay = value;
                    markPickupConfigDirty();
                }
            )
        );
        card.append(fields);
        card.append(createWorkflowRegionsEditor(workflow));
        card.append(createWorkflowCachesEditor(workflow));
        return card;
    }

    /**
     * @param {object} workflow
     * @returns {HTMLElement}
     */
    function createWorkflowRegionsEditor(workflow) {
        const section = document.createElement("section");
        section.className = "binding-section";
        const title = document.createElement("h4");
        title.textContent = "区域绑定（可拖动排序）";
        section.append(title);
        for (const [index, binding] of workflow.regions.entries()) {
            const region = pickupConfigDraft.pickupRegions.find((candidate) => {
                return candidate.id === binding.regionId;
            });
            const row = document.createElement("div");
            row.className = "binding-row";
            row.draggable = true;
            addBindingDragHandlers(row, workflow, index);
            const name = document.createElement("strong");
            name.textContent = region?.name || binding.regionId;
            row.append(name);
            row.append(createNumberField("优先级", binding.priority, -2147483648, (value) => {
                binding.priority = value;
                markPickupConfigDirty();
            }));
            row.append(createNumberField("空闲秒数", binding.idleDelay, 0, (value) => {
                binding.idleDelay = value;
                markPickupConfigDirty();
            }));
            row.append(createMoveButtons(index, workflow.regions.length, (offset) => {
                moveWorkflowBinding(workflow, index, offset);
            }));
            const remove = document.createElement("button");
            remove.type = "button";
            remove.className = "ghost compact danger-action";
            remove.textContent = "移除";
            remove.addEventListener("click", () => {
                workflow.regions.splice(index, 1);
                markPickupConfigDirty(true);
            });
            row.append(remove);
            section.append(row);
        }
        const available = pickupConfigDraft.pickupRegions.filter((region) => {
            return !workflow.regions.some((binding) => binding.regionId === region.id);
        });
        if (available.length > 0) {
            const addRow = document.createElement("div");
            addRow.className = "binding-add-row";
            const select = document.createElement("select");
            setAccessibleLabel(select, "选择要加入流程的区域");
            for (const region of available) {
                const option = document.createElement("option");
                option.value = region.id;
                option.textContent = `${region.name} · ${region.id}`;
                select.append(option);
            }
            const add = document.createElement("button");
            add.type = "button";
            add.className = "secondary compact";
            add.textContent = "加入区域";
            add.addEventListener("click", () => {
                workflow.regions.push({
                    regionId: select.value,
                    priority: 100,
                    idleDelay: 60
                });
                markPickupConfigDirty(true);
            });
            addRow.append(select, add);
            section.append(addRow);
        }
        return section;
    }

    /**
     * @param {HTMLElement} row
     * @param {object} workflow
     * @param {number} index
     * @returns {void}
     */
    function addBindingDragHandlers(row, workflow, index) {
        row.addEventListener("dragstart", (event) => {
            event.stopPropagation();
            event.dataTransfer?.setData(
                "text/plain",
                `binding:${workflow.id}:${index}`
            );
        });
        row.addEventListener("dragover", (event) => event.preventDefault());
        row.addEventListener("drop", (event) => {
            event.preventDefault();
            event.stopPropagation();
            const [kind, workflowId, sourceIndex] = String(
                event.dataTransfer?.getData("text/plain") || ""
            ).split(":");
            if (kind !== "binding" || workflowId !== workflow.id) {
                return;
            }
            const source = Number(sourceIndex);
            if (Number.isInteger(source) && source !== index) {
                const [binding] = workflow.regions.splice(source, 1);
                workflow.regions.splice(index, 0, binding);
                markPickupConfigDirty(true);
            }
        });
    }

    /**
     * @param {object} workflow
     * @returns {HTMLElement}
     */
    function createWorkflowCachesEditor(workflow) {
        const section = document.createElement("section");
        section.className = "binding-section";
        const title = document.createElement("h4");
        title.textContent = "可用缓存";
        section.append(title);
        const options = document.createElement("div");
        options.className = "check-grid";
        for (const cache of pickupConfigDraft.cacheContainers) {
            const label = document.createElement("label");
            label.className = "check-option";
            const input = document.createElement("input");
            input.type = "checkbox";
            input.checked = workflow.cacheIds.includes(cache.id);
            input.addEventListener("change", () => {
                if (input.checked) {
                    workflow.cacheIds.push(cache.id);
                } else {
                    workflow.cacheIds = workflow.cacheIds.filter((id) => id !== cache.id);
                }
                markPickupConfigDirty();
            });
            const text = document.createElement("span");
            text.textContent = `${cache.name} · ${cache.id}`;
            label.append(input, text);
            options.append(label);
        }
        if (!options.hasChildNodes()) {
            options.append(emptyState("请先创建缓存容器"));
        }
        section.append(options);
        return section;
    }

    /**
     * @param {string} type
     * @param {object} resource
     * @param {number} index
     * @param {() => void} onDelete
     * @returns {HTMLElement}
     */
    function createEditorCard(type, resource, index, onDelete) {
        const card = document.createElement("article");
        card.className = "editor-card";
        card.draggable = true;
        card.dataset.editorType = type;
        card.dataset.index = String(index);
        addResourceDragHandlers(card, type, index);
        const header = document.createElement("div");
        header.className = "editor-card-heading";
        const title = document.createElement("strong");
        title.textContent = resource.name || resource.id || "未命名";
        const actions = document.createElement("div");
        actions.className = "editor-card-actions";
        actions.append(createMoveButtons(index, resourceList(type).length, (offset) => {
            movePickupResource(type, index, offset);
        }));
        const remove = document.createElement("button");
        remove.type = "button";
        remove.className = "ghost compact danger-action";
        remove.textContent = "删除";
        remove.addEventListener("click", onDelete);
        actions.append(remove);
        header.append(title, actions);
        card.append(header);
        return card;
    }

    /**
     * @param {HTMLElement} card
     * @param {string} type
     * @param {number} index
     * @returns {void}
     */
    function addResourceDragHandlers(card, type, index) {
        card.addEventListener("dragstart", (event) => {
            event.dataTransfer?.setData("text/plain", `${type}:${index}`);
        });
        card.addEventListener("dragover", (event) => event.preventDefault());
        card.addEventListener("drop", (event) => {
            event.preventDefault();
            const [sourceType, sourceIndex] = String(
                event.dataTransfer?.getData("text/plain") || ""
            ).split(":");
            if (sourceType !== type) {
                return;
            }
            const source = Number(sourceIndex);
            if (Number.isInteger(source) && source !== index) {
                const list = resourceList(type);
                const [item] = list.splice(source, 1);
                list.splice(index, 0, item);
                markPickupConfigDirty(true);
            }
        });
    }

    /**
     * @param {string} type
     * @returns {Array<object>}
     */
    function resourceList(type) {
        if (type === "region") {
            return pickupConfigDraft.pickupRegions;
        }
        if (type === "cache") {
            return pickupConfigDraft.cacheContainers;
        }
        return pickupConfigDraft.pickupWorkflows;
    }

    /**
     * @param {number} index
     * @param {number} length
     * @param {(offset: number) => void} move
     * @returns {HTMLElement}
     */
    function createMoveButtons(index, length, move) {
        const group = document.createElement("span");
        group.className = "move-buttons";
        const up = document.createElement("button");
        up.type = "button";
        up.className = "ghost compact";
        up.textContent = "↑";
        up.title = "上移";
        up.setAttribute("aria-label", "上移");
        up.disabled = index === 0;
        up.addEventListener("click", () => move(-1));
        const down = document.createElement("button");
        down.type = "button";
        down.className = "ghost compact";
        down.textContent = "↓";
        down.title = "下移";
        down.setAttribute("aria-label", "下移");
        down.disabled = index >= length - 1;
        down.addEventListener("click", () => move(1));
        group.append(up, down);
        return group;
    }

    /**
     * @param {string} type
     * @param {object} resource
     * @param {string} labelText
     * @returns {HTMLElement}
     */
    function createIdentityField(type, resource, labelText) {
        const locked = !editablePickupIds.has(resource);
        return createTextField(labelText, resource.id, (value) => {
            const previous = resource.id;
            resource.id = value;
            updateDraftReferences(type, previous, value);
            markPickupConfigDirty();
        }, locked);
    }

    /**
     * @param {string} type
     * @param {string} previous
     * @param {string} next
     * @returns {void}
     */
    function updateDraftReferences(type, previous, next) {
        if (!previous || previous === next) {
            return;
        }
        for (const workflow of pickupConfigDraft.pickupWorkflows) {
            if (type === "region") {
                for (const binding of workflow.regions) {
                    if (binding.regionId === previous) {
                        binding.regionId = next;
                    }
                }
            }
            if (type === "cache") {
                workflow.cacheIds = workflow.cacheIds.map((id) => {
                    return id === previous ? next : id;
                });
            }
        }
    }

    /**
     * @param {string} labelText
     * @param {string} value
     * @param {(value: string) => void} onInput
     * @param {boolean} disabled
     * @returns {HTMLElement}
     */
    function createTextField(labelText, value, onInput, disabled = false) {
        const field = document.createElement("div");
        field.className = "editor-field";
        const label = document.createElement("label");
        const input = document.createElement("input");
        input.type = "text";
        input.value = String(value ?? "");
        input.disabled = disabled;
        connectLabel(label, input, labelText);
        input.addEventListener("input", () => onInput(input.value));
        field.append(label, input);
        return field;
    }

    /**
     * @param {string} labelText
     * @param {number} value
     * @param {number} minimum
     * @param {(value: number|null) => void} onInput
     * @returns {HTMLElement}
     */
    function createNumberField(labelText, value, minimum, onInput) {
        const field = document.createElement("div");
        field.className = "editor-field compact-field";
        const label = document.createElement("label");
        const input = document.createElement("input");
        input.type = "number";
        input.step = "1";
        input.min = String(minimum);
        input.value = String(value ?? 0);
        connectLabel(label, input, labelText);
        input.addEventListener("input", () => {
            onInput(parseInteger(input.value));
        });
        field.append(label, input);
        return field;
    }

    /**
     * @param {string} labelText
     * @param {string} value
     * @param {Array<Array<string>>} options
     * @param {(value: string) => void} onChange
     * @returns {HTMLElement}
     */
    function createSelectField(labelText, value, options, onChange) {
        const field = document.createElement("div");
        field.className = "editor-field";
        const label = document.createElement("label");
        const select = document.createElement("select");
        connectLabel(label, select, labelText);
        for (const [optionValue, optionText] of options) {
            const option = document.createElement("option");
            option.value = optionValue;
            option.textContent = optionText;
            select.append(option);
        }
        select.value = value;
        select.addEventListener("change", () => onChange(select.value));
        field.append(label, select);
        return field;
    }

    /**
     * @param {string} title
     * @param {object} position
     * @param {(value: object) => void} onInput
     * @returns {HTMLElement}
     */
    function createPositionEditor(title, position, onInput) {
        const fieldset = document.createElement("fieldset");
        fieldset.className = "position-editor";
        const legend = document.createElement("legend");
        legend.textContent = title;
        fieldset.append(legend);
        const value = cloneJson(position || { x: 0, y: 64, z: 0 });
        for (const axis of ["x", "y", "z"]) {
            const field = createNumberField(
                axis.toUpperCase(),
                value[axis],
                -2147483648,
                (next) => {
                    value[axis] = next;
                    onInput(cloneJson(value));
                }
            );
            fieldset.append(field);
        }
        return fieldset;
    }

    /**
     * @param {HTMLLabelElement} label
     * @param {HTMLElement} control
     * @param {string} text
     * @returns {void}
     */
    function connectLabel(label, control, text) {
        const id = `editor-${Math.random().toString(36).slice(2)}`;
        control.id = id;
        label.htmlFor = id;
        label.textContent = text;
    }

    /**
     * @param {HTMLElement} control
     * @param {string} text
     * @returns {void}
     */
    function setAccessibleLabel(control, text) {
        control.setAttribute("aria-label", text);
    }

    /**
     * @param {string} value
     * @returns {number|null}
     */
    function parseInteger(value) {
        if (!/^-?(0|[1-9][0-9]*)$/.test(value.trim())) {
            return null;
        }
        const result = Number(value);
        return Number.isSafeInteger(result) ? result : null;
    }

    /**
     * @param {string} type
     * @param {number} index
     * @param {number} offset
     * @returns {void}
     */
    function movePickupResource(type, index, offset) {
        const list = resourceList(type);
        const destination = index + offset;
        if (destination < 0 || destination >= list.length) {
            return;
        }
        const [item] = list.splice(index, 1);
        list.splice(destination, 0, item);
        markPickupConfigDirty(true);
    }

    /**
     * @param {object} workflow
     * @param {number} index
     * @param {number} offset
     * @returns {void}
     */
    function moveWorkflowBinding(workflow, index, offset) {
        const destination = index + offset;
        if (destination < 0 || destination >= workflow.regions.length) {
            return;
        }
        const [binding] = workflow.regions.splice(index, 1);
        workflow.regions.splice(destination, 0, binding);
        markPickupConfigDirty(true);
    }

    /**
     * @param {string} type
     * @param {number} index
     * @returns {void}
     */
    function removePickupResource(type, index) {
        const resource = resourceList(type)[index];
        const references = pickupResourceReferences(type, resource.id);
        if (references.length > 0) {
            setPickupConfigErrors([
                `无法删除“${resource.name || resource.id}”：被流程 `
                    + `${references.join("、")} 引用`
            ]);
            return;
        }
        resourceList(type).splice(index, 1);
        markPickupConfigDirty(true);
    }

    /**
     * @param {string} type
     * @param {string} id
     * @returns {Array<string>}
     */
    function pickupResourceReferences(type, id) {
        if (type === "workflow") {
            return [];
        }
        return pickupConfigDraft.pickupWorkflows
            .filter((workflow) => {
                if (type === "region") {
                    return workflow.regions.some((binding) => binding.regionId === id);
                }
                return workflow.cacheIds.includes(id);
            })
            .map((workflow) => workflow.name || workflow.id);
    }

    /**
     * @param {boolean} rerender
     * @returns {void}
     */
    function markPickupConfigDirty(rerender = false) {
        pickupConfigDirty = true;
        if (rerender) {
            renderPickupConfigEditor();
            return;
        }
        updatePickupConfigState();
    }

    /**
     * @returns {void}
     */
    function updatePickupConfigState() {
        const errors = validatePickupConfigDraft();
        setPickupConfigErrors(errors);
        elements.pickupConfigSave.disabled = !pickupConfigDirty || errors.length > 0;
        elements.pickupConfigStatus.classList.toggle("dirty", pickupConfigDirty);
        if (errors.length > 0) {
            elements.pickupConfigStatus.textContent = "草稿中有错误，修正后才能保存";
        } else if (pickupConfigDirty) {
            elements.pickupConfigStatus.textContent = "有未保存的拾取配置修改";
        } else {
            elements.pickupConfigStatus.textContent = "拾取配置已与配置文件同步";
        }
    }

    /**
     * @param {Array<string>} errors
     * @returns {void}
     */
    function setPickupConfigErrors(errors) {
        elements.pickupConfigErrors.replaceChildren();
        if (errors.length === 0) {
            return;
        }
        const title = document.createElement("strong");
        title.textContent = "请修正以下问题：";
        const list = document.createElement("ul");
        for (const error of errors) {
            const item = document.createElement("li");
            item.textContent = error;
            list.append(item);
        }
        elements.pickupConfigErrors.append(title, list);
    }

    /**
     * @returns {Array<string>}
     */
    function validatePickupConfigDraft() {
        if (!pickupConfigDraft) {
            return ["尚未读取拾取配置"];
        }
        const errors = [];
        const ids = new Map();
        const resources = [
            ...pickupConfigDraft.pickupRegions.map((value) => ["区域", value]),
            ...pickupConfigDraft.cacheContainers.map((value) => ["缓存", value]),
            ...pickupConfigDraft.pickupWorkflows.map((value) => ["流程", value])
        ];
        for (const [kind, resource] of resources) {
            const id = String(resource.id || "");
            if (!/^[a-z0-9][a-z0-9._-]{0,63}$/.test(id)) {
                errors.push(`${kind} ID“${id || "空"}”格式无效`);
            }
            if (ids.has(id)) {
                errors.push(`重复 ID“${id}”，已被${ids.get(id)}使用`);
            } else {
                ids.set(id, kind);
            }
            if (!String(resource.name || "").trim()) {
                errors.push(`${kind}“${id || "未命名"}”的显示名称不能为空`);
            }
            if (!String(resource.dimension || "").trim()) {
                errors.push(`${kind}“${id || "未命名"}”的维度不能为空`);
            }
        }
        validateRegionDrafts(errors);
        validateCacheDrafts(errors);
        validateWorkflowDrafts(errors);
        return [...new Set(errors)];
    }

    /**
     * @param {Array<string>} errors
     * @returns {void}
     */
    function validateRegionDrafts(errors) {
        for (const region of pickupConfigDraft.pickupRegions) {
            if (!validPosition(region.standbyPosition)) {
                errors.push(`区域“${region.name || region.id}”的待机点坐标无效`);
            }
            if (region.shape?.type === "SPHERE") {
                if (!validPosition(region.shape.center)) {
                    errors.push(`区域“${region.name || region.id}”的球心坐标无效`);
                }
                if (!Number.isInteger(region.shape.radius)
                        || region.shape.radius < 1
                        || region.shape.radius > 128) {
                    errors.push(`区域“${region.name || region.id}”半径必须为 1-128`);
                }
                continue;
            }
            const first = region.shape?.firstCorner;
            const second = region.shape?.secondCorner;
            if (!validPosition(first) || !validPosition(second)) {
                errors.push(`区域“${region.name || region.id}”的长方体角点无效`);
            } else if (first.x > second.x || first.y > second.y || first.z > second.z) {
                errors.push(`区域“${region.name || region.id}”第一角点不能大于第二角点`);
            }
        }
    }

    /**
     * @param {Array<string>} errors
     * @returns {void}
     */
    function validateCacheDrafts(errors) {
        const locations = new Map();
        for (const cache of pickupConfigDraft.cacheContainers) {
            if (!validPosition(cache.position)) {
                errors.push(`缓存“${cache.name || cache.id}”的坐标无效`);
                continue;
            }
            const key = `${cache.dimension}:${cache.position.x}:${cache.position.y}`
                + `:${cache.position.z}`;
            if (locations.has(key)) {
                errors.push(`缓存“${cache.name || cache.id}”与“${locations.get(key)}”坐标重复`);
            } else {
                locations.set(key, cache.name || cache.id);
            }
        }
    }

    /**
     * @param {Array<string>} errors
     * @returns {void}
     */
    function validateWorkflowDrafts(errors) {
        const regions = new Map(
            pickupConfigDraft.pickupRegions.map((region) => [region.id, region])
        );
        const caches = new Map(
            pickupConfigDraft.cacheContainers.map((cache) => [cache.id, cache])
        );
        for (const workflow of pickupConfigDraft.pickupWorkflows) {
            const label = workflow.name || workflow.id;
            if (workflow.regions.length === 0) {
                errors.push(`流程“${label}”不能为空，至少选择一个区域`);
            }
            if (workflow.cacheIds.length === 0) {
                errors.push(`流程“${label}”没有缓存，至少选择一个缓存`);
            }
            for (const binding of workflow.regions) {
                const region = regions.get(binding.regionId);
                if (!region) {
                    errors.push(`流程“${label}”引用了不存在的区域“${binding.regionId}”`);
                } else if (region.dimension !== workflow.dimension) {
                    errors.push(`流程“${label}”与区域“${region.name}”跨维度`);
                }
                if (!Number.isInteger(binding.priority)) {
                    errors.push(`流程“${label}”的区域优先级必须为整数`);
                }
                if (!Number.isInteger(binding.idleDelay) || binding.idleDelay < 0) {
                    errors.push(`流程“${label}”的区域空闲时间必须为非负整数`);
                }
            }
            for (const cacheId of workflow.cacheIds) {
                const cache = caches.get(cacheId);
                if (!cache) {
                    errors.push(`流程“${label}”引用了不存在的缓存“${cacheId}”`);
                } else if (cache.dimension !== workflow.dimension) {
                    errors.push(`流程“${label}”与缓存“${cache.name}”跨维度`);
                }
            }
            if (!Number.isInteger(workflow.completionIdleDelay)
                    || workflow.completionIdleDelay < 0) {
                errors.push(`流程“${label}”的结束等待必须为非负整数`);
            }
        }
    }

    /**
     * @param {object} value
     * @returns {boolean}
     */
    function validPosition(value) {
        return value != null
            && [value.x, value.y, value.z].every((coordinate) => {
                return Number.isInteger(coordinate)
                    && coordinate >= -2147483648
                    && coordinate <= 2147483647;
            });
    }

    /**
     * @param {string} prefix
     * @returns {string}
     */
    function nextPickupId(prefix) {
        const used = new Set([
            ...pickupConfigDraft.pickupRegions.map((item) => item.id),
            ...pickupConfigDraft.cacheContainers.map((item) => item.id),
            ...pickupConfigDraft.pickupWorkflows.map((item) => item.id)
        ]);
        let number = 1;
        while (used.has(`${prefix}-${number}`)) {
            number += 1;
        }
        return `${prefix}-${number}`;
    }

    /**
     * @returns {void}
     */
    function addPickupRegion() {
        const id = nextPickupId("region");
        const region = {
            id,
            name: "新拾取区域",
            dimension: "minecraft:overworld",
            standbyPosition: { x: 0, y: 64, z: 0 },
            shape: { type: "SPHERE", center: { x: 0, y: 64, z: 0 }, radius: 16 }
        };
        pickupConfigDraft.pickupRegions.push(region);
        editablePickupIds.add(region);
        markPickupConfigDirty(true);
    }

    /**
     * @returns {void}
     */
    function addPickupCache() {
        const id = nextPickupId("cache");
        const cache = {
            id,
            name: "新缓存",
            dimension: "minecraft:overworld",
            position: { x: 0, y: 64, z: 0 },
            type: "CHEST"
        };
        pickupConfigDraft.cacheContainers.push(cache);
        editablePickupIds.add(cache);
        markPickupConfigDirty(true);
    }

    /**
     * @returns {void}
     */
    function addPickupWorkflow() {
        const id = nextPickupId("workflow");
        const workflow = {
            id,
            name: "新拾取流程",
            dimension: "minecraft:overworld",
            regions: [],
            cacheIds: [],
            cachePolicy: "NEAREST_AVAILABLE",
            classificationTiming: "AFTER_ROUND",
            completionMode: "MANUAL",
            completionIdleDelay: 300
        };
        pickupConfigDraft.pickupWorkflows.push(workflow);
        editablePickupIds.add(workflow);
        markPickupConfigDirty(true);
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
            ["CONTAINER", "仓库与溢出容器"],
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
            if (task.workflowId) {
                meta.append(metaText("流程", task.workflowId));
            }
            if (task.currentRegionId) {
                meta.append(metaText("当前区域", task.currentRegionId));
            }
            if (task.round != null) {
                meta.append(metaText("巡逻轮次", String(task.round)));
            }
            if (task.stage) {
                meta.append(metaText("工作阶段", task.stage));
            }
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
            refreshPickupWorkflows(),
            refreshPickupConfig(true),
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
            await Promise.all([refreshPickupWorkflows(), refreshContainers()]);
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
        const workflowId = elements.workflowSelect.value;
        const regionId = elements.pickupRegionSelect.value;
        if (!workflowId) {
            showToast("请选择一个拾取流程", true);
            return;
        }
        const body = { workflowId };
        if (regionId) {
            body.regionId = regionId;
        }
        try {
            await requestJson("/v1/tasks/pickup", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(body)
            });
            showToast("拾取任务已创建");
            await refreshTasks();
        } catch (error) {
            handleError(error, "启动拾取失败");
        }
    });

    elements.workflowSelect.addEventListener("change", renderPickupLaunchRegions);

    elements.addRegionButton.addEventListener("click", addPickupRegion);
    elements.addCacheButton.addEventListener("click", addPickupCache);
    elements.addWorkflowButton.addEventListener("click", addPickupWorkflow);

    elements.pickupConfigForm.addEventListener("submit", async (event) => {
        event.preventDefault();
        const errors = validatePickupConfigDraft();
        if (errors.length > 0) {
            setPickupConfigErrors(errors);
            showToast("拾取配置中仍有错误，暂未保存", true);
            return;
        }
        if (!pickupConfigSnapshot || !pickupConfigDirty) {
            return;
        }
        elements.pickupConfigSave.disabled = true;
        elements.pickupConfigDiscard.disabled = true;
        elements.pickupConfigStatus.textContent = "正在保存全部拾取配置…";
        try {
            await requestJson("/v1/config/pickup", {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    revision: pickupConfigSnapshot.revision,
                    pickupRegions: pickupConfigDraft.pickupRegions,
                    cacheContainers: pickupConfigDraft.cacheContainers,
                    pickupWorkflows: pickupConfigDraft.pickupWorkflows
                })
            });
            pickupConfigDirty = false;
            await Promise.all([
                refreshPickupConfig(true),
                refreshPickupWorkflows(),
                refreshCoordinates(true),
                refreshContainers()
            ]);
            showToast("拾取配置已保存并回读，旧配置已生成备份");
        } catch (error) {
            updatePickupConfigState();
            handleError(error, "保存拾取配置失败");
        } finally {
            elements.pickupConfigDiscard.disabled = false;
        }
    });

    elements.pickupConfigDiscard.addEventListener("click", () => {
        elements.pickupConfigDiscard.disabled = true;
        refreshPickupConfig(true)
            .then(() => showToast("已放弃草稿并重新读取拾取配置"))
            .catch((error) => handleError(error, "重新读取拾取配置失败"))
            .finally(() => {
                elements.pickupConfigDiscard.disabled = false;
            });
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
