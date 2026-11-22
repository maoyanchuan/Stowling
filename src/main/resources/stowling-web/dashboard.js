(() => {
    "use strict";

    const Stowling = window.Stowling;
    const elements = {
        refresh: document.querySelector("#refresh-button"),
        overview: document.querySelector(".dashboard-overview"),
        summary: document.querySelector("#dashboard-summary"),
        state: document.querySelector("#status-state"),
        observed: document.querySelector("#status-observed"),
        coordinateCount: document.querySelector("#coordinate-count"),
        taskCount: document.querySelector("#task-count"),
        triggerStatus: document.querySelector("#dashboard-trigger-status"),
        triggerCount: document.querySelector("#trigger-count"),
        recent: document.querySelector("#recent-tasks"),
        recentCount: document.querySelector("#recent-count"),
        errors: document.querySelector("#dashboard-errors")
    };
    let refreshBusy = false;

    const stateLabels = new Map([
        ["IDLE", "空闲"],
        ["RUNNING", "运行中"],
        ["CONFIG_INVALID", "配置异常"],
        ["QUEUED", "排队中"],
        ["WAITING", "等待中"],
        ["INTERRUPTING", "正在中断"],
        ["INTERRUPTION_FAILED", "中断收尾失败"],
        ["SUCCEEDED", "已完成"],
        ["FAILED", "失败"],
        ["PREEMPTED", "已中断"],
        ["CANCELLED", "已取消"],
        ["COOLDOWN", "冷却中"],
        ["ERROR", "异常"]
    ]);
    const triggerStateLabels = new Map([
        ["IDLE", "空闲"],
        ["RUNNING", "已入队"],
        ["COOLDOWN", "冷却中"],
        ["ERROR", "异常"]
    ]);

    /**
     * @param {unknown} state
     * @returns {string}
     */
    function stateLabel(state) {
        const raw = String(state || "").trim();
        if (!raw) {
            return "未知";
        }
        return stateLabels.get(raw.toUpperCase()) || raw;
    }

    /**
     * @param {unknown} state
     * @returns {string}
     */
    function triggerStateLabel(state) {
        const raw = String(state || "").trim();
        if (!raw) {
            return "未知";
        }
        return triggerStateLabels.get(raw.toUpperCase()) || raw;
    }

    /**
     * @param {unknown} state
     * @returns {string}
     */
    function stateTone(state) {
        const raw = String(state || "").toUpperCase();
        if (["RUNNING", "SUCCEEDED"].includes(raw)) {
            return "success";
        }
        if (["WAITING", "INTERRUPTING", "COOLDOWN"].includes(raw)) {
            return "warning";
        }
        if (["ERROR", "FAILED", "INTERRUPTION_FAILED", "CONFIG_INVALID"].includes(raw)) {
            return "danger";
        }
        return "neutral";
    }

    /**
     * @param {unknown} state
     * @returns {string}
     */
    function stateTagClass(state) {
        return `state-tag state-tag--${stateTone(state)}`;
    }

    /**
     * @param {string} message
     * @returns {HTMLLIElement}
     */
    function emptyListState(message) {
        const item = Stowling.element("li", "empty-state empty-state--row", message);
        return item;
    }

    /**
     * @param {unknown} value
     * @returns {HTMLTimeElement}
     */
    function timeElement(value) {
        const time = document.createElement("time");
        time.className = "activity-time";
        if (value) {
            time.dateTime = String(value);
        }
        time.textContent = Stowling.formatDate(value);
        return time;
    }

    /**
     * @param {unknown} status
     * @param {number} coordinateCount
     * @param {number} taskCount
     * @returns {void}
     */
    function renderSummary(status, coordinateCount, taskCount) {
        if (!elements.summary) {
            return;
        }
        const label = stateLabel(status?.state);
        elements.summary.textContent =
            `自动化${label}，已保存 ${coordinateCount} 个坐标和 ${taskCount} 个任务。`;
    }

    /**
     * @param {boolean} background
     * @returns {Promise<void>}
     */
    async function refresh(background = false) {
        if (refreshBusy) {
            return;
        }
        refreshBusy = true;
        if (!background) {
            elements.refresh.disabled = true;
            elements.refresh.textContent = "同步中…";
            elements.overview?.setAttribute("aria-busy", "true");
            elements.errors.replaceChildren();
        }
        try {
            const [status, coordinates, tasks, runs] = await Promise.all([
                Stowling.request("/v1/status"),
                Stowling.request("/v1/coordinates"),
                Stowling.request("/v1/tasks"),
                Stowling.request("/v1/task-runs")
            ]);
            const coordinateCount =
                Array.isArray(coordinates?.coordinates)
                    ? coordinates.coordinates.length
                    : 0;
            const taskCount = Array.isArray(tasks?.tasks) ? tasks.tasks.length : 0;
            elements.state.textContent = stateLabel(status?.state || "IDLE");
            elements.state.dataset.tone = stateTone(status?.state || "IDLE");
            elements.observed.textContent = Stowling.formatDate(status?.observedAt);
            elements.coordinateCount.textContent = String(coordinateCount);
            elements.taskCount.textContent = String(taskCount);
            renderSummary(status, coordinateCount, taskCount);
            renderTriggerStatuses(
                Array.isArray(runs?.triggers) ? runs.triggers : []
            );
            renderRuns(Array.isArray(runs?.runs) ? runs.runs : []);
            elements.errors.replaceChildren();
        } catch (error) {
            const message = error instanceof Error ? error.message : "未知错误";
            const staleMessage = `同步失败，正在显示上次数据。${message}`;
            if (elements.errors.textContent !== staleMessage) {
                elements.errors.textContent = staleMessage;
            }
            const unauthorized =
                error instanceof Stowling.ApiError && error.status === 401;
            if (!background || unauthorized) {
                Stowling.handleError(error, "刷新状态失败");
            }
        } finally {
            refreshBusy = false;
            if (!background) {
                elements.refresh.disabled = false;
                elements.refresh.textContent = "立即同步";
                elements.overview?.setAttribute("aria-busy", "false");
            }
        }
    }

    /**
     * @param {Array<object>} triggers
     * @returns {void}
     */
    function renderTriggerStatuses(triggers) {
        elements.triggerStatus.replaceChildren();
        elements.triggerCount.textContent = String(triggers.length);
        if (triggers.length === 0) {
            elements.triggerStatus.append(emptyListState(
                "还没有后台触发器。可在任务管理中按条件添加。"
            ));
            return;
        }
        for (const trigger of triggers) {
            const row = Stowling.element("li", "dashboard-row trigger-row");
            const heading = Stowling.element("div", "dashboard-row-heading");
            heading.append(
                Stowling.element("strong", "", String(trigger.triggerName || "触发器")),
                Stowling.element(
                    "span",
                    stateTagClass(trigger.state),
                    triggerStateLabel(trigger.state)
                )
            );
            const target = String(trigger.targetTaskName || "目标任务");
            const message = String(trigger.message || "暂无补充信息");
            row.append(
                heading,
                Stowling.element(
                    "p",
                    "dashboard-row-detail",
                    `${target} · ${message}`
                )
            );
            elements.triggerStatus.append(row);
        }
    }

    /**
     * @param {Array<object>} runs
     * @returns {void}
     */
    function renderRuns(runs) {
        elements.recent.replaceChildren();
        const visible = runs.filter((run) => run.state !== "QUEUED");
        if (visible.length === 0) {
            elements.recentCount.textContent = "0";
            elements.recent.append(emptyListState(
                "当前没有运行记录。启动任务后，最近状态会显示在这里。"
            ));
            return;
        }
        const displayed = visible.slice(0, 8);
        elements.recentCount.textContent = String(displayed.length);
        for (const run of displayed) {
            const row = Stowling.element("li", "dashboard-row activity-row");
            const heading = Stowling.element("div", "activity-heading");
            heading.append(
                Stowling.element("strong", "", String(run.taskName || "任务")),
                Stowling.element(
                    "span",
                    stateTagClass(run.state),
                    stateLabel(run.state)
                )
            );
            row.append(
                heading,
                Stowling.element(
                    "p",
                    "activity-message",
                    String(run.message || "没有补充说明")
                ),
                timeElement(run.updatedAt)
            );
            elements.recent.append(row);
        }
    }

    elements.refresh.addEventListener("click", () => void refresh());
    Stowling.onConnected(() => refresh());
    window.setInterval(() => {
        if (Stowling.isConnected() && document.visibilityState !== "hidden") {
            void refresh(true);
        }
    }, 5000);
    document.addEventListener("visibilitychange", () => {
        if (document.visibilityState === "visible" && Stowling.isConnected()) {
            void refresh(true);
        }
    });
    Stowling.mountPage("dashboard");
})();
