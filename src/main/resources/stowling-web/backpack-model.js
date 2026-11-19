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

    window.StowlingBackpackModel = Object.freeze({freeCount, allocation});
})();
