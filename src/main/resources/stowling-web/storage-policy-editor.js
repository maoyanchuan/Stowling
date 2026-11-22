(() => {
    "use strict";
    const S = window.Stowling;
    const Settings = window.StowlingSettings;

    /** @param {string} text @param {HTMLElement} control @returns {HTMLElement} */
    function field(text, control) {
        const row = S.element("label", "move-field");
        row.append(S.element("span", "", text), control);
        return row;
    }

    /** @param {object} policy @param {object} resources @returns {object} */
    function create(policy = {}, resources = {}) {
        const content = S.element("div", "auto-storage-fields");
        const mode = document.createElement("select");
        for (const [value, name] of [["NORMAL", "普通散装"],
                ["MIXED", "散装与潜影盒混合"], ["BOX_ONLY", "纯潜影盒"]]) {
            mode.append(new Option(name, value));
        }
        mode.value = policy.mode || "NORMAL";
        const slots = document.createElement("input");
        Object.assign(slots, {type: "number", min: "0", max: "54", step: "1"});
        slots.value = String(policy.looseSlots ?? Math.floor(
            54 * (policy.loosePercent ?? 50) / 100));
        const counter = S.element("div", "storage-slot-counter");
        counter.append(slots, S.element("span", "", "/54"));
        const slotRow = field("散装槽位", counter);
        const grid = S.element("div", "storage-slot-grid");
        grid.setAttribute("role", "group");
        grid.setAttribute("aria-label", "双箱槽位：点击选择散装占用数量");
        for (let index = 1; index <= 54; index++) {
            const button = S.element("button", "storage-slot");
            button.type = "button";
            button.setAttribute("aria-label", `${index}/54 槽`);
            button.addEventListener("click", () => {
                slots.value = String(index);
                slots.dispatchEvent(new Event("input", {bubbles: true}));
            });
            grid.append(button);
        }
        const partial = Settings.switchControl(document.createElement("input"));
        partial.checked = Boolean(policy.allowPartialBoxes);
        const partialRow = field("允许未满盒入库，下次优先补满", partial);
        const destination = document.createElement("select");
        destination.append(new Option("与分类目标相同", ""));
        for (const [kind, items, label] of [
                ["coordinate", resources.coordinates || [], "箱子坐标"],
                ["region", resources.regions || [], "容器区域"]]) {
            for (const item of items) {
                destination.append(new Option(`[${label}] ${item.name}`, `${kind}:${item.id}`));
            }
        }
        const ref = policy.partialBoxDestinationRef || "";
        if (ref && !Array.from(destination.options).some(option => option.value === ref)) {
            const missing = new Option("原存放位置已删除，请重新选择", ref);
            missing.disabled = true;
            destination.append(missing);
        }
        destination.value = ref;
        const destinationRow = S.element("div", "");
        destinationRow.append(field("未满盒储存位置", destination));
        const refill = Settings.switchControl(document.createElement("input"));
        refill.checked = Boolean(policy.refillLoose);
        const refillRow = field("散货不足设定槽位时拆盒补足", refill);
        content.append(field("储存模式", mode), slotRow, grid, partialRow,
            destinationRow, refillRow);
        window.StowlingResourcePicker?.group(destination);
        const disclosure = Settings.disclosure("储存模式", "", content);
        disclosure.root.classList.add("auto-storage-policy");

        /** @returns {void} */
        function refresh() {
            const mixed = mode.value === "MIXED";
            const boxesOnly = mode.value === "BOX_ONLY";
            slotRow.hidden = grid.hidden = !mixed;
            slots.disabled = !mixed;
            partialRow.hidden = !mixed;
            partial.disabled = !mixed;
            destinationRow.hidden = !boxesOnly && (!mixed || !partial.checked);
            destination.disabled = destinationRow.hidden;
            refillRow.hidden = !mixed;
            refill.disabled = !mixed;
            disclosure.description.textContent = mode.selectedOptions[0]?.textContent || "";
            Array.from(grid.children).forEach((button, index) => {
                button.setAttribute("aria-pressed", String(index < Number(slots.value)));
            });
        }
        content.addEventListener("input", refresh);
        refresh();
        return {
            root: disclosure.root,
            collapse: () => disclosure.setExpanded(false),
            read: () => ({
                mode: mode.value,
                loosePercent: Math.round(Number(slots.value) * 100 / 54),
                looseSlots: mode.value === "MIXED" ? Number(slots.value) : null,
                allowPartialBoxes: mode.value === "BOX_ONLY"
                    || mode.value === "MIXED" && partial.checked,
                partialBoxDestinationRef: mode.value === "BOX_ONLY"
                    || mode.value === "MIXED" && partial.checked
                    ? destination.value || null : null,
                refillLoose: mode.value === "MIXED" && refill.checked,
                packingArea: policy.packingArea || null
            })
        };
    }
    window.StowlingStoragePolicyEditor = Object.freeze({create});
})();
