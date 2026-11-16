(() => {
    "use strict";

    const Stowling = window.Stowling;
    const Model = window.StowlingTaskModel;
    const TriggerModel = window.StowlingTriggerModel;
    const Editor = window.StowlingTaskEditor;
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
        coordinates: [],
        regions: [],
        categoryZones: [],
        selectedId: "",
        selectedTriggerId: "",
        runTaskId: "",
        draft: null,
        triggerDraft: null,
        presetMode: false,
        dirty: false,
        triggerDirty: false,
        runs: [],
        queue: [],
        query: "",
        taskFilter: "ALL",
        folderFilter: FOLDER_FILTER_ALL,
        folderEditorId: "",
        folderBusy: false,
        logScrollPaused: false,
        preserveTabOnSelect: false,
        openTriggersAfterTaskReady: false,
        taskEditSerial: 0,
        triggerEditSerial: 0
    };

    const elements = {
        taskFilters: [...document.querySelectorAll("[data-task-filter]")],
        newTask: document.querySelector("#new-task-button"),
        newCacheTask: document.querySelector("#new-cache-task-button"),
        newSortTask: document.querySelector("#new-sort-task-button"),
        newMarkerTask: document.querySelector("#new-marker-task-button"),
        newInterruptionTask: document.querySelector(
            "#new-interruption-task-button"
        ),
        newAutoEatTask: document.querySelector("#new-auto-eat-task-button"),
        autoEatForm: document.querySelector("#auto-eat-preset-form"),
        autoEatCoordinate: document.querySelector("#auto-eat-coordinate"),
        autoEatFood: document.querySelector("#auto-eat-food"),
        autoEatThreshold: document.querySelector("#auto-eat-threshold"),
        autoEatErrors: document.querySelector("#auto-eat-preset-errors"),
        createAutoEatPreset: document.querySelector("#create-auto-eat-preset"),
        cancelAutoEatPreset: document.querySelector("#cancel-auto-eat-preset"),
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
        settingsLabel: document.querySelector("#task-settings-label"),
        editorTitle: document.querySelector("#task-editor-title"),
        draftStatus: document.querySelector("#task-draft-status"),
        list: document.querySelector("#task-list"),
        listMessage: document.querySelector("#task-list-message"),
        empty: document.querySelector("#task-empty"),
        form: document.querySelector("#task-form"),
        name: document.querySelector("#task-name"),
        folder: document.querySelector("#task-folder-select"),
        priority: document.querySelector("#task-priority"),
        repeat: document.querySelector("#task-repeat"),
        interruptionMode: document.querySelector("#task-interruption-mode"),
        interruptionTaskRef: document.querySelector(
            "#task-interruption-task-ref"
        ),
        interruptionDefaultHint: document.querySelector(
            "#interruption-default-hint"
        ),
        defaultInterruption: document.querySelector(
            "#task-default-interruption"
        ),
        requeueAfterInterruption: document.querySelector(
            "#task-requeue-after-interruption"
        ),
        steps: document.querySelector("#task-steps"),
        addStep: document.querySelector("#add-step-button"),
        addIfStep: document.querySelector("#add-if-step-button"),
        addRepeatStep: document.querySelector("#add-repeat-step-button"),
        addCreateTaskStep: document.querySelector("#add-create-task-step"),
        moduleCatalog: document.querySelector("#task-module-catalog"),
        moduleCount: document.querySelector("#task-module-count"),
        errors: document.querySelector("#task-errors"),
        revision: document.querySelector("#task-revision"),
        save: document.querySelector("#save-task-button"),
        discard: document.querySelector("#discard-task-button"),
        runSelect: document.querySelector("#run-task-select"),
        runPriority: document.querySelector("#run-priority-select"),
        run: document.querySelector("#run-task-button"),
        remove: document.querySelector("#delete-task-button"),
        newTrigger: document.querySelector("#new-trigger-button"),
        triggerSettings: document.querySelector("#task-trigger-settings"),
        triggerSummary: document.querySelector("#task-trigger-summary"),
        triggerSaveHint: document.querySelector("#trigger-save-hint"),
        triggerBoundTask: document.querySelector("#trigger-bound-task"),
        triggerList: document.querySelector("#trigger-list"),
        triggerForm: document.querySelector("#trigger-form"),
        triggerName: document.querySelector("#trigger-name"),
        triggerType: document.querySelector("#trigger-type"),
        triggerPriority: document.querySelector("#trigger-priority"),
        triggerInterval: document.querySelector("#trigger-interval"),
        triggerIntervalLabel: document.querySelector("#trigger-interval-label"),
        triggerCooldown: document.querySelector("#trigger-cooldown"),
        triggerCoordinate: document.querySelector("#trigger-coordinate"),
        triggerItemFilter: document.querySelector("#trigger-item-filter"),
        triggerDropFields: document.querySelector("#trigger-drop-fields"),
        triggerFoodFields: document.querySelector("#trigger-food-fields"),
        triggerFoodThreshold: document.querySelector("#trigger-food-threshold"),
        triggerCacheFields: document.querySelector("#trigger-cache-fields"),
        triggerCacheRegion: document.querySelector("#trigger-cache-region"),
        triggerErrors: document.querySelector("#trigger-errors"),
        saveTrigger: document.querySelector("#save-trigger-button"),
        discardTrigger: document.querySelector("#discard-trigger-button"),
        deleteTrigger: document.querySelector("#delete-trigger-button"),
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
        runDetails: document.querySelector("#task-run-details"),
        showRunDetails: document.querySelector("#show-run-details"),
        closeRunDetails: document.querySelector("#close-run-details")
    };

    /**
     * @returns {boolean}
     */
    function hasLegacyTriggerHash() {
        return String(window.location.hash || "").toLocaleLowerCase()
            === "#triggers";
    }

    /**
     * @returns {void}
     */
    function openCurrentTaskTriggers() {
        if (!state.selectedId || !state.draft) {
            return;
        }
        state.openTriggersAfterTaskReady = false;
        elements.triggerSettings.open = true;
        renderTriggerList();
        renderTriggerEditor();
    }

    /**
     * @returns {void}
     */
    function applyTaskHashRoute() {
        if (hasLegacyTriggerHash()) {
            state.openTriggersAfterTaskReady = true;
        }
        if (state.openTriggersAfterTaskReady) {
            openCurrentTaskTriggers();
        }
    }

    /**
     * @param {string} view
     * @returns {void}
     */
    function setTaskRoute(view) {
        const hash = view === "monitor" ? "#queue" : "#tasks";
        if (window.location.hash !== hash) {
            window.history.replaceState(null, "", hash);
        }
    }

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
     * @returns {string}
     */
    function defaultFolderIdForNewTask() {
        const filter = state.folderFilter;
        if (filter === FOLDER_FILTER_UNGROUPED) {
            return "";
        }
        const folderId = filter === FOLDER_FILTER_ALL
            ? String(state.draft?.folderId || "")
            : filter;
        return state.folders.some((folder) => folder.id === folderId)
            ? folderId
            : "";
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
    function renderTaskFolderSelect() {
        const draft = state.draft;
        const current = String(draft?.folderId || "");
        elements.folder.replaceChildren(new Option("未分组", ""));
        for (const folder of state.folders) {
            elements.folder.append(new Option(folder.name, folder.id));
        }
        if (current && !state.folders.some((folder) => folder.id === current)) {
            elements.folder.append(new Option("已解散的文件夹", current));
        }
        elements.folder.value = current;
        elements.folder.disabled = !draft;
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
        renderTaskFolders();
        renderTaskFolderSelect();
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
            if (state.draft?.folderId === folder.id) {
                state.draft.folderId = "";
            }
            closeFolderForm();
            renderTaskFolders();
            renderTaskList();
            renderTaskFolderSelect();
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
            if (state.draft?.folderId === folder.id) {
                state.draft.folderId = "";
            }
            renderTaskList();
            renderTaskFolderSelect();
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
            const [
                tasks,
                folders,
                triggers,
                modules,
                coordinates,
                regions,
                categoryZones,
                runs
            ] = await Promise.all([
                Stowling.request("/v1/tasks"),
                Stowling.request("/v1/task-folders"),
                Stowling.request("/v1/task-triggers"),
                Stowling.request("/v1/task-modules"),
                Stowling.request("/v1/coordinates"),
                Stowling.request("/v1/regions"),
                Stowling.request("/v1/category-zones"),
                Stowling.request("/v1/task-runs")
            ]);
            state.revision = String(
                tasks?.revision || folders?.revision || ""
            );
            state.tasks = Array.isArray(tasks?.tasks)
                ? tasks.tasks.map(Model.normalizeTask)
                : [];
            state.folders = normalizeFolders(folders?.folders);
            state.triggers = Array.isArray(triggers?.triggers)
                ? triggers.triggers.map(TriggerModel.normalizeTrigger)
                : [];
            state.modules = Array.isArray(modules?.modules) ? modules.modules : [];
            state.coordinates = Array.isArray(coordinates?.coordinates)
                ? coordinates.coordinates
                : [];
            state.regions = Array.isArray(regions?.regions)
                ? regions.regions
                : [];
            state.categoryZones = Array.isArray(categoryZones?.categoryZones)
                ? categoryZones.categoryZones
                : [];
            state.runs = Array.isArray(runs?.runs) ? runs.runs : [];
            state.queue = Array.isArray(runs?.queue) ? runs.queue : [];
            state.triggerStatuses = Array.isArray(runs?.triggers)
                ? runs.triggers
                : [];
            renderTaskFolders();
            renderTaskList();
            renderTriggerList();
            renderTriggerEditor();
            renderModuleCatalog();
            renderRunControls();
            if (!state.draft && state.tasks.length > 0
                    && state.openTriggersAfterTaskReady) {
                state.preserveTabOnSelect = true;
                selectTask(state.tasks[0]);
            }
            renderRuntime();
            renderQueue();
            appendMonitorLogs();
            showMonitorSync();
            applyTaskHashRoute();
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
     * @returns {Array<object>}
     */
    function currentTaskTriggers() {
        if (!state.selectedId) {
            return [];
        }
        return state.triggers.filter(
            (trigger) => trigger.targetTaskId === state.selectedId
        );
    }

    /**
     * @returns {Array<object>}
     */
    function editingTaskTriggers() {
        const triggers = currentTaskTriggers().filter(
            (trigger) => trigger.id !== state.selectedTriggerId || !state.triggerDraft
        );
        if (state.triggerDraft) {
            triggers.push(state.triggerDraft);
        }
        return triggers;
    }

    /**
     * @param {string} triggerId
     * @returns {object | null}
     */
    function triggerStatusFor(triggerId) {
        return state.triggerStatuses.find(
            (status) => String(status.triggerId) === String(triggerId)
        ) || null;
    }

    /**
     * @returns {void}
     */
    function renderTriggerSummary() {
        const triggers = currentTaskTriggers();
        const statusCount = triggers.filter(
            (trigger) => triggerStatusFor(trigger.id)
        ).length;
        const selectedTask = state.tasks.find(
            (task) => task.id === state.selectedId
        );
        elements.triggerBoundTask.textContent = state.selectedId
            ? selectedTask?.name || "当前任务"
            : state.draft?.name
                ? `${state.draft.name}（未保存）`
                : "未选择任务";
        if (!state.selectedId) {
            elements.triggerSummary.textContent = state.triggerDraft
                ? "检测器待随任务保存" : "点击添加检测器";
            return;
        }
        elements.triggerSummary.textContent = triggers.length === 0
            ? "0 个 · 未配置"
            : `${triggers.length} 个 · ${statusCount} 个已加载`;
    }

    /**
     * @returns {void}
     */
    function renderTriggerList() {
        elements.triggerList.replaceChildren();
        renderTriggerSummary();
        if (!state.selectedId) {
            elements.triggerList.append(
                Stowling.emptyState("添加检测器，保存时自动绑定当前任务")
            );
            return;
        }
        const triggers = currentTaskTriggers();
        if (triggers.length === 0) {
            elements.triggerList.append(
                Stowling.emptyState("当前任务尚未配置自动触发")
            );
            return;
        }
        for (const trigger of triggers) {
            const status = triggerStatusFor(trigger.id);
            const button = Stowling.element(
                "button",
                "task-card task-select trigger-select"
            );
            button.type = "button";
            button.classList.toggle(
                "active",
                trigger.id === state.selectedTriggerId
            );
            const identity = Stowling.element("div", "task-select-identity");
            identity.append(
                Stowling.element("strong", "", trigger.name),
                Stowling.element(
                    "span",
                    "compact-help",
                    TriggerModel.conditionLabel(trigger)
                ),
                Stowling.element(
                    "span",
                    "muted compact-help",
                    `${Model.priorityLabel(trigger.priority)}队列 · `
                        + (status
                            ? triggerStateLabel(status.state)
                            : "状态未加载")
                )
            );
            button.append(identity);
            button.addEventListener("click", () => selectTrigger(trigger));
            elements.triggerList.append(button);
        }
    }

    /**
     * @param {object} trigger
     * @returns {void}
     */
    function selectTrigger(trigger) {
        if (trigger.targetTaskId !== state.selectedId) {
            return;
        }
        if (state.triggerDirty
                && !window.confirm("当前触发器有未保存修改，继续将放弃这些修改。")) {
            return;
        }
        state.selectedTriggerId = trigger.id;
        state.triggerDraft = TriggerModel.normalizeTrigger(trigger);
        state.triggerEditSerial++;
        state.triggerDirty = false;
        elements.triggerSettings.open = true;
        renderEditor();
    }

    /**
     * @returns {void}
     */
    function startNewTrigger() {
        if (!state.draft) {
            Stowling.showToast("请先新建或选择一个任务", true);
            return;
        }
        if (state.triggerDirty
                && !window.confirm("当前触发器有未保存修改，继续将放弃这些修改。")) {
            return;
        }
        state.selectedTriggerId = "";
        state.triggerDraft = TriggerModel.newTrigger(state.selectedId);
        state.triggerDraft.name = `${state.draft.name || "任务"} · 掉落物检测器`;
        state.triggerDraft.priority = state.draft.priority;
        state.triggerEditSerial++;
        state.triggerDirty = true;
        elements.triggerSettings.open = true;
        renderEditor();
        elements.triggerName.focus();
        elements.triggerName.select();
    }

    /**
     * @returns {void}
     */
    function renderTriggerEditor() {
        const draft = state.triggerDraft;
        elements.triggerForm.hidden = !draft;
        if (!draft) {
            updateTriggerActions();
            return;
        }
        elements.triggerCoordinate.replaceChildren(
            new Option("请选择检测坐标", "")
        );
        for (const coordinate of state.coordinates) {
            elements.triggerCoordinate.append(
                new Option(coordinate.name, coordinate.id)
            );
        }
        elements.triggerCacheRegion.replaceChildren(
            new Option("请选择缓存区域", "")
        );
        for (const region of state.regions) {
            elements.triggerCacheRegion.append(
                new Option(region.name, region.id)
            );
        }
        elements.triggerName.value = draft.name;
        elements.triggerType.value = draft.type;
        elements.triggerPriority.value = draft.priority;
        elements.triggerInterval.value = String(
            Math.max(0.05, draft.intervalTicks / 20)
        );
        elements.triggerCooldown.value = String(
            Math.max(0, draft.cooldownTicks / 20)
        );
        elements.triggerCoordinate.value = draft.coordinateId;
        elements.triggerItemFilter.value = draft.itemFilter;
        elements.triggerCacheRegion.value = draft.cacheRegionId;
        elements.triggerFoodThreshold.value = String(
            draft.foodLevelThreshold
        );
        elements.triggerDropFields.hidden =
            draft.type !== "DROPPED_ITEMS";
        elements.triggerFoodFields.hidden =
            draft.type !== "FOOD_LEVEL_AT_OR_BELOW";
        elements.triggerCacheFields.hidden =
            draft.type !== "CACHE_UPDATED";
        elements.triggerIntervalLabel.textContent =
            draft.type === "CACHE_UPDATED"
                ? "触发防抖（秒）"
                : "检测间隔（秒）";
        updateTriggerActions();
    }

    /**
     * @returns {void}
     */
    function markTriggerDirty() {
        state.triggerDirty = true;
        state.triggerEditSerial++;
        updateTriggerActions();
        updateActions();
    }

    /**
     * @returns {Array<string>}
     */
    function triggerValidationErrors() {
        const trigger = state.triggerDraft;
        if (!trigger) {
            return ["尚未选择触发器"];
        }
        const errors = [];
        if (!String(trigger.name || "").trim()) {
            errors.push("触发器名称不能为空");
        }
        if (!TriggerModel.TYPES.includes(trigger.type)) {
            errors.push("触发条件无效");
        }
        if (!state.draft || (state.selectedId
                && trigger.targetTaskId !== state.selectedId)) {
            errors.push("触发器必须绑定当前任务");
        }
        if (!Number.isInteger(trigger.intervalTicks)
                || trigger.intervalTicks < 1
                || trigger.intervalTicks > 1200) {
            errors.push("检测间隔必须在 0.05 到 60 秒之间");
        }
        if (!Number.isInteger(trigger.cooldownTicks)
                || trigger.cooldownTicks < 0
                || trigger.cooldownTicks > 72000) {
            errors.push("完成冷却必须在 0 到 3600 秒之间");
        }
        if (trigger.type === "DROPPED_ITEMS"
                && !state.coordinates.some(
                    (point) => point.id === trigger.coordinateId
                )) {
            errors.push("掉落物触发必须选择一个检测坐标");
        }
        if (trigger.type === "FOOD_LEVEL_AT_OR_BELOW"
                && (!Number.isInteger(trigger.foodLevelThreshold)
                    || trigger.foodLevelThreshold < 0
                    || trigger.foodLevelThreshold > 20)) {
            errors.push("饱食度阈值必须是 0 到 20 的整数");
        }
        if (trigger.type === "CACHE_UPDATED"
                && !state.regions.some(
                    (region) => region.id === trigger.cacheRegionId
                )) {
            errors.push("缓存触发必须选择一个缓存区域");
        }
        return errors;
    }

    /**
     * @returns {void}
     */
    function updateTriggerActions() {
        const errors = state.triggerDraft
            ? triggerValidationErrors()
            : [];
        elements.triggerErrors.replaceChildren();
        if (state.triggerDirty && errors.length > 0) {
            elements.triggerErrors.append(
                Stowling.element("strong", "", "请修正以下问题：")
            );
            for (const error of errors) {
                elements.triggerErrors.append(
                    Stowling.element("p", "", error)
                );
            }
        }
        elements.newTrigger.disabled = !state.draft;
        elements.triggerSaveHint.hidden = Boolean(state.selectedId);
        elements.saveTrigger.textContent = state.selectedId
            ? "保存检测器" : "随任务保存";
        elements.saveTrigger.disabled = !state.triggerDraft
            || !state.triggerDirty
            || errors.length > 0;
        elements.discardTrigger.disabled = !state.triggerDirty;
        elements.deleteTrigger.disabled = !state.selectedTriggerId;
        renderTriggerSummary();
    }

    /**
     * @returns {Promise<boolean>}
     */
    async function saveTrigger() {
        const taskId = state.selectedId;
        if (!taskId && state.triggerDraft && state.draft) {
            await saveTask();
            return !state.triggerDirty;
        }
        if (!taskId || !state.triggerDraft) {
            elements.triggerSettings.open = true;
            elements.triggerSaveHint.hidden = false;
            return false;
        }
        const errors = triggerValidationErrors();
        if (errors.length > 0) {
            updateTriggerActions();
            return false;
        }
        const triggerId = state.selectedTriggerId;
        const draftSnapshot = state.triggerDraft;
        draftSnapshot.targetTaskId = taskId;
        const editSerial = state.triggerEditSerial;
        const knownIds = new Set(state.triggers.map((trigger) => trigger.id));
        const payload = {
            ...TriggerModel.payloadTrigger(draftSnapshot),
            targetTaskId: taskId
        };
        try {
            let response;
            if (triggerId) {
                response = await Stowling.request(
                    `/v1/task-triggers/${encodeURIComponent(triggerId)}`,
                    {
                        method: "PUT",
                        headers: { "Content-Type": "application/json" },
                        body: JSON.stringify({
                            revision: state.revision,
                            ...payload
                        })
                    }
                );
            } else {
                response = await Stowling.request("/v1/task-triggers", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify(payload)
                });
            }
            state.revision = String(response?.revision || state.revision);
            state.triggers = Array.isArray(response?.triggers)
                ? response.triggers.map(TriggerModel.normalizeTrigger)
                : state.triggers;
            const saved = triggerId
                ? state.triggers.find((trigger) => trigger.id === triggerId)
                : state.triggers.find((trigger) => !knownIds.has(trigger.id))
                    || state.triggers.find((trigger) => (
                        trigger.name === payload.name
                        && trigger.targetTaskId === taskId
                    ));
            const stillEditing = state.selectedId === taskId
                && state.triggerDraft === draftSnapshot
                && state.triggerEditSerial === editSerial;
            if (stillEditing) {
                state.selectedTriggerId = saved?.id || "";
                state.triggerDraft = saved
                    ? TriggerModel.normalizeTrigger(saved)
                    : null;
                state.triggerDirty = false;
                state.triggerEditSerial++;
                renderTriggerEditor();
            }
            renderTriggerList();
            await loadRuns();
            renderEditor();
            Stowling.showToast("任务检测器已保存");
            return true;
        } catch (error) {
            Stowling.handleError(error, "保存任务触发器失败");
            return false;
        }
    }

    /**
     * @returns {Promise<void>}
     */
    async function deleteTrigger() {
        const triggerId = state.selectedTriggerId;
        const taskId = state.selectedId;
        const draftSnapshot = state.triggerDraft;
        if (!triggerId || !draftSnapshot) {
            return;
        }
        if (!window.confirm(`删除触发器“${draftSnapshot.name}”？`)) {
            return;
        }
        try {
            const response = await Stowling.request(
                `/v1/task-triggers/${encodeURIComponent(triggerId)}`,
                {
                    method: "DELETE",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ revision: state.revision })
                }
            );
            state.revision = String(response?.revision || state.revision);
            state.triggers = Array.isArray(response?.triggers)
                ? response.triggers.map(TriggerModel.normalizeTrigger)
                : state.triggers;
            const stillEditing = state.selectedId === taskId
                && state.triggerDraft === draftSnapshot;
            if (stillEditing) {
                state.selectedTriggerId = "";
                state.triggerDraft = null;
                state.triggerDirty = false;
                state.triggerEditSerial++;
                renderTriggerEditor();
            }
            renderTriggerList();
            await loadRuns();
            Stowling.showToast("任务触发器已删除");
        } catch (error) {
            Stowling.handleError(error, "删除任务触发器失败");
        }
    }

    /**
     * @param {string} triggerState
     * @returns {string}
     */
    function triggerStateLabel(triggerState) {
        const labels = {
            IDLE: "空闲",
            RUNNING: "已入队",
            COOLDOWN: "冷却中",
            ERROR: "异常"
        };
        return labels[triggerState] || "未加载";
    }

    /**
     * @returns {void}
     */
    function renderModuleCatalog() {
        elements.moduleCatalog.replaceChildren();
        const visibleModules = Model.visibleModules(state.modules);
        elements.moduleCount.textContent = `${visibleModules.length} 个`;
        if (visibleModules.length === 0) {
            elements.moduleCatalog.append(
                Stowling.emptyState("当前没有可用基础动作")
            );
            return;
        }
        const commonIds = new Set([
            "CONTAINER_INTERACTION", "MOVE_TO_COORDINATE", "EAT_FOOD"
        ]);
        for (const [kind, label] of [
            ["COMMON", "常用模块"],
            ["ADVANCED", "高级基础动作 · 兼容旧任务"],
            ["LISTENER", "任务启动条件"]
        ]) {
            const modules = visibleModules.filter((module) => {
                if (kind === "LISTENER") {
                    return Model.moduleKind(module) === "LISTENER";
                }
                return Model.moduleKind(module) === "ACTION"
                    && commonIds.has(module.id) === (kind === "COMMON");
            });
            if (modules.length === 0) {
                continue;
            }
            const group = Stowling.element(
                kind === "ADVANCED" ? "details" : "section", "module-group"
            );
            const heading = Stowling.element(
                kind === "ADVANCED" ? "summary" : "div", "task-group-heading"
            );
            heading.append(
                Stowling.element("strong", "", label),
                Stowling.element(
                    "span",
                    "muted compact-help",
                    `${modules.length} 个`
                )
            );
            group.append(heading);
            for (const module of modules) {
                group.append(createModuleCard(module));
            }
            elements.moduleCatalog.append(group);
        }
    }

    /**
     * @param {object} module
     * @returns {string}
     */
    function moduleTag(module) {
        return Model.moduleKind(module) === "LISTENER"
            ? "监听"
            : "基础动作";
    }

    /**
     * @param {object} module
     * @returns {HTMLElement}
     */
    function createModuleCard(module) {
        const card = Stowling.element("details", "module-card");
        const summary = Stowling.element("summary", "");
        summary.append(
            Stowling.element("strong", "", module.name),
            Stowling.element("span", "module-level-tag", moduleTag(module))
        );
        const parameters = Array.isArray(module.parameters)
            ? module.parameters
            : [];
        const parameterText = parameters.length === 0
            ? "无需参数"
            : parameters.map((parameter) => {
                const required = parameter.required ? "（必填）" : "";
                return `${parameter.label}${required}`;
            }).join("、");
        card.append(
            summary,
            Stowling.element("p", "", module.description || ""),
            Stowling.element(
                "p",
                "compact-help",
                `参数：${parameterText}`
            )
        );
        return card;
    }

    /**
     * @returns {boolean}
     */
    function confirmDiscardForTaskChange() {
        if (state.dirty
                && !window.confirm("当前任务有未保存修改，继续将放弃这些修改。")) {
            return false;
        }
        if (state.triggerDirty
                && !window.confirm(
                    "当前任务的触发器有未保存修改，继续将放弃这些修改。"
                )) {
            return false;
        }
        return true;
    }

    /**
     * @returns {void}
     */
    function resetTriggerDraft() {
        state.selectedTriggerId = "";
        state.triggerDraft = null;
        state.triggerDirty = false;
        state.triggerEditSerial++;
        renderTriggerList();
        renderTriggerEditor();
    }

    /**
     * @param {object} task
     * @param {boolean} revealInList
     * @returns {void}
     */
    function selectTask(task, revealInList = false) {
        if (!confirmDiscardForTaskChange()) {
            return;
        }
        if (revealInList) {
            state.query = "";
            elements.search.value = "";
            state.taskFilter = "ALL";
            state.folderFilter = FOLDER_FILTER_ALL;
        }
        const preserveTab = state.preserveTabOnSelect;
        state.preserveTabOnSelect = false;
        if (!preserveTab) {
            setTaskRoute("tasks");
        }
        state.presetMode = false;
        elements.autoEatForm.hidden = true;
        state.selectedId = task.id;
        state.runTaskId = task.id;
        state.draft = Model.normalizeTask(task);
        state.dirty = false;
        resetTriggerDraft();
        renderEditor();
        renderTaskList();
        renderRunControls();
        animateEditor();
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
            : state.tasks[0]?.id || "";
        state.runTaskId = preserved;
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
     * @param {string} base
     * @param {Array<string>} existing
     * @returns {string}
     */
    function uniqueName(base, existing) {
        const used = new Set(existing.map((name) => name.toLocaleLowerCase()));
        if (!used.has(base.toLocaleLowerCase())) {
            return base;
        }
        let suffix = 2;
        while (used.has(`${base} ${suffix}`.toLocaleLowerCase())) {
            suffix++;
        }
        return `${base} ${suffix}`;
    }

    /**
     * @returns {void}
     */
    function showAutoEatPresetForm() {
        if (!confirmDiscardForTaskChange()) {
            return;
        }
        elements.autoEatCoordinate.replaceChildren(
            new Option("请选择食物箱坐标", "")
        );
        for (const coordinate of state.coordinates) {
            elements.autoEatCoordinate.append(
                new Option(coordinate.name, coordinate.id)
            );
        }
        elements.autoEatFood.value = "minecraft:bread";
        elements.autoEatThreshold.value = "18";
        elements.autoEatErrors.replaceChildren();
        state.presetMode = true;
        state.selectedId = "";
        state.selectedTriggerId = "";
        state.triggerDraft = null;
        state.triggerDirty = false;
        state.triggerEditSerial++;
        state.draft = Model.eatTask(
            state.modules,
            "",
            "minecraft:bread",
            18
        );
        state.draft.folderId = defaultFolderIdForNewTask();
        state.draft.name = uniqueName(
            "自动进食",
            state.tasks.map((task) => task.name)
        );
        state.dirty = true;
        elements.autoEatForm.hidden = false;
        setTaskRoute("tasks");
        syncAutoEatDraft();
        renderEditor();
        elements.autoEatCoordinate.focus();
    }

    /**
     * @returns {void}
     */
    function hideAutoEatPresetForm() {
        if (state.presetMode) {
            resetAutoEatPreset();
        }
    }

    /**
     * @returns {Promise<void>}
     */
    async function createAutoEatPreset() {
        syncAutoEatDraft();
        const threshold = Number(elements.autoEatThreshold.value);
        const errors = [...validationErrors()];
        if (!Number.isInteger(threshold) || threshold < 1 || threshold > 20) {
            errors.push("饱食度阈值必须是 1 到 20 的整数");
        }
        elements.autoEatErrors.replaceChildren();
        if (errors.length > 0) {
            for (const error of errors) {
                elements.autoEatErrors.append(
                    Stowling.element("p", "", error)
                );
            }
            return;
        }
        const payload = Model.payloadTask(state.draft);
        const taskName = payload.name;
        const triggerName = uniqueName(
            `${taskName} 监听器`,
            state.triggers.map((trigger) => trigger.name)
        );
        elements.createAutoEatPreset.disabled = true;
        try {
            const taskResponse = await Stowling.request("/v1/tasks", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(payload)
            });
            state.revision = String(taskResponse?.revision || state.revision);
            state.tasks = taskResponse.tasks.map(Model.normalizeTask);
            const createdTask = state.tasks.find(
                (task) => task.name === taskName
            );
            if (!createdTask) {
                throw new Error("任务创建成功，但没有返回任务 ID");
            }
            const triggerPayload = {
                name: triggerName,
                type: "FOOD_LEVEL_AT_OR_BELOW",
                targetTaskId: createdTask.id,
                priority: payload.priority,
                intervalTicks: 20,
                cooldownTicks: 100,
                coordinateId: null,
                itemFilter: null,
                foodLevelThreshold: threshold,
                cacheRegionId: null
            };
            const triggerResponse = await Stowling.request(
                "/v1/task-triggers",
                {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify(triggerPayload)
                }
            );
            state.revision = String(
                triggerResponse?.revision || state.revision
            );
            state.triggers = triggerResponse.triggers.map(
                TriggerModel.normalizeTrigger
            );
            state.presetMode = false;
            elements.autoEatForm.hidden = true;
            state.draft = null;
            state.dirty = false;
            selectTask(createdTask);
            renderTriggerList();
            renderTriggerEditor();
            renderRunControls();
            Stowling.showToast("自动进食任务与饱食度监听器已创建");
        } catch (error) {
            Stowling.handleError(error, "创建自动进食预设失败");
        } finally {
            elements.createAutoEatPreset.disabled = false;
        }
    }

    /**
     * @returns {void}
     */
    function startNewTask() {
        startTaskTemplate("new");
    }

    /**
     * @returns {void}
     */
    function startCacheTask() {
        startTaskTemplate("collect");
    }

    /**
     * @returns {void}
     */
    function startSortTask() {
        startTaskTemplate("sort");
    }

    /**
     * @returns {void}
     */
    function startMarkerTask() {
        startTaskTemplate("marker");
    }

    /**
     * @returns {void}
     */
    function startInterruptionTask() {
        startTaskTemplate("interruption");
    }

    /**
     * @param {string} template
     * @returns {void}
     */
    function startTaskTemplate(template) {
        if (!confirmDiscardForTaskChange()) {
            return;
        }
        if (state.modules.length === 0) {
            Stowling.showToast("任务动作尚未加载", true);
            return;
        }
        if (state.presetMode) {
            resetAutoEatPreset();
        }
        const draft = switchTaskTemplate(template);
        const missing = Model.missingModules(
            state.modules,
            Model.taskModuleIds(draft)
        );
        if (missing.length > 0) {
            Stowling.showToast("任务模板所需基础动作尚未加载", true);
            return;
        }
        state.selectedId = "";
        state.selectedTriggerId = "";
        state.triggerDraft = null;
        state.triggerDirty = false;
        state.triggerEditSerial++;
        draft.folderId = defaultFolderIdForNewTask();
        state.draft = draft;
        if (template === "collect") {
            state.triggerDraft = TriggerModel.newTrigger("");
            state.triggerDraft.name = `${draft.name} · 掉落物检测器`;
            state.triggerDraft.priority = draft.priority;
            state.triggerDirty = true;
            elements.triggerSettings.open = true;
        }
        state.dirty = true;
        setTaskRoute("tasks");
        renderEditor();
        renderTaskList();
        elements.name.focus();
        elements.name.select();
        animateEditor();
    }

    /**
     * @param {string} template
     * @returns {object}
     */
    function switchTaskTemplate(template) {
        if (template === "collect") {
            return Model.collectTask(state.modules);
        }
        if (template === "sort") {
            return Model.sortTask(state.modules);
        }
        if (template === "marker") {
            return Model.markerTask(state.modules);
        }
        if (template === "interruption") {
            return Model.interruptionTask(state.modules);
        }
        return Model.newTask(state.modules);
    }

    /**
     * @returns {void}
     */
    function syncAutoEatDraft() {
        if (!state.presetMode || !state.draft) {
            return;
        }
        const targetRef = Model.coordinateRef(elements.autoEatCoordinate.value);
        const foodItemId = elements.autoEatFood.value.trim();
        const threshold = Number(elements.autoEatThreshold.value);
        visitSteps(state.draft.steps, (step) => {
            if (step.moduleId === "MOVE_TO_COORDINATE") {
                step.parameters.targetRef = targetRef;
            } else if (step.moduleId === "WITHDRAW_FOOD") {
                step.parameters.targetRef = targetRef;
                step.parameters.foodItemId = foodItemId;
            } else if (step.moduleId === "EAT_FOOD") {
                step.parameters.foodItemId = foodItemId;
            }
            if (step.parameters.condition === "FOOD_BELOW_THRESHOLD") {
                step.parameters.foodLevelThreshold = String(threshold);
            }
        });
    }

    /**
     * @param {Array<object>} steps
     * @param {(step: object) => void} visitor
     * @returns {void}
     */
    function visitSteps(steps, visitor) {
        for (const step of Array.isArray(steps) ? steps : []) {
            visitor(step);
            visitSteps(step.steps, visitor);
            visitSteps(step.otherwise, visitor);
        }
    }

    /**
     * @returns {void}
     */
    function resetAutoEatPreset() {
        state.presetMode = false;
        state.selectedId = "";
        state.draft = null;
        state.dirty = false;
        state.selectedTriggerId = "";
        state.triggerDraft = null;
        state.triggerDirty = false;
        state.triggerEditSerial++;
        elements.autoEatForm.hidden = true;
        elements.autoEatErrors.replaceChildren();
        renderTriggerList();
        renderTriggerEditor();
        renderEditor();
        renderTaskList();
    }

    /**
     * @returns {void}
     */
    function renderEditor() {
        const hasDraft = Boolean(state.draft);
        elements.empty.hidden = hasDraft;
        elements.form.hidden = !hasDraft;
        elements.triggerSettings.hidden = !hasDraft;
        renderTaskFolderSelect();
        if (!hasDraft) {
            updateActions();
            renderTriggerList();
            renderTriggerEditor();
            return;
        }
        elements.name.value = state.draft.name;
        elements.priority.value = state.draft.priority;
        elements.repeat.checked = state.draft.repeatWhenIdle;
        elements.repeat.disabled = state.draft.priority !== "BACKGROUND"
            || state.draft.defaultInterruptionFlow;
        renderInterruptionControls();
        updateSettingsLabel();
        elements.revision.textContent = state.selectedId
            ? Stowling.revisionLabel(state.revision)
            : "新任务";
        Editor.render(
            elements.steps,
            state.draft,
            state.modules,
            state.coordinates,
            state.regions,
            state.categoryZones,
            rerenderEditor,
            state.tasks,
            editingTaskTriggers()
        );
        renderTriggerList();
        renderTriggerEditor();
        updateActions();
        if (state.openTriggersAfterTaskReady) {
            openCurrentTaskTriggers();
        }
    }

    /**
     * @returns {void}
     */
    function renderInterruptionControls() {
        const draft = state.draft;
        elements.interruptionMode.value = draft.interruptionMode;
        elements.interruptionMode.disabled = draft.defaultInterruptionFlow;
        elements.defaultInterruption.checked = draft.defaultInterruptionFlow;
        elements.requeueAfterInterruption.checked =
            draft.requeueAfterInterruption;
        elements.requeueAfterInterruption.disabled =
            draft.interruptionMode === "NONE"
            && !draft.defaultInterruptionFlow;
        elements.interruptionTaskRef.replaceChildren(
            new Option("请选择收尾任务", "")
        );
        for (const task of [...state.tasks].sort(compareTasks)) {
            if (task.id === state.selectedId) {
                continue;
            }
            elements.interruptionTaskRef.append(new Option(
                `${Model.priorityLabel(task.priority)} · ${task.name}`,
                Model.taskRef(task.id)
            ));
        }
        const currentRef = String(draft.interruptionTaskRef || "");
        if (currentRef && ![...elements.interruptionTaskRef.options].some(
            (option) => option.value === currentRef
        )) {
            elements.interruptionTaskRef.append(new Option(
                "已失效的收尾任务",
                currentRef
            ));
        }
        elements.interruptionTaskRef.value = currentRef;
        elements.interruptionTaskRef.disabled =
            draft.defaultInterruptionFlow || draft.interruptionMode !== "CUSTOM";
        const defaultTask = state.tasks.find(
            (task) => task.defaultInterruptionFlow
            && task.id !== state.selectedId
        );
        const selfDefault = state.tasks.some((task) => (
            task.id === state.selectedId
            && task.defaultInterruptionFlow
        ));
        if (defaultTask) {
            elements.interruptionDefaultHint.textContent =
                `当前全局默认收尾：${defaultTask.name}`;
        } else if (draft.defaultInterruptionFlow || selfDefault) {
            elements.interruptionDefaultHint.textContent =
                "当前任务已标记为全局默认中断收尾";
        } else {
            elements.interruptionDefaultHint.textContent =
                "当前尚未标记全局默认收尾任务";
        }
    }

    /**
     * @returns {void}
     */
    function updateSettingsLabel() {
        const behavior = state.draft.repeatWhenIdle
            ? " · 自动重排"
            : " · 执行一次";
        const interruption = state.draft.defaultInterruptionFlow
            ? " · 默认收尾"
            : ` · ${Model.interruptionModeLabel(
                state.draft.interruptionMode
            )}`;
        elements.settingsLabel.textContent =
            Model.priorityLabel(state.draft.priority) + behavior + interruption;
    }

    /**
     * @param {string} kind
     * @returns {void}
     */
    function addRootStep(kind) {
        if (!state.draft) {
            return;
        }
        state.draft.steps.push(Model.newStep(
            Model.defaultModule(state.modules),
            kind
        ));
        markDirty(true);
    }

    /**
     * @returns {void}
     */
    function animateEditor() {
        if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
            return;
        }
        elements.form.animate(
            [{ opacity: 0.5, transform: "translateY(6px)" }, { opacity: 1, transform: "none" }],
            { duration: 180, easing: "ease-out" }
        );
    }

    /**
     * @param {boolean} rerender
     * @returns {void}
     */
    function markDirty(rerender = false) {
        state.dirty = true;
        state.taskEditSerial++;
        if (rerender) {
            renderEditor();
        } else {
            updateActions();
        }
    }

    /**
     * @param {boolean} rerender
     * @returns {void}
     */
    function rerenderEditor(rerender = false) {
        markDirty(Boolean(rerender));
    }

    /**
     * @returns {Array<string>}
     */
    function validationErrors() {
        const errors = [];
        const draft = state.draft;
        if (!draft) {
            return ["尚未选择任务"];
        }
        if (!String(draft.name || "").trim()) {
            errors.push("任务名称不能为空");
        }
        if (!Model.PRIORITIES.includes(draft.priority)) {
            errors.push("任务优先级无效");
        }
        if (draft.repeatWhenIdle && draft.priority !== "BACKGROUND") {
            errors.push("只有后台任务可以自动重新排队");
        }
        if (!Array.isArray(draft.steps) || draft.steps.length === 0) {
            errors.push("任务至少需要一个根步骤");
        } else {
            if (draft.steps.length > Model.MAX_ROOT_STEPS) {
                errors.push(
                    `根步骤不能超过 ${Model.MAX_ROOT_STEPS} 个`
                );
            }
            const stats = taskStepStats(draft.steps);
            if (stats.total > Model.MAX_TOTAL_STEPS) {
                errors.push(`递归步骤总数不能超过 ${Model.MAX_TOTAL_STEPS} 个`);
            }
            if (stats.depth > Model.MAX_STEP_DEPTH) {
                errors.push(`递归步骤深度不能超过 ${Model.MAX_STEP_DEPTH} 层`);
            }
            errors.push(...validateStepList(draft.steps, "步骤"));
        }
        errors.push(...validateInterruption(draft));
        return errors;
    }

    /**
     * @param {Array<object>} steps
     * @returns {{total: number, depth: number}}
     */
    function taskStepStats(steps) {
        const stats = { total: 0, depth: 0 };
        const visit = (current, depth) => {
            for (const step of Array.isArray(current) ? current : []) {
                stats.total++;
                stats.depth = Math.max(stats.depth, depth);
                visit(step.steps, depth + 1);
                visit(step.otherwise, depth + 1);
            }
        };
        visit(steps, 1);
        return stats;
    }

    /**
     * @param {Array<object>} steps
     * @param {string} prefix
     * @returns {Array<string>}
     */
    function validateStepList(steps, prefix) {
        const errors = [];
        if (!Array.isArray(steps)) {
            return [`${prefix}必须是步骤列表`];
        }
        steps.forEach((step, index) => {
            const label = `${prefix} ${index + 1}`;
            const kind = step?.kind || "ACTION";
            if (!Model.STEP_KINDS.includes(kind)) {
                errors.push(`${label}的步骤类型无效`);
                return;
            }
            if ((kind === "ACTION" || kind === "CREATE_TASK")
                    && ((step.steps || []).length > 0
                        || (step.otherwise || []).length > 0)) {
                errors.push(`${label}不能包含子步骤`);
            }
            if ((kind === "IF" || kind === "REPEAT")
                    && (step.steps || []).length === 0) {
                errors.push(`${label}至少需要一个子步骤`);
            }
            if (kind === "REPEAT" && (step.otherwise || []).length > 0) {
                errors.push(`${label}的循环步骤不能配置否则分支`);
            }
            if (kind === "ACTION") {
                errors.push(...validateActionStep(step, label));
            } else if (kind === "CREATE_TASK") {
                errors.push(...validateCreateTaskStep(step, label));
            } else {
                errors.push(...validateControlStep(step, label));
            }
            errors.push(...validateStepList(step.steps || [], `${label} 子步骤`));
            errors.push(...validateStepList(
                step.otherwise || [],
                `${label} 否则分支`
            ));
        });
        return errors;
    }

    /**
     * @param {object} step
     * @param {string} label
     * @returns {Array<string>}
     */
    function validateActionStep(step, label) {
        const errors = [];
        const module = state.modules.find(
            (candidate) => candidate.id === step.moduleId
        );
        if (!module || Model.moduleKind(module) !== "ACTION") {
            errors.push(`${label}必须选择一个基础动作`);
            return errors;
        }
        const parameters = step.parameters || {};
        if (Model.isInteractionStep(step)) {
            return validateInteractionAction(step, label);
        }
        for (const parameter of module.parameters || []) {
            const value = String(parameters[parameter.key] ?? "").trim();
            if (parameter.required && !value) {
                errors.push(`${label}必须填写“${parameter.label}”`);
                continue;
            }
            if (!value) {
                continue;
            }
            if (parameterExpectsReference(parameter)) {
                if (!validateReference(parameter, value, step.moduleId)) {
                    errors.push(`${label}的“${parameter.label}”引用不可用`);
                }
            }
            if (parameter.kind === "integer") {
                const integerError = validateInteger(parameter, value);
                if (integerError) {
                    errors.push(`${label}的“${parameter.label}”${integerError}`);
                }
            }
            if (parameter.kind === "select"
                    && !selectOptions(parameter).includes(value)) {
                errors.push(`${label}的“${parameter.label}”不是有效选项`);
            }
        }
        return errors;
    }

    /**
     * @param {object} step
     * @param {string} label
     * @returns {Array<string>}
     */
    function validateInteractionAction(step, label) {
        const errors = Model.validateInteractionStep(step).map(
            (message) => `${label}：${message}`
        );
        for (const key of ["areaRef", "targetRef"]) {
            const fallback = Model.isInteractionStep(step, "COLLECT")
                && key === "areaRef" ? "detector" : "";
            const value = step.parameters?.[key] ?? fallback;
            if (!value) {
                continue;
            }
            if (value === "detector") {
                const detectors = editingTaskTriggers().filter(
                    (trigger) => trigger.type === "DROPPED_ITEMS"
                );
                if (detectors.length > 1) {
                    errors.push(`${label}有多个掉落物检测器，请明确选择拾取范围`);
                }
                continue;
            }
            const reference = Model.parseReference(value);
            const resources = {
                coordinate: state.coordinates,
                region: state.regions,
                "category-zone": state.categoryZones
            };
            if (reference && reference.type !== "output"
                    && !(resources[reference.type] || []).some(
                        (resource) => resource.id === reference.id
                    )) {
                errors.push(`${label}选择的地址已不存在，请重新选择`);
            }
        }
        return errors;
    }

    /**
     * @param {object} step
     * @param {string} label
     * @returns {Array<string>}
     */
    function validateCreateTaskStep(step, label) {
        const errors = [];
        const reference = String(step.parameters?.taskRef || "");
        const parsed = Model.parseReference(reference);
        if (!parsed || parsed.type !== "task") {
            errors.push(`${label}必须选择一个任务`);
        } else if (parsed.id === state.selectedId) {
            errors.push(`${label}不能创建当前任务自身`);
        } else if (!state.tasks.some((task) => task.id === parsed.id)) {
            errors.push(`${label}引用的任务不存在`);
        }
        const priority = String(step.parameters?.priority || "");
        if (priority && !Model.PRIORITIES.includes(priority)) {
            errors.push(`${label}的新任务优先级无效`);
        }
        return errors;
    }

    /**
     * @param {object} step
     * @param {string} label
     * @returns {Array<string>}
     */
    function validateControlStep(step, label) {
        const errors = [];
        const condition = String(step.parameters?.condition || "");
        if (!Model.CONDITIONS.includes(condition)) {
            errors.push(`${label}的条件无效`);
        }
        if (Model.conditionNeedsThreshold(condition)) {
            const threshold = Number(step.parameters?.foodLevelThreshold);
            if (!Number.isInteger(threshold)
                    || threshold < 0
                    || threshold > 20) {
                errors.push(`${label}的饱食度阈值必须是 0 到 20 的整数`);
            }
        }
        if ((step.kind || "ACTION") === "REPEAT") {
            const maximum = Number(step.parameters?.maxIterations);
            if (!Number.isInteger(maximum)
                    || maximum < 1
                    || maximum > Model.MAX_REPEAT_ITERATIONS) {
                errors.push(
                    `${label}的最大循环次数必须是 1 到 `
                    + `${Model.MAX_REPEAT_ITERATIONS} 的整数`
                );
            }
        }
        return errors;
    }

    /**
     * @param {object} task
     * @returns {Array<string>}
     */
    function validateInterruption(task) {
        const errors = [];
        if (!Model.INTERRUPTION_MODES.includes(task.interruptionMode)) {
            errors.push("中断收尾模式无效");
        }
        if (task.defaultInterruptionFlow && task.interruptionMode !== "NONE") {
            errors.push("默认中断收尾任务不能同时选择其他收尾模式");
        }
        if (task.defaultInterruptionFlow && task.repeatWhenIdle) {
            errors.push("默认中断收尾任务不能自动重新排队");
        }
        if (task.defaultInterruptionFlow && containsCreateTask(task.steps)) {
            errors.push("默认中断收尾任务不能创建其他任务");
        }
        const otherDefault = state.tasks.find((candidate) => (
            candidate.defaultInterruptionFlow
            && candidate.id !== state.selectedId
        ));
        if (task.defaultInterruptionFlow && otherDefault) {
            errors.push(`已有默认中断收尾任务“${otherDefault.name}”`);
        }
        if (task.interruptionMode === "CUSTOM") {
            const parsed = Model.parseReference(task.interruptionTaskRef);
            if (!parsed || parsed.type !== "task" || !parsed.id) {
                errors.push("指定中断收尾时必须选择一个任务");
            } else if (parsed.id === state.selectedId) {
                errors.push("中断收尾不能引用当前任务自身");
            } else if (!state.tasks.some((candidate) => candidate.id === parsed.id)) {
                errors.push("中断收尾引用的任务不存在");
            }
        }
        return errors;
    }

    /**
     * @param {Array<object>} steps
     * @returns {boolean}
     */
    function containsCreateTask(steps) {
        return (Array.isArray(steps) ? steps : []).some((step) => (
            (step.kind || "ACTION") === "CREATE_TASK"
            || containsCreateTask(step.steps)
            || containsCreateTask(step.otherwise)
        ));
    }

    /**
     * @param {object} parameter
     * @returns {boolean}
     */
    function parameterExpectsReference(parameter) {
        return parameterReferenceTypes(parameter).length > 0;
    }

    /**
     * @param {object} parameter
     * @returns {Array<string>}
     */
    function parameterReferenceTypes(parameter) {
        const key = String(parameter.key || "");
        if (key === "taskRef" || parameter.kind === "task-reference") {
            return ["task"];
        }
        if (key === "regionRef" || parameter.kind === "region-point") {
            return ["region"];
        }
        if (key === "categoryZoneRef"
                || parameter.kind === "category-zone-point") {
            return ["category-zone"];
        }
        if (key === "areaRef" || parameter.kind === "area-reference") {
            return ["coordinate", "region"];
        }
        if (key === "targetRef") {
            return ["coordinate", "output"];
        }
        if (
            parameter.kind === "coordinate-point"
            || parameter.kind === "coordinate-reference"
        ) {
            return ["coordinate"];
        }
        if (
            parameter.kind === "region-point"
            || parameter.kind === "region-reference"
        ) {
            return ["region"];
        }
        if (
            parameter.kind === "category-zone-point"
            || parameter.kind === "category-zone-reference"
        ) {
            return ["category-zone"];
        }
        if (parameter.kind === "container-reference") {
            return ["output"];
        }
        if (parameter.kind === "resource-reference") {
            return ["coordinate", "region", "category-zone", "task"];
        }
        return [];
    }

    /**
     * @param {object} parameter
     * @param {string} value
     * @param {string} moduleId
     * @returns {boolean}
     */
    function validateReference(parameter, value, moduleId) {
        if (parameter.key === "targetRef" && Model.isOutputReference(value)) {
            return validOutputReference(moduleId, value);
        }
        const parsed = Model.parseReference(value);
        const allowed = parameter.key === "targetRef"
            ? targetReferenceTypesFor(moduleId)
            : parameterReferenceTypes(parameter);
        if (!parsed || !allowed.includes(parsed.type)) {
            return false;
        }
        if (parsed.type === "output") {
            return Model.OUTPUT_REFERENCES.includes(value);
        }
        if (parsed.type === "coordinate") {
            return state.coordinates.some((point) => point.id === parsed.id);
        }
        if (parsed.type === "region") {
            return state.regions.some((region) => region.id === parsed.id);
        }
        if (parsed.type === "category-zone") {
            return state.categoryZones.some((zone) => zone.id === parsed.id);
        }
        if (parsed.type === "task") {
            return state.tasks.some((task) => task.id === parsed.id);
        }
        return false;
    }

    /**
     * @param {string} moduleId
     * @returns {Array<string>}
     */
    function targetReferenceTypesFor(moduleId) {
        if (
            moduleId === "MOVE_TO_COORDINATE"
            || moduleId === "DEPOSIT_ITEMS_TO_CONTAINERS"
        ) {
            return ["coordinate", "output"];
        }
        if (moduleId === "WITHDRAW_FOOD") {
            return ["coordinate"];
        }
        const outputModules = new Set([
            "WITHDRAW_ITEMS",
            "READ_CONTAINER"
        ]);
        return outputModules.has(moduleId) ? ["output"] : ["coordinate"];
    }

    /**
     * @param {string} moduleId
     * @param {string} value
     * @returns {boolean}
     */
    function validOutputReference(moduleId, value) {
        const allowed = {
            MOVE_TO_COORDINATE: Model.OUTPUT_REFERENCES,
            WITHDRAW_ITEMS: ["output:container"],
            DEPOSIT_ITEMS_TO_CONTAINERS: Model.OUTPUT_REFERENCES,
            READ_CONTAINER: ["output:marker"]
        };
        return (allowed[moduleId] || []).includes(value);
    }

    /**
     * @param {object} parameter
     * @param {string} value
     * @returns {string}
     */
    function validateInteger(parameter, value) {
        const number = Number(value);
        if (!Number.isInteger(number)) {
            return "必须是整数";
        }
        const minimum = Number(parameter.min ?? parameter.minimum);
        if (Number.isFinite(minimum) && number < minimum) {
            return `不能小于 ${minimum}`;
        }
        const maximum = Number(parameter.max ?? parameter.maximum);
        if (Number.isFinite(maximum) && number > maximum) {
            return `不能大于 ${maximum}`;
        }
        return "";
    }

    /**
     * @param {object} parameter
     * @returns {Array<string>}
     */
    function selectOptions(parameter) {
        return Array.isArray(parameter.options)
            ? parameter.options.map((option) => String(option))
            : [];
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
     * @returns {void}
     */
    function updateActions() {
        const errors = validationErrors();
        elements.editorTitle.textContent = state.draft?.name || "任务编辑器";
        elements.draftStatus.textContent = !state.draft
            ? "未选择任务"
            : !state.selectedId ? "未保存的新任务"
            : state.dirty ? "有未保存修改" : "已保存";
        elements.draftStatus.classList.toggle("is-dirty", state.dirty);
        elements.errors.replaceChildren();
        if (state.dirty && errors.length > 0) {
            elements.errors.append(
                Stowling.element("strong", "", "请修正以下问题：")
            );
            for (const error of errors) {
                elements.errors.append(Stowling.element("p", "", error));
            }
        }
        elements.save.disabled = state.presetMode
            || (!state.dirty && !state.triggerDirty)
            || errors.length > 0;
        elements.discard.disabled = !state.dirty && !state.triggerDirty;
        elements.remove.disabled = state.presetMode || !state.selectedId;
    }

    /**
     * @returns {Promise<void>}
     */
    async function saveTask() {
        const pendingTrigger = state.triggerDirty ? state.triggerDraft : null;
        const triggerSerial = state.triggerEditSerial;
        if (pendingTrigger && triggerValidationErrors().length > 0) {
            elements.triggerSettings.open = true;
            updateTriggerActions();
            Stowling.showToast("请先补全检测器的设置", true);
            return;
        }
        if (state.selectedId && !state.dirty && pendingTrigger) {
            await saveTrigger();
            return;
        }
        const errors = validationErrors();
        if (errors.length > 0) {
            updateActions();
            return;
        }
        const requestTaskId = state.selectedId;
        const draftSnapshot = state.draft;
        const editSerial = state.taskEditSerial;
        const knownIds = new Set(state.tasks.map((task) => task.id));
        const payload = Model.payloadTask(draftSnapshot);
        try {
            let response;
            if (requestTaskId) {
                response = await Stowling.request(
                    `/v1/tasks/${encodeURIComponent(requestTaskId)}`,
                    {
                        method: "PUT",
                        headers: { "Content-Type": "application/json" },
                        body: JSON.stringify({
                            revision: state.revision,
                            ...payload
                        })
                    }
                );
            } else {
                response = await Stowling.request("/v1/tasks", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify(payload)
                });
            }
            state.revision = String(response?.revision || state.revision);
            state.tasks = Array.isArray(response?.tasks)
                ? response.tasks.map(Model.normalizeTask)
                : state.tasks;
            const saved = requestTaskId
                ? state.tasks.find((task) => task.id === requestTaskId)
                : state.tasks.find((task) => !knownIds.has(task.id))
                    || state.tasks.find((task) => (
                        task.name === payload.name
                        && task.folderId === (payload.folderId || "")
                    ));
            const stillEditing = state.selectedId === requestTaskId
                && state.draft === draftSnapshot;
            const unchanged = stillEditing
                && state.taskEditSerial === editSerial;
            if (unchanged) {
                state.selectedId = saved?.id || "";
                state.draft = saved ? Model.normalizeTask(saved) : null;
                state.dirty = false;
                state.taskEditSerial++;
                if (!state.tasks.some(
                    (task) => task.id === state.runTaskId
                )) {
                    state.runTaskId = saved?.id || "";
                }
                renderEditor();
            } else if (stillEditing) {
                if (!requestTaskId && saved) {
                    state.selectedId = saved.id;
                }
                elements.revision.textContent = Stowling.revisionLabel(
                    state.revision
                );
                if (!state.tasks.some(
                    (task) => task.id === state.runTaskId
                )) {
                    state.runTaskId = saved?.id || state.runTaskId;
                }
                renderTriggerList();
                renderTriggerEditor();
                updateActions();
            }
            renderTaskList();
            renderTriggerList();
            renderRunControls();
            let detectorSaved = false;
            if (stillEditing && saved && pendingTrigger
                    && state.triggerDraft === pendingTrigger) {
                pendingTrigger.targetTaskId = saved.id;
                if (state.triggerEditSerial !== triggerSerial) {
                    updateActions();
                    Stowling.showToast("任务已保存，检测器有新修改，请再次保存");
                    return;
                }
                detectorSaved = await saveTrigger();
                if (!detectorSaved) {
                    Stowling.showToast("任务已保存，检测器未保存，请重试保存检测器", true);
                    return;
                }
                renderEditor();
            }
            Stowling.showToast(detectorSaved ? "任务与检测器已保存" : "任务已保存");
        } catch (error) {
            Stowling.handleError(error, "保存任务失败");
        }
    }

    /**
     * @returns {Promise<void>}
     */
    async function deleteTask() {
        const deletedTaskId = state.selectedId;
        if (!deletedTaskId || !state.draft) {
            return;
        }
        if (!window.confirm(`删除任务“${state.draft.name}”？`)) {
            return;
        }
        try {
            const response = await Stowling.request(
                `/v1/tasks/${encodeURIComponent(deletedTaskId)}`,
                {
                    method: "DELETE",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ revision: state.revision })
                }
            );
            state.revision = String(response?.revision || state.revision);
            state.tasks = Array.isArray(response?.tasks)
                ? response.tasks.map(Model.normalizeTask)
                : state.tasks;
            const stillSelected = state.selectedId === deletedTaskId;
            if (stillSelected) {
                state.selectedId = "";
                state.draft = null;
                state.dirty = false;
                state.selectedTriggerId = "";
                state.triggerDraft = null;
                state.triggerDirty = false;
                state.triggerEditSerial++;
                if (!state.tasks.some(
                    (task) => task.id === state.runTaskId
                )) {
                    state.runTaskId = state.tasks[0]?.id || "";
                }
                renderEditor();
            }
            renderTaskList();
            renderRunControls();
            try {
                const triggersResponse = await Stowling.request(
                    "/v1/task-triggers"
                );
                state.revision = String(
                    triggersResponse?.revision || state.revision
                );
                state.triggers = Array.isArray(triggersResponse?.triggers)
                    ? triggersResponse.triggers.map(
                        TriggerModel.normalizeTrigger
                    )
                    : state.triggers;
                renderTriggerList();
                if (stillSelected) {
                    renderTriggerEditor();
                }
                await loadRuns();
                Stowling.showToast("任务已删除");
            } catch (error) {
                Stowling.handleError(error, "任务已删除，但刷新触发器失败");
            }
        } catch (error) {
            Stowling.handleError(error, "删除任务失败");
        }
    }

    /**
     * @returns {Promise<void>}
     */
    async function runTask() {
        if (!state.runTaskId) {
            return;
        }
        try {
            setTaskRoute("monitor");
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
            renderTriggerList();
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
            const edit = Stowling.element("button", "secondary compact", "查看任务配置");
            edit.type = "button";
            edit.addEventListener("click", () => {
                // Use the existing selection path, including unsaved-change confirmation.
                selectTask(task, true);
            });
            actions.append(edit);
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
                name.title = "查看任务配置";
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
    window.addEventListener("hashchange", applyTaskHashRoute);
    elements.search.addEventListener("input", () => {
        state.query = elements.search.value.trim().toLocaleLowerCase();
        renderTaskList();
    });
    elements.newFolder.addEventListener(
        "click",
        () => openFolderForm()
    );
    elements.folderForm.addEventListener("submit", (event) => {
        event.preventDefault();
        void saveFolder();
    });
    elements.cancelFolder.addEventListener("click", closeFolderForm);
    elements.deleteFolder.addEventListener("click", () => {
        void deleteFolder();
    });
    elements.newTask.addEventListener("click", startNewTask);
    elements.newCacheTask.addEventListener("click", startCacheTask);
    elements.newSortTask.addEventListener("click", startSortTask);
    elements.newMarkerTask.addEventListener("click", startMarkerTask);
    elements.newInterruptionTask.addEventListener(
        "click",
        startInterruptionTask
    );
    elements.newAutoEatTask.addEventListener("click", showAutoEatPresetForm);
    elements.cancelAutoEatPreset.addEventListener("click", hideAutoEatPresetForm);
    elements.autoEatForm.addEventListener("submit", (event) => {
        event.preventDefault();
        void createAutoEatPreset();
    });
    for (const control of [
        elements.autoEatCoordinate,
        elements.autoEatFood,
        elements.autoEatThreshold
    ]) {
        control.addEventListener("input", () => {
            syncAutoEatDraft();
            renderEditor();
        });
        control.addEventListener("change", () => {
            syncAutoEatDraft();
            renderEditor();
        });
    }
    elements.name.addEventListener("input", () => {
        state.draft.name = elements.name.value;
        markDirty();
    });
    elements.folder.addEventListener("change", () => {
        state.draft.folderId = elements.folder.value;
        markDirty();
    });
    elements.priority.addEventListener("change", () => {
        state.draft.priority = elements.priority.value;
        if (state.draft.priority !== "BACKGROUND") {
            state.draft.repeatWhenIdle = false;
        }
        markDirty(true);
    });
    elements.repeat.addEventListener("change", () => {
        state.draft.repeatWhenIdle = elements.repeat.checked;
        updateSettingsLabel();
        markDirty();
    });
    elements.interruptionMode.addEventListener("change", () => {
        state.draft.interruptionMode = elements.interruptionMode.value;
        if (state.draft.interruptionMode !== "CUSTOM") {
            state.draft.interruptionTaskRef = "";
        }
        if (state.draft.interruptionMode === "NONE") {
            state.draft.requeueAfterInterruption = false;
        }
        renderEditor();
        markDirty();
    });
    elements.interruptionTaskRef.addEventListener("change", () => {
        state.draft.interruptionTaskRef =
            elements.interruptionTaskRef.value;
        markDirty();
    });
    elements.defaultInterruption.addEventListener("change", () => {
        state.draft.defaultInterruptionFlow =
            elements.defaultInterruption.checked;
        if (state.draft.defaultInterruptionFlow) {
            state.draft.interruptionMode = "NONE";
            state.draft.interruptionTaskRef = "";
            state.draft.repeatWhenIdle = false;
        }
        renderEditor();
        markDirty();
    });
    elements.requeueAfterInterruption.addEventListener("change", () => {
        state.draft.requeueAfterInterruption =
            elements.requeueAfterInterruption.checked;
        markDirty();
    });
    elements.newTrigger.addEventListener("click", startNewTrigger);
    elements.triggerName.addEventListener("input", () => {
        state.triggerDraft.name = elements.triggerName.value;
        markTriggerDirty();
    });
    elements.triggerType.addEventListener("change", () => {
        state.triggerDraft.type = elements.triggerType.value;
        renderTriggerEditor();
        markTriggerDirty();
    });
    elements.triggerPriority.addEventListener("change", () => {
        state.triggerDraft.priority = elements.triggerPriority.value;
        markTriggerDirty();
    });
    elements.triggerInterval.addEventListener("input", () => {
        state.triggerDraft.intervalTicks = Math.round(
            Number(elements.triggerInterval.value) * 20
        );
        markTriggerDirty();
    });
    elements.triggerCooldown.addEventListener("input", () => {
        state.triggerDraft.cooldownTicks = Math.round(
            Number(elements.triggerCooldown.value) * 20
        );
        markTriggerDirty();
    });
    elements.triggerCoordinate.addEventListener("change", () => {
        state.triggerDraft.coordinateId = elements.triggerCoordinate.value;
        markTriggerDirty();
    });
    elements.triggerCacheRegion.addEventListener("change", () => {
        state.triggerDraft.cacheRegionId =
            elements.triggerCacheRegion.value;
        markTriggerDirty();
    });
    elements.triggerItemFilter.addEventListener("input", () => {
        state.triggerDraft.itemFilter = elements.triggerItemFilter.value;
        markTriggerDirty();
    });
    elements.triggerFoodThreshold.addEventListener("input", () => {
        state.triggerDraft.foodLevelThreshold = Number(
            elements.triggerFoodThreshold.value
        );
        markTriggerDirty();
    });
    elements.addStep.addEventListener("click", () => addRootStep("ACTION"));
    elements.addIfStep.addEventListener("click", () => addRootStep("IF"));
    elements.addRepeatStep.addEventListener("click", () => addRootStep("REPEAT"));
    elements.addCreateTaskStep.addEventListener(
        "click",
        () => addRootStep("CREATE_TASK")
    );
    elements.form.addEventListener("submit", (event) => {
        event.preventDefault();
        void saveTask();
    });
    elements.triggerForm.addEventListener("submit", (event) => {
        event.preventDefault();
        void saveTrigger();
    });
    elements.runSelect.addEventListener("change", () => {
        state.runTaskId = elements.runSelect.value;
        updateRunActions();
    });
    elements.discard.addEventListener("click", () => {
        if (state.presetMode) {
            resetAutoEatPreset();
            return;
        }
        const task = state.tasks.find((entry) => entry.id === state.selectedId);
        const trigger = state.triggers.find(
            (entry) => entry.id === state.selectedTriggerId
                && entry.targetTaskId === state.selectedId
        );
        state.draft = task ? Model.normalizeTask(task) : null;
        state.dirty = false;
        state.taskEditSerial++;
        state.selectedTriggerId = trigger?.id || "";
        state.triggerDraft = trigger ? TriggerModel.normalizeTrigger(trigger) : null;
        state.triggerDirty = false;
        state.triggerEditSerial++;
        renderEditor();
        renderTaskList();
    });
    elements.run.addEventListener("click", () => void runTask());
    elements.remove.addEventListener("click", () => void deleteTask());
    elements.discardTrigger.addEventListener("click", () => {
        const trigger = state.triggers.find(
            (entry) => entry.id === state.selectedTriggerId
        );
        if (trigger) {
            selectTrigger(trigger);
        } else {
            state.triggerDraft = null;
            state.triggerDirty = false;
            state.triggerEditSerial++;
            renderEditor();
        }
    });
    elements.deleteTrigger.addEventListener("click", () => {
        void deleteTrigger();
    });
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
    renderEditor();
    renderMonitorLogs();
    updateTaskFilters();
    updateFolderControls();
    applyTaskHashRoute();
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
