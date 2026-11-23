(() => {
    "use strict";

    const panels = Array.from(document.querySelectorAll(".resource-tab-panel"));
    const bookmarks = Array.from(document.querySelectorAll(".coordinate-bookmark"));
    const search = document.querySelector("#coordinate-library-search");
    const resourceSearch = document.querySelector("#resource-search");
    const folderForm = document.querySelector("#coordinate-folder-form");
    const kindForm = document.querySelector("#coordinate-kind-form");
    const coordinateForm = document.querySelector("#new-coordinate-form");
    const regionForm = document.querySelector("#new-region-form");
    const status = document.querySelector("#coordinate-library-status");

    /**
     * @param {string} panelId
     * @returns {void}
     */
    function activatePanel(panelId) {
        for (const panel of panels) {
            panel.hidden = panel.id !== panelId;
        }
        for (const bookmark of bookmarks) {
            const isSelected = bookmark.dataset.panel === panelId;
            if (isSelected) {
                bookmark.setAttribute("aria-current", "true");
            } else {
                bookmark.removeAttribute("aria-current");
            }
        }
        document.body.dataset.coordinateTab = panelId;
    }

    /**
     * @param {HTMLElement | null} form
     * @returns {void}
     */
    function showForm(form) {
        for (const candidate of [folderForm, kindForm, coordinateForm, regionForm]) {
            if (candidate) {
                candidate.hidden = candidate !== form;
            }
        }
        form?.querySelector("input, select")?.focus();
    }

    /**
     * @param {string} message
     * @returns {void}
     */
    function updateStatus(message) {
        if (status) {
            status.textContent = message;
        }
    }

    /**
     * @param {string} query
     * @returns {void}
     */
    function filterBookmarks(query) {
        const normalized = query.trim().toLocaleLowerCase();
        for (const row of document.querySelectorAll(".coordinate-bookmark-row")) {
            const text = row.textContent?.toLocaleLowerCase() || "";
            row.hidden = Boolean(normalized) && !text.includes(normalized);
        }
    }

    /**
     * @param {string} query
     * @returns {void}
     */
    function filterResources(query) {
        const normalized = query.trim().toLocaleLowerCase();
        const activePanel = document.querySelector(
            ".resource-tab-panel:not([hidden]) .editor-list"
        );
        if (!activePanel) {
            return;
        }
        let visible = 0;
        for (const card of activePanel.querySelectorAll("[data-search-text]")) {
            const text = card.dataset.searchText?.toLocaleLowerCase() || "";
            card.hidden = Boolean(normalized) && !text.includes(normalized);
            visible += card.hidden ? 0 : 1;
        }
        updateStatus(normalized ? `找到 ${visible} 个匹配资源` : "已载入 4 个示例资源");
    }

    /**
     * @param {HTMLElement} button
     * @returns {void}
     */
    function bindPanelButton(button) {
        button.addEventListener("click", () => {
            const panelId = button.dataset.panel;
            if (panelId) {
                activatePanel(panelId);
                filterResources(resourceSearch?.value || "");
            }
        });
    }

    for (const bookmark of bookmarks) {
        if (bookmark.dataset.panel) {
            bindPanelButton(bookmark);
        }
    }

    for (const folder of document.querySelectorAll(".coordinate-bookmark-folder")) {
        const button = folder.querySelector(
            ":scope > .coordinate-bookmark-row .coordinate-bookmark"
        );
        const children = folder.querySelector(":scope > .coordinate-bookmark-list");
        button?.addEventListener("click", () => {
            if (!children) {
                return;
            }
            const expanded = button.getAttribute("aria-expanded") === "true";
            button.setAttribute("aria-expanded", String(!expanded));
            children.hidden = expanded;
        });
    }

    search?.addEventListener("input", () => filterBookmarks(search.value));
    resourceSearch?.addEventListener("input", () => filterResources(resourceSearch.value));

    document.querySelector("#new-coordinate-resource")?.addEventListener("click", () => {
        showForm(kindForm);
    });
    document.querySelector("#new-coordinate-folder")?.addEventListener("click", () => {
        showForm(folderForm);
    });
    document.querySelector("#add-coordinate-button")?.addEventListener("click", () => {
        showForm(coordinateForm);
    });
    document.querySelector("#add-region-button")?.addEventListener("click", () => {
        showForm(regionForm);
    });

    for (const selector of [
        "#cancel-coordinate-folder",
        "#cancel-coordinate-kind",
        "#cancel-coordinate-button",
        "#cancel-region-button"
    ]) {
        document.querySelector(selector)?.addEventListener("click", () => showForm(null));
    }

    kindForm?.addEventListener("submit", event => {
        event.preventDefault();
        const kind = document.querySelector("#coordinate-resource-kind")?.value;
        const panelId = kind === "regions" ? "regions" : kind === "warehouses"
            ? "warehouses" : "coordinate-points";
        activatePanel(panelId);
        showForm(null);
        updateStatus("已切换到示例资源");
    });

    const toggles = document.querySelectorAll(
        ".coordinate-optional-field input[type=checkbox]"
    );
    for (const toggle of toggles) {
        const field = toggle.closest(".coordinate-optional-field")?.querySelector(
            "input:not([type=checkbox])"
        );
        toggle.addEventListener("change", () => {
            if (field) {
                field.disabled = !toggle.checked;
                field.hidden = !toggle.checked;
            }
        });
    }

    activatePanel("coordinate-points");
})();
