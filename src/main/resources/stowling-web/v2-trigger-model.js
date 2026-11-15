(() => {
    "use strict";

    const TYPES = Object.freeze([
        "DROPPED_ITEMS",
        "FOOD_LEVEL_AT_OR_BELOW"
    ]);

    /**
     * @param {object} value
     * @returns {object}
     */
    function normalizeTrigger(value) {
        const type = TYPES.includes(value?.type)
            ? value.type
            : "DROPPED_ITEMS";
        return {
            id: String(value?.id || ""),
            name: String(value?.name || "").trim(),
            type,
            targetTaskId: String(value?.targetTaskId || ""),
            priority: ["BACKGROUND", "NORMAL", "URGENT"].includes(
                value?.priority
            )
                ? value.priority
                : "NORMAL",
            intervalTicks: Number(value?.intervalTicks || 20),
            cooldownTicks: Number(value?.cooldownTicks ?? 100),
            coordinateId: String(value?.coordinateId || ""),
            itemFilter: String(value?.itemFilter || ""),
            foodLevelThreshold: value?.foodLevelThreshold === null
                || value?.foodLevelThreshold === undefined
                ? 18
                : Number(value.foodLevelThreshold)
        };
    }

    /**
     * @param {string | undefined} targetTaskId
     * @returns {object}
     */
    function newTrigger(targetTaskId) {
        return {
            id: "",
            name: "新触发器",
            type: "DROPPED_ITEMS",
            targetTaskId: String(targetTaskId || ""),
            priority: "NORMAL",
            intervalTicks: 20,
            cooldownTicks: 100,
            coordinateId: "",
            itemFilter: "",
            foodLevelThreshold: 18
        };
    }

    /**
     * @param {object} trigger
     * @returns {object}
     */
    function payloadTrigger(trigger) {
        const droppedItems = trigger.type === "DROPPED_ITEMS";
        return {
            name: trigger.name.trim(),
            type: trigger.type,
            targetTaskId: trigger.targetTaskId,
            priority: trigger.priority,
            intervalTicks: Number(trigger.intervalTicks),
            cooldownTicks: Number(trigger.cooldownTicks),
            coordinateId: droppedItems ? trigger.coordinateId : null,
            itemFilter: droppedItems
                ? trigger.itemFilter.trim()
                : null,
            foodLevelThreshold: droppedItems
                ? null
                : Number(trigger.foodLevelThreshold)
        };
    }

    /**
     * @param {object} trigger
     * @returns {string}
     */
    function conditionLabel(trigger) {
        if (trigger.type === "FOOD_LEVEL_AT_OR_BELOW") {
            return `饱食度 ≤ ${trigger.foodLevelThreshold}`;
        }
        return trigger.itemFilter
            ? `掉落物：${trigger.itemFilter}`
            : "任意掉落物";
    }

    window.StowlingTriggerModel = Object.freeze({
        TYPES,
        normalizeTrigger,
        newTrigger,
        payloadTrigger,
        conditionLabel
    });
})();
