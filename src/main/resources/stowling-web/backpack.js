(() => {
    "use strict";
    const S = window.Stowling;
    const Model = window.StowlingBackpackModel;
    const list = document.querySelector("#backpack-items");
    const editor = document.querySelector("#backpack-editor");
    const keep = document.querySelector("#backpack-keep");
    const keepAll = document.querySelector("#backpack-keep-all");
    const classifyAll = document.querySelector("#backpack-classify-all");
    const save = document.querySelector("#backpack-save");
    const status = document.querySelector("#backpack-status");
    const message = document.querySelector("#backpack-message");
    let snapshot = null;
    let draft = null;
    let selected = null;
    let selectedKey = "";
    let dirty = false;
    let loading = false;
    let saving = false;
    let generation = 0;

    /** @returns {void} */
    function refreshState() {
        const disabled = saving || !snapshot?.available || snapshot.busy;
        for (const control of [keep, keepAll, classifyAll, save]) control.disabled = disabled;
        status.textContent = !snapshot?.available ? "等待游戏中的背包数据"
            : snapshot.busy ? "任务正在运行，背包归属暂不可修改"
                : snapshot.classificationAvailable ? "空闲 · 自用物品受到保护"
                    : "空闲 · 可分配物品；交给分类的物品会等待你启用自动分类任务";
    }

    /** @returns {void} */
    function preview() {
        if (!selected) return;
        const count = Model.freeCount(selected);
        const amount = Number(keep.value);
        document.querySelector("#backpack-preview").textContent =
            Number.isInteger(amount) && amount >= 0 && amount <= count
                ? `保留 ${amount} 件自用，${count - amount} 件交给分类。`
                : "保留数量超出可分配范围。";
    }

    /** @param {object} item @returns {void} */
    function select(item) {
        selected = item;
        selectedKey = item.key;
        draft = snapshot;
        dirty = false;
        editor.hidden = false;
        document.querySelector("#backpack-item-name").textContent = item.name;
        document.querySelector("#backpack-item-id").textContent = item.itemId;
        document.querySelector("#backpack-counts").textContent =
            `背包共 ${item.count} 件 · 自用 ${item.protectedCount} 件`
            + ` · 任务占用 ${item.taskCount} 件 · 待分类 ${item.classificationCount} 件`;
        keep.max = String(Model.freeCount(item));
        keep.value = String(item.protectedCount);
        list.querySelectorAll("button").forEach(button => {
            button.setAttribute("aria-pressed", String(button.dataset.key === selectedKey));
        });
        preview();
        refreshState();
    }

    /** @returns {void} */
    function render() {
        list.replaceChildren();
        const items = snapshot.items || [];
        if (items.length === 0) list.append(S.emptyState("背包中没有物品"));
        for (const item of items) {
            const button = S.element("button", "backpack-item");
            button.type = "button";
            button.dataset.key = item.key;
            button.setAttribute("aria-pressed", String(item.key === selectedKey));
            button.append(S.element("strong", "", item.name),
                S.element("span", "backpack-quantity", `× ${item.count}`),
                S.element("small", "muted compact-help",
                    `自用 ${item.protectedCount} · 任务 ${item.taskCount}`
                    + ` · 待分类 ${item.classificationCount}`));
            button.addEventListener("click", () => select(item));
            list.append(button);
        }
        const item = items.find(value => value.key === selectedKey);
        if (item) select(item);
        else {selected = null; editor.hidden = true;}
        refreshState();
    }

    /** @returns {Promise<void>} */
    async function load() {
        if (!S.isConnected() || loading || saving) return;
        loading = true;
        const version = generation;
        try {
            const value = await S.request("/v1/player-inventory");
            if (version !== generation) return;
            snapshot = value;
            if (!dirty) render();
            else refreshState();
        } catch (error) {
            message.textContent = error.message;
        } finally {
            loading = false;
        }
    }

    /** @param {number} amount @returns {Promise<void>} */
    async function allocate(amount) {
        if (!selected || saving) return;
        try {
            const payload = Model.allocation({...draft, busy: snapshot.busy}, selected, amount);
            generation++;
            saving = true;
            refreshState();
            snapshot = await S.request("/v1/player-inventory", {method: "PUT",
                headers: {"Content-Type": "application/json"}, body: JSON.stringify(payload)});
            dirty = false;
            render();
            message.textContent = "背包归属已更新。";
        } catch (error) {
            message.textContent = error.message;
            if (error.status === 409) {dirty = false; selectedKey = "";}
        } finally {
            saving = false;
            refreshState();
            void load();
        }
    }

    keep.addEventListener("input", () => {dirty = true; preview();});
    keepAll.addEventListener("click", () => void allocate(Model.freeCount(selected)));
    classifyAll.addEventListener("click", () => void allocate(0));
    editor.addEventListener("submit", event => {
        event.preventDefault();
        if (keep.value.trim() === "") return;
        void allocate(Number(keep.value));
    });
    document.querySelector("#backpack-refresh").addEventListener("click", () => {
        dirty = false;
        void load();
    });
    window.setInterval(() => {
        if (document.visibilityState === "visible") void load();
    }, 2000);
    S.onConnected(() => void load());
    S.mountPage("backpack");
})();
