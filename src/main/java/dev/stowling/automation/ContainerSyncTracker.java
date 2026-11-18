package dev.stowling.automation;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/** Initial content belongs to the menu object, never to a reusable numeric window ID. */
public final class ContainerSyncTracker {
    private final Set<Object> initialized = Collections.newSetFromMap(new WeakHashMap<>());

    public synchronized void initialized(Object menu) {
        initialized.add(menu);
    }

    public synchronized boolean ready(Object menu) {
        return initialized.contains(menu);
    }
}
