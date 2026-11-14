package dev.stowling.headless;

import java.util.concurrent.Callable;

/**
 * Compatibility adapter for the removed pause command; no longer registered or executable.
 */
@Deprecated
public final class Pause implements Callable<Integer> {
    public Pause() {
    }

    @Override
    public Integer call() {
        throw new UnsupportedOperationException("The pause command has been removed");
    }
}
