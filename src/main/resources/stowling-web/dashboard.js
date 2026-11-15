(() => {
    "use strict";

    const Stowling = window.Stowling;
    const elements = {
        refresh: document.querySelector("#refresh-button"),
        state: document.querySelector("#status-state"),
        observed: document.querySelector("#status-observed"),
        coordinateCount: document.querySelector("#coordinate-count"),
        taskCount: document.querySelector("#task-count"),
        recent: document.querySelector("#recent-tasks"),
        errors: document.querySelector("#dashboard-errors")
    };

    /**
     * @returns {Promise<void>}
     */
    async function refresh() {
        elements.errors.replaceChildren();
        try {
            const [status, coordinates, tasks, runs] = await Promise.all([
                Stowling.request("/v1/status"),
                Stowling.request("/v1/coordinates"),
                Stowling.request("/v1/tasks"),
                Stowling.request("/v1/task-runs")
            ]);
            elements.state.textContent = String(status?.state || "IDLE");
            elements.observed.textContent = Stowling.formatDate(status?.observedAt);
            elements.coordinateCount.textContent =
                String(Array.isArray(coordinates?.coordinates)
                    ? coordinates.coordinates.length
                    : 0);
            elements.taskCount.textContent =
                String(Array.isArray(tasks?.tasks) ? tasks.tasks.length : 0);
            renderRuns(Array.isArray(runs?.runs) ? runs.runs : []);
        } catch (error) {
            Stowling.handleError(error, "刷新状态失败");
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
            elements.recent.append(Stowling.emptyState("当前没有运行记录"));
            return;
        }
        for (const run of visible.slice(0, 8)) {
            const card = Stowling.element("article", "task-card");
            card.append(
                Stowling.element("strong", "", String(run.taskName || "任务")),
                Stowling.element(
                    "span",
                    "muted compact-help",
                    `${run.state} · ${run.message || ""}`
                ),
                Stowling.element(
                    "span",
                    "muted compact-help",
                    Stowling.formatDate(run.updatedAt)
                )
            );
            elements.recent.append(card);
        }
    }

    elements.refresh.addEventListener("click", () => void refresh());
    Stowling.onConnected(() => refresh());
    window.setInterval(() => {
        if (Stowling.isConnected()) {
            void refresh();
        }
    }, 5000);
    Stowling.mountPage("dashboard");
})();
