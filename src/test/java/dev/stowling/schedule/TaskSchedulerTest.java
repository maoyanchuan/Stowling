package dev.stowling.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.AutomationStep;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TaskSchedulerTest {
    private static final Instant START = Instant.parse("2026-11-15T01:30:00Z");

    @Test
    void mergesRunNowRequestsAndStartsOnlyOnTick() {
        TaskDefinition definition = manualTask(
                "manual",
                TaskPriority.HIGH,
                TaskInterruption.none()
        );
        MutableClock clock = clock();
        FakeRuntimeFactory factory = new FakeRuntimeFactory();
        FakeRuntime runtime = new FakeRuntime();
        factory.addRuntime(runtime);
        TaskScheduler scheduler = scheduler(
                List.of(definition),
                factory,
                new FakeRegionItemProbe(),
                new InMemoryTaskScheduleStore(),
                clock
        );

        UUID first = scheduler.runNow(definition.key());
        UUID second = scheduler.runNow(definition.key());

        assertEquals(first, second);
        assertEquals("QUEUED", snapshot(scheduler, definition.key()).state());
        assertFalse(scheduler.isBusy());
        assertEquals(1, factory.queuedCalls().size());
        assertTrue(factory.startCalls().isEmpty());

        scheduler.tick();

        assertTrue(scheduler.isBusy());
        assertEquals(1, factory.startCalls().size());
        assertEquals(first, factory.startCalls().getFirst().runId());

        runtime.succeed("done");
        scheduler.tick();

        assertFalse(scheduler.isBusy());
        assertEquals("DISABLED", snapshot(scheduler, definition.key()).state());
    }

    @Test
    void idleTicksDoNotRewriteUnchangedRecords() {
        TaskDefinition definition = manualTask(
                "idle-write",
                TaskPriority.MEDIUM,
                TaskInterruption.none()
        );
        CountingTaskScheduleStore store = new CountingTaskScheduleStore();
        FakeRuntimeFactory factory = new FakeRuntimeFactory();
        factory.addRuntime(new FakeRuntime());
        TaskScheduler scheduler = scheduler(
                List.of(definition),
                factory,
                new FakeRegionItemProbe(),
                store,
                clock()
        );
        scheduler.runNow(definition.key());
        scheduler.tick();
        scheduler.tick();
        int writesBeforeIdleTick = store.saveCount();

        scheduler.tick();

        assertEquals(writesBeforeIdleTick, store.saveCount());
    }

    @Test
    void fixedIntervalAdvancesFromNowWithoutCatchingUp() {
        TaskTrigger trigger = new TaskTrigger(
                TriggerKind.FIXED_INTERVAL,
                10L,
                List.of(),
                TaskTrigger.DEFAULT_ZONE_ID,
                List.of()
        );
        TaskDefinition definition = manualTask(
                "fixed",
                TaskPriority.MEDIUM,
                TaskInterruption.none(),
                trigger
        );
        MutableClock clock = clock();
        FakeRuntimeFactory factory = new FakeRuntimeFactory();
        factory.addRuntime(new FakeRuntime());
        TaskScheduler scheduler = scheduler(
                List.of(definition),
                factory,
                new FakeRegionItemProbe(),
                new InMemoryTaskScheduleStore(),
                clock
        );

        scheduler.enable(definition.key());
        assertEquals(START.plusSeconds(10), snapshot(scheduler, definition.key()).nextRunAt());

        clock.advance(Duration.ofSeconds(35));
        scheduler.tick();

        assertEquals(1, factory.queuedCalls().size());
        assertEquals(START.plusSeconds(40), snapshot(scheduler, definition.key()).nextRunAt());
        assertEquals(1, factory.startCalls().size());
    }

    @Test
    void samePriorityDoesNotPreemptAndStartsInFifoOrder() {
        TaskDefinition first = manualTask(
                "first",
                TaskPriority.MEDIUM,
                TaskInterruption.none()
        );
        TaskDefinition second = manualTask(
                "second",
                TaskPriority.MEDIUM,
                TaskInterruption.none()
        );
        FakeRuntimeFactory factory = new FakeRuntimeFactory();
        FakeRuntime firstRuntime = new FakeRuntime();
        FakeRuntime secondRuntime = new FakeRuntime();
        factory.addRuntime(firstRuntime);
        TaskScheduler scheduler = scheduler(
                List.of(first, second),
                factory,
                new FakeRegionItemProbe(),
                new InMemoryTaskScheduleStore(),
                clock()
        );
        scheduler.runNow(first.key());
        scheduler.tick();

        scheduler.runNow(second.key());
        factory.addRuntime(secondRuntime);
        scheduler.tick();

        assertEquals(0, firstRuntime.requestStopCount());
        firstRuntime.succeed("first done");
        scheduler.tick();

        assertEquals(
                List.of(first.key(), second.key()),
                factory.startCalls().stream()
                        .map(call -> call.definition().key())
                        .toList()
        );
    }

    @Test
    void higherPriorityPreemptsAfterCleanStopAndRunsInterruption() {
        AutomationStep placeholder = TaskFixtures.waitStep("cleanup");
        TaskInterruption interruption = new TaskInterruption(
                InterruptionKind.INLINE,
                List.of(placeholder),
                null
        );
        TaskDefinition low = manualTask("low", TaskPriority.LOW, interruption);
        TaskDefinition high = manualTask(
                "high",
                TaskPriority.HIGH,
                TaskInterruption.none()
        );
        FakeRuntimeFactory factory = new FakeRuntimeFactory();
        FakeRuntime lowRuntime = new FakeRuntime();
        FakeRuntime interruptionRuntime = new FakeRuntime();
        FakeRuntime highRuntime = new FakeRuntime();
        FakeRuntime lowRerun = new FakeRuntime();
        interruptionRuntime.succeed("interrupted cleanly");
        highRuntime.succeed("high done");
        factory.addRuntime(lowRuntime);
        factory.addRuntime(interruptionRuntime);
        factory.addRuntime(highRuntime);
        factory.addRuntime(lowRerun);
        TaskScheduler scheduler = scheduler(
                List.of(low, high),
                factory,
                new FakeRegionItemProbe(),
                new InMemoryTaskScheduleStore(),
                clock()
        );

        UUID originalRunId = scheduler.runNow(low.key());
        scheduler.tick();
        scheduler.runNow(high.key());
        scheduler.tick();

        assertEquals(1, lowRuntime.requestStopCount());
        assertEquals(0, lowRuntime.tickCount());
        assertEquals(1, factory.startCalls().size());

        scheduler.tick();

        assertEquals(2, factory.startCalls().size());
        assertEquals(
                List.of(placeholder),
                factory.startCalls().get(1).definition().steps()
        );
        assertTrue(factory.finishedCalls().stream().anyMatch(call ->
                call.runId().equals(originalRunId)
                        && "INTERRUPTED".equals(call.status())
        ));

        scheduler.tick();
        assertEquals(high.key(), factory.startCalls().get(2).definition().key());

        scheduler.tick();
        assertEquals(4, factory.startCalls().size());
        assertEquals(low.key(), factory.startCalls().get(3).definition().key());
        assertNotEquals(originalRunId, factory.startCalls().get(3).runId());
        assertEquals("RUNNING", snapshot(scheduler, low.key()).state());
    }

    @Test
    void disableStopsManualRunAndRunsInterruptionWithoutRerun() {
        AutomationStep cleanup = TaskFixtures.waitStep("cleanup");
        TaskInterruption interruption = new TaskInterruption(
                InterruptionKind.INLINE,
                List.of(cleanup),
                null
        );
        TaskDefinition definition = manualTask(
                "manual-disable",
                TaskPriority.LOW,
                interruption
        );
        FakeRuntimeFactory factory = new FakeRuntimeFactory();
        FakeRuntime runtime = new FakeRuntime();
        FakeRuntime interruptionRuntime = new FakeRuntime();
        interruptionRuntime.succeed("clean");
        factory.addRuntime(runtime);
        factory.addRuntime(interruptionRuntime);
        TaskScheduler scheduler = scheduler(
                List.of(definition),
                factory,
                new FakeRegionItemProbe(),
                new InMemoryTaskScheduleStore(),
                clock()
        );
        scheduler.runNow(definition.key());
        scheduler.tick();

        scheduler.disable(definition.key());
        assertEquals(0, runtime.requestStopCount());
        assertTrue(scheduler.isBusy());

        scheduler.tick();
        assertEquals(1, runtime.requestStopCount());
        assertEquals(1, factory.startCalls().size());

        scheduler.tick();
        assertEquals(2, factory.startCalls().size());
        assertEquals(List.of(cleanup), factory.startCalls().get(1).definition().steps());
        assertEquals("INTERRUPTING", snapshot(scheduler, definition.key()).state());

        scheduler.tick();

        assertFalse(scheduler.isBusy());
        assertEquals(2, factory.startCalls().size());
        assertEquals("DISABLED", snapshot(scheduler, definition.key()).state());
        assertFalse(snapshot(scheduler, definition.key()).enabled());
    }

    @Test
    void unsafeRuntimeKeepsTheResourceLocked() {
        TaskDefinition low = manualTask(
                "unsafe",
                TaskPriority.LOW,
                TaskInterruption.none()
        );
        TaskDefinition high = manualTask(
                "blocked",
                TaskPriority.HIGH,
                TaskInterruption.none()
        );
        FakeRuntimeFactory factory = new FakeRuntimeFactory();
        FakeRuntime runtime = new FakeRuntime();
        runtime.unsafe("unsafe cleanup");
        factory.addRuntime(runtime);
        TaskScheduler scheduler = scheduler(
                List.of(low, high),
                factory,
                new FakeRegionItemProbe(),
                new InMemoryTaskScheduleStore(),
                clock()
        );
        scheduler.runNow(low.key());
        scheduler.tick();
        scheduler.tick();

        scheduler.runNow(high.key());
        scheduler.tick();

        assertTrue(scheduler.isBusy());
        assertEquals(1, factory.startCalls().size());
        assertEquals("UNSAFE", snapshot(scheduler, low.key()).state());
        assertEquals("QUEUED", snapshot(scheduler, high.key()).state());
    }

    @Test
    void safeFailureDoesNotUpdateCompletionTiming() {
        TaskTrigger trigger = new TaskTrigger(
                TriggerKind.AFTER_COMPLETION,
                30L,
                List.of(),
                TaskTrigger.DEFAULT_ZONE_ID,
                List.of()
        );
        TaskDefinition definition = manualTask(
                "failure",
                TaskPriority.MEDIUM,
                TaskInterruption.none(),
                trigger
        );
        MutableClock clock = clock();
        FakeRuntimeFactory factory = new FakeRuntimeFactory();
        FakeRuntime runtime = new FakeRuntime();
        factory.addRuntime(runtime);
        TaskScheduler scheduler = scheduler(
                List.of(definition),
                factory,
                new FakeRegionItemProbe(),
                new InMemoryTaskScheduleStore(),
                clock
        );

        scheduler.enable(definition.key());
        scheduler.tick();
        clock.advance(Duration.ofSeconds(5));
        runtime.fail("safe failure");
        scheduler.tick();

        TaskScheduleInfo info = snapshot(scheduler, definition.key());
        assertNull(info.lastFinishedAt());
        assertNull(info.nextRunAt());
        assertEquals("WAITING", info.state());
        assertTrue(info.enabled());
    }

    @Test
    void queuedRunUsesLatestDefinitionWhenItStarts() {
        AutomationStep oldStep = TaskFixtures.waitStep("old");
        AutomationStep latestStep = TaskFixtures.waitStep("latest");
        TaskDefinition oldDefinition = TaskFixtures.task(
                "refresh",
                TaskPriority.MEDIUM,
                new TaskTrigger(),
                true,
                TaskInterruption.none(),
                List.of(oldStep)
        );
        TaskDefinition latestDefinition = TaskFixtures.task(
                "refresh",
                TaskPriority.MEDIUM,
                new TaskTrigger(),
                true,
                TaskInterruption.none(),
                List.of(latestStep)
        );
        AtomicReference<List<TaskDefinition>> definitions =
                new AtomicReference<>(List.of(oldDefinition));
        FakeRuntimeFactory factory = new FakeRuntimeFactory();
        factory.addRuntime(new FakeRuntime());
        TaskScheduler scheduler = new TaskScheduler(
                definitions::get,
                factory,
                new FakeRegionItemProbe(),
                new InMemoryTaskScheduleStore(),
                clock()
        );

        scheduler.runNow(oldDefinition.key());
        definitions.set(List.of(latestDefinition));
        scheduler.tick();

        assertEquals(List.of(latestStep), factory.startCalls().getFirst().definition().steps());
    }

    @Test
    void runNowOverrideKeepsItsOwnBodyUntilStart() {
        AutomationStep configuredStep = TaskFixtures.waitStep("configured");
        AutomationStep overrideStep = TaskFixtures.waitStep("override");
        TaskDefinition configured = TaskFixtures.task(
                "legacy-override",
                TaskPriority.HIGH,
                new TaskTrigger(),
                false,
                TaskInterruption.none(),
                List.of(configuredStep)
        );
        TaskDefinition override = TaskFixtures.task(
                "legacy-override",
                TaskPriority.MEDIUM,
                new TaskTrigger(),
                false,
                TaskInterruption.none(),
                List.of(overrideStep)
        );
        FakeRuntimeFactory factory = new FakeRuntimeFactory();
        factory.addRuntime(new FakeRuntime());
        TaskScheduler scheduler = scheduler(
                List.of(configured),
                factory,
                new FakeRegionItemProbe(),
                new InMemoryTaskScheduleStore(),
                clock()
        );

        scheduler.runNow(override);
        scheduler.tick();

        assertEquals(
                List.of(overrideStep),
                factory.startCalls().getFirst().definition().steps()
        );
        assertEquals(TaskPriority.HIGH, snapshot(scheduler, configured.key()).priority());
    }

    @Test
    void runNowOverrideDoesNotReplaceConfiguredPriorityForPreemption() {
        TaskDefinition configured = TaskFixtures.task(
                "priority-owner",
                TaskPriority.LOW,
                new TaskTrigger(),
                false,
                TaskInterruption.none()
        );
        TaskDefinition override = TaskFixtures.task(
                "priority-owner",
                TaskPriority.HIGH,
                new TaskTrigger(),
                false,
                TaskInterruption.none()
        );
        TaskDefinition medium = manualTask(
                "priority-challenger",
                TaskPriority.MEDIUM,
                TaskInterruption.none()
        );
        FakeRuntimeFactory factory = new FakeRuntimeFactory();
        FakeRuntime runtime = new FakeRuntime();
        factory.addRuntime(runtime);
        TaskScheduler scheduler = scheduler(
                List.of(configured, medium),
                factory,
                new FakeRegionItemProbe(),
                new InMemoryTaskScheduleStore(),
                clock()
        );
        scheduler.runNow(override);
        scheduler.tick();
        scheduler.runNow(medium.key());

        scheduler.tick();

        assertEquals(1, runtime.requestStopCount());
    }

    @Test
    void removedDefinitionCancelsQueueAndDisablesScheduling() {
        TaskDefinition definition = manualTask(
                "removed",
                TaskPriority.MEDIUM,
                TaskInterruption.none()
        );
        AtomicReference<List<TaskDefinition>> definitions =
                new AtomicReference<>(List.of(definition));
        FakeRuntimeFactory factory = new FakeRuntimeFactory();
        TaskScheduler scheduler = new TaskScheduler(
                definitions::get,
                factory,
                new FakeRegionItemProbe(),
                new InMemoryTaskScheduleStore(),
                clock()
        );
        scheduler.enable(definition.key());
        UUID queuedRunId = scheduler.runNow(definition.key());
        definitions.set(List.of());

        scheduler.tick();

        assertTrue(factory.startCalls().isEmpty());
        assertEquals("DISABLED", snapshot(scheduler, definition.key()).state());
        assertTrue(factory.finishedCalls().stream().anyMatch(call ->
                call.runId().equals(queuedRunId)
                        && "CANCELLED".equals(call.status())
        ));
    }

    @Test
    void removedDefinitionSafelyStopsActiveRunOnClientTicks() {
        TaskDefinition definition = manualTask(
                "removed-active",
                TaskPriority.MEDIUM,
                TaskInterruption.none()
        );
        AtomicReference<List<TaskDefinition>> definitions =
                new AtomicReference<>(List.of(definition));
        FakeRuntimeFactory factory = new FakeRuntimeFactory();
        FakeRuntime runtime = new FakeRuntime();
        factory.addRuntime(runtime);
        TaskScheduler scheduler = new TaskScheduler(
                definitions::get,
                factory,
                new FakeRegionItemProbe(),
                new InMemoryTaskScheduleStore(),
                clock()
        );
        scheduler.runNow(definition.key());
        scheduler.tick();
        definitions.set(List.of());

        scheduler.tick();

        assertEquals(1, runtime.requestStopCount());
        assertEquals(0, runtime.tickCount());

        scheduler.tick();

        assertFalse(scheduler.isBusy());
        assertEquals("DISABLED", snapshot(scheduler, definition.key()).state());
    }

    @Test
    void changedFixedIntervalRearmsFromRefreshTime() {
        TaskDefinition original = manualTask(
                "retimed",
                TaskPriority.MEDIUM,
                TaskInterruption.none(),
                new TaskTrigger(
                        TriggerKind.FIXED_INTERVAL,
                        10L,
                        List.of(),
                        TaskTrigger.DEFAULT_ZONE_ID,
                        List.of()
                )
        );
        TaskDefinition changed = manualTask(
                "retimed",
                TaskPriority.MEDIUM,
                TaskInterruption.none(),
                new TaskTrigger(
                        TriggerKind.FIXED_INTERVAL,
                        20L,
                        List.of(),
                        TaskTrigger.DEFAULT_ZONE_ID,
                        List.of()
                )
        );
        AtomicReference<List<TaskDefinition>> definitions =
                new AtomicReference<>(List.of(original));
        MutableClock clock = clock();
        TaskScheduler scheduler = new TaskScheduler(
                definitions::get,
                new FakeRuntimeFactory(),
                new FakeRegionItemProbe(),
                new InMemoryTaskScheduleStore(),
                clock
        );
        scheduler.enable(original.key());
        clock.advance(Duration.ofSeconds(5));

        definitions.set(List.of(changed));
        scheduler.tick();

        assertEquals(START.plusSeconds(25), snapshot(scheduler, original.key()).nextRunAt());
    }

    @Test
    void changedAfterCompletionWaitsForActiveRunToFinish() {
        TaskDefinition original = manualTask(
                "after-change",
                TaskPriority.MEDIUM,
                TaskInterruption.none()
        );
        TaskDefinition changed = manualTask(
                "after-change",
                TaskPriority.MEDIUM,
                TaskInterruption.none(),
                new TaskTrigger(
                        TriggerKind.AFTER_COMPLETION,
                        30L,
                        List.of(),
                        TaskTrigger.DEFAULT_ZONE_ID,
                        List.of()
                )
        );
        AtomicReference<List<TaskDefinition>> definitions =
                new AtomicReference<>(List.of(original));
        MutableClock clock = clock();
        FakeRuntimeFactory factory = new FakeRuntimeFactory();
        FakeRuntime runtime = new FakeRuntime();
        factory.addRuntime(runtime);
        TaskScheduler scheduler = new TaskScheduler(
                definitions::get,
                factory,
                new FakeRegionItemProbe(),
                new InMemoryTaskScheduleStore(),
                clock
        );
        scheduler.enable(original.key());
        scheduler.runNow(original.key());
        scheduler.tick();
        clock.advance(Duration.ofSeconds(5));

        definitions.set(List.of(changed));
        scheduler.tick();

        assertNull(snapshot(scheduler, original.key()).nextRunAt());

        runtime.succeed("complete");
        scheduler.tick();

        assertEquals(START.plusSeconds(35), snapshot(scheduler, original.key()).nextRunAt());
    }

    @Test
    void disableStopsOnlyTheRequestedManualRun() {
        TaskDefinition first = manualTask(
                "manual-first",
                TaskPriority.MEDIUM,
                TaskInterruption.none()
        );
        TaskDefinition second = manualTask(
                "manual-second",
                TaskPriority.MEDIUM,
                TaskInterruption.none()
        );
        FakeRuntimeFactory factory = new FakeRuntimeFactory();
        factory.addRuntime(new FakeRuntime());
        factory.addRuntime(new FakeRuntime());
        TaskScheduler scheduler = scheduler(
                List.of(first, second),
                factory,
                new FakeRegionItemProbe(),
                new InMemoryTaskScheduleStore(),
                clock()
        );

        scheduler.runNow(first.key());
        scheduler.tick();
        scheduler.runNow(second.key());
        scheduler.disable(first.key());
        scheduler.tick();
        scheduler.tick();

        assertEquals(2, factory.startCalls().size());
        assertEquals(second.key(), factory.startCalls().get(1).definition().key());
    }

    @Test
    void itemTriggerUsesPickupRegionsAndWaitsOneSecondBetweenProbes() {
        TaskTrigger trigger = new TaskTrigger(
                TriggerKind.ITEMS_PRESENT,
                0L,
                List.of(),
                TaskTrigger.DEFAULT_ZONE_ID,
                List.of()
        );
        TaskSettings settings = new TaskSettings(
                TaskPriority.HIGH,
                trigger,
                false,
                TaskInterruption.none()
        );
        TaskDefinition definition = TaskFixtures.pickup("pickup-items", settings);
        MutableClock clock = clock();
        FakeRegionItemProbe probe = new FakeRegionItemProbe();
        probe.setObservation(RegionItemProbe.Observation.PRESENT);
        FakeRuntimeFactory factory = new FakeRuntimeFactory();
        factory.addRuntime(new FakeRuntime());
        TaskScheduler scheduler = scheduler(
                List.of(definition),
                factory,
                probe,
                new InMemoryTaskScheduleStore(),
                clock
        );

        scheduler.enable(definition.key());
        scheduler.tick();

        assertEquals(List.of(List.of("region-a")), probe.observedRegions());
        assertEquals(1, factory.startCalls().size());

        probe.setObservation(RegionItemProbe.Observation.UNOBSERVABLE);
        clock.advance(Duration.ofSeconds(1));
        scheduler.tick();

        assertEquals(2, probe.observedRegions().size());
        assertEquals(1, factory.queuedCalls().size());
    }

    @Test
    void afterCompletionUpdatesTimingOnlyAfterSuccess() {
        TaskTrigger trigger = new TaskTrigger(
                TriggerKind.AFTER_COMPLETION,
                30L,
                List.of(),
                TaskTrigger.DEFAULT_ZONE_ID,
                List.of()
        );
        TaskDefinition definition = manualTask(
                "after",
                TaskPriority.MEDIUM,
                TaskInterruption.none(),
                trigger
        );
        MutableClock clock = clock();
        FakeRuntimeFactory factory = new FakeRuntimeFactory();
        FakeRuntime runtime = new FakeRuntime();
        factory.addRuntime(runtime);
        TaskScheduler scheduler = scheduler(
                List.of(definition),
                factory,
                new FakeRegionItemProbe(),
                new InMemoryTaskScheduleStore(),
                clock
        );

        scheduler.enable(definition.key());
        scheduler.tick();
        clock.advance(Duration.ofSeconds(5));
        runtime.succeed("complete");
        scheduler.tick();

        TaskScheduleInfo info = snapshot(scheduler, definition.key());
        assertEquals(START.plusSeconds(5), info.lastFinishedAt());
        assertEquals(START.plusSeconds(35), info.nextRunAt());
        assertEquals("WAITING", info.state());
    }

    @Test
    void dailyTriggerUsesConfiguredZoneAndNextWallClockTime() {
        TaskTrigger trigger = new TaskTrigger(
                TriggerKind.DAILY,
                0L,
                List.of("18:00", "10:00"),
                "Asia/Shanghai",
                List.of()
        );
        TaskDefinition definition = manualTask(
                "daily",
                TaskPriority.MEDIUM,
                TaskInterruption.none(),
                trigger
        );
        TaskScheduler scheduler = scheduler(
                List.of(definition),
                new FakeRuntimeFactory(),
                new FakeRegionItemProbe(),
                new InMemoryTaskScheduleStore(),
                clock()
        );

        scheduler.enable(definition.key());

        assertEquals(
                Instant.parse("2026-11-15T02:00:00Z"),
                snapshot(scheduler, definition.key()).nextRunAt()
        );
    }

    @Test
    void restoreCancelsOldRunAndQueuesNewIdentityForAutoStart() {
        TaskDefinition definition = autoStartTask("restore");
        MutableClock clock = clock();
        InMemoryTaskScheduleStore store = new InMemoryTaskScheduleStore();
        UUID oldRunId = UUID.randomUUID();
        store.save(new TaskScheduleRecord(
                definition.key(),
                true,
                "RUNNING",
                TaskPriority.HIGH,
                null,
                null,
                oldRunId,
                definition.key(),
                false,
                null,
                0L,
                true,
                "running before restart",
                false,
                null
        ));
        FakeRuntimeFactory factory = new FakeRuntimeFactory();
        factory.addRuntime(new FakeRuntime());
        TaskScheduler scheduler = scheduler(
                List.of(definition),
                factory,
                new FakeRegionItemProbe(),
                store,
                clock
        );

        scheduler.restore();

        assertTrue(factory.finishedCalls().stream().anyMatch(call ->
                call.runId().equals(oldRunId) && "CANCELLED".equals(call.status())
        ));
        assertEquals(1, factory.queuedCalls().size());
        assertFalse(scheduler.isBusy());

        scheduler.tick();

        assertEquals(1, factory.startCalls().size());
        assertNotEquals(oldRunId, factory.startCalls().getFirst().runId());
        assertTrue(scheduler.isBusy());
    }

    @Test
    void restoreReplacesOldQueuedIdentityBeforeStarting() {
        TaskDefinition definition = autoStartTask("restore-queued");
        InMemoryTaskScheduleStore store = new InMemoryTaskScheduleStore();
        UUID oldQueuedRunId = UUID.randomUUID();
        store.save(new TaskScheduleRecord(
                definition.key(),
                true,
                "QUEUED",
                TaskPriority.HIGH,
                null,
                null,
                oldQueuedRunId,
                null,
                true,
                oldQueuedRunId,
                3L,
                false,
                "queued before restart",
                false,
                null
        ));
        FakeRuntimeFactory factory = new FakeRuntimeFactory();
        factory.addRuntime(new FakeRuntime());
        TaskScheduler scheduler = scheduler(
                List.of(definition),
                factory,
                new FakeRegionItemProbe(),
                store,
                clock()
        );

        scheduler.restore();

        assertTrue(factory.finishedCalls().stream().anyMatch(call ->
                call.runId().equals(oldQueuedRunId)
                        && "CANCELLED".equals(call.status())
        ));
        assertEquals(1, factory.queuedCalls().size());
        UUID replacementRunId = factory.queuedCalls().getFirst().runId();
        assertNotEquals(oldQueuedRunId, replacementRunId);
        assertEquals(replacementRunId, snapshot(scheduler, definition.key()).runId());

        scheduler.tick();

        assertEquals(replacementRunId, factory.startCalls().getFirst().runId());
    }

    @Test
    void restoreKeepsUnsafeLockOnTheAffectedTask() {
        TaskDefinition definition = autoStartTask("restore-unsafe");
        InMemoryTaskScheduleStore store = new InMemoryTaskScheduleStore();
        UUID oldRunId = UUID.randomUUID();
        store.save(new TaskScheduleRecord(
                definition.key(),
                true,
                "UNSAFE",
                TaskPriority.HIGH,
                null,
                null,
                oldRunId,
                definition.key(),
                false,
                null,
                0L,
                false,
                "unsafe before restart",
                true,
                null
        ));
        FakeRuntimeFactory factory = new FakeRuntimeFactory();
        TaskScheduler scheduler = scheduler(
                List.of(definition),
                factory,
                new FakeRegionItemProbe(),
                store,
                clock()
        );

        scheduler.restore();
        scheduler.tick();

        assertTrue(scheduler.isBusy());
        assertTrue(factory.startCalls().isEmpty());
        assertEquals("UNSAFE", snapshot(scheduler, definition.key()).state());
    }

    @Test
    void restoreDisablesTasksWithoutAutoStart() {
        TaskDefinition definition = manualTask(
                "no-auto",
                TaskPriority.MEDIUM,
                TaskInterruption.none()
        );
        InMemoryTaskScheduleStore store = new InMemoryTaskScheduleStore();
        UUID oldRunId = UUID.randomUUID();
        store.save(new TaskScheduleRecord(
                definition.key(),
                true,
                "RUNNING",
                TaskPriority.MEDIUM,
                null,
                null,
                oldRunId,
                definition.key(),
                false,
                null,
                0L,
                false,
                "running before restart",
                false,
                null
        ));
        FakeRuntimeFactory factory = new FakeRuntimeFactory();
        TaskScheduler scheduler = scheduler(
                List.of(definition),
                factory,
                new FakeRegionItemProbe(),
                store,
                clock()
        );

        scheduler.restore();

        assertFalse(scheduler.isBusy());
        assertTrue(factory.startCalls().isEmpty());
        assertEquals("DISABLED", snapshot(scheduler, definition.key()).state());
        assertTrue(factory.finishedCalls().stream().anyMatch(call ->
                call.runId().equals(oldRunId) && "CANCELLED".equals(call.status())
        ));
    }

    private static TaskScheduler scheduler(
            List<TaskDefinition> definitions,
            FakeRuntimeFactory factory,
            RegionItemProbe probe,
            TaskScheduleStore store,
            MutableClock clock
    ) {
        return new TaskScheduler(
                () -> definitions,
                factory,
                probe,
                store,
                clock
        );
    }

    private static TaskScheduleInfo snapshot(TaskScheduler scheduler, TaskKey key) {
        return scheduler.snapshots().stream()
                .filter(info -> info.key().equals(key))
                .findFirst()
                .orElseThrow();
    }

    private static TaskDefinition manualTask(
            String id,
            TaskPriority priority,
            TaskInterruption interruption
    ) {
        return manualTask(id, priority, interruption, new TaskTrigger());
    }

    private static TaskDefinition manualTask(
            String id,
            TaskPriority priority,
            TaskInterruption interruption,
            TaskTrigger trigger
    ) {
        return TaskFixtures.task(id, priority, trigger, false, interruption);
    }

    private static TaskDefinition autoStartTask(String id) {
        return TaskFixtures.task(
                id,
                TaskPriority.HIGH,
                new TaskTrigger(),
                true,
                TaskInterruption.none()
        );
    }

    private static MutableClock clock() {
        return new MutableClock(START, ZoneId.of("UTC"));
    }
}
