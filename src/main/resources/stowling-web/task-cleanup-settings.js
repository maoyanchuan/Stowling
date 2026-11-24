(() => {
    "use strict";
    /** @param {HTMLElement} host @param {object} options @returns {object} */
    function create(host, options = {}) {
        const S = window.Stowling;
        const content = S.element("div", "task-cleanup-fields");
        const destination = document.createElement("select");
        const resume = document.createElement("select");
        const supply = document.createElement("select");
        supply.multiple = true;
        supply.size = 4;
        const packing = document.createElement("select");
        let supplyRow = null;
        const row = document.createElement("label");
        row.className = "move-field";
        row.append(S.element("span", "", "中断存放位置"), destination);
        content.append(row);
        resume.append(new Option("重新执行任务", "RESTART"),
            new Option("保留已完成阶段和交付记录", "CONTINUE"));
        if (!options.fixedResumeMode && options.allowContinue !== false) {
            const mode = document.createElement("label");
            mode.className = "move-field";
            mode.append(S.element("span", "", "被紧急任务中断后的恢复方式"), resume);
            content.append(mode);
        }
        if (options.facilities) {
            supplyRow = S.element("label", "move-field");
            supplyRow.append(S.element("span", "", "空潜影盒供应箱"), supply,
                S.element("small", "muted", "可选择多个箱子点位"));
            const packingRow = S.element("label", "move-field");
            packingRow.append(S.element("span", "", "后备打包位置"), packing);
            content.append(supplyRow, packingRow);
        }
        content.append(S.element("p", "auto-config-note", options.allowContinue === false
            ? "先回收本任务放置的潜影盒，再将任务物品整盒或散装存入指定位置；分类重新扫描。"
            : "先回收本任务放置的潜影盒，再归还任务物品。保留进度会在紧急任务后自动重排，跳过已完成阶段，"
                + "当前阶段重新核对；已确认交付数量可供材料收集模块扣减。"));
        const section = window.StowlingSettings.disclosure("中断与收尾设置",
            options.defaultLabel || "存放位置与恢复方式", content);
        section.root.classList.add("task-cleanup-section");
        host.append(section.root);
        /** @param {Array<object>} points @param {Array<object>} regions @returns {void} */
        function resources(points, regions) {
            const previous = destination.value;
            destination.replaceChildren(new Option(options.defaultLabel
                || "不指定存放位置", ""));
            for (const point of points) {
                destination.append(new Option(`[箱子坐标] ${point.name}`,
                    `coordinate:${point.id}`));
            }
            for (const region of regions) {
                destination.append(new Option(`[容器区域] ${region.name}`, `region:${region.id}`));
            }
            destination.value = previous;
            window.StowlingResourcePicker?.group(destination);
            if (options.facilities) {
                const boxes = Array.from(supply.selectedOptions, item => item.value);
                const fallback = packing.value;
                supply.replaceChildren();
                packing.replaceChildren(new Option("不指定", ""));
                for (const point of points) {
                    const ref = "coordinate:" + point.id;
                    const option = new Option(point.name, ref);
                    option.selected = boxes.includes(ref);
                    supply.append(option);
                    packing.append(new Option(point.name, ref));
                }
                packing.value = fallback;
                window.StowlingResourcePicker?.group(supply);
                window.StowlingResourcePicker?.group(packing);
            }
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
            if (options.facilities) {
                const boxes = policy?.emptyBoxRefs || [];
                for (const option of supply.options) option.selected = boxes.includes(option.value);
                packing.value = policy?.packingPositionRef || "";
            }
            resume.value = policy?.resumeMode === "CONTINUE" ? "CONTINUE" : "RESTART";
            section.description.textContent = destination.selectedOptions?.[0]?.textContent
                || options.defaultLabel || "存放位置与恢复方式";
            section.setExpanded(false);
        }
        /** @returns {object} */
        function read() {
            const policy = {destinationRef: destination.value || null,
                resumeMode: options.fixedResumeMode
                    || (options.allowContinue === false ? "RESTART" : resume.value)};
            if (options.facilities) {
                policy.emptyBoxRefs = Array.from(supply.selectedOptions, option => option.value);
                policy.packingPositionRef = packing.value || null;
            }
            return policy;
        }
        /** @param {boolean} visible @returns {void} */
        function setSupplyVisible(visible) {
            const row = supply.closest?.(".move-field") || supplyRow;
            if (row) row.hidden = !visible;
        }
        return {resources, set, read, setSupplyVisible};
    }
    window.StowlingTaskCleanupSettings = Object.freeze({create});
})();
