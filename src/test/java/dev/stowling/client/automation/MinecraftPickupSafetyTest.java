package dev.stowling.client.automation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.BlockPosition;
import java.util.List;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

class MinecraftPickupSafetyTest {
    @Test
    void beingAbleToReachTheCacheDoesNotMakeTheIntakeStanceSafe() {
        AABB body = new AABB(5.2, -44, 34.2, 5.8, -42.2, 34.8);
        var drops = List.of(new AABB(5.3, -44, 34.3, 5.55, -43.75, 34.55));

        assertFalse(MinecraftPickupSafety.safe(body, drops));
        assertTrue(MinecraftPickupSafety.safe(body.move(0, 0, -3), drops));
    }

    @Test
    void nearbyDropsAreUnsafeEvenWithAFullInventoryThatCannotCurrentlyPickThemUp() {
        AABB body = new AABB(0.2, 64, 0.2, 0.8, 65.8, 0.8);
        var drops = List.of(new AABB(1.6, 64, 0.3, 1.85, 64.25, 0.55));

        assertFalse(MinecraftPickupSafety.safe(body, drops));
        assertTrue(MinecraftPickupSafety.safe(body, List.of()));
        assertTrue(MinecraftPickupSafety.safe(body, List.of(
                new AABB(0.3, 60, 0.3, 0.55, 60.25, 0.55))));
    }

    @Test
    void packingKeepsTheMiningAndPickupAreaAwayFromContinuousInput() {
        var drops = List.of(new AABB(5.3, -44, 34.3, 5.55, -43.75, 34.55));

        assertFalse(MinecraftPickupSafety.safePackingSite(new BlockPosition(6, -44, 34), drops));
        assertTrue(MinecraftPickupSafety.safePackingSite(new BlockPosition(6, -44, 30), drops));
    }
}
