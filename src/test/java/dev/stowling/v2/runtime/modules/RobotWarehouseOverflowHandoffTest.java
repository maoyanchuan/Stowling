package dev.stowling.v2.runtime.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RobotWarehouseOverflowHandoffTest {
    @Test
    void confirmedFailureHandsLeftoversToTotalOverflowOnlyOnce() {
        var warehouse = new Warehouse();
        var handoff = new RobotWarehouseOverflowHandoff(warehouse, () -> true);
        var result = handoff.start();
        assertEquals(TaskModuleState.SUCCEEDED, result.state());
        assertTrue(result.message().contains("总溢出"));
        assertTrue(result.message().contains("仓库已满"));
        assertTrue(warehouse.cancelled);
        assertSame(result, handoff.tick());
        assertEquals(1, warehouse.calls);
    }

    @Test
    void unconfirmedTransfersAndUnrecoveredResourcesKeepTheFailure() {
        for (boolean safe : new boolean[] {false, true}) {
            for (boolean recovered : new boolean[] {false, true}) {
                if (safe && recovered) continue;
                var warehouse = new Warehouse();
                warehouse.safe = safe;
                var handoff = new RobotWarehouseOverflowHandoff(warehouse, () -> recovered);
                assertEquals(TaskModuleState.FAILED, handoff.start().state());
                assertFalse(warehouse.cancelled);
            }
        }
    }

    @Test
    void stoppingDoesNotStartAnotherStorageStage() {
        var warehouse = new Warehouse();
        var handoff = new RobotWarehouseOverflowHandoff(warehouse, () -> true);
        handoff.requestStop();
        assertTrue(warehouse.stopped);
        assertEquals(TaskModuleState.FAILED, handoff.tick().state());
        assertFalse(warehouse.cancelled);
    }

    @Test
    void runningAndSuccessfulWarehouseResultsKeepTheirMeaning() {
        var warehouse = new Warehouse();
        var handoff = new RobotWarehouseOverflowHandoff(warehouse, () -> true);
        warehouse.result = TaskModuleResult.waiting("等待服务器确认");
        assertSame(warehouse.result, handoff.start());
        warehouse.result = TaskModuleResult.succeeded("已入库");
        assertSame(warehouse.result, handoff.tick());
        assertFalse(warehouse.cancelled);
    }

    private static final class Warehouse implements TaskModuleExecutor {
        private TaskModuleResult result = TaskModuleResult.failed("仓库已满");
        private boolean safe = true;
        private boolean cancelled;
        private boolean stopped;
        private int calls;

        @Override
        public TaskModuleResult start() {
            return tick();
        }

        @Override
        public TaskModuleResult tick() {
            calls++;
            return result;
        }

        @Override
        public boolean safeToStop() {
            return safe;
        }

        @Override
        public void requestStop() {
            stopped = true;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }
}
