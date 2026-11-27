(() => {
    "use strict";

    /** @param {unknown} value @returns {any} */
    function copy(value) {
        return value == null ? value : JSON.parse(JSON.stringify(value));
    }

    /** @param {object} binding @returns {object} */
    function draft(binding) {
        if (binding.standingPlan) return copy(binding.standingPlan);
        const legacy = binding.original?.standingArea;
        return {enabled: Boolean(legacy), points: legacy ? [{position: {
            x: legacy.firstX, y: legacy.firstY - 1, z: legacy.firstZ
        }, markerArea: null, storageArea: null, expanded: false}] : []};
    }

    /** @param {object} bounds @returns {object} */
    function region(bounds) {
        return {first: {x: bounds.firstX, y: bounds.firstY, z: bounds.firstZ},
            second: {x: bounds.secondX, y: bounds.secondY, z: bounds.secondZ}};
    }

    /** @param {object|null} value @returns {object|null} */
    function area(value) {
        if (!value) return null;
        return {firstX: value.first.x, firstY: value.first.y, firstZ: value.first.z,
            secondX: value.second.x, secondY: value.second.y, secondZ: value.second.z};
    }

    /** @param {string} tag @param {string} className @param {string} [text]
     * @returns {HTMLElement} */
    function element(tag, className, text = "") {
        const node = document.createElement(tag);
        node.className = className;
        node.textContent = text;
        return node;
    }

    /** @returns {HTMLButtonElement} */
    function arrow() {
        const button = element("button", "category-option-expand");
        button.type = "button";
        button.innerHTML = '<svg width="16" height="16" viewBox="0 0 24 24" '
            + 'fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">'
            + '<path d="m9 6 6 6-6 6"/></svg>';
        return button;
    }

    /** @param {object} binding @param {object} helpers @returns {object} */
    function create(binding, helpers) {
        const plan = draft(binding);
        const root = element("section", "category-standing-plan");
        const row = element("div", "category-option-row");
        const label = element("label", "category-option-label", "指定站立位置");
        const summary = element("small", "category-standing-summary");
        label.append(summary);
        const controls = element("div", "category-option-actions");
        const expand = arrow();
        const switchLabel = element("label", "category-option-switch");
        const enabled = document.createElement("input");
        enabled.type = "checkbox";
        enabled.checked = plan.enabled;
        enabled.id = `standing-${binding.id}-enabled`;
        enabled.setAttribute("role", "switch");
        enabled.setAttribute("aria-label", "启用本绑定组指定站立位置");
        label.htmlFor = enabled.id;
        switchLabel.append(enabled);
        controls.append(expand, switchLabel);
        row.append(label, controls);
        const body = element("div", "category-recognition-panel category-standing-body");
        body.id = `standing-${binding.id}-body`;
        expand.setAttribute("aria-controls", body.id);
        const note = element("p", "settings-description",
            "填写站立脚下方块坐标。可绑定子识别区与子储存区，请覆盖需要操作的容器。");
        const activation = element("p", "settings-description",
            "在分类任务的实验性设置中开启“分类多站位与子区绑定”后生效。");
        const legacy = binding.original?.standingArea;
        const baseline = element("p", "settings-description category-standing-baseline", legacy
            ? `实验关闭时沿用原单站位基线：${legacy.firstX}，${legacy.firstY - 1}，`
                + `${legacy.firstZ}（脚下方块）。`
            : "实验关闭时沿用原流程：本组未指定站位。");
        const list = element("div", "category-standing-list");
        const add = element("button", "ghost category-standing-add", "添加站立坐标");
        add.type = "button";
        body.append(note, list, add);
        root.append(row, body, activation, baseline);
        let expanded = false;
        let sequence = 0;

        /** @returns {void} */
        function update() {
            summary.textContent = `${enabled.checked ? "已开启" : "已关闭"} · `
                + `${list.children.length} 个站位`;
            expand.hidden = !enabled.checked;
            expand.disabled = !enabled.checked;
            expand.setAttribute("aria-expanded", String(enabled.checked && expanded));
            expand.setAttribute("aria-label", `${expanded ? "收起" : "展开"}站立位置`);
            helpers.reveal(body, enabled.checked && expanded, true);
        }

        /** @param {object} [point] @returns {void} */
        function append(point = {position: {}, expanded: true}) {
            const identity = `${binding.id}-point-${++sequence}`;
            const entry = element("article", "category-standing-point");
            const heading = element("div", "category-standing-heading");
            const toggle = arrow();
            const title = element("strong", "category-option-label");
            heading.append(toggle, title);
            const detail = element("div", "category-standing-detail");
            detail.id = `standing-${identity}-detail`;
            toggle.setAttribute("aria-controls", detail.id);
            const position = element("div", "coordinate-corner-fields");
            const fields = {};
            for (const axis of ["x", "y", "z"]) {
                const field = element("label", "field-label", axis.toUpperCase());
                const input = document.createElement("input");
                input.type = "number";
                input.step = "1";
                input.min = "-2147483648";
                input.max = axis === "y" ? "2147483646" : "2147483647";
                input.value = point.position?.[axis] ?? "";
                input.dataset.standingAxis = axis;
                input.id = `standing-${identity}-${axis}`;
                field.htmlFor = input.id;
                field.append(input);
                position.append(field);
                fields[axis] = input;
            }
            helpers.bindInputs?.(fields.x, fields.y, fields.z);
            const mapRow = element("label", "category-option-row");
            const mapLabel = element("span", "category-option-label", "绑定子识别区与储存区");
            const mapControl = element("span", "category-option-switch");
            const mapped = document.createElement("input");
            mapped.type = "checkbox";
            mapped.className = "category-standing-mapped";
            mapped.checked = point.subregionsEnabled ?? Boolean(point.markerArea || point.storageArea);
            mapped.setAttribute("role", "switch");
            mapped.setAttribute("aria-label", "绑定本站位的子识别区与子储存区");
            mapControl.append(mapped);
            mapRow.append(mapLabel, mapControl);
            const grid = element("div", "coordinate-region-grid category-standing-regions");
            const parentRegions = helpers.currentRegions?.() || binding;
            for (const [kind, text, bounds] of [["marker", "子识别区", point.markerArea],
                ["storage", "子储存区", point.storageArea]]) {
                helpers.appendRegion(grid, text, `standing-${kind}`, identity,
                    bounds ? region(bounds) : copy(parentRegions[`${kind}Region`]
                        || binding[`${kind}Region`]));
            }
            grid.querySelectorAll("input").forEach(input => { input.required = false; });
            const remove = element("button", "ghost danger category-standing-remove", "删除此站位");
            remove.type = "button";
            detail.append(position, mapRow, grid, remove);
            entry.append(heading, detail);
            entry.expanded = Boolean(point.expanded);

            /** @param {boolean} [animate] @returns {void} */
            function updateEntry(animate = true) {
                const index = Array.from(list.children).indexOf(entry) + 1;
                title.textContent = `站位 ${index}`;
                toggle.setAttribute("aria-expanded", String(entry.expanded));
                toggle.setAttribute("aria-label", `${entry.expanded ? "收起" : "展开"}站位 ${index}`);
                helpers.reveal(detail, entry.expanded, animate);
                helpers.reveal(grid, mapped.checked, animate);
            }
            toggle.addEventListener("click", () => {
                entry.expanded = !entry.expanded;
                updateEntry();
                helpers.changed();
            });
            mapped.addEventListener("change", () => { updateEntry(); helpers.changed(); });
            remove.addEventListener("click", () => {
                entry.remove();
                Array.from(list.children).forEach((child, index) => {
                    child.querySelector("strong").textContent = `站位 ${index + 1}`;
                    const button = child.querySelector("button");
                    button.setAttribute("aria-label",
                        `${child.expanded ? "收起" : "展开"}站位 ${index + 1}`);
                });
                update();
                helpers.changed();
                add.focus();
            });
            /** @returns {object} */
            entry.readStanding = () => ({position: Object.fromEntries(
                ["x", "y", "z"].map(axis => [axis,
                    fields[axis].value === "" ? null : fields[axis].valueAsNumber])),
                subregionsEnabled: mapped.checked,
                markerArea: area(helpers.readRegion(entry, "standing-marker")),
                storageArea: area(helpers.readRegion(entry, "standing-storage")),
                expanded: entry.expanded});
            list.append(entry);
            updateEntry(false);
        }
        plan.points.forEach(point => append(point));
        add.addEventListener("click", () => {
            append();
            update();
            helpers.changed();
            list.lastElementChild.querySelector("input").focus();
        });
        expand.addEventListener("click", () => { expanded = !expanded; update(); });
        enabled.addEventListener("change", () => {
            expanded = enabled.checked;
            if (enabled.checked && !list.children.length) append();
            update();
            helpers.changed();
        });
        update();
        return {root, read: () => ({enabled: enabled.checked,
            points: Array.from(list.children, entry => entry.readStanding())})};
    }

    window.StowlingCategoryStanding = Object.freeze({create, draft});
})();
