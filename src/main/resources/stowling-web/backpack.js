(() => {
    "use strict";
    const S = window.Stowling;
    const Model = window.StowlingBackpackModel;
    const list = document.querySelector("#backpack-items");
    const empty = document.querySelector("#backpack-empty");
    const tooltip = document.querySelector("#backpack-tooltip");
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
    let selectedIndex = -1;
    let dirty = false;
    let loading = false;
    let saving = false;
    let generation = 0;
    const slots = new Map();
    const icons = new Map();
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
        tooltipSlot = entry?.item ? entry : null;
        tooltip.hidden = !tooltipSlot;
        if (!tooltipSlot) return;
        const lines = Model.slotDetails(entry.slot, entry.item);
        tooltip.replaceChildren(S.element("strong", "backpack-tooltip-title", entry.item.name),
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
        const protection = S.element("span", "backpack-protection", "保");
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
        button.addEventListener("click", () => select(entry.item, index));
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
        if (!icons.has(model)) {
            icons.set(model, S.request(`/v1/player-inventory/icons/${model}`).catch(() => {
                icons.delete(model);
                return null;
            }));
        }
        void icons.get(model).then(source => {
            if (entry.model !== model) return;
            if (source === null) entry.model = "";
            if (typeof source !== "string" || !source.startsWith("data:image/png;base64,")) {
                return;
            }
            entry.image.src = source;
            entry.image.hidden = false;
            entry.fallback.hidden = true;
        });
    }

    /** @param {object} entry @param {?object} slot @param {?object} item @returns {void} */
    function updateSlot(entry, slot, item) {
        const signature = item ? JSON.stringify([slot, item]) : "";
        const changed = signature !== entry.signature;
        if (changed) {
            entry.signature = signature;
            entry.slot = slot;
            entry.item = item;
            entry.button.hidden = !item;
            if (item) {
                entry.button.setAttribute("aria-label", `${item.name}，本格 ${slot.count} 件`);
                entry.fallback.textContent = Array.from(item.name).slice(0, 2).join("");
                entry.count.textContent = slot.count > 1 ? String(slot.count) : "";
                entry.protection.hidden = item.protectedCount === 0;
            } else {
                if (hovered === entry) hovered = null;
                if (focused === entry) focused = null;
            }
            if (hovered === entry || focused === entry || tooltipSlot === entry) showTooltip();
        }
        loadIcon(entry, item ? slot.icon : "");
    }

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

    /** @param {object} item @param {number} index @returns {void} */
    function select(item, index) {
        selected = item;
        selectedKey = item.key;
        selectedIndex = index;
        draft = snapshot;
        dirty = false;
        editor.hidden = false;
        document.querySelector("#backpack-item-name").textContent = item.name;
        document.querySelector("#backpack-item-id").textContent = item.itemId;
        document.querySelector("#backpack-counts").textContent =
            `同一物品变体共 ${item.count} 件 · 自用 ${item.protectedCount} 件`
            + ` · 任务占用 ${item.taskCount} 件 · 待分类 ${item.classificationCount} 件`;
        keep.max = String(Model.freeCount(item));
        keep.value = String(item.protectedCount);
        paintSelection();
        preview();
        refreshState();
    }

    /** @returns {void} */
    function paintSelection() {
        for (const entry of slots.values()) {
            const active = String(entry.index === selectedIndex && entry.item?.key === selectedKey);
            if (entry.button.getAttribute("aria-pressed") !== active) {
                entry.button.setAttribute("aria-pressed", active);
            }
        }
    }

    /** @returns {void} */
    function render() {
        const items = new Map((snapshot.items || []).map(item => [item.key, item]));
        const occupied = new Map((snapshot.slots || []).map(slot => [slot.index, slot]));
        empty.hidden = !snapshot.available || occupied.size > 0;
        for (const entry of slots.values()) {
            const slot = occupied.get(entry.index) || null;
            updateSlot(entry, slot, items.get(slot?.key) || null);
        }
        const item = items.get(selectedKey);
        if (!item) {
            selected = null;
            selectedKey = "";
            selectedIndex = -1;
            dirty = false;
            editor.hidden = true;
        } else {
            if (occupied.get(selectedIndex)?.key !== selectedKey) {
                selectedIndex = Array.from(occupied.values())
                    .find(slot => slot.key === selectedKey)?.index ?? -1;
            }
            if (!dirty) {
                if (JSON.stringify(selected) !== JSON.stringify(item)) select(item, selectedIndex);
                else draft = snapshot;
            }
        }
        paintSelection();
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
        if (selected) select(selected, selectedIndex);
        void load();
    });
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
