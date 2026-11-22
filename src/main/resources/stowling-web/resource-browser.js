(() => {
    "use strict";
    const S = window.Stowling;
    const Picker = window.StowlingResourcePicker;
    /** @param {string} id @returns {HTMLElement} */
    const byId = id => document.getElementById(id);
    const library = byId("coordinate-library");
    if (!library) return;

    const ROOT = "unfiled";
    const folderFilter = byId("coordinate-folder-filter");
    const search = byId("coordinate-library-search");
    const folderForm = byId("coordinate-folder-form");
    const folderName = byId("coordinate-folder-name");
    const rename = byId("rename-coordinate-folder");
    const removeFolder = byId("delete-coordinate-folder");
    const kindForm = byId("coordinate-kind-form");
    const kind = byId("coordinate-resource-kind");
    const warehouseForm = byId("new-warehouse-form");
    const warehouseList = byId("warehouse-list");
    const status = byId("coordinate-library-status");
    const views = new Map();
    const panels = {
        "coordinate-points": "add-coordinate-button",
        regions: "add-region-button",
        "category-zones": "add-category-zone-button"
    };
    let selectedFolder = "";
    let editingFolder = "";
    let directory;
    let readNumber = 0;
    let busy = false;

    /** @param {string} message @param {boolean} [error] @returns {void} */
    function showStatus(message, error = false) {
        status.textContent = message;
        status.classList.toggle("error", error);
    }

    /** @returns {Array<object>} */
    function folders() {
        return directory?.catalog?.folders || [];
    }

    /** @param {object} value @returns {void} */
    function apply(value) {
        directory = value;
        if (selectedFolder && selectedFolder !== ROOT
                && !folders().some(folder => folder.id === selectedFolder)) {
            selectedFolder = ROOT;
        }
        folderFilter.replaceChildren(new Option("全部文件夹", ""), new Option("未分组", ROOT),
            ...folders().map(folder => new Option(folder.name, folder.id)));
        folderFilter.value = selectedFolder;
        syncFolderActions();
        renderWarehouses();
        for (const render of views.values()) render();
    }

    /** @returns {Promise<void>} */
    async function load() {
        const current = ++readNumber;
        try {
            const response = await Picker.load(true);
            if (current === readNumber) apply(response);
        } catch (error) {
            showStatus("读取坐标目录失败，请刷新后重试。", true);
            S.handleError(error, "读取坐标目录失败");
        }
    }

    /** @param {string} path @param {string} method @param {object} payload @returns {Promise<boolean>} */
    async function write(path, method, payload) {
        if (!directory || busy) return false;
        ++readNumber;
        busy = true;
        try {
            const response = await S.request(path, {
                method,
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({revision: directory.revision, ...payload})
            });
            apply(response);
            document.dispatchEvent(new CustomEvent("stowling:coordinate-directory-saved",
                {detail: {revision: response.revision}}));
            showStatus("已保存");
            return true;
        } catch (error) {
            showStatus(String(error.message || "保存失败，请刷新后重试。"), true);
            S.handleError(error, "保存坐标目录失败");
            await load();
            return false;
        } finally {
            busy = false;
        }
    }

    /** @param {string} ref @param {string} folderId @returns {Promise<boolean>} */
    function move(ref, folderId) {
        return write("/v1/coordinate-resources", "PUT", {
            resourceRef: ref, folderId: folderId || null
        });
    }

    /** @returns {void} */
    function syncFolderActions() {
        rename.hidden = removeFolder.hidden = !selectedFolder || selectedFolder === ROOT;
    }

    /** @param {string} id @returns {void} */
    function editFolder(id) {
        editingFolder = id;
        folderName.value = folders().find(folder => folder.id === id)?.name || "";
        folderForm.hidden = false;
        folderName.focus();
    }

    folderFilter.addEventListener("change", () => {
        selectedFolder = folderFilter.value;
        syncFolderActions();
        for (const render of views.values()) render();
    });
    search.addEventListener("input", () => {
        for (const render of views.values()) render();
    });
    byId("new-coordinate-folder").addEventListener("click", () => editFolder(""));
    rename.addEventListener("click", () => editFolder(selectedFolder));
    byId("cancel-coordinate-folder").addEventListener("click", () => {
        folderForm.hidden = true;
    });
    removeFolder.addEventListener("click", () => {
        if (window.confirm("删除文件夹？其中的资源将移回未分组。")) {
            void write("/v1/coordinate-folders/" + selectedFolder, "DELETE", {});
        }
    });
    folderForm.addEventListener("submit", async event => {
        event.preventDefault();
        const path = "/v1/coordinate-folders" + (editingFolder ? "/" + editingFolder : "");
        const previous = new Set(folders().map(folder => folder.id));
        if (await write(path, editingFolder ? "PUT" : "POST", {name: folderName.value})) {
            if (!editingFolder) {
                selectedFolder = folders().find(folder => !previous.has(folder.id))?.id || "";
                folderFilter.value = selectedFolder;
                syncFolderActions();
                for (const render of views.values()) render();
            }
            folderForm.hidden = true;
            folderFilter.focus();
        }
    });
    byId("new-coordinate-resource").addEventListener("click", () => {
        kind.value = "";
        kindForm.hidden = false;
        kind.focus();
    });
    byId("cancel-coordinate-kind").addEventListener("click", () => {
        kindForm.hidden = true;
        byId("new-coordinate-resource").focus();
    });
    kindForm.addEventListener("submit", event => {
        event.preventDefault();
        const panel = kind.value;
        if (!panel) return;
        kindForm.hidden = true;
        window.location.hash = panel;
        if (panels[panel]) byId(panels[panel]).click();
        else {
            warehouseForm.reset();
            warehouseForm.hidden = false;
            renderMembers(byId("new-warehouse-members"), []);
            byId("new-warehouse-name").focus();
        }
    });
    byId("cancel-new-warehouse").addEventListener("click", () => {
        warehouseForm.hidden = true;
        byId("new-coordinate-resource").focus();
    });
    warehouseForm.addEventListener("submit", async event => {
        event.preventDefault();
        const previous = new Set((directory?.catalog?.warehouses || []).map(item => item.id));
        if (await write("/v1/warehouses", "POST", {
            name: byId("new-warehouse-name").value,
            members: checkedMembers(warehouseForm)
        })) {
            const created = directory.catalog.warehouses.find(item => !previous.has(item.id));
            if (created && selectedFolder && selectedFolder !== ROOT) {
                await move("warehouse:" + created.id, selectedFolder);
            }
            warehouseForm.hidden = true;
        }
    });

    /** @param {HTMLElement} host @returns {Array<string>} */
    function checkedMembers(host) {
        return Array.from(host.querySelectorAll("input[type=checkbox]:checked"), item => item.value);
    }

    /** @param {HTMLElement} host @param {Array<string>} members @returns {void} */
    function renderMembers(host, members) {
        host.replaceChildren(S.element("legend", "", "仓库成员"));
        const values = [
            ...(directory?.coordinates || []).map(item => ["coordinate:" + item.id,
                "点位 · " + item.name]),
            ...(directory?.regions || []).map(item => ["region:" + item.id, "区域 · " + item.name])
        ];
        if (!values.length) host.append(S.element("p", "muted", "请先添加点位或普通区域。"));
        for (const [ref, label] of values) {
            const checkbox = document.createElement("input");
            checkbox.type = "checkbox";
            checkbox.value = ref;
            checkbox.checked = members.includes(ref);
            const field = S.element("label", "coordinate-warehouse-member");
            field.append(checkbox, S.element("span", "", label));
            host.append(field);
        }
    }

    /** @returns {void} */
    function renderWarehouses() {
        warehouseList.replaceChildren(...(directory?.catalog?.warehouses || []).map(warehouseCard));
    }

    /** @param {object} warehouse @returns {HTMLElement} */
    function warehouseCard(warehouse) {
        const card = S.element("details", "editor-card");
        card.dataset.resourceId = warehouse.id;
        card.dataset.searchText = warehouse.name;
        const summary = S.element("summary", "editor-card-heading");
        summary.append(S.element("strong", "", warehouse.name),
            S.element("span", "resource-summary-meta", warehouse.members.length + " 个成员"));
        const form = S.element("form", "resource-editor-body");
        const label = S.element("label", "move-field");
        const name = document.createElement("input");
        name.type = "text";
        name.value = warehouse.name;
        name.maxLength = 80;
        name.required = true;
        label.append(S.element("span", "", "仓库名称"), name);
        const members = S.element("fieldset", "");
        renderMembers(members, warehouse.members);
        const actions = S.element("div", "move-form-actions");
        const save = S.element("button", "primary", "保存仓库");
        save.type = "submit";
        const remove = S.element("button", "ghost danger-action", "删除");
        remove.type = "button";
        const path = "/v1/warehouses/" + warehouse.id;
        remove.addEventListener("click", () => {
            if (window.confirm("删除仓库“" + warehouse.name + "”？")) {
                void write(path, "DELETE", {});
            }
        });
        form.addEventListener("submit", event => {
            event.preventDefault();
            void write(path, "PUT", {name: name.value, members: checkedMembers(form)});
        });
        actions.append(save, remove);
        form.append(label, members, actions);
        card.append(summary, form);
        return card;
    }

    /** @param {HTMLElement} card @param {string} ref @returns {void} */
    function showMetadata(card, ref) {
        let metadata = card.querySelector(":scope > .coordinate-resource-metadata");
        if (!metadata) {
            metadata = S.element("div", "coordinate-resource-metadata");
            const label = S.element("label", "move-field");
            const folder = document.createElement("select");
            label.append(S.element("span", "", "移到文件夹"), folder);
            const hint = S.element("p", "muted compact-help");
            metadata.append(label, hint);
            card.append(metadata);
            folder.addEventListener("change", async () => {
                if (!await move(ref, folder.value)) showMetadata(card, ref);
            });
        }
        const folder = metadata.querySelector("select");
        folder.replaceChildren(new Option("未分组", ""),
            ...folders().map(item => new Option(item.name, item.id)));
        folder.value = directory?.catalog?.placements?.[ref] || "";
        folder.disabled = !directory;
        const references = directory?.references?.[ref] || [];
        metadata.querySelector("p").textContent = references.length
            ? "正在使用：" + references.join("、") : "";
        const remove = card.querySelector(".danger-action");
        if (remove) remove.disabled = references.length > 0;
    }

    /** @param {string} id @param {string} type @returns {void} */
    function mount(id, type) {
        const list = byId(id);
        const browser = S.element("div", "resource-browser");
        const navigation = S.element("nav", "resource-browser-list");
        navigation.setAttribute("aria-label", "选择要编辑的资源");
        list.before(browser);
        browser.append(navigation, list);
        let selected = "";

        /** @returns {void} */
        function render() {
            const cards = Array.from(list.children).filter(card => card.tagName === "DETAILS");
            const query = search.value.trim().toLocaleLowerCase();
            const visible = cards.filter(card => {
                const ref = type + ":" + card.dataset.resourceId;
                const folderId = directory?.catalog?.placements?.[ref] || ROOT;
                const name = card.dataset.searchText || card.querySelector("strong").textContent;
                return (!selectedFolder || selectedFolder === folderId)
                    && name.toLocaleLowerCase().includes(query);
            });
            if (!visible.some(card => card.dataset.resourceId === selected)) {
                selected = visible[0]?.dataset.resourceId || "";
            }
            navigation.replaceChildren();
            if (!visible.length) navigation.append(S.element("p", "muted", "没有匹配的资源。"));
            for (const card of cards) {
                const key = card.dataset.resourceId;
                const active = selected === key;
                card.classList.toggle("resource-not-selected", !active);
                if (active) {
                    card.open = true;
                    showMetadata(card, type + ":" + key);
                }
                if (!visible.includes(card)) continue;
                const button = S.element("button", "resource-browser-item",
                    card.querySelector("summary strong")?.textContent || "资源");
                button.type = "button";
                button.setAttribute("aria-current", String(active));
                const meta = card.querySelector("summary .resource-summary-meta");
                if (meta) button.append(S.element("small", "", meta.textContent));
                button.addEventListener("click", () => {
                    selected = key;
                    render();
                    navigation.querySelector('[aria-current="true"]')?.focus();
                });
                navigation.append(button);
            }
        }
        new MutationObserver(render).observe(list, {childList: true});
        views.set(id, render);
        render();
    }

    mount("coordinate-list", "coordinate");
    mount("region-list", "region");
    mount("category-zone-list", "category-zone");
    mount("warehouse-list", "warehouse");
    document.addEventListener("stowling:coordinate-directory-change", async event => {
        const {created, revision} = event.detail;
        if (created && directory && selectedFolder && selectedFolder !== ROOT) {
            directory = {...directory, revision};
            await move(created, selectedFolder);
        } else {
            await load();
        }
    });
    S.onConnected(() => void load());
})();
