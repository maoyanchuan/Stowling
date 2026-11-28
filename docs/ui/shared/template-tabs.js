(() => {
    "use strict";

    const tabs = Array.from(document.querySelectorAll("[data-template-tab]"));
    const tabKey = "stowling.settings-template.tab";
    const themeKey = "stowling.settings-template.theme";

    /** @param {string} name @param {boolean} [persist] @returns {void} */
    function selectTab(name, persist = true) {
        const selected = tabs.find(tab => tab.dataset.templateTab === name) || tabs[0];
        for (const tab of tabs) {
            const active = tab === selected;
            tab.setAttribute("aria-selected", String(active));
            tab.tabIndex = active ? 0 : -1;
            const panel = document.getElementById(tab.getAttribute("aria-controls"));
            panel.hidden = !active;
            panel.inert = !active;
        }
        document.body.dataset.activeTemplate = selected.dataset.templateTab;
        document.dispatchEvent(new CustomEvent("stowling:template-tab-change"));
        if (!persist) return;
        try {
            localStorage.setItem(tabKey, selected.dataset.templateTab);
        } catch {
            // Switching panels does not require browser storage.
        }
    }

    /** @param {string} theme @param {boolean} [persist] @returns {void} */
    function setTheme(theme, persist = true) {
        const selected = theme === "gray" ? "gray" : "green";
        document.documentElement.dataset.theme = selected;
        for (const button of document.querySelectorAll("[data-theme-choice]")) {
            button.setAttribute("aria-pressed", String(button.dataset.themeChoice === selected));
        }
        if (!persist) return;
        try {
            localStorage.setItem(themeKey, selected);
        } catch {
            // Theme switching does not require browser storage.
        }
    }

    for (const [index, tab] of tabs.entries()) {
        tab.addEventListener("click", () => selectTab(tab.dataset.templateTab));
        tab.addEventListener("keydown", event => {
            if (!["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
            event.preventDefault();
            let next = index;
            if (event.key === "ArrowLeft") next = (index - 1 + tabs.length) % tabs.length;
            if (event.key === "ArrowRight") next = (index + 1) % tabs.length;
            if (event.key === "Home") next = 0;
            if (event.key === "End") next = tabs.length - 1;
            selectTab(tabs[next].dataset.templateTab);
            tabs[next].focus();
        });
    }
    for (const button of document.querySelectorAll("[data-theme-choice]")) {
        button.addEventListener("click", () => setTheme(button.dataset.themeChoice));
    }

    let initial = window.location.hash.slice(1);
    if (!initial) {
        try {
            initial = localStorage.getItem(tabKey) || "task";
        } catch {
            initial = "task";
        }
    }
    setTheme(document.documentElement.dataset.theme, false);
    selectTab(initial, false);
})();
