(() => {
    "use strict";

    const S = window.Stowling;
    const endpoint = "/v1/auto-eat-tasks";
    const $ = (id) => document.getElementById(id);
    const list = $("auto-eat-list");
    const status = $("auto-eat-status");
    const newTask = $("new-auto-eat");
    const dialog = $("auto-eat-dialog");
    const form = $("auto-eat-form");
    const fields = form.querySelector("fieldset");
    const close = $("close-auto-eat");
    const remove = $("delete-auto-eat");
    const source = $("auto-eat-source");
    const coordinate = $("auto-eat-coordinate");
    const message = $("auto-eat-message");
    let tasks = [];
    let coordinates = [];
    let revision = "";
    let editRevision = "";
    let taskId = null;
    let loading = false;
    let busy = false;

    /** @returns {void} */
    function updateControls() {
        newTask.disabled = loading || busy || !revision || !S.isConnected();
        fields.disabled = busy;
        close.disabled = busy;
    }

    /** @returns {void} */
    function renderTasks() {
        list.replaceChildren();
        for (const task of tasks) {
            const settings = task.settings;
            const location = settings.coordinateId
                ? coordinates.find((point) => point.id === settings.coordinateId)?.name
                    || "指定食物箱"
                : "随身锁定食物";
            const row = S.element("article", "move-saved-task");
            const button = S.element("button", "move-task-summary");
            button.type = "button";
            button.setAttribute("aria-haspopup", "dialog");
            const kind = S.element("span", "move-task-kind", "食");
            kind.setAttribute("aria-hidden", "true");
            const overview = S.element("span", "move-task-overview");
            overview.append(S.element("strong", "", settings.name), S.element("span", "",
                `${location} · 饱食度 ≤ ${settings.foodLevelThreshold} · ${settings.foodItemId}`));
            button.append(kind, overview, S.element("span", "move-task-state",
                settings.enabled ? "监听中" : "已暂停"));
            button.addEventListener("click", () => openEditor(task));
            row.append(button);
            list.append(row);
        }
        document.dispatchEvent(new CustomEvent("stowling:auto-eat-task-count",
            { detail: tasks.length }));
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
     * @param {object | null} [task]
     * @returns {void}
     */
    function openEditor(task = null) {
        if (newTask.disabled) return;
        const settings = task?.settings;
        taskId = task?.id ?? null;
        editRevision = revision;
        let name = "自动进食";
        for (let suffix = 2; tasks.some((item) => item.settings.name === name); suffix++) {
            name = `自动进食 ${suffix}`;
        }
        $("auto-eat-name").value = settings?.name ?? name;
        $("auto-eat-food").value = settings?.foodItemId ?? "minecraft:bread";
        $("auto-eat-threshold").value = settings?.foodLevelThreshold ?? 18;
        $("auto-eat-interval").value = settings?.intervalSeconds ?? 1;
        $("auto-eat-enabled").checked = settings?.enabled ?? true;
        source.value = settings?.coordinateId ? "chest" : "inventory";
        coordinate.replaceChildren(new Option("请选择食物箱所在方块", ""));
        for (const point of coordinates) {
            coordinate.append(new Option(
                `${point.name} · ${point.x}, ${point.y}, ${point.z}`, point.id));
        }
        coordinate.value = settings?.coordinateId ?? "";
        updateSource();
        remove.hidden = taskId === null;
        message.textContent = "";
        dialog.showModal();
    }

    /**
     * @param {boolean} deleting
     * @returns {Promise<void>}
     */
    async function save(deleting) {
        if (busy || !S.isConnected()) return;
        const method = deleting ? "DELETE" : taskId ? "PUT" : "POST";
        const path = taskId ? `${endpoint}/${encodeURIComponent(taskId)}` : endpoint;
        const body = { revision: editRevision };
        if (!deleting) {
            Object.assign(body, {
                name: $("auto-eat-name").value.trim(),
                foodItemId: $("auto-eat-food").value.trim(),
                coordinateId: source.value === "chest" ? coordinate.value : null,
                foodLevelThreshold: $("auto-eat-threshold").valueAsNumber,
                intervalSeconds: $("auto-eat-interval").valueAsNumber,
                enabled: $("auto-eat-enabled").checked
            });
        }
        busy = true;
        updateControls();
        message.textContent = "";
        try {
            const response = await S.request(path, {
                method,
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(body)
            });
            revision = response.revision;
            tasks = response.items;
            dialog.close();
            renderTasks();
            document.dispatchEvent(new Event("stowling:auto-eat-tasks-changed"));
            document.dispatchEvent(new Event("stowling:tasks-changed"));
            S.showToast(deleting ? "已删除自动进食任务" : "已保存自动进食任务");
        } catch (error) {
            message.textContent = error.status === 409 && error.code === "revision_conflict"
                ? "配置已更新，请关闭窗口并刷新任务列表后再编辑。"
                : String(error.message || error);
            S.handleError(error, deleting ? "删除自动进食任务失败" : "保存自动进食任务失败");
        } finally {
            busy = false;
            updateControls();
        }
    }

    newTask.addEventListener("click", () => openEditor());
    close.addEventListener("click", () => dialog.close());
    dialog.addEventListener("cancel", (event) => {
        if (busy) event.preventDefault();
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
