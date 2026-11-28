(() => {
    "use strict";

    /** @param {object} region @returns {object} */
    function example(region) {
        const first = {};
        const second = {};
        for (const axis of ["x", "y", "z"]) {
            first[axis] = Math.min(region.first[axis], region.second[axis]);
            second[axis] = Math.max(region.first[axis], region.second[axis]);
        }
        // Synthetic structure only. These offsets do not infer anything about a game world.
        /** @param {number} y @param {number} z @returns {object} */
        const at = (y, z) => ({
            x: first.x, y: Math.min(first.y + y, second.y),
            z: Math.min(first.z + z, second.z)
        });
        /** @param {object} lower @param {object} upper @returns {object} */
        const span = (lower, upper) => ({first: lower, second: {...upper, x: second.x}});
        return {
            markerRegion: span(at(3, 9), at(4, 9)),
            storageRegion: span(at(1, 3), at(2, 3)),
            standing: at(0, 6)
        };
    }

    /** @param {object} root @param {object} helpers @returns {object} */
    function create(root, helpers) {
        const modeRow = root.querySelector("#category-setup-row");
        const mode = root.querySelector("#category-setup-mode");
        const scan = root.querySelector("#category-scan-panel");
        const status = root.querySelector("#category-scan-status");
        const start = root.querySelector("#category-scan-start");
        const preview = root.querySelector("#category-scan-preview");
        const apply = root.querySelector("#category-scan-apply");
        const discard = root.querySelector("#category-scan-discard");
        const range = root.querySelector("#category-scan-range");
        const toggle = root.querySelector("#category-generated-toggle");
        const details = root.querySelector("#category-details");
        const configuration = root.querySelector("#category-configuration");
        const content = root.querySelector("#category-configuration-content");
        const classic = window.StowlingClassicSlice.create(root, (positions, size) => {
            if (draft.previewRegion) {
                draft.previewPieces = positions;
                draft.previewSize = size;
            } else {
                draft.appliedPieces = positions;
                draft.appliedSize = size;
            }
            update(false);
        });
        content.append(root.querySelector(".category-options"),
            root.querySelector(".category-binding-section"));
        let draft = {mode: "manual", previewRegion: null, appliedRegion: null, expanded: false};
        const annotations = window.StowlingSliceAnnotations.create(root, () =>
            draft.previewRegion ? draft.previewBindings : helpers.annotationBindings(),
        (groups, action, id) => {
            if (draft.previewRegion) draft.previewBindings = groups;
            else helpers.writeAnnotation(groups, action, id);
            update(false);
        }, () => Boolean(draft.previewRegion) || helpers.validStations());
        const syncSelect = helpers.bindMode(modeRow, () => {
            draft.mode = mode.value;
            helpers.closeMenus();
            update();
        });

        /** @returns {object|null} */
        function currentRegion() {
            const fields = Array.from(root.querySelectorAll('[id^="category-overall-"]'));
            if (fields.some(field => !field.value.trim() || !field.validity.valid)) return null;
            return helpers.readRegion();
        }

        /** @param {object|null} region @returns {string} */
        function regionKey(region) {
            if (!region) return "";
            // Reversing the two corners still describes the same selection.
            return JSON.stringify(["x", "y", "z"].map(axis => [
                Math.min(region.first[axis], region.second[axis]),
                Math.max(region.first[axis], region.second[axis])
            ]));
        }

        /** @returns {boolean} */
        function canSave() {
            return mode.value !== "scan" || Boolean(draft.appliedRegion
                && regionKey(draft.appliedRegion) === regionKey(currentRegion()));
        }

        /** @param {boolean} [animate] @returns {void} */
        function update(animate = true) {
            const scanning = mode.value === "scan";
            const region = currentRegion();
            const freshPreview = Boolean(draft.previewRegion
                && regionKey(draft.previewRegion) === regionKey(region));
            const freshApplied = canSave();
            start.disabled = !region;
            start.textContent = draft.appliedRegion ? "重新预览扫描示例" : "预览扫描示例";
            const complete = draft.previewBindings?.every(g => g.markerRegion && g.storageRegion);
            apply.disabled = !freshPreview || !complete;
            toggle.hidden = !scanning || !draft.appliedRegion || Boolean(draft.previewRegion);
            const showDetails = !scanning || Boolean(!draft.previewRegion && draft.appliedRegion && draft.expanded);
            configuration.disabled = !showDetails;
            toggle.setAttribute("aria-expanded", String(showDetails));
            helpers.reveal(scan, scanning, animate);
            helpers.reveal(preview, Boolean(draft.previewRegion), animate);
            helpers.reveal(details, showDetails, animate);
            const mapRegion = draft.previewRegion || draft.appliedRegion;
            classic.render(scanning ? mapRegion : null, draft.previewRegion
                ? draft.previewPieces : draft.appliedPieces,
                regionKey(mapRegion) !== regionKey(region), Boolean(draft.previewRegion),
                draft.previewRegion ? draft.previewSize : draft.appliedSize);
            annotations.render(scanning ? mapRegion : null,
                regionKey(mapRegion) !== regionKey(region));
            if (draft.previewRegion) {
                root.querySelector("#scan-binding-count").textContent =
                    draft.previewBindings.length + " 组绑定";
                const stations = new Set(draft.previewBindings.flatMap(g =>
                    (g.standingPlan?.points || []).map(p => p.standingId)));
                root.querySelector("#scan-standing-count").textContent = stations.size + " 个站位";
                const format = point => `${point.x}, ${point.y}, ${point.z}`;
                range.textContent = `${format(draft.previewRegion.first)} → `
                    + format(draft.previewRegion.second);
            }
            let message = "填写总选区的两个角点，即可预览自动生成的配置。";
            if (!region) {
                message = "请填写完整的 6 个整数坐标，再预览扫描示例。";
            } else if (draft.previewRegion && !freshPreview) {
                message = "总选区已改变，此预览已过期。请重新预览后再应用。";
            } else if (draft.previewRegion) {
                message = complete ? "示例已生成，尚未应用。当前配置保持原样。"
                    : "请为每个绑定组画好识别区和储存区，再应用示例配置。";
            } else if (draft.appliedRegion && !freshApplied) {
                message = "总选区已改变，已有配置待更新。请重新预览并应用后保存。";
            } else if (draft.appliedRegion) {
                message = draft.appliedSize || Object.keys(draft.appliedPieces || {}).length
                    ? "单片结构已手动调整，点击保存坐标可保留本次草稿。"
                    : "已应用示例配置，可直接编辑单片结构，或展开查看区域参数。";
            }
            status.textContent = message;
            status.dataset.tone = (!region || (draft.previewRegion && !freshPreview)
                || (draft.appliedRegion && !freshApplied)) ? "warning" : "";
            helpers.setSaveEnabled(!scanning || freshApplied);
        }

        /** @returns {void} */
        function generatePreview() {
            const region = currentRegion();
            if (!region) return;
            draft.previewPieces = {};
            draft.previewSize = null;
            draft.previewRegion = structuredClone(region);
            draft.previewBindings = helpers.exampleBindings(region);
            classic.reset();
            update();
        }

        /** @returns {void} */
        function applyPreview() {
            if (!draft.previewRegion
                    || regionKey(draft.previewRegion) !== regionKey(currentRegion())) return;
            if (apply.disabled) return;
            helpers.applyExample(draft.previewBindings);
            draft.appliedRegion = structuredClone(draft.previewRegion);
            draft.appliedSize = draft.previewSize;
            draft.appliedPieces = structuredClone(draft.previewPieces || {});
            draft.previewPieces = {};
            draft.previewSize = null;
            draft.previewRegion = null;
            delete draft.previewBindings;
            classic.reset();
            draft.expanded = false;
            update();
            toggle.focus();
        }

        /** @param {object|undefined} value @returns {void} */
        function load(value) {
            draft = value ? structuredClone(value)
                : {mode: "manual", previewRegion: null, appliedRegion: null, expanded: false};
            if (draft.previewRegion && !draft.previewBindings) {
                draft.previewBindings = helpers.exampleBindings(draft.previewRegion);
            }
            annotations.reset();
            mode.value = draft.mode === "scan" ? "scan" : "manual";
            classic.reset();
            syncSelect();
            update(false);
        }

        /** @returns {object} */
        function read() {
            return structuredClone(draft);
        }

        start.addEventListener("click", generatePreview);
        apply.addEventListener("click", applyPreview);
        discard.addEventListener("click", () => {
            draft.previewRegion = null;
            delete draft.previewBindings;
            draft.previewPieces = {};
            draft.previewSize = null;
            classic.reset();
            update();
            start.focus();
        });
        toggle.addEventListener("click", () => {
            draft.expanded = !draft.expanded;
            helpers.closeMenus();
            update();
        });
        root.addEventListener("input", event => {
            if (event.target.id.startsWith("category-overall-")) update();
            else if (configuration.contains(event.target)) annotations.refresh();
        });
        return {load, read, canSave, refreshAnnotations: annotations.refresh};
    }

    window.StowlingCoordinateScan = {example, create};
})();
