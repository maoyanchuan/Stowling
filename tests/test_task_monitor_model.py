import json
import shutil
import subprocess
from pathlib import Path
from typing import Any

import pytest


ROOT = Path(__file__).resolve().parents[1]
MODEL = (
    ROOT
    / "src"
    / "main"
    / "resources"
    / "stowling-web"
    / "task-monitor-model.js"
)
NODE = shutil.which("node")
pytestmark = pytest.mark.skipif(
    NODE is None,
    reason="Node.js is required to exercise the JavaScript monitor model",
)

NODE_RUNNER = """
const fs = require("node:fs");
const vm = require("node:vm");
const source = fs.readFileSync(process.argv[1], "utf8");
globalThis.window = {};
vm.runInThisContext(source, { filename: process.argv[1] });
const result = vm.runInThisContext(`(${process.argv[2]})`);
process.stdout.write(JSON.stringify(result));
"""


def _run_model(expression: str) -> Any:
    if NODE is None:
        raise RuntimeError("Node.js is unavailable")
    completed = subprocess.run(
        [NODE, "-e", NODE_RUNNER, str(MODEL), expression],
        check=True,
        capture_output=True,
        encoding="utf-8",
        text=True,
    )
    return json.loads(completed.stdout)


def test_monitor_does_not_fill_with_unchanged_polling_but_keeps_progress_and_errors() -> None:
    result = _run_model(
        """
        (() => {
            const log = window.StowlingTaskMonitorModel.createLogBuffer(300);
            const event = { sequence: 1, runId: "run", phase: "TASK", step: "1",
                module: "STORE", kind: "WAIT", operation: "confirm", details: "actual=0",
                elapsedMillis: 0, repeated: 0, time: "2026-11-19T13:00:00Z" };
            const batch = (events) => ({ session: "s", events });
            log.ingestDiagnostics(batch([event]));
            const unchanged = log.ingestDiagnostics(batch([
                { ...event, sequence: 2, elapsedMillis: 1000 }
            ]));
            const progress = log.ingestDiagnostics(batch([
                { ...event, sequence: 3, details: "actual=64" },
                { ...event, sequence: 4, kind: "ERROR", details: "failed" },
                { ...event, sequence: 5, kind: "ERROR", details: "failed" }
            ]));
            return { unchanged, progress, count: log.entries().length };
        })()
        """
    )
    assert result == {"unchanged": 0, "progress": 3, "count": 4}


def test_run_exports_are_separate_and_compact_without_losing_errors() -> None:
    result = _run_model(
        """
        (() => {
            const model = window.StowlingTaskMonitorModel;
            const event = { runId: "selected", phase: "TASK", step: "1", module: "PICKUP",
                kind: "WAIT", operation: "pickup", details: "waiting", elapsedMillis: 0,
                time: "2026-11-19T13:40:00Z", repeated: 0 };
            const batch = { session: "s", firstAvailable: 1, latestSequence: 6, events: [
                { ...event, sequence: 1 },
                { ...event, sequence: 2, elapsedMillis: 1000 },
                { ...event, sequence: 3, runId: "other", details: "OTHER_RUN_SECRET" },
                { ...event, sequence: 4, kind: "CALL", details: "INTERFACE_DETAIL" },
                { ...event, sequence: 5, kind: "ERROR", details: "OUTSIDE_SAFE_AREA" },
                { ...event, sequence: 6, phase: "CLEANUP", kind: "ERROR",
                    details: "RECOVERY_PENDING" }
            ] };
            const runs = model.exportRuns([
                { runId: "selected", state: "INTERRUPTION_FAILED",
                    updatedAt: "2026-11-19T13:41:50Z" },
                { runId: "queued", state: "QUEUED", updatedAt: "2026-11-19T13:44:00Z" },
                { runId: "older", state: "SUCCEEDED", updatedAt: "2026-11-19T13:39:00Z" }
            ]);
            return { compact: model.exportDiagnostics(batch, "selected", false),
                detailed: model.exportDiagnostics(batch, "selected", true),
                runs: runs.map((run) => run.runId) };
        })()
        """
    )
    assert result["runs"] == ["selected", "older"]
    assert "OTHER_RUN_SECRET" not in result["compact"]
    assert "OTHER_RUN_SECRET" not in result["detailed"]
    assert "INTERFACE_DETAIL" not in result["compact"]
    assert "INTERFACE_DETAIL" in result["detailed"]
    assert result["compact"].count("waiting") == 1
    assert "OUTSIDE_SAFE_AREA" in result["compact"]
    assert "RECOVERY_PENDING" in result["compact"]


