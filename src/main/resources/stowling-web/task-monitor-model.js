(() => {
    "use strict";

    const ACTIVE_RUN_STATES = Object.freeze([
        "RUNNING",
        "WAITING",
        "INTERRUPTING",
        "INTERRUPTION_FAILED",
        "QUEUED"
    ]);
    const PRIORITY_RANK = Object.freeze({
        URGENT: 0,
        NORMAL: 1,
        BACKGROUND: 2
    });
    const RUN_STATE_LABELS = Object.freeze({
        RUNNING: "运行中",
        WAITING: "等待中",
        INTERRUPTING: "正在中断",
        INTERRUPTION_FAILED: "中断失败（活动）",
        QUEUED: "排队中",
        SUCCEEDED: "已完成",
        FAILED: "失败",
        PREEMPTED: "已中断",
        CANCELLED: "已取消"
    });
    const TRIGGER_STATE_LABELS = Object.freeze({
        IDLE: "监听空闲",
        RUNNING: "已触发",
        COOLDOWN: "冷却中",
        ERROR: "监听异常"
    });
    const TRIGGER_STATE_RANK = Object.freeze({
        ERROR: 0,
        RUNNING: 1,
        COOLDOWN: 2,
        IDLE: 3
    });
    const PRIORITY_LABELS = Object.freeze({
        URGENT: "紧急",
        NORMAL: "普通",
        BACKGROUND: "后台"
    });

    /**
     * @param {unknown} value
     * @returns {Array<object>}
     */
    function asObjects(value) {
        const result = [];
        if (!Array.isArray(value)) {
            return result;
        }
        for (const item of value) {
            if (item !== null && typeof item === "object") {
                result.push(item);
            }
        }
        return result;
    }

    /**
     * @param {unknown} value
     * @param {string} [fallback]
     * @returns {string}
     */
    function asText(value, fallback = "") {
        if (value === null || value === undefined) {
            return fallback;
        }
        const text = String(value).trim();
        return text || fallback;
    }

    /**
     * @param {unknown} value
     * @returns {number|null}
     */
    function finiteNumber(value) {
        if (value === null || value === undefined || value === "") {
            return null;
        }
        const number = Number(value);
        return Number.isFinite(number) ? number : null;
    }

    /**
     * @param {object} snapshot
     * @param {number} index
     * @param {string} prefix
     * @returns {string}
     */
    function runIdentity(snapshot, index, prefix) {
        return asText(snapshot.runId) || `__${prefix}_run_${index}`;
    }

    /**
     * @param {object} snapshot
     * @param {number} index
     * @returns {string}
     */
    function triggerIdentity(snapshot, index) {
        return asText(snapshot.triggerId) || `__trigger_${index}`;
    }

    /**
     * @param {string} priority
     * @returns {number}
     */
    function priorityRank(priority) {
        if (Object.prototype.hasOwnProperty.call(PRIORITY_RANK, priority)) {
            return PRIORITY_RANK[priority];
        }
        return Object.keys(PRIORITY_RANK).length;
    }

    /**
     * @param {string} state
     * @returns {number}
     */
    function triggerStateRank(state) {
        if (Object.prototype.hasOwnProperty.call(TRIGGER_STATE_RANK, state)) {
            return TRIGGER_STATE_RANK[state];
        }
        return Object.keys(TRIGGER_STATE_RANK).length;
    }

    /**
     * @param {Array<object>} runs
     * @param {Array<object>} queue
     * @returns {Array<object>}
     */
    function collectRunRecords(runs, queue) {
        const records = new Map();
        const queuedOrder = new Map();
        const queuedSnapshots = asObjects(queue);

        for (let index = 0; index < queuedSnapshots.length; index += 1) {
            const snapshot = queuedSnapshots[index];
            const state = asText(snapshot.state, "QUEUED");
            if (state !== "QUEUED") {
                continue;
            }
            const runId = runIdentity(snapshot, index, "queue");
            if (!queuedOrder.has(runId)) {
                queuedOrder.set(runId, index);
            }
            if (!records.has(runId)) {
                records.set(runId, {
                    runId,
                    snapshot,
                    queueIndex: index,
                    runIndex: null
                });
            }
        }

        const runSnapshots = asObjects(runs);
        for (let index = 0; index < runSnapshots.length; index += 1) {
            const snapshot = runSnapshots[index];
            const state = asText(snapshot.state);
            if (!ACTIVE_RUN_STATES.includes(state)) {
                continue;
            }
            const runId = runIdentity(snapshot, index, "runs");
            const queueIndex = queuedOrder.has(runId)
                ? queuedOrder.get(runId)
                : null;
            const record = records.get(runId);
            if (!record) {
                records.set(runId, {
                    runId,
                    snapshot,
                    queueIndex,
                    runIndex: index
                });
            } else if (state !== "QUEUED") {
                record.snapshot = snapshot;
                record.queueIndex = null;
                record.runIndex = index;
            }
        }

        return Array.from(records.values());
    }

    /**
     * @param {object} left
     * @param {object} right
     * @returns {number}
     */
    function compareRunRecords(left, right) {
        const leftQueueOrder = left.queueIndex === null ? -1 : left.queueIndex;
        const rightQueueOrder = right.queueIndex === null ? -1 : right.queueIndex;
        if (leftQueueOrder !== rightQueueOrder) {
            return leftQueueOrder - rightQueueOrder;
        }
        const leftRunOrder = left.runIndex === null ? -1 : left.runIndex;
        const rightRunOrder = right.runIndex === null ? -1 : right.runIndex;
        return leftRunOrder - rightRunOrder;
    }

    /**
     * @param {object} record
     * @returns {object}
     */
    function runEntry(record) {
        const run = record.snapshot;
        return {
            key: `run:${record.runId}`,
            kind: "run",
            taskId: asText(run.taskId),
            taskName: asText(run.taskName, "未命名任务"),
            priority: asText(run.priority, "NORMAL"),
            state: asText(run.state),
            message: asText(run.message),
            run
        };
    }

    /**
     * @param {Array<object>} triggers
     * @returns {Array<object>}
     */
    function collectTriggerGroups(triggers) {
        const seen = new Set();
        const groups = new Map();
        const snapshots = asObjects(triggers);

        for (let index = 0; index < snapshots.length; index += 1) {
            const snapshot = snapshots[index];
            const triggerId = triggerIdentity(snapshot, index);
            if (seen.has(triggerId)) {
                continue;
            }
            seen.add(triggerId);
            const targetTaskId = asText(snapshot.targetTaskId)
                || `__target_${triggerId}`;
            let group = groups.get(targetTaskId);
            if (!group) {
                group = {
                    targetTaskId,
                    snapshots: []
                };
                groups.set(targetTaskId, group);
            }
            group.snapshots.push(snapshot);
        }

        return Array.from(groups.values());
    }

    /**
     * @param {Array<object>} snapshots
     * @returns {string}
     */
    function strongestPriority(snapshots) {
        let selected = "";
        for (const snapshot of snapshots) {
            const candidate = asText(snapshot.priority);
            if (!candidate) {
                continue;
            }
            if (!selected
                || priorityRank(candidate) < priorityRank(selected)) {
                selected = candidate;
            }
        }
        return selected || "NORMAL";
    }

    /**
     * @param {Array<object>} snapshots
     * @returns {string}
     */
    function aggregateTriggerState(snapshots) {
        let selected = "";
        for (const snapshot of snapshots) {
            const candidate = asText(snapshot.state);
            if (!candidate) {
                continue;
            }
            if (!selected
                || triggerStateRank(candidate)
                    < triggerStateRank(selected)) {
                selected = candidate;
            }
        }
        return selected || "IDLE";
    }

    /**
     * @param {Array<object>} snapshots
     * @returns {string}
     */
    function triggerMessages(snapshots) {
        const messages = [];
        for (const snapshot of snapshots) {
            const name = asText(snapshot.triggerName);
            const message = asText(snapshot.message);
            const state = asText(snapshot.state, "IDLE");
            const text = message || stateLabel("trigger", state);
            messages.push(name ? `${name}：${text}` : text);
        }
        return messages.join("；");
    }

    /**
     * @param {object} group
     * @returns {object}
     */
    function triggerEntry(group) {
        let taskName = "";
        let triggerName = "";
        for (const snapshot of group.snapshots) {
            if (!taskName) {
                taskName = asText(snapshot.targetTaskName);
            }
            if (!triggerName) {
                triggerName = asText(snapshot.triggerName);
            }
        }
        return {
            key: `trigger:${group.targetTaskId}`,
            kind: "trigger",
            taskId: group.targetTaskId,
            taskName: taskName || triggerName || "监听任务",
            priority: strongestPriority(group.snapshots),
            state: aggregateTriggerState(group.snapshots),
            message: triggerMessages(group.snapshots),
            triggers: group.snapshots.slice()
        };
    }

    /**
     * @param {object} left
     * @param {object} right
     * @returns {number}
     */
    function compareCandidates(left, right) {
        const priorityDifference = priorityRank(left.entry.priority)
            - priorityRank(right.entry.priority);
        if (priorityDifference !== 0) {
            return priorityDifference;
        }
        const kindDifference = left.kindOrder - right.kindOrder;
        if (kindDifference !== 0) {
            return kindDifference;
        }
        return left.order - right.order;
    }

    /**
     * @param {Array<object>} [runs]
     * @param {Array<object>} [queue]
     * @param {Array<object>} [triggers]
     * @returns {Array<object>}
     */
    function queueEntries(runs = [], queue = [], triggers = []) {
        const runRecords = collectRunRecords(runs, queue);
        runRecords.sort(compareRunRecords);
        const candidates = [];

        for (let index = 0; index < runRecords.length; index += 1) {
            candidates.push({
                entry: runEntry(runRecords[index]),
                kindOrder: 0,
                order: index
            });
        }

        const triggerGroups = collectTriggerGroups(triggers);
        for (let index = 0; index < triggerGroups.length; index += 1) {
            candidates.push({
                entry: triggerEntry(triggerGroups[index]),
                kindOrder: 1,
                order: index
            });
        }

        candidates.sort(compareCandidates);
        const entries = [];
        for (const candidate of candidates) {
            entries.push(candidate.entry);
        }
        return entries;
    }

    /**
     * @param {unknown} value
     * @returns {number}
     */
    function normalizeLimit(value) {
        const number = Math.floor(Number(value));
        return Number.isFinite(number) && number >= 0 ? number : 300;
    }

    /**
     * @param {Array<object>} runs
     * @param {Array<object>} queue
     * @returns {Array<object>}
     */
    function collectRunSnapshots(runs, queue) {
        const result = [];
        const seen = new Set();
        const runSnapshots = asObjects(runs);

        for (let index = 0; index < runSnapshots.length; index += 1) {
            const snapshot = runSnapshots[index];
            const runId = runIdentity(snapshot, index, "runs");
            if (seen.has(runId)) {
                continue;
            }
            seen.add(runId);
            result.push({ runId, snapshot });
        }

        const queuedSnapshots = asObjects(queue);
        for (let index = 0; index < queuedSnapshots.length; index += 1) {
            const snapshot = queuedSnapshots[index];
            const state = asText(snapshot.state, "QUEUED");
            if (state !== "QUEUED") {
                continue;
            }
            const runId = runIdentity(snapshot, index, "queue");
            if (seen.has(runId)) {
                continue;
            }
            seen.add(runId);
            result.push({ runId, snapshot });
        }

        return result;
    }

    /**
     * @param {Array<object>} triggers
     * @returns {Array<object>}
     */
    function collectTriggerSnapshots(triggers) {
        const result = [];
        const seen = new Set();
        const snapshots = asObjects(triggers);

        for (let index = 0; index < snapshots.length; index += 1) {
            const snapshot = snapshots[index];
            const triggerId = triggerIdentity(snapshot, index);
            if (seen.has(triggerId)) {
                continue;
            }
            seen.add(triggerId);
            result.push({ triggerId, snapshot });
        }

        return result;
    }

    /**
     * @param {object} run
     * @returns {string}
     */
    function runFingerprint(run) {
        return JSON.stringify([
            asText(run.state),
            asText(run.message),
            finiteNumber(run.completedSteps),
            finiteNumber(run.totalSteps)
        ]);
    }

    /**
     * @param {object} trigger
     * @returns {string}
     */
    function triggerFingerprint(trigger) {
        return JSON.stringify([
            asText(trigger.state),
            asText(trigger.message)
        ]);
    }

    /**
     * @param {string} kind
     * @param {string} state
     * @returns {string}
     */
    function stateLabel(kind, state) {
        const labels = kind === "trigger"
            ? TRIGGER_STATE_LABELS
            : RUN_STATE_LABELS;
        return labels[state] || asText(state, "未知");
    }

    /**
     * @param {string} priority
     * @returns {string}
     */
    function priorityLabel(priority) {
        return PRIORITY_LABELS[priority] || asText(priority, "未知");
    }

    /**
     * @param {string} kind
     * @returns {string}
     */
    function kindLabel(kind) {
        return kind === "trigger" ? "监听" : "运行";
    }

    /**
     * @param {unknown} value
     * @returns {string}
     */
    function sanitizeCell(value) {
        return asText(value).replace(/[\t\r\n]+/g, " ");
    }

    /**
     * @param {object} entry
     * @returns {string}
     */
    function progressLabel(entry) {
        const completed = finiteNumber(entry.completedSteps);
        const total = finiteNumber(entry.totalSteps);
        if (completed !== null && total !== null) {
            return `${completed} / ${total} 步`;
        }
        if (completed !== null) {
            return `${completed} 步`;
        }
        return "—";
    }

    /**
     * @param {number} [limit]
     * @returns {object}
     */
    function createLogBuffer(limit = 300) {
        const maximum = normalizeLimit(limit);
        const baseline = new Map();
        const records = [];
        let serial = 0;

        /**
         * @param {object} entry
         * @returns {void}
         */
        function append(entry) {
            records.push(entry);
            if (records.length > maximum) {
                records.splice(0, records.length - maximum);
            }
        }

        /**
         * @param {string} runId
         * @param {object} run
         * @param {string} time
         * @returns {object}
         */
        function makeRunEntry(runId, run, time) {
            serial += 1;
            const entry = {
                id: `run:${runId}:${serial}`,
                time,
                kind: "run",
                taskName: asText(run.taskName, "未命名任务"),
                priority: asText(run.priority, "NORMAL"),
                state: asText(run.state),
                message: asText(run.message)
            };
            const completedSteps = finiteNumber(run.completedSteps);
            const totalSteps = finiteNumber(run.totalSteps);
            if (completedSteps !== null) {
                entry.completedSteps = completedSteps;
            }
            if (totalSteps !== null) {
                entry.totalSteps = totalSteps;
            }
            return entry;
        }

        /**
         * @param {string} triggerId
         * @param {object} trigger
         * @param {string} time
         * @returns {object}
         */
        function makeTriggerEntry(triggerId, trigger, time) {
            serial += 1;
            const triggerName = asText(trigger.triggerName);
            const state = asText(trigger.state);
            const message = asText(trigger.message)
                || stateLabel("trigger", state || "IDLE");
            return {
                id: `trigger:${triggerId}:${serial}`,
                time,
                kind: "trigger",
                taskName: asText(
                    trigger.targetTaskName,
                    triggerName || "后台触发器"
                ),
                priority: asText(trigger.priority, "NORMAL"),
                state,
                message: triggerName ? `${triggerName}：${message}` : message
            };
        }

        /**
         * @param {Array<object>} [runs]
         * @param {Array<object>} [queue]
         * @param {Array<object>} [triggers]
         * @param {string} [observedAt]
         * @returns {number}
         */
        function ingest(
            runs = [],
            queue = [],
            triggers = [],
            observedAt = ""
        ) {
            const time = asText(observedAt) || new Date().toISOString();
            let added = 0;
            const runSnapshots = collectRunSnapshots(runs, queue);
            for (const item of runSnapshots) {
                const key = `run:${item.runId}`;
                const fingerprint = runFingerprint(item.snapshot);
                if (baseline.get(key) === fingerprint) {
                    continue;
                }
                baseline.set(key, fingerprint);
                append(makeRunEntry(item.runId, item.snapshot, time));
                added += 1;
            }

            const triggerSnapshots = collectTriggerSnapshots(triggers);
            for (const item of triggerSnapshots) {
                const key = `trigger:${item.triggerId}`;
                const fingerprint = triggerFingerprint(item.snapshot);
                if (baseline.get(key) === fingerprint) {
                    continue;
                }
                baseline.set(key, fingerprint);
                append(makeTriggerEntry(
                    item.triggerId,
                    item.snapshot,
                    time
                ));
                added += 1;
            }
            return added;
        }

        /**
         * @returns {Array<object>}
         */
        function readEntries() {
            const result = [];
            for (const entry of records) {
                const copy = {
                    id: entry.id,
                    time: entry.time,
                    kind: entry.kind,
                    taskName: entry.taskName,
                    priority: entry.priority,
                    state: entry.state,
                    message: entry.message
                };
                if (entry.completedSteps !== undefined) {
                    copy.completedSteps = entry.completedSteps;
                }
                if (entry.totalSteps !== undefined) {
                    copy.totalSteps = entry.totalSteps;
                }
                result.push(copy);
            }
            return result;
        }

        /**
         * @returns {void}
         */
        function clearEntries() {
            records.length = 0;
        }

        /**
         * @returns {string}
         */
        function exportText() {
            const lines = [
                "时间\t类型\t任务\t状态\t优先级\t消息\t进度"
            ];
            for (const entry of records) {
                lines.push([
                    sanitizeCell(entry.time),
                    kindLabel(entry.kind),
                    sanitizeCell(entry.taskName),
                    stateLabel(entry.kind, entry.state),
                    priorityLabel(entry.priority),
                    sanitizeCell(entry.message),
                    progressLabel(entry)
                ].join("\t"));
            }
            return lines.join("\n");
        }

        return Object.freeze({
            ingest,
            entries: readEntries,
            clear: clearEntries,
            exportText
        });
    }

    window.StowlingTaskMonitorModel = Object.freeze({
        queueEntries,
        createLogBuffer
    });
})();