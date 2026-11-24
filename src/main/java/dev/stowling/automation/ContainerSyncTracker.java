package dev.stowling.automation;

import java.util.Map;
import java.util.WeakHashMap;

/** Server generations belong to the menu object, never a reusable window number. */
public final class ContainerSyncTracker {
    private final Map<Object, Long> snapshots = new WeakHashMap<>();

    public synchronized void initialized(Object menu) {
        snapshots.merge(menu, 1L, Long::sum);
    }

    public synchronized boolean ready(Object menu) {
        return snapshots.containsKey(menu);
    }

    public synchronized long generation(Object menu) {
        return snapshots.getOrDefault(menu, 0L);
    }

    public synchronized boolean confirmed(Object menu, Object batchMenu, long generation) {
        return menu == batchMenu && generation(menu) > generation;
    }
}
