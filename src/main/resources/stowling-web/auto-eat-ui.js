(() => {
    "use strict";

    const S = window.Stowling;
    const Settings = window.StowlingSettings;
    const endpoint = "/v1/auto-eat-tasks";
    /** @param {string} id @returns {HTMLElement} */
    const $ = (id) => document.getElementById(id);
    const list = $("auto-eat-list");
    const detailRows = $("task-editor-eat-rows");
    const status = $("auto-eat-status");
    const newTask = $("new-auto-eat");
    const draft = $("auto-eat-draft");
    const draftToggle = $("toggle-auto-eat-draft");
    const draftBody = $("auto-eat-draft-body");
    const form = $("auto-eat-form");
    const fields = form.querySelector("fieldset");
    const remove = $("delete-auto-eat");
    const source = $("auto-eat-source");
    const coordinate = $("auto-eat-coordinate");
    const message = $("auto-eat-message");
    const rows = new Map();
    let tasks = [];
    let coordinates = [];
    let revision = "";
    /** @type {{id: string | null, revision: string, expanded: boolean, dirty: boolean} | null} */
    let editor = null;
    let loading = false;
    let busy = false;

    /** @returns {boolean} */
    function unavailable() {
        return loading || busy || !revision || !S.isConnected();
    }

    /**
     * @param {HTMLElement} button
     * @param {HTMLElement} body
     * @param {boolean} expanded
     * @returns {void}
     */
    function disclose(button, body, expanded) {
        button.setAttribute("aria-expanded", String(expanded));
        body.inert = !expanded;
        void Settings.reveal(body, expanded);
    }

    /** @returns {void} */
    function updateControls() {
        const disabled = unavailable();
        const isDraft = editor?.id === null;
        newTask.disabled = disabled;
        fields.disabled = disabled;
        draftToggle.disabled = disabled;
        draft.hidden = !isDraft;
        disclose(draftToggle, draftBody, isDraft && editor.expanded);
        for (const [id, row] of rows) {
            const selected = editor?.id === id;
            row.button.disabled = disabled;
            row.listening.disabled = disabled || (selected && editor.dirty);
            row.listening.checked = row.enabled;
            row.listening.title = selected && editor.dirty
                ? "先保存当前修改，再切换监听" : "饱食度监听";
            disclose(row.button, row.body, selected && editor.expanded);
            row.status.textContent = selected && editor.dirty
                ? "未保存修改"
                : row.enabled ? "监听中" : "已暂停";
        }
    }

    /** @returns {void} */
    function notifyTaskCount() {
        document.dispatchEvent(new CustomEvent("stowling:auto-eat-task-count", {
            detail: {count: tasks.length, draftActive: editor?.id === null}
        }));
    }

    /** @returns {void} */
    function renderTasks() {
        draftBody.append(form);
        list.replaceChildren();
        detailRows.replaceChildren();
        rows.clear();
        for (const task of tasks) {
            const settings = task.settings;
            const location = settings.coordinateId
                ? coordinates.find((point) => point.id === settings.coordinateId)?.name
                    || "指定食物箱"
                : "随身锁定食物";
            const row = S.element("article", "move-saved-task");
            const button = S.element("button", "move-task-summary");
            button.type = "button";
            const body = S.element("div", "move-task-body");
            body.id = `auto-eat-task-body-${task.id}`;
            body.hidden = true;
            button.setAttribute("aria-controls", body.id);
            const kind = S.element("span", "move-task-kind", "食");
            kind.setAttribute("aria-hidden", "true");
            const overview = S.element("span", "move-task-overview");
            overview.append(S.element("strong", "", settings.name), S.element("span", "",
                `${location} · 饱食度 ≤ ${settings.foodLevelThreshold} · ${settings.foodItemId}`));
            const state = S.element("span", "move-task-state");
            const chevron = S.element("span", "move-task-chevron", "⌄");
            chevron.setAttribute("aria-hidden", "true");
            button.append(kind, overview, state, chevron);
            button.addEventListener("click", () => selectTask(task));
            if (editor?.id === task.id) {
                body.append(form);
            }
            const header = S.element("div", "task-card-header");
            const listening = Settings.switchControl(
                document.createElement("input"), `监听任务：${settings.name}`
            );
            listening.addEventListener("change", () => {
                void setListening(task.id, listening.checked);
            });
            header.append(button, listening);
            row.append(header);
            detailRows.append(body);
            list.append(row);
            rows.set(task.id, {button, body, status: state, listening, enabled: settings.enabled});
        }
        updateControls();
        notifyTaskCount();
    }

    /** @returns {Promise<void>} */
    async function load() {
        if (loading || busy || !S.isConnected()) return;
        loading = true;
        updateControls();
        try {
            const [response, points] = await Promise.all([
                S.request(endpoint),
                S.request("/v1/coordinates")
            ]);
            revision = response.revision;
            tasks = response.items;
            coordinates = points.coordinates;
            const selected = tasks.find((task) => task.id === editor?.id);
            if (editor?.id && !selected) {
                editor = null;
            } else if (editor && !editor.dirty) {
                editor.revision = revision;
                fillForm(selected?.settings);
            }
            status.hidden = true;
            renderTasks();
        } catch (error) {
            revision = "";
            status.textContent = "读取自动进食任务失败，请刷新重试。";
            status.hidden = false;
            S.handleError(error, "读取自动进食任务失败");
        } finally {
            loading = false;
            updateControls();
        }
    }

    /** @returns {void} */
    function updateSource() {
        const useChest = source.value === "chest";
        $("auto-eat-coordinate-field").hidden = !useChest;
        $("auto-eat-inventory-note").hidden = useChest;
        $("auto-eat-chest-note").hidden = !useChest;
        coordinate.required = useChest;
        coordinate.disabled = !useChest;
    }

    /**
     * @param {object} [settings]
     * @returns {void}
     */
    function fillForm(settings) {
        let name = "自动进食";
        for (let suffix = 2; tasks.some((item) => item.settings.name === name); suffix++) {
            name = `自动进食 ${suffix}`;
        }
        $("auto-eat-name").value = settings?.name ?? name;
        $("auto-eat-food").value = settings?.foodItemId ?? "minecraft:bread";
        $("auto-eat-threshold").value = settings?.foodLevelThreshold ?? 18;
        $("auto-eat-interval").value = settings?.intervalSeconds ?? 1;
        source.value = settings?.coordinateId ? "chest" : "inventory";
        coordinate.replaceChildren(new Option("请选择食物箱所在方块", ""));
        for (const point of coordinates) {
            coordinate.append(new Option(
                `${point.name} · ${point.x}, ${point.y}, ${point.z}`, point.id));
        }
        coordinate.value = settings?.coordinateId ?? "";
        updateSource();
        remove.hidden = editor.id === null;
        message.textContent = "";
    }

    /**
     * @param {object | null} [task]
     * @returns {void}
     */
    function selectTask(task = null) {
        if (unavailable()) return;
        const id = task?.id ?? null;
        const row = id ? rows.get(id) : {button: draftToggle, body: draftBody};
        if (editor?.id === id) {
            editor.expanded = id === null || !editor.expanded;
        } else {
            if (editor?.dirty && !window.confirm("当前修改尚未保存。仍要切换任务吗？")) return;
            editor = {id, revision, expanded: true, dirty: false};
            fillForm(task?.settings);
            row.body.append(form);
        }
        if (editor.expanded) window.StowlingTaskWorkbench?.openEditor("eat");
        updateControls();
        notifyTaskCount();
        row.button.focus();
    }

    /** @returns {void} */
    function cancelEditor() {
        const button = editor.id ? rows.get(editor.id)?.button : newTask;
        editor = null;
        updateControls();
        notifyTaskCount();
        button?.focus();
    }

    /**
     * @param {string} id
     * @param {boolean} enabled
     * @returns {Promise<void>}
     */
    async function setListening(id, enabled) {
        const row = rows.get(id);
        if (!row || row.listening.disabled || unavailable()) {
            updateControls();
            return;
        }
        const previousRevision = revision;
        busy = true;
        updateControls();
        try {
            const operation = enabled ? "start" : "stop";
            const response = await S.request(`${endpoint}/${encodeURIComponent(id)}/${operation}`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ revision: previousRevision })
            });
            const updated = response.items.find((task) => task.id === id);
            row.enabled = updated.settings.enabled;
            revision = response.revision;
            tasks = response.items;
            if (editor?.revision === previousRevision) {
                editor.revision = revision;
            }
            document.dispatchEvent(new Event("stowling:auto-eat-tasks-changed"));
            document.dispatchEvent(new Event("stowling:tasks-changed"));
            S.showToast(row.enabled ? "已开始饱食度监听" : "已停止饱食度监听");
        } catch (error) {
            S.handleError(error, "切换自动进食监听失败");
        } finally {
            busy = false;
            updateControls();
        }
    }

    /**
     * @param {boolean} deleting
     * @returns {Promise<void>}
     */
    async function save(deleting) {
        if (unavailable() || !editor?.expanded) return;
        const id = editor.id;
        const method = deleting ? "DELETE" : id ? "PUT" : "POST";
        const path = id ? `${endpoint}/${encodeURIComponent(id)}` : endpoint;
        const body = {revision: editor.revision};
        if (!deleting) {
            Object.assign(body, {
                name: $("auto-eat-name").value.trim(),
                foodItemId: $("auto-eat-food").value.trim(),
                coordinateId: source.value === "chest" ? coordinate.value : null,
                foodLevelThreshold: $("auto-eat-threshold").valueAsNumber,
                intervalSeconds: $("auto-eat-interval").valueAsNumber
            });
        }
        busy = true;
        updateControls();
        message.textContent = "";
        let focusTarget;
        try {
            const response = await S.request(path, {
                method,
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(body)
            });
            revision = response.revision;
            tasks = response.items;
            const saved = tasks.find((task) => task.id === response.taskId);
            editor = saved ? {id: saved.id, revision, expanded: true, dirty: false} : null;
            if (saved) {
                fillForm(saved.settings);
                message.textContent = "已保存";
            }
            renderTasks();
            focusTarget = saved ? rows.get(saved.id)?.button : newTask;
            document.dispatchEvent(new Event("stowling:auto-eat-tasks-changed"));
            document.dispatchEvent(new Event("stowling:tasks-changed"));
            S.showToast(deleting ? "已删除自动进食任务" : "已保存自动进食任务");
        } catch (error) {
            message.textContent = error.status === 409 && error.code === "revision_conflict"
                ? "配置已更新，请取消编辑并刷新任务列表后重试。"
                : String(error.message || error);
            S.handleError(error, deleting ? "删除自动进食任务失败" : "保存自动进食任务失败");
        } finally {
            busy = false;
            updateControls();
            focusTarget?.focus();
        }
    }

    window.StowlingTaskWorkbench?.watchDraft(() => !!editor?.dirty);
    document.addEventListener("stowling:task-editor-change", (event) => {
        if (event.detail !== "eat" && editor?.expanded) {
            editor.expanded = false;
            updateControls();
        }
    });
    newTask.addEventListener("click", () => selectTask());
    draftToggle.addEventListener("click", () => {
        editor.expanded = !editor.expanded;
        if (editor.expanded) window.StowlingTaskWorkbench?.openEditor("eat");
        updateControls();
    });
    $("cancel-auto-eat").addEventListener("click", cancelEditor);
    form.addEventListener("input", () => {
        editor.dirty = true;
        message.textContent = "未保存修改";
        updateControls();
    });
    source.addEventListener("change", updateSource);
    form.addEventListener("submit", (event) => {
        event.preventDefault();
        void save(false);
    });
    remove.addEventListener("click", () => {
        if (window.confirm("删除这个自动进食任务？")) void save(true);
    });
    $("refresh-move-tasks").addEventListener("click", () => void load());
    document.addEventListener("stowling:auto-tasks-changed", () => void load());
    document.addEventListener("stowling:tasks-changed", () => {
        if (!busy) void load();
    });
    S.onConnected(load);
})();