def test_runtime_diagnostics_deduplicate_pages_and_export_wait_details() -> None:
    result = _run_model(
        """
        (() => {
            const model = window.StowlingTaskMonitorModel;
            const log = model.createLogBuffer(300);
            const event = {
                sequence: 1, time: "2026-11-19T10:00:00Z", runId: "run-1",
                phase: "TASK", step: "2.2.1", module: "DEPOSIT_ITEMS_TO_CONTAINERS",
                kind: "WAIT", operation: "deposit.confirm", elapsedMillis: 8000,
                repeated: 19, details: "planned=64; pickedUp=64; outgoing=64; actual=0"
            };
            const batch = {
                session: "session-1", firstAvailable: 1, nextSequence: 1,
                latestSequence: 1, events: [event]
            };
            const first = log.ingestDiagnostics(batch, [{
                runId: "run-1", taskName: "全物品", priority: "URGENT"
            }]);
            const duplicate = log.ingestDiagnostics(batch);
            const exported = model.exportDiagnostics(batch);
            log.clear();
            const clearedDuplicate = log.ingestDiagnostics(batch);
            const restarted = log.ingestDiagnostics({ ...batch, session: "session-2" });
            return { first, duplicate, clearedDuplicate, restarted, exported,
                label: model.diagnosticLabel("RESYNC"), entries: log.entries() };
        })()
        """
    )
    assert result["first"] == 1
    assert result["duplicate"] == 0
    assert result["clearedDuplicate"] == 0
    assert result["restarted"] == 1
    assert result["label"] == "重新读取"
    assert "deposit.confirm" in result["exported"]
    assert "planned=64; pickedUp=64" in result["exported"]
    assert "8000\t19" in result["exported"]
    assert "2.2.1" in result["entries"][0]["message"]


def test_full_diagnostic_export_is_independent_of_the_small_visible_log_buffer() -> None:
    result = _run_model(
        """
        (() => {
            const model = window.StowlingTaskMonitorModel;
            const batch = { session: "s", firstAvailable: 1, latestSequence: 4,
                events: [1, 2, 3, 4].map((sequence) => ({
                    sequence, time: "2026-11-19T10:00:00Z", runId: "r", phase: "TASK",
                    step: "1", module: "PICKUP", kind: "CALL", operation: `call-${sequence}`,
                    details: "", elapsedMillis: 0, repeated: 0
                })) };
            const log = model.createLogBuffer(2);
            log.ingestDiagnostics(batch);
            return { visible: log.entries().length, exported: model.exportDiagnostics(batch) };
        })()
        """
    )
    assert result["visible"] == 2
    assert "call-1" in result["exported"]
    assert "call-4" in result["exported"]


