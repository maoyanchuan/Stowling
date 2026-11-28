(() => {
    "use strict";

    /**
     * Each owner keeps its own animation state; this helper never reads business configuration.
     * @returns {(content: HTMLElement, expanded: boolean, animate?: boolean,
     *     kind?: string) => Promise<void>}
     */
    function createReveal() {
        const reveals = new WeakMap();

        /** @param {HTMLElement} content @param {boolean} expanded
         * @param {boolean} [animate] @param {string} [kind] @returns {Promise<void>} */
        function reveal(content, expanded, animate = true, kind = "panel") {
            const previous = reveals.get(content);
            if (previous?.expanded === expanded) return previous.done;
            const animated = previous && animate && content.animate
                && !window.matchMedia?.("(prefers-reduced-motion: reduce)")?.matches;
            const height = animated && !content.hidden ? content.getBoundingClientRect().height : 0;
            const computed = previous?.animation ? window.getComputedStyle(content) : null;
            const opacity = computed?.opacity ?? (expanded ? 0 : 1);
            const offset = content.dataset?.placement === "above"
                ? "translateY(4px)" : "translateY(-4px)";
            const transform = computed?.transform ?? (expanded ? offset : "none");
            previous?.animation?.cancel();
            const minHeight = previous?.minHeight ?? content.style?.minHeight ?? "";
            const current = {expanded, minHeight, animation: null, done: Promise.resolve()};
            reveals.set(content, current);
            content.inert = !expanded;
            content.setAttribute?.("aria-hidden", String(!expanded));
            if (!animated) {
                content.hidden = !expanded;
                if (content.style && previous?.animation && kind === "panel") {
                    content.style.overflow = "";
                    content.style.minHeight = minHeight;
                }
                return current.done;
            }
            content.hidden = false;
            const style = content.style;
            if (kind === "panel") style.minHeight = "";
            const end = expanded ? content.scrollHeight : 0;
            if (kind === "panel") {
                style.minHeight = "0";
                style.overflow = "hidden";
            }
            const frames = kind === "menu" ? [
                {opacity, transform},
                {opacity: expanded ? 1 : 0, transform: expanded ? "none" : offset}
            ] : [
                {height: `${height}px`, opacity},
                {height: `${end}px`, opacity: expanded ? 1 : 0}
            ];
            const animation = content.animate(frames, {
                duration: expanded ? 200 : 150,
                easing: "cubic-bezier(0.16, 1, 0.3, 1)"
            });
            current.animation = animation;
            current.done = animation.finished.then(() => {
                if (reveals.get(content) !== current) return;
                content.hidden = !expanded;
                if (kind === "panel") {
                    style.overflow = "";
                    style.minHeight = minHeight || "";
                }
                current.animation = null;
            }, () => {});
            return current.done;
        }
        return reveal;
    }

    /** @param {HTMLElement} trigger @param {HTMLElement} menu @returns {void} */
    function positionMenu(trigger, menu) {
        menu.hidden = false;
        const bounds = trigger.getBoundingClientRect();
        const below = window.innerHeight - bounds.bottom - 12;
        const above = bounds.top - 12;
        const upwards = below < Math.min(menu.scrollHeight, 320) && above > below;
        menu.dataset.placement = upwards ? "above" : "below";
        menu.style.maxHeight = `${Math.max(0, Math.min(360, upwards ? above : below))}px`;
    }

    /** @typedef {{choices: () => HTMLButtonElement[], open: () => void,
     * close: (focus: boolean) => void, select?: (option: HTMLButtonElement) => void}} MenuKeys */

    /** @param {HTMLButtonElement} trigger @param {HTMLElement} menu
     * @param {MenuKeys} options @returns {void} */
    function bindMenuKeys(trigger, menu, options) {
        trigger.addEventListener("keydown", event => {
            if (!["ArrowDown", "ArrowUp", "Enter", " "].includes(event.key)) return;
            event.preventDefault();
            options.open();
        });
        menu.addEventListener("keydown", event => {
            if (event.key === "Escape" || event.key === "Tab") {
                if (event.key === "Escape") event.preventDefault();
                options.close(event.key === "Escape");
                return;
            }
            const choices = options.choices().filter(option => !option.disabled);
            if (options.select && ["Enter", " "].includes(event.key)) {
                const selected = choices.find(option => option === document.activeElement);
                if (selected) {
                    event.preventDefault();
                    options.select(selected);
                }
                return;
            }
            if (!["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) return;
            event.preventDefault();
            if (!choices.length) return;
            let index = choices.indexOf(document.activeElement);
            if (event.key === "Home") index = 0;
            if (event.key === "End") index = choices.length - 1;
            if (event.key === "ArrowDown") index = (index + 1) % choices.length;
            if (event.key === "ArrowUp") index = (index - 1 + choices.length) % choices.length;
            choices[index]?.focus();
        });
    }

    window.StowlingSettingsControls = Object.freeze({createReveal, positionMenu, bindMenuKeys});
})();
