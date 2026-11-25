(() => {
    "use strict";

    const storageKey = "stowling.taskDebugMode";
    const toggle = document.querySelector("#task-debug-mode");
    if (!toggle) return;
    let active = false;
    try {
        active = window.localStorage.getItem(storageKey) === "true";
    } catch (_) {
        // The current page can still expose debug controls without browser storage.
    }
    toggle.checked = active;

    /** @returns {boolean} */
    function enabled() {
        return active;
    }

    toggle.addEventListener("change", () => {
        active = toggle.checked;
        try {
            window.localStorage.setItem(storageKey, String(active));
        } catch (_) {
            // Keep this page's preference when browser storage is unavailable.
        }
        document.dispatchEvent(new CustomEvent("stowling:task-debug-mode-changed"));
    });
    window.StowlingTaskDebugMode = Object.freeze({enabled});
})();