def test_queue_entries_mix_priority_fifo_and_run_deduplication() -> None:
    result = _run_model(
        """
        (() => {
            const model = window.StowlingTaskMonitorModel;
            const entries = model.queueEntries(
                [
                    {
                        runId: "run-int",
                        taskId: "task-int",
                        taskName: "异常收尾",
                        priority: "URGENT",
                        state: "INTERRUPTION_FAILED",
                        message: "收尾失败"
                    },
                    {
                        runId: "run-wait",
                        taskId: "task-wait",
                        taskName: "等待任务",
                        priority: "NORMAL",
                        state: "WAITING",
                        message: "等待资源"
                    },
                    {
                        runId: "run-done",
                        taskId: "task-done",
                        taskName: "已完成任务",
                        priority: "URGENT",
                        state: "SUCCEEDED",
                        message: "已完成"
                    },
                    {
                        runId: "queue-b",
                        taskId: "task-b",
                        taskName: "任务B",
                        priority: "NORMAL",
                        state: "QUEUED",
                        message: "旧队列消息"
                    },
                    {
                        runId: "queue-c",
                        taskId: "task-c",
                        taskName: "任务C",
                        priority: "NORMAL",
                        state: "QUEUED",
                        message: "排队C"
                    }
                ],
                [
                    {
                        runId: "queue-a",
                        taskId: "task-a",
                        taskName: "任务A",
                        priority: "URGENT",
                        state: "QUEUED",
                        message: "排队A"
                    },
                    {
                        runId: "queue-b",
                        taskId: "task-b",
                        taskName: "任务B",
                        priority: "NORMAL",
                        state: "QUEUED",
                        message: "队列B"
                    },
                    {
                        runId: "queue-c",
                        taskId: "task-c",
                        taskName: "任务C",
                        priority: "NORMAL",
                        state: "QUEUED",
                        message: "排队C"
                    }
                ],
                [
                    {
                        triggerId: "trigger-urgent",
                        triggerName: "紧急监听",
                        targetTaskId: "task-urgent",
                        targetTaskName: "紧急任务",
                        priority: "URGENT",
                        state: "IDLE",
                        message: "等待条件"
                    },
                    {
                        triggerId: "trigger-b",
                        triggerName: "任务B监听",
                        targetTaskId: "task-b",
                        targetTaskName: "任务B",
                        priority: "NORMAL",
                        state: "RUNNING",
                        message: "已触发"
                    },
                    {
                        triggerId: "trigger-bg",
                        triggerName: "后台监听",
                        targetTaskId: "task-bg",
                        targetTaskName: "后台任务",
                        priority: "BACKGROUND",
                        state: "COOLDOWN",
                        message: "冷却中"
                    }
                ]
            );
            return {
                summary: entries.map((entry) => ({
                    key: entry.key,
                    kind: entry.kind,
                    state: entry.state,
                    message: entry.message
                })),
                queueBCount: entries.filter(
                    (entry) => entry.key === "run:queue-b"
                ).length,
                taskKinds: entries
                    .filter((entry) => entry.taskId === "task-b")
                    .map((entry) => entry.kind)
            };
        })()
        """
    )

    summary = result["summary"]
    assert [entry["key"] for entry in summary] == [
        "run:run-int",
        "run:queue-a",
        "trigger:task-urgent",
        "run:run-wait",
        "run:queue-b",
        "run:queue-c",
        "trigger:task-b",
        "trigger:task-bg",
    ]
    assert summary[0]["state"] == "INTERRUPTION_FAILED"
    assert summary[4]["message"] == "队列B"
    assert result["queueBCount"] == 1
    assert result["taskKinds"] == ["run", "trigger"]


