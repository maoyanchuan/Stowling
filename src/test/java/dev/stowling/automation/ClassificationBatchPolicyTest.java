package dev.stowling.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClassificationBatchPolicyTest {
    @Test
    void layoutPriorityDoesNotChangeAfterTakingSeveralStacksOfTheFirstItem() {
        ClassificationBatchPolicy policy = new ClassificationBatchPolicy(Set.of(),
                List.of("minecraft:glass", "minecraft:dirt", "minecraft:stone"));
        for (int index = 0; index < 12; index++) {
            policy.recordConfirmed("minecraft:glass");
        }

        assertEquals(0, policy.priority("minecraft:glass"));
        assertEquals(0, policy.priority("minecraft:glass#variant"));
        assertEquals(0, policy.priority("minecraft:shulker_box#full@minecraft:glass"));
        assertEquals(1, policy.priority("minecraft:dirt"));
        assertEquals(2, policy.priority("minecraft:stone"));
        assertEquals(Integer.MAX_VALUE, policy.priority("minecraft:oak_log"));
    }

    @Test
    void nextBatchContinuesWithTheFirstRemainingLayoutItemAfterTheCapacityLimit() {
        List<String> layout = List.of("minecraft:glass", "minecraft:dirt", "minecraft:stone");
        ClassificationBatchPolicy first = new ClassificationBatchPolicy(Set.of(), layout);
        for (int index = 0; index < 27; index++) {
            first.recordConfirmed("minecraft:glass");
        }
        assertFalse(first.hasCapacity());

        ClassificationBatchPolicy next = new ClassificationBatchPolicy(Set.of(), layout);
        String selected = List.of("minecraft:stone", "minecraft:dirt").stream()
                .min(java.util.Comparator.comparingInt(next::priority)).orElseThrow();
        assertEquals("minecraft:dirt", selected);
        assertTrue(next.hasCapacity());
    }

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
