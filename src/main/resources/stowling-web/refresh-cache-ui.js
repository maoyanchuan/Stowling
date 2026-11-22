(() => {
    "use strict";

    const moduleId = "REFRESH_CONTAINER_CACHE";
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

    globalThis.StowlingRefreshCacheModel = Object.freeze({
        isRefreshTask, settings, parameters, schedulePayload
    });
    if (typeof document === "undefined") return;

    const S = window.Stowling;
    const id = (value) => document.getElementById(value);
    const add = id("new-refresh-cache-task");
    const editor = id("refresh-cache-editor");
    const form = id("refresh-cache-form");
    const fieldset = form.querySelector("fieldset");
    const list = id("refresh-cache-list");
    const name = id("refresh-cache-name");
    const target = id("refresh-cache-target");
    const markerScan = id("refresh-scan-markers");
    const markerStock = id("refresh-include-markers");
    const kinds = [...form.querySelectorAll("[data-refresh-kind]")];
    const mode = id("refresh-schedule-mode");
    const time = id("refresh-daily-time");
    const interval = id("refresh-interval");
    const unit = id("refresh-interval-unit");
    const remove = id("delete-refresh-task");
    const message = id("refresh-cache-message");
    const browserZone = Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";
    const nextLabels = new Map();
    let nextTimes = new Map();
    let locations = new Map();
    let tasks = [];
    let schedules = {};
    let revision = "";
    let current = null;
    let ready = false;
    let busy = false;

    /** @returns {void} */
    function controls() {
        const layout = target.value.startsWith("category-zone:");
        id("refresh-marker-field").hidden = !layout;
        id("refresh-marker-stock-field").hidden = !layout;
        markerScan.disabled = !layout;
        markerStock.disabled = !layout || !markerScan.checked;
        if (markerStock.disabled) markerStock.checked = false;
        id("refresh-daily-field").hidden = mode.value !== "DAILY";
        id("refresh-interval-field").hidden = mode.value !== "INTERVAL";
        time.disabled = mode.value !== "DAILY";
        unit.disabled = interval.disabled = mode.value !== "INTERVAL";
        add.disabled = !ready || busy || !S.isConnected();
        fieldset.disabled = busy || !ready || !S.isConnected();
    }

    /** @returns {void} */
    function syncCount() {
        document.dispatchEvent(new CustomEvent("stowling:refresh-task-count", {
            detail: {count: tasks.filter(isRefreshTask).length, draftActive: current?.task === null}
        }));
    }

    /** @returns {void} */
    function renderNextTimes() {
        for (const [taskId, label] of nextLabels) {
            const schedule = schedules[taskId];
            const next = nextTimes.get(taskId);
            const zone = schedule?.zoneId || browserZone;
            label.textContent = schedule ? "下次触发时间同步中" : "仅手动";
            if (schedule && next) {
                const formatted = new Intl.DateTimeFormat("zh-CN", {timeZone: zone,
                    month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit"})
                    .format(new Date(next));
                label.textContent = "下次触发 " + formatted + " · " + zone;
            }
        }
    }

    /** @returns {void} */
    function renderRows() {
        list.replaceChildren();
        nextLabels.clear();
        for (const task of tasks.filter(isRefreshTask)) {
            const card = S.element("article", "move-saved-task");
            const header = S.element("div", "task-card-header");
            const edit = S.element("button", "move-task-summary");
            edit.type = "button";
            edit.setAttribute("aria-controls", editor.id);
            edit.setAttribute("aria-expanded", String(current?.task?.id === task.id));
            const kind = S.element("span", "move-task-kind", "刷");
            kind.setAttribute("aria-hidden", "true");
            const overview = S.element("span", "move-task-overview");
            const label = S.element("span", "move-task-state");
            nextLabels.set(task.id, label);
            const targetRef = task.steps[0].parameters.targetRef;
            overview.append(S.element("strong", "", task.name),
                S.element("span", "", locations.get(targetRef) || "选区已不存在"), label);
            edit.append(kind, overview);
            edit.addEventListener("click", () => open(task));
            const run = S.element("button", "secondary move-task-run", "加入队列");
            run.type = "button";
            run.setAttribute("aria-label", "加入队列：" + task.name);
            run.addEventListener("click", () => void enqueue(task.id));
            header.append(edit, run);
            card.append(header);
            list.append(card);
        }
        renderNextTimes();
        syncCount();
    }

    /** @param {string} selected @returns {void} */
    function renderOptions(selected) {
        target.replaceChildren(new Option("选择基础区域或仓储布局", ""));
        for (const [value, label] of locations) target.append(new Option(label, value));
        target.value = locations.has(selected) ? selected : "";
    }

    /** @returns {string} */
    function newName() {
        const names = new Set(tasks.map((task) => task.name.toLowerCase()));
        let candidate = "刷新缓存";
        for (let ordinal = 2; names.has(candidate.toLowerCase()); ordinal++) {
            candidate = "刷新缓存 " + ordinal;
        }
        return candidate;
    }

    /** @param {object|null} task @returns {void} */
    function open(task = null) {
        if (busy || !ready) return;
        const draft = settings(task?.steps?.[0]?.parameters);
        const schedule = task && schedules[task.id];
        current = {task, revision, zone: schedule?.zoneId || browserZone};
        name.value = task?.name || newName();
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
        id("refresh-cache-heading").textContent = task ? "编辑刷新缓存任务" : "新建刷新缓存任务";
        remove.hidden = !task;
        message.textContent = "";
        message.classList.remove("error");
        editor.hidden = false;
        controls();
        renderRows();
        name.focus();
    }

    /** @returns {void} */
    function close() {
        current = null;
        editor.hidden = true;
        renderRows();
    }

    /** @param {object} response @returns {void} */
    function apply(response) {
        revision = response.revision;
        tasks = response.tasks || [];
        schedules = response.schedules || {};
        renderRows();
    }

    /** @returns {Promise<void>} */
    async function load() {
        if (busy || !S.isConnected()) return;
        busy = true;
        controls();
        try {
            const [response, regions, zones] = await Promise.all([
                S.request("/v1/tasks"),
                S.request("/v1/regions"),
                S.request("/v1/category-zones")
            ]);
            locations = new Map();
            for (const region of regions.regions) {
                locations.set("region:" + region.id, "基础区域 · " + region.name);
            }
            for (const zone of zones.categoryZones) {
                locations.set("category-zone:" + zone.id, "仓储布局 · " + zone.name);
            }
            if (current) renderOptions(target.value);
            ready = true;
            apply(response);
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

    /** @param {Event} event @returns {Promise<void>} */
    async function save(event) {
        event.preventDefault();
        if (!current || busy) return;
        message.textContent = "";
        message.classList.remove("error");
        try {
            const taskId = current.task?.id;
            const payload = {
                name: name.value.trim(),
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
            const response = await S.request(taskId ? "/v1/tasks/" + taskId : "/v1/tasks", {
                method: taskId ? "PUT" : "POST",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify(payload)
            });
            close();
            apply(response);
            changed();
            S.showToast("刷新缓存任务已保存");
        } catch (error) {
            message.textContent = error.message;
            message.classList.add("error");
        } finally {
            busy = false;
            controls();
        }
    }

    /** @param {string} taskId @returns {Promise<void>} */
    async function enqueue(taskId) {
        if (busy || !S.isConnected()) return;
        try {
            await S.request("/v1/tasks/" + taskId + "/runs", {method: "POST"});
            S.showToast("刷新缓存任务已提交");
            document.dispatchEvent(new Event("stowling:monitor-refresh"));
        } catch (error) {
            S.handleError(error, "启动刷新缓存任务失败");
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

    add.addEventListener("click", () => open());
    id("cancel-refresh-edit").addEventListener("click", close);
    remove.addEventListener("click", () => void deleteTask());
    form.addEventListener("change", controls);
    form.addEventListener("submit", (event) => void save(event));
    id("refresh-move-tasks").addEventListener("click", () => void load());
    document.addEventListener("stowling:tasks-changed", () => void load());
    document.addEventListener("stowling:refresh-schedule-status", (event) => {
        nextTimes = new Map(event.detail.filter((trigger) => trigger.type === "SCHEDULED")
            .map((trigger) => [trigger.targetTaskId, trigger.nextAt]));
        renderNextTimes();
    });
    S.onConnected(load);
})();
