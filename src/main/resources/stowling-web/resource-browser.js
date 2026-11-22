(() => {
    "use strict";
    const S = window.Stowling;
    const Picker = window.StowlingResourcePicker;
    /** @param {string} id @returns {HTMLElement} */
    const byId = id => document.getElementById(id);
    const navigation = byId("coordinate-bookmarks");
    if (!navigation) return;

    const search = byId("coordinate-library-search");
    const folderForm = byId("coordinate-folder-form");
    const folderName = byId("coordinate-folder-name");
    const kindForm = byId("coordinate-kind-form");
    const kind = byId("coordinate-resource-kind");
    const areaKind = byId("coordinate-area-kind");
    const areaKindField = byId("coordinate-area-kind-field");
    const warehouseForm = byId("new-warehouse-form");
    const warehouseList = byId("warehouse-list");
    const status = byId("coordinate-library-status");
    const editorEmpty = byId("coordinate-editor-empty");
    const editors = [
        ["coordinate", "coordinate-points", "坐标点", "coordinate-list",
            "add-coordinate-button", "new-coordinate-form", "cancel-coordinate-button"],
        ["region", "regions", "区域", "region-list",
            "add-region-button", "new-region-form", "cancel-region-button"],
        ["category-zone", "category-zones", "分类区", "category-zone-list",
            "add-category-zone-button", "new-category-zone-form", "cancel-category-zone-button"],
        ["warehouse", "warehouses", "储存区", "warehouse-list"]
    ];
    const expanded = new Set();
    let directory;
    let warehouseData;
    let readNumber = 0;
    let busy = false;
    let selected = "";
    let unselectedPanel = window.location.hash.slice(1);
    let editingFolder = null;
    let creation = null;
    let dragged = "";
    let dropIndicator = null;

    /** @param {string} message @param {boolean} [error] @returns {void} */
    function showStatus(message, error = false) {
        status.textContent = message;
        status.classList.toggle("error", error);
    }

    /** @returns {Array<object>} */
    function bookmarks() {
        if (!directory) return [];
        const catalog = directory.catalog;
        const groups = [
            ["coordinate", directory.coordinates],
            ["region", directory.regions],
            ["category-zone", directory.categoryZones],
            ["warehouse", catalog.warehouses],
            ["folder", catalog.folders]
        ];
        const data = new Map();
        for (const [type, items] of groups) {
            for (const item of items) {
                const ref = type + ":" + item.id;
                const coordinates = type === "coordinate" ? [item.x, item.y, item.z]
                    : type === "region" ? [item.firstX, item.firstY, item.firstZ,
                        item.secondX, item.secondY, item.secondZ] : [];
                data.set(ref, {ref, id: item.id, name: item.name, type,
                    folderId: catalog.placements[ref] || "",
                    searchText: [item.name, ...coordinates].join(" ").toLocaleLowerCase()});
            }
        }
        const order = new Set([...(catalog.order || []), ...data.keys()]);
        return Array.from(order, ref => data.get(ref)).filter(Boolean);
    }

    /** @param {object} value @returns {void} */
    function apply(value) {
        directory = value;
        const data = JSON.stringify([value.catalog.warehouses, value.coordinates, value.regions]);
        if (data !== warehouseData) {
            warehouseData = data;
            warehouseList.replaceChildren(...value.catalog.warehouses.map(warehouseCard));
        }
        const entries = bookmarks();
        if (!entries.some(item => item.ref === selected)) {
            const preferred = editors.find(([, panel]) => panel === unselectedPanel)?.[0];
            const candidates = entries.filter(item => item.type !== "folder");
            const match = preferred ? candidates.find(item => item.type === preferred)
                : candidates.find(item => !item.folderId) || candidates[0];
            selected = match?.ref || "";
            if (match?.folderId) expanded.add(match.folderId);
        }
        render();
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

    /**
     * @param {string} path
     * @param {string} method
     * @param {object} payload
     * @returns {Promise<boolean>}
     */
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

    /**
     * @param {string} ref
     * @param {string} folderId
     * @param {string|null} [beforeRef]
     * @returns {Promise<boolean>}
     */
    function place(ref, folderId, beforeRef = null) {
        if (folderId) expanded.add(folderId);
        return write("/v1/coordinate-resources", "PUT", {
            resourceRef: ref, folderId: folderId || null, beforeRef
        });
    }

    /** @param {string} folderId @returns {Array<object>} */
    function siblings(folderId) {
        return bookmarks().filter(item => item.folderId === folderId);
    }

    /**
     * @param {string} ref
     * @param {string} folderId
     * @param {HTMLButtonElement} up
     * @param {HTMLButtonElement} down
     * @returns {void}
     */
    function setOrderActions(ref, folderId, up, down) {
        const items = siblings(folderId);
        const index = items.findIndex(item => item.ref === ref);
        up.disabled = index < 1;
        down.disabled = index < 0 || index >= items.length - 1;
        up.onclick = () => void place(ref, folderId, items[index - 1]?.ref);
        down.onclick = () => void place(ref, folderId, items[index + 2]?.ref);
    }

    /** @param {string} id @returns {void} */
    function editFolder(id) {
        creation = null;
        editingFolder = id;
        folderName.value = directory?.catalog.folders.find(folder => folder.id === id)?.name || "";
        byId("coordinate-folder-editor-title").textContent = id ? "管理文件夹" : "新建文件夹";
        byId("delete-coordinate-folder").hidden = byId("coordinate-folder-order").hidden = !id;
        render();
        folderName.focus();
    }

    /** @param {string} folderId @returns {void} */
    function beginCreation(folderId) {
        editingFolder = null;
        creation = {folderId, panel: ""};
        kind.value = "";
        areaKind.value = "regions";
        areaKindField.hidden = true;
        const folder = directory?.catalog.folders.find(item => item.id === folderId);
        byId("coordinate-create-destination").textContent =
            folder ? "保存到「" + folder.name + "」" : "";
        render();
        kind.focus();
    }

    /** @returns {void} */
    function closeEditor() {
        creation = null;
        editingFolder = null;
        for (const [, , , , , form] of editors) {
            if (form) byId(form).hidden = true;
        }
        warehouseForm.hidden = true;
        render();
    }

    /** @param {string} ref @param {string} revision @returns {Promise<void>} */
    async function selectCreated(ref, revision) {
        const folderId = creation?.folderId;
        creation = null;
        selected = ref;
        if (folderId && directory) {
            directory = {...directory, revision};
            await place(ref, folderId);
        } else {
            await load();
        }
        render();
        navigation.querySelector('[aria-current="true"]')?.focus();
    }

    /** @param {HTMLElement} host @returns {Array<string>} */
    function checkedMembers(host) {
        return Array.from(host.querySelectorAll("input[type=checkbox]:checked"), item => item.value);
    }

    /** @param {HTMLElement} host @param {Array<string>} selectedMembers @returns {void} */
    function renderMembers(host, selectedMembers) {
        host.replaceChildren(S.element("legend", "", "储存区成员"));
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
            checkbox.checked = selectedMembers.includes(ref);
            const field = S.element("label", "coordinate-warehouse-member");
            field.append(checkbox, S.element("span", "", label));
            host.append(field);
        }
    }

    /** @param {object} warehouse @returns {HTMLElement} */
    function warehouseCard(warehouse) {
        const card = S.element("details", "editor-card");
        card.dataset.resourceId = warehouse.id;
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
        label.append(S.element("span", "", "储存区名称"), name);
        const members = S.element("fieldset", "");
        renderMembers(members, warehouse.members);
        const actions = S.element("div", "move-form-actions");
        const save = S.element("button", "primary", "保存储存区");
        save.type = "submit";
        const remove = S.element("button", "ghost danger-action", "删除");
        remove.type = "button";
        const path = "/v1/warehouses/" + warehouse.id;
        remove.addEventListener("click", () => {
            if (window.confirm("删除储存区“" + warehouse.name + "”？")) {
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
            const actions = S.element("div", "coordinate-order-actions");
            for (const text of ["上移", "下移"]) {
                const button = S.element("button", "ghost compact", text);
                button.type = "button";
                actions.append(button);
            }
            metadata.append(label, actions, S.element("p", "muted compact-help"));
            card.append(metadata);
            folder.addEventListener("change", async () => {
                if (!await place(ref, folder.value)) showMetadata(card, ref);
            });
        }
        const folder = metadata.querySelector("select");
        folder.replaceChildren(new Option("根目录", ""),
            ...directory.catalog.folders.map(item => new Option(item.name, item.id)));
        folder.value = directory.catalog.placements[ref] || "";
        const [up, down] = metadata.querySelectorAll("button");
        setOrderActions(ref, folder.value, up, down);
        const references = directory.references?.[ref] || [];
        metadata.querySelector("p").textContent = references.length
            ? "正在使用：" + references.join("、") : "";
        const remove = card.querySelector(".danger-action");
        if (remove) remove.disabled = references.length > 0;
    }

    /** @returns {void} */
    function renderEditors() {
        const type = selected.split(":")[0];
        const active = editors.find(([kind]) => kind === type);
        const panelId = editingFolder !== null ? "" : creation ? creation.panel : active?.[1] || "";
        folderForm.hidden = editingFolder === null;
        kindForm.hidden = !creation || Boolean(creation.panel);
        editorEmpty.hidden = Boolean(panelId || editingFolder !== null || creation);
        editorEmpty.firstElementChild.textContent = bookmarks().some(item => item.type !== "folder")
            ? "选择左侧的坐标查看或编辑。" : "添加坐标后，可以在这里查看和编辑。";
        document.dispatchEvent(new CustomEvent("stowling:coordinate-panel-open",
            {detail: {panelId}}));
        for (const [kind, panel, , id] of editors) {
            const list = byId(id);
            list.hidden = creation?.panel === panel;
            for (const card of list.querySelectorAll(":scope > details")) {
                const ref = kind + ":" + card.dataset.resourceId;
                const current = panel === panelId && ref === selected && !creation;
                card.classList.toggle("resource-not-selected", !current);
                if (current) {
                    if (!card.open) card.open = true;
                    showMetadata(card, ref);
                }
            }
        }
        if (editingFolder) {
            setOrderActions("folder:" + editingFolder, "", byId("move-coordinate-folder-up"),
                byId("move-coordinate-folder-down"));
        }
    }

    /** @param {object} item @param {boolean} sortable @returns {HTMLElement} */
    function bookmarkRow(item, sortable) {
        const row = S.element("div", "coordinate-bookmark-row");
        row.dataset.ref = item.ref;
        row.dataset.folderId = item.folderId;
        row.draggable = sortable;
        const button = S.element("button", "coordinate-bookmark");
        button.type = "button";
        button.title = item.name;
        button.dataset.bookmark = item.ref;
        button.append(S.element("span", "coordinate-bookmark-name", item.name));
        row.append(button);
        if (item.type === "folder") {
            row.classList.add("coordinate-bookmark-folder");
            button.setAttribute("aria-expanded", String(expanded.has(item.id) || !sortable));
            button.setAttribute("aria-controls", "coordinate-folder-" + item.id);
            button.addEventListener("click", () => {
                if (expanded.has(item.id)) expanded.delete(item.id);
                else expanded.add(item.id);
                render();
            });
            const add = S.element("button", "ghost compact", "添加");
            add.type = "button";
            add.setAttribute("aria-label", "向" + item.name + "添加坐标");
            add.addEventListener("click", () => beginCreation(item.id));
            const manage = S.element("button", "ghost compact", "管理");
            manage.type = "button";
            manage.setAttribute("aria-label", "管理文件夹" + item.name);
            manage.addEventListener("click", () => editFolder(item.id));
            row.append(add, manage);
        } else {
            const active = !creation && editingFolder === null && selected === item.ref;
            if (active) button.setAttribute("aria-current", "true");
            const label = editors.find(([type]) => type === item.type)[2];
            button.append(S.element("small", "coordinate-bookmark-type", label));
            button.addEventListener("click", () => {
                selected = item.ref;
                closeEditor();
            });
        }
        return row;
    }

    /** @returns {void} */
    function renderNavigation() {
        const focused = navigation.contains(document.activeElement)
            ? document.activeElement.dataset.bookmark : "";
        const query = search.value.trim().toLocaleLowerCase();
        const sortable = !query;
        const entries = bookmarks();
        const tree = S.element("ul", "coordinate-bookmark-list");
        for (const item of entries.filter(entry => !entry.folderId)) {
            const matches = item.searchText.includes(query);
            const children = item.type === "folder"
                ? entries.filter(entry => entry.folderId === item.id
                    && (matches || entry.searchText.includes(query))) : [];
            if (!matches && !children.length) continue;
            const node = S.element("li", "");
            node.append(bookmarkRow(item, sortable));
            if (item.type === "folder") {
                const group = S.element("ul", "coordinate-bookmark-list coordinate-bookmark-children");
                group.id = "coordinate-folder-" + item.id;
                group.dataset.dropFolder = item.id;
                group.hidden = sortable && !expanded.has(item.id);
                for (const child of children) {
                    const leaf = S.element("li", "");
                    leaf.append(bookmarkRow(child, sortable));
                    group.append(leaf);
                }
                if (!children.length) {
                    group.append(S.element("li", "coordinate-bookmark-empty", "空文件夹"));
                }
                node.append(group);
            }
            tree.append(node);
        }
        if (!tree.children.length) {
            const text = !directory ? "正在读取坐标…" : query ? "没有匹配的坐标" : "还没有坐标";
            tree.append(S.element("li", "coordinate-bookmark-empty", text));
        }
        const rootDrop = S.element("p", "coordinate-root-drop", "移到根目录");
        rootDrop.dataset.dropFolder = "";
        navigation.replaceChildren(tree, rootDrop);
        if (focused) navigation.querySelector('[data-bookmark="' + focused + '"]')?.focus();
    }

    /** @returns {void} */
    function render() {
        renderNavigation();
        renderEditors();
    }

    /** @returns {void} */
    function clearDropIndicator() {
        if (dropIndicator) {
            dropIndicator.element.removeAttribute("data-drop-position");
            dropIndicator = null;
        }
    }

    /** @param {DragEvent} event @returns {object|null} */
    function dropLocation(event) {
        const row = event.target.closest(".coordinate-bookmark-row");
        const movingFolder = dragged.startsWith("folder:");
        if (!row) {
            const group = event.target.closest("[data-drop-folder]");
            if (!group || movingFolder && group.dataset.dropFolder) return null;
            return {element: group, position: "inside",
                folderId: group.dataset.dropFolder, beforeRef: null};
        }
        const {ref, folderId} = row.dataset;
        if (ref === dragged || movingFolder && folderId) return null;
        const bounds = row.getBoundingClientRect();
        const offset = (event.clientY - bounds.top) / bounds.height;
        if (!movingFolder && ref.startsWith("folder:") && offset > 0.25 && offset < 0.75) {
            return {element: row, position: "inside", folderId: ref.slice(7), beforeRef: null};
        }
        const items = siblings(folderId);
        const index = items.findIndex(item => item.ref === ref);
        const before = offset < 0.5;
        return {element: row, position: before ? "before" : "after", folderId,
            beforeRef: before ? ref : items[index + 1]?.ref || null};
    }

    /** @returns {void} */
    function stopDragging() {
        clearDropIndicator();
        dragged = "";
        navigation.classList.remove("is-dragging");
    }

    navigation.addEventListener("dragstart", event => {
        const row = event.target.closest(".coordinate-bookmark-row");
        if (!row?.draggable || busy) {
            event.preventDefault();
            return;
        }
        dragged = row.dataset.ref;
        event.dataTransfer.setData("text/plain", dragged);
        event.dataTransfer.effectAllowed = "move";
        navigation.classList.add("is-dragging");
    });
    navigation.addEventListener("dragover", event => {
        if (!dragged) return;
        const target = dropLocation(event);
        clearDropIndicator();
        if (!target) return;
        event.preventDefault();
        event.dataTransfer.dropEffect = "move";
        target.element.dataset.dropPosition = target.position;
        dropIndicator = target;
    });
    navigation.addEventListener("dragleave", event => {
        if (!navigation.contains(event.relatedTarget)) clearDropIndicator();
    });
    navigation.addEventListener("drop", event => {
        if (!dragged) return;
        const target = dropLocation(event);
        const ref = dragged;
        stopDragging();
        if (target && target.beforeRef !== ref) {
            event.preventDefault();
            void place(ref, target.folderId, target.beforeRef);
        }
    });
    navigation.addEventListener("dragend", stopDragging);

    byId("new-coordinate-resource").addEventListener("click", () => beginCreation(""));
    byId("new-coordinate-folder").addEventListener("click", () => editFolder(""));
    for (const id of ["cancel-coordinate-folder", "cancel-coordinate-kind", "cancel-new-warehouse",
        ...editors.map(([, , , , , , cancel]) => cancel).filter(Boolean)]) {
        byId(id).addEventListener("click", closeEditor);
    }
    kind.addEventListener("change", () => { areaKindField.hidden = kind.value !== "regions"; });
    kindForm.addEventListener("submit", event => {
        event.preventDefault();
        if (!kind.value) return;
        const panel = kind.value === "regions" ? areaKind.value : kind.value;
        creation.panel = panel;
        const editor = editors.find(([, id]) => id === panel);
        renderEditors();
        if (editor[4]) byId(editor[4]).click();
        else {
            warehouseForm.reset();
            warehouseForm.hidden = false;
            renderMembers(byId("new-warehouse-members"), []);
            byId("new-warehouse-name").focus();
        }
    });
    byId("delete-coordinate-folder").addEventListener("click", async () => {
        const id = editingFolder;
        if (window.confirm("删除文件夹？其中的坐标会移到根目录。")
                && await write("/v1/coordinate-folders/" + id, "DELETE", {})) {
            expanded.delete(id);
            if (editingFolder === id) closeEditor();
        }
    });
    folderForm.addEventListener("submit", async event => {
        event.preventDefault();
        const id = editingFolder;
        const path = "/v1/coordinate-folders" + (id ? "/" + id : "");
        const previous = new Set(directory?.catalog.folders.map(folder => folder.id));
        if (await write(path, id ? "PUT" : "POST", {name: folderName.value})) {
            const created = directory.catalog.folders.find(folder => !previous.has(folder.id));
            if (created) expanded.add(created.id);
            closeEditor();
            const ref = "folder:" + (id || created?.id);
            navigation.querySelector('[data-bookmark="' + ref + '"]')?.focus();
        }
    });
    warehouseForm.addEventListener("submit", async event => {
        event.preventDefault();
        const previous = new Set(directory?.catalog.warehouses.map(item => item.id));
        if (await write("/v1/warehouses", "POST", {
            name: byId("new-warehouse-name").value,
            members: checkedMembers(warehouseForm)
        })) {
            const created = directory.catalog.warehouses.find(item => !previous.has(item.id));
            warehouseForm.hidden = true;
            if (created) await selectCreated("warehouse:" + created.id, directory.revision);
        }
    });
    search.addEventListener("input", renderNavigation);
    for (const [, , , listId] of editors) {
        new MutationObserver(renderEditors).observe(byId(listId), {childList: true});
    }
    document.addEventListener("stowling:coordinate-directory-change", async event => {
        if (event.detail.created) await selectCreated(event.detail.created, event.detail.revision);
        else await load();
    });
    window.addEventListener("hashchange", () => {
        unselectedPanel = window.location.hash.slice(1);
        selected = "";
        closeEditor();
        if (directory) apply(directory);
    });
    renderNavigation();
    S.onConnected(() => void load());
})();
