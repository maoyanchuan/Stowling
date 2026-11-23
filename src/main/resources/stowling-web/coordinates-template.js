(() => {
    "use strict";

    const panels = Array.from(document.querySelectorAll(".resource-tab-panel"));
    const bookmarks = Array.from(document.querySelectorAll(".coordinate-bookmark"));
    const search = document.querySelector("#coordinate-library-search");
    const resourceSearch = document.querySelector("#resource-search");
    const resourceKind = document.querySelector("#coordinate-resource-kind");
    const areaKindField = document.querySelector("#coordinate-area-kind-field");
    const areaKind = document.querySelector("#coordinate-area-kind");
    const folderForm = document.querySelector("#coordinate-folder-form");
    const kindForm = document.querySelector("#coordinate-kind-form");
    const coordinateForm = document.querySelector("#new-coordinate-form");
    const regionForm = document.querySelector("#new-region-form");
    const status = document.querySelector("#coordinate-library-status");
    let draggedRow = null;

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
        updateStatus(normalized ? `找到 ${visible} 个匹配资源` : "已载入 3 个示例资源");
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

    /**
     * @returns {void}
     */
    function clearDragState() {
        draggedRow?.classList.remove("is-dragging");
        draggedRow = null;
        document.body.classList.remove("is-dragging");
        for (const folder of document.querySelectorAll(".coordinate-bookmark-folder")) {
            folder.classList.remove("is-drag-over");
        }
    }

    /**
     * @returns {void}
     */
    function bindDragAndDrop() {
        for (const row of document.querySelectorAll(
            ".coordinate-bookmark-row[draggable=\"true\"]"
        )) {
            row.addEventListener("dragstart", event => {
                draggedRow = row;
                row.classList.add("is-dragging");
                document.body.classList.add("is-dragging");
                event.dataTransfer?.setData(
                    "text/plain",
                    row.dataset.resourceRef || row.textContent || ""
                );
                if (event.dataTransfer) {
                    event.dataTransfer.effectAllowed = "move";
                }
            });
            row.addEventListener("dragend", clearDragState);
        }

        for (const folder of document.querySelectorAll(".coordinate-bookmark-folder")) {
            const folderRow = folder.querySelector(":scope > .coordinate-bookmark-row");
            const children = folder.querySelector(":scope > .coordinate-bookmark-children");
            if (!folderRow || !children) {
                continue;
            }
            folderRow.addEventListener("dragover", event => {
                event.preventDefault();
                folder.classList.add("is-drag-over");
                if (event.dataTransfer) {
                    event.dataTransfer.dropEffect = "move";
                }
            });
            folderRow.addEventListener("dragleave", event => {
                if (!folderRow.contains(event.relatedTarget)) {
                    folder.classList.remove("is-drag-over");
                }
            });
            folderRow.addEventListener("drop", event => {
                event.preventDefault();
                if (!draggedRow || draggedRow === folderRow || folder.contains(draggedRow)) {
                    clearDragState();
                    return;
                }
                const name = draggedRow.querySelector(".coordinate-bookmark-name")?.textContent
                    || "坐标";
                children.hidden = false;
                const folderButton = folderRow.querySelector(".coordinate-bookmark");
                folderButton?.setAttribute("aria-expanded", "true");
                children.append(draggedRow);
                updateStatus(`已将“${name.trim()}”移入文件夹`);
                clearDragState();
            });
        }
    }

    search?.addEventListener("input", () => filterBookmarks(search.value));
    resourceSearch?.addEventListener("input", () => filterResources(resourceSearch.value));

    resourceKind?.addEventListener("change", () => {
        if (areaKindField) {
            areaKindField.hidden = resourceKind.value !== "regions";
        }
    });

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
        const panelId = kind === "regions" && areaKind?.value === "category-zones"
            ? "category-zones"
            : kind === "regions" ? "regions" : kind === "warehouses"
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

    bindDragAndDrop();
    activatePanel("coordinate-points");
})();
