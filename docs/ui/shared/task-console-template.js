/* Preview interactions only. No game services, configuration or execution are accessed. */
(() => {
    "use strict";

    /** @typedef {{name: string, state: string, message: string, time: string}} PreviewLog */
    const runSwitch = document.getElementById("sample-enabled");
    const collapse = document.getElementById("sample-collapse");
    const taskName = "售卖弹性物资";
    /** @type {PreviewLog[]} */
    const logs = [{name: taskName, state: runSwitch.checked ? "运行中" : "未启动",
        message: "离线界面示例，未执行游戏任务。", time: time()}];
    const dialog = document.getElementById("template-run-dialog");

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

    /** @returns {HTMLElement} */
    function queueEntry() {
        const entry = element("article", "template-queue-entry");
        const heading = element("div", "template-entry-heading");
        heading.append(element("strong", "", taskName), element("small", "", "普通"));
        entry.append(heading, element("span", "template-entry-state", "运行中（示例）"),
            element("p", "template-entry-message", "按当前地区、售卖策略与数量预览任务状态。"));
        return entry;
    }

    /** @returns {void} */
    function renderQueue() {
        const running = runSwitch.checked;
        for (const id of ["template-queue-list", "template-run-dialog-list"]) {
            const list = document.getElementById(id);
            list.replaceChildren(running ? queueEntry()
                : element("p", "template-empty", "暂无运行任务"));
        }
        document.getElementById("template-queue-count").textContent = running ? "1" : "0";
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

    runSwitch.addEventListener("change", () => {
        logs.unshift({name: taskName, state: runSwitch.checked ? "已开启" : "已关闭",
            message: "仅切换离线预览状态，未执行游戏任务。", time: time()});
        renderQueue();
        renderLogs();
    });
    document.getElementById("template-select-selling-task").addEventListener("click", () => {
        if (collapse.getAttribute("aria-expanded") !== "true") collapse.click();
        collapse.focus();
        feedback("已打开售卖弹性物资设置，保留当前填写内容。");
    });
    document.getElementById("template-refresh-queue").addEventListener("click", renderQueue);
    document.getElementById("template-refresh-tasks").addEventListener("click", () => {
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
})();
