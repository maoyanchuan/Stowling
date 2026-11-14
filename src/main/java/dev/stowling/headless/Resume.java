package dev.stowling.headless;

import java.util.concurrent.Callable;

/**
 * Compatibility adapter for the removed resume command; no longer registered or executable.
 */
@Deprecated
public final class Resume implements Callable<Integer> {
    public Resume() {
    }

    @Override
    public Integer call() {
        throw new UnsupportedOperationException("The resume command has been removed");
    }
}
