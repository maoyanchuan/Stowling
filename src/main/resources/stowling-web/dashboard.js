(() => {
    "use strict";

    const Stowling = window.Stowling;
    const STATUS_LABELS = new Map([
        ["PENDING", "等待中"],
        ["RUNNING", "运行中"],
        ["WAITING", "等待条件"],
        ["PAUSED", "已暂停"],
        ["SUCCEEDED", "已完成"],
        ["FAILED", "失败"],
        ["CANCELLED", "已取消"]
    ]);

    const elements = {
        refresh: document.querySelector("#refresh-button"),
        pause: document.querySelector("#pause-button"),
        resume: document.querySelector("#resume-button"),
        reload: document.querySelector("#reload-button"),
        controlMessage: document.querySelector("#control-message"),
        state: document.querySelector("#status-state"),
        paused: document.querySelector("#status-paused"),
        observed: document.querySelector("#status-observed"),
        activeCount: document.querySelector("#active-task-count"),
        recentTasks: document.querySelector("#recent-tasks"),
        coordinateCount: document.querySelector("#coordinate-count"),
        automationCount: document.querySelector("#automation-count"),
        containerCount: document.querySelector("#container-count"),
        pickupCount: document.querySelector("#pickup-count"),
        errors: document.querySelector("#dashboard-errors")
    };

    /**
     * @returns {Promise<void>}
     */
    async function loadStatus() {
        const status = await Stowling.request("/v1/status");
        elements.state.textContent = String(status?.state || "未知");
        elements.paused.textContent = status?.paused ? "是" : "否";
        elements.observed.textContent = Stowling.formatDate(status?.observedAt);
    }

    /**
     * @returns {Promise<Array<object>>}
     */
    async function loadTasks() {
        const tasks = await Stowling.request("/v1/tasks");
        const values = Array.isArray(tasks) ? tasks : [];
        const active = values.filter((task) => Stowling.ACTIVE_TASK_STATES.has(task.status));
        elements.activeCount.textContent = String(active.length);
        renderRecentTasks(values);
        return values;
    }

    /**
     * @returns {Promise<void>}
     */
    async function loadResources() {
        const results = await Promise.allSettled([
            Stowling.request("/v1/config/coordinate-points"),
            Stowling.request("/v1/config/automation-workflows"),
            Stowling.request("/v1/containers"),
            Stowling.request("/v1/pickup-workflows")
        ]);
        setCount(
            elements.coordinateCount,
            results[0],
            (value) => value?.coordinatePoints?.length
        );
        setCount(
            elements.automationCount,
            results[1],
            (value) => value?.automationWorkflows?.length
        );
        setCount(elements.containerCount, results[2], (value) => value?.length);
        setCount(elements.pickupCount, results[3], (value) => value?.length);
        const failures = results
            .map((result, index) => [result, index])
            .filter(([result]) => result.status === "rejected")
            .filter(([, index]) => ![1, 2, 3].includes(index)
                || resultLooksUnavailable(results[index]));
        if (failures.length > 0) {
            showErrors([
                "部分资源无法读取，请确认客户端已进入世界并加载最新后端。"
            ]);
        }
    }

    /**
     * @param {PromiseSettledResult<unknown>} result
     * @returns {boolean}
     */
    function resultLooksUnavailable(result) {
        return result.status === "rejected"
            && !(result.reason instanceof Stowling.ApiError)
            || result.status === "rejected" && result.reason.status !== 404;
    }

    /**
     * @param {HTMLElement} target
     * @param {PromiseSettledResult<unknown>} result
     * @param {(value: any) => number | undefined} count
     * @returns {void}
     */
    function setCount(target, result, count) {
        if (result.status === "fulfilled") {
            target.textContent = String(count(result.value) ?? 0);
            return;
        }
        target.textContent = result.reason?.status === 404 ? "未启用" : "--";
    }

    /**
     * @param {Array<object>} tasks
     * @returns {void}
     */
    function renderRecentTasks(tasks) {
        elements.recentTasks.replaceChildren();
        if (tasks.length === 0) {
            elements.recentTasks.append(Stowling.emptyState("还没有运行记录"));
            return;
        }
        const sorted = [...tasks].sort((left, right) => {
            return String(right.updatedAt || "").localeCompare(String(left.updatedAt || ""));
        });
        for (const task of sorted.slice(0, 5)) {
            const card = Stowling.element("article", "task-card");
            const content = Stowling.element("div");
            const title = Stowling.element(
                "div",
                "task-title",
                String(task.workflowId || task.kind || "任务")
            );
            title.append(statusBadge(task.status));
            const meta = Stowling.element("div", "task-meta");
            meta.append(
                metaText("任务 ID", String(task.id || "未知")),
                metaText("阶段", String(task.stage || task.automationState || "未知")),
                metaText("更新", Stowling.formatDate(task.updatedAt))
            );
            content.append(title, meta);
            card.append(content);
            elements.recentTasks.append(card);
        }
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
     * @param {string} value
     * @returns {HTMLSpanElement}
     */
    function statusBadge(value) {
        const status = String(value || "UNKNOWN");
        const badge = Stowling.element("span", "badge", STATUS_LABELS.get(status) || status);
        if (["FAILED", "CANCELLED"].includes(status)) {
            badge.classList.add("danger");
        } else if (["PAUSED", "WAITING", "UNKNOWN"].includes(status)) {
            badge.classList.add("warning");
        }
        return badge;
    }

    /**
     * @param {Array<string>} messages
     * @returns {void}
     */
    function showErrors(messages) {
        elements.errors.replaceChildren();
        if (messages.length === 0) {
            return;
        }
        const list = Stowling.element("ul");
        for (const message of messages) {
            list.append(Stowling.element("li", "", message));
        }
        elements.errors.append(list);
    }

    /**
     * @returns {Promise<void>}
     */
    async function refreshDashboard() {
        elements.refresh.disabled = true;
        showErrors([]);
        const results = await Promise.allSettled([
            loadStatus(),
            loadTasks(),
            loadResources()
        ]);
        const failure = results.find((result) => result.status === "rejected");
        if (failure) {
            Stowling.handleError(failure.reason, "刷新概览失败");
        }
        elements.refresh.disabled = false;
    }

    /**
     * @param {string} path
     * @param {string} label
     * @returns {Promise<void>}
     */
    async function runControl(path, label) {
        elements.controlMessage.textContent = `正在${label}…`;
        try {
            await Stowling.request(path, { method: "POST" });
            Stowling.showToast(`${label}成功`);
            elements.controlMessage.textContent = `${label}成功`;
            await loadStatus();
        } catch (error) {
            elements.controlMessage.textContent = `${label}失败`;
            Stowling.handleError(error, `${label}失败`);
        }
    }

    elements.refresh.addEventListener("click", () => {
        void refreshDashboard();
    });
    elements.pause.addEventListener("click", () => {
        void runControl("/v1/pause", "暂停机器人");
    });
    elements.resume.addEventListener("click", () => {
        void runControl("/v1/resume", "恢复机器人");
    });
    elements.reload.addEventListener("click", async () => {
        elements.reload.disabled = true;
        try {
            await Stowling.request("/v1/config/reload", { method: "POST" });
            Stowling.showToast("配置已重载");
            await refreshDashboard();
        } catch (error) {
            Stowling.handleError(error, "重载配置失败");
        } finally {
            elements.reload.disabled = false;
        }
    });

    window.setInterval(() => {
        if (document.visibilityState === "visible" && Stowling.isConnected()) {
            void refreshDashboard();
        }
    }, 10000);

    Stowling.onConnected(refreshDashboard);
    Stowling.mountPage("dashboard");
})();
