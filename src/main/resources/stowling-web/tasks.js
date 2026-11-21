(() => {
    "use strict";

    const Stowling = window.Stowling;
    const Model = window.StowlingTaskModel;
    const Monitor = window.StowlingTaskMonitorModel;
    const Move = window.StowlingMoveTaskModel;
    const Coordinates = window.StowlingCoordinateModel;
    const cleanup = window.StowlingTaskCleanupSettings.create(
        document.querySelector("#move-cleanup-settings"));
    const taskLogs = Monitor.createLogBuffer(300);
    const state = {
        triggerStatuses: [],
        modules: [],
        runs: [],
        queue: [],
        runRequestId: 0,
        lastRunResponseId: 0,
        moveRevision: "",
        moveTasks: [],
        autoTaskCount: 0,
        autoEatTaskCount: 0,
        materialTaskCount: 0,
        materialDraftActive: false,
        autoDraftActive: false,
        autoEatDraftActive: false,
        moveAllTasks: [],
        coordinates: [],
        regions: [],
        moveDraft: Move.newDraft(),
        moveFormOpen: false,
        moveDraftActive: false,
        moveResourcesReady: false,
        moveLoading: false,
        moveBusy: false,
        moveDirty: false
    };

    const elements = {
        moveForm: document.querySelector("#move-task-form"),
        moveFields: document.querySelector("#move-task-fields"),
        moveDraftCard: document.querySelector("#move-task-draft"),
        moveDraftToggle: document.querySelector("#toggle-move-draft"),
        moveDraftBody: document.querySelector("#move-draft-body"),
        moveCount: document.querySelector("#move-task-count"),
        movePlaceholder: document.querySelector("#move-task-placeholder"),
        moveName: document.querySelector("#move-task-name"),
        moveSources: [...document.querySelectorAll("[data-move-source]")],
        moveManual: document.querySelector("#move-manual-target"),
        moveSaved: document.querySelector("#move-saved-target"),
        moveX: document.querySelector("#move-target-x"),
        moveY: document.querySelector("#move-target-y"),
        moveZ: document.querySelector("#move-target-z"),
        moveCoordinate: document.querySelector("#move-saved-coordinate"),
        moveNote: document.querySelector("#move-target-note"),
        movePriority: document.querySelector("#move-priority"),
        moveMessage: document.querySelector("#move-task-message"),
        moveResourcesMessage: document.querySelector("#move-resources-message"),
        newMove: document.querySelector("#new-move-task"),
        moveList: document.querySelector("#move-task-list"),
        moveEditorRows: document.querySelector("#task-editor-move-rows"),
        createMenu: document.querySelector("#new-task-menu"),
        monitorSummary: document.querySelector("#task-monitor-summary"),
        refreshMove: document.querySelector("#refresh-move-tasks"),
        removeMove: document.querySelector("#delete-move-task"),
        runtime: document.querySelector("#runtime-list"),
        queueList: document.querySelector("#task-queue-list"),
        refreshRuns: document.querySelector("#refresh-runs-button"),
        queueCount: document.querySelector("#task-queue-count"),
        monitorMessage: document.querySelector("#task-monitor-message"),
        logList: document.querySelector("#task-log-list"),
        logCount: document.querySelector("#task-log-count"),
        exportLogs: document.querySelector("#export-task-logs"),
        clearLogs: document.querySelector("#clear-task-logs"),
        showRunDetails: document.querySelector("#show-run-details"),
        closeRunDetails: document.querySelector("#close-run-details"),
        runDetails: document.querySelector("#task-run-details")
    };

    const moveRows = new Map();
    let activeEditor = "";
    let lastQueue = "";
    const taskDraftChecks = [() => state.moveDirty];

    /** @param {string} kind @returns {void} */
    function openEditor(kind) {
        elements.createMenu.open = false;
        const editor = document.querySelector("#task-editor-" + kind);
        if (activeEditor !== kind) {
            if (activeEditor) document.querySelector("#task-editor-" + activeEditor).hidden = true;
            activeEditor = kind;
            editor.hidden = false;
            document.dispatchEvent(new CustomEvent("stowling:task-editor-change", {detail: kind}));
        }
        if (window.matchMedia?.("(max-width: 780px)")?.matches) {
            queueMicrotask(() => editor.scrollIntoView({block: "nearest"}));
        }
    }

    /** @param {() => boolean} hasChanges @returns {void} */
    function watchDraft(hasChanges) {
        taskDraftChecks.push(hasChanges);
    }

    window.StowlingTaskWorkbench = Object.freeze({openEditor, watchDraft});

    for (const link of document.querySelectorAll("[data-warehouse-link]")) {
        link.addEventListener("click", (event) => {
            if (event.ctrlKey || event.metaKey || event.shiftKey) return;
            if (taskDraftChecks.some((check) => check())
                    && !window.confirm("当前任务有未保存修改，仍要前往机器人仓库吗？")) {
                event.preventDefault();
            }
        });
    }

    /**
     * @returns {Promise<void>}
     */
    async function loadMoveResources() {
        if (state.moveBusy || state.moveLoading) {
            return;
        }
        state.moveLoading = true;
        updateMoveControls();
        elements.moveResourcesMessage.textContent = "正在读取任务和坐标…";
        elements.moveResourcesMessage.classList.remove("error");
        try {
            const [tasks, coordinates, regions] = await Promise.all([
                Stowling.request("/v1/tasks"),
                Stowling.request("/v1/coordinates"),
                Stowling.request("/v1/regions")
            ]);
            state.moveRevision = String(tasks?.revision || "");
            const allTasks = Array.isArray(tasks?.tasks)
                ? tasks.tasks.map(Model.normalizeTask) : [];
            state.moveAllTasks = allTasks;
            state.moveTasks = allTasks.filter(Move.isMoveTask);
            state.coordinates = Coordinates.normalizeCoordinates(coordinates).coordinates;
            state.regions = regions.regions || [];
            cleanup.resources(state.coordinates, state.regions);
            state.moveResourcesReady = true;
            if (state.moveDraft.id && !state.moveDirty) {
                const refreshed = state.moveTasks.find(
                    (task) => task.id === state.moveDraft.id
                );
                if (refreshed) {
                    state.moveDraft = Move.draftFromTask(refreshed);
                } else {
                    state.moveDraft = Move.newDraft(state.moveAllTasks);
                    state.moveDraftActive = false;
                    state.moveFormOpen = false;
                }
            }
            syncMoveForm();
            renderMoveTaskList();
            elements.moveResourcesMessage.textContent = "";
        } catch (error) {
            state.moveResourcesReady = false;
            elements.moveResourcesMessage.textContent = "读取失败，请点击刷新重试";
            elements.moveResourcesMessage.classList.add("error");
            Stowling.handleError(error, "读取移动任务和坐标失败");
        } finally {
            state.moveLoading = false;
            updateMoveControls();
        }
    }

    /**
     * @returns {void}
     */
    function renderMoveCoordinates() {
        const selectedId = state.moveDraft.coordinateId;
        elements.moveCoordinate.replaceChildren();
        const placeholder = Stowling.element("option", "", state.coordinates.length
            ? "请选择已保存坐标" : "暂无坐标，请先在坐标页面添加");
        placeholder.value = "";
        elements.moveCoordinate.append(placeholder);
        for (const coordinate of state.coordinates) {
            const label = `${coordinate.name} · ${coordinate.x}, ${coordinate.y}, ${coordinate.z}`;
            const option = Stowling.element("option", "", label);
            option.value = coordinate.id;
            elements.moveCoordinate.append(option);
        }
        if (selectedId && !state.coordinates.some((point) => point.id === selectedId)) {
            const missing = Stowling.element("option", "", "原坐标已不存在，请重新选择");
            missing.value = selectedId;
            missing.disabled = true;
            elements.moveCoordinate.append(missing);
        }
        elements.moveCoordinate.value = selectedId;
    }

    /**
     * @returns {void}
     */
    function renderMoveTaskList() {
        // Keep the one editor alive while rebuilding its surrounding task rows.
        elements.moveDraftBody.append(elements.moveForm);
        elements.moveList.replaceChildren();
        elements.moveEditorRows.replaceChildren();
        moveRows.clear();
        for (const task of state.moveTasks) {
            const draft = Move.draftFromTask(task);
            const card = Stowling.element("article", "move-saved-task");
            card.dataset.taskId = task.id;
            const button = Stowling.element("button", "move-task-summary");
            button.type = "button";
            button.dataset.taskId = task.id;
            const body = Stowling.element("div", "move-task-body");
            body.id = `move-task-body-${task.id}`;
            button.setAttribute("aria-controls", body.id);
            const kind = Stowling.element("span", "move-task-kind", "↗");
            kind.setAttribute("aria-hidden", "true");
            button.append(kind);
            const overview = Stowling.element("span", "move-task-overview");
            overview.append(Stowling.element("strong", "", task.name));
            const coordinate = state.coordinates.find((point) => point.id === draft.coordinateId);
            const target = draft.source === "saved"
                ? (coordinate?.name || "坐标已不存在")
                : `${draft.x}, ${draft.y}, ${draft.z}`;
            overview.append(Stowling.element("span", "move-task-target", target));
            button.append(overview);
            button.append(Stowling.element(
                "span", "move-task-priority", Model.priorityLabel(task.priority)
            ));
            const status = Stowling.element("span", "move-task-state", "已保存");
            button.append(status);
            const chevron = Stowling.element("span", "move-task-chevron", "⌄");
            chevron.setAttribute("aria-hidden", "true");
            button.append(chevron);
            button.addEventListener("click", () => selectMoveTask(task));
            if (state.moveDraft.id === task.id) {
                body.append(elements.moveForm);
            }
            const header = Stowling.element("div", "task-card-header");
            const run = Stowling.element("button", "secondary move-task-run", "加入队列");
            run.type = "button";
            run.setAttribute("aria-label", `加入队列：${task.name}`);
            run.addEventListener("click", () => void queueMoveTask(task.id));
            header.append(button, run);
            card.append(header);
            elements.moveEditorRows.append(body);
            elements.moveList.append(card);
            moveRows.set(task.id, { button, body, status, run });
        }
        updateMoveControls();
    }

    /**
     * @returns {void}
     */
    function syncMoveDisclosure() {
        const newDraft = state.moveDraftActive && !state.moveDraft.id;
        elements.moveDraftCard.hidden = !newDraft;
        void window.StowlingSettings.reveal(elements.moveDraftBody,
            newDraft && state.moveFormOpen);
        elements.moveDraftToggle.setAttribute("aria-expanded", String(
            newDraft && state.moveFormOpen
        ));
        elements.moveForm.hidden = false;
        const savedCount = state.moveTasks.length + state.autoTaskCount + state.autoEatTaskCount
            + state.materialTaskCount;
        elements.movePlaceholder.hidden = savedCount > 0 || newDraft || state.autoDraftActive
            || state.autoEatDraftActive || state.materialDraftActive;
        elements.moveCount.textContent = String(savedCount);
        for (const [id, row] of moveRows) {
            const selected = state.moveDraft.id === id;
            const expanded = selected && state.moveFormOpen;
            row.button.setAttribute("aria-expanded", String(expanded));
            void window.StowlingSettings.reveal(row.body, expanded);
            row.status.textContent = selected && state.moveDirty ? "未保存修改" : "已保存";
        }
    }

    /**
     * @param {object | null} task
     * @returns {void}
     */
    function selectMoveTask(task = null) {
        if (state.moveBusy || state.moveLoading || !state.moveResourcesReady
                || !Stowling.isConnected()) {
            return;
        }
        if (task && state.moveDraft.id === task.id) {
            state.moveFormOpen = !state.moveFormOpen;
            if (state.moveFormOpen) openEditor("move");
            syncMoveDisclosure();
            return;
        }
        if (state.moveDirty && !window.confirm("当前修改尚未保存，切换任务会丢弃修改。继续？")) {
            return;
        }
        openEditor("move");
        state.moveDraft = task
            ? Move.draftFromTask(task) : Move.newDraft(state.moveAllTasks);
        state.moveDirty = false;
        state.moveDraftActive = !task;
        state.moveFormOpen = true;
        showMoveMessage(task ? "已保存，可直接加入队列" : "新任务，配置目标后保存或运行");
        syncMoveForm();
        renderMoveTaskList();
        if (task) {
            moveRows.get(task.id)?.button.focus();
        } else {
            elements.moveDraftToggle.focus();
        }
    }

    /**
     * @returns {void}
     */
    function toggleMoveDraft() {
        if (state.moveBusy || state.moveLoading || !state.moveResourcesReady
                || !Stowling.isConnected()) {
            return;
        }
        state.moveFormOpen = !state.moveFormOpen;
        if (state.moveFormOpen) openEditor("move");
        syncMoveDisclosure();
    }

    /**
     * @returns {void}
     */
    function syncMoveForm() {
        const draft = state.moveDraft;
        cleanup.set(draft.cleanupPolicy || {});
        elements.moveName.value = draft.name;
        elements.moveX.value = draft.x.replace(/^\+/, "");
        elements.moveY.value = draft.y.replace(/^\+/, "");
        elements.moveZ.value = draft.z.replace(/^\+/, "");
        elements.movePriority.value = draft.priority;
        renderMoveCoordinates();
        updateMoveTarget();
        updateMoveControls();
    }

    /**
     * @returns {void}
     */
    function readMoveForm() {
        state.moveDraft = {
            ...state.moveDraft,
            name: elements.moveName.value,
            priority: elements.movePriority.value,
            x: elements.moveX.value,
            y: elements.moveY.value,
            z: elements.moveZ.value,
            coordinateId: elements.moveCoordinate.value,
            cleanupPolicy: cleanup.read()
        };
    }

    /**
     * @returns {void}
     */
    function updateMoveTarget() {
        state.moveDraft.source = "saved";
        const manual = state.moveDraft.source === "manual";
        elements.moveManual.hidden = !manual;
        elements.moveSaved.hidden = manual;
        for (const button of elements.moveSources) {
            button.setAttribute("aria-pressed", String(button.dataset.moveSource
                === state.moveDraft.source));
        }
        if (manual) {
            elements.moveNote.textContent = "精确到达目标方块；手动坐标在当前维度生效。";
            return;
        }
        const coordinate = state.coordinates.find(
            (point) => point.id === state.moveDraft.coordinateId
        );
        if (!coordinate) {
            elements.moveNote.textContent = state.coordinates.length
                ? "选择坐标页面中已保存的坐标点。" : "暂无坐标，请先到坐标页面添加。";
            return;
        }
        const dimension = coordinate.dimension ? ` · ${coordinate.dimension}` : " · 当前维度";
        elements.moveNote.textContent = `${coordinate.x}, ${coordinate.y}, ${coordinate.z}`
            + dimension + " · 精确到达目标方块";
    }

    /**
     * @returns {void}
     */
    function updateMoveControls() {
        const disabled = state.moveBusy || state.moveLoading
            || !state.moveResourcesReady || !Stowling.isConnected();
        elements.moveFields.disabled = disabled;
        elements.newMove.disabled = disabled;
        elements.refreshMove.disabled = state.moveBusy || state.moveLoading
            || !Stowling.isConnected();
        elements.moveDraftToggle.disabled = disabled;
        for (const [id, row] of moveRows) {
            const unsaved = state.moveDraft.id === id && state.moveDirty;
            row.button.disabled = disabled;
            row.run.disabled = disabled || unsaved;
            row.run.title = unsaved ? "先保存当前修改，再加入队列" : "加入运行队列";
        }
        elements.moveCoordinate.disabled = state.moveDraft.source !== "saved"
            || state.coordinates.length === 0;
        for (const input of [elements.moveX, elements.moveY, elements.moveZ]) {
            input.disabled = state.moveDraft.source !== "manual";
        }
        elements.removeMove.hidden = !state.moveDraft.id;
        elements.removeMove.disabled = disabled;
        syncMoveDisclosure();
    }

    /**
     * @param {string} text
     * @param {boolean} [error]
     * @returns {void}
     */
    function showMoveMessage(text, error = false) {
        elements.moveMessage.textContent = text;
        elements.moveMessage.classList.toggle("error", error);
    }

    /**
     * @param {unknown} error
     * @returns {boolean}
     */
    function isTaskNameConflict(error) {
        const message = String(error?.message ?? "").trim();
        return /^task name must be unique:\s+.+$/.test(message);
    }

    /**
     * @param {object} response
     * @param {string} existingId
     * @returns {void}
     */
    function applyMoveSave(response, existingId) {
        const id = existingId || String(response?.taskId || "");
        const allTasks = Array.isArray(response?.tasks)
            ? response.tasks.map(Model.normalizeTask) : [];
        const tasks = allTasks.filter(Move.isMoveTask);
        const saved = tasks.find((task) => task.id === id);
        if (!id || !saved) {
            throw new Error("保存响应缺少任务编号或内容，请刷新任务列表");
        }
        state.moveRevision = String(response.revision || "");
        state.moveAllTasks = allTasks;
        state.moveTasks = tasks;
        state.moveDraft = Move.draftFromTask(saved);
        state.moveDirty = false;
        state.moveDraftActive = false;
        syncMoveForm();
        renderMoveTaskList();
    }

    /** @returns {Promise<void>} */
    async function saveMoveTask() {
        if (state.moveBusy || state.moveLoading || !state.moveResourcesReady
                || !Stowling.isConnected() || !state.moveFormOpen) {
            return;
        }
        readMoveForm();
        const original = state.moveDraft.id
            ? state.moveTasks.find((task) => task.id === state.moveDraft.id) : null;
        if (state.moveDraft.id && !original) {
            showMoveMessage("任务已不存在或流程已更改，请重新选择任务。", true);
            return;
        }
        let task;
        try {
            task = Move.createTask(
                state.moveDraft,
                state.coordinates,
                original,
                state.moveAllTasks
            );
        } catch (error) {
            showMoveMessage(String(error.message || error), true);
            return;
        }
        state.moveBusy = true;
        updateMoveControls();
        try {
            if (!task.id || state.moveDirty) {
                showMoveMessage("正在保存任务…");
                const payload = Model.payloadTask(task);
                const response = await Stowling.request(task.id
                    ? `/v1/tasks/${encodeURIComponent(task.id)}` : "/v1/tasks", {
                    method: task.id ? "PUT" : "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify(task.id
                        ? { ...payload, revision: state.moveRevision } : payload)
                });
                applyMoveSave(response, task.id);
                document.dispatchEvent(new Event("stowling:tasks-changed"));
            }
            showMoveMessage("任务已保存");
            Stowling.showToast("移动任务已保存");
        } catch (error) {
            if (isTaskNameConflict(error)) {
                showMoveMessage("任务名称已存在，请使用其他名称", true);
            } else {
                showMoveMessage("保存失败，请检查输入或刷新重试", true);
                Stowling.handleError(error, "保存移动任务失败");
            }
        } finally {
            state.moveBusy = false;
            updateMoveControls();
        }
    }

    /**
     * @param {string} id
     * @returns {Promise<void>}
     */
    async function queueMoveTask(id) {
        const row = moveRows.get(id);
        if (!row || row.run.disabled || !Stowling.isConnected()) return;
        state.moveBusy = true;
        updateMoveControls();
        try {
            const response = await Stowling.request(`/v1/tasks/${encodeURIComponent(id)}/runs`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({})
            });
            Stowling.showToast(response?.state === "QUEUED"
                ? "任务已加入队列" : "任务已开始运行");
            await loadRuns();
        } catch (error) {
            Stowling.handleError(error, "加入队列失败");
        } finally {
            state.moveBusy = false;
            updateMoveControls();
        }
    }

    /**
     * @returns {Promise<void>}
     */
    async function deleteMoveTask() {
        if (state.moveBusy || state.moveLoading || !state.moveResourcesReady
                || !Stowling.isConnected() || !state.moveFormOpen || !state.moveDraft.id) {
            return;
        }
        const original = state.moveTasks.find((task) => task.id === state.moveDraft.id);
        if (!original || !Move.isMoveTask(original)) {
            showMoveMessage("任务已不存在或流程已更改，请刷新任务列表。", true);
            return;
        }
        const warning = state.moveDirty ? "未保存的修改也会丢弃。" : "";
        if (!window.confirm(`删除移动任务“${original.name}”？${warning}`
                + "已排队或正在运行的任务不会被取消。")) {
            return;
        }
        state.moveBusy = true;
        updateMoveControls();
        let deleted = false;
        try {
            const response = await Stowling.request(
                `/v1/tasks/${encodeURIComponent(original.id)}`, {
                    method: "DELETE",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ revision: state.moveRevision })
                }
            );
            if (!Array.isArray(response?.tasks) || !response.revision
                    || response.tasks.some((task) => task.id === original.id)) {
                throw new Error("删除响应不完整，请刷新任务列表确认结果");
            }
            state.moveRevision = String(response.revision);
            state.moveAllTasks = response.tasks.map(Model.normalizeTask);
            state.moveTasks = state.moveAllTasks.filter(Move.isMoveTask);
            state.moveDraft = Move.newDraft(state.moveAllTasks);
            state.moveDirty = false;
            state.moveDraftActive = false;
            state.moveFormOpen = false;
            syncMoveForm();
            renderMoveTaskList();
            deleted = true;
            Stowling.showToast("移动任务已删除");
        } catch (error) {
            showMoveMessage(String(error.message || "删除失败，请刷新重试"), true);
            Stowling.handleError(error, "删除移动任务失败");
        } finally {
            state.moveBusy = false;
            updateMoveControls();
            if (deleted) {
                elements.newMove.focus();
                document.dispatchEvent(new Event("stowling:tasks-changed"));
            }
        }
    }

    /**
     * @returns {Promise<void>}
     */
    async function loadAll() {
        const requestId = ++state.runRequestId;
        try {
            const [modules, runs] = await Promise.all([
                Stowling.request("/v1/task-modules"),
                Stowling.request("/v1/task-runs")
            ]);
            state.modules = Array.isArray(modules?.modules) ? modules.modules : [];
            if (requestId <= state.lastRunResponseId) return;
            state.runs = Array.isArray(runs?.runs) ? runs.runs : [];
            state.queue = Array.isArray(runs?.queue) ? runs.queue : [];
            state.triggerStatuses = Array.isArray(runs?.triggers) ? runs.triggers : [];
            if (elements.runDetails.open) renderRuntime();
            renderQueue();
            appendMonitorLogs();
            showMonitorSync();
            state.lastRunResponseId = requestId;
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
            INTERRUPTING: "正在中断",
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
        const message = String(run.message || "")
            .replace("no marker container selected", "未选择标记容器（缺少前序选择结果）")
            .replace("no container selected", "未选择容器（缺少前序选择结果）")
            .replace("no storage target selected", "未选择存储位（缺少前序选择结果）")
            .replace(/第 ([\d.]+) 步（([^）]+)）/g, (match, path, moduleId) => {
                const module = state.modules.find((item) => item.id === moduleId);
                return module ? `第 ${path} 步（${module.name}）` : match;
            });
        if (run.state === "INTERRUPTING") {
            return message ? `${message}，队列暂停` : "正在执行中断收尾，队列暂停";
        }
        return run.state === "INTERRUPTION_FAILED"
            ? (message || "中断收尾失败")
                + "；队列暂停，请重试收尾，或确认导航停止并处理受管物品后跳过。"
                + "人工接手后也可强制停止。"
            : message;
    }

    /**
     * @returns {Promise<void>}
     */
    async function loadRuns() {
        const requestId = ++state.runRequestId;
        try {
            const response = await Stowling.request("/v1/task-runs");
            if (requestId <= state.lastRunResponseId) return;
            state.runs = Array.isArray(response?.runs) ? response.runs : [];
            state.queue = Array.isArray(response?.queue)
                ? response.queue
                : [];
            state.triggerStatuses = Array.isArray(response?.triggers)
                ? response.triggers
                : [];
            if (elements.runDetails.open) renderRuntime();
            renderQueue();
            appendMonitorLogs();
            showMonitorSync();
            state.lastRunResponseId = requestId;
        } catch (error) {
            if (requestId <= state.lastRunResponseId) return;
            elements.monitorMessage.textContent = "同步失败 · 显示上次状态";
            elements.monitorSummary.textContent = "同步失败 · 展开查看上次状态";
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
        card.append(footer);
        return card;
    }

    /**
     * @returns {void}
     */
    function renderQueue() {
        const scrollTop = elements.queueList.scrollTop;
        const entries = Monitor.queueEntries(
            state.runs, state.queue, state.triggerStatuses
        );
        elements.queueCount.textContent = String(entries.length);
        if (Stowling.isConnected()) {
            const active = entries.filter((entry) => entry.run && isActiveRun(entry.run)).length;
            const queued = entries.filter((entry) => entry.state === "QUEUED").length;
            elements.monitorSummary.textContent = active || queued
                ? "执行 " + active + " 项 · 排队 " + queued + " 项" : "无执行或排队任务";
        }
        const queue = JSON.stringify(entries.map((entry) => [
            entry.kind, entry.taskName, entry.state, entry.priority, entry.message,
            entry.run?.runId, entry.run?.state
        ]));
        if (queue === lastQueue) return;
        lastQueue = queue;
        elements.queueList.replaceChildren();
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
            if (entry.run && isActiveRun(entry.run)) {
                actions.append(forceStopButton(entry.run.runId));
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
     * @param {string} runId
     * @returns {HTMLButtonElement}
     */
    function forceStopButton(runId) {
        const button = railAction("强制停止", () => void forceStopRun(runId));
        button.classList.add("danger-action");
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
        elements.exportLogs.disabled = Monitor.exportRuns(state.runs).length === 0;
        const added = taskLogs.ingest(state.runs, state.queue);
        if (added > 0 || elements.logList.childElementCount === 0) {
            renderMonitorLogs();
        }
    }

    /**
     * @returns {void}
     */
    function renderMonitorLogs() {
        const { scrollTop, scrollHeight, clientHeight } = elements.logList;
        const followLatest = scrollHeight - clientHeight - scrollTop <= 1;
        const entries = taskLogs.entries();
        elements.logList.replaceChildren();
        elements.logCount.textContent = String(entries.length);
        elements.clearLogs.disabled = entries.length === 0;
        if (entries.length === 0) {
            elements.logList.append(Stowling.emptyState("暂无任务进度"));
        }
        for (const entry of entries) {
            const row = Stowling.element("article", "rail-log-entry");
            row.classList.toggle("is-error", [
                "FAILED", "INTERRUPTION_FAILED"
            ].includes(entry.state));
            const time = Stowling.element("time", "rail-log-time");
            time.dateTime = entry.time;
            time.textContent = new Date(entry.time).toLocaleTimeString(
                "zh-CN", { hour12: false }
            );
            const status = runStateLabel(entry.state);
            const heading = Stowling.element("div", "rail-log-heading");
            heading.append(time, Stowling.element("span", "rail-state", status));
            row.append(heading, Stowling.element("strong", "", entry.taskName));
            const message = runStateMessage(entry);
            if (message && message !== status) {
                row.append(Stowling.element("p", "rail-log-message", message));
            }
            if (entry.completedSteps !== undefined) {
                const progress = `${entry.completedSteps} / ${entry.totalSteps ?? "—"} 步`;
                row.append(Stowling.element("span", "rail-log-time", progress));
            }
            elements.logList.append(row);
        }
        elements.logList.scrollTop = followLatest ? elements.logList.scrollHeight : scrollTop;
    }

    /**
     * @returns {Promise<void>}
     */
    async function exportMonitorLogs() {
        const [run] = Monitor.exportRuns(state.runs);
        if (!run) {
            return;
        }
        const { runId } = run;
        let batch;
        try {
            batch = await Stowling.request(
                `/v1/task-diagnostics/run/${encodeURIComponent(runId)}`);
        } catch (error) {
            Stowling.handleError(error, "导出日志失败，请重试");
            return;
        }
        const output = Monitor.exportCurrentRun(batch, runId, run);
        const date = new Date().toISOString().replace(/[:.]/g, "-");
        const name = `stowling-task-log-${runId}-${date}`;
        downloadLog(new Blob([output], { type: "text/plain;charset=utf-8" }), `${name}.txt`);
    }

    /**
     * @param {Blob} blob
     * @param {string} name
     * @returns {void}
     */
    function downloadLog(blob, name) {
        const url = URL.createObjectURL(blob);
        const link = document.createElement("a");
        link.href = url;
        link.download = name;
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

    /**
     * @param {string} runId
     * @returns {Promise<void>}
     */
    async function forceStopRun(runId) {
        const confirmed = window.confirm(
            "强制停止将结束本次运行并跳过剩余收尾。请确认已接手背包、"
            + "光标上的物品和临时潜影盒。已发往服务器的操作仍可能生效。"
        );
        if (!confirmed) return;
        try {
            await Stowling.request(
                `/v1/task-runs/${encodeURIComponent(runId)}/force-stop`,
                { method: "POST" }
            );
            await loadRuns();
            Stowling.showToast("已请求强制停止");
        } catch (error) {
            Stowling.handleError(error, "强制停止任务失败");
        }
    }

    document.addEventListener("stowling:auto-task-count", (event) => {
        state.autoTaskCount = Number(event.detail?.count || 0);
        state.autoDraftActive = event.detail?.draftActive === true;
        syncMoveDisclosure();
    });
    document.addEventListener("stowling:auto-eat-task-count", (event) => {
        state.autoEatTaskCount = Number(event.detail?.count || 0);
        state.autoEatDraftActive = event.detail?.draftActive === true;
        syncMoveDisclosure();
    });
    document.addEventListener("stowling:material-task-count", (event) => {
        state.materialTaskCount = event.detail.count;
        state.materialDraftActive = event.detail.draftActive;
        syncMoveDisclosure();
    });
    document.addEventListener("stowling:task-editor-change", (event) => {
        if (event.detail !== "move" && state.moveFormOpen) {
            state.moveFormOpen = false;
            syncMoveDisclosure();
        }
    });
    document.addEventListener("stowling:material-tasks-changed", () => {
        void loadMoveResources();
        void loadRuns();
    });
    document.addEventListener("stowling:auto-eat-tasks-changed", () => {
        void loadMoveResources();
        void loadRuns();
    });
    document.addEventListener("stowling:auto-tasks-changed", () => {
        void loadMoveResources();
        void loadRuns();
    });
    document.addEventListener("stowling:monitor-refresh", () => void loadRuns());
    elements.newMove.addEventListener("click", () => selectMoveTask());
    elements.moveDraftToggle.addEventListener("click", toggleMoveDraft);
    elements.refreshMove.addEventListener("click", () => void loadMoveResources());
    elements.moveForm.addEventListener("input", () => {
        readMoveForm();
        state.moveDirty = true;
        showMoveMessage("未保存修改");
        updateMoveTarget();
        updateMoveControls();
    });
    for (const button of elements.moveSources) {
        button.addEventListener("click", () => {
            readMoveForm();
            if (state.moveDraft.source === button.dataset.moveSource) {
                return;
            }
            state.moveDraft.source = button.dataset.moveSource;
            state.moveDirty = true;
            showMoveMessage("未保存修改");
            updateMoveTarget();
            updateMoveControls();
        });
    }
    elements.removeMove.addEventListener("click", () => void deleteMoveTask());
    elements.moveForm.addEventListener("submit", (event) => {
        event.preventDefault();
        void saveMoveTask();
    });
    elements.refreshRuns.addEventListener("click", () => void loadRuns());
    elements.exportLogs.addEventListener("click", exportMonitorLogs);
    elements.clearLogs.addEventListener("click", () => {
        state.lastRunResponseId = state.runRequestId;
        taskLogs.clear();
        renderMonitorLogs();
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
    if (window.location?.hash === "#task-monitor") {
        document.querySelector("#task-monitor").open = true;
    }
    renderQueue();
    renderMonitorLogs();
    syncMoveForm();
    Stowling.onConnected(async () => {
        await Promise.all([loadAll(), loadMoveResources()]);
    });
    window.setInterval(() => {
        updateMoveControls();
        if (Stowling.isConnected()) {
            void loadRuns();
        } else {
            elements.monitorMessage.textContent = "未连接 · 等待同步";
            elements.monitorSummary.textContent = "等待连接";
            elements.monitorMessage.classList.add("is-stale");
        }
    }, 3000);
    Stowling.mountPage("tasks");
})();
