/* Preview interactions only. No game services, configuration or execution are accessed. */
(() => {
    "use strict";

    /** @typedef {{name: string, description: string, icon: string}} TaskType */
    /** @typedef {{id: number, type: string, running: boolean, expanded: boolean}} PreviewTask */
    /** @typedef {{name: string, state: string, message: string, time: string}} PreviewLog */
    /** @type {Record<string, TaskType>} */
    const types = {
        move: {name: "移动到坐标", description: "保存目标坐标，按需加入队列",
            icon: "M12 3v18M3 12h18m-9-9-3 3m3-3 3 3m-3 15-3-3m3 3 3-3"},
        pickup: {name: "掉落物拾取", description: "监听掉落物，拾取后存入缓存",
            icon: "m12 3 9 5v8l-9 5-9-5V8l9-5Zm0 10v8M3 8l9 5 9-5"},
        classification: {name: "分类", description: "从缓存取料，按分类区规则入库",
            icon: "M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z"},
        eat: {name: "自动进食", description: "饱食度偏低时吃随身食物或到箱子取食",
            icon: "M4 3v6a3 3 0 0 0 6 0V3M7 3v18M20 3c-4 0-5 9 0 9V3Zm0 9v9"},
        material: {name: "自动收集材料", description: "上传投影，自动从仓库取料并交付",
            icon: "M3 7h18v14H3zM7 7V3h10v4M3 12h18M9 12v4h6v-4"},
        refresh: {name: "刷新缓存", description: "前往选区逐一开箱，按需定时更新库存",
            icon: "M20 11a8 8 0 1 0-2 6M20 4v7h-7"}
    };
    /** @type {PreviewTask[]} */
    const tasks = [
        {id: 1, type: "pickup", running: true, expanded: false},
        {id: 2, type: "classification", running: false, expanded: false},
        {id: 3, type: "eat", running: true, expanded: false}
    ];
    /** @type {PreviewLog[]} */
    const logs = [{name: "分类", state: "已完成", message: "任务已完成 · 7 / 7 步（示例）",
        time: "16:38:22"}];
    const root = document.getElementById("stowling-settings-standard");
    const savedList = document.getElementById("template-saved-list");
    const dialog = document.getElementById("template-run-dialog");
    const reveal = window.StowlingSettingsControls.createReveal();
    let nextId = 4;

    /**
     * @param {string} tag @param {string} className @param {string} [text]
     * @returns {HTMLElement}
     */
    function element(tag, className, text = "") {
        const node = document.createElement(tag);
        node.className = className;
        node.textContent = text;
        return node;
    }

    /** @param {string} path @returns {SVGSVGElement} */
    function icon(path) {
        const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
        const shape = document.createElementNS(svg.namespaceURI, "path");
        svg.setAttribute("viewBox", "0 0 24 24");
        svg.setAttribute("fill", "none");
        svg.setAttribute("stroke", "currentColor");
        svg.setAttribute("stroke-width", "1.8");
        svg.setAttribute("stroke-linecap", "round");
        svg.setAttribute("stroke-linejoin", "round");
        svg.setAttribute("aria-hidden", "true");
        shape.setAttribute("d", path);
        svg.append(shape);
        return svg;
    }

    /** @returns {string} */
    function time() {
        return new Date().toLocaleTimeString("zh-CN", {hour12: false});
    }

    /** @param {string} message @returns {void} */
    function feedback(message) {
        const status = document.getElementById("template-task-feedback");
        status.hidden = false;
        status.textContent = message;
    }

    /** @param {PreviewTask} task @returns {HTMLElement} */
    function queueEntry(task) {
        const entry = element("article", "template-queue-entry");
        const heading = element("div", "template-entry-heading");
        heading.append(element("strong", "", types[task.type].name),
                element("small", "", ["pickup", "eat"].includes(task.type) ? "紧急" : "普通"));
        entry.append(heading, element("span", "template-entry-state", "监听中（示例）"),
            element("p", "template-entry-message", types[task.type].description));
        return entry;
    }

    /** @returns {void} */
    function renderQueue() {
        const running = tasks.filter(task => task.running).sort((left, right) =>
            Number(["pickup", "eat"].includes(right.type))
                - Number(["pickup", "eat"].includes(left.type)));
        for (const id of ["template-queue-list", "template-run-dialog-list"]) {
            const list = document.getElementById(id);
            list.replaceChildren(...running.map(queueEntry));
            if (!running.length) list.append(element("p", "template-empty", "暂无运行任务"));
        }
        document.getElementById("template-queue-count").textContent = String(running.length);
        document.getElementById("template-queue-status").textContent = `预览已刷新 · ${time()}`;
    }

    /** @returns {void} */
    function renderLogs() {
        const list = document.getElementById("template-log-list");
        list.replaceChildren(...logs.map(log => {
            const entry = element("article", "template-log-entry");
            const heading = element("div", "template-entry-heading");
            heading.append(element("small", "", log.time),
                element("span", "template-entry-state", log.state));
            entry.append(heading, element("strong", "", log.name),
                element("p", "template-entry-message", log.message));
            return entry;
        }));
        if (!logs.length) list.append(element("p", "template-empty", "暂无任务进度"));
        document.getElementById("template-log-count").textContent = String(logs.length);
        document.getElementById("template-export-logs").disabled = !logs.length;
        document.getElementById("template-clear-logs").disabled = !logs.length;
    }

    /** @param {PreviewTask} task @returns {void} */
    function addCard(task) {
        const type = types[task.type];
        const card = element("article", "sample-module template-task-card");
        card.dataset.previewTaskId = String(task.id);
        const header = element("div", "template-card-header");
        const title = element("h3", "template-card-title");
        title.append(icon(type.icon), document.createTextNode(type.name));
        const expand = element("button", "sample-icon-button sample-expand");
        expand.type = "button";
        expand.append(icon("m9 5 7 7-7 7"));
        const detail = element("div", "template-card-detail");
        detail.id = `template-task-detail-${task.id}`;
        const inner = element("div", "template-card-detail-inner");
        inner.append(element("p", "", type.description),
            element("p", "", "此卡片仅演示任务布局与状态，完整控件见下方设置控件示例。"));
        const remove = element("button", "template-text-button", "删除示例任务");
        remove.type = "button";
        inner.append(remove);
        detail.append(inner);
        const toggle = document.createElement("input");
        toggle.type = "checkbox";
        toggle.className = "sample-switch";
        toggle.checked = task.running;
        toggle.setAttribute("role", "switch");
        toggle.setAttribute("aria-label", `运行${type.name}任务（示例）`);
        expand.setAttribute("aria-controls", detail.id);

        /** @param {boolean} [animate] @returns {void} */
        function renderExpanded(animate = false) {
            expand.setAttribute("aria-expanded", String(task.expanded));
            expand.setAttribute("aria-label", `${task.expanded ? "收起" : "展开"}${type.name}示例`);
            reveal(detail, task.expanded, animate);
        }

        expand.addEventListener("click", () => {
            task.expanded = !task.expanded;
            renderExpanded(true);
        });
        toggle.addEventListener("change", () => {
            task.running = toggle.checked;
            logs.unshift({name: type.name, state: task.running ? "已开启" : "已关闭",
                message: "仅切换离线预览状态，未执行游戏任务。", time: time()});
            renderQueue();
            renderLogs();
        });
        remove.addEventListener("click", () => {
            tasks.splice(tasks.indexOf(task), 1);
            card.remove();
            updateCount();
            renderQueue();
            document.querySelector(`[data-template-task="${task.type}"]`).focus();
            feedback(`已移除${type.name}示例。`);
        });
        header.append(title, expand, toggle);
        card.append(header, detail);
        savedList.append(card);
        renderExpanded();
    }

    /** @returns {void} */
    function updateCount() {
        document.getElementById("template-saved-count").textContent = String(tasks.length);
    }

    for (const task of tasks) addCard(task);
    for (const button of document.querySelectorAll("[data-template-task]")) {
        button.addEventListener("click", () => {
            const task = {id: nextId++, type: button.dataset.templateTask,
                running: false, expanded: true};
            tasks.push(task);
            addCard(task);
            updateCount();
            feedback(`已添加${types[task.type].name}示例，仅在本次预览中保留。`);
            savedList.lastElementChild.querySelector("button").focus();
        });
    }
    document.getElementById("template-refresh-queue").addEventListener("click", renderQueue);
    document.getElementById("template-refresh-tasks").addEventListener("click", () => {
        updateCount();
        renderQueue();
        feedback("已刷新预览，任务开关与展开状态保持不变。");
    });
    document.getElementById("template-run-details").addEventListener("click", () => {
        renderQueue();
        dialog.showModal();
    });
    document.getElementById("template-clear-logs").addEventListener("click", () => {
        logs.length = 0;
        renderLogs();
    });
    document.getElementById("template-export-logs").addEventListener("click", () => {
        const blob = new Blob([JSON.stringify({preview: true, logs}, null, 4)],
            {type: "application/json;charset=utf-8"});
        const url = URL.createObjectURL(blob);
        const link = document.createElement("a");
        link.href = url;
        link.download = "stowling-template-example-logs.json";
        link.click();
        setTimeout(() => URL.revokeObjectURL(url), 0);
    });
    renderQueue();
    renderLogs();
    updateCount();
})();
