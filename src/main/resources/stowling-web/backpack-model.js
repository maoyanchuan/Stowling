(() => {
    "use strict";

    /** @param {object} item @returns {number} */
    function freeCount(item) {
        return Math.max(0, Number(item.count) - Number(item.taskCount));
    }

    /** @param {object} snapshot @param {object} item @param {number} keep @returns {object} */
    function allocation(snapshot, item, keep) {
        if (!snapshot.available || snapshot.busy) {
            throw new RangeError("请等待任务和收尾结束后调整背包归属");
        }
        if (!Number.isInteger(keep) || keep < 0 || keep > freeCount(item)) {
            throw new RangeError("请填写有效的保留数量");
        }
        return {revision: String(snapshot.revision), key: String(item.key), keepCount: keep};
    }

    /** @returns {number[]} */
    function slotOrder() {
        return Array.from({length: 36}, (_, index) => (index + 9) % 36);
    }

    /** @param {object} slot @param {object} item @returns {string[]} */
    function slotDetails(slot, item) {
        const details = [...slot.tooltip];
        if (slot.maxDurability > 0) {
            details.push(`耐久 ${slot.remainingDurability} / ${slot.maxDurability}`);
        }
        details.push(
            `保留自用 ${item.protectedCount} · 任务 ${item.taskCount}`
                + ` · 待分类 ${item.classificationCount}`
        );
        return details;
    }

    window.StowlingBackpackModel = Object.freeze({freeCount, allocation, slotOrder, slotDetails});
})();
