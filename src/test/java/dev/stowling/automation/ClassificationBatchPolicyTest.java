package dev.stowling.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ClassificationBatchPolicyTest {
    @Test
    void mixedBatchPrioritizesKindsThatHaveNotBeenTakenYet() {
        ClassificationBatchPolicy policy = new ClassificationBatchPolicy(Set.of());
        policy.recordConfirmed("minecraft:stone");

        assertTrue(policy.accepts("minecraft:dirt"));
        assertTrue(policy.accepts("minecraft:stone"));
        assertEquals(0, policy.priority("minecraft:dirt"));
        assertEquals(1, policy.priority("minecraft:stone"));
        policy.recordConfirmed("minecraft:dirt");
        assertEquals(1, policy.priority("minecraft:dirt"));
    }

    @Test
    void fullBoxOnlyCategoryKeepsItsDedicatedBatch() {
        ClassificationBatchPolicy policy = new ClassificationBatchPolicy(Set.of("minecraft:stone"));
        assertTrue(policy.accepts("minecraft:stone"));
        policy.recordConfirmed("minecraft:stone");

        assertTrue(policy.accepts("minecraft:stone#variant"));
        assertFalse(policy.accepts("minecraft:dirt"));
    }

    @Test
    void mixedBatchLeavesFullBoxOnlyLooseStockForTheNextTrip() {
        ClassificationBatchPolicy policy = new ClassificationBatchPolicy(Set.of("minecraft:stone"));
        policy.recordConfirmed("minecraft:dirt");

        assertFalse(policy.accepts("minecraft:stone"));
        assertTrue(policy.accepts("minecraft:shulker_box#full@minecraft:stone"));
    }

    @Test
    void carriedBoxesDoNotForceADedicatedLooseStockBatch() {
        ClassificationBatchPolicy policy = new ClassificationBatchPolicy(Set.of("minecraft:stone"));
        policy.recordConfirmed("minecraft:shulker_box#full@minecraft:stone");

        assertTrue(policy.accepts("minecraft:dirt"));
        assertFalse(policy.accepts("minecraft:stone"));
    }

    @Test
    void capsTheBatchToLeaveRoomForPackingResources() {
        ClassificationBatchPolicy policy = new ClassificationBatchPolicy(Set.of());
        for (int index = 0; index < 27; index++) {
            assertTrue(policy.hasCapacity());
            policy.recordConfirmed("item-" + index);
        }
        assertFalse(policy.hasCapacity());
    }
}
