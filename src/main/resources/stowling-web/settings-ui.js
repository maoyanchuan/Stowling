(() => {
    "use strict";

    const UI = window.Stowling;
    let nextId = 0;
    const reveals = new WeakMap();

    /** @param {HTMLElement} content @param {boolean} expanded @returns {Promise<void>} */
    function reveal(content, expanded) {
        const previous = reveals.get(content);
        if (previous?.expanded === expanded) return previous.done;
        const animated = previous && content.animate
            && !window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
        const height = animated && !content.hidden ? content.getBoundingClientRect().height : 0;
        const opacity = previous?.animation
            ? window.getComputedStyle(content).opacity : (expanded ? 0 : 1);
        previous?.animation?.cancel();
        const current = {expanded, animation: null, done: Promise.resolve()};
        reveals.set(content, current);
        content.inert = !expanded;
        if (!animated) {
            content.hidden = !expanded;
            if (content.style) content.style.overflow = "";
            return current.done;
        }
        content.hidden = false;
        content.style.overflow = "hidden";
        const end = expanded ? content.scrollHeight : 0;
        const animation = content.animate([
            {height: `${height}px`, opacity},
            {height: `${end}px`, opacity: expanded ? 1 : 0}
        ], {duration: expanded ? 200 : 150, easing: "cubic-bezier(0.16, 1, 0.3, 1)"});
        current.animation = animation;
        current.done = animation.finished.catch(() => {}).then(() => {
            if (reveals.get(content) !== current) return;
            content.hidden = !expanded;
            content.style.overflow = "";
            current.animation = null;
        });
        return current.done;
    }

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
     * @returns {object}
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
        const controller = bindDisclosure(root, summary, content, expanded);
        return {root, summary, description: note, ...controller};
    }

    /**
     * @param {HTMLDetailsElement} root
     * @param {HTMLElement} summary
     * @param {HTMLElement} content
     * @param {boolean} [expanded]
     * @returns {{setExpanded: (value: boolean) => void}}
     */
    function bindDisclosure(root, summary, content, expanded = false) {
        root.open = expanded;
        let nativeOpen = expanded;
        let desired = expanded;
        summary.setAttribute("aria-expanded", String(expanded));
        void reveal(content, expanded);
        /** @param {boolean} value @returns {void} */
        function setExpanded(value) {
            desired = value;
            summary.setAttribute("aria-expanded", String(value));
            if (value) {
                nativeOpen = true;
                root.open = true;
            }
            void reveal(content, value).then(() => {
                if (!desired) {
                    nativeOpen = false;
                    root.open = false;
                }
            });
        }
        summary.addEventListener("click", event => {
            event.preventDefault();
            setExpanded(!desired);
        });
        content.addEventListener("invalid", () => setExpanded(true), true);
        root.addEventListener("toggle", () => {
            if (root.open !== nativeOpen) {
                nativeOpen = root.open;
                setExpanded(root.open);
            }
        });
        return {setExpanded};
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
            arrow.hidden = !toggle.checked;
            void reveal(content, expanded && toggle.checked);
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

    window.StowlingSettings = Object.freeze({
        switchControl, disclosure, optionalSection, reveal, bindDisclosure
    });
})();
