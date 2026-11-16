(() => {
    "use strict";

    const Stowling = window.Stowling;
    const Model = window.StowlingTaskModel;
    const Monitor = window.StowlingTaskMonitorModel;
    const taskLogs = Monitor.createLogBuffer(300);
    const FOLDER_FILTER_ALL = "ALL";
    const FOLDER_FILTER_UNGROUPED = "UNGROUPED";
    const state = {
        revision: "",
        tasks: [],
        folders: [],
        triggers: [],
        triggerStatuses: [],
        modules: [],
        selectedId: "",
        runTaskId: "",
        runs: [],
        queue: [],
        query: "",
        taskFilter: "ALL",
        folderFilter: FOLDER_FILTER_ALL,
        folderEditorId: "",
        folderBusy: false,
        logScrollPaused: false
    };

    const elements = {
        taskFilters: [...document.querySelectorAll("[data-task-filter]")],
        selectionPanel: document.querySelector(".task-selection-panel"),
        taskFolders: document.querySelector("#task-folder-list"),
        newFolder: document.querySelector("#new-folder-button"),
        folderForm: document.querySelector("#task-folder-form"),
        folderFormLabel: document.querySelector("#task-folder-form-label"),
        folderName: document.querySelector("#task-folder-name"),
        folderErrors: document.querySelector("#task-folder-errors"),
        saveFolder: document.querySelector("#save-folder-button"),
        cancelFolder: document.querySelector("#cancel-folder-button"),
        deleteFolder: document.querySelector("#delete-folder-button"),
        search: document.querySelector("#task-search"),
        taskCount: document.querySelector("#task-list-count"),
        list: document.querySelector("#task-list"),
        runSelect: document.querySelector("#run-task-select"),
        runPriority: document.querySelector("#run-priority-select"),
        run: document.querySelector("#run-task-button"),
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
     * @returns {void}
     */
    function updateTaskFilters() {
        for (const button of elements.taskFilters) {
            const active = button.dataset.taskFilter === state.taskFilter;
            button.setAttribute("aria-pressed", String(active));
        }
    }

    /**
     * @param {object} value
     * @returns {{id: string, name: string}}
     */
    function normalizeFolder(value) {
        return {
            id: String(value?.id || ""),
            name: String(value?.name || "").trim()
        };
    }

    /**
     * @param {Array<object>} folders
     * @returns {Array<object>}
     */
    function normalizeFolders(folders) {
        return Array.isArray(folders)
            ? folders.map(normalizeFolder).filter((folder) => folder.id)
            : [];
    }

    /**
     * @param {object} task
     * @returns {boolean}
     */
    function matchesTaskFolder(task) {
        if (state.folderFilter === FOLDER_FILTER_ALL) {
            return true;
        }
        if (state.folderFilter === FOLDER_FILTER_UNGROUPED) {
            return !task.folderId;
        }
        return task.folderId === state.folderFilter;
    }

    /**
     * @param {string} filter
     * @returns {number}
     */
    function folderTaskCount(filter) {
        return state.tasks.filter((task) => {
            if (filter === FOLDER_FILTER_ALL) {
                return true;
            }
            if (filter === FOLDER_FILTER_UNGROUPED) {
                return !task.folderId;
            }
            return task.folderId === filter;
        }).length;
    }

    /**
     * @param {string} filter
     * @param {string} label
     * @param {number} count
     * @param {string} manageId
     * @returns {HTMLElement}
     */
    function folderRow(filter, label, count, manageId = "") {
        const row = Stowling.element("div", "task-folder-row");
        const button = Stowling.element("button", "task-folder-filter");
        button.type = "button";
        button.setAttribute(
            "aria-pressed",
            String(state.folderFilter === filter)
        );
        button.append(
            Stowling.element("span", "task-folder-name", label),
            Stowling.element("span", "task-folder-count", String(count))
        );
        button.addEventListener("click", () => {
            state.folderFilter = filter;
            renderTaskList();
        });
        row.append(button);
        if (!manageId) {
            return row;
        }
        const folder = state.folders.find((entry) => entry.id === manageId);
        const manage = Stowling.element(
            "button",
            "task-folder-manage",
            "⋯"
        );
        manage.type = "button";
        manage.title = `管理文件夹“${folder?.name || label}”`;
        manage.setAttribute("aria-label", manage.title);
        manage.addEventListener("click", () => openFolderForm(manageId));
        row.append(manage);
        return row;
    }

    /**
     * @returns {void}
     */
    function renderTaskFolders() {
        elements.taskFolders.replaceChildren(
            folderRow(
                FOLDER_FILTER_ALL,
                "全部任务",
                folderTaskCount(FOLDER_FILTER_ALL)
            ),
            folderRow(
                FOLDER_FILTER_UNGROUPED,
                "未分组",
                folderTaskCount(FOLDER_FILTER_UNGROUPED)
            )
        );
        const folders = [...state.folders].sort((left, right) => (
            left.name.localeCompare(right.name, "zh-CN")
        ));
        for (const folder of folders) {
            elements.taskFolders.append(folderRow(
                folder.id,
                folder.name,
                folderTaskCount(folder.id),
                folder.id
            ));
        }
    }

    /**
     * @returns {void}
     */
    function updateFolderControls() {
        const editing = Boolean(state.folderEditorId);
        elements.folderFormLabel.textContent = editing
            ? "重命名文件夹"
            : "新建文件夹";
        elements.deleteFolder.hidden = !editing;
        elements.newFolder.disabled = state.folderBusy;
        elements.saveFolder.disabled = state.folderBusy;
        elements.cancelFolder.disabled = state.folderBusy;
        elements.deleteFolder.disabled = state.folderBusy;
        elements.folderName.disabled = state.folderBusy;
    }

    /**
     * @param {string} folderId
     * @returns {void}
     */
    function openFolderForm(folderId = "") {
        const folder = state.folders.find((entry) => entry.id === folderId);
        state.folderEditorId = folder?.id || "";
        elements.folderName.value = folder?.name || "";
        elements.folderErrors.replaceChildren();
        elements.folderForm.hidden = false;
        updateFolderControls();
        elements.folderName.focus();
        elements.folderName.select();
    }

    /**
     * @returns {void}
     */
    function closeFolderForm() {
        state.folderEditorId = "";
        elements.folderForm.hidden = true;
        elements.folderName.value = "";
        elements.folderErrors.replaceChildren();
        updateFolderControls();
    }

    /**
     * @param {unknown} response
     * @returns {void}
     */
    function applyFolderResponse(response) {
        state.revision = String(response?.revision || state.revision);
        state.folders = normalizeFolders(response?.folders);
        renderTaskList();
    }

    /**
     * @returns {Array<string>}
     */
    function folderValidationErrors() {
        const errors = [];
        const name = elements.folderName.value.trim();
        if (!name) {
            errors.push("文件夹名称不能为空");
        }
        if (name.length > 80) {
            errors.push("文件夹名称不能超过 80 个字符");
        }
        return errors;
    }

    /**
     * @returns {Promise<void>}
     */
    async function saveFolder() {
        const errors = folderValidationErrors();
        elements.folderErrors.replaceChildren();
        if (errors.length > 0) {
            for (const error of errors) {
                elements.folderErrors.append(
                    Stowling.element("p", "", error)
                );
            }
            return;
        }
        const name = elements.folderName.value.trim();
        const editing = Boolean(state.folderEditorId);
        state.folderBusy = true;
        updateFolderControls();
        try {
            let response;
            if (editing) {
                response = await Stowling.request(
                    `/v1/task-folders/${encodeURIComponent(
                        state.folderEditorId
                    )}`,
                    {
                        method: "PUT",
                        headers: { "Content-Type": "application/json" },
                        body: JSON.stringify({
                            revision: state.revision,
                            name
                        })
                    }
                );
            } else {
                response = await Stowling.request("/v1/task-folders", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ name })
                });
            }
            applyFolderResponse(response);
            closeFolderForm();
            Stowling.showToast(
                editing ? "文件夹已重命名" : "文件夹已创建"
            );
        } catch (error) {
            Stowling.handleError(error, "保存文件夹失败");
        } finally {
            state.folderBusy = false;
            updateFolderControls();
        }
    }

    /**
     * @returns {Promise<void>}
     */
    async function deleteFolder() {
        const folder = state.folders.find(
            (entry) => entry.id === state.folderEditorId
        );
        if (!folder) {
            return;
        }
        const confirmed = window.confirm(
            `解散文件夹“${folder.name}”？其中的任务会变为未分组，`
                + "任务本身不会被删除。"
        );
        if (!confirmed) {
            return;
        }
        state.folderBusy = true;
        updateFolderControls();
        let response;
        try {
            response = await Stowling.request(
                `/v1/task-folders/${encodeURIComponent(folder.id)}`,
                {
                    method: "DELETE",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ revision: state.revision })
                }
            );
        } catch (error) {
            Stowling.handleError(error, "解散文件夹失败");
            state.folderBusy = false;
            updateFolderControls();
            return;
        }
        try {
            state.revision = String(response?.revision || state.revision);
            state.folders = normalizeFolders(response?.folders);
            if (state.folderFilter === folder.id) {
                state.folderFilter = FOLDER_FILTER_ALL;
            }
            state.tasks = state.tasks.map((task) => (
                task.folderId === folder.id
                    ? { ...task, folderId: "" }
                    : task
            ));
            closeFolderForm();
            renderTaskList();
            renderRunControls();
            let tasksResponse;
            try {
                tasksResponse = await Stowling.request("/v1/tasks");
            } catch (error) {
                Stowling.handleError(
                    error,
                    "文件夹已解散，但刷新任务失败"
                );
                return;
            }
            state.revision = String(
                tasksResponse?.revision || state.revision
            );
            state.tasks = Array.isArray(tasksResponse?.tasks)
                ? tasksResponse.tasks.map(Model.normalizeTask)
                : [];
            state.tasks = state.tasks.map((task) => (
                task.folderId === folder.id
                    ? { ...task, folderId: "" }
                    : task
            ));
            renderTaskList();
            renderRunControls();
            Stowling.showToast("文件夹已解散，任务已保留为未分组");
        } finally {
            state.folderBusy = false;
            updateFolderControls();
        }
    }

    /**
     * @param {object} task
     * @returns {boolean}
     */
    function isInterruptionTask(task) {
        if (task.defaultInterruptionFlow) {
            return true;
        }
        return state.tasks.some((candidate) => {
            if (candidate.id === task.id) {
                return false;
            }
            const reference = Model.parseReference(
                candidate.interruptionTaskRef
            );
            return reference?.type === "task" && reference.id === task.id;
        });
    }

    /**
     * @param {object} task
     * @returns {boolean}
     */
    function matchesTaskFilter(task) {
        if (state.taskFilter === "INTERRUPTION") {
            return isInterruptionTask(task);
        }
        if (state.taskFilter === "NORMAL") {
            return !isInterruptionTask(task);
        }
        return true;
    }

    /**
     * @returns {Promise<void>}
     */
    async function loadAll() {
        try {
            const [tasks, folders, triggers, modules, runs] = await Promise.all([
                Stowling.request("/v1/tasks"),
                Stowling.request("/v1/task-folders"),
                Stowling.request("/v1/task-triggers"),
                Stowling.request("/v1/task-modules"),
                Stowling.request("/v1/task-runs")
            ]);
            state.revision = String(tasks?.revision || folders?.revision || "");
            state.tasks = Array.isArray(tasks?.tasks)
                ? tasks.tasks.map(Model.normalizeTask) : [];
            state.folders = normalizeFolders(folders?.folders);
            state.triggers = Array.isArray(triggers?.triggers) ? triggers.triggers : [];
            state.modules = Array.isArray(modules?.modules) ? modules.modules : [];
            state.runs = Array.isArray(runs?.runs) ? runs.runs : [];
            state.queue = Array.isArray(runs?.queue) ? runs.queue : [];
            state.triggerStatuses = Array.isArray(runs?.triggers) ? runs.triggers : [];
            renderRunControls();
            renderTaskList();
            renderRuntime();
            renderQueue();
            appendMonitorLogs();
            showMonitorSync();
        } catch (error) {
            Stowling.handleError(error, "读取任务失败");
        }
    }

    /**
     * @returns {void}
     */
    function renderTaskList() {
        elements.list.replaceChildren();
        renderTaskFolders();
        const matches = state.tasks
            .filter((task) => (
                task.name.toLocaleLowerCase().includes(state.query)
                && matchesTaskFilter(task)
                && matchesTaskFolder(task)
            ))
            .sort((left, right) => (
                Number(isInterruptionTask(left)) - Number(isInterruptionTask(right))
                || compareTasks(left, right)
            ));
        elements.taskCount.textContent = String(matches.length);
        updateTaskFilters();
        if (matches.length === 0) {
            elements.list.append(Stowling.emptyState(
                state.tasks.length === 0
                    ? "尚未创建任务"
                    : "没有符合当前分类和搜索条件的任务"
            ));
            return;
        }
        const groupHeadings = new Set();
        for (const task of matches) {
            const group = isInterruptionTask(task) ? "中断收尾" : "日常任务";
            if (state.taskFilter === "ALL" && !groupHeadings.has(group)) {
                elements.list.append(Stowling.element("h3", "task-list-group", group));
                groupHeadings.add(group);
            }
            const button = Stowling.element(
                "button",
                "task-card task-select"
            );
            button.type = "button";
            const identity = Stowling.element("div", "task-select-identity");
            const interruption = isInterruptionTask(task);
            identity.append(
                Stowling.element("strong", "", task.name),
                Stowling.element(
                    "span",
                    "compact-help",
                    taskListSummary(task)
                ),
                ...(interruption
                    ? [Stowling.element(
                        "span",
                        "task-category-tag",
                        task.defaultInterruptionFlow
                            ? "默认中断收尾"
                            : "中断收尾"
                    )]
                    : [])
            );
            button.setAttribute(
                "aria-pressed",
                String(task.id === state.selectedId)
            );
            button.append(
                identity,
                Stowling.element(
                    "span",
                    "module-level-tag",
                    Model.priorityLabel(task.priority)
                )
            );
            button.classList.toggle("active", task.id === state.selectedId);
            button.addEventListener("click", () => selectTask(task));
            elements.list.append(button);
        }
    }

    /**
     * @param {object} task
     * @returns {string}
     */
    function taskListSummary(task) {
        const folder = state.folders.find((item) => item.id === task.folderId);
        const boundTriggers = state.triggers.filter(
            (trigger) => trigger.targetTaskId === task.id
        ).length;
        const activation = boundTriggers > 0
            ? `${boundTriggers} 个触发器`
            : task.repeatWhenIdle ? "完成后重排" : "手动 / 其他任务调用";
        return `${folder?.name || "未分组"} · ${task.steps.length} 个根步骤 · ${activation}`;
    }

    /**
     * @param {object} left
     * @param {object} right
     * @returns {number}
     */
    function compareTasks(left, right) {
        const priority = Model.priorityRank(left.priority)
            - Model.priorityRank(right.priority);
        return priority || left.name.localeCompare(right.name, "zh-CN");
    }

    /**
     * @param {object} task
     * @param {boolean} revealInList
     * @returns {void}
     */
    function selectTask(task, revealInList = false) {
        if (revealInList) {
            state.query = "";
            elements.search.value = "";
            state.taskFilter = "ALL";
            state.folderFilter = FOLDER_FILTER_ALL;
        }
        state.selectedId = task.id;
        state.runTaskId = task.id;
        renderTaskList();
        renderRunControls();
        if (revealInList) {
            elements.runDetails.close();
            elements.selectionPanel.scrollIntoView({ behavior: "smooth", block: "start" });
        }
    }

    /**
     * @returns {void}
     */
    function renderRunControls() {
        const current = state.runTaskId;
        elements.runSelect.replaceChildren(new Option("请选择任务", ""));
        for (const task of [...state.tasks].sort(compareTasks)) {
            elements.runSelect.append(new Option(task.name, task.id));
        }
        const preserved = state.tasks.some((task) => task.id === current)
            ? current
            : "";
        state.runTaskId = preserved;
        state.selectedId = preserved;
        elements.runSelect.value = preserved;
        updateRunActions();
    }

    /**
     * @returns {void}
     */
    function updateRunActions() {
        const task = state.tasks.find((entry) => entry.id === state.runTaskId);
        elements.runPriority.options[0].textContent = task
            ? `跟随任务（${Model.priorityLabel(task.priority)}）`
            : "跟随任务";
        elements.runSelect.disabled = state.tasks.length === 0;
        elements.runPriority.disabled = state.tasks.length === 0;
        elements.run.disabled = !state.runTaskId;
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
    async function runTask() {
        if (!state.runTaskId) {
            return;
        }
        try {
            const response = await Stowling.request(
                `/v1/tasks/${encodeURIComponent(state.runTaskId)}/runs`,
                {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify(elements.runPriority.value
                        ? { priority: elements.runPriority.value }
                        : {})
                }
            );
            await loadRuns();
            Stowling.showToast(
                response?.state === "QUEUED"
                    ? "任务已加入调度队列"
                    : "任务已开始运行"
            );
        } catch (error) {
            Stowling.handleError(error, "运行任务失败");
        }
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
            updateRunActions();
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
        const task = state.tasks.find((item) => item.id === run.taskId);
        if (task) {
            const select = Stowling.element("button", "secondary compact", "选择任务");
            select.type = "button";
            select.addEventListener("click", () => {
                selectTask(task, true);
            });
            actions.append(select);
        }
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
            const task = state.tasks.find((item) => item.id === entry.taskId);
            const name = Stowling.element(
                task ? "button" : "strong", "rail-task-name", entry.taskName
            );
            if (task) {
                name.type = "button";
                name.title = "选择任务";
                name.addEventListener("click", () => selectTask(task, true));
            }
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

    for (const button of elements.taskFilters) {
        button.addEventListener("click", () => {
            state.taskFilter = button.dataset.taskFilter;
            renderTaskList();
        });
    }
    elements.search.addEventListener("input", () => {
        state.query = elements.search.value.trim().toLocaleLowerCase();
        renderTaskList();
    });
    elements.newFolder.addEventListener("click", () => openFolderForm());
    elements.folderForm.addEventListener("submit", (event) => {
        event.preventDefault();
        void saveFolder();
    });
    elements.cancelFolder.addEventListener("click", closeFolderForm);
    elements.deleteFolder.addEventListener("click", () => void deleteFolder());
    elements.runSelect.addEventListener("change", () => {
        const task = state.tasks.find((entry) => entry.id === elements.runSelect.value);
        if (task) {
            selectTask(task);
        } else {
            state.selectedId = "";
            state.runTaskId = "";
            renderTaskList();
            updateRunActions();
        }
    });
    elements.run.addEventListener("click", () => void runTask());
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
    renderTaskList();
    renderRunControls();
    renderQueue();
    renderMonitorLogs();
    updateFolderControls();
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
