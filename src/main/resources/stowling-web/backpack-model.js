(() => {
    "use strict";

    /** @param {object} snapshot @param {number} slotIndex @param {boolean} locked
     * @returns {object} */
    function lockChange(snapshot, slotIndex, locked) {
        if (!snapshot.available || snapshot.busy) {
            throw new RangeError("请等待任务和收尾结束后调整栏位锁定");
        }
        if (!Number.isInteger(slotIndex) || slotIndex < 0 || slotIndex >= 36
                || typeof locked !== "boolean") {
            throw new RangeError("请选择有效的背包栏位");
        }
        return {revision: String(snapshot.revision), slotIndex, locked};
    }

    /** @returns {number[]} */
    function slotOrder() {
        return Array.from({length: 36}, (_, index) => (index + 9) % 36);
    }

    /** @param {object} slot @param {boolean} locked @returns {string[]} */
    function slotDetails(slot, locked) {
        const details = [...(slot.tooltip || [])];
        if (slot.maxDurability > 0) {
            details.push(`耐久 ${slot.remainingDurability} / ${slot.maxDurability}`);
        }
        details.push(locked ? "栏位已锁定 · 自动搬运跳过此格" : "栏位未锁定 · 物品可自动存入");
        return details;
    }

    window.StowlingBackpackModel = Object.freeze({lockChange, slotOrder, slotDetails});
})();
