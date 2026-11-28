package dev.stowling.client.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.domain.BlockPosition;
import java.util.List;
import org.junit.jupiter.api.Test;

class MinecraftStorageSessionTest {
    private static final ContainerTarget FIRST = target(0);
    private static final ContainerTarget SECOND = target(1);

    @Test
    void scanKeepsThePreparedHandAcrossBoxesAndRestoresItBeforeTransfers() {
        var access = new Access();
        var inventory = new SwitchingHand();
        var hand = new MinecraftContainerOpenHand(inventory, true);
        var session = new MinecraftStorageSession(access, hand);
        assertFalse(session.begin(FIRST, true));
        assertFalse(hand.prepare());
        for (int tick = 0; tick < 3; tick++) assertFalse(hand.prepare());
        assertTrue(hand.prepare());
        assertEquals(1, inventory.changes);
        for (int chest = 0; chest < 36; chest++) {
            var target = chest % 2 == 0 ? FIRST : SECOND;
            assertFalse(session.begin(target, true));
            assertTrue(session.hand().prepare());
            access.menu = new Object();
            assertTrue(session.retain(target));
        }
        assertEquals(1, inventory.changes);
        assertTrue(session.begin(SECOND, false));
        assertEquals(0, inventory.selected);
        assertEquals(2, inventory.changes);
        session.close();
        assertEquals(2, inventory.changes);
    }

    @Test
    void readThenTransferAndRepeatedTransfersReuseOnlyTheOwnedMenu() {
        var access = new Access();
        var session = session(access);
        assertFalse(session.begin(FIRST, true));
        access.menu = new Object();
        session.retain(FIRST);
        assertTrue(session.begin(FIRST, false));
        session.retain(FIRST);
        assertTrue(session.begin(FIRST, false));
        assertEquals(0, access.closed);
        session.retain(FIRST);
        session.close();
        session.close();
        assertEquals(1, access.closed);
        assertFalse(session.begin(FIRST, false));
    }

    @Test
    void changedTargetClosesMenuButAnExternalMenuIsNeverAdoptedOrClosed() {
        var access = new Access();
        var session = session(access);
        access.menu = new Object();
        session.retain(FIRST);
        assertFalse(session.begin(SECOND, true));
        assertEquals(1, access.closed);
        access.menu = new Object();
        session.retain(SECOND);
        access.menu = new Object();
        assertFalse(session.begin(SECOND, false));
        assertEquals(1, access.closed);
        assertFalse(session(access).begin(SECOND, false));
    }

    @Test
    void worldOrRangeChangeAndUnsettledCursorCannotReuseTheMenu() {
        var access = new Access();
        var session = session(access);
        access.menu = new Object();
        session.retain(FIRST);
        access.valid = false;
        assertFalse(session.begin(FIRST, false));
        session.close();
    }

    private static MinecraftStorageSession session(Access access) {
        return new MinecraftStorageSession(access,
                new MinecraftContainerOpenHand(new SafeHand(), true));
    }

    private static ContainerTarget target(int x) {
        return new ContainerTarget(new BlockPosition(x, 64, 0), "minecraft:chest");
    }

    private static final class Access implements MinecraftStorageSession.Access {
        private Object menu;
        private boolean valid = true;
        private int closed;

        @Override
        public Object menu() {
            return menu;
        }

        @Override
        public boolean valid(ContainerTarget target, Object candidate) {
            return valid && candidate != null && candidate == menu;
        }

        @Override
        public void close(Object candidate) {
            if (valid && candidate == menu) {
                closed++;
                menu = null;
            }
        }
    }

    private static class SafeHand implements MinecraftContainerOpenHand.HandAccess {
        @Override
        public boolean available() {
            return true;
        }

        @Override
        public int selected() {
            return 0;
        }

        @Override
        public boolean selectedSafe() {
            return true;
        }

        @Override
        public boolean locked(int index) {
            return false;
        }

        @Override
        public List<Boolean> safeSlots() {
            return List.of(true);
        }

        @Override
        public void select(int index) {
        }

        @Override
        public void swap(int index) {
            throw new AssertionError("Already safe hand must not swap");
        }
    }

    private static final class SwitchingHand extends SafeHand {
        private int selected;
        private int changes;

        @Override
        public int selected() {
            return selected;
        }

        @Override
        public boolean selectedSafe() {
            return selected == 1;
        }

        @Override
        public List<Boolean> safeSlots() {
            return List.of(false, true);
        }

        @Override
        public void select(int index) {
            if (selected != index) changes++;
            selected = index;
        }
    }
}
