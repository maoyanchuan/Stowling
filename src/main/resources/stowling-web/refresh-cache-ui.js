(() => {
    "use strict";

    const moduleId = "REFRESH_CONTAINER_CACHE";
    const runLabels = Object.freeze({
        QUEUED: "排队中",
        RUNNING: "运行中",
        WAITING: "运行中",
        INTERRUPTING: "正在停止",
        INTERRUPTION_FAILED: "收尾失败"
    });
    const defaultKinds = Object.freeze({
        chests: true,
        barrels: true,
        shulkerBoxes: true,
        otherContainers: false
    });

    /** @param {object} task @returns {boolean} */
    function isRefreshTask(task) {
        const step = task?.steps?.[0];
        return task?.steps?.length === 1 && step?.moduleId === moduleId
            && String(step.parameters?.markerOnly).toLowerCase() !== "true";
    }

    /** @param {object} parameters @returns {object} */
    function settings(parameters = {}) {
        const kinds = Object.fromEntries(Object.entries(defaultKinds).map(([name, enabled]) => {
            const value = parameters[name];
            return [name, value === undefined ? enabled : String(value).toLowerCase() === "true"];
        }));
        return {
            kinds,
            targetRef: parameters.targetRef || "",
            scanMarkers: String(parameters.scanMarkers).toLowerCase() === "true",
            includeMarkers: String(parameters.includeMarkersInInventory).toLowerCase() === "true"
        };
    }

    /** @param {object} draft @returns {object} */
    function parameters(draft) {
        if (!Object.values(draft.kinds).some(Boolean)) {
            throw new Error("至少启用一类容器");
        }
        const scanMarkers = draft.targetRef.startsWith("category-zone:") && draft.scanMarkers;
        return {
            targetRef: draft.targetRef,
            ...Object.fromEntries(Object.entries(draft.kinds).map(([key, value]) =>
                [key, String(value)])),
            scanMarkers: String(scanMarkers),
            includeMarkersInInventory: String(scanMarkers && draft.includeMarkers)
        };
    }

    /** @param {object} draft @returns {object} */
    function schedulePayload({mode, time, zoneId, intervalMinutes}) {
        if (mode === "DAILY") {
            return {mode, time, zoneId};
        }
        if (mode === "INTERVAL") {
            if (!Number.isSafeInteger(intervalMinutes) || intervalMinutes < 1
                    || intervalMinutes > 2147483647) {
                throw new Error("请输入有效的正整数时间间隔");
            }
            return {mode, intervalMinutes};
        }
        return {mode: "MANUAL"};
    }

    /**
     * @param {boolean} scheduled
     * @param {boolean} scheduleEnabled
     * @param {string|null} runState
     * @returns {{checked: boolean, stopping: boolean, label: string}}
     */
    function taskSwitch(scheduled, scheduleEnabled, runState) {
        const label = Object.hasOwn(runLabels, runState) ? runLabels[runState] : "";
        const stopping = runState === "INTERRUPTING" || runState === "INTERRUPTION_FAILED";
        return {
            checked: scheduled ? scheduleEnabled : Boolean(label) && !stopping,
            stopping,
            label: label || (scheduled && scheduleEnabled ? "已启用" : "已暂停")
        };
    }

    globalThis.StowlingRefreshCacheModel = Object.freeze({
        isRefreshTask, settings, parameters, schedulePayload, taskSwitch
    });
    if (typeof document === "undefined") return;

    const S = window.Stowling;
    const Settings = window.StowlingSettings;
    const Autosave = window.StowlingTaskAutosave;
    const endpoint = "/v1/tasks";
    /** @param {string} value @returns {HTMLElement} */
    const id = (value) => document.getElementById(value);
    const add = id("new-refresh-cache-task");
    const draftCard = id("refresh-cache-editor");
    const draftToggle = id("toggle-refresh-cache-draft");
    const draftBody = id("refresh-cache-draft-body");
    const form = id("refresh-cache-form");
    const fieldset = form.querySelector("fieldset");
    const list = id("refresh-cache-list");
    const target = id("refresh-cache-target");
    const markerScan = Settings.switchControl(id("refresh-scan-markers"));
    const markerStock = Settings.switchControl(id("refresh-include-markers"));
    const kinds = [...form.querySelectorAll("[data-refresh-kind]")]
        .map((input) => Settings.switchControl(input));
    const kindSelection = id("refresh-kind-selection");
    const kindDisclosure = Settings.bindDisclosure(id("refresh-kind-settings"),
        id("refresh-kind-summary"), id("refresh-kind-options"));
    const mode = id("refresh-schedule-mode");
    const time = id("refresh-daily-time");
    const interval = id("refresh-interval");
    const unit = id("refresh-interval-unit");
    const remove = id("delete-refresh-task");
    const message = id("refresh-cache-message");
    const selects = window.StowlingTaskSelect.create(form);
    const browserZone = Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";
    const savedCards = new Map();
    let nextTimes = new Map();
    let runStates = new Map();
    let enabledSchedules = new Set();
    let pendingSwitch = null;
    let locations = new Map();
    let tasks = [];
    let schedules = {};
    let revision = "";
    let current = null;
    let ready = false;
    let busy = false;
    const autosave = Autosave.create(form, save, () => busy);

    /** @param {HTMLElement} button @param {HTMLElement} body
     * @param {boolean} expanded @returns {void} */
    function disclose(button, body, expanded) {
        button.setAttribute("aria-expanded", String(expanded));
        button.setAttribute("aria-label", (expanded ? "收起" : "展开") + "刷新缓存设置");
        body.inert = !expanded;
        void Settings.reveal(body, expanded);
    }

    /** @returns {void} */
    function controls() {
        const layout = target.value.startsWith("category-zone:");
        void Settings.reveal(id("refresh-marker-field"), layout);
        void Settings.reveal(id("refresh-marker-stock-field"), layout && markerScan.checked);
        markerScan.disabled = !layout;
        markerStock.disabled = !layout || !markerScan.checked;
        void Settings.reveal(id("refresh-daily-field"), mode.value === "DAILY");
        void Settings.reveal(id("refresh-interval-field"), mode.value === "INTERVAL");
        time.disabled = mode.value !== "DAILY";
        unit.disabled = interval.disabled = mode.value !== "INTERVAL";
        const enabledKinds = kinds.filter((input) => input.checked)
            .map((input) => input.labels[0].textContent.trim());
        kindSelection.textContent = enabledKinds.join("、") || "未选择容器";
        const disabled = !ready || busy || !S.isConnected();
        add.disabled = disabled;
        fieldset.disabled = disabled;
        const isDraft = current?.task === null;
        draftCard.hidden = !isDraft;
        draftToggle.disabled = disabled;
        disclose(draftToggle, draftBody, isDraft && current.expanded);
        for (const [taskId, {button, body, toggle, status}] of savedCards) {
            const selected = current?.task?.id === taskId;
            const dirty = selected && current.dirty;
            const scheduled = Boolean(schedules[taskId]);
            const state = taskSwitch(scheduled, enabledSchedules.has(taskId), runStates.get(taskId));
            button.disabled = disabled;
            toggle.checked = pendingSwitch?.id === taskId ? pendingSwitch.enabled : state.checked;
            toggle.disabled = disabled || dirty || (state.stopping && !state.checked);
            toggle.setAttribute("aria-busy", String(pendingSwitch?.id === taskId));
            toggle.title = dirty ? "修改保存后可切换任务"
                : state.checked ? "停止任务" : "启动任务";
            status.textContent = dirty ? "未保存修改" : state.label;
            disclose(button, body, selected && current.expanded);
        }
        selects.refresh(true);
    }

    /** @returns {void} */
    function syncCount() {
        document.dispatchEvent(new CustomEvent("stowling:refresh-task-count", {
            detail: {count: tasks.filter(isRefreshTask).length, draftActive: current?.task === null}
        }));
    }

    /** @returns {void} */
    function renderNextTimes() {
        for (const [taskId, {nextLabel: label}] of savedCards) {
            const schedule = schedules[taskId];
            const next = nextTimes.get(taskId);
            const zone = schedule?.zoneId || browserZone;
            label.textContent = schedule?.mode === "DAILY" ? "每天 " + schedule.time
                : schedule ? intervalLabel(schedule.intervalMinutes) : "仅手动";
            label.title = schedule?.zoneId || "";
            if (schedule && enabledSchedules.has(taskId) && next) {
                const formatted = new Intl.DateTimeFormat("zh-CN", {timeZone: zone,
                    month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit"})
                    .format(new Date(next));
                label.textContent = "下次触发 " + formatted;
                label.title = zone;
            }
        }
    }

    /** @param {number} minutes @returns {string} */
    function intervalLabel(minutes) {
        return minutes % 60 === 0 ? "每隔 " + minutes / 60 + " 小时"
            : "每隔 " + minutes + " 分钟";
    }

    /** @param {string} path @returns {SVGElement} */
    function icon(path) {
        const namespace = "http://www.w3.org/2000/svg";
        const svg = document.createElementNS(namespace, "svg");
        for (const [key, value] of Object.entries({width: "18", height: "18",
            viewBox: "0 0 24 24", fill: "none", stroke: "currentColor",
            "stroke-width": "1.8", "stroke-linecap": "round", "stroke-linejoin": "round",
            "aria-hidden": "true"})) {
            svg.setAttribute(key, value);
        }
        const shape = document.createElementNS(namespace, "path");
        shape.setAttribute("d", path);
        svg.append(shape);
        return svg;
    }

    /** @returns {void} */
    function renderRows() {
        draftBody.append(form);
        list.replaceChildren();
        savedCards.clear();
        for (const task of tasks.filter(isRefreshTask)) {
            const card = S.element("article", "pickup-task-module refresh-cache-task-module");
            const header = S.element("div", "pickup-task-header");
            const title = S.element("h3", "pickup-task-title");
            title.append(icon("M20 11a8 8 0 1 0-2 6M20 4v7h-7"),
                S.element("span", "", task.name));
            const edit = S.element("button", "pickup-task-expand");
            edit.type = "button";
            edit.append(icon("m9 5 7 7-7 7"));
            const body = S.element("div", "pickup-task-body");
            body.id = "refresh-cache-body-" + task.id;
            void Settings.reveal(body, false);
            edit.setAttribute("aria-controls", body.id);
            const nextLabel = S.element("p", "refresh-task-timing field-message");
            const status = S.element("span", "visually-hidden");
            status.id = "refresh-cache-status-" + task.id;
            status.setAttribute("role", "status");
            status.setAttribute("aria-live", "polite");
            edit.addEventListener("click", () => open(task));
            const toggle = Settings.switchControl(document.createElement("input"),
                "运行任务：" + task.name);
            toggle.setAttribute("aria-describedby", status.id);
            toggle.addEventListener("change", () => void setRunning(task.id, toggle.checked));
            header.append(title, edit, toggle);
            body.append(nextLabel);
            card.append(header, body, status);
            if (current?.task?.id === task.id) body.append(form);
            list.append(card);
            savedCards.set(task.id, {button: edit, body, toggle, status, nextLabel});
        }
        controls();
        renderNextTimes();
        syncCount();
    }

    /** @param {string} selected @returns {void} */
    function renderOptions(selected) {
        target.replaceChildren(new Option("选择普通区域", ""));
        for (const [value, label] of locations) target.append(new Option(label, value));
        target.value = locations.has(selected) ? selected : "";
        window.StowlingResourcePicker?.group(target, undefined, "regions", false);
    }

    /** @param {object|null} task @returns {void} */
    function fillForm(task) {
        const draft = settings(task?.steps?.[0]?.parameters);
        const schedule = task && schedules[task.id];
        current.zone = schedule?.zoneId || browserZone;
        renderOptions(draft.targetRef);
        for (const input of kinds) input.checked = draft.kinds[input.dataset.refreshKind];
        markerScan.checked = draft.scanMarkers;
        markerStock.checked = draft.includeMarkers;
        mode.value = schedule?.mode || "MANUAL";
        time.value = schedule?.time || "04:00";
        const minutes = schedule?.intervalMinutes || 60;
        unit.value = minutes % 60 === 0 ? "60" : "1";
        interval.value = String(minutes / Number(unit.value));
        id("refresh-zone").textContent = current.zone;
        remove.hidden = !task;
        showMessage(task ? "" : "选择选区后自动保存");
    }

    /** @param {object|null} task @returns {void} */
    function open(task = null) {
        const row = task ? savedCards.get(task.id) : {button: draftToggle, body: draftBody};
        if (busy || !ready || !S.isConnected() || !row) return;
        if (current && current.task?.id === task?.id) {
            current.expanded = !current.expanded;
        } else {
            if (current?.dirty && !window.confirm("当前修改尚未保存。仍要切换任务吗？")) return;
            autosave.cancel();
            current = {task, revision, expanded: true, dirty: false, zone: browserZone,
                schedule: task ? schedules[task.id] : undefined};
            row.body.append(form);
            kindDisclosure.setExpanded(false);
            fillForm(task);
        }
        controls();
        syncCount();
        row.button.focus();
    }

    /** @returns {void} */
    function close() {
        autosave.cancel();
        current = null;
        draftBody.append(form);
        controls();
        syncCount();
    }

    /** @param {object} response @param {boolean} [refill] @returns {void} */
    function apply(response, refill = true) {
        revision = response.revision;
        tasks = response.tasks || [];
        schedules = response.schedules || {};
        enabledSchedules = new Set(response.enabledSchedules || []);
        if (current) {
            const taskId = current.task?.id;
            const saved = taskId && tasks.find((task) => task.id === taskId && isRefreshTask(task));
            if (taskId && !saved) {
                close();
            } else if (!current.dirty) {
                current.revision = revision;
                if (saved) {
                    current.task = saved;
                    current.schedule = schedules[taskId];
                    if (refill) fillForm(saved);
                }
            }
        }
        renderRows();
    }

    /** @returns {Promise<void>} */
    async function loadResources() {
        const [response, resources] = await Promise.all([
            S.request(endpoint),
            window.StowlingResourcePicker.load()
        ]);
        locations = new Map();
        for (const region of resources.regions) {
            locations.set("region:" + region.id, "区域 · " + region.name);
        }
        if (current) renderOptions(target.value);
        ready = true;
        apply(response);
    }

    /** @returns {Promise<void>} */
    async function load() {
        if (busy || !S.isConnected()) return;
        busy = true;
        controls();
        try {
            await loadResources();
        } catch (error) {
            S.handleError(error, "读取刷新任务失败");
        } finally {
            busy = false;
            controls();
        }
    }

    /** @returns {void} */
    function changed() {
        document.dispatchEvent(new Event("stowling:refresh-tasks-changed"));
        document.dispatchEvent(new Event("stowling:tasks-changed"));
    }

    /** @param {string} value @param {boolean} [error] @returns {void} */
    function showMessage(value, error = false) {
        message.hidden = !value;
        message.textContent = value;
        message.classList.toggle("error", error);
    }

    /** @returns {Promise<void>} */
    async function save() {
        if (!current?.dirty || busy || !S.isConnected()) return;
        try {
            const taskId = current.task?.id;
            if (!target.value) throw new Error("请选择刷新选区");
            if (mode.value === "DAILY" && !time.value) throw new Error("请输入每天触发时间");
            if (mode.value === "INTERVAL" && interval.validity?.valid === false) {
                throw new Error("请输入有效的正整数时间间隔");
            }
            const payload = {
                schedule: schedulePayload({
                    mode: mode.value,
                    time: time.value,
                    zoneId: current.zone,
                    intervalMinutes: Number(interval.value) * Number(unit.value)
                }),
                steps: [{
                    ...(taskId ? {id: current.task.steps[0].id} : {}),
                    moduleId,
                    parameters: parameters({
                        targetRef: target.value,
                        kinds: Object.fromEntries(kinds.map((input) =>
                            [input.dataset.refreshKind, input.checked])),
                        scanMarkers: markerScan.checked,
                        includeMarkers: markerStock.checked
                    })
                }]
            };
            if (taskId) {
                payload.revision = current.revision;
                for (const key of ["priority", "repeatWhenIdle", "interruptionMode",
                        "interruptionTaskRef", "defaultInterruptionFlow",
                        "requeueAfterInterruption", "folderId"]) {
                    payload[key] = current.task[key];
                }
            }
            busy = true;
            controls();
            showMessage("正在自动保存…");
            const response = taskId
                ? await Autosave.write(`${endpoint}/${taskId}`, "PUT", payload,
                    () => Autosave.latestRevision(endpoint,
                        {task: current.task, schedule: current.schedule},
                        (latest) => ({task: latest.tasks.find((task) => task.id === taskId),
                            schedule: latest.schedules[taskId]})))
                : await Autosave.write(endpoint, "POST", payload);
            const savedId = response.taskId || taskId;
            current.task = response.tasks.find((task) => task.id === savedId);
            current.dirty = false;
            remove.hidden = false;
            apply(response, false);
            changed();
            showMessage("已自动保存");
        } catch (error) {
            showMessage(error.message, true);
        } finally {
            busy = false;
            controls();
        }
    }

    /** @param {string} taskId @param {boolean} enabled @returns {Promise<void>} */
    async function setRunning(taskId, enabled) {
        if (busy || !S.isConnected()) {
            controls();
            return;
        }
        pendingSwitch = {id: taskId, enabled};
        busy = true;
        controls();
        try {
            const action = enabled ? "start" : "stop";
            const path = `/v1/tasks/${encodeURIComponent(taskId)}/${action}`;
            let previousRevision = revision;
            /** @returns {Promise<object>} */
            const request = () => S.request(path, {
                method: "POST",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({revision: previousRevision})
            });
            const response = await request().catch(async (error) => {
                if (enabled || error.status !== 409 || error.code !== "revision_conflict") {
                    throw error;
                }
                await loadResources();
                previousRevision = revision;
                return savedCards.has(taskId) ? request() : null;
            });
            if (!response) return;
            if (current?.revision === previousRevision) current.revision = response.revision;
            revision = response.revision;
            if (response.scheduleEnabled) enabledSchedules.add(taskId);
            else enabledSchedules.delete(taskId);
            if (response.runState) runStates.set(taskId, response.runState);
            else runStates.delete(taskId);
            if (response.nextAt) nextTimes.set(taskId, response.nextAt);
            else nextTimes.delete(taskId);
            renderNextTimes();
            document.dispatchEvent(new CustomEvent("stowling:task-switch-changed", {
                detail: {previousRevision, revision}
            }));
            S.showToast(enabled
                ? response.scheduleEnabled ? "已开启定时刷新" : "刷新缓存任务已提交"
                : response.runState === "INTERRUPTING" ? "正在停止刷新任务" : "刷新任务已暂停");
        } catch (error) {
            S.handleError(error, "切换刷新缓存任务失败");
        } finally {
            busy = false;
            pendingSwitch = null;
            controls();
            savedCards.get(taskId)?.toggle.focus({preventScroll: true});
        }
    }

    /** @returns {Promise<void>} */
    async function deleteTask() {
        const task = current?.task;
        if (!task || busy || !window.confirm("删除刷新缓存任务「" + task.name + "」？")) return;
        busy = true;
        controls();
        try {
            const response = await S.request("/v1/tasks/" + task.id, {
                method: "DELETE",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({revision: current.revision})
            });
            close();
            apply(response);
            changed();
            S.showToast("刷新缓存任务已删除");
        } catch (error) {
            S.handleError(error, "删除刷新缓存任务失败");
        } finally {
            busy = false;
            controls();
        }
    }

    add.addEventListener("click", () => {
        if (current?.task === null && current.expanded) {
            draftToggle.focus();
        } else {
            open();
        }
    });
    draftToggle.addEventListener("click", () => open());
    remove.addEventListener("click", () => void deleteTask());
    form.addEventListener("input", () => {
        if (!current) return;
        current.dirty = true;
        showMessage("未保存修改");
        controls();
        autosave.schedule();
    });
    form.addEventListener("submit", (event) => {
        event.preventDefault();
        void autosave.flush();
    });
    id("refresh-move-tasks").addEventListener("click", () => void load());
    document.addEventListener("stowling:tasks-changed", () => void load());
    document.addEventListener("stowling:task-switch-changed", ({detail}) => {
        if (revision === detail.previousRevision) revision = detail.revision;
        if (current?.revision === detail.previousRevision) current.revision = detail.revision;
    });
    document.addEventListener("stowling:refresh-task-status", (event) => {
        nextTimes = new Map(event.detail.triggers.filter((trigger) => trigger.type === "SCHEDULED")
            .map((trigger) => [trigger.targetTaskId, trigger.nextAt]));
        runStates = new Map(event.detail.pending.map((run) => [run.taskId, run.state]));
        renderNextTimes();
        controls();
    });
    S.onConnected(load);
})();