def test_queue_entries_group_triggers_and_keep_trigger_messages() -> None:
    result = _run_model(
        """
        (() => {
            const model = window.StowlingTaskMonitorModel;
            const entries = model.queueEntries(
                [
                    {
                        runId: "shared-run",
                        taskId: "task-shared",
                        taskName: "共享任务",
                        priority: "NORMAL",
                        state: "RUNNING",
                        message: "运行中"
                    }
                ],
                [],
                [
                    {
                        triggerId: "food-trigger",
                        triggerName: "饥饿监听",
                        targetTaskId: "task-auto",
                        targetTaskName: "自动进食",
                        priority: "NORMAL",
                        state: "IDLE",
                        message: "等待饱食度"
                    },
                    {
                        triggerId: "food-trigger",
                        triggerName: "重复监听",
                        targetTaskId: "task-auto",
                        targetTaskName: "自动进食",
                        priority: "URGENT",
                        state: "ERROR",
                        message: "不应保留"
                    },
                    {
                        triggerId: "read-trigger",
                        triggerName: "读取监听",
                        targetTaskId: "task-auto",
                        targetTaskName: "自动进食",
                        priority: "URGENT",
                        state: "ERROR",
                        message: "读取失败"
                    },
                    {
                        triggerId: "shared-trigger",
                        triggerName: "共享监听",
                        targetTaskId: "task-shared",
                        targetTaskName: "共享任务",
                        priority: "BACKGROUND",
                        state: "IDLE",
                        message: "等待缓存"
                    }
                ]
            );
            const grouped = entries.find(
                (entry) => entry.key === "trigger:task-auto"
            );
            const shared = entries.filter(
                (entry) => entry.taskId === "task-shared"
            );
            return {
                grouped: {
                    taskName: grouped.taskName,
                    priority: grouped.priority,
                    state: grouped.state,
                    message: grouped.message,
                    triggerIds: grouped.triggers.map(
                        (trigger) => trigger.triggerId
                    )
                },
                sharedKinds: shared.map((entry) => entry.kind)
            };
        })()
        """
    )

    grouped = result["grouped"]
    assert grouped["taskName"] == "自动进食"
    assert grouped["priority"] == "URGENT"
    assert grouped["state"] == "ERROR"
    assert grouped["triggerIds"] == ["food-trigger", "read-trigger"]
    assert "等待饱食度" in grouped["message"]
    assert "读取失败" in grouped["message"]
    assert "不应保留" not in grouped["message"]
    assert result["sharedKinds"] == ["run", "trigger"]


