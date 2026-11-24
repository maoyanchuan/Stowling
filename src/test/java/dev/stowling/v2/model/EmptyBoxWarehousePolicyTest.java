package dev.stowling.v2.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class EmptyBoxWarehousePolicyTest {
    @Test
    void regionsAndPointsCanSupplyBoxesAndDisabledSettingsRetainTheirDraft() {
        for (String type : List.of("region:", "coordinate:")) {
            String ref = type + UUID.randomUUID();
            var draft = new EmptyBoxWarehousePolicy(false, ref, true, null);
            assertEquals(ref, draft.warehouseRef());
            assertEquals(9, draft.retainedLimit());
            var policy = new TaskCleanupPolicy(null, TaskResumeMode.RESTART,
                    List.of(), null, draft);
            assertEquals(draft, policy.withDestination("region:" + UUID.randomUUID())
                    .emptyBoxWarehouse());
        }
    }

    @Test
    void disablingRetentionKeepsTheLimitWithoutReservingBoxes() {
        var policy = new EmptyBoxWarehousePolicy(true, "region:" + UUID.randomUUID(), false, 12);
        assertEquals(0, policy.retainedLimit());
        assertEquals(12, policy.cacheLimit());
    }

    @Test
    void enabledWarehouseRequiresAStorageLocationAndValidIntegerLimit() {
        assertThrows(IllegalArgumentException.class,
                () -> new EmptyBoxWarehousePolicy(true, null, true, 9));
        assertThrows(IllegalArgumentException.class,
                () -> new EmptyBoxWarehousePolicy(false, "task:" + UUID.randomUUID(), true, 9));
        assertThrows(IllegalArgumentException.class,
                () -> new EmptyBoxWarehousePolicy(false, null, true, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new EmptyBoxWarehousePolicy(false, null, true, 3457));
    }
}
