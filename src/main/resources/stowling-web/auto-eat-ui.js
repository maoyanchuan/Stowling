(() => {
    "use strict";

    const S = window.Stowling;
    const Settings = window.StowlingSettings;
    const Autosave = window.StowlingTaskAutosave;
    const endpoint = "/v1/auto-eat-tasks";
    /** @param {string} id @returns {HTMLElement} */
    const $ = (id) => document.getElementById(id);
    const list = $("auto-eat-list");
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
    /** @type {{id: string|null, revision: string, expanded: boolean, dirty: boolean,
     * original: object|null} | null} */
    let editor = null;
    let loading = false;
    let busy = false;
    let pendingSwitch = null;
    const autosave = Autosave.create(form, () => save(false), () => loading || busy);

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
            row.listening.checked = pendingSwitch?.id === id ? pendingSwitch.enabled : row.enabled;
            row.listening.title = selected && editor.dirty
                ? "修改保存后可切换监听" : "饱食度监听";
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
            button.addEventListener("click", () => {
                selectTask(tasks.find((item) => item.id === task.id));
            });
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
            row.append(header, body);
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
                editor.original = selected || null;
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
        window.StowlingResourcePicker?.group(coordinate, "coordinate");
        updateSource();
        remove.hidden = editor.id === null;
        showMessage("");
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
            autosave.cancel();
            editor = {id, revision, expanded: true, dirty: !task, original: task};
            fillForm(task?.settings);
            row.body.append(form);
            if (!task) {
                message.textContent = "等待自动保存…";
                autosave.schedule();
            }
        }
        updateControls();
        notifyTaskCount();
        row.button.focus();
    }

    /** @returns {Promise<void>} */
    async function cancelEditor() {
        if (!editor) return;
        if (editor.dirty) await autosave.flush();
        if (!editor || (editor.dirty && !window.confirm("关闭并放弃未保存的修改？"))) return;
        autosave.cancel();
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
        const retainFocus = document.activeElement === row.listening;
        pendingSwitch = {id, enabled};
        busy = true;
        row.listening.setAttribute("aria-busy", "true");
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
            if (editor?.id === id) editor.original = updated;
            document.dispatchEvent(new CustomEvent("stowling:task-switch-changed", {
                detail: {previousRevision, revision}
            }));
            S.showToast(row.enabled ? "已开始饱食度监听" : "已停止饱食度监听");
        } catch (error) {
            S.handleError(error, "切换自动进食监听失败");
        } finally {
            busy = false;
            pendingSwitch = null;
            row.listening.removeAttribute("aria-busy");
            updateControls();
            if (retainFocus && document.activeElement === document.body) {
                row.listening.focus({preventScroll: true});
            }
        }
    }

    /**
     * @param {boolean} deleting
     * @returns {Promise<void>}
     */
    async function save(deleting) {
        if (unavailable() || !editor || (!deleting && !editor.dirty)) return;
        const id = editor.id;
        const method = deleting ? "DELETE" : id ? "PUT" : "POST";
        const path = id ? `${endpoint}/${encodeURIComponent(id)}` : endpoint;
        const body = {revision: editor.revision};
        if (!deleting) {
            Object.assign(body, {
                foodItemId: $("auto-eat-food").value.trim(),
                coordinateId: source.value === "chest" ? coordinate.value : null,
                foodLevelThreshold: $("auto-eat-threshold").valueAsNumber,
                intervalSeconds: $("auto-eat-interval").valueAsNumber
            });
            if (!body.foodItemId || !form.checkValidity()) {
                showMessage("请填写有效的食物和进食设置", true);
                return;
            }
        }
        busy = true;
        updateControls();
        showMessage(deleting ? "" : "正在自动保存…");
        let focusTarget;
        try {
            const response = await Autosave.write(path, method, body, deleting ? undefined
                : () => Autosave.latestRevision(endpoint, editor.original,
                    id ? (latest) => latest.items.find((task) => task.id === id) : undefined));
            revision = response.revision;
            tasks = response.items;
            const saved = tasks.find((task) => task.id === response.taskId);
            editor = saved ? {id: saved.id, revision, expanded: editor.expanded, dirty: false,
                original: saved} : null;
            if (saved) {
                remove.hidden = false;
                showMessage("已自动保存");
            }
            renderTasks();
            if (deleting) focusTarget = newTask;
            document.dispatchEvent(new Event("stowling:auto-eat-tasks-changed"));
            document.dispatchEvent(new Event("stowling:tasks-changed"));
            if (deleting) S.showToast("已删除自动进食任务");
        } catch (error) {
            showMessage(String(error.message || error), true);
            S.handleError(error, deleting ? "删除自动进食任务失败" : "保存自动进食任务失败");
        } finally {
            busy = false;
            updateControls();
            focusTarget?.focus();
        }
    }

    /** @param {string} text @param {boolean} [error] @returns {void} */
    function showMessage(text, error = false) {
        message.textContent = text;
        message.classList.toggle("error", error);
    }

    newTask.addEventListener("click", () => selectTask());
    draftToggle.addEventListener("click", () => {
        editor.expanded = !editor.expanded;
        updateControls();
    });
    $("cancel-auto-eat").addEventListener("click", cancelEditor);
    form.addEventListener("input", () => {
        if (!editor) return;
        editor.dirty = true;
        showMessage("未保存修改");
        updateControls();
        autosave.schedule();
    });
    source.addEventListener("change", updateSource);
    form.addEventListener("submit", (event) => {
        event.preventDefault();
        void autosave.flush();
    });
    remove.addEventListener("click", () => {
        if (window.confirm("删除这个自动进食任务？")) void save(true);
    });
    $("refresh-move-tasks").addEventListener("click", () => void load());
    document.addEventListener("stowling:task-switch-changed", ({detail}) => {
        if (revision === detail.previousRevision) revision = detail.revision;
        if (editor?.revision === detail.previousRevision) editor.revision = detail.revision;
    });
    document.addEventListener("stowling:auto-tasks-changed", () => void load());
    document.addEventListener("stowling:tasks-changed", () => {
        if (!busy) void load();
    });
    S.onConnected(load);
})();
