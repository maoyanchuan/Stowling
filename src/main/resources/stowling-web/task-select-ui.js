(() => {
    "use strict";

    const S = window.Stowling;
    let nextId = 0;

    /** @param {HTMLElement} root @returns {{refresh: (enabled: boolean) => void}} */
    function create(root) {
        const views = new Map();
        let active = false;
        let opened = null;

        /** @param {boolean} [focus] @returns {void} */
        function close(focus = false) {
            if (!opened) return;
            const view = opened;
            opened = null;
            view.trigger.setAttribute("aria-expanded", "false");
            view.animation?.cancel();
            view.menu.hidden = true;
            if (focus) view.trigger.focus({preventScroll: true});
        }

        /** @param {object} view @returns {void} */
        function sync(view) {
            const {select, trigger, current} = view;
            select.hidden = active;
            trigger.hidden = !active;
            trigger.disabled = select.matches(":disabled");
            const labels = Array.from(select.selectedOptions)
                .sort((left, right) => Number(left.dataset.selectionOrder || 0)
                    - Number(right.dataset.selectionOrder || 0))
                .map(option => option.textContent);
            const limit = Number(select.dataset.summaryLimit);
            current.textContent = select.multiple && limit > 0 && labels.length > limit
                ? `已选 ${labels.length} 个${select.dataset.selectionNoun || "选项"}`
                : labels.join("、") || select.dataset.placeholder || "请选择";
            trigger.title = labels.join(" → ") || current.textContent;
            if (opened === view && (!active || trigger.disabled
                || trigger.closest("[hidden], [inert]"))) close();
            if (opened === view) renderOptions(view);
        }

        /** @param {object} view @returns {void} */
        function renderOptions(view) {
            const {select, menu} = view;
            const focused = menu.contains(document.activeElement)
                ? document.activeElement.dataset.index : null;
            const items = [];
            view.choices = [];
            for (const [index, option] of Array.from(select.options).entries()) {
                if (select.required && !option.value) continue;
                const button = S.element("button", "task-select-option");
                button.type = "button";
                button.setAttribute("role", "option");
                button.setAttribute("aria-selected", String(option.selected));
                button.dataset.index = String(index);
                button.disabled = option.disabled || option.parentElement.disabled === true;
                const copy = S.element("span", "task-select-copy", option.textContent);
                const group = option.parentElement.tagName === "OPTGROUP"
                    ? option.parentElement.label : "";
                const description = option.dataset.description || group
                    || select.dataset.optionDescription || "";
                if (description) copy.append(S.element("small", "", description));
                const check = S.element("span", "task-select-check");
                check.setAttribute("aria-hidden", "true");
                button.append(copy, check);
                button.addEventListener("click", () => {
                    if (select.multiple) option.selected = !option.selected;
                    else select.value = option.value;
                    select.dispatchEvent(new Event("input", {bubbles: true}));
                    select.dispatchEvent(new Event("change", {bubbles: true}));
                    sync(view);
                    if (!select.multiple) close(true);
                    else view.choices.find(item => item.dataset.index === String(index))?.focus();
                });
                items.push(button);
                if (!button.disabled) view.choices.push(button);
            }
            if (!items.length) {
                items.push(S.element("p", "task-select-empty",
                    select.dataset.emptyMessage || "暂无可选项，请先添加对应资源。"));
            }
            menu.replaceChildren(...items);
            if (focused !== null) {
                view.choices.find(item => item.dataset.index === focused)?.focus();
            }
        }

        /** @param {object} view @param {boolean} [focus] @returns {void} */
        function open(view, focus = false) {
            if (!active || view.trigger.disabled) return;
            close();
            opened = view;
            renderOptions(view);
            const {trigger, menu, choices} = view;
            trigger.setAttribute("aria-expanded", "true");
            menu.hidden = false;
            const bounds = trigger.getBoundingClientRect();
            const width = Math.min(Math.max(bounds.width, 280), window.innerWidth - 32);
            const below = window.innerHeight - bounds.bottom - 12;
            const above = bounds.top - 12;
            menu.style.width = `${width}px`;
            const upwards = below < Math.min(menu.scrollHeight, 320) && above > below;
            menu.style.maxHeight = `${Math.max(0, Math.min(360, upwards ? above : below))}px`;
            menu.style.left = `${Math.max(16, Math.min(bounds.right - width,
                window.innerWidth - width - 16))}px`;
            menu.style.top = upwards ? "auto" : `${bounds.bottom + 6}px`;
            menu.style.bottom = upwards ? `${window.innerHeight - bounds.top + 6}px` : "auto";
            menu.dataset.placement = upwards ? "above" : "below";
            if (!window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
                view.animation = menu.animate([
                    {opacity: 0, transform: `translateY(${upwards ? 4 : -4}px)`},
                    {opacity: 1, transform: "none"}
                ], {duration: 200, easing: "cubic-bezier(0.16, 1, 0.3, 1)"});
            }
            if (focus) {
                (choices.find(item => item.getAttribute("aria-selected") === "true")
                    || choices[0])?.focus();
            }
        }

        /** @param {HTMLSelectElement} select @returns {object} */
        function mount(select) {
            const id = `task-select-${++nextId}`;
            const row = select.closest(".move-field");
            if (row?.tagName === "LABEL") {
                const replacement = document.createElement("div");
                for (const attribute of Array.from(row.attributes)) {
                    if (attribute.name !== "for") {
                        replacement.setAttribute(attribute.name, attribute.value);
                    }
                }
                replacement.append(...row.childNodes);
                row.replaceWith(replacement);
            }
            const wrap = S.element("span", "task-select-wrap");
            select.before(wrap);
            const trigger = S.element("button", "task-select-trigger");
            trigger.id = id;
            trigger.type = "button";
            trigger.setAttribute("aria-haspopup", "listbox");
            trigger.setAttribute("aria-expanded", "false");
            trigger.setAttribute("aria-controls", `${id}-menu`);
            const description = select.getAttribute("aria-describedby");
            if (description) trigger.setAttribute("aria-describedby", description);
            const label = wrap.parentElement.querySelector("span");
            if (label) {
                label.id ||= `${id}-label`;
                select.setAttribute("aria-labelledby", label.id);
                trigger.setAttribute("aria-labelledby", `${label.id} ${id}-value`);
            } else {
                trigger.setAttribute("aria-label", select.getAttribute("aria-label") || "选择设置");
            }
            const current = S.element("span", "task-select-current");
            current.id = `${id}-value`;
            const arrow = S.element("span", "task-select-arrow");
            arrow.setAttribute("aria-hidden", "true");
            trigger.append(current, arrow);
            wrap.append(select, trigger);
            const menu = S.element("div", "task-select-menu");
            menu.id = `${id}-menu`;
            menu.hidden = true;
            menu.setAttribute("role", "listbox");
            menu.setAttribute("aria-label", label?.textContent || "设置选项");
            if (select.multiple) menu.setAttribute("aria-multiselectable", "true");
            document.body.append(menu);
            const view = {select, trigger, current, menu, choices: []};
            trigger.addEventListener("click", () => {
                if (opened === view) close();
                else open(view);
            });
            trigger.addEventListener("keydown", event => {
                if (!["ArrowDown", "ArrowUp"].includes(event.key)) return;
                event.preventDefault();
                open(view, true);
            });
            menu.addEventListener("keydown", event => {
                if (event.key === "Escape" || event.key === "Tab") {
                    if (event.key === "Escape") event.preventDefault();
                    close(true);
                    return;
                }
                if (!["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) return;
                event.preventDefault();
                const choices = view.choices;
                if (!choices.length) return;
                let index = choices.indexOf(document.activeElement);
                if (event.key === "Home") index = 0;
                if (event.key === "End") index = choices.length - 1;
                if (event.key === "ArrowDown") index = (index + 1) % choices.length;
                if (event.key === "ArrowUp") index = (index - 1 + choices.length) % choices.length;
                choices[index].focus();
            });
            select.addEventListener("change", () => sync(view));
            view.observer = new MutationObserver(() => sync(view));
            view.observer.observe(select, {
                childList: true, subtree: true, attributes: true,
                attributeFilter: ["disabled", "label", "selected", "data-selection-order"]
            });
            return view;
        }

        document.addEventListener("pointerdown", event => {
            if (opened && !opened.menu.contains(event.target)
                && !opened.trigger.contains(event.target)) close();
        });
        document.addEventListener("keydown", event => {
            if (opened && event.key === "Escape") {
                event.preventDefault();
                close(true);
            }
        });
        document.addEventListener("scroll", event => {
            if (opened && !opened.menu.contains(event.target)) close(true);
        }, true);
        window.addEventListener("resize", () => close(true));

        /** @param {boolean} enabled @returns {void} */
        function refresh(enabled) {
            active = enabled;
            for (const [select, view] of views) {
                if (root.contains(select)) continue;
                if (opened === view) close();
                view.observer.disconnect();
                view.menu.remove();
                views.delete(select);
            }
            for (const select of root.querySelectorAll("select")) {
                if (active && !views.has(select)) views.set(select, mount(select));
            }
            for (const view of views.values()) sync(view);
        }

        return {refresh};
    }

    window.StowlingTaskSelect = Object.freeze({create});
})();
