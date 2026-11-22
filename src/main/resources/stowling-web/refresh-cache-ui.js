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
    const Settings = window.StowlingSettings;
    /** @param {string} value @returns {HTMLElement} */
    const id = (value) => document.getElementById(value);
    const add = id("new-refresh-cache-task");
    const draftCard = id("refresh-cache-editor");
    const draftToggle = id("toggle-refresh-cache-draft");
    const draftBody = id("refresh-cache-draft-body");
    const form = id("refresh-cache-form");
    const fieldset = form.querySelector("fieldset");
    const list = id("refresh-cache-list");
    const name = id("refresh-cache-name");
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
    const browserZone = Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC";
    const savedCards = new Map();
    let nextTimes = new Map();
    let locations = new Map();
    let tasks = [];
    let schedules = {};
    let revision = "";
    let current = null;
    let ready = false;
    let busy = false;

    /** @param {HTMLElement} button @param {HTMLElement} body
     * @param {boolean} expanded @returns {void} */
    function disclose(button, body, expanded) {
        button.setAttribute("aria-expanded", String(expanded));
        body.inert = !expanded;
        void Settings.reveal(body, expanded);
    }

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
        for (const [taskId, {button, body, run, status}] of savedCards) {
            const selected = current?.task?.id === taskId;
            button.disabled = run.disabled = disabled;
            status.textContent = selected && current.dirty ? "未保存修改" : "已保存";
            disclose(button, body, selected && current.expanded);
        }
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
            label.textContent = schedule ? "同步中" : "仅手动";
            label.removeAttribute("title");
            if (schedule && next) {
                const formatted = new Intl.DateTimeFormat("zh-CN", {timeZone: zone,
                    month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit"})
                    .format(new Date(next));
                label.textContent = "下次触发 " + formatted;
                label.title = zone;
            }
        }
    }

    /** @returns {void} */
    function renderRows() {
        draftBody.append(form);
        list.replaceChildren();
        savedCards.clear();
        for (const task of tasks.filter(isRefreshTask)) {
            const card = S.element("article", "move-saved-task");
            const header = S.element("div", "task-card-header");
            const edit = S.element("button", "move-task-summary");
            edit.type = "button";
            const body = S.element("div", "move-task-body");
            body.id = "refresh-cache-body-" + task.id;
            body.hidden = true;
            edit.setAttribute("aria-controls", body.id);
            const kind = S.element("span", "move-task-kind", "刷");
            kind.setAttribute("aria-hidden", "true");
            const overview = S.element("span", "move-task-overview");
            const nextLabel = S.element("span", "refresh-task-timing");
            const status = S.element("span", "move-task-state", "已保存");
            const chevron = S.element("span", "move-task-chevron");
            chevron.setAttribute("aria-hidden", "true");
            const targetRef = task.steps[0].parameters.targetRef;
            const meta = S.element("span", "refresh-task-meta");
            meta.append(S.element("span", "refresh-task-target",
                locations.get(targetRef) || "选区已不存在"), nextLabel);
            overview.append(S.element("strong", "", task.name), meta);
            edit.append(kind, overview, status, chevron);
            edit.addEventListener("click", () => open(task));
            const run = S.element("button", "secondary move-task-run", "加入队列");
            run.type = "button";
            run.setAttribute("aria-label", "加入队列：" + task.name);
            run.addEventListener("click", () => void enqueue(task.id));
            header.append(edit, run);
            card.append(header, body);
            if (current?.task?.id === task.id) body.append(form);
            list.append(card);
            savedCards.set(task.id, {button: edit, body, run, status, nextLabel});
        }
        controls();
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
    function fillForm(task) {
        const draft = settings(task?.steps?.[0]?.parameters);
        const schedule = task && schedules[task.id];
        current.zone = schedule?.zoneId || browserZone;
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
        remove.hidden = !task;
        showMessage("");
    }

    /** @param {object|null} task @returns {void} */
    function open(task = null) {
        const row = task ? savedCards.get(task.id) : {button: draftToggle, body: draftBody};
        if (busy || !ready || !S.isConnected() || !row) return;
        if (current && current.task?.id === task?.id) {
            current.expanded = !current.expanded;
        } else {
            if (current?.dirty && !window.confirm("当前修改尚未保存。仍要切换任务吗？")) return;
            current = {task, revision, expanded: true, dirty: false, zone: browserZone};
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
        current = null;
        draftBody.append(form);
        controls();
        syncCount();
    }

    /** @param {object} response @returns {void} */
    function apply(response) {
        revision = response.revision;
        tasks = response.tasks || [];
        schedules = response.schedules || {};
        if (current) {
            const taskId = current.task?.id;
            const saved = taskId && tasks.find((task) => task.id === taskId && isRefreshTask(task));
            if (taskId && !saved) {
                close();
            } else if (!current.dirty) {
                current.revision = revision;
                if (saved) {
                    current.task = saved;
                    fillForm(saved);
                }
            }
        }
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

    /** @param {string} value @returns {void} */
    function showMessage(value) {
        message.hidden = !value;
        message.textContent = value;
        message.classList.toggle("error", Boolean(value));
    }

    /** @param {Event} event @returns {Promise<void>} */
    async function save(event) {
        event.preventDefault();
        if (!current || busy) return;
        showMessage("");
        let savedId;
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
            savedId = response.taskId || taskId;
            changed();
            S.showToast("刷新缓存任务已保存");
        } catch (error) {
            showMessage(error.message);
            if (!kinds.some((input) => input.checked)) kindDisclosure.setExpanded(true);
        } finally {
            busy = false;
            controls();
            if (savedId) savedCards.get(savedId)?.button.focus();
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

    add.addEventListener("click", () => {
        if (current?.task === null && current.expanded) {
            draftToggle.focus();
        } else {
            open();
        }
    });
    draftToggle.addEventListener("click", () => open());
    id("cancel-refresh-edit").addEventListener("click", () => {
        const button = savedCards.get(current?.task?.id)?.button || add;
        close();
        button.focus();
    });
    remove.addEventListener("click", () => void deleteTask());
    form.addEventListener("input", () => {
        if (current) current.dirty = true;
        controls();
    });
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
