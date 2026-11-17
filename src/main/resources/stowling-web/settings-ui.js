(() => {
    "use strict";

    const UI = window.Stowling;
    let nextId = 0;

    /**
     * @param {HTMLInputElement} control
     * @param {string} [label]
     * @returns {HTMLInputElement}
     */
    function switchControl(control, label = "") {
        control.type = "checkbox";
        control.classList.add("settings-switch");
        control.setAttribute("role", "switch");
        if (label) {
            control.setAttribute("aria-label", label);
        }
        return control;
    }

    /**
     * @param {string} title
     * @param {string} description
     * @param {HTMLElement} content
     * @param {boolean} [expanded]
     * @returns {{root: HTMLElement, description: HTMLElement}}
     */
    function disclosure(title, description, content, expanded = false) {
        const root = UI.element("details", "settings-disclosure");
        root.open = expanded;
        const summary = UI.element("summary", "settings-row");
        const copy = UI.element("span", "settings-copy");
        const note = UI.element("span", "settings-description", description);
        copy.append(UI.element("strong", "", title), note);
        const arrow = UI.element("span", "settings-chevron", "›");
        arrow.setAttribute("aria-hidden", "true");
        summary.append(copy, arrow);
        content.classList.add("settings-detail");
        root.append(summary, content);
        return { root, description: note };
    }

    /**
     * @param {string} title
     * @param {string} description
     * @param {HTMLInputElement} toggle
     * @param {HTMLElement} content
     * @returns {{root: HTMLElement, refresh: () => void}}
     */
    function optionalSection(title, description, toggle, content) {
        const root = UI.element("section", "settings-option");
        const row = UI.element("div", "settings-option-header");
        const button = UI.element("button", "settings-row settings-option-toggle");
        button.type = "button";
        const copy = UI.element("span", "settings-copy");
        copy.append(UI.element("strong", "", title),
            UI.element("span", "settings-description", description));
        const arrow = UI.element("span", "settings-chevron", "›");
        arrow.setAttribute("aria-hidden", "true");
        button.append(copy, arrow);
        switchControl(toggle, title);
        content.id ||= `settings-option-${++nextId}`;
        content.classList.add("settings-detail");
        button.setAttribute("aria-controls", content.id);
        let expanded = false;
        const disabledStates = new Map();

        /** @returns {void} */
        function refresh() {
            root.dataset.enabled = String(toggle.checked);
            button.disabled = !toggle.checked;
            button.setAttribute("aria-expanded", String(expanded && toggle.checked));
            content.hidden = !expanded || !toggle.checked;
            for (const control of content.querySelectorAll("input, select, textarea, button")) {
                if (!toggle.checked && !disabledStates.has(control)) {
                    disabledStates.set(control, control.disabled);
                    control.disabled = true;
                } else if (toggle.checked && disabledStates.has(control)) {
                    control.disabled = disabledStates.get(control);
                    disabledStates.delete(control);
                }
            }
        }

        button.addEventListener("click", () => {
            expanded = !expanded;
            refresh();
        });
        toggle.addEventListener("change", () => {
            expanded = toggle.checked;
            refresh();
        });
        content.addEventListener("invalid", () => {
            expanded = true;
            refresh();
        }, true);
        row.append(button, toggle);
        root.append(row, content);
        refresh();
        return { root, refresh };
    }

    document.addEventListener("invalid", (event) => {
        let parent = event.target.parentElement;
        while (parent) {
            if (parent.tagName === "DETAILS") {
                parent.open = true;
            }
            parent = parent.parentElement;
        }
    }, true);

    window.StowlingSettings = Object.freeze({ switchControl, disclosure, optionalSection });
})();
