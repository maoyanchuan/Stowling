package dev.stowling.client.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MinecraftContainerOpenHandTest {
    @ParameterizedTest
    @ValueSource(ints = {1, 20})
    void continuousReadsSwitchAndSettleOnceThenRestoreBorrowedSlots(int safeSlot) {
        var inventory = new Inventory(safeSlot);
        var hand = new MinecraftContainerOpenHand(inventory, true);
        assertFalse(hand.prepare());
        for (int tick = 0; tick < 3; tick++) assertFalse(hand.prepare());
        assertTrue(hand.prepare());
        for (int chest = 0; chest < 36; chest++) assertTrue(hand.prepare());
        assertEquals(1, inventory.changes);
        hand.restore();
        assertEquals(0, inventory.selected);
        assertEquals(safeSlot < 9 ? 0 : 2, inventory.swaps);
        assertTrue(inventory.safe.get(safeSlot));
        assertFalse(inventory.safe.get(0));
        assertEquals(2, inventory.changes);
        hand.restore();
        assertEquals(2, inventory.changes);
    }

    @Test
    void alreadySafeSelectionNeverAddsAWaitBetweenReads() {
        var inventory = new Inventory(0);
        var hand = new MinecraftContainerOpenHand(inventory, true);
        for (int chest = 0; chest < 36; chest++) assertTrue(hand.prepare());
        hand.restore();
        assertEquals(0, inventory.changes);
    }

    @Test
    void changedHandIsCheckedAgainBeforeEveryRead() {
        var inventory = new Inventory(0);
        var hand = new MinecraftContainerOpenHand(inventory, true);
        assertTrue(hand.prepare());
        inventory.safe.set(0, false);
        assertThrows(IllegalStateException.class, hand::prepare);
    }

    @Test
    void fullUnsafeInventoryStillRefusesToOpen() {
        var inventory = new Inventory(-1);
        var hand = new MinecraftContainerOpenHand(inventory, true);
        assertThrows(IllegalStateException.class, hand::prepare);
        hand.restore();
        assertEquals(0, inventory.changes);
    }

    @Test
    void ordinaryVisitsStillRestoreAndWaitForEveryNewHand() {
        var inventory = new Inventory(1);
        for (int chest = 0; chest < 2; chest++) {
            var hand = new MinecraftContainerOpenHand(inventory, false);
            assertFalse(hand.prepare());
            for (int tick = 0; tick < 3; tick++) assertFalse(hand.prepare());
            assertTrue(hand.prepare());
            hand.restore();
            assertEquals(0, inventory.selected);
        }
        assertEquals(4, inventory.changes);
    }

    private static final class Inventory implements MinecraftContainerOpenHand.HandAccess {
        private final List<Boolean> safe = new ArrayList<>(Collections.nCopies(36, false));
        private int selected;
        private int changes;
        private int swaps;

        private Inventory(int safeSlot) {
            if (safeSlot >= 0) safe.set(safeSlot, true);
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public int selected() {
            return selected;
        }

        @Override
        public boolean selectedSafe() {
            return safe.get(selected);
        }

        @Override
        public boolean locked(int index) {
            return false;
        }

        @Override
        public List<Boolean> safeSlots() {
            return List.copyOf(safe);
        }

        @Override
        public void select(int index) {
            if (selected != index) changes++;
            selected = index;
        }

        @Override
        public void swap(int inventoryIndex) {
            Collections.swap(safe, selected, inventoryIndex);
            swaps++;
            changes++;
        }
    }
}
