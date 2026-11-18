package dev.stowling.v2.runtime.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.BlockPosition;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskProgressTest {
    @Test
    void confirmedDeliveryReducesTheRemainingRequestForOnlyThatDestinationAndItem() {
        var target = new BlockPosition(1, 64, 1);
        var other = new BlockPosition(2, 64, 1);
        var progress = new TaskProgress();
        progress.recordDelivery(target, Map.of("minecraft:stone", 400));
        progress.recordDelivery(other, Map.of("minecraft:stone", 200));
        assertEquals(Map.of("minecraft:stone", 600, "minecraft:dirt", 64),
                progress.remainingAt(target, Map.of("minecraft:stone", 1000, "minecraft:dirt", 64)));
        progress.recordDelivery(target, Map.of("minecraft:stone", 600));
        assertTrue(progress.remainingAt(target, Map.of("minecraft:stone", 1000)).isEmpty());
        assertTrue(new TaskProgress().deliveredTo(target).isEmpty());
    }
}
