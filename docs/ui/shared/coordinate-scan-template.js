(() => {
    "use strict";

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
        const mapHost = root.querySelector("#category-scan-map");
        const map = window.StowlingCoordinateSlicePlan.create(mapHost);
        content.append(root.querySelector(".category-options"),
            root.querySelector(".category-binding-section"));
        let draft = {mode: "manual", previewRegion: null, appliedRegion: null, expanded: false};
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
            apply.disabled = !freshPreview;
            toggle.hidden = !scanning || !draft.appliedRegion;
            const showDetails = !scanning || Boolean(draft.appliedRegion && draft.expanded);
            configuration.disabled = !showDetails;
            toggle.setAttribute("aria-expanded", String(showDetails));
            helpers.reveal(scan, scanning, animate);
            helpers.reveal(preview, Boolean(draft.previewRegion), animate);
            helpers.reveal(details, showDetails, animate);
            const mapRegion = draft.previewRegion || draft.appliedRegion;
            if (mapRegion) {
                const stage = regionKey(mapRegion) !== regionKey(region) ? "stale"
                    : draft.previewRegion ? "pending" : "applied";
                map.render(mapRegion, stage);
            }
            helpers.reveal(mapHost, Boolean(mapRegion), animate);
            if (draft.previewRegion) {
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
                message = "示例已生成，尚未应用。当前配置保持原样。";
            } else if (draft.appliedRegion && !freshApplied) {
                message = "总选区已改变，已有配置待更新。请重新预览并应用后保存。";
            } else if (draft.appliedRegion) {
                message = "已应用示例配置，可展开微调，确认后保存坐标。";
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
            draft.previewRegion = structuredClone(region);
            update();
        }

        /** @returns {void} */
        function applyPreview() {
            if (!draft.previewRegion
                    || regionKey(draft.previewRegion) !== regionKey(currentRegion())) return;
            helpers.applyExample(draft.previewRegion);
            draft.appliedRegion = structuredClone(draft.previewRegion);
            draft.previewRegion = null;
            draft.expanded = false;
            update();
            toggle.focus();
        }

        /** @param {object|undefined} value @returns {void} */
        function load(value) {
            draft = value ? structuredClone(value)
                : {mode: "manual", previewRegion: null, appliedRegion: null, expanded: false};
            mode.value = draft.mode === "scan" ? "scan" : "manual";
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
        });
        return {load, read, canSave};
    }

    window.StowlingCoordinateScan = {create};
})();