def test_log_buffer_records_only_state_message_or_progress_changes() -> None:
    result = _run_model(
        """
        (() => {
            const model = window.StowlingTaskMonitorModel;
            const buffer = model.createLogBuffer(20);
            const first = buffer.ingest(
                [
                    {
                        runId: "run-1",
                        taskId: "task-1",
                        taskName: "任务一",
                        priority: "NORMAL",
                        state: "RUNNING",
                        message: "正在运行",
                        completedSteps: 1,
                        totalSteps: 3,
                        updatedAt: "2026-11-16T01:00:00Z"
                    },
                    {
                        runId: "queue-1",
                        taskId: "task-q",
                        taskName: "排队任务",
                        priority: "BACKGROUND",
                        state: "QUEUED",
                        message: "排队等待",
                        completedSteps: 0,
                        totalSteps: 2,
                        updatedAt: "2026-11-16T01:00:00Z"
                    }
                ],
                [
                    {
                        runId: "queue-1",
                        taskId: "task-q",
                        taskName: "排队任务",
                        priority: "BACKGROUND",
                        state: "QUEUED",
                        message: "排队等待",
                        completedSteps: 0,
                        totalSteps: 2,
                        updatedAt: "2026-11-16T01:00:00Z"
                    }
                ],
                [
                    {
                        triggerId: "trigger-1",
                        triggerName: "监听一",
                        targetTaskId: "task-2",
                        targetTaskName: "任务二",
                        priority: "NORMAL",
                        state: "IDLE",
                        message: "等待条件"
                    }
                ],
                "2026-11-16T01:00:01Z"
            );
            const unchanged = buffer.ingest(
                [
                    {
                        runId: "run-1",
                        taskId: "task-1",
                        taskName: "任务一",
                        priority: "NORMAL",
                        state: "RUNNING",
                        message: "正在运行",
                        completedSteps: 1,
                        totalSteps: 3,
                        updatedAt: "2026-11-16T01:00:02Z"
                    },
                    {
                        runId: "queue-1",
                        taskId: "task-q",
                        taskName: "排队任务",
                        priority: "BACKGROUND",
                        state: "QUEUED",
                        message: "排队等待",
                        completedSteps: 0,
                        totalSteps: 2,
                        updatedAt: "2026-11-16T01:00:02Z"
                    }
                ],
                [],
                [
                    {
                        triggerId: "trigger-1",
                        triggerName: "监听一",
                        targetTaskId: "task-2",
                        targetTaskName: "任务二",
                        priority: "NORMAL",
                        state: "IDLE",
                        message: "等待条件"
                    }
                ],
                "2026-11-16T01:00:02Z"
            );
            const messageChanged = buffer.ingest(
                [
                    {
                        runId: "run-1",
                        taskId: "task-1",
                        taskName: "任务一",
                        priority: "NORMAL",
                        state: "RUNNING",
                        message: "处理中",
                        completedSteps: 1,
                        totalSteps: 3,
                        updatedAt: "2026-11-16T01:00:03Z"
                    },
                    {
                        runId: "queue-1",
                        taskId: "task-q",
                        taskName: "排队任务",
                        priority: "BACKGROUND",
                        state: "QUEUED",
                        message: "排队等待",
                        completedSteps: 0,
                        totalSteps: 2,
                        updatedAt: "2026-11-16T01:00:03Z"
                    }
                ],
                [],
                [
                    {
                        triggerId: "trigger-1",
                        triggerName: "监听一",
                        targetTaskId: "task-2",
                        targetTaskName: "任务二",
                        priority: "NORMAL",
                        state: "IDLE",
                        message: "等待条件"
                    }
                ],
                "2026-11-16T01:00:03Z"
            );
            const progressChanged = buffer.ingest(
                [
                    {
                        runId: "run-1",
                        taskId: "task-1",
                        taskName: "任务一",
                        priority: "NORMAL",
                        state: "RUNNING",
                        message: "处理中",
                        completedSteps: 2,
                        totalSteps: 3,
                        updatedAt: "2026-11-16T01:00:04Z"
                    },
                    {
                        runId: "queue-1",
                        taskId: "task-q",
                        taskName: "排队任务",
                        priority: "BACKGROUND",
                        state: "QUEUED",
                        message: "排队等待",
                        completedSteps: 0,
                        totalSteps: 2,
                        updatedAt: "2026-11-16T01:00:04Z"
                    }
                ],
                [],
                [
                    {
                        triggerId: "trigger-1",
                        triggerName: "监听一",
                        targetTaskId: "task-2",
                        targetTaskName: "任务二",
                        priority: "NORMAL",
                        state: "IDLE",
                        message: "等待条件"
                    }
                ],
                "2026-11-16T01:00:04Z"
            );
            const triggerChanged = buffer.ingest(
                [
                    {
                        runId: "run-1",
                        taskId: "task-1",
                        taskName: "任务一",
                        priority: "NORMAL",
                        state: "RUNNING",
                        message: "处理中",
                        completedSteps: 2,
                        totalSteps: 3,
                        updatedAt: "2026-11-16T01:00:05Z"
                    },
                    {
                        runId: "queue-1",
                        taskId: "task-q",
                        taskName: "排队任务",
                        priority: "BACKGROUND",
                        state: "QUEUED",
                        message: "排队等待",
                        completedSteps: 0,
                        totalSteps: 2,
                        updatedAt: "2026-11-16T01:00:05Z"
                    }
                ],
                [],
                [
                    {
                        triggerId: "trigger-1",
                        triggerName: "监听一",
                        targetTaskId: "task-2",
                        targetTaskName: "任务二",
                        priority: "NORMAL",
                        state: "ERROR",
                        message: "监听失败"
                    }
                ],
                "2026-11-16T01:00:05Z"
            );
            const entries = buffer.entries();
            return {
                counts: [
                    first,
                    unchanged,
                    messageChanged,
                    progressChanged,
                    triggerChanged
                ],
                length: entries.length,
                uniqueIds: new Set(entries.map((entry) => entry.id)).size,
                progressEntry: entries.find(
                    (entry) => entry.completedSteps === 2
                ),
                last: entries[entries.length - 1]
            };
        })()
        """
    )

    assert result["counts"] == [3, 0, 1, 1, 1]
    assert result["length"] == 6
    assert result["uniqueIds"] == 6
    assert result["progressEntry"]["completedSteps"] == 2
    assert result["progressEntry"]["totalSteps"] == 3
    assert result["last"]["kind"] == "trigger"
    assert result["last"]["state"] == "ERROR"
    assert result["last"]["taskName"] == "任务二"
    assert result["last"]["message"] == "监听一：监听失败"


