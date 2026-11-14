(() => {
    "use strict";

    const TOKEN_KEY = "stowling.control.token";
    const ACTIVE_TASK_STATES = new Set(["PENDING", "RUNNING", "WAITING", "PAUSED"]);
    const TASK_STATUS_LABELS = new Map([
        ["PENDING", "等待中"],
        ["RUNNING", "运行中"],
        ["WAITING", "等待条件"],
        ["PAUSED", "已暂停"],
        ["SUCCEEDED", "已完成"],
        ["FAILED", "失败"],
        ["CANCELLED", "已取消"],
        ["READY", "就绪"],
        ["UNKNOWN", "未知"]
    ]);
    const TASK_KIND_LABELS = new Map([
        ["PICKUP", "自动拾取"],
        ["AUTOMATION", "自定义任务"],
        ["TRANSFER", "容器搬运"],
        ["AUDIT", "库存盘点"],
        ["PACK", "潜影盒装箱"]
    ]);
    const TASK_STAGE_LABELS = new Map([
        ["NAVIGATE_PICKUP", "前往拾取区域"],
        ["COLLECT", "拾取掉落物"],
        ["DEPOSIT_BUFFER", "存入缓存"],
        ["WAIT_FOR_IDLE", "等待区域空闲"],
        ["CLASSIFY_BUFFER", "分类缓存"],
        ["DELIVER_LOOSE", "散装入库"],
        ["PACK", "潜影盒装箱"],
        ["COMPLETE", "已完成"],
        ["READY", "就绪"],
        ["RUNNING", "运行中"],
        ["PAUSED", "已暂停"],
        ["FAILED", "失败"]
    ]);
    const MODULE_STATE_LABELS = new Map([
        ["RUNNING", "运行中"],
        ["SUCCEEDED", "已完成"],
        ["PAUSED", "已暂停"],
        ["FAILED", "失败"]
    ]);
    const DIMENSION_LABELS = new Map([
        ["minecraft:overworld", "主世界"],
        ["minecraft:the_nether", "下界"],
        ["minecraft:the_end", "末地"]
    ]);
    const CONTAINER_TYPE_LABELS = new Map([
        ["CHEST", "箱子"],
        ["TRAPPED_CHEST", "陷阱箱"],
        ["BARREL", "木桶"],
        ["SHULKER_BOX", "潜影盒"],
        ["HOPPER", "漏斗"],
        ["OTHER", "其他"]
    ]);
    const CACHE_POLICY_LABELS = new Map([
        ["NEAREST_AVAILABLE", "最近可用缓存"]
    ]);
    const CLASSIFICATION_TIMING_LABELS = new Map([
        ["AFTER_REGION", "每个区域结束后"],
        ["AFTER_ROUND", "整轮结束后"]
    ]);
    const COMPLETION_MODE_LABELS = new Map([
        ["MANUAL", "手动停止"],
        ["ALL_IDLE", "全部区域空闲后"]
    ]);
    const NAV_ITEMS = Object.freeze([
        { page: "dashboard", href: "/index.html", label: "状态概览" },
        { page: "coordinates", href: "/coordinates.html", label: "命名坐标" },
        { page: "tasks", href: "/tasks.html", label: "任务编排" },
        { page: "storage", href: "/storage.html", label: "仓库与库存" }
    ]);
    const connectedHandlers = new Set();

    let authToken = "";
    let connected = false;
    let connectBusy = false;

    class ApiError extends Error {
        /**
         * @param {string} message
         * @param {number} status
         * @param {string} code
         */
        constructor(message, status = 0, code = "") {
            super(message);
            this.name = "ApiError";
            this.status = status;
            this.code = code;
        }
    }

    /**
     * @param {string} tag
     * @param {string} className
     * @param {string} text
     * @returns {HTMLElement}
     */
    function element(tag, className = "", text = "") {
        const node = document.createElement(tag);
        if (className) {
            node.className = className;
        }
        if (text) {
            node.textContent = text;
        }
        return node;
    }

    /**
     * @param {string} path
     * @param {RequestInit} options
     * @returns {Promise<unknown>}
     */
    async function request(path, options = {}) {
        if (!authToken) {
            throw new ApiError("请先验证访问令牌", 401);
        }
        const controller = new AbortController();
        const timeout = window.setTimeout(() => controller.abort(), 12000);
        const headers = new Headers(options.headers || {});
        headers.set("Accept", "application/json");
        headers.set("Authorization", `Bearer ${authToken}`);
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
                        throw new ApiError("服务器返回了无法读取的数据", response.status);
                    }
                }
            }
            if (!response.ok) {
                const code = String(payload?.error?.code || "");
                const fallback = String(payload?.error?.message || statusMessage(response.status));
                throw new ApiError(friendlyMessage(code, fallback), response.status, code);
            }
            return payload;
        } catch (error) {
            if (error instanceof ApiError) {
                throw error;
            }
            if (error?.name === "AbortError") {
                throw new ApiError("请求超时，请确认游戏仍在运行");
            }
            throw new ApiError("无法连接 Stowling，请确认本地服务正在运行");
        } finally {
            window.clearTimeout(timeout);
        }
    }

    /**
     * @param {number} status
     * @returns {string}
     */
    function statusMessage(status) {
        const messages = new Map([
            [400, "请求未通过校验"],
            [401, "令牌无效或已失效，请重新输入"],
            [403, "当前请求没有访问权限"],
            [404, "请求的资源不存在"],
            [405, "服务器不接受此操作"],
            [409, "配置或任务状态已发生变化"],
            [413, "请求内容过大"]
        ]);
        return messages.get(status) || "Stowling 暂时无法完成该操作";
    }

    /**
     * @param {string} code
     * @param {string} fallback
     * @returns {string}
     */
    function friendlyMessage(code, fallback) {
        const messages = new Map([
            ["config_revision_conflict", "配置已被外部修改，请重新读取后再保存"],
            ["active_task_conflict", "活动或暂停任务存在，请先取消任务或等待完成"],
            ["invalid_coordinate_points", "命名坐标未通过校验，请检查页面中的错误"],
            ["coordinate_point_in_use", "命名坐标仍被其他配置引用，暂不能删除"],
            ["invalid_pickup_config", "拾取配置未通过校验，请检查页面中的错误"],
            ["invalid_pickup_request", "所选拾取流程或区域不可用"],
            ["pickup_workflow_not_found", "配置中找不到这个拾取流程"],
            ["task_not_found", "该任务已不存在，请刷新任务列表"],
            ["container_not_found", "配置中找不到所选容器"],
            ["invalid_automation_workflow", "自动化任务未通过校验"],
            ["automation_workflow_not_found", "配置中找不到这个自动化任务"],
            ["automation_module_not_found", "任务步骤使用了不可用的机器人模块"]
        ]);
        return messages.get(code) || fallback;
    }

    /**
     * @param {string} text
     * @param {boolean} isError
     * @returns {void}
     */
    function showToast(text, isError = false) {
        const region = document.querySelector("#toast-region");
        if (!region) {
            return;
        }
        const toast = element("div", isError ? "toast error" : "toast", text);
        region.append(toast);
        window.setTimeout(() => toast.remove(), 5000);
    }

    /**
     * @param {unknown} error
     * @param {string} context
     * @returns {void}
     */
    function handleError(error, context) {
        if (error instanceof ApiError && error.status === 401) {
            clearAuthentication();
            showToast(`${context}：${error.message}`, true);
            return;
        }
        const message = error instanceof Error ? error.message : "未知错误";
        showToast(`${context}：${message}`, true);
    }

    /**
     * @param {string} text
     * @returns {void}
     */
    function setConnectionState(text) {
        const label = document.querySelector("#connection-label");
        const dot = document.querySelector("#connection-dot");
        if (label) {
            label.textContent = text;
        }
        dot?.classList.toggle("online", connected);
        dot?.classList.toggle("offline", !connected);
    }

    /**
     * @returns {void}
     */
    function mountNavigation() {
        const nav = document.querySelector("#app-nav");
        if (!nav) {
            return;
        }
        const activePage = String(document.body.dataset.page || "");
        for (const item of NAV_ITEMS) {
            const link = document.createElement("a");
            link.className = "workspace-nav-link";
            link.href = item.href;
            link.textContent = item.label;
            if (item.page === activePage) {
                link.classList.add("active");
                link.setAttribute("aria-current", "page");
            }
            nav.append(link);
        }
    }

    /**
     * @returns {void}
     */
    function mountAuthPanel() {
        const host = document.querySelector("#auth-shell");
        if (!host) {
            return;
        }
        host.classList.add("auth-shell");
        const panel = element("section", "panel auth-panel");
        panel.setAttribute("aria-labelledby", "auth-title");
        const heading = element("div", "panel-heading");
        const headingText = element("div");
        headingText.append(
            element("p", "eyebrow", "本地安全连接"),
            element("h2", "", "连接到 Stowling")
        );
        headingText.querySelector("h2").id = "auth-title";
        heading.append(headingText, element("span", "loopback-tag", "127.0.0.1"));
        const description = element(
            "p",
            "muted",
            "输入游戏实例中 config/stowling-api.token 的内容。令牌仅保存在当前标签页的"
                + "会话存储中。"
        );
        const form = element("form", "token-form");
        form.id = "auth-form";
        const label = element("label", "", "访问令牌（Bearer Token）");
        label.htmlFor = "token-input";
        const controls = element("div", "inline-control");
        const input = document.createElement("input");
        input.id = "token-input";
        input.type = "password";
        input.autocomplete = "off";
        input.spellcheck = false;
        input.placeholder = "粘贴访问令牌";
        input.required = true;
        const connect = element("button", "primary", "验证并连接");
        connect.id = "connect-button";
        connect.type = "submit";
        const clear = element("button", "secondary", "清除令牌");
        clear.id = "clear-token-button";
        clear.type = "button";
        controls.append(input, connect, clear);
        form.append(label, controls);
        panel.append(heading, description, form);
        host.append(panel);

        form.addEventListener("submit", (event) => {
            event.preventDefault();
            void connectToken(input.value);
        });
        clear.addEventListener("click", () => {
            clearAuthentication();
            showToast("访问令牌已清除");
            input.focus();
        });
    }

    /**
     * @param {string} candidate
     * @param {boolean} restored
     * @returns {Promise<void>}
     */
    async function connectToken(candidate, restored = false) {
        if (connectBusy) {
            return;
        }
        const token = candidate.trim();
        const input = document.querySelector("#token-input");
        const button = document.querySelector("#connect-button");
        if (!token) {
            showToast("请输入访问令牌", true);
            input?.focus();
            return;
        }
        connectBusy = true;
        if (button) {
            button.disabled = true;
            button.textContent = "正在验证…";
        }
        try {
            const previousToken = authToken;
            authToken = token;
            try {
                await request("/v1/status");
            } catch (error) {
                authToken = previousToken;
                throw error;
            }
            sessionStorage.setItem(TOKEN_KEY, token);
            connected = true;
            setConnectionState("已连接");
            document.querySelector("#workspace")?.setAttribute("aria-hidden", "false");
            document.querySelector("#auth-shell")?.classList.add("connected");
            if (input) {
                input.value = "";
            }
            for (const handler of connectedHandlers) {
                await handler();
            }
            if (!restored) {
                showToast("连接成功");
            }
        } catch (error) {
            connected = false;
            setConnectionState("未连接");
            handleError(error, "验证失败");
            input?.focus();
        } finally {
            connectBusy = false;
            if (button) {
                button.disabled = false;
                button.textContent = "验证并连接";
            }
        }
    }

    /**
     * @returns {void}
     */
    function clearAuthentication() {
        authToken = "";
        connected = false;
        sessionStorage.removeItem(TOKEN_KEY);
        setConnectionState("未连接");
        document.querySelector("#workspace")?.setAttribute("aria-hidden", "true");
        document.querySelector("#auth-shell")?.classList.remove("connected");
        const input = document.querySelector("#token-input");
        if (input) {
            input.value = "";
            input.focus();
        }
    }

    /**
     * @param {() => void | Promise<void>} handler
     * @returns {void}
     */
    function onConnected(handler) {
        connectedHandlers.add(handler);
    }

    /**
     * @returns {boolean}
     */
    function isConnected() {
        return connected;
    }

    /**
     * @param {string} page
     * @returns {void}
     */
    function mountPage(page) {
        document.body.dataset.page = page;
        mountNavigation();
        mountAuthPanel();
        setConnectionState("未连接");
        const token = sessionStorage.getItem(TOKEN_KEY);
        if (token) {
            void connectToken(token, true);
        } else {
            document.querySelector("#token-input")?.focus();
        }
    }

    /**
     * @param {object} value
     * @returns {object}
     */
    function clone(value) {
        return JSON.parse(JSON.stringify(value));
    }

    /**
     * @param {string} message
     * @returns {HTMLElement}
     */
    function emptyState(message) {
        return element("div", "empty-state", message);
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
     * @param {Map<string, string>} labels
     * @param {unknown} value
     * @param {string} fallback
     * @returns {string}
     */
    function translatedLabel(labels, value, fallback = "未知") {
        const raw = String(value ?? "").trim();
        if (!raw) {
            return fallback;
        }
        return labels.get(raw) || labels.get(raw.toUpperCase()) || raw;
    }

    /**
     * @param {unknown} value
     * @returns {string}
     */
    function conciseFailureReason(value) {
        const reason = String(value || "").trim();
        if (!reason) {
            return "";
        }
        const normalized = reason.toLowerCase();
        if (
            reason.includes("目标不可达")
            || reason.includes("寻路失败")
            || normalized.includes("path_failed")
        ) {
            return "目标不可达";
        }
        if (normalized.includes("cancel")) {
            return "任务已取消";
        }
        if (normalized.includes("disconnect")) {
            return "客户端已断开";
        }
        if (normalized.includes("not alive") || normalized.includes("dead")) {
            return "角色已死亡";
        }
        if (normalized.includes("dimension")) {
            return "维度不匹配";
        }
        if (normalized.includes("timeout")) {
            return "操作超时";
        }
        const clause = reason.split(/[：:；;，,。]/u)[0].trim();
        return clause.length <= 24 ? clause : `${clause.slice(0, 23)}…`;
    }

    /**
     * @param {unknown} value
     * @returns {string}
     */
    function taskStatusLabel(value) {
        return translatedLabel(TASK_STATUS_LABELS, value);
    }

    /**
     * @param {unknown} value
     * @returns {string}
     */
    function taskKindLabel(value) {
        return translatedLabel(TASK_KIND_LABELS, value, "任务");
    }

    /**
     * @param {unknown} value
     * @returns {string}
     */
    function taskStageLabel(value) {
        return translatedLabel(TASK_STAGE_LABELS, value);
    }

    /**
     * @param {unknown} value
     * @returns {string}
     */
    function moduleStateLabel(value) {
        return translatedLabel(MODULE_STATE_LABELS, value);
    }

    /**
     * @param {unknown} value
     * @returns {string}
     */
    function dimensionLabel(value) {
        return translatedLabel(DIMENSION_LABELS, value, "未选择");
    }

    /**
     * @param {unknown} value
     * @returns {string}
     */
    function containerTypeLabel(value) {
        return translatedLabel(CONTAINER_TYPE_LABELS, value);
    }

    /**
     * @param {unknown} value
     * @returns {string}
     */
    function cachePolicyLabel(value) {
        return translatedLabel(CACHE_POLICY_LABELS, value);
    }

    /**
     * @param {unknown} value
     * @returns {string}
     */
    function classificationTimingLabel(value) {
        return translatedLabel(CLASSIFICATION_TIMING_LABELS, value);
    }

    /**
     * @param {unknown} value
     * @returns {string}
     */
    function completionModeLabel(value) {
        return translatedLabel(COMPLETION_MODE_LABELS, value);
    }

    /**
     * @param {unknown} value
     * @returns {string}
     */
    function revisionLabel(value) {
        const raw = String(value ?? "");
        return `配置版本：${raw.slice(0, 12) || "--"}`;
    }

    window.Stowling = Object.freeze({
        ACTIVE_TASK_STATES,
        ApiError,
        cachePolicyLabel,
        classificationTimingLabel,
        clearAuthentication,
        clone,
        conciseFailureReason,
        completionModeLabel,
        containerTypeLabel,
        dimensionLabel,
        element,
        emptyState,
        formatDate,
        handleError,
        isConnected,
        moduleStateLabel,
        mountPage,
        onConnected,
        request,
        revisionLabel,
        showToast,
        taskKindLabel,
        taskStageLabel,
        taskStatusLabel
    });
})();
