(() => {
    "use strict";

    const Stowling = window.Stowling;
    const Model = window.StowlingTaskModel;
    const Monitor = window.StowlingTaskMonitorModel;
    const taskLogs = Monitor.createLogBuffer(300);
    const state = {
        triggerStatuses: [],
        modules: [],
        runs: [],
        queue: [],
        logScrollPaused: false
    };

    const elements = {
        runtime: document.querySelector("#runtime-list"),
        queueList: document.querySelector("#task-queue-list"),
        refreshRuns: document.querySelector("#refresh-runs-button"),
        queueCount: document.querySelector("#task-queue-count"),
        monitorMessage: document.querySelector("#task-monitor-message"),
        logList: document.querySelector("#task-log-list"),
        logCount: document.querySelector("#task-log-count"),
        exportLogs: document.querySelector("#export-task-logs"),
        clearLogs: document.querySelector("#clear-task-logs"),
        pauseLogScroll: document.querySelector("#pause-task-log-scroll"),
        showRunDetails: document.querySelector("#show-run-details"),
        closeRunDetails: document.querySelector("#close-run-details"),
        runDetails: document.querySelector("#task-run-details")
    };

    /**
     * @returns {Promise<void>}
     */
    async function loadAll() {
        try {
            const [modules, runs] = await Promise.all([
                Stowling.request("/v1/task-modules"),
                Stowling.request("/v1/task-runs")
            ]);
            state.modules = Array.isArray(modules?.modules) ? modules.modules : [];
            state.runs = Array.isArray(runs?.runs) ? runs.runs : [];
            state.queue = Array.isArray(runs?.queue) ? runs.queue : [];
            state.triggerStatuses = Array.isArray(runs?.triggers) ? runs.triggers : [];
            renderRuntime();
            renderQueue();
            appendMonitorLogs();
            showMonitorSync();
        } catch (error) {
            Stowling.handleError(error, "读取运行状态失败");
        }
    }

    /**
     * @param {object} run
     * @returns {boolean}
     */
    function isActiveRun(run) {
        return [
            "RUNNING",
            "WAITING",
            "INTERRUPTING",
            "INTERRUPTION_FAILED"
        ].includes(run.state);
    }

    /**
     * @param {string} state
     * @returns {string}
     */
    function runStateLabel(state) {
        const labels = {
            QUEUED: "排队中",
            RUNNING: "运行中",
            WAITING: "等待中",
            INTERRUPTING: "正在执行中断收尾",
            INTERRUPTION_FAILED: "中断收尾失败，队列暂停",
            SUCCEEDED: "已完成",
            FAILED: "失败",
            PREEMPTED: "已中断",
            CANCELLED: "已取消"
        };
        return labels[state] || String(state || "");
    }

    /**
     * @param {object} run
     * @returns {string}
     */
    function runStateMessage(run) {
        if (run.state === "INTERRUPTING") {
            return "正在执行中断收尾，队列暂停";
        }
        const message = String(run.message || "")
            .replace("no marker container selected", "未选择标记容器（缺少前序选择结果）")
            .replace("no container selected", "未选择容器（缺少前序选择结果）")
            .replace("no storage target selected", "未选择存储位（缺少前序选择结果）")
            .replace(/第 ([\d.]+) 步（([^）]+)）/g, (match, path, moduleId) => {
                const module = state.modules.find((item) => item.id === moduleId);
                return module ? `第 ${path} 步（${module.name}）` : match;
            });
        return run.state === "INTERRUPTION_FAILED"
            ? (message || "中断收尾失败")
                + "；队列暂停，请重试收尾，或确认导航停止并处理受管物品后跳过"
            : message;
    }

    /**
     * @returns {Promise<void>}
     */
    async function loadRuns() {
        try {
            const response = await Stowling.request("/v1/task-runs");
            state.runs = Array.isArray(response?.runs) ? response.runs : [];
            state.queue = Array.isArray(response?.queue)
                ? response.queue
                : [];
            state.triggerStatuses = Array.isArray(response?.triggers)
                ? response.triggers
                : [];
            renderRuntime();
            renderQueue();
            appendMonitorLogs();
            showMonitorSync();
        } catch (error) {
            elements.monitorMessage.textContent = "同步失败 · 显示上次状态";
            elements.monitorMessage.classList.add("is-stale");
            Stowling.handleError(error, "读取运行状态失败");
        }
    }

    /**
     * @returns {void}
     */
    function renderRuntime() {
        const wasOpen = elements.runtime.querySelector(".task-run-history")?.open;
        elements.runtime.replaceChildren();
        const runs = state.runs.filter((run) => run.state !== "QUEUED");
        const activeRuns = runs.filter(isActiveRun);
        const historyRuns = runs.filter((run) => !isActiveRun(run));
        const current = Stowling.element("section", "task-run-group");
        current.append(Stowling.element("h3", "task-run-group-title", "当前执行"));
        if (activeRuns.length === 0) {
            current.append(Stowling.emptyState("当前空闲，没有正在执行的任务"));
        } else {
            for (const run of activeRuns) {
                current.append(runtimeCard(run));
            }
        }
        elements.runtime.append(current);
        if (historyRuns.length > 0) {
            const history = Stowling.element("details", "task-run-history");
            history.open = wasOpen ?? activeRuns.length === 0;
            history.append(Stowling.element(
                "summary", "", `最近记录 · ${historyRuns.length} 次`
            ));
            const records = Stowling.element("div", "task-run-group");
            for (const run of historyRuns) {
                records.append(runtimeCard(run));
            }
            history.append(records);
            elements.runtime.append(history);
        }
    }

    /**
     * @param {object} run
     * @returns {HTMLElement}
     */
    function runtimeCard(run) {
        const failed = run.state === "FAILED" || run.state === "INTERRUPTION_FAILED";
        const card = Stowling.element("article", "task-card task-run-card");
        card.classList.toggle("is-failed", failed);
        const heading = Stowling.element("div", "task-run-heading");
        heading.append(
            Stowling.element("strong", "", run.taskName),
            Stowling.element("span", "task-state-tag", runStateLabel(run.state))
        );
        const meta = Stowling.element("p", "task-run-meta");
        meta.append(Stowling.element(
            "span", "", `本次优先级：${Model.priorityLabel(run.priority)}`
        ));
        meta.append(Stowling.element(
            "span", "", `已完成 ${run.completedSteps} / ${run.totalSteps} 个根步骤`
        ));
        const message = Stowling.element("p", "task-run-message", runStateMessage(run));
        if (failed) {
            message.prepend(Stowling.element("strong", "", "失败原因："));
        }
        card.append(heading, meta, message);
        if (failed && /no marker container selected|未选择标记容器/.test(run.message || "")) {
            card.append(Stowling.element(
                "p", "task-reference-hint",
                "请检查目标引用：固定缓存箱选它的已保存坐标；"
                    + "使用标记容器则需先扫描分类标记并选择标记容器。"
            ));
        }
        const footer = Stowling.element("div", "task-run-footer");
        footer.append(Stowling.element(
            "span", "muted compact-help", Stowling.formatDate(run.updatedAt)
        ));
        const actions = Stowling.element("div", "action-row");
        if (run.state === "INTERRUPTION_FAILED") {
            const retry = Stowling.element("button", "primary compact", "重试收尾");
            retry.type = "button";
            retry.addEventListener("click", () => { void retryInterruption(run.runId); });
            const skip = Stowling.element("button", "secondary compact", "尝试跳过收尾");
            skip.type = "button";
            skip.addEventListener("click", () => { void skipInterruption(run.runId); });
            actions.append(retry, skip);
        } else if (isActiveRun(run)) {
            const cancel = Stowling.element("button", "secondary compact", "取消任务");
            cancel.type = "button";
            cancel.addEventListener("click", () => { void cancelRun(run.runId); });
            actions.append(cancel);
        }
        footer.append(actions);
        card.append(footer);
        return card;
    }

    /**
     * @returns {void}
     */
    function renderQueue() {
        const scrollTop = elements.queueList.scrollTop;
        elements.queueList.replaceChildren();
        const entries = Monitor.queueEntries(
            state.runs, state.queue, state.triggerStatuses
        );
        elements.queueCount.textContent = String(entries.length);
        if (entries.length === 0) {
            elements.queueList.append(Stowling.emptyState("暂无排队或监听任务"));
        }
        for (const entry of entries) {
            const card = Stowling.element("article", "rail-queue-card");
            card.dataset.priority = entry.priority;
            card.dataset.kind = entry.kind;
            const heading = Stowling.element("div", "rail-card-heading");
            const name = Stowling.element("strong", "rail-task-name", entry.taskName);
            heading.append(name, Stowling.element(
                "span", "rail-priority", Model.priorityLabel(entry.priority)
            ));
            const status = entry.kind === "trigger"
                ? listeningStateLabel(entry.state)
                : runStateLabel(entry.state);
            card.append(heading, Stowling.element("span", "rail-state", status));
            card.append(Stowling.element("p", "rail-card-message", entry.message));
            const actions = Stowling.element("div", "rail-card-actions");
            if (entry.run?.state === "INTERRUPTION_FAILED") {
                actions.append(
                    railAction("重试收尾", () => void retryInterruption(entry.run.runId)),
                    railAction("尝试跳过", () => void skipInterruption(entry.run.runId))
                );
            } else if (entry.run) {
                actions.append(railAction(
                    entry.state === "QUEUED" ? "取消排队" : "取消任务",
                    () => void cancelRun(entry.run.runId)
                ));
            }
            if (actions.childElementCount > 0) {
                card.append(actions);
            }
            elements.queueList.append(card);
        }
        elements.queueList.scrollTop = scrollTop;
    }

    /**
     * @param {string} label
     * @param {() => void} action
     * @returns {HTMLButtonElement}
     */
    function railAction(label, action) {
        const button = Stowling.element("button", "ghost compact", label);
        button.type = "button";
        button.addEventListener("click", action);
        return button;
    }

    /**
     * @param {string} value
     * @returns {string}
     */
    function listeningStateLabel(value) {
        const labels = {
            IDLE: "监听中", RUNNING: "监听 · 已触发",
            COOLDOWN: "监听 · 冷却中", ERROR: "监听异常"
        };
        return labels[value] || "监听中";
    }

    /**
     * @returns {void}
     */
    function showMonitorSync() {
        elements.monitorMessage.textContent = "已同步 · "
            + new Date().toLocaleTimeString("zh-CN", { hour12: false });
        elements.monitorMessage.classList.remove("is-stale");
    }

    /**
     * @returns {void}
     */
    function appendMonitorLogs() {
        const added = taskLogs.ingest(
            state.runs, state.queue, state.triggerStatuses, new Date().toISOString()
        );
        if (added > 0 || elements.logList.childElementCount === 0) {
            renderMonitorLogs();
        }
    }

    /**
     * @returns {void}
     */
    function renderMonitorLogs() {
        const scrollTop = elements.logList.scrollTop;
        const entries = taskLogs.entries();
        elements.logList.replaceChildren();
        elements.logCount.textContent = String(entries.length);
        elements.exportLogs.disabled = entries.length === 0;
        elements.clearLogs.disabled = entries.length === 0;
        if (entries.length === 0) {
            elements.logList.append(Stowling.emptyState("暂无任务日志"));
        }
        for (const entry of entries) {
            const row = Stowling.element("article", "rail-log-entry");
            row.classList.toggle("is-error", [
                "ERROR", "FAILED", "INTERRUPTION_FAILED"
            ].includes(entry.state));
            const time = Stowling.element("time", "rail-log-time");
            time.dateTime = entry.time;
            time.textContent = new Date(entry.time).toLocaleTimeString(
                "zh-CN", { hour12: false }
            );
            const status = entry.kind === "trigger"
                ? listeningStateLabel(entry.state)
                : runStateLabel(entry.state);
            const heading = Stowling.element("div", "rail-log-heading");
            heading.append(time, Stowling.element("span", "rail-state", status));
            row.append(heading, Stowling.element("strong", "", entry.taskName));
            row.append(Stowling.element("p", "rail-log-message", entry.message));
            const progress = entry.completedSteps !== undefined
                ? ` · ${entry.completedSteps} / ${entry.totalSteps ?? "—"} 步` : "";
            row.append(Stowling.element(
                "span", "rail-log-time", Model.priorityLabel(entry.priority) + progress
            ));
            elements.logList.append(row);
        }
        elements.logList.scrollTop = state.logScrollPaused
            ? scrollTop : elements.logList.scrollHeight;
    }

    /**
     * @returns {void}
     */
    function exportMonitorLogs() {
        const blob = new Blob([taskLogs.exportText()], { type: "text/plain;charset=utf-8" });
        const url = URL.createObjectURL(blob);
        const link = document.createElement("a");
        link.href = url;
        const date = new Date().toISOString().replace(/[:.]/g, "-");
        link.download = `stowling-task-log-${date}.txt`;
        document.body.append(link);
        link.click();
        link.remove();
        window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    }

    /**
     * @param {string} runId
     * @returns {Promise<void>}
     */
    async function retryInterruption(runId) {
        try {
            await Stowling.request(
                `/v1/task-runs/${encodeURIComponent(runId)}`
                    + "/retry-interruption",
                { method: "POST" }
            );
            await loadRuns();
            Stowling.showToast("正在重新执行中断收尾");
        } catch (error) {
            Stowling.handleError(error, "重试中断收尾失败");
        }
    }

    /**
     * @param {string} runId
     * @returns {Promise<void>}
     */
    async function skipInterruption(runId) {
        const confirmed = window.confirm(
            "只有确认导航已停止，且任务管理物品已处理完毕，"
            + "后端才允许跳过中断收尾。跳过不会再次自动收尾。"
            + "确认现在尝试跳过？"
        );
        if (!confirmed) {
            return;
        }
        try {
            await Stowling.request(
                `/v1/task-runs/${encodeURIComponent(runId)}`
                    + "/skip-interruption",
                { method: "POST" }
            );
            await loadRuns();
            Stowling.showToast("已跳过中断收尾");
        } catch (error) {
            Stowling.handleError(error, "跳过中断收尾失败");
        }
    }

    /**
     * @param {string} runId
     * @returns {Promise<void>}
     */
    async function cancelRun(runId) {
        try {
            await Stowling.request(
                `/v1/task-runs/${encodeURIComponent(runId)}/cancel`,
                { method: "POST" }
            );
            await loadRuns();
        } catch (error) {
            Stowling.handleError(error, "取消任务失败");
        }
    }

    elements.refreshRuns.addEventListener("click", () => void loadRuns());
    elements.exportLogs.addEventListener("click", exportMonitorLogs);
    elements.clearLogs.addEventListener("click", () => {
        taskLogs.clear();
        renderMonitorLogs();
    });
    elements.pauseLogScroll.addEventListener("click", () => {
        state.logScrollPaused = !state.logScrollPaused;
        elements.pauseLogScroll.setAttribute("aria-pressed", String(state.logScrollPaused));
        elements.pauseLogScroll.textContent = state.logScrollPaused
            ? "继续滚动" : "暂停滚动";
        if (!state.logScrollPaused) {
            elements.logList.scrollTop = elements.logList.scrollHeight;
        }
    });
    elements.showRunDetails.addEventListener("click", () => {
        renderRuntime();
        elements.runDetails.showModal();
    });
    elements.closeRunDetails.addEventListener("click", () => elements.runDetails.close());
    elements.runDetails.addEventListener("click", (event) => {
        if (event.target !== elements.runDetails) {
            return;
        }
        const bounds = elements.runDetails.getBoundingClientRect();
        if (event.clientX < bounds.left || event.clientX > bounds.right
                || event.clientY < bounds.top || event.clientY > bounds.bottom) {
            elements.runDetails.close();
        }
    });
    renderQueue();
    renderMonitorLogs();
    Stowling.onConnected(() => loadAll());
    window.setInterval(() => {
        if (Stowling.isConnected()) {
            void loadRuns();
        } else {
            elements.monitorMessage.textContent = "未连接 · 等待同步";
            elements.monitorMessage.classList.add("is-stale");
        }
    }, 3000);
    Stowling.mountPage("tasks");
})();
