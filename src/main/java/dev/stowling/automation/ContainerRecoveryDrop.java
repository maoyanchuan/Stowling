package dev.stowling.automation;

import dev.stowling.domain.BlockPosition;

/** Matches the registered box while it is being mined and picked up. */
public final class ContainerRecoveryDrop {
    private ContainerRecoveryDrop() {
    }

    public static boolean matches(DroppedItem item, BlockPosition site, String expectedKey) {
        return ItemIdentity.isBox(item.itemId()) && expectedKey.equals(item.itemId())
                && Math.abs((long) item.position().x() - site.x()) <= 2
                && Math.abs((long) item.position().y() - site.y()) <= 2
                && Math.abs((long) item.position().z() - site.z()) <= 2;
    }
}
