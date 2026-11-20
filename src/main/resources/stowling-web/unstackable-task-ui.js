(() => {
    "use strict";
    const S = window.Stowling;
    const Model = window.StowlingTaskModel;
    const Sort = window.StowlingUnstackableTaskModel;
    const elements = Object.fromEntries([
        "new", "list", "draft", "title", "form", "fields", "name", "cache", "storage",
        "priority", "message", "save", "remove"
    ].map(key => [key, document.querySelector(`#unstackable-${key}`)]));
    const state = {tasks: [], regions: [], revision: "", ready: false, busy: false,
        loading: false, active: false, dirty: false, draft: Sort.newDraft()};

    /** @param {string} message @param {boolean} error @returns {void} */
    function showMessage(message, error = false) {
        elements.message.textContent = message;
        elements.message.classList.toggle("error", error);
    }

    /** @returns {void} */
    function updateControls() {
        const blocked = !S.isConnected() || !state.ready || state.busy || state.loading;
        elements.new.disabled = blocked;
        elements.fields.disabled = blocked;
        elements.draft.hidden = !state.active;
        elements.remove.hidden = !state.draft.id;
        elements.list.querySelectorAll("button").forEach(button => {button.disabled = blocked;});
        document.dispatchEvent(new CustomEvent("stowling:unstackable-task-count", {
            detail: {count: state.tasks.filter(Sort.isUnstackableTask).length,
                draftActive: state.active && !state.draft.id}
        }));
    }

    /** @param {HTMLSelectElement} select @param {string} value @returns {void} */
    function populate(select, value) {
        select.replaceChildren(new Option("请选择容器区域", ""));
        for (const region of state.regions) {
            select.append(new Option(region.name, Model.regionRef(region.id)));
        }
        if (value && !Array.from(select.options).some(option => option.value === value)) {
            const missing = new Option("原区域已不存在，请重新选择", value);
            missing.disabled = true;
            select.append(missing);
        }
        select.value = value;
    }

    /** @returns {void} */
    function syncForm() {
        elements.title.textContent = state.draft.id ? state.draft.name : "新建 · 不可堆叠物品分类";
        elements.name.value = state.draft.name;
        elements.priority.value = state.draft.priority;
        populate(elements.cache, state.draft.regionRef);
        populate(elements.storage, state.draft.targetRef);
        updateControls();
    }

    /** @returns {void} */
    function readForm() {
        Object.assign(state.draft, {name: elements.name.value, priority: elements.priority.value,
            regionRef: elements.cache.value, targetRef: elements.storage.value});
    }

    /** @param {object | null} task @returns {void} */
    function selectTask(task = null) {
        if (state.busy || state.loading || !state.ready || !S.isConnected()) return;
        if (state.dirty && !window.confirm("放弃当前未保存的修改？")) return;
        state.draft = task ? Sort.draftFromTask(task) : Sort.newDraft(state.tasks);
        state.active = true;
        state.dirty = false;
        syncForm();
        elements.draft.open = true;
        showMessage("");
        elements.draft.scrollIntoView({behavior: "smooth", block: "nearest"});
    }

    /** @returns {void} */
    function renderList() {
        elements.list.replaceChildren();
        for (const task of state.tasks.filter(Sort.isUnstackableTask)) {
            const card = S.element("article", "move-saved-task");
            const button = S.element("button", "move-task-summary");
            button.type = "button";
            const copy = S.element("span", "move-task-overview");
            copy.append(S.element("strong", "", task.name),
                S.element("span", "", "不可堆叠分类 · 排除潜影盒"));
            button.append(S.element("span", "move-task-kind", "◇"), copy,
                S.element("span", "move-task-priority", Model.priorityLabel(task.priority)));
            button.addEventListener("click", () => selectTask(task));
            card.append(button);
            elements.list.append(card);
        }
        updateControls();
    }

    /** @returns {Promise<void>} */
    async function loadResources() {
        if (state.busy || state.loading || !S.isConnected()) return;
        state.loading = true;
        updateControls();
        try {
            const [tasks, regions] = await Promise.all([
                S.request("/v1/tasks"), S.request("/v1/regions")
            ]);
            state.tasks = tasks.tasks;
            state.regions = regions.regions;
            state.revision = tasks.revision;
            state.ready = true;
            if (state.draft.id && !state.dirty) {
                const task = state.tasks.find(task => task.id === state.draft.id);
                if (Sort.isUnstackableTask(task)) state.draft = Sort.draftFromTask(task);
                else state.active = false;
            }
            syncForm();
            renderList();
        } catch (error) {
            state.ready = false;
            S.handleError(error, "读取不可堆叠分类任务失败，请刷新重试");
        } finally {
            state.loading = false;
            updateControls();
        }
    }

    /** @param {boolean} enqueue @returns {Promise<void>} */
    async function saveTask(enqueue = false) {
        if (state.busy || state.loading || !state.ready || !S.isConnected()) return;
        readForm();
        let task;
        try {
            task = Sort.createTask(state.draft, state.regions, state.tasks);
        } catch (error) {
            showMessage(error.message, true);
            return;
        }
        state.busy = true;
        updateControls();
        let saved = false;
        try {
            if (!task.id || state.dirty) {
                const payload = Model.payloadTask(task);
                const response = await S.request(task.id
                    ? `/v1/tasks/${encodeURIComponent(task.id)}` : "/v1/tasks", {
                    method: task.id ? "PUT" : "POST",
                    headers: {"Content-Type": "application/json"},
                    body: JSON.stringify(task.id ? {...payload, revision: state.revision} : payload)
                });
                const id = task.id || response.taskId;
                const stored = response.tasks.find(item => item.id === id);
                if (!Sort.isUnstackableTask(stored)) throw new Error("保存响应缺少分类任务");
                state.tasks = response.tasks;
                state.revision = response.revision;
                state.draft = Sort.draftFromTask(stored);
                state.dirty = false;
                saved = true;
                syncForm();
                renderList();
            }
            if (enqueue) {
                await S.request(`/v1/tasks/${encodeURIComponent(state.draft.id)}/runs`, {
                    method: "POST", headers: {"Content-Type": "application/json"},
                    body: JSON.stringify({})
                });
            }
            const message = enqueue ? "不可堆叠分类已加入队列" : "不可堆叠分类任务已保存";
            showMessage(message);
            S.showToast(message);
        } catch (error) {
            showMessage(saved ? "任务已保存，但加入队列失败，请重试" : error.message, true);
            S.handleError(error, "保存或运行不可堆叠分类失败");
        } finally {
            state.busy = false;
            updateControls();
            document.dispatchEvent(new Event("stowling:tasks-changed"));
            document.dispatchEvent(new Event("stowling:unstackable-tasks-changed"));
        }
    }

    /** @returns {Promise<void>} */
    async function removeTask() {
        if (state.busy || state.loading || !state.ready || !S.isConnected() || !state.draft.id) {
            return;
        }
        if (!window.confirm("删除此分类任务？未保存修改将丢弃，已排队的任务仍会继续。")) return;
        state.busy = true;
        updateControls();
        try {
            const response = await S.request(`/v1/tasks/${encodeURIComponent(state.draft.id)}`, {
                method: "DELETE", headers: {"Content-Type": "application/json"},
                body: JSON.stringify({revision: state.revision})
            });
            state.tasks = response.tasks;
            state.revision = response.revision;
            state.draft = Sort.newDraft(state.tasks);
            state.active = false;
            state.dirty = false;
            renderList();
            S.showToast("不可堆叠分类任务已删除");
        } catch (error) {
            showMessage(error.message, true);
            S.handleError(error, "删除不可堆叠分类任务失败");
        } finally {
            state.busy = false;
            updateControls();
            document.dispatchEvent(new Event("stowling:tasks-changed"));
            document.dispatchEvent(new Event("stowling:unstackable-tasks-changed"));
        }
    }

    elements.new.addEventListener("click", () => selectTask());
    elements.form.addEventListener("input", () => {
        readForm();
        state.dirty = true;
        showMessage("未保存修改");
    });
    elements.form.addEventListener("submit", event => {
        event.preventDefault();
        void saveTask(true);
    });
    elements.save.addEventListener("click", () => void saveTask());
    elements.remove.addEventListener("click", () => void removeTask());
    document.querySelector("#refresh-move-tasks").addEventListener("click",
        () => void loadResources());
    document.addEventListener("stowling:tasks-changed", () => void loadResources());
    document.addEventListener("stowling:auto-tasks-changed", () => void loadResources());
    S.onConnected(loadResources);
    updateControls();
    window.setInterval(updateControls, 3000);
})();
