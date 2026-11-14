(() => {
    "use strict";

    const Stowling = window.Stowling;
    const RUNNING_TASK_STATES = new Set(["RUNNING"]);
    const QUEUED_TASK_STATES = new Set(["PENDING", "WAITING"]);
    const INTERRUPTED_TASK_STATES = new Set(["PAUSED", "FAILED", "CANCELLED"]);
    const GROUP_TASK_LIMIT = 4;

    const elements = {
        refresh: document.querySelector("#refresh-button"),
        pause: document.querySelector("#pause-button"),
        resume: document.querySelector("#resume-button"),
        cancelTask: document.querySelector("#cancel-task-button"),
        controlTaskSelect: document.querySelector("#control-task-select"),
        reload: document.querySelector("#reload-button"),
        controlMessage: document.querySelector("#control-message"),
        state: document.querySelector("#status-state"),
        paused: document.querySelector("#status-paused"),
        observed: document.querySelector("#status-observed"),
        runningCount: document.querySelector("#running-task-count"),
        runningList: document.querySelector("#running-task-list"),
        queuedCount: document.querySelector("#queued-task-count"),
        queuedList: document.querySelector("#queued-task-list"),
        interruptedCount: document.querySelector("#interrupted-task-count"),
        interruptedList: document.querySelector("#interrupted-task-list"),
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
        elements.state.textContent = Stowling.taskStatusLabel(status?.state);
        elements.paused.textContent = status?.paused ? "是" : "否";
        elements.observed.textContent = Stowling.formatDate(status?.observedAt);
    }

    /**
     * @returns {Promise<Array<object>>}
     */
    async function loadTasks() {
        const tasks = await Stowling.request("/v1/tasks");
        const values = Array.isArray(tasks) ? tasks : [];
        renderTaskOverview(values);
        renderControlTaskOptions(values);
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
    function renderTaskOverview(tasks) {
        renderTaskGroup(
            elements.runningCount,
            elements.runningList,
            tasks.filter((task) => RUNNING_TASK_STATES.has(task.status))
        );
        renderTaskGroup(
            elements.queuedCount,
            elements.queuedList,
            tasks.filter((task) => QUEUED_TASK_STATES.has(task.status))
        );
        renderTaskGroup(
            elements.interruptedCount,
            elements.interruptedList,
            tasks.filter((task) => INTERRUPTED_TASK_STATES.has(task.status))
        );
    }

    /**
     * @param {HTMLElement} countTarget
     * @param {HTMLElement} listTarget
     * @param {Array<object>} tasks
     * @returns {void}
     */
    function renderTaskGroup(countTarget, listTarget, tasks) {
        const sorted = sortTasks(tasks);
        countTarget.textContent = String(sorted.length);
        listTarget.replaceChildren();
        if (sorted.length === 0) {
            listTarget.append(Stowling.emptyState("暂无任务"));
            return;
        }
        for (const task of sorted.slice(0, GROUP_TASK_LIMIT)) {
            listTarget.append(renderCompactTask(task));
        }
        if (sorted.length > GROUP_TASK_LIMIT) {
            const more = Stowling.element(
                "a",
                "compact-task-more",
                `另有 ${sorted.length - GROUP_TASK_LIMIT} 个任务`
            );
            more.href = "/tasks.html";
            listTarget.append(more);
        }
    }

    /**
     * @param {object} task
     * @returns {HTMLElement}
     */
    function renderCompactTask(task) {
        const card = Stowling.element("article", "compact-task-card");
        const title = Stowling.element("div", "compact-task-title");
        title.append(
            Stowling.element("strong", "compact-task-name", taskTitle(task)),
            statusBadge(task.status)
        );
        const meta = Stowling.element("div", "compact-task-meta");
        meta.append(metaText("步骤", String(task.currentStepId || task.stage || "-")));
        const failure = Stowling.conciseFailureReason(task.failureReason);
        if (failure) {
            const reason = metaText("原因", failure);
            reason.title = String(task.failureReason);
            meta.append(reason);
        }
        card.append(title, meta);
        return card;
    }

    /**
     * @param {Array<object>} tasks
     * @returns {void}
     */
    function renderControlTaskOptions(tasks) {
        const selected = elements.controlTaskSelect.value;
        const active = sortTasks(
            tasks.filter((task) => Stowling.ACTIVE_TASK_STATES.has(task.status))
        );
        elements.controlTaskSelect.replaceChildren();
        if (active.length === 0) {
            elements.controlTaskSelect.append(new Option("暂无活动任务", ""));
            elements.controlTaskSelect.disabled = true;
            updateCancelTaskButton();
            return;
        }
        elements.controlTaskSelect.append(new Option("请选择任务", ""));
        for (const task of active) {
            const status = Stowling.taskStatusLabel(task.status);
            const label = `${taskTitle(task)} · ${status}`;
            elements.controlTaskSelect.append(new Option(label, String(task.id)));
        }
        elements.controlTaskSelect.disabled = false;
        elements.controlTaskSelect.value = active.some(
            (task) => String(task.id) === selected
        ) ? selected : "";
        updateCancelTaskButton();
    }

    /**
     * @returns {void}
     */
    function updateCancelTaskButton() {
        elements.cancelTask.disabled = !elements.controlTaskSelect.value;
    }

    /**
     * @param {Array<object>} tasks
     * @returns {Array<object>}
     */
    function sortTasks(tasks) {
        return [...tasks].sort((left, right) => {
            const rightUpdated = String(right.updatedAt || "");
            const leftUpdated = String(left.updatedAt || "");
            return rightUpdated.localeCompare(leftUpdated);
        });
    }

    /**
     * @param {object} task
     * @returns {string}
     */
    function taskTitle(task) {
        return String(task.workflowId || Stowling.taskKindLabel(task.kind));
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
        const sorted = sortTasks(tasks);
        for (const task of sorted.slice(0, 5)) {
            const card = Stowling.element("article", "task-card");
            const content = Stowling.element("div");
            const title = Stowling.element(
                "div",
                "task-title",
                taskTitle(task)
            );
            title.append(statusBadge(task.status));
            const meta = Stowling.element("div", "task-meta");
            meta.append(
                metaText("任务 ID", String(task.id || "未知")),
                metaText(
                    "阶段",
                    Stowling.taskStageLabel(task.stage || task.automationState)
                ),
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
        const badge = Stowling.element(
            "span",
            "badge",
            Stowling.taskStatusLabel(status)
        );
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
            await Promise.allSettled([loadStatus(), loadTasks()]);
        } catch (error) {
            elements.controlMessage.textContent = `${label}失败`;
            Stowling.handleError(error, `${label}失败`);
        }
    }

    /**
     * @returns {Promise<void>}
     */
    async function cancelSelectedTask() {
        const taskId = elements.controlTaskSelect.value;
        if (!taskId) {
            return;
        }
        elements.cancelTask.disabled = true;
        elements.controlMessage.textContent = "正在取消所选任务…";
        try {
            await Stowling.request(
                `/v1/tasks/${encodeURIComponent(taskId)}/cancel`,
                { method: "POST" }
            );
            Stowling.showToast("任务已取消");
            elements.controlMessage.textContent = "所选任务已取消";
            await refreshDashboard();
        } catch (error) {
            elements.controlMessage.textContent = "取消任务失败";
            Stowling.handleError(error, "取消任务失败");
        } finally {
            updateCancelTaskButton();
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
    elements.cancelTask.addEventListener("click", () => {
        void cancelSelectedTask();
    });
    elements.controlTaskSelect.addEventListener("change", () => {
        elements.controlMessage.textContent = "";
        updateCancelTaskButton();
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
    }, 5000);

    Stowling.onConnected(refreshDashboard);
    Stowling.mountPage("dashboard");
})();