def test_trigger_logs_keep_target_task_and_listener_messages() -> None:
    result = _run_model(
        """
        (() => {
            const model = window.StowlingTaskMonitorModel;
            const buffer = model.createLogBuffer(10);
            const added = buffer.ingest(
                [],
                [],
                [
                    {
                        triggerId: "food-trigger",
                        triggerName: "饥饿监听",
                        targetTaskId: "task-eat",
                        targetTaskName: "自动进食",
                        priority: "NORMAL",
                        state: "IDLE",
                        message: "等待饱食度"
                    },
                    {
                        triggerId: "inventory-trigger",
                        triggerName: "背包监听",
                        targetTaskId: "task-eat",
                        targetTaskName: "自动进食",
                        priority: "NORMAL",
                        state: "ERROR",
                        message: "读取背包失败"
                    },
                    {
                        triggerId: "cache-trigger",
                        triggerName: "缓存监听",
                        targetTaskId: "task-cache",
                        priority: "BACKGROUND",
                        state: "COOLDOWN",
                        message: ""
                    }
                ],
                "2026-11-16T05:00:00Z"
            );
            return {
                added,
                entries: buffer.entries().map((entry) => ({
                    id: entry.id,
                    taskName: entry.taskName,
                    state: entry.state,
                    message: entry.message
                }))
            };
        })()
        """
    )

    assert result["added"] == 3
    entries = result["entries"]
    assert [entry["taskName"] for entry in entries] == [
        "自动进食",
        "自动进食",
        "缓存监听",
    ]
    assert entries[0]["message"] == "饥饿监听：等待饱食度"
    assert entries[1]["message"] == "背包监听：读取背包失败"
    assert entries[2]["message"] == "缓存监听：冷却中"
    assert len({entry["id"] for entry in entries}) == 3


def test_log_buffer_keeps_only_the_latest_limit_entries() -> None:
    result = _run_model(
        """
        (() => {
            const model = window.StowlingTaskMonitorModel;
            const buffer = model.createLogBuffer(2);
            const first = buffer.ingest(
                [
                    {
                        runId: "run-1",
                        taskName: "任务一",
                        priority: "NORMAL",
                        state: "RUNNING",
                        message: "运行一",
                        completedSteps: 0,
                        totalSteps: 1
                    },
                    {
                        runId: "run-2",
                        taskName: "任务二",
                        priority: "NORMAL",
                        state: "RUNNING",
                        message: "运行二",
                        completedSteps: 0,
                        totalSteps: 1
                    },
                    {
                        runId: "run-3",
                        taskName: "任务三",
                        priority: "NORMAL",
                        state: "RUNNING",
                        message: "运行三",
                        completedSteps: 0,
                        totalSteps: 1
                    }
                ],
                [],
                [],
                "2026-11-16T02:00:00Z"
            );
            const afterFirst = buffer.entries();
            const second = buffer.ingest(
                [
                    {
                        runId: "run-1",
                        taskName: "任务一",
                        priority: "NORMAL",
                        state: "WAITING",
                        message: "等待一",
                        completedSteps: 0,
                        totalSteps: 1
                    },
                    {
                        runId: "run-2",
                        taskName: "任务二",
                        priority: "NORMAL",
                        state: "RUNNING",
                        message: "运行二",
                        completedSteps: 0,
                        totalSteps: 1
                    },
                    {
                        runId: "run-3",
                        taskName: "任务三",
                        priority: "NORMAL",
                        state: "RUNNING",
                        message: "运行三",
                        completedSteps: 0,
                        totalSteps: 1
                    }
                ],
                [],
                [],
                "2026-11-16T02:00:01Z"
            );
            const afterSecond = buffer.entries();
            return {
                counts: [first, second],
                firstTasks: afterFirst.map((entry) => entry.taskName),
                secondTasks: afterSecond.map((entry) => entry.taskName),
                secondStates: afterSecond.map((entry) => entry.state)
            };
        })()
        """
    )

    assert result["counts"] == [3, 1]
    assert result["firstTasks"] == ["任务二", "任务三"]
    assert result["secondTasks"] == ["任务三", "任务一"]
    assert result["secondStates"] == ["RUNNING", "WAITING"]


