package dev.stowling.automation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.BlockPosition;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContainerRecoveryDropTest {
    private static final BlockPosition SITE = new BlockPosition(10, 64, 10);
    private static final String BOX = "minecraft:blue_shulker_box#remaining@minecraft:stone";

    @Test
    void recognizesTheExactBoxAtItsRecoverySiteIncludingSmallDropMotion() {
        assertTrue(ContainerRecoveryDrop.matches(drop(BOX, SITE), SITE, BOX));
        assertTrue(ContainerRecoveryDrop.matches(drop(BOX, SITE.offset(1, -1, 1)), SITE, BOX));
    }

    @Test
    void doesNotSuppressOtherItemsColorsOrContents() {
        assertFalse(ContainerRecoveryDrop.matches(drop("minecraft:stone", SITE), SITE, BOX));
        assertFalse(ContainerRecoveryDrop.matches(drop(
                "minecraft:red_shulker_box#remaining@minecraft:stone", SITE), SITE, BOX));
        assertFalse(ContainerRecoveryDrop.matches(drop(
                "minecraft:blue_shulker_box#different@minecraft:stone", SITE), SITE, BOX));
    }

    @Test
    void anIdenticalBoxDroppedElsewhereRemainsANormalPickup() {
        assertFalse(ContainerRecoveryDrop.matches(drop(BOX, SITE.offset(3, 0, 0)), SITE, BOX));
        assertFalse(ContainerRecoveryDrop.matches(drop(BOX, SITE.offset(0, 3, 0)), SITE, BOX));
        assertFalse(ContainerRecoveryDrop.matches(drop(BOX, SITE.offset(0, 0, 3)), SITE, BOX));
    }

    private static DroppedItem drop(String key, BlockPosition position) {
        return new DroppedItem(UUID.randomUUID(), key, 1, position);
    }
}
