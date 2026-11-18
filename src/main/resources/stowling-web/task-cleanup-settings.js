(() => {
    "use strict";
    /** @param {HTMLElement} host @param {object} options @returns {object} */
    function create(host, options = {}) {
        const S = window.Stowling;
        const destination = document.createElement("select");
        const resume = document.createElement("select");
        const row = document.createElement("label");
        row.className = "move-field";
        row.append(S.element("span", "", "中断存放位置"), destination);
        host.append(row);
        resume.append(new Option("重新执行任务", "RESTART"),
            new Option("保留已完成阶段和交付记录", "CONTINUE"));
        if (options.allowContinue !== false) {
            const mode = document.createElement("label");
            mode.className = "move-field";
            mode.append(S.element("span", "", "被紧急任务中断后的恢复方式"), resume);
            host.append(mode);
        }
        host.append(S.element("p", "auto-config-note", options.allowContinue === false
            ? "先回收本任务放置的潜影盒，再将任务物品整盒或散装存入指定位置；分类重新扫描。"
            : "先回收本任务放置的潜影盒，再归还任务物品。保留进度会在紧急任务后自动重排，跳过已完成阶段，"
                + "当前阶段重新核对；已确认交付数量可供材料收集模块扣减。"));
        /** @param {Array<object>} points @param {Array<object>} regions @returns {void} */
        function resources(points, regions) {
            const previous = destination.value;
            destination.replaceChildren(new Option(options.defaultLabel
                || "使用旧任务公共暂存（兼容）", ""));
            for (const point of points) {
                destination.append(new Option(`[箱子坐标] ${point.name}`,
                    `coordinate:${point.id}`));
            }
            for (const region of regions) {
                destination.append(new Option(`[容器区域] ${region.name}`, `region:${region.id}`));
            }
            destination.value = previous;
        }
        /** @param {object} policy @returns {void} */
        function set(policy = {}) {
            const ref = policy?.destinationRef || "";
            if (ref && !Array.from(destination.options).some(option => option.value === ref)) {
                const missing = new Option("原存放位置不存在，请重新选择", ref);
                missing.disabled = true;
                destination.append(missing);
            }
            destination.value = ref;
            resume.value = policy?.resumeMode === "CONTINUE" ? "CONTINUE" : "RESTART";
        }
        /** @returns {object} */
        function read() {
            return {destinationRef: destination.value || null,
                resumeMode: options.allowContinue === false ? "RESTART" : resume.value};
        }
        return {resources, set, read};
    }
    window.StowlingTaskCleanupSettings = Object.freeze({create});
})();
