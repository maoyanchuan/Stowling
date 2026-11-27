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
        button.innerHTML = '<svg width="18" height="18" viewBox="0 0 24 24" '
            + 'fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">'
            + '<path d="m9 6 6 6-6 6"/></svg>';
        return button;
    }

    /** @param {object[]} bindings @param {object} helpers @returns {object} */
    function create(bindings, helpers) {
        const states = new Map(bindings.map(binding => [binding.id, draft(binding).enabled]));
        const shared = new Map();
        for (const binding of bindings) {
            for (const point of draft(binding).points) {
                const identity = point.standingId || helpers.nextId();
                const key = `${identity}:${JSON.stringify(point.position)}`;
                if (!shared.has(key)) shared.set(key, {
                    point: {...copy(point), standingId: identity}, targets: new Map()
                });
                shared.get(key).targets.set(binding.id, copy(point));
            }
        }
        const root = element("section", "category-standing-plan");
        const row = element("div", "category-option-row");
        const label = element("label", "category-option-label", "指定站立位置");
        label.htmlFor = "category-standing-enabled";
        const summary = element("small", "category-standing-summary");
        label.append(summary);
        const actions = element("div", "category-option-actions");
        const expand = arrow();
        const switchLabel = element("label", "category-option-switch");
        const enabled = document.createElement("input");
        enabled.type = "checkbox";
        enabled.id = "category-standing-enabled";
        enabled.checked = Array.from(states.values()).some(Boolean);
        enabled.setAttribute("role", "switch");
        enabled.setAttribute("aria-label", "启用指定站立位置");
        switchLabel.htmlFor = enabled.id;
        switchLabel.append(enabled);
        actions.append(expand, switchLabel);
        row.append(label, actions);
        const body = element("div", "category-recognition-panel category-standing-body");
        body.id = "category-standing-detail";
        expand.setAttribute("aria-controls", body.id);
        const note = element("p", "settings-description",
            "填写站立脚下方块坐标，可多选识别区与储存区。区域范围在下方绑定组维护。");
        const list = element("div", "category-standing-list");
        const add = element("button", "ghost category-standing-add", "添加站立坐标");
        add.type = "button";
        body.append(note, list, add);
        root.append(row, body, element("p", "settings-description",
            "需在分类任务的实验性设置中开启“分类多站位与子区绑定”；关闭实验时沿用原流程。"));
        let expanded = false;
        let sequence = 0;

        /** @returns {HTMLElement[]} */
        function entries() {
            return Array.from(list.children);
        }

        /** @param {HTMLElement} entry @returns {boolean} */
        function active(entry) {
            return enabled.checked && Array.from(entry.targets.keys())
                .some(id => states.get(id) === true);
        }

        /** @param {boolean} [animate] @returns {void} */
        function update(animate = true) {
            const points = entries();
            summary.textContent = `${points.length} 个站位 · ${points.filter(active).length} 个启用`;
            expand.hidden = !enabled.checked;
            expand.disabled = !enabled.checked;
            expand.setAttribute("aria-expanded", String(enabled.checked && expanded));
            expand.setAttribute("aria-label", `${expanded ? "收起" : "展开"}站立位置`);
            helpers.reveal(body, enabled.checked && expanded, animate);
            points.forEach((entry, index) => {
                entry.querySelector("strong").textContent = `站位 ${index + 1}`;
                const toggle = entry.querySelector("button");
                toggle.setAttribute("aria-expanded", String(entry.expanded));
                toggle.setAttribute("aria-label",
                    `${entry.expanded ? "收起" : "展开"}站位 ${index + 1}`);
                const enabledGroups = enabled.checked ? Array.from(entry.targets.keys())
                    .filter(id => states.get(id) === true).length : 0;
                entry.querySelector(".category-standing-state").textContent = enabledGroups
                    ? `已启用 ${enabledGroups}/${entry.targets.size} 组`
                    : `未启用 · ${entry.targets.size} 组`;
            });
        }

        /** @param {object} [point] @param {Map<string, object>} [targets] @returns {void} */
        function append(point = {position: {}, expanded: true}, targets = new Map()) {
            const identity = `standing-point-${++sequence}`;
            const standingId = point.standingId || helpers.nextId();
            const drafts = new Map(targets);
            const entry = element("article", "category-standing-point");
            entry.targets = new Map(Array.from(targets, ([id, target]) => [id, {
                custom: target.subregionsEnabled ?? Boolean(target.markerArea || target.storageArea)
            }]));
            entry.expanded = Boolean(point.expanded);
            const heading = element("div", "category-standing-heading");
            const toggle = arrow();
            const title = element("strong", "category-option-label");
            heading.append(toggle, title, element("span", "category-standing-state"));
            const detail = element("div", "category-standing-detail");
            detail.id = `${identity}-detail`;
            toggle.setAttribute("aria-controls", detail.id);
            const position = element("div", "coordinate-corner-fields");
            const fields = {};
            for (const axis of ["x", "y", "z"]) {
                const field = element("label", "field-label", axis.toUpperCase());
                const input = document.createElement("input");
                input.id = `${identity}-${axis}`;
                input.type = "number";
                input.step = "1";
                input.value = point.position?.[axis] ?? "";
                input.dataset.standingAxis = axis;
                field.htmlFor = input.id;
                field.append(input);
                position.append(field);
                fields[axis] = input;
            }
            helpers.bindInputs?.(fields.x, fields.y, fields.z);
            const targetRow = element("div", "category-option-row");
            const targetLabel = element("label", "category-option-label", "识别区与储存区");
            const pickerHost = element("div", "category-standing-picker");
            const hint = element("p", "settings-description category-standing-target-help");
            targetRow.append(targetLabel, pickerHost);
            let picker = null;

            /** @returns {void} */
            entry.refresh = () => {
                picker?.close();
                const groups = helpers.groups();
                const choices = groups.map(group => [group.id, group.name,
                    "可多选，共用本站位坐标"]);
                const options = new Map(groups.map(group => [group.id,
                    {id: group.id, custom: false}]));
                for (const group of groups) {
                    const saved = drafts.get(group.id);
                    if (!(saved?.subregionsEnabled
                            ?? Boolean(saved?.markerArea || saved?.storageArea))) continue;
                    const key = `${identity}-saved-${group.id}`;
                    choices.push([key, `${group.name} · 已保存子区`, "保留原子区范围"]);
                    options.set(key, {id: group.id, custom: true});
                }
                /** @returns {string[]} */
                const selectedKeys = () => Array.from(entry.targets, ([id, target]) =>
                    target.custom ? `${identity}-saved-${id}` : id);
                picker = helpers.createSelect(selectedKeys(), choices, (values, changed) => {
                    const next = new Map();
                    for (const key of values) {
                        const target = options.get(key);
                        if (!target) continue;
                        const previous = next.get(target.id);
                        if (!previous || key === changed || previous.key !== changed) {
                            next.set(target.id, {...target, key});
                        }
                    }
                    entry.targets = next;
                    const changedTarget = options.get(changed);
                    if (enabled.checked && changedTarget && next.has(changedTarget.id)) {
                        states.set(changedTarget.id, true);
                    }
                    hint.textContent = "可多选；所选各组共用本站位，已保存子区保留原范围。";
                    update();
                    helpers.changed();
                    return selectedKeys();
                });
                pickerHost.replaceChildren(picker.root);
                targetLabel.htmlFor = picker.trigger.id;
                hint.textContent = "可多选；所选各组共用本站位，已保存子区保留原范围。";
            };
            const remove = element("button", "ghost danger category-standing-remove", "删除此站位");
            remove.type = "button";
            remove.addEventListener("click", () => {
                picker?.close();
                entry.remove();
                update();
                helpers.changed();
                add.focus();
            });
            detail.append(position, targetRow, hint, remove);
            entry.append(heading, detail);
            toggle.addEventListener("click", () => {
                picker?.close();
                entry.expanded = !entry.expanded;
                helpers.reveal(detail, entry.expanded, true);
                update();
                helpers.changed();
            });
            /** @param {string} id @returns {object} */
            entry.readStanding = id => ({...copy(drafts.get(id) || {}), standingId,
                position: Object.fromEntries(["x", "y", "z"].map(axis => [axis,
                    fields[axis].value === "" ? null : fields[axis].valueAsNumber])),
                subregionsEnabled: entry.targets.get(id).custom, expanded: entry.expanded});
            list.append(entry);
            entry.refresh();
            helpers.reveal(detail, entry.expanded, false);
        }
        shared.forEach(value => append(value.point, value.targets));
        add.addEventListener("click", () => {
            const groups = helpers.groups();
            const id = groups.length === 1 ? groups[0].id : "";
            if (id) states.set(id, enabled.checked);
            append(undefined, id ? new Map([[id, {}]]) : new Map());
            update();
            helpers.changed();
            list.lastElementChild.querySelector("input").focus();
        });
        expand.addEventListener("click", () => {
            expanded = !expanded;
            if (!expanded) entries().forEach(entry => entry.refresh());
            update();
        });
        enabled.addEventListener("change", () => {
            helpers.groups().forEach(group => states.set(group.id, enabled.checked));
            expanded = enabled.checked;
            if (enabled.checked && !list.children.length) add.click();
            entries().forEach(entry => entry.refresh());
            update();
            helpers.changed();
        });
        update(false);
        return {root,
            refresh: () => { entries().forEach(entry => entry.refresh()); update(false); },
            references: id => entries().some(entry => entry.targets.has(id)),
            valid: () => entries().every(entry => entry.targets.size > 0
                && Array.from(entry.targets.keys()).every(id => helpers.groups()
                    .some(group => group.id === id))),
            read: id => ({enabled: enabled.checked && states.get(id) === true
                && entries().some(entry => entry.targets.has(id)),
                points: entries().filter(entry => entry.targets.has(id))
                    .map(entry => entry.readStanding(id))})};
    }

    window.StowlingCategoryStanding = Object.freeze({create, draft});
})();
