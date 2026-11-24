(() => {
    "use strict";
    const S = window.Stowling;
    const Model = window.StowlingBackpackModel;
    const list = document.querySelector("#backpack-items");
    const empty = document.querySelector("#backpack-empty");
    const tooltip = document.querySelector("#backpack-tooltip");
    const editor = document.querySelector("#backpack-editor");
    const lock = document.querySelector("#backpack-lock");
    const unlock = document.querySelector("#backpack-unlock");
    const status = document.querySelector("#backpack-status");
    const message = document.querySelector("#backpack-message");
    let snapshot = null;
    let draft = null;
    let selected = null;
    let selectedIndex = -1;
    let loading = false;
    let saving = false;
    let generation = 0;
    const slots = new Map();
    let hovered = null;
    let focused = null;
    let tooltipSlot = null;

    /** @returns {void} */
    function positionTooltip() {
        if (tooltip.hidden || !tooltipSlot) return;
        const anchor = tooltipSlot.button.getBoundingClientRect();
        const width = tooltip.offsetWidth;
        const height = tooltip.offsetHeight;
        const edge = 8;
        const left = Math.max(edge, Math.min(anchor.left, window.innerWidth - width - edge));
        const above = anchor.top - height - edge;
        const top = above >= edge ? above : Math.min(anchor.bottom + edge,
            window.innerHeight - height - edge);
        tooltip.style.left = `${left}px`;
        tooltip.style.top = `${Math.max(edge, top)}px`;
    }

    /** @returns {void} */
    function showTooltip() {
        const entry = hovered || focused;
        if (tooltipSlot) tooltipSlot.button.removeAttribute("aria-describedby");
        tooltipSlot = entry?.slot ? entry : null;
        tooltip.hidden = !tooltipSlot;
        if (!tooltipSlot) return;
        const lines = Model.slotDetails(entry.slot, snapshot.lockedSlots.includes(entry.index));
        const title = S.element("strong", "backpack-tooltip-title", entry.item?.name || "空栏位");
        tooltip.replaceChildren(title,
            ...lines.map(line => S.element("div", "", line)));
        entry.button.setAttribute("aria-describedby", tooltip.id);
        positionTooltip();
    }

    /** @param {number} index @returns {object} */
    function createSlot(index) {
        const square = S.element("div", "backpack-slot");
        const button = S.element("button", "backpack-item");
        const image = S.element("img", "backpack-icon");
        const fallback = S.element("span", "backpack-icon-fallback");
        const count = S.element("span", "backpack-quantity");
        const protection = S.element("span", "backpack-protection", "锁");
        button.type = "button";
        button.hidden = true;
        button.setAttribute("aria-pressed", "false");
        image.alt = "";
        image.hidden = true;
        for (const decoration of [fallback, count, protection]) {
            decoration.setAttribute("aria-hidden", "true");
        }
        button.append(image, fallback, count, protection);
        square.append(button);
        list.append(square);
        const entry = {index, button, image, fallback, count, protection,
            item: null, slot: null, signature: "", model: ""};
        button.addEventListener("pointerenter", event => {
            if (event.pointerType === "touch") return;
            hovered = entry;
            showTooltip();
        });
        button.addEventListener("pointerleave", () => {
            if (hovered === entry) hovered = null;
            showTooltip();
        });
        button.addEventListener("focus", () => {
            if (button.matches(":focus-visible")) focused = entry;
            showTooltip();
        });
        button.addEventListener("blur", () => {
            if (focused === entry) focused = null;
            showTooltip();
        });
        button.addEventListener("click", () => select(index));
        button.addEventListener("keydown", event => {
            const order = Model.slotOrder();
            const offset = {ArrowLeft: -1, ArrowRight: 1, ArrowUp: -9, ArrowDown: 9}[event.key];
            if (offset === undefined) return;
            event.preventDefault();
            const next = Math.max(0, Math.min(35, order.indexOf(index) + offset));
            slots.get(order[next]).button.focus();
        });
        return entry;
    }

    /** @param {object} entry @param {string} model @returns {void} */
    function loadIcon(entry, model) {
        if (entry.model === model) return;
        entry.model = model;
        entry.image.hidden = true;
        entry.image.removeAttribute("src");
        entry.fallback.hidden = false;
        if (!model) return;
        void S.itemIcon(model).then(source => {
            if (entry.model !== model) return;
            if (source === null) entry.model = "";
            if (!source) return;
            entry.image.src = source;
            entry.image.hidden = false;
            entry.fallback.hidden = true;
        });
    }

    /** @param {object} entry @param {?object} slot @param {?object} item @returns {void} */
    function updateSlot(entry, slot, item) {
        const locked = snapshot.lockedSlots.includes(entry.index);
        const signature = JSON.stringify([slot, item, locked]);
        if (signature !== entry.signature) {
            entry.signature = signature;
            entry.slot = slot;
            entry.item = item;
            entry.button.hidden = !snapshot.available;
            const name = item?.name || "空栏位";
            entry.button.setAttribute("aria-label",
                `栏位 ${entry.index + 1}，${name}，${slot?.count || 0} 件，`
                    + (locked ? "已锁定" : "未锁定"));
            entry.fallback.textContent = item ? Array.from(name).slice(0, 2).join("") : "";
            entry.count.textContent = slot?.count > 1 ? String(slot.count) : "";
            entry.protection.hidden = !locked;
            if (hovered === entry || focused === entry || tooltipSlot === entry) showTooltip();
        }
        loadIcon(entry, item ? slot.icon : "");
    }

    /** @returns {void} */
    function refreshState() {
        const disabled = saving || !snapshot?.available || snapshot.busy || selectedIndex < 0;
        lock.disabled = disabled || snapshot?.lockedSlots.includes(selectedIndex);
        unlock.disabled = disabled || !snapshot?.lockedSlots.includes(selectedIndex);
        status.textContent = !snapshot?.available ? "等待游戏中的完整背包数据"
            : snapshot.busy ? "任务正在运行，可查看栏位；安全停止后可修改锁定"
                : `空闲 · 已锁定 ${snapshot.lockedSlots.length} 格（含空格）`;
    }

    /** @param {number} index @returns {void} */
    function select(index) {
        selectedIndex = index;
        selected = slots.get(index);
        draft = snapshot;
        editor.hidden = false;
        document.querySelector("#backpack-item-name").textContent =
            `栏位 ${index + 1} · ${selected.item?.name || "空栏位"}`;
        document.querySelector("#backpack-item-id").textContent = selected.item?.itemId || "";
        document.querySelector("#backpack-counts").textContent =
            `此格 ${selected.slot?.count || 0} 件 · `
                + (snapshot.lockedSlots.includes(index) ? "已锁定" : "未锁定");
        document.querySelector("#backpack-preview").textContent =
            "锁定跟随格子位置，物品移走后锁仍保留；锁定格内物品可以正常使用。";
        for (const entry of slots.values()) {
            entry.button.setAttribute("aria-pressed", String(entry.index === index));
        }
        refreshState();
    }

    /** @returns {void} */
    function render() {
        const items = new Map((snapshot.items || []).map(item => [item.key, item]));
        const occupied = new Map((snapshot.slots || []).map(slot => [slot.index, slot]));
        empty.hidden = !snapshot.available || [...occupied.values()].some(slot => slot.count > 0);
        for (const entry of slots.values()) {
            const slot = occupied.get(entry.index) || null;
            updateSlot(entry, slot, items.get(slot?.key) || null);
        }
        if (selectedIndex >= 0 && snapshot.available) select(selectedIndex);
        else editor.hidden = true;
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
            render();
        } catch (error) {
            message.textContent = error.message;
        } finally {
            loading = false;
        }
    }

    /** @param {boolean} locked @returns {Promise<void>} */
    async function setLocked(locked) {
        if (selectedIndex < 0 || saving) return;
        try {
            const payload = Model.lockChange({...draft, busy: snapshot.busy}, selectedIndex, locked);
            generation++;
            saving = true;
            refreshState();
            snapshot = await S.request("/v1/player-inventory", {method: "PUT",
                headers: {"Content-Type": "application/json"}, body: JSON.stringify(payload)});
            render();
            message.textContent = locked ? "此栏位已锁定。" : "此栏位已解锁。";
        } catch (error) {
            message.textContent = error.message;
        } finally {
            saving = false;
            refreshState();
            void load();
        }
    }

    lock.addEventListener("click", () => void setLocked(true));
    unlock.addEventListener("click", () => void setLocked(false));
    editor.addEventListener("submit", event => event.preventDefault());
    document.querySelector("#backpack-refresh").addEventListener("click", () => void load());
    for (const index of Model.slotOrder()) slots.set(index, createSlot(index));
    window.addEventListener("resize", positionTooltip);
    window.addEventListener("scroll", positionTooltip, true);
    document.addEventListener("keydown", event => {
        if (event.key !== "Escape" || tooltip.hidden) return;
        hovered = null;
        focused = null;
        showTooltip();
    });
    window.setInterval(() => {
        if (document.visibilityState === "visible") void load();
    }, 2000);
    S.onConnected(() => void load());
    S.mountPage("backpack");
})();