def test_clear_removes_entries_but_keeps_the_change_baseline() -> None:
    result = _run_model(
        """
        (() => {
            const model = window.StowlingTaskMonitorModel;
            const buffer = model.createLogBuffer(10);
            buffer.ingest(
                [
                    {
                        runId: "run-clear",
                        taskName: "清空任务",
                        priority: "NORMAL",
                        state: "RUNNING",
                        message: "运行中",
                        completedSteps: 0,
                        totalSteps: 2,
                        updatedAt: "2026-11-16T03:00:00Z"
                    }
                ],
                [],
                [],
                "2026-11-16T03:00:00Z"
            );
            buffer.clear();
            const clearedLength = buffer.entries().length;
            const sameAfterClear = buffer.ingest(
                [
                    {
                        runId: "run-clear",
                        taskName: "清空任务",
                        priority: "NORMAL",
                        state: "RUNNING",
                        message: "运行中",
                        completedSteps: 0,
                        totalSteps: 2,
                        updatedAt: "2026-11-16T03:00:01Z"
                    }
                ],
                [],
                [],
                "2026-11-16T03:00:01Z"
            );
            const afterSameLength = buffer.entries().length;
            const changed = buffer.ingest(
                [
                    {
                        runId: "run-clear",
                        taskName: "清空任务",
                        priority: "NORMAL",
                        state: "SUCCEEDED",
                        message: "已完成",
                        completedSteps: 2,
                        totalSteps: 2,
                        updatedAt: "2026-11-16T03:00:02Z"
                    }
                ],
                [],
                [],
                "2026-11-16T03:00:02Z"
            );
            const finalEntries = buffer.entries();
            return {
                clearedLength,
                sameAfterClear,
                afterSameLength,
                changed,
                finalLength: finalEntries.length,
                finalState: finalEntries[0].state
            };
        })()
        """
    )

    assert result["clearedLength"] == 0
    assert result["sameAfterClear"] == 0
    assert result["afterSameLength"] == 0
    assert result["changed"] == 1
    assert result["finalLength"] == 1
    assert result["finalState"] == "SUCCEEDED"


def test_export_text_uses_chinese_headers_labels_and_progress() -> None:
    result = _run_model(
        """
        (() => {
            const model = window.StowlingTaskMonitorModel;
            const buffer = model.createLogBuffer(10);
            buffer.ingest(
                [
                    {
                        runId: "run-export",
                        taskId: "task-navigation",
                        taskName: "导航任务",
                        priority: "URGENT",
                        state: "INTERRUPTION_FAILED",
                        message: "导航失败\\n请检查",
                        completedSteps: 2,
                        totalSteps: 4
                    }
                ],
                [],
                [
                    {
                        triggerId: "trigger-export",
                        triggerName: "饥饿监听",
                        targetTaskId: "task-eat",
                        targetTaskName: "自动进食",
                        priority: "BACKGROUND",
                        state: "ERROR",
                        message: "读取饱食度失败"
                    }
                ],
                "2026-11-16T04:00:00Z"
            );
            return {
                text: buffer.exportText()
            };
        })()
        """
    )

    lines = result["text"].splitlines()
    assert lines[0] == "时间\t类型\t任务\t状态\t优先级\t消息\t进度"
    assert len(lines) == 3
    assert "2026-11-16T04:00:00Z" in lines[1]
    assert "\t运行\t" in lines[1]
    assert "导航任务" in lines[1]
    assert "中断失败（活动）" in lines[1]
    assert "紧急" in lines[1]
    assert "导航失败 请检查" in lines[1]
    assert "2 / 4 步" in lines[1]
    trigger_columns = lines[2].split("\t")
    assert "\t监听\t" in lines[2]
    assert trigger_columns[2] == "自动进食"
    assert trigger_columns[3] == "监听异常"
    assert trigger_columns[4] == "后台"
    assert trigger_columns[5] == "饥饿监听：读取饱食度失败"
    assert lines[2].endswith("—")
